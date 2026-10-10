package com.lsy.seckill.modules.order.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 下单后的「非核心逻辑」模拟：短信 / 邮件 / 积分
 *
 * <p>r0-baseline 里同步执行（串行 sleep 共 ~1000ms），
 * r4-async 会把它拆到异步线程池。
 */
@Slf4j
@Service
public class NotificationService {

    /** 模拟发短信耗时 */
    private static final long SMS_MS = 500;
    /** 模拟发邮件耗时 */
    private static final long EMAIL_MS = 300;
    /** 模拟加积分耗时 */
    private static final long POINTS_MS = 200;

    /**
     * 同步执行三个非核心动作（r0 基线：全部阻塞在请求线程里）
     */
    public void afterOrderCreated(String orderNo) {
        sleep(SMS_MS);
        log.info("[通知] 已发送下单短信 orderNo={}", orderNo);

        sleep(EMAIL_MS);
        log.info("[通知] 已发送确认邮件 orderNo={}", orderNo);

        sleep(POINTS_MS);
        log.info("[通知] 已累加用户积分 orderNo={}", orderNo);
    }

    /**
     * 异步执行（r4-async）：丢到 orderExecutor 线程池，立即返回
     */
    @Async("orderExecutor")
    public void afterOrderCreatedAsync(String orderNo) {
        afterOrderCreated(orderNo);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
