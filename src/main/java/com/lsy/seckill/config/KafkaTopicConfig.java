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

    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(SECKILL_ORDER_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
