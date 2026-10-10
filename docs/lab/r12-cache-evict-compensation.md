# r12-cache-evict-compensation —— 删缓存失败 → Kafka 补偿重试

**tag**：`r12-cache-evict-compensation`
**日期**：2026-10-10
**主题**：补齐「黑马点评」文档**数据一致性**职责点的完整版——
「更新数据库后删缓存，**若删除失败则发 Kafka 消息补偿重试**，TTL 兜底保证最终一致」。

---

## 1. 背景

r3 做的是「更新 DB → 删缓存」+ 延迟双删。但**如果删缓存这一步失败**（Redis 抖动/网络），
缓存就会一直留着旧值。文档的方案：**删失败 → 发消息到 Kafka → 消费者重试删除**，
配合缓存 **TTL 兜底**，保证最终一致。

## 2. 实现

| 组件 | 作用 |
|---|---|
| `CacheEvictSupport` | 删缓存；**失败则发 Kafka** `cache-evict`（key） |
| `CacheEvictConsumer` | 消费 `cache-evict`，重试删除；失败不 ack → Kafka 重试 →（耗尽）死信 `seckill-order-dlq`（r9 全局错误处理器） |
| `GoodsServiceImpl.update` | 改为调用 `goodsCacheService.evictWithCompensation(id)`（原为直接 `evict`） |

- topic：`cache-evict`（1 分区）。
- 开关：`seckill.cache.evict.simulate-fail`（模拟删除失败）、`seckill.cache.evict.consumer-sleep-ms`（放大补偿窗口）。
- 接口：`POST /api/goods/cache/evict/simulate-fail?on=`、`GET /api/goods/{id}/cached`（读缓存值）。

## 3. 验证（模拟删除失败）

前置：缓存 `cache:goods:1` 已预热为 `888.88`；开启 `simulate-fail=true`。

| 步骤 | DB(MySQL 实测) | 缓存值 |
|---|---:|---:|
| 更新前 | 888.88 | 888.88 |
| `PUT /api/goods/1` price=777.77（删缓存**失败**，发 Kafka） | **777.77** | 仍 **888.88**（脏） |
| 补偿消费者延迟 2s 后删除缓存 | 777.77 | 缓存被删 → 下次读回源 |
| 5s 后读 `/cached` | 777.77 | **777.77**（一致） |

> 结论：删除失败时缓存**短暂脏读**（888.88 vs DB 777.77），
> 补偿消费者把缓存删掉后，**最终一致**。若补偿也一直失败，还有 TTL 兜底 + 死信告警。

## 4. 与 r3「延迟双删」的关系（两种一致性方案）

| 方案 | 思路 | 适用 |
|---|---|---|
| 延迟双删（r3） | 更新后删一次，延迟再删一次，覆盖并发回填的旧值 | 解决**并发读写竞态**导致的脏缓存 |
| **MQ 补偿（r12）** | 删失败就发消息重试，直到删成功（TTL 兜底） | 解决**删除动作本身失败**（Redis 抖动） |

两者互补：r3 防竞态，r12 防删除失败。

## 5. 面试点

- **为什么先更新 DB 后删缓存**：先删缓存会出现「删了→读回源旧值→写回」的不一致窗口；先更 DB 后删缓存，脏窗口更小。
- **删除失败怎么办**：发 MQ 补偿重试；重试有限次 → 死信 + 告警；TTL 兜底。
- **为什么不更新缓存而是删除**：删除更简单、避免并发写覆盖；下次读回源重建（Cache-Aside）。
- 与 r9 死信队列、r3 双删串起来，就是完整的一致性方案。

## 6. 复现

```powershell
# 启动（补偿消费者延迟 2s 便于观察）
java -jar target\seckill-lab-0.0.1-SNAPSHOT.jar --seckill.cache.evict.consumer-sleep-ms=2000 ...
# 开模拟失败
curl -X POST "http://127.0.0.1:8081/api/goods/cache/evict/simulate-fail?on=true"
# 更新后观察 /api/goods/1/cached 与 MySQL goods.price
```

## 7. 遗留

- `simulate-fail` 是**模拟**失败，未真正停 Redis 验证。
- 重试次数达到上限后的**告警**未实现（仅进死信队列）。
- 补偿消费者日志因 stdout 重定向缓冲未落盘，验证以「缓存值行为」为准。
