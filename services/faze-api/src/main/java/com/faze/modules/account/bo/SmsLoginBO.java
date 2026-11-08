/*
 * 职责：短信登录接口的入参（`POST /pub/auth/login/sms`）。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：客户端提交。
 * 注意：
 *   1. **必须已注册**（B2 第 3 条）：短信登录不做「首次登录即注册」，
 *      否则会绕过注册页的协议勾选与未成年人判定。
 *   2. deviceId 必填，与密码登录口径一致（《接口规范》A.1.1）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 短信登录请求。
 */
@Data
public class SmsLoginBO {

    /** 中国大陆手机号。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 短信验证码。 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;

    /** 设备标识，必须与请求头 X-Device-Id 一致。 */
    @NotBlank(message = "设备标识不能为空")
    @Size(max = 64, message = "设备标识过长")
    private String deviceId;
}
