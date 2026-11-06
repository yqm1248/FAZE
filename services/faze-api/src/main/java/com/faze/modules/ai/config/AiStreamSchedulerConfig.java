/*
 * 职责：AI 流式专用的小型调度器——**只用于单次延迟任务**（首字节超时、断线残留回收）。
 * 调用方：modules/ai/service/AiStreamService（schedule/cancel）。
 * 数据来源：无（进程内线程）。
 * 注意：
 *   1. **为什么不复用 ThreadPoolConfig 的 bizExecutor**：那是「有界队列 + CallerRuns」的
 *      业务线程池，用于执行可排队的任务；本类需要的是「延迟 N 毫秒后只跑一次」的定时器，
 *      往业务池里塞延迟任务会占住线程。也不改用 Spring 的 TaskScheduler 自动配置——
 *      那会受 `spring.task.scheduling.pool-size` 影响，且与 `@Scheduled` 任务抢同一批线程。
 *      这里独立 2 个 daemon 线程，只跑「几十毫秒内返回」的检查任务。
 *   2. 本类**不执行任何 DB / 网络操作**：定时任务只做「标记 + 回调一次」（回调里再由
 *      AiStreamService 决定是否取消上游 / 写库），避免慢 DB 把调度线程占满。
 *   3. 队列用 ScheduledThreadPoolExecutor 内置的 DelayedWorkQueue：它是无界的，但因为
 *      **本类每个请求最多排 2 个短命任务**（首字节检查 + 断线回收），且任务执行后会立即释放，
 *      不存在「任务堆积到 OOM」的路径；`setRemoveOnCancelPolicy(true)` 保证取消的任务立即移除。
 *   4. **返回类型必须是包装类 `AiStreamScheduler`，不能直接返回 `ScheduledExecutorService`**
 *      （2026-11-07 实测的启动事故）：后者会让 Boot 的
 *      `TaskExecutionAutoConfiguration` 的 `@ConditionalOnMissingBean(Executor.class)` 判定为
 *      「已有 Executor」，于是 `applicationTaskExecutor` 不再创建，`ProbeController`
 *      构造注入失败、应用起不来。包装类不实现 `Executor`，不污染该条件。
 *      详见 `AiStreamScheduler` 的类头注释。
 */
package com.faze.modules.ai.config;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 流式延迟任务调度器配置。
 */
@Configuration
public class AiStreamSchedulerConfig {

    /** 调度线程数：2 足够（任务只做标记，不做 IO）。 */
    private static final int POOL_SIZE = 2;

    /**
     * AI 流式延迟任务调度器。
     *
     * @return 包装过的单次延迟任务调度器（daemon 线程，应用关闭时随进程退出）
     */
    @Bean(name = "aiStreamScheduler", destroyMethod = "shutdownNow")
    public AiStreamScheduler aiStreamScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                POOL_SIZE, namedFactory());
        // 取消的任务立即从队列移除：断线重连后旧的超时检查应彻底消失，不能留在队列里
        executor.setRemoveOnCancelPolicy(true);
        // 应用关闭后不再执行已排队的延迟任务
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return new AiStreamScheduler(executor);
    }

    /**
     * 带名字的 daemon 线程工厂。
     *
     * @return 线程工厂
     */
    private ThreadFactory namedFactory() {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "faze-ai-stream-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
