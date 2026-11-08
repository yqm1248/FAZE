/*
 * 职责：刷新令牌接口的响应 data。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：JwtTokenProvider + RefreshTokenStore。
 * 注意：按《接口规范》附录 A.1.2，刷新响应**只回这三个字段**，不带 user
 *       （客户端此时手里还有旧 user，多回一份只会造成两份真相）。
 */
package com.faze.modules.account.vo;

import lombok.Data;

/**
 * 刷新令牌响应。
 */
@Data
public class TokenVO {

    /** 新的 access token。 */
    private String accessToken;

    /** 轮换后的新 refresh token（旧的立刻失效）。 */
    private String refreshToken;

    /** access token 剩余秒数。 */
    private Integer expiresIn;
}
