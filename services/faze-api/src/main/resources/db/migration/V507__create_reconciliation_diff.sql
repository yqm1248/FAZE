-- ============================================================================
-- 迁移：V507__create_reconciliation_diff.sql
-- 所属段：第 5 段（对账差错，为段 6 的渠道对账铺表）
-- 做什么：建 reconciliation_diff（14 字段）——对账差错工单。
-- 依赖：V200 的 orders / recharge_record 等（recharge_id 逻辑引用）。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.11 的 reconciliation_diff DDL 原文照抄；
--   《第 5 段工作流》§零之二 第 9 项与 §七 第 5 项：
--   **本段只建表 + 写入差异，不做页面、不改 A-09**（段 6 才在 A-09 上加「渠道对账」Tab）。
--
-- ⚠️ 本表在技术路线里**没有唯一键**（只有两个普通索引），段 6 的比对逻辑需要「先查后插」兜底。
--    本迁移按《第 5 段工作流》§三 A1 的建议补一条唯一键：
--    uk_diff_day_channel_txn_type (bill_date, channel, channel_txn_id, diff_type)
--    —— 若 channel_txn_id 为 NULL，MySQL 唯一索引允许多行 NULL，不影响「无渠道流水号」的差异行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS reconciliation_diff (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  bill_date      DATE         NOT NULL COMMENT '账单日期',
  channel        VARCHAR(20)  NOT NULL COMMENT '渠道 wechat / alipay / bank 等',
  diff_type      VARCHAR(30)  NOT NULL COMMENT '差异类型：平台有渠道无 / 渠道有平台无 / 金额不一致 等',
  channel_txn_id VARCHAR(64)           DEFAULT NULL COMMENT '渠道流水号；无则为 NULL',
  recharge_id    BIGINT                DEFAULT NULL COMMENT 'recharge_record.id（若差异关联充值单）',
  amount_cents   BIGINT                DEFAULT NULL COMMENT '差异金额（分），可正可负',
  detail         JSON                  DEFAULT NULL COMMENT '原始差异明细（用于人工核对）',
  status         VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / handled 已处理 / ignored 已忽略',
  handler_id     BIGINT                DEFAULT NULL COMMENT '处理人 app_user.id',
  handle_note    VARCHAR(500)          DEFAULT NULL COMMENT '处理说明',
  handled_at     DATETIME              DEFAULT NULL COMMENT '处理时间（UTC）',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at     DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_diff_day_channel_txn_type (bill_date, channel, channel_txn_id, diff_type),
  KEY idx_diff_date (bill_date, channel, status),
  KEY idx_diff_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='对账差错工单；段 5 只写不展示（段 6 在 A-09 加「渠道对账」Tab）';
