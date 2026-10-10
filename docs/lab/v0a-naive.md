# v0a-naive —— 秒杀基线（超卖 + 重复下单）

**tag**：`v0a-naive`
**日期**：2026-10-10
**主题**：秒杀下单写成"新手版"——无保护扣库存、无幂等，暴露两个病灶。
**标准口径**：日志关闭。

---

## 1. 这一步要演示什么

Phase S 的第一条基线：一个"能跑但会出事"的秒杀接口。

| 病灶 | 成因 | 证据 |
|---|---|---|
| **超卖** | `UPDATE stock = stock - 1` 缺 `WHERE stock >= 1` | 库存扣成负数 |
| **重复下单** | 无幂等/去重，同一用户可无限次下单 | 一个用户多单 |

`v0b-atomic` 会加 `WHERE stock >= 1`（只治超卖）；`v1-redis-lua` 才两个都治。

---

## 2. 代码与数据变更

### 2.1 新增秒杀模块 `modules/seckill`

```
controller/SeckillController   -> POST /api/seckill/{activityId}?userId=
service/SeckillService(+Impl)  -> 秒杀逻辑
entity/SeckillActivity|SeckillStock|StockLog
mapper/SeckillActivityMapper|SeckillStockMapper|StockLogMapper
```

### 2.2 关键代码：无保护扣减

```java
// SeckillStockMapper.java
@Update("UPDATE seckill_stock SET stock = stock - 1 WHERE activity_id = #{activityId}")
int deductNaive(Long activityId);   // ★ 缺 stock >= 1，会扣成负数
```

下单流程（`SeckillServiceImpl.seckill`）：
1. 查活动、校验时间窗口；
2. `deductNaive` 扣库存（无保护）；
3. 插入 `orders`（`order_type=1`）+ `order_item` + `stock_log`；
4. 返回订单号。

**全程没有幂等判断**，同一用户每次请求都成功建单。

### 2.3 数据/脚本

- 统一订单表 `orders` 承载秒杀单（`order_type=1`，`activity_id` 关联活动）。
- `reconcile.sql` / `reset-stock.sql` 已适配统一订单表。
- 活动：id=1，`total_stock=100`，`stock=100`。

---

## 3. 压测数据（POST /api/seckill/1?userId=随机1..5000）

30s/档，日志关闭，错误率全 0%（HTTP 层；业务失败也返回 HTTP 200，见备注）。

| 并发 | 样本 | 吞吐 | 平均 | P95 | P99 |
|---:|---:|---:|---:|---:|---:|
| 100 | 9344 | 312.2/s | 270ms | 354 | 381 |
| 200 | 10293 | 343.9/s | 492ms | 625 | 680 |
| 500 | 10493 | 350.3/s | 1225ms | 1519 | 1616 |
| 1000 | 10993 | 366.2/s | 2411ms | 2789 | 2799 |

> 每秒 ~312~366，比读侧低很多：每次秒杀要写 3 张表（orders / order_item / stock_log）并提交事务。
> 注：本接口业务失败也返回 HTTP 200（`Result.code` 里区分），所以 JMeter 的错误率测不出业务失败，
> 业务正确性必须看对账 SQL。

---

## 4. 业务正确性对账（1000 并发那一轮）

```
总库存   剩余库存   秒杀订单数   下单用户数   单人最多下单数   重复下单用户数
100      -10893     10993      4446        9              3199
```

**结论（全部为预期症状）**：

1. **严重超卖**：库存 100，却卖出 **10993** 件，剩余库存 **-10893**。
   （`deductNaive` 没有 `stock >= 1` 保护，请求多就一路扣到负数。）
2. **大量重复下单**：4446 个用户里 **3199 人**下了多单，单人最多 **9 单**。
   （无幂等，同一用户反复请求都成功。）

对账 SQL 结果：
- ① 超卖检测：**❌ FAIL**（`stock < 0`）
- ② 一人一单：**❌ FAIL**（大量用户多单）
- ③ 库存守恒：✅ PASS（流水净扣减 10993 = 库存扣减 10993，说明每条扣减都记了流水）

---

## 5. 结论

- `v0a-naive` 成功复现**超卖 + 重复下单**两个病灶，作为 Phase S 的基线 0。
- 吞吐 ~312~366/s，比读侧低一个数量级（写多 + 事务提交）。
- 下一版 `v0b-atomic` 只加 `WHERE stock >= 1`，用于把"超卖"这一项单独消掉，
  从而把两个病分开归因。

## 6. 遗留问题 / 下一步

- `v0b-atomic`：`deductAtomic`（`WHERE stock >= 1`）——预期"不超卖，但仍重复下单"。
- 后续 `v1` 用 Redis + Lua 同时治超卖与重复，并提升吞吐。
