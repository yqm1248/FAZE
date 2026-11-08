/*
 * 职责：OrderActionResolver 的单测——8 个状态下详情页该显示哪些按钮。
 * 调用方：mvn test（纯 JUnit，不启动 Spring）。
 * 数据来源：无（纯函数）。
 * 注意：
 *   1. 断言的是「按钮清单」，不是鉴权：接口侧的越权与状态校验另有测试。
 *   2. 重点防两件事：① `REFUNDING` 不能给出任何可点动作（否则用户点了必然报错）；
 *      ② `PENDING` 必须同时有 pay 和 cancel（2-A8 ①「超时关单后不能再支付」靠状态拦，
 *      前端按钮也要跟着状态走）。
 */
package com.faze.modules.trade.rule;

import com.faze.modules.trade.enums.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单动作推导测试。
 */
class OrderActionResolverTest {

    @Test
    @DisplayName("PENDING：去支付 + 取消")
    void pendingActions() {
        assertEquals(List.of(OrderActionResolver.ACTION_PAY, OrderActionResolver.ACTION_CANCEL),
                OrderActionResolver.resolve(OrderStatus.PENDING, true));
    }

    @Test
    @DisplayName("PAID：没有可点动作（等律师接单，是状态提示不是按钮）")
    void paidHasNoAction() {
        assertTrue(OrderActionResolver.resolve(OrderStatus.PAID, true).isEmpty());
    }

    @Test
    @DisplayName("IN_SERVICE：进会话 + 申请退款")
    void inServiceActions() {
        assertEquals(List.of(OrderActionResolver.ACTION_ENTER_CHAT, OrderActionResolver.ACTION_REFUND),
                OrderActionResolver.resolve(OrderStatus.IN_SERVICE, true));
    }

    @Test
    @DisplayName("COMPLETED：去评价")
    void completedActions() {
        assertEquals(List.of(OrderActionResolver.ACTION_REVIEW),
                OrderActionResolver.resolve(OrderStatus.COMPLETED, true));
    }

    @Test
    @DisplayName("CANCELLED / REFUNDING / REFUNDED：没有可点动作")
    void noActionStates() {
        assertTrue(OrderActionResolver.resolve(OrderStatus.CANCELLED, true).isEmpty());
        assertTrue(OrderActionResolver.resolve(OrderStatus.REFUNDING, true).isEmpty());
        assertTrue(OrderActionResolver.resolve(OrderStatus.REFUNDED, true).isEmpty());
    }

    @Test
    @DisplayName("REFUND_REJECTED：能继续会话，也能再次申请退款")
    void refundRejectedActions() {
        assertEquals(List.of(OrderActionResolver.ACTION_ENTER_CHAT, OrderActionResolver.ACTION_REFUND),
                OrderActionResolver.resolve(OrderStatus.REFUND_REJECTED, true));
    }

    @Test
    @DisplayName("非本人视角：不给用户侧动作（律师侧动作 G3 再补）")
    void notOwnerHasNoActions() {
        assertTrue(OrderActionResolver.resolve(OrderStatus.PENDING, false).isEmpty());
    }

    @Test
    @DisplayName("状态为空：返回空列表而不是抛 NPE")
    void nullStatusSafe() {
        assertTrue(OrderActionResolver.resolve(null, true).isEmpty());
    }
}
