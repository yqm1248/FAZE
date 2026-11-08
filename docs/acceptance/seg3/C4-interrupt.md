# 段 3 · C4 中断 / 首字节超时 / 流上限 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 C4（L687–L710）
> 产出：`modules/ai/service/AiStreamService.java` 的首字节超时 / 单次上限 / 断线回收 / 上游失败分支
>          + `modules/ai/config/{AiStreamScheduler,AiStreamSchedulerConfig}.java`（延迟任务）
> 数据来源：`sys_config.ai_first_byte_timeout_ms` / `ai_timeout_seconds`、`AiQuotaService`、`ai_message`
> 证据日期：2026-11-07 · 执行人：be-stream
>
> **本次为 mock（`faze.ai.mock=true`）**：三个分支的**代码路径**真实执行，但 `3-A2`/`3-A3`/`3-A4`/`3-A8` 锚点记挂账。
> 复现方式：`UPDATE sys_config SET config_value=... WHERE config_key=...` → 等 6 秒（AiProperties 5 秒缓存）→ 提交 + 订阅。

---

## 一、单次流上限（`ai_timeout_seconds=3`）：主动发 `done` + timeout，不静默断开

> **修复记录（重要）**：第一版用 `SseEmitter` 的容器 timeout 做上限，容器超时后再 `send` 会被拒绝——
> 实测「DB 写了截断提示、客户端收不到 `done`」，正是 lead 发现的 id=32 那条。
> 现改为：容器 timeout 设 `ai_timeout_seconds + 5` 秒只做兜底；用 `aiStreamScheduler.schedule(timeoutSeconds)`
> **主动触发** `onStreamTimeout`，先发截断 delta、再发 done、最后 complete。

操作：`UPDATE sys_config SET config_value='3' WHERE config_key='ai_timeout_seconds'` → 等 6 秒 → 提交 → 订阅。

真实输出（user 9227，conversation 7）：

```
提交: {"code":200,"data":{"conversationId":"7",...,"streamUrl":"/api/v1/app/ai/stream?conversationId=7&fromSeq=0"}}
耗时=3092ms delta=47 done=1 error=0
--- 尾部 8 行 ---
data:{"content":"生纠"}

event:delta
data:{"content":"\n\n（回答较长已截断）"}

event:done
data:{"messageId":"47","finishReason":"timeout","promptTokens":null,"completeTokens":null,"degraded":false}
```

判读：
- **3.09 秒**触发（配置 3 秒），不是静默断开；
- 先发一条截断 delta（「回答较长已截断」），再发 `done {finishReason:"timeout"}`；
- `done` 的 token 为 null：3 秒内上游最后一帧（带 usage）还没到，属预期；
- 库内该消息：

```
id	role	len	tail	content_id IS NULL	refs IS NULL	prompt_tokens	complete_tokens
47	assistant	103	，以便在发生纠…（回答较长已截断）	0	1	NULL	NULL
```

  → content 含截断提示、**content_id 非 NULL（是 AI 内容）**、**refs 为 NULL（没走完 D4）**。

## 二、首字节超时（`ai_first_byte_timeout_ms=1`）：`508001` + 配额回退 + 固定文案

操作：`UPDATE sys_config SET config_value='1' WHERE config_key='ai_first_byte_timeout_ms'` → 等 6 秒 → 提交 → 订阅。

真实输出：

```
提交: {"code":200,"data":{"conversationId":"7",...}}
delta=0 done=0 error=1
--- 全部事件 ---
event:start
data:{"conversationId":"7","messageId":"49","model":"doubao-seed-1.6-32k"}

event:error
data:{"code":508001,"msg":"AI 服务暂时不可用，请稍后重试"}
```

配额核对（`GET /app/ai/quota` 的 used 走同一 Redis key）：

```
配额 used: u0=3 提交后=4 失败后=3（回退到 u0）
```

库内该消息：

```
id	role	content	cid_null	refs_null	prompt_tokens	complete_tokens
49	assistant	服务暂时不可用	1	1	NULL	NULL
```

判读：
- 首字节超时（1ms，任何 chunk 都来不及）→ 发 `error {code:508001}`，回退配额（**生成开始前失败不扣**）；
- 落库固定文案「服务暂时不可用」；该文案**不是模型输出** → `content_id` 留 NULL、`refs` 留 NULL（没查过）。

## 三、用户主动中断（`abort()` → 取消上游）：**mock 下挂账**

设计（`AiStreamService` 类注释已写）：RN 的 `abort()` 与网络断线在服务端都是「emitter 断开」，无法区分。
若一断就 `dispose()`，`3-A8` 的「断网 10 秒重连从断点接上」物理上做不到。因此：

1. 断线时**不立即取消**上游，继续消费并写 Redis/刷库（`logs` 可见 `AI 流客户端断开，等待重连接管 messageId=51 window=30s`）；
2. 挂 **30 秒**回收任务；窗口内重连接管则取消回收（C3 第五节已实测：断线 1.2 秒重连成功）；
3. 30 秒仍无人接管 → `dispose()` + 保留半截 + 日志 `UPSTREAM_CANCELED`。

**挂账原因**：mock 的 Stub 全文 4.3 秒生成完，30 秒窗口内必然已经 `onComplete`，**无法构造「超过 30 秒仍在生成」的流**，
所以本环境无法触发 `UPSTREAM_CANCELED` 日志。真 key 接入一个长回答（>30 秒）后按下列步骤复验：
提交 → `curl --max-time 2` 断开 → 不重连 → 等 35 秒 → `grep UPSTREAM_CANCELED logs/faze-api.out.log` → 查 `ai_message` 保留半截。

