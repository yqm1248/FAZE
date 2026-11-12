/*
 * 职责：BalanceQueryService 的实现。
 * 调用方：Spring 依赖注入（modules/account 的资料与注销前置校验）。
 * 数据来源：faze.user_balance（缓存口径）+ faze.balance_flow（汇总口径）。
 * 注意：
 *   1. `getAvailableCents` / `getFrozenCents` 读缓存字段。**决策点不能只看它**：
 *      注销前置校验（AccountSecurityService.cancelAccount）会把它与流水汇总取较大值，
 *      因为缓存是派生值，偏小时会放行「带余额注销」。
 *   2. `getTotalCentsFromFlows` 走 SUM(delta_cents)，给展示、对账与注销校验用（锚点 1-A6）。
 *      两个口径**故意分开**，不要互相替代。
 *   3. 查不到记录返回 0（新用户没有余额行是正常状态，不是异常）。
 *   4. 不做任何写操作。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.BalanceQueryService;
import com.faze.modules.trade.service.BalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 余额只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class BalanceQueryServiceImpl implements BalanceQueryService {

    private final UserBalanceMapper userBalanceMapper;
    private final BalanceFlowMapper balanceFlowMapper;

    /**
     * 取可用余额。
     *
     * @param userId 用户主键
     * @return 可用余额（分），无记录为 0
     */
    @Override
    public long getAvailableCents(Long userId) {
        UserBalance row = findByUserId(userId);
        return row == null || row.getAvailableCents() == null ? 0L : row.getAvailableCents();
    }

    /**
     * 取冻结余额。
     *
     * @param userId 用户主键
     * @return 冻结余额（分），无记录为 0
     */
    @Override
    public long getFrozenCents(Long userId) {
        UserBalance row = findByUserId(userId);
        return row == null || row.getFrozenCents() == null ? 0L : row.getFrozenCents();
    }

    /**
     * 按流水汇总取总余额。
     *
     * @param userId 用户主键
     * @return 总余额（分），无流水为 0
     */
    @Override
    public long getTotalCentsFromFlows(Long userId) {
        if (userId == null) {
            return 0L;
        }
        Long sum = balanceFlowMapper.sumDeltaByOwner(BalanceService.OWNER_USER, userId);
        return sum == null ? 0L : sum;
    }

    private UserBalance findByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        return userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, userId));
    }
}
