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

    /** 律师接单（G3.3）。 */
    public static final String ACTION_ACCEPT = "accept";

    /** 律师拒单（G3.3）。 */
    public static final String ACTION_REJECT = "reject";

    /** 律师结束服务（G3.4）。 */
    public static final String ACTION_COMPLETE = "complete";

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
        // 律师视角的动作见 resolveForLawyer（G3.3/G3.4）
        if (!ownerIsViewer) {
            return List.of();
        }
        return switch (status) {
            case PENDING -> List.of(ACTION_PAY, ACTION_CANCEL);
            case IN_SERVICE -> List.of(ACTION_ENTER_CHAT, ACTION_REFUND);
            case COMPLETED -> List.of(ACTION_REVIEW);
            // 退款被驳回后回到可服务状态：还能继续会话、也能再次申请退款
            case REFUND_REJECTED -> List.of(ACTION_ENTER_CHAT, ACTION_REFUND);
            // PAID = 已支付、等律师接单：**可以申请退款**（RefundService 的 APPLICABLE 就是
            // PAID / IN_SERVICE），这里不给 refund 会让「已支付」订单详情页一个动作按钮都没有
            // （工单 W05 的第一处断点）。不能给 cancel —— 钱已经扣了，只能走退款。
            case PAID -> List.of(ACTION_REFUND);
            // REFUNDING 是退款审核中、REFUNDED 是已退款、CANCELLED 是已取消：都无动作
            case CANCELLED, REFUNDING, REFUNDED -> List.of();
        };
    }

    /**
     * 推导律师视角的可执行动作。
     *
     * <p>与用户视角**完全分开**：律师在 PAID 状态能做的是接单/拒单，而用户在 PAID 状态什么都做不了
     * （只能等）。合成一个方法必然写出「用户也能接单」这种权限漏洞。</p>
     *
     * <p>`REFUND_REJECTED` 律师端也不给动作：驳回后由系统回退到服务中或已完成
     * （状态机只允许 SYSTEM 触发），律师不需要在这个中间态操作。</p>
     *
     * @param status 订单当前状态
     * @return 动作 code 列表；无动作返回空列表
     */
    public static List<String> resolveForLawyer(OrderStatus status) {
        if (status == null) {
            return List.of();
        }
        return switch (status) {
            case PAID -> List.of(ACTION_ACCEPT, ACTION_REJECT);
            case IN_SERVICE -> List.of(ACTION_COMPLETE);
            default -> List.of();
        };
    }
}
