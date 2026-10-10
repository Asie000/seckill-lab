# v3-consistency —— 幂等消费 + 手动 ack + 支付/关单状态机

**tag**：`v3-consistency`
**日期**：2026-10-10
**主题**：把 v2 的"裸异步"补齐可靠性，并加上支付与超时关单。
**标准口径**：日志关闭。

---

## 1. 代码变更

### 1.1 幂等消费 + 手动 ack（`SeckillOrderConsumer`）

```java
@KafkaListener(topics = "seckill-order", groupId = "...")
public void onMessage(SeckillMessage msg, Acknowledgment ack) {
    Boolean first = redis.setIfAbsent("seckill:consumed:" + msg.getOrderNo(), "1", 2h);
    if (!first) { ack.acknowledge(); return; }        // 重复消息：跳过
    try {
        seckillService.handleSeckillMessage(msg);      // 落库
        ack.acknowledge();                             // ★ 成功才 ack
    } catch (Exception e) {
        redis.delete(idemKey);                         // 允许重试
        throw e;                                       // 不 ack -> errorHandler 重试
    }
}
```

配套：`application-dev.yml` 增加 `spring.kafka.listener.ack-mode: manual`。
- 幂等键：`seckill:consumed:{orderNo}`（TTL 2h）。
- 数据库唯一索引 `uk_activity_user` 仍是最后一道防线。

### 1.2 支付乐观锁（`OrderMapper.markPaid`）

```sql
UPDATE orders SET status = 1, pay_time = NOW() WHERE order_no = ? AND status = 0
```
并发支付时，只有第一条能把 `status=0 -> 1`，其余 `WHERE status=0` 不命中，影响行数为 0 → 返回失败。
接口：`POST /api/seckill/pay/{orderNo}`。

### 1.3 超时关单 + 回补库存（`OrderMapper.markClosed` / `SeckillStockMapper.restoreStock`）

```sql
UPDATE orders SET status = 3 WHERE id = ? AND status = 0        -- 条件更新，防与支付打架
UPDATE seckill_stock SET stock = stock + 1 WHERE activity_id = ?
```
关单成功后：回补 DB 库存 + 回补 Redis 库存 + 写一条 `change_type=+1` 的流水。
接口：`POST /api/seckill/{activityId}/close-timeout?timeoutSeconds=`
（实验用手动触发；生产可挂 `@Scheduled`。）

---

## 2. 功能验证

### 2.1 支付并发（`scripts/pay-test.ps1`，同一订单并发支付 20 次）

```
[pay-test] orderNo=S8b25..., 并发 20 次支付
[pay-test] 支付成功次数 = 1 / 20 （预期 1）
```
✅ 并发支付只 1 次成功。

### 2.2 超时关单回补（`timeoutSeconds=0`）

| 阶段 | 订单状态 | 剩余库存 | 流水 |
|---|---:|---:|---|
| 关单前 | 0（待支付） | 99 | -1 × 1 |
| 调用 close-timeout | → data=1（关闭 1 单） | | |
| 关单后 | 3（超时关闭） | **100** | -1 × 1, **+1 × 1** |

✅ 关单后库存回补到 100，流水记录回补 +1。

---

## 3. 压测数据（30s，日志关闭，库存 100）

| 并发 | 吞吐 | 平均 | P95 | P99 |
|---:|---:|---:|---:|---:|
| 100 | 3951.1/s | 21.1ms | 29 | 37 |
| 200 | 3476.4/s | 48.2ms | 68 | 75 |
| 500 | 4066.9/s | 103.1ms | 131 | 149 |
| 1000 | 3950.2/s | 212.7ms | 258 | 280 |

→ 与 v2 基本一致。**可靠性增强（幂等/ack）只发生在消费者侧，不影响接口吞吐。**

---

## 4. 业务正确性

```
剩余库存 0 / 订单 100 / 重复用户 0
```
对账全 PASS，异步 + 幂等没有破坏正确性。

---

## 5. 结论

1. **消费幂等**：以 orderNo 去重，重复投递不会重复落库。
2. **手动 ack**：处理成功才确认；失败不 ack 交给重试。这是消息"至少一次"语义下的正确姿势。
3. **支付乐观锁**：条件更新让并发支付只 1 次成功。
4. **超时关单回补**：状态机闭环，库存守恒。
5. 可靠性的代价在消费者侧，接口吞吐不变。

## 6. 遗留问题 / 下一步

- `v4-ratelimit`：Redis 滑动窗口 + AOP 限流，削掉入口洪峰。
- 可扩展（未做）：Kafka 死信队列、发送端可靠（事务消息 / 本地消息表）、`@Scheduled` 自动关单。
