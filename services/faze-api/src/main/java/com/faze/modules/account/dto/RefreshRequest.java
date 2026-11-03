/*
 * 职责：刷新令牌接口的请求体（`POST /api/v1/app/auth/refresh`）。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：客户端持有的 refreshToken（《接口规范》附录 A.1.2）。
 * 注意：该接口本身**不需要 access token**（前提就是它已过期），鉴权完全靠这个字段。
 */
package com.faze.modules.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 */
@Data
public class RefreshRequest {

    /** 登录时下发的 refresh token（43 字符 Base64URL 随机串）。 */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
