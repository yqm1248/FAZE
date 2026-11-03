-- ============================================================================
-- V6 · 平台配置与审计（第 1 段 A1）
--
-- 做什么：建 sys_config / operation_log 两张表。
-- 依赖：无（只看 V1 之后的库状态）。
-- 依据：《技术路线》§5.11.2（sys_config）、§5.11.5（operation_log，取后者，
--       §5.10 的同名 DDL 已作废）；《数据表规范》附录 A 第 40 项（sys_config D 类 9 字段）。
--
-- 字段数说明：sys_config 保持 **9 字段**，不加《数据表规范》§4.1 提到的 `is_enabled`。
--   理由：附录 A 第 40 项明确记「9 字段」，本段 A1 的验收判据是「字段数 = 附录 A」；
--   该表的停用语义由 `editable` + 读取方控制，属规范内部口径差，已记入第 1 段 §九 待回写。
--
-- 本段补充：无（两张表按原 DDL 照抄）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 平台参数（D 基础数据 / 配置表；运行期读多写少）
--    首批配置项由 V7 种子脚本写入，不在建表脚本里插数据。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  config_key   VARCHAR(100) NOT NULL COMMENT '配置键，点分命名，如 trade.platform_fee_rate',
  config_value VARCHAR(500) NOT NULL COMMENT '配置值（统一按字符串存，由读取方按 value_type 解析）',
  value_type   VARCHAR(20)  NOT NULL DEFAULT 'string' COMMENT 'string / int / long / decimal / bool / json',
  category     VARCHAR(30)  DEFAULT NULL COMMENT '分组：trade / ai / security / audit / sms',
  remark       VARCHAR(200) DEFAULT NULL COMMENT '配置说明（给 PC A-08 系统参数页展示）',
  editable     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许在 PC 页面修改：1 可改 0 只读',
  created_at   DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at   DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key),
  KEY idx_config_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='平台参数配置';

-- ---------------------------------------------------------------------------
-- 2. 敏感操作审计（B 追加型流水表：只有 created_at，只追加不可改删）
--    审计四要素：谁（operator_id + operator_type）/ 何时 / 从哪（ip）/
--    做了什么（module + action + target_type + target_id + detail）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS operation_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  operator_id   BIGINT       NOT NULL COMMENT '操作人 ID（管理员为 faze_admin.sys_user.id）',
  operator_type VARCHAR(20)  NOT NULL COMMENT 'admin / user / lawyer / system',
  module        VARCHAR(50)  NOT NULL COMMENT '业务模块：account / lawyer / trade / ops',
  action        VARCHAR(50)  NOT NULL COMMENT '动作码，如 MANUAL_RECHARGE / REALNAME_APPROVE',
  target_type   VARCHAR(30)  DEFAULT NULL COMMENT '操作对象类型：USER / LAWYER_APPLICATION / REALNAME_AUTH',
  target_id     BIGINT       DEFAULT NULL COMMENT '操作对象主键',
  detail        JSON         DEFAULT NULL COMMENT '变更明细，必须存 before / after',
  ip            VARCHAR(50)  DEFAULT NULL COMMENT '操作来源 IP',
  result        VARCHAR(20)  NOT NULL COMMENT 'success / fail',
  created_at    DATETIME     NOT NULL COMMENT '发生时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_oplog_operator (operator_id, created_at),
  KEY idx_oplog_module (module, action, created_at),
  KEY idx_oplog_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='敏感操作审计，仅追加';
