# seckill-lab 电商大促综合实验 — 设计文档

**日期：** 2026-10-10
**状态：** 已评审（四节设计均通过）
**目标读者：** 项目作者本人（学习 / 面试素材）
**关联文档：**

- `docs/superpowers/specs/2026-09-20-seckill-lab-evolution-design.md`（原秒杀演进设计，本文件在其基础上扩展并调整）
- 参考实现：`hm-dianping/`（黑马点评，自带 .git，已加入 `.gitignore`，仅作对照，不提交）

---

## 0. 一句话概述

把秒杀实验室包装成一个**真实电商系统**：**平时**后台订单/商品读多写少，**整点大促**秒杀写热点。
先走完一整段"读侧性能优化"（对标文章：N+1 → 连接池 → 缓存 → 异步），再进"写侧大促加固"（秒杀 V0~V5），
每一步都用 JMeter 拿前后对照数据，最终回答："每一步各贡献多少性能、代价是什么"。

---

## 1. 背景与目标

### 1.1 为什么做这个改造

原设计（2026-09-20）只覆盖秒杀写侧（超卖 / 重复下单 / 单行锁 / 削峰），
缺一条"普通 CRUD 系统从慢到快"的排查叙事（N+1、连接池、缓存、异步）。
本设计把两者合并到同一个业务系统、同一套数据上，形成"平时 vs 大促"的完整故事。

### 1.2 成功标准

1. Phase R 与 Phase S 各版本都有可复现的压测数据（QPS / avg / P95 / P99 / 错误率）。
2. 每一版都能用 SQL 对账给出明确结论：超卖？重复下单？库存守恒？
3. 故障注入（停 Redis / 停 Kafka）有明确降级行为。
4. 最终报告能回答："从 r0 到 v4，每一步贡献多少？代价是什么？"

### 1.3 非目标（明确不做）

- 不做前端页面，用 curl + JMeter 验证。
- 不做登录鉴权，`userId` 请求参数直传。
- 不做分布式部署，单机单实例。
- 不做真实支付/短信/邮件对接（状态机 + `Thread.sleep` 模拟）。
- 不追求生产级代码完备度，优先"能跑出对照数据"。

---

## 2. 总体结构

**两条线串联，按业务时间线：**

```
Phase R（大促前的后台，读侧）         Phase S（整点大促，写侧）
r0-baseline                        v0a-naive / v0b-atomic
  → r1-n1                          → v1-redis-lua
  → r2-pool                        → v2-kafka
  → r3-cache                       → v3-consistency
  → r4-async                       → v4-ratelimit
```

- 先做完整段读侧优化，再进写侧。一次只改一类变量，压测归因干净。
- 两个 Phase **共用一个应用、一套 modules、同一张 `orders` 表**，只是 tag 先后。
- 每个 tag 冻结，压测结果绑定 tag，代码不回改。

**部署形态：** 单机 Spring Boot（Java 17 / Boot 4），连本机 MySQL 3306 / Redis 6379 / Kafka 9092；
端口 8081。分层沿用现有 `Controller → Service → Mapper` + `Result` / `BusinessException` / `GlobalExceptionHandler`。

---

## 3. 数据模型

现有 6 张表 → 调整如下：

```
user              共享，10 万压测用户
goods             共享，商品（r3 起加缓存）
orders            【新/统一】订单主表
order_item        【新】订单明细（一单多商品）
order_logistics   【新】物流信息
seckill_activity  秒杀活动（total_stock 不可变，作对账基准）
seckill_stock     库存写热点，独立窄表
stock_log         库存流水，全程记录，供对账
```

### 3.1 `orders`（统一订单表）

```
id, order_no, user_id, order_type(NORMAL/SECKILL),
total_amount, status(PENDING/PAID/CLOSED),
create_time, pay_time
```

- 普通单与秒杀单**写同一张表**，`order_type` 区分。
- 大促后后台列表里两种单混出，放大 N+1 与缓存问题，两条线自然咬合。
- 原设计文档的 `seckill_order` 并入本表（业务代码尚未编写，改动代价极低）。

### 3.2 `order_item` / `order_logistics`（N+1 的必要条件）

```
order_item(id, order_id, goods_id, qty, price)
order_logistics(id, order_id, company, tracking_no, status)
```

订单列表要展示"用户名 / 商品名 / 物流状态"，因此查完 N 条订单后需逐条补查
`user`、`order_item`+`goods`、`order_logistics` → 3N 次查询 → **N+1**。
（20 条订单 = 1 次列表 + 60 次补查 = 61 次数据库调用。）

### 3.3 对账基准

- `seckill_activity.total_stock`（不可变）vs `seckill_stock.stock`（可变）。
- 任意时刻可算"应该卖了多少"，`stock_log` 全程记流水，覆盖所有版本。

---

## 4. Phase R — 读侧（大促前的后台）

### 4.1 接口

