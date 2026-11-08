/*
 * 职责：App 端文书业务编排（段 4 C1）——模板列表、草稿保存、我的文书、详情、软删、投递生成。
 * 调用方：AppDocumentController。
 * 数据来源：faze.doc_template / faze.document / faze.task_queue。
 * 注意：
 *   1. **每条按 id 的查询都带 `user_id = 当前用户`**：`selectById` 再判归属有 TOCTOU 窗口，
 *      而且漏判时没有任何提示（后端规范 §11.2）。越权统一抛 406004（HTTP 403），
 *      不伪装成 404——用户能区分「不存在」和「不是你的」对排障有用，而枚举风险已被
 *      登录态 + 自增 id 的组合抵消。
 *   2. **草稿保存不生成文件**：只写 form_data。生成是 `generate` 投递任务，由
 *      DocGenerateHandler 异步执行（文书写成异步是为了接口 <300ms 返回）。
 *   3. `generate` 必须幂等：已有 `doc_generate` 在跑的，直接返回不再投递
 *      （`hasActiveTask`），否则用户连点两次会生成两份互相覆盖的文件。
 *   4. 重新生成：已 `generated` 的文书再次生成时把 `status` 重置为 `draft`，
 *      旧产物**不删**（file_record 保留历史，document 只指最新）。
 *   5. `form_snapshot` 只在生成成功那一刻由 handler 写，本类不碰。
 *   6. 事务只包 DB 操作；投递任务要**在同一事务内**（否则文书回滚了任务还在队列里）。
 */
