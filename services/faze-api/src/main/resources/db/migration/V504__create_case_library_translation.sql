-- ============================================================================
-- 迁移：V504__create_case_library_translation.sql
-- 所属段：第 5 段（案例库）
-- 做什么：建 case_library_translation（13 字段）——案例译文表。
-- 依赖：V503 的 case_library。
-- 编写：2026-11-08
-- 依据：
--   技术路线 §5.8 的 case_library_translation DDL 原文照抄；
--   《第 5 段工作流》§零之二 第 6 项（案例上线门禁：源语言齐全 + 缺译登记，4/4 属段 8）；
--   《i18n 规范》§4.3（case namespace 四语言目录必须同构）。
--
-- 为什么译文单独一张表（不是主表加 lang 列）：
--   行内 lang 会让同一案例变成 4 条互不相干的记录，无法关联、无法做过期告警；
--   本表 (case_id, lang) 唯一，主表 status 决定可见性，译文是否过期看 translated_from 与主表 source_updated_at 的先后。
-- ============================================================================

CREATE TABLE IF NOT EXISTS case_library_translation (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  case_id            BIGINT       NOT NULL COMMENT 'case_library.id',
  lang               VARCHAR(20)  NOT NULL COMMENT '语言：zh-Hans / ug-Arab-CN / bo / kk-Arab-CN（源语言 zh-Hans 存主表，本表只存译文）',
  title              VARCHAR(200)          DEFAULT NULL COMMENT '译文标题',
  summary            TEXT                  DEFAULT NULL COMMENT '译文案情简介',
  judgment           TEXT                  DEFAULT NULL COMMENT '译文裁判结果',
  insight            TEXT                  DEFAULT NULL COMMENT '译文案例启示',
  status             VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / reviewed 已校对 / published 已发布',
  translator         VARCHAR(100)          DEFAULT NULL COMMENT '译者（人工校对时填）',
  translation_engine VARCHAR(50)           DEFAULT NULL COMMENT '翻译引擎标识（机翻时填，便于区分人工/机翻）',
  translated_from    DATETIME              DEFAULT NULL COMMENT '译文对应的源内容时间点（UTC）；早于主表 source_updated_at 即过期',
  created_at         DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at         DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_case_trans (case_id, lang),
  KEY idx_case_trans_lang (lang, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='案例译文；与主表 case_library 是 1:N（每条案例每种语言至多一条译文）';
