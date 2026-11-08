/*
 * 职责：退款单展示对象（C 端详情页的退款进度 / PC 审批列表）。
 * 调用方：modules/trade/controller/OrderController#refundDetail（C 端）、
 *        modules/trade/admin/controller/AdminRefundController#listByOrder（PC）。
 * 数据来源：refund_record。
 * 注意：
 *   1. 不返回 `auditedBy` 的原始管理员 id 之外的任何管理员信息（管理员资料属后台数据）。
 *   2. `transactionId`（BAL-&lt;flowId&gt;）可以给用户看：它是退款到账的凭证号，不含敏感信息。
 *   3. 金额是**分**，前端负责展示换算（AGENTS §4.5）。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 退款单 VO。
 */
@Data
public class RefundVO {

    /** 退款单主键。 */
    private Long id;

    /** 订单主键。 */
    private Long orderId;

    /** 申请原因。 */
    private String reason;

    /** 申请退款金额（分）。 */
    private Long amountCents;

    /** 状态：applying / rejected / refunded。 */
    private String status;

    /** 审批意见 / 驳回原因。 */
    private String auditRemark;

    /** 审批时间（UTC）。 */
    private LocalDateTime auditedAt;

    /** 到账时间（UTC）。 */
    private LocalDateTime refundedAt;

    /** 退款流水号（BAL-&lt;flowId&gt;）；未到账为空。 */
    private String transactionId;

    /** 申请时间（UTC）。 */
    private LocalDateTime createdAt;
}
