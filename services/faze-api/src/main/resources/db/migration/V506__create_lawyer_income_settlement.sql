-- ============================================================================
-- 迁移：V506__create_lawyer_income_settlement.sql
-- 所属段：第 5 段（结算）
-- 做什么：建 lawyer_income_settlement（14 字段）——律师结算批次，**一位律师 × 一个账期一条**。
-- 依赖：V4 的 lawyer、V200 的 orders、V5 的 balance_flow（只读，不写）。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.5 的 lawyer_income_settlement DDL 原文照抄（v1.30 定稿）；
--   《第 5 段工作流》§零之二 第 1 项与 §三 E 组、§四 5-A4。
--
-- ⚠️ 本表**不是资金真相**，只是结算批次状态记录：
--   删掉本表任意一行，律师的钱一分不少；真相永远是 balance_flow。
--   confirm 接口**只改 status**，不转账、不改 lawyer_balance、不写 balance_flow——
--   这是「结算与提现重复打款」的唯一防线（A-13 与 A-10 不能变成两条打款链路）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS lawyer_income_settlement (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  settlement_no VARCHAR(32)  NOT NULL COMMENT '结算单号 JS+yyyyMMdd+6位（无分隔符）',
  lawyer_id     BIGINT       NOT NULL COMMENT 'lawyer.id',
  period        VARCHAR(7)   NOT NULL COMMENT '账期 yyyy-MM',
  order_count   INT          NOT NULL DEFAULT 0 COMMENT '该账期完成订单数（来自 orders）',
  income_cents  BIGINT       NOT NULL DEFAULT 0 COMMENT '订单收入合计（来自 orders.amount_cents）',
  fee_cents     BIGINT       NOT NULL DEFAULT 0 COMMENT '平台服务费合计（来自 orders.platform_fee_cents）',
  settle_cents  BIGINT       NOT NULL DEFAULT 0 COMMENT '应结金额 = income 净额 − fee（来自 balance_flow 的 income 流水净额，不是重算订单）',
  status        VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待结算 / settled 已结算 / rejected 已驳回',
  settled_by    BIGINT                DEFAULT NULL COMMENT '确认人 app_user.id（管理端）',
  settled_at    DATETIME              DEFAULT NULL COMMENT '确认时间（UTC）',
  remark        VARCHAR(500)          DEFAULT NULL COMMENT '备注（驳回理由或人工说明）',
  created_at    DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at    DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_no (settlement_no),
  UNIQUE KEY uk_settlement_lawyer_period (lawyer_id, period),
  KEY idx_settlement_status (status, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='结算批次状态记录，非资金真相；确认只改状态，不动余额、不写流水';
