/*
 * 职责：PC A-11 订单列表行（G9.1）。
 * 调用方：modules/trade/admin/service/AdminOrderService → AdminOrderController。
 * 数据来源：faze.orders + faze.lawyer（批量）+ faze.app_user（批量，仅昵称与脱敏手机号）。
 * 注意：
 *   1. **金额单位是分**（`amountCents`）：PC 前端负责显示成元（`views/biz/shared/format.js`）。
 *      接口层不做元转换——一旦接口给元，两端各有一套换算，迟早对不上。
 *   2. 用户手机号**必须脱敏**（`phoneMasked`）：管理端列表是高频查看页面，
 *      给明文等于把全站用户手机号摊开（审计与日志规范 §9）。
 *   3. 不返回 `platformFeeCents` / `lawyerIncomeCents`：分账口径在段 5 结算页展示，
 *      订单列表只需要「用户付了多少」。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 订单列表行。
 */
@Data
public class AdminOrderRowVO {

    /** 订单主键。 */
    private Long id;

    /** 业务单号。 */
    private String orderNo;

    /** 订单状态 code（draft / pending / paid / in_service / completed / cancelled / refunding / refunded / refund_rejected）。 */
    private String status;

    /** 服务类型 code。 */
    private String serviceType;

    /** 订单金额（分）。 */
    private Long amountCents;

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

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 完成时间（UTC）。 */
    private LocalDateTime finishedAt;
}
