-- ============================================================================
-- 回滚：V400_1__drop_document_tables.sql
-- 所属段：第 4 段（文书中心）
-- 做什么：删掉 V400 建的四张表。**手动执行**，不自动跑。
-- 依赖：无
-- 可回滚：不适用（本文件就是回滚）
-- 编写：2026-11-07
--
-- ⚠️ **为什么放在 db/rollback/ 而不是 db/migration/**
--    工作流 §三 A1 第 4 条把回滚脚本写成 `V400_1__drop_document_tables.sql`，并说「手动执行，
--    **不进 flyway_schema_history**」。但 Flyway 的 locations 就是 `classpath:db/migration`，
--    文件名以 `V400_1__` 开头、放在同一目录，**应用一重启就会自动执行 DROP**——
--    这与「手动执行」的意图正好相反，是本段最容易造成数据丢失的一处。
--    所以本文件放在 `db/rollback/`（不在 Flyway 扫描路径内），必须手动执行。
--
-- 执行方式（在仓库根目录）：
--   Get-Content services\faze-api\src\main\resources\db\rollback\V400_1__drop_document_tables.sql |
--     & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --host=127.0.0.1 --user=<user> --password=<pwd> faze
--
-- ⚠️ 删表前先确认：`document` 里有没有 `status='generated'` 的行。有就说明已有真实文书产物，
--    删表会让 pdf_oss_key 指向的对象变成孤儿（对象本身还在磁盘上，但再没有记录指向它）。
-- ============================================================================

-- 顺序按「子 → 父」：signature 引用 document，document 引用 doc_template。
-- 本库没有物理外键，顺序不影响执行，但保持这个顺序便于人工核对。
DROP TABLE IF EXISTS signature;
DROP TABLE IF EXISTS document;
DROP TABLE IF EXISTS doc_template;
DROP TABLE IF EXISTS file_record;
