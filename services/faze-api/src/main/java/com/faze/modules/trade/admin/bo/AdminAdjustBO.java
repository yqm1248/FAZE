/*
 * 职责：A-07 管理端调账的入参（userId 在路径，body 不带 userId）。
 * 调用方：modules/trade/admin/controller/AdminWalletOpsController。
 * 数据来源：请求体。
 * 注意：
 *   1. `deltaCents` 可正可负但不能为 0：0 的调账只会产生一条无意义流水，
 *      「非 0」由 RechargeService 校验（与既有调账链路同一口径）。
 *   2. 负向调账余额不足会被拒绝（不能把余额调成负数），校验在 RechargeService。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端调账请求（无 userId）。
 */
@Data
public class AdminAdjustBO {

    /** 变动金额（分），正入账、负出账，不能为 0。 */
    @NotNull(message = "变动金额不能为空")
    private Long deltaCents;

    /** 调账原因（必填，≤200）。 */
    @NotBlank(message = "调账原因不能为空")
    @Size(max = 200, message = "原因过长")
    private String reason;

    /** 备注（可空，≤500）。 */
    @Size(max = 500, message = "备注过长")
    private String remark;
}
