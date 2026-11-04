/*
 * 职责：取当前管理端操作人（faze_admin.sys_user.id）。
 * 调用方：`/api/v1/admin/**` 下所有要写审计或要记 `audited_by` 的 Service / Controller。
 * 数据来源：SecurityContext 的 principal（AdminAuthenticationFilter 从 RuoYi 登录态还原）。
 * 注意：
 *   1. **必须与 security/LoginUser（C 端）区分**：那个是 app_user.id，本类是 sys_user.id。
 *      两张表主键各自从 1 开始，值会撞；用错类型就会把审计记到另一个人身上。
 *   2. 未认证时返回 null，由调用方决定怎么办。**不要在这里抛异常**：
 *      纯查询接口也可能调到本方法（比如「我的」类接口），抛异常会让只读页面 500。
 *   3. 不返回整个 principal：调用方只需要 id 和登录名，给多了就会被拿来当权限判断依据。
 */
package com.faze.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前管理端操作人。
 */
public final class CurrentAdmin {

    private CurrentAdmin() {
    }

    /**
     * 取当前管理员的账号 ID。
     *
     * @return faze_admin.sys_user.id；未认证时返回 null
     */
    public static Long id() {
        AdminLoginUser admin = current();
        return admin == null ? null : admin.adminId();
    }

    /**
     * 取当前管理员的登录名。
     *
     * @return 登录名；未认证时返回 null
     */
    public static String username() {
        AdminLoginUser admin = current();
        return admin == null ? null : admin.username();
    }

    /**
     * 取当前管理员主体。
     *
     * @return 管理员；未认证或 principal 类型不符时返回 null
     */
    public static AdminLoginUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminLoginUser admin)) {
            return null;
        }
        return admin;
    }
}
