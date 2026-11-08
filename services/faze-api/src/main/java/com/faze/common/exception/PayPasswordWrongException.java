/*
 * 职责：支付密码错误的异常（第 1 段 E1，用于回传「还可尝试 N 次」）。
 * 调用方：modules/account/service/PayPasswordService 校验失败时抛出。
 * 数据来源：Redis 失败计数。
 * 注意：
 *   1. 错误码复用 `402001`（凭证错误，不区分具体是哪个错）——
 *      错误码规范里**没有「支付密码错误」专用码**，自造会破坏客户端归一化。
 *      客户端按 msgKey 走 i18n，支付场景文案由前端配置。
 *   2. 剩余次数走响应头 `X-Pay-Pwd-Remaining`，不进 body：
 *      与锁定用 `Retry-After` 同一个思路——动态数据走 header，固定文案走 msg。
 */
package com.faze.common.exception;


/**
 * 支付密码校验失败异常。
 */
public class PayPasswordWrongException extends BusinessException {

    /** 本次失败后还剩几次机会（0 表示这次已触发锁定）。 */
    private final int remainingAttempts;

    /**
     * 构造异常。
     *
     * @param remainingAttempts 剩余尝试次数，不能为负
     */
    public PayPasswordWrongException(int remainingAttempts) {
        super(ErrorCode.AUTH_LOGIN_FAILED);
        this.remainingAttempts = Math.max(remainingAttempts, 0);
    }

    /**
     * 取剩余尝试次数。
     *
     * @return 次数
     */
    public int getRemainingAttempts() {
        return remainingAttempts;
    }
}
