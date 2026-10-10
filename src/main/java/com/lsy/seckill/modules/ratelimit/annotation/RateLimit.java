package com.lsy.seckill.modules.ratelimit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解（v4-ratelimit）
 *
 * <p>标注在方法上，由 {@code RateLimitAspect} 在调用前执行 Redis 滑动窗口限流。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** key 前缀 */
    String key() default "";

    /** 时间窗口（秒） */
    long window() default 1;

    /** 窗口内允许的最大请求数 */
    long limit() default 100;

    /** 限流维度 */
    LimitType type() default LimitType.METHOD;

    /** 被限流时的提示 */
    String message() default "请求过于频繁，请稍后再试";

    enum LimitType {
        /** 按客户端 IP */
        IP,
        /** 按请求参数 userId */
        USER,
        /** 只按方法（全局） */
        METHOD
    }
}
