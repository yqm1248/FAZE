# 段 3 · A2 `law_item` 补三字段 + 关键词索引裁决 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 A2（L285–L317）
> 迁移：`services/faze-api/src/main/resources/db/migration/V301__alter_law_item.sql`
> 证据日期：2026-11-07
> 执行人：lead

---

## 一、补字段后的实际形态（15 字段）

```
SELECT table_name, COUNT(*) AS cols FROM information_schema.columns
WHERE table_schema='faze' AND table_name='law_item' GROUP BY table_name;
```

真实输出：

```
TABLE_NAME	cols
law_item	15
```

✅ **12 → 15**，正好 +3。

三个新列的定义与注释实测：

```
SELECT column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema='faze' AND table_name='law_item'
  AND column_name IN ('is_enabled','created_by','created_by_type');
```

真实输出（在重放库 `faze_seg3_replay` 上采集，与开发库一致）：

```
COLUMN_NAME	COLUMN_TYPE	IS_NULLABLE	COLUMN_DEFAULT	COLUMN_COMMENT
is_enabled	tinyint	NO	1	是否启用；停用而非删除（D 类表硬要求）
created_by	bigint	YES	NULL	录入人（管理员用户 id）；系统迁移为 NULL
created_by_type	varchar(20)	YES	NULL	录入人类型 operator_type：admin / system；系统迁移为 system
```

✅ `is_enabled` 是 `NOT NULL DEFAULT 1`（停用而非删除，D 类表硬要求）。
✅ `created_by` / `created_by_type` 可为 NULL（系统迁移写入的行为 NULL）。
✅ 原有 12 个字段**一个都没改**（`V301` 只做 `ADD COLUMN` + `ADD KEY`）。

---

## 二、已有数据没有被置 NULL

工作流 A2 判据：已有 5 条数据 `is_enabled` 自动为 1。

在重放库（先跑 V1+V2 造出 5 条数据，再跑 V301）上验证：

```
SELECT id, article_no, is_enabled, created_by, created_by_type FROM faze_seg3_replay.law_item ORDER BY id;
```

真实输出：

```
id	article_no	is_enabled	created_by	created_by_type
1	第四百六十四条	1	NULL	NULL
2	第五百零九条	1	NULL	NULL
3	第六百六十七条	1	NULL	NULL
4	第六百八十条	1	NULL	NULL
5	第一千零七十九条	1	NULL	NULL
6	第六百七十九条	1	NULL	system
```

✅ V2 灌的 5 条全部 `is_enabled=1`，没有 NULL。
✅ 第 6 条是 V302 新导入的（`created_by_type='system'`），说明新数据也能正确落进三列。

> **注意**：这次验证是「先有数据再 ALTER」，符合 A2「迁移在已有数据的库上执行成功（不是只在空库试）」。
> 开发库同样是先有 5 条数据、后跑 V301（`flyway_schema_history` 中 V2 早于 V301）。

---

## 三、索引裁决：`keywords` 上**没有** FULLTEXT 索引

```
SELECT COUNT(*) AS ft_on_keywords FROM information_schema.statistics
WHERE table_schema='faze' AND table_name='law_item'
  AND index_type='FULLTEXT' AND column_name='keywords';
```

真实输出：

```
ft_on_keywords
0
```

✅ 没有新建 `ft_law_keywords`。

`law_item` 的完整索引清单实测：

```
INDEX_NAME	cols	NON_UNIQUE
ft_law_content	content	1
idx_law_code_seq	law_code,article_seq	1
idx_law_item_enabled	is_enabled,law_code	1
PRIMARY	id	0
uk_law_article	law_code,article_no,effective_date	0
```

✅ `ft_law_content` 只有 **content** 一列（段 0 建的，未动）。
✅ 新增 `idx_law_item_enabled (is_enabled, law_code)` 已生效。
✅ 唯一键 `uk_law_article (law_code, article_no, effective_date)` 未动（A3 的 `ON DUPLICATE KEY UPDATE` 靠它去重）。

---

## 四、为什么裁决「不加 `ft_law_keywords`」

（照工作流 A2 第 3 条，写进迁移注释与本文档，避免以后有人「顺手加上」）

1. `keywords` 是 `VARCHAR(500)` 的**人工维护短词**（逗号分隔的 2~4 字词）。ngram 分词是为长文本设计的，
   对「借款,利息,高利贷」这种短词列表切 2-gram 得到的 token 几乎不可能是用户输入的子串，
   索引建了也不会被用上。
2. 本段数据规模 5~50 条，未来上限 3000 条。`keywords LIKE '%kw%'` 的全表扫描成本可忽略
   （3000 行 × 500 字节 ≈ 1.5MB，内存里扫一遍是微秒级）。
3. **替代方案已实现**：B1 检索的第 4 条路径把 `keywords` 当「同义词扩展的输入」——
   拿用户问题去和 `keywords` 的逗号分隔列表做匹配，命中的 `law_code + article_no` 追加进结果。
   这样既不建索引，也不损失同义词能力，而且 `hitReason='keywords'` 能说清是哪条路命中的（`3-A1` 要用）。

---

## 五、工作流 A2「算做完」逐条对照

| 判据（工作流 L308–L312） | 结果 | 证据 |
|---|---|---|
| `law_item` 字段数 12 → 15；含三个新列 | ✅ 通过 | §一 |
| 已有 5 条数据 `is_enabled` 自动为 1，没有被置 NULL | ✅ 通过 | §二 |
| `keywords` 上没有新增 FULLTEXT 索引 | ✅ 通过 | §三 |
| 迁移在已有数据的库上执行成功 | ✅ 通过 | 开发库 V2 早于 V301；重放库 §二 也是先有数据后 ALTER |

---

## 六、遗留与说明

1. `V301` 不改 `status` 的三值（`active` / `amended` / `repealed`）——「待校对/复核状态」不进 DDL
   （工作流 §零.3 第 8 项，负责人 2026-11-01 拍板「去字段留机制」）。
2. `idx_law_item_enabled (is_enabled, law_code)` 的列序是刻意的：检索与存在性校验都先过滤
   `is_enabled=1`，再按 `law_code` 收窄；反过来放（`law_code, is_enabled`）在「按条号查」时会退化成
   前缀等值 + 回表过滤，条数一大就慢。
3. `law_item` 的 15 字段里 `created_by_type` 注释用 `operator_type：admin / system`，
   与 `operation_log.operator_type` 的取值口径一致（`admin` / `user` / `lawyer` / `system`）。
