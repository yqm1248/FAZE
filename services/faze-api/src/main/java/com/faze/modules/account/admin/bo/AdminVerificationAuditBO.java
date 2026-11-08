/*
 * 职责：A-04 认证审核的入参。
 * 调用方：modules/account/admin/controller/AdminVerificationController。
 * 数据来源：请求体。
 * 注意：
 *   1. `type` 必填：**两张表 id 会重复**，只有 id + type 才能唯一定位一条认证记录。
 *      缺 type 时按 401001 拒绝，绝不猜「先查实名表再查企业表」。
 *   2. `action` 只接受 approve / reject；其它值 401001。
 *   3. `reason` 在 reject 时必须非空——这个动态校验在 Service 里做（注解只能表达静态必填）。
 */
package com.faze.modules.account.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 认证审核请求。
 */
@Data
public class AdminVerificationAuditBO {

    /** 认证类型：realname / enterprise（必填）。 */
    @NotBlank(message = "认证类型不能为空")
    @Pattern(regexp = "realname|enterprise", message = "认证类型只能是 realname 或 enterprise")
    private String type;

    /** 审核动作：approve / reject。 */
    @NotBlank(message = "审核动作不能为空")
    @Pattern(regexp = "approve|reject", message = "审核动作只能是 approve 或 reject")
    private String action;

    /** 驳回理由（reject 时必填，≤200）。 */
    @Size(max = 200, message = "驳回理由过长")
    private String reason;
}
