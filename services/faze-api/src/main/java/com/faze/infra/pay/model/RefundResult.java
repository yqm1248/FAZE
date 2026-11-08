/*
 * 职责：渠道退款结果（PaymentGateway#refund 的返回）。
 * 调用方：C3 的重复支付处置。
 * 数据来源：各网关实现。
 * 注意：
 *   1. **退款成功与否只影响「重复支付」这一条旁路**，不影响主链路入账：
 *      用户余额按第一次成功的支付入账，多扣的那笔原路退回。
 *   2. 退款失败要打 ERROR 并把单号留给人工（渠道后台可手工退），
 *      **不许静默吞掉**——那是用户的钱。
 */
package com.faze.infra.pay.model;

import lombok.Data;

/**
 * 渠道退款结果。
 */
@Data
public class RefundResult {

    /** 退款是否受理成功（渠道返回成功即 true，到账是异步的）。 */
    private boolean success;

    /** 渠道退款单号。 */
    private String refundId;

    /** 失败原因（成功时为 null）；只进日志，不进响应。 */
    private String message;
}
