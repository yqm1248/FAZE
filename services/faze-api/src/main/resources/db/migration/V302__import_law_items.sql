-- ============================================================================
-- 迁移：V302__import_law_items.sql
-- 所属段：第 3 段（AI 法律顾问）
-- 做什么：导入法条基础数据（版本资产，不是种子）。本文件是**可复制的迁移模板**，
--         同时放入首批 1 条真实条文作为「模板跑通一次」的实证。
-- 依赖：V1__baseline.sql（law_item 表）、V301__alter_law_item.sql（is_enabled/created_by 三列）
-- 可回滚：本地库可 flyway clean；已进 main 后只能追加补丁迁移（不许改本文件）
-- ============================================================================
-- 写法要求（三条硬要求，细则见 docs/standards/法条数据录入规范.md）：
--   ① 逐字准确：条文必须从权威文本抄（国家法律法规数据库 / 官方公报），不许自己编、
--      不许改写、不许精简。effective_date 必填（它是唯一键 uk_law_article 的组成部分）。
--   ② article_no 与 article_seq 都要填：article_no 是人读的「第六百七十九条」，
--      article_seq 是整数 679（用于排序与范围查询）。
--   ③ law_code 用小写短码：civil_code / civil_procedure / labor_contract /
--      labor_dispute / consumer_protection / marriage_judicial_1。
--   → 靠 uk_law_article(law_code, article_no, effective_date) 去重，
--     所以用 INSERT ... ON DUPLICATE KEY UPDATE 保证可重复执行。
-- ----------------------------------------------------------------------------
-- 边界（负责人最关心的一条，务必不要越过）：
--   * 随版本发布的**首批法条** → 本迁移（Flyway，应用启动时自动执行）。
--   * **运营期新增法条**（负责人以后自己加 10 条、200 条）→ 走 PC 后台 A-27 的
--     「批量导入」接口（H1），或直接 SQL 插。**不要**为新数据发一个迁移：
--     每次加数据都发版不现实，而且 Flyway 迁移一旦执行不可回改。
--   * 演示/测试数据 → database/seed/，**不碰法条**。
-- ----------------------------------------------------------------------------
-- 本次内容：第 679 条（自然人之间的借款合同自贷款人提供借款时成立）。
--   选它的理由：段 0 的 V2 已灌 464 / 509 / 667 / 680 / 1079 五条，缺 679；
--   而 679 正好覆盖「自然人借款」这一最常见的咨询场景，能同时验证
--   「导入后立刻可被 ngram 检索到」与「条号精确命中」两条路径（3-A1 第 ③ 条）。
-- ============================================================================

INSERT INTO law_item
  (law_code, law_name, article_no, article_seq, content, effective_date, expire_date,
   status, keywords, is_enabled, created_by, created_by_type, created_at, updated_at)
VALUES
  ('civil_code', '中华人民共和国民法典', '第六百七十九条', 679,
   '自然人之间的借款合同，自贷款人提供借款时成立。',
   '2021-01-01', NULL,
   'active', '借款合同,自然人借款,借条,民间借贷,合同成立,交付', 1, NULL, 'system', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  law_name        = VALUES(law_name),
  article_seq     = VALUES(article_seq),
  content         = VALUES(content),
  status          = VALUES(status),
  keywords        = VALUES(keywords),
  is_enabled      = VALUES(is_enabled),
  updated_at      = VALUES(updated_at);
