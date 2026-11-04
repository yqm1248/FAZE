/*
 * 职责：A-07 管理端充值的入参（userId 在路径，body 不带 userId）。
 * 调用方：modules/trade/admin/controller/AdminWalletOpsController。
 * 数据来源：请求体（PC A-07 余额与充值页）。
 * 注意：
 *   1. **body 里没有 userId**：目标用户来自路径变量，避免「路径改 A、body 改 B」。
 *   2. `bizNo` 必填：线下转账凭证号，没有它这笔钱对账时说不清来源（接口规范 A.5）。
 *   3. `amountCents` 用 `@Min(1)` 只做格式层；业务层的「上限 / 正整数」由
 *      RechargeService 统一校验（复用既有入账链路，不在这里另写一套）。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端充值请求（无 userId）。
 */
@Data
public class AdminRechargeBO {

    /** 充值金额（分），必须 > 0。 */
    @NotNull(message = "金额不能为空")
    @Min(value = 1, message = "金额必须大于 0")
    private Long amountCents;

    /** 充值原因（必填，≤200）。 */
    @NotBlank(message = "充值原因不能为空")
    @Size(max = 200, message = "原因过长")
    private String reason;

    /** 线下转账凭证号（必填，≤64）。 */
    @NotBlank(message = "凭证号不能为空")
    @Size(max = 64, message = "凭证号过长")
    private String bizNo;

    /** 备注（可空，≤500）。 */
    @Size(max = 500, message = "备注过长")
    private String remark;
}
