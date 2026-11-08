/*
 * 职责：渠道回调解析结果（PaymentGateway#parseCallback 的返回）。
 * 调用方：C 组回调（RechargeCallbackService）——解析 → 验签 → CAS 入账。
 * 数据来源：渠道异步通知报文 + 请求头。
 * 注意：
 *   1. **验签不通过必须抛异常，不许返回 success=false 混过去**：405017（回调验签失败）
 *      是安全事件，要写审计。返回 false 会把它当「支付失败」处理，等于把攻击当业务失败。
 *   2. `sanitizedPayload` 是**脱敏后**的报文（去掉 openid / 卡号尾号等 L4/L5 字段），
 *      落 recharge_record.raw_payload；**原始报文不进日志**（L4 禁入）。
 *   3. `amountCents` 由网关把渠道的「元」转成「分」——转换只在网关里做一次，
 *      业务层拿到的永远是整数分。
 */
package com.faze.infra.pay.model;

import com.faze.modules.trade.enums.PayChannel;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 渠道回调解析结果。
 */
@Data
public class CallbackResult {

    /** 渠道。 */
    private PayChannel channel;

    /** 平台充值单号（渠道原样回传的 out_trade_no）。 */
    private String orderNo;

    /** 渠道交易号（微信 transaction_id / 支付宝 trade_no）。 */
    private String channelTxnId;

    /** 渠道实付金额（分）。 */
    private Long amountCents;

    /** 渠道是否判定为支付成功。 */
    private boolean success;

    /** 渠道支付成功时间（UTC）；失败通知为 null。 */
    private LocalDateTime paidAt;

    /** 脱敏后的报文（落库用，禁止进日志）。 */
    private String sanitizedPayload;
}
