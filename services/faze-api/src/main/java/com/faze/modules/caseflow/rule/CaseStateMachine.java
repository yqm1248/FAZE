/*
 * 职责：案件状态机——唯一负责「从 A 能不能到 B、哪个角色能触发」。
 * 调用方：caseflow 域的 CaseService / 案件审核与进度推进流程。
 * 数据来源：《第 5 段工作流》§三 A2 的 9 行迁移表。
 * 注意：
 *   1. 纯规则类：不注入 Spring、不查库、无状态，可直接静态调用做单测（后端规范 §3.8）。
 *   2. 非法迁移抛 BusinessException(407002)，**不许静默忽略**。
 *   3. 案件创建（null → filed）**不走本状态机**：创建不是迁移，由 CaseService 直接插入，
 *      因此本类不接受 from = null（传 null 一律判 false，防止误用）。
 *   4. 跨域引用 `trade.enums.OperatorRole`：它是 4 个通用触发方（user/lawyer/admin/system），
 *      不属 trade 域特有；**引用枚举不等于跨域注入 Mapper**（后端规范 §2.3 只禁后者）。
 */
package com.faze.modules.caseflow.rule;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.caseflow.enums.CaseStatus;
import com.faze.modules.trade.enums.OperatorRole;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 案件状态机。只回答「能不能迁」，不落库、不发通知、不写 case_progress。
 *
 * <p>迁移表是服务端唯一依据；客户端只发意图（{@code POST /app/cases/{id}/progress} 之类），
 * 从不发目标状态（《第 5 段工作流》§零.3）。</p>
 */
public final class CaseStateMachine {

    private CaseStateMachine() {
        throw new AssertionError("纯规则类，不要实例化");
    }

    /**
     * 6 条合法迁移 → 该迁移允许的触发方。
     *
     * <p>工作流 §三 A2 的 9 行表里另有 3 行是「不允许」（in_progress→rejected / closed→* /
     * rejected→accepted），不建边即自动拒绝，无需在表里写死。</p>
     *
     * <pre>
     * FILED       → ACCEPTED     管理员立案审核通过(ADMIN)
     * FILED       → REJECTED     管理员立案审核驳回(ADMIN)
     * REJECTED    → FILED        用户补正后重新提交(USER)
     * ACCEPTED    → IN_PROGRESS  律师登记法院案号(LAWYER)
     * IN_PROGRESS → CLOSED       律师推进结案(LAWYER)
     * ACCEPTED    → CLOSED       律师特殊情况直接结案(LAWYER)
     * </pre>
     */
    private static final Map<CaseStatus, Map<CaseStatus, Set<OperatorRole>>> TABLE = buildTable();

    private static Map<CaseStatus, Map<CaseStatus, Set<OperatorRole>>> buildTable() {
        Map<CaseStatus, Map<CaseStatus, Set<OperatorRole>>> table = new EnumMap<>(CaseStatus.class);

        table.put(CaseStatus.FILED, Map.of(
                CaseStatus.ACCEPTED, Set.of(OperatorRole.ADMIN),
                CaseStatus.REJECTED, Set.of(OperatorRole.ADMIN)));

        table.put(CaseStatus.REJECTED, Map.of(
                // 驳回后只能「重新提交」，不能直接跳到立案中
                CaseStatus.FILED, Set.of(OperatorRole.USER)));

        table.put(CaseStatus.ACCEPTED, Map.of(
                CaseStatus.IN_PROGRESS, Set.of(OperatorRole.LAWYER),
                CaseStatus.CLOSED, Set.of(OperatorRole.LAWYER)));

        table.put(CaseStatus.IN_PROGRESS, Map.of(
                CaseStatus.CLOSED, Set.of(OperatorRole.LAWYER)));

        // CLOSED 是终态：不放入任何出边，get() 为 null 即拒绝
        return Collections.unmodifiableMap(table);
    }

    /**
     * 判断迁移是否合法。
     *
     * @param from 迁移前状态（不允许为 null）
     * @param to   目标状态
     * @param role 触发方（服务端判定，不从请求体取）
     * @return 允许返回 true
     */
    public static boolean canTransit(CaseStatus from, CaseStatus to, OperatorRole role) {
        if (from == null || to == null || role == null) {
            return false;
        }
        Map<CaseStatus, Set<OperatorRole>> edges = TABLE.get(from);
        if (edges == null) {
            return false;
        }
        Set<OperatorRole> allowed = edges.get(to);
        return allowed != null && allowed.contains(role);
    }

    /**
     * 校验迁移，非法即抛业务异常。
     *
     * <p>前置：from / to / role 由服务端从数据库当前状态与登录身份得出。
     * 副作用：无（不改状态、不写 case_progress——那是调用方与 CAS 更新的事）。</p>
     *
     * @param from 迁移前状态
     * @param to   目标状态
     * @param role 触发方
     * @throws BusinessException 迁移非法时抛 407002，detail 带三元组供日志定位
     */
    public static void assertCanTransit(CaseStatus from, CaseStatus to, OperatorRole role) {
        if (!canTransit(from, to, role)) {
            throw new BusinessException(ErrorCode.CASE_STATE_INVALID,
                    "非法案件状态迁移 from=" + from + " to=" + to + " role=" + role);
        }
    }

    /**
     * 某个状态的合法下一跳（只给文档与测试用，业务代码不要据此绕过角色校验）。
     *
     * @param from 迁移前状态
     * @return 可迁移到的状态集合；终态或未知状态返回空集
     */
    public static Set<CaseStatus> targetsOf(CaseStatus from) {
        Map<CaseStatus, Set<OperatorRole>> edges = TABLE.get(from);
        return edges == null ? Set.of() : Collections.unmodifiableSet(edges.keySet());
    }

    /**
     * 合法状态对的数量（6）。
     *
     * @return 状态对个数，供单测与文档自检
     */
    public static int legalPairCount() {
        return TABLE.values().stream().mapToInt(Map::size).sum();
    }
}
