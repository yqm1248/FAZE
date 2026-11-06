# 段 3 · A1 三张 AI 表建表与 Flyway 迁移 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 A1（L212–L282）
> 迁移：`services/faze-api/src/main/resources/db/migration/V300__create_ai_tables.sql`
> 证据日期：2026-11-07
> 执行人：lead

---

## 一、迁移已应用（开发库）

```
SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

真实输出（截取与本段相关的尾部）：

```
installed_rank	version	description	success
...
13	200	seg2 tables	1
14	201	seg2 refund flow biz id	1
15	300	create ai tables	1
16	301	alter law item	1
```

✅ 本段记录 `success=1`。`V300` 于 `2026-11-07 00:20:24` 由应用启动时的 Flyway 自动执行
（证据：`flyway_schema_history.installed_on`）。

> 说明：`V302`/`V303` 写在 V300 之后，将在 A3/A4 收口时随下一次应用启动一并应用（见 A3-import.md / A4-glossary.md）。

---

## 二、字段数实测（要求 7 / 16 / 10）

```
SELECT table_name, COUNT(*) AS cols FROM information_schema.columns
WHERE table_schema='faze' AND table_name IN ('ai_conversation','ai_message','i18n_glossary','law_item')
GROUP BY table_name;
```

真实输出：

```
TABLE_NAME	cols
ai_conversation	7
ai_message	16
i18n_glossary	10
law_item	15
```

✅ `ai_conversation` = **7**、`ai_message` = **16**、`i18n_glossary` = **10**，与工作流 A1 的三张字段表逐字一致。
（`law_item` = 15 属 A2，见 `A2-law-item.md`。）

---

## 三、`COLLATE` 与 `updated_at`

```
SELECT table_name, table_collation FROM information_schema.tables
WHERE table_schema='faze' AND table_name IN ('ai_conversation','ai_message','i18n_glossary');
```

真实输出：

```
TABLE_NAME	TABLE_COLLATION
ai_conversation	utf8mb4_0900_ai_ci
ai_message	utf8mb4_0900_ai_ci
i18n_glossary	utf8mb4_0900_ai_ci
```

✅ 三张表均为 `utf8mb4_0900_ai_ci`（《数据表规范》§六）。

```
SELECT column_name FROM information_schema.columns
WHERE table_schema='faze' AND table_name='ai_message' AND column_name='updated_at';
```

真实输出：

```
COLUMN_NAME
updated_at
```

✅ `ai_message` 有 `updated_at`（C 类状态机表硬要求，工作流 §零.3 第 12 项）。

---

## 四、索引清单实测

```
SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols, non_unique
FROM information_schema.statistics
WHERE table_schema='faze' AND table_name IN ('ai_message','law_item') AND index_type IN ('BTREE','FULLTEXT')
GROUP BY table_name, index_name, non_unique ORDER BY table_name, index_name;
```

真实输出（`ai_message` 部分）：

```
INDEX_NAME	cols	NON_UNIQUE
idx_ai_message_conversation	conversation_id,id	1
idx_ai_message_user	user_id,created_at	1
PRIMARY	id	0
uk_ai_message_content_id	content_id	0
```

✅ `uk_ai_message_content_id (content_id)` 是**唯一索引**（`NON_UNIQUE=0`），E2 的内容编号唯一性靠它。
✅ `idx_ai_message_conversation (conversation_id, id)` 支撑「按会话取消息」。
✅ 额外的 `idx_ai_message_user (user_id, created_at)` 支撑 A-28「按用户 + 时间检索」，
   与工作流要求的索引不冲突（工作流只规定必须有的，不是白名单）。

---

## 五、清库重放（空库一次跑通，结果完全一致）

**方法**：新建空库 `faze_seg3_replay`（`utf8mb4_0900_ai_ci`），按版本号顺序对 18 个迁移逐个
`source`（V1 → V2 → V3…V12 → V200 → V201 → V300 → V301 → V302 → V303），全程 `exit=0`。

真实输出：

```
库已建 exit=0
OK   V1__baseline.sql
OK   V2__init_law_items.sql
OK   V3__account_auth.sql
OK   V4__lawyer.sql
OK   V5__wallet.sql
OK   V6__ops_config.sql
OK   V7__account_b_group.sql
OK   V8__cancel_phone_width.sql
OK   V9__realname_mask.sql
OK   V10__realname_cipher_width.sql
OK   V11__pay_password.sql
OK   V12__ops_config_value_text.sql
OK   V200__seg2_tables.sql
OK   V201__seg2_refund_flow_biz_id.sql
OK   V300__create_ai_tables.sql
OK   V301__alter_law_item.sql
OK   V302__import_law_items.sql
OK   V303__init_i18n_glossary.sql
```

重放库里再测一次字段数（与开发库完全一致）：

```
TABLE_NAME	cols
ai_conversation	7
ai_message	16
i18n_glossary	10
law_item	15
law_items        6      -- V2 的 5 条 + V302 的 1 条
glossary         51     -- V303
lang             n
zh-Hans          51     -- 只有中文，藏/维/哈行数为 0（A4 要求）
```

✅ 空库可重放，且结果与开发库一致。
✅ 重放库已 `DROP DATABASE faze_seg3_replay;`（`exit=0`），不留残余。

---

## 六、`git status` 干净度

迁移执行期间**没有手工改库**：三张表的全部结构与数据只来自 `V300`/`V301`（已提交的两个文件）与
本次新增的 `V302`/`V303`。开发库中不存在手工建的表或手工加的列
（核对方式：`information_schema.columns` 的字段数与迁移文件逐字对照，见第二节）。

---

## 七、工作流 A1「算做完」逐条对照

| 判据（工作流 L272–L276） | 结果 | 证据 |
|---|---|---|
| `flyway:migrate` 空库一次跑通，`success=1` | ✅ 通过 | §五 重放 18 个迁移全 `exit=0`；§一 开发库 `success=1` |
| 字段数实测 = 7 / 16 / 10 | ✅ 通过 | §二 |
| 三张表 `COLLATE` 均 `utf8mb4_0900_ai_ci`；`ai_message` 有 `updated_at` | ✅ 通过 | §三 |
| 清库重放一次迁移，结果完全一致；`git status` 干净 | ✅ 通过 | §五 / §六 |

---

## 八、遗留与说明

1. **`V302`/`V303` 尚未在开发库执行**（只写文件、已在重放库验证）。下一次应用启动时 Flyway 会
   自动执行；A3 与 A4 的验收会补上「开发库已应用」的真实输出。
2. 三张表**没有物理外键**（`user_id` / `conversation_id` 只是逻辑引用），符合《数据表规范》附录 C 第 10 条；
   迁移文件头注释已写明。
3. 本任务**没有**给 `ai_message` 建 `refs` 的多值索引（B4 明写「超 10 万行再建，本段不做」）。
