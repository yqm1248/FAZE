/*
 * 职责：订单状态机——唯一负责「从 A 能不能到 B、哪个角色能触发」。
 * 调用方：trade 域的 OrderService / RefundService / 订单定时任务。
 * 数据来源：《技术路线》§9.2.1 的 16 行迁移表 → 段 2 收敛为 10 条状态对 / 14 个触发分支。
 * 注意：
 *   1. 纯规则类：不注入 Spring、不查库、无状态，可直接静态调用做单测（后端规范 §3.8）。
 *   2. 非法迁移抛 BusinessException(405003)，**不许静默忽略**（工作流 G1.2 第 3 条）。
 *   3. 创建订单是唯一允许 from = null 的迁移（null → PENDING）；其余 null 一律非法。
 *   4. **G4 补了 3 条边**（2026-11-06，原 10 条 → 13 条）：
 *      - `REFUND_REJECTED → PAID`（SYSTEM）：驳回后回退到「已付款待接单」。原表只给了回退到
 *        IN_SERVICE / COMPLETED，但 G4.1 允许 **PAID 状态也能申请退款**，驳回后必须能回到 PAID，
 *        否则订单卡死在 REFUND_REJECTED（G1.2 记录的已知缺口，G4 必须补）。
 *      - `REFUNDING → PAID` / `REFUNDING → IN_SERVICE`（ADMIN、SYSTEM）：**部分退款审批通过**
 *        后订单回到退款前状态（G4.3 裁决：部分退不代表订单作废）。不能走「先 REFUNDED 再回退」，
 *        因为 REFUNDED 是终态、没有出边。
 */
package com.faze.modules.trade.rule;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机。只回答「能不能迁」，不落库、不发通知、不读配置。
 *
 * <p>迁移表是服务端唯一依据；客户端只发意图（{@code POST /orders/{id}/pay}），
 * 从不发目标状态（段 2 §零.4 第 4 条）。</p>
 */
public final class OrderStatusMachine {

    private OrderStatusMachine() {
        throw new AssertionError("纯规则类，不要实例化");
    }

    /**
     * 13 条状态对 → 该迁移允许的触发方。
     *
     * <p>14 个触发分支的对应关系（同一状态对合并成一条，按角色区分权限）：
     * <pre>
     * PENDING → PAID           用户支付 / 支付回调(SYSTEM)
     * PENDING → CANCELLED      用户取消(USER) / 15 分钟超时(SYSTEM)
     * PAID    → IN_SERVICE     律师接单(LAWYER)
     * PAID    → REFUNDING      律师拒单(LAWYER) / 30 分钟无人接(SYSTEM) / 用户取消(USER)
     * IN_SERVICE → COMPLETED   律师完成服务(LAWYER)
     * IN_SERVICE → REFUNDING   用户申请退款(USER) / 律师放弃(LAWYER)
     * REFUNDING → REFUNDED     后台审核通过(ADMIN) / 自动全额退(SYSTEM)
     * REFUNDING → REFUND_REJECTED 后台驳回(ADMIN)
     * REFUNDING → PAID         部分退款通过后回退(ADMIN) / 系统回退(SYSTEM)
     * REFUNDING → IN_SERVICE   部分退款通过后回退(ADMIN) / 系统回退(SYSTEM)
     * REFUND_REJECTED → IN_SERVICE  驳回后回退(SYSTEM)
     * REFUND_REJECTED → COMPLETED   驳回后回退(SYSTEM)
     * REFUND_REJECTED → PAID        驳回后回退(SYSTEM)
     * </pre>
     */
    private static final Map<OrderStatus, Map<OrderStatus, Set<OperatorRole>>> TABLE = buildTable();

