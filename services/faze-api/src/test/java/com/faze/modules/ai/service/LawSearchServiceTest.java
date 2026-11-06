/*
 * 职责：LawSearchService 的单测（B1/B2）——四条检索路径、topN 夹取、B2 分页夹取。
 * 调用方：mvn test（Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 LawItemMapper 与 SysConfigQueryService。
 * 注意：
 *   1. 四条路径各测一次，断言 `hitReason`（验收要看命中依据）；条号精确必须 score=1.0。
 *   2. `topN=100` 必须被夹到 10（通过 verify Mapper 的 limit 参数）。
 *   3. 分页测试覆盖「pageSize=999 → 50」与 kw 为空时的真实 total。
 */
package com.faze.modules.ai.service;

import com.faze.common.pojo.PageResult;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.law.mapper.LawItemSearchRow;
import com.faze.infra.law.mapper.LawSearchRow;
import com.faze.modules.ai.dto.LawHit;
import com.faze.modules.ai.dto.LawItemBrief;
import com.faze.modules.ops.service.SysConfigQueryService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 法条检索服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LawSearchServiceTest {

    @Mock
    private LawItemMapper lawItemMapper;

    @Mock
    private SysConfigQueryService sysConfigQueryService;

    private LawSearchService service;

    /**
     * 组装被测服务。
     */
    @BeforeEach
    void setUp() {
        service = new LawSearchService(lawItemMapper, sysConfigQueryService);
        when(sysConfigQueryService.getInt(eq(LawSearchService.CONFIG_TOP_N), anyInt()))
                .thenReturn(LawSearchService.DEFAULT_TOP_N);
    }

    /**
     * 造一条查询行。
     *
     * @param id        主键
     * @param articleNo 条号
     * @param seq       序号
     * @return 行
     */
    private LawItemSearchRow row(long id, String articleNo, int seq) {
        LawItemSearchRow row = new LawItemSearchRow();
        row.setId(id);
        row.setLawCode("civil_code");
        row.setLawName("中华人民共和国民法典");
        row.setArticleNo(articleNo);
        row.setArticleSeq(seq);
        row.setContent("这是条文内容，用于验证截断与命中。");
        row.setEffectiveDate(LocalDate.of(2021, 1, 1));
        row.setStatus("active");
        return row;
    }

    /**
     * 路径 1：条号精确 → article_no，score=1.0。
     */
    @Test
    @DisplayName("路径 1：条号精确命中，score=1.0")
    void articleNoPath() {
        when(lawItemMapper.selectByArticleNo(eq("第六百八十条"), eq(680),
                eq("中华人民共和国民法典"), isNull()))
                .thenReturn(List.of(row(4L, "第六百八十条", 680)));

        List<LawHit> hits = service.search("《中华人民共和国民法典》第六百八十条怎么规定", 5);

        assertFalse(hits.isEmpty());
        assertEquals("article_no", hits.get(0).getHitReason());
        assertEquals(1.0d, hits.get(0).getScore());
        assertEquals(4L, hits.get(0).getLawItemId());
        assertEquals("第六百八十条", hits.get(0).getArticleNo());
        assertEquals(LocalDate.of(2021, 1, 1), hits.get(0).getEffectiveDate());
    }

    /**
     * 路径 2：长度 ≥2 → ngram 全文；得分取 MySQL MATCH 得分。
     */
    @Test
    @DisplayName("路径 2：全文命中，hitReason=fulltext")
    void fulltextPath() {
        LawSearchRow ft = new LawSearchRow();
        ft.setId(3L);
        ft.setScore(2.5d);
        ft.setArticleNo("第六百六十七条");
        when(lawItemMapper.searchByNgram("借款", 5)).thenReturn(List.of(ft));
        when(lawItemMapper.selectEnabledByIds(any(), isNull()))
                .thenReturn(List.of(row(3L, "第六百六十七条", 667)));

        List<LawHit> hits = service.search("借款", 5);

        assertFalse(hits.isEmpty());
        assertEquals("fulltext", hits.get(0).getHitReason());
        assertEquals(2.5d, hits.get(0).getScore());
        assertEquals("第六百六十七条", hits.get(0).getArticleNo());
    }

    /**
     * 路径 3：单字 → LIKE（不调 ngram）。
     */
    @Test
    @DisplayName("路径 3：单字走 LIKE，hitReason=like")
    void likePath() {
        when(lawItemMapper.selectByLike(eq("婚"), isNull(), eq(5)))
                .thenReturn(List.of(row(5L, "第一千零七十九条", 1079)));

        List<LawHit> hits = service.search("婚", 5);

        assertFalse(hits.isEmpty());
        assertEquals("like", hits.get(0).getHitReason());
        assertEquals(0.5d, hits.get(0).getScore());
        verify(lawItemMapper, never()).searchByNgram(any(), anyInt());
    }

    /**
     * 路径 4：全文/LIKE 不足 → keywords 同义扩展。
     */
    @Test
    @DisplayName("路径 4：keywords 扩展命中，hitReason=keywords")
    void keywordsPath() {
        when(lawItemMapper.selectByKeywords(any(), isNull(), eq(5)))
                .thenReturn(List.of(row(6L, "第四十七条", 47)));

        List<LawHit> hits = service.search("劳动仲裁", 5);

        assertFalse(hits.isEmpty());
        assertEquals("keywords", hits.get(0).getHitReason());
        assertEquals(0.3d, hits.get(0).getScore());
    }

    /**
     * 内容截断：B1 的 `LawHit.content` 截到 200 字，B2 的 `LawItemBrief.content` 截到 300 字。
     */
    @Test
    @DisplayName("内容截断：LawHit 200 字 / LawItemBrief 300 字")
    void contentTruncation() {
        LawItemSearchRow longRow = row(4L, "第六百八十条", 680);
        longRow.setContent("文".repeat(500));
        when(lawItemMapper.selectByArticleNo(eq("第六百八十条"), eq(680), isNull(), isNull()))
                .thenReturn(List.of(longRow));
        when(lawItemMapper.selectEnabledPage(isNull(), eq(0), eq(20)))
                .thenReturn(List.of(longRow));
        when(lawItemMapper.countEnabled(isNull())).thenReturn(1L);

        List<LawHit> hits = service.search("第六百八十条", 5);
        assertTrue(hits.get(0).getContent().length() <= LawSearchService.HIT_CONTENT_MAX + 1,
                "LawHit.content 必须截断到 200 字（+省略号）");

        PageResult<LawItemBrief> page = service.searchPage(null, null, 1, 20);
        assertTrue(page.getList().get(0).getContent().length() <= LawSearchService.BRIEF_CONTENT_MAX + 1,
                "LawItemBrief.content 必须截断到 300 字（+省略号）");
    }

    /**
     * topN=100 被夹到 10。
     */
    @Test
    @DisplayName("topN 传 100 → 夹到 10")
    void topNClamped() {
        service.search("借款", 100);

        verify(lawItemMapper).searchByNgram("借款", LawSearchService.MAX_TOP_N);
    }

    /**
     * topN≤0 时读 sys_config（缺省 5）。
     */
    @Test
    @DisplayName("topN≤0 → 读 sys_config.ai_search_topn")
    void topNFromConfig() {
        service.search("借款", 0);

        verify(sysConfigQueryService).getInt(eq(LawSearchService.CONFIG_TOP_N), anyInt());
        verify(lawItemMapper).searchByNgram("借款", LawSearchService.DEFAULT_TOP_N);
    }

    /**
     * B2：kw 为空 + pageSize=999 → 夹到 50，total 取 countEnabled。
     */
    @Test
    @DisplayName("B2 分页：pageSize=999 夹到 50；kw 空时按 lawCode 过滤")
    void pageSizeClamped() {
        when(lawItemMapper.selectEnabledPage(isNull(), eq(0), eq(LawSearchService.MAX_PAGE_SIZE)))
                .thenReturn(List.of(row(4L, "第六百八十条", 680)));
        when(lawItemMapper.countEnabled(isNull())).thenReturn(1L);

        PageResult<LawItemBrief> page = service.searchPage(null, null, 1, 999);

        assertEquals(LawSearchService.MAX_PAGE_SIZE, page.getPageSize());
        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getList().size());
        // id 必须是字符串（Long 型 ID 序列化为字符串）
        assertEquals("4", page.getList().get(0).getId());
        assertFalse(page.isHasMore());
    }

    /**
     * B2：kw 非空时复用四路径并在外层切片；候选耗尽时 total 精确。
     */
    @Test
    @DisplayName("B2 分页：kw 非空 → 复用四路径 + 外层切片")
    void pageWithKeyword() {
        when(lawItemMapper.selectByKeywords(any(), isNull(), anyInt()))
                .thenReturn(List.of(row(4L, "第六百八十条", 680)));

        PageResult<LawItemBrief> page = service.searchPage("高利贷", null, 1, 20);

        assertEquals(1, page.getList().size());
        assertEquals(1L, page.getTotal());
        assertTrue(page.getList().get(0).getContent().length() <= LawSearchService.BRIEF_CONTENT_MAX);
    }
}