| 接口 | 用途 |
|---|---|
| `GET /api/order/list?userId=&pageNum=&pageSize=` | 订单列表，**N+1 主角** |
| `GET /api/order/{orderNo}` | 订单详情 |
| `POST /api/order/create` | 普通下单，**@Async 练习场** |
| `PUT /api/goods/{id}` | 商品更新，**缓存一致性练习场** |
| `GET /api/goods`、`GET /api/goods/{id}` | 商品查询，纳入缓存改造 |

- `userId` 直传，无鉴权。
- 短信 / 邮件 / 积分是假实现（`Thread.sleep`），不接真实服务。

### 4.2 版本（tag）

| tag | 改什么 | 预期现象 |
|---|---|---|
| `r0-baseline` | 四接口写成"新手版"：列表 for 循环补查（61 次查询）、无缓存、Hikari **10** 连接 / 超时 30s、下单同步 sleep | 慢、TP99 高、连接超时错误 |
| `r1-n1` | 批量 `IN` 查询，61 → 4 次 | 平均响应大幅下降 |
| `r2-pool` | 连接池 10→30、超时 30s→3s；附连接池梯度实验（10/20/30） | 错误率归零、TP99 下降 |
| `r3-cache` | Redis 缓存商品详情；更新时删缓存；复现"删缓存 / 回填旧值"竞态 | 商品读变快，暴露一致性问题 |
| `r4-async` | 非核心逻辑 `@Async` 线程池拆出；附拒绝策略实验 | 下单接口 200ms 内返回 |

> `r0` 连接池故意设 10（对齐文章基线），否则复现不出 "Connection is not available"。

---

## 5. Phase S — 写侧（整点大促）

### 5.1 数据（新增）

```
seckill_activity(id, goods_id, seckill_price, total_stock, start_time, end_time)
seckill_stock(id, activity_id, stock)
stock_log(id, activity_id, user_id, order_no, delta, stock_before, stock_after, type, create_time)
orders(... order_type=SECKILL ...)   -- 复用 Phase R 的统一订单表
```

### 5.2 接口

| 接口 | 用途 |
|---|---|
| `POST /api/seckill/{activityId}?userId=` | 秒杀下单（主链路） |
| `GET /api/order/{orderNo}` | 查秒杀结果（复用订单详情） |
| `POST /api/pay?orderNo=` | 支付（v3 乐观锁） |
| `GET /api/seckill/{activityId}/warmup` | 库存预热（v1 起，压测前调用） |

### 5.3 版本（tag）

| tag | 改什么 | 预期现象 |
|---|---|---|
| `v0a-naive` | `UPDATE stock=stock-1`，无 `WHERE stock>=1`，无幂等 | **超卖** + 重复下单 |
| `v0b-atomic` | 加 `WHERE stock>=1` | 不超卖，仍重复下单 |
| `v1-redis-lua` | Redis 预热 + Lua 原子预扣 + 一人一单 Set + **双兜底（见 5.4）** | 不超卖、不重复，QPS 提升 |
| `v2-kafka` | Lua 通过 → 发 Kafka → 消费者异步落库 | 接口 QPS 全版本峰值，削峰 |
| `v3-consistency` | 支付乐观锁(@Version) + 超时关单回补 + 消费幂等 + 手动 ack | 并发支付仅 1 次成功，库存守恒 |
| `v4-ratelimit` | Redis ZSet 滑动窗口 + `@RateLimit` AOP | 超限请求被挡（429） |

> 原设计的 `v4-cache` 已删除——缓存已在 `r3` 完成。
> `v0a` / `v0b` 并列：先做 v0a，从中分出 v0b，再回主线做 v1。

### 5.4 v1 兜底方案：双方案对照（决策）

v1 同时实现两套兜底，通过配置开关切换，各跑一组压测对比：

- **A. 数据库唯一索引**：`uk_activity_user(activity_id, user_id)`，更轻、少一个依赖。
- **B. Redisson 分布式锁**：`lock:order:{userId}`，与参考项目 hm-dianping 一致，更灵活。

对照结论用于回答面试题"为什么不用分布式锁 / 什么时候用"。

---

## 6. 参考项目 hm-dianping 的映射

参考实现位于 `hm-dianping/`（已 gitignore），其方案与本设计的对应关系：

| 本设计版本 | hm-dianping 现状 |
|---|---|
| v1 Redis+Lua | ✅ `resources/seckill.lua`（库存 + Set 幂等），兜底用 **Redisson 锁**而非唯一索引 |
| v1 兜底 | ✅ `RedissonConfig` + `RLock` + `UPDATE ... WHERE stock>0` |
| v2 Kafka | ⚠️ 生产端 active，消费端 `@KafkaListener` / `KafkaConfig` 被注释，**半成品**，只能借鉴意图 |
| v3 支付/关单/幂等消费 | ❌ 没有（无支付状态机） |
| v4 多级缓存（并入 r3） | ✅ `VoucherListCacheService`：Caffeine + Redis + DB，三模式可切 |
| v5 限流 | ✅ `@RateLimiter` + `RateLimiterAspect` + `limiter.lua`（ZSet 滑动窗口，IP/USER/METHOD 三维度） |
| 分布式锁 | ✅ Redisson + `unlock.lua`（Lua 校验线程标识） |
| 订单号 | ✅ `RedisIdWorker`（时间戳 << 32 \| 当天自增） |

