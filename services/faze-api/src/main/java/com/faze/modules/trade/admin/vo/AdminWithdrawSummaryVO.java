/*
 * 职责：PC 提现审核页（A-10）顶部四张统计卡。
 * 调用方：modules/trade/admin/controller/AdminWithdrawController#summary。
 * 数据来源：withdraw_record（只读聚合）。
 * 注意：
 *   1. 「今日已审」「本月已出款」都按**东八区**自然日 / 自然月切：
 *      财务的「今天」是北京时间，不是 UTC（`audited_at` 存的是 UTC，换算在 Service 做）。
 *   2. 「待审总额」= `SUM(amount_cents) WHERE status='applying'`，是**申请金额**不是手续费后金额：
 *      财务关心的是要出多少钱，实际到账 = 金额 − 手续费，差的是平台的。
 *   3. 四个数都是聚合查询，前端不许拿列表求和（列表有分页）。
 */
package com.faze.modules.trade.admin.vo;

import lombok.Data;

/**
 * 提现审核页统计卡。
 */
@Data
public class AdminWithdrawSummaryVO {

    /** 待审笔数（status=applying）。 */
    private Integer pendingCount;

    /** 待审金额（分，status=applying 的 amount 合计）。 */
    private Long pendingAmountCents;

    /** 今日已审笔数（东八区今日审核过的单，含通过与驳回）。 */
    private Integer todayAuditedCount;

    /** 本月已出款金额（分，东八区本月 status=paid 的 actual 合计）。 */
    private Long monthPaidCents;
}
