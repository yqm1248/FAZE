/*
 * 职责：BalanceQueryService 的实现。
 * 调用方：Spring 依赖注入。
 * 数据来源：faze.user_balance。
 * 注意：
 *   1. 读缓存字段而不是流水汇总，理由见接口注释第 2 条。
 *   2. 查不到记录返回 0（新用户没有余额行是正常状态，不是异常）。
 *   3. 不做任何写操作。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.service.BalanceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 余额只读查询实现。
 */
@Service
@RequiredArgsConstructor
public class BalanceQueryServiceImpl implements BalanceQueryService {

    private final UserBalanceMapper userBalanceMapper;

    /**
     * 取可用余额。
     *
     * @param userId 用户主键
     * @return 可用余额（分），无记录为 0
     */
    @Override
    public long getAvailableCents(Long userId) {
        UserBalance row = findByUserId(userId);
        return row == null || row.getBalanceCents() == null ? 0L : row.getBalanceCents();
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

    private UserBalance findByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        return userBalanceMapper.selectOne(Wrappers.<UserBalance>lambdaQuery()
                .eq(UserBalance::getUserId, userId));
    }
}
