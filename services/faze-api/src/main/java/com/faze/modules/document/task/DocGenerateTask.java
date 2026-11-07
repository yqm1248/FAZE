/*
 * 职责：文书生成任务扫描器（段 4 A4）——每 5 秒领一批 `doc_generate` 任务并交给 handler。
 * 调用方：Spring 的 `@Scheduled`（调度线程池由 config/ThreadPoolConfig 提供）。
 * 数据来源：task_queue（领取/标记）、DocGenerateHandler（业务执行）。
 * 注意：
 *   1. 与 OrderCloseTask / PushTask 同一套模式：**扫描器只管领、标记、失败退避**，
 *      不写业务。业务全在 handler 里。
 *   2. **必须按 task_type 过滤领取**：`claimBatch(worker, types)` 的第二个参数不能省。
 *      省了会领走别的消费者的任务（task_queue 现在有 3 个消费者）。
 *   3. `@Scheduled` 没生效时**没有任何报错**，所以 `logRegistered()` 那行日志是唯一可观测点。
 *   4. 领取本身失败（库连不上）本轮放弃、不吞异常日志；单条执行失败走 `markFailed`
 *      的指数退避，`retry_count >= 5` 进死信（PC A-26 可见）。
 */
package com.faze.modules.document.task;

import com.faze.common.exception.BusinessException;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文书生成任务扫描器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocGenerateTask {

    /** 扫描间隔：生成是用户主动触发的低频任务，5 秒足够。 */
    private static final long SCAN_INTERVAL_MS = 5_000L;

    /** 首次延迟：避开启动期（Flyway + 连接池初始化）。 */
    private static final long INITIAL_DELAY_MS = 15_000L;

    /** 本扫描器的工作线程标识。 */
    private static final String WORKER_NAME = "doc-generate";

    private final TaskQueueService taskQueueService;
    private final DocGenerateHandler docGenerateHandler;

    /**
     * 启动时登记一行日志（`@Scheduled` 未生效时没有其它痕迹）。
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("文书生成扫描器已注册：fixedDelay={}ms initialDelay={}ms worker={}",
                SCAN_INTERVAL_MS, INITIAL_DELAY_MS, WORKER_NAME);
    }

    /**
     * 扫描并执行到期的文书生成任务。
     *
     * <p>副作用：领取任务、生成 PDF、更新 document 状态、更新任务状态。</p>
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void scan() {
        log.debug("文书生成扫描触发");
        List<TaskQueue> tasks;
        try {
            tasks = taskQueueService.claimBatch(WORKER_NAME,
                    List.of(TaskQueueService.TYPE_DOC_GENERATE));
        } catch (RuntimeException e) {
            log.error("领取文书生成任务失败，本轮跳过", e);
            return;
        }
        if (tasks.isEmpty()) {
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER_NAME);
        for (TaskQueue task : tasks) {
            try {
                docGenerateHandler.handle(task);
                taskQueueService.markDone(task, owner);
            } catch (RuntimeException e) {
                log.error("执行文书生成任务失败 taskId={} bizNo={}",
                        task.getId(), task.getBizNo(), e);
                taskQueueService.markFailed(task, owner, describeFailure(e));
            }
        }
    }

    /**
     * 把执行异常转成死信里能读懂的失败原因。
     *
     * <p>C4 要求按错误码归因（506003 字体缺失 / 506002 渲染失败 / 406002 表单非法 …），
     * 只写异常类名 + 消息会让死信看不出是哪一类失败，所以业务异常一律带码。</p>
     *
     * <p>2026-11-07 补：**码后面还要带具体原因**。`406002` 的通用文案是「表单校验失败」，
     * 但占位符不匹配与非法 XHTML 都是 406002——不把 `BusinessException` 的具体消息带上，
     * `4-A8` 的「last_error 有可读原因」就没法区分两个场景，排障时只能去翻堆栈。
     * 业务异常的具体消息是**我们自己写的中文判据**（不含 `e.getMessage()` 透传用户数据），
     * 长度截断到 120 字，不会把整段模板写进死信。</p>
     *
     * @param e 执行期抛出的运行时异常
     * @return 形如 `406002: 表单校验失败 —— 模板引用了未定义的字段：not_in_schema` 的短句（不含堆栈）
     */
    private String describeFailure(RuntimeException e) {
        if (e instanceof BusinessException be && be.getErrorCode() != null) {
            String code = be.getErrorCode().getCode() + ": " + be.getErrorCode().getMsg();
            // BusinessException#getMessage() 返回的是**通用文案**（errorCode.getMsg()），
            // 具体判据在 getDetail() 里（后端规范：detail 只进日志、不进响应体，这里正是写日志的地方）
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
