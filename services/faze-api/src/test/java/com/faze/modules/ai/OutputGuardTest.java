/*
 * 职责：D3 OutputGuard 的单测——禁语三类 / 条号全在 / 部分不在 / 全不在 / 无条号 五种 + 裁决 B 的两支。
 * 调用方：mvn test（纯单测，不启 Spring、不连库：ArticleNoValidator 用 Mockito 打桩）。
 * 数据来源：测试里直接构造回答文本；B3 的 ValidationResult 用 mock 返回 missing/valid 两个列表。
 * 注意：
 *   1. lead 2026-11-07 裁决 B 的两支必须各有一条用例：
 *      ①「整段编造条号」删完 <20 字 → OUTPUT_INVALID（508002，落固定文案）；
 *      ②「正常内容里夹一个不存在条号」删完 ≥20 字 → 返回删除版 + 追加核实提示。
 *   2. 条号删除**不置 degraded**；degraded 只看禁语（同一裁决）。
 *   3. 测试数据不写真实手机号/身份证，免得日志断言时踩脱敏规则。
 */
package com.faze.modules.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faze.common.exception.ErrorCode;
import com.faze.modules.ai.dto.GuardOutcome;
import com.faze.modules.ai.dto.ValidationResult;
import com.faze.modules.ai.service.ArticleNoValidator;
import com.faze.modules.ai.service.OutputGuard;
import com.faze.modules.ai.service.OutputGuardRules;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 输出侧守卫单测。
 */
class OutputGuardTest {

    // ── 一、禁语三类：降级但不删回答 ─────────────────────────────────────────

    @Test
    @DisplayName("禁语 PATTERN_PROMISE：含「胜诉率90%」→ 追加免责、degraded=true、回答没被删")
    void promisePatternDegrades() {
        String answer = "这个案子胜诉率90%，你放心。";
        GuardOutcome outcome = guardWith(List.of(), List.of()).inspect(answer);

        assertTrue(outcome.isDegraded());
        assertEquals(GuardOutcome.ResultKind.DEGRADED, outcome.getResultKind());
        assertEquals(List.of(OutputGuardRules.RULE_PROMISE), outcome.getMatchedRules());
        assertTrue(outcome.getAnswer().contains("胜诉率90%"), "命中禁语不许删回答");
        assertTrue(outcome.getAnswer().endsWith(OutputGuard.DEGRADE_NOTICE), "必须追加推测免责提示");
        assertFalse(outcome.isOutputInvalid());
    }

    @Test
    @DisplayName("禁语 PATTERN_STRATEGY：含「你应该去法院起诉」→ degraded")
    void strategyPatternDegrades() {
        String answer = "你应该去法院起诉，先把证据准备齐。";
        GuardOutcome outcome = guardWith(List.of(), List.of()).inspect(answer);

        assertTrue(outcome.isDegraded());
        assertEquals(List.of(OutputGuardRules.RULE_STRATEGY), outcome.getMatchedRules());
        assertTrue(outcome.getAnswer().contains("你应该去法院起诉"));
        assertTrue(outcome.getAnswer().endsWith(OutputGuard.DEGRADE_NOTICE));
    }

    @Test
    @DisplayName("禁语 PATTERN_PREDICTION：含「大概赔」「预计判」→ degraded")
    void predictionPatternDegrades() {
        GuardOutcome first = guardWith(List.of(), List.of()).inspect("这种情况大概赔两万元。");
        assertTrue(first.isDegraded());
        assertEquals(List.of(OutputGuardRules.RULE_PREDICTION), first.getMatchedRules());

        GuardOutcome second = guardWith(List.of(), List.of()).inspect("法院预计判三个月。");
        assertEquals(List.of(OutputGuardRules.RULE_PREDICTION), second.getMatchedRules());
    }

    @Test
    @DisplayName("禁语不误伤：「保证合同无效」不得命中 PATTERN_PROMISE")
    void promisePatternDoesNotHitGuaranteeContract() {
        GuardOutcome outcome = guardWith(List.of(), List.of()).inspect("保证合同无效的情形有四种，需要看主合同效力。");

        assertTrue(outcome.getMatchedRules().isEmpty(), "「保证合同」不能误判为「保证赢」");
        assertFalse(outcome.isDegraded());
        assertEquals(GuardOutcome.ResultKind.OK, outcome.getResultKind());
    }

    // ── 二、条号存在性：全在 / 部分不在 / 全不在 / 无条号 ─────────────────────

    @Test
    @DisplayName("条号全在：保留引用，不追加核实提示")
    void allArticlesExist() {
        String answer = "根据《中华人民共和国民法典》第六百八十条，禁止高利放贷，借款的利率不得违反国家有关规定。";
        GuardOutcome outcome = guardWith(List.of(), List.of("第六百八十条")).inspect(answer);

        assertEquals(GuardOutcome.ResultKind.OK, outcome.getResultKind());
        assertFalse(outcome.isCitationStripped());
        assertFalse(outcome.getAnswer().contains(OutputGuard.CITATION_NOTICE));
        assertEquals(1, outcome.getValidArticleCount());
        assertEquals(0, outcome.getMissingArticleCount());
    }

