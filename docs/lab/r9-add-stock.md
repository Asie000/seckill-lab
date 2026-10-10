# r9-add-stock —— 秒杀加库存（运营补货）

**tag**：`r9-add-stock`
**日期**：2026-10-10
**主题**：活动进行中「加库存」；顺带查出并修复两个真实 bug（Kafka 生产端配置、消费端毒消息阻塞）。

---

## 1. 要解决什么

秒杀进行中运营补货，需要**同时**把库存加到 DB 和 Redis，且不能与并发的扣减打架。

## 2. 实现

`SeckillService.addStock(activityId, qty, requestId)`：

1. **幂等**：`SETNX seckill:addstock:{activityId}:{requestId}`（TTL 24h），重复 requestId 直接返回；
2. **先改 DB**（唯一真相）：`UPDATE seckill_stock SET stock = stock + #{qty}`，记库存流水 `change_type=2`；
3. **再改 Redis**：key 存在则 `INCRBY qty`（原子，不会覆盖并发扣减）；未预热则用 DB 现值 `SET`
   （⚠️ 不能调 `warmUp`——它会清空 `seckill:order` 已下单集合，导致一人多单）。

接口：`POST /api/seckill/{id}/add-stock?qty=..&requestId=..`、`GET /api/seckill/{id}/stock`（对账）。

> 顺序为什么是「先 DB 后 Redis」：若先 Redis 后 DB，DB 写失败会造成 **Redis 多、DB 少 → 超卖**；
> 先 DB 后 Redis，即使 Redis 失败最坏是**少卖**（安全），可重试补偿。

---

## 3. 测试

### 3.1 顺序加库存 + 幂等

| 步骤 | redisStock | dbStock | applied |
|---|---:|---:|---|
| 初始 | 100 | 100 | — |
| +50 (requestId=r1) | 150 | 150 | true |
| 重复 r1 | 150 | 150 | **false（幂等拦截）** |
| +30 (requestId=r2) | 180 | 180 | true |

### 3.2 并发加库存 + 秒杀扣减 ★

场景：先重置 DB/Redis 库存=100000 并预热；JMeter 500 线程跑秒杀 20s（userId 20000~25000），
同时 5 次补货各 +100。

- JMeter：**60483 请求，2992/s**；
- 5 次补货全部 `applied=true`，Redis/DB 同步增加；
- **结束后：redisStock = dbStock = 95499，`consistent=true`；**
- **订单增量 5001，库存扣减 5001**（100500 − 95499 = 5001）——**完全对账，不超卖不丢单**。

---

## 4. ★ 顺带查出的两个真实 bug

### bug1：Kafka 生产端配置错误，导致秒杀**一直** 500

`application-dev.yml` 里设了 `delivery.timeout.ms: 5000`，却没设 `request.timeout.ms`
（默认 30000）。Kafka 要求 `delivery.timeout.ms >= request.timeout.ms + linger.ms`，
不满足时 **producer 构造直接失败**，报 `Failed to construct kafka producer` ——
表现为**每次秒杀都 500**（即使 Kafka 正常）。

**修复**：补 `request.timeout.ms: 3000`。

> ⚠️ 这会影响之前 `fault-injection.md` 的结论：那次「Kafka 挂 → 明确 500」的 500，
> 很可能是这个配置 bug 造成的（与 Kafka 是否可用无关），需重验。

### bug2：毒消息永久阻塞分区（缺死信队列）

压测中一条消息因唯一索引冲突反复抛 `SECKILL_REPEAT`，消费者「失败不 ack → seek 回退重试」，
于是**同一条消息无限重试，整个分区被卡死**，后续消息全部无法消费（实测订单增量为 0）。

**修复**：接入 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`：
失败重试 2 次（间隔 200ms），仍失败则投递到死信主题 `seckill-order-dlq` 并提交 offset，分区得以继续。

```java
DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
        (r, ex) -> new TopicPartition("seckill-order-dlq", r.partition()));
return new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2));
```

---

## 5. 结论 / 面试点

- 加库存要点：**DB 为准、先 DB 后 Redis、Redis 用 INCRBY（原子）、requestId 幂等**。
- 补货与扣减并发用原子操作互不干扰；实测对账一致。
- 消费端必须考虑**毒消息**：无限重试会卡死分区，要有**重试上限 + 死信队列 + 告警**。
- Kafka 参数有**互相约束**（`delivery.timeout.ms >= request.timeout.ms + linger.ms`），改一个要连带检查。

## 6. 复现

```powershell
# 顺序 + 幂等
curl -X POST "http://127.0.0.1:8081/api/seckill/1/add-stock?qty=50&requestId=r1"
curl -X POST "http://127.0.0.1:8081/api/seckill/1/add-stock?qty=50&requestId=r1"   # applied=false
# 并发
scripts\add-stock-concurrency.ps1
```

## 7. 遗留

- 死信消息的消费/告警未做。
- 旧消费者组 `seckill-lab` 仍有历史毒消息积压（本实验改用新组 `seckill-lab-r9` 绕开）。
- `fault-injection.md` 的 Kafka 结论需用修好的配置重验。
