package com.lsy.seckill.modules.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
import com.lsy.seckill.modules.order.entity.Order;
import com.lsy.seckill.modules.order.entity.OrderItem;
import com.lsy.seckill.modules.order.mapper.OrderItemMapper;
import com.lsy.seckill.modules.order.mapper.OrderMapper;
import com.lsy.seckill.modules.seckill.entity.SeckillActivity;
import com.lsy.seckill.modules.seckill.entity.SeckillStock;
import com.lsy.seckill.modules.seckill.entity.StockLog;
import com.lsy.seckill.modules.seckill.mapper.SeckillActivityMapper;
import com.lsy.seckill.modules.seckill.mapper.SeckillStockMapper;
import com.lsy.seckill.modules.seckill.mapper.StockLogMapper;
import com.lsy.seckill.modules.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 秒杀服务实现 —— ★ v0a-naive 基线
 *
 * <p>刻意留下的两个病灶（见设计文档 §5）：
 * <ol>
 *   <li><b>超卖</b>：扣库存用 {@link SeckillStockMapper#deductNaive}，无 {@code stock >= 1} 保护；</li>
 *   <li><b>重复下单</b>：没有任何幂等/去重，同一用户可以抢多次。</li>
 * </ol>
 *
 * <p>v0b-atomic 会把扣减换成 {@link SeckillStockMapper#deductAtomic}（只治超卖）；
 * v1-redis-lua 才同时治两个病。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillActivityMapper activityMapper;
    private final SeckillStockMapper stockMapper;
    private final StockLogMapper stockLogMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /** v0a / v0b 切换：true=原子扣减(防超卖)，false=朴素扣减(会超卖) */
    @Value("${seckill.deduct.atomic:false}")
    private boolean atomicDeduct;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String seckill(Long activityId, Long userId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "秒杀活动不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BusinessException(ResultCode.SECKILL_NOT_START);
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BusinessException(ResultCode.SECKILL_ENDED);
        }

        // ★ 扣库存：v0a 朴素（会超卖）/ v0b 原子（防超卖）
        if (atomicDeduct) {
            int rows = stockMapper.deductAtomic(activityId);
            if (rows == 0) {
                throw new BusinessException(ResultCode.SECKILL_STOCK_EMPTY);
            }
        } else {
            stockMapper.deductNaive(activityId);
        }

        // 读取扣减后库存（仅供流水记录；并发下 before/after 可能不自洽，属 v0 预期症状）
        List<SeckillStock> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<SeckillStock>().eq(SeckillStock::getActivityId, activityId));
        SeckillStock stock = stocks.isEmpty() ? null : stocks.get(0);

        // ★ 建订单：无任何幂等保护 → 同一用户可重复下单
        String orderNo = "S" + UUID.randomUUID().toString().replace("-", "");
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setOrderType(1);
        order.setActivityId(activityId);
        order.setTotalAmount(activity.getSeckillPrice());
        order.setStatus(0);
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setGoodsId(activity.getGoodsId());
        item.setQty(1);
        item.setPrice(activity.getSeckillPrice());
        orderItemMapper.insert(item);

        StockLog stockLog = new StockLog();
        stockLog.setActivityId(activityId);
        stockLog.setUserId(userId);
        stockLog.setOrderNo(orderNo);
        stockLog.setChangeType(-1);
        stockLog.setQuantity(1);
        if (stock != null) {
            stockLog.setStockAfter(stock.getStock());
            stockLog.setStockBefore(stock.getStock() + 1);
        }
        stockLogMapper.insert(stockLog);

        return orderNo;
    }
}
