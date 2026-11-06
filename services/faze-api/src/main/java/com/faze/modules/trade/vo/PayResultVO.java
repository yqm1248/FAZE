/*
 * 职责：支付结果（`POST /app/orders/{id}/pay` 的响应，G2.2）。
 * 调用方：modules/trade/controller/OrderController#pay → APP 支付页（31-pay.html）。
 * 数据来源：orders（状态、金额、支付时间）+ balance_flow（流水 ID）。
 * 注意：
 *   1. `duplicated=true` 表示「这笔已经付过了」，**不是失败**：
 *      连点两次支付按钮、网络重试都会走到这里。客户端应直接进支付成功页。
 *   2. 返回 `balanceAfterCents`：支付页要显示「支付后余额」，让用户看到钱去哪了。
 *   3. **不返回支付密码相关的任何信息**（剩余次数走异常响应头，不在这里）。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 支付结果。
 */
@Data
public class PayResultVO {

    /** 订单主键。 */
    private Long orderId;

    /** 业务单号。 */
    private String orderNo;

    /** 订单状态（支付成功后是 paid）。 */
    private String status;

    /** 实付金额（分）。 */
    private Long amountCents;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 变动后账户余额（分）。 */
    private Long balanceAfterCents;

    /** 是否幂等命中（这笔之前已经付过）。 */
    private boolean duplicated;
}
