# 段 3 · B1 法条检索内核（四路径）· 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 B1（L400–L441）
> 产出：`modules/ai/service/LawSearchService.java`、`modules/ai/dto/LawHit.java`、
> `infra/law/mapper/LawItemMapper.java`（追加 6 条查询）、`infra/law/mapper/LawItemSearchRow.java`、
> `resources/mapper/law/LawItemMapper.xml`（追加，`searchByNgram` 未动）、`LawSearchServiceTest.java`、本文档
> 数据来源：`faze.law_item`（V2 五条 + V302 的 679，共 6 条；`ft_law_content` ngram 索引）
> 证据日期：2026-11-07
> 执行人：be-search

---

## 一、真实 service 执行结果（四路径各一次）

应用未启动，故用临时 harness 直接实例化真实 `LawSearchService`，Mapper 用动态代理 + JDBC 连真库；
代理里每条 SQL 与 `resources/mapper/law/LawItemMapper.xml` 的语句逐字对应（`#{}` 代入绑定值）。
`sys_config.ai_search_topn` 代理返回 5。

真实输出：

```
B1 search(借条) hits=1
B1   id=7 articleNo=第六百七十九条 hitReason=keywords score=0.3 effectiveDate=2021-01-01 contentLen=23
B1 search(借款) hits=3
B1   id=3 articleNo=第六百六十七条 hitReason=fulltext score=0.362476 effectiveDate=2021-01-01 contentLen=30
B1   id=4 articleNo=第六百八十条 hitReason=fulltext score=0.362476 effectiveDate=2021-01-01 contentLen=121
B1   id=7 articleNo=第六百七十九条 hitReason=fulltext score=0.181238 effectiveDate=2021-01-01 contentLen=23
B1 search(婚) hits=2
B1   id=1 articleNo=第四百六十四条 hitReason=like score=0.5 effectiveDate=2021-01-01 contentLen=83
B1   id=5 articleNo=第一千零七十九条 hitReason=like score=0.5 effectiveDate=2021-01-01 contentLen=77
B1 search(第六百八十条) hits=1
B1   id=4 articleNo=第六百八十条 hitReason=article_no score=1.0 effectiveDate=2021-01-01 contentLen=121
B1 search(《中华人民共和国民法典》第六百八十条) hits=2
B1   id=4 articleNo=第六百八十条 hitReason=article_no score=1.0 effectiveDate=2021-01-01 contentLen=121
B1   id=5 articleNo=第一千零七十九条 hitReason=fulltext score=2.422077 effectiveDate=2021-01-01 contentLen=77
B1 search(交付) hits=1
B1   id=7 articleNo=第六百七十九条 hitReason=keywords score=0.3 effectiveDate=2021-01-01 contentLen=23
B1 search(劳动仲裁) hits=0
```

逐条对照 B1「算做完」：

| 判据 | 结果 |
|---|---|
| ① 条号精确：「第六百八十条」→ `hitReason=article_no`、`score=1.0` | ✅ id=4，score=1.0 |
| ② 长度≥2：「借款」→ `MATCH ... AGAINST`，`hitReason=fulltext` | ✅ 3 条（667/680/679） |
| ③ 长度<2：「婚」→ LIKE，`hitReason=like`，不报错 | ✅ 2 条（464/1079） |
| ④ 结果不足 topN：「借条」（content 无此词）→ keywords 命中 679 | ✅ `hitReason=keywords`（V302 的 `keywords` 含「借条」） |
| topN 传 100 → 夹到 10 | ✅ 单测 `topNClamped` 用 `verify(searchByNgram("借款", 10))` 断言 |

说明：
- 命中「《中华人民共和国民法典》第六百八十条」时，结果里**同时**有 1 条全文命中（1079），
  这是「四条路径按优先级拼接、不是四选一」的预期行为（条号命中的 1.0 排在全文得分之前）。
- 「劳动仲裁」0 命中：当前库只有`civil_code`一个法系（6 条），没有劳动合同法条文；
  任务书举的「库里只有仲裁 keyword」例子在现有数据不可构造，改用真实数据 **「交付」→ keywords 命中 679** 验路径 ④。
- `LawHit.content` 一律 ≤200 字；`effectiveDate`/`status` 供 D4 冻结 refs 快照（见 `LawHit` 类注释）。

## 二、真库 SQL 与 EXPLAIN（全文走 `ft_law_content`）

```sql
SELECT id, law_code, article_no, content, MATCH(content) AGAINST('借款') AS score
FROM law_item
WHERE MATCH(content) AGAINST('借款') AND status='active' AND is_enabled=1
ORDER BY score DESC LIMIT 5;
```

