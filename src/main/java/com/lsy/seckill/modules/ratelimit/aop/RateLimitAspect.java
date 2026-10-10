package com.lsy.seckill.modules.ratelimit.aop;

import com.lsy.seckill.modules.ratelimit.annotation.RateLimit;
import com.lsy.seckill.modules.ratelimit.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

/**
 * 滑动窗口限流切面（v4-ratelimit）
 *
 * <p>拦截标注 {@link RateLimit} 的方法，执行 {@code limiter.lua}：
 * 用 Redis ZSet 记录窗口内请求时间戳，超出阈值则抛 {@link RateLimitException}。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> LIMITER_SCRIPT;

    static {
        LIMITER_SCRIPT = new DefaultRedisScript<>();
        LIMITER_SCRIPT.setLocation(new ClassPathResource("limiter.lua"));
        LIMITER_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    /** 限流开关：压测时可用 --seckill.ratelimit.enabled=false 做 A/B */
    @Value("${seckill.ratelimit.enabled:true}")
    private boolean enabled;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        if (!enabled) {
            return;
        }
        String key = buildKey(point, rateLimit);
        Long result = stringRedisTemplate.execute(
                LIMITER_SCRIPT,
                List.of(key),
                String.valueOf(rateLimit.window()),
                String.valueOf(rateLimit.limit()),
                String.valueOf(System.currentTimeMillis()),
                UUID.randomUUID().toString());
        if (result == null || result == 0L) {
            throw new RateLimitException(rateLimit.message());
        }
    }

    private String buildKey(JoinPoint point, RateLimit rateLimit) {
        StringBuilder sb = new StringBuilder(rateLimit.key());
        sb.append(':')
                .append(point.getSignature().getDeclaringType().getSimpleName())
                .append('.')
                .append(point.getSignature().getName());
        HttpServletRequest request = currentRequest();
        switch (rateLimit.type()) {
            case IP -> sb.append(":ip:").append(request == null ? "unknown" : clientIp(request));
            case USER -> sb.append(":user:").append(request == null ? "anonymous" : request.getParameter("userId"));
            default -> { /* METHOD：只用类名+方法名 */ }
        }
        return sb.toString();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
