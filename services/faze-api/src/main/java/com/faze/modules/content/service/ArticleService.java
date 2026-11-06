/*
 * 职责：C 端文章列表与详情（G8.1：四语言 + 无译文降级）。
 * 调用方：modules/content/controller/ArticleController（游客与登录用户都能读）。
 * 数据来源：faze.article（简体源内容）、faze.article_translation（非中文译文）、
 *          faze.law_item（法条引用块的原文）、infra/storage/FileStorage（封面与图片签名地址）。
 * 注意：
 *   1. **可见性只看主表 `status='published'`**：译文表的 `status` 是质量阶段，不控制可见性
 *      （《技术路线》§5.8.1）。按译文状态过滤会让「译文还是 draft」的文章整篇消失。
 *   2. **降级规则**：请求语言是 zh-Hans → 直接用主表；否则查译文，译文有 title 用标题、
 *      有 content 用正文，缺哪部分降级哪部分并置 `langFallback=true`
 *      （前端据此显示「暂无该语言版本」）。
 *   3. **批量取译文**：列表一次 `in(article_id, ids)` 查完，禁止逐篇查询（N+1）。
 *   4. 图片/封面存 OSS key，出参前换签名地址；签名失败（存储不可用）时给 null 并 warn，
 *      不让整篇文章 500。
 *   5. 阅读数只读不写（本段不做自增，理由见 ArticleDetailVO 注释）。
 */
