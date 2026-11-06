/*
 * 职责：C2 提交接口的编排结果——正常 / 降级 / 配额超限 / 输入拦截四种出口的载体。
 * 调用方：modules/ai/controller/AiConversationController（按 errorCode 决定 R 的 code 与 Retry-After 头）。
 * 数据来源：AiQuestionService 的编排结果。
 * 注意：
 *   1. **配额超限不能用 BusinessException 表达**：它要带 `Retry-After` 响应头
 *      （接口规范 §6.5 裁决 11：动态数字走 header），而全局异常处理器不写这个头。
 *      所以由 Controller 按本结果的 `retryAfterSeconds` 设置头。
 *   2. `errorCode != null` 时 `response` 为 null（失败不带 data，形状与 R.fail 一致）；
 *      `errorCode == null` 时 `response` 非 null（正常或降级）。
 *   3. 输入拦截时 user 原文与固定引导 assistant 文案**已经落库**（D2 第 4 条），
 *      只是响应体不回 data——前端按 code 显示引导，历史消息里能看到那两条。
 */
package com.faze.modules.ai.dto;

import com.faze.common.exception.ErrorCode;

/**
 * C2 提交结果。
 *
 * @param response         成功/降级时的响应体（失败时为 null）
 * @param errorCode        失败错误码（成功/降级时为 null）
 * @param retryAfterSeconds 配额超限时至重置的秒数（其他情况为 0）
 */
public record SubmitOutcome(SubmitQuestionResponse response, ErrorCode errorCode, long retryAfterSeconds) {

    /**
     * 成功（含 AI 关闭时的降级响应）。
     *
     * @param response 响应体
     * @return 成功结果
     */
    public static SubmitOutcome ok(SubmitQuestionResponse response) {
        return new SubmitOutcome(response, null, 0L);
    }

    /**
     * 配额超限（408002）。
     *
     * @param retryAfterSeconds 至次日 0 点的秒数
     * @return 失败结果
     */
    public static SubmitOutcome quotaExceeded(long retryAfterSeconds) {
        return new SubmitOutcome(null, ErrorCode.AI_QUOTA_EXCEEDED, retryAfterSeconds);
    }

    /**
     * 输入拦截（408004 / 408005）。
     *
     * @param errorCode 拦截错误码
     * @return 失败结果
     */
    public static SubmitOutcome blocked(ErrorCode errorCode) {
        return new SubmitOutcome(null, errorCode, 0L);
    }
}
