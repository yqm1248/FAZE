/*
 * 职责：发码接口的入参。
 * 调用方：modules/account/controller/SmsController。
 * 数据来源：客户端提交（《接口规范》§7.2 注册登录组 + B1 的入参定义 {phone, scene}）。
 * 注意：
 *   1. scene 只允许三个值，**用 @Pattern 白名单**而不是任由字符串透传——
 *      场景值会被写进日志与后续业务归属，野值进来会污染统计。
 *   2. 手机号校验与登录接口保持同一正则，避免「发码能发、登录说格式不对」。
 *   3. 这里**不收**图形验证码：本段没有图形验证码（风险由三层限流兜），
 *      不设字段比设了不用好（避免前端以为传了有用）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 短信验证码发送请求。
 */
@Data
public class SmsCodeBO {

    /** 中国大陆手机号。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 场景：register / login / reset。 */
    @NotBlank(message = "场景不能为空")
    @Pattern(regexp = "^(register|login|reset)$", message = "场景只能是 register/login/reset")
    private String scene;
}
