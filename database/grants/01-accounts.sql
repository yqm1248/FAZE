-- ══════════════════════════════════════════════════════════════════
-- 法泽 FAZE 数据库账号（迁移账号 + 运行时账号的基础授权）
--
-- 职责：建 `faze_migrate`（Flyway 迁移与人工归档用），并授 `faze` 库级 DDL/DML；
--       运行时账号 `faze_app` 的**表级**授权不写在这里（表随迁移变化，写死会漂移），
--       由 deploy/sync-db-grants.ps1 按 information_schema 动态生成。
-- 调用方：deploy/sync-db-grants.ps1（以 root 身份执行）；生产首次初始化同样用它。
-- 数据来源：本文件不含任何真实口令。`{{MIGRATE_PASSWORD}}` 由脚本从
--           deploy/.env 的 FAZE_DB_MIGRATE_PASSWORD 读入后替换。
-- 注意事项：
--   1. 为什么必须拆成两个账号：《审计与日志规范》要求 `operation_log` 只追加、
--      不可改不可删，且**DB 账号无 UPDATE/DELETE 权限**。MySQL 的权限是并集，
--      库级 GRANT 无法用表级 REVOKE 减掉，所以「既能在库里建表、又不能改审计表」
--      只有一个合法解——迁移与运行分离。
--   2. **不要给 `faze_app` 任何库级权限**。一旦有了 `faze.*` 的 GRANT，
--      operation_log 的只读约束立刻失效（这正是第 1 段收口时实测到的状态）。
--   3. `REVOKE` 不在本文件里：撤销一个不存在的授权在 MySQL 会直接报错，
--      判断逻辑放在 sync 脚本里，本文件只做纯增量授权。
-- ══════════════════════════════════════════════════════════════════

-- ── 迁移 / 运维账号 ────────────────────────────────────────────────
-- CREATE USER IF NOT EXISTS + ALTER 两步，使得本文件可重复执行（幂等）
CREATE USER IF NOT EXISTS 'faze_migrate'@'localhost' IDENTIFIED BY '{{MIGRATE_PASSWORD}}';
ALTER USER 'faze_migrate'@'localhost' IDENTIFIED BY '{{MIGRATE_PASSWORD}}';

-- Flyway 需要：CREATE/ALTER/DROP/INDEX/REFERENCES 做结构迁移；
-- SELECT/INSERT/UPDATE/DELETE 读写 flyway_schema_history；
-- operation_log 的 DELETE 只在这个账号上（归档清理须人工审批，见规范 §5.3）
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX, REFERENCES
  ON `faze`.* TO 'faze_migrate'@'localhost';
