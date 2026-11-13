/*
 * 职责：结算单详情（A-13「明细」抽屉）——批次头 + 逐单明细。
 * 调用方：AdminSettlementController#detail。
 * 数据来源：lawyer_income_settlement + orders。
 * 注意：
 *   1. 明细是**实时查 orders**，不是生成批次时冻结的快照（工作流 E5 第 4 条）。
 *   2. **口径（2026-11-13 负责人拍板）：明细合计 = Σ订单成交额 `amount_cents`**，
 *      必须等于批次头的「订单收入」`incomeCents`（后者同样是 Σ`amount_cents`，见 SettlementAgg）。
 *      不相等说明该账期在生成后又发生了订单变动——Service 会把 `consistent` 置 false，
 *      前端要在抽屉顶部给出醒目提示，而不是静默展示两个不同的数。
 *      **不要拿明细合计去比「应结金额」**：应结 = 订单收入 − 平台服务费，本来就不相等（盲测 B85）。
 *   3. `consistent` 只做展示提示，**不阻断确认**：确认是财务对「这一批已转完账」的确认，
 *      数据漂移要人工判断，系统不替财务做决定。
 */
package com.faze.modules.trade.admin.vo;

import java.util.List;
import lombok.Data;

/**
 * 结算单详情。
 */
@Data
public class SettlementDetailVO {

    /** 批次头（与列表行同构，前端不用写两套渲染）。 */
    private SettlementRowVO settlement;

    /** 逐单明细（按完成时间升序）。 */
    private List<SettlementItemVO> items;

    /** 明细成交额合计（分）= Σ orders.amount_cents，供前端与批次头「订单收入」对照。 */
    private Long itemsTotalCents;

    /** 明细成交额合计是否等于批次头的「订单收入」（不是「应结金额」）。 */
    private Boolean consistent;
}
