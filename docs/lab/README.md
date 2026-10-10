# seckill-lab 实验文档（docs/lab）

本目录记录「电商大促综合实验」的全部过程：每一步命令、输出、报错、结论。
**目的**：任何时候都能回看"当时到底做了什么、为什么、结果如何"。

## 文档索引

| 文档 | 内容 |
|---|---|
| `01-environment.md` | 实验环境搭建：中间件启动、建库造数、编译、起应用、JMeter 冒烟验证（含踩坑记录） |
| `x-sql-log-overhead.md` | 专题：SQL 日志开销（关日志 QPS 提升 13~20 倍） |
| `r0-baseline.md` | Phase R 读侧基线：N+1 订单列表、连接池 10、下单同步阻塞 |
| `r1-n1.md` | 批量 IN 查询消除 N+1（62 → 5 次 SQL） |
| `r2-pool.md` | 连接池调优 + 梯度实验（结论：池不是瓶颈，日志才是） |
| `r3-cache.md` | Redis 缓存 + 一致性竞态（脏读复现与延迟双删修复） |
| `r4-async.md` | @Async 拆非核心逻辑（下单 3.7x，暴露线程池容量上限） |
| `v0a-naive.md` | 秒杀基线：超卖 + 重复下单（病灶复现） |
| `v0b-atomic.md` | 原子扣减：只治超卖，重复下单仍在 |
| `v1-redis-lua.md` | Redis+Lua 预扣 + 一人一单 + 双兜底（唯一索引 vs 锁） |
| `v2-kafka.md` | Kafka 异步落库（成功路径 ~7.7x；异步 vs 同步对照） |
| `v3-consistency.md` | 幂等消费 + 手动 ack + 支付乐观锁 + 超时关单回补 |
| `v4-ratelimit.md` | Redis 滑动窗口限流（含 member 撞车 bug 修复） |
| `fault-injection.md` | 故障注入：停 Redis / 停 Kafka 的降级行为 |
| `REPORT.md` | ★ 总报告：每一步贡献与代价 + 三个真实 bug |
| `r5-cache-breakdown.md` | 缓存击穿：互斥锁 vs 逻辑过期（补 Phase R） |
| `r6-multilevel-cache.md` | 多级缓存：Caffeine + Redis（补 Phase R） |
| `r6b-multilevel-cache-hardened.md` | 多级缓存加强：Redis 真实负载 + 容量 + 一致性（含 bug） |
| `r7-bloom-filter.md` | 缓存穿透 + 布隆过滤器（Redis bitmap） |
| `r8-redis-idworker.md` | 分布式 ID：RedisIdWorker（时间戳+自增） |
| `r9-add-stock.md` | 秒杀加库存（含 Kafka 配置 bug + 毒消息/死信队列） |
| `r10-kafka-lab.md` | Kafka 顺序消费 / 堆积(lag) / offset |
| `r11-order-autoclose.md` | 未支付订单到期自动关闭（Spring Task 定时任务） |
| `v0a-naive.md` … `v4-ratelimit.md` | （待做）Phase S 秒杀写侧各版本 |
| `REPORT.md` | （最后写）总报告：每一步贡献与代价 |

## 实验阶段总表

| 阶段 | tag | 主题 | 状态 |
|---|---|---|---|
| 环境 | `stage0-env` `stage1-db` | 中间件 + 六表 | ✅ 已完成（早于本实验） |
| Phase R | `r0-baseline` | 读侧基线（N+1 / 小连接池 / 同步阻塞） | ✅ 已完成 |
| Phase R | `r1-n1` | 批量 IN 消除 N+1 | ✅ 已完成 |
| Phase R | `r2-pool` | 连接池调优 | ✅ 已完成 |
| Phase R | `r3-cache` | Redis 缓存 + 一致性 | ✅ 已完成 |
| Phase R | `r4-async` | @Async 异步化 | ✅ 已完成 |
| Phase S | `v0a-naive` / `v0b-atomic` | 秒杀基线（超卖 / 重复下单） | ✅ 已完成 |
| Phase S | `v1-redis-lua` | Lua 预扣 + 双兜底 | ✅ 已完成 |
| Phase S | `v2-kafka` | Kafka 异步削峰 | ✅ 已完成 |
| Phase S | `v3-consistency` | 支付/关单/幂等 | ✅ 已完成 |
| Phase S | `v4-ratelimit` | 滑动窗口限流 | ✅ 已完成 |

## 压测标准口径（重要）

**所有版本横向对比，统一关闭 SQL 日志**（`-NoSqlLog`）。
原因见 `x-sql-log-overhead.md`：SQL 日志本身造成 13~20 倍的开销，会把各优化之间的差异全部盖住。
日志开启的数据仅作为日志开销专题保留。

## 固定流程（每个 tag）

```
1. 实现代码
2. 停旧应用 -> 编译 (mvnw package)
3. 重置数据 (schema.sql + 造数脚本)
4. 起应用 (后台 java -jar)
5. 跑 JMeter (scripts/bench.ps1)
6. 采指标 (解析 .jtl)
7. 对账 (reconcile.sql)
8. 写本阶段文档
9. git commit + git tag
```

## 环境约定（写死，避免踩坑）

| 项 | 约定 |
|---|---|
| 应用 JDK | `C:\Users\lsy1\.jdks\openjdk-25.0.1` |
| JMeter JDK | `C:\Users\lsy1\.jdks\ms-11.0.29`（JMeter 5.6.3 对高版本 JDK 兼容差） |
| JMeter | `D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat` |
| 应用端口 | 8081 |
| 中间件 | MySQL 3306 / Redis 6379 / Kafka 9092（Windows 原生安装） |
| MySQL 客户端 | `C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe -uroot -p123456` |
| 压测运行器 | `scripts/bench.ps1` |
| JMeter 计划 | `bench/*.jmx`（含 `@THREADS@` / `@RAMP@` / `@DURATION@` 占位符） |

> **关键区别**：应用用 JDK 25，JMeter 用 JDK 11。JMeter 只是 HTTP 客户端，两者互不影响。
