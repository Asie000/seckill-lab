package com.lsy.seckill.modules.seckill.controller;

import com.lsy.seckill.common.result.Result;
import com.lsy.seckill.modules.ratelimit.annotation.RateLimit;
import com.lsy.seckill.modules.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接口（Phase S 写侧主链路）
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    /** 秒杀下单（userId 请求参数直传，无鉴权）
     *
     * <p>v4-ratelimit：入口按 IP 做滑动窗口限流（500 次/秒）。
     */
    @RateLimit(key = "seckill", window = 1, limit = 500,
            type = RateLimit.LimitType.IP, message = "秒杀请求过于频繁，请稍后再试")
    @PostMapping("/{activityId}")
    public Result<String> seckill(@PathVariable Long activityId, @RequestParam Long userId) {
        return Result.ok(seckillService.seckill(activityId, userId));
    }

    /** 库存预热（v1 起，压测前调用） */
    @PostMapping("/{activityId}/warmup")
    public Result<Void> warmUp(@PathVariable Long activityId) {
        seckillService.warmUp(activityId);
        return Result.ok();
    }

    /** 支付（v3）：并发支付只 1 次成功 */
    @PostMapping("/pay/{orderNo}")
    public Result<Void> pay(@PathVariable String orderNo) {
        seckillService.pay(orderNo);
        return Result.ok();
    }

    /** 关闭超时未支付订单并回补库存（v3） */
    @PostMapping("/{activityId}/close-timeout")
    public Result<Integer> closeTimeout(@PathVariable Long activityId,
                                        @RequestParam(defaultValue = "60") long timeoutSeconds) {
        return Result.ok(seckillService.closeTimeoutOrders(activityId, timeoutSeconds));
    }

    /** ★ r9-add-stock：运营补货（活动进行中加库存，带幂等 requestId） */
    @PostMapping("/{activityId}/add-stock")
    public Result<java.util.Map<String, Object>> addStock(@PathVariable Long activityId,
                                                          @RequestParam int qty,
                                                          @RequestParam String requestId) {
        return Result.ok(seckillService.addStock(activityId, qty, requestId));
    }

    /** r9：查询 DB 与 Redis 当前库存（对账） */
    @org.springframework.web.bind.annotation.GetMapping("/{activityId}/stock")
    public Result<java.util.Map<String, Object>> stock(@PathVariable Long activityId) {
        return Result.ok(seckillService.stockInfo(activityId));
    }
}
