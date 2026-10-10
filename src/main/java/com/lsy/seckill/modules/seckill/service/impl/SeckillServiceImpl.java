package com.lsy.seckill.modules.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
import com.lsy.seckill.config.KafkaTopicConfig;
import com.lsy.seckill.modules.order.entity.Order;
import com.lsy.seckill.modules.order.entity.OrderItem;
import com.lsy.seckill.modules.order.mapper.OrderItemMapper;
import com.lsy.seckill.modules.order.mapper.OrderMapper;
import com.lsy.seckill.modules.id.RedisIdWorker;
import com.lsy.seckill.modules.seckill.dto.SeckillMessage;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀服务实现 —— ★ v1-redis-lua
 *
 * <p>主链路：
 * <ol>
 *   <li>活动信息读 Redis（hash），避免每请求查 DB；</li>
 *   <li><b>Redis + Lua 原子预扣</b>：库存判断 + 一人一单 + 扣减一次性完成，返回值 0/1/2；</li>
 *   <li>通过后落库：DB 原子扣减 + 写订单/明细/流水；</li>
 *   <li><b>双兜底</b>（{@code seckill.fallback} 切换）：
 *       <ul>
 *         <li>{@code index}（默认）：依赖数据库唯一索引 {@code uk_activity_user}；</li>
 *         <li>{@code lock}：Redis SETNX 分布式锁锁住 {@code 活动+用户}。</li>
 *       </ul>
 *   </li>
 * </ol>
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
    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisIdWorker redisIdWorker;

    /** 兜底方案：index=唯一索引 / lock=Redis 分布式锁 */
    @Value("${seckill.fallback:index}")
    private String fallback;

    /** v2-kafka：Lua 通过后是否只发 Kafka、由消费者异步落库 */
    @Value("${seckill.kafka.enabled:true}")
    private boolean kafkaEnabled;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /** 活动快照（从 Redis 读取，避免每请求查 DB） */
    private record ActivitySnapshot(Long goodsId, BigDecimal seckillPrice, long startMillis, long endMillis) {
    }

    // ---------- key 约定 ----------
    private String stockKey(Long activityId) {
        return "seckill:stock:" + activityId;
    }

    private String orderKey(Long activityId) {
        return "seckill:order:" + activityId;
    }

    private String activityKey(Long activityId) {
        return "seckill:activity:" + activityId;
    }

    private String lockKey(Long activityId, Long userId) {
        return "lock:seckill:" + activityId + ":" + userId;
    }

    /** 库存 + 活动信息预热；压测前必须调用 */
    @Override
    public void warmUp(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "秒杀活动不存在");
        }
        List<SeckillStock> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<SeckillStock>().eq(SeckillStock::getActivityId, activityId));
        int stock = stocks.isEmpty() ? 0 : stocks.get(0).getStock();
        stringRedisTemplate.opsForValue().set(stockKey(activityId), String.valueOf(stock));
        stringRedisTemplate.delete(orderKey(activityId));
        cacheActivity(activity);
        log.info("[预热] activityId={} 库存={} 活动信息已写入 Redis", activityId, stock);
    }

    private void cacheActivity(SeckillActivity a) {
        Map<String, String> m = new HashMap<>();
        m.put("goodsId", String.valueOf(a.getGoodsId()));
        m.put("seckillPrice", a.getSeckillPrice().toPlainString());
        m.put("startMillis", String.valueOf(toMillis(a.getStartTime())));
        m.put("endMillis", String.valueOf(toMillis(a.getEndTime())));
        stringRedisTemplate.opsForHash().putAll(activityKey(a.getId()), m);
    }

    private ActivitySnapshot loadActivity(Long activityId) {
        Map<Object, Object> h = stringRedisTemplate.opsForHash().entries(activityKey(activityId));
        if (!h.isEmpty()) {
            return new ActivitySnapshot(
                    Long.valueOf(h.get("goodsId").toString()),
                    new BigDecimal(h.get("seckillPrice").toString()),
                    Long.parseLong(h.get("startMillis").toString()),
                    Long.parseLong(h.get("endMillis").toString()));
        }
        // 缓存未命中：回源 DB 并回填
        SeckillActivity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "秒杀活动不存在");
        }
        cacheActivity(a);
        return new ActivitySnapshot(a.getGoodsId(), a.getSeckillPrice(),
                toMillis(a.getStartTime()), toMillis(a.getEndTime()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String seckill(Long activityId, Long userId) {
        // 1. 活动信息（走 Redis，不查 DB）
        ActivitySnapshot snap = loadActivity(activityId);
        long now = System.currentTimeMillis();
        if (now < snap.startMillis()) {
            throw new BusinessException(ResultCode.SECKILL_NOT_START);
        }
        if (now > snap.endMillis()) {
            throw new BusinessException(ResultCode.SECKILL_ENDED);
        }

        // 2. Redis + Lua 原子预扣
        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(stockKey(activityId), orderKey(activityId)),
                String.valueOf(userId));
        if (r == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "秒杀脚本执行失败");
        }
        if (r == 1L) {
            throw new BusinessException(ResultCode.SECKILL_STOCK_EMPTY);
        }
        if (r == 2L) {
            throw new BusinessException(ResultCode.SECKILL_REPEAT);
        }

        // 3. 通过：v2 发 Kafka 异步落库；否则同步落库（v1 行为）
        String orderNo = redisIdWorker.nextOrderNo("seckill", "S");
        if (kafkaEnabled) {
            try {
                // 发送失败回调：回滚 Redis 并报错，避免"静默丢单"
                kafkaTemplate.send(KafkaTopicConfig.SECKILL_ORDER_TOPIC, orderNo,
                                new SeckillMessage(activityId, userId, orderNo))
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("[v2-发送] Kafka 异步发送失败，回滚 Redis orderNo={}, 原因={}", orderNo, ex.getMessage());
                                safeCompensate(activityId, userId, orderNo);
                            }
                        });
                return orderNo;
            } catch (Exception e) {
                // 同步失败（如拿不到 metadata）：回滚 Redis 并显式报错
                log.error("[v2-发送] Kafka 同步发送失败，回滚 Redis orderNo={}, 原因={}", orderNo, e.getMessage());
                safeCompensate(activityId, userId, orderNo);
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "秒杀下单失败，请稍后重试");
            }
        }
        return persist(activityId, userId, snap, orderNo);
    }

    /**
     * 消费 Kafka 消息后的异步落库（v2）。
     *
     * <p>异常向上抛（由消费者捕获记录），使 @Transactional 能正常回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillMessage(SeckillMessage message) {
        ActivitySnapshot snap = loadActivity(message.getActivityId());
        persist(message.getActivityId(), message.getUserId(), snap, message.getOrderNo());
    }

    private String persist(Long activityId, Long userId, ActivitySnapshot snap, String orderNo) {
        if ("lock".equalsIgnoreCase(fallback)) {
            String key = lockKey(activityId, userId);
            Boolean locked = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(5));
            if (!Boolean.TRUE.equals(locked)) {
                throw new BusinessException(ResultCode.SECKILL_REPEAT);
            }
            try {
                doPersist(activityId, userId, snap, orderNo);
            } finally {
                stringRedisTemplate.delete(key);
            }
        }
        doPersist(activityId, userId, snap, orderNo);
        return orderNo;
    }

    private void doPersist(Long activityId, Long userId, ActivitySnapshot snap, String orderNo) {
        int rows = stockMapper.deductAtomic(activityId);
        if (rows == 0) {
            compensateRedis(activityId, userId);
            throw new BusinessException(ResultCode.SECKILL_STOCK_EMPTY);
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setOrderType(1);
        order.setActivityId(activityId);
        order.setTotalAmount(snap.seckillPrice());
        order.setStatus(0);
        try {
            orderMapper.insert(order);   // ★ 唯一索引兜底
        } catch (DuplicateKeyException e) {
            compensateRedis(activityId, userId);
            throw new BusinessException(ResultCode.SECKILL_REPEAT);
        }

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setGoodsId(snap.goodsId());
        item.setQty(1);
        item.setPrice(snap.seckillPrice());
        orderItemMapper.insert(item);

        List<SeckillStock> stocks = stockMapper.selectList(
                new LambdaQueryWrapper<SeckillStock>().eq(SeckillStock::getActivityId, activityId));
        SeckillStock stock = stocks.isEmpty() ? null : stocks.get(0);

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
    }

    /**
     * 支付（v3）：条件更新实现乐观锁，并发支付只有 1 次能把 0 -> 1。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(String orderNo) {
        int rows = orderMapper.markPaid(orderNo);
        if (rows == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单不存在或已支付/已关闭");
        }
    }

    /**
     * 关闭超时未支付订单并回补库存（v3）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeTimeoutOrders(Long activityId, long timeoutSeconds) {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(timeoutSeconds);
        List<Order> pendings = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getActivityId, activityId)
                .eq(Order::getOrderType, 1)
                .eq(Order::getStatus, 0)
                .le(Order::getCreateTime, deadline)
                .last("LIMIT 200"));
        int closed = 0;
        for (Order o : pendings) {
            // 条件更新：只有仍为「待支付」才关闭（防止与支付并发打架）
            if (orderMapper.markClosed(o.getId()) == 1) {
                stockMapper.restoreStock(activityId);
                stringRedisTemplate.opsForValue().increment(stockKey(activityId));
                StockLog log = new StockLog();
                log.setActivityId(activityId);
                log.setUserId(o.getUserId());
                log.setOrderNo(o.getOrderNo());
                log.setChangeType(1);   // +1 回补
                log.setQuantity(1);
                stockLogMapper.insert(log);
                closed++;
            }
        }
        return closed;
    }

    private void safeCompensate(Long activityId, Long userId, String orderNo) {
        try {
            compensateRedis(activityId, userId);
        } catch (Exception e) {
            log.error("[v2-发送] 回滚 Redis 失败 orderNo={}", orderNo, e);
        }
    }

    private void compensateRedis(Long activityId, Long userId) {
        stringRedisTemplate.opsForValue().increment(stockKey(activityId));
        stringRedisTemplate.opsForSet().remove(orderKey(activityId), String.valueOf(userId));
    }

    private static long toMillis(LocalDateTime t) {
        return t == null ? 0L : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
