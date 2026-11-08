/*
 * 职责：D1 管理端人工补单接口（`POST /api/v1/admin/recharges/{orderNo}/sync`）的结果出参。
 * 调用方：modules/trade/admin/controller/AdminRechargeSyncController。
 * 数据来源：recharge_record（同步后的状态）+ RechargeTaskService.SyncOutcome（本次同步结论）。
 * 注意：
 *   1. **`syncResult` 不是错误码**：它是「这次人工触发到底做了什么」的结论，运营据此判断
 *      要不要再点一次。取值 `settled` / `marked_failed` / `channel_pending` /
 *      `channel_not_found` / `not_pending`，与 `RechargeTaskService.SyncOutcome` 一一对应（小写化）。
 *   2. `status` 与 `syncResult` 是两件事：`status` 是单据状态机的值（pending/success/failed/closed），
 *      `syncResult` 是本次动作。`channel_not_found` 时 `status` 仍是 `pending`。
 *   3. `channelTxnId` / `paidAt` 只在渠道回填之后才有值；`not_pending` 且早已 success 的单也能带上。
 *   4. 不返回渠道报文原文（`raw_payload` 属 L4 禁入字段，同样不进 VO）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 管理端人工补单结果。
 */
@Data
public class AdminRechargeSyncResultVO {

    /** 平台充值单号。 */
    private String orderNo;

    /** 同步后的单据状态：pending / success / failed / closed。 */
    private String status;

    /** 本次同步结论：settled / marked_failed / channel_pending / channel_not_found / not_pending。 */
    private String syncResult;

    /** 支付渠道：wechat / alipay / mock。 */
    private String channel;

    /** 渠道交易号；渠道未回填时为 null。 */
    private String channelTxnId;

    /** 渠道支付成功时间（UTC）；未成功为 null。 */
    private LocalDateTime paidAt;
}
