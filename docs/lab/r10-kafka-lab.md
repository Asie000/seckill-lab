# r10-kafka-lab —— Kafka 顺序消费 / 堆积 / offset

**tag**：`r10-kafka-lab`
**日期**：2026-10-10
**主题**：用可复现的实验讲清 Kafka 的**分区顺序、消费堆积(lag)、offset 提交**。
**模块**：`modules/kafka`（`KafkaLabService`/`KafkaLabConsumer`/`KafkaLabController`），topic `lab-msg`（3 分区）。

---

## 1. 顺序消费

**Kafka 只保证「单分区内」有序，不保证跨分区有序。** 所以「同一条业务线」的消息要用**同一个 key**，
才能落进同一分区、被同一消费者线程顺序处理。

实验：向 `lab-msg`（3 分区）生产 2000 条 `key=A, seq=0..1999`，消费者按逻辑键检查 seq 是否递增。

| 生产方式 | 说明 | 消费到的乱序次数 |
|---|---|---:|
| **keyed**（Kafka key=A） | A 全落同一分区 | **0** |
| unkeyed（key=null，打散 3 分区） | A 分散到 3 分区、3 线程并发 | **4 / 2000** |
| unkeyed（20000 条） | 同上，量更大 | **2404 / 20000** |

> keyed 的 `lastSeqByKey = {A:1999}`（完整有序消费完）；unkeyed 只到 19579，说明确实交错。

**分区分布**（unkeyed 20000 条的 endOffset）：
`partition0=6026, partition2=7391, partition1=10583` —— 消息被均匀打散到 3 个分区。

**结论**：要顺序 → **按业务键分区**（如 orderId/activityId+userId）；代价是热点 key 会集中在单分区。

---

## 2. 消费堆积（lag）

**lag = 分区 latest offset − 消费者组已提交 offset**。生产快于消费时，lag 上涨。
用 AdminClient 从 broker 真实读取（不是应用内估算）。

实验：设消费者每条 sleep 5ms（3 线程 → 理论 ~600/s），一次灌入 12000 条：

| 时刻 | totalLag | 说明 |
|---|---:|---|
| 灌完立刻 | **12000** | 生产 12000 条仅 **0.2s**（~6万/s） |
| 10s 后 | **6848** | 消费 ~5152 条 → **~515/s**（≈ 3 线程 / 5ms） |
| 恢复速度 8s 后 | **0** | 消费者追上，lag 清零 |

**结论**：
- 堆积的本质是**生产速率 > 消费速率**；
- 消费并行度**上限 = 分区数**——加消费者线程只能到分区数（本例 3），再多也是空闲；
- 所以「削峰」要么提高消费能力（加分区+消费者、批量、异步），要么限流生产。

---

## 3. offset 提交

- 本项目 v3 起用 **手动 ack**（`ack-mode: manual`）：处理成功才提交，**至少一次**语义；
- ⚠️ 实测坑：`ack-mode: manual` 是**全局**的——lab 消费者一开始忘了调 `acknowledge()`，
  导致 lag 里 `committed` 恒为 0、lag 永远等于 endOffset。加上 `ack.acknowledge()` 后正常。
- 自动提交 vs 手动提交：自动提交简单但可能**丢消息/重复**；手动提交可精确控制，但要自己处理幂等与重试（见 r9 死信队列）。

---

## 4. Rebalance / ISR（本环境限制，说明为主）

- **Rebalance**：消费者组内成员增减 / 分区数变化时，分区重新分配。本例 `lab-msg` 3 分区、
  消费者 `concurrency=3` → 3 个消费者各分到 1 个分区。成员掉线会触发 rebalance，期间**消费暂停**。
- **ISR**（In-Sync Replicas）：本机是**单节点 Kafka**，topic `replicas=1` → ISR 只有 broker0，
  无法演示副本同步/Leader 切换。多副本需要 ≥3 broker。
- 生产建议：`acks=all` + `min.insync.replicas=2` + 多副本，才能容忍单 broker 故障不丢数据。

---

## 5. 接口 / 复现

```
POST /api/kafka/produce?key=A&count=2000&useKey=true   # 顺序：keyed
POST /api/kafka/produce?key=A&count=2000&useKey=false  # 顺序：unkeyed
POST /api/kafka/slow?ms=5                              # 让消费者变慢（造堆积）
GET  /api/kafka/lag                                    # 真实 lag（AdminClient）
GET  /api/kafka/stats                                  # 应用内 生产/消费/乱序计数
```

## 6. 面试一句话

> Kafka 顺序**只在分区内**，要顺序就按业务键分区；堆积看 **lag**，消费并行度**受分区数封顶**；
> offset 用**手动提交**换精确控制，但要配幂等 + 死信队列兜底。
