/*
 * 职责：ArticleNoValidator 的单测（B3）——存在/不存在/无条号/多引用混合/无法归属法律 五种。
 * 调用方：mvn test（Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 LawItemMapper。
 * 注意：
 *   1. 「无法归属法律」必须**判为不存在**（宁可误拦），且此时不该查库（法律名都没有，查了也是错的）。
 *   2. `validArticleNos` 断言库里的规范条号；`missingArticleNos` 断言回答原文（含「第…条」）。
 *   3. `findByArticleNo(null, ...)` 是 D4 的用法：只按条号回查，不做法律名过滤。
 */
package com.faze.modules.ai.service;

import com.faze.infra.law.entity.LawItem;
import com.faze.infra.law.mapper.LawItemMapper;
import com.faze.infra.law.mapper.LawItemSearchRow;
import com.faze.modules.ai.dto.ValidationResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 条文号校验测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleNoValidatorTest {

    @Mock
    private LawItemMapper lawItemMapper;

    private ArticleNoValidator validator;

    /**
     * 组装被测服务。
     */
    @BeforeEach
    void setUp() {
        validator = new ArticleNoValidator(lawItemMapper);
    }

    /**
     * 造一条法条查询行。
     *
     * @param articleNo 条号
     * @param seq       序号
     * @return 行
     */
    private LawItemSearchRow row(String articleNo, int seq) {
        LawItemSearchRow row = new LawItemSearchRow();
        row.setId(4L);
        row.setLawCode("civil_code");
        row.setLawName("中华人民共和国民法典");
        row.setArticleNo(articleNo);
        row.setArticleSeq(seq);
        row.setContent("条文内容");
        row.setEffectiveDate(LocalDate.of(2021, 1, 1));
        row.setStatus("active");
        return row;
    }

    /**
     * 情况 1：存在。
     */
    @Test
    @DisplayName("情况 1：条号存在 → ALL_EXIST")
    void exists() {
        when(lawItemMapper.selectByArticleNo(eq("第六百八十条"), eq(680),
                eq("中华人民共和国民法典"), isNull()))
                .thenReturn(List.of(row("第六百八十条", 680)));

        ValidationResult result = validator.validate("依据《中华人民共和国民法典》第六百八十条，禁止高利放贷。");

        assertEquals(ValidationResult.Status.ALL_EXIST, result.getStatus());
        assertEquals(List.of("第六百八十条"), result.getValidArticleNos());
        assertTrue(result.getMissingArticleNos().isEmpty());
    }

    /**
     * 情况 2：不存在。
     */
    @Test
    @DisplayName("情况 2：条号不存在 → PARTIAL_MISS")
    void missing() {
        when(lawItemMapper.selectByArticleNo(any(), any(), any(), any())).thenReturn(List.of());

        ValidationResult result = validator.validate("《中华人民共和国民法典》第九千九百九十九条规定的。");

        assertEquals(ValidationResult.Status.PARTIAL_MISS, result.getStatus());
        assertEquals(List.of("第九千九百九十九条"), result.getMissingArticleNos());
        assertTrue(result.getValidArticleNos().isEmpty());
    }

    /**
     * 情况 3：回答里没有任何条号。
     */
    @Test
    @DisplayName("情况 3：无条号 → NO_REF")
    void noReference() {
        ValidationResult result = validator.validate("合同应当全面履行，否则构成违约。");

        assertEquals(ValidationResult.Status.NO_REF, result.getStatus());
        assertTrue(result.getMissingArticleNos().isEmpty());
        assertTrue(result.getValidArticleNos().isEmpty());
        verifyNoInteractions(lawItemMapper);
    }

    /**
     * 情况 4：多引用混合（一条存在、一条不存在）。
     */
    @Test
    @DisplayName("情况 4：多引用混合 → PARTIAL_MISS")
    void mixed() {
        when(lawItemMapper.selectByArticleNo(any(), any(), any(), any())).thenAnswer(invocation -> {
            String articleNo = invocation.getArgument(0);
            return "第六百八十条".equals(articleNo)
                    ? List.of(row("第六百八十条", 680))
                    : List.of();
        });

        ValidationResult result = validator.validate(
                "《中华人民共和国民法典》第六百八十条与第九千九百九十九条都有规定。");

        assertEquals(ValidationResult.Status.PARTIAL_MISS, result.getStatus());
        assertEquals(List.of("第六百八十条"), result.getValidArticleNos());
        assertEquals(List.of("第九千九百九十九条"), result.getMissingArticleNos());
    }

    /**
     * 情况 5：无法归属法律 → 判不存在，且不查库。
     */
    @Test
    @DisplayName("情况 5：无法归属法律 → 判不存在（宁可误拦）")
    void cannotAttribute() {
        ValidationResult result = validator.validate("第680条怎么规定的？");

        assertEquals(ValidationResult.Status.PARTIAL_MISS, result.getStatus());
        assertEquals(List.of("第680条"), result.getMissingArticleNos());
        assertTrue(result.getValidArticleNos().isEmpty());
        verifyNoInteractions(lawItemMapper);
    }

    /**
     * D4 用法：lawNameOrCode 传 null 时只按条号回查。
     */
    @Test
    @DisplayName("findByArticleNo(null, 条号) → 只按条号回查")
    void findByArticleNoWithoutLawName() {
        when(lawItemMapper.selectByArticleNo(eq("第六百八十条"), eq(680), isNull(), isNull()))
                .thenReturn(List.of(row("第六百八十条", 680)));

        Optional<LawItem> found = validator.findByArticleNo(null, "第680条");

        assertTrue(found.isPresent());
        assertEquals("第六百八十条", found.get().getArticleNo());
        assertEquals("中华人民共和国民法典", found.get().getLawName());
        assertEquals(680, found.get().getArticleSeq());
    }

    /**
     * 空参数返回空，不抛异常。
     */
    @Test
    @DisplayName("空参数 → Optional.empty，不抛异常")
    void emptyArguments() {
        assertFalse(validator.findByArticleNo("民法典", null).isPresent());
        assertFalse(validator.findByArticleNo(null, "  ").isPresent());
        assertEquals(ValidationResult.Status.NO_REF, validator.validate(null).getStatus());
    }
}
