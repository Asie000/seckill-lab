package com.lsy.seckill.modules.seckill.service;

import com.lsy.seckill.modules.seckill.dto.SeckillMessage;

/**
 * 秒杀服务（Phase S 写侧）
 */
public interface SeckillService {

    /**
     * 秒杀下单
     *
     * @param activityId 活动 ID
     * @param userId     用户 ID
     * @return 订单号
     */
    String seckill(Long activityId, Long userId);

    /**
     * 库存预热：把 DB 库存写入 Redis 并清空已下单集合（v1 起压测前必须调用）
     */
    void warmUp(Long activityId);

    /**
     * 消费 Kafka 消息，异步落库（v2-kafka）
     */
    void handleSeckillMessage(SeckillMessage message);

    /**
     * 支付（v3）：乐观锁式条件更新，并发支付只 1 次成功
     */
    void pay(String orderNo);

    /**
     * 关闭超时未支付订单并回补库存（v3）
     *
     * @return 关闭的订单数
     */
    int closeTimeoutOrders(Long activityId, long timeoutSeconds);
}
