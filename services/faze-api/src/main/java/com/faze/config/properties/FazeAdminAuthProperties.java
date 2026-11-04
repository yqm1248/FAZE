/*
 * 职责：管理端鉴权桥（方案 A）的强类型配置绑定（`faze.admin-auth.*`）。
 * 调用方：AdminTokenVerifier（JWT 校验）、AdminSessionStore（读 RuoYi 登录态）、Spring 启动时绑定。
 * 数据来源：application.yml / application-dev.yml / application-prod.yml 与环境变量。
 * 注意：
 *   1. `tokenSecret` **必须与 RuoYi-Vue 的 `token.secret` 完全一致**（接口规范 §1.3.1 第 1 条）。
 *      两边各写一份值一定会漂移，所以本项在 prod 由环境变量 `FAZE_ADMIN_TOKEN_SECRET` 注入，
 *      只在 dev 里留 RuoYi 发行版的出厂默认值。
 *   2. Redis 指向 **RuoYi 用的逻辑库（默认 0）**，不是 faze-api 自己的 1 号库。
 *      两个应用共用一个 Redis 实例，靠库号隔离登录态与业务缓存（§1.3.1 第 2 条）。
 *   3. 与 FazeJwtProperties 的分工：那个管 C 端 access token（HS256、自己签发），
 *      本类管 PC 管理端 token（HS512、RuoYi 签发）。**两套密钥不许混用**。
 */
package com.faze.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 管理端鉴权配置。
 *
 * @param enabled        是否启用管理端鉴权桥；关掉后 `/api/v1/admin/**` 一律 401（默认拒绝）
 * @param tokenSecret    RuoYi 的 `token.secret`；prod 必须由 FAZE_ADMIN_TOKEN_SECRET 提供
 * @param redisHost      RuoYi 使用的 Redis 主机
 * @param redisPort      RuoYi 使用的 Redis 端口
 * @param redisPassword  RuoYi 使用的 Redis 口令（无口令时为空串）
 * @param redisDatabase  RuoYi 使用的逻辑库号，默认 0
 * @param tokenKeyPrefix 登录态 key 前缀，取自 RuoYi `CacheConstants.LOGIN_TOKEN_KEY`
 */
@ConfigurationProperties(prefix = "faze.admin-auth")
public record FazeAdminAuthProperties(
        @DefaultValue("true") boolean enabled,
        String tokenSecret,
        @DefaultValue("127.0.0.1") String redisHost,
        @DefaultValue("6379") int redisPort,
        String redisPassword,
        @DefaultValue("0") int redisDatabase,
        @DefaultValue("login_tokens:") String tokenKeyPrefix) {
}
