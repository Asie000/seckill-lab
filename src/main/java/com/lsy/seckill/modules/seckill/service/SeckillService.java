package com.lsy.seckill.modules.seckill.service;

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
}
