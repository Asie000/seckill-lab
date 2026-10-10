package com.lsy.seckill.modules.order.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细视图（含商品名）
 */
@Data
public class OrderItemVO {

    /** 所属订单 ID（批量查询后按此分组） */
    private Long orderId;

    private Long goodsId;

    private String goodsName;

    private Integer qty;

    private BigDecimal price;
}
