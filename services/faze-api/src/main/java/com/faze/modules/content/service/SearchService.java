/*
 * 职责：全局搜索（第 5 段 G3）——跨律师 / 文章 / 案例 / 文书模板四域检索，分域返回。
 * 调用方：modules/content/controller/SearchController（`GET /app/search`）。
 * 数据来源：
 *   - 律师：account 域的 LawyerQueryService（只读查询接口，跨域合规）；
 *   - 文章 / 案例：content 域自己的 Mapper（同域直连）；
 *   - 文书模板：document 域的 DocumentQueryService（只读查询接口，跨域合规）。
 * 注意：
 *   1. **不引入 Elasticsearch**（AGENTS 禁向量库，且 V1 数据量小）：四域各一条
 *      `LIKE '%kw%'` 查询；数据量大到需要全文索引时再换，不改协议形状。
 *   2. **必须分域**：四组独立返回，不许拼成一个大列表（总纲 §5.6「必须做」第 4 条、5-A8）。
 *   3. 单域搜索（`type` 非空）只填该域，其余为 null；综合（`type` 空）四域各前 3 条 + 总数。
 *   4. 某域 0 命中时该组**照常返回**（list 空、total 0），由前端决定整组隐藏
 *      （工作流 G3「卡住怎么办」：某域没数据时该组不显示）。
 *   5. 关键词只做 trim，不做分词：分词会把「民间借贷」拆成「借贷」，反而召回到不相关的案例。
 */
