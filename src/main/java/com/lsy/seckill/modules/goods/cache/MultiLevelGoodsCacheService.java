package com.lsy.seckill.modules.goods.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
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

    @Value("${seckill.cache.multilevel.l1-max-size:1000}")
    private long l1MaxSize;

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
                .maximumSize(l1MaxSize)
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
        data.put("l1MaxSize", l1MaxSize);
        data.put("l1EstimatedSize", l1.estimatedSize());
        data.put("l1Evictions", cs.evictionCount());
        long policyMax = l1.policy().eviction().map(e -> e.getMaximum()).orElse(-1L);
        data.put("l1PolicyMax", policyMax);
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

    /**
     * ★ 复现多级缓存一致性竞态：L1 未命中 -> 读库拿到旧值 -> 并发更新+失效 -> 旧值才写入 L1
     *
     * <pre>
     * t0   reader L1 miss，loader 从 DB 读到 old
     * t50  writer 改 DB=new，失效 L1+L2
     * t300 reader 把旧值 put 进 L1        <-- 竞态：L1 留下旧值
     * => DB=new，L1=old，不一致，直到 L1 TTL 到期
     * </pre>
     */
    public Map<String, Object> simulateMlRace(Long id) throws InterruptedException {
        invalidate(id);
        Goods oldGoods = goodsMapper.selectById(id);
        if (oldGoods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        BigDecimal oldPrice = oldGoods.getPrice();
        BigDecimal newPrice = oldPrice.add(BigDecimal.ONE);

        // reader：模拟 L1 miss 后的 loader —— 读到旧值，停顿，再写入 L1
        Thread reader = new Thread(() -> {
            Goods db = goodsMapper.selectById(id);
            sleep(300);
            l1.put(id, db);
        }, "ml-race-reader");
        reader.start();

        sleep(50);
        // writer：改库 + 失效两级缓存
        updatePrice(oldGoods, newPrice);
        invalidate(id);

        reader.join();

        Goods fromL1 = l1.getIfPresent(id);
        Goods dbNow = goodsMapper.selectById(id);
        BigDecimal l1Price = fromL1 == null ? null : fromL1.getPrice();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbPrice", dbNow.getPrice());
        result.put("l1Price", l1Price);
        result.put("stale", l1Price != null && l1Price.compareTo(dbNow.getPrice()) != 0);
        result.put("desc", "多级缓存 naive 失效：并发回填把旧值写进 L1，L1 与 DB 不一致");
        return result;
    }

    /**
     * ★ 修复对照：更新后「延迟再失效一次」，把并发回填进 L1 的旧值删掉。
     */
    public Map<String, Object> simulateMlRaceFixed(Long id) throws InterruptedException {
        invalidate(id);
        Goods oldGoods = goodsMapper.selectById(id);
        if (oldGoods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        BigDecimal oldPrice = oldGoods.getPrice();
        BigDecimal newPrice = oldPrice.add(BigDecimal.ONE);

        Thread reader = new Thread(() -> {
            Goods db = goodsMapper.selectById(id);
            sleep(300);
            l1.put(id, db);
        }, "ml-race-fixed-reader");
        reader.start();

        sleep(50);
        updatePrice(oldGoods, newPrice);
        invalidate(id);
        // ★ 修复：延迟再失效一次，覆盖 reader 稍后回填的旧值
        delayedInvalidate(id, 500);

        reader.join();
        sleep(700);

        Goods fromL1 = l1.getIfPresent(id);
        // 重新读一次（此时旧值已被第二次失效清掉，会回源到新值）
        Goods after = get(id);
        BigDecimal l1Price = fromL1 == null ? null : fromL1.getPrice();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbPrice", after.getPrice());
        result.put("l1PriceAfterInvalidate", l1Price);
        result.put("readPrice", after.getPrice());
        result.put("stale", l1Price != null && l1Price.compareTo(after.getPrice()) != 0);
        result.put("desc", "延迟失效：并发回填的旧值被第二次失效清除，最终一致");
        return result;
    }

    private void updatePrice(Goods oldGoods, BigDecimal newPrice) {
        Goods update = new Goods();
        update.setId(oldGoods.getId());
        update.setName(oldGoods.getName());
        update.setPrice(newPrice);
        update.setStatus(oldGoods.getStatus());
        goodsMapper.updateById(update);
    }

    /** 延迟失效（延迟双删的本地版）：更新后延迟一段时间再失效一次 */
    public void delayedInvalidate(Long id, long delayMillis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            invalidate(id);
            log.info("[多级缓存] 延迟失效完成 goodsId={}", id);
        }, "ml-delayed-invalidate");
        t.setDaemon(true);
        t.start();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
