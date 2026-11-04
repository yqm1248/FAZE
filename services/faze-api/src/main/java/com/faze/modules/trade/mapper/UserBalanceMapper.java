/*
 * 职责：user_balance 的数据访问。
 * 调用方：modules/trade/service/impl/BalanceQueryServiceImpl。
 * 数据来源：faze.user_balance。
 * 注意：本段只提供读取；扣减与流水在段 1 D 组补，**禁止**在这里提前开放
 *      「直接改余额」的方法（会导致绕过 balance_flow 的写法被顺手使用）。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.UserBalance;

/**
 * 用户余额 Mapper。
 */
public interface UserBalanceMapper extends BaseMapper<UserBalance> {
}
