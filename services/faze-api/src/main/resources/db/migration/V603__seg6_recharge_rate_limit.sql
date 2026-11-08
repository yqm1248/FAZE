/*
 * 迁移：V603__seg6_recharge_rate_limit.sql
 * 所属段：第 6 段（真实充值渠道 + 掉单补单）B3
 * 做什么：写入充值下单的用户维度频次限流阈值（1 个键）。
 * 依赖：V300 的 sys_config（uk_config_key 唯一）；V601（同段前一批 recharge.* 键）。
 * 编写：2026-11-08
 * 依据：《第 6 段工作流》§三 B3 第 3 条「按《接口规范》的限流规则对下单接口加用户维度限流
 *       （如 1 分钟 3 次）」；B3「算做完」最后一句「同一用户 1 分钟内第 4 次下单被限流」。
 *
 * 为什么单独一条迁移而不是并进 V601：
 *   V601 已在 2026-11-08 执行并入基线，改它会让「已执行的迁移」与文件内容不一致
 *   （Flyway 校验和冲突）。新增键一律新开版本号，不回改已执行的迁移。
 *
 * 为什么阈值放 sys_config 而不是写死：
 *   限流阈值是运营参数（黑五/活动期可能要放宽），与 recharge.* 其它阈值同口径
 *   （RechargeQuotaPolicy 类注释第 1 条：代码里只留兜底值，且必须与种子一致）。
 *
 * 幂等：ON DUPLICATE KEY UPDATE 只刷新 updated_at，不覆盖运营改过的值。
 * ============================================================================
 */

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('recharge.rate_limit_per_min', '3', 'int', 'recharge', '充值下单用户维度限流：同一用户每分钟最多 N 次下单（B3 第 3 条）；超限返回 401007 + Retry-After', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
