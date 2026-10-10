# 故障注入（阶段 9）

**日期**：2026-10-10
**目标**：验证中间件故障时，系统表现出**明确的降级行为**，而不是无声挂掉。

---

## 1. 停 Redis

操作：`Taskkill /IM redis-server.exe /F`，然后发一次秒杀。

结果：

```
seckill resp(HTTP 200): {"code":500,"message":"系统繁忙，请稍后再试","data":null,"success":false}
```

- 秒杀直接失败（`code=500`）。
- 原因：请求路径强依赖 Redis——限流切面执行 `limiter.lua`、秒杀执行 `seckill.lua`，
  Redis 不可用会立刻抛连接异常，被全局异常处理转成 500。
- **评估**：明确失败，符合预期。可改进点：返回 HTTP 503 更贴合语义。

## 2. 停 Kafka（运行期）

### 2.1 修复前：静默丢单（严重）

操作：应用已启动、Kafka 正常运行；预热后杀掉 Kafka，再发秒杀。

```
seckill resp: {"code":200,"message":"操作成功","data":"S929...","success":true}
5 秒后 DB 订单数 = 0
```

- 接口**返回成功并给了订单号**，但订单**永远不落库**（消息发不出去，消费者也没消息）。
- 用户以为抢到了，实际没有 —— 这是最危险的**静默失败**。
- 根因：`kafkaTemplate.send(...)` 是异步的，代码发送后立即返回，没有处理发送失败。

### 2.2 修复

1. **发送失败回调**：`send(...).whenComplete(...)`，失败时回滚 Redis（库存 +1、移出已下单集合）并打 ERROR；
2. **同步失败兜底**：`send` 同步抛异常时也要回滚 + 抛业务异常；
3. **缩短失败时间**：生产者 `max.block.ms=3000`、`delivery.timeout.ms=5000`，
   否则 Kafka 不可用时发送会阻塞很久。

### 2.3 修复后

```
redis stock before: 100
try seckill (kafka down):
resp: {"code":500,"message":"秒杀下单失败，请稍后重试","data":null,"success":false}
redis stock after (应回 100): 100
mysql orders: 0
```

- **明确返回失败**（`code=500`）；
- **Redis 库存回滚到 100**（没有泄漏）；
- DB 无脏订单。
- **评估**：从"静默丢单"变成"明确失败 + 状态一致"。

## 3. 附带发现：Kafka 不可用会阻塞应用启动

当 Kafka 未启动时启动应用，应用会卡在 `KafkaAdmin` 创建 Topic（`seckill-order`）上反复重连，
端口 8081 迟迟不监听；等 Kafka 恢复后应用才完成启动。

- 原因：`KafkaTopicConfig` 声明的 `NewTopic` 由 `KafkaAdmin` 在启动时创建。
- 影响：**Kafka 故障会放大为"应用无法启动"**。
- 改进方向：`spring.kafka.admin.fail-fast=false` + 异步建 Topic，或让消费端容忍 Topic 暂不存在。

---

## 4. 小结

| 故障 | 修复前 | 修复后 |
|---|---|---|
| 停 Redis | 明确失败（500） | 明确失败（500） |
| 停 Kafka（运行期） | ❌ 静默成功 + 丢单 | ✅ 明确失败（500）+ Redis 回滚 |
| 停 Kafka（启动时） | 应用启动被阻塞 | （待改进） |

**结论**：加了生产端失败处理之后，主要故障都能"明确失败且状态一致"；
唯一剩下的隐患是启动期对 Kafka 的强依赖。

## 5. 遗留

- 生产端更彻底的方案是**本地消息表 / 事务消息（Outbox）**，保证"业务成功则消息必达"。
- 消费者死信队列（重试耗尽后的兜底）未做。
- 应用启动对 Kafka 的强依赖待解耦。

---

## 6. ⚠️ 更正（2026-10-10，做 r9 时发现）

**上文"停 Kafka（运行期）→ 明确失败 500"的结论存疑。**

当时为了"Kafka 挂了尽快失败"，只加了 `delivery.timeout.ms: 5000`，**没加 `request.timeout.ms`**。
而 Kafka 要求 `delivery.timeout.ms >= request.timeout.ms + linger.ms`，默认 `request.timeout.ms=30000`，
不满足 → **producer 构造直接失败**（`Failed to construct kafka producer`）。

后果：**无论 Kafka 是否可用，秒杀都返回 500**。所以那次观察到的 500，很可能是这个**配置 bug**，
而非真正的"Kafka 故障被正确检测"。已补 `request.timeout.ms: 3000` 修复，结论待重验。

（另外 r9 还实测到：消费者遇毒消息会无限重试卡死分区，已用 `DefaultErrorHandler` + 死信队列 `seckill-order-dlq` 修复。）
