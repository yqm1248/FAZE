/*
 * 职责：支付密码校验被锁定时的异常（第 1 段 E2，锚点 1-A5）。
 * 调用方：modules/account/service/PayPasswordService（锁定期内校验直接抛）。
 * 数据来源：Redis 锁定 key 的 TTL。
 * 注意：
 *   1. 错误码复用 `401007`（限流）——错误码规范**没有「支付密码锁定」专用码**，
 *      自造新码会破坏客户端错误码归一化（负责人 2026-11-05 拍板方案 A）。
 *   2. **剩余秒数走响应头 `Retry-After`**（HTTP 标准），不进 body：
 *      body 的 msg 是 ErrorCode 的固定文案，把动态数字塞进 msg 会让「文案」与「数据」混在一起，
 *      翻译、缓存、断言全部失效。
 *   3. 继承 BusinessException 是为了复用统一的异常出口与日志口径。
 */
package com.faze.common.exception;


/**
 * 支付密码锁定异常。
 */
public class PayPasswordLockedException extends BusinessException {

    /** 剩余锁定秒数，用于 Retry-After 头。 */
    private final long remainingSeconds;

    /**
     * 构造锁定异常。
     *
     * @param remainingSeconds 剩余锁定秒数（至少 1）
     */
    public PayPasswordLockedException(long remainingSeconds) {
        super(ErrorCode.COMMON_RATE_LIMITED);
        this.remainingSeconds = Math.max(remainingSeconds, 1L);
    }

    /**
     * 取剩余锁定秒数。
     *
     * @return 秒数
     */
    public long getRemainingSeconds() {
        return remainingSeconds;
    }
}
