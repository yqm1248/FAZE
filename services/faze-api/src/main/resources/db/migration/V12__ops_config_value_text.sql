-- ============================================================================
-- V12 · 放宽 sys_config.config_value 到 TEXT
--
-- 为什么改：《第 1 段工作流》G1 的「用户服务协议页 / 隐私政策页」按裁决 #12 必须
--   「正文走配置、页面只渲染」。正文存 sys_config 的 user_agreement_content /
--   privacy_policy_content 两条键。
--
-- 实测缺陷：V6 建成 varchar(500)，导入种子时报
--   ERROR 1406 (22001): Data too long for column 'config_value' at row 11
--   —— 一份占位正文约 900 汉字（utf8mb4 下 2700 字节），任何真实法务文本都装不下。
--   500 字符只够放技术参数（sms_fixed_code / pay_pwd_max_fail 这类）。
--
-- 为什么用 TEXT 而不是 varchar(20000)：TEXT 上限 65535 字节 ≈ 16383 汉字，
--   对协议正文足够；varchar(20000) 在 utf8mb4 下是 80000 字节，超过行内 65535 的限制
--   只能落成 off-page 存储，与 TEXT 实质相同却要多写一个魔数。
--
-- 注意：
--   1. TEXT 列不能有 DEFAULT，本列原就 NOT NULL 无默认值，语义不变。
--   2. 读取方按 value_type 解析，本改动不影响 int / bool / decimal 类键的行为。
-- ============================================================================

ALTER TABLE sys_config
    MODIFY COLUMN config_value TEXT NOT NULL COMMENT '配置值（统一按字符串存，由读取方按 value_type 解析）';
