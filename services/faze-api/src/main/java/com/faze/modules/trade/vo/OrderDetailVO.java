/*
 * 职责：订单详情（`GET /app/orders/{id}`，G2.1）。
 * 调用方：modules/trade/controller/OrderController#detail → APP「订单详情」页（30-order-detail.html）。
 * 数据来源：faze.orders 单行 + faze.lawyer 的姓名/头像/执业地区。
 * 注意：
 *   1. `actions` 是**服务端按状态机推出来的可执行动作**（pay / cancel / refund / review / enter_chat /
 *      wait_accept / refunding），前端据此渲染按钮，**不要在客户端再写一份状态判断**——
 *      两份判断必然漂移，而状态机的唯一权威在服务端（技术路线 §2.3）。
 *      但 `actions` **只是展示建议，不是鉴权依据**：真正的拦截在每个接口里自己做（越权校验 + 状态校验）。
 *   2. 不返回 `platformFeeCents` / `lawyerIncomeCents`：那是平台与律师的分账口径；
 *      用户侧只关心「我付了多少」（= amountCents）。给出去等于把抽成比例暴露给客户端。
 *   3. 不返回用户本人的手机号/身份证等敏感字段——详情页是订单，不是个人资料页。
 *   4. 时间字段一律 UTC，前端按设备时区展示。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 订单详情。
 */
@Data
public class OrderDetailVO {

    /** 订单主键。 */
    private Long id;

    /** 业务单号。 */
    private String orderNo;

    /** 服务类型 code。 */
    private String serviceType;

    /** 订单状态 code。 */
    private String status;

    /** 订单金额（分）。 */
    private Long amountCents;

    /** 用户下单备注。 */
    private String remark;

    /** 律师主键。 */
    private Long lawyerId;

    /** 律师姓名。 */
    private String lawyerName;

    /** 律师头像地址。 */
    private String lawyerAvatarUrl;

    /** 律师执业地区。 */
    private String lawyerRegion;

    /** 律师执业律所。 */
    private String lawyerFirm;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 接单时间（UTC）。 */
    private LocalDateTime acceptedAt;

    /** 服务完成时间（UTC）。 */
    private LocalDateTime finishedAt;

    /** 取消时间（UTC）。 */
    private LocalDateTime cancelledAt;

    /** 支付截止时间（UTC）；仅 PENDING 有值。 */
    private LocalDateTime payExpireAt;

    /** 当前可执行动作（服务端按状态机推导，仅供渲染按钮）。 */
    private List<String> actions;
}
