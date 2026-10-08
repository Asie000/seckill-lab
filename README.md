# seckill-lab

秒杀实验室 —— 基于 **Spring Boot 4 + MyBatis-Plus + Redis + Kafka** 的项目模板，用于学习/实验高并发秒杀场景。

## 技术栈

| 组件        | 版本   | 说明                                            |
| ----------- | ------ | ----------------------------------------------- |
| JDK         | 17     | LTS                                             |
| Spring Boot | 4.1.1  | 默认使用 **Jackson 3**（`tools.jackson`）       |
| MyBatis-Plus| 3.5.17 | 使用 `mybatis-plus-spring-boot4-starter`        |
| MySQL       | 8.0.29 | 本机原生安装（非 Docker），端口 3306             |
| Redis       | 5.0    | 本机原生安装（非 Docker），端口 6379             |
| Kafka       | 4.3.1  | 本机原生安装，单节点 KRaft（无 ZooKeeper），9092 |
| Maven       | Wrapper| `mvnw` 自带 3.9.x，无需本地安装 Maven           |

## 环境要求

- JDK 17+
- 本机已安装 MySQL / Redis / Kafka（当前为 Windows 原生安装版，**不依赖 Docker**）
- 不需要本地安装 Maven，直接用项目自带的 `mvnw`

## 本机中间件（Windows 原生安装）

中间件不通过 Docker 启动，已在本机安装好，各自带启动/停止脚本：

| 组件  | 版本   | 安装目录                      | 端口               | 启动脚本                     | 停止脚本                    |
| ----- | ------ | ----------------------------- | ------------------ | ---------------------------- | --------------------------- |
| MySQL | 8.0.29 | `C:\Users\lsy1\mysql-8.0.29` | 3306               | `mysql-8.0.29\start-mysql.bat` | `mysql-8.0.29\stop-mysql.bat` |
| Redis | 5.0    | `C:\Users\lsy1\redis`        | 6379               | `redis\start-redis.bat`      | `redis\stop-redis.bat`      |
| Kafka | 4.3.1  | `C:\Users\lsy1\kafka`        | 9092 / 9093        | `kafka\start-kafka.bat`      | `kafka\stop-kafka.bat`      |

- **MySQL**：`root / 123456`，数据目录 `C:\Users\lsy1\mysql-data`，配置 `C:\Users\lsy1\mysql-8.0.29\my.ini`（`innodb_buffer_pool_size=128M`、`performance_schema=OFF`、`max_connections=50`）
- **Redis**：无密码，`bind 127.0.0.1`，`appendonly no`（纯内存，重启丢数据）
- **Kafka**：KRaft 单节点（broker + controller），数据目录 `C:\Users\lsy1\kafka-data`，堆 `-Xmx256M`。**首次使用或删过 `kafka-data` 后，先执行一次 `kafka\init-kafka.bat` 格式化存储**，再 `start-kafka.bat`

后文统一用这些客户端/工具：

- MySQL 客户端：`C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe -uroot -p123456`
- Redis 客户端：`C:\Users\lsy1\redis\redis-cli.exe`
- Kafka 工具：`C:\Users\lsy1\kafka\bin\windows\*.bat`

> `docker-compose.yml` 仍保留作为**可选的容器化方案**，但当前实验默认走本机安装版。

## 快速开始

```powershell
# 1. 启动中间件（三个独立进程；Kafka 首次先跑 init-kafka.bat）
C:\Users\lsy1\mysql-8.0.29\start-mysql.bat
C:\Users\lsy1\redis\start-redis.bat
C:\Users\lsy1\kafka\start-kafka.bat

# 2. 首次初始化数据库（建库建表 + 示例数据；会 DROP/CREATE 全部表）
C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe -uroot -p123456 -e "source src/main/resources/db/schema.sql"

# 3. 启动应用（默认 dev 环境，连接本机 3306 / 6379 / 9092）
.\mvnw.cmd spring-boot:run        # Windows
./mvnw spring-boot:run            # macOS / Linux

# 4. 验证
curl http://localhost:8081/api/goods
```

