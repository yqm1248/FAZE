-- ============================================================================
-- V4 · 律师与入驻（第 1 段 A1）
--
-- 做什么：建 lawyer / lawyer_application / lawyer_application_log 三张表。
-- 依赖：V1 的 app_user；lawyer.user_id 逻辑引用 app_user.id。
-- 依据：《技术路线》§5.3（lawyer / lawyer_application）、§5.11.1（lawyer_application_log）。
--
-- 本段补充（原 DDL 没有，见第 1 段工作流 A1）：
--   lawyer_application：补 KEY idx_app_status (status, created_at)，服务 A-05 待审队列。
--
-- 字段数与附录 A 的差异说明：
--   《数据表规范》附录 A 记 lawyer 为 23 字段，那是 v1.28 的计数；
--   《技术路线》v2.3 的 lawyer 已追加 6 个字段（4 个上架/接单开关 + 2 个提现收款字段），
--   现为 29 字段。此处**照抄技术路线 v2.3**，属于文档版本差，不是发明字段。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 律师（A 业务实体表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lawyer (
  id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id               BIGINT       NOT NULL COMMENT '关联 app_user.id；一个用户最多一名律师',
  real_name             VARCHAR(50)  NOT NULL COMMENT '执业姓名',
  license_number        VARCHAR(50)  NOT NULL COMMENT '律师执业证号',
  license_image         VARCHAR(500) DEFAULT NULL COMMENT '执业证影像 OSS key（私有桶）',
  law_firm              VARCHAR(100) DEFAULT NULL COMMENT '执业律所',
  practice_years        INT          DEFAULT NULL COMMENT '执业年限',
  region                VARCHAR(100) DEFAULT NULL COMMENT '执业地区',
  specialties           JSON         DEFAULT NULL COMMENT '擅长领域，如 ["婚姻家事","合同纠纷"]',
  bio                   TEXT         DEFAULT NULL COMMENT '个人简介',
  avatar_url            VARCHAR(500) DEFAULT NULL COMMENT '头像地址',
  consult_price_cents   BIGINT       DEFAULT 0 COMMENT '图文咨询价格（分）',
  phone_price_cents     BIGINT       DEFAULT 0 COMMENT '电话咨询价格（分）',
  document_price_cents  BIGINT       DEFAULT 0 COMMENT '文书代写价格（分）',
  service_text_on       TINYINT      NOT NULL DEFAULT 1 COMMENT '图文咨询上架开关：1 上架 0 下架（v1.30 段 5）',
  service_phone_on      TINYINT      NOT NULL DEFAULT 1 COMMENT '电话咨询上架开关：1 上架 0 下架（v1.30 段 5）',
  service_doc_on        TINYINT      NOT NULL DEFAULT 1 COMMENT '文书代写上架开关：1 上架 0 下架（v1.30 段 5）',
  auto_accept           TINYINT      NOT NULL DEFAULT 0 COMMENT '自动接单开关：1 开 0 关（v1.30 段 5）',
  rating                DECIMAL(2,1) DEFAULT 5.0 COMMENT '评分，0.0~5.0',
  review_count          INT          DEFAULT 0 COMMENT '评价数',
  order_count           INT          DEFAULT 0 COMMENT '累计接单数',
  online_status         VARCHAR(20)  DEFAULT 'offline' COMMENT 'online / busy / offline',
  audit_status          VARCHAR(20)  DEFAULT 'pending' COMMENT 'pending / approved / rejected',
  withdraw_bank_name    VARCHAR(50)  DEFAULT NULL COMMENT '提现收款开户行（v1.30 段 5）',
  withdraw_bank_account VARCHAR(100) DEFAULT NULL COMMENT '提现收款卡号（v1.30 段 5）；对外只返回尾 4 位',
  balance_cents         BIGINT       DEFAULT 0 COMMENT '⚠️ 冗余缓存，权威是 lawyer_balance；段 5 起禁止读写本字段',
  created_at            DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at            DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  deleted_at            DATETIME     DEFAULT NULL COMMENT '软删除时间（UTC）；NULL 表示未删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lawyer_user (user_id),
  UNIQUE KEY uk_lawyer_license (license_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='律师资料；余额权威在 lawyer_balance，balance_cents 仅历史缓存';

-- ---------------------------------------------------------------------------
-- 2. 律师入驻申请（A 业务实体表；四步流程，step 记录填到第几步）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lawyer_application (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT       NOT NULL COMMENT '申请人 app_user.id',
  step            INT          DEFAULT 1 COMMENT '当前填写步骤 1~4',
  form_data       JSON         DEFAULT NULL COMMENT '四步表单原始数据（完整快照）',
  real_name       VARCHAR(50)  DEFAULT NULL COMMENT '执业姓名',
  license_number  VARCHAR(50)  DEFAULT NULL COMMENT '律师执业证号',
  license_image   VARCHAR(500) DEFAULT NULL COMMENT '执业证影像 OSS key（私有桶）',
  law_firm        VARCHAR(100) DEFAULT NULL COMMENT '执业律所',
  specialties     JSON         DEFAULT NULL COMMENT '擅长领域',
  bio             TEXT         DEFAULT NULL COMMENT '个人简介',
  audit_status    VARCHAR(20)  DEFAULT 'pending' COMMENT 'pending / approved / rejected',
  reject_reason   VARCHAR(500) DEFAULT NULL COMMENT '驳回原因',
  audited_by      BIGINT       DEFAULT NULL COMMENT '审核管理员 ID（faze_admin.sys_user.id）',
  audited_at      DATETIME     DEFAULT NULL COMMENT '审核时间（UTC）',
  created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at      DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_application_user (user_id),
  KEY idx_app_status (audit_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='律师入驻申请；四步流程，驳回历史见 lawyer_application_log';

-- ---------------------------------------------------------------------------
-- 3. 入驻审核流水（B 追加型流水表：只有 created_at，只追加不修改）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lawyer_application_log (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  application_id BIGINT       NOT NULL COMMENT 'lawyer_application.id',
  action         VARCHAR(20)  NOT NULL COMMENT 'submit / approve / reject / resubmit / revoke',
  from_status    VARCHAR(20)  DEFAULT NULL COMMENT '变更前状态；首次提交为 NULL',
  to_status      VARCHAR(20)  NOT NULL COMMENT '变更后状态',
  operator_type  VARCHAR(20)  NOT NULL COMMENT 'user / admin / system',
  operator_id    BIGINT       DEFAULT NULL COMMENT '操作人 ID',
  reason         VARCHAR(500) DEFAULT NULL COMMENT '驳回原因 / 操作备注',
  snapshot       JSON         DEFAULT NULL COMMENT '操作时的申请快照，便于回溯',
  created_at     DATETIME     NOT NULL COMMENT '发生时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_applog_application (application_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='入驻审核流水，只追加不修改';
