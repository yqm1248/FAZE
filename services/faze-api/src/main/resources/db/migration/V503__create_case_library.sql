-- ============================================================================
-- 迁移：V503__create_case_library.sql
-- 所属段：第 5 段（案例库）
-- 做什么：建 case_library（21 字段）——案例主表，**简体中文为源语言**，译文走 case_library_translation。
-- 依赖：无（案例不引用案件，避免把当事人信息带进公开库）。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.8 的 case_library DDL 原文照抄（21 字段，v1.30 已更新）；
--   《第 5 段工作流》§零之二 第 13 项（案件类型 5 类 vs 案例分类 3 类，两套独立）。
--
-- 两个编号必须分清：
--   case_no       = 平台案例编号 CL+yyyyMMdd+6位，对外唯一标识（App 展示）；
--   court_case_no = 法院案号，只供后台内部核对，**APP 不展示**（避免公开案件当事人可被检索）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS case_library (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  case_no           VARCHAR(50)  NOT NULL COMMENT '平台案例编号 CL+yyyyMMdd+6位，对外唯一标识',
  court_case_no     VARCHAR(64)           DEFAULT NULL COMMENT '法院案号，只供后台内部核对，APP 不展示',
  title             VARCHAR(200) NOT NULL COMMENT '案例标题（简体中文源语言）',
  court_name        VARCHAR(100)          DEFAULT NULL COMMENT '审理法院',
  case_type         VARCHAR(30)           DEFAULT NULL COMMENT '大类 3 值：civil 民事 / administrative 行政 / criminal 刑事（App 列表按它分组）',
  category          VARCHAR(30)           DEFAULT NULL COMMENT '细分 6 值：marriage/labor/contract/traffic/criminal/policy（PC 筛选用）',
  judgment_date     DATE                  DEFAULT NULL COMMENT '裁判日期',
  keywords          VARCHAR(500)          DEFAULT NULL COMMENT '关键词标签（逗号分隔）',
  summary           TEXT                  DEFAULT NULL COMMENT '案情简介',
  judgment          TEXT                  DEFAULT NULL COMMENT '裁判结果',
  insight           TEXT                  DEFAULT NULL COMMENT '案例启示',
  law_refs          JSON                  DEFAULT NULL COMMENT '关联法条：[{"lawName","articleNo","lawItemId"}]，条号须经 ArticleNoValidator 校验',
  source            VARCHAR(100)          DEFAULT NULL COMMENT '案例来源（脱敏后的出处说明）',
  view_count        INT          NOT NULL DEFAULT 0 COMMENT '浏览量',
  status            VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / published 已上线 / offline 已下架',
  published_at      DATETIME              DEFAULT NULL COMMENT '上线时间（UTC）',
  source_updated_at DATETIME     NOT NULL COMMENT '源内容最后更新时间（UTC）；译文过期告警依据（译文更早于它就提示过期）',
  created_at        DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at        DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at        DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；已上线案例不能直删，须先下架',
  PRIMARY KEY (id),
  UNIQUE KEY uk_case_library_no (case_no),
  KEY idx_caselib_type (case_type, status),
  KEY idx_caselib_pub (status, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='案例库主表，存简体中文源内容；译文见 case_library_translation；court_case_no 不对 App 展示';
