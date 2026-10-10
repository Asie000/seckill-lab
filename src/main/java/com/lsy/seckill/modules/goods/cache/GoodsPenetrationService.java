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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存穿透演示 —— ★ r7-bloom-filter：三种策略
 *
 * <p>缓存穿透 = 查询**数据库里也不存在**的 key（如伪造 id=999999），
 * 缓存永远不命中，每次都打到 DB，可被用来打垮数据库。
 *
 * <p>模式（{@code seckill.cache.penetration}）：
 * <ul>
 *   <li>{@code none}：无防护，直接查库 —— 每个请求都打 DB（穿透）；</li>
 *   <li>{@code null}：缓存空值 —— 不存在也缓存 "NULL"（短 TTL），后续命中；</li>
 *   <li>{@code bloom}：布隆过滤器 —— 判定不存在直接返回，根本不查库。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsPenetrationService {

    private static final String KEY_PREFIX = "pen:goods:";
    private static final String NULL_MARK = "NULL";

    private final GoodsMapper goodsMapper;
    private final StringRedisTemplate redisTemplate;
    private final GoodsBloomFilter bloomFilter;

    @Value("${seckill.cache.penetration:none}")
    private String mode;

    @Value("${seckill.cache.penetration.ttl-seconds:60}")
    private long ttlSeconds;

    private final AtomicLong dbLoads = new AtomicLong();
    private final AtomicLong bloomRejects = new AtomicLong();

    public String mode() {
        return mode;
    }

    public long dbLoads() {
        return dbLoads.get();
    }

    public long bloomRejects() {
        return bloomRejects.get();
    }

    public void resetStats() {
        dbLoads.set(0);
        bloomRejects.set(0);
    }

    public Goods get(Long id) {
        return switch (mode.toLowerCase()) {
            case "null" -> getWithNullCache(id);
            case "bloom" -> getWithBloom(id);
            default -> getNaive(id);
        };
    }

    // ---------- none：无防护，直接查库 ----------
    private Goods getNaive(Long id) {
        dbLoads.incrementAndGet();
        return goodsMapper.selectById(id);
    }

    // ---------- null：缓存空值 ----------
    private Goods getWithNullCache(Long id) {
        String key = KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return NULL_MARK.equals(cached) ? null : decode(id, cached);
        }
        dbLoads.incrementAndGet();
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            redisTemplate.opsForValue().set(key, NULL_MARK, Duration.ofSeconds(ttlSeconds));
        } else {
            redisTemplate.opsForValue().set(key, encode(goods), Duration.ofSeconds(ttlSeconds));
        }
        return goods;
    }

    // ---------- bloom：布隆过滤器 ----------
    private Goods getWithBloom(Long id) {
        if (!bloomFilter.mightContain(id)) {
            bloomRejects.incrementAndGet();
            return null;
        }
        String key = KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return NULL_MARK.equals(cached) ? null : decode(id, cached);
        }
        dbLoads.incrementAndGet();
        Goods goods = goodsMapper.selectById(id);
        if (goods != null) {
            redisTemplate.opsForValue().set(key, encode(goods), Duration.ofSeconds(ttlSeconds));
        }
        return goods;
    }

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
}
