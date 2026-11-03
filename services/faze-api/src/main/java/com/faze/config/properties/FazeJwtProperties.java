/*
 * 职责：JWT 相关配置的强类型绑定（`faze.jwt.*`）。
 * 调用方：JwtTokenProvider（签发/校验）、AuthService（refresh 有效期）、Spring Boot 启动时绑定。
 * 数据来源：application.yml / application-dev.yml / application-prod.yml 与环境变量。
 * 注意：
 *   1. 规范 §12.3：一个领域一个 `@ConfigurationProperties`，用**构造绑定 + @DefaultValue**，
 *      不用 `@Data` + 手写 getter 补默认值（那是 66 行纯样板）。
 *   2. `secret` 故意**不给默认值**：生产必须由 `FAZE_TOKEN_SECRET` 提供，缺失即启动失败
 *      （JwtTokenProvider 构造期校验），这是「默认拒绝」。
 *   3. 本类不含任何校验逻辑——校验留在使用方（JwtTokenProvider），因为只有那里知道
 *      「多长才算够 256 bit」。
 */
package com.faze.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT 配置。
 *
 * @param secret           HS256 密钥（≥32 字节），生产由环境变量提供
 * @param accessTtlMinutes access token 有效期（分钟），默认 120
 * @param refreshTtlDays   refresh token 有效期（天），默认 30
 */
@ConfigurationProperties(prefix = "faze.jwt")
public record FazeJwtProperties(
        String secret,
        @DefaultValue("120") long accessTtlMinutes,
        @DefaultValue("30") long refreshTtlDays) {
}
