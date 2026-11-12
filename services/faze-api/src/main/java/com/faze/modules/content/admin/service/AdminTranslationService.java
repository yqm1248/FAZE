/*
 * 职责：PC A-17 多语言翻译管理的业务实现（译文队列 + 术语表）。
 * 调用方：modules/content/admin/controller/AdminTranslationController。
 * 数据来源：`article_translation`（译文）、`article`（中文源标题/源文更新时间）、
 *          `i18n_glossary`（术语表）。**不新增表**。
 * 注意：
 *   1. 为什么单独成服务而不并入 `AdminArticleService`：A-15 文章编辑是「按文章看译文」，
 *      A-17 是「按译文看文章」——聚合维度相反。塞进一个服务会让两页的查询条件互相污染。
 *   2. **译文状态不控制可见性**：可见性看主表 `article.status`（《技术路线》§5.8.1）。
 *      本页把 status 改成 published 不会让文章上线，只在队列里推进阶段。
 *   3. 写操作的审计 `@OperationLog` 标在**本类方法**上，detail 存 before/after
 *      （只存标题/状态/译者这几个可判别字段，正文太大不进审计）。
 *   4. 术语表**不许机翻**（技术路线 §13.8）：本类只负责把人工译者的译文存进去，
 *      不提供任何自动翻译入口。机翻辅助入口属设计稿画了但本段明确不做的那一项。
 */
