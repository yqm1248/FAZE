/*
 * 职责：充值单状态机（4 态 / 3 条合法边）的合法与非法迁移判定。
 * 调用方：modules/trade/service/RechargeService、C 组回调（RechargeCallbackService）、
 *         D 组关单与补单任务（写库前先判一次，非法迁移直接 405015）。
 * 数据来源：《第 6 段工作流》§零之二 第 4 项（4 态 6 条迁移）与 §三 A2 第 2 条。
 * 注意：
 *   1. **本文档的「6 条迁移」= 3 条合法边 + 3 条「终态不可再迁」的禁令**，不是 6 条合法边。
 *      4 态两两组合共 4×3 = 12 种，合法只有 3 条（pending→success / pending→failed /
 *      pending→closed），其余 9 条全非法（含同态迁移 pending→pending，重复提交必须被拒）。
 *   2. **本类只是内存里的显式校验，不是并发防线**。并发防线是 SQL 的 CAS
 *      （`UPDATE ... WHERE status = from`，见 RechargeRecordMapper#casTransit）。
 *      两者都要：状态机给出明确原因，CAS 挡住并发（两个回调同时打同一张 pending 单，
 *      只有一个能迁移成功）。
 *   3. **晚到回调不是本类的职责**：单已 closed / failed 时收到 success 回调，
 *      **不迁移原单**，而是新开补偿单（工作流 A2 第 4 条）。所以这里
 *      `canTransit(CLOSED, SUCCESS)` 必须是 false——否则会把「补偿新单」的路径改坏。
 */
package com.faze.modules.trade.rule;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.RechargeStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 充值单状态机。
 *
 * <p>4 个状态：{@code pending} / {@code success} / {@code failed} / {@code closed}。</p>
 */
public final class RechargeStateMachine {

    /**
     * 3 条合法迁移（与 RechargeStatus 的类注释逐条对应）。
     */
    private static final Set<List<RechargeStatus>> LEGAL_EDGES = Set.of(
            List.of(RechargeStatus.PENDING, RechargeStatus.SUCCESS),
            List.of(RechargeStatus.PENDING, RechargeStatus.FAILED),
            List.of(RechargeStatus.PENDING, RechargeStatus.CLOSED));

    private RechargeStateMachine() {
    }

    /**
     * 判断一次状态迁移是否合法。
     *
     * @param from 原状态（null 视为非法）
     * @param to   目标状态（null 视为非法）
     * @return 合法返回 true；任一为 null、或不在合法边集合内返回 false
     */
    public static boolean canTransit(RechargeStatus from, RechargeStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return LEGAL_EDGES.contains(List.of(from, to));
    }

    /**
     * 校验一次状态迁移，非法直接抛 405015。
     *
     * @param from 原状态
     * @param to   目标状态
     * @throws BusinessException 405015 充值单状态非法
     */
    public static void check(RechargeStatus from, RechargeStatus to) {
        if (!canTransit(from, to)) {
            throw new BusinessException(ErrorCode.TRADE_RECHARGE_STATE_INVALID,
                    "充值单不允许从 " + codeOf(from) + " 迁移到 " + codeOf(to));
        }
    }

    /**
     * 是否为终态（success / failed / closed 没有出边）。
     *
     * @param status 状态（null 返回 true，视为不可继续）
     * @return 终态返回 true
     */
    public static boolean isTerminal(RechargeStatus status) {
        return status == null || status.isTerminal();
    }

    /**
     * 状态转码，null 安全（只用于拼错误文案）。
     *
     * @param status 状态
     * @return 字符串码；null 返回 {@code "null"}
     */
    private static String codeOf(RechargeStatus status) {
        return Objects.isNull(status) ? "null" : status.getCode();
    }
}
