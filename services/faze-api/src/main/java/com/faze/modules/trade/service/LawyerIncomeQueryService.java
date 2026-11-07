/*
 * 职责：trade 域面向律师收入/工作台的**只读**查询出口（段 5 D3）。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl。
 * 数据来源：lawyer_balance / balance_flow / orders。
 * 注意：
 *   1. **金额口径以 balance_flow 为准**，`lawyer_balance` 只是缓存（§八.1 第 1 条）：
 *      两者不一致时按流水汇总，本接口把两者都给出去，让调用方决定用哪个。
 *   2. **只读**：入账、解冻、提现扣减都在 trade 域的写服务里，这里不开口子。
 *   3. `bizType` 的取值见 `balance_flow.biz_type`：income / withdraw / freeze / unfreeze / adjust。
 */
package com.faze.modules.trade.service;

import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.Orders;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 律师收入只读查询。
 */
public interface LawyerIncomeQueryService {

    /**
     * 取律师余额缓存行。
     *
     * @param lawyerId 律师主键
     * @return 余额行；尚未建行时为空
     */
    Optional<LawyerBalance> balanceOf(Long lawyerId);

    /**
     * 汇总某时间点之后的律师收入（`biz_type = income` 的 `delta_cents` 之和）。
     *
     * @param lawyerId 律师主键
     * @param from     起始时间（null 表示全部）
     * @return 收入合计（分），可能为负的调整也计入
     */
    long incomeSumCents(Long lawyerId, LocalDateTime from);

    /**
     * 汇总某时间点之后的提现（`biz_type = withdraw` 的绝对值之和）。
     *
     * @param lawyerId 律师主键
     * @param from     起始时间（null 表示全部）
     * @return 提现合计（分，正数）
     */
    long withdrawSumCents(Long lawyerId, LocalDateTime from);

    /**
     * 统计某时间点之后产生收入的订单数。
     *
     * @param lawyerId 律师主键
     * @param from     起始时间（null 表示全部）
     * @return 订单数
     */
    int incomeOrderCount(Long lawyerId, LocalDateTime from);

    /**
     * 取律师的流水明细（按 id 倒序，游标分页）。
     *
     * @param lawyerId 律师主键
     * @param from     起始时间（null 表示全部）
     * @param beforeId 游标：只取 id 小于它的记录（null 表示第一页）
     * @param limit    条数（含一条用于判断 hasMore，调用方传 limit+1）
     * @return 流水列表
     */
    List<BalanceFlow> incomeFlows(Long lawyerId, LocalDateTime from, Long beforeId, int limit);

    /**
     * 按 id 批量取订单（流水补订单号用）。
     *
     * @param orderIds 订单 id 集合
     * @return id → 订单
     */
    Map<Long, Orders> ordersByIds(Collection<Long> orderIds);

    /**
     * 在给定用户里筛出「与该律师有过已付款订单」的用户（D1 的订单状态标签）。
     *
     * <p>口径：「已付款」= 状态不是 `pending` 也不是 `cancelled`（paid / in_service / completed 等）。
     * 没有订单的用户不出现在结果里，前端显示「未付款」。</p>
     *
     * @param lawyerId 律师主键
     * @param userIds  待判断的用户集合
     * @return 有已付款订单的用户 id 集合
     */
    Set<Long> paidUserIds(Long lawyerId, Collection<Long> userIds);
}
