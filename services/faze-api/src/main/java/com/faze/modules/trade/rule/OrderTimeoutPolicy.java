/*
 * 职责：订单超时策略（支付超时 15 分钟、接单超时 30 分钟）的唯一出处。
 * 调用方：modules/trade/converter/OrderConverter（算 payExpireAt 给前端倒计时）、
 *         modules/trade/task/OrderCloseTask（G2.4 定时关单/超时退款）。
 * 数据来源：《第 2 段工作流》G2.4「PENDING 超过 15 分钟自动取消」「PAID 超过 30 分钟无人接自动退款」。
 * 注意：
 *   1. **两个时长只在这里定义一次**：前端倒计时、定时任务判定、单测都用它。
 *      分散写死两个 15 会让「改了配置但任务还按旧值跑」，这类不一致极难发现。
 *   2. 纯函数、无 Spring 依赖，可脱离容器单测（《后端代码规范》§2.4 的 `rule/` 约定）。
 *   3. 时长是**代码常量**而不是 `sys_config`：超时时长改变会让「已在队列里的任务」的
 *      `next_retry_at` 与新规则不一致，需要额外迁移；本段按工作流定为固定值，
 *      真要可配必须连带设计队列重排，不能只改一个配置项。
 */
package com.faze.modules.trade.rule;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 订单超时策略。
 */
public final class OrderTimeoutPolicy {

    /** 待支付时长：超时自动取消（2-A8 ①）。 */
    public static final Duration PAY_TIMEOUT = Duration.ofMinutes(15);

    /** 已支付等接单时长：超时自动全额退款（2-A8 ①）。 */
    public static final Duration ACCEPT_TIMEOUT = Duration.ofMinutes(30);

    private OrderTimeoutPolicy() {
    }

    /**
     * 计算支付截止时间。
     *
     * @param createdAt 下单时间（UTC）
     * @return 截止时间；入参为空时返回 null
     */
    public static LocalDateTime payExpireAt(LocalDateTime createdAt) {
        return createdAt == null ? null : createdAt.plus(PAY_TIMEOUT);
    }

    /**
     * 计算接单截止时间。
     *
     * @param paidAt 支付时间（UTC）
     * @return 截止时间；入参为空时返回 null
     */
    public static LocalDateTime acceptExpireAt(LocalDateTime paidAt) {
        return paidAt == null ? null : paidAt.plus(ACCEPT_TIMEOUT);
    }
}
