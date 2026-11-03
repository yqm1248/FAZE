-- ============================================================================
-- V3 · 账户与认证（第 1 段 A1）
--
-- 做什么：建 realname_auth / enterprise_auth / user_device / login_log 四张表。
-- 依赖：V1__baseline.sql 的 app_user（user_id 逻辑引用，不建物理外键）。
-- 依据：《技术路线》§5.3（realname_auth / enterprise_auth）、§5.11.1（user_device）、
--       §5.11.5（login_log，取后者，§5.10 的同名 DDL 已作废）；
--       《数据表规范》§4.1 四类判定、§6.2 表级 COLLATE 必写、§6.3 DATETIME 存 UTC。
--
-- 本段补充（原 DDL 没有，见第 1 段工作流 A1「必须补的字段 / 约束」）：
--   realname_auth：补 KEY idx_realname_status (status, created_at)，服务 A-04 待审队列。
--
-- 编号说明：第 1 段工作流原文写 V2__account_auth.sql，但段 0 已占用 V1（baseline）
--   与 V2（init_law_items），同名版本号会让 Flyway 启动即报 duplicate version。
--   因此本段顺延为 V3~V6，内容与拆分口径不变。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 实名认证（A 业务实体表：created_at + updated_at）
--    L1 加密存储 id_card_number / real_name；影像是私有 OSS key，不是完整 URL。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS realname_auth (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT       NOT NULL COMMENT 'C 端用户 ID；一个用户只有一份实名记录',
  real_name       VARCHAR(50)  NOT NULL COMMENT '真实姓名（L1 加密存储，出参脱敏）',
  id_card_number  VARCHAR(20)  NOT NULL COMMENT '身份证号（L1 加密存储，出参脱敏）',
  id_card_front   VARCHAR(500) NOT NULL COMMENT '身份证正面 OSS key（私有桶，签名 URL 才能访问）',
  id_card_back    VARCHAR(500) NOT NULL COMMENT '身份证反面 OSS key（私有桶）',
  status          VARCHAR(20)  DEFAULT 'pending' COMMENT 'pending / approved / rejected',
  reject_reason   VARCHAR(500) DEFAULT NULL COMMENT '驳回原因；status=rejected 时必填',
  audited_by      BIGINT       DEFAULT NULL COMMENT '审核管理员 ID（faze_admin.sys_user.id）',
  audited_at      DATETIME     DEFAULT NULL COMMENT '审核时间（UTC）',
  created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at      DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_realname_user (user_id),
  KEY idx_realname_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='实名认证；L1 加密 + L2 脱敏，影像走私有 OSS';

-- ---------------------------------------------------------------------------
-- 2. 企业认证（A 业务实体表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS enterprise_auth (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT       NOT NULL COMMENT 'C 端用户 ID；一个用户只有一份企业认证',
  company_name    VARCHAR(100) NOT NULL COMMENT '企业名称',
  credit_code     VARCHAR(30)  NOT NULL COMMENT '统一社会信用代码',
  legal_person    VARCHAR(50)  NOT NULL COMMENT '法定代表人姓名',
  license_image   VARCHAR(500) NOT NULL COMMENT '营业执照 OSS key（私有桶）',
  contact_phone   VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
  status          VARCHAR(20)  DEFAULT 'pending' COMMENT 'pending / approved / rejected',
  reject_reason   VARCHAR(500) DEFAULT NULL COMMENT '驳回原因',
  audited_by      BIGINT       DEFAULT NULL COMMENT '审核管理员 ID（faze_admin.sys_user.id）',
  audited_at      DATETIME     DEFAULT NULL COMMENT '审核时间（UTC）',
  created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at      DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_enterprise_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='企业认证；营业执照走私有 OSS';

-- ---------------------------------------------------------------------------
-- 3. 用户设备与推送标识（A 业务实体表，带 deleted_at）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_device (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id        BIGINT       NOT NULL COMMENT 'C 端用户 ID',
  device_id      VARCHAR(128) NOT NULL COMMENT '客户端设备标识（与请求头 X-Device-Id 一致）',
  platform       VARCHAR(10)  NOT NULL COMMENT 'android / ios',
  push_cid       VARCHAR(128) DEFAULT NULL COMMENT '离线推送 cid',
  device_model   VARCHAR(100) DEFAULT NULL COMMENT '机型',
  os_version     VARCHAR(50)  DEFAULT NULL COMMENT '系统版本',
  app_version    VARCHAR(20)  DEFAULT NULL COMMENT 'App 版本号',
  last_active_at DATETIME     DEFAULT NULL COMMENT '最后活跃时间（UTC）',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at     DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  deleted_at     DATETIME     DEFAULT NULL COMMENT '软删除时间（UTC）；NULL 表示未删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_user_device (user_id, device_id),
  KEY idx_device_cid (push_cid),
  KEY idx_device_user (user_id, last_active_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户设备与推送标识';

-- ---------------------------------------------------------------------------
-- 4. 登录日志（B 追加型流水表：只有 created_at，不做软删）
--    证据链，只插入不修改不删除；留存 6 个月以上。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS login_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT       DEFAULT NULL COMMENT '登录成功的用户 ID；失败时为 NULL',
  account     VARCHAR(50)  NOT NULL COMMENT '登录账号（手机号，落库前脱敏）',
  login_type  VARCHAR(20)  NOT NULL COMMENT 'password / sms / refresh',
  result      VARCHAR(20)  NOT NULL COMMENT 'success / fail',
  fail_reason VARCHAR(100) DEFAULT NULL COMMENT '失败原因（不区分账号错还是密码错，防枚举）',
  ip          VARCHAR(50)  DEFAULT NULL COMMENT '客户端 IP',
  user_agent  VARCHAR(500) DEFAULT NULL COMMENT 'User-Agent',
  device_id   VARCHAR(128) DEFAULT NULL COMMENT '设备标识',
  created_at  DATETIME     NOT NULL COMMENT '发生时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_login_account (account, created_at),
  KEY idx_login_user (user_id, created_at),
  KEY idx_login_ip (ip, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='登录日志，仅追加，留存 6 个月以上';
