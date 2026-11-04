/*
 * 职责：balance_flow 的数据访问（只插入 + 按 owner 查询/聚合）。
 * 调用方：modules/trade（扣费引擎、充值、调账、对账）。
 * 数据来源：faze.balance_flow。
 * 注意：
 *   1. **不提供 update / delete**：这张表是只追加的证据链，改一条流水等于改账。
 *   2. 「余额 = SUM(delta_cents)」走 XML（规范 §8.1：聚合用 XML，不用 Wrapper 拼）。
 *      调用点读 `sumDeltaByOwner` 时能一眼看出是「流水汇总」而不是读缓存。
 *   3. 幂等查询用 `(owner_type, owner_id, biz_type, biz_id)` 走 uk_flow_biz。
 */
package com.faze.modules.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.faze.modules.trade.entity.BalanceFlow;
import org.apache.ibatis.annotations.Param;

/**
 * 资金流水 Mapper。
 */
public interface BalanceFlowMapper extends BaseMapper<BalanceFlow> {

    /**
     * 按归属方汇总流水净额（分）。
     *
     * <p>**这是「余额」的定义**：返回 0 表示没有流水（新用户），不是异常。</p>
     *
     * @param ownerType user / lawyer
     * @param ownerId   归属主键
     * @return 净额（分），无流水时为 0
     */
    Long sumDeltaByOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);
}
