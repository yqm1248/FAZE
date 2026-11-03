/*
 * 职责：已认证用户的进程内主体。Security 的 principal 就是它，业务代码用 CurrentUser 取当前用户。
 * 调用方：security/JwtAuthenticationFilter（构造并放入 SecurityContext）、
 *         modules/account/ProfileController（取 id 查资料）。
 * 数据来源：access token 的 JWT claims（sub=用户主键，role=角色）。
 * 注意：
 *   1. **不实现 UserDetails**：段 0 不需要 Spring Security 的账号锁定/密码过期那一套
 *      （密码校验在 AuthService 里用 BCrypt 自己做），塞进来只会让 principal 每次都被
 *      ProviderManager 重新校验一遍。
 *   2. **只放 token 里已有的字段**：不要把数据库实体塞进 SecurityContext——
 *      token 里的信息可能已过期，业务要最新数据必须重新查库。
 */
package com.faze.security;

import lombok.Getter;

/**
 * 当前请求的认证主体。
 */
@Getter
public class LoginUser {

    /** 用户主键（app_user.id）。 */
    private final Long userId;

    /** 角色：user / lawyer / customer_service / admin（与 app_user.role 同值）。 */
    private final String role;

    /**
     * 构造认证主体。
     *
     * @param userId 用户主键，来自 JWT 的 sub
     * @param role   角色，来自 JWT 的 role claim
     */
    public LoginUser(Long userId, String role) {
        this.userId = userId;
        this.role = role;
    }
}
