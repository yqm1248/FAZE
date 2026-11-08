/*
 * 职责：对账任务扫描器（第 6 段 D3/D4）——每 60 秒领一批对账任务并执行。
 * 调用方：Spring 的 @Scheduled（ThreadPoolConfig 的 taskScheduler）。
 * 数据来源：task_queue（领取/标记）+ ReconcileTaskService（业务）。
 * 注意：
 *   1. 与 `RechargeTaskScanner` **分开**：两者的任务类型完全不同，合在一起会让
 *      「哪类任务在拖慢哪一类」看不出来；`claimBatch` 按类型领，两个扫描器互不干扰。
 *   2. 用 `fixedDelay` 不用 `fixedRate`（比对可能跑几十秒，别堆触发）。
 *   3. **单条失败不拖垮整批**：逐条 try/catch，失败交给 `markFailed` 走 2^n 退避，5 次后死信。
 *   4. `owner` 必须取自 `taskQueueService.ownerOf(WORKER_NAME)`，否则 `markDone` 全部落空。
 *   5. 多实例安全由 task_queue 的 CAS 领取保证，不加 synchronized。
 */
package com.faze.modules.trade.task;

import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.trade.service.ReconcileBillService;
import com.faze.modules.trade.service.ReconcileTaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账任务扫描器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconcileTaskScanner {

    /** 扫描间隔（毫秒）。对账任务一天两次量级，60 秒够用。 */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    /** 首次扫描延迟（毫秒）。 */
    private static final long INITIAL_DELAY_MS = 45_000L;

    /** 工作线程标识。 */
    private static final String WORKER_NAME = "reconcile-task";

    private final TaskQueueService taskQueueService;

    private final ReconcileTaskService reconcileTaskService;

    /**
     * 启动时登记一行日志（调度没生效时这是唯一能看出来的痕迹）。
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("对账任务扫描器已注册：fixedDelay={}ms initialDelay={}ms worker={}",
                SCAN_INTERVAL_MS, INITIAL_DELAY_MS, WORKER_NAME);
    }

    /**
     * 扫描并执行到期的对账任务。
     *
     * <p>副作用：领取任务、下载账单、写差异、补入账、更新任务状态。</p>
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void scan() {
        List<TaskQueue> tasks;
        try {
            tasks = taskQueueService.claimBatch(WORKER_NAME,
                    List.of(ReconcileBillService.TASK_DOWNLOAD, ReconcileBillService.TASK_COMPARE));
        } catch (RuntimeException e) {
            log.error("领取对账任务失败，本轮跳过", e);
            return;
        }
        if (tasks.isEmpty()) {
            log.debug("本轮没有到期的对账任务");
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER_NAME);
        for (TaskQueue task : tasks) {
            try {
                if (ReconcileBillService.TASK_COMPARE.equals(task.getTaskType())) {
                    reconcileTaskService.handleCompare(task.getBizNo());
                } else {
                    reconcileTaskService.handleDownload(task.getBizNo());
                }
                taskQueueService.markDone(task, owner);
            } catch (RuntimeException e) {
                log.error("执行对账任务失败 taskId={} type={} bizNo={}",
                        task.getId(), task.getTaskType(), task.getBizNo(), e);
                taskQueueService.markFailed(task, owner,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
