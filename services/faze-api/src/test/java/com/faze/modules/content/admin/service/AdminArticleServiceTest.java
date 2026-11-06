/*
 * 职责：AdminArticleService 的单测——PC A-14 列表的四语言完成度聚合、A-15 详情/新建/保存/上架/下架（G9.3 / G9.4）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 ArticleMapper / ArticleTranslationMapper；JsonMapper 用真实实例
 *          （正文「必须是 JSON 块数组」的校验是纯函数，mock 掉就测不出格式判断）。
 * 注意：
 *   1. **四语言完成度是本类的核心语义**（工作流 G9.4 第 3 条）：
 *      简体看主表 title+content；bo / ug-Arab-CN / kk-Arab-CN 三种译文要**各自** title 且 content
 *      都非空才算该语言完成。只有标题或只有正文都算缺。
 *   2. **上架才校验齐全**：草稿允许只填一部分；publish 不齐全必须 COMMON_ILLEGAL_STATE，
 *      且把缺失语言按 ALL_LANGS 顺序列进 detail（服务端日志可定位；对外响应是固定文案）。
 *   3. **保存不许直接传 published**：上架只能走 publish，否则绕过四语言校验。
 *   4. 列表的语言完成度**一次批量查完**（按 articleIds 一次 selectList），不逐行查（N+1）。
 *   5. 已知口径问题（不属本测试能修复的范围，已写入测试报告）：`lang` 过滤在内存里做，
 *      过滤后 total 仍取数据库总数，与 rows 条数不同步；本类用断言标明「口径修复时需同步改这里」。
 */
