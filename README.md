# seckill-lab

秒杀实验室 —— 基于 **Spring Boot 4 + MyBatis-Plus + Redis + Kafka** 的项目模板，用于学习/实验高并发秒杀场景。

## 技术栈

| 组件        | 版本   | 说明                                            |
| ----------- | ------ | ----------------------------------------------- |
| JDK         | 17     | LTS                                             |
| Spring Boot | 4.1.1  | 默认使用 **Jackson 3**（`tools.jackson`）       |
| MyBatis-Plus| 3.5.17 | 使用 `mybatis-plus-spring-boot4-starter`        |
| MySQL       | 8.4    | Docker 启动，自动执行 `schema.sql`              |
| Redis       | 7      | Docker 启动                                     |
| Kafka       | 4.3.1  | Docker 启动，单节点 KRaft（无 ZooKeeper）       |
| Maven       | Wrapper| `mvnw` 自带 3.9.x，无需本地安装 Maven           |

## 环境要求

- JDK 17+
- Docker Desktop（用于一键启动 MySQL / Redis / Kafka）
- 不需要本地安装 Maven，直接用项目自带的 `mvnw`

## 快速开始

```powershell
# 1. 启动中间件（首次会自动执行建库建表脚本）
docker compose up -d

# 2. 启动应用（默认 dev 环境，连接本机 3306/6379/9092）
.\mvnw.cmd spring-boot:run        # Windows
./mvnw spring-boot:run            # macOS / Linux

# 3. 验证
curl http://localhost:8080/api/goods
```

`schema.sql` 已插入 2 条示例商品，启动后即可查询。

> MySQL 初始化脚本仅在数据卷为空（首次启动）时执行；如需重跑：
> `docker compose down -v` 后重新 `docker compose up -d`。

## 目录结构

```
seckill-lab
├── docker-compose.yml                 # MySQL + Redis + Kafka 一键环境
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
        ├── db/schema.sql              # 建库建表 + 示例数据
        └── mapper/GoodsMapper.xml     # MyBatis XML 示例
```

## 接口示例

```powershell
# 分页查询商品
curl "http://localhost:8080/api/goods?pageNum=1&pageSize=10"

# 按名称搜索
curl "http://localhost:8080/api/goods?name=示例"

# 商品详情
curl http://localhost:8080/api/goods/1

# 新增商品
curl -X POST http://localhost:8080/api/goods `
  -H "Content-Type: application/json" `
  -d '{"name":"新商品","price":9.9,"stock":100}'

# 扣减库存（演示原子扣减）
curl -X POST "http://localhost:8080/api/goods/1/stock/deduct?quantity=1"
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
