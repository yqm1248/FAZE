/*
 * 职责：调账入参（`POST /admin/wallet/adjust`，第 1 段 D4）。
 * 调用方：modules/trade/controller/AdminWalletController。
 * 数据来源：PC A-07（调账允许负向扣减）。
 * 注意：
 *   1. `deltaCents` **可正可负**，但不能为 0——0 的调账只会产生一条无意义流水。
 *   2. 负向调账（扣减）同样必须写原因；调账是「纠正错误」的动作，
 *      不是「随便改余额」的后门，没有原因的调账等于销毁证据。
 *   3. 调账不复用扣费引擎的余额不足校验？**要复用**：扣减时余额不足必须拒绝，
 *      否则调账能把余额调成负数。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手工调账请求。
 */
@Data
public class AdjustBO {

    /** 目标用户主键。 */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /** 变动金额（分），正入账、负出账，不能为 0。 */
    @NotNull(message = "变动金额不能为空")
    private Long deltaCents;

    /** 调账原因（必填）。 */
    @NotBlank(message = "调账原因不能为空")
    @Size(max = 200, message = "原因过长")
    private String reason;

    /** 备注（可空）。 */
    @Size(max = 500, message = "备注过长")
    private String remark;
}
