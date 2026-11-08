/*
 * 职责：PC A-12 支付记录行（G9.2）。
 * 调用方：modules/trade/admin/service/AdminOrderService → AdminOrderDetailVO.payments。
 * 数据来源：faze.payment_record。
 * 注意：
 *   1. `amountCents` **退款行为负数**（拍板第 5 项）：页面按正负显示「支付 / 退款」两种颜色，
 *      不靠 `channel` 或 `transactionId` 前缀猜方向。
 *   2. `transactionId` 是内部流水号（`BAL-<flowId>`），不是第三方交易号；
 *      段 2 只有余额渠道，页面上不要写成「微信支付」。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 支付记录行。
 */
@Data
public class AdminPaymentVO {

    /** 支付记录主键。 */
    private Long id;

    /** 渠道：段 2 固定 `balance`。 */
    private String channel;

    /** 内部流水号（`BAL-<flowId>`）。 */
    private String transactionId;

    /** 金额（分）；**退款为负数**。 */
    private Long amountCents;

    /** 状态（success 等）。 */
    private String status;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;
}
