/*
 * 职责：校验支付密码的入参（`POST /app/account/pay-password/verify`，第 1 段 E2）。
 * 调用方：modules/account/controller/PayPasswordController。
 * 数据来源：App 支付密码页；段 2 支付前预校验。
 * 注意：
 *   1. **不为 verify 单独设"剩余次数"字段**：剩余次数由服务端通过响应头
 *      `X-Pay-Pwd-Remaining` 返回，不放在请求/响应体里（见 E2 的设计取舍）。
 *   2. 明文只在这里出现一次，不 toString。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

/**
 * 校验支付密码请求。
 */
@Data
public class VerifyPayPasswordBO {

    /** 6 位数字支付密码。 */
    @NotBlank(message = "支付密码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "支付密码必须是 6 位数字")
    @ToString.Exclude
    private String password;
}
