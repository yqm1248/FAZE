/*
 * 职责：律师工作台「收入概览」（`GET /app/workbench/income?range=`）。
 * 调用方：modules/workbench/controller/AppWorkbenchController、App 66-income 页。
 * 数据来源：balance_flow（收入真相）+ lawyer_balance（缓存）+ orders（订单数）。
 * 注意：
 *   1. **金额口径以 balance_flow 为准**，lawyer_balance 只是缓存；两者不一致时以流水为准
 *      （《第 5 段工作流》§八.1 第 1 条）。
 *   2. 三档 range：week（近 7 天）/ month（近 30 天）/ total（全部）；默认 month。
 *   3. 全部金额是**整数分**，前端负责换算成元展示。
 */
package com.faze.modules.workbench.vo;

import lombok.Data;

/**
 * 收入概览。
 */
@Data
public class IncomeOverviewVO {

    /** 统计区间：week / month / total。 */
    private String range;

    /** 区间内收入合计（分，balance_flow 的 income 净额）。 */
    private Long totalIncomeCents;

    /** 区间内完成订单数。 */
    private Integer orderCount;

    /** 可提现余额（分，lawyer_balance.available_cents）。 */
    private Long availableCents;

    /** 冻结中金额（分，lawyer_balance.frozen_cents）。 */
    private Long frozenCents;

    /** 待结算金额（分，尚未解冻的 income 合计）。 */
    private Long pendingSettleCents;

    /** 已提现合计（分，withdraw 流水绝对值之和）。 */
    private Long totalWithdrawCents;
}
