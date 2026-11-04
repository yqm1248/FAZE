/*
 * 职责：balance_flow 按归属方聚合的一行结果（对账用，A-09 的 mapper 返回类型）。
 * 调用方：modules/trade/mapper/BalanceFlowMapper#selectOwnerSums、
 *         modules/trade/admin/service/AdminReconciliationService。
 * 数据来源：faze.balance_flow 的 GROUP BY owner_type, owner_id。
 * 注意：
 *   1. 这是**联表/聚合结果**而不是表实体（规范 §3.5 的 View 类）：不映射整行、
 *      不参与插入更新，只承载对账需要的两个维度 + 一个汇总值。
 *   2. `sumCents` 是净额（分），可能为负——负余额是数据问题，要原样报出来，
 *      不能在这里纠正。
 */
package com.faze.modules.trade.mapper;

import lombok.Data;

/**
 * 归属方流水汇总。
 */
@Data
public class OwnerFlowSum {

    /** 归属类型：user / lawyer。 */
    private String ownerType;

    /** 归属主键（user_id 或 lawyer_id）。 */
    private Long ownerId;

    /** 流水净额（分）。 */
    private Long sumCents;
}
