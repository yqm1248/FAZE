-- ============================================================================
-- 迁移：V301__alter_law_item.sql
-- 所属段：第 3 段（AI 法律顾问）
-- 做什么：给段 0 建的 law_item 补三个字段 + 一个索引。
--         只加列与索引，**不改任何已有列**（段 0 的 12 个原字段原样不动）。
-- 依赖：V1__baseline.sql（law_item 已存在；本迁移在已有数据的库上执行）。
-- 可回滚：本地库可 flyway clean 重建；已进 main 后不可改，只能追加 V301_1__fix_*.sql。
-- 编写：2026-11-07
-- 依据：
--   《第 3 段工作流》§三 A2（三个字段与索引逐字按该节）；
--   《数据表规范》§4.1（D 类表必须有 is_enabled）、§9.1（law_item 在「已确定要加
--   created_by + created_by_type」名单里）；
--   段 0 的 V1 逐字抄了《V1基线表结构》的 12 字段且明令「不许加字段」，所以只能由
--   本段追加（这就是为什么不是改 V1，而是新开 V301）。
--
-- 关键词索引裁决（§三 A2 第 3 条）：**不加 ft_law_keywords**。
--   keywords 是 VARCHAR(500) 的人工短词，ngram 分词对它没有意义（ngram 是为长文本
--   设计的）；段 3 数据量 5~50 条、上限 3000，keywords LIKE '%kw%' 的扫描成本可忽略。
--   替代方案是 B1 检索时把 keywords 当作「同义词扩展的输入」，不在 SQL 里全文检索它。
--
-- 版本号：按《数据表规范》§8.1，第 3 段用 V300~V399。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- law_item 补 3 字段：12 → 15
--   is_enabled      放 status 之后（语义相邻：效力状态 → 是否启用）
--   created_by(_type) 放 updated_at 之后（审计字段成组）
-- ---------------------------------------------------------------------------
ALTER TABLE law_item
  ADD COLUMN is_enabled      TINYINT     NOT NULL DEFAULT 1
    COMMENT '是否启用；停用而非删除（D 类表硬要求）' AFTER status,
  ADD COLUMN created_by      BIGINT               DEFAULT NULL
    COMMENT '录入人（管理员用户 id）；系统迁移为 NULL' AFTER updated_at,
  ADD COLUMN created_by_type VARCHAR(20)          DEFAULT NULL
    COMMENT '录入人类型 operator_type：admin / system；系统迁移为 system' AFTER created_by,
  ADD KEY idx_law_item_enabled (is_enabled, law_code);

-- 已有 5 条数据的 is_enabled 由 DEFAULT 1 自动回填，无需 UPDATE（是设计意图，不是漏写）。
-- created_by / created_by_type 对既有数据为 NULL——它们是「系统迁移进来」的，没有录入人。
