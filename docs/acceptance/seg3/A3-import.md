# 段 3 · A3 法条导入通道与录入规范 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 A3（L320–L367）
> 产出：`V302__import_law_items.sql`（迁移模板 + 首批 1 条）、`docs/standards/法条数据录入规范.md`、本文档
> 证据日期：2026-11-07
> 执行人：lead

---

## 一、模板迁移做了什么

`V302__import_law_items.sql` 同时是两样东西：

1. **首批法条载体**：本次放 1 条真实条文——民法典第六百七十九条
   （「自然人之间的借款合同，自贷款人提供借款时成立。」）。
   选它的理由：V2 已灌 464/509/667/680/1079 五条，缺 679；而 679 正好覆盖「自然人借款」这一最常见咨询场景，
   能同时验证「导入后立刻可被 ngram 检索到」与「条号精确命中」两条路径（`3-A1` 第 ③ 条要用）。
2. **可复制的模板**：文件头写了三条硬要求（逐字准确 / 两个条号字段都填 / `law_code` 用短码）
   与「两条路的边界」（首批走 Flyway、运营期走 A-27 批量导入或直接 SQL），
   以后负责人要加条文，照抄本文件改 VALUES 即可。

关键写法：`INSERT ... ON DUPLICATE KEY UPDATE`，靠 `uk_law_article (law_code, article_no, effective_date)` 去重。

---

## 二、空库执行成功，`COUNT(*)` 与插入条数一致

在空库 `faze_seg3_replay` 上按版本顺序跑到 V301 后执行 V302：

```
source .../V302__import_law_items.sql;
```

真实输出：`exit=0`（无报错）。

执行前后条数：

```
SELECT COUNT(*) AS law_items FROM faze_seg3_replay.law_item;
```

真实输出（执行 V302 后）：

```
law_items
6
```

✅ V2 的 5 条 + V302 的 1 条 = **6**，与插入条数一致。

导入的那一行逐列核对：

```
SELECT id, article_no, is_enabled, created_by, created_by_type FROM faze_seg3_replay.law_item ORDER BY id;
```

```
6	第六百七十九条	1	NULL	system
```

✅ `is_enabled=1`、`created_by=NULL`（系统迁移）、`created_by_type='system'`——与 A3 模板注释的约定一致。

---

## 三、重复执行不报错、不重复插入

连续第二次执行 V302：

```
source .../V302__import_law_items.sql;     →  exit=0
SELECT COUNT(*) AS law_items FROM faze_seg3_replay.law_item;
```

真实输出：

```
law_items
6
```

✅ 仍是 6 行，`ON DUPLICATE KEY UPDATE` 生效（第二次是 UPDATE，不是 INSERT）。

---

## 四、故意改一列再执行 → 该列被更新、`article_no` 未变

```
UPDATE faze_seg3_replay.law_item SET content='被手工改过的内容' WHERE law_code='civil_code' AND article_seq=679;
source .../V302__import_law_items.sql;
SELECT article_no, LEFT(content,20) AS content_head FROM faze_seg3_replay.law_item WHERE article_seq=679;
```

真实输出：

```
article_no	content_head
第六百七十九条	自然人之间的借款合同，自贷款人提供借款时
```

✅ `content` 被**还原成迁移里的权威文本**（`ON DUPLICATE KEY UPDATE` 里的 `content = VALUES(content)` 生效）。
✅ `article_no` 未变（它参与唯一键，改了就等于插新行——这正是「改条号要新增迁移、不许 UPDATE」的原因）。

---

## 五、两条路的边界（写进文档，防止以后有人越过）

| 场景 | 走什么 | 谁做 |
|---|---|---|
| 首批法条（随版本发布） | Flyway `V302__import_law_items.sql` | 段 3 建模板；负责人提供内容 |
| **运营期新增法条**（负责人以后自己插） | ① PC 后台 A-27「批量导入」接口（H1）<br>② 或直接 SQL 插 | 负责人 |
| 演示 / 测试数据 | `database/seed/` | 各段，**不碰法条** |

**为什么运营期数据不能走 Flyway**：Flyway 是应用启动时自动执行的，
每次加 10 条法条就发一个版本不现实；而且迁移一旦执行就不可回改（`validate-on-migrate: true`），
运营数据录错是常态，这条路走不通。

---

## 六、录入规范文档

`docs/standards/法条数据录入规范.md` 已创建，含：

1. **三条硬要求**：逐字准确（权威来源清单 + 禁止来源）／`article_no` 与 `article_seq` 都要填／`law_code` 用 6 个登记短码
2. **两条路的边界表**（上节）
3. **可复制的 SQL 模板** + 6 条写法要点（含「失效条文不要删行」「停用改 `is_enabled` 不要 DELETE」）
4. **录入前核对清单** 10 条
5. **常见错误与后果** 6 条（含「同一部法两种 `law_code`」「UPDATE 覆盖旧版本条文」）

---

## 七、工作流 A3「算做完」逐条对照

| 判据（工作流 L359–L363） | 结果 | 证据 |
|---|---|---|
| 模板 SQL 在空库上执行成功，`SELECT COUNT(*)` 与插入条数一致 | ✅ 通过 | §二 |
| 重复执行不报错、不重复插入 | ✅ 通过 | §三 |
| 故意改一列再执行 → 该列被更新、`article_no` 未变 | ✅ 通过 | §四 |
| 录入规范文档存在且被 `docs/README.md` 索引 | ✅ 通过 | §六 + §八.1（索引已由本轮收口加上，真实行号 96） |

---

## 八、遗留与说明

1. **`docs/README.md` 的索引已加**（真实行号 96）：
   `| 法条怎么录（逐字准确、两个条号字段、Flyway 与运营期两条路） | [standards/法条数据录入规范.md](standards/法条数据录入规范.md) |`
   同时 `docs/BASELINE.md` v1.49 的 §2.4 已登记本文件指纹（`4ae1d4122096fce0`），`docs/CHANGELOG.md` 有对应一节。
2. **开发库尚未执行 V302**：下一次应用启动时 Flyway 自动执行；届时 A3 的证据会补一行
   `SELECT COUNT(*) FROM law_item` 的真实输出（预期 6）。
3. **本任务不含 2000 条全量法条**（工作流 §零.3 第 5 项：本段只验证通道）。
   全量数据由负责人提供，走运营期的 A-27 批量导入。
