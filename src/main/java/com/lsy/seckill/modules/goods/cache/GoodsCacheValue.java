package com.lsy.seckill.modules.goods.cache;

import com.lsy.seckill.modules.goods.entity.Goods;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 商品缓存值对象
 *
 * <p>刻意不复用 {@link Goods}：时间字段用 epoch 毫秒存储，避免 Redis JSON
 * 序列化对 LocalDateTime 的兼容问题（Boot 4 / Jackson 3）。
 */
@Data
public class GoodsCacheValue {

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer status;
    private Long createTime;
    private Long updateTime;

    public static GoodsCacheValue of(Goods g) {
        GoodsCacheValue v = new GoodsCacheValue();
        v.setId(g.getId());
        v.setName(g.getName());
        v.setPrice(g.getPrice());
        v.setStatus(g.getStatus());
        v.setCreateTime(toMillis(g.getCreateTime()));
        v.setUpdateTime(toMillis(g.getUpdateTime()));
        return v;
    }

    public Goods toGoods() {
        Goods g = new Goods();
        g.setId(id);
        g.setName(name);
        g.setPrice(price);
        g.setStatus(status);
        g.setCreateTime(toDateTime(createTime));
        g.setUpdateTime(toDateTime(updateTime));
        return g;
    }

    private static Long toMillis(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static LocalDateTime toDateTime(Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
