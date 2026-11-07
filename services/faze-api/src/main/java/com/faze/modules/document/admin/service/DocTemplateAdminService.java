/*
 * 职责：A-18 文书模板管理（段 4 B1）——列表 / 详情 / 新建 / 保存（版本行）/ 预览。
 * 调用方：AdminDocTemplateController。
 * 数据来源：faze.doc_template、FileRecordService（预览图登记）、TemplateHtmlValidator（校验）。
 * 注意：
 *   1. **保存不是原地 UPDATE**：改模板要 INSERT 一条 `(code, lang, version+1)` 新行，
 *      再把旧行置 `inactive`。旧行永不删、永不改——历史文书重渲染靠
 *      `(template_id, template_version)` 找回（`4-A4` 的判据）。
 *   2. **保存前必须过 TemplateHtmlValidator**：well-formed、标签白名单、占位符与 schema 一一对应。
 *      不校验的话，模板要等用户点「生成」才炸，而且炸在异步任务里，运营看不到。
 *   3. 预览 = 真渲染：示例数据 → TemplateRenderer → PdfRenderer → 第一页 PNG → 登记 → 现签 URL。
 *      **不能拿 HTML 截图冒充**（`4-A2` 判据）。
 *   4. 写方法标 `@OperationLog`（审计规范 §3.2），纯查询不标。
 *   5. 事务只包 DB 操作；预览的渲染与写文件**不进事务**（会长时间占用连接）。
 */
