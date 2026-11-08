/*
 * 职责：客服工单的状态机——只回答「从 A 能不能到 B」，不做落库、不发通知。
 * 调用方：modules/ops 的 TicketService（每次状态变更前调用）。
 * 数据来源：《第 7 段工作流》§三 C1 第 2 条（4 态 8 条迁移）。
 * 注意：
 *   1. **纯 POJO，不依赖 Spring**：可以直接 `new` 出来单测（第 7 段规范要求状态机必须有单测）。
 *   2. **closed 是终态**：任何 reopen 都非法（410012）。工单是服务凭证，关闭后重开会让
 *      SLA 统计与「已解决」口径失真；用户有新问题请新开一条。
 *   3. **5 条合法迁移**（工作流表格里有 5 条 from→to，第 6 行是 closed 的「不可回退」说明）：
 *      waiting→processing、waiting→closed、processing→replied、processing→closed、replied→closed。
 *   4. 客户端只发意图，不许直接传目标状态——状态由服务端按动作决定。
 */
package com.faze.modules.ops.ticket;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.ops.enums.TicketStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 工单状态机。
 */
public final class TicketStateMachine {

    /** 合法迁移表：from → 允许的 to 集合。 */
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = new EnumMap<>(TicketStatus.class);

    static {
        ALLOWED.put(TicketStatus.WAITING, EnumSet.of(TicketStatus.PROCESSING, TicketStatus.CLOSED));
        ALLOWED.put(TicketStatus.PROCESSING, EnumSet.of(TicketStatus.REPLIED, TicketStatus.CLOSED));
        ALLOWED.put(TicketStatus.REPLIED, EnumSet.of(TicketStatus.CLOSED));
        // CLOSED 是终态：不登记任何出边 → 任何迁移都会被拒
        ALLOWED.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class));
    }

    /** 工具类，禁止实例化（全部是静态判定）。 */
    private TicketStateMachine() {
    }

    /**
     * 判断能否迁移。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 合法返回 true
     */
    public static boolean canTransit(TicketStatus from, TicketStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TicketStatus.class)).contains(to);
    }

    /**
     * 断言可以迁移，不允许就抛 410012。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws BusinessException 410012 工单状态不允许该操作
     */
    public static void assertCanTransit(TicketStatus from, TicketStatus to) {
        if (!canTransit(from, to)) {
            throw new BusinessException(ErrorCode.OPS_TICKET_STATUS_INVALID,
                    "工单状态不允许从 " + (from == null ? "null" : from.getCode())
                            + " 迁移到 " + (to == null ? "null" : to.getCode()));
        }
    }

    /**
     * 当前状态允许的下一步集合。
     *
     * @param from 当前状态
     * @return 允许的目标状态（不可变集合）
     */
    public static Set<TicketStatus> nextStates(TicketStatus from) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TicketStatus.class));
    }
}
