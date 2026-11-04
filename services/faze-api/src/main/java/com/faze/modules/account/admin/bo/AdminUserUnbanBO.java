/*
 * 职责：A-03 解封用户的入参。
 * 调用方：modules/account/admin/controller/AdminUserController（POST /users/{id}/unban）。
 * 数据来源：请求体。
 * 注意：`reason` 必填——解封同样是影响用户权利的操作，必须能回答「谁批准的、为什么」。
 */
package com.faze.modules.account.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 解封用户请求。
 */
@Data
public class AdminUserUnbanBO {

    /** 解封理由（必填，≤200）。 */
    @NotBlank(message = "解封理由不能为空")
    @Size(max = 200, message = "解封理由过长")
    private String reason;
}
