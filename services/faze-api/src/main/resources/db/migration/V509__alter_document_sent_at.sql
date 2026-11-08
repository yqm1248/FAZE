-- ============================================================================
-- 迁移：V509__alter_document_sent_at.sql
-- 所属段：第 5 段（D2 律师文书「发送给客户」）
-- 做什么：给 document 加一列 `sent_at`——记录文书**首次发给委托人**的时间。
-- 依赖：V400 的 document 表。
-- 边界：
--   1. 只加 1 列，不改任何既有列、不动 `status` 的取值集合。
--   2. `sent_at` 是「已发送」的判据：NULL = 待发送 / 有值 = 已发送。
--      不新造 `status='sent'`：status 描述的是文书生成生命周期（draft/generated/signed/failed），
--      发送是另一条独立的业务事实，混进 status 会把生成状态机搞乱。
--   3. 不新加消息表、不动 conversation_message 的结构（段 2 已建，段 5 只加 msg_type 取值 doc）。
-- 幂等：MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，用 information_schema 判断后动态执行。
-- ============================================================================

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'sent_at'
);

SET @ddl := IF(@exists = 0,
  'ALTER TABLE document ADD COLUMN sent_at DATETIME DEFAULT NULL COMMENT ''首次发送给委托人的时间（UTC）；NULL 表示待发送'' AFTER status',
  'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
