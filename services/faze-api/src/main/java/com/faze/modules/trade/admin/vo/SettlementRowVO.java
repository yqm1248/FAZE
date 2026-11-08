/*
 * 职责：PC 律师结算列表行（A-13 的 8 列）。
 * 调用方：modules/trade/admin/controller/AdminSettlementController、SettlementService#list。
 * 数据来源：lawyer_income_settlement + lawyer（姓名/律所/头像）。
 * 注意：
 *   1. **`settleCents` 是应结金额，来自 `balance_flow` 的 income 净额**，
 *      不是 `incomeCents − feeCents` 现算的。两者在生成时做过硬断言（相等），
 *      但展示一律用落库值——页面上现算会掩盖生成时的口径错误（R1）。
 *   2. 金额字段一律给「分」，前端负责乘除与千分位（AGENTS.md §4.5）。
 *   3. `feeCents` 前端展示为负数（设计稿写 `− ¥540.00`），出参保持正数，
 *      符号是展示层的事，接口不掺展示约定。
 *   4. 不对前端暴露 `settledBy` 的内部 id 语义，只给 `settledAt`；
 *      谁确认的在 `operation_log` 里查（审计是证据源，列表不承担审计职责）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 律师结算列表行。
 */
@Data
public class SettlementRowVO {

    /** 结算批次主键。 */
    private Long id;

    /** 结算单号 `JS...`（对外唯一标识）。 */
    private String settlementNo;

    /** 状态 pending / settled / rejected。 */
    private String status;

    /** 律师主键。 */
    private Long lawyerId;

    /** 律师姓名。 */
    private String lawyerName;

    /** 律所名。 */
    private String lawFirm;

    /** 律师头像 URL（可空，前端回退首字头像）。 */
    private String lawyerAvatar;

    /** 账期 `yyyy-MM`。 */
    private String period;

    /** 订单数。 */
    private Integer orderCount;

    /** 订单收入合计（分）。 */
    private Long incomeCents;

    /** 平台服务费合计（分，正数；前端展示为负）。 */
    private Long feeCents;

    /** 应结金额（分，来自 balance_flow 的 income 净额）。 */
    private Long settleCents;

    /** 备注（驳回理由或人工说明）。 */
    private String remark;

    /** 确认时间（UTC）。 */
    private LocalDateTime settledAt;

    /** 生成时间（UTC）。 */
    private LocalDateTime createdAt;
}