package com.faze.modules.document.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.pdf.PdfPageRenderer;
import com.faze.infra.pdf.PdfRenderer;
import com.faze.infra.storage.FileRecordService;
import com.faze.modules.document.admin.bo.DocTemplateQueryBO;
import com.faze.modules.document.admin.bo.DocTemplateSaveBO;
import com.faze.modules.document.admin.vo.DocTemplateDetailVO;
import com.faze.modules.document.admin.vo.DocTemplatePreviewVO;
import com.faze.modules.document.admin.vo.DocTemplateRowVO;
import com.faze.modules.document.bo.FieldSchema;
import com.faze.modules.document.entity.DocTemplate;
import com.faze.modules.document.mapper.DocTemplateMapper;
import com.faze.modules.document.service.TemplateRenderer;
import com.faze.modules.document.validator.TemplateHtmlValidator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模板管理服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocTemplateAdminService {

    private static final String MODULE = "document";
    private static final String TARGET = "DOC_TEMPLATE";
    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";
    private static final String LANG_DEFAULT = "zh-Hans";
    private static final int MAX_PAGE_SIZE = 100;
    private static final long PREVIEW_TTL_SECONDS = 60L;

    private final DocTemplateMapper docTemplateMapper;
    private final TemplateHtmlValidator templateHtmlValidator;
    private final TemplateRenderer templateRenderer;
    private final PdfRenderer pdfRenderer;
    private final PdfPageRenderer pdfPageRenderer;
    private final FileRecordService fileRecordService;
    private final JsonMapper jsonMapper;

    /**
     * 模板列表（RuoYi `rows` 分页）。
     *
     * @param query 查询条件
     * @return 分页响应
     */
    public TableDataInfo<DocTemplateRowVO> list(DocTemplateQueryBO query) {
        int pageNum = query.getPageNum() == null ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null ? 20 : Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        LambdaQueryWrapper<DocTemplate> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(query.getType() != null && !query.getType().isBlank(),
                DocTemplate::getCategory, query.getType());
        wrapper.eq(query.getStatus() != null && !query.getStatus().isBlank(),
                DocTemplate::getStatus, query.getStatus());
        wrapper.eq(query.getLang() != null && !query.getLang().isBlank(),
                DocTemplate::getLang, query.getLang());
        if (query.getKw() != null && !query.getKw().isBlank()) {
            String kw = query.getKw().trim();
            wrapper.and(w -> w.like(DocTemplate::getName, kw).or().like(DocTemplate::getCode, kw));
        }
        wrapper.orderByDesc(DocTemplate::getUpdatedAt).orderByDesc(DocTemplate::getId);
        IPage<DocTemplate> page = docTemplateMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<DocTemplateRowVO> rows = page.getRecords().stream().map(this::toRow).toList();
        return TableDataInfo.of(rows, page.getTotal());
    }

    /**
     * 模板详情（含 HTML 与字段定义）。
     *
     * @param id 模板主键
     * @return 详情
     */
    public DocTemplateDetailVO detail(Long id) {
        DocTemplate t = requireTemplate(id);
        DocTemplateDetailVO vo = new DocTemplateDetailVO();
        vo.setId(t.getId());
        vo.setCode(t.getCode());
        vo.setName(t.getName());
        vo.setCategory(t.getCategory());
        vo.setTemplateHtml(t.getTemplateHtml());
        vo.setFieldSchema(t.getFieldSchema());
        vo.setLang(t.getLang());
        vo.setVersion(t.getVersion());
        vo.setSortOrder(t.getSortOrder());
        vo.setStatus(t.getStatus());
        vo.setUpdatedAt(t.getUpdatedAt());
        if (t.getPreviewOssKey() != null && !t.getPreviewOssKey().isBlank()) {
            vo.setPreviewUrl(fileRecordService.presignDownload(t.getPreviewOssKey()));
        }
        return vo;
    }

    /**
     * 新建模板（version=1，默认 draft）。
     *
     * @param bo 保存入参
     * @return 新模板主键
     */
    @OperationLog(module = MODULE, action = "DOC_TEMPLATE_CREATE", targetType = TARGET)
    @Transactional(rollbackFor = Exception.class)
    public Long create(DocTemplateSaveBO bo) {
        FieldSchema schema = parseAndValidate(bo);
        String lang = LANG_DEFAULT;
        Long exists = docTemplateMapper.selectCount(Wrappers.<DocTemplate>lambdaQuery()
                .eq(DocTemplate::getCode, bo.getCode())
                .eq(DocTemplate::getLang, lang));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_TEMPLATE_NOT_FOUND,
                    "模板编码已存在：" + bo.getCode());
        }
        DocTemplate t = new DocTemplate();
        applyToEntity(t, bo, schema);
        t.setLang(lang);
        t.setVersion(1);
        t.setStatus(bo.getStatus() == null ? STATUS_DRAFT : bo.getStatus());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        docTemplateMapper.insert(t);
        log.info("新建模板 id={} code={}", t.getId(), t.getCode());
        return t.getId();
    }

    /**
     * 保存模板 → **生成新版本行**（旧行置 inactive）。
     *
     * @param id 模板主键（可以是任一版本行）
     * @param bo 保存入参
     * @return 新版本行的主键
     */
    @OperationLog(module = MODULE, action = "DOC_TEMPLATE_UPDATE", targetType = TARGET)
    @Transactional(rollbackFor = Exception.class)
    public Long update(Long id, DocTemplateSaveBO bo) {
        DocTemplate current = requireTemplate(id);
        FieldSchema schema = parseAndValidate(bo);

        DocTemplate next = new DocTemplate();
        applyToEntity(next, bo, schema);
        next.setCode(current.getCode());
        next.setLang(current.getLang());
        next.setVersion(current.getVersion() + 1);
        next.setStatus(bo.getStatus() == null ? STATUS_DRAFT : bo.getStatus());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        docTemplateMapper.insert(next);

        // 旧版本就地置 inactive：**只改 status，不动模板内容**
        DocTemplate oldRow = new DocTemplate();
        oldRow.setId(current.getId());
        oldRow.setStatus(STATUS_INACTIVE);
        oldRow.setUpdatedAt(now);
        docTemplateMapper.updateById(oldRow);

        log.info("模板改版 code={} {} -> {} 新行id={}", current.getCode(),
                current.getVersion(), next.getVersion(), next.getId());
        return next.getId();
    }

    /**
     * 渲染预览图（真 PDF 转 PNG）。
     *
     * @param id 模板主键
     * @return 预览图短时地址
     */
    public DocTemplatePreviewVO preview(Long id) {
        DocTemplate t = requireTemplate(id);
        FieldSchema schema = FieldSchema.parse(readSchemaNode(t.getFieldSchema()));
        String xhtml = templateRenderer.render(t.getTemplateHtml(), schema, sampleData(schema));
        templateHtmlValidator.assertWellFormed(xhtml);
        byte[] pdf = pdfRenderer.render(xhtml);
        byte[] png = pdfPageRenderer.renderFirstPagePng(pdf);
        String key = fileRecordService.storeGenerated(FileRecordService.BIZ_DOC_TEMPLATE,
                t.getId(), 0L, "png", png, "image/png");

        DocTemplate update = new DocTemplate();
        update.setId(t.getId());
        update.setPreviewOssKey(key);
        update.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        docTemplateMapper.updateById(update);

        DocTemplatePreviewVO vo = new DocTemplatePreviewVO();
        vo.setUrl(fileRecordService.presignDownload(key));
        vo.setExpiresIn(PREVIEW_TTL_SECONDS);
        return vo;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 解析 schema 并做 HTML 校验。
     *
     * @param bo 保存入参
     * @return 解析后的字段定义
     */
    private FieldSchema parseAndValidate(DocTemplateSaveBO bo) {
        FieldSchema schema = FieldSchema.parse(readSchemaNode(bo.getFieldSchema()));
        templateHtmlValidator.validate(bo.getTemplateHtml(), schema);
        return schema;
    }

    /**
     * 读字段定义 JSON（空字符串视为空 schema）。
     *
     * @param raw JSON 字符串
     * @return JsonNode
     */
    private tools.jackson.databind.JsonNode readSchemaNode(String raw) {
        if (raw == null || raw.isBlank()) {
            return jsonMapper.readTree("{\"version\":1,\"fields\":[]}");
        }
        try {
            return jsonMapper.readTree(raw);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORM_INVALID, "field_schema 不是合法 JSON");
        }
    }

    /**
     * 把保存入参写进实体（不含版本与状态）。
     *
     * @param t      实体
     * @param bo     入参
     * @param schema 已校验的字段定义
     */
    private void applyToEntity(DocTemplate t, DocTemplateSaveBO bo, FieldSchema schema) {
        t.setName(bo.getName());
        t.setCategory(bo.getCategory());
        t.setTemplateHtml(bo.getTemplateHtml());
        t.setFieldSchema(bo.getFieldSchema());
        t.setSortOrder(bo.getSortOrder() == null ? 0 : bo.getSortOrder());
    }

    /**
     * 按主键取模板，不存在抛 406001。
     *
     * @param id 主键
     * @return 模板行
     */
    private DocTemplate requireTemplate(Long id) {
        DocTemplate t = docTemplateMapper.selectById(id);
        if (t == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_TEMPLATE_NOT_FOUND);
        }
        return t;
    }

    /**
     * 实体 → 列表行。
     *
     * @param t 模板行
     * @return 行 VO
     */
    private DocTemplateRowVO toRow(DocTemplate t) {
        DocTemplateRowVO vo = new DocTemplateRowVO();
        vo.setId(t.getId());
        vo.setCode(t.getCode());
        vo.setName(t.getName());
        vo.setCategory(t.getCategory());
        vo.setLang(t.getLang());
        vo.setVersion(t.getVersion());
        vo.setStatus(t.getStatus());
        vo.setSortOrder(t.getSortOrder());
        vo.setUpdatedAt(t.getUpdatedAt());
        try {
            vo.setFieldCount(FieldSchema.parse(readSchemaNode(t.getFieldSchema())).fields().size());
        } catch (RuntimeException e) {
            vo.setFieldCount(0);
        }
        return vo;
    }

    /**
     * 生成预览用的示例数据（每个字段一个占位值，array 给 1 项）。
     *
     * @param schema 字段定义
     * @return JSON 节点
     */
    private tools.jackson.databind.JsonNode sampleData(FieldSchema schema) {
        return jsonMapper.readTree(sampleJson(schema));
    }

    /**
     * 拼示例数据的 JSON 文本（值里不含引号与反斜杠，可安全拼接）。
     *
     * @param schema 字段定义
     * @return JSON 字符串
     */
    private String sampleJson(FieldSchema schema) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (FieldSchema.FieldDef f : schema.fields()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(f.key()).append("\":");
            if ("array".equals(f.type())) {
                sb.append('[').append('{');
                boolean firstItem = true;
                for (FieldSchema.FieldDef sub : f.itemFields()) {
                    if (!firstItem) {
                        sb.append(',');
                    }
                    firstItem = false;
                    sb.append('"').append(sub.key()).append("\":\"").append(sampleText(sub)).append('"');
                }
                sb.append('}').append(']');
            } else {
                sb.append('"').append(sampleText(f)).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 示例值：日期给固定日期，其余给「示例+标签」。
     *
     * @param f 字段
     * @return 示例文本（不含引号与反斜杠，可安全拼进 JSON）
     */
    private String sampleText(FieldSchema.FieldDef f) {
        if ("date".equals(f.type())) {
            return "2026-01-01";
        }
        String label = f.label() == null ? f.key() : f.label();
        return "示例" + label.replace("\"", "").replace("\\", "");
    }
}
