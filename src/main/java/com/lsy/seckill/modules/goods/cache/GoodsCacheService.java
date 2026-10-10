package com.lsy.seckill.modules.goods.cache;

import com.lsy.seckill.common.exception.BusinessException;
import com.lsy.seckill.common.result.ResultCode;
import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品缓存服务（r3-cache）
 *
 * <p>缓存模式：Cache-Aside（旁路缓存）——读时先查缓存，未命中回源 DB 并回填；
 * 更新时改 DB 后删除缓存。
 *
 * <p>本类还提供两个「教学用」方法：
 * <ul>
 *   <li>{@link #simulateRace}：复现「删缓存 + 并发回填旧值」导致缓存脏读的竞态；</li>
 *   <li>{@link #simulateRaceFixed}：用「延迟双删」修复后的对照。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsCacheService {

    /** 缓存 key 前缀 */
    private static final String KEY_PREFIX = "cache:goods:";
    /** 缓存过期时间 */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;
    private final GoodsMapper goodsMapper;

    /** 缓存开关：压测时可用 --seckill.cache.goods.enabled=false 做 A/B 对照 */
    @Value("${seckill.cache.goods.enabled:true}")
    private boolean cacheEnabled;

    private String key(Long id) {
        return KEY_PREFIX + id;
    }

    /**
     * 读商品：缓存优先（Cache-Aside）
     */
    public Goods getById(Long id) {
        if (!cacheEnabled) {
            return goodsMapper.selectById(id);
        }
        String key = key(id);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof GoodsCacheValue value) {
            return value.toGoods();
        }
        // 未命中：回源 DB 并回填
        Goods goods = goodsMapper.selectById(id);
        if (goods != null) {
            redisTemplate.opsForValue().set(key, GoodsCacheValue.of(goods), TTL);
        }
        return goods;
    }

    /** 更新后删除缓存（r3 的朴素做法，存在竞态） */
    public void evict(Long id) {
        redisTemplate.delete(key(id));
    }

    /** 延迟双删：更新后先删一次，延迟一段时间再删一次，覆盖并发回填的旧值 */
    public void evictWithDelay(Long id, long delayMillis) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            evict(id);
            log.info("[缓存] 延迟双删完成 goodsId={}", id);
        }, "cache-double-delete");
        t.setDaemon(true);
        t.start();
    }

    /**
     * ★ 复现缓存一致性竞态：删除缓存 + 并发回填旧值
     *
     * <pre>
     * t0   reader 读到旧值 DB=old
     * t50  writer 改 DB=new，删缓存
     * t350 reader 把旧值回填进缓存   <-- 竞态发生在这里
     * => 最终 DB=new，缓存=old，不一致
     * </pre>
     */
    public Map<String, Object> simulateRace(Long id) throws InterruptedException {
        evict(id);
        Goods oldGoods = goodsMapper.selectById(id);
        if (oldGoods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        BigDecimal oldPrice = oldGoods.getPrice();
        BigDecimal newPrice = oldPrice.add(BigDecimal.ONE);

        // reader：读库 -> 停顿 -> 回填缓存（用旧值）
        Thread reader = new Thread(() -> {
            Goods db = goodsMapper.selectById(id);
            sleep(300);
            redisTemplate.opsForValue().set(key(id), GoodsCacheValue.of(db), TTL);
        }, "race-reader");
        reader.start();

        sleep(50);
        // writer：改库并删缓存
        Goods update = new Goods();
        update.setId(id);
        update.setName(oldGoods.getName());
        update.setPrice(newPrice);
        update.setStatus(oldGoods.getStatus());
        goodsMapper.updateById(update);
        evict(id);

        reader.join();

        Goods dbNow = goodsMapper.selectById(id);
        Object cached = redisTemplate.opsForValue().get(key(id));
        BigDecimal cachePrice = cached instanceof GoodsCacheValue v ? v.getPrice() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbPrice", dbNow.getPrice());
        result.put("cachePrice", cachePrice);
        result.put("stale", cachePrice != null && cachePrice.compareTo(dbNow.getPrice()) != 0);
        result.put("desc", "朴素删缓存：并发回填旧值，缓存与 DB 不一致");
        return result;
    }

    /**
     * ★ 延迟双删修复后的对照：同样竞态下，等待延迟删除生效后缓存与 DB 一致。
     */
    public Map<String, Object> simulateRaceFixed(Long id) throws InterruptedException {
        evict(id);
        Goods oldGoods = goodsMapper.selectById(id);
        if (oldGoods == null) {
            throw new BusinessException(ResultCode.GOODS_NOT_FOUND);
        }
        BigDecimal oldPrice = oldGoods.getPrice();
        BigDecimal newPrice = oldPrice.add(BigDecimal.ONE);

        Thread reader = new Thread(() -> {
            Goods db = goodsMapper.selectById(id);
            sleep(300);
            redisTemplate.opsForValue().set(key(id), GoodsCacheValue.of(db), TTL);
        }, "race-fixed-reader");
        reader.start();

        sleep(50);
        Goods update = new Goods();
        update.setId(id);
        update.setName(oldGoods.getName());
        update.setPrice(newPrice);
        update.setStatus(oldGoods.getStatus());
        goodsMapper.updateById(update);
        evict(id);
        // ★ 修复：延迟再删一次，覆盖 reader 稍后回填的旧值
        evictWithDelay(id, 500);

        reader.join();
        // 等延迟删除生效
        sleep(700);
        // 再次读取（缓存已被清空，会回源到新值）
        Goods after = getById(id);
        Object cached = redisTemplate.opsForValue().get(key(id));
        BigDecimal cachePrice = cached instanceof GoodsCacheValue v ? v.getPrice() : null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbPrice", after.getPrice());
        result.put("cachePrice", cachePrice);
        result.put("stale", cachePrice != null && cachePrice.compareTo(after.getPrice()) != 0);
        result.put("desc", "延迟双删：并发的旧值被第二次删除覆盖，最终一致");
        return result;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
