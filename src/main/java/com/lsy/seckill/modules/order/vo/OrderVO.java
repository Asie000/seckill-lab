package com.lsy.seckill.modules.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单列表视图（需拼接用户 / 商品 / 物流）
 */
@Data
public class OrderVO {

    private String orderNo;

    /** 0-普通单 1-秒杀单 */
    private Integer orderType;

    private Long userId;

    private String userName;

    private BigDecimal totalAmount;

    private Integer status;

    private String logisticsStatus;

    private List<OrderItemVO> items;

    private LocalDateTime createTime;
}
