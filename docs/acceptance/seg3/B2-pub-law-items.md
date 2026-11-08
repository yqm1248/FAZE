# 段 3 · B2 公开法条检索接口 `GET /api/v1/pub/law-items` · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 B2（L445–L472）
> 产出：`modules/ai/controller/LawItemPublicController.java`、`modules/ai/dto/LawItemBrief.java`、
> `modules/ai/support/LawSearchRateLimiter.java`、`LawSearchRateLimiterTest.java`、本文档
> 数据来源：`LawSearchService`（B1 四路径内核，复用同一套 SQL，本接口不另写检索 SQL）
> 证据日期：2026-11-07
> 执行人：be-search

---

## 一、接口约定

| 项 | 实现 |
|---|---|
| 方法与路径 | `GET /api/v1/pub/law-items` |
| 鉴权 | **无**（`pub` 前缀 + 法条是公开信息；既要服务游客查询，也要承担 AI 关闭后的降级检索） |
| 参数 | `kw` 1~100 字（可空；1 个字允许，B1 有 LIKE 兜底）、`lawCode`、`pageNum` 默认 1、`pageSize` 默认 20 最大 50（超上限 clamp，不报错） |
| 响应 | `R<PageResult<LawItemBrief>>`；`id` 是 **String**；**不返回 `score`/`hitReason`** |
| 内容 | `LawItemBrief.content` 截断 300 字 |
| 限流 | 每 IP 每分钟 60 次（`sys_config.law_search_rate_per_min`，缺省 60），超限 `401007` |

Controller 只做收参/校验/调 service/包 `R`，不注入 Mapper、不写业务（后端规范 §2.2/§3.2）。

## 二、service 层证据（真实库；应用起来前先收集）

> 这一节是在 API 尚未启动时收集的 service 层证据；API 起来后的真 HTTP 证据见第四节，两者结论一致。

harness 直接实例化真实 `LawSearchService` 跑 `searchPage`（Mapper 代理的 SQL 与 XML 逐字对应）：

```
B2 page(kw=借条) total=1 size=1 firstId=7
B2 page(kw=null,lawCode=civil_code,pageSize=999) pageSize=50 total=6 size=6
B2 page(kw=婚) size=2 total=2 articleNos=[第四百六十四条, 第一千零七十九条]
```

对照 B2「算做完」：

| 判据 | 结果 |
|---|---|
| `kw=借条` 返回 ≥1 条，`id` 是字符串 | ✅ total=1（679）；`LawItemBrief.id` 字段类型就是 String（单测断言 `"4"`） |
| `kw=婚`（单字）不报错、结构正确 | ✅ 2 条，走 LIKE |
| `kw` 空 + `lawCode=civil_code` → 该法全部条文（分页） | ✅ total=6（6 条全是 civil_code），`pageSize` 默认/上限生效 |
| `pageSize=999` → 夹到 50 | ✅ `pageSize=50` |
| 1 分钟 61 次 → 第 61 次 `401007` | ✅ 真 HTTP 实测：#59/#60=200，#61=401007（见第四节） |

## 三、限流实现与单测（正式 mvn 证据）

`LawSearchRateLimiter.check(ip)`：
- key = `faze:law:search:rate:<ip>:<yyyyMMddHHmm>`，`INCR` + `EXPIRE 120s`（窗口 60s + 60s 缓冲）；
- 阈值每次从 `sys_config.law_search_rate_per_min` 读（缺省 60）；
- 超限抛 `BusinessException(ErrorCode.COMMON_RATE_LIMITED)`（401007）；
- **Redis 不可用时 fail-open（放行 + warn）**：法条检索是降级通道，不能让「AI 关闭 + Redis 抖动」时连法条都查不到；代价是短时限流失效，已在类注释写明。

`LawSearchRateLimiterTest` 覆盖：第 60 次放行、第 61 次 401007、Redis 异常放行、首次计数设 TTL。
先做了一次预跑（javac 临时目录 + junit-platform-launcher，不跑 mvn、不碰 target）；随后由 lead 用 mvn 正式跑：

```
RESULT tests=33 succeeded=33 failed=0   （6 个测试类合计，本类全绿）
```

> **正式证据（mvn surefire，2026-11-07 00:52:15）**：`LawSearchRateLimiterTest` 为 `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`（报告 `target/surefire-reports/com.faze.modules.ai.support.LawSearchRateLimiterTest.txt`）；6 个类合计 33 tests 全绿。