    private static Map<OrderStatus, Map<OrderStatus, Set<OperatorRole>>> buildTable() {
        Map<OrderStatus, Map<OrderStatus, Set<OperatorRole>>> table = new EnumMap<>(OrderStatus.class);

        table.put(OrderStatus.PENDING, Map.of(
                OrderStatus.PAID, Set.of(OperatorRole.USER, OperatorRole.SYSTEM),
                OrderStatus.CANCELLED, Set.of(OperatorRole.USER, OperatorRole.SYSTEM)));

        table.put(OrderStatus.PAID, Map.of(
                OrderStatus.IN_SERVICE, Set.of(OperatorRole.LAWYER),
                OrderStatus.REFUNDING, Set.of(OperatorRole.LAWYER, OperatorRole.SYSTEM, OperatorRole.USER)));

        table.put(OrderStatus.IN_SERVICE, Map.of(
                OrderStatus.COMPLETED, Set.of(OperatorRole.LAWYER),
                OrderStatus.REFUNDING, Set.of(OperatorRole.USER, OperatorRole.LAWYER)));

        table.put(OrderStatus.REFUNDING, Map.of(
                OrderStatus.REFUNDED, Set.of(OperatorRole.ADMIN, OperatorRole.SYSTEM),
                OrderStatus.REFUND_REJECTED, Set.of(OperatorRole.ADMIN),
                // 部分退款：钱退了但订单继续，回退到退款前状态（G4.3 裁决）
                OrderStatus.PAID, Set.of(OperatorRole.ADMIN, OperatorRole.SYSTEM),
                OrderStatus.IN_SERVICE, Set.of(OperatorRole.ADMIN, OperatorRole.SYSTEM)));

        table.put(OrderStatus.REFUND_REJECTED, Map.of(
                OrderStatus.IN_SERVICE, Set.of(OperatorRole.SYSTEM),
                OrderStatus.COMPLETED, Set.of(OperatorRole.SYSTEM),
                // PAID 阶段发起的退款被驳回：必须能回到 PAID（G4 补）
                OrderStatus.PAID, Set.of(OperatorRole.SYSTEM)));

        return Collections.unmodifiableMap(table);
    }

    /**
     * 判断迁移是否合法。
     *
     * @param from 迁移前状态；创建订单时为 null
     * @param to   目标状态
     * @param role 触发方（服务端判定，不从请求体取）
     * @return 允许返回 true
     */
    public static boolean canTransit(OrderStatus from, OrderStatus to, OperatorRole role) {
        if (to == null || role == null) {
            return false;
        }
        // 创建订单：只允许 null → PENDING，且只有用户本人能创建
        if (from == null) {
            return to == OrderStatus.PENDING && role == OperatorRole.USER;
        }
        Map<OrderStatus, Set<OperatorRole>> edges = TABLE.get(from);
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
     * 副作用：无（不改状态、不落日志——落 order_status_log 是调用方的事）。</p>
     *
     * @param from 迁移前状态；创建订单时为 null
     * @param to   目标状态
     * @param role 触发方
     * @throws BusinessException 迁移非法时抛 405003，detail 里带三元组供日志定位
     */
    public static void assertCanTransit(OrderStatus from, OrderStatus to, OperatorRole role) {
        if (!canTransit(from, to, role)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID,
                    "非法状态迁移 from=" + from + " to=" + to + " role=" + role);
        }
    }

    /**
     * 某个状态的合法下一跳（只给文档与测试用，业务代码不要据此绕过角色校验）。
     *
     * @param from 迁移前状态
     * @return 可迁移到的状态集合；终态或未知状态返回空集
     */
    public static Set<OrderStatus> targetsOf(OrderStatus from) {
        Map<OrderStatus, Set<OperatorRole>> edges = TABLE.get(from);
        return edges == null ? Set.of() : Collections.unmodifiableSet(edges.keySet());
    }

    /**
     * 合法状态对的数量（10）。
     *
     * @return 状态对个数，供单测与文档自检
     */
    public static int legalPairCount() {
        return TABLE.values().stream().mapToInt(Map::size).sum();
    }
}
