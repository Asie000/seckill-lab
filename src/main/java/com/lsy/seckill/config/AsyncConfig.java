package com.lsy.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行配置（r4-async）
 *
 * <p>为下单后的非核心逻辑（短信 / 邮件 / 积分）提供独立线程池，避免阻塞 Tomcat 请求线程。
 *
 * <p>线程池参数：
 * <ul>
 *   <li>core 16 / max 64：按通知类 IO 任务的并发量估算；</li>
 *   <li>queue 500：缓冲队列；</li>
 *   <li>CallerRunsPolicy：队列满时由调用线程直接执行（背压，避免任务丢失）。</li>
 * </ul>
 * 注意：当请求速率持续超过池的消费速率（每个任务 sleep 1s），队列会满、触发 CallerRuns，
 * 此时请求线程会退回"同步执行"，响应时间随之回升——这是异步方案的真实边界。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("orderExecutor")
    public ThreadPoolTaskExecutor orderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
