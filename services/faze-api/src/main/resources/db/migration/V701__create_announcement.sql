-- ============================================================================
-- 迁移：V701__create_announcement.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：建第 55 张表 announcement——公告批次主表。
--
-- 为什么需要这张表（冲突 3 的裁决 5）：
--   A-23 稿表头有「触达」「打开率」，而 notification 是「每人一行」的通知，
--   没有「公告批次」概念——一条公告发给 1000 人就是 1000 行 notification，
--   「这次公告发了多少人、多少人点开」无处落。所以必须有批次主表。
--
-- 依据：《技术路线 v2.3》§5.11.4（v1.34 补登记）；《第 7 段工作流》§零之二 第 5 项。
--
-- 四个设计取舍：
--   1. **sent 不可回退**（裁决 26、A-23 稿「公告发出后不可撤回」）：
--      状态机 draft → scheduled/sent，scheduled → sending → sent/failed。
--      撤回请求返回 410013，不改 status。
--   2. **reach_count / open_count 是计数器不是明细**：明细在 notification
--      （biz_type='announcement', biz_id=本表 id），这里只累计，便于列表直接展示。
--   3. **channels 用 JSON**：站内信必选、推送/短信可选，组合固定但顺序无意义，
--      单独拆三列会引入三个布尔字段且未来加渠道要改表。
--   4. **target_value 用 JSON**：四种目标人群（all/role/region/user_ids）形状不同，
--      拆列会让一半字段恒为 NULL。
--
-- 时间字段：库内一律 UTC，DATETIME，由应用层写入。
-- ============================================================================

CREATE TABLE IF NOT EXISTS announcement (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  announcement_no VARCHAR(32)  NOT NULL COMMENT '公告编号：AN + yyyyMMdd + 6 位序号',
  title           VARCHAR(200) NOT NULL COMMENT '公告标题',
  content         TEXT         NOT NULL COMMENT '公告正文（纯文本，换行保留；不做富文本）',
  target_type     VARCHAR(20)  NOT NULL DEFAULT 'all' COMMENT '目标人群类型：all 全量 / role 按角色 / region 按地区 / user_ids 指定用户',
  target_value    JSON                  DEFAULT NULL COMMENT '目标人群参数：role={"role":"lawyer"}；region={"regions":["伊宁市"]}；user_ids={"userIds":[1,2]}；all 为 NULL',
  channels        JSON         NOT NULL COMMENT '发送渠道数组：["inapp"] 必选，可选 "push" / "sms"，如 ["inapp","push"]',
  status          VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT '状态机：draft 草稿 / scheduled 定时待发 / sending 发送中 / sent 已发送（不可回退） / failed 部分失败可重试',
  scheduled_at    DATETIME              DEFAULT NULL COMMENT '定时发送时间（UTC）；立即发送为 NULL',
  sent_at         DATETIME              DEFAULT NULL COMMENT '实际发送完成时间（UTC）',
  reach_count     INT          NOT NULL DEFAULT 0 COMMENT '触达数：实际写入 notification 的条数（分批累加）',
  open_count      INT          NOT NULL DEFAULT 0 COMMENT '打开数：用户首次点开时 +1（D3 的 read 接口回写）',
  created_by      BIGINT       NOT NULL COMMENT '创建管理员 app_user.id',
  created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at      DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_announcement_no (announcement_no),
  KEY idx_announcement_status (status, scheduled_at),
  KEY idx_announcement_sent (sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='公告批次主表；一条公告一行，触达/打开数在此累计';
