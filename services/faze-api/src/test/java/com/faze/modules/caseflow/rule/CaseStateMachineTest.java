/*
 * 职责：案件状态机（CaseStateMachine）的合法 / 非法迁移单测。
 * 调用方：mvn test（surefire）。
 * 数据来源：《第 5 段工作流》§三 A2 的 9 行迁移表 + §四 5-A1 的判据。
 * 注意：
 *   1. 工作流写「覆盖 5 态之间全部 20 种组合（5×4），合法 5 条通过、非法 15 条被拒」，
 *      但同一节的表格列了 **6 条合法边**（含 accepted→closed）。本测试**按表格**实现 6 条合法 +
 *      14 条非法，并把这处文档内部数字不一致记入段 5 偏差清单（不改表格语义）。
 *   2. 每个状态还要单独验「终态没有出边」与「null 输入一律拒绝」，这两类不在 20 组合里。
 */
package com.faze.modules.caseflow.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faze.common.exception.BusinessException;
import com.faze.modules.caseflow.enums.CaseStatus;
import com.faze.modules.trade.enums.OperatorRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 案件状态机单测。
 */
class CaseStateMachineTest {

    /** 6 条合法迁移（与工作流 §三 A2 的表格逐条对应）。 */
    private static final Set<List<CaseStatus>> LEGAL_EDGES = Set.of(
            List.of(CaseStatus.FILED, CaseStatus.ACCEPTED),
            List.of(CaseStatus.FILED, CaseStatus.REJECTED),
            List.of(CaseStatus.REJECTED, CaseStatus.FILED),
            List.of(CaseStatus.ACCEPTED, CaseStatus.IN_PROGRESS),
            List.of(CaseStatus.IN_PROGRESS, CaseStatus.CLOSED),
            List.of(CaseStatus.ACCEPTED, CaseStatus.CLOSED));

    /**
     * 覆盖 5×4 = 20 种状态组合：合法 6 条通过，非法 14 条被拒。
     */
    @Test
    @DisplayName("20 种状态组合：6 条合法通过，14 条非法被拒")
    void allPairs() {
        int legal = 0;
        int illegal = 0;
        for (CaseStatus from : CaseStatus.values()) {
            for (CaseStatus to : CaseStatus.values()) {
                if (from == to) {
                    continue;
                }
                boolean expected = LEGAL_EDGES.contains(List.of(from, to));
                OperatorRole role = roleOf(from, to);
                assertThat(CaseStateMachine.canTransit(from, to, role))
                        .as("%s → %s 期望 %s", from, to, expected)
                        .isEqualTo(expected);
                if (expected) {
                    legal++;
                } else {
                    illegal++;
                }
            }
        }
        assertThat(legal).as("合法迁移条数").isEqualTo(6);
        assertThat(illegal).as("非法迁移条数").isEqualTo(14);
        assertThat(CaseStateMachine.legalPairCount()).as("状态机自报的合法对数").isEqualTo(6);
    }

    /**
     * 每条合法边在**错误角色**下必须被拒（角色校验不是摆设）。
     */
    @Test
    @DisplayName("合法边的错误角色一律被拒")
    void wrongRoleRejected() {
        for (List<CaseStatus> edge : LEGAL_EDGES) {
            CaseStatus from = edge.get(0);
            CaseStatus to = edge.get(1);
            OperatorRole correct = roleOf(from, to);
            for (OperatorRole role : OperatorRole.values()) {
                if (role == correct) {
                    continue;
                }
                assertThatThrownBy(() -> CaseStateMachine.assertCanTransit(from, to, role))
                        .as("%s → %s 用 %s 应被拒", from, to, role)
                        .isInstanceOf(BusinessException.class);
            }
        }
    }

    /**
     * 非法迁移必须抛 407002（不是静默忽略、不是通用错误）。
     */
    @Test
    @DisplayName("非法迁移抛 407002")
    void illegalTransitThrowsCode() {
        assertThatThrownBy(() ->
                CaseStateMachine.assertCanTransit(CaseStatus.CLOSED, CaseStatus.FILED, OperatorRole.ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo(407002));

        assertThatThrownBy(() ->
                CaseStateMachine.assertCanTransit(CaseStatus.REJECTED, CaseStatus.ACCEPTED, OperatorRole.ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo(407002));
    }

    /**
     * 终态没有出边；rejected 不是终态（要能回到 filed）。
     */
    @Test
    @DisplayName("终态无出边，rejected 不是终态")
    void terminalStates() {
        assertThat(CaseStatus.CLOSED.isTerminal()).isTrue();
        assertThat(CaseStatus.REJECTED.isTerminal()).isFalse();
        assertThat(CaseStateMachine.targetsOf(CaseStatus.CLOSED)).isEmpty();
        assertThat(CaseStateMachine.targetsOf(CaseStatus.REJECTED)).containsExactly(CaseStatus.FILED);
    }

    /**
     * null 输入（含「创建」误用本状态机）一律拒绝。
     */
    @Test
    @DisplayName("null 入参一律拒绝")
    void nullInputsRejected() {
        assertThat(CaseStateMachine.canTransit(null, CaseStatus.FILED, OperatorRole.USER)).isFalse();
        assertThat(CaseStateMachine.canTransit(CaseStatus.FILED, null, OperatorRole.ADMIN)).isFalse();
        assertThat(CaseStateMachine.canTransit(CaseStatus.FILED, CaseStatus.ACCEPTED, null)).isFalse();
    }

    /**
     * 每条合法边对应的正确触发方（与工作流表格的「权限」列一致）。
     *
     * @param from 迁移前
     * @param to   目标
     * @return 正确触发方；非法边返回 ADMIN（只为让断言走到「被拒」分支）
     */
    private static OperatorRole roleOf(CaseStatus from, CaseStatus to) {
        if (from == CaseStatus.FILED && (to == CaseStatus.ACCEPTED || to == CaseStatus.REJECTED)) {
            return OperatorRole.ADMIN;
        }
        if (from == CaseStatus.REJECTED && to == CaseStatus.FILED) {
            return OperatorRole.USER;
        }
        if (from == CaseStatus.ACCEPTED && (to == CaseStatus.IN_PROGRESS || to == CaseStatus.CLOSED)) {
            return OperatorRole.LAWYER;
        }
        if (from == CaseStatus.IN_PROGRESS && to == CaseStatus.CLOSED) {
            return OperatorRole.LAWYER;
        }
        return OperatorRole.ADMIN;
    }
}
