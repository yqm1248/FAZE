/*
 * 职责：带「多久后重试」的限流异常。与普通 BusinessException 的区别只在**响应形态**：
 *       由 GlobalExceptionHandler 转成 HTTP 429 + `Retry-After` 头，而不是统一响应体里的 200。
 * 调用方：modules/trade/service/RechargeRateLimitGuard（B3 充值时序限流）。
 * 数据来源：错误码来自 ErrorCode（`TRADE_RECHARGE_RATE_LIMITED(405030, 429)`）。
 * 注意：
 *   1. **只有状态码是 429 的错误码才允许抛本异常**：`ErrorCode.httpStatus` 是 200 的码
 *      （如 `COMMON_RATE_LIMITED` 401007）走普通 BusinessException，不要套这个类——
 *      否则 429 与 200 两种限流口径会在同一个客户端里混起来。
 *   2. 秒数走 `Retry-After` 头，**不进 msg**：msg 是 ErrorCode 的固定文案，塞动态数字会让
 *      文案、翻译、断言全部失效（与 `PayPasswordLockedException` 同一处置，2026-11-05 拍板）。
 *   3. 客户端（App / PC）必须按 HTTP 状态处理 429，不能只看 body 的 code——
 *      这正是《接口规范》§6.5 裁决 11 反对 429 的理由，负责人 2026-11-08 知情后仍选择 429。
 */
package com.faze.common.exception;

import lombok.Getter;

/**
 * 限流异常：HTTP 429 + `Retry-After` 头。
 */
@Getter
public class RateLimitedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** 建议客户端等待的秒数（写进 `Retry-After` 头）。 */
    private final long retryAfterSeconds;

    /**
     * 构造限流异常。
     *
     * @param errorCode         错误码（其 httpStatus 必须是 429）
     * @param retryAfterSeconds 建议等待秒数，小于 1 时按 1 处理
     */
    public RateLimitedException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }
}
