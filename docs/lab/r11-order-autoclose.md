# r11-order-autoclose —— 未支付订单到期自动关闭（Spring Task 定时任务）

**tag**：`r11-order-autoclose`
**日期**：2026-10-10
**主题**：对应「黑马点评」文档的简历职责点**「未支付订单到期自动关闭」**——方案一：Spring Task 每分钟扫表。

---

## 1. 背景与 3 种方案（文档原话）

用户下单后超时未支付，需关闭订单并**释放库存**。本质是「检测订单支付状态」，3 种方案：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **① Spring Task 扫表**（文档选它） | 定时 `select ... where status=未支付 and order_time < now-15min`，逐单关 | 实现简单 | 数据量大时扫表有压力（需联合索引） |
| ② MQ 延迟消息 | 下单时发延迟消息，到点消费关单 | 解耦、不用扫表 | 大量无效调度（多数订单已支付）；消息可能丢/重 |
| ③ Redis 过期监听 | 每单设 TTL key，监听 `__keyevent@0__:expired` | Redis 高性能 | Redis 过期**不保证准时**，通知可能延迟数分钟 |

> 文档选 **①**（单体、订单量不大），并补充两个优化：
> - **被动关单**：用户查订单列表时顺手关闭已超时单；
> - **兜底任务**：即使丢消息，也定期全量扫一次。

---

## 2. 实现

- `OrderAutoCloseJob`：`@Scheduled(fixedDelayString="${seckill.order.autoclose.interval-ms:60000}")`
  - **Redis 锁** `lock:order:autoclose`（SETNX+TTL）→ 多实例下同一时刻只有 1 个实例在扫
    （关闭本身的正确性由条件更新兜底，锁只是省得重复扫）。
- `SeckillService.closeTimeoutOrdersAll(timeoutSeconds, batchSize)`：
  - 扫 `status=0 且 create_time <= now-timeout`，`LIMIT batchSize`；
  - 逐单 `markClosed(id)`（`UPDATE ... WHERE id=? AND status=0`）成功才**回补库存**
    （`restoreStock` + Redis `INCR` + 写 `stock_log`）。

依赖索引：`orders(idx_status_create(status, create_time))`（schema 已建）。

---

## 3. 实测

### 3.1 扫表查询的执行计划（关键：有无索引）

```sql
SELECT * FROM orders WHERE status=0 AND create_time <= NOW() - INTERVAL 900 SECOND LIMIT 200;
```

| | type | key | rows |
|---|---|---|---|
| 正常 | **range** | `idx_status_create` | ~5918 |
| `IGNORE INDEX(idx_status_create)` | **ALL** | NULL | ~11837 |

> 走联合索引 = 只扫「未支付且超时」的那段；没索引 = **全表扫**。订单千万级时差别巨大。

### 3.2 关单执行（orders 共 12005 行，其中 10005 待支付）

配置：`interval-ms=5000, timeout-seconds=1, batch-size=5000`

| 扫描 | 关闭单数 | 耗时 | 每单耗时 |
|---|---:|---:|---:|
| 第 1 次 | 5000 | **27467ms** | 5.49ms |
| 第 2 次 | 5000 | **26181ms** | 5.24ms |
| 第 3 次 | 5 | 39ms | 7.8ms |
| **合计** | **10005** | — | — |

- `status 0→3`：10005 笔全部关闭；`stock_log` 增加 **10004**（= 秒杀单数，普通单不回补）。
- **一致性**：关完后 `redisStock == dbStock == 105503`（`consistent=true`）。

### 3.3 ★ 性能发现：逐单循环太慢

每单 4 次远程往返（`markClosed` UPDATE + `restoreStock` UPDATE + Redis `INCR` + `stock_log` INSERT），
5000 单 ≈ **2 万条语句串行** → 27s，**约 185 单/秒**。

**优化方向**（可继续做）：
- `UPDATE orders SET status=3 WHERE id IN (...) AND status=0` 批量关闭；
- `stock_log` 用 `INSERT ... VALUES (...),(...)` 批量插入；
- Redis 用 pipeline / 直接按活动聚合一次 `INCRBY N`（同一活动只调一次）。

---

## 4. 面试点

- **不会重复关闭/重复回补**：`UPDATE ... WHERE status='未支付'` 条件更新，同一单只有一次成功。
- **关单成功但回补库存失败**：引入 MQ 异步回补 + 重试 + 告警（r9 已有死信队列基础）。
- **既要实时又不要扫表压力**：被动关单（查列表时顺手关）+ 兜底定时任务。
- **定时任务多实例**：Redis 锁避免重复扫。
- **索引**：`(status, create_time)` 联合索引是这类「状态+时间范围」扫描的标配。

---

## 5. 复现

```powershell
# 快速演示：间隔5s、超时1s、批量5000
scripts\app.ps1 -Action restart -NoSqlLog -AutoCloseIntervalMs 5000 -AutoCloseTimeoutSec 1 -AutoCloseBatch 5000
# 看日志
Get-Content target\app.log | Select-String "定时关单"
```

## 6. 遗留

- 被动关单（订单列表查询时关闭超时单）未实现。
- 逐单循环的**批量优化**未做（见 3.3）。
- 关单↔回补库存的原子性：目前先关单再回补，回补失败无重试。