**移植警告：** hm-dianping 为 Boot 2.3 / Java 8 / `javax.*` / Jackson 2；
本项目为 Boot 4 / Java 17 / `jakarta.*` / Jackson 3。算法可直接借鉴，Java 代码必须改写。

---

## 7. 压测、对账与实验记录

### 7.1 工具与环境

- JMeter 5.6.3（`D:\develop\apache-jmeter-5.6.3`），CLI 非 GUI。
- 先建 `bin/setenv.bat`：`set HEAP=-Xms2g -Xmx2g`（否则 JMeter 先 OOM）。

### 7.2 压测场景与梯度

| 场景 | 接口 | 梯度 |
|---|---|---|
| 读侧 | `GET /api/order/list` | 100 / 200 / 500 线程 |
| 写侧 | `POST /api/seckill/{id}` | 100 / 200 / 500 / 1000 线程 |

### 7.3 每版固定流程

```
重置数据 → 预热(必要时) → 压测 → 采指标 → 跑对账 → 写实验日志 → 打 tag
```

### 7.4 采集指标

- **性能**：QPS、平均、P95 / P99、错误率。
- **业务正确性（最重要）**：超卖数（`stock<0`）、重复下单数、库存守恒
  （`total_stock = 售出 + 剩余`）、Redis 与 DB 库存差值。
- **连接池证据**：Hikari `Connection is not available` 日志条数。

### 7.5 对账 SQL

沿用 `db/reconcile.sql`，补充统一 `orders` 表后的口径（普通单 + 秒杀单）。

### 7.6 故障注入（阶段 9）

`v2` 之后各做一次：停 Redis、停 Kafka，确认**明确降级**而非静默挂掉。

### 7.7 实验日志规范

每 tag 一页 `docs/lab/<tag>.md`：

```
## 改了什么
## 压测结果（表格：场景 / 梯度 / QPS / avg / P99 / 错误率）
## 结论
## 遗留问题 / 下一步
```

### 7.8 最终报告

`docs/lab/REPORT.md`，回答："从 r0 到 v4，每一步各贡献多少 QPS、代价是什么"。
附总览表（版本 × 关键指标）+ 业务正确性矩阵。

---

## 8. 交付物清单

```
代码：单模块，分阶段 tag（r0~r4 + v0a/v0b + v1~v4）
文档：本设计 spec + docs/lab/*.md 实验日志 + REPORT.md
数据：JMeter .jtl 结果 + 对账 SQL 输出
```

---

## 9. 已知风险

| # | 风险 | 应对 |
|---|---|---|
| R1 | 10 万用户造数触发 `cte_max_recursion_depth` | 脚本显式 `SET SESSION cte_max_recursion_depth = 100001` |
| R2 | V0 重复订单导致 V1 加唯一索引失败 | 迁移脚本先清理重复单再 `ALTER` |
| R3 | v2 异步导致对账假阳性 | 对账前等 Kafka LAG 归零 |
| R4 | JMeter 自身成为瓶颈 | 堆调 2G，监控其 CPU |
| R5 | Windows 动态端口耗尽 | JMeter 开 Keep-Alive 复用连接 |
| R6 | Redis 预热遗漏导致压测全失败 | 压测流程固定加预热调用 |
| R7 | Kafka topic 跨轮次累积影响 LAG | 每轮换 group-id 或重建 topic |
| R8 | 中间件未启动 | 每次开工按 README 依次启动 |
| R9 | 应用与 JMeter 抢同机资源 | 报告标注"单机压测"，只做相对对比 |
| R10 | v0a 的 `stock_before/after` 互相矛盾 | 属预期症状，报告中作为超卖证据 |

---

## 10. 验收标准

- [ ] 表结构齐备，10 万用户造数完成。
- [ ] `r0` 压测：N+1 现象明显，存在连接超时错误。
- [ ] `r1` 后：查询数从 61 → 4，平均响应大幅下降。
- [ ] `r2` 后：错误率归零，TP99 下降。
- [ ] `r3` 后：商品读变快，并能复现缓存一致性竞态。
- [ ] `r4` 后：下单接口 200ms 内返回。
- [ ] v0a 后：库存为负、存在重复下单。
- [ ] v0b 后：库存恰为 0、仍有重复下单。
- [ ] v1 后：不超卖、不重复；唯一索引 vs Redisson 锁两组数据。
- [ ] v2 后：QPS 达峰值，LAG 归零后对账 PASS。
- [ ] v3 后：并发放大仅 1 次支付成功，超时单关闭且库存回补正确。
- [ ] v4 后：超限请求被限流。
- [ ] 停 Redis / 停 Kafka 有明确降级行为。
- [ ] `REPORT.md` 能回答"每一步贡献与代价"。
