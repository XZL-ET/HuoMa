package com.bookstore.qrcode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 * <p>
 * 定义项目中使用的所有异步执行器（Executor），
 * 包括回调事件消费线程池和通用异步任务线程池。
 * 每个线程池独立配置核心线程数、最大线程数、队列容量、线程名前缀及拒绝策略，
 * 以隔离不同业务场景的资源占用，防止相互影响。
 * </p>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
@Configuration
public class AsyncConfig {

    /**
     * 企业微信回调事件消费线程池 — 专供 Worker 引擎消费 Stream 消息使用。
     * <p>
     * <b>线程池参数说明：</b>
     * <ul>
     *   <li>corePoolSize = 4  &mdash; 核心线程数，常驻 4 个线程处理回调</li>
     *   <li>maxPoolSize = 8  &mdash; 最大线程数，突发流量时最多扩展到 8 个线程</li>
     *   <li>queueCapacity = 5000  &mdash; 阻塞队列容量，最多缓存 5000 个待处理任务，
     *       回调事件量级较大，需要较大队列缓冲</li>
     *   <li>threadNamePrefix = "callback-"  &mdash; 线程名前缀，方便日志定位和问题排查</li>
     *   <li>CallerRunsPolicy  &mdash; 拒绝策略：当线程池和队列都满时，
     *       由提交任务的线程（主线程）直接执行，降低回调接收速度，
     *       实现背压效果，防止消息丢失</li>
     * </ul>
     * </p>
     *
     * @return 回调事件消费线程池
     */
    @Bean("callbackExecutor")
    public Executor callbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("callback-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 通用异步任务线程池 — 用于批量导⼊、后台数据同步、报表生成等非回调类任务。
     * <p>
     * <b>线程池参数说明：</b>
     * <ul>
     *   <li>corePoolSize = 2  &mdash; 核心线程数，通用任务不频繁，2 个常驻线程足够</li>
     *   <li>maxPoolSize = 4  &mdash; 最大线程数，允许少量并发提升吞吐</li>
     *   <li>queueCapacity = 1000  &mdash; 队列容量适中，避免任务堆积过多消耗内存</li>
     *   <li>threadNamePrefix = "async-"  &mdash; 线程名前缀，便于区分日志来源</li>
     *   <li>CallerRunsPolicy  &mdash; 拒绝策略由调用线程直接运行，保证任务不丢失</li>
     * </ul>
     * </p>
     *
     * @return 通用异步任务线程池
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
