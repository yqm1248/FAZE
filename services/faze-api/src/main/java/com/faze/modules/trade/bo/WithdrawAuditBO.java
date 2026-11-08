/*
 * 职责：PC 提现审核弹窗的请求体（`POST /admin/withdrawals/{id}/audit`）。
 * 调用方：modules/trade/admin/controller/AdminWithdrawController → WithdrawService#audit。
 * 数据来源：A-10 的「通过 / 驳回」两个弹窗。
 * 注意：
 *   1. `approve` 时 `voucherFileId` **必填**（工作流 F2 第 5 条）：没有凭证的通过等于无凭据出款。
 *   2. `reject` 时 `reason` **必填**（≤500 字，与 `withdraw_record.reject_reason` 列宽一致）：
 *      律师要能看到为什么被驳回。
 *   3. `transactionId` 选填（渠道出款流水号，人工填网银流水）。
 *   4. 状态校验（只能对 `applying` 单审核）在 Service 的 CAS 里做，不在 BO 上。
 */
package com.faze.modules.trade.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提现审核入参。
 */
@Data
public class WithdrawAuditBO {

    /** 审核动作：`approve` 通过 / `reject` 驳回。 */
    @NotBlank(message = "审核动作不能为空")
    private String action;

    /** 驳回理由（reject 时必填，≤500 字）。 */
    @Size(max = 500, message = "驳回理由不能超过 500 字")
    private String reason;

    /** 出款凭证 file_record.id（approve 时必填）。 */
    private Long voucherFileId;

    /** 渠道出款流水号（选填）。 */
    @Size(max = 64, message = "出款流水号不能超过 64 字")
    private String transactionId;
}
