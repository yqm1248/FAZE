/*
 * 职责：A-02 核心指标卡片的出参（4 个指标 + 环比 + 数据时点）。
 * 调用方：modules/ops 的 AdminDashboardService / AdminDashboardController。
 * 数据来源：DashboardMapper 的聚合 SQL。
 * 注意：
 *   1. **环比用 `Double` 而不是 `int`**：`null` 表示「昨日为 0，无法算环比」，
 *      前端显示 `—`（工作流 A5 算做完第 4 条明确禁止返回 Infinity / NaN）。
 *      用 double 会不自觉地写出 `x / 0`。
 *   2. `revenueCents` 是**分**，前端负责转元（AGENTS §4.5：金额一律整数分）。
 *   3. `onlineCount` 本段**恒为 null**：实时在线依赖 WebSocket 会话表（conversation 域），
 *      本段不跨域读；口径文档与 §十一 都记了这一项，不返回假数。
 *   4. `note` 是给页面 hover 提示用的口径说明，**不要把算法写死在前端**。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 看板核心指标。
 */
@Data
public class DashboardSummaryVO {

    /** 时间范围：today / 7d / 30d。 */
    private String range;

    /** 时间窗内活跃用户数（DAU，降级口径：登录成功的去重用户）。 */
    private Long dailyActive;

    /** 活跃用户环比（%）；昨日为 0 时 null。 */
    private Double dailyActiveDelta;

    /** 时间窗内新增注册数。 */
    private Long newUsers;

    /** 新增注册环比（%）。 */
    private Double newUsersDelta;

    /** 时间窗内订单数（全状态）。 */
    private Long orderCount;

    /** 订单数环比（%）。 */
    private Double orderCountDelta;

    /** 时间窗内平台服务费合计（分），不含充值。 */
    private Long revenueCents;

    /** 收入环比（%）。 */
    private Double revenueDelta;

    /** 实时在线数；本段恒为 null（见类注释第 3 条）。 */
    private Integer onlineCount;

    /** 数据统计截止时间（UTC）。 */
    private LocalDateTime dataAsOf;

    /** 口径说明（页面 tooltip 用）。 */
    private String note;
}
