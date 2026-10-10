package com.lsy.seckill.modules.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存变动流水（对账 / 回补的证据链）
 */
@Data
@TableName("stock_log")
public class StockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    private Long userId;

    private String orderNo;

    /** -1 扣减，+1 回补 */
    private Integer changeType;

    /** 变动数量（恒为正） */
    private Integer quantity;

    private Integer stockBefore;

    private Integer stockAfter;

    private String remark;

    private LocalDateTime createTime;
}
