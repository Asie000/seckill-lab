package com.lsy.seckill.modules.id;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 分布式 ID 生成器 —— ★ r8-redis-idworker
 *
 * <p>用「时间戳 + Redis 自增」组成 64 位、**趋势递增**的全局唯一 ID：
 *
 * <pre>
 *   id = (当前秒 - 起始秒) << 32  |  Redis 自增序列
 *         └── 高 32 位：秒级时间戳     └── 低 32 位：当天该业务的自增序号
 * </pre>
 *
 * <p>特点：
 * <ul>
 *   <li>全局唯一（同秒内靠 Redis INCR 保证，跨秒靠时间戳）；</li>
 *   <li>趋势递增——高 32 位是时间，利于 MySQL 聚簇索引插入；</li>
 *   <li>性能高（一次 Redis INCR）；每天一个 key，避免计数器无限增长。</li>
 * </ul>
 *
 * <p>对比：UUID 无序且 36 字节，做 MySQL 主键会导致随机插入、页分裂；数据库自增在分布式下不可用。
 */
@Component
@RequiredArgsConstructor
public class RedisIdWorker {

    /** 起始时间戳：2022-01-01 00:00:00 UTC */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    private final StringRedisTemplate redisTemplate;

    public long nextId(String keyPrefix) {
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        String day = LocalDate.now().format(DAY_FMT);
        String key = "icr:" + keyPrefix + ":" + day;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("Redis INCR 返回 null");
        }
        return timestamp << 32 | count;
    }

    /** 生成字符串订单号，如 N-xxxx */
    public String nextOrderNo(String keyPrefix, String prefix) {
        return prefix + nextId(keyPrefix);
    }
}
