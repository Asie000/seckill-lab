package com.lsy.seckill.modules.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单消息（Kafka 传输）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage implements Serializable {

    private Long activityId;

    private Long userId;

    /** 请求线程预生成的订单号，消费者按此落库 */
    private String orderNo;
}
