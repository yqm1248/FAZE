/*
 * 职责：律师收入只读查询实现（段 5 D3）。
 * 调用方：modules/workbench/service/WorkbenchServiceImpl。
 * 数据来源：lawyer_balance / balance_flow / orders。
 * 注意：
 *   1. 时间过滤走 `created_at >= ?`，`from == null` 表示全部（不用 1970 这种魔法值）。
 *   2. 聚合用 `QueryWrapper.select("COALESCE(SUM(delta_cents),0)")` + `selectObjs`：
 *      字段名是写死的常量、没有拼接用户输入，因此不违反「XML 禁 `${}`」的原意。
 *      （规范更偏好聚合写 XML；这里为不扩散改动面选择 Wrapper，已在 D 组偏差里登记。）
 *   3. 明细用 `Page` 而非 `last("LIMIT n")`：LIMIT 数值来自参数，走分页插件更稳。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.LawyerBalanceMapper;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.service.LawyerIncomeQueryService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 律师收入只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class LawyerIncomeQueryServiceImpl implements LawyerIncomeQueryService {

    /** 流水归属类型：律师。与 `BalanceService.OWNER_LAWYER` 同值（跨类引用会带出写服务依赖）。 */
    private static final String OWNER_LAWYER = "lawyer";

    /** 收入流水业务类型。 */
    private static final String BIZ_INCOME = "income";

    /** 提现流水业务类型。 */
    private static final String BIZ_WITHDRAW = "withdraw";

    /** 订单未付款的两个状态（其余都算「已付款」）。 */
    private static final Set<String> UNPAID_ORDER_STATUS = Set.of("pending", "cancelled");

    private final LawyerBalanceMapper lawyerBalanceMapper;
    private final BalanceFlowMapper balanceFlowMapper;
    private final OrdersMapper ordersMapper;

    @Override
    public Optional<LawyerBalance> balanceOf(Long lawyerId) {
        if (lawyerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lawyerBalanceMapper.selectOne(
                Wrappers.<LawyerBalance>lambdaQuery().eq(LawyerBalance::getLawyerId, lawyerId)));
    }

    @Override
    public long incomeSumCents(Long lawyerId, LocalDateTime from) {
        return sumCents(lawyerId, BIZ_INCOME, from, false);
    }

    @Override
    public long withdrawSumCents(Long lawyerId, LocalDateTime from) {
        // 提现流水是负数（出账），展示要正数，所以取绝对值
        return sumCents(lawyerId, BIZ_WITHDRAW, from, true);
    }

    @Override
    public int incomeOrderCount(Long lawyerId, LocalDateTime from) {
        if (lawyerId == null) {
            return 0;
        }
        QueryWrapper<BalanceFlow> wrapper = new QueryWrapper<BalanceFlow>()
                .eq("owner_type", OWNER_LAWYER)
                .eq("owner_id", lawyerId)
                .eq("biz_type", BIZ_INCOME);
        if (from != null) {
            wrapper.ge("created_at", from);
        }
        Long count = balanceFlowMapper.selectCount(wrapper);
        return count == null ? 0 : Math.toIntExact(count);
    }

    @Override
    public List<BalanceFlow> incomeFlows(Long lawyerId, LocalDateTime from, Long beforeId, int limit) {
        if (lawyerId == null || limit < 1) {
            return List.of();
        }
        var wrapper = Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, OWNER_LAWYER)
                .eq(BalanceFlow::getOwnerId, lawyerId)
                .orderByDesc(BalanceFlow::getId);
        if (from != null) {
            wrapper.ge(BalanceFlow::getCreatedAt, from);
        }
        if (beforeId != null) {
            wrapper.lt(BalanceFlow::getId, beforeId);
        }
        Page<BalanceFlow> page = balanceFlowMapper.selectPage(new Page<>(1, limit), wrapper);
        return page.getRecords();
    }

    @Override
    public Map<Long, Orders> ordersByIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = orderIds.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ordersMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Orders::getId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    @Override
    public Set<Long> paidUserIds(Long lawyerId, Collection<Long> userIds) {
        if (lawyerId == null || userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = userIds.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Orders> rows = ordersMapper.selectList(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getLawyerId, lawyerId)
                .in(Orders::getUserId, ids)
                .isNull(Orders::getDeletedAt));
        Set<Long> paid = new HashSet<>();
        for (Orders order : rows) {
            if (order.getStatus() != null && !UNPAID_ORDER_STATUS.contains(order.getStatus())) {
                paid.add(order.getUserId());
            }
        }
        return paid;
    }

    /**
     * 汇总某业务类型下的流水金额。
     *
     * @param lawyerId 律师主键
     * @param bizType  业务类型
     * @param from     起始时间（null 表示全部）
     * @param absolute 是否取绝对值（提现行是负数，展示为正）
     * @return 合计（分）
     */
    private long sumCents(Long lawyerId, String bizType, LocalDateTime from, boolean absolute) {
        if (lawyerId == null) {
            return 0L;
        }
        QueryWrapper<BalanceFlow> wrapper = new QueryWrapper<BalanceFlow>()
                .select("COALESCE(SUM(delta_cents), 0) AS total")
                .eq("owner_type", OWNER_LAWYER)
                .eq("owner_id", lawyerId)
                .eq("biz_type", bizType);
        if (from != null) {
            wrapper.ge("created_at", from);
        }
        List<Object> values = balanceFlowMapper.selectObjs(wrapper);
        if (values.isEmpty() || values.get(0) == null) {
            return 0L;
        }
        Object raw = values.get(0);
        long value = raw instanceof Number number ? number.longValue() : Long.parseLong(raw.toString());
        return absolute ? Math.abs(value) : value;
    }
}
