/*
 * 职责：结算批次聚合结果（E4 从 orders 侧按「律师 × 账期」聚集的四个数）。
 * 调用方：com.faze.modules.trade.mapper.OrdersMapper#selectSettlementAgg。
 * 数据来源：faze.orders（订单金额与服务费在段 2 已算好，本类只做 SUM）。
 * 注意：
 *   1. 字段全是包装类型：`SUM(...)` 在无匹配行时返回 NULL，Java 侧必须能表达「没有」，
 *      用基本类型会被 MyBatis 塞成 0 而看不出「一行都没有」。
 *   2. **订单数按 `finished_at` 归账期**，不是 `created_at`：一个 10 月下单、11 月完成的单
 *      属于 11 月账（钱在 11 月才成为律师收入）。
 *   3. `settleCents` **不在这个类里**：应结金额来自 `balance_flow` 的 income 净额，
 *      由 SettlementService 另查一条 SQL 得到，两者必须交叉校验（E4 第 3 条）。
 */
package com.faze.modules.trade.mapper;

import lombok.Data;

/**
 * 结算批次的订单侧聚合。
 */
@Data
public class SettlementAgg {

    /** 该账期完成订单数。 */
    private Integer orderCount;

    /** 订单金额合计（分），对应 `orders.amount_cents`。 */
    private Long incomeCents;

    /** 平台服务费合计（分），对应 `orders.platform_fee_cents`。 */
    private Long feeCents;
}
