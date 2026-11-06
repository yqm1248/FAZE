# 段 3 · A4 法律术语读取服务 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 A4（L371–L396）
> 产出：`modules/ai/service/GlossaryService.java`、`src/test/java/com/faze/modules/ai/service/GlossaryServiceTest.java`、本文档
> 数据来源：`V303__init_i18n_glossary.sql`（51 条 `zh-Hans` 术语，lead 编写并已应用）
> 证据日期：2026-11-07
> 执行人：be-search

---

## 一、迁移与数据（真实执行）

`V303__init_i18n_glossary.sql` 由 lead 编写。执行方式：先用 `faze_migrate` 账号直接执行文件内容（幂等
`ON DUPLICATE KEY UPDATE`），随后 lead 重启应用时由 Flyway 正式登记（`history` 里 `302/303 success=1`）。

条数与语言分布（真实输出）：

```sql
SELECT COUNT(*) AS total FROM i18n_glossary;
SELECT lang, COUNT(*) AS cnt FROM i18n_glossary GROUP BY lang;
```

```
total
51

lang	cnt
zh-Hans	51
```

✅ 要求 ≥50 → 实际 **51**。
✅ 藏/维/哈三种 `lang` 的行数为 **0**（上表只有 `zh-Hans` 一行）——没有编译文，符合 A4 第 1 条。

三次验收用到的术语确实在库且为启用状态：

```sql
SELECT term_key, source_text, lang, status, is_enabled FROM i18n_glossary
WHERE source_text IN ('定金','违约金','证据') ORDER BY LENGTH(source_text) DESC;
```

```
term_key	source_text	lang	status	is_enabled
liquidatedDamages	违约金	zh-Hans	confirmed	1
evidence	证据	zh-Hans	confirmed	1
deposit	定金	zh-Hans	confirmed	1
```

## 二、加载与最长匹配（真实 service 执行）

**真实应用启动日志**（Spring 容器内 `@PostConstruct` 实际加载，非 harness）：

```
2026-11-07 00:46:23.755 INFO  [no-trace] [anon] [] c.f.m.ai.service.GlossaryService - 法律术语表加载完成 count=51
```

✅ 应用启动时全量加载 51 条，与库内条数一致。

匹配验证用临时 harness **直接实例化真实 `GlossaryService` 类**（应用当时尚未启动完），
Mapper 用动态代理 + JDBC 从真库读取同一张表（读取 SQL 与 `I18nGlossaryMapper.selectEnabledZhHans`
逐字一致：`lang='zh-Hans' AND is_enabled=1 AND status='confirmed' ORDER BY LENGTH(source_text) DESC, id ASC`）。
命令：

```powershell
# 编译：target/classes + Spring/MyBatis/MySQL 驱动 jar；运行：见 RealDbCheck.a4()
java -Dfile.encoding=UTF-8 -cp "<harness>;<target/classes>;<deps>" RealDbCheck
```

真实输出：

```
A4 loaded_terms=51
A4 match(我要告他违约，要求返还定金)=[定金]
A4 match(违约金怎么算)=[违约金]
A4 match(今天天气不错)=[]
A4 getTerms(定金,违约金,不存在)=[定金, 违约金]
```

对照 A4「算做完」四条：

| 判据 | 结果 |
|---|---|
| `COUNT(*)` ≥ 50 | ✅ 51 |
| 「我要告他违约，要求返还定金」→ 命中「定金」（不命中「违约金」） | ✅ `[定金]` |
| 「违约金怎么算」→ 命中「违约金」 | ✅ `[违约金]` |
| 未命中返回空列表、不抛异常 | ✅ `[]` |
| 藏/维/哈 `lang` 不存在 | ✅ 51 行全 `zh-Hans` |

补充：`getTerms(List)` 只返回术语表中真实存在的词（`定金/违约金` 命中，`不存在` 被忽略），供 D1 需要时使用。

## 三、最长匹配的单元测试（正式 mvn 证据）

`GlossaryServiceTest` 覆盖：两次验收输入、未命中、长短术语重叠（「违约金」命中后不再吐出「违约」）、`getTerms` 过滤。

先做了一次预跑（**不跑 mvn、不碰 target**）：`javac` 把测试源码编到临时目录，再用 `junit-platform-launcher` 直接执行；随后由 lead 用 mvn 正式跑：

```
RESULT tests=33 succeeded=33 failed=0   （6 个测试类合计；GlossaryServiceTest 全绿）
```

> **正式证据（mvn surefire，2026-11-07 00:52:14）**：`mvn -B -Dtest=...` 跑 6 个测试类 **33 tests, Failures: 0, Errors: 0, Skipped: 0**；其中 `GlossaryServiceTest` 为 `Tests run: 4, Failures: 0, Errors: 0`（报告 `target/surefire-reports/com.faze.modules.ai.service.GlossaryServiceTest.txt`）。

## 四、挂账

- **A4 无 HTTP 入口**（本段不做 `/admin/glossary`，A-17 归段 8），所以没有 curl 证据，这是设计如此。
- 应用启动后 D1 的第一次真实注入日志（术语命中列表）属 F/D 组联调，不在本任务范围。
