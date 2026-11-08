-- ============================================================================
-- 迁移：V600__recharge_record.sql
-- 所属段：第 6 段（真实充值渠道 + 掉单补单）
-- 做什么：建 recharge_record（13 字段）——App 充值的渠道记录，是「钱从外部进来」
--         的唯一凭据。充值状态机 4 态 6 迁移（pending → success / failed / closed）。
-- 依赖：V200 段 1 的 balance_flow / user_balance（入账时写，本表不引用外键）。
-- 编写：2026-11-08
-- 依据：
--   《技术路线 v2.3》L1452-1471 的 recharge_record DDL 原文（已含 v1.32 段 6 补的
--   `channel_prepay_id` / `expire_at` 两个字段）；
--   《第 6 段工作流》§三 A 组 A1（13 字段 + 4 个索引，不许建 payment_channel）；
--   《数据表规范》§三/§四（表名、字段名、索引名、COLLATE、公共字段、12 问清单）。
--
-- 三个设计取舍（写在这里防后人「优化」）：
--   1. **不建 deleted_at**：充值单是财务凭证，只追加状态机、永不删除。
--      软删会让「用户付了钱但单没了」成为可能，对账时无法自证。
--   2. **uk_recharge_channel_txn 是回调幂等的数据库兜底**（6-A2 的硬依赖）：
--      同一渠道交易号只能入账一次。注意 MySQL 唯一索引允许多行 NULL——
--      pending 单的 channel_txn_id 一直为 NULL（回调到了才回填），所以不冲突。
--      原单关单时**不许**回填 channel_txn_id（见工作流 A2 第 4 条：晚到回调要新开补偿单）。
--   3. **expire_at 落库而不是现算**：超时时长可配（recharge.expire_minutes），
--      写死在 SQL 里改一次就得发一次版；关单任务按 (status, expire_at) 扫描。
--
-- channel 取值：wechat / alipay / mock（mock 是段 6 的测试通道，生产 pay.mock.enabled=false）。
-- 时间字段：库内一律 UTC，由应用层写入（DATETIME，不用 DB 默认值）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS recharge_record (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id           BIGINT       NOT NULL COMMENT '充值用户 app_user.id',
  order_no          VARCHAR(32)  NOT NULL COMMENT '平台充值单号，RC + yyyyMMdd + 6 位序号',
  channel           VARCHAR(20)  NOT NULL COMMENT '支付渠道：wechat / alipay / mock',
  channel_txn_id    VARCHAR(64)           DEFAULT NULL COMMENT '渠道交易号；回调验签通过后才回填，pending 时为 NULL',
  channel_prepay_id VARCHAR(64)           DEFAULT NULL COMMENT '渠道预支付单号：微信 prepay_id / 支付宝 trade_no；回调对不上时反查用',
  amount_cents      BIGINT       NOT NULL COMMENT '充值金额（分），整数，禁止浮点',
  status            VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '状态机：pending 待支付 / success 成功 / failed 渠道失败 / closed 超时关闭；三个终态不可再迁',
  paid_at           DATETIME              DEFAULT NULL COMMENT '渠道支付成功时间（UTC）；未成功为 NULL',
  expire_at         DATETIME     NOT NULL COMMENT '充值单过期时间（UTC）= 创建时间 + recharge.expire_minutes；超时关单任务按 (status, expire_at) 扫描',
  raw_payload       TEXT                  DEFAULT NULL COMMENT '渠道原始报文（已脱敏：去掉 openid / 卡号尾号等 L4/L5 字段）；只落库，禁止进日志',
  created_at        DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at        DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recharge_order_no (order_no),
  UNIQUE KEY uk_recharge_channel_txn (channel, channel_txn_id),
  KEY idx_recharge_user (user_id, created_at),
  KEY idx_recharge_status_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='充值记录（段 6）。财务凭证，只追加状态机、不软删、不物理删；渠道信息全部落本表，不写 payment_record';
