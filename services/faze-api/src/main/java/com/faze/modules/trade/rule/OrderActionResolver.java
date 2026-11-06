/*
 * 职责：按订单状态推导「当前可执行动作」，供详情页渲染按钮。
 * 调用方：modules/trade/converter/OrderConverter（组装 OrderDetailVO）。
 * 数据来源：入参状态 + 当前用户身份（是否下单用户 / 是否承办律师）。
 * 注意：
 *   1. **这里只回答「按钮能不能点」**，不承担鉴权：每个接口自己校验归属与状态，
 *      绕过前端直接调接口也必须被拦。把 actions 当权限用是常见错。
 *   2. 纯函数、无 Spring 依赖、无 IO —— 必须能脱离容器单测（《后端代码规范》§2.4 的
 *      `rule/` 约定）。因此这里不注入任何 Mapper。
 *   3. `wait_accept` / `refunding` 这类**不是动作而是状态提示**，不放进来：
 *      它们没有接口可调，放进来前端会渲染出一个点了没反应的按钮。
 *   4. `enter_chat` 只对已支付及之后的订单有意义（会话在律师接单时建立，G6 落地）。
 */
package com.faze.modules.trade.rule;

import com.faze.modules.trade.enums.OrderStatus;
import java.util.List;

/**
 * 订单动作推导器。
 */
public final class OrderActionResolver {

    /** 去支付。 */
    public static final String ACTION_PAY = "pay";

    /** 取消订单。 */
    public static final String ACTION_CANCEL = "cancel";

    /** 申请退款。 */
    public static final String ACTION_REFUND = "refund";

    /** 去评价。 */
    public static final String ACTION_REVIEW = "review";

    /** 进会话。 */
    public static final String ACTION_ENTER_CHAT = "enter_chat";

    private OrderActionResolver() {
    }

    /**
     * 推导某个状态下、某个身份可执行的动作。
     *
     * @param status       订单当前状态
     * @param ownerIsViewer 查看者是否为本单下单用户（律师侧 G3 传 false）
     * @return 动作 code 列表；无动作返回空列表（不返回 null）
     */
    public static List<String> resolve(OrderStatus status, boolean ownerIsViewer) {
        if (status == null) {
            return List.of();
        }
        // 律师视角的动作（接单/拒单/完成）在 G3 的 ActionResolver 里补，本段先只用用户视角
        if (!ownerIsViewer) {
            return List.of();
        }
        return switch (status) {
            case PENDING -> List.of(ACTION_PAY, ACTION_CANCEL);
            case IN_SERVICE -> List.of(ACTION_ENTER_CHAT, ACTION_REFUND);
            case COMPLETED -> List.of(ACTION_REVIEW);
            // 退款被驳回后回到可服务状态：还能继续会话、也能再次申请退款
            case REFUND_REJECTED -> List.of(ACTION_ENTER_CHAT, ACTION_REFUND);
            // PAID 是「等律师接单」，REFUNDING 是「退款审核中」，都不是可点动作
            case PAID, CANCELLED, REFUNDING, REFUNDED -> List.of();
        };
    }
}
