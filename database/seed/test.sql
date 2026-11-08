-- ============================================================
-- 文件：database/seed/test.sql
-- 所属段：第 0 段（0-A2 的可登录测试用户）
-- 做什么：插入 1 个可登录的测试用户（**不进 Flyway**；换机器要手工执行一次）
-- 怎么执行：mysql -ufaze_app -p faze -e "source E:/dshcode/FAZE/database/seed/test.sql"
-- 注意：
--   1. 按负责人 2026-11-02 拍板：V2 只放 5 条法条，测试用户放本 seed 文件（数据表规范 §8.4 的「测试数据不进 Flyway」）。
--   2. 口令明文为 123456，password_hash 是 BCrypt（cost=10），**只用于本地验收**，上线前删号。
--   3. created_at / updated_at 按约定存 UTC。
-- ============================================================

INSERT INTO app_user
  (phone, password_hash, nickname, role, lang, realname_status, is_minor, status, created_at, updated_at)
VALUES
  ('13800000001',
   '$2b$10$NmCEkZCBOYi6siga2FzvJ.dOPHTryNH3/295iNfS5Bx/RZodoGuKy',
   '测试用户', 'user', 'zh-Hans', 'none', 0, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  password_hash = VALUES(password_hash),
  nickname = VALUES(nickname),
  status = VALUES(status),
  updated_at = UTC_TIMESTAMP();
