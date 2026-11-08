/*
 * 职责：结算单明细里的一行（A-13「明细」抽屉的逐单列表）。
 * 调用方：SettlementService#detail、SettlementDetailVO。
 * 数据来源：orders（订单号 / 完成时间 / 订单金额 / 服务费）+ orders.lawyer_income_cents（净收入）。
 * 注意：
 *   1. **净收入取 `orders.lawyer_income_cents`（段 2 已算好的快照）**，
 *      不在这里用 amount − fee 现算：费率改过后现算会与入账金额不一致。
 *   2. 数据是**实时查**的，不是结算时冻结的快照——工作流 E5 第 4 条明确「明细从 orders + balance_flow 实时查」。
 *      这样结算单与明细不会因为你补了回补脚本而对不上。
 *   3. `incomeCents` 与 `orderNo` 是 `5-A4`「每条明细都能点出订单号」的落点，
 *      前端必须把它们渲染出来，不许只显示金额。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 结算单逐单明细行。
 */
@Data
public class SettlementItemVO {

    /** 订单主键。 */
    private Long orderId;

    /** 订单号 `FAZE...`（对外，前端要能点出去）。 */
    private String orderNo;

    /** 服务完成时间（UTC）；账期归期依据。 */
    private LocalDateTime finishedAt;

    /** 订单金额（分）。 */
    private Long amountCents;

    /** 平台服务费（分，正数）。 */
    private Long feeCents;

    /** 律师净收入（分）= orders.lawyer_income_cents。 */
    private Long incomeCents;
}
