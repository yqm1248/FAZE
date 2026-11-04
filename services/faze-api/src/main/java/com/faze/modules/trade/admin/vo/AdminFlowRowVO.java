/*
 * 职责：A-07 资金流水分页的行出参。
 * 调用方：modules/trade/admin/service/AdminWalletService、AdminWalletLedgerController。
 * 数据来源：faze.balance_flow。
 * 注意：
 *   1. `userId` 是行内归属方，方便前端在「按用户查」的页面里做自检。
 *   2. `deltaCents` / `balanceAfterCents` 是**原生 long**（金额必须是 JSON 数字）。
 *   3. 不返回 `bizId`（内部幂等键，对运营无意义）与 `frozenUntil`（段 1 不用冻结）。
 */
package com.faze.modules.trade.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 资金流水行。
 */
@Data
public class AdminFlowRowVO {

    /** 流水主键。 */
    private Long id;

    /** 归属用户主键。 */
    private Long userId;

    /** 业务类型：recharge / consume / refund / adjust / … */
    private String bizType;

    /** 变动金额（分，正入账 / 负出账）。 */
    private long deltaCents;

    /** 变动后总余额（分）。 */
    private long balanceAfterCents;

    /** 备注。 */
    private String remark;

    /** 发生时间（UTC）。 */
    private LocalDateTime createdAt;
}
