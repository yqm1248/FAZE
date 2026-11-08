-- ============================================================================
-- V7 · 账号内核所需字段（第 1 段 B 组）
--
-- 做什么：
--   1. app_user.token_version —— 凭证版本号，改密 / 换绑 / 注销时 +1，旧 token 立即失效。
--      B3 的风险出口明确要求「用 token_version 而不是黑名单」：黑名单会无限增长，
--      而版本号只是用户表上的一个整数，校验时比对 token 里的 ver 即可。
--   2. app_user.original_phone —— 注销时把 phone 改写成占位值以释放原号，
--      原号留在这里供审计与客服追溯（《数据表规范》§4.3 的「改写被删记录的键值」方案）。
--
-- 依赖：V1__baseline.sql 的 app_user。
-- 注意：ALTER 不可逆。回滚需要手工 DROP COLUMN，见本文件末尾的注释。
-- ============================================================================

ALTER TABLE app_user
  ADD COLUMN token_version INT NOT NULL DEFAULT 0
      COMMENT '凭证版本号；改密/换绑/注销时 +1，旧 token 立即失效' AFTER status,
  ADD COLUMN original_phone VARCHAR(20) DEFAULT NULL
      COMMENT '注销前的原手机号；注销时 phone 改为占位值释放唯一键，此处留原值供审计' AFTER phone;

-- 回滚（仅手工，Flyway 不做 undo）：
-- ALTER TABLE app_user DROP COLUMN token_version, DROP COLUMN original_phone;
