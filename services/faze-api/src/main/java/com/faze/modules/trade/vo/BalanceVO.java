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

    /**
     * 充值页参数快照（单笔区间 / 剩余额度 / 档位 / 可用渠道 / 实名门槛）。
     *
     * <p>**只在 `GET /app/wallet/balance` 这个入口填充**，`BalanceService#getBalance` 的其它
     * 调用方（下单、支付、退款等）拿到的是 null —— 那些场景不需要档位与渠道，
     * 多查三次库换不来任何东西。前端读之前先判空。</p>
     */
    private RechargePanelVO recharge;
}
