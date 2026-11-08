/*
 * 职责：修改支付密码的入参（`PUT /app/account/pay-password`，第 1 段 E1）。
 * 调用方：modules/account/controller/PayPasswordController。
 * 数据来源：App 支付密码页。
 * 注意：
 *   1. 修改**必须验旧支付密码**——这是支付密码与登录密码的关键区别：
 *      登录密码可以用短信重置，支付密码的修改必须证明「你知道旧的」。
 *   2. 旧密码校验失败会计入 E2 的锁定计数（与支付场景共用同一套计数），
 *      所以这个接口本身就是 1-A5 的验收载体之一。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

/**
 * 修改支付密码请求。
 */
@Data
public class ChangePayPasswordBO {

    /** 旧支付密码（6 位数字）。 */
    @NotBlank(message = "旧支付密码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "支付密码必须是 6 位数字")
    @ToString.Exclude
    private String oldPassword;

    /** 新支付密码（6 位数字）。 */
    @NotBlank(message = "新支付密码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "支付密码必须是 6 位数字")
    @ToString.Exclude
    private String newPassword;
}
