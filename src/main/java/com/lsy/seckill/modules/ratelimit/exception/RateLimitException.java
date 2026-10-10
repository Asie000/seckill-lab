package com.lsy.seckill.modules.ratelimit.exception;

/**
 * 触发限流异常（由全局异常处理转为 HTTP 429）
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
