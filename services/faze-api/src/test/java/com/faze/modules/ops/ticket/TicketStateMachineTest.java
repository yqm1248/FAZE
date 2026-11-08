/*
 * 职责：工单状态机的单元测试（纯 POJO，不启动 Spring）。
 * 调用方：CI（mvn test）。
 * 数据来源：无（纯内存判定）；测试用例对照《第 7 段工作流》§三 C1 的 4 态 8 条迁移。
 * 注意：
 *   1. **必须覆盖「非法迁移被拒」**：这是后端规范 §12.4 对状态机的硬要求，
 *      也是 410012 这条错误码唯一的验证点。
 *   2. 覆盖 5 条合法迁移 + closed 的 3 条出边全非法 + null 边界。
 */
package com.faze.modules.ops.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.enums.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工单状态机单测。
 */
class TicketStateMachineTest {

    @Test
    @DisplayName("waiting → processing：分配/首次回复，合法")
    void waitingToProcessing() {
        assertThat(TicketStateMachine.canTransit(TicketStatus.WAITING, TicketStatus.PROCESSING)).isTrue();
    }

    @Test
    @DisplayName("waiting → closed：无效工单直接关闭，合法")
    void waitingToClosed() {
        assertThat(TicketStateMachine.canTransit(TicketStatus.WAITING, TicketStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("processing → replied：管理员回复，合法")
    void processingToReplied() {
        assertThat(TicketStateMachine.canTransit(TicketStatus.PROCESSING, TicketStatus.REPLIED)).isTrue();
    }

    @Test
    @DisplayName("processing → closed：处理中直接关闭，合法")
    void processingToClosed() {
        assertThat(TicketStateMachine.canTransit(TicketStatus.PROCESSING, TicketStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("replied → closed：回复后关闭，合法")
    void repliedToClosed() {
        assertThat(TicketStateMachine.canTransit(TicketStatus.REPLIED, TicketStatus.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("closed → 任何状态都非法（终态不可回退）")
    void closedIsTerminal() {
        for (TicketStatus target : TicketStatus.values()) {
            assertThat(TicketStateMachine.canTransit(TicketStatus.CLOSED, target)).isFalse();
        }
        assertThatThrownBy(() -> TicketStateMachine.assertCanTransit(TicketStatus.CLOSED, TicketStatus.PROCESSING))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OPS_TICKET_STATUS_INVALID);
    }

    @Test
    @DisplayName("waiting → replied：跳过 processing 非法")
    void waitingCannotSkipToReplied() {
        assertThatThrownBy(() -> TicketStateMachine.assertCanTransit(TicketStatus.WAITING, TicketStatus.REPLIED))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OPS_TICKET_STATUS_INVALID);
    }

    @Test
    @DisplayName("replied → processing：用户追问不在本段定义内，非法")
    void repliedCannotGoBackToProcessing() {
        assertThat(TicketStateMachine.canTransit(TicketStatus.REPLIED, TicketStatus.PROCESSING)).isFalse();
    }

    @Test
    @DisplayName("null 入参不抛异常，判定为不可迁移")
    void nullSafe() {
        assertThat(TicketStateMachine.canTransit(null, TicketStatus.PROCESSING)).isFalse();
        assertThat(TicketStateMachine.canTransit(TicketStatus.WAITING, null)).isFalse();
        assertThat(TicketStateMachine.canTransit(null, null)).isFalse();
    }

    @Test
    @DisplayName("nextStates 返回当前状态的合法出边")
    void nextStates() {
        assertThat(TicketStateMachine.nextStates(TicketStatus.WAITING))
                .containsExactlyInAnyOrder(TicketStatus.PROCESSING, TicketStatus.CLOSED);
        assertThat(TicketStateMachine.nextStates(TicketStatus.CLOSED)).isEmpty();
    }
}
