/*
 * 职责：订单超时任务的定时扫描器——每 30 秒领一批 task_queue 任务并执行（G2.4）。
 * 调用方：Spring 的 `@Scheduled`（由 config/ThreadPoolConfig 的 taskScheduler 驱动）。
 * 数据来源：task_queue（领取/标记）、OrderTimeoutService（业务执行）。
 * 注意：
 *   1. **用 `fixedDelay` 不用 `fixedRate`**：fixedRate 会在上一轮没跑完时继续堆触发，
 *      慢一次就雪崩；fixedDelay 保证「上一轮结束后再等 30 秒」，天然限流。
 *   2. `initialDelay` 给 15 秒：等服务启动完成（Flyway 迁移、连接池预热）再开始扫，
 *      否则启动瞬间的 SQL 会与迁移抢锁。
 *   3. **单条任务失败不拖垮整批**：逐条 try/catch，失败交给 `markFailed` 走退避重试，
 *      一条坏任务（例如订单数据异常）不该让后面 19 条都不执行。
 *   4. `owner` 必须用 `taskQueueService.ownerOf(worker)` 取得，与 `claimBatch` 内部一致；
 *      不一致会导致 `markDone` 全部落空（任务被永久标不回完成，租约到期后重跑）。
 *   5. 多实例安全由 task_queue 的 CAS 领取保证，**不加 synchronized**（规范 §9.3 规则 4：
 *      synchronized 只在单 JVM 有效，会给人「已经防住了」的错觉）。
 */
package com.faze.modules.trade.task;

import com.faze.modules.trade.entity.TaskQueue;
import com.faze.modules.trade.service.OrderTimeoutService;
import com.faze.modules.trade.service.TaskQueueService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单超时任务扫描器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCloseTask {

    /** 扫描间隔（毫秒）：30 秒。 */
    private static final long SCAN_INTERVAL_MS = 30_000L;

    /** 首次扫描延迟（毫秒）：15 秒，等应用启动稳定。 */
    private static final long INITIAL_DELAY_MS = 15_000L;

    /** 本扫描器的工作线程标识（多实例时靠它 + 实例 ID 区分 owner）。 */
    private static final String WORKER_NAME = "order-close";

    private final TaskQueueService taskQueueService;
    private final OrderTimeoutService orderTimeoutService;

    /**
     * 启动时登记一行日志。
     *
     * <p>为什么需要它：`@Scheduled` 没生效时**没有任何报错**，表现只是「订单不自动关闭」。
     * 有了这行日志，运维看启动日志就能确认「调度器活着」；没有这行就是调度没注册。
     * （2026-11-06 实测踩过：扫描器未执行时，日志里静默无任何痕迹。）</p>
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("订单超时扫描器已注册：fixedDelay={}ms initialDelay={}ms worker={}",
                SCAN_INTERVAL_MS, INITIAL_DELAY_MS, WORKER_NAME);
    }

    /**
     * 扫描并执行到期的订单超时任务。
     *
     * <p>副作用：领取任务、改订单状态、可能触发退款、更新任务状态。</p>
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void scan() {
        log.debug("扫描触发");
        List<TaskQueue> tasks;
        try {
            tasks = taskQueueService.claimBatch(WORKER_NAME);
        } catch (RuntimeException e) {
            // 领取本身失败（库连不上等）：本轮放弃，下一轮再来。不吞异常日志。
            log.error("领取超时任务失败，本轮跳过", e);
            return;
        }
        if (tasks.isEmpty()) {
            log.debug("本轮没有到期任务");
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER_NAME);
        for (TaskQueue task : tasks) {
            try {
                orderTimeoutService.handle(task);
                taskQueueService.markDone(task, owner);
            } catch (RuntimeException e) {
                // 单条失败：退避重试，达到上限进死信（PC A-26 可见）
                log.error("执行超时任务失败 taskId={} type={} bizNo={}",
                        task.getId(), task.getTaskType(), task.getBizNo(), e);
                taskQueueService.markFailed(task, owner,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
