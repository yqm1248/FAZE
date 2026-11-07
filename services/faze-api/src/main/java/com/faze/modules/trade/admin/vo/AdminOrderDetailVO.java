/*
 * 职责：PC A-12 订单详情出参（G9.2：订单信息 + 支付记录 + 退款单 + 关联会话）。
 * 调用方：modules/trade/admin/service/AdminOrderService → AdminOrderController#detail。
 * 数据来源：faze.orders、faze.payment_record、faze.refund_record、faze.conversation、
 *          faze.app_user（脱敏）、faze.lawyer。
 * 注意：
 *   1. **支付记录段 2 显示 2 条**（拍板第 5 项：支付 + 退款各一条，服务费结算行在段 5）。
 *      页面必须注明「服务费结算见段 5」，否则运营会以为漏了一条。
 *   2. **关联会话按 `(user_id, lawyer_id)` 查**（拍板第 6 项：一个律师一个会话，跨订单复用），
 *      `conversationId` 可能为 null（还没聊过）；**不编造 `CV-2026-xxxxx` 这种假编号**。
 *   3. 用户/律师信息只出展示字段（昵称、脱敏手机号、律所），**不出身份证、银行卡**。
 *   4. 本 VO 不含状态流水：流水走独立接口 `GET /admin/orders/{id}/status-log`，
 *      避免详情页一次拖回几十条流水（订单详情是低频页，但流水可能上百条）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * PC 订单详情。
 */
@Data
public class AdminOrderDetailVO {

    /** 订单主键。 */
    private Long id;

    /** 业务单号。 */
    private String orderNo;

    /** 订单状态 code。 */
    private String status;

    /** 服务类型 code。 */
    private String serviceType;

    /** 订单金额（分）。 */
    private Long amountCents;

    /**
     * 平台服务费（分，段 5 B5 补）。
     *
     * <p>下单时按 `platform_fee_rate` 快照写入 `orders.platform_fee_cents`，之后改费率不影响历史单。
     * **只读展示**：服务费不进 `payment_record`（那张表永远只有支付 / 退款两行，§零之二 第 8 项）。</p>
     */
    private Long platformFeeCents;

    /** 律师收入（分，= 订单金额 - 平台服务费）；详情页金额说明里给出三者关系用。 */
    private Long lawyerIncomeCents;

    /** 用户备注。 */
    private String remark;

    /** 下单用户主键。 */
    private Long userId;

    /** 下单用户昵称。 */
    private String userName;

    /** 下单用户手机号（脱敏）。 */
    private String userPhoneMasked;

    /** 律师主键。 */
    private Long lawyerId;

    /** 律师姓名。 */
    private String lawyerName;

    /** 律师执业律所。 */
    private String lawyerFirm;

    /** 关联会话主键（按 user+lawyer 查；未聊过为 null）。 */
    private Long conversationId;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 接单时间（UTC）。 */
    private LocalDateTime acceptedAt;

    /** 完成时间（UTC）。 */
    private LocalDateTime finishedAt;

    /** 取消时间（UTC）。 */
    private LocalDateTime cancelledAt;

    /** 支付记录（段 2 最多 2 条：支付、退款）。 */
    private List<AdminPaymentVO> payments;

    /** 退款单（按时间倒序，可能有多次驳回后再申请）。 */
    private List<AdminRefundVO> refunds;
}