真实输出：

```
id	law_code	article_no	score
3	civil_code	第六百六十七条	0.362476
4	civil_code	第六百八十条	0.362476
7	civil_code	第六百七十九条	0.181238
```

```sql
EXPLAIN SELECT id, law_code, article_no, content, MATCH(content) AGAINST('借款') AS score
FROM law_item WHERE MATCH(content) AGAINST('借款') AND status='active' AND is_enabled=1
ORDER BY score DESC LIMIT 5;
```

真实输出：

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	law_item	NULL	fulltext	idx_law_item_enabled,ft_law_content	ft_law_content	0	const	1	16.67	Using where; Ft_hints: sorted
```

✅ `type=fulltext`、`key=ft_law_content`——命中走 ngram 全文索引，不是全表扫。

LIKE 路径同样有索引可用（非全表扫）：

```sql
EXPLAIN SELECT id FROM law_item WHERE status='active' AND is_enabled=1
AND content LIKE CONCAT('%','婚','%') ORDER BY article_seq ASC, id ASC LIMIT 5;
```

```
id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	SIMPLE	law_item	NULL	ref	idx_law_item_enabled	idx_law_item_enabled	1	const	6	16.67	Using where; Using filesort
```

## 三、真实应用内调用（日志）

应用最终启动成功后，D1 的 `PromptAssembler` 已按类型注入本类并真实调用（日志原文）：

```
2026-11-07 00:49:16.256 DEBUG [854d4d91-...] [user:1] [] c.f.i.l.m.L.searchByNgram - ==>  Preparing: SELECT id, law_code AS lawCode, article_no AS articleNo, content, MATCH(content) AGAINST (?) AS score FROM law_item WHERE MATCH(content) AGAINST (?) AND status = 'active' AND (expire_date IS NULL OR expire_date > CURRENT_DATE) ORDER BY score DESC LIMIT ?
2026-11-07 00:49:16.257 DEBUG [854d4d91-...] [user:1] [] c.f.i.l.m.L.searchByNgram - ==> Parameters: ……(String), ……(String), 5(Integer)
2026-11-07 00:49:16.257 DEBUG [854d4d91-...] [user:1] [] c.f.m.ai.service.LawSearchService - 法条检索完成 qLen=12 topN=5 hits=0
```

✅ `c.f.m.ai.service.LawSearchService`（本类，bean 名 `aiLawSearchService`）在真实容器里被调用，
`topN=5` 来自配置；`searchByNgram` 的 XML 语句真实执行。
B2 的 HTTP 请求还真实执行了新增的 `selectByKeywords`/`countEnabled`（见 B2 文档 4.7），
全程日志**无 `BindingException` / `Invalid bound statement`**——Mapper XML 绑定正确。

## 四、单测（正式 mvn 证据）

`LawSearchServiceTest` 覆盖 9 个用例：四路径各一次、`topN=100→10`、`topN≤0` 读配置、
`pageSize=999→50`、内容截断（LawHit 200 / LawItemBrief 300）。

先做了一次预跑（**不跑 mvn、不碰 target**）：`javac` 编到临时目录 + `junit-platform-launcher`；随后由 lead 用 mvn 正式跑：

```
RESULT tests=33 succeeded=33 failed=0   （6 个测试类合计，本类全绿）
```

> **正式证据（mvn surefire，2026-11-07 00:52:15）**：`LawSearchServiceTest` 为 `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`（报告 `target/surefire-reports/com.faze.modules.ai.service.LawSearchServiceTest.txt`）；6 个类合计 33 tests 全绿。

## 五、与段 0 探针的关系（要求写进类注释）

- 段 0 的 `infra/law/LawSearchService`（`/pub/probe/law`）只验 ngram 可用，**一字未改**；
- 本类是段 3 业务内核（四路径 + 命中依据），**共用同一个 `LawItemMapper` 与同一份 XML**，没有复制检索 SQL；
- `searchByNgram` 保持原样（段 0 冻结），新增查询全部返回 `LawItemSearchRow`（与 `LawSearchRow` 字段不同、各管一条链路）。

## 六、挂账

- ~~`LawSearchServiceTest` 的正式 mvn 证据待 lead 整合阶段跑~~ → **已跑**：9 tests, 0 failures（surefire，2026-11-07 00:52:15）。
- D1 真实回答里「检索到的法条」段落的联调证据属 D1/F 组，不在本任务范围。
