-- ============================================================================
-- 迁移：V510__seg5_case_library_and_recon_fix.sql
-- 所属段：第 5 段（G1 案例库四正文块 + H1 恒等式差异落表）
-- 做什么：两处结构补正，都是施工中发现「计划文档的字段清单与实际需求不一致」：
--
--   ① `case_library` 补 `dispute_focus`（争议焦点）。
--      原因：《第 5 段工作流》§三 G1 第 6 条与 §四 `5-A7` 都要求详情页有
--      **四个正文块**（案情简介 / 争议焦点 / 裁判结果 / 案例启示），
--      但《技术路线》§5.8 的 DDL 与 V503 只建了三个（summary / judgment / insight）。
--      不补这一列，「四个正文块齐全」这条验收标准无法达成。
--
--   ② `reconciliation_diff` 补恒等式巡检字段，并把渠道对账的必填列改为可空。
--      原因：V507 按《技术路线》§5.11 建的是**渠道对账**形状
--      （bill_date / channel / diff_type / channel_txn_id 全 NOT NULL），
--      但 H1 要求把**账务恒等式巡检**的差异也写进同一张表
--      （check_type / biz_type / biz_id / expected_cents / actual_cents / diff_cents / found_at）。
--      恒等式差异没有「账单日期」和「渠道」，所以那两列必须允许 NULL。
--
-- 边界：
--   1. 只补列 + 放宽 NOT NULL，**不改任何既有列的语义、不删列、不改数据**。
--   2. `status` 默认值 `pending` → `open`：与 H1 的 `status='open'` 统一
--      （段 6 尚未写代码，此刻改零成本；两套值并存会让巡检结果筛选写两套条件）。
--   3. `diff_type` 保持 NOT NULL：恒等式差异写 `identity_mismatch`，与渠道差异靠它区分。
--
-- 幂等：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，逐列用 information_schema 判断后动态执行。
-- 依据：《第 5 段工作流》§三 G1 第 6 条、§三 H1；《数据表规范》附录 A（需在 H3 回写字段数）。
-- ============================================================================

-- ⚠️ 本迁移在 Flyway 执行前修订过两次（2026-11-08）：
--    第一次只补主表 `case_library.dispute_focus`，漏了译文表（G1 第 7 条要求四块正文一起切语言）；
--    第二次补 `plaintiff_name` / `defendant_name`——A-16 列表要显示「标题 + 当事人」、
--    录入表单要填「原告/被告（脱敏姓名）」，但《技术路线》§5.8 的 DDL 没有这两个字段。
--    Flyway 尚未执行过本文件（V510 没进 flyway_schema_history），不算破坏校验和。

-- ── ① case_library.dispute_focus ────────────────────────────────────────────
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_library' AND COLUMN_NAME = 'dispute_focus'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE case_library ADD COLUMN dispute_focus TEXT DEFAULT NULL COMMENT ''争议焦点（段 5 G1 补：详情页四个正文块之一）'' AFTER summary',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ── ② reconciliation_diff：放宽渠道列 + 补恒等式列 ──────────────────────────
SET @exists := (
  SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'bill_date'
);
SET @ddl := IF(@exists = 'NO',
  'ALTER TABLE reconciliation_diff MODIFY COLUMN bill_date DATE DEFAULT NULL COMMENT ''账单日期（渠道对账用；恒等式差异为 NULL）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'channel'
);
SET @ddl := IF(@exists = 'NO',
  'ALTER TABLE reconciliation_diff MODIFY COLUMN channel VARCHAR(20) DEFAULT NULL COMMENT ''渠道 wechat / alipay / bank 等（恒等式差异为 NULL）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'check_type'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN check_type VARCHAR(30) DEFAULT NULL COMMENT ''巡检类型：identity_balance（缓存 vs 最后一条流水）/ identity_sum（缓存 vs 流水汇总）'' AFTER diff_type',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'biz_type'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN biz_type VARCHAR(30) DEFAULT NULL COMMENT ''差异主体类型：lawyer / user'' AFTER check_type',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'biz_id'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN biz_id BIGINT DEFAULT NULL COMMENT ''差异主体主键（lawyer.id / app_user.id）'' AFTER biz_type',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'expected_cents'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN expected_cents BIGINT DEFAULT NULL COMMENT ''按流水算出的应有余额（分）'' AFTER biz_id',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'actual_cents'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN actual_cents BIGINT DEFAULT NULL COMMENT ''缓存表里的实际余额（分）'' AFTER expected_cents',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'diff_cents'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN diff_cents BIGINT DEFAULT NULL COMMENT ''差异额 = actual − expected（分）'' AFTER actual_cents',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reconciliation_diff' AND COLUMN_NAME = 'found_at'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE reconciliation_diff ADD COLUMN found_at DATETIME DEFAULT NULL COMMENT ''发现时间（UTC）'' AFTER diff_cents',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- status 默认值统一为 open（H1 口径）。MODIFY 重复执行结果相同，天然幂等。
ALTER TABLE reconciliation_diff
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'open'
    COMMENT 'open 待处理 / handled 已处理 / ignored 已忽略';

-- ── ④ case_library 当事人（脱敏姓名）────────────────────────────────────────
-- ── ③ case_library 当事人（脱敏姓名）────────────────────────────────────────
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_library' AND COLUMN_NAME = 'plaintiff_name'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE case_library ADD COLUMN plaintiff_name VARCHAR(50) DEFAULT NULL COMMENT ''原告（脱敏姓名，如 张*；段 5 G1 补：A-16 列表要显示当事人）'' AFTER title',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_library' AND COLUMN_NAME = 'defendant_name'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE case_library ADD COLUMN defendant_name VARCHAR(50) DEFAULT NULL COMMENT ''被告（脱敏姓名，如 李*；段 5 G1 补）'' AFTER plaintiff_name',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ── ④ case_library_translation.dispute_focus ────────────────────────────────
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'case_library_translation' AND COLUMN_NAME = 'dispute_focus'
);
SET @ddl := IF(@exists = 0,
  'ALTER TABLE case_library_translation ADD COLUMN dispute_focus TEXT DEFAULT NULL COMMENT ''译文的争议焦点（段 5 G1 补：四块正文要一起切语言）'' AFTER summary',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
