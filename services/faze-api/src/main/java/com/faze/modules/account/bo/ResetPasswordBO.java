/*
 * 职责：忘记密码接口的入参（`POST /pub/auth/reset-password`，未登录）。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. 未登录场景只能靠「手机号 + 短信验证码」证明身份，所以验证码是必填且消费即失效。
 *   2. 该接口**不返回令牌**：重置后所有旧 token 失效，用户必须重新登录，
 *      这既是安全要求，也让客户端的行为可预期（不会出现「重置完还是登录态」的错觉）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 忘记密码请求。
 */
@Data
public class ResetPasswordBO {

    /** 中国大陆手机号。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 短信验证码。 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;

    /** 新密码。 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8~64 之间")
    private String newPassword;
}
