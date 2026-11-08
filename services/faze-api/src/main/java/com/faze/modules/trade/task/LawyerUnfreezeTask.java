/*
 * 职责：律师收入到期解冻的调度器（段 5 E3）——每 10 分钟入队一条 tick 并执行解冻。
 * 调用方：Spring 的 `@Scheduled`（调度线程池由 config/ThreadPoolConfig 提供）。
 * 数据来源：task_queue（入队/领取/标记）、SettlementService#unfreezeDue（业务执行）。
 * 注意：
 *   1. **必须按 task_type 过滤领取**：`claimBatch(worker, types)` 的第二个参数不能省，
 *      否则会领走别的消费者的任务（task_queue 现在有 4 个消费者）。
 *   2. 为什么走 task_queue 而不是 `@Scheduled` 直连数据库：段 2 的异步任务五条规范要求
 *      后台批量动作留下「已投递 / 已领取 / 已失败」的痕迹；直连扫描器崩了只剩日志。
 *   3. 幂等在执行侧（`NOT EXISTS` + `uk_flow_biz`），**不在调度侧**：
 *      同一条 tick 被重复执行只会解冻 0 笔，不会重复转移资金。
 *   4. `@Scheduled` 没生效时没有任何报错，`logRegistered()` 那行是唯一可观测点。
 */
package com.faze.modules.trade.task;

import com.faze.common.exception.BusinessException;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.trade.service.SettlementService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 律师收入到期解冻调度器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LawyerUnfreezeTask {

    /** 扫描间隔：10 分钟（工作流 E3 第 3 条）。 */
    private static final long SCAN_INTERVAL_MS = 10 * 60 * 1000L;

    /** 首次延迟：2 分钟，避开启动期（Flyway + 连接池初始化）。 */
    private static final long INITIAL_DELAY_MS = 120_000L;

    /** 本扫描器的工作线程标识。 */
    private static final String WORKER_NAME = "lawyer-unfreeze";

    /** 单批解冻上限，与 SettlementService 的批大小对齐。 */
    private static final int BATCH_LIMIT = 200;

    private final TaskQueueService taskQueueService;
    private final SettlementService settlementService;

    /**
     * 启动时登记一行日志（`@Scheduled` 未生效时没有其它痕迹）。
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("律师收入解冻调度器已注册：fixedDelay={}ms initialDelay={}ms worker={}",
                SCAN_INTERVAL_MS, INITIAL_DELAY_MS, WORKER_NAME);
    }

    /**
     * 入队一条 tick 并执行本轮到期的解冻。
     *
     * <p>副作用：写一条 task_queue 记录、写 unfreeze 流水、更新 lawyer_balance、标记任务完成。</p>
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void scan() {
        log.debug("律师收入解冻扫描触发");
        // 任务 1：先入队一条 tick（留痕：什么时候跑过、跑没跑成）
        try {
            taskQueueService.enqueue(TaskQueueService.TYPE_LAWYER_UNFREEZE,
                    "unfreeze-" + LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC), null);
        } catch (RuntimeException e) {
            // 入队失败不影响本轮执行：解冻是幂等的，少一次痕迹比少一次解冻轻
            log.error("解冻 tick 入队失败，本轮继续执行", e);
        }
        // 任务 2：按类型领取（不能省 types，否则会领走别的消费者的任务）
        List<TaskQueue> tasks;
        try {
            tasks = taskQueueService.claimBatch(WORKER_NAME,
                    List.of(TaskQueueService.TYPE_LAWYER_UNFREEZE));
        } catch (RuntimeException e) {
            log.error("领取解冻任务失败，本轮跳过", e);
            return;
        }
        if (tasks.isEmpty()) {
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER_NAME);
        for (TaskQueue task : tasks) {
            try {
                int count = settlementService.unfreezeDue(BATCH_LIMIT);
                log.info("解冻任务执行完成 taskId={} 解冻笔数={}", task.getId(), count);
                taskQueueService.markDone(task, owner);
            } catch (RuntimeException e) {
                log.error("执行解冻任务失败 taskId={}", task.getId(), e);
                taskQueueService.markFailed(task, owner, describeFailure(e));
            }
        }
    }

    /**
     * 把执行异常转成死信里能读懂的失败原因。
     *
     * @param e 执行期抛出的运行时异常
     * @return 形如 `500000: 服务器内部错误 —— 律师余额更新失败（乐观锁冲突）` 的短句
     */
    private String describeFailure(RuntimeException e) {
        if (e instanceof BusinessException be && be.getErrorCode() != null) {
            String code = be.getErrorCode().getCode() + ": " + be.getErrorCode().getMsg();
            String detail = be.getDetail();
            if (detail == null || detail.isBlank() || detail.equals(be.getErrorCode().getMsg())) {
                return code;
            }
            String clipped = detail.length() > 120 ? detail.substring(0, 120) + "…" : detail;
            return code + " —— " + clipped;
        }
        String raw = e.getClass().getSimpleName() + ": " + e.getMessage();
        return raw.length() > 160 ? raw.substring(0, 160) + "…" : raw;
    }
}
