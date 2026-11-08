/*
 * 职责：渠道下单时传给 PaymentGateway#prepay 的充值单视图。
 * 调用方：infra/pay 的三个网关实现、PaymentGatewayFactory。
 * 数据来源：由 RechargeService 从 recharge_record 组装，**不是实体**。
 * 注意：
 *   1. **不传 RechargeRecord 实体**（技术路线 §9.1 v1.32 补充）：网关只该看到
 *      「下单需要什么」，实体带 raw_payload / status 等它不该碰的字段。
 *   2. 金额单位是**分**，网关内部转元只在拼渠道报文时做，用 BigDecimal。
 *   3. `subject` 是渠道账单里显示的商品标题，固定「法泽账户充值」，不含用户信息。
 */
package com.faze.infra.pay.model;

import com.faze.modules.trade.enums.PayChannel;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 充值单视图（渠道下单入参）。
 */
@Data
public class RechargeOrder {

    /** 平台充值单号（对外唯一，渠道回调用它匹配）。 */
    private String orderNo;

    /** 充值用户主键（仅用于日志脱敏后定位，不发给渠道）。 */
    private Long userId;

    /** 充值金额（分）。 */
    private Long amountCents;

    /** 支付渠道。 */
    private PayChannel channel;

    /** 渠道侧订单过期时间（UTC），与 recharge_record.expire_at 一致。 */
    private LocalDateTime expireAt;

    /** 商品标题（渠道账单展示），固定「法泽账户充值」。 */
    private String subject;
}
