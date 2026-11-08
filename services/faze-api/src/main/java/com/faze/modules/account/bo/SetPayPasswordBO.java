/*
 * 职责：首次设置支付密码的入参（`POST /app/account/pay-password`，第 1 段 E1）。
 * 调用方：modules/account/controller/PayPasswordController。
 * 数据来源：App 支付密码页。
 * 注意：
 *   1. **6 位纯数字**是产品口径（第 1 段 E1 第 2 条），不是"至少 6 位"。
 *      放宽成字母会让用户在支付场景频繁输错，收紧成 4 位会让暴力空间只剩 1 万。
 *   2. 明文只在请求体里出现一次，Service 立刻 BCrypt；BO 不做 toString（见下）。
 */
package com.faze.modules.account.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

/**
 * 设置支付密码请求。
 */
@Data
public class SetPayPasswordBO {

    /** 6 位纯数字支付密码。 */
    @NotBlank(message = "支付密码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "支付密码必须是 6 位数字")
    @ToString.Exclude
    private String password;
}
