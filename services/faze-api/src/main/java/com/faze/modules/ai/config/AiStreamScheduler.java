/*
 * 职责：AI 流式延迟任务的**调度器包装**（不是线程池本身）——包住一个
 *       `ScheduledThreadPoolExecutor`，只暴露本段需要的三个动作。
 * 调用方：`AiStreamSchedulerConfig`（造它并交给容器）、`AiStreamService`（execute / schedule）。
 * 数据来源：无（进程内线程）。
 * 注意：
 *   1. **为什么要这层包装**（2026-11-07 实测的启动事故）：如果直接把
 *      `ScheduledExecutorService` 注册成 Spring bean，它会命中 Boot 的
 *      `TaskExecutionAutoConfiguration` 上的 `@ConditionalOnMissingBean(Executor.class)`
 *      ——`ScheduledExecutorService` 是 `Executor` 的子类型，条件判定为「已有 Executor」，
 *      于是 Boot **不再创建 `applicationTaskExecutor`**；接着
 *      `infra/probe/ProbeController` 的构造参数
 *      `@Qualifier("applicationTaskExecutor") TaskExecutor` 注入失败，应用启动即失败：
 *      `Parameter 2 of constructor in com.faze.infra.probe.ProbeController required a bean of
 *       type 'org.springframework.core.task.TaskExecutor' that could not be found`。
 *      包装类**不实现 `Executor`**，所以不会污染 Boot 的类型条件。
 *   2. 包装类**不新增线程**：线程池仍由 `AiStreamSchedulerConfig` 创建，
 *      本类只是转发。`destroyMethod = "shutdownNow"` 仍能在容器关闭时收线程。
 *   3. **不许**让本类 `implements Executor` / `extends ThreadPoolExecutor`——
 *      那样第 1 条的坑会原样复现。
 */
package com.faze.modules.ai.config;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 流式延迟任务调度器（`ScheduledExecutorService` 的类型安全包装）。
 *
 * <p>只提供本段用到的三个动作，故意不实现 {@link java.util.concurrent.Executor}，
 * 避免污染 Spring Boot 的任务执行器自动配置（原因见类头注释）。</p>
 */
public class AiStreamScheduler {

    /** 真实线程池（由配置类创建，本类只转发）。 */
    private final ScheduledThreadPoolExecutor delegate;

    /**
     * 构造。
     *
     * @param delegate 已配置好的单次延迟任务线程池；调用方负责线程命名与
     *                 `setRemoveOnCancelPolicy(true)` 等策略
     */
    public AiStreamScheduler(ScheduledThreadPoolExecutor delegate) {
        this.delegate = delegate;
    }

    /**
     * 立即提交一个任务（用于「新流开始」这类不需要延迟的动作）。
     *
     * @param task 任务；不得为 null
     */
    public void execute(Runnable task) {
        delegate.execute(task);
    }

    /**
     * 延迟执行一次。
     *
     * @param task  任务
     * @param delay 延迟时长
     * @param unit  时间单位
     * @return 可取消的句柄（取消后任务立即从队列移除，见配置类的 removeOnCancelPolicy）
     */
    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return delegate.schedule(task, delay, unit);
    }

    /**
     * 关闭线程池（容器关闭时由 `destroyMethod` 调用）。
     *
     * <p>用 `shutdownNow` 而不是 `shutdown`：延迟任务在关闭后没有执行的意义，
     * 保留只会拖慢停机。</p>
     */
    public void shutdownNow() {
        delegate.shutdownNow();
    }
}
