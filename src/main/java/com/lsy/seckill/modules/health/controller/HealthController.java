package com.lsy.seckill.modules.health.controller;

import com.lsy.seckill.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 健康检查接口
 *
 * <p>对三个中间件各做一次轻量探测，任一不可用即整体标记为 DOWN，但接口本身仍返回
 * {@code code=200}（探测结果放在 data 里），便于压测流程第 7 步做"链路是否通"的预检。
 *
 * <p>这是唯一一个"一次性写好、7 个版本都用"的接口，不参与秒杀业务演进。
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    /** 单个组件探测的超时时间（毫秒），避免中间件不可用时把健康检查拖住 */
    private static final int PROBE_TIMEOUT_MS = 2000;

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final KafkaAdmin kafkaAdmin;

    @GetMapping
    public Result<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("mysql", probeMysql());
        components.put("redis", probeRedis());
        components.put("kafka", probeKafka());

        boolean allUp = components.values().stream().allMatch("UP"::equals);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", allUp ? "UP" : "DOWN");
        data.putAll(components);
        return Result.ok(data);
    }

    /** MySQL：从连接池取一条连接并校验有效性 */
    private String probeMysql() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("健康检查 - MySQL 不可用: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** Redis：PING 一次 */
    private String probeRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("健康检查 - Redis 不可用: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** Kafka：用带超时的 AdminClient 查一次集群 ID（不复用 KafkaAdmin，避免默认 30s 超时） */
    private String probeKafka() {
        Map<String, Object> adminProps = new LinkedHashMap<>(kafkaAdmin.getConfigurationProperties());
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, PROBE_TIMEOUT_MS);
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, PROBE_TIMEOUT_MS);
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            String clusterId = adminClient.describeCluster().clusterId().get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return clusterId != null ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("健康检查 - Kafka 不可用: {}", e.getMessage());
            return "DOWN";
        }
    }
}
