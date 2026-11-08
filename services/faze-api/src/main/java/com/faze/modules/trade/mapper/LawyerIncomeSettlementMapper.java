/*
 * 职责：lawyer_income_settlement 的数据访问（结算批次的增查改状态）。
 * 调用方：modules/trade 的 SettlementService。
 * 数据来源：faze.lawyer_income_settlement（V506）。
 * 注意：
 *   1. **只做本表的读写**，不碰 balance_flow / lawyer_balance——结算不碰钱，
 *      这一约束靠「本 Mapper 根本没有那两张表的注入」来保证（看代码就知道）。
 *   2. 唯一键 `uk_settlement_lawyer_period(lawyer_id, period)` 是幂等的最后防线：
 *      并发重复生成时靠 DuplicateKeyException 捕获后回查已有单。
 *   3. 无软删：状态机表，删行等于抹掉对账证据。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.LawyerIncomeSettlement;

/**
 * 律师结算批次 Mapper。
 */
public interface LawyerIncomeSettlementMapper extends BaseMapper<LawyerIncomeSettlement> {
}
