/*
 * 职责：refund_record 表的实体映射（退款单，C 状态机表）。
 * 调用方：modules/trade/mapper/RefundRecordMapper、OrderRefundService（G2.4 自动退 / G4 人工审批）。
 * 数据来源：V200__seg2_tables.sql 建的 refund_record（12 字段）。
 * 注意：
 *   1. 状态：applying（用户申请、待审）/ approved（审核通过）/ rejected（驳回）/ refunded（已到账）。
 *      段 2 的**自动全额退款**直接落 `refunded`：它不需要人工审批，也没有中间态停留。
 *   2. `transactionId = BAL-<balance_flow.id>`：把退款单与资金流水串起来的唯一线索，
 *      对账时靠它反查。为空表示「还没入账」。
 *   3. **驳回必须留原因**（`auditRemark`，段 2 拍板）：没有原因的驳回，客服无法回答用户。
 *   4. `auditedBy` 在系统自动退款时为空（没有人工审批人），不是数据缺失。
 */
package com.faze.modules.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 退款单。
 */
@Data
@TableName("refund_record")
public class RefundRecord {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单主键（orders.id）。 */
    private Long orderId;

    /** 用户申请退款的原因。 */
    private String reason;

    /** 申请退款金额（分）。 */
    private Long amountCents;

    /** 状态：applying / approved / rejected / refunded。 */
    private String status;

    /** 退款流水号（BAL-&lt;balance_flow.id&gt;）；未入账时为空。 */
    private String transactionId;

    /** 审批意见 / 驳回原因（驳回必填）。 */
    private String auditRemark;

    /** 审批人 ID（PC 后台管理员）；系统自动退款时为空。 */
    private Long auditedBy;

    /** 审批时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 退款到账时间（UTC）。 */
    private LocalDateTime refundedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
