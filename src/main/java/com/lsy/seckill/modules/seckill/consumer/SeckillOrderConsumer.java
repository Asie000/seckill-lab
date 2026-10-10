package com.lsy.seckill.modules.seckill.consumer;

import com.lsy.seckill.config.KafkaTopicConfig;
import com.lsy.seckill.modules.seckill.dto.SeckillMessage;
import com.lsy.seckill.modules.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀订单消费者（v2-kafka）
 *
 * <p>从 Kafka 取出秒杀消息，异步完成「DB 扣库存 + 建订单」。
 *
 * <p>v2 阶段：失败仅记录日志（尚未做重试 / 幂等 / 死信），可靠性留到 v3。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private final SeckillService seckillService;

    @KafkaListener(topics = KafkaTopicConfig.SECKILL_ORDER_TOPIC,
            groupId = "${spring.kafka.consumer.group-id:seckill-lab}")
    public void onMessage(SeckillMessage message) {
        try {
            seckillService.handleSeckillMessage(message);
        } catch (Exception e) {
            log.error("[v2-消费] 落库失败 orderNo={}, 原因={}", message.getOrderNo(), e.getMessage());
        }
    }
}
