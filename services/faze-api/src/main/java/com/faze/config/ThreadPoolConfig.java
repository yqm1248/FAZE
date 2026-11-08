/*
 * 职责：定时任务调度器与业务线程池的显式配置（《后端代码规范》§9.3 / §9.4）。
 * 调用方：Spring 容器；`@Scheduled` 任务（G2.4 的 OrderCloseTask 起）与后续段 6/7 的推送任务。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. **必须显式配调度线程数**：Spring 默认 TaskScheduler 只有 1 个线程，
 *      所有 `@Scheduled` 串行。一个慢任务（如退款重试）会把扫单任务一起饿死，
 *      表现是「订单不自动关闭了」，但日志里没有任何异常。
 *   2. **调度线程池与业务线程池分开**：`taskScheduler` 只负责触发，具体耗时活儿丢给
 *      `bizExecutor`。合并成一个池会让长任务占住调度线程，后续触发全部延后。
 *   3. `bizExecutor` 的**队列必须有界**，拒绝策略必须显式（CallerRuns）：
 *      无界队列会让 `maxPoolSize` 永远不生效（核心线程满 → 入队 → 队列永不满），
 *      任务堆积到 OOM（wgcloud 的 ThreadPoolUtil 就是这个反例）。
 *      **调度器本身不再自定义 bean**：改用 `spring.task.scheduling.pool-size`（见 application.yml）。
 *      自定义 `ThreadPoolTaskScheduler` 会与 Spring Boot 的自动配置各注册一个 TaskExecutor，
 *      导致 `ProbeController` 注入 `TaskExecutor` 时报「找到了 2 个候选」而**启动失败**（2026-11-06 实测）。
 *   4. `@EnableScheduling` **放在主启动类 FazeApiApplication**（集中一处）。2026-11-06 实测：
 *      放在本类时 `@Scheduled` 静默不执行、日志里没有任何报错；移到主类后恢复。
 */

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 线程池与定时任务配置。
 */
@Configuration
public class ThreadPoolConfig {

    /** 业务线程池核心线程数。 */
    private static final int BIZ_CORE_SIZE = 8;

    /** 业务线程池最大线程数。 */
    private static final int BIZ_MAX_SIZE = 32;

    /** 业务线程池队列容量（有界，满了走 CallerRuns）。 */
    private static final int BIZ_QUEUE_CAPACITY = 500;

    /**
     * 业务线程池（异步任务的实际执行者）。
     *
     * @return 有界队列 + CallerRuns 拒绝策略的线程池
     */
    @Bean("bizExecutor")
    public ThreadPoolExecutor bizExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                BIZ_CORE_SIZE,
                BIZ_MAX_SIZE,
                60,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(BIZ_QUEUE_CAPACITY),
                namedFactory("faze-biz-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 生成带前缀的线程工厂（不引入 guava，保持依赖清单不变）。
     *
     * @param prefix 线程名前缀
     * @return 线程工厂
     */
    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
