package com.lsy.seckill.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 定义（v2-kafka）
 */
@Configuration
public class KafkaTopicConfig {

    public static final String SECKILL_ORDER_TOPIC = "seckill-order";
    public static final String SECKILL_ORDER_DLQ_TOPIC = "seckill-order-dlq";
    public static final String CACHE_EVICT_TOPIC = "cache-evict";

    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(SECKILL_ORDER_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /** 死信队列：重试耗尽后把毒消息投递到此处，避免永久阻塞分区 */
    @Bean
    public NewTopic seckillOrderDlqTopic() {
        return TopicBuilder.name(SECKILL_ORDER_DLQ_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /** r10-kafka-lab：3 分区（配合 3 个消费者线程演示顺序/堆积） */
    @Bean
    public NewTopic labMsgTopic() {
        return TopicBuilder.name("lab-msg")
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** r12：缓存删除补偿 topic */
    @Bean
    public NewTopic cacheEvictTopic() {
        return TopicBuilder.name(CACHE_EVICT_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
