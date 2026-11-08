-- ============================================================================
-- 迁移：V704__seg7_sensitive_switch.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：补 2 个内容审核开关配置项——`sensitive.enabled` / `sensitive.block_enabled`。
--
-- 为什么要单独一个迁移（而不是并进 V703）：
--   V703 已经在开发库和 CI 上执行过，改它能过 Flyway 校验的唯一方式是 `repair`，
--   而 repair 会掩盖「迁移被改过」这件事本身。**已执行的迁移一律不改**是硬规则
--   （《数据表规范》§七）。所以补一个 V704。
--
-- 为什么需要这两个键（第 7 段工作流 §三 A3 第 8 条）：
--   - `sensitive.enabled`：审核总开关。关闭后**完全不判定、不写 content_review**，
--     只打一条 WARN。用于「线上误伤大面积发生时紧急止血」，不是日常开关。
--   - `sensitive.block_enabled`：是否真的拦截。关闭后**照常判定与落记录**，只是不拦，
--     用于「从只记录切换到真拦截」的灰度期。
--   两者语义不同，不要合并成一个。
--
-- 默认值取 true：审核默认开启（安全默认）。A3 的验收会临时关掉再打开来验证行为。
-- 幂等：ON DUPLICATE KEY UPDATE，重跑不产生重复行、不重置运营改过的值。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('sensitive.enabled',       'true', 'bool', 'sensitive', '内容审核总开关；false 时完全不判定、不写 content_review，仅打 WARN（紧急止血用）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('sensitive.block_enabled', 'true', 'bool', 'sensitive', '是否为 block 级命中真拦截；false 时照常判定与落记录但不拦（灰度期用）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  value_type = VALUES(value_type),
  category   = VALUES(category),
  remark     = VALUES(remark),
  editable   = VALUES(editable),
  updated_at = UTC_TIMESTAMP();