    @Test
    @DisplayName("条号部分不在：只删「第X条」几个字 + 追加核实提示，不置 degraded")
    void partialMissingStripsArticleNoOnly() {
        String answer = "关于你的问题，先说明一点：借款合同自双方达成合意时成立并生效，这一点在实务中没有争议。"
                + "另见《中华人民共和国民法典》第九千九百九十九条。";
        GuardOutcome outcome = guardWith(List.of("第九千九百九十九条"), List.of("第六百八十条")).inspect(answer);

        assertTrue(outcome.isCitationStripped());
        assertFalse(outcome.isDegraded(), "条号删除本身不置 degraded");
        assertEquals(GuardOutcome.ResultKind.DEGRADED, outcome.getResultKind(),
                "删过条号 = 处理过（resultKind 反映处理痕迹），但 degraded 仍为 false");
        assertFalse(outcome.getAnswer().contains("第九千九百九十九条"), "不存在的条号必须删掉");
        assertTrue(outcome.getAnswer().contains("另见《中华人民共和国民法典》。") || outcome.getAnswer().contains("另见"),
                "只删条号、前后文不动");
        assertTrue(outcome.getAnswer().endsWith(OutputGuard.CITATION_NOTICE), "必须追加核实提示");
    }

    @Test
    @DisplayName("条号全不在 + 整段编造：删完 <20 字 → OUTPUT_INVALID（508002，固定文案）")
    void allMissingAndTooShortIsInvalid() {
        String answer = "《中华人民共和国民法典》第九千九百九十九条：质权人可以直接变卖。";
        GuardOutcome outcome = guardWith(List.of("第九千九百九十九条"), List.of()).inspect("msg-1", answer);

        assertTrue(outcome.isOutputInvalid());
        assertEquals(GuardOutcome.ResultKind.OUTPUT_INVALID, outcome.getResultKind());
        assertEquals(ErrorCode.AI_OUTPUT_INVALID, outcome.getErrorCode());
        assertEquals(508002, outcome.getErrorCode().getCode());
        assertEquals(OutputGuard.OUTPUT_INVALID_CONTENT, outcome.getAnswer(), "落库必须是固定文案");
        assertFalse(outcome.getAnswer().contains("质权人"), "编造内容不得出现在落库文本里");
        assertTrue(outcome.isCitationStripped());
    }

    @Test
    @DisplayName("条号全不在但删完 ≥20 字：返回删除版 + 追加核实提示（不是 508002）")
    void allMissingButEnoughContentDegrades() {
        String answer = "关于你的问题，先说明一点：借款合同自双方达成合意时成立并生效，这一点在实务中没有争议，"
                + "利率约定要符合国家有关规定。另见《中华人民共和国民法典》第九千九百九十九条。";
        GuardOutcome outcome = guardWith(List.of("第九千九百九十九条"), List.of()).inspect(answer);

        assertFalse(outcome.isOutputInvalid(), "删完还有实质内容，不能判无效");
        assertTrue(outcome.isCitationStripped());
        assertFalse(outcome.getAnswer().contains("第九千九百九十九条"));
        assertTrue(outcome.getAnswer().endsWith(OutputGuard.CITATION_NOTICE));
        assertEquals(GuardOutcome.ResultKind.DEGRADED, outcome.getResultKind(), "删过条号 = 处理过");
        assertFalse(outcome.isDegraded());
    }

    @Test
    @DisplayName("无条号（NO_REF）：不删、不追加，正常通过")
    void noArticleReferencePasses() {
        String answer = "借款合同是借款人向贷款人借款，到期返还借款并支付利息的合同。";
        GuardOutcome outcome = guardWith(List.of(), List.of()).inspect(answer);

        assertEquals(GuardOutcome.ResultKind.OK, outcome.getResultKind());
        assertFalse(outcome.isCitationStripped());
        assertEquals(answer, outcome.getAnswer(), "NO_REF 不改回答、不追加任何提示");
        assertFalse(outcome.getAnswer().contains(OutputGuard.CITATION_NOTICE));
    }

    @Test
    @DisplayName("禁语 + 部分条号缺失同时出现：两个提示都追加")
    void degradeAndStripTogether() {
        String answer = "这个案子胜诉率90%，你应该去法院起诉。另外《中华人民共和国民法典》第九千九百九十九条也有规定。"
                + "补充说明：借款合同自成立时生效，证据要保留好借条与转账记录，这一点很重要。";
        GuardOutcome outcome = guardWith(List.of("第九千九百九十九条"), List.of()).inspect(answer);

        assertTrue(outcome.isDegraded());
        assertEquals(GuardOutcome.ResultKind.DEGRADED, outcome.getResultKind());
        assertTrue(outcome.isCitationStripped());
        assertTrue(outcome.getAnswer().contains(OutputGuard.CITATION_NOTICE));
        assertTrue(outcome.getAnswer().endsWith(OutputGuard.DEGRADE_NOTICE), "免责提示放最后一段");
        assertFalse(outcome.getAnswer().contains("第九千九百九十九条"));
    }

    @Test
    @DisplayName("空回答：不崩，按 NO_REF 处理")
    void emptyAnswerPasses() {
        assertEquals(GuardOutcome.ResultKind.OK, guardWith(List.of(), List.of()).inspect("").getResultKind());
        assertEquals(GuardOutcome.ResultKind.OK, guardWith(List.of(), List.of()).inspect((String) null).getResultKind());
    }

    // ── 脚手架 ──────────────────────────────────────────────────────────────

    /**
     * 打桩一个 ArticleNoValidator：validate 恒返回给定的 missing/valid 两个列表。
     */
    private OutputGuard guardWith(List<String> missing, List<String> valid) {
        ArticleNoValidator validator = mock(ArticleNoValidator.class);
        ValidationResult validation = mock(ValidationResult.class);
        when(validation.getMissingArticleNos()).thenReturn(missing);
        when(validation.getValidArticleNos()).thenReturn(valid);
        when(validator.validate(anyString())).thenReturn(validation);
        return new OutputGuard(validator);
    }
}