> 与工作流 C4 第 1 条原文「中断即 cancel」的差异已在 `C3-stream.md` 第九节与类注释写清，lead 已同意登记 §九。

## 四、上游中途失败：**mock 下挂账**

Stub 不产生上游错误（固定文本），无法构造「已出字后上游失败」。
真 key 时可用「生成到一半把 baseUrl 改成错的」或上游 5xx 触发。代码路径已实现（`onUpstreamError`）：
已出字 → 保留半截 + `event:error {code:508001, msg:"生成中断，请重试"}` + **不重试** + 照扣配额（C5 口径）；
未出字 → 回退配额 + 固定文案。

## 五、`SseEmitter` 已断开再 `send`：不变成 500

- 代码：`sendEvent` 捕获 `IllegalStateException | IOException` 并返回 false，`completeQuietly` 同样静默（C4 卡住项第 2 条）；
- 实测：`curl --max-time 1` 断开后，服务端继续刷库并最终 `onComplete`（日志 `AI 流完成 messageId=51 ... chars=130`），
  **HTTP 层没有 500**（客户端已断开，无响应可回）；
- ⚠️ **附带发现（需 lead 处理，不在 C 组文件）**：SSE 失败后容器做 **ASYNC dispatch**，此时无状态 JWT 的
  `JwtAuthenticationFilter` 已跳过、SecurityContext 已清空，`AuthorizationFilter` 对 `/api/v1/app/**` 重新授权失败，
  日志里出现 `AuthorizationDeniedException` + `ErrorMvcAutoConfiguration$StaticView - Cannot render error page`（3 条 ERROR）。
  **不影响**本次流的落库与完成（时序可证：ERROR 在 00:55:32.226，刷库/完成在 00:55:32.4~35.2），但日志噪音大。
  建议 lead 在 `SecurityConfig` 的 `authorizeHttpRequests` 里加
  `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()`（Spring Security 官方对 SSE/异步请求的标准做法）。
  已单独报给 lead。

## 六、`content_id` / `refs` 五种语义（lead 要求逐条给出库内实测）

| 分支 | content | `content_id` | `refs` | 库内实测（id） |
|---|---|---|---|---|
| 正常完成（D3 OK + D4） | 最终回答（D3 处理后的文本） | **生成**（命中禁语也生成；`degraded` 只影响展示） | 数组（`[]` = 查过没引用；`≥1` = 快照） | 34/36/39/41/43/51：`cid_null=0`、`refs_null=0`、refs_cnt=1、tokens 有值 |
| 流上限截断 | 半截 + 「（回答较长已截断）」 | **生成**（是 AI 内容） | **NULL（没走完 D4）** | 47：`cid_null=0`、`refs_null=1`、tokens NULL |
| 上游中途失败（已出字，保留半截） | 半截 | **生成** | **NULL** | mock 未构造（挂账） |
| 首字节超时 / 未出字失败 | 「服务暂时不可用」（固定文案） | **NULL（非模型输出）** | **NULL** | 49：`cid_null=1`、`refs_null=1` |
| D3 判 OUTPUT_INVALID | 固定文案「本次回答未通过校验，请重试」 | NULL | `[]`（lead 裁决：先落库再回 508002） | mock 未构造（Stub 文本含真实条号，D3 判 OK；D3 有单测） |
| 用户消息（对照组） | 问题原文 | NULL | NULL | 全量 SQL：9/9 行 user 的 model/prompt/complete/content_id/refs 全 NULL |

全量核对 SQL（真实输出）：

```sql
SELECT COUNT(*) AS user_rows, SUM(model IS NULL) AS model_null, SUM(prompt_tokens IS NULL) AS prompt_null,
       SUM(complete_tokens IS NULL) AS complete_null, SUM(content_id IS NULL) AS content_id_null,
       SUM(refs IS NULL) AS refs_null
FROM ai_message WHERE role = 'user';
→ 9	9	9	9	9	9
```

## 七、C4「算做完」逐条对照

| 判据 | 结果 |
|---|---|
| 生成中点「停止」→ 上游被取消（日志 `UPSTREAM_CANCELED`），content 保留半截 | **挂账**：mock 流 4.3 秒 < 30 秒回收窗口，无法构造；设计见第三节 |
| 把 `ai_first_byte_timeout_ms` 改成 1 → 每次都在第 2 秒前拿到 `508001` | ✅ 第二节：`event:error {code:508001}`，耗时 < 2 秒 |
| 把 `ai_timeout_seconds` 改成 3 → 3 秒后收到 `done` + `timeout` | ✅ 第一节：3.09 秒收到 `done {finishReason:"timeout"}` + 截断提示 |
| 三种情况都**不扣**配额 | ⚠️ 按 C5 第 3 条口径执行（已与 lead 确认）：首字节超时**回退**（第二节实测 3→4→3）；流上限**已出字照扣**（第一节实测 2→3→3）；用户中断若已出字照扣。C4 原文第 4 条与 C5 第 3 条冲突，按 C5 执行 |
| SseEmitter 断开再 send 不 500 | ✅ 第五节（捕获忽略；HTTP 无 500），附带 Security async dispatch ERROR 已报 lead |
| 上游中途失败保留半截 + error、不重试 | 代码路径已实现；mock 无法构造 → 挂账 |

## 八、挂账清单

1. 用户主动中断 → `UPSTREAM_CANCELED`（需 >30 秒的真流；mock 无法构造）。
2. 上游中途失败（需真 key 或上游 5xx）。
3. `3-A2`/`3-A3`/`3-A4`/`3-A8` 锚点：mock 下不判过。
4. 真机录屏（用户点停止）由 F 组/负责人 121 执行；本节是服务端协议级证据。
