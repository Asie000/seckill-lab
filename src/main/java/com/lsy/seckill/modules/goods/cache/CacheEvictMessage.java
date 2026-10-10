package com.lsy.seckill.modules.goods.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 缓存删除补偿消息（r12）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheEvictMessage {
    private String key;
}
