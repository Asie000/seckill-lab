package com.lsy.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置：key 用字符串，value 用 JSON
 *
 * <p>Boot 4 默认使用 Jackson 3，因此这里采用
 * {@link GenericJacksonJsonRedisSerializer}（Jackson 2 版本已不推荐）。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = StringRedisSerializer.UTF_8;
        // enableUnsafeDefaultTyping 会在 JSON 中写入 @class 类型信息，
        // 从而保证“存什么类型、取回什么类型”。
        // 注意：仅适合学习/内网环境；生产环境建议使用带白名单校验的
        // enableDefaultTyping(PolymorphicTypeValidator) 或直接存 JSON 字符串。
        GenericJacksonJsonRedisSerializer valueSerializer = GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        return template;
    }
}
