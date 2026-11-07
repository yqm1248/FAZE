/*
 * 职责：文书生成任务的业务执行器（段 4 A4 的接入点，C3 填实现）。
 * 调用方：modules/document/task/DocGenerateTask（扫描器）。
 * 数据来源：document（待生成文书）、doc_template（模板版本行）、task_queue（任务上下文）。
 * 注意：
 *   1. **A4 阶段这里只有骨架**：任务通道（常量 + 扫描器 + 领取 + 失败退避）已就位，
 *      真正的生成链路（读模板 → 占位符替换 → 渲染 PDF → 上传登记 → 改 document 状态）
 *      在 C2/C3 补齐。骨架期抛 506001，**不要**在这里写「成功」——那会让 A4 的
 *      「`task_queue` 能捞到 doc_generate」看似通过、实际什么都没生成。
 *   2. 幂等：同一 `document.id` 连投 3 次，不能生成 3 份互相覆盖的文件。
 *      C3 的实现必须「先查 document.status，已是 generated 且 key 未变就跳过」，
 *      或在投递侧用 `hasActiveTask` 去重（MessagePushService 就是这么做的）。
 *   3. 整个方法**不在事务里**：里面要调渲染（CPU 重）与写文件（IO），
 *      把它们包进事务会把数据库连接占满（后端规范 §7.2 第 2 条）。
 *      状态更新是最后一步的单条 UPDATE，自带原子性。
 *   4. 抛出的异常由扫描器捕获并 `markFailed`（指数退避，5 次进死信），
 *      **不要**在这里 catch 后自己写状态——两处写会让状态判断打架。
 */
package com.faze.modules.document.task;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.mq.TaskQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文书生成执行器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocGenerateHandler {

    /**
     * 执行一条生成任务。
     *
     * <p>前置：`task.bizNo` 是 `document.id` 的字符串形式。
     * 副作用（C3 完成后）：渲染 PDF、上传登记、更新 document 与 file_record。</p>
     *
     * @param task 已领取的任务行
     * @throws BusinessException 506001（生成失败）；扫描器据此做退避重试
     */
    public void handle(TaskQueue task) {
        log.info("收到文书生成任务 taskId={} bizNo={}", task.getId(), task.getBizNo());
        // A4 骨架：C3 在这里补真实链路。
        // 现在抛 506001 是**故意的**——宁可任务失败进重试，也不写一条「成功但没产物」的假记录。
        throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED, "文书生成链路待 C3 接入");
    }
}
