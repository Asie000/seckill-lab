package com.lsy.seckill.modules.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * kafka-lab 演示消息（r10）
 *
 * <p>{@code key} 是「逻辑键」：即使 Kafka 消息键为 null，也用它来判断是否乱序。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LabMessage {
    private String key;
    private long seq;
}
