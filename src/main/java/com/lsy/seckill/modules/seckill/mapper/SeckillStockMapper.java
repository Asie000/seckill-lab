package com.lsy.seckill.modules.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsy.seckill.modules.seckill.entity.SeckillStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀库存 Mapper
 */
@Mapper
public interface SeckillStockMapper extends BaseMapper<SeckillStock> {

    /**
     * ★ v0a-naive：无保护扣减
     *
     * <p>缺少 {@code AND stock >= 1} 条件，库存会被扣成负数（超卖）。
     * v0b-atomic 会补上该条件。
     */
    @Update("UPDATE seckill_stock SET stock = stock - 1 WHERE activity_id = #{activityId}")
    int deductNaive(@Param("activityId") Long activityId);

    /**
     * v0b-atomic：原子扣减（WHERE stock >= 1 防超卖）
     */
    @Update("UPDATE seckill_stock SET stock = stock - 1 WHERE activity_id = #{activityId} AND stock >= 1")
    int deductAtomic(@Param("activityId") Long activityId);

    /**
     * v3：回补库存（超时关单）
     */
    @Update("UPDATE seckill_stock SET stock = stock + 1 WHERE activity_id = #{activityId}")
    int restoreStock(@Param("activityId") Long activityId);

    /**
     * ★ r9-add-stock：运营补货（活动进行中加库存）
     */
    @Update("UPDATE seckill_stock SET stock = stock + #{qty} WHERE activity_id = #{activityId}")
    int addStock(@Param("activityId") Long activityId, @Param("qty") int qty);

    /** 查询当前库存（对账用） */
    @Select("SELECT stock FROM seckill_stock WHERE activity_id = #{activityId}")
    Integer selectStock(@Param("activityId") Long activityId);
}
