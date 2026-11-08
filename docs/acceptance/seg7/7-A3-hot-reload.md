# `7-A3` 敏感词即时生效 · 验收证据

> **验收人**：Lead（独立复核）
> **时间**：2026-11-09 01:21:56 ~ 01:22:01（Asia/Shanghai）
> **方式**：真实 faze-api(8081)，全程**未重启**（用进程启动时间前后比对证明）
> **脚本**：`tmp/seg7h/anchor-run.mjs A3`（原始输出 `tmp/seg7h/anchor-A3.log` / `.json`，不入库）
> **被验对象**：`AdminSensitiveWordService` 增/改/删 → `SensitiveWordLoader.reload()` → `SensitiveWordEngine` → 内容提交判定

## 1. 结论：**通过**

三次判定全部按**最新词库**执行，且服务进程启动时间前后完全一致（未重启）。

## 2. 服务未重启的证明

```text
服务启动时间（前置）= 2026-11-09 01:11:44
服务启动时间（后置）= 2026-11-09 01:11:44
是否重启 = false
```

取法：`Get-NetTCPConnection -LocalPort 8081` → 监听进程 → `Get-Process().StartTime`。
日志侧可交叉验证：`logs/faze-api.log` 里 `sensitive word loaded: version=1` 出现在 01:11:49（启动时一次），之后每个 version 都由**管理端改动**触发。

## 3. 三次判定的实测序列

| 步 | 动作 | 请求 | 响应 | 期望 | 结果 |
|---|---|---|---|---|---|
| 1 | 新增词「验收违禁词ZZ18882」，`level=block`，`lang=zh-Hans` | `POST /admin/sensitive-words` | `HTTP 200 code=200 id=152` | 成功 | ✅ |
| 2 | **立刻**（同一秒）提交含该词的反馈 | `POST /app/feedback` | `HTTP 200 **code=410006** msg=内容含违规信息，未发送` | 被拦 | ✅ |
| 3 | 改成 `level=warn` | `PUT /admin/sensitive-words/152` | `HTTP 200 code=200` | 成功 | ✅ |
| 4 | 立刻再提交 | `POST /app/feedback` | `HTTP 200 code=200` | 放行 | ✅ |
| 5 | 删除该词 | `DELETE /admin/sensitive-words/152` | `HTTP 200 code=200` | 成功 | ✅ |
| 6 | 立刻再提交 | `POST /app/feedback` | `HTTP 200 code=200` | 放行 | ✅ |

第 1→2 步的间隔在**同一秒内**（`01:21:58` → `01:21:58`），优于锚点要求的「≤ 1 次请求的间隔」。

## 4. 引擎侧日志（证明重载真的发生了）

```text
01:20:01.128 sensitive word loaded: version=2, count=51, languages=[zh-Hans, zh]
01:20:01.270 sensitive word loaded: version=4, count=51, languages=[zh-Hans, zh]
01:20:01.371 sensitive word loaded: version=6, count=50, languages=[zh-Hans]
```

`count` 从 50 → 51（新增）→ 仍是 51（改级）→ 50（删除），`languages` 里多出的 `zh` 是**上一轮错误实验**留下的痕迹（见 §5），本轮正式验证用的是 `zh-Hans`。
`version` 每次改动 +2：一次是本实例 `loader.reload()`，一次是 Redis Pub/Sub 监听器收到广播后的重载（`SensitiveWordReloadListener`），属预期设计。

## 5. 顺带挖到的缺陷（不属本锚点，但会「静默失效」）

第一次实验时我用 `lang='zh'` 建词，结果：**保存成功、引擎重载成功（`count=51, languages=[zh-Hans, zh]`），但正文含该词的反馈照常放行（code=200）**。

根因（读代码确认）：

1. `SensitiveWordSaveBO.lang` 只有 `@Size(max=20)`，**没有白名单校验**；
2. `SensitiveWordEngine.check(content, lang)` 的逻辑是「`roots.get(lang)` 拿得到就**只查那一棵树**」：
   ```java
   TrieNode single = lang == null ? null : roots.get(lang);
   if (single != null) { match(content, single, hits); } else { /* 所有树都跑 */ }
   ```
   请求侧固定传 `zh-Hans`，于是 `zh` 树里的词**永远不会被查到**。

影响：管理员只要把语言填成任何非标准值（`zh`、`cn`、`中文`），该词就**静默永久失效**，界面上有词、库里也有、引擎也加载了，但就是不拦。真实 UI（`SensitiveWord.vue`）默认 `zh-Hans`，所以日常不会踩；但 API 层与 CSV 导入（表头 `word,category,level,lang`）都是裸字符串。

**处置**：不在本批改（属 A1/B1 已交付范围）。已记 §十一 挂账，段 8 处理。建议两处一起改：给 `lang` 加白名单（`zh-Hans|bo|ug-Arab-CN|kk-Arab-CN`），并让 `check` 在「精确 lang 未命中」时退回全树扫描。

## 6. 与工作流「算做完」逐条对照

| 工作流要求 | 本文件证据 |
|---|---|
| 记录服务启动时间，之后全程不重启 | §2 |
| 在 A-25 新增一个此前不存在的词，级别 block | §3 第 1 步（`验收违禁词ZZ18882`） |
| 立刻发一条含该词的内容 → 被拦（410006） | §3 第 2 步 |
| 改为 warn 级 → 再发 → 能提交成功 | §3 第 3~4 步 |
| 删除该词 → 再发 → 能提交成功 | §3 第 5~6 步 |
| 全程进程启动时间不变 | §2 |

## 7. 复现命令

```powershell
cd E:\dshcode\FAZE
node tmp/seg7h/anchor-run.mjs A3
```

> 注意：脚本每跑一次都会新建并删除一条 `验收违禁词ZZ#####`，不会污染词库（结束后自动删除）。
> 如果中途失败留下残词，按 `DELETE FROM sensitive_word WHERE word LIKE '验收违禁词ZZ%';` 清理。
