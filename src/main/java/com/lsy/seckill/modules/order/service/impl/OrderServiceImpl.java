package com.lsy.seckill.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import com.lsy.seckill.modules.order.dto.OrderCreateRequest;
import com.lsy.seckill.modules.order.entity.Order;
import com.lsy.seckill.modules.order.entity.OrderItem;
import com.lsy.seckill.modules.order.entity.OrderLogistics;
import com.lsy.seckill.modules.order.mapper.OrderItemMapper;
import com.lsy.seckill.modules.order.mapper.OrderLogisticsMapper;
import com.lsy.seckill.modules.order.mapper.OrderMapper;
import com.lsy.seckill.modules.order.service.OrderService;
import com.lsy.seckill.modules.order.support.NotificationService;
import com.lsy.seckill.modules.order.vo.OrderVO;
import com.lsy.seckill.modules.user.entity.User;
import com.lsy.seckill.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 订单服务实现 —— ★ r0-baseline：订单列表为典型 N+1
 *
 * <p>列表查 N 条订单后，对每条订单再发 3 次查询：
 * 用户、明细+商品、物流。20 条 = 1 + 3*20 = 61 次数据库调用。
 * 这是刻意留下的「新手写法」，r1-n1 会改为批量 IN 查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderLogisticsMapper orderLogisticsMapper;
    private final UserMapper userMapper;
    private final GoodsMapper goodsMapper;
    private final NotificationService notificationService;

    @Override
    public IPage<OrderVO> list(Long userId, int pageNum, int pageSize) {
        Page<Order> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(userId != null, Order::getUserId, userId)
                .orderByDesc(Order::getId);
        // 第 1 次查询：订单分页
        IPage<Order> orderPage = orderMapper.selectPage(page, wrapper);

        // ★ N+1：对每条订单各补查 3 次
        List<OrderVO> records = orderPage.getRecords().stream()
                .map(this::toVoNaive)
                .collect(Collectors.toList());

        Page<OrderVO> result = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public OrderVO detail(String orderNo) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return toVoNaive(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(OrderCreateRequest request) {
        Goods goods = goodsMapper.selectById(request.getGoodsId());
        if (goods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        int qty = request.getQty() == null ? 1 : request.getQty();
        BigDecimal amount = goods.getPrice().multiply(BigDecimal.valueOf(qty));

        Order order = new Order();
        order.setOrderNo("N" + System.currentTimeMillis()
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        order.setUserId(request.getUserId());
        order.setOrderType(0);
        order.setTotalAmount(amount);
        order.setStatus(0);
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setGoodsId(goods.getId());
        item.setQty(qty);
        item.setPrice(goods.getPrice());
        orderItemMapper.insert(item);

        OrderLogistics logistics = new OrderLogistics();
        logistics.setOrderId(order.getId());
        logistics.setCompany("顺丰");
        logistics.setTrackingNo("SF" + order.getId());
        logistics.setStatus(0);
        orderLogisticsMapper.insert(logistics);

        // ★ r0：非核心逻辑同步执行，阻塞请求线程约 1 秒
        notificationService.afterOrderCreated(order.getOrderNo());

        return order.getOrderNo();
    }

    /**
     * ★ N+1 核心：每条订单 3 次补查
     */
    private OrderVO toVoNaive(Order order) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOrderType(order.getOrderType());
        vo.setUserId(order.getUserId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setCreateTime(order.getCreateTime());

        // 补查 1：用户
        User user = userMapper.selectById(order.getUserId());
        vo.setUserName(user == null ? null : user.getNickname());

        // 补查 2：明细 + 商品名
        vo.setItems(orderItemMapper.selectByOrderIdWithGoods(order.getId()));

        // 补查 3：物流
        List<OrderLogistics> logisticsList = orderLogisticsMapper.selectList(
                new LambdaQueryWrapper<OrderLogistics>().eq(OrderLogistics::getOrderId, order.getId()));
        vo.setLogisticsStatus(logisticsList.isEmpty() ? "无" : logisticsText(logisticsList.get(0).getStatus()));
        return vo;
    }

    private String logisticsText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待发货";
            case 1 -> "已发货";
            case 2 -> "运输中";
            case 3 -> "已签收";
            default -> "未知";
        };
    }
}