`schema.sql` 会创建 6 张核心表（`user` / `goods` / `seckill_activity` / `seckill_stock` / `seckill_order` / `stock_log`）并插入 1 条示例商品 + 活动 + 库存。

> 数据持久化在 `C:\Users\lsy1\mysql-data`；需要重置表结构时，重跑上面第 2 步的 `schema.sql` 即可（脚本内含 `DROP TABLE`）。

## 目录结构

```
seckill-lab
├── docker-compose.yml                 # （可选）MySQL + Redis + Kafka 容器化方案
├── pom.xml
└── src/main
    ├── java/com/lsy/seckill
    │   ├── SeckillLabApplication.java # 启动类
    │   ├── common
    │   │   ├── result                 # Result 统一响应 + ResultCode 业务码
    │   │   └── exception              # BusinessException + 全局异常处理
    │   ├── config
    │   │   ├── MybatisPlusConfig.java # 分页插件
    │   │   └── RedisConfig.java       # RedisTemplate JSON 序列化
    │   └── modules
    │       └── goods                  # 示例模块：controller/service/mapper/entity/dto
    └── resources
        ├── application.yml            # 公共配置
        ├── application-dev.yml        # 开发环境（本地中间件）
        ├── application-prod.yml       # 生产环境（环境变量注入）
        ├── db/schema.sql              # 阶段1 六表 DDL + 示例数据
        ├── db/seed-users.sql          # 10 万压测用户造数
        ├── db/reset-stock.sql         # 压测前重置库存/订单/流水
        ├── db/reconcile.sql           # 对账 SQL
        └── mapper/GoodsMapper.xml     # MyBatis XML 示例
```

## 接口示例

```powershell
# 分页查询商品
curl "http://localhost:8081/api/goods?pageNum=1&pageSize=10"

# 按名称搜索
curl "http://localhost:8081/api/goods?name=示例"

# 商品详情
curl http://localhost:8081/api/goods/1

# 新增商品
curl -X POST http://localhost:8081/api/goods `
  -H "Content-Type: application/json" `
  -d '{"name":"新商品","price":9.9}'
```

统一响应格式：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { "...": "..." }
}
```

## 分层约定

- 所有接口返回 `Result<T>`，业务失败抛 `BusinessException`，由 `GlobalExceptionHandler` 统一转换
- 业务码集中在 `ResultCode`：通用码对齐 HTTP 语义，1xxx 秒杀业务，2xxx 商品业务
- Mapper 接口标注 `@Mapper` 即可被自动扫描；复杂 SQL 写 XML，单表 CRUD 用 `BaseMapper`
- 每个业务模块放在 `modules/<业务名>` 下，包含 `controller / service / mapper / entity / dto`

## 后续实验建议（秒杀演进路线）

1. **Redis 预扣库存**：商品库存预热到 Redis，用 Lua 脚本保证原子扣减
2. **限流**：网关 / 过滤器层实现令牌桶或滑动窗口，削减峰值流量
3. **防重复下单**：`userId + seckillGoodsId` 唯一约束 + Redis setnx
4. **Kafka 异步下单**：Redis 扣减成功后发送消息，消费者异步创建订单，实现削峰填谷
5. **压测对比**：JMeter 对比「直连数据库」与「Redis + MQ」两版方案的 QPS 和超卖情况

## Spring Boot 4 注意事项

- **Jackson 3**：默认 JSON 库为 `tools.jackson`。
  - Kafka value 序列化器请用 `JacksonJsonSerializer` / `JacksonJsonDeserializer`（不要用旧的 `JsonSerializer`，其依赖 Jackson 2 且未随 Boot 4 传递）
  - Redis value 序列化器请用 `GenericJacksonJsonRedisSerializer`（`GenericJackson2JsonRedisSerializer` 为 Jackson 2 版本，已不推荐）
- **starter 拆分**：`spring-boot-starter-web` 变为 `spring-boot-starter-webmvc`，测试依赖带 `-test` 后缀
- 本地安装了 Maven 3.6.1 时请勿直接使用 `mvn`，`mvnw` 已锁定兼容版本
