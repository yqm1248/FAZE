/*
 * 职责：换绑手机接口的入参（`POST /app/account/change-phone`，已登录）。
 * 调用方：modules/account/controller/AccountSecurityController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. **旧号与新号各要一个验证码**：只验新号的话，攻击者拿到一个登录态就能把号换成自己的；
 *      只验旧号的话，换绑后无法证明新号归属。两个都验才是「本人 + 新号可用」。
 *   2. 该接口不在《接口规范》§7.2 的 C 端清单里，是第 1 段 B3 补的第 24 个（清单缺口）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 换绑手机请求。
 */
@Data
public class ChangePhoneBO {

    /** 旧手机号收到的验证码。 */
    @NotBlank(message = "原手机验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String oldSmsCode;

    /** 新手机号。 */
    @NotBlank(message = "新手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "新手机号格式不正确")
    private String newPhone;

    /** 新手机号收到的验证码。 */
    @NotBlank(message = "新手机验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String newSmsCode;
}
