# 改动说明

<!-- 一句话讲清改了什么。关联的 DRIFT / DEFERRED / GAP 编号写在这里，没有就不写。 -->

**关联**：DRIFT-___ / DEF-___ / 无

**改了哪一层**（勾选）：`APP` · `服务端` · `PC 后台` · `数据库迁移` · `文档/基线` · `CI/脚本`

---

# 自动门禁（CI 会跑，不用在这里勾）

以下由 `.github/workflows/ci.yml` 自动执行，**红了先修，不要用「本地跑过」当理由**：

| 门禁 | 查什么 | 命令 |
|---|---|---|
| 后端测试 | 532 个用例（含 DB 门禁用例） | `mvn -B test` |
| 数据完整性巡检 | 21 条只读检查（A01~A21） | `npm run check:integrity` |
| APP 静态检查 | `tsc --noEmit` + `eslint` | `npm run verify`（在 `apps/mobile-app`） |
| 裸文本 | 禁裸 `<Text>`，必须走 `LocalizedText` | `pwsh scripts/check-raw-text.ps1` |
| i18n | 四语言目录同构 + 缺译登记不漂移 | `pwsh scripts/check-i18n.ps1` |
| 事实同步 | `docs/facts/*.json` 与源码一致 | `npm run check:facts` |
| 受控区块 | 文档里的生成表与事实一致 | `npm run check:render` |
| 基线指纹 | `docs/BASELINE.md` 的行数/字节/指纹 | `npm run check:baseline` |
| 设计稿映射 | 设计稿与页面一一对应、无漏登记 | `npm run check:pages` |
| 承诺清单 | 承诺条数不漂移、范围承诺在台账内 | `npm run check:contracts` |
| 文案口吻 | 用户可见文案无「演示 / 目标用户」等内部话术 | `npm run check:copy-tone` |
| 写入路径 | 每个字段都有写入证据，或已在基线里 | `npm run check:write-paths` |

---

# 人工回归清单（R-01 ~ R-20 中机器查不了的 7 条）

**只勾本次改动碰到的项。** 每条后面括号里写的是「什么情况下必须查」——不相关就整行删掉，别全勾。

- [ ] **R-03 归属校验必须带当前用户**（改了任何「按 id 查/改/删」的接口）
  禁止「先查任意一条、再比对 userId」，必须把 `userId` 写进 WHERE 条件。
  反例：DRIFT-014 的取消收藏——先 `LIMIT 1` 取任意用户的收藏行再判 403，结果是**越权没拦住、合法用户被误伤**。
  查法：`grep -rn "LIMIT 1" services/faze-api/src/main` 逐条看；本轮结果 8 处全部合法（都带 `ORDER BY` 或当前用户条件）。

- [ ] **R-08 前端错误文案按错误码分流**（改了任何接口调用或错误提示）
  禁止统一弹「网络错误」。必须走 `useErrorMessage` / `ErrorState`，按业务码给不同句子。
  验收：把接口改成一个已知业务码（如 `402001`），页面提示应与该码对应，而不是「网络错误」。

- [ ] **R-09 按钮 label 必须是动作名**（新增或改了按钮）
  「点击这里」「请上传」不算动作名；`上传营业执照`、`确认提交` 才算。四语言同步。

- [ ] **R-12 只读展示数据禁止写入 URL**（新增路由参数或页面跳转）
  只读详情页不该把整条数据塞进 query string。反例：把 `caseId` 之外的一整段内容序列化进 URL。
  正确：URL 带 id，详情页按 id 拉数据。

- [ ] **R-13 列表里的时间字段必须带语义标签**（新增或改了列表/卡片）
  「2026-11-13 08:52」不足以让用户知道这是下单时间还是完成时间，必须带「下单时间」「完成时间」这类标签。

- [ ] **R-16 进度/时间线标题禁止前端按 nodeCode 覆盖服务端值**（改了时间线/进度页）
  服务端已经给了节点标题与描述，前端只做展示与状态样式；自己按 `nodeCode` 拼一套文案就会与服务端不一致。

- [ ] **R-20 渲染类改动必须真浏览器验证**（改了 PDF / 富文本 / 打印 / 邮件模板）
  必须附一张真实渲染截图（`node scripts/cdp-shot.mjs <url> <out.png>`），**不接受「看着没问题」**。
  注意：`openhtmltopdf` 不做 GSUB/GPOS，含少数民族文字的 PDF 一律不许用它——这类改动必须说明用的是哪种渲染路径。

---

# 证据

- [ ] 跑过 `npm run verify`（APP 改动）或 `mvn -B test`（服务端改动），结果贴在下面
- [ ] 改了数据库 → 有新的 `V___` 迁移文件，且**没有改已应用的旧迁移**（会 checksum 失败）
- [ ] 改了基线内文档 → `npm run check:baseline` 全绿，且 `docs/CHANGELOG.md` 有本次条目
- [ ] 新增了「已知非零 / 已知缺口」→ 同时改了台账（`known-issues.json` / `write-paths-baseline.json` / `copy-tone-known.json` / `DEFERRED.md`），**且写了 closeWhen**

```
（把关键命令输出贴在这里）
```

---

# 自检：这次改动让哪条护栏变得更没必要存在了？

> 如果答案是「没有」，说明这次改动只是在加功能，没有减少以后返工的可能。写一句，没有就写「无」。