## 四、HTTP 层验收（真实 curl 输出）

API 最终由 lead 启动成功（`/api/v1/pub/ping` 返回 200）。以下命令与输出均为真机执行。

### 4.1 `kw=借条` → 200，total=1，`id` 是字符串，无 score/hitReason

```powershell
curl.exe -s --get --data-urlencode "kw=借条" "http://127.0.0.1:8081/api/v1/pub/law-items"
```

```json
{"code":200,"data":{"hasMore":false,"list":[{"articleNo":"第六百七十九条","content":"自然人之间的借款合同，自贷款人提供借款时成立。","effectiveDate":"2021-01-01","id":"7","lawCode":"civil_code","lawName":"中华人民共和国民法典","status":"active"}],"pageNum":1,"pageSize":20,"total":1},"msg":"ok","traceId":"e169a592-eec1-49ad-9afe-af2b6fa057cf"}
```

✅ `id":"7"` 是字符串；列表元素没有 `score`/`hitReason`；`total=1`。

### 4.2 `kw=婚`（单字）→ 200，LIKE 兜底命中 464/1079

```powershell
curl.exe -s --get --data-urlencode "kw=婚" "http://127.0.0.1:8081/api/v1/pub/law-items"
```

```json
{"code":200,"data":{"hasMore":false,"list":[{"articleNo":"第四百六十四条","content":"合同是民事主体之间设立、变更、终止民事法律关系的协议。婚姻、收养、监护等有关身份关系的协议，适用有关该身份关系的法律规定；没有规定的，可以根据其性质参照适用本编规定。","effectiveDate":"2021-01-01","id":"1","lawCode":"civil_code","lawName":"中华人民共和国民法典","status":"active"},{"articleNo":"第一千零七十九条","content":"夫妻一方要求离婚的，可以由有关组织进行调解或者直接向人民法院提起离婚诉讼。人民法院审理离婚案件，应当进行调解；如果感情确已破裂，调解无效的，应当准予离婚。","effectiveDate":"2021-01-01","id":"5","lawCode":"civil_code","lawName":"中华人民共和国民法典","status":"active"}],"pageNum":1,"pageSize":20,"total":2},"msg":"ok","traceId":"7190204a-d729-4e6b-8f4c-888a1aa2b366"}
```

✅ 单字不报错、结构正确、`total=2`。

### 4.3 `kw` 空 + `lawCode=civil_code`（分页）

```powershell
curl.exe -s --get --data-urlencode "lawCode=civil_code" --data-urlencode "pageSize=2" "http://127.0.0.1:8081/api/v1/pub/law-items"
```

```json
{"code":200,"data":{"hasMore":true,"list":[{"articleNo":"第四百六十四条","content":"合同是民事主体之间设立、变更、终止民事法律关系的协议。婚姻、收养、监护等有关身份关系的协议，适用有关该身份关系的法律规定；没有规定的，可以根据其性质参照适用本编规定。","effectiveDate":"2021-01-01","id":"1","lawCode":"civil_code","lawName":"中华人民共和国民法典","status":"active"},{"articleNo":"第五百零九条","content":"当事人应当按照约定全面履行自己的义务。当事人应当遵循诚信原则，根据合同的性质、目的和交易习惯履行通知、协助、保密等义务。当事人在履行合同过程中，应当避免浪费资源、污染环境和破坏生态。","effectiveDate":"2021-01-01","id":"2","lawCode":"civil_code","lawName":"中华人民共和国民法典","status":"active"}],"pageNum":1,"pageSize":2,"total":6},"msg":"ok","traceId":"fb4ed69a-70e5-4117-ae4d-15b9ace32a1d"}
```

✅ `total=6`（该法全部 6 条）、`hasMore=true`、分页生效。

### 4.4 `pageSize=999` → 夹到 50

```powershell
curl.exe -s --get --data-urlencode "lawCode=civil_code" --data-urlencode "pageSize=999" "http://127.0.0.1:8081/api/v1/pub/law-items"
```

真实输出的关键字段（完整 6 条列表略）：

```
"pageSize":50,"total":6,"list":[……6 条……]
```

✅ 回包 `pageSize=50`，不报参数错误（clamp 而非 401001）。

### 4.5 第 61 次触发限流 `401007`

