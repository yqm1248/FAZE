/*
 * 职责：lawyer_income_settlement 表的实体映射（律师结算批次，第 5 段 E4）。
 * 调用方：modules/trade 的 SettlementService、AdminSettlementController、PC A-13 律师结算页。
 * 数据来源：V506__create_lawyer_income_settlement.sql（14 字段）。
 * 注意：
 *   1. **本表不是资金真相**，只是结算批次状态记录。删掉任意一行，律师的钱一分不少；
 *      真相永远是 balance_flow。confirm 只改 status，不转账、不改 lawyer_balance、不写流水。
 *      这是「结算与提现重复打款」的唯一防线（《第 5 段工作流》§零之二 第 1 项）。
 *   2. `period` 是账期字符串 `yyyy-MM`，口径是**东八区**（财务按自然月对账），
 *      与库内 UTC 时间字段不是一回事，不要拿 created_at 去推账期。
 *   3. `settleCents` 来自 `balance_flow` 的 `income` 净额，**不是**用 orders 重算；
 *      生成时断言 `incomeCents − feeCents == settleCents`，不成立就不许写单（E4 第 3 条）。
 *   4. 无 `deleted_at`：结算批次是状态机表，不做软删（《数据表规范》四类表口径）。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 律师结算批次（一位律师 × 一个账期一条）。
 */
@Data
@TableName("lawyer_income_settlement")
public class LawyerIncomeSettlement {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务单号：JS + yyyyMMdd + 6 位序号（无分隔符），对外只暴露它。 */
    private String settlementNo;

    /** 律师主键（lawyer.id）。 */
    private Long lawyerId;

    /** 账期 `yyyy-MM`（东八区自然月）。 */
    private String period;

    /** 该账期完成订单数（来自 orders）。 */
    private Integer orderCount;

    /** 订单收入合计（来自 orders.amount_cents）。 */
    private Long incomeCents;

    /** 平台服务费合计（来自 orders.platform_fee_cents），展示为负数。 */
    private Long feeCents;

    /** 应结金额（来自 balance_flow 的 income 净额）。 */
    private Long settleCents;

    /** 状态：pending / settled / rejected，见 {@link com.faze.modules.trade.enums.SettlementStatus}。 */
    private String status;

    /** 确认人 app_user.id（管理端）。 */
    private Long settledBy;

    /** 确认时间（UTC）。 */
    private LocalDateTime settledAt;

    /** 备注（驳回理由或人工说明）。 */
    private String remark;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
