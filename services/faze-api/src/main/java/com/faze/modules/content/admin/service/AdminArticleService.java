/*
 * 职责：PC A-14 / A-15 的文章管理——列表（含四语言完成度）、详情、新建、保存、上架、下架（G9.3 / G9.4）。
 * 调用方：modules/content/admin/controller/AdminArticleController。
 * 数据来源：faze.article（读写）、faze.article_translation（读写）。
 * 注意：
 *   1. **上架才校验四语言齐全**（工作流 G9.4 第 3 条 / 总纲 §5.3 L375 的硬要求）：
 *      源语言（主表）+ bo / ug-Arab-CN / kk-Arab-CN 三种译文都必须有 title 与 content。
 *      草稿可以只填一部分（`status='draft'`），但 `publish` 不齐全直接拒并**列出缺哪几种语言**
 *      （只说「不齐全」运营得自己猜）。
 *   2. **保存时 `status` 不许直接传 `published`**：上架必须走 publish 接口，
 *      绕过校验的入口一个都不能留（编辑页保存按钮传 published 会被拒）。
 *   3. 四个写方法各标一个 `@OperationLog`（模块 content，动作 ARTICLE_CREATE / ARTICLE_UPDATE /
 *      ARTICLE_PUBLISH / ARTICLE_OFFLINE）：审计规范要求管理端写接口默认必须审计。
 *   4. `content` 只校验「是合法 JSON 数组」：块类型是内容域约定，服务端不锁枚举
 *      （锁了以后运营加新块类型要发版）。
 *   5. 译文 upsert 按 `uk_art_trans (article_id, lang)`：存在则更新，不存在则插入。
 *   6. 列表的语言完成度**一次聚合算完**（按 articleIds 批量查译文），不做逐行查询。
 */
