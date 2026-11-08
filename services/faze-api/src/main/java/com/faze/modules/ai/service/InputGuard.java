/*
 * 职责：D2 输入侧意图识别与拦截——在调模型之前拦掉三类高风险输入 + Prompt 注入。
 * 调用方：C2 的对话入口（be-stream）：收到问题先调 `check()`，未 PASS 就**不进 Prompt**。
 * 数据来源：InputGuardRules 的规则表（代码常量）；错误码 ErrorCode（408004 / 408005）。
 * 注意：
 *   1. **日志只记规则名，不记问题原文**（原文属 L4/L5 级，且技术路线 §16.2.1 要求原文落
 *      `ai_message` 而不是日志）。本类所有日志都只有 rule/decision。
 *   2. **不扣配额**：被拦的请求没调模型，不该计费（D2 第 5 条）；结果里 `shouldChargeQuota=false`。
 *   3. 命中后的落库（1 条原文 user + 1 条固定引导 assistant）是调用方 C2 的事，**本类不写库**
 *      （Service 只管判定，落库时机与事务在 C2/C3）。本类也不返回给用户的固定文案。
 *   4. 不做模型分类、不接内容审核 API（段 3 裁决：只做注入/越界识别）。
 */
package com.faze.modules.ai.service;

import com.faze.common.exception.ErrorCode;
import com.faze.modules.ai.dto.GuardResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 输入侧守卫。
 */
@Service
@Slf4j
public class InputGuard {

    /**
     * 判定一个问题能否进 Prompt。
     *
     * <p>判定顺序：注入规则 → 越界规则 → PASS。先判注入，避免一句话同时命中两类时
     * 把 408005 误报成 408004。</p>
     *
     * <p>副作用：命中拦截时打一条 INFO 日志（**只有规则名与判定**，没有问题原文）。</p>
     *
     * @param question 用户问题原文（可为 null/空白，空白直接放行交给参数校验层）
     * @return 判定结果；`matchedRule` 非空表示命中
     */
    public GuardResult check(String question) {
        if (question == null || question.isBlank()) {
            return GuardResult.pass();
        }
        for (InputGuardRules.Rule rule : InputGuardRules.INJECTION_RULES) {
            if (rule.matches(question)) {
                log.info("AI 输入拦截 decision={} rule={}", GuardResult.Decision.PROMPT_INJECTED, rule.name());
                return GuardResult.blocked(rule.name(), ErrorCode.AI_PROMPT_INJECTED);
            }
        }
        for (InputGuardRules.Rule rule : InputGuardRules.OUT_OF_SCOPE_RULES) {
            if (rule.matches(question)) {
                log.info("AI 输入拦截 decision={} rule={}", GuardResult.Decision.OUT_OF_SCOPE, rule.name());
                return GuardResult.blocked(rule.name(), ErrorCode.AI_OUT_OF_SCOPE);
            }
        }
        return GuardResult.pass();
    }
}
