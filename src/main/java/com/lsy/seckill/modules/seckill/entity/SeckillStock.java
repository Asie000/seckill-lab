package com.lsy.seckill.modules.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀库存（写热点，独立窄表）
 */
@Data
@TableName("seckill_stock")
public class SeckillStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long activityId;

    /** 剩余库存（写热点字段） */
    private Integer stock;

    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
