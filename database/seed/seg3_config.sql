-- ============================================================================
-- 第 3 段 · sys_config 种子数据（手工执行，**不进 Flyway**）
--
-- 口径沿用第 1/2 段（见 seg1_config.sql、seg2_config.sql 头部说明）：
--   配置值会随环境改，放迁移里等于每次部署都覆盖运营在 PC A-08 改过的值。
--   因此段内新增的配置键一律用种子脚本，幂等：ON DUPLICATE KEY UPDATE
--   只刷新说明与分类，**不覆盖已改过的 config_value**。
--
-- 执行方式（在仓库根）：
--   mysql -uroot --default-character-set=utf8mb4 faze -e "source database/seed/seg3_config.sql"
-- 或：
--   Get-Content database/seed/seg3_config.sql -Encoding UTF8 |
--     & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --host=127.0.0.1 --user=root `
--       --default-character-set=utf8mb4 faze
--
-- 键的来源：docs/architecture/法泽FAZE_第3段工作流_v1.0.md §五.5（配置项）。共 8 个：
--   AI 开关与模型（C1）、检索条数与限流（B1/B2）、超时（C4）、配额（C5）、留存（E3）。
-- ============================================================================

INSERT INTO sys_config
    (config_key, config_value, value_type, category, remark, editable, created_at, updated_at)
VALUES
    -- C1 / F4 / 3-A7：AI 总开关。false 时 App 的 AI 页降级为纯法条检索，C2 直接返回 degraded。
    --   默认 true（本地开发要能跑通链路）；上线前若要灰度关闭，在 PC A-08 改成 false 即可，不用发版。
    ('ai_enabled', 'true', 'bool', 'ai',
     'AI 法律顾问总开关（false=App 端降级为纯法条检索，不调豆包）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- C1：模型 id。**必须带完整上下文后缀**（技术路线 L2867 警告：不带后缀会被平台按默认上下文处理，
    --   长对话中途截断且不报错）。值以火山方舟控制台实际 id 为准，改这里即可，不用改代码。
    ('ai_model', 'doubao-seed-1.6-32k', 'string', 'ai',
     '豆包模型 id，必须含上下文后缀（如 doubao-seed-1.6-32k）；以火山控制台为准',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- C5：每用户每日轮次。总纲已裁决 AI 不计费（§零之二 第 5 项），所以是「配额」不是「计费」。
    ('ai_daily_quota', '50', 'int', 'ai',
     '每用户每日 AI 问答轮次上限（不计费，仅为成本与滥用防护）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- B1：检索条数。技术路线 §11.6.2 第 2 步「全文索引 top-5」。代码里上限夹到 10。
    ('ai_search_topn', '5', 'int', 'ai',
     'AI 回答时注入 Prompt 的法条条数（top-N，代码上限 10）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- C4：单次流上限（秒）。
    ('ai_timeout_seconds', '120', 'int', 'ai',
     '单次 AI 流式回答的最长时间（秒），超时发 done+timeout 而不是静默断开',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- C4：首字节超时（毫秒）。超过即 508001，且本次不扣配额。
    ('ai_first_byte_timeout_ms', '5000', 'int', 'ai',
     'AI 首字节超时（毫秒），超时返回 508001 且退回配额',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- E3：留存期（天）。负责人 2026-11-01 拍板 3 年（《个人信息保护法》第 56 条处理记录 ≥3 年）。
    --   本段**不做自动清理**，只登记这个值供运维脚本引用。
    ('ai_message_retention_days', '1095', 'int', 'audit',
     'AI 对话留存天数（1095=3 年，负责人拍板）；本段不做自动清理，清理须人工审批+先归档',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- B2：pub 法条检索限流（无鉴权接口，必须限流，接口规范 §6.5）。
    ('law_search_rate_per_min', '60', 'int', 'ai',
     'GET /pub/law-items 每 IP 每分钟请求上限（无鉴权接口的滥用防护）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
    value_type = VALUES(value_type),
    category   = VALUES(category),
    remark     = VALUES(remark),
    updated_at = UTC_TIMESTAMP();
