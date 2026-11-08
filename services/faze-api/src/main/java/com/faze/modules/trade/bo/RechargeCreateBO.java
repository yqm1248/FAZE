/*
 * 职责：App 发起充值的请求体（`POST /api/v1/app/wallet/recharges`）。
 * 调用方：modules/trade/controller/RechargeController#create。
 * 数据来源：客户端提交的 JSON。
 * 注意：
 *   1. **只有两个字段，且都没有默认值**：金额必须由客户端明确给出档位值，
 *      服务端只做区间校验，不替用户选金额（自动补默认值会让用户付错钱）。
 *   2. `amountCents` 用 `Long` 包装类而不是 `long`：缺字段时必须能区分
 *      「没传」与「传了 0」，两者都拒但错误文案不同（见 B1 校验第 ② 步）。
 *   3. **不接受客户端传的任何价格/费率/状态字段**（《后端代码规范》§11.2）：
 *      本 BO 没有 status、没有 platformFee、没有 userId——用户身份只从 token 取。
 */
package com.faze.modules.trade.bo;

import lombok.Data;

/**
 * 充值下单请求。
 */
@Data
public class RechargeCreateBO {

    /** 充值金额（分）。允许区间由 `recharge.min_cents` / `recharge.max_amount_cents` 决定。 */
    private Long amountCents;

    /** 支付渠道：`wechat` / `alipay` / `mock`，见 {@link com.faze.modules.trade.enums.PayChannel}。 */
    private String channel;
}
