# r4-async —— @Async 拆非核心逻辑

**tag**：`r4-async`
**日期**：2026-10-10
**主题**：把普通下单的非核心逻辑（短信 / 邮件 / 积分，共 ~1s）拆到异步线程池。
**标准口径**：日志关闭；本版对「下单接口」压测。

---

## 1. 代码变更

### 1.1 `AsyncConfig`（新增）

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean("orderExecutor")
    public ThreadPoolTaskExecutor orderExecutor() {
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler(new CallerRunsPolicy()); // 队列满 -> 调用线程执行
        ...
    }
}
```

### 1.2 `NotificationService` 增加异步方法

```java
@Async("orderExecutor")
public void afterOrderCreatedAsync(String orderNo) {
    afterOrderCreated(orderNo);   // 短信500ms + 邮件300ms + 积分200ms
}
```

### 1.3 下单接口开关

`OrderServiceImpl.create()`：

```java
if (asyncEnabled) notificationService.afterOrderCreatedAsync(orderNo); // 立即返回
else              notificationService.afterOrderCreated(orderNo);      // 同步（r0~r3 行为）
```

- 开关：`seckill.order.async.enabled`（默认 true），压测用 `--seckill.order.async.enabled=false` 关掉做 A/B。
- 顺带把订单号改成 UUID（原 `时间戳+4位随机` 在高并发下会撞唯一键）。

---

## 2. 压测数据（下单接口 `POST /api/order/create`）

时长 30s，日志关闭，错误率全 0%。

### 同步（async OFF）

| 并发 | 样本 | 吞吐 | 平均 | P50 | P95 | P99 |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 843 | 28.2/s | 1543ms | 1620 | 1801 | 1814 |
| 100 | 938 | 31.4/s | 2850ms | 3320 | 3430 | 3442 |
| 200 | 1614 | 54.0/s | 3300ms | 3004 | 4001 | 4007 |

### 异步（async ON）

| 并发 | 样本 | 吞吐 | 平均 | P50 | P95 | P99 |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 3129 | **104.5/s** | 403ms | **23** | 1203 | 1427 |
| 100 | 3339 | **113.7/s** | 763ms | 1028 | 1941 | 2065 |
| 200 | 3475 | **116.1/s** | 1486ms | 1808 | 3001 | 3039 |

### 对比（50 并发）

| 指标 | 同步 | 异步 | 提升 |
|---|---:|---:|---:|
| 吞吐 | 28.2/s | 104.5/s | **3.7x** |
| 平均延迟 | 1543ms | 403ms | **3.8x** |
| P50 | 1620ms | **23ms** | **70x** |

---

## 3. 解读

1. **异步显著有效**：请求线程不再干等 1 秒，吞吐约 **3.7 倍**，P50 从 1.6s 掉到 **23ms**。
2. **但出现明显的双峰分布**：异步 P50 是 23ms（快路径），平均却 403ms、P95 1.2s。
   原因是异步线程池被打满，队列溢出触发 `CallerRunsPolicy`，一部分请求退回"同步执行"。
3. **异步有容量上限**：通知线程池每秒最多消化 ~64 个任务（每个 sleep 1s），
   所以下单吞吐被压在 **~105~116/s**，加并发也不涨。
4. **这才是异步方案的真正边界**：线程池只能扛住有限的通知量，扛不住持续高并发。
   要彻底解决，得把通知丢给**消息队列**（消费者按自己的节奏处理）——正是 Phase S 的 v2。

## 4. 面试可展开

- `@Async` 的线程池参数（core/max/queue/rejected）怎么定。
- 拒绝策略取值：`AbortPolicy`（抛异常）/ `CallerRunsPolicy`（背压）/ `DiscardPolicy`（丢任务）。
  本次用 CallerRuns，表现为"退化为同步、延迟回升"。
- `@Async` 失效场景：同类内部调用、方法非 public（应改为用其它 Bean 调用或 MQ）。
- 为什么高并发最终要用 MQ 而不是线程池。

## 5. Phase R 小结

| 版本 | 主要改动 | 关键结果 |
|---|---|---|
| r0 | N+1 + 小连接池 + 同步 | 订单列表 ~420/s（日志关） |
| r1 | 批量 IN | ~1370/s（**3.3x**） |
| r2 | 连接池 10→30 | 无提升；**发现日志瓶颈（14x）** |
| r3 | Redis 缓存 | 商品读 ~1.4x；**复现缓存一致性竞态** |
| r4 | @Async | 下单 ~3.7x；暴露线程池容量上限 |

**Phase R 读侧完成**，下一步进入 **Phase S 秒杀写侧**（`v0a-naive` 起）。
