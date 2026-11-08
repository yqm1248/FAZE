/*
 * 职责：A-05 律师入驻审核的入参。
 * 调用方：modules/account/admin/controller/AdminLawyerApplicationController。
 * 数据来源：请求体。
 * 注意：
 *   1. `action` 只接受 approve / reject；reject 必须有 `reason`（Service 里动态校验）。
 *   2. 通过时**不读请求体里的任何表单字段**：内容一律取自已提交的申请记录，
 *      防止管理员在审核时顺手改掉律师资料而不留痕（表单改动应回到 App 端重提）。
 */
package com.faze.modules.account.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 律师入驻审核请求。
 */
@Data
public class AdminLawyerApplicationAuditBO {

    /** 审核动作：approve / reject。 */
    @NotBlank(message = "审核动作不能为空")
    @Pattern(regexp = "approve|reject", message = "审核动作只能是 approve 或 reject")
    private String action;

    /** 驳回理由（reject 时必填，≤200）。 */
    @Size(max = 200, message = "驳回理由过长")
    private String reason;
}
