# 段 3 · D4 refs 快照生成 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 D4（L866–L908）、§四 `3-A6`（A-28 读 refs 快照）
> 产出：`modules/ai/service/RefSnapshotBuilder.java`、`modules/ai/dto/LawRefSnapshot.java`、
> `src/test/java/com/faze/modules/ai/RefSnapshotBuilderTest.java`、本文档
> 数据来源：B1 `LawHit`（content ≤200 字）+ B3 `ArticleNoValidator.findByArticleNo`（不在 hits 时的补查）+ `faze.law_item`
> 证据日期：2026-11-07　执行人：be-guard

---

## 〇、验证方式（如实说明）

本段铁律不执行 `mvn`。本文输出由 `javac 17.0.17` 编译真实源码后、独立 main 直接
`new RefSnapshotBuilder(validator).build(answer, hits)` 执行；`ValidationResult` / `findByArticleNo`
用子类覆盖返回**构造好的**结果（B3 真实实现另见 `B3-validator.md`）。JSON 由 **Jackson 3（`tools.jackson`）**
真实序列化。单测真实执行：**37 tests / 37 successful**，其中 `RefSnapshotBuilderTest` **7 个**。
正式 `mvn test` 由 lead 统一执行（挂账）。

---

## 一、Schema（冻结，字段名不许改）

```json
[{"lawItemId":679,"lawCode":"civil_code","lawName":"中华人民共和国民法典","articleNo":"第六百七十九条","content":"…≤200字…","effectiveDate":"2021-01-01","status":"active"}]
```

- `lawItemId` **必填**（A-27「AI 引用次数」按它统计）；
- `content` ≤200 字（快照摘要，不是全文）；
- `effectiveDate` 为 ISO `yyyy-MM-dd` 字符串，`status` 为当时的 `law_item.status`，两者**冻结**；
- 空引用存 **`[]`**（不是 NULL）。

---

## 二、一条真实 refs JSON（Jackson 3 序列化，真实输出）

回答引用了 2 条法条（库中 id=4 第六百八十条、id=3 第六百六十七条），`hits` 两条都在：

```json
[{"articleNo":"第六百八十条","content":"禁止高利放贷，借款的利率不得违反国家有关规定。借款合同对支付利息没有约定的，视为没有利息。借款合同对支付利息约定不明确，当事人不能达成补充协议的，按照当地或者当事人的交易方式、交易习惯、市场利率等因素确定利息；自然人之间借款的，视为没有利息。","effectiveDate":"2021-01-01","lawCode":"civil_code","lawItemId":4,"lawName":"中华人民共和国民法典","status":"active"},{"articleNo":"第六百六十七条","content":"借款合同是借款人向贷款人借款，到期返还借款并支付利息的合同。","effectiveDate":"2021-01-01","lawCode":"civil_code","lawItemId":3,"lawName":"中华人民共和国民法典","status":"active"}]
```

> 说明：Jackson 3 默认把对象键按字母序输出（`articleNo` 在最前），**字段名与 schema 完全一致**；
> JSON 对象键无序，段 4 / A-28 按 key 读取，不受顺序影响。

空引用（回答里没有条号）真实输出：

```json
[]
```

---

## 三、四种情况（真实输出）

| # | 情况 | 真实输出 |
|---|---|---|
| 0 | 0 条（回答没引条号） | `size=0 isNull=false`（空 list，不是 null） |
| 1 | 1 条（回答引用 hits 里的 680） | `size=1 lawItemId=4 articleNo=第六百八十条 effectiveDate=2021-01-01 status=active content.len=121` |
| 2 | 多条（680 + 667） | `size=2 ids=[4, 3]` |
| 3 | 条号存在但 hits 里没有（1079，库里 id=5） | `size=1 lawItemId=5 content.len=77`（走 `findByArticleNo` 现查） |

「条号存在但 hits 里没有」这条的完整 JSON（content 从 `law_item` 现查填进快照）：

```json
[{"articleNo":"第一千零七十九条","content":"夫妻一方要求离婚的，可以由有关组织进行调解或者直接向人民法院提起离婚诉讼。人民法院审理离婚案件，应当进行调解；如果感情确已破裂，调解无效的，应当准予离婚。","effectiveDate":"2021-01-01","lawCode":"civil_code","lawItemId":5,"lawName":"中华人民共和国民法典","status":"active"}]
```

`content` 截断：单测用 300 字输入断言 `content.length == 200`（按码点截断，不切代理对）；
上限：11 条引用 → 只存前 10，日志 `REFS_TRUNCATED stored=10 total=11`（真实日志，见 §五）；
`lawItemId` 缺失的命中项跳过，日志 `REFS_SKIP_NO_ID count=1`。

---

## 四、冻结语义的真库证据（改 `law_item.content` 后快照不变）

动作：把上面那条 2 条引用的 refs **真实插入** `ai_message`（事务内临时行）→ 改 `law_item.content` →
再看 refs。**全部在一个事务里执行并 `ROLLBACK`，不污染 `faze` 库。**

执行的 SQL（`d4-freeze2.sql`，refs 值为 §二 的 JSON 原文）：

