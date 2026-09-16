package com.lsy.seckill.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 *
 * <p>Mapper 接口标注 {@code @Mapper} 后会被自动扫描注册，无需额外 @MapperScan。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 插件链。后续可按需追加：
     * <ul>
     *   <li>OptimisticLockerInnerInterceptor：@Version 乐观锁（秒杀扣库存常用）</li>
     *   <li>BlockAttackInnerInterceptor：阻止全表更新/删除</li>
     * </ul>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
