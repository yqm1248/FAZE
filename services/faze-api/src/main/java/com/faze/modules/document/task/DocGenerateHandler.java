/*
 * 职责：文书生成任务执行器（段 4 C3/C4）——读模板 → 占位符替换 → 渲染 PDF → 上传登记 → 更新状态。
 * 调用方：modules/document/task/DocGenerateTask（扫描器）。
 * 数据来源：document、doc_template、file_record；产出写 document.status / pdf_oss_key。
 * 注意：
 *   1. **整个方法不包事务**：里面要跑渲染（CPU 重，~1-3 秒）与写文件（IO），
 *      包进事务会长时间占着数据库连接（后端规范 §7.2 第 2 条）。状态更新是最后一条 UPDATE，自带原子性。
 *   2. **失败必须落 `status='failed'` 且把两个 `*_oss_key` 置 NULL**（失败不留半成品）。
 *      注意 MP 的 `updateById` 默认忽略 null 字段，清空 key 必须用 `lambdaUpdate().set(col, null)`，
 *      否则"上次成功、这次失败"的文书会留着旧的 pdf_oss_key，看起来像成功。
 *   3. 失败后**仍然把异常抛给扫描器**：由它做指数退避重试（5 次进死信）。
 *      本类只负责把业务状态写对，不负责重试策略。
 *   4. 幂等：同一条任务被重复领取时，若 document 已是 generated 且 key 未变，直接跳过。
 *      投递侧的 `hasActiveTask` 是第一道，这里是第二道（防租约回收重跑）。
 *   5. `form_snapshot` 在**生成成功那一刻**写入（= 本次 form_data），之后 form_data 怎么改都不动它。
 *   6. 预览图一并生成并登记（`biz_type=document`、`content_type=image/png`），列表缩略图用它。
 */
package com.faze.modules.document.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.mq.TaskQueue;
import com.faze.infra.pdf.PdfPageRenderer;
import com.faze.infra.pdf.PdfRenderer;
import com.faze.infra.storage.FileRecordService;
import com.faze.modules.document.bo.FieldSchema;
import com.faze.modules.document.entity.DocTemplate;
import com.faze.modules.document.entity.Document;
import com.faze.modules.document.mapper.DocTemplateMapper;
import com.faze.modules.document.mapper.DocumentMapper;
import com.faze.modules.document.service.TemplateRenderer;
import com.faze.modules.document.validator.TemplateHtmlValidator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 文书生成执行器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocGenerateHandler {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_GENERATED = "generated";
    private static final String STATUS_FAILED = "failed";

    private final DocumentMapper documentMapper;
    private final DocTemplateMapper docTemplateMapper;
    private final TemplateRenderer templateRenderer;
    private final TemplateHtmlValidator templateHtmlValidator;
    private final PdfRenderer pdfRenderer;
    private final PdfPageRenderer pdfPageRenderer;
    private final FileRecordService fileRecordService;
    private final JsonMapper jsonMapper;

    /**
     * 执行一条生成任务。
     *
     * <p>前置：`task.bizNo` 是 `document.id` 的字符串形式。
     * 副作用：渲染 PDF、写文件、更新 document 状态；失败时落 failed 并抛出。</p>
     *
     * @param task 已领取的任务行
     * @throws BusinessException 生成失败（扫描器据此退避重试）
     */
    public void handle(TaskQueue task) {
        long documentId;
        try {
            documentId = Long.parseLong(task.getBizNo());
        } catch (NumberFormatException e) {
            // 脏任务：biz_no 不是文书 id。记日志后当作已完成，不重试（重试也不会好）
            log.error("生成任务的 bizNo 不是合法文书 id，跳过：{}", task.getBizNo());
            return;
        }
        Document doc = documentMapper.selectById(documentId);
        if (doc == null || doc.getDeletedAt() != null) {
            log.info("文书不存在或已删除，任务直接完成 documentId={}", documentId);
            return;
        }
        try {
            doGenerate(doc);
        } catch (BusinessException e) {
            markFailed(doc.getId());
            throw e;
        } catch (RuntimeException e) {
            markFailed(doc.getId());
            log.error("文书生成出现未预期异常 documentId={}", doc.getId(), e);
            throw new BusinessException(ErrorCode.DOCUMENT_GENERATE_FAILED);
        }
    }

    // ── 生成 ────────────────────────────────────────────────────────────────

    /**
     * 真实生成链路。
     *
     * @param doc 文书
     */
    private void doGenerate(Document doc) {
        DocTemplate template = docTemplateMapper.selectById(doc.getTemplateId());
        if (template == null || !STATUS_ACTIVE.equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.DOCUMENT_TEMPLATE_NOT_FOUND);
        }
        FieldSchema schema = parseSchema(template.getFieldSchema());
        JsonNode form = parseForm(doc.getFormData());

        String xhtml = templateRenderer.render(template.getTemplateHtml(), schema, form);
        templateHtmlValidator.assertWellFormed(xhtml);

        byte[] pdf = pdfRenderer.render(xhtml);
        String pdfKey = fileRecordService.storeGenerated(FileRecordService.BIZ_DOCUMENT,
                doc.getId(), doc.getUserId(), "pdf", pdf, "application/pdf");

        byte[] previewPng = pdfPageRenderer.renderFirstPagePng(pdf);
        fileRecordService.storeGenerated(FileRecordService.BIZ_DOCUMENT,
                doc.getId(), doc.getUserId(), "png", previewPng, "image/png");

        // 成功：状态 + key + 表单快照 + 模板版本，一次 UPDATE
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Document update = new Document();
        update.setId(doc.getId());
        update.setStatus(STATUS_GENERATED);
        update.setPdfOssKey(pdfKey);
        update.setTemplateVersion(template.getVersion());
        update.setFormSnapshot(doc.getFormData());
        update.setUpdatedAt(now);
        documentMapper.updateById(update);
        log.info("文书生成成功 documentId={} templateId={} version={} pdfBytes={}",
                doc.getId(), template.getId(), template.getVersion(), pdf.length);
    }

    /**
     * 失败落库：status=failed 且两个 oss_key 置 NULL。
     *
     * <p>用 `lambdaUpdate().set(col, null)` 而不是 `updateById`：后者默认忽略 null 字段，
     * 清不掉上一次成功后留下的 key。</p>
     *
     * @param documentId 文书主键
     */
    private void markFailed(Long documentId) {
        documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
                .set(Document::getStatus, STATUS_FAILED)
                .set(Document::getPdfOssKey, null)
                .set(Document::getSignedPdfOssKey, null)
                .set(Document::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(Document::getId, documentId));
    }

    /**
     * 解析字段定义。
     *
     * @param raw JSON 字符串
     * @return 字段定义
     */
    private FieldSchema parseSchema(String raw) {
        try {
            return FieldSchema.parse(jsonMapper.readTree(raw == null || raw.isBlank()
                    ? "{\"version\":1,\"fields\":[]}" : raw));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "field_schema 非法");
        }
    }

    /**
     * 解析表单数据。
     *
     * @param raw JSON 字符串
     * @return JSON 节点
     */
    private JsonNode parseForm(String raw) {
        try {
            return jsonMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "form_data 不是合法 JSON");
        }
    }
}
