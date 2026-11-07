-- ============================================================================
-- 迁移：V501__create_case_progress.sql
-- 所属段：第 5 段（案件中心）
-- 做什么：建 case_progress（9 字段）——案件进度节点，**只追加、不修改不删除**。
-- 依赖：V500 的 legal_case。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.7 的 case_progress DDL 原文照抄；
--   《第 5 段工作流》§三 A2 第 3 条（**不建 case_status_log**，本表就是迁移日志）；
--   《数据表规范》§四（B 追加型流水表只有 created_at，无 updated_at / deleted_at）。
--
-- 唯一键 uk_progress_case_step (case_id, step)：
--   同一案件的 step 顺序不可乱；并发插入冲突即重试，天然幂等。
--   node_code 是枚举编码（submitted/review/accepted/supplement/hearing/judgment/effective/closed），
--   与 step 分开：step 管顺序，node_code 管语义（钉死「材料补正」不是一个新状态，而是一个节点）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS case_progress (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  case_id       BIGINT       NOT NULL COMMENT 'legal_case.id',
  step          INT          NOT NULL COMMENT '顺序号，从 1 起；同一案件唯一',
  node_code     VARCHAR(30)  NOT NULL COMMENT '节点编码 submitted/review/accepted/supplement/hearing/judgment/effective/closed',
  title         VARCHAR(100) NOT NULL COMMENT '节点标题（展示用，可由 node_code 取 i18n，但保留一列防历史文案随版本漂移）',
  description   TEXT                  DEFAULT NULL COMMENT '节点说明',
  operator_id   BIGINT                DEFAULT NULL COMMENT '操作人 app_user.id；系统自动节点为 NULL',
  operator_role VARCHAR(20)           DEFAULT NULL COMMENT '操作人角色 user/lawyer/admin/system',
  created_at    DATETIME     NOT NULL COMMENT '发生时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_progress_case_step (case_id, step),
  KEY idx_progress_case (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='案件进度节点，只追加；本表即案件状态迁移日志（不另建 case_status_log）';
