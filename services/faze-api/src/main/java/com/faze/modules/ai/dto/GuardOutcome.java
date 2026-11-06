/*
 * 职责：D3 输出侧校验的结果（禁语降级 / 条号删除 / 判为无效）。
 * 调用方：modules/ai/service/OutputGuard（构造）、C3 的流式收尾（be-stream：落库文案 + done 事件 + 错误码）。
 * 数据来源：OutputGuardRules 的禁语命中 + ArticleNoValidator 的条号存在性。
 * 注意：
 *   1. **本类不含未处理前的回答原文**，只有「可落库的最终文本」`answer`；日志禁止打 `answer`（L4）。
 *   2. `resultKind=OUTPUT_INVALID` 时 `answer` 是**固定文案**（不是模型输出），C3 必须
 *      先把这条固定文案 + `refs=[]` 落库，再按 508002 回包（lead 2026-11-07 裁决 B）。
 *   3. `degraded` 只看「命中禁语」；**删条号本身不置 degraded**（同上裁决）。
 */
package com.faze.modules.ai.dto;

import com.faze.common.exception.ErrorCode;
import java.util.List;
import lombok.Getter;

/**
 * 输出侧校验结果。三种结局见 {@link ResultKind}。
 */
@Getter
public final class GuardOutcome {

    /** 结局三态。 */
    public enum ResultKind {
        /** 通过：禁语未命中、也没有删条号，回答原样返回。 */
        OK,
        /** 处理过：命中禁语（追加免责、前端灰显）或删掉了不存在的条号（追加核实提示）。 */
        DEGRADED,
        /** 判为无效：删掉不存在的条号后剩余内容 < 20 字，无可信内容，回 508002。 */
        OUTPUT_INVALID
    }

    /** 可落库的最终文本（OUTPUT_INVALID 时是固定文案）。 */
    private final String answer;

    /** 结局。 */
    private final ResultKind resultKind;

    /** 是否降级（= 命中禁语；删条号不置 true）。 */
    private final boolean degraded;

    /** 命中的禁语规则名列表（PATTERN_PROMISE / PATTERN_STRATEGY / PATTERN_PREDICTION）。 */
    private final List<String> matchedRules;

    /** 是否删除过不存在的条号。 */
    private final boolean citationStripped;

    /** 被判为不存在的条号条数。 */
    private final int missingArticleCount;

    /** 被判为存在的条号条数。 */
    private final int validArticleCount;

    /** OUTPUT_INVALID 时为 AI_OUTPUT_INVALID；其余为 null。 */
    private final ErrorCode errorCode;

    /**
     * 构造普通结果（OK / DEGRADED）。
     *
     * @param answer              可落库最终文本
     * @param degraded            是否命中禁语
     * @param matchedRules        命中的禁语规则名
     * @param citationStripped    是否删过条号
     * @param missingArticleCount 不存在条号数
     * @param validArticleCount   存在条号数
     */
    public GuardOutcome(String answer, boolean degraded, List<String> matchedRules, boolean citationStripped,
                        int missingArticleCount, int validArticleCount) {
        this(answer, (degraded || citationStripped) ? ResultKind.DEGRADED : ResultKind.OK, degraded,
                matchedRules, citationStripped, missingArticleCount, validArticleCount, null);
    }

    /**
     * 全字段构造。
     *
     * @param answer              可落库最终文本
     * @param resultKind          结局
     * @param degraded            是否降级
     * @param matchedRules        命中的禁语规则名
     * @param citationStripped    是否删过条号
     * @param missingArticleCount 不存在条号数
     * @param validArticleCount   存在条号数
     * @param errorCode           错误码（OUTPUT_INVALID 时非空）
     */
    public GuardOutcome(String answer, ResultKind resultKind, boolean degraded, List<String> matchedRules,
                        boolean citationStripped, int missingArticleCount, int validArticleCount,
                        ErrorCode errorCode) {
        this.answer = answer;
        this.resultKind = resultKind;
        this.degraded = degraded;
        this.matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        this.citationStripped = citationStripped;
        this.missingArticleCount = missingArticleCount;
        this.validArticleCount = validArticleCount;
        this.errorCode = errorCode;
    }

    /**
     * 判为无效（508002）的结果工厂。
     *
     * @param fixedContent 应落库的固定文案（由 OutputGuard 提供，非模型输出）
     * @param matchedRules 命中的禁语规则名（可为空列表）
     * @param missingCount 不存在条号数
     * @param validCount   存在条号数
     * @return OUTPUT_INVALID 结局、errorCode = AI_OUTPUT_INVALID
     */
    public static GuardOutcome outputInvalid(String fixedContent, List<String> matchedRules, int missingCount,
                                             int validCount) {
        return new GuardOutcome(fixedContent, ResultKind.OUTPUT_INVALID, false, matchedRules, true,
                missingCount, validCount, ErrorCode.AI_OUTPUT_INVALID);
    }

    /**
     * 是否判为无效（C3 据此回 508002）。
     *
     * @return resultKind == OUTPUT_INVALID 时 true
     */
    public boolean isOutputInvalid() {
        return resultKind == ResultKind.OUTPUT_INVALID;
    }

    /**
     * 是否完全通过（未降级、未判无效）。
     *
     * @return resultKind == OK 时 true
     */
    public boolean isPassed() {
        return resultKind == ResultKind.OK;
    }
}
