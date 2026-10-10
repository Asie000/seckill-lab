# v2-kafka —— Kafka 异步落库（削峰）

**tag**：`v2-kafka`
**日期**：2026-10-10
**主题**：Lua 预扣通过后只发 Kafka 消息，消费者异步落库，请求线程立即返回。
**标准口径**：日志关闭。

---

## 1. 代码变更

| 文件 | 作用 |
|---|---|
| `seckill/dto/SeckillMessage` | 消息体：`activityId / userId / orderNo` |
| `config/KafkaTopicConfig` | 声明 Topic `seckill-order`（1 分区 1 副本） |
| `seckill/consumer/SeckillOrderConsumer` | `@KafkaListener`，收到消息调 `handleSeckillMessage` 落库 |
| `SeckillServiceImpl` | 新增 `seckill.kafka.enabled`（默认 true）；通过 Lua 后发 Kafka 并立即返回 |

关键分支：

```java
// Lua 通过后
String orderNo = "S" + UUID...;
if (kafkaEnabled) {
    kafkaTemplate.send("seckill-order", orderNo, new SeckillMessage(activityId, userId, orderNo));
    return orderNo;               // ★ 请求线程立即返回，不等落库
}
return persist(activityId, userId, snap, orderNo);   // v1 同步落库
```

消费者侧 `handleSeckillMessage` 是 `@Transactional`：DB 扣减 + 写单，失败抛异常让事务回滚，
由消费者捕获记录日志（v2 尚未做重试/幂等/死信，留给 v3）。

---

## 2. 功能验证

```
发一次秒杀 -> 立即返回订单号
3 秒后查 DB -> 订单数=1，库存=99     ★ 消费者异步落库成功
```

---

## 3. 压测数据

### 3.1 库存 100（拒绝为主，和 v1 一样）

| 并发 | 吞吐 | 平均 | P95 | P99 |
|---:|---:|---:|---:|---:|
| 100 | 3972.7/s | 21.0ms | 29 | 34 |
| 200 | 3517.5/s | 47.6ms | 67 | 74 |
| 500 | 4069.3/s | 103.1ms | 130 | 141 |
| 1000 | 3873.1/s | 217.0ms | 268 | 283 |

> 库存只有 100，绝大多数请求在 Lua 阶段被拒，异步与否看不出差别。

### 3.2 ★ 异步 vs 同步 对照（库存放大到 5000，200 并发，15s）

让"成功路径"占比上升，才能看出 Kafka 异步的价值：

| 模式 | 吞吐 | 平均 | P95 | P99 |
|---|---:|---:|---:|---:|
| 同步落库（kafka off） | 360.9/s | 377.6ms | 609 | 675 |
| **异步 Kafka（kafka on）** | **2768.0/s** | **48.7ms** | 87 | 104 |

- **吞吐 ~7.7 倍**，**平均延迟 ~7.8 倍**。
- 原因：同步模式下请求线程要等 3 张表写完 + 事务提交；异步模式下请求线程只做
  Redis 预扣 + 发一条 Kafka，落库交给消费者。

> 注：异步模式的高吞吐也部分来自"拒绝路径"（Redis 内完成，不碰 DB）。
> 生产实际还会关心：**消费者能否跟上**（Kafka LAG）——这正是需要监控的指标。

---

## 4. 业务正确性

- 库存 100 的标准轮：**剩余库存 0 / 订单 100 / 重复 0**，对账全 PASS。
- 库存 5000 的同步轮：**订单 3321 / 用户 3321 / 重复 0**（其余请求是同一用户重试被拒）。
- 消费者落库后数据与 v1 一致，证明异步没有破坏正确性。

---

## 5. 结论

1. **Kafka 异步把 DB 写从请求链路里彻底摘出去**，成功路径吞吐/延迟大幅改善（~7.7x）。
2. 拒绝路径在 v1 已不碰 DB，所以低库存场景 v2 看不出差别——**要看成功路径**。
3. v2 的可靠性还是"裸的"：发送失败、消费失败、重复消费都没处理。
   → `v3-consistency` 补：支付乐观锁、超时关单回补、幂等消费、手动 ack。
4. 对齐参考项目 hm-dianping：它的生产端是活的、消费端被注释，我们这里把消费端补齐了。

## 6. 遗留问题 / 下一步

- `v3-consistency`：消费幂等（防重复落库）、发送/消费可靠性、超时关单。
- Kafka LAG 监控与对账前置条件（本实验靠 `sleep 3s` 等消费者落库，v3 会规范化）。
