/*
 * 职责：管理端的 SecurityContext 主体（PC 后台管理员）。
 * 调用方：security/AdminAuthenticationFilter（构造）、后续管理端业务代码（取当前操作人）。
 * 数据来源：RuoYi 登录态（Redis）、只取 adminId 与 username。
 * 注意：
 *   1. **与 security/LoginUser 是两个类型，不许合并**。LoginUser 的 userId 是 `app_user.id`，
 *      本类的 adminId 是 `faze_admin.sys_user.id`；混用会把 C 端用户当成管理员（或反之）。
 *      两张表的主键各自从 1 开始，值撞在一起完全正常，靠类型区分才安全。
 *   2. 不实现 Spring Security 的 `UserDetails`：管理端的账号锁定 / 密码策略由 RuoYi 自己管，
 *      faze-api 只消费它的登录态，不做第二套账号体系。
 *   3. 权限只有一个角色（AGENTS 决策：后台只有管理员），因此不携带 permissions 集合；
 *      接口规范 §6.2 明确「只校验角色，不做数据级权限细分」。
 */
package com.faze.security;

/**
 * 管理端认证主体。
 *
 * @param adminId  后台账号主键（faze_admin.sys_user.id）
 * @param username 后台登录名，只用于日志可读性
 */
public record AdminLoginUser(Long adminId, String username) {
}
