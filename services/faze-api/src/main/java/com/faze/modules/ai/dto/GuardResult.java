/*
 * 职责：D2 输入侧意图识别的判定结果（PASS / 越界 / Prompt 注入）。
 * 调用方：modules/ai/service/InputGuard（构造）、C2 的对话入口（be-stream，按它决定走模型还是直接引导）。
 * 数据来源：InputGuardRules 的规则命中；错误码来自 ErrorCode（408004 / 408005）。
 * 注意：
 *   1. **本类不带问题原文**：日志只能记 `matchedRule`（规则名），不许把 `question` 塞进来
 *      （技术路线 §16.2.1 的原文落库在 ai_message，不在日志；L4 级字段禁进日志）。
 *   2. `shouldChargeQuota`：被拦时**恒为 false**（没有调模型，不该计费，工作流 D2 第 5 条）；
 *      PASS 时为 true，表示「计费由 C2 按正常链路决定」，不是「已经扣了」。
 */
package com.faze.modules.ai.dto;

import com.faze.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 输入侧拦截结果。构造走 {@link #pass()} / {@link #blocked(String, ErrorCode)} 两个工厂，避免调用方传错组合。
 */
@Getter
public final class GuardResult {

    /** 判定三态。 */
    public enum Decision {
        /** 放行，可进 Prompt。 */
        PASS,
        /** 超出可咨询范围（诉讼策略/胜诉、金额刑期预测、代写可递交文书），错误码 408004。 */
        OUT_OF_SCOPE,
        /** 检测到 Prompt 注入企图，错误码 408005。 */
        PROMPT_INJECTED
    }

    /** 判定结果。 */
    private final Decision decision;

    /** 命中的规则名（如 `inject_prompt_leak`）；PASS 时为 null。 */
    private final String matchedRule;

    /** 对应的稳定错误码；PASS 时为 null。 */
    private final ErrorCode errorCode;

    /**
     * 内部构造。
     *
     * @param decision    判定
     * @param matchedRule 命中规则名（PASS 时传 null）
     * @param errorCode   错误码（PASS 时传 null）
     */
    private GuardResult(Decision decision, String matchedRule, ErrorCode errorCode) {
        this.decision = decision;
        this.matchedRule = matchedRule;
        this.errorCode = errorCode;
    }

    /**
     * 放行结果。
     *
     * @return PASS 单例
     */
    public static GuardResult pass() {
        return new GuardResult(Decision.PASS, null, null);
    }

    /**
     * 拦截结果。
     *
     * @param matchedRule 命中的规则名（规则表里的 name，用于测试断言与日志；**不要传问题原文**）
     * @param errorCode   错误码，只允许 AI_OUT_OF_SCOPE / AI_PROMPT_INJECTED
     * @return 拦截结果
     * @throws IllegalArgumentException errorCode 不是这两个拦截码之一时抛出（写代码时就该发现）
     */
    public static GuardResult blocked(String matchedRule, ErrorCode errorCode) {
        Decision decision;
        if (errorCode == ErrorCode.AI_PROMPT_INJECTED) {
            decision = Decision.PROMPT_INJECTED;
        } else if (errorCode == ErrorCode.AI_OUT_OF_SCOPE) {
            decision = Decision.OUT_OF_SCOPE;
        } else {
            throw new IllegalArgumentException("拦截错误码只允许 AI_OUT_OF_SCOPE / AI_PROMPT_INJECTED");
        }
        return new GuardResult(decision, matchedRule, errorCode);
    }

    /**
     * 是否放行。
     *
     * @return 判定为 PASS 时 true
     */
    public boolean isPassed() {
        return decision == Decision.PASS;
    }

    /**
     * 本次请求是否应计配额。
     *
     * <p>被拦的请求没调模型，**不该计费**（D2 第 5 条）；PASS 时返回 true，表示计费交给 C2 正常链路。</p>
     *
     * @return PASS 时 true，拦截时 false
     */
    public boolean isShouldChargeQuota() {
        return decision == Decision.PASS;
    }
}
