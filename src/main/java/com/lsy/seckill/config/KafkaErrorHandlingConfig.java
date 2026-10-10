package com.lsy.seckill.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费错误处理 —— ★ r9：毒消息不再永久阻塞分区
 *
 * <p>背景：消费者手动 ack，失败就不 ack（seek 回退重试）。若某条消息**永远处理失败**
 * （如 DB 唯一索引冲突导致的 SECKILL_REPEAT），它会无限重试、把整个分区卡死，
 * 后续消息全部无法消费。实测就踩到了这个坑。
 *
 * <p>方案：接入 {@link DefaultErrorHandler}：
 * <ul>
 *   <li>对失败记录重试 {@code N} 次（间隔 1s）；</li>
 *   <li>仍失败则用 {@link DeadLetterPublishingRecoverer} 投递到死信主题
 *       {@code seckill-order-dlq}，然后提交 offset —— 分区得以继续。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // 投递到死信队列（同分区）
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopicConfig.SECKILL_ORDER_DLQ_TOPIC, record.partition()));
        // 重试 2 次（间隔 200ms），仍失败 -> 死信
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        handler.setCommitRecovered(true);
        return handler;
    }
}