```sql
START TRANSACTION;
INSERT INTO ai_message (conversation_id, user_id, role, content, refs, lang, is_safety_passed, is_exported, created_at, updated_at)
VALUES (999999999, 999999999, 'assistant', '[D4 证据临时行：事务内插入并回滚]', '<§二 的 JSON>', 'zh-Hans', 1, 0, NOW(), NOW());
SELECT MD5(refs), JSON_LENGTH(refs), JSON_EXTRACT(refs,'$[0].lawItemId') FROM ai_message WHERE conversation_id=999999999;
UPDATE law_item SET content = CONCAT(content, '【D4快照冻结验证-临时改动】') WHERE id = 4;
SELECT RIGHT(content,30) FROM law_item WHERE id = 4;
SELECT MD5(refs), JSON_LENGTH(refs), JSON_EXTRACT(refs,'$[0].content') FROM ai_message WHERE conversation_id=999999999;
ROLLBACK;
SELECT RIGHT(content,30) FROM law_item WHERE id = 4;
SELECT COUNT(*) FROM ai_message WHERE conversation_id=999999999;
```

真实输出：

```
+-------------------+----------------------------------+------------+-----------------+
| step              | refs_md5                         | refs_count | first_lawItemId |
+-------------------+----------------------------------+------------+-----------------+
| step1 refs 指纹   | 276e768b1be19ec8d3a11593672bb874 |          2 | 4               |
+-------------------+----------------------------------+------------+-----------------+
+-------------------------------+--------------------------------------------------------------+
| step                          | content_tail                                                 |
+-------------------------------+--------------------------------------------------------------+
| step2 law_item.content 已改   | 然人之间借款的，视为没有利息。【D4快照冻结验证-临时改动】    |
+-------------------------------+--------------------------------------------------------------+
+---------------------------+----------------------------------+------------+-----------------------------+
| step                      | refs_md5                         | refs_count | first_content               |
+---------------------------+----------------------------------+------------+-----------------------------+
| step3 旧 refs 指纹不变    | 276e768b1be19ec8d3a11593672bb874 |          2 | "禁止高利放贷，借款的…"     |
+---------------------------+----------------------------------+------------+-----------------------------+
+----------------------------------+--------------------------------------------------------------+
| step                             | content_tail                                                 |
+----------------------------------+--------------------------------------------------------------+
| step4 回滚后 law_item.content    | 惯、市场利率等因素确定利息；自然人之间借款的，视为没有利息。 |
+----------------------------------+--------------------------------------------------------------+
+--------------------------+-----+
| step                     | cnt |
+--------------------------+-----+
| step5 临时行残留数       |   0 |
+--------------------------+-----+
```

结论（可复核）：
- `step1` 与 `step3` 的 **`MD5(refs)` 完全相同**（`276e768b1be19ec8d3a11593672bb874`）→ 改 `law_item.content` **不影响快照**；
- `refs_count` 恒为 2、`first_lawItemId` 恒为 4、`first_content` 仍是旧条文；
- `ROLLBACK` 后 `law_item.content` 恢复原样、临时行 `cnt=0`（库干净）。

> 段 4 契约：文书中心的法条引用块**按同一 schema 存**（见第 3 段工作流 §八），本结构已冻结。

---

## 五、真实日志（只记条数，不记内容）

harness 运行（`run.log`，logback INFO）：

```
00:45:52.303 INFO  c.f.m.a.s.RefSnapshotBuilder - REFS_BUILT count=1
00:45:52.307 INFO  c.f.m.a.s.RefSnapshotBuilder - REFS_BUILT count=2
00:45:52.309 INFO  c.f.m.a.s.RefSnapshotBuilder - REFS_BUILT count=1
```

单测运行（`junit.log`；`REFS_TRUNCATED` 由「11 条引用」用例触发，`REFS_SKIP_NO_ID` 由「命中项缺 lawItemId」用例触发）：

```
00:45:49.278 [main] WARN  com.faze.modules.ai.service.RefSnapshotBuilder -- REFS_SKIP_NO_ID count=1
00:45:49.278 [main] WARN  com.faze.modules.ai.service.RefSnapshotBuilder -- REFS_LOOKUP_MISS count=1
00:45:49.282 [main] INFO  com.faze.modules.ai.service.RefSnapshotBuilder -- REFS_TRUNCATED stored=10 total=11
```

无一条日志出现条文全文或回答原文。

> 说明：日志行的时间戳是某一次真实运行实例，重跑会变；条数等字段值稳定。

---

## 六、挂账与未决

| 项 | 状态 | 说明 |
|---|---|---|
| `mvn test` 正式执行 | 挂账 | 铁律不执行 mvn；由 lead 统一跑 |
| C3 写库（`ai_message.refs`） | 挂账 | 落库点在 C3；本类返回 `List<LawRefSnapshot>`，C3 用 Jackson 3 序列化后写（空 list → `[]`） |
| `3-A6` 的 A-28 页面读取 | 挂账 | 属 H2/A-28；本文件只保证 schema 与冻结语义 |
| `3-A1` ④「导入后 refs 出现该条号」 | 挂账 | 需真模型 + 导入链路，非本任务 |
| `content` 200 字口径 | 已实现 | hits 路径直接用 B1 的 ≤200 字 content；补查路径把库中全文截到 200 字 |
