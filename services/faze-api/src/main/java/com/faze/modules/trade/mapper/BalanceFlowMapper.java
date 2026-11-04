/*
 * 职责：balance_flow 的数据访问（只插入 + 按 owner 查询/聚合）。
 * 调用方：modules/trade（扣费引擎、充值、调账、对账）。
 * 数据来源：faze.balance_flow。
 * 注意：
 *   1. **不提供 update / delete**：这张表是只追加的证据链，改一条流水等于改账。
 *   2. 「余额 = SUM(delta_cents)」的聚合**放在 Service 里用 QueryWrapper 拼**也可以，
 *      但 D1 的锚点要求能一眼看出「用的是流水汇总」，所以这里给一个显式方法，
 *      调用点读起来不会误以为是读缓存。
 *   3. 幂等查询用 `(owner_type, owner_id, biz_type, biz_id)` 走 uk_flow_biz。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.BalanceFlow;

/**
 * 资金流水 Mapper。
 */
public interface BalanceFlowMapper extends BaseMapper<BalanceFlow> {
}
