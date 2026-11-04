/*
 * 职责：钱包余额出参（`GET /app/wallet/balance`）。
 * 调用方：modules/trade/controller/WalletController。
 * 数据来源：**流水汇总**（不是 user_balance 缓存字段）。
 * 注意：
 *   1. 三个字段的关系：`totalCents == availableCents + frozenCents`。
 *   2. 金额一律整数分（Long），客户端负责分转元展示；**服务端不返回元**。
 *   3. 段 1 没有冻结场景，`frozenCents` 恒为 0；段 2 订单占用后才会有值。
 */
package com.faze.modules.trade.vo;

import lombok.Data;

/**
 * 钱包余额。
 */
@Data
public class BalanceVO {

    /** 可用余额（分）= 总余额 − 冻结。 */
    private Long availableCents;

    /** 冻结金额（分）。 */
    private Long frozenCents;

    /** 总余额（分）= SUM(balance_flow.delta_cents)。 */
    private Long totalCents;
}
