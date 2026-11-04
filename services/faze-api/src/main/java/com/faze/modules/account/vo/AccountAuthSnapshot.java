/*
 * 职责：账号认证状态的**只读**跨域快照（供 security 层比对 token_version）。
 * 调用方：security/JwtAuthenticationFilter。
 * 数据来源：app_user。
 * 注意：
 *   1. 只暴露认证需要的四个值，**不暴露手机号、密码哈希等任何敏感字段**——
 *      快照一旦带上这些字段，将来一定会被某个调用方顺手打进日志。
 *   2. 用 record：不可变、字段即契约。
 *   3. 放在 account 域的 vo 下（而不是 security 包），保证依赖方向是
 *      security → account，而不是 account → security。
 */
package com.faze.modules.account.vo;

/**
 * 账号认证快照。
 *
 * @param userId       用户主键
 * @param role         角色
 * @param status       active / blocked / cancelled
 * @param tokenVersion 凭证版本号
 * @param deleted      是否已软删（deleted_at 非空）
 */
public record AccountAuthSnapshot(Long userId, String role, String status, int tokenVersion, boolean deleted) {
}
