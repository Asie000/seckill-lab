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
}
