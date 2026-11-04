/*
 * 职责：资金流水列表项出参（`GET /app/wallet/flows`，第 1 段 D2）。
 * 调用方：modules/trade/controller/WalletController。
 * 数据来源：balance_flow。
 * 注意：
 *   1. 金额一律整数分（`deltaCents` 正入账负出账），客户端负责分转元。
 *   2. `balanceAfterCents` 是**变动后总余额**快照（不是可用余额），
 *      与 D 组第 4 条口径一致——段 5 结算要按这个口径对账。
 *   3. `bizId` **不返回**：它是内部业务主键，客户端用不上；
 *      而 `bizType` 足够区分流水类型。少返回一个内部 ID，就少一处泄漏面。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 资金流水项。
 */
@Data
public class FlowVO {

    /** 流水主键（序列化为字符串）。 */
    private Long id;

    /** 业务类型：recharge / consume / refund / income / withdraw / freeze / unfreeze / adjust。 */
    private String bizType;

    /** 变动金额（分）：正入账、负出账、0 为转移记录。 */
    private Long deltaCents;

    /** 变动后总余额（分）。 */
    private Long balanceAfterCents;

    /** 备注。 */
    private String remark;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
