/*
 * 职责：单笔充值单详情（App 从渠道返回后轮询用）。
 * 调用方：modules/trade/controller/RechargeController#detail → App 充值结果页。
 * 数据来源：recharge_record + balance_flow 汇总（`balanceAfterCents`）。
 * 注意：
 *   1. **`balanceAfterCents` 取流水汇总**，与段 1 D1 同源（锚点 1-A6）；
 *      不许改成读 `user_balance` 缓存——缓存与流水不一致时，用户看到的就是假余额。
 *   2. **状态同时给机器值和 i18n key**：`status` 给前端做分支（pending/success/failed/closed），
 *      `statusKey` 给前端显示。不返回中文，四语种共用。
 *   3. **不返回 `rawPayload`**：渠道原始报文属 L4 禁入字段，只落库。
 *   4. `paidAt` / `expireAt` 是 UTC；未支付时 `paidAt` 为 null（JSON 里省略）。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 充值单详情。
 */
@Data
public class RechargeDetailVO {

    /** 平台充值单号。 */
    private String orderNo;

    /** 状态持久化值：pending / success / failed / closed。 */
    private String status;

    /** 状态名 i18n key，形如 `trade.recharge.statusPending`。 */
    private String statusKey;

    /** 充值金额（分）。 */
    private long amountCents;

    /** 支付渠道持久化值。 */
    private String channel;

    /** 渠道名 i18n key。 */
    private String channelKey;

    /** 渠道支付成功时间（UTC）；未成功为 null。 */
    private LocalDateTime paidAt;

    /** 下单时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 支付截止时间（UTC）。 */
    private LocalDateTime expireAt;

    /** 当前可用余额（分），取流水汇总。 */
    private long balanceAfterCents;
}
