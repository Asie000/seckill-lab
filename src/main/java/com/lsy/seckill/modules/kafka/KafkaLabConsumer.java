package com.lsy.seckill.modules.kafka;

import com.lsy.seckill.modules.kafka.dto.LabMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * kafka-lab 消费者（r10）：3 个并发线程对应 3 个分区。
 *
 * <p>可通过 {@code /api/kafka/slow} 设置每条消息的处理耗时，用来制造**消费堆积**。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaLabConsumer {

    private final KafkaLabService labService;

    @KafkaListener(topics = KafkaLabService.TOPIC, groupId = KafkaLabService.GROUP, concurrency = "3")
    public void onMessage(LabMessage msg, Acknowledgment ack) {
        long ms = labService.slowMs();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        labService.record(msg);
        // 全局 ack-mode=manual，必须显式提交，否则 lag 里 committed 恒为 0
        ack.acknowledge();
    }
}
