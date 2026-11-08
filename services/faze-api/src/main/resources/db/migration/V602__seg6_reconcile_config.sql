-- ============================================================================
-- 迁移：V602__seg6_reconcile_config.sql
-- 所属段：第 6 段（真实充值渠道 + 掉单补单）D3/D4
-- 做什么：写入渠道对账需要的 sys_config 配置键（1 个，验收用差异注入）。
-- 依赖：V300 的 sys_config（uk_config_key 唯一）；V601（同段前一批键）。
-- 编写：2026-11-08
-- 依据：《第 6 段工作流》§三 D3 第 4 条、D4 第 2 条（三类差异的验收口径）。
--
-- 为什么需要这个键：
--   D4 验收要求「造 3 类差异各 1 条，比对后 reconciliation_diff 3 行且 diff_type 正确」。
--   Mock 账单默认与平台数据完全一致（这才是「没有差异」的正常态），所以差异必须是
--   **可复现地注入**的，而不是靠手工改库——手工改库的验收不可重复、也无法在单测里跑。
--   注入规格的 JSON 结构见 `infra/pay/reconcile/BillInjection` 的类注释。
--
-- ⚠️ **生产必须为空串**：真实渠道的账单来自渠道，平台不生成也不改。本键只在
--    `pay.mock.enabled=true` 时被读取（ReconcileBillService#loadInjection）。
--    真有一个非空值留在生产，等于让 Mock 账单凭空多出/少掉交易。
--
-- 幂等：ON DUPLICATE KEY UPDATE 只刷新 updated_at，不覆盖已改过的值。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('reconcile.mock.inject', '', 'json', 'reconcile', 'Mock 账单差异注入规格（JSON，验收专用；生产必须为空）。extraRows=造 channel_only，dropOrderNos=造 local_only，amountShifts=造 amount_mismatch', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
