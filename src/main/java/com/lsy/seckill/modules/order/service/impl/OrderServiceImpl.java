package com.lsy.seckill.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import com.lsy.seckill.modules.id.RedisIdWorker;
import com.lsy.seckill.modules.order.dto.OrderCreateRequest;
import com.lsy.seckill.modules.order.entity.Order;
import com.lsy.seckill.modules.order.entity.OrderItem;
import com.lsy.seckill.modules.order.entity.OrderLogistics;
import com.lsy.seckill.modules.order.mapper.OrderItemMapper;
import com.lsy.seckill.modules.order.mapper.OrderLogisticsMapper;
import com.lsy.seckill.modules.order.mapper.OrderMapper;
import com.lsy.seckill.modules.order.service.OrderService;
import com.lsy.seckill.modules.order.support.NotificationService;
import com.lsy.seckill.modules.order.vo.OrderItemVO;
import com.lsy.seckill.modules.order.vo.OrderVO;
import com.lsy.seckill.modules.user.entity.User;
import com.lsy.seckill.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 *
 * <p>r0-baseline：订单列表为典型 N+1（每单 3 次补查，20 行 = 61 次业务查询）。
 * <p>r1-n1：改为批量 IN，整页只 4 次查询（订单 + 用户 + 明细 + 物流）。
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
    private final RedisIdWorker redisIdWorker;

    /** r4-async：是否异步执行非核心逻辑；压测可用 --seckill.order.async.enabled=false 做 A/B */
    @Value("${seckill.order.async.enabled:true}")
    private boolean asyncEnabled;

    @Override
    public IPage<OrderVO> list(Long userId, int pageNum, int pageSize) {
        Page<Order> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(userId != null, Order::getUserId, userId)
                .orderByDesc(Order::getId);
        // 第 1 次查询：订单分页
        IPage<Order> orderPage = orderMapper.selectPage(page, wrapper);

        // ★ r1-n1：批量补查（共 4 次查询）
        List<Order> orders = orderPage.getRecords();
        List<OrderVO> records = orders.isEmpty() ? List.of() : toVoBatch(orders);

        Page<OrderVO> result = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        result.setRecords(records);
        return result;
    }

    /**
     * ★ r1-n1 核心：整页订单只用 4 次查询
     *
     * <p>先收集 ID 集合，再一次 IN 查出用户 / 明细+商品 / 物流，最后在内存里匹配。
     * 替代 r0 的「每条订单 3 次补查」。
     */
    private List<OrderVO> toVoBatch(List<Order> orders) {
        Set<Long> userIds = orders.stream().map(Order::getUserId).collect(Collectors.toSet());
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());

        // 补查 1：用户（1 次）
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // 补查 2：明细 + 商品名（1 次 IN）
        Map<Long, List<OrderItemVO>> itemMap = orderItemMapper.selectByOrderIdsWithGoods(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItemVO::getOrderId));

        // 补查 3：物流（1 次 IN）
        Map<Long, OrderLogistics> logisticsMap = orderLogisticsMapper.selectList(
                        new LambdaQueryWrapper<OrderLogistics>().in(OrderLogistics::getOrderId, orderIds))
                .stream().collect(Collectors.toMap(OrderLogistics::getOrderId, Function.identity(), (a, b) -> a));

        return orders.stream().map(order -> {
            OrderVO vo = new OrderVO();
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderType(order.getOrderType());
            vo.setUserId(order.getUserId());
            vo.setTotalAmount(order.getTotalAmount());
            vo.setStatus(order.getStatus());
            vo.setCreateTime(order.getCreateTime());

            User user = userMap.get(order.getUserId());
            vo.setUserName(user == null ? null : user.getNickname());
            vo.setItems(itemMap.getOrDefault(order.getId(), List.of()));

            OrderLogistics logistics = logisticsMap.get(order.getId());
            vo.setLogisticsStatus(logistics == null ? "无" : logisticsText(logistics.getStatus()));
            return vo;
        }).collect(Collectors.toList());
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
        order.setOrderNo(redisIdWorker.nextOrderNo("order", "N"));
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

        // r4-async：非核心逻辑丢线程池，请求线程立即返回；关闭开关时退回同步（r0~r3 行为）
        if (asyncEnabled) {
            notificationService.afterOrderCreatedAsync(order.getOrderNo());
        } else {
            notificationService.afterOrderCreated(order.getOrderNo());
        }

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