package com.faze.modules.document.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.infra.mq.TaskQueueService;
import com.faze.infra.storage.FileRecordService;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.infra.storage.mapper.FileRecordMapper;
import com.faze.modules.document.bo.DocumentSaveBO;
import com.faze.modules.document.entity.Document;
import com.faze.modules.document.entity.DocTemplate;
import com.faze.modules.document.mapper.DocTemplateMapper;
import com.faze.modules.document.mapper.DocumentMapper;
import com.faze.modules.document.vo.DocTemplateBriefVO;
import com.faze.modules.document.vo.DocumentDetailVO;
import com.faze.modules.document.vo.DocumentRowVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App 端文书服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppDocumentService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_GENERATED = "generated";
    private static final String STATUS_SIGNED = "signed";
    private static final String STATUS_FAILED = "failed";
    private static final String LANG_DEFAULT = "zh-Hans";
    private static final int MAX_PAGE_SIZE = 50;
    private static final long PREVIEW_TTL_SECONDS = 60L;

    private final DocumentMapper documentMapper;
    private final DocTemplateMapper docTemplateMapper;
    private final DocTemplateResolver docTemplateResolver;
    private final TaskQueueService taskQueueService;
    private final FileRecordService fileRecordService;
    private final FileRecordMapper fileRecordMapper;

    /**
     * App 端可用的模板列表（只返回 active）。
     *
     * @return 模板列表（含 field_schema）
     */
    public List<DocTemplateBriefVO> listActiveTemplates() {
        List<DocTemplate> templates = docTemplateMapper.selectList(Wrappers.<DocTemplate>lambdaQuery()
                .eq(DocTemplate::getStatus, "active")
                .eq(DocTemplate::getLang, LANG_DEFAULT)
                .orderByAsc(DocTemplate::getSortOrder)
                .orderByAsc(DocTemplate::getId));
        return templates.stream().map(t -> {
            DocTemplateBriefVO vo = new DocTemplateBriefVO();
            vo.setId(t.getId());
            vo.setCode(t.getCode());
            vo.setName(t.getName());
            vo.setCategory(t.getCategory());
            vo.setSortOrder(t.getSortOrder());
            vo.setFieldSchema(t.getFieldSchema());
            return vo;
        }).toList();
    }

    /**
     * 保存草稿（新建或更新同一份）。
     *
     * @param bo     保存入参
     * @param userId 当前用户
     * @return 文书主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(DocumentSaveBO bo, Long userId) {
        // 用解析器：模板升版后旧行会变 inactive，直接按 id 取会误判成「模板已停用」
        DocTemplate template = docTemplateResolver.activeOrDefault(bo.getTemplateId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (bo.getId() == null) {
            Document doc = new Document();
            doc.setUserId(userId);
            doc.setTemplateId(template.getId());
            doc.setTemplateVersion(template.getVersion());
            doc.setLang(LANG_DEFAULT);
            doc.setTitle(bo.getTitle() == null || bo.getTitle().isBlank()
                    ? template.getName() : bo.getTitle());
            doc.setDocType(template.getCategory());
            doc.setFormData(bo.getFormData());
            doc.setStatus(STATUS_DRAFT);
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            documentMapper.insert(doc);
            return doc.getId();
        }
        Document existing = requireOwned(bo.getId(), userId);
        Document update = new Document();
        update.setId(existing.getId());
        update.setTitle(bo.getTitle() == null || bo.getTitle().isBlank()
                ? existing.getTitle() : bo.getTitle());
        update.setFormData(bo.getFormData());
        // 失败的文书再次保存 → 回到草稿态，允许重新生成
        if (STATUS_FAILED.equals(existing.getStatus())) {
            update.setStatus(STATUS_DRAFT);
        }
        update.setUpdatedAt(now);
        documentMapper.updateById(update);
        return existing.getId();
    }

    /**
     * 我的文书列表（C 端 list 分页）。
     *
     * <p>`status` 支持**逗号分隔的多值**（如 `generated,signed`）：App「我的文书」的
     * 「已完成」筛选要同时覆盖「已生成」和「已盖章」，而 `signed` 是独立状态
     * （盖过章的文书如果被 eq 单值筛到，在「已完成」里就会消失）。</p>
     *
     * @param userId   当前用户
     * @param status   状态筛选（可空；支持逗号分隔多值）
     * @param docType  类型筛选（可空）
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public PageResult<DocumentRowVO> listMine(Long userId, String status, String docType,
                                             int pageNum, int pageSize) {
        int pageNo = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<Document> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(Document::getUserId, userId);
        wrapper.isNull(Document::getDeletedAt);
        List<String> statuses = parseStatuses(status);
        if (!statuses.isEmpty()) {
            wrapper.in(Document::getStatus, statuses);
        }
        if (docType != null && !docType.isBlank()) {
            wrapper.eq(Document::getDocType, docType);
        }
        wrapper.orderByDesc(Document::getCreatedAt).orderByDesc(Document::getId);
        IPage<Document> page = documentMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<Long> ids = page.getRecords().stream().map(Document::getId).toList();
        Map<Long, String> thumbs = thumbUrls(ids);
        List<DocumentRowVO> rows = page.getRecords().stream()
                .map(doc -> toRow(doc, thumbs.get(doc.getId())))
                .toList();
        return PageResult.of(rows, page.getTotal(), pageNo, size);
    }

    /**
     * 解析状态筛选（逗号分隔、去空、去重、只接受四个合法状态）。
     *
     * @param status 原始查询参数
     * @return 合法状态列表（非法值忽略，避免 SQL 里出现脏条件）
     */
    private List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        Set<String> allowed = Set.of(STATUS_DRAFT, STATUS_GENERATED, STATUS_SIGNED, STATUS_FAILED);
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(allowed::contains)
                .distinct()
                .toList();
    }

    /**
     * 批量取列表缩略图地址（生成时登记的第一页 PNG，见 DocGenerateHandler）。
     *
     * <p>E5 的「我的文书」列表要显示缩略图（这条在 A-18 验收时被定为「留到 E 组」）：
     * 缩略图是 `biz_type=document` 且 `original_name=generated.png` 的最新一条，
     * 一次查询批量取，避免 N+1。</p>
     *
     * @param ids 文书主键列表
     * @return documentId → 60 秒现签地址
     */
    private Map<Long, String> thumbUrls(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<FileRecord> records = fileRecordMapper.selectList(Wrappers.<FileRecord>lambdaQuery()
                .eq(FileRecord::getBizType, FileRecordService.BIZ_DOCUMENT)
                .in(FileRecord::getBizId, ids)
                .eq(FileRecord::getOriginalName, "generated.png")
                .eq(FileRecord::getStatus, "active")
                .orderByAsc(FileRecord::getId));
        Map<Long, String> urls = new HashMap<>(ids.size());
        for (FileRecord record : records) {
            // 同一份文书可能重新生成过多次，取最新一条（id 升序遍历，后写覆盖前写）
            urls.put(record.getBizId(), fileRecordService.presignDownload(record.getOssKey()));
        }
        return urls;
    }

    /**
     * 文书详情。
     *
     * @param id     文书主键
     * @param userId 当前用户
     * @return 详情
     */
    public DocumentDetailVO detail(Long id, Long userId) {
        Document doc = requireOwned(id, userId);
        DocumentDetailVO vo = new DocumentDetailVO();
        vo.setId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setStatus(doc.getStatus());
        vo.setFormData(doc.getFormData());
        vo.setTemplateId(doc.getTemplateId());
        vo.setTemplateVersion(doc.getTemplateVersion());
        vo.setCreatedAt(doc.getCreatedAt());
        vo.setUpdatedAt(doc.getUpdatedAt());
        if (doc.getPdfOssKey() != null && !doc.getPdfOssKey().isBlank()) {
            vo.setPdfUrl(fileRecordService.presignDownload(doc.getPdfOssKey()));
        }
        if (STATUS_FAILED.equals(doc.getStatus())) {
            vo.setErrorMsg("上次生成失败，请修改后重新生成");
        }
        // 「正在生成」必须由服务端说：generate 把状态重置成 draft，光看 status 分不出
        // 「从没生成过」和「任务在跑」（E4 的生成中态靠这个字段）
        vo.setGenerating(taskQueueService.hasActiveTask(
                TaskQueueService.TYPE_DOC_GENERATE, String.valueOf(doc.getId())));
        return vo;
    }

    /**
     * 软删草稿/文书。
     *
     * @param id     文书主键
     * @param userId 当前用户
     */
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long id, Long userId) {
        Document doc = requireOwned(id, userId);
        Document update = new Document();
        update.setId(doc.getId());
        update.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        documentMapper.updateById(update);
    }

    /**
     * 投递生成任务（立即返回，异步生成）。
     *
     * @param id     文书主键
     * @param userId 当前用户
     */
    @Transactional(rollbackFor = Exception.class)
    public void generate(Long id, Long userId) {
        Document doc = requireOwned(id, userId);
        String bizNo = String.valueOf(doc.getId());
        if (taskQueueService.hasActiveTask(TaskQueueService.TYPE_DOC_GENERATE, bizNo)) {
            // 失败态下再点「重新生成」：把排定的重试提前，而不是静默返回。
            // 否则用户在指数退避窗口内点几次都没反应（第一次失败要等 1 分钟、第二次 2 分钟…）。
            if (STATUS_FAILED.equals(doc.getStatus())
                    && taskQueueService.expeditePending(TaskQueueService.TYPE_DOC_GENERATE, bizNo)) {
                log.info("失败后手动重试：已把等待中的任务提前 documentId={}", doc.getId());
                return;
            }
            log.debug("已有生成任务在跑，跳过重复投递 documentId={}", doc.getId());
            return;
        }
        // 重新生成：把已生成/失败的状态拉回草稿态，等 handler 跑完再置 generated
        Document reset = new Document();
        reset.setId(doc.getId());
        reset.setStatus(STATUS_DRAFT);
        reset.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        documentMapper.updateById(reset);

        taskQueueService.enqueue(TaskQueueService.TYPE_DOC_GENERATE, bizNo,
                LocalDateTime.now(ZoneOffset.UTC), null);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 取当前用户自己的文书；不是自己的 → 403（406004）。
     *
     * @param id     文书主键
     * @param userId 当前用户
     * @return 文书
     */
    private Document requireOwned(Long id, Long userId) {
        Document doc = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getId, id)
                .eq(Document::getUserId, userId)
                .isNull(Document::getDeletedAt));
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORBIDDEN);
        }
        return doc;
    }

    /**
     * 实体 → 列表行。
     *
     * @param doc      文书
     * @param thumbUrl 缩略图 60 秒现签地址（没有生成过时 null）
     * @return 行 VO
     */
    private DocumentRowVO toRow(Document doc, String thumbUrl) {
        DocumentRowVO vo = new DocumentRowVO();
        vo.setId(doc.getId());
        vo.setTitle(doc.getTitle());
        vo.setDocType(doc.getDocType());
        vo.setStatus(doc.getStatus());
        vo.setThumbUrl(thumbUrl);
        vo.setCreatedAt(doc.getCreatedAt());
        vo.setUpdatedAt(doc.getUpdatedAt());
        return vo;
    }

    /**
     * 供 handler 判断「是否已生成」用的常量（避免字面量散落）。
     *
     * @return generated 状态码
     */
    public static String generatedStatus() {
        return STATUS_GENERATED;
    }

    /**
     * 供 handler 判断「是否已签名」用的常量。
     *
     * @return signed 状态码
     */
    public static String signedStatus() {
        return STATUS_SIGNED;
    }
}
