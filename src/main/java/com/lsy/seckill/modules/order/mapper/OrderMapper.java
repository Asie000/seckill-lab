package com.lsy.seckill.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsy.seckill.modules.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订单 Mapper
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 支付（v3 乐观锁式条件更新）：仅当状态为「待支付」时才成功
     *
     * @return 影响行数，0 表示已被支付或已关闭
     */
    @Update("UPDATE orders SET status = 1, pay_time = NOW() WHERE order_no = #{orderNo} AND status = 0")
    int markPaid(@Param("orderNo") String orderNo);

    /**
     * 关闭超时单：仅当状态为「待支付」时才成功
     *
     * @return 影响行数
     */
    @Update("UPDATE orders SET status = 3 WHERE id = #{id} AND status = 0")
    int markClosed(@Param("id") Long id);
}
