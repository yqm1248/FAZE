/*
 * 职责：管理端登录态的进程内载体（从 RuoYi 缓存的 LoginUser 中抽出的最小字段集）。
 * 调用方：infra/adminauth/AdminSessionStore（构造）、security/AdminAuthenticationFilter（当 principal 用）。
 * 数据来源：Redis `login_tokens:<uuid>` 的值（RuoYi 写入）。
 * 注意：
 *   1. **只有两个字段是故意的**（接口规范 §1.3.1 第 4 条「只取必要字段」）。
 *      RuoYi 的 LoginUser 有十几个字段，取全了等于把 faze-api 焊死在 RuoYi 的内部结构上。
 *   2. `adminId` 是 `faze_admin.sys_user.id`，**不是** `app_user.id`。
 *      审计表 `operation_log.operator_id` 记的就是它，`operator_type` 记 `admin`。
 *   3. `username` 只用于日志与审计可读性，**不参与鉴权判定**。
 */
package com.faze.infra.adminauth;

/**
 * 管理端登录态。
 *
 * @param adminId  后台账号主键（faze_admin.sys_user.id）
 * @param username 后台账号登录名
 */
public record AdminSession(Long adminId, String username) {
}