package com.faze.modules.content.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.common.util.Pages;
import com.faze.modules.ai.entity.I18nGlossary;
import com.faze.modules.ai.mapper.I18nGlossaryMapper;
import com.faze.modules.content.admin.bo.AdminGlossaryQueryBO;
import com.faze.modules.content.admin.bo.AdminGlossarySaveBO;
import com.faze.modules.content.admin.bo.AdminTranslationQueryBO;
import com.faze.modules.content.admin.bo.AdminTranslationSaveBO;
import com.faze.modules.content.admin.vo.AdminGlossaryRowVO;
import com.faze.modules.content.admin.vo.AdminTranslationDetailVO;
import com.faze.modules.content.admin.vo.AdminTranslationRowVO;
import com.faze.modules.content.entity.Article;
import com.faze.modules.content.entity.ArticleTranslation;
import com.faze.modules.content.mapper.ArticleMapper;
import com.faze.modules.content.mapper.ArticleTranslationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * PC 多语言翻译管理服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTranslationService {

    private static final String MODULE = "content";
    private static final String TARGET_TRANSLATION = "ARTICLE_TRANSLATION";
    private static final String TARGET_GLOSSARY = "I18N_GLOSSARY";
    /** 允许出现的译文语言（zh-Hans 是源语言，不在这张表里）。 */
    private static final Set<String> TRANSLATION_LANGS = Set.of("bo", "ug-Arab-CN", "kk-Arab-CN");
    private static final Set<String> TRANSLATION_STATUS = Set.of("draft", "reviewed", "published");
    private static final Set<String> GLOSSARY_STATUS = Set.of("confirmed", "pending");
    private static final String DEFAULT_LANG = "zh-Hans";

    private final ArticleTranslationMapper translationMapper;
    private final ArticleMapper articleMapper;
    private final I18nGlossaryMapper glossaryMapper;
    private final JsonMapper jsonMapper;

    /**
     * 译文队列（A-17 左栏）。
     *
     * @param query 语言 / 状态 / 关键词 / 分页
     * @return RuoYi 分页响应
     * @throws BusinessException 401001 语言或状态不在白名单
     */
    public TableDataInfo<AdminTranslationRowVO> listTranslations(AdminTranslationQueryBO query) {
        String lang = normalizeLang(query.getLang());
        String status = normalizeEnum(query.getStatus(), TRANSLATION_STATUS);
        IPage<ArticleTranslation> page = translationMapper.selectPage(
                Pages.of(query.getPageNum(), query.getPageSize()),
                Wrappers.<ArticleTranslation>lambdaQuery()
                        .eq(lang != null, ArticleTranslation::getLang, lang)
                        .eq(status != null, ArticleTranslation::getStatus, status)
                        .like(StringUtils.hasText(query.getKeyword()),
                                ArticleTranslation::getTitle, query.getKeyword())
                        .orderByDesc(ArticleTranslation::getUpdatedAt)
                        .orderByDesc(ArticleTranslation::getId));
        Map<Long, Article> articles = loadArticles(page.getRecords());
        List<AdminTranslationRowVO> rows = page.getRecords().stream()
                .map(row -> toRow(row, articles.get(row.getArticleId())))
                .toList();
        return TableDataInfo.of(rows, page.getTotal());
    }

    /**
     * 译文详情（含正文）。
     *
     * @param id 译文主键
     * @return 详情
     * @throws BusinessException 401004 不存在
     */
    public AdminTranslationDetailVO detail(Long id) {
        ArticleTranslation row = requireTranslation(id);
        Article article = row.getArticleId() == null ? null : articleMapper.selectById(row.getArticleId());
        AdminTranslationDetailVO vo = new AdminTranslationDetailVO();
        fillRow(vo, row, article);
        vo.setContent(row.getContent());
        vo.setSourceUpdatedAt(article == null ? null : article.getSourceUpdatedAt());
        vo.setTranslatedFrom(row.getTranslatedFrom());
        return vo;
    }

    /**
     * 保存一条译文（A-17 编辑）。
     *
     * <p>前置：译文存在；`status` 在白名单内。</p>
     * <p>副作用：更新 `article_translation`；写 `operation_log`（before/after 只存可判别字段）。</p>
     *
     * @param id      译文主键
     * @param request 保存内容
     * @throws BusinessException 401004 不存在 / 401001 状态非法
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "TRANSLATION_UPDATED", targetType = TARGET_TRANSLATION)
    public void updateTranslation(Long id, AdminTranslationSaveBO request) {
        ArticleTranslation row = requireTranslation(id);
        String status = normalizeEnum(request.getStatus(), TRANSLATION_STATUS);
        if (status == null) {
            status = row.getStatus();
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        translationMapper.update(null, Wrappers.<ArticleTranslation>lambdaUpdate()
                .set(request.getTitle() != null, ArticleTranslation::getTitle, request.getTitle())
                .set(request.getSummary() != null, ArticleTranslation::getSummary, request.getSummary())
                .set(request.getContent() != null, ArticleTranslation::getContent, request.getContent())
                .set(ArticleTranslation::getStatus, status)
                .set(ArticleTranslation::getTranslator, request.getTranslator())
                .set(ArticleTranslation::getTranslationEngine, request.getTranslationEngine())
                .set(ArticleTranslation::getUpdatedAt, now)
                .eq(ArticleTranslation::getId, id));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", row.getStatus());
        before.put("translator", row.getTranslator());
        before.put("titleLength", lengthOf(row.getTitle()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("translator", request.getTranslator());
        after.put("titleLength", lengthOf(request.getTitle() == null ? row.getTitle() : request.getTitle()));
        AuditContext.mark(MODULE, "TRANSLATION_UPDATED", TARGET_TRANSLATION, String.valueOf(id),
                detailJson(before, after));
        log.info("译文已保存 translationId={} lang={} status={}", id, row.getLang(), status);
    }

    /**
     * 术语表列表（A-17 右栏）。
     *
     * @param query 语言 / 关键词 / 只看待填 / 分页
     * @return RuoYi 分页响应
     */
    public TableDataInfo<AdminGlossaryRowVO> listGlossary(AdminGlossaryQueryBO query) {
        String lang = StringUtils.hasText(query.getLang()) ? query.getLang().trim() : DEFAULT_LANG;
        boolean pendingOnly = Boolean.TRUE.equals(query.getPendingOnly());
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
        IPage<I18nGlossary> page = glossaryMapper.selectPage(
                Pages.of(query.getPageNum(), query.getPageSize()),
                Wrappers.<I18nGlossary>lambdaQuery()
                        .eq(I18nGlossary::getLang, lang)
                        .and(pendingOnly, w -> w.isNull(I18nGlossary::getTargetText)
                                .or()
                                .eq(I18nGlossary::getTargetText, ""))
                        .and(keyword != null, w -> w.like(I18nGlossary::getTermKey, keyword)
                                .or()
                                .like(I18nGlossary::getSourceText, keyword))
                        .orderByAsc(I18nGlossary::getId));
        return TableDataInfo.of(page.getRecords().stream().map(this::toGlossaryRow).toList(), page.getTotal());
    }

    /**
     * 保存术语译文（A-17 右栏编辑）。
     *
     * <p>前置：术语存在；`status` 在白名单内。</p>
     * <p>副作用：更新 `i18n_glossary`；写 `operation_log`。</p>
     *
     * @param id      术语主键
     * @param request 保存内容
     * @throws BusinessException 401004 不存在 / 401001 状态非法
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "GLOSSARY_UPDATED", targetType = TARGET_GLOSSARY)
    public void updateGlossary(Long id, AdminGlossarySaveBO request) {
        I18nGlossary row = glossaryMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        String status = normalizeEnum(request.getStatus(), GLOSSARY_STATUS);
        if (status == null) {
            status = row.getStatus();
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // targetText 无条件写：请求里 null 表示"不改"，保留原值；空串表示"清空，退回待填"。
        // 不能包在条件 set 里，否则"清空"和"不改"就没法区分了。
        String nextTargetText = request.getTargetText() == null ? row.getTargetText() : request.getTargetText();
        glossaryMapper.update(null, Wrappers.<I18nGlossary>lambdaUpdate()
                .set(I18nGlossary::getTargetText, nextTargetText)
                .set(request.getNote() != null, I18nGlossary::getNote, request.getNote())
                .set(I18nGlossary::getStatus, status)
                .set(request.getEnabled() != null, I18nGlossary::getIsEnabled,
                        Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0)
                .set(I18nGlossary::getUpdatedAt, now)
                .eq(I18nGlossary::getId, id));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("hasTargetText", StringUtils.hasText(row.getTargetText()));
        before.put("status", row.getStatus());
        before.put("enabled", row.getIsEnabled());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("hasTargetText", StringUtils.hasText(request.getTargetText()));
        after.put("status", status);
        after.put("enabled", request.getEnabled());
        AuditContext.mark(MODULE, "GLOSSARY_UPDATED", TARGET_GLOSSARY, String.valueOf(id),
                detailJson(before, after));
        log.info("术语已保存 glossaryId={} termKey={}", id, row.getTermKey());
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 取译文，取不到 401004。
     *
     * @param id 译文主键
     * @return 实体
     * @throws BusinessException 401004
     */
    private ArticleTranslation requireTranslation(Long id) {
        ArticleTranslation row = translationMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return row;
    }

    /**
     * 批量取文章（一次查询，避免逐行查库）。
     *
     * @param rows 译文行
     * @return articleId → Article
     */
    private Map<Long, Article> loadArticles(List<ArticleTranslation> rows) {
        List<Long> ids = rows.stream().map(ArticleTranslation::getArticleId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return articleMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, Function.identity(), (a, b) -> a));
    }

    /**
     * 实体 → 行对象。
     *
     * @param row     译文
     * @param article 文章（可空）
     * @return 行对象
     */
    private AdminTranslationRowVO toRow(ArticleTranslation row, Article article) {
        AdminTranslationRowVO vo = new AdminTranslationRowVO();
        fillRow(vo, row, article);
        return vo;
    }

    /**
     * 填充行对象的公共字段（列表与详情共用）。
     *
     * @param vo      目标行对象
     * @param row     译文
     * @param article 文章（可空）
     */
    private void fillRow(AdminTranslationRowVO vo, ArticleTranslation row, Article article) {
        vo.setId(row.getId());
        vo.setArticleId(row.getArticleId());
        vo.setArticleTitle(article == null ? null : article.getTitle());
        vo.setLang(row.getLang());
        vo.setTitle(row.getTitle());
        vo.setSummary(row.getSummary());
        vo.setStatus(row.getStatus());
        vo.setTranslator(row.getTranslator());
        vo.setTranslationEngine(row.getTranslationEngine());
        vo.setUpdatedAt(row.getUpdatedAt());
        vo.setOutdated(isOutdated(row, article));
    }

    /**
     * 译文是否过期：源文在翻译之后又更新过。
     *
     * @param row     译文
     * @param article 文章（可空）
     * @return 过期返回 true
     */
    private boolean isOutdated(ArticleTranslation row, Article article) {
        if (article == null || article.getSourceUpdatedAt() == null || row.getTranslatedFrom() == null) {
            return false;
        }
        return article.getSourceUpdatedAt().isAfter(row.getTranslatedFrom());
    }

    /**
     * 术语实体 → 行对象。
     *
     * @param row 术语
     * @return 行对象
     */
    private AdminGlossaryRowVO toGlossaryRow(I18nGlossary row) {
        AdminGlossaryRowVO vo = new AdminGlossaryRowVO();
        vo.setId(row.getId());
        vo.setTermKey(row.getTermKey());
        vo.setSourceText(row.getSourceText());
        vo.setLang(row.getLang());
        vo.setTargetText(row.getTargetText());
        vo.setNote(row.getNote());
        vo.setStatus(row.getStatus());
        vo.setEnabled(row.getIsEnabled() == null || row.getIsEnabled() == 1);
        vo.setFrozenAt(row.getFrozenAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }

    /**
     * 语言白名单校验：只允许三种非中文。
     *
     * @param raw 原值（可空）
     * @return 规范化后的语言码；空值返回 null
     * @throws BusinessException 401001 不在白名单（含 zh-Hans——它是源语言，不在译文表里）
     */
    private String normalizeLang(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (!TRANSLATION_LANGS.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 枚举白名单校验。
     *
     * @param raw    原值（可空）
     * @param allows 允许值（小写）
     * @return 规范化后的值；空值返回 null
     * @throws BusinessException 401001 不在白名单
     */
    private String normalizeEnum(String raw, Set<String> allows) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!allows.contains(value)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        return value;
    }

    /**
     * 取字符串长度（null 记 0）。
     *
     * @param text 文本
     * @return 长度
     */
    private int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 写审计 detail（before/after）。
     *
     * @param before 变更前
     * @param after  变更后
     * @return JSON
     */
    private String detailJson(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        return jsonMapper.writeValueAsString(detail);
    }
}
