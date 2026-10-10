# r8-redis-idworker —— 分布式 ID（Redis 自增）

**tag**：`r8-redis-idworker`
**日期**：2026-10-10
**主题**：给订单号换一个**全局唯一、趋势递增**的分布式 ID 生成器，替代 UUID。

---

## 1. 为什么要分布式 ID

订单号 / 流水号需要**全局唯一**。常见方案对比：

| 方案 | 唯一 | 趋势递增 | 长度 | 问题 |
|---|---|---|---|---|
| 数据库自增 | ✅ | ✅ | 8B | 单点、分布式下要分段/号段 |
| UUID | ✅ | ❌ | 36 字符 | 无序 → MySQL 随机插入、页分裂；占空间 |
| 雪花算法 Snowflake | ✅ | ✅ | 8B | 要分配 workerId、依赖时钟 |
| **Redis 自增（本实验）** | ✅ | ✅ | 8B | 依赖 Redis |

本项目原来用 `"N" + UUID`，改成本方案。

---

## 2. 实现：`RedisIdWorker`

```java
id = (当前秒 - 起始秒) << 32  |  Redis 当天自增序列
      └── 高 32 位：秒级时间戳     └── 低 32 位：当天该业务序号
```

- 起始秒 = `2022-01-01 00:00:00 UTC`。
- 每天一个 key `icr:{业务}:{yyyy:MM:dd}`，避免计数器无限增长。
- 同秒靠 Redis `INCR` 保证不重，跨秒靠时间戳递增。
- 一次 Redis 往返即出一个 ID。

接口：`GET /api/id/next?prefix=order`；订单号已改为 `redisIdWorker.nextOrderNo("order","N")`（实测返回 `N647095316302004225`）。

---

## 3. 测试

### 3.1 并发唯一性自检（200 线程 × 500 = 10 万个 ID）

`POST /api/id/selftest?threads=200&perThread=500&strategy=redis|uuid`

| 策略 | 总数 | 唯一个数 | **重复** | 耗时 | 速率 | 可排序 |
|---|---:|---:|---:|---:|---:|---|
| **redis** | 100000 | 100000 | **0** | 1539ms | **64977/s** | ✅ 趋势递增 |
| uuid | 100000 | 100000 | 0 | 209ms | **478468/s** | ❌ |

- 两者都不重复，正确性 OK。
- **告警式的结论：UUID 反而快 7.4 倍**（本地生成，无网络）。
  **RedisIdWorker 的价值不在快，而在"趋势递增 + 定长 BIGINT 友好"**，详见第 4 节。
- 样例 id 递增（…785, 786, 787, 788, 791——中间 789/790 被其他线程取走）。

### 3.2 HTTP 层吞吐（200 并发，30s）

`GET /api/id/next`：**29574/s**，平均 5ms，P99 约 30ms 内（Max 384ms）。

---

## 4. 为什么选它（面试点）

- **趋势递增**：高 32 位是时间 → 新订单 id 总是更大。作为 MySQL 主键时**顺序插入**，
  避免 UUID 的随机写导致的**页分裂、插入变慢**；分页/排序也天然有序。
- **定长 8 字节**：UUID 是 36 字符，索引更占空间（InnoDB 二级索引都要存主键）。
- **可反解**：`id >> 32` 即秒级时间，能看出生成时间。
- **代价**：依赖 Redis（要保证 Redis 高可用）；单 Redis 约 6.5 万 ID/s（本机），
  远超业务需要；要更高可号段模式/多实例。
- UUID 更快但无序——**"快"不是主键选择的唯一标准，聚簇索引友好度更重要**。

---

## 5. 复现

```powershell
# 并发自检
curl -X POST "http://127.0.0.1:8081/api/id/selftest?threads=200&perThread=500"
curl -X POST "http://127.0.0.1:8081/api/id/selftest?threads=200&perThread=500&strategy=uuid"
# HTTP 压测
scripts\bench.ps1 -Jmx bench\id.jmx -Threads 200 -Duration 30 -Tag r8-id-http
```

## 6. 遗留

- 未做号段模式 / 多 Redis 实例的 ID 分配。
- 未与雪花算法做实测对比。
