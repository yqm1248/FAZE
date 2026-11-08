/*
 * 职责：F4 异步导出任务的消费者——每 30 秒从 `task_queue` 领一批 `export` 任务并执行。
 * 调用方：Spring `@Scheduled`（主类已 @EnableScheduling）。
 * 数据来源：faze.task_queue（`task_type='export'`，`biz_no`=taskId）+ 各业务 Service 注册的 ExportSource。
 * 注意：
 *   1. **走 task_queue 而不是在 @Scheduled 里直接干活**：段 2 的异步任务五条要求所有后台批量动作
 *      都有「已投递 / 已领取 / 已失败」的可观测痕迹；直接执行的话任务崩了只能靠日志猜
 *      （与 LawyerUnfreezeTask 同一模式）。
 *   2. **owner 必须用与 claimBatch 相同的 suffix**：`TaskQueueService.claimBatch` 内部用
 *      `ownerOf(workerName)` 生成 owner，`markDone/markFailed` 的 where 条件认 owner，
 *      这里必须用同一个字符串，否则标记永远不命中（表现为任务被反复回收重跑）。
 *   3. `List<ExportSource>` 在**本类**注入而不是 ExportService 注入：ExportService 是数据源的
 *      调用方，若它反向注入 List<ExportSource> 会与各 Service 形成构造器循环依赖。
 *   4. 失败原因可能含内部路径：只写 `task_queue.last_error`（内部表）与日志，
 *      **不返回给客户端**（后端规范 §5.3）。
 *   5. 单轮最多领 20 条（TaskQueueService 的 CLAIM_LIMIT），失败的按指数退避下轮再试。
 */
package com.faze.modules.ops.task;

import com.faze.infra.mq.TaskQueue;
import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.ops.service.ExportService;
import com.faze.modules.ops.service.ExportSource;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步导出任务消费者。
 */
@Slf4j
@Component
public class ExportTaskHandler {

    /** 工作线程标识（同一实例内固定，owner 会拼上实例 id）。 */
    private static final String WORKER = "export-handler";

    private final TaskQueueService taskQueueService;
    private final ExportService exportService;
    private final List<ExportSource> exportSources;

    /**
     * 构造消费者。
     *
     * @param taskQueueService 任务队列
     * @param exportService    导出服务
     * @param exportSources    全部导出数据源（Spring 注入；无实现时为空列表）
     */
    public ExportTaskHandler(TaskQueueService taskQueueService,
                             ExportService exportService,
                             List<ExportSource> exportSources) {
        this.taskQueueService = taskQueueService;
        this.exportService = exportService;
        this.exportSources = exportSources == null ? Collections.emptyList() : exportSources;
    }

    /**
     * 每 30 秒扫描一次待执行的导出任务。
     *
     * <p>副作用：写 CSV / meta 文件，更新 task_queue 状态。</p>
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void scan() {
        List<TaskQueue> tasks = taskQueueService.claimBatch(WORKER, List.of(ExportService.TASK_TYPE_EXPORT));
        if (tasks.isEmpty()) {
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER);
        for (TaskQueue task : tasks) {
            try {
                exportService.runTask(task, exportSources);
                taskQueueService.markDone(task, owner);
                log.info("异步导出任务完成 taskId={}", task.getBizNo());
            } catch (Exception e) {
                // 只把异常摘要写进 last_error（内部表），完整堆栈进日志
                log.error("异步导出任务失败 taskId={}", task.getBizNo(), e);
                taskQueueService.markFailed(task, owner,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}
