-- ============================================================================
-- V8 · 注销占位手机号需要更宽的 phone 列（第 1 段 B5）
--
-- 背景：B5 要求注销时把手机号改写成 `cancelled_<userId>_<原号>` 释放唯一键，
--   同时把原号留到 original_phone 供审计。按这个格式，长度 ≈ 10 + 19 + 1 + 11 = 41，
--   原列 VARCHAR(20) 装不下，会插入失败（或静默截断，那更糟）。
--
-- 为什么不用「del_<userId>」这种短格式：
--   短格式虽然能塞进 20 字符，但丢掉原号后，客服追溯「这个注销号原来是哪个手机号」
--   只能靠 original_phone 一个字段；两个字段都保留原号口径更稳。
--
-- 影响面：phone 只被唯一索引 uk_app_user_phone 与登录查询使用，加宽不影响索引选择性。
-- ============================================================================

ALTER TABLE app_user
  MODIFY COLUMN phone VARCHAR(40) NOT NULL COMMENT '手机号，登录账号，唯一；注销后改写为 cancelled_<userId>_<原号> 释放唯一键';

ALTER TABLE app_user
  MODIFY COLUMN original_phone VARCHAR(40) DEFAULT NULL COMMENT '注销前的原手机号；注销时 phone 改为占位值释放唯一键，此处留原值供审计';
