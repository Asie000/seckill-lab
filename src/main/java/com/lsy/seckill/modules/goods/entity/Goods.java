package com.lsy.seckill.modules.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品
 *
 * <p>表结构见 src/main/resources/db/schema.sql
 */
@Data
@TableName("goods")
public class Goods {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商品名称 */
    private String name;

    /** 价格（元） */
    private BigDecimal price;

    /** 状态：1-上架 0-下架 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（由数据库自动更新） */
    private LocalDateTime updateTime;
}
