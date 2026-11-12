/*
 * 职责：PC A-16 案例库的写与查（第 5 段 G1）。
 * 调用方：modules/content/admin/controller/AdminCaseLibraryController。
 * 数据来源：case_library / case_library_translation / law_item（条号校验）。
 * 注意：
 *   1. **大类由细分推导**：写库时只落 `category`，`case_type` 由
 *      `CaseCategory#bigType()` 算出来（工作流 G1 第 2 条）。
 *   2. **法条引用保存前逐条回查**：条号在法条库不存在就拒（G1 第 6 条），
 *      否则 App 详情页会出现点不开的条文。
 *   3. **上线门禁**（G4）：`published` 的必要条件是源语言四块正文齐全；
 *      三种译文允许缺，但要登记 `pending`（段 8 才补全译文）。
 *   4. 状态迁移集中在 publish / offline / offlineBatch，**不在 save 里顺手改状态**：
 *      否则「保存草稿」会把已上线案例意外下架。
 *   5. 写接口全部标 `@OperationLog`（管理端写操作默认必须审计）。
 */
package com.faze.modules.content.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.infra.seq.BizNoRetry;
import com.faze.modules.ai.service.ArticleNoValidator;
import com.faze.modules.content.admin.bo.AdminCaseLibraryQueryBO;
import com.faze.modules.content.admin.bo.AdminCaseLibrarySaveBO;
import com.faze.modules.content.admin.bo.AdminCaseTranslationBO;
import com.faze.modules.content.admin.bo.AdminLawRefBO;
import com.faze.modules.content.admin.vo.AdminCaseLibraryDetailVO;
import com.faze.modules.content.admin.vo.AdminCaseLibraryRowVO;
import com.faze.modules.content.entity.CaseLibrary;
import com.faze.modules.content.entity.CaseLibraryTranslation;
import com.faze.modules.content.enums.CaseCategory;
import com.faze.modules.content.enums.CaseLibraryType;
import com.faze.modules.content.mapper.CaseLibraryMapper;
import com.faze.modules.content.mapper.CaseLibraryTranslationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * PC 案例库服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCaseLibraryService {

    /** 允许的译文语言（不含源语言 zh-Hans）。 */
    private static final Set<String> TRANSLATION_LANGS = Set.of("bo", "ug-Arab-CN", "kk-Arab-CN");

    /** 状态：草稿。 */
    private static final String STATUS_DRAFT = "draft";

    /** 状态：已上线。 */
    private static final String STATUS_PUBLISHED = "published";

    /** 状态：已下架。 */
    private static final String STATUS_OFFLINE = "offline";

    /** 译文状态：待译。 */
    private static final String TRANS_PENDING = "pending";

    /** 语言总数（中文 + 3 种译文）。 */
    private static final int LANG_TOTAL = 4;

    private final CaseLibraryMapper caseLibraryMapper;
    private final CaseLibraryTranslationMapper translationMapper;
    private final ArticleNoValidator articleNoValidator;
    private final BizNoGenerator bizNoGenerator;
    private final JsonMapper jsonMapper;

    /**
     * 案例列表（分页，rows/total）。
     *
     * @param query 查询入参
     * @return 分页结果
     */
    public TableDataInfo<AdminCaseLibraryRowVO> list(AdminCaseLibraryQueryBO query) {
        LambdaQueryWrapper<CaseLibrary> wrapper = Wrappers.<CaseLibrary>lambdaQuery()
                .isNull(CaseLibrary::getDeletedAt)
                .eq(StringUtils.hasText(query.getCategory()), CaseLibrary::getCategory, query.getCategory())
                .eq(StringUtils.hasText(query.getCaseType()), CaseLibrary::getCaseType, query.getCaseType())
                .eq(StringUtils.hasText(query.getStatus()), CaseLibrary::getStatus, query.getStatus())
                .orderByDesc(CaseLibrary::getUpdatedAt)
                .orderByDesc(CaseLibrary::getId);
        if (StringUtils.hasText(query.getKw())) {
            String kw = query.getKw().trim();
            wrapper.and(w -> w.like(CaseLibrary::getTitle, kw)
                    .or().like(CaseLibrary::getCaseNo, kw)
                    .or().like(CaseLibrary::getPlaintiffName, kw)
                    .or().like(CaseLibrary::getDefendantName, kw));
        }
        IPage<CaseLibrary> page = caseLibraryMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        List<CaseLibrary> records = page.getRecords();
        // 语言完成度：一次查出本页所有 translation，避免逐行查
        Map<Long, Integer> langCounts = countLangs(records);
        List<AdminCaseLibraryRowVO> rows = new ArrayList<>(records.size());
        for (CaseLibrary entity : records) {
            rows.add(toRow(entity, langCounts.getOrDefault(entity.getId(), 0)));
        }
        return TableDataInfo.of(rows, page.getTotal());
    }

    /**
     * 案例详情（录入抽屉回显）。
     *
     * @param id 案例主键
     * @return 详情
     * @throws BusinessException 409002 案例不存在
     */
    public AdminCaseLibraryDetailVO detail(Long id) {
        CaseLibrary entity = requireCase(id);
        AdminCaseLibraryDetailVO vo = new AdminCaseLibraryDetailVO();
        vo.setId(entity.getId());
        vo.setCaseNo(entity.getCaseNo());
        vo.setCourtCaseNo(entity.getCourtCaseNo());
        vo.setCourtName(entity.getCourtName());
        vo.setTitle(entity.getTitle());
        vo.setPlaintiffName(entity.getPlaintiffName());
        vo.setDefendantName(entity.getDefendantName());
        vo.setCategory(entity.getCategory());
        vo.setCaseType(entity.getCaseType());
        vo.setKeywords(entity.getKeywords());
        vo.setSource(entity.getSource());
        vo.setJudgmentDate(entity.getJudgmentDate());
        vo.setSummary(entity.getSummary());
        vo.setDisputeFocus(entity.getDisputeFocus());
        vo.setJudgment(entity.getJudgment());
        vo.setInsight(entity.getInsight());
        vo.setLawRefs(parseLawRefs(entity.getLawRefs()));
        List<CaseLibraryTranslation> translations = listTranslations(id);
        vo.setTranslations(translations.stream().map(this::toTranslationBO).toList());
        vo.setLangCount(1 + translations.size());
        vo.setStatus(entity.getStatus());
        vo.setPublishedAt(entity.getPublishedAt());
        vo.setSourceUpdatedAt(entity.getSourceUpdatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    /**
     * 新建案例。
     *
     * @param bo 保存入参
     * @return 新案例主键
     * @throws BusinessException 409005 法条条号不存在 / 400001 参数非法
     */
    @OperationLog(module = "content", action = "CASE_LIBRARY_CREATE", targetType = "CASE_LIBRARY")
    @Transactional(rollbackFor = Exception.class)
    public Long create(AdminCaseLibrarySaveBO bo) {
        validateLawRefs(bo.getLawRefs());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CaseLibrary entity = new CaseLibrary();
        entity.setStatus(STATUS_DRAFT);
        entity.setViewCount(0);
        applyFields(entity, bo);
        entity.setSourceUpdatedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        BizNoRetry.insertWithNo("uk_case_library_no", bizNoGenerator::nextCaseLibraryNo, caseNo -> {
            entity.setCaseNo(caseNo);
            return caseLibraryMapper.insert(entity);
        });
        saveTranslations(entity.getId(), bo.getTranslations(), now);
        AuditContext.mark("content", "CASE_LIBRARY_CREATE", "CASE_LIBRARY",
                String.valueOf(entity.getId()),
                detailJson(Map.of("caseNo", entity.getCaseNo(), "category", bo.getCategory())));
        log.info("案例已新建 caseId={} caseNo={} category={}",
                entity.getId(), entity.getCaseNo(), bo.getCategory());
        return entity.getId();
    }

    /**
     * 保存案例（全量覆盖可空字段）。
     *
     * @param id 案例主键
     * @param bo 保存入参
     * @throws BusinessException 409002 不存在 / 409005 条号非法
     */
    @OperationLog(module = "content", action = "CASE_LIBRARY_UPDATE", targetType = "CASE_LIBRARY")
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AdminCaseLibrarySaveBO bo) {
        CaseLibrary entity = requireCase(id);
        validateLawRefs(bo.getLawRefs());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        applyFields(entity, bo);
        entity.setSourceUpdatedAt(now);
        entity.setUpdatedAt(now);
        // 已上线的案例改内容后要重新审视译文是否过期：不在保存时自动下架，
        // 只在 PC 列表用 source_updated_at 与译文 translated_from 比出「过期」提示。
        caseLibraryMapper.updateById(entity);
        saveTranslations(id, bo.getTranslations(), now);
        AuditContext.mark("content", "CASE_LIBRARY_UPDATE", "CASE_LIBRARY",
                String.valueOf(id),
                detailJson(Map.of("caseNo", String.valueOf(entity.getCaseNo()),
                        "status", String.valueOf(entity.getStatus()))));
        log.info("案例已保存 caseId={}", id);
    }

    /**
     * 设为上线（G4 门禁：源语言四块正文必须齐全）。
     *
     * @param id 案例主键
     * @throws BusinessException 409002 不存在 / 409006 正文不全 / 409005 无法条引用
     */
    @OperationLog(module = "content", action = "CASE_LIBRARY_PUBLISH", targetType = "CASE_LIBRARY")
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long id) {
        CaseLibrary entity = requireCase(id);
        if (STATUS_PUBLISHED.equals(entity.getStatus())) {
            return;
        }
        // 上线门禁：源语言四块正文齐全（G4 第 1 条）。译文允许 pending，不在这里拦。
        if (!StringUtils.hasText(entity.getTitle())
                || !StringUtils.hasText(entity.getSummary())
                || !StringUtils.hasText(entity.getDisputeFocus())
                || !StringUtils.hasText(entity.getJudgment())
                || !StringUtils.hasText(entity.getInsight())) {
            throw new BusinessException(ErrorCode.CONTENT_CASE_LANG_INCOMPLETE,
                    "源语言正文四块未填全，不能上线");
        }
        // 5-A7 要求详情页法条引用正常渲染：没有引用就上线等于交付一个空白块
        if (parseLawRefs(entity.getLawRefs()).isEmpty()) {
            throw new BusinessException(ErrorCode.CONTENT_CASE_NO_LAW_REF);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        caseLibraryMapper.update(null, Wrappers.<CaseLibrary>lambdaUpdate()
                .set(CaseLibrary::getStatus, STATUS_PUBLISHED)
                .set(CaseLibrary::getPublishedAt, entity.getPublishedAt() == null
                        ? now : entity.getPublishedAt())
                .set(CaseLibrary::getUpdatedAt, now)
                .eq(CaseLibrary::getId, id)
                .isNull(CaseLibrary::getDeletedAt));
        AuditContext.mark("content", "CASE_LIBRARY_PUBLISH", "CASE_LIBRARY",
                String.valueOf(id),
                detailJson(Map.of("before", STATUS_DRAFT, "after", STATUS_PUBLISHED,
                        "caseNo", String.valueOf(entity.getCaseNo()))));
        log.info("案例已上线 caseId={} caseNo={}", id, entity.getCaseNo());
    }

    /**
     * 下架（已上线案例必须先下架才能删）。
     *
     * @param id 案例主键
     * @throws BusinessException 409002 不存在
     */
    @OperationLog(module = "content", action = "CASE_LIBRARY_OFFLINE", targetType = "CASE_LIBRARY")
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long id) {
        CaseLibrary entity = requireCase(id);
        if (STATUS_OFFLINE.equals(entity.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        caseLibraryMapper.update(null, Wrappers.<CaseLibrary>lambdaUpdate()
                .set(CaseLibrary::getStatus, STATUS_OFFLINE)
                .set(CaseLibrary::getUpdatedAt, now)
                .eq(CaseLibrary::getId, id)
                .isNull(CaseLibrary::getDeletedAt));
        AuditContext.mark("content", "CASE_LIBRARY_OFFLINE", "CASE_LIBRARY",
                String.valueOf(id),
                detailJson(Map.of("before", String.valueOf(entity.getStatus()),
                        "after", STATUS_OFFLINE)));
        log.info("案例已下架 caseId={}", id);
    }

    /**
     * 批量下架（G1 第 9 条：V1 只做批量下架，不做批量导出）。
     *
     * @param ids 案例主键列表
     * @return 实际下架条数
     */
    @OperationLog(module = "content", action = "CASE_LIBRARY_OFFLINE", targetType = "CASE_LIBRARY")
    @Transactional(rollbackFor = Exception.class)
    public int offlineBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = caseLibraryMapper.update(null, Wrappers.<CaseLibrary>lambdaUpdate()
                .set(CaseLibrary::getStatus, STATUS_OFFLINE)
                .set(CaseLibrary::getUpdatedAt, now)
                .in(CaseLibrary::getId, ids)
                .isNull(CaseLibrary::getDeletedAt));
        AuditContext.mark("content", "CASE_LIBRARY_OFFLINE", "CASE_LIBRARY",
                String.valueOf(ids.size()), detailJson(Map.of("ids", ids, "rows", rows)));
        log.info("批量下架完成 count={} ids={}", rows, ids);
        return rows;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 把入参写进实体（不含 id / caseNo / 时间字段）。
     *
     * @param entity 实体
     * @param bo     入参
     */
    private void applyFields(CaseLibrary entity, AdminCaseLibrarySaveBO bo) {
        entity.setTitle(bo.getTitle().trim());
        CaseCategory category = CaseCategory.ofOrNull(bo.getCategory());
        if (category == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "分类非法");
        }
        entity.setCategory(category.getCode());
        // 大类由细分推导——不许两处都填（G1 第 2 条）
        entity.setCaseType(category.getBigType().getCode());
        entity.setPlaintiffName(bo.getPlaintiffName());
        entity.setDefendantName(bo.getDefendantName());
        entity.setKeywords(bo.getKeywords());
        entity.setSource(bo.getSource() == null ? "已脱敏改编" : bo.getSource());
        entity.setCourtCaseNo(bo.getCourtCaseNo());
        entity.setCourtName(bo.getCourtName());
        entity.setJudgmentDate(bo.getJudgmentDate());
        entity.setSummary(bo.getSummary());
        entity.setDisputeFocus(bo.getDisputeFocus());
        entity.setJudgment(bo.getJudgment());
        entity.setInsight(bo.getInsight());
        entity.setLawRefs(writeLawRefs(bo.getLawRefs()));
    }

    /**
     * 校验法条引用（条号必须在法条库存在）。
     *
     * @param refs 引用列表
     * @throws BusinessException 409005 条号不存在
     */
    private void validateLawRefs(List<AdminLawRefBO> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (AdminLawRefBO ref : refs) {
            boolean found = articleNoValidator.findByArticleNo(ref.getLawName(), ref.getArticleNo())
                    .isPresent();
            if (!found) {
                throw new BusinessException(ErrorCode.CONTENT_CASE_NO_LAW_REF,
                        "法条不存在：" + ref.getLawName() + " " + ref.getArticleNo());
            }
        }
    }

    /**
     * 保存译文（按 lang 覆盖）。
     *
     * @param caseId       案例主键
     * @param translations 译文列表
     * @param now          当前 UTC
     */
    private void saveTranslations(Long caseId, List<AdminCaseTranslationBO> translations,
                                  LocalDateTime now) {
        if (translations == null || translations.isEmpty()) {
            return;
        }
        for (AdminCaseTranslationBO bo : translations) {
            if (bo.getLang() == null || !TRANSLATION_LANGS.contains(bo.getLang())) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID,
                        "译文语言只允许 bo / ug-Arab-CN / kk-Arab-CN");
            }
            CaseLibraryTranslation existing = translationMapper.selectOne(
                    Wrappers.<CaseLibraryTranslation>lambdaQuery()
                            .eq(CaseLibraryTranslation::getCaseId, caseId)
                            .eq(CaseLibraryTranslation::getLang, bo.getLang()));
            CaseLibraryTranslation entity = existing == null ? new CaseLibraryTranslation() : existing;
            entity.setCaseId(caseId);
            entity.setLang(bo.getLang());
            entity.setTitle(bo.getTitle());
            entity.setSummary(bo.getSummary());
            entity.setDisputeFocus(bo.getDisputeFocus());
            entity.setJudgment(bo.getJudgment());
            entity.setInsight(bo.getInsight());
            entity.setStatus(StringUtils.hasText(bo.getStatus()) ? bo.getStatus() : TRANS_PENDING);
            entity.setTranslatedFrom(now);
            entity.setUpdatedAt(now);
            if (existing == null) {
                entity.setCreatedAt(now);
                translationMapper.insert(entity);
            } else {
                translationMapper.updateById(entity);
            }
        }
    }

    /**
     * 取案例（不存在或已删即 409002）。
     *
     * @param id 主键
     * @return 实体
     * @throws BusinessException 409002
     */
    private CaseLibrary requireCase(Long id) {
        CaseLibrary entity = id == null ? null : caseLibraryMapper.selectById(id);
        if (entity == null || entity.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CASE_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 取某案例的全部译文。
     *
     * @param caseId 案例主键
     * @return 译文列表
     */
    private List<CaseLibraryTranslation> listTranslations(Long caseId) {
        return translationMapper.selectList(Wrappers.<CaseLibraryTranslation>lambdaQuery()
                .eq(CaseLibraryTranslation::getCaseId, caseId)
                .orderByAsc(CaseLibraryTranslation::getLang));
    }

    /**
     * 批量统计每条例案的译文数量（语言完成度的分子减 1）。
     *
     * @param records 本页案例
     * @return 案例主键 → 译文条数
     */
    private Map<Long, Integer> countLangs(List<CaseLibrary> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = records.stream().map(CaseLibrary::getId).toList();
        List<CaseLibraryTranslation> translations = translationMapper.selectList(
                Wrappers.<CaseLibraryTranslation>lambdaQuery()
                        .in(CaseLibraryTranslation::getCaseId, ids));
        Map<Long, Integer> counts = new HashMap<>();
        for (CaseLibraryTranslation translation : translations) {
            counts.merge(translation.getCaseId(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 实体 → 列表行。
     *
     * @param entity        实体
     * @param translationCount 译文条数（0~3）
     * @return 列表行
     */
    private AdminCaseLibraryRowVO toRow(CaseLibrary entity, int translationCount) {
        AdminCaseLibraryRowVO vo = new AdminCaseLibraryRowVO();
        vo.setId(entity.getId());
        vo.setCaseNo(entity.getCaseNo());
        vo.setTitle(entity.getTitle());
        vo.setPlaintiffName(entity.getPlaintiffName());
        vo.setDefendantName(entity.getDefendantName());
        vo.setCategory(entity.getCategory());
        CaseCategory category = CaseCategory.ofOrNull(entity.getCategory());
        vo.setCategoryLabel(category == null ? entity.getCategory() : category.getLabel());
        vo.setCaseType(entity.getCaseType());
        CaseLibraryType type = CaseLibraryType.ofOrNull(entity.getCaseType());
        vo.setCaseTypeLabel(type == null ? entity.getCaseType() : type.getLabel());
        vo.setLawRefText(buildLawRefText(parseLawRefs(entity.getLawRefs())));
        vo.setLangCount(1 + translationCount);
        vo.setStatus(entity.getStatus());
        vo.setPublishedAt(entity.getPublishedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    /**
     * 拼「适用法条」列的可读串。
     *
     * @param refs 引用列表
     * @return 形如「民法典 第六百八十条、合同法 第九条」；无引用返回空串
     */
    private String buildLawRefText(List<AdminLawRefBO> refs) {
        if (refs.isEmpty()) {
            return "";
        }
        return refs.stream()
                .map(ref -> shortLawName(ref.getLawName()) + " " + ref.getArticleNo())
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
    }

    /**
     * 法律全称 → 列表里用的简称（去掉「中华人民共和国」前缀）。
     *
     * @param lawName 法律全称
     * @return 简称；为空时返回空串
     */
    private String shortLawName(String lawName) {
        if (!StringUtils.hasText(lawName)) {
            return "";
        }
        return lawName.replace("中华人民共和国", "");
    }

    /**
     * 序列化法条引用。
     *
     * @param refs 引用列表
     * @return JSON 字符串；空列表返回 null
     */
    private String writeLawRefs(List<AdminLawRefBO> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(refs);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "法条引用格式非法");
        }
    }

    /**
     * 反序列化法条引用（脏数据按空处理，不让整页挂掉）。
     *
     * @param raw JSON 字符串
     * @return 引用列表；解析失败返回空列表
     */
    private List<AdminLawRefBO> parseLawRefs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            List<AdminLawRefBO> parsed = jsonMapper.readValue(raw,
                    new TypeReference<List<AdminLawRefBO>>() { });
            return parsed == null ? List.of() : parsed;
        } catch (RuntimeException e) {
            log.warn("法条引用 JSON 解析失败，按空处理");
            return List.of();
        }
    }

    /**
     * 译文实体 → 入参结构（详情回显与保存结构同构，前端不用写两套）。
     *
     * @param entity 译文实体
     * @return 译文 BO
     */
    private AdminCaseTranslationBO toTranslationBO(CaseLibraryTranslation entity) {
        AdminCaseTranslationBO bo = new AdminCaseTranslationBO();
        bo.setLang(entity.getLang());
        bo.setTitle(entity.getTitle());
        bo.setSummary(entity.getSummary());
        bo.setDisputeFocus(entity.getDisputeFocus());
        bo.setJudgment(entity.getJudgment());
        bo.setInsight(entity.getInsight());
        bo.setStatus(entity.getStatus());
        return bo;
    }

    /**
     * 序列化审计 detail。
     *
     * @param payload 结构化明细
     * @return JSON；失败返回 null
     */
    private String detailJson(Map<String, Object> payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("审计 detail 序列化失败");
            return null;
        }
    }
}
