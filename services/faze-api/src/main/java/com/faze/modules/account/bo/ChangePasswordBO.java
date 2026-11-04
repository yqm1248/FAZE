/*
 * 职责：修改密码接口的入参（`PUT /app/account/password`，已登录）。
 * 调用方：modules/account/controller/AccountSecurityController。
 * 数据来源：客户端提交。
 * 注意：新密码强度在 Service 用 PasswordPolicy 校验，注解只做非空与长度，
 *      避免「注解写了一版规则、Service 又写一版」两处漂移。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求。
 */
@Data
public class ChangePasswordBO {

    /** 旧密码（明文，TLS 内传输）。 */
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    /** 新密码。 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8~64 之间")
    private String newPassword;
}
