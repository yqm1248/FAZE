/*
 * 职责：登录接口的响应 data（access + refresh + 用户资料）。
 * 调用方：modules/account/controller/AuthController。
 * 数据来源：JwtTokenProvider 签发 + RefreshTokenStore 记录 + app_user 资料。
 * 注意：字段集合按《接口规范》附录 A.1.1 冻结（accessToken / refreshToken / expiresIn /
 *       tokenType / user），多一个字段都是契约变更。
 */
package com.faze.modules.account.vo;

import lombok.Data;

/**
 * 登录响应。
 */
@Data
public class LoginVO {

    /** access token（JWT，2 小时）。 */
    private String accessToken;

    /** refresh token（30 天，可轮换）。 */
    private String refreshToken;

    /** access token 剩余秒数。 */
    private Integer expiresIn;

    /** 令牌类型，固定 `Bearer`。 */
    private String tokenType;

    /** 用户资料。 */
    private UserVO user;
}
