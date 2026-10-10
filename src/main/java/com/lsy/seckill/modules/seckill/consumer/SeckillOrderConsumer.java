package com.lsy.seckill.modules.seckill.consumer;

import com.lsy.seckill.config.KafkaTopicConfig;
import com.lsy.seckill.modules.seckill.dto.SeckillMessage;
import com.lsy.seckill.modules.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 秒杀订单消费者（v3-consistency）
 *
 * <p>相比 v2 增加了两点可靠性：
 * <ol>
 *   <li><b>幂等</b>：以 orderNo 为去重键，同一消息重复投递只落库一次；</li>
 *   <li><b>手动 ack</b>：处理成功才 ack；失败不 ack（删掉幂等键后抛出），由 errorHandler 重试。</li>
 * </ol>
 * 数据库唯一索引仍是最后一道防线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderConsumer {

    private static final String IDEMPOTENT_PREFIX = "seckill:consumed:";

    private final SeckillService seckillService;
    private final StringRedisTemplate stringRedisTemplate;

    @KafkaListener(topics = KafkaTopicConfig.SECKILL_ORDER_TOPIC,
            groupId = "${spring.kafka.consumer.group-id:seckill-lab}")
    public void onMessage(SeckillMessage message, Acknowledgment ack) {
        String idemKey = IDEMPOTENT_PREFIX + message.getOrderNo();

        // 幂等：同一 orderNo 只处理一次
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(idemKey, "1", Duration.ofHours(2));
        if (!Boolean.TRUE.equals(first)) {
            log.info("[v3-消费] 重复消息，跳过 orderNo={}", message.getOrderNo());
            ack.acknowledge();
            return;
        }

        try {
            seckillService.handleSeckillMessage(message);
            ack.acknowledge();   // ★ 成功才确认
        } catch (Exception e) {
            // 失败：删掉幂等键，允许重试，且不 ack
            stringRedisTemplate.delete(idemKey);
            log.error("[v3-消费] 落库失败，不 ack 待重试 orderNo={}, 原因={}", message.getOrderNo(), e.getMessage());
            throw e;
        }
    }
}
