/*
 * 职责：ArticleService 的单测——语言降级、可见性、法条引用补原文（G8.1）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 ArticleMapper / ArticleTranslationMapper / LawItemMapper / FileStorage；
 *          ArticleBlockConverter 用真实实例（JSON 解析是纯函数，mock 掉就测不出块结构）。
 * 注意：
 *   1. **降级是这一组的核心**：请求语言无译文时必须返回简体且 `langFallback=true`；
 *      中文请求永远不降级（`langFallback=false`）。
 *   2. 非 published 的文章不可见（401004），与「不存在」同码——不泄露草稿的存在。
 *   3. 法条引用块要补 `lawContent`：这是「法条引用块正常渲染」判据（`2-A7`）的服务端部分。
 */
package com.faze.modules.content.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.storage.FileStorage;
import com.faze.modules.content.converter.ArticleBlockConverter;
import com.faze.modules.content.entity.Article;
import com.faze.modules.content.entity.ArticleTranslation;
import com.faze.modules.content.mapper.ArticleMapper;
import com.faze.modules.content.mapper.ArticleTranslationMapper;
import com.faze.modules.content.vo.ArticleCardVO;
import com.faze.modules.content.vo.ArticleDetailVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 文章服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleServiceTest {

    private static final Long ARTICLE_ID = 3001L;
    private static final String CONTENT_JSON =
            "[{\"type\":\"paragraph\",\"text\":\"正文第一段\"},"
                    + "{\"type\":\"law_ref\",\"lawName\":\"民法典\",\"articleNo\":\"第六百八十条\",\"lawItemId\":9}]";

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTranslationMapper articleTranslationMapper;

    @Mock
    private LawItemMapper lawItemMapper;

    @Mock
    private FileStorage fileStorage;

    private ArticleService articleService;

    /**
     * 注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, ArticleTranslation.class);
        TableInfoHelper.initTableInfo(assistant, LawItem.class);
    }

    @BeforeEach
    void setUp() {
        ArticleBlockConverter converter = new ArticleBlockConverter(JsonMapper.builder().build());
        articleService = new ArticleService(articleMapper, articleTranslationMapper, converter,
                lawItemMapper, fileStorage);
    }

    /**
     * 造一篇已发布文章。
     *
     * @return 文章实体
     */
    private Article article() {
        Article article = new Article();
        article.setId(ARTICLE_ID);
        article.setTitle("中文标题");
        article.setSummary("中文摘要");
        article.setContent(CONTENT_JSON);
        article.setCategory("借款纠纷");
        article.setStatus("published");
        article.setViewCount(10);
        return article;
    }

    /**
     * 中文列表：不降级。
     */
    @Test
    @DisplayName("列表：中文请求不降级")
    void listChinese() {
        stubPage(article());

        var result = articleService.list("zh-Hans", null, 1, 20);

        ArticleCardVO row = result.getList().get(0);
        assertEquals("中文标题", row.getTitle());
        assertFalse(row.isLangFallback());
    }

    /**
     * 无译文的请求语言：降级为简体并标记。
     */
    @Test
    @DisplayName("列表：无译文 → 降级简体且 langFallback=true")
    void listFallbackWhenNoTranslation() {
        stubPage(article());
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var result = articleService.list("ug-Arab-CN", null, 1, 20);

        ArticleCardVO row = result.getList().get(0);
        assertEquals("中文标题", row.getTitle());
        assertTrue(row.isLangFallback());
    }

    /**
     * 有译文：用译文标题，不降级。
     */
    @Test
    @DisplayName("列表：有译文 → 用译文")
    void listUsesTranslation() {
        stubPage(article());
        ArticleTranslation translation = new ArticleTranslation();
        translation.setArticleId(ARTICLE_ID);
        translation.setLang("ug-Arab-CN");
        translation.setTitle("ئۇيغۇرچە تېما");
        translation.setSummary("ئۇيغۇرچە خۇلاسە");
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(translation));

        var result = articleService.list("ug-Arab-CN", null, 1, 20);

        assertEquals("ئۇيغۇرچە تېما", result.getList().get(0).getTitle());
        assertFalse(result.getList().get(0).isLangFallback());
    }

    /**
     * 详情：正文块解析 + 法条原文补齐。
     */
    @Test
    @DisplayName("详情：解析块并补法条原文")
    void detailWithLawRef() {
        when(articleMapper.selectOne(any(Wrapper.class))).thenReturn(article());
        LawItem item = new LawItem();
        item.setId(9L);
        item.setLawName("民法典");
        item.setArticleNo("第六百八十条");
        item.setContent("禁止高利放贷，借款的利率不得违反国家有关规定。");
        when(lawItemMapper.selectBatchIds(any())).thenReturn(List.of(item));

        ArticleDetailVO vo = articleService.detail(ARTICLE_ID, "zh-Hans");

        assertEquals(2, vo.getBlocks().size());
        assertEquals("paragraph", vo.getBlocks().get(0).getType());
        assertEquals("law_ref", vo.getBlocks().get(1).getType());
        assertEquals("禁止高利放贷，借款的利率不得违反国家有关规定。",
                vo.getBlocks().get(1).getLawContent());
        assertFalse(vo.isLangFallback());
    }

    /**
     * 详情：非 published 的文章不可见（404，与不存在同码）。
     */
    @Test
    @DisplayName("详情：未上架 → 401004")
    void detailNotPublished() {
        when(articleMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> articleService.detail(ARTICLE_ID, "zh-Hans"));

        assertEquals(ErrorCode.COMMON_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 详情：只有译文标题、没有译文正文 → 标题用译文，正文降级且 langFallback=true。
     */
    @Test
    @DisplayName("详情：部分降级（有标题无正文）也要标 langFallback")
    void detailPartialTranslation() {
        when(articleMapper.selectOne(any(Wrapper.class))).thenReturn(article());
        ArticleTranslation translation = new ArticleTranslation();
        translation.setArticleId(ARTICLE_ID);
        translation.setLang("ug-Arab-CN");
        translation.setTitle("ئۇيغۇرچە ماۋزۇ");
        translation.setContent(null);
        when(articleTranslationMapper.selectOne(any(Wrapper.class))).thenReturn(translation);

        ArticleDetailVO vo = articleService.detail(ARTICLE_ID, "ug-Arab-CN");

        assertEquals("ئۇيغۇرچە ماۋزۇ", vo.getTitle());
        assertTrue(vo.isLangFallback());
        assertEquals("正文第一段", vo.getBlocks().get(0).getText());
    }

    /**
     * 未知语言：按简体处理（不降级）。
     */
    @Test
    @DisplayName("列表：未知语言按简体处理")
    void listUnknownLang() {
        stubPage(article());

        var result = articleService.list("fr-FR", null, 1, 20);

        assertFalse(result.getList().get(0).isLangFallback());
    }

    /**
     * 让分页查询返回单篇文章。
     *
     * @param row 文章
     */
    private void stubPage(Article row) {
        when(articleMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<Article> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });
    }
}
