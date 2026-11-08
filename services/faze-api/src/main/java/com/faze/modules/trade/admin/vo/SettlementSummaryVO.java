/*
 * 职责：PC 律师结算页（A-13）顶部三张统计卡的数据。
 * 调用方：modules/trade/admin/controller/AdminSettlementController#summary。
 * 数据来源：lawyer_income_settlement（只读聚合）。
 * 注意：
 *   1. 「本月已结算」按 `settled_at` 落在**东八区当月**统计，不按 `created_at`：
 *      财务关心的是「这个月确认了多少」，不是「这个月生成了多少」。
 *   2. 「涉及律师」只数 `pending` 单，去重后是**待结算单涉及的律师数**，
 *      不是全平台律师数（设计稿原文口径，见工作流 E5 第 1 条）。
 *   3. 三个数都是聚合查询得来，不允许前端拿列表数据自己求和——
 *      列表有分页，前端求和必然偏小。
 */
package com.faze.modules.trade.admin.vo;

import lombok.Data;

/**
 * 结算页统计卡。
 */
@Data
public class SettlementSummaryVO {

    /** 待结算总额（分）：`SUM(settle_cents) WHERE status='pending'`。 */
    private Long pendingTotalCents;

    /** 本月已结算（分）：本月 `settled` 批次的合计。 */
    private Long settledThisMonthCents;

    /** 涉及律师数：待结算单的律师去重数。 */
    private Integer lawyerCount;
}
