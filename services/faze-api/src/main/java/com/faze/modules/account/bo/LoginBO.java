/*
 * 职责：登录接口的请求体（`POST /api/v1/pub/auth/login/password`）。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：客户端提交（《接口规范》附录 A.1.1 字段级冻结）。
 * 注意：
 *   1. 校验注解的提示文案会进响应的 msg（供开发者看），**客户端展示仍走 msgKey**。
 *   2. `deviceId` 必填且必须与请求头 `X-Device-Id` 一致（不一致记风控，见 AuthService）。
 *      段 0 没有风控表，只记日志；段 1 起接风控。
 *   3. `smsCode` 段 0 不实现（短信通道未定，属段 1），此处不设字段——宁可少字段也不要
 *      留一个「收了但不用」的参数，那会让联调方以为风控已生效。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手机号 + 密码登录请求。
 */
@Data
public class LoginBO {

    /** 中国大陆手机号。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 明文密码（TLS 内传输），服务端用 BCrypt 校验。 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
    private String password;

    /** 设备标识，必须与请求头 X-Device-Id 一致。 */
    @NotBlank(message = "设备标识不能为空")
    @Size(max = 64, message = "设备标识过长")
    private String deviceId;
}
