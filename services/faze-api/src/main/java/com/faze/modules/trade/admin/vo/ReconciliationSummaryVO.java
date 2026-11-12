/*
 * 职责：A-09 对账汇总的出参。
 * 调用方：modules/trade/admin/service/AdminReconciliationService、AdminReconciliationController。
 * 数据来源：现算（balance_flow 汇总 vs user_balance / lawyer_balance 缓存），不落表。
 * 注意：
 *   1. 计数用**原生 int**、总额用**原生 long**：它们不是 ID，
 *      必须保持 JSON 数字（JacksonConfig 只把包装类 Long 转字符串）。
 *   2. 口径与 `deploy/verify-balance.ps1` 完全一致（锚点 1-A7 要求页面与脚本结果一致）：
 *      - user：`user_balance.available_cents + frozen_cents == SUM(flow.delta_cents)`
 *      - lawyer：`lawyer_balance.available_cents + frozen_cents == SUM(flow.delta_cents)`
 *      **只比「可用 + 冻结」**：只比可用额时，任何有冻结的账户都会误报差异。
 *   3. `totalCheckedCents` 是本次检查的缓存总额（Σ 各归属方 cache 值），
 *      用于「对账覆盖了多少资金」的展示，不是流水总额。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 对账汇总。
 */
@Data
public class ReconciliationSummaryVO {

    /** 被检查的归属方数量（缓存表行数）。 */
    private int checkedOwners;

    /** 无差异数量。 */
    private int balancedOwners;

    /** 有差异数量。 */
    private int diffOwners;

    /** 被检查的缓存总额（分）。 */
    private long totalCheckedCents;

    /** 本次检查时间（UTC）。 */
    private LocalDateTime checkedAt;
}
