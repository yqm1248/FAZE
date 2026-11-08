/*
 * 职责：段 5 E2 收入回补的结果（核对与执行两个动作共用）。
 * 调用方：AdminSettlementController（回补 / 只读核对）、SettlementService。
 * 数据来源：orders（待补订单）+ balance_flow（回补后的流水）。
 * 注意：
 *   1. `pendingBackfill()` 只填 `scanned` / `pendingCents`，其余为 0——**只读，不改任何数据**。
 *   2. `backfillIncome()` 填 `credited` / `skipped` / `creditedCents`；
 *      `scanned != credited + skipped` 说明扫描期间有订单被别的流程处理了（正常，记日志即可）。
 *   3. `pendingCents` 是**净收入合计**（amount − fee），不是订单金额合计：
 *      回补写进律师余额的是净收入，用订单金额去对会对不上。
 */
package com.faze.modules.trade.admin.vo;

import lombok.Data;

/**
 * 收入回补结果。
 */
@Data
public class BackfillResultVO {

    /** 本次扫描到的待回补订单数（只读核对模式下是「当前库里有多少笔待补」）。 */
    private int scanned;

    /** 本次真正入账成功的笔数。 */
    private int credited;

    /** 跳过的笔数（金额为 0、并发已入账等）。 */
    private int skipped;

    /** 本次入账的净收入合计（分）。 */
    private Long creditedCents;

    /** 待回补的净收入合计（分）：只读核对模式下的「如果全补上，律师总余额会增加多少」。 */
    private Long pendingCents;
}
