/*
 * 职责：重置支付密码的入参（`POST /app/account/pay-password/reset`，第 1 段 E1）。
 * 调用方：modules/account/controller/PayPasswordController。
 * 数据来源：App 支付密码页。
 * 注意：
 *   1. 重置走**短信验证码**而不是登录密码（E1 第 4 条）：前提是「用户忘了支付密码但能收短信」。
 *      用登录密码重置等于把两个凭证的独立性抹掉——登录密码一旦泄漏，支付密码跟着失守。
 *   2. 重置成功后**清掉锁定计数**（E2 验收第 5 条）：用户已经证明自己是本人，
 *      继续锁着只会让他连新密码都试不了。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

/**
 * 重置支付密码请求。
 */
@Data
public class ResetPayPasswordBO {

    /** 短信验证码。 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;

    /** 新支付密码（6 位数字）。 */
    @NotBlank(message = "新支付密码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "支付密码必须是 6 位数字")
    @ToString.Exclude
    private String newPassword;
}
