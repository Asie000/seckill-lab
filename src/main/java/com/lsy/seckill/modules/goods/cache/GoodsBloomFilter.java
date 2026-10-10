package com.lsy.seckill.modules.goods.cache;

import com.lsy.seckill.modules.goods.entity.Goods;
import com.lsy.seckill.modules.goods.mapper.GoodsMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品布隆过滤器 —— ★ r7-bloom-filter：用 Redis bitmap 防缓存穿透
 *
 * <p>把所有已存在的商品 id 用 k 个哈希位标记进一个 Redis bitmap；
 * 查询前先问布隆过滤器："这个 id 可能存在吗？"
 * <ul>
 *   <li>返回 false：**一定不存在** → 直接返回，不查库（挡住穿透）；</li>
 *   <li>返回 true：可能存在（有误判率）→ 继续走缓存/DB。</li>
 * </ul>
 *
 * <p>用 Redis bitmap 而非 JVM 本地 BloomFilter，是为了多实例共享同一份过滤器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsBloomFilter {

    /** 位图 key */
    public static final String KEY = "bloom:goods";

    private final StringRedisTemplate redisTemplate;
    private final GoodsMapper goodsMapper;

    @Value("${seckill.bloom.size:100000}")
    private long bitSize;

    @Value("${seckill.bloom.k:3}")
    private int k;

    public long bitSize() {
        return bitSize;
    }

    public int k() {
        return k;
    }

    /** 启动时用现有商品 id 重建过滤器 */
    @PostConstruct
    public void rebuild() {
        redisTemplate.delete(KEY);
        List<Long> ids = goodsMapper.selectList(null).stream().map(Goods::getId).toList();
        for (Long id : ids) {
            add(id);
        }
        log.info("[布隆] 重建完成：装载 {} 个商品 id，bitSize={}, k={}", ids.size(), bitSize, k);
    }

    public void add(long id) {
        for (long offset : offsets(id)) {
            redisTemplate.opsForValue().setBit(KEY, offset, true);
        }
    }

    /** false = 一定不存在；true = 可能存在（有误判率） */
    public boolean mightContain(long id) {
        for (long offset : offsets(id)) {
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(KEY, offset))) {
                return false;
            }
        }
        return true;
    }

    /** k 个位偏移：h1 + i*h2 取模 */
    private long[] offsets(long id) {
        long h1 = mix64(id);
        long h2 = mix64(id ^ 0x9E3779B97F4A7C15L) | 1L;
        long[] off = new long[k];
        for (int i = 0; i < k; i++) {
            off[i] = Math.floorMod(h1 + i * h2, bitSize);
        }
        return off;
    }

    /** splitmix64：简单的 64 位混合哈希 */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
