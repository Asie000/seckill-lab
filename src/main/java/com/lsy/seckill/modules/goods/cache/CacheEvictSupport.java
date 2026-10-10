package com.lsy.seckill.modules.goods.cache;

import com.lsy.seckill.config.KafkaTopicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存删除 + 失败补偿（r12）
 *
 * <p>更新数据库后删缓存：成功即返回；**失败则发 Kafka 消息**，由消费者重试删除，
 * 配合缓存 TTL 兜底，保证「最终一致」。
 *
 * <p>{@code seckill.cache.evict.simulate-fail} 用于**模拟**删除失败（演示补偿流程用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictSupport {

    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${seckill.cache.evict.simulate-fail:false}")
    private volatile boolean simulateFail;

    @Value("${seckill.cache.evict.consumer-sleep-ms:0}")
    private volatile long consumerSleepMs;

    @Value("${seckill.cache.goods.enabled:true}")
    private boolean cacheEnabled;

    public boolean isSimulateFail() {
        return simulateFail;
    }

    public void setSimulateFail(boolean v) {
        this.simulateFail = v;
    }

    /** 消费者删除前的延迟（演示“补偿前的脏读窗口”用） */
    public long getConsumerSleepMs() {
        return consumerSleepMs;
    }

    /** 删除缓存；失败则发 Kafka 补偿 */
    public void evictWithCompensation(String key) {
        try {
            if (simulateFail) {
                throw new IllegalStateException("模拟 Redis 删除失败");
            }
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[缓存补偿] 删除失败，发 Kafka 补偿 key={} 原因={}", key, e.getMessage());
            kafkaTemplate.send(KafkaTopicConfig.CACHE_EVICT_TOPIC, key, new CacheEvictMessage(key));
        }
    }
}
