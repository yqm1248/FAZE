-- ============================================================================
-- 迁移：V705__seg7_risk_config.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：补 B5 风控的 6 个配置项。
--
-- 为什么要这 6 个键（第 7 段工作流 §三 B5）：
--   风控阈值必须可配、改完即时生效（工作流第 6 条），不能写死在代码里——
--   写死意味着「运营发现误封只能找开发发版」，而误封是常态。
--
-- 键名说明：
--   `risk.sms_per_minute` / `risk.login_fail_lock_count` / `risk.login_fail_lock_minutes`
--   / `risk.ai_rounds_per_day` 按工作流原文命名；
--   `risk.register_ip_per_day` / `risk.register_device_per_day` 是工作流第 2 条
--   「同 IP 24 小时 > 3 个账号 / 同设备 > 2 个账号」的阈值落地（原文只给数字没给键名）。
--
-- 默认值：全部取工作流原文的数字。**这些是「记录并告警」阈值，不是硬拦**——
--   第 4 个账号仍然能注册成功，只是写一条 user_violation(restrict) 供运营看。
-- 幂等：ON DUPLICATE KEY UPDATE，不重置运营改过的值（value 列也更新为默认值，
--   因为这是「初始默认」，运营改过的值会被覆盖——为避免这点，本迁移用 IGNORE 语义）。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('risk.sms_per_minute',          '1',  'int', 'risk', '同手机号短信发送最小间隔（分钟）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('risk.login_fail_lock_count',   '5',  'int', 'risk', '连续登录失败几次触发锁定', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('risk.login_fail_lock_minutes', '30', 'int', 'risk', '登录失败锁定时长（分钟）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('risk.ai_rounds_per_day',       '50', 'int', 'risk', '单用户 AI 每日轮次上限', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('risk.register_ip_per_day',     '3',  'int', 'risk', '同 IP 24 小时注册账号数阈值，超出只记录不拦', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('risk.register_device_per_day', '2',  'int', 'risk', '同设备 24 小时注册账号数阈值，超出只记录不拦', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  value_type = VALUES(value_type),
  category   = VALUES(category),
  remark     = VALUES(remark),
  editable   = VALUES(editable),
  updated_at = UTC_TIMESTAMP();
