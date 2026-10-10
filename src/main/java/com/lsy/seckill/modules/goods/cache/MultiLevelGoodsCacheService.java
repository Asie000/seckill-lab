package com.lsy.seckill.modules.goods.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多级缓存 —— ★ r6-multilevel-cache：Caffeine(L1) + Redis(L2) + DB(L3)
 *
 * <p>读路径：L1 命中直接返回（不走网络）；L1 未命中查 L2，命中则回填 L1；
 * L2 也未命中才查 DB（回填 L2）。
 *
 * <p>开关 {@code seckill.cache.multilevel.enabled}：
 * <ul>
 *   <li>{@code true}（默认）：Caffeine + Redis，L1 命中省掉 Redis 网络往返；</li>
 *   <li>{@code false}：仅 Redis（等价 r3 的单级缓存），用于对比。</li>
 * </ul>
 *
 * <p>{@code l1.get(id, mappingFunction)} 对同一 key 是**单飞**的（只一个线程回源），
 * 顺带避免了击穿。
 *
 * <p><b>一致性说明</b>：更新时需同时失效 L1 与 L2。单实例下本地 {@code invalidate} 即可；
 * 多实例部署时，其他实例的 L1 仍是旧值，需借助 Redis 发布订阅 / 版本号广播失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiLevelGoodsCacheService {

    private static final String KEY_PREFIX = "ml:goods:";

    private final GoodsMapper goodsMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${seckill.cache.multilevel.enabled:true}")
    private boolean enabled;

    @Value("${seckill.cache.multilevel.l1-ttl-seconds:5}")
    private long l1TtlSeconds;

    @Value("${seckill.cache.multilevel.l2-ttl-seconds:30}")
    private long l2TtlSeconds;

    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong dbLoads = new AtomicLong();

    private volatile Cache<Long, Goods> l1;

    @PostConstruct
    void init() {
        l1 = buildL1();
    }

    private Cache<Long, Goods> buildL1() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(l1TtlSeconds))
                .recordStats()
                .build();
    }

    public boolean enabled() {
        return enabled;
    }

    public Goods get(Long id) {
        if (!enabled) {
            return redisThenDb(id);
        }
        // L1 单飞：同一 key 只一个线程执行 loader
        return l1.get(id, this::redisThenDb);
    }

    private Goods redisThenDb(Long id) {
        String key = KEY_PREFIX + id;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            l2Hits.incrementAndGet();
            return decode(id, cached);
        }
        dbLoads.incrementAndGet();
        Goods goods = goodsMapper.selectById(id);
        if (goods != null) {
            redisTemplate.opsForValue().set(key, encode(goods), Duration.ofSeconds(l2TtlSeconds));
        }
        return goods;
    }

    /** 更新商品后调用：同时失效 L1 与 L2。 */
    public void invalidate(Long id) {
        if (l1 != null) {
            l1.invalidate(id);
        }
        redisTemplate.delete(KEY_PREFIX + id);
    }

    public Map<String, Object> stats() {
        CacheStats cs = l1.stats();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled);
        data.put("l1Hits", cs.hitCount());
        data.put("l1Misses", cs.missCount());
        data.put("l1HitRate", String.format("%.4f", cs.hitRate()));
        data.put("l2Hits", l2Hits.get());
        data.put("dbLoads", dbLoads.get());
        return data;
    }

    public synchronized void resetStats() {
        l1 = buildL1();
        l2Hits.set(0);
        dbLoads.set(0);
    }

    // ---------- 序列化 ----------
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
