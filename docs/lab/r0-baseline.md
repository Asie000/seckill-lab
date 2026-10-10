# r0-baseline —— Phase R 读侧基线（N+1）

**tag**：`r0-baseline`
**日期**：2026-10-10
**主题**：把后台订单/商品接口写成"新手版"——订单列表 N+1、连接池 10、下单同步阻塞。

---

## 1. 这一步要演示什么

对标文章的第一段：一个日活三位数的普通后台，打开 JMeter 一压就崩：
响应几秒、吞吐几十、连接超时。根因不是线程数，是**接口内部的 N+1 查询**。

本版刻意留下以下"病灶"，作为后续每个优化点的对照基线：

| 病灶 | 落点 | 留给谁修 |
|---|---|---|
| 订单列表 N+1（20 行 = 62 次 SQL） | `OrderServiceImpl.list()` | `r1-n1` |
| 连接池只有 10、超时 30s | `application-dev.yml` | `r2-pool` |
| 商品查询不走缓存 | `GoodsService.getById()` | `r3-cache` |
| 下单同步执行短信/邮件/积分（~1s） | `NotificationService` | `r4-async` |

---

## 2. 代码与数据变更

### 2.1 数据模型（`src/main/resources/db/schema.sql`）

- **新增统一订单表 `orders`**：`order_type` 区分普通单(0)/秒杀单(1)，替代原 `seckill_order`。
- **新增 `order_item`**（订单明细，一单多商品）。
- **新增 `order_logistics`**（物流）。
- 后两者是 N+1 场景的**必要条件**：列表要展示用户名/商品名/物流状态，就得逐条补查。

### 2.2 造数（`src/main/resources/db/seed-orders.sql`，新增）

递归 CTE 批量生成，规模：**5000 用户 / 21 商品 / 2000 订单（各 1 明细 + 1 物流）**。
脚本幂等（先清空再生成）。

### 2.3 新增接口

| 接口 | 说明 |
|---|---|
| `GET /api/order/list?userId=&pageNum=&pageSize=` | 订单列表（N+1 主角）；`userId` 可空→查全部 |
| `GET /api/order/{orderNo}` | 订单详情 |
| `POST /api/order/create` | 普通下单（同步阻塞） |
| `PUT /api/goods/{id}` | 更新商品（r3 缓存一致性落点） |

### 2.4 关键代码：N+1

`OrderServiceImpl.toVoNaive()` —— 每条订单补查 3 次：

```java
// 第 1 次查询：订单分页（1 次）
IPage<Order> orderPage = orderMapper.selectPage(page, wrapper);
// ★ 对每条订单各补查 3 次
List<OrderVO> records = orderPage.getRecords().stream()
        .map(this::toVoNaive).collect(Collectors.toList());

private OrderVO toVoNaive(Order order) {
    User user = userMapper.selectById(order.getUserId());                       // 补查 1
    vo.setItems(orderItemMapper.selectByOrderIdWithGoods(order.getId()));       // 补查 2
    List<OrderLogistics> lgs = orderLogisticsMapper.selectList(...);            // 补查 3
    ...
}
```

### 2.5 连接池基线（`application-dev.yml`）

```yaml
hikari:
  maximum-pool-size: 10      # 刻意用默认小池
  connection-timeout: 30000  # 等 30 秒才失败
```

### 2.6 下单同步阻塞（`NotificationService`）

```java
public void afterOrderCreated(String orderNo) {
    sleep(500); // 短信
    sleep(300); // 邮件
    sleep(200); // 积分
}
```

---

## 3. 运行步骤（实际执行的命令）

```powershell
# 1) 停旧应用（jar 被占用时无法重新编译）
Stop-Process -Id <旧的pid> -Force

# 2) 编译
cd C:\Users\lsy1\Desktop\seckill-lab
set JAVA_HOME=C:\Users\lsy1\.jdks\openjdk-25.0.1
mvnw.cmd -DskipTests package            # BUILD SUCCESS, 7.6s

# 3) 重置数据
& C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe -uroot -p123456 -e "source .../db/schema.sql"
& C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe -uroot -p123456 -e "source .../db/seed-orders.sql"

# 4) 后台起应用（java -jar，日志重定向到 target/app.log）

# 5) 三档压测
powershell -File scripts\bench.ps1 -Jmx bench\order-list.jmx -Threads 100 -Duration 60 -Tag r0-order-list
powershell -File scripts\bench.ps1 -Jmx bench\order-list.jmx -Threads 200 -Duration 60 -Tag r0-order-list
powershell -File scripts\bench.ps1 -Jmx bench\order-list.jmx -Threads 500 -Duration 60 -Tag r0-order-list
```

