/*
 * 职责：PC 端退款审批结果（G4.2）。
 * 调用方：modules/trade/admin/controller/AdminRefundController#audit。
 * 数据来源：refund_record + orders（审批完成后的状态）。
 * 注意：只回状态与主键，不回用户隐私字段（管理员审批弹窗用不到，多回就是多一份泄露面）。
 */
package com.faze.modules.trade.admin.vo;

import lombok.Data;

/**
 * 退款审批结果。
 */
@Data
public class AdminRefundAuditResultVO {

    /** 退款单主键。 */
    private Long refundId;

    /** 订单主键。 */
    private Long orderId;

    /** 退款单状态：refunded / rejected。 */
    private String refundStatus;

    /** 审批后订单状态（全额退=refunded；部分退/驳回=退款前状态）。 */
    private String orderStatus;

    /** 审批动作：approve / reject。 */
    private String action;
}
