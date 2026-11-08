-- ============================================================
-- 文件：database/00-create-database.sql
-- 所属段：第 0 段（任务 A3）
-- 做什么：建业务库 faze + 应用账号 faze_app（**不进 Flyway**，Flyway 连库时库必须已存在）
-- 怎么执行：用 root（或有建库权账号）执行一次，输出存档到 docs/acceptance/seg0/
--   mysql -uroot -p --default-character-set=utf8mb4 -e "source E:/dshcode/FAZE/database/00-create-database.sql"
-- 注意：
--   1. 口令不落仓库：下面的 '<口令>' 执行时由人替换，或分两条语句执行（先建账号，再单独 ALTER USER 设口令）。
--   2. 不要用 root 跑应用：应用会用它的 CREATE/DROP/GRANT 权限跑迁移，一次写错 SQL 能清空整个实例。
--   3. 段 0 唯一允许建的库是 faze（RuoYi 的库不在此脚本内；库名守卫会拒绝 faze 以外的库名）。
--   4. operation_log 的「DB 账号无 UPDATE/DELETE」是段 1 的表级授权，段 0 不做（表还不存在）。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `faze`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 应用账号：只授权 faze.*，不授 GRANT OPTION、不授全局权限
CREATE USER IF NOT EXISTS 'faze_app'@'localhost' IDENTIFIED BY '<口令>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER,
      REFERENCES, CREATE VIEW, SHOW VIEW, EXECUTE, TRIGGER
  ON `faze`.* TO 'faze_app'@'localhost';
FLUSH PRIVILEGES;