package com.faze.modules.content.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.content.entity.Article;
import com.faze.modules.content.entity.CaseLibrary;
import com.faze.modules.content.enums.CaseCategory;
import com.faze.modules.content.mapper.ArticleMapper;
import com.faze.modules.content.mapper.CaseLibraryMapper;
import com.faze.modules.content.vo.SearchResponseVO;
import com.faze.modules.document.entity.DocTemplate;
import com.faze.modules.document.service.DocumentQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 全局搜索服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    /** 综合页每域返回条数。 */
    private static final int OVERVIEW_LIMIT = 3;

    /** 单域页每页条数（服务端夹住）。 */
    private static final int SINGLE_LIMIT = 20;

    /** 类型值：律师。 */
    private static final String TYPE_LAWYER = "lawyer";

    /** 类型值：文章。 */
    private static final String TYPE_ARTICLE = "article";

    /** 类型值：案例。 */
    private static final String TYPE_CASE = "case";

    /** 类型值：文书模板。 */
    private static final String TYPE_DOCUMENT = "document";

    /** 文章状态：已发布。 */
    private static final String ARTICLE_PUBLISHED = "published";

    /** 案例状态：已上线。 */
    private static final String CASE_PUBLISHED = "published";

    private final LawyerQueryService lawyerQueryService;
    private final ArticleMapper articleMapper;
    private final CaseLibraryMapper caseLibraryMapper;
    private final DocumentQueryService documentQueryService;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;

    /**
     * 全局搜索。
     *
     * @param keyword 关键词（空则返回空结构，不查库）
     * @param type    lawyer / article / case / document；空表示综合
     * @return 分域结果
     */
    public SearchResponseVO search(String keyword, String type) {
        SearchResponseVO response = new SearchResponseVO();
        if (!StringUtils.hasText(keyword)) {
            // 未输入（idle 态）由前端处理「历史 + 热门」，这里不返回数据也不报错
            return response;
        }
        String kw = keyword.trim();
        if (StringUtils.hasText(type)) {
            switch (type) {
                case TYPE_LAWYER -> response.setLawyers(searchLawyers(kw, SINGLE_LIMIT));
                case TYPE_ARTICLE -> response.setArticles(searchArticles(kw, SINGLE_LIMIT));
                case TYPE_CASE -> response.setCases(searchCases(kw, SINGLE_LIMIT));
                case TYPE_DOCUMENT -> response.setDocuments(searchDocTemplates(kw, SINGLE_LIMIT));
                default -> throw new com.faze.common.exception.BusinessException(
                        com.faze.common.exception.ErrorCode.COMMON_PARAM_INVALID,
                        "type 只能是 lawyer / article / case / document");
            }
            return response;
        }
        // 综合：四域各前 3 条 + 总数
        response.setLawyers(searchLawyers(kw, OVERVIEW_LIMIT));
        response.setArticles(searchArticles(kw, OVERVIEW_LIMIT));
        response.setCases(searchCases(kw, OVERVIEW_LIMIT));
        response.setDocuments(searchDocTemplates(kw, OVERVIEW_LIMIT));
        return response;
    }

    /**
     * 搜律师（姓名 / 律所 / 执业证号）。
     *
     * <p>通过 account 域的查询接口拿 id 列表，再批量取实体：跨域只走 QueryService，
     * 不注入别的域的 Mapper。</p>
     *
     * @param kw    关键词
     * @param limit 最多条数
     * @return 律师域分组
     */
    private SearchResponseVO.Group<SearchResponseVO.LawyerHit> searchLawyers(String kw, int limit) {
        List<Long> ids = lawyerQueryService.searchIdsByKeyword(kw);
        long total = ids.size();
        if (ids.isEmpty()) {
            return SearchResponseVO.Group.of(List.of(), 0L);
        }
        List<Long> pageIds = ids.size() > limit ? ids.subList(0, limit) : ids;
        Map<Long, Lawyer> lawyers = lawyerQueryService.mapByIds(pageIds);
        List<SearchResponseVO.LawyerHit> hits = new ArrayList<>(pageIds.size());
        for (Long id : pageIds) {
            Lawyer lawyer = lawyers.get(id);
            if (lawyer == null) {
                continue;
            }
            SearchResponseVO.LawyerHit hit = new SearchResponseVO.LawyerHit();
            hit.setId(lawyer.getId());
            hit.setName(lawyer.getRealName());
            hit.setLawFirm(lawyer.getLawFirm());
            hit.setSpecialtiesText(joinSpecialties(lawyer.getSpecialties()));
            hit.setAvatarUrl(lawyer.getAvatarUrl());
            hits.add(hit);
        }
        return SearchResponseVO.Group.of(hits, total);
    }

    /**
     * 搜文章（标题 / 摘要）。
     *
     * @param kw    关键词
     * @param limit 最多条数
     * @return 文章域分组
     */
    private SearchResponseVO.Group<SearchResponseVO.ArticleHit> searchArticles(String kw, int limit) {
        var wrapper = Wrappers.<Article>lambdaQuery()
                .eq(Article::getStatus, ARTICLE_PUBLISHED)
                .isNull(Article::getDeletedAt)
                .and(w -> w.like(Article::getTitle, kw).or().like(Article::getSummary, kw))
                .orderByDesc(Article::getPublishedAt)
                .orderByDesc(Article::getId);
        long total = countOrZero(articleMapper.selectCount(wrapper));
        wrapper.last("LIMIT " + Math.min(Math.max(limit, 1), SINGLE_LIMIT));
        List<Article> records = articleMapper.selectList(wrapper);
        List<SearchResponseVO.ArticleHit> hits = new ArrayList<>(records.size());
        for (Article article : records) {
            SearchResponseVO.ArticleHit hit = new SearchResponseVO.ArticleHit();
            hit.setId(article.getId());
            hit.setTitle(article.getTitle());
            hit.setCategory(article.getCategory());
            hit.setPublishedAt(article.getPublishedAt());
            hits.add(hit);
        }
        return SearchResponseVO.Group.of(hits, total);
    }

    /**
     * 搜案例（标题）。
     *
     * @param kw    关键词
     * @param limit 最多条数
     * @return 案例域分组
     */
    private SearchResponseVO.Group<SearchResponseVO.CaseHit> searchCases(String kw, int limit) {
        var wrapper = Wrappers.<CaseLibrary>lambdaQuery()
                .eq(CaseLibrary::getStatus, CASE_PUBLISHED)
                .isNull(CaseLibrary::getDeletedAt)
                .like(CaseLibrary::getTitle, kw)
                .orderByDesc(CaseLibrary::getPublishedAt)
                .orderByDesc(CaseLibrary::getId);
        long total = countOrZero(caseLibraryMapper.selectCount(wrapper));
        wrapper.last("LIMIT " + Math.min(Math.max(limit, 1), SINGLE_LIMIT));
        List<CaseLibrary> records = caseLibraryMapper.selectList(wrapper);
        List<SearchResponseVO.CaseHit> hits = new ArrayList<>(records.size());
        for (CaseLibrary entity : records) {
            SearchResponseVO.CaseHit hit = new SearchResponseVO.CaseHit();
            hit.setId(entity.getId());
            hit.setTitle(entity.getTitle());
            CaseCategory category = CaseCategory.ofOrNull(entity.getCategory());
            hit.setCategoryLabel(category == null ? entity.getCategory() : category.getLabel());
            hit.setSource(entity.getSource());
            hits.add(hit);
        }
        return SearchResponseVO.Group.of(hits, total);
    }

    /**
     * 搜文书模板（名称）。
     *
     * @param kw    关键词
     * @param limit 最多条数
     * @return 文书模板域分组
     */
    private SearchResponseVO.Group<SearchResponseVO.DocTemplateHit> searchDocTemplates(String kw,
                                                                                      int limit) {
        List<DocTemplate> templates = documentQueryService.searchActiveTemplates(kw, limit);
        List<SearchResponseVO.DocTemplateHit> hits = new ArrayList<>(templates.size());
        for (DocTemplate template : templates) {
            SearchResponseVO.DocTemplateHit hit = new SearchResponseVO.DocTemplateHit();
            hit.setId(template.getId());
            hit.setName(template.getName());
            hit.setCategory(template.getCategory());
            hits.add(hit);
        }
        // 模板没有单独的总数查询：单域页用「本页条数」近似（模板数量是个位数，
        // 多查一条的成本比再加一条 count SQL 更划算；这里如实返回 hits.size()，
        // 页面上「查看全部」对模板域不显示，见 App 端实现）。
        return SearchResponseVO.Group.of(hits, hits.size());
    }

    /**
     * 专长 JSON 数组 → 逗号串（最多取 2 个，列表里不显示一长串）。
     *
     * @param raw JSON 数组字符串
     * @return 形如「合同纠纷、劳动争议」；解析失败返回空串
     */
    private String joinSpecialties(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        try {
            List<String> values = jsonMapper.readValue(raw,
                    new tools.jackson.core.type.TypeReference<List<String>>() { });
            if (values == null || values.isEmpty()) {
                return "";
            }
            return values.stream()
                    .filter(StringUtils::hasText)
                    .limit(2)
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("专长 JSON 解析失败，按空处理");
            return "";
        }
    }

    /**
     * count 结果 null → 0。
     *
     * @param count MyBatis-Plus 的 count 返回值
     * @return 非负计数
     */
    private long countOrZero(Long count) {
        return count == null ? 0L : count;
    }
}