---

## 4. 证据一：N+1 确凿（62 次 SQL / 请求）

请求一次 `GET /api/order/list?pageNum=1&pageSize=20`，
从 `app.log`（`StdOutImpl` 打印全部 SQL）统计 `Preparing:` 行数：

```
本次请求产生的 SQL 总数：62

20  SELECT id,username,nickname,create_time FROM user WHERE id=?
20  SELECT oi.goods_id AS goodsId, g.name AS goodsName, ... FROM order_item oi LEFT JOIN goods g ...
20  SELECT id,order_id,company,tracking_no,status,create_time FROM order_logistics WHERE order_id=?
 1  SELECT id,order_no,user_id,... FROM orders ...
 1  SELECT COUNT(*) AS total FROM orders       <- MyBatis-Plus 分页计数
```

**1 + 3×20 = 61 次业务查询**（与文章一致），外加 1 次分页 COUNT = 62。

---

## 5. 证据二：压测数据

场景：`GET /api/order/list?pageNum=1&pageSize=20`，ramp 10s，duration 60s。

| 并发 | 样本数 | 吞吐(req/s) | 平均(ms) | P95(ms) | P99(ms) | Max(ms) | 错误率 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 1198 | 19.3 | 4705 | 6385 | 6893 | 7036 | 0% |
| 200 | 1246 | 19.0 | 9479 | 10774 | 10937 | 11072 | 0% |
| 500 | 1518 | 19.6 | 21426 | 26977 | 27460 | 27600 | 0% |
| 1000 | 2000 | 19.9 | 38110 | 48279 | 48430 | 48533 | 0% |

> 吞吐取自 JMeter 自身 summary（总样本/总耗时）；P95/P99 由 `.jtl` 计算。
> 每版固定口径为 **100 / 200 / 500**；1000 为「加戏」档，用于观察极限与失败模式。

**现象**：
1. **吞吐被钉死在 ~19~20 req/s**，并发从 100 加到 1000 几乎不变。
   → 说明瓶颈不在 Tomcat 线程（200 线程够用），而在后面的**数据库连接池 + N+1**。
2. **响应时间随并发线性恶化**：100→200→500→1000，平均 4.7s→9.5s→21.4s→**38.1s**，最长 **48.5s**。
   典型的"请求在排队"信号。
3. **到 1000 并发仍然 0 错误**（最长 48.5s > 30s 超时却未报错）：
   因为 Hikari 的 30s `connection-timeout` 是**单次借连接**的超时，
   而本接口**无长事务**——每次 SQL 借完立刻归还，单次等待始终 < 30s，
   所以只会整体变慢，不会报 `Connection is not available`。
   （若接口有长事务占着连接不放，就容易触发超时，这个现象留给后面的写侧验证。）

---

## 6. 结论

- `r0` 成功复现"慢系统"：**吞吐 ~19~20 req/s、P99 从 6.9s 一路涨到 48s**，且并发越高越慢。
- 瓶颈定位（下一步 r1 验证）：**N+1 让单请求打 62 次数据库**，把 10 个连接全部占满，
  请求只能排队 → 吞吐上不去、延迟随并发线性上升。
- 加并发到 1000 不会提高吞吐，只会把延迟堆到 48 秒——再次证明**瓶颈不在线程数**。
- 这版数字作为后续所有优化的**基线 0**。

## 7. 遗留问题 / 下一步

- `r1-n1`：把 for 循环补查改成**批量 IN**，目标 62 → 4 次查询，观察吞吐与延迟变化。
- 记录项：本次 `target/app.log` 因打印全量 SQL 涨到 **172MB**，
  说明 SQL 日志本身就是可观开销（后续可单独做一个专题实验）。
- 尚未做对账（读侧无库存变动，对账从 Phase S 起才需要）。