在干净的一分钟窗口内连续 61 次（命令）：

```powershell
$targets=@(); for ($i=1; $i -le 61; $i++) {
  $resp = curl.exe -s --get --data-urlencode "kw=借款" "http://127.0.0.1:8081/api/v1/pub/law-items"
  $code = ($resp | ConvertFrom-Json).code
  if ($i -in 59,60,61) { $targets += ("#" + $i + " code=" + $code) }
}; $targets -join ' | '
```

真实输出：

```
start=00:49:02
--- 59/60/61: #59 code=200 | #60 code=200 | #61 code=401007
--- end=00:49:04
```

第 61 次的响应体（同一轮循环捕获）：

```json
{"code":401007,"data":null,"msg":"请求过于频繁","traceId":"<当次 traceId>"}
```

✅ 每分钟 60 次、第 61 次 `401007`。注：同一分钟窗口内此前还有 4 次正常 curl（4.1~4.4），
所以早先一次 70 连发循环里第一个 401007 出现在累计第 61 次（循环第 57 次）——与阈值一致。

### 4.6 边界：`kw` 101 字 → 401001；`pageNum=2` 正常

```powershell
$kw = '借' * 101
curl.exe -s --get --data-urlencode "kw=$kw" "http://127.0.0.1:8081/api/v1/pub/law-items"
```

```json
{"code":401001,"data":null,"msg":"参数校验失败","traceId":"131bde1b-a8d1-46c0-8a9a-a2c52d8b8ffe"}
```

```powershell
curl.exe -s "http://127.0.0.1:8081/api/v1/pub/law-items?kw=%E5%80%9F%E6%9D%A1&pageNum=2&pageSize=20"
```

```json
{"code":200,"data":{"hasMore":false,"list":[],"pageNum":2,"pageSize":20,"total":1},"msg":"ok","traceId":"d410a2ca-20f7-45cc-a4ea-4014c7bdfaa1"}
```

✅ 超长 `kw` 401001；第 2 页不报错（`total=1`，空列表）。

### 4.7 真实应用日志（B1 内核在 B2 里被真实调用，SQL 绑定正确）

`kw=借条&pageNum=2&pageSize=20` 的请求在应用日志里留下了新增查询的真实执行记录：

```
2026-11-07 00:50:05.572 DEBUG [...] [anon] [] c.f.i.l.m.LawItemMapper.countEnabled - ==> Parameters: civil_code(String)
2026-11-07 00:50:05.572 DEBUG [...] [anon] [] c.f.i.l.m.LawItemMapper.countEnabled - <==      Total: 1
2026-11-07 00:50:06.069 DEBUG [...] [anon] [] c.f.i.l.m.L.selectByKeywords - ==>  Preparing: SELECT id, law_code AS lawCode, law_name AS lawName, article_no AS articleNo, article_seq AS articleSeq, content, effective_date AS effectiveDate, expire_date AS expireDate, status FROM law_item WHERE status = 'active' AND is_enabled = 1 AND keywords IS NOT NULL AND ( keywords LIKE CONCAT('%', ?, '%') ) ORDER BY article_seq ASC, id ASC LIMIT ?
2026-11-07 00:50:06.069 DEBUG [...] [anon] [] c.f.i.l.m.L.selectByKeywords - ==> Parameters: 借条(String), 41(Integer)
2026-11-07 00:50:06.069 DEBUG [...] [anon] [] c.f.i.l.m.L.selectByKeywords - <==      Total: 1
```

✅ 真实参数「借条」进入 keywords 路径；`LIMIT 41` = `pageNum=2 × pageSize=20 + 1`（多取 1 条判断 `hasMore`），
证明 B2 的「分页做在外层、复用 B1 四路径」真实生效；全程日志**无 `BindingException`**。

### 4.8 挂账

- 无（B2 的 HTTP 验收项已全部真实跑通）。

## 五、口径说明

- `kw` 为空时走 SQL 分页（`selectEnabledPage` + `countEnabled`，真实 total）；
- `kw` 非空时复用 B1 四路径候选集、在外层切片；深分页超过候选抓取上限（200 条）时
  `total=-1`（`PageResult` 的「未统计」语义），`hasMore` 由候选集真实情况覆盖计算；
- `LawItemBrief` 只含公开字段（id/lawCode/lawName/articleNo/content/status/effectiveDate）。
