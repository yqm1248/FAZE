/*
 * 职责：提现状态机（WithdrawStateMachine）的合法 / 非法迁移单测。
 * 调用方：mvn test（surefire）。
 * 数据来源：《第 5 段工作流》§三 F3 的「状态机单测」要求 + WithdrawStatus 的类注释。
 * 注意：
 *   1. 4 态两两组合 4×3 = 12 种：合法 3 条、非法 9 条。工作流 F3 原文写
 *      「5 条合法 + 4 条非法」——那组数字既不等于 12 也不等于实际边数，
 *      本测试按**实际状态数**穷举，并把文档内部矛盾记入段 5 偏差清单。
 *   2. 除了 12 组合，还要单独验：终态没有出边、同态迁移非法、null 输入一律拒绝。
 *      前两类的「不合法」原因不同（终态是业务上走完了，同态是重复提交），不能合并。
 */

package com.faze.modules.trade.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.WithdrawStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 提现状态机单测。
 */
class WithdrawStateMachineTest {

    /** 3 条合法迁移（与 WithdrawStatus 类注释逐条对应）。 */
    private static final Set<List<WithdrawStatus>> LEGAL_EDGES = Set.of(
            List.of(WithdrawStatus.APPLYING, WithdrawStatus.APPROVED),
            List.of(WithdrawStatus.APPLYING, WithdrawStatus.REJECTED),
            List.of(WithdrawStatus.APPROVED, WithdrawStatus.PAID));

    /**
     * 覆盖 4×3 = 12 种状态组合：合法 3 条通过，非法 9 条被拒。
     */
    @Test
    @DisplayName("12 种状态组合：3 条合法通过，9 条非法被拒")
    void allPairs() {
        int legal = 0;
        int illegal = 0;
        for (WithdrawStatus from : WithdrawStatus.values()) {
            for (WithdrawStatus to : WithdrawStatus.values()) {
                if (from == to) {
                    continue;
                }
                boolean expected = LEGAL_EDGES.contains(List.of(from, to));
                assertThat(WithdrawStateMachine.canTransit(from, to))
                        .as("%s -> %s 期望 %s", from, to, expected)
                        .isEqualTo(expected);
                if (expected) {
                    legal++;
                } else {
                    illegal++;
                }
            }
        }
        assertThat(legal).as("合法迁移应为 3 条").isEqualTo(3);
        assertThat(illegal).as("非法迁移应为 9 条").isEqualTo(9);
    }

    /**
     * 终态（rejected / paid）没有出边。
     */
    @Test
    @DisplayName("终态没有出边：rejected / paid 无法再迁移")
    void terminalStatesHaveNoOutgoingEdges() {
        for (WithdrawStatus terminal : List.of(WithdrawStatus.REJECTED, WithdrawStatus.PAID)) {
            assertThat(WithdrawStateMachine.isTerminal(terminal)).as("%s 是终态", terminal).isTrue();
            for (WithdrawStatus target : WithdrawStatus.values()) {
                assertThat(WithdrawStateMachine.canTransit(terminal, target))
                        .as("终态 %s 不应能迁移到 %s", terminal, target)
                        .isFalse();
            }
        }
    }

    /**
     * 同态迁移非法：重复点「通过」不能被当成合法迁移。
     */
    @Test
    @DisplayName("同态迁移非法：applying -> applying 被拒")
    void selfTransitRejected() {
        for (WithdrawStatus status : WithdrawStatus.values()) {
            assertThat(WithdrawStateMachine.canTransit(status, status))
                    .as("%s -> %s 应非法", status, status)
                    .isFalse();
        }
    }

    /**
     * null 输入一律拒绝（不因为「没查到状态」就放行）。
     */
    @Test
    @DisplayName("null 输入：一律拒绝")
    void nullRejected() {
        assertThat(WithdrawStateMachine.canTransit(null, WithdrawStatus.APPROVED)).isFalse();
        assertThat(WithdrawStateMachine.canTransit(WithdrawStatus.APPLYING, null)).isFalse();
        assertThat(WithdrawStateMachine.canTransit(null, null)).isFalse();
        assertThat(WithdrawStateMachine.isTerminal(null)).as("null 视为不可继续").isTrue();
    }

    /**
     * 非法迁移抛 405025，合法迁移不抛。
     */
    @Test
    @DisplayName("check：非法抛 405025，合法不抛")
    void checkThrowsStateInvalid() {
        for (WithdrawStatus from : WithdrawStatus.values()) {
            for (WithdrawStatus to : WithdrawStatus.values()) {
                boolean legal = LEGAL_EDGES.contains(List.of(from, to));
                if (legal) {
                    assertThatCode(() -> WithdrawStateMachine.check(from, to))
                            .as("%s -> %s 应通过", from, to)
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> WithdrawStateMachine.check(from, to))
                            .as("%s -> %s 应被拒", from, to)
                            .isInstanceOf(BusinessException.class)
                            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                    .isEqualTo(ErrorCode.TRADE_WITHDRAW_STATE_INVALID));
                }
            }
        }
    }

    /**
     * 穷举后的完整合法边清单（防「改了一条边却没改另一处」）。
     */
    @Test
    @DisplayName("合法边清单：恰好 3 条且与业务定义一致")
    void legalEdgeInventory() {
        List<List<WithdrawStatus>> actual = new ArrayList<>();
        for (WithdrawStatus from : WithdrawStatus.values()) {
            for (WithdrawStatus to : WithdrawStatus.values()) {
                if (WithdrawStateMachine.canTransit(from, to)) {
                    actual.add(List.of(from, to));
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(LEGAL_EDGES);
    }
}
