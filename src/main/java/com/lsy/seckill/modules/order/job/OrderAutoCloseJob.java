package com.lsy.seckill.modules.order.job;

import com.lsy.seckill.modules.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 未支付订单到期自动关闭 —— ★ r11-order-autoclose（Spring Task 定时任务）
 *
 * <p>对应「黑马点评」文档方案一：**每分钟扫表**（`status=0 且 create_time 超时`），
 * 依赖联合索引 `idx_status_create(status, create_time)`。关闭成功才回补库存。
 *
 * <p>多实例部署时用 Redis 锁保证同一时刻只有一个实例在扫（否则会重复扫、白耗资源；
 * 关闭本身的正确性由 `markClosed` 的 `WHERE status=0` 条件更新兜底，不会重复回补库存）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderAutoCloseJob {

    private static final String LOCK_KEY = "lock:order:autoclose";

    private final SeckillService seckillService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${seckill.order.autoclose.enabled:true}")
    private boolean enabled;

    @Value("${seckill.order.autoclose.timeout-seconds:900}")
    private long timeoutSeconds;

    @Value("${seckill.order.autoclose.batch-size:200}")
    private int batchSize;

    @Value("${seckill.order.autoclose.batch-restock:true}")
    private boolean batchRestock;

    @Value("${seckill.order.autoclose.lock-ttl-seconds:30}")
    private long lockTtlSeconds;

    /** 间隔扫描（fixedDelay：上次执行完再等 interval） */
    @Scheduled(fixedDelayString = "${seckill.order.autoclose.interval-ms:60000}")
    public void autoClose() {
        if (!enabled) {
            return;
        }
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            int closed = seckillService.closeTimeoutOrdersAll(timeoutSeconds, batchSize, batchRestock);
            long cost = System.currentTimeMillis() - start;
            if (closed > 0) {
                log.info("[定时关单] 关闭 {} 笔超时未支付订单，耗时 {}ms", closed, cost);
            }
        } catch (Exception e) {
            log.error("[定时关单] 执行失败", e);
        } finally {
            stringRedisTemplate.delete(LOCK_KEY);
        }
    }
}
