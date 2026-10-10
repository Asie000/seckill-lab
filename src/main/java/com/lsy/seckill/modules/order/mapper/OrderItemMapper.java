package com.lsy.seckill.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lsy.seckill.modules.order.entity.OrderItem;
import com.lsy.seckill.modules.order.vo.OrderItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单明细 Mapper
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 按订单查明细（关联商品名）
     *
     * <p>r0-baseline 里被「每条订单调一次」，是 N+1 的三次补查之一。
     */
    List<OrderItemVO> selectByOrderIdWithGoods(@Param("orderId") Long orderId);
}
