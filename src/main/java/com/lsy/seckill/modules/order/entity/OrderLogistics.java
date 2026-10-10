package com.lsy.seckill.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单物流（订单列表展示物流状态）
 */
@Data
@TableName("order_logistics")
public class OrderLogistics {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String company;

    private String trackingNo;

    /** 0-待发货 1-已发货 2-运输中 3-已签收 */
    private Integer status;

    private LocalDateTime createTime;
}
