package com.lsy.seckill.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一订单表（普通单 + 秒杀单，order_type 区分）
 */
@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long userId;

    /** 0-普通单 1-秒杀单 */
    private Integer orderType;

    /** 秒杀活动 ID（仅秒杀单） */
    private Long activityId;

    private BigDecimal totalAmount;

    /** 0-待支付 1-已支付 2-已取消 3-超时关闭 */
    private Integer status;

    private LocalDateTime payTime;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
