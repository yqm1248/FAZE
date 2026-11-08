-- ============================================================================
-- V10 · 身份证密文列加宽（第 1 段 C1 实测修正）
--
-- 现象：V9 之后提交实名报 `Data too long for column 'id_card_number'`。
-- 原因：id_card_number 是 VARCHAR(20)，按"存身份证号"设计；改成存 AES-256-GCM 密文后，
--   Base64(iv 12 字节 + 密文 + tag 16 字节) 约 60~70 字符，装不下。
--
-- 教训：**改字段语义（明文→密文）时，列宽也要跟着改**。
--   只改代码不改列宽，错误会推迟到第一次真实写入才暴露，而不是编译期。
--   这是 C1「身份证号必须加密」这条要求落地时最容易漏的一步。
-- ============================================================================

ALTER TABLE realname_auth
  MODIFY COLUMN id_card_number VARCHAR(255) NOT NULL
      COMMENT '身份证号密文（AES-256-GCM，Base64(iv+密文+tag)）；明文只在提交瞬间存在于内存';

ALTER TABLE realname_auth
  MODIFY COLUMN id_card_masked VARCHAR(20) DEFAULT NULL
      COMMENT '身份证号掩码（110***********1234），供列表/详情展示，禁止用于业务判断';
