package com.lsy.seckill.modules.goods.cache;

import com.lsy.seckill.config.KafkaTopicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 缓存删除补偿消费者 —— ★ r12-cache-evict-compensation
 *
 * <p>更新数据库后删缓存；若删失败，生产者会把 key 发到 {@code cache-evict}，
 * 这里负责重试删除。删除仍失败则**不 ack**，由 Kafka 重试；重试耗尽进死信队列（r9 的
 * KafkaErrorHandlingConfig 已全局生效）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictConsumer {

    private final StringRedisTemplate redisTemplate;
    private final CacheEvictSupport support;

    @KafkaListener(topics = KafkaTopicConfig.CACHE_EVICT_TOPIC, groupId = "cache-evict")
    public void onMessage(CacheEvictMessage msg, Acknowledgment ack) {
        // 生产端可能在上游“删除失败”，这里负责真正重试删除
        long sleep = support.getConsumerSleepMs();
        if (sleep > 0) {
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        redisTemplate.delete(msg.getKey());
        ack.acknowledge();
        log.info("[缓存补偿] 补偿删除成功 key={}", msg.getKey());
    }
}
