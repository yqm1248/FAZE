/*
 * 职责：用户申请退款的入参（G4.1，`POST /app/orders/{id}/refund`）。
 * 调用方：modules/trade/controller/OrderController#refund。
 * 数据来源：请求体。
 * 注意：
 *   1. `reason` **必填**：没有原因的退款单，客服无法回答用户，也无法做退款原因统计。
 *      前端只给 4 个预设原因（设计稿 33-refund.html），但服务端不锁枚举——运营改文案不需要发版。
 *   2. `amountCents` **可空**：null = 全额退；传了就是部分退，必须 ≤ 订单实付金额，
 *      且必须 > 0（0 元退款会写出一条无意义流水）。
 *   3. 金额单位一律**分**（AGENTS §4.5），前端负责元→分换算。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 退款申请入参。
 */
@Data
public class RefundApplyBO {

    /** 退款原因（必填，≤200 字，与 order_status_log.reason 宽度一致）。 */
    @Size(max = 200, message = "reason 最长 200 字")
    private String reason;

    /** 退款金额（分）；不传 = 全额退。 */
    @Positive(message = "退款金额必须大于 0")
    private Long amountCents;
}
