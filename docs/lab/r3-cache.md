# r3-cache —— Redis 缓存 + 一致性竞态

**tag**：`r3-cache`
**日期**：2026-10-10
**主题**：给商品读取加 Redis 缓存（Cache-Aside），并复现/修复缓存一致性竞态。
**标准口径**：本版起压测统一**关闭 SQL 日志**（见 `x-sql-log-overhead.md`）。

---

## 1. 代码变更

### 1.1 新增 `GoodsCacheService`（`modules/goods/cache/`）

Cache-Aside 模式：

```java
public Goods getById(Long id) {
    Object cached = redisTemplate.opsForValue().get(key(id));
    if (cached instanceof GoodsCacheValue v) return v.toGoods();   // 命中
    Goods goods = goodsMapper.selectById(id);                       // 回源
    if (goods != null) redisTemplate.opsForValue().set(key(id), GoodsCacheValue.of(goods), TTL); // 回填
    return goods;
}
```

- key：`cache:goods:{id}`，TTL 5 分钟。
- 缓存值用 `GoodsCacheValue`（时间字段存 epoch 毫秒），规避 Redis JSON 序列化对 `LocalDateTime` 的兼容问题。

### 1.2 更新时删缓存

`GoodsServiceImpl.update()` 改完 DB 后调用 `goodsCacheService.evict(id)`（**朴素做法，有竞态**）。

### 1.3 缓存开关

`seckill.cache.goods.enabled`（默认 true），压测时可用
`--seckill.cache.goods.enabled=false` 做 A/B 对照。

### 1.4 教学端点（复现竞态）

| 端点 | 作用 |
|---|---|
| `POST /api/goods/{id}/race` | 复现「删缓存 + 并发回填旧值」的脏读 |
| `POST /api/goods/{id}/race-fixed` | 延迟双删修复后的对照 |

---

## 2. 证据一：缓存一致性竞态（核心）

调用两个端点，实际返回：

```
朴素删缓存 : {"dbPrice":8000.00,"cachePrice":7999.00,"stale":true,
             "desc":"朴素删缓存：并发回填旧值，缓存与 DB 不一致"}
延迟双删   : {"dbPrice":8001.00,"cachePrice":8001.00,"stale":false,
             "desc":"延迟双删：并发的旧值被第二次删除覆盖，最终一致"}
```

**竞态时序**（`simulateRace` 内部）：

```
t0    reader 读到旧值 DB=old
t50   writer 改 DB=new，删缓存
t350  reader 把旧值回填进缓存      <-- 竞态：缓存被写回旧值
=> 最终 DB=new，缓存=old，不一致（stale=true）
```

**修复**：延迟双删——更新后删一次，延迟 500ms 再删一次，覆盖 reader 稍后回填的旧值。

> 这是本次实验里少见的**正确性问题**（前面都是性能问题）。缓存让读变快，但引入了"缓存与 DB 不一致"的风险。

## 3. 证据二：缓存带来的吞吐提升

商品详情 `GET /api/goods/1`，日志关闭：

| 并发 | 缓存 OFF | 缓存 ON | 提升 |
|---:|---:|---:|---:|
| 100 | 20342/s | **33257/s** | 1.63x |
| 200 | 22045/s | **32299/s** | 1.46x |
| 500 | 21679/s | **30802/s** | 1.42x |
| 1000 | 21473/s | **29844/s** | 1.39x |

（缓存 ON 平均响应 2.7~30.8ms，P99 6~75ms）

**解读**：单行主键查询本来就快（MySQL 有缓冲池），缓存只带来 **~1.4~1.6 倍**。
缓存的真正价值在**更重的查询**（多表 join、复杂条件）或**数据库本身成为瓶颈**时才会放大。
本实验里它更多是"演示正确性问题"的载体。

## 4. 证据三：订单列表无回归

`r3` 订单列表（不受商品缓存影响，应保持 r2 水平）：

| 并发 | 吞吐 | 平均 | P95 | P99 |
|---:|---:|---:|---:|---:|
| 100 | 1233/s | 74 | 103 | 135 |
| 200 | 1297/s | 142 | 223 | 279 |
| 500 | 1285/s | 359 | 461 | 550 |
| 1000 | 1278/s | 724 | 856 | 989 |

与 r2（1202~1288/s）基本一致 → **无回归**。

---

## 5. 结论

1. Redis 缓存让商品读吞吐提升 **~1.4~1.6 倍**（单行查询，收益有限）。
2. **最重要的是暴露了缓存一致性问题**：朴素"删缓存"在并发下会产生脏读（`stale=true`）。
3. **延迟双删**可修复（`stale=false`），代价是引入延迟、且并非强一致。
4. 面试可展开：缓存一致性的几种方案（延迟双删 / 订阅 binlog / 短 TTL 兜底）及各自局限。

## 6. 遗留问题 / 下一步

- `r4-async`：把下单接口的短信/邮件/积分拆到异步线程池。
- 缓存其它问题未覆盖（穿透 / 击穿 / 雪崩），后续可作为专题补充。
