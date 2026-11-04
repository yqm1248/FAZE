/*
 * 职责：lawyer_balance 的数据访问。
 * 调用方：modules/trade（D 组余额服务）。
 * 数据来源：faze.lawyer_balance。
 * 注意：本段只提供 CRUD 级能力；扣减逻辑集中在余额服务里，
 *      不要在 Mapper 里写业务判断。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.LawyerBalance;

/**
 * 律师余额 Mapper。
 */
public interface LawyerBalanceMapper extends BaseMapper<LawyerBalance> {
}