package com.faze.modules.content.admin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.Update;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.content.admin.bo.AdminArticleQueryBO;
import com.faze.modules.content.admin.bo.AdminArticleSaveBO;
import com.faze.modules.content.admin.bo.AdminArticleTranslationBO;
import com.faze.modules.content.admin.vo.AdminArticleDetailVO;
import com.faze.modules.content.admin.vo.AdminArticleRowVO;
import com.faze.modules.content.entity.Article;
import com.faze.modules.content.entity.ArticleTranslation;
import com.faze.modules.content.mapper.ArticleMapper;
import com.faze.modules.content.mapper.ArticleTranslationMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端文章服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminArticleServiceTest {

    private static final Long ARTICLE_ID = 3001L;
    private static final Long SECOND_ARTICLE_ID = 3002L;

    /** 合法正文：JSON 块数组。 */
    private static final String CONTENT_JSON = "[{\"type\":\"paragraph\",\"text\":\"正文第一段\"}]";

    /** 译文正文：同样是合法 JSON 数组。 */
    private static final String TRANSLATED_CONTENT_JSON = "[{\"type\":\"paragraph\",\"text\":\"译文第一段\"}]";

    private static final String ZH = "zh-Hans";
    private static final String BO = "bo";
    private static final String UG = "ug-Arab-CN";
    private static final String KK = "kk-Arab-CN";

    @Mock
    private ArticleMapper articleMapper;

    @Mock
    private ArticleTranslationMapper articleTranslationMapper;

    private AdminArticleService adminArticleService;

    /** 最近一次传给 Mapper 的分页对象（用于断言分页参数）。 */
    private IPage<Article> capturedPage;

    /**
     * 注册 lambda 列名解析所需的 TableInfo：不注册时 `Wrappers.lambdaQuery()` 找不到列缓存会直接抛异常。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Article.class);
        TableInfoHelper.initTableInfo(assistant, ArticleTranslation.class);
    }

    /**
     * 手工装配：JsonMapper 用真实实例，正文 JSON 校验不被 mock 吞掉。
     */
    @BeforeEach
    void setUp() {
        adminArticleService = new AdminArticleService(articleMapper, articleTranslationMapper,
                JsonMapper.builder().build());
    }

    /**
     * 造一篇文章。
     *
     * @param id      主键
     * @param title   标题
     * @param content 正文
     * @param status  状态
     * @return 文章实体
     */
    private Article article(Long id, String title, String content, String status) {
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setContent(content);
        article.setCategory("借款纠纷");
        article.setStatus(status);
        article.setSortOrder(10);
        return article;
    }

    /**
     * 造一条译文。
     *
     * @param articleId 文章主键
     * @param lang      语言
     * @param title     标题
     * @param content   正文
     * @return 译文实体
     */
    private ArticleTranslation translation(Long articleId, String lang, String title, String content) {
        ArticleTranslation translation = new ArticleTranslation();
        translation.setId(articleId * 10 + (long) lang.length());
        translation.setArticleId(articleId);
        translation.setLang(lang);
        translation.setTitle(title);
        translation.setContent(content);
        return translation;
    }

    /**
     * 造一条译文入参。
     *
     * @param lang    语言
     * @param title   标题
     * @param content 正文
     * @return 译文入参
     */
    private AdminArticleTranslationBO translationBO(String lang, String title, String content) {
        AdminArticleTranslationBO bo = new AdminArticleTranslationBO();
        bo.setLang(lang);
        bo.setTitle(title);
        bo.setContent(content);
        return bo;
    }

    /**
     * 造保存入参。
     *
     * @param title       标题
     * @param content     正文
     * @param status      状态
     * @param translations 译文
     * @return 保存入参
     */
    private AdminArticleSaveBO saveBO(String title, String content, String status,
                                      AdminArticleTranslationBO... translations) {
        AdminArticleSaveBO bo = new AdminArticleSaveBO();
        bo.setTitle(title);
        bo.setSummary("摘要");
        bo.setContent(content);
        bo.setCategory("借款纠纷");
        bo.setStatus(status);
        bo.setTranslations(List.of(translations));
        return bo;
    }

    /**
     * 让分页查询返回给定文章与总数，并记录服务实际传入的分页对象。
     *
     * @param total 数据库总条数
     * @param rows  本页文章
     */
    private void stubPage(long total, Article... rows) {
        when(articleMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<Article> page = invocation.getArgument(0);
            capturedPage = page;
            page.setRecords(List.of(rows));
            page.setTotal(total);
            return page;
        });
    }

    /**
     * 捕获 update 语句的 set 片段（用于断言到底更新了哪些列）。
     *
     * @return SQL set 片段
     */
    @SuppressWarnings("unchecked")
    private String captureSqlSet() {
        ArgumentCaptor<Wrapper<Article>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(articleMapper).update(isNull(), captor.capture());
        return ((Update<?, ?>) captor.getValue()).getSqlSet();
    }

    /**
     * 列表：四语言完成度按「简体看主表、三种译文各自 title 且 content」聚合。
     */
    @Test
    @DisplayName("列表：四语言完成度聚合（译文缺标题或缺正文都不算完成）")
    void listAggregatesFourLanguageCompleteness() {
        stubPage(1L, article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON),
                translation(ARTICLE_ID, UG, "维吾尔文标题", null),
                translation(ARTICLE_ID, KK, null, TRANSLATED_CONTENT_JSON)));

        AdminArticleRowVO row = adminArticleService.list(new AdminArticleQueryBO()).getRows().get(0);

        assertEquals(List.of(ZH, BO), row.getLanguages(),
                "违反「四语言完成度」：简体看主表 title+content 齐全；译文要 title 与 content 都非空才算完成，"
                        + "只有标题（ug）或只有正文（kk）都不能进 languages");
        assertFalse(row.getLanguages().contains(UG), "维吾尔文只有标题没有正文，不得计入完成");
        assertFalse(row.getLanguages().contains(KK), "哈萨克文只有正文没有标题，不得计入完成");
    }

    /**
     * 列表：主表标题或正文为空时，简体语言不算完成。
     */
    @Test
    @DisplayName("列表：主表标题/正文为空时简体不算完成")
    void listSourceIncompleteExcludesChinese() {
        stubPage(1L, article(ARTICLE_ID, "   ", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AdminArticleRowVO row = adminArticleService.list(new AdminArticleQueryBO()).getRows().get(0);

        assertTrue(row.getLanguages().isEmpty(),
                "违反「简体完成度看主表 title+content」：标题只有空白时 zh-Hans 不能算完成");
    }

    /**
     * 列表：译文按 articleIds 一次批量查完，不逐行查。
     */
    @Test
    @DisplayName("列表：译文一次批量查（N+1 防护）")
    void listBatchesTranslationLookup() {
        stubPage(2L,
                article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"),
                article(SECOND_ARTICLE_ID, "第二篇", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON)));

        List<AdminArticleRowVO> rows = adminArticleService.list(new AdminArticleQueryBO()).getRows();

        verify(articleTranslationMapper, times(1)).selectList(any(Wrapper.class));
        assertEquals(List.of(ZH, BO), rows.get(0).getLanguages(), "有完整译文的文章应把该语言计入完成");
        assertEquals(List.of(ZH), rows.get(1).getLanguages(), "没有译文的文章只应计简体");
    }

    /**
     * 列表：lang 过滤在内存中按完成度筛选（已知 total 口径问题见类注释第 5 条）。
     */
    @Test
    @DisplayName("列表：lang 过滤只保留该语言已完成的文章")
    void listFiltersByCompletedLang() {
        stubPage(2L,
                article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"),
                article(SECOND_ARTICLE_ID, "第二篇", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON)));
        AdminArticleQueryBO query = new AdminArticleQueryBO();
        query.setLang(BO);

        TableDataInfo<AdminArticleRowVO> result = adminArticleService.list(query);

        assertEquals(1, result.getRows().size(), "lang=bo 只返回藏文已完成的文章");
        assertEquals(ARTICLE_ID, result.getRows().get(0).getId());
        assertEquals(2L, result.getTotal(),
                "当前实现 total 取数据库总数、未随内存 lang 过滤同步；若改成过滤后数量，说明口径已修复，请同步本断言");
    }

    /**
     * 详情：文章不存在 → 401004。
     */
    @Test
    @DisplayName("详情：文章不存在抛 401004")
    void detailMissingArticle() {
        when(articleMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminArticleService.detail(ARTICLE_ID));

        assertEquals(ErrorCode.COMMON_NOT_FOUND, ex.getErrorCode(),
                "违反「文章不存在统一 401004」：与「已删除」同码，不向客户端泄露草稿/归档状态");
        verify(articleTranslationMapper, never()).selectList(any(Wrapper.class));
    }

    /**
     * 详情：源内容与已存在的译文全部映射。
     */
    @Test
    @DisplayName("详情：返回源内容 + 已存在译文")
    void detailMapsTranslations() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"));
        ArticleTranslation bo = translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON);
        bo.setTranslator("translator-1");
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(bo));

        AdminArticleDetailVO vo = adminArticleService.detail(ARTICLE_ID);

        assertEquals("中文标题", vo.getTitle());
        assertEquals(CONTENT_JSON, vo.getContent());
        assertEquals(1, vo.getTranslations().size());
        assertEquals(BO, vo.getTranslations().get(0).getLang());
        assertEquals("藏文标题", vo.getTranslations().get(0).getTitle());
        assertEquals("translator-1", vo.getTranslations().get(0).getTranslator());
    }

    /**
     * 上架：译文缺标题/缺正文 → 401006，detail 列出缺失语言，且不落库。
     */
    @Test
    @DisplayName("上架：四语言不齐全抛 401006 并列出缺失语言")
    void publishRejectsIncompleteLangs() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON),
                translation(ARTICLE_ID, UG, "维吾尔文标题", null),
                translation(ARTICLE_ID, KK, null, TRANSLATED_CONTENT_JSON)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminArticleService.publish(ARTICLE_ID));

        assertEquals(ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode(),
                "违反「上架前必须校验四语言齐全」：缺语言时不能把文章置为 published");
        assertTrue(ex.getDetail() != null && ex.getDetail().contains(UG) && ex.getDetail().contains(KK),
                "违反「拒绝时要列出缺哪几种语言」：detail 必须含 ug-Arab-CN 与 kk-Arab-CN，"
                        + "否则运营只能自己猜（注意：detail 只进服务端日志，不进响应体）");
        assertTrue(ex.getDetail().indexOf(UG) < ex.getDetail().indexOf(KK),
                "缺失语言必须按 ALL_LANGS 顺序列出（zh-Hans → bo → ug-Arab-CN → kk-Arab-CN），便于运营对照");
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /**
     * 上架：主表标题为空 → 缺 zh-Hans。
     */
    @Test
    @DisplayName("上架：主表标题为空时缺 zh-Hans")
    void publishRejectsIncompleteSource() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "  ", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON),
                translation(ARTICLE_ID, UG, "维吾尔文标题", TRANSLATED_CONTENT_JSON),
                translation(ARTICLE_ID, KK, "哈萨克文标题", TRANSLATED_CONTENT_JSON)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminArticleService.publish(ARTICLE_ID));

        assertEquals(ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode());
        assertTrue(ex.getDetail() != null && ex.getDetail().contains(ZH),
                "主表标题为空时必须把 zh-Hans 报为缺失语言");
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /**
     * 上架：四语言齐全 → 更新 status=published 且写 published_at。
     */
    @Test
    @DisplayName("上架：四语言齐全时置 published 并写发布时间")
    void publishSucceedsWhenAllComplete() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"));
        when(articleTranslationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                translation(ARTICLE_ID, BO, "藏文标题", TRANSLATED_CONTENT_JSON),
                translation(ARTICLE_ID, UG, "维吾尔文标题", TRANSLATED_CONTENT_JSON),
                translation(ARTICLE_ID, KK, "哈萨克文标题", TRANSLATED_CONTENT_JSON)));

        adminArticleService.publish(ARTICLE_ID);

        String sqlSet = captureSqlSet();
        assertTrue(sqlSet.contains("status"), "上架必须更新 status 列");
        assertTrue(sqlSet.contains("published_at"),
                "违反「上架要记录发布时间」：published_at 不写会让前台文章排序与展示缺少时间基准");
    }

    /**
     * 上架：已 published 幂等返回，不重复校验、不重复更新。
     */
    @Test
    @DisplayName("上架：已 published 时幂等返回")
    void publishIsIdempotentWhenAlreadyPublished() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "中文标题", CONTENT_JSON, "published"));

        adminArticleService.publish(ARTICLE_ID);

        verify(articleTranslationMapper, never()).selectList(any(Wrapper.class));
        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /**
     * 新建：状态强制 draft（保存接口不能直接把文章置为上架），译文按新记录落库。
     */
    @Test
    @DisplayName("新建：主表强制 draft、标题 trim、译文 insert")
    void createForcesDraftAndInsertsTranslation() {
        when(articleMapper.insert(any(Article.class))).thenAnswer(invocation -> {
            Article inserted = invocation.getArgument(0);
            inserted.setId(ARTICLE_ID);
            return 1;
        });
        when(articleTranslationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        Long id = adminArticleService.create(saveBO("  中文标题  ", CONTENT_JSON, "archived",
                translationBO(BO, "藏文标题", TRANSLATED_CONTENT_JSON)));

        ArgumentCaptor<Article> articleCaptor = ArgumentCaptor.forClass(Article.class);
        verify(articleMapper).insert(articleCaptor.capture());
        Article inserted = articleCaptor.getValue();
        assertEquals(ARTICLE_ID, id, "新建应返回落库后的文章主键");
        assertEquals("中文标题", inserted.getTitle(), "标题入库前必须 trim，否则列表会出现前后空白");
        assertEquals("draft", inserted.getStatus(),
                "违反「保存不许直接上架」：保存接口一律落 draft，上架只能走 publish（否则绕过四语言校验）");
        assertEquals(0, inserted.getViewCount().intValue(), "新文章阅读数应从 0 开始");

        ArgumentCaptor<ArticleTranslation> translationCaptor =
                ArgumentCaptor.forClass(ArticleTranslation.class);
        verify(articleTranslationMapper).insert(translationCaptor.capture());
        ArticleTranslation saved = translationCaptor.getValue();
        assertEquals(ARTICLE_ID, saved.getArticleId());
        assertEquals(BO, saved.getLang());
        assertEquals("draft", saved.getStatus(), "译文未传状态时应默认 draft");
        assertTrue(saved.getTranslatedFrom() != null, "译文要记录翻译基准时间（translated_from），否则无法判断译文是否过期");
    }

    /**
     * 新建：正文不是 JSON 数组 → 401001，且不落主表。
     */
    @Test
    @DisplayName("新建：正文非 JSON 数组抛 401001 且不落库")
    void createRejectsNonJsonContent() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminArticleService.create(saveBO("标题", "not-json", "draft")));

        assertEquals(ErrorCode.COMMON_PARAM_INVALID, ex.getErrorCode(),
                "违反「content 必须是合法 JSON 块数组」：放行会让前台文章详情解析失败");
        verify(articleMapper, never()).insert(any(Article.class));
    }

    /**
     * 新建：保存接口传 published → 401006，绕过校验的入口一个不留。
     */
    @Test
    @DisplayName("新建：保存传 published 抛 401006")
    void createRejectsPublishedStatus() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminArticleService.create(saveBO("标题", CONTENT_JSON, "published")));

        assertEquals(ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode(),
                "违反「保存只能传 draft / archived」：published 只能由 publish 接口写入");
        verify(articleMapper, never()).insert(any(Article.class));
    }

    /**
     * 新建：译文语言不在三种译文内 → 401001，且提示带语言码。
     */
    @Test
    @DisplayName("新建：译文语言非法抛 401001")
    void createRejectsUnsupportedTranslationLang() {
        when(articleMapper.insert(any(Article.class))).thenAnswer(invocation -> {
            Article inserted = invocation.getArgument(0);
            inserted.setId(ARTICLE_ID);
            return 1;
        });

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminArticleService.create(saveBO("标题", CONTENT_JSON, "draft",
                        translationBO("en", "English", "[]"))));

        assertEquals(ErrorCode.COMMON_PARAM_INVALID, ex.getErrorCode(),
                "违反「译文语言只允许 bo / ug-Arab-CN / kk-Arab-CN」：放开会写入无法渲染的语言");
        assertTrue(ex.getDetail() != null && ex.getDetail().contains("en"),
                "错误 detail 应带上非法语言码，便于运营定位");
    }

    /**
     * 保存：源文变化时更新 source_updated_at（它是「译文是否过期」的判定基准）。
     */
    @Test
    @DisplayName("保存：源文变化时更新 source_updated_at")
    void updateTouchesSourceUpdatedAtWhenSourceChanged() {
        Article existing = article(ARTICLE_ID, "旧标题", CONTENT_JSON, "draft");
        existing.setSummary("旧摘要");
        when(articleMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(articleTranslationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        adminArticleService.update(ARTICLE_ID, saveBO("新标题", CONTENT_JSON, "draft"));

        String sqlSet = captureSqlSet();
        assertTrue(sqlSet.contains("source_updated_at"),
                "违反「译文过期判定」：源标题/正文变化时必须刷新 source_updated_at，否则旧译文永远显示为最新");
    }

    /**
     * 保存：源文未变化时不动 source_updated_at。
     */
    @Test
    @DisplayName("保存：源文未变化时不动 source_updated_at")
    void updateKeepsSourceUpdatedAtWhenUnchanged() {
        Article existing = article(ARTICLE_ID, "旧标题", CONTENT_JSON, "draft");
        existing.setSummary("摘要");
        when(articleMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(articleTranslationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        adminArticleService.update(ARTICLE_ID, saveBO("旧标题", CONTENT_JSON, "draft"));

        String sqlSet = captureSqlSet();
        assertFalse(sqlSet.contains("source_updated_at"),
                "源文没变时不得刷新 source_updated_at：否则每次点保存都把译文标记成过期");
    }

    /**
     * 保存：已存在译文走 update 分支，不重复 insert。
     */
    @Test
    @DisplayName("保存：已存在译文走 update（uk_art_trans 唯一键 upsert）")
    void updateUpsertsExistingTranslation() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "中文标题", CONTENT_JSON, "draft"));
        ArticleTranslation existing = translation(ARTICLE_ID, BO, "旧藏文标题", TRANSLATED_CONTENT_JSON);
        existing.setId(77L);
        existing.setStatus("draft");
        when(articleTranslationMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        adminArticleService.update(ARTICLE_ID, saveBO("中文标题", CONTENT_JSON, "draft",
                translationBO(BO, "新藏文标题", TRANSLATED_CONTENT_JSON)));

        verify(articleTranslationMapper).update(isNull(), any(Wrapper.class));
        verify(articleTranslationMapper, never()).insert(any(ArticleTranslation.class));
    }

    /**
     * 下架：已归档幂等返回，不重复更新。
     */
    @Test
    @DisplayName("下架：已归档时幂等返回")
    void offlineIsIdempotentWhenArchived() {
        when(articleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(article(ARTICLE_ID, "中文标题", CONTENT_JSON, "archived"));

        adminArticleService.offline(ARTICLE_ID);

        verify(articleMapper, never()).update(isNull(), any(Wrapper.class));
    }
}
