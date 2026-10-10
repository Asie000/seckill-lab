package com.lsy.seckill.modules.order.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lsy.seckill.modules.order.dto.OrderCreateRequest;
import com.lsy.seckill.modules.order.vo.OrderVO;

/**
 * 订单服务（Phase R 读侧）
 */
public interface OrderService {

    /**
     * 订单列表（r0 为 N+1 实现）
     *
     * @param userId 用户 ID，可为空（为空则查全部，便于后台列表压测）
     */
    IPage<OrderVO> list(Long userId, int pageNum, int pageSize);

    /** 订单详情 */
    OrderVO detail(String orderNo);

    /** 普通下单，返回订单号 */
    String create(OrderCreateRequest request);
}