package com.faze.modules.content.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.content.admin.bo.AdminArticleQueryBO;
import com.faze.modules.content.admin.bo.AdminArticleSaveBO;
import com.faze.modules.content.admin.bo.AdminArticleTranslationBO;
import com.faze.modules.content.admin.vo.AdminArticleDetailVO;
import com.faze.modules.content.admin.vo.AdminArticleRowVO;
import com.faze.modules.content.admin.vo.AdminArticleTranslationVO;
import com.faze.modules.content.entity.Article;
import com.faze.modules.content.entity.ArticleTranslation;
import com.faze.modules.content.mapper.ArticleMapper;
import com.faze.modules.content.mapper.ArticleTranslationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PC 文章管理服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminArticleService {

    /** 全部语言（含源语言），顺序固定便于前端展示。 */
    public static final List<String> ALL_LANGS = List.of("zh-Hans", "bo", "ug-Arab-CN", "kk-Arab-CN");

    /** 非源语言（译文）。 */
    private static final Set<String> TRANSLATION_LANGS = Set.of("bo", "ug-Arab-CN", "kk-Arab-CN");

    /** 每页条数上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 状态：草稿。 */
    private static final String STATUS_DRAFT = "draft";

    /** 状态：已上架。 */
    private static final String STATUS_PUBLISHED = "published";

    /** 状态：已归档。 */
    private static final String STATUS_ARCHIVED = "archived";

    private final ArticleMapper articleMapper;
    private final ArticleTranslationMapper articleTranslationMapper;
    private final JsonMapper jsonMapper;

    /**
     * 分页查文章（A-14）。
     *
     * @param query 筛选条件
     * @return RuoYi 口径分页响应（带语言完成度）
     */
    public TableDataInfo<AdminArticleRowVO> list(AdminArticleQueryBO query) {
        int pageNum = query == null || query.getPageNum() == null || query.getPageNum() < 1
                ? 1 : query.getPageNum();
        int pageSize = query == null || query.getPageSize() == null
                ? 10 : Math.min(Math.max(query.getPageSize(), 1), MAX_PAGE_SIZE);
        String status = StringUtils.hasText(query == null ? null : query.getStatus())
                ? query.getStatus().trim() : null;
        String category = StringUtils.hasText(query == null ? null : query.getCategory())
                ? query.getCategory().trim() : null;
        String keyword = StringUtils.hasText(query == null ? null : query.getKeyword())
                ? query.getKeyword().trim() : null;
        String lang = StringUtils.hasText(query == null ? null : query.getLang())
                ? query.getLang().trim() : null;

        IPage<Article> page = articleMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<Article>lambdaQuery()
                        .isNull(Article::getDeletedAt)
                        .eq(status != null, Article::getStatus, status)
                        .eq(category != null, Article::getCategory, category)
                        .like(keyword != null, Article::getTitle, keyword)
                        .orderByDesc(Article::getSortOrder)
                        .orderByDesc(Article::getUpdatedAt));

        List<Article> rows = page.getRecords();
        Map<Long, List<ArticleTranslation>> translations = loadTranslations(
                rows.stream().map(Article::getId).collect(Collectors.toSet()));
        List<AdminArticleRowVO> list = rows.stream()
                .map(article -> toRow(article, translations.getOrDefault(article.getId(), List.of())))
                .filter(row -> lang == null || row.getLanguages().contains(lang))
                .collect(Collectors.toList());
        return TableDataInfo.of(list, page.getTotal());
    }

    /**
     * 查文章详情（A-15）。
     *
     * @param id 文章主键
     * @return 详情（源内容 + 已存在的译文）
     * @throws BusinessException 401004 文章不存在
     */
    public AdminArticleDetailVO detail(Long id) {
        Article article = requireArticle(id);
        AdminArticleDetailVO vo = new AdminArticleDetailVO();
        vo.setId(article.getId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setContent(article.getContent());
        vo.setCategory(article.getCategory());
        vo.setCoverOssKey(article.getCoverOssKey());
        vo.setSortOrder(article.getSortOrder());
        vo.setStatus(article.getStatus());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setSourceUpdatedAt(article.getSourceUpdatedAt());
        vo.setTranslations(articleTranslationMapper.selectList(Wrappers.<ArticleTranslation>lambdaQuery()
                        .eq(ArticleTranslation::getArticleId, id))
                .stream().map(this::toTranslationVO).collect(Collectors.toList()));
        return vo;
    }

    /**
     * 新建文章（A-15）。
     *
     * @param request 保存入参
     * @return 新文章主键
     * @throws BusinessException 401001 正文不是合法 JSON 数组 / 401006 状态非法
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = "content", action = "ARTICLE_CREATE", targetType = "ARTICLE")
    public Long create(AdminArticleSaveBO request) {
        requireDraftStatus(request.getStatus());
        String content = requireContentJson(request.getContent(), "正文");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Article article = new Article();
        article.setTitle(request.getTitle().trim());
        article.setSummary(request.getSummary());
        article.setContent(content);
        article.setCategory(request.getCategory());
        article.setCoverOssKey(request.getCoverOssKey());
        article.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        article.setStatus(STATUS_DRAFT);
        article.setSourceUpdatedAt(now);
        article.setViewCount(0);
        article.setCreatedAt(now);
        article.setUpdatedAt(now);
        articleMapper.insert(article);
        saveTranslations(article.getId(), request.getTranslations(), now);
        log.info("PC 新建文章 articleId={} titleLength={}", article.getId(), article.getTitle().length());
        return article.getId();
    }

    /**
     * 保存文章（A-15）。
     *
     * @param id      文章主键
     * @param request 保存入参
     * @throws BusinessException 401004 文章不存在 / 401001 正文非法 / 401006 状态非法
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = "content", action = "ARTICLE_UPDATE", targetType = "ARTICLE")
    public void update(Long id, AdminArticleSaveBO request) {
        Article article = requireArticle(id);
        requireDraftStatus(request.getStatus());
        String content = requireContentJson(request.getContent(), "正文");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 源文改动就更新 source_updated_at：它是「译文是否过期」的判定基准
        boolean sourceChanged = !content.equals(article.getContent())
                || !request.getTitle().trim().equals(article.getTitle())
                || (request.getSummary() != null && !request.getSummary().equals(article.getSummary()));
        articleMapper.update(null, Wrappers.<Article>lambdaUpdate()
                .eq(Article::getId, id)
                .set(Article::getTitle, request.getTitle().trim())
                .set(Article::getSummary, request.getSummary())
                .set(Article::getContent, content)
                .set(Article::getCategory, request.getCategory())
                .set(Article::getCoverOssKey, request.getCoverOssKey())
                .set(Article::getSortOrder, request.getSortOrder() == null ? 0 : request.getSortOrder())
                .set(Article::getUpdatedAt, now)
                .set(sourceChanged, Article::getSourceUpdatedAt, now));
        saveTranslations(id, request.getTranslations(), now);
        log.info("PC 保存文章 articleId={} sourceChanged={}", id, sourceChanged);
    }

    /**
     * 上架（A-15）：四语言齐全才允许。
     *
     * @param id 文章主键
     * @throws BusinessException 401004 文章不存在 / 401006 四语言不齐全（msg 列出缺失语言）
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = "content", action = "ARTICLE_PUBLISH", targetType = "ARTICLE")
    public void publish(Long id) {
        Article article = requireArticle(id);
        if (STATUS_PUBLISHED.equals(article.getStatus())) {
            return;
        }
        List<String> missing = missingLangs(article);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE,
                    "四语言不齐全，缺少：" + String.join("、", missing) + "（标题与正文都要有）");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        articleMapper.update(null, Wrappers.<Article>lambdaUpdate()
                .eq(Article::getId, id)
                .set(Article::getStatus, STATUS_PUBLISHED)
                .set(Article::getPublishedAt, now)
                .set(Article::getUpdatedAt, now));
        log.info("PC 上架文章 articleId={}", id);
    }

    /**
     * 下架（A-14）。
     *
     * @param id 文章主键
     * @throws BusinessException 401004 文章不存在
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = "content", action = "ARTICLE_OFFLINE", targetType = "ARTICLE")
    public void offline(Long id) {
        Article article = requireArticle(id);
        if (STATUS_ARCHIVED.equals(article.getStatus())) {
            return;
        }
        articleMapper.update(null, Wrappers.<Article>lambdaUpdate()
                .eq(Article::getId, id)
                .set(Article::getStatus, STATUS_ARCHIVED)
                .set(Article::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        log.info("PC 下架文章 articleId={}", id);
    }

    /**
     * 找出缺失的语言（源标题/正文 + 三种译文各自 title/content 齐全）。
     *
     * @param article 文章
     * @return 缺失语言列表（按 ALL_LANGS 顺序）
     */
    private List<String> missingLangs(Article article) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(article.getTitle()) || !StringUtils.hasText(article.getContent())) {
            missing.add("zh-Hans");
        }
        Map<String, ArticleTranslation> map = articleTranslationMapper.selectList(
                        Wrappers.<ArticleTranslation>lambdaQuery()
                                .eq(ArticleTranslation::getArticleId, article.getId()))
                .stream().collect(Collectors.toMap(ArticleTranslation::getLang, item -> item, (left, right) -> left));
        for (String lang : ALL_LANGS) {
            if ("zh-Hans".equals(lang)) {
                continue;
            }
            ArticleTranslation translation = map.get(lang);
            if (translation == null
                    || !StringUtils.hasText(translation.getTitle())
                    || !StringUtils.hasText(translation.getContent())) {
                missing.add(lang);
            }
        }
        return missing;
    }

    /**
     * 保存译文（upsert）。
     *
     * @param articleId    文章主键
     * @param translations 译文入参
     * @param now          当前时间
     */
    private void saveTranslations(Long articleId, List<AdminArticleTranslationBO> translations,
                                  LocalDateTime now) {
        if (translations == null || translations.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (AdminArticleTranslationBO item : translations) {
            if (item == null || !StringUtils.hasText(item.getLang())) {
                continue;
            }
            String lang = item.getLang().trim();
            if (!TRANSLATION_LANGS.contains(lang)) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "不支持的语言：" + lang);
            }
            if (!seen.add(lang)) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "同一语言只能提交一次：" + lang);
            }
            String content = StringUtils.hasText(item.getContent())
                    ? requireContentJson(item.getContent(), lang + " 正文") : null;
            ArticleTranslation existing = articleTranslationMapper.selectOne(
                    Wrappers.<ArticleTranslation>lambdaQuery()
                            .eq(ArticleTranslation::getArticleId, articleId)
                            .eq(ArticleTranslation::getLang, lang));
            if (existing == null) {
                ArticleTranslation row = new ArticleTranslation();
                row.setArticleId(articleId);
                row.setLang(lang);
                row.setTitle(item.getTitle());
                row.setSummary(item.getSummary());
                row.setContent(content);
                row.setStatus(StringUtils.hasText(item.getStatus()) ? item.getStatus() : STATUS_DRAFT);
                row.setTranslator(item.getTranslator());
                row.setTranslationEngine(item.getTranslationEngine());
                row.setTranslatedFrom(now);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                articleTranslationMapper.insert(row);
            } else {
                articleTranslationMapper.update(null, Wrappers.<ArticleTranslation>lambdaUpdate()
                        .eq(ArticleTranslation::getId, existing.getId())
                        .set(ArticleTranslation::getTitle, item.getTitle())
                        .set(ArticleTranslation::getSummary, item.getSummary())
                        .set(ArticleTranslation::getContent, content)
                        .set(ArticleTranslation::getStatus,
                                StringUtils.hasText(item.getStatus()) ? item.getStatus() : existing.getStatus())
                        .set(ArticleTranslation::getTranslator, item.getTranslator())
                        .set(ArticleTranslation::getTranslationEngine, item.getTranslationEngine())
                        .set(ArticleTranslation::getTranslatedFrom, now)
                        .set(ArticleTranslation::getUpdatedAt, now));
            }
        }
    }

    /**
     * 校验保存时的状态（只允许 draft；上架走 publish 接口）。
     *
     * @param status 入参状态
     * @throws BusinessException 401006 状态非法
     */
    private void requireDraftStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return;
        }
        if (!STATUS_DRAFT.equals(status.trim()) && !STATUS_ARCHIVED.equals(status.trim())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE,
                    "保存只能传 draft / archived；上架请用「上架」操作（会校验四语言齐全）");
        }
    }

    /**
     * 校验正文是合法 JSON 数组。
     *
     * @param raw  正文
     * @param name 字段名（错误提示用）
     * @return 去空白后的正文
     * @throws BusinessException 401001 不是 JSON 数组
     */
    private String requireContentJson(String raw, String name) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, name + "不能为空");
        }
        try {
            JsonNode node = jsonMapper.readTree(value);
            if (node == null || !node.isArray()) {
                throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, name + "必须是 JSON 块数组");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, name + "不是合法 JSON");
        }
        return value;
    }

    /**
     * 取文章（未软删）。
     *
     * @param id 文章主键
     * @return 文章
     * @throws BusinessException 401004
     */
    private Article requireArticle(Long id) {
        Article article = id == null ? null : articleMapper.selectOne(Wrappers.<Article>lambdaQuery()
                .eq(Article::getId, id)
                .isNull(Article::getDeletedAt));
        if (article == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return article;
    }

    /**
     * 批量取译文。
     *
     * @param articleIds 文章 id 集合
     * @return articleId → 译文列表
     */
    private Map<Long, List<ArticleTranslation>> loadTranslations(Set<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return Map.of();
        }
        return articleTranslationMapper.selectList(Wrappers.<ArticleTranslation>lambdaQuery()
                        .in(ArticleTranslation::getArticleId, articleIds))
                .stream().collect(Collectors.groupingBy(ArticleTranslation::getArticleId));
    }

    /**
     * 行 → 列表 VO（算语言完成度）。
     *
     * @param article      文章
     * @param translations 该文章的译文
     * @return 列表行
     */
    private AdminArticleRowVO toRow(Article article, List<ArticleTranslation> translations) {
        AdminArticleRowVO vo = new AdminArticleRowVO();
        vo.setId(article.getId());
        vo.setTitle(article.getTitle());
        vo.setCategory(article.getCategory());
        vo.setStatus(article.getStatus());
        vo.setSortOrder(article.getSortOrder());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setUpdatedAt(article.getUpdatedAt());
        List<String> languages = new ArrayList<>();
        if (StringUtils.hasText(article.getTitle()) && StringUtils.hasText(article.getContent())) {
            languages.add("zh-Hans");
        }
        for (String lang : ALL_LANGS) {
            if ("zh-Hans".equals(lang)) {
                continue;
            }
            boolean complete = translations.stream().anyMatch(item ->
                    lang.equals(item.getLang())
                            && StringUtils.hasText(item.getTitle())
                            && StringUtils.hasText(item.getContent()));
            if (complete) {
                languages.add(lang);
            }
        }
        vo.setLanguages(languages);
        return vo;
    }

    /**
     * 译文 → 出参。
     *
     * @param translation 译文实体
     * @return 译文 VO
     */
    private AdminArticleTranslationVO toTranslationVO(ArticleTranslation translation) {
        AdminArticleTranslationVO vo = new AdminArticleTranslationVO();
        vo.setLang(translation.getLang());
        vo.setTitle(translation.getTitle());
        vo.setSummary(translation.getSummary());
        vo.setContent(translation.getContent());
        vo.setStatus(translation.getStatus());
        vo.setTranslator(translation.getTranslator());
        vo.setTranslationEngine(translation.getTranslationEngine());
        vo.setTranslatedFrom(translation.getTranslatedFrom());
        return vo;
    }
}
