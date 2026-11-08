# 段 3 · B4 AI 引用次数统计 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 B4（L520–L552）
> 产出：`modules/ai/service/LawRefStatsService.java`、`LawRefStatsServiceTest.java`、本文档
> 依赖（lead 建好）：`modules/ai/mapper/AiMessageMapper#selectRefCounts`、`RefCountRow`、
> `resources/mapper/ai/AiMessageMapper.xml`
> 数据来源：`faze.ai_message.refs`（JSON 列，schema 由 D4 冻结为 `[{lawItemId, lawCode, lawName, articleNo, content, effectiveDate, status}]`）
> 证据日期：2026-11-07
> 执行人：be-search

---

## 一、口径（写进 A-27 页面 `title`）

> **「AI 引用次数」= 历史回答（`role='assistant'`）中引用过该条文的次数，含已软删会话**
> （合规追溯要算全量）。`refs` 为 NULL（没查过）不参与统计，`refs='[]'`（查过但没引用）也不产生计数。

实现上**不加计数列**：加了就有两个真相（计数列 vs `refs` 实际内容），对话被软删或导出时计数会漂；
`refs` 是只读快照，从它现算永远一致。统计 SQL 用 MySQL 8 的 `JSON_TABLE` 展开 `refs` 后 `GROUP BY`，
一次覆盖整页 id（`IN` 列表），**不做 N+1**。

## 二、真库 SQL 证据（手工插 5 条 → 查询 → 按 id 清理）

插入 5 条 `ai_message`（`role` 见下），其中 3 条 `refs` 分别引用 679、679、1042；1 条 `refs=NULL`；
1 条 `role='user'` 也带 679（验证用户消息不计入）：

```sql
INSERT INTO ai_message (conversation_id, user_id, role, content, refs, lang, is_safety_passed, is_exported, created_at, updated_at)
VALUES
 (90001, 90001, 'assistant', 'seg3-b4-acceptance-1', '[{"lawItemId":679,...}]', 'zh-Hans', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
 (90001, 90001, 'assistant', 'seg3-b4-acceptance-2', '[{"lawItemId":679,...}]', 'zh-Hans', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
 (90001, 90001, 'assistant', 'seg3-b4-acceptance-3', '[{"lawItemId":1042,...}]', 'zh-Hans', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
 (90001, 90001, 'assistant', 'seg3-b4-acceptance-null', NULL, 'zh-Hans', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
 (90001, 90001, 'user',      'seg3-b4-acceptance-user', '[{"lawItemId":679,...}]', 'zh-Hans', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP());
```

`AiMessageMapper.xml` 的统计 SQL 原样（ids=679,1042,680）：

```sql
SELECT jt.law_item_id AS lawItemId, COUNT(*) AS refCount
FROM ai_message m,
     JSON_TABLE(m.refs, '$[*]' COLUMNS (law_item_id BIGINT PATH '$.lawItemId')) AS jt
WHERE m.role = 'assistant'
  AND m.refs IS NOT NULL
  AND jt.law_item_id IN (679, 1042, 680)
GROUP BY jt.law_item_id;
```

真实输出：

```
lawItemId	refCount
679	2
1042	1
```

✅ **679:2 / 1042:1**（与任务书一致）。
✅ `refs=NULL` 的那条不参与（否则会报错或计数变化）。
✅ `role='user'` 的 679 未计入（否则 679 会是 3）——用户消息不该有 refs，统计只算 assistant。

清理（只删自己插的行，**不是清表**）：

```sql
DELETE FROM ai_message WHERE content LIKE 'seg3-b4-acceptance%';
SELECT COUNT(*) FROM ai_message WHERE content LIKE 'seg3-b4-acceptance%';
```

真实输出：`remaining_acceptance_rows = 0`。

## 三、service 层证据（真实 service + 真实库）

harness 直接实例化真实 `LawRefStatsService`，Mapper 代理执行与 XML 相同的 `JSON_TABLE` SQL
（另插一批自己的行，结束后**按 `RETURN_GENERATED_KEYS` 拿到的 id 逐条清理**）：

```
B4 counts={679=2, 1042=1}
B4 emptyInput={}
B4 cleanup_deleted=5 ids=[11, 12, 13, 14, 15]
B4 cleanup_remaining=0
```

✅ 空入参直接返回空 Map、**不进 SQL**（避免 `IN ()` 语法错）。

## 四、单测（正式 mvn 证据）

`LawRefStatsServiceTest` 覆盖：空入参不查库、计数映射（679:2 / 1042:1）、Mapper 返回 null 不 NPE、
**整页 20 条 id 只查 1 次**（`verify(times(1))`，N+1 的机械约束）。
先做了一次预跑（javac 临时目录 + junit-platform-launcher，不跑 mvn、不碰 target）；随后由 lead 用 mvn 正式跑：

```
RESULT tests=33 succeeded=33 failed=0   （6 个测试类合计，本类全绿）
```

> **正式证据（mvn surefire，2026-11-07 00:52:14）**：`LawRefStatsServiceTest` 为 `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`（报告 `target/surefire-reports/com.faze.modules.ai.service.LawRefStatsServiceTest.txt`）；6 个类合计 33 tests 全绿。

## 五、性能兜底（本段不做）

`ai_message` 超过 10 万行后本查询会慢，届时改为在 `refs` 上建多值索引（MySQL 8.0.17+），
本段**不做**；该注释已写在 `AiMessageMapper` / `AiMessageMapper.xml` / `LawRefStatsService` 三处。

## 六、挂账

- A-27 法条库列表页的接入（列显示 + `title` 口径文案）归 H1，本任务只供数；
- ~~`LawRefStatsServiceTest` 正式 mvn 证据待 lead 整合阶段跑~~ → **已跑**：4 tests, 0 failures（surefire，2026-11-07 00:52:14）。
