-- ============================================================================
-- 迁移：V505__create_withdraw_record.sql
-- 所属段：第 5 段（提现）
-- 做什么：建 withdraw_record（21 字段）——律师提现单。
-- 依赖：V4 的 lawyer、V5 的 balance_flow / lawyer_balance、V400 的 file_record（voucher_file_id）。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.5 的 withdraw_record DDL 原文照抄（v1.30 已补 6 字段）；
--   《第 5 段工作流》§零之二 第 4、5、15 项与 §三 F 组；
--   《数据表规范》§3.11（对外编号一律 xxx_no）。
--
-- 资金口径（写进注释，防重复打款）：
--   本表是**提现单**，只记状态；真正动钱是 balance_flow 的 freeze → withdraw（解冻失败走 unfreeze）。
--   最低提现 10 分、手续费 0.6%（floor 到分，不足 1 分记 0），均为 sys_config 可配项。
--   bank_account_tail 只存卡号后 4 位；完整卡号在 account_number 列，**任何接口都不返回完整卡号**。
-- ============================================================================

CREATE TABLE IF NOT EXISTS withdraw_record (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  withdraw_no       VARCHAR(32)  NOT NULL COMMENT '业务单号 WD+yyyyMMdd+6位，对外只暴露它（无分隔符）',
  lawyer_id         BIGINT       NOT NULL COMMENT 'lawyer.id（归属校验依据）',
  amount_cents      BIGINT       NOT NULL COMMENT '申请金额（分）',
  fee_cents         BIGINT       NOT NULL DEFAULT 0 COMMENT '提现手续费 = floor(申请金额 × 0.6%)；10 分时记 0',
  actual_cents      BIGINT       NOT NULL DEFAULT 0 COMMENT '实际到账 = 申请金额 − 手续费（分）',
  channel           VARCHAR(20)           DEFAULT NULL COMMENT 'wechat / alipay / bank / offline',
  bank_name         VARCHAR(50)           DEFAULT NULL COMMENT '收款银行名称（列表展示用）',
  account_name      VARCHAR(50)           DEFAULT NULL COMMENT '收款人姓名',
  account_number    VARCHAR(100)          DEFAULT NULL COMMENT '完整收款账号（加密或仅内部使用，任何接口不返回完整值）',
  bank_account_tail VARCHAR(4)            DEFAULT NULL COMMENT '卡号后 4 位，列表展示用',
  status            VARCHAR(20)  NOT NULL COMMENT 'applying 待审核 / approved 已通过 / rejected 已驳回 / paid 已出款',
  transaction_id    VARCHAR(64)           DEFAULT NULL COMMENT '渠道出款流水号',
  voucher_file_id   BIGINT                DEFAULT NULL COMMENT '出款凭证 file_record.id（biz_type=withdraw）',
  audited_by        BIGINT                DEFAULT NULL COMMENT '审核人 app_user.id（管理端）',
  audited_at        DATETIME              DEFAULT NULL COMMENT '审核时间（UTC）',
  paid_at           DATETIME              DEFAULT NULL COMMENT '出款完成时间（UTC）',
  reject_reason     VARCHAR(500)          DEFAULT NULL COMMENT '驳回理由（status=rejected 时必填）',
  created_at        DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at        DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at        DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_withdraw_no (withdraw_no),
  KEY idx_withdraw_lawyer (lawyer_id, created_at),
  KEY idx_withdraw_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='律师提现单；只记状态，动钱在 balance_flow（freeze/withdraw/unfreeze）；完整卡号不对任何接口返回';
