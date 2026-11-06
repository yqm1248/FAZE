# 段 3 · B3 条文号存在性校验 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 B3（L476–L516）
> 产出：`modules/ai/service/ArticleNoValidator.java`、`modules/ai/dto/ValidationResult.java`、
> `modules/ai/util/ChineseNumberUtil.java`、`ArticleNoValidatorTest.java`、`ChineseNumberUtilTest.java`、本文档
> 数据来源：`faze.law_item`（6 条）
> 证据日期：2026-11-07
> 执行人：be-search

---

## 一、五种输入输出（真实 service 执行）

harness 直接实例化真实 `ArticleNoValidator`，Mapper 代理的 `selectByArticleNo` SQL 与
`LawItemMapper.xml` 逐字对应（`status='active' AND is_enabled=1`，`article_no=? OR article_seq=?`，
法律名 `law_name LIKE %hint% OR law_code=?`）。真实输出：

```
B3 validate(依据《中华人民共和国民法典》第六百八十条，禁止高利放贷。) status=ALL_EXIST valid=[第六百八十条] missing=[]
B3 validate(《中华人民共和国民法典》第九千九百九十九条规定的。) status=PARTIAL_MISS valid=[] missing=[第九千九百九十九条]
B3 validate(合同应当全面履行，否则构成违约。) status=NO_REF valid=[] missing=[]
B3 validate(《中华人民共和国民法典》第六百八十条与第九千九百九十九条都有规定。) status=PARTIAL_MISS valid=[第六百八十条] missing=[第九千九百九十九条]
B3 validate(第680条怎么规定的？) status=PARTIAL_MISS valid=[] missing=[第680条]
```

| # | 情况 | 结果 | 判据 |
|---|---|---|---|
| 1 | 存在（《民法典》第六百八十条，库里 680） | `ALL_EXIST` | ✅ valid=[ 第六百八十条 ] |
| 2 | 不存在（第九千九百九十九条） | `PARTIAL_MISS`，判为不存在 | ✅ missing=[ 第九千九百九十九条 ] |
| 3 | 回答里没有条号 | `NO_REF`，不报错 | ✅ 两个列表都空 |
| 4 | 多引用混合（680 存在 + 9999 不存在） | `PARTIAL_MISS` | ✅ valid/missing 各一条 |
| 5 | 无法归属法律（「第680条」无《法律名》） | 判不存在（宁可误拦），**且不查库** | ✅ missing=[ 第680条 ]，单测 `verifyNoInteractions` |

补充 `findByArticleNo`（D4/段 4 复用入口，真实输出）：

```
B3 findByArticleNo(null, 第680条)=第六百八十条
B3 findByArticleNo(民法典, 第六百七十九条)=第六百七十九条
B3 findByArticleNo(civil_code, 第680条)=第六百八十条
B3 findByArticleNo(民法典, 第九千九百九十九条)=EMPTY
```

✅ `lawNameOrCode` 传 null 时只按条号查；传《民法典》做包容匹配（命中库里的**中华人民共和国**民法典）；
传 `civil_code` 也命中；查不到返回 `Optional.empty()`，不抛异常。

## 二、真库 SQL 佐证

```sql
-- 680 存在：命中 1 条
SELECT id, article_no, article_seq FROM law_item
WHERE status='active' AND is_enabled=1 AND (article_no='第六百八十条' OR article_seq=680)
  AND (law_name LIKE CONCAT('%','民法典','%') OR law_code='民法典');

-- 9999 不存在：0 条（同时确认库里没有任何 9999 序号的条文）
SELECT id, article_no FROM law_item
WHERE status='active' AND is_enabled=1 AND (article_no='第九千九百九十九条' OR article_seq=9999)
  AND (law_name LIKE CONCAT('%','民法典','%') OR law_code='民法典');
```

真实输出（680）：

```
id	article_no	article_seq
4	第六百八十条	680
```

9999 查询返回 0 行（MySQL CLI 无结果行输出）。

## 三、中文数字转换（真实执行，1~1260 全覆盖）

`ChineseNumberUtil` 是纯函数，用 `javac` 单独编译 + 独立 main 跑全量（真实输出）：

```
roundtrip 1..1260 failures=0
key cases failures=0
invalid inputs accepted=0
toChinese(680)=六百八十
ALL DONE fail=0
```

覆盖：1~1260 双向全覆盖（`toArabic(toChinese(n)) == n`）；重点值
十九/二十/一百/一百零一/一百一十/一千零一/一千零六十/一千二百六十/六百八十/九千九百九十九；
非法输入（null/空/abc/十百/一百百/一二三/10000/一万）全部返回 null 不抛异常。
`toChinese` 越界抛 `IllegalArgumentException`（纯函数被误用属编程错误，与「解析失败返回 null」语义不同）。

## 四、单测（正式 mvn 证据）

`ArticleNoValidatorTest` 覆盖存在/不存在/无条号/多引用混合/无法归属法律 + `findByArticleNo(null,…)` + 空参数；
`ChineseNumberUtilTest` 覆盖 1~1260 全覆盖与边界。先做了一次预跑（javac 临时目录 + junit-platform-launcher，未跑 mvn）；随后由 lead 用 mvn 正式跑：

```
RESULT tests=33 succeeded=33 failed=0   （6 个测试类合计，本组两个类全绿）
```

预跑时发现并已修复一处口径问题：「第680条」的 `article_no` 等值匹配应先规整成库里的中文写法
`第六百八十条`（同时仍带 `article_seq=680` 兜底），修复后用例通过。

> **正式证据（mvn surefire，2026-11-07 00:52:14~15）**：`ArticleNoValidatorTest` `Tests run: 7, Failures: 0, Errors: 0`、`ChineseNumberUtilTest` `Tests run: 5, Failures: 0, Errors: 0`（报告在 `target/surefire-reports/`）；6 个类合计 33 tests 全绿。

## 五、段 4 复用签名（已写进类注释）

```java
Optional<LawItem> findByArticleNo(String lawNameOrCode, String articleNo); // lawNameOrCode 可 null
ValidationResult validate(String answerText);
ValidationResult validate(String answerText, List<String> fallbackLawNames); // 检索结果 lawName 兜底
```

段 4 文书中心直接注入本服务，**不许另写一份**。
