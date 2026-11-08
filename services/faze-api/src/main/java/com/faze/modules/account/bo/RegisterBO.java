/*
 * 职责：注册接口的入参（`POST /pub/auth/register`）。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：客户端提交；字段依据《第 1 段工作流》B2 的入参清单。
 * 注意：
 *   1. `isMinor` 与 `guardianPhone` 的联动校验**不在注解里做**（跨字段条件注解要自定义
 *      ConstraintValidator），放在 AuthService 里做——那里的错误码更准确（402009）。
 *   2. `agreementAccepted` 必须是 true：协议勾选是合规要求，不能默认通过。
 *   3. 密码只校验「非空 + 长度」，强度规则在 PasswordPolicy（纯函数，可脱离 Spring 单测）。
 *   4. **`region` 只校验非空**（第 7 段尾巴）：白名单校验在 `RegionOptionsService`，
 *      因为它读 `sys_config.user.region_options`——运行时数据，写成注解参数就把
 *      「加一个县只改配置」的设计废掉了。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 */
@Data
public class RegisterBO {

    /** 中国大陆手机号。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 短信验证码（6 位数字）。 */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字")
    private String smsCode;

    /** 密码明文（TLS 内传输），强度由 PasswordPolicy 校验。 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8~64 之间")
    private String password;

    /**
     * 用户所在地区（第 7 段尾巴新增，**必填**）。
     *
     * <p>负责人 2026-11-09 拍板「注册页采集」——不选资料页选填，理由是选填覆盖率太低，
     * 会让 A-02 看板「地区分布」与「按地区发公告」形同虚设。</p>
     *
     * <p>**这里只校验非空**；「是否命中白名单」由 `AuthService` 交给
     * `RegionOptionsService.assertValid` 做——白名单来自 `sys_config`，是运行时数据，
     * 不能写成注解参数（那样加一个县就得改代码发版，等于把可配置设计废掉）。</p>
     */
    @NotBlank(message = "请选择所在地区")
    @Size(max = 64, message = "地区名称过长")
    private String region;

    /** 是否未成年人：由用户手动勾选（负责人 2026-11-01 裁决 #10）。 */
    @NotNull(message = "请选择是否未成年")
    private Boolean isMinor;

    /** 监护人手机号，isMinor=true 时必填。 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "监护人手机号格式不正确")
    private String guardianPhone;

    /** 是否已勾选用户协议与隐私政策。 */
    @NotNull(message = "请先同意用户协议与隐私政策")
    private Boolean agreementAccepted;
}
