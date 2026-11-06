-- ============================================================================
-- 第 2 段 · sys_config 种子数据（手工执行，**不进 Flyway**）
--
-- 口径沿用第 1 段（见 seg1_config.sql 头部说明）：
--   配置值会随环境改，放迁移里等于每次部署都覆盖运营在 PC A-08 改过的值。
--   因此段内新增的配置键一律用种子脚本，幂等：ON DUPLICATE KEY UPDATE
--   只刷新说明与分类，**不覆盖已改过的 config_value**。
-- 执行方式：
--   mysql -uroot --default-character-set=utf8mb4 faze -e "source database/seed/seg2_config.sql"
-- ============================================================================

INSERT INTO sys_config
    (config_key, config_value, value_type, category, remark, editable, created_at, updated_at)
VALUES
    -- G2.1「下单前是否要求律师在线」开关。
    --   默认 false：律师可能只是没开 App，写死 true 会误杀正常业务（工作流 G2.1「卡住怎么办」）。
    --   打开后，online_status != 'online' 的律师不可被下单，返回 405010。
    ('order_require_lawyer_online', 'false', 'bool', 'trade',
     '下单是否要求律师在线（true=离线不可下单；默认 false，避免律师没开 App 就被误杀）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
    value_type = VALUES(value_type),
    category   = VALUES(category),
    remark     = VALUES(remark),
    updated_at = UTC_TIMESTAMP();
