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
 *      为让页面显示「口径待定」而不是空白，本 VO 带 `pendingReason` / `pendingReasons` 两个字段。
 *   4. `note` 是给页面 hover 提示用的口径说明，**不要把算法写死在前端**。
 *   5. 4 个数值指标声明为 `Long`（包装类）而不是 `long`：允许 null 表达「口径待定」
 *      （工作流 F3 第 4 条），null 时前端读 `pendingReasons` 里的同名键取原因。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import java.util.Map;
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

    /**
     * 口径待定原因（F3 第 4 条）。
     *
     * <p>当任一指标为 `null` 时，页面必须显示「口径待定」而不是 0 或空白，
     * 原因文本从本字段读。当前只有 `onlineCount` 会触发。</p>
     */
    private String pendingReason;

    /**
     * 字段级口径待定原因（key = 指标字段名，value = 原因）。
     *
     * <p>为什么还要一个 map：`pendingReason` 只有一个字符串，当两个指标同时待定时
     * 只能表达一个原因，页面无法把原因挂到具体卡片上。字段级 map 让前端按
     * `pendingReasons.onlineCount` 精确取值。两个字段同时返回是为了兼容
     * 只认 `pendingReason` 的旧前端。</p>
     */
    private Map<String, String> pendingReasons;

    /** 数据统计截止时间（UTC）。 */
    private LocalDateTime dataAsOf;

    /** 口径说明（页面 tooltip 用）。 */
    private String note;
}
