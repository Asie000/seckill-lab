package com.lsy.seckill.modules.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsy.seckill.modules.seckill.entity.StockLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 库存流水 Mapper
 */
@Mapper
public interface StockLogMapper extends BaseMapper<StockLog> {

    /**
     * ★ r11-opt：批量插入库存流水（一条多值 INSERT，替代逐条 insert）
     */
    @Insert("<script>INSERT INTO stock_log(activity_id, user_id, order_no, change_type, quantity, remark) VALUES "
            + "<foreach collection='list' item='l' separator=','>"
            + "(#{l.activityId}, #{l.userId}, #{l.orderNo}, #{l.changeType}, #{l.quantity}, #{l.remark})"
            + "</foreach></script>")
    int insertBatch(@Param("list") List<StockLog> list);
}
