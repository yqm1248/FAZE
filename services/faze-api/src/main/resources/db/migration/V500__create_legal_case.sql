-- ============================================================================
-- 迁移：V500__create_legal_case.sql
-- 所属段：第 5 段（案件中心）
-- 做什么：建 legal_case（17 字段）——案件主表，`status` 为 5 态状态机。
-- 依赖：V1 的 app_user / V4 的 lawyer（逻辑引用，本库不建物理外键，见《数据表规范》附录 C 第 10 条）。
-- 可回滚：DROP TABLE legal_case（仅开发库，生产须先确认无数据）。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.7 的 legal_case DDL 原文照抄（17 字段，v1.30 已按段 5 裁决更新）；
--   《第 5 段工作流》§三 A1 与《数据表规范》附录 A 第 21 行；
--   《数据表规范》§四（C 状态机表公共字段）、§六（COLLATE utf8mb4_0900_ai_ci）、§七（字段注释）。
--
-- 本段裁决（见《第 5 段工作流》§零之二）：
--   ① 状态 5 值：filed / accepted / in_progress / closed / rejected，`waiting_judgment` 作废（第 12 项）；
--   ② 补 form_snapshot / court_case_no / reject_reason 三字段（第 11、14 项）；
--   ③ 单号 CS 由应用层生成（无分隔符，第 17 项），不靠 DB 自增回填。
-- ============================================================================

CREATE TABLE IF NOT EXISTS legal_case (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  case_no       VARCHAR(32)  NOT NULL COMMENT '平台案件单号 CS+yyyyMMdd+6位，对外唯一标识（无分隔符）',
  user_id       BIGINT       NOT NULL COMMENT '委托人 user_id（归属校验依据，禁止从请求体取）',
  lawyer_id     BIGINT                DEFAULT NULL COMMENT '承办律师 lawyer.id；未分配为 NULL',
  title         VARCHAR(200)          DEFAULT NULL COMMENT '案件标题（用户填写的简述）',
  case_type     VARCHAR(30)           DEFAULT NULL COMMENT '案件类型 5 类：civil/criminal/administrative/labor/family',
  description   TEXT                  DEFAULT NULL COMMENT '案情陈述（与 form_snapshot 不同：这里是摘要，快照是原始表单）',
  evidence_urls JSON                  DEFAULT NULL COMMENT '证据文件 file_record 的 key 数组',
  status        VARCHAR(20)  NOT NULL DEFAULT 'filed' COMMENT 'filed 待立案审核 / accepted 立案中 / in_progress 审理中 / closed 已结案 / rejected 已驳回',
  court_name    VARCHAR(100)          DEFAULT NULL COMMENT '管辖法院名称',
  court_case_no VARCHAR(64)           DEFAULT NULL COMMENT '法院案号（受理后由律师登记）；与平台单号 case_no 是两回事，APP 不展示',
  form_snapshot JSON                  DEFAULT NULL COMMENT '立案四步的原始表单快照（PC 立案审核逐字段看，生成后不随页面改动）',
  reject_reason VARCHAR(500)          DEFAULT NULL COMMENT '立案审核驳回理由（status=rejected 时必填）',
  amount_cents  BIGINT                DEFAULT NULL COMMENT '争议金额（分）；NULL 表示未填',
  created_at    DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at    DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at    DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；C 类表必须有，NULL 表示未删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_legal_case_no (case_no),
  KEY idx_legal_case_user (user_id),
  KEY idx_legal_case_lawyer (lawyer_id),
  KEY idx_legal_case_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='案件主表；状态 5 态由服务端状态机校验（客户端只发意图）；单号 CS 应用层生成';
