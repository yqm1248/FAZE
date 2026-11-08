/*
 * 职责：律师提现申请请求体（`POST /app/withdrawals`）。
 * 调用方：modules/trade/controller/WithdrawController → WithdrawService#apply。
 * 数据来源：App 66-income 的提现面板。
 * 注意：
 *   1. **金额只用分**（BIGINT），禁止浮点（AGENTS.md §4.5）。
 *   2. 最低额、可提现余额、绑卡状态、单日笔数**四条校验全部在服务端重做**
 *      （前端校验只是体验，见工作流 F1 第 2 条）。
 *   3. 幂等键在**请求头** `Idempotency-Key`，不在 body 里——body 是业务意图，头是传输层语义。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 提现申请入参。
 */
@Data
public class WithdrawApplyBO {

    /** 申请金额（分），必须为正。 */
    @NotNull(message = "提现金额不能为空")
    @Positive(message = "提现金额必须大于 0")
    private Long amountCents;
}
