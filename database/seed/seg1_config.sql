-- ============================================================================
-- 第 1 段 · sys_config 种子数据（手工执行，**不进 Flyway**）
--
-- 为什么不进迁移：《第 1 段工作流》B1 明确「sys_config 插入 4 个键，用种子脚本，不放迁移里」。
--   原因：配置值会随环境改（开发期固定码、生产关掉），放迁移里等于每次部署都覆盖运营改过的值。
-- 执行方式：
--   mysql -uroot --default-character-set=utf8mb4 faze -e "source database/seed/seg1_config.sql"
-- 幂等：ON DUPLICATE KEY UPDATE 只刷新说明与分类，**不覆盖已改过的 config_value**
--   （运营在 PC A-08 调过的值，重跑种子不能被冲掉）。
-- ============================================================================

INSERT INTO sys_config
    (config_key, config_value, value_type, category, remark, editable, created_at, updated_at)
VALUES
    ('sms_fixed_code_enabled', 'true',  'bool',  'sms',      '开发期固定验证码开关；生产 profile 启动时强制校验必须为 false', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('sms_fixed_code',         '123456','string','sms',      '开发期固定验证码（仅 dev 生效，生产不读本键）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('sms_send_interval_seconds', '60', 'int',   'sms',      '同一手机号两次发送的最小间隔（秒）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('sms_daily_limit',        '10',    'int',   'sms',      '同一手机号每日发送上限（次）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('login_max_fail',         '5',     'int',   'security', '登录密码连续错误次数上限，达到后锁定', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('login_lock_minutes',     '15',    'int',   'security', '登录锁定分钟数（与支付密码的 30 分钟分开）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('pay_pwd_max_fail',       '5',     'int',   'security', '支付密码连续错误次数上限（负责人 2026-11-01 拍板）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('pay_pwd_lock_minutes',   '30',    'int',   'security', '支付密码锁定时长（分钟）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('platform_fee_rate',      '0.15',  'decimal','trade',   '平台服务费比例（占位 15%，段 2 订单生效）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('order_no_format',        'PREFIX2+yyyyMMdd+SEQ6', 'string', 'trade', '平台单号格式（段 2 建 orders.order_no 时生效）', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())
AS new
ON DUPLICATE KEY UPDATE
    value_type = new.value_type,
    category   = new.category,
    remark     = new.remark,
    updated_at = new.updated_at;
