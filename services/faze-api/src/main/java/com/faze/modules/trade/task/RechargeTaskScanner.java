/*
 * 职责：充值单定时任务扫描器（第 6 段 D1/D2）——每 60 秒领一批 recharge 任务并执行。
 * 调用方：Spring 的 @Scheduled（ThreadPoolConfig 的 taskScheduler）。
 * 数据来源：task_queue（领取/标记）+ RechargeTaskService（业务）。
 * 注意：
 *   1. 用 `fixedDelay` 不用 `fixedRate`（上一轮没跑完不堆触发），与 OrderCloseTask 同一理由。
 *   2. `owner` 必须取自 `taskQueueService.ownerOf(WORKER_NAME)`，否则 `markDone` 全部落空。
 *   3. **单条失败不拖垮整批**：逐条 try/catch，失败交给 markFailed 走 2^n 退避，5 次后死信。
 *      D1 的「查不到」正是靠这条路径重试（业务层抛 IllegalStateException）。
 *   4. 多实例安全由 task_queue 的 CAS 领取保证，不加 synchronized。
 *   5. 本扫描器只管「查询补单」与「超时关单」；对账下载/比对（D3/D4）是每日任务，
 *      由各自的调度器投递，不在本类里跑。
 */
package com.faze.modules.trade.task;

import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.trade.service.RechargeTaskService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 充值任务扫描器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RechargeTaskScanner {

    /** 扫描间隔（毫秒）：60 秒。充值单查询不需要 30 秒那么激进。 */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    /** 首次扫描延迟（毫秒）：30 秒，等应用启动稳定。 */
    private static final long INITIAL_DELAY_MS = 30_000L;

    /** 工作线程标识。 */
    private static final String WORKER_NAME = "recharge-task";

    private final TaskQueueService taskQueueService;

    private final RechargeTaskService rechargeTaskService;

    /**
     * 启动时登记一行日志（调度没生效时这是唯一能看出来的痕迹）。
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("充值任务扫描器已注册：fixedDelay={}ms initialDelay={}ms worker={}",
                SCAN_INTERVAL_MS, INITIAL_DELAY_MS, WORKER_NAME);
    }

    /**
     * 扫描并执行到期的充值任务。
     *
     * <p>副作用：领取任务、可能补入账/关单、更新任务状态。</p>
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void scan() {
        List<TaskQueue> tasks;
        try {
            tasks = taskQueueService.claimBatch(WORKER_NAME,
                    List.of(RechargeTaskService.TASK_PENDING_QUERY, RechargeTaskService.TASK_CLOSE));
        } catch (RuntimeException e) {
            log.error("领取充值任务失败，本轮跳过", e);
            return;
        }
        if (tasks.isEmpty()) {
            log.debug("本轮没有到期的充值任务");
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER_NAME);
        for (TaskQueue task : tasks) {
            try {
                if (RechargeTaskService.TASK_CLOSE.equals(task.getTaskType())) {
                    rechargeTaskService.handleClose(task.getBizNo());
                } else {
                    rechargeTaskService.handlePendingQuery(task.getBizNo());
                }
                taskQueueService.markDone(task, owner);
            } catch (RuntimeException e) {
                log.error("执行充值任务失败 taskId={} type={} bizNo={}",
                        task.getId(), task.getTaskType(), task.getBizNo(), e);
                taskQueueService.markFailed(task, owner,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
