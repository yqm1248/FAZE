/*
 * 职责：提现规则（`GET /app/withdrawals/config`）——面板要显示的「最低提现额」与「手续费率」。
 * 调用方：modules/trade/controller/WithdrawController#config、App 66-income 的提现面板。
 * 数据来源：sys_config 的 `withdraw.min_amount_cents` / `withdraw.fee_rate`。
 * 注意：
 *   1. **为什么要在服务端给**：工作流 F1 第 3 条要求面板显示「本次手续费 ¥0.00（不足 1 分按 0 计）」，
 *      前端算这个数需要费率；写死 0.6% 会让「运营改了 sys_config 但页面还按旧费率提示」。
 *   2. 费率以**字符串**返回（如 `0.006`），不用 double：金额计算在前端只做展示换算，
 *      真正的扣费在服务端用 BigDecimal 算（AGENTS §4.5 禁止浮点运算）。
 *   3. 这是段 5 接口清单外的第 2 个补充接口（第 1 个是凭证预览），已登记偏差。
 *      不加进 `/pub/config` 白名单：那个白名单是给**匿名**用户的唯一出口，提现规则不该扩大匿名面。
 */
package com.faze.modules.trade.vo;

import lombok.Data;

/**
 * 提现规则。
 */
@Data
public class WithdrawConfigVO {

    /** 最低提现额（分）。 */
    private Long minAmountCents;

    /** 手续费率（字符串，如 `0.006`）。 */
    private String feeRate;
}
