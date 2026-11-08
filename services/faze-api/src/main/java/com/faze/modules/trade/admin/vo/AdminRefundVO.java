/*
 * 职责：PC A-12 订单详情里的退款单行（G9.2）。
 * 调用方：modules/trade/admin/service/AdminOrderService → AdminOrderDetailVO.refunds。
 * 数据来源：faze.refund_record。
 * 注意：
 *   1. `status` 是退款单自身状态（applying / refunded / rejected），**不是订单状态**：
 *      两者在页面上分属两个区块，混用会让「订单 refunding + 退款单 rejected」看起来矛盾。
 *   2. `auditedBy` 是操作人（RuoYi 管理员 id），`auditRemark` 是审批意见（驳回必填）。
 *   3. 一个订单可能有多条退款单（驳回后重新申请），页面按时间倒序展示；**只有 applying 的能审批**。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 退款单行。
 */
@Data
public class AdminRefundVO {

    /** 退款单主键。 */
    private Long id;

    /** 订单主键。 */
    private Long orderId;

    /** 申请退款金额（分）。 */
    private Long amountCents;

    /** 用户申请原因。 */
    private String reason;

    /** 退款单状态：applying / refunded / rejected。 */
    private String status;

    /** 审批意见 / 驳回原因。 */
    private String auditRemark;

    /** 审批人（管理员 id；Long 会被序列化成字符串）。 */
    private Long auditedBy;

    /** 审批时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 到账时间（UTC）。 */
    private LocalDateTime refundedAt;

    /** 退款流水号（`BAL-<flowId>`）。 */
    private String transactionId;

    /** 申请时间（UTC）。 */
    private LocalDateTime createdAt;
}
