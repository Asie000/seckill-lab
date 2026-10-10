# seckill-lab 实验文档（docs/lab）

本目录记录「电商大促综合实验」的全部过程：每一步命令、输出、报错、结论。
**目的**：任何时候都能回看"当时到底做了什么、为什么、结果如何"。

## 文档索引

| 文档 | 内容 |
|---|---|
| `01-environment.md` | 实验环境搭建：中间件启动、建库造数、编译、起应用、JMeter 冒烟验证（含踩坑记录） |
| `r0-baseline.md` | Phase R 读侧基线：N+1 订单列表、连接池 10、下单同步阻塞 |
| `r1-n1.md` | 批量 IN 查询消除 N+1（62 → 5 次 SQL） |
| `r2-pool.md` | （待做）连接池调优 + 梯度实验 |
| `r3-cache.md` | （待做）Redis 缓存 + 一致性竞态 |
| `r4-async.md` | （待做）@Async 拆非核心逻辑 |
| `v0a-naive.md` … `v4-ratelimit.md` | （待做）Phase S 秒杀写侧各版本 |
| `REPORT.md` | （最后写）总报告：每一步贡献与代价 |

## 实验阶段总表

| 阶段 | tag | 主题 | 状态 |
|---|---|---|---|
| 环境 | `stage0-env` `stage1-db` | 中间件 + 六表 | ✅ 已完成（早于本实验） |
| Phase R | `r0-baseline` | 读侧基线（N+1 / 小连接池 / 同步阻塞） | ✅ 已完成 |
| Phase R | `r1-n1` | 批量 IN 消除 N+1 | ✅ 已完成 |
| Phase R | `r2-pool` | 连接池调优 | ⬜ |
| Phase R | `r3-cache` | Redis 缓存 + 一致性 | ⬜ |
| Phase R | `r4-async` | @Async 异步化 | ⬜ |
| Phase S | `v0a-naive` / `v0b-atomic` | 秒杀基线（超卖 / 重复下单） | ⬜ |
| Phase S | `v1-redis-lua` | Lua 预扣 + 双兜底 | ⬜ |
| Phase S | `v2-kafka` | Kafka 异步削峰 | ⬜ |
| Phase S | `v3-consistency` | 支付/关单/幂等 | ⬜ |
| Phase S | `v4-ratelimit` | 滑动窗口限流 | ⬜ |

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
