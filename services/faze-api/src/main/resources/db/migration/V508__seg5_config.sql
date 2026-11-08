-- ============================================================================
-- 迁移：V508__seg5_config.sql
-- 所属段：第 5 段（结算 / 提现 / 律师服务的可配参数）
-- 做什么：向 sys_config 写入本段 5 个新配置键（**不写数据表结构**）。
-- 依赖：V6 的 sys_config。
-- 编写：2026-11-08
-- 依据：《第 5 段工作流》§二 第 7 项、§五 5.5（6 个键）。
--
-- 键名裁决（2026-11-08 负责人确认）：
--   平台服务费率**沿用 V7 已写入的 `platform_fee_rate`（0.15）**，不新增 `platform.fee_rate`，
--   避免同一语义两个键、两处都可能被改而漂移。本迁移因此只补 5 个键。
--
-- 幂等：INSERT IGNORE —— 已存在的键保持原值不动（开发库重跑、生产库已有旧值都不会被覆盖）。
-- 最低提现 10 分是**测试值**；上线前由负责人调整（《第 5 段工作流》§十 第 3 项）。
-- ============================================================================

INSERT IGNORE INTO sys_config
  (config_key, config_value, value_type, category, remark, editable, created_at, updated_at)
VALUES
  ('withdraw.min_amount_cents', '10',     'long',    'trade',
   '最低提现金额（分）；10 分 = 0.1 元，测试值，上线前复核', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('withdraw.fee_rate',         '0.006',  'decimal', 'trade',
   '提现手续费率；0.006 = 0.6%，计算到分，不足 1 分记 0', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('settlement.freeze_days',    '7',      'int',     'trade',
   '收入冻结天数；income 流水满 7 天后由 LAWYER_UNFREEZE 任务解冻', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('lawyer.price.min_cents',    '2000',   'long',    'trade',
   '律师服务定价下限（分）；2000 = 20 元', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('lawyer.price.max_cents',    '200000', 'long',    'trade',
   '律师服务定价上限（分）；200000 = 2000 元', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP());
