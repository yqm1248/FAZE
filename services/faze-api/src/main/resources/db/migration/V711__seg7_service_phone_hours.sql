-- ============================================================================
-- 迁移：V711__seg7_service_phone_hours.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：补 1 个 sys_config 键 `service.phone_hours` = `09:00-18:00`，
--         供 APP 55（客服中心）的热线入口展示时段。
--
-- 依据：《第 7 段工作流》§十「改了 sys_config 后页面跟着变」。
--   APP 55 的热线时段原先写死在 `SupportPage.tsx` 的 TS 常量里
--   （`PHONE_HOURS = '9:00–18:00'`），而同一文件头写着「号码与时段一律从服务端读，
--   页面不许写死」——这是 P2-3 的真实缺陷。设计稿 `design/ui/55-support.html`
--   热线原文是「工作日 9:00–18:00」，所以种子值取 09:00-18:00。
--
-- 幂等：用 ON DUPLICATE KEY UPDATE，**不更新 config_value**：
--   热线时段是运营会改的值（节假日调整、临时作息），重跑迁移把运营改过的值冲回种子值，
--   等于迁移脚本偷偷回滚了运营动作。只更新 value_type / category / remark / editable
--   （结构信息）。这是 V708 定下的规矩，本迁移沿用。
--
-- ⚠️ 三条必须让后人知道的：
--   1. **值必须是 `09:00-18:00`（补零 + 半角连字符）**：APP 端
--      `isWithinServiceHours()` 的正则是 `/^(\d{2}):(\d{2})-(\d{2}):(\d{2})$/`；
--      写成 `9:00-18:00`（不补零）或 `09:00–18:00`（en dash）都会解析失败，
--      **静默**判成「不在服务时间」——不报错、只是页面永远显示下班。
--   2. **`service.phone_hours` 与 `service.online_hours` 是两条不同服务**：
--      在线客服 09:00-20:00、热线 09:00-18:00，两个值不同是设计意图，
--      **不要合并成一个键**，也不要拿一个去覆盖另一个。
--   3. 本迁移**只加 sys_config 行，不建表、不改列**，因此不需要补表级授权
--      （deploy/sync-db-grants.ps1 只对新表有意义）。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('service.phone_hours', '09:00-18:00', 'string', 'service',
 '客服热线服务时段（HH:mm-HH:mm，半角连字符；不补零/用 en dash 会让前端时段解析静默判定为下班）',
 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  value_type = VALUES(value_type),
  category   = VALUES(category),
  remark     = VALUES(remark),
  editable   = VALUES(editable),
  updated_at = UTC_TIMESTAMP();
