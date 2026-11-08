-- ============================================================================
-- 迁移：V706__seg7_ticket_type_id.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：给 service_ticket 补 `type_id`——指向「原表 id」（complaint.id / feedback.id）。
--
-- 为什么必须补（计划缺陷，不是锦上添花）：
--   裁决 23 原文：「用户提交投诉或反馈后，写 complaint / feedback 原表 + 同时在
--   service_ticket 生成一条工单（`category` = `complaint`/`feedback`，**`type_id` 指向原表 id**）。
--   A-21/A-22 只操作 service_ticket，详情页 join 原表拿正文与截图。」
--   但 A1 定稿的 service_ticket 13 个字段里**没有 type_id**（只有 ticket_no/user_id/
--   agent_id/conversation_id/category/title/priority/status/sla_due_at/closed_at/
--   created_at/updated_at）。没有这个字段，工单与投诉/反馈之间**无法关联**：
--     · A-22 详情页拿不到投诉正文与截图；
--     · C2 的「APP 投诉列表能看到回复」也无法定位到正确工单。
--   所以补这一列——这是把文档矛盾落实成可施工的最小改动，不是新增需求。
--
-- 索引：`idx_ticket_type (category, type_id)`——A-22 详情页按「类型 + 原表 id」反查工单。
-- ============================================================================

ALTER TABLE service_ticket
  ADD COLUMN type_id BIGINT DEFAULT NULL COMMENT '原表主键：category=complaint 时为 complaint.id，feedback 时为 feedback.id',
  ADD KEY idx_ticket_type (category, type_id);
