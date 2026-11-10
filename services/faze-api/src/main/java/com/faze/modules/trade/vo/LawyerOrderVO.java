/*
 * 职责：律师侧订单行（`GET /app/orders/lawyer`，G3.3）。
 * 调用方：modules/trade/service/LawyerOrderService → APP「律师订单管理」页（64-lawyer-orders.html）。
 * 数据来源：faze.orders + faze.app_user（下单人昵称）。
 * 注意：
 *   1. **只给昵称，不给手机号**：手机号是 L2 敏感，律师侧展示需求可以先靠站内消息沟通；
 *      真要给手机号必须走单独脱敏字段（`139****0009`）并记录查看行为，本段不做。
 *   2. `actions` 是服务端按律师视角推导的动作（accept / reject / complete），
 *      前端据此渲染按钮，**不自己判断状态**。
 *   3. `lawyerIncomeCents` 只出现在律师侧：这是**有效预计收入**（订单记账口径，**不是真到账**），
 *      仅 `in_service` / `completed` 下发，其余状态为 null；结算属段 5。
 *      给用户侧看会泄露平台抽成比例。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 律师侧订单行。
 */
@Data
public class LawyerOrderVO {

    /** 订单主键。 */
    private Long id;

    /** 业务单号。 */
    private String orderNo;

    /** 服务类型 code。 */
    private String serviceType;

    /** 订单状态 code。 */
    private String status;

    /** 订单金额（分，用户实付）。 */
    private Long amountCents;

    /**
     * 有效预计收入（分）。
     *
     * <p>仅 `in_service` / `completed` 有值；其它状态（含退款中 / 已退款 / 已取消）为
     * {@code null}，表示这笔钱不计入律师收入。这不是「真到账」——结算与提现见段 5。</p>
     */
    private Long lawyerIncomeCents;

    /** 用户下单备注。 */
    private String remark;

    /** 下单人昵称。 */
    private String userNickname;

    /** 下单时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 支付时间（UTC）。 */
    private LocalDateTime paidAt;

    /** 接单时间（UTC）。 */
    private LocalDateTime acceptedAt;

    /** 律师可执行动作（accept / reject / complete）。 */
    private List<String> actions;
}
