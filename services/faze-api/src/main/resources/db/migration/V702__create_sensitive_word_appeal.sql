-- ============================================================================
-- 迁移：V702__create_sensitive_word_appeal.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：建第 54 张表 sensitive_word_appeal——敏感词误判申诉。
--
-- 为什么需要这张表（冲突 2 的裁决 4）：
--   技术路线 §4.8.4.11 有 2 个申诉接口、A-25 稿画了「申诉复核」右栏、
--   §16.2.3 写了申诉入口，但 53 张表清单里从来没有这张表、全仓无 CREATE TABLE。
--   没有它，「用户认为被误判」这件事只能靠口头/工单，无法与具体命中记录关联。
--
-- 依据：《第 7 段工作流》§零之二 第 4 项（字段逐个给定）；《技术路线 v2.3》§5.11.3 末段。
--
-- 三个设计取舍：
--   1. **uk_appeal_review 让「一条命中只能申诉一次」由数据库兜底**——应用层
--      先查后插在并发下会双写，唯一键才是真的拦住（重复提交返回 410009）。
--   2. **content_review_id 指向上游命中记录**，不存原文：申诉的是「判定」，
--      不是「内容」。原文仍在业务表，不在本表复制。
--   3. **status 只有 pending/accepted/rejected 三态**：A-25 稿只有「通过申诉 /
--      维持原判」两个按钮，技术路线写的「降级」不实现（以稿为准）。
--      accepted → 对应 content_review.review_result 改 ignored，词条本身不动。
--
-- 时间字段：库内一律 UTC，DATETIME，由应用层写入。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sensitive_word_appeal (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id           BIGINT       NOT NULL COMMENT '申诉人 app_user.id（必须 = content_review.biz_user_id，IDOR 防护）',
  content_review_id BIGINT       NOT NULL COMMENT '被误判的命中记录 content_review.id',
  appeal_reason     VARCHAR(500) NOT NULL COMMENT '申诉理由（用户填写，≤500 字）',
  status            VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '状态机：pending 待复核 / accepted 通过申诉 / rejected 维持原判',
  handler_id        BIGINT                DEFAULT NULL COMMENT '复核管理员 app_user.id',
  handle_reason     VARCHAR(500)          DEFAULT NULL COMMENT '复核说明（给用户看）',
  handled_at        DATETIME              DEFAULT NULL COMMENT '复核时间（UTC）',
  created_at        DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at        DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_appeal_review (content_review_id),
  KEY idx_appeal_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='敏感词误判申诉；一条命中记录只能申诉一次';
