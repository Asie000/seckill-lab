package com.lsy.seckill.modules.goods.cache;

import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热 key 缓存服务 —— ★ r5-cache-breakdown：三种防击穿策略
 *
 * <p>通过 {@code seckill.cache.breakdown} 切换：
 * <ul>
 *   <li>{@code off}（默认）：无保护 cache-aside，缓存过期瞬间大量请求同时打 DB（击穿）；</li>
 *   <li>{@code mutex}：互斥锁——只放一个线程回源建缓存，其余短暂休眠重试；</li>
 *   <li>{@code logical}：逻辑过期——缓存不设物理 TTL，读到逻辑过期时返回旧值 + 异步重建。</li>
 * </ul>
 *
 * <p>为便于观察击穿，回源 DB 会故意 sleep {@code seckill.cache.hot.delay-ms}（模拟慢查询）；
 * 用 {@code dbLoads} 计数统计真实回源次数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotGoodsService {

    private static final String KEY_PREFIX = "hot:goods:";
    private static final String LOCK_PREFIX = "hot:goods:lock:";

    private final GoodsMapper goodsMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${seckill.cache.breakdown:off}")
    private String mode;

    @Value("${seckill.cache.hot.delay-ms:200}")
    private long delayMs;

    @Value("${seckill.cache.hot.ttl-seconds:2}")
    private long ttlSeconds;

    private final AtomicLong dbLoads = new AtomicLong();
    private final ExecutorService rebuildPool = Executors.newFixedThreadPool(4);

    public String mode() {
        return mode;
    }

    public long dbLoads() {
        return dbLoads.get();
    }

    public void resetStats() {
        dbLoads.set(0);
    }

    /** 清除热 key 缓存（压测前冷启动用） */
    public void evict(Long id) {
        redisTemplate.delete(KEY_PREFIX + id);
    }

    public Goods get(Long id) {
        return switch (mode.toLowerCase()) {
            case "mutex" -> getWithMutex(id);
            case "logical" -> getWithLogicalExpire(id);
            default -> getNaive(id);
        };
    }

    // ---------- off：无保护 ----------
    private Goods getNaive(Long id) {
        String key = KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return decode(id, cached);
        }
        Goods goods = loadFromDb(id);
        if (goods != null) {
            redisTemplate.opsForValue().set(key, encode(goods), Duration.ofSeconds(ttlSeconds));
        }
        return goods;
    }

    // ---------- mutex：互斥锁 ----------
    private Goods getWithMutex(Long id) {
        String key = KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return decode(id, cached);
        }
        String lockKey = LOCK_PREFIX + id;
        if (tryLock(lockKey)) {
            try {
                // 双重检查
                cached = redisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return decode(id, cached);
                }
                Goods goods = loadFromDb(id);
                if (goods != null) {
                    redisTemplate.opsForValue().set(key, encode(goods), Duration.ofSeconds(ttlSeconds));
                }
                return goods;
            } finally {
                unlock(lockKey);
            }
        }
        // 没抢到锁：短暂休眠后重试读缓存
        for (int i = 0; i < 20; i++) {
            sleep(50);
            cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return decode(id, cached);
            }
        }
        return loadFromDb(id);
    }

    // ---------- logical：逻辑过期 ----------
    private Goods getWithLogicalExpire(Long id) {
        String key = KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null) {
            Goods goods = loadFromDb(id);
            if (goods != null) {
                setLogical(key, goods);
            }
            return goods;
        }
        // 格式：过期时间戳 | 值
        int sep = cached.indexOf('|');
        long expireAt = Long.parseLong(cached.substring(0, sep));
        String value = cached.substring(sep + 1);
        if (System.currentTimeMillis() < expireAt) {
            return decode(id, value);
        }
        // 已逻辑过期：抢锁 -> 异步重建，当前请求返回旧值
        String lockKey = LOCK_PREFIX + id;
        if (tryLock(lockKey)) {
            rebuildPool.submit(() -> {
                try {
                    Goods fresh = loadFromDb(id);
                    if (fresh != null) {
                        setLogical(key, fresh);
                    }
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return decode(id, value);
    }

    private void setLogical(String key, Goods goods) {
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        // 物理不过期：给个较大的 TTL 兜底（如 1 小时）
        redisTemplate.opsForValue().set(key, expireAt + "|" + encode(goods), Duration.ofHours(1));
    }

    // ---------- 回源（故意慢查询，便于观察击穿） ----------
    private Goods loadFromDb(Long id) {
        dbLoads.incrementAndGet();
        sleep(delayMs);
        return goodsMapper.selectById(id);
    }

    // ---------- 序列化：id 已知，只存 name\tprice\tstatus ----------
    private String encode(Goods g) {
        return g.getName() + "\t" + g.getPrice().toPlainString() + "\t" + g.getStatus();
    }

    private Goods decode(Long id, String s) {
        String[] p = s.split("\t", 3);
        Goods g = new Goods();
        g.setId(id);
        g.setName(p[0]);
        g.setPrice(new BigDecimal(p[1]));
        g.setStatus(Integer.valueOf(p[2]));
        return g;
    }

    private boolean tryLock(String key) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(10)));
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
