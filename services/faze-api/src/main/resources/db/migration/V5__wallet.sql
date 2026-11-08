-- ============================================================================
-- V5 · 资金内核（第 1 段 A1）
--
-- 做什么：建 user_balance / lawyer_balance / balance_flow 三张表。
-- 依赖：V1 的 app_user（owner_id 逻辑引用）。
-- 依据：《技术路线》§5.5（三张表的原始 DDL）。
--
-- 三条铁律（第 1 段 §零.4 / 锚点 1-A6）：
--   1. balance_flow 是唯一真相，user_balance.balance_cents 只是缓存；
--   2. 任何改余额的操作必须先插流水，再由流水推算缓存，禁止直接 UPDATE 余额字段；
--   3. uk_flow_biz 是幂等保障（同一业务同一类型只记一次），**不许删**。
--
-- 本段补充（原 DDL 没有，见第 1 段工作流 A1）：
--   user_balance：补 CHECK (balance_cents >= 0)，MySQL 8.0.16+ 真实生效；
--   balance_flow：补 KEY idx_flow_biz (biz_type, biz_id)，服务 PC A-07 按业务单号查流水。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 用户账户余额（A 业务实体表；缓存表，真相是 balance_flow）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_balance (
  id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id       BIGINT   NOT NULL COMMENT 'C 端用户 ID',
  balance_cents BIGINT   NOT NULL DEFAULT 0 COMMENT '可用余额（分）；只能由 balance_flow 推导',
  frozen_cents  BIGINT   NOT NULL DEFAULT 0 COMMENT '冻结余额（分），如进行中订单占用',
  version       INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at    DATETIME NOT NULL COMMENT '创建时间（UTC）',
  updated_at    DATETIME NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_balance_user (user_id),
  CONSTRAINT ck_user_balance_non_negative CHECK (balance_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户余额缓存；权威是 balance_flow，禁止业务代码直接加减';

-- ---------------------------------------------------------------------------
-- 2. 律师账户余额（A 业务实体表；可提现 / 冻结 / 累计三口径）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lawyer_balance (
  id                 BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  lawyer_id          BIGINT   NOT NULL COMMENT 'lawyer.id',
  available_cents    BIGINT   NOT NULL DEFAULT 0 COMMENT '可提现金额（分）',
  frozen_cents       BIGINT   NOT NULL DEFAULT 0 COMMENT '冻结中金额（分），收入满 7 天解冻',
  total_income_cents BIGINT   NOT NULL DEFAULT 0 COMMENT '累计收入（分，含已提现）',
  version            INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_at         DATETIME NOT NULL COMMENT '创建时间（UTC）',
  updated_at         DATETIME NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lawyer_balance_lawyer (lawyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='律师余额缓存；权威是 balance_flow';

-- ---------------------------------------------------------------------------
-- 3. 资金流水（B 追加型流水表：只有 created_at，是唯一真相）
--    用户与律师共用一张表，用 owner_type 区分。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS balance_flow (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  owner_type    VARCHAR(10)  NOT NULL COMMENT 'user / lawyer',
  owner_id      BIGINT       NOT NULL COMMENT 'user_id 或 lawyer_id（由 owner_type 决定）',
  biz_type      VARCHAR(20)  NOT NULL COMMENT 'recharge / consume / refund / income / withdraw / freeze / unfreeze / adjust',
  biz_id        BIGINT       DEFAULT NULL COMMENT '关联业务主键（订单/充值/提现/文书）；人工调账可为 NULL',
  delta_cents   BIGINT       NOT NULL COMMENT '变动金额（分）；正为入账，负为出账',
  balance_after BIGINT       NOT NULL COMMENT '变动后余额快照（分），用于对账',
  frozen_until  DATETIME     DEFAULT NULL COMMENT '冻结到期时间（UTC）；income / freeze 类型使用',
  remark        VARCHAR(200) DEFAULT NULL COMMENT '流水备注',
  created_at    DATETIME     NOT NULL COMMENT '发生时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_flow_biz (owner_type, owner_id, biz_type, biz_id),
  KEY idx_flow_owner_time (owner_type, owner_id, created_at),
  KEY idx_flow_frozen (frozen_until),
  KEY idx_flow_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='资金流水，唯一真相；只追加，不修改不删除';
