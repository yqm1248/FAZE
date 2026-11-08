/*
 * 职责：渠道退款请求（PaymentGateway#refund 的入参）。
 * 调用方：C3 的「重复支付自动原路退回」——段 6 唯一使用场景。
 * 数据来源：本地充值单 + 渠道交易号。
 * 注意：
 *   1. **段 6 不做用户主动退款**（退款走段 2 的余额体系，钱没出过平台）。
 *      这个 refund 只用于「同一笔充值被渠道重复扣款」时的原路退回。
 *   2. `amountCents` 必须是**全额**：渠道侧部分退款会让对账口径变复杂，
 *      段 6 不实现部分退款。
 */
package com.faze.infra.pay.model;

import com.faze.modules.trade.enums.PayChannel;
import lombok.Data;

/**
 * 渠道退款请求。
 */
@Data
public class RefundRequest {

    /** 渠道。 */
    private PayChannel channel;

    /** 平台充值单号（原单）。 */
    private String orderNo;

    /** 渠道交易号（原单）。 */
    private String channelTxnId;

    /** 退款金额（分），必须等于原单金额。 */
    private Long amountCents;

    /** 退款原因（写进渠道备注，≤80 字）。 */
    private String reason;
}
