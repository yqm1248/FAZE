/*
 * 职责：提现状态机（4 态 / 3 条合法边）的合法与非法迁移判定。
 * 调用方：modules/trade/service/WithdrawService 的 approve / reject / markPaid
 *         （写库前先判一次，非法迁移直接 405025）。
 * 数据来源：《第 5 段工作流》§三 F3「状态机单测」、WithdrawStatus 的类注释。
 * 注意：
 *   1. **本类只是内存里的显式校验，不是并发防线**。真正的并发防线仍是 SQL 的 CAS
 *      （`UPDATE ... WHERE status = from`，见 WithdrawRecordMapper#casTransit）。
 *      两者都要有：状态机给出**明确原因**（「不能从 rejected 迁移到 paid」），
 *      CAS 挡住**并发**（两个请求同时从 applying 走，只有一个能成功）。
 *   2. 工作流 F3 原文写「状态机单测（5 条合法 + 4 条非法）」，但 4 态两两组合只有
 *      4×3 = 12 种，合法边只有 3 条（applying→approved / applying→rejected /
 *      approved→paid），非法 9 条——**原文的「5+4」凑不出任何自洽的划分**。
 *      本类按实际状态数实现 3 合法 + 9 非法，并在段 5 偏差清单登记这处文档内部矛盾
 *      （与 CaseStateMachineTest 头部记录工作流「20 组合 vs 6 条合法」是同类问题）。
 *   3. rejected 与 paid 是终态，没有出边；同态迁移（applying→applying）也算非法——
 *      「重复提交审核」必须被拒，不能靠 CAS 静默失败。
 */

package com.faze.modules.trade.rule;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.WithdrawStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 提现状态机。
 *
 * <p>4 个状态：{@code applying} / {@code approved} / {@code rejected} / {@code paid}。</p>
 */
public final class WithdrawStateMachine {

    /**
     * 3 条合法迁移（与 WithdrawStatus 的类注释逐条对应）。
     */
    private static final Set<List<WithdrawStatus>> LEGAL_EDGES = Set.of(
            List.of(WithdrawStatus.APPLYING, WithdrawStatus.APPROVED),
            List.of(WithdrawStatus.APPLYING, WithdrawStatus.REJECTED),
            List.of(WithdrawStatus.APPROVED, WithdrawStatus.PAID));

    private WithdrawStateMachine() {
    }

    /**
     * 判断一次状态迁移是否合法。
     *
     * @param from 原状态（null 视为非法）
     * @param to   目标状态（null 视为非法）
     * @return 合法返回 true；任一为 null、或不在合法边集合内返回 false
     */
    public static boolean canTransit(WithdrawStatus from, WithdrawStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return LEGAL_EDGES.contains(List.of(from, to));
    }

    /**
     * 校验一次状态迁移，非法直接抛 405025。
     *
     * @param from 原状态
     * @param to   目标状态
     * @throws BusinessException 405025 提现状态非法
     */
    public static void check(WithdrawStatus from, WithdrawStatus to) {
        if (!canTransit(from, to)) {
            throw new BusinessException(ErrorCode.TRADE_WITHDRAW_STATE_INVALID,
                    "提现状态不允许从 " + codeOf(from) + " 迁移到 " + codeOf(to));
        }
    }

    /**
     * 是否为终态（rejected / paid 没有出边）。
     *
     * @param status 状态（null 返回 true，视为不可继续）
     * @return 终态返回 true
     */
    public static boolean isTerminal(WithdrawStatus status) {
        return status == null || status.isTerminal();
    }

    /**
     * 状态转码，null 安全（只用于拼错误文案）。
     *
     * @param status 状态
     * @return 字符串码；null 返回 {@code "null"}
     */
    private static String codeOf(WithdrawStatus status) {
        return Objects.isNull(status) ? "null" : status.getCode();
    }
}
