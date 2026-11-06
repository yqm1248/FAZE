/*
 * 职责：OrderStatusMachine 的单测——穷举 8×8=64 组合，断言只有 10 条合法、角色判定正确。
 * 调用方：mvn test（不启动 Spring，纯 JUnit）。
 * 数据来源：段 2 工作流 G1.2 的迁移表；期望值在测试里**硬编码**，不从生产代码读，
 *          否则「用被测对象验证被测对象」等于没测。
 * 注意：
 *   1. 64 组合里 10 条合法 / 54 条非法，数字变了就是状态机被改了，必须解释。
 *   2. 每条边的触发方也逐个断言：新增一条边却没加角色，或给了不该给的角色的权限，都会红。
 */
package com.faze.modules.trade.rule;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机穷举测试。
 */
class OrderStatusMachineTest {

    /**
     * 期望的迁移表（10 条状态对 → 允许的触发方）。
     *
     * <p>与 OrderStatusMachine 里的表**独立维护**：改了一边不改另一边，测试就会失败。</p>
     */
    private static final Map<OrderStatus, Map<OrderStatus, Set<OperatorRole>>> EXPECTED = new EnumMap<>(OrderStatus.class);

    static {
        EXPECTED.put(OrderStatus.PENDING, Map.of(
                OrderStatus.PAID, Set.of(OperatorRole.USER, OperatorRole.SYSTEM),
                OrderStatus.CANCELLED, Set.of(OperatorRole.USER, OperatorRole.SYSTEM)));
        EXPECTED.put(OrderStatus.PAID, Map.of(
                OrderStatus.IN_SERVICE, Set.of(OperatorRole.LAWYER),
                OrderStatus.REFUNDING, Set.of(OperatorRole.LAWYER, OperatorRole.SYSTEM, OperatorRole.USER)));
        EXPECTED.put(OrderStatus.IN_SERVICE, Map.of(
                OrderStatus.COMPLETED, Set.of(OperatorRole.LAWYER),
                OrderStatus.REFUNDING, Set.of(OperatorRole.USER, OperatorRole.LAWYER)));
        EXPECTED.put(OrderStatus.REFUNDING, Map.of(
                OrderStatus.REFUNDED, Set.of(OperatorRole.ADMIN, OperatorRole.SYSTEM),
                OrderStatus.REFUND_REJECTED, Set.of(OperatorRole.ADMIN)));
        EXPECTED.put(OrderStatus.REFUND_REJECTED, Map.of(
                OrderStatus.IN_SERVICE, Set.of(OperatorRole.SYSTEM),
                OrderStatus.COMPLETED, Set.of(OperatorRole.SYSTEM)));
    }

    @Test
    @DisplayName("穷举 8×8=64 组合，只有 10 条状态对合法")
    void onlyTenLegalPairs() {
        List<String> legalPairs = new ArrayList<>();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean allowed = Arrays.stream(OperatorRole.values())
                        .anyMatch(role -> OrderStatusMachine.canTransit(from, to, role));
                if (allowed) {
                    legalPairs.add(from.getCode() + "->" + to.getCode());
                }
            }
        }
        assertEquals(10, legalPairs.size(), "合法状态对必须 10 条，实际: " + legalPairs);
        assertEquals(10, OrderStatusMachine.legalPairCount(), "legalPairCount() 与实际表不一致");
    }

    @Test
    @DisplayName("每条合法边：期望角色通过、其他角色被拒")
    void roleCheckPerEdge() {
        for (Map.Entry<OrderStatus, Map<OrderStatus, Set<OperatorRole>>> fromEntry : EXPECTED.entrySet()) {
            OrderStatus from = fromEntry.getKey();
            for (Map.Entry<OrderStatus, Set<OperatorRole>> toEntry : fromEntry.getValue().entrySet()) {
                OrderStatus to = toEntry.getKey();
                Set<OperatorRole> allowed = toEntry.getValue();
                for (OperatorRole role : OperatorRole.values()) {
                    boolean expected = allowed.contains(role);
                    boolean actual = OrderStatusMachine.canTransit(from, to, role);
                    assertEquals(expected, actual,
                            "迁移 " + from.getCode() + "->" + to.getCode() + " 角色 " + role.getCode()
                                    + " 期望 " + expected + " 实际 " + actual);
                }
            }
        }
    }

    @Test
    @DisplayName("创建订单：null → PENDING 只允许用户，其余 null 迁移全非法")
    void createOrderOnlyToPending() {
        assertTrue(OrderStatusMachine.canTransit(null, OrderStatus.PENDING, OperatorRole.USER));
        assertFalse(OrderStatusMachine.canTransit(null, OrderStatus.PENDING, OperatorRole.SYSTEM));
        assertFalse(OrderStatusMachine.canTransit(null, OrderStatus.PAID, OperatorRole.USER));
        for (OrderStatus to : OrderStatus.values()) {
            if (to != OrderStatus.PENDING) {
                assertFalse(OrderStatusMachine.canTransit(null, to, OperatorRole.ADMIN),
                        "null -> " + to + " 不该合法");
            }
        }
    }

    @Test
    @DisplayName("非法迁移抛 405003，且 detail 带三元组")
    void illegalTransitionThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> OrderStatusMachine.assertCanTransit(OrderStatus.PENDING, OrderStatus.COMPLETED, OperatorRole.USER));
        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        assertEquals(405003, ex.getErrorCode().getCode());
        assertTrue(ex.getDetail() != null && ex.getDetail().contains("PENDING"));
    }

    @Test
    @DisplayName("终态没有出边（COMPLETED / CANCELLED / REFUNDED）")
    void terminalStatesHaveNoOutgoing() {
        for (OrderStatus s : new OrderStatus[]{OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.REFUNDED}) {
            assertTrue(s.isTerminal(), s + " 应为终态");
            assertTrue(OrderStatusMachine.targetsOf(s).isEmpty(), s + " 不应有出边");
        }
        assertFalse(OrderStatus.REFUND_REJECTED.isTerminal(), "REFUND_REJECTED 还要回退，不是终态");
    }

    @Test
    @DisplayName("order_status_log 的 operator_type 与 OperatorRole.code 对得上")
    void operatorRoleCodes() {
        assertEquals(Set.of("user", "lawyer", "admin", "system"),
                Set.of(OperatorRole.USER.getCode(), OperatorRole.LAWYER.getCode(),
                        OperatorRole.ADMIN.getCode(), OperatorRole.SYSTEM.getCode()));
        assertEquals(OperatorRole.SYSTEM, OperatorRole.ofOrNull("system"));
        assertEquals(null, OperatorRole.ofOrNull("nobody"));
    }
}
