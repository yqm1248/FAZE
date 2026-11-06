/*
 * 职责：D4 RefSnapshotBuilder 的单测——0 条 / 1 条 / 多条 / 条号存在但 hits 里没有 / 超上限截断。
 * 调用方：mvn test（纯单测，不启 Spring、不连库：ArticleNoValidator 用 Mockito 打桩）。
 * 数据来源：LawRefSnapshot schema 见任务书 D4；LawItem 用真实库 5 条法条的字段值构造。
 * 注意：
 *   1. 断言字段名（Jackson 序列化名）与任务书 schema 完全一致：lawItemId/lawCode/lawName/
 *      articleNo/content/effectiveDate/status。
 *   2. `content` ≤200 字；`lawItemId` 必填；`effectiveDate` 冻结为 ISO 日期字符串。
 *   3. 空引用必须是**空列表**（C3 序列化成 `[]`，不是 NULL）。
 */
package com.faze.modules.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faze.infra.law.entity.LawItem;
import com.faze.modules.ai.dto.LawHit;
import com.faze.modules.ai.dto.LawRefSnapshot;
import com.faze.modules.ai.dto.ValidationResult;
import com.faze.modules.ai.service.ArticleNoValidator;
import com.faze.modules.ai.service.RefSnapshotBuilder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * refs 快照生成单测。
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class RefSnapshotBuilderTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2021, 1, 1);

    @Test
    @DisplayName("0 条：回答没引条号 → 返回空 list（不是 null）")
    void noCitation_returnsEmptyList() {
        RefSnapshotBuilder builder = builder(List.of(), List.of());

        List<LawRefSnapshot> refs = builder.build("这个借款能要回来吗", List.of(hit(4L, "第六百八十条")));

        assertNotNull(refs, "空引用必须返回空 list，不能返回 null");
        assertTrue(refs.isEmpty());
    }

    @Test
    @DisplayName("1 条：回答引用 hits 里的 680 条 → 快照字段齐全")
    void oneCitation_fromHits() {
        LawHit hit = hit(4L, "第六百八十条");
        hit.setContent("禁止高利放贷，借款的利率不得违反国家有关规定。");
        RefSnapshotBuilder builder = builder(List.of(), List.of("第六百八十条"));

        List<LawRefSnapshot> refs = builder.build("根据《中华人民共和国民法典》第六百八十条，禁止高利放贷。", List.of(hit));

        assertEquals(1, refs.size());
        LawRefSnapshot snapshot = refs.get(0);
        assertEquals(4L, snapshot.getLawItemId(), "lawItemId 必填且要对");
        assertEquals("civil_code", snapshot.getLawCode());
        assertEquals("中华人民共和国民法典", snapshot.getLawName());
        assertEquals("第六百八十条", snapshot.getArticleNo());
        assertEquals("禁止高利放贷，借款的利率不得违反国家有关规定。", snapshot.getContent());
        assertEquals("2021-01-01", snapshot.getEffectiveDate(), "effectiveDate 冻结为 ISO 日期字符串");
        assertEquals("active", snapshot.getStatus(), "status 冻结");
    }

    @Test
    @DisplayName("多条：回答引用 2 条、hits 里都有 → 长度 2、都有 lawItemId")
    void multipleCitations_fromHits() {
        LawHit first = hit(4L, "第六百八十条");
        LawHit second = hit(3L, "第六百六十七条");
        RefSnapshotBuilder builder = builder(List.of(), List.of("第六百八十条", "第六百六十七条"));

        List<LawRefSnapshot> refs = builder.build(
                "依据《中华人民共和国民法典》第六百八十条与第六百六十七条，借款合同应当采用书面形式。",
                List.of(first, second));

        assertEquals(2, refs.size());
        assertTrue(refs.stream().allMatch(ref -> ref.getLawItemId() != null), "每条都必须有 lawItemId");
        assertEquals(4L, refs.get(0).getLawItemId(), "顺序按 hits");
        assertEquals(3L, refs.get(1).getLawItemId());
    }

    @Test
    @DisplayName("条号存在但 hits 里没有：从库现查后仍要存快照（模型自己写对的情况）")
    void citationNotInHits_looksUpDb() {
        LawHit onlyHit = hit(4L, "第六百八十条");
        LawItem dbItem = lawItem(5L, "第一千零七十九条", "夫妻一方要求离婚的，可以由有关组织进行调解或者直接向人民法院提起离婚诉讼。");
        RefSnapshotBuilder builder = builder(List.of(dbItem), List.of("第一千零七十九条"));

        List<LawRefSnapshot> refs = builder.build("依据《中华人民共和国民法典》第一千零七十九条，可以起诉离婚。",
                List.of(onlyHit));

        assertEquals(1, refs.size(), "引用里的 1079 条不在 hits 里，但库里有 → 必须存快照");
        assertEquals(5L, refs.get(0).getLawItemId());
        assertEquals("第一千零七十九条", refs.get(0).getArticleNo());
        assertTrue(refs.get(0).getContent().startsWith("夫妻一方要求离婚"));
    }

    @Test
    @DisplayName("content 截断：快照只存 ≤200 字摘要，不是全文")
    void contentTruncatedTo200() {
        String longContent = "甲".repeat(300);
        LawHit hit = hit(4L, "第六百八十条");
        hit.setContent(longContent);
        RefSnapshotBuilder builder = builder(List.of(), List.of("第六百八十条"));

        List<LawRefSnapshot> refs = builder.build("《中华人民共和国民法典》第六百八十条。", List.of(hit));

        assertEquals(1, refs.size());
        assertEquals(200, refs.get(0).getContent().length(), "快照 content 必须截到 200 字");
    }

    @Test
    @DisplayName("上限：11 条引用 → 只存前 10 条（REFS_TRUNCATED）")
    void refsCappedAtTen() {
        List<LawHit> hits = new ArrayList<>();
        List<String> valid = new ArrayList<>();
        StringBuilder answer = new StringBuilder("依据：");
        for (int i = 1; i <= 11; i++) {
            String articleNo = "第" + (600 + i) + "条";
            hits.add(hit((long) i, articleNo));
            valid.add(articleNo);
            answer.append("《中华人民共和国民法典》").append(articleNo).append("、");
        }
        RefSnapshotBuilder builder = builder(List.of(), valid);

        List<LawRefSnapshot> refs = builder.build(answer.toString(), hits);

        assertEquals(RefSnapshotBuilder.MAX_REFS, refs.size(), "refs 上限 10 条");
        assertEquals(1L, refs.get(0).getLawItemId());
        assertEquals(10L, refs.get(9).getLawItemId());
    }

    @Test
    @DisplayName("没有 lawItemId 的命中项跳过，不写半截快照")
    void hitWithoutLawItemIdSkipped() {
        LawHit broken = hit(null, "第六百八十条");
        RefSnapshotBuilder builder = builder(List.of(), List.of("第六百八十条"));

        List<LawRefSnapshot> refs = builder.build("《中华人民共和国民法典》第六百八十条。", List.of(broken));

        assertTrue(refs.isEmpty(), "lawItemId 必填，缺失就不存");
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    /**
     * 打桩 ArticleNoValidator：validate 返回给定 valid 列表；findByArticleNo 在 dbItems 里按条号找。
     */
    private RefSnapshotBuilder builder(List<LawItem> dbItems, List<String> validArticleNos) {
        ArticleNoValidator validator = mock(ArticleNoValidator.class);
        ValidationResult validation = mock(ValidationResult.class);
        when(validation.getMissingArticleNos()).thenReturn(List.of());
        when(validation.getValidArticleNos()).thenReturn(validArticleNos);
        when(validator.validate(anyString())).thenReturn(validation);
        when(validator.findByArticleNo(any(), anyString())).thenAnswer(invocation -> {
            String articleNo = invocation.getArgument(1);
            return dbItems.stream().filter(item -> articleNo.equals(item.getArticleNo())).findFirst();
        });
        return new RefSnapshotBuilder(validator);
    }

    private LawHit hit(Long lawItemId, String articleNo) {
        LawHit hit = new LawHit();
        hit.setLawItemId(lawItemId);
        hit.setLawCode("civil_code");
        hit.setLawName("中华人民共和国民法典");
        hit.setArticleNo(articleNo);
        hit.setContent("（摘要）");
        hit.setStatus("active");
        hit.setEffectiveDate(EFFECTIVE);
        return hit;
    }

    private LawItem lawItem(Long id, String articleNo, String content) {
        LawItem item = new LawItem();
        item.setId(id);
        item.setLawCode("civil_code");
        item.setLawName("中华人民共和国民法典");
        item.setArticleNo(articleNo);
        item.setContent(content);
        item.setEffectiveDate(EFFECTIVE);
        item.setStatus("active");
        return item;
    }
}