package com.faze.modules.content.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.PageResult;
import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.storage.FileStorage;
import com.faze.modules.content.converter.ArticleBlockConverter;
import com.faze.modules.content.entity.Article;
import com.faze.modules.content.entity.ArticleTranslation;
import com.faze.modules.content.mapper.ArticleMapper;
import com.faze.modules.content.mapper.ArticleTranslationMapper;
import com.faze.modules.content.vo.ArticleBlockVO;
import com.faze.modules.content.vo.ArticleCardVO;
import com.faze.modules.content.vo.ArticleDetailVO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 文章服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleService {

    /** 简体语言码（源语言，不在译文表）。 */
    public static final String LANG_ZH = "zh-Hans";

    /** 允许的译文语言（与 i18n 目录一致）。 */
    private static final Set<String> TRANSLATION_LANGS = Set.of("bo", "ug-Arab-CN", "kk-Arab-CN");

    /** 列表分页上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 已发布状态。 */
    private static final String STATUS_PUBLISHED = "published";

    private final ArticleMapper articleMapper;
    private final ArticleTranslationMapper articleTranslationMapper;
    private final ArticleBlockConverter articleBlockConverter;
    private final LawItemMapper lawItemMapper;
    private final FileStorage fileStorage;

    /**
     * 分页查询文章列表（只返回已发布）。
     *
     * @param lang     请求语言（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN；未知按 zh-Hans）
     * @param category 分类筛选（可空）
     * @param pageNum  页码（可空）
     * @param pageSize 每页条数（可空）
     * @return 分页结果
     */
    public PageResult<ArticleCardVO> list(String lang, String category, Integer pageNum, Integer pageSize) {
        String targetLang = normalizeLang(lang);
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null ? 20 : Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String normalizedCategory = StringUtils.hasText(category) ? category.trim() : null;

        IPage<Article> rows = articleMapper.selectPage(new Page<>(page, size),
                Wrappers.<Article>lambdaQuery()
                        .eq(Article::getStatus, STATUS_PUBLISHED)
                        .isNull(Article::getDeletedAt)
                        .eq(normalizedCategory != null, Article::getCategory, normalizedCategory)
                        .orderByDesc(Article::getPublishedAt)
                        .orderByDesc(Article::getId));

        Map<Long, ArticleTranslation> translations = loadTranslations(
                rows.getRecords().stream().map(Article::getId).collect(Collectors.toSet()), targetLang);
        List<ArticleCardVO> list = rows.getRecords().stream()
                .map(row -> toCard(row, translations.get(row.getId()), targetLang))
                .collect(Collectors.toList());
        return PageResult.of(list, rows.getTotal(), page, size);
    }

    /**
     * 查文章详情（只返回已发布）。
     *
     * @param id   文章主键
     * @param lang 请求语言
     * @return 详情（正文为块数组）
     * @throws BusinessException 401004 文章不存在或未上架
     */
    public ArticleDetailVO detail(Long id, String lang) {
        String targetLang = normalizeLang(lang);
        Article article = id == null ? null : articleMapper.selectOne(Wrappers.<Article>lambdaQuery()
                .eq(Article::getId, id)
                .eq(Article::getStatus, STATUS_PUBLISHED)
                .isNull(Article::getDeletedAt));
        if (article == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        ArticleTranslation translation = findTranslation(id, targetLang);
        // 标题/摘要/正文**各自**降级：译文表可能只有标题（运营先录标题），此时正文是中文，
        // 必须仍然置 langFallback=true，否则等于把中文正文冒充成译文（工作流 G8.1 第 2 条）。
        boolean titleFallback = translation == null || !StringUtils.hasText(translation.getTitle());
        boolean summaryFallback = translation == null || !StringUtils.hasText(translation.getSummary());
        boolean bodyFallback = translation == null || !StringUtils.hasText(translation.getContent());

        ArticleDetailVO vo = new ArticleDetailVO();
        vo.setId(article.getId());
        vo.setTitle(titleFallback ? article.getTitle() : translation.getTitle());
        vo.setSummary(summaryFallback ? article.getSummary() : translation.getSummary());
        vo.setCategory(article.getCategory());
        vo.setViewCount(article.getViewCount());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setCoverUrl(signKey(article.getCoverOssKey()));
        String rawContent = bodyFallback ? article.getContent() : translation.getContent();
        List<ArticleBlockVO> blocks = articleBlockConverter.parse(rawContent);
        enrichLawContent(blocks);
        vo.setBlocks(blocks);
        vo.setLangFallback(!LANG_ZH.equals(targetLang) && bodyFallback);
        return vo;
    }

    /**
     * 行 → 列表 VO。
     *
     * @param article     文章
     * @param translation 译文（可空）
     * @param targetLang  目标语言
     * @return 列表行
     */
    private ArticleCardVO toCard(Article article, ArticleTranslation translation, String targetLang) {
        boolean titleFallback = translation == null || !StringUtils.hasText(translation.getTitle());
        boolean summaryFallback = translation == null || !StringUtils.hasText(translation.getSummary());
        ArticleCardVO vo = new ArticleCardVO();
        vo.setId(article.getId());
        vo.setTitle(titleFallback ? article.getTitle() : translation.getTitle());
        vo.setSummary(summaryFallback ? article.getSummary() : translation.getSummary());
        vo.setCategory(article.getCategory());
        vo.setCoverUrl(signKey(article.getCoverOssKey()));
        vo.setViewCount(article.getViewCount());
        vo.setPublishedAt(article.getPublishedAt());
        // 只有「标题还是中文」时才算降级：摘要缺失不影响列表主体信息
        vo.setLangFallback(!LANG_ZH.equals(targetLang) && titleFallback);
        return vo;
    }

    /**
     * 批量取某语言的译文。
     *
     * @param articleIds 文章 id 集合
     * @param targetLang 目标语言
     * @return articleId → 译文；中文或空集合返回空 Map
     */
    private Map<Long, ArticleTranslation> loadTranslations(Set<Long> articleIds, String targetLang) {
        if (LANG_ZH.equals(targetLang) || articleIds.isEmpty()) {
            return Map.of();
        }
        return articleTranslationMapper.selectList(Wrappers.<ArticleTranslation>lambdaQuery()
                        .in(ArticleTranslation::getArticleId, articleIds)
                        .eq(ArticleTranslation::getLang, targetLang))
                .stream()
                .collect(Collectors.toMap(ArticleTranslation::getArticleId, item -> item, (left, right) -> left));
    }

    /**
     * 取单篇某语言译文。
     *
     * @param articleId  文章主键
     * @param targetLang 目标语言
     * @return 译文；中文或没有返回 null
     */
    private ArticleTranslation findTranslation(Long articleId, String targetLang) {
        if (LANG_ZH.equals(targetLang)) {
            return null;
        }
        return articleTranslationMapper.selectOne(Wrappers.<ArticleTranslation>lambdaQuery()
                .eq(ArticleTranslation::getArticleId, articleId)
                .eq(ArticleTranslation::getLang, targetLang));
    }

    /**
     * 给法条引用块补上原文（一次批量查，避免逐块查询）。
     *
     * @param blocks 正文块
     */
    private void enrichLawContent(List<ArticleBlockVO> blocks) {
        Set<Long> lawIds = blocks.stream()
                .filter(block -> "law_ref".equals(block.getType()) && block.getLawItemId() != null)
                .map(ArticleBlockVO::getLawItemId)
                .collect(Collectors.toSet());
        if (lawIds.isEmpty()) {
            return;
        }
        Map<Long, LawItem> items = lawItemMapper.selectBatchIds(lawIds).stream()
                .collect(Collectors.toMap(LawItem::getId, item -> item, (left, right) -> left));
        for (ArticleBlockVO block : blocks) {
            if (!"law_ref".equals(block.getType()) || block.getLawItemId() == null) {
                continue;
            }
            LawItem item = items.get(block.getLawItemId());
            if (item != null) {
                block.setLawContent(item.getContent());
                if (!StringUtils.hasText(block.getLawName())) {
                    block.setLawName(item.getLawName());
                }
                if (!StringUtils.hasText(block.getArticleNo())) {
                    block.setArticleNo(item.getArticleNo());
                }
            }
        }
    }

    /**
     * 把 OSS key 换成可读签名地址。
     *
     * @param key OSS key
     * @return 签名地址；key 为空或签名失败返回 null
     */
    private String signKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key;
        }
        try {
            return fileStorage.presignDownload(key).url();
        } catch (RuntimeException e) {
            log.warn("文章图片签名失败 keyLength={} 原因={}", key.length(), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 规整语言码（未知语言按简体处理）。
     *
     * @param lang 原始语言
     * @return 白名单语言或 zh-Hans
     */
    private String normalizeLang(String lang) {
        if (!StringUtils.hasText(lang)) {
            return LANG_ZH;
        }
        String value = lang.trim();
        return TRANSLATION_LANGS.contains(value) ? value : LANG_ZH;
    }
}
