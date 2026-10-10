# r6-multilevel-cache —— 多级缓存（Caffeine + Redis）

**tag**：`r6-multilevel-cache`
**日期**：2026-10-10
**主题**：在 Redis 之前再加一层 **JVM 本地缓存（Caffeine）**，减少 Redis 网络往返。
**标准口径**：日志关闭；单机，相对对比。

---

## 1. 为什么要多级缓存

r3 用 Redis 做缓存，已能挡住大部分 DB 压力。但热点 key 的每次读仍要**一次 Redis 网络往返**。
当 QPS 很高（尤其是热 key），Redis 本身会成为瓶颈/网络开销。

**多级缓存**：L1 本地缓存（进程内，纳秒级）→ L2 Redis → L3 DB。

- 读：L1 命中直接返回；未命中查 L2，命中回填 L1；L2 也未命中才查 DB。
- L1 用 Caffeine：`maximumSize` + `expireAfterWrite` + `recordStats()`。
- 一致性：更新时**同时失效 L1 与 L2**（单实例本地 `invalidate` 即可）。

---

## 2. 代码

`MultiLevelGoodsCacheService`（`modules/goods/cache/`），开关 `seckill.cache.multilevel.enabled`：

| 值 | 行为 |
|---|---|
| `true`（默认） | Caffeine(L1) + Redis(L2)，L1 命中不走 Redis |
| `false` | 仅 Redis（等价 r3 单级缓存），用于对比 |

- L1：`Caffeine.maximumSize(1000).expireAfterWrite(5s).recordStats()`
- L2：Redis string，TTL 30s
- 读用 `l1.get(id, mappingFunction)`：同一 key **单飞**，顺带避免击穿
- 更新商品时 `multiLevelGoodsCacheService.invalidate(id)`（失效 L1+L2）

接口：
- `GET /api/goods/{id}/ml` 多级缓存读取
- `GET /api/goods/ml/stats`、`POST /api/goods/ml/stats/reset` 命中统计
- `POST /api/goods/{id}/ml/evict` 清两级缓存

> 说明：更新 `PUT /api/goods/{id}` 已挂上 `invalidate(id)`。
> ⚠️ 此版本**并不能**保证不脏读——多级缓存在单实例下也会因"并发回填旧值"而脏读，
> 详见 `r6b-multilevel-cache-hardened.md` 实验 3（已复现并修复）。

---

## 3. 压测数据（100/200/500/1000 四档，各 30s）

### 3.1 吞吐与延迟

| 模式 | 并发 | 吞吐 | 平均 | P95 | P99 |
|---|---:|---:|---:|---:|---:|
| **多级(L1+L2)** | 100 | **52425/s** | 1.6ms | 3 | 4 |
| **多级** | 200 | **45987/s** | 3.6ms | 7 | 10 |
| **多级** | 500 | **46926/s** | 8.9ms | 15 | 19 |
| **多级** | 1000 | **46146/s** | 18.0ms | 30 | 36 |
| 仅 Redis | 100 | 32872/s | 2.5ms | 4 | 6 |
| 仅 Redis | 200 | 27213/s | 6.1ms | 10 | 13 |
| 仅 Redis | 500 | 30655/s | 13.6ms | 20 | 23 |
| 仅 Redis | 1000 | 27937/s | 29.9ms | 44 | 81 |

- **吞吐约 1.6x**（52425/32872≈1.59，46146/27937≈1.65）。
- **延迟约减半**（如 1000 并发 P99 36ms vs 81ms）。

### 3.2 命中分布（多级模式）

| 并发 | L1 命中 | L1 未命中 | L2 命中 | DB 回源 |
|---:|---:|---:|---:|---:|
| 100 | 1,570,027 | 6 | 5 | 1 |
| 200 | 1,376,938 | 6 | 5 | 1 |
| 500 | 1,404,334 | 6 | 5 | 1 |
| 1000 | 1,386,227 | 6 | 5 | 1 |

- 30s 内 L1 TTL 5s → 约 6 次重建；每次重建穿到 L2 一次、最终只有 1 次到 DB。
- **L1 挡住了 >=99.999% 的请求**，Redis QPS 从「每请求一次」降到「每 5s 一次」。

---

## 4. 解读

1. 本机 Redis 也在 localhost，仍测得 **1.6x**，说明「省一次网络往返」在高 QPS 下很值。
2. **对 Redis 的保护更大**：L2 请求量从 ~百万级降到个位数，Redis 压力几乎归零。
3. 代价：**一致性更难**。多实例部署时，A 实例更新后 B 实例的 L1 仍是旧值，
   需要 Redis 发布订阅 / 版本号 / 短 TTL 兜底来广播失效。
   本实验单实例，`invalidate` 本地失效即可，且 L1 TTL 5s 本身也限制脏读窗口。

---

## 5. 结论 / 面试点

- 多级缓存的收益：**吞吐↑、延迟↓、下游(Redis)压力↓↓**。
- 核心矛盾：**一致性 vs 性能**；L1 越久越省，也越容易脏。
- 常见落地：Caffeine + Redis + 变更时广播失效（pub/sub 或 MQ）。
- 与 r5 的呼应：Caffeine 的 `get(key, loader)` 天然单飞，也顺带防击穿。

## 6. 遗留

- 多实例 L1 失效广播未实现（单实例实验）。
- 布隆过滤器防穿透（下一项）。
