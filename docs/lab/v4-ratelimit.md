# v4-ratelimit —— Redis 滑动窗口 + AOP 限流

**tag**：`v4-ratelimit`
**日期**：2026-10-10
**主题**：在秒杀入口加一层滑动窗口限流，削掉洪峰。
**标准口径**：日志关闭。

---

## 1. 代码变更

| 文件 | 作用 |
|---|---|
| `resources/limiter.lua` | Redis ZSet 滑动窗口限流脚本 |
| `modules/ratelimit/annotation/RateLimit` | `@RateLimit(key, window, limit, type, message)`，type 支持 IP/USER/METHOD |
| `modules/ratelimit/aop/RateLimitAspect` | `@Before("@annotation(rateLimit)")` 执行脚本，超限抛异常 |
| `modules/ratelimit/exception/RateLimitException` | 触发限流的异常 |
| `GlobalExceptionHandler` | 新增处理：限流返回 **HTTP 429** |
| `SeckillController` | 秒杀接口标注 `@RateLimit(window=1, limit=500, type=IP)` |

限流开关：`seckill.ratelimit.enabled`（默认 true），压测可 `--seckill.ratelimit.enabled=false` 关掉做 A/B。

```java
@RateLimit(key = "seckill", window = 1, limit = 500,
        type = RateLimit.LimitType.IP, message = "秒杀请求过于频繁，请稍后再试")
@PostMapping("/{activityId}")
public Result<String> seckill(...) { ... }
```

---

## 2. ★ 发现并修复了一个限流绕过 bug（本次最有价值的部分）

### 2.1 现象

第一次跑 200 并发，限流设定 **500/s**，但实际放行：

```
总吞吐 3581/s，放行 200 = 1370/s（38.3%），限流 429 = 2211/s
```

放行量是设定值的 **2.7 倍**，限流被绕过了。

### 2.2 根因

原 Lua 脚本用时间戳生成 ZSet 的 member：

```lua
math.randomseed(now)
local member = now .. '-' .. math.random(1000000)
```

`math.randomseed(now)` 用**毫秒时间戳**做种子。**同一毫秒内的多个请求**，
种子相同 → 第一个随机数相同 → **member 完全相同** → ZSet 视作同一个成员去重，
于是 `ZCARD` 只按"不同毫秒数"计数，而不是"请求数"。
`ZCARD` 少算 → `current < limit` 判定一直成立 → 疯狂放行。

> 这是一个很隐蔽、但在高并发下必然触发的 bug：并发越高、同毫秒请求越多，绕过越严重。

### 2.3 修复

member 由调用方传入**每次唯一的 UUID**（不再在 Lua 里用时间戳播种随机）：

```lua
local member = ARGV[4]
...
redis.call('ZADD', key, now, member)
```
```java
..., String.valueOf(System.currentTimeMillis()), UUID.randomUUID().toString());
```

### 2.4 修复前后对比（200 并发，限流 500/s）

| 版本 | 总吞吐 | 放行(200) | 限流(429) |
|---|---:|---:|---:|
| 修复前（member 撞车） | 3581/s | **1370/s** | 2211/s |
| **修复后** | 3904/s | **512/s** ✅ | 3392/s |

修复后放行 **512/s ≈ 设定的 500/s**，符合预期。

---

## 3. 数据小结

| 场景 | 总吞吐 | 放行 | 限流 |
|---|---:|---:|---:|
| 无限流（v3 同接口） | ~3950/s | ~3950/s | 0 |
| 限流 500/s（修复后） | 3904/s | **512/s** | 3392/s |

- 限流**不降低系统总处理能力**（总吞吐仍 ~3900/s），只是把"超出的请求"提前用 429 挡掉，
  不让它们进入后面的 Lua/DB，从而保护后端。
- 业务正确性不受影响：`剩余库存 0 / 订单 100 / 重复 0`。

---

## 4. 结论

1. **AOP + Redis ZSet 滑动窗口**实现了入口限流，超限返回 429。
2. **发现并修复了 member 撞车导致的限流绕过**（高并发下 ZCARD 少算）——
   这是整套实验里最"实战"的一个 bug，面试非常能讲：
   - 怎么发现问题：限流设定值与实测放行量对不上；
   - 怎么定位：想清楚 ZSet member 的唯一性；
   - 怎么验证：修复后放行量回到 500/s。
3. 限流是"弃车保帅"：牺牲超额请求，保护下游。

## 5. 遗留问题 / 下一步

- 限流维度目前按 IP；生产可按用户/接口/全局组合。
- 令牌桶 vs 滑动窗口的取舍未展开（面试可聊）。
- Phase S 主线完成，下一步收尾：故障注入、总报告 `REPORT.md`。
