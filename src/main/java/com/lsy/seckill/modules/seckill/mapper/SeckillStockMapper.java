package com.lsy.seckill.modules.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsy.seckill.modules.seckill.entity.SeckillStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}
