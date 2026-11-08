/*
 * 职责：充值状态机（RechargeStateMachine）的合法 / 非法迁移单测。
 * 调用方：mvn test（surefire）。
 * 数据来源：《第 6 段工作流》§零之二 第 4 项（4 态 6 条迁移）与 §三 A2 第 2 条。
 * 注意：
 *   1. 4 态两两组合 4×3 = 12 种：合法 3 条（pending→success / pending→failed /
 *      pending→closed）、非法 9 条。工作流说的「6 条迁移」= 3 条合法 + 3 条终态禁令，
 *      不是 6 条合法边；本测试按**实际状态数**穷举。
 *   2. 除 12 组合外单独验三类边界：终态无出边、同态迁移非法、null 一律拒绝。
 *   3. **晚到回调的边界必须单独钉死**：`closed → success` 与 `failed → success`
 *      恒为非法。这不是「实现细节」——晚到回调走的是「新开补偿单」路径（A2 第 4 条），
 *      一旦允许原地迁移，「补偿单」逻辑就会被绕过，用户余额与渠道账目对不上。
 */
package com.faze.modules.trade.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.RechargeStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 充值状态机单测。
 */
class RechargeStateMachineTest {

    /** 3 条合法迁移（与 RechargeStatus 类注释逐条对应）。 */
    private static final Set<List<RechargeStatus>> LEGAL_EDGES = Set.of(
            List.of(RechargeStatus.PENDING, RechargeStatus.SUCCESS),
            List.of(RechargeStatus.PENDING, RechargeStatus.FAILED),
            List.of(RechargeStatus.PENDING, RechargeStatus.CLOSED));

    /**
     * 覆盖 4×3 = 12 种状态组合：合法 3 条通过，非法 9 条被拒。
     */
    @Test
    @DisplayName("12 种状态组合：3 条合法通过，9 条非法被拒")
    void allPairs() {
        int legal = 0;
        int illegal = 0;
        for (RechargeStatus from : RechargeStatus.values()) {
            for (RechargeStatus to : RechargeStatus.values()) {
                if (from == to) {
                    continue;
                }
                boolean expected = LEGAL_EDGES.contains(List.of(from, to));
                assertThat(RechargeStateMachine.canTransit(from, to))
                        .as("%s -> %s 期望 %s", from, to, expected)
                        .isEqualTo(expected);
                if (expected) {
                    legal++;
                } else {
                    illegal++;
                }
            }
        }
        assertThat(legal).as("合法边数").isEqualTo(3);
        assertThat(illegal).as("非法边数").isEqualTo(9);
    }

    /**
     * 三个终态没有出边：success / failed / closed 各 3 条目标状态全非法。
     */
    @Test
    @DisplayName("终态无出边：success / failed / closed 的 9 条出边全非法")
    void terminalStatesHaveNoOutgoingEdge() {
        for (RechargeStatus terminal : new RechargeStatus[]{
                RechargeStatus.SUCCESS, RechargeStatus.FAILED, RechargeStatus.CLOSED}) {
            assertThat(RechargeStateMachine.isTerminal(terminal)).isTrue();
            for (RechargeStatus to : RechargeStatus.values()) {
                assertThat(RechargeStateMachine.canTransit(terminal, to))
                        .as("终态 %s -> %s 必须被拒", terminal, to)
                        .isFalse();
            }
        }
    }

    /**
     * 同态迁移一律非法：pending→pending 是「重复提交」，必须明确拒绝而不是静默成功。
     */
    @Test
    @DisplayName("同态迁移：一律非法（含 pending -> pending）")
    void sameStateRejected() {
        for (RechargeStatus status : RechargeStatus.values()) {
            assertThat(RechargeStateMachine.canTransit(status, status))
                    .as("%s -> %s 同态必须被拒", status, status)
                    .isFalse();
        }
    }

    /**
     * **晚到回调边界**：已关闭 / 已失败的单不能原地改 success。
     *
     * <p>真实场景：用户超时后才在微信付款。渠道回调 success 打过来时本地单已 closed，
     * 正确处置是新开补偿单 + 入账（A2 第 4 条），不是迁移原单。</p>
     */
    @Test
    @DisplayName("晚到回调：closed/failed -> success 必须非法（走补偿新单）")
    void lateCallbackCannotTransitInPlace() {
        assertThat(RechargeStateMachine.canTransit(RechargeStatus.CLOSED, RechargeStatus.SUCCESS))
                .as("closed -> success 必须被拒，否则补偿单路径被绕过")
                .isFalse();
        assertThat(RechargeStateMachine.canTransit(RechargeStatus.FAILED, RechargeStatus.SUCCESS))
                .as("failed -> success 必须被拒")
                .isFalse();
    }

    /**
     * null 输入一律拒绝（不因为「没查到状态」就放行）。
     */
    @Test
    @DisplayName("null 输入：一律拒绝")
    void nullRejected() {
        assertThat(RechargeStateMachine.canTransit(null, RechargeStatus.SUCCESS)).isFalse();
        assertThat(RechargeStateMachine.canTransit(RechargeStatus.PENDING, null)).isFalse();
        assertThat(RechargeStateMachine.canTransit(null, null)).isFalse();
        assertThat(RechargeStateMachine.isTerminal(null)).as("null 视为不可继续").isTrue();
    }

    /**
     * 非法迁移抛 405015，合法迁移不抛。
     */
    @Test
    @DisplayName("check：非法抛 405015，合法不抛")
    void checkThrowsStateInvalid() {
        for (RechargeStatus from : RechargeStatus.values()) {
            for (RechargeStatus to : RechargeStatus.values()) {
                boolean legal = LEGAL_EDGES.contains(List.of(from, to));
                if (legal) {
                    assertThatCode(() -> RechargeStateMachine.check(from, to))
                            .as("%s -> %s 应通过", from, to)
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> RechargeStateMachine.check(from, to))
                            .as("%s -> %s 应被拒", from, to)
                            .isInstanceOf(BusinessException.class)
                            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                    .isEqualTo(ErrorCode.TRADE_RECHARGE_STATE_INVALID));
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
        List<List<RechargeStatus>> actual = new ArrayList<>();
        for (RechargeStatus from : RechargeStatus.values()) {
            for (RechargeStatus to : RechargeStatus.values()) {
                if (from != to && RechargeStateMachine.canTransit(from, to)) {
                    actual.add(List.of(from, to));
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(LEGAL_EDGES);
    }
}
