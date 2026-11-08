# 段 3 · C2 提交问题（`POST /app/ai/conversations`）· 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 C2（L606–L643）
> 产出：`modules/ai/controller/AiConversationController.java`、`modules/ai/service/AiQuestionService.java`、
> `modules/ai/dto/{SubmitQuestionRequest,SubmitQuestionResponse,SubmitOutcome,PendingAsk}.java`、本文档
> 数据来源：JWT 的 userId → ai_conversation / ai_message 写库；B1/D1/D2/C5 的返回值
> 证据日期：2026-11-07 · 执行人：be-stream
>
> **本环境为 mock（`faze.ai.mock=true`）**：C2 只做「提交 + 落库 + 返回 streamUrl」，不调模型，
> 所以本节证据真实；但整体锚点判定仍记 mock 挂账。
> 请求方式：`curl --data-binary @<UTF-8 文件>`（**不用 PowerShell 内联字符串**——实测会把中文编码成 `?`，落库成 `????????`，那是 L4 数据错误）。
> 鉴权：`Authorization: Bearer <accessToken>`（测试账号 `13800000001 / 123456`）。

---

## 一、新问题 → 返回 conversationId + streamUrl，落库 1 行会话 + 1 条 user 消息

请求（`request body` 文件 UTF-8 无 BOM）：

```json
{"question":"借条没有约定还款期限，出借人能不能随时要回？"}
```

真实响应：

```http
HTTP 200
{"code":200,"data":{"conversationId":"4","degraded":false,"messageId":"68f60cc3-88fb-4783-9bda-73959978dd6e","streamUrl":"/api/v1/app/ai/stream?conversationId=4&fromSeq=0"},"msg":"ok","traceId":"50781836-4649-44ae-acce-1d4f29069efa"}
```

判读：
- `conversationId` 是 **Long → JSON 字符串**（`"4"`，`JacksonConfig` 全局规则）；
- `messageId` 是 **UUID 追踪 id**（`68f60cc3-...`），**不是** assistant 消息 id——assistant 真 id 在 `event:start` 给出（见 `C3-stream.md` 的 `messageId=36`）；
- `streamUrl = /api/v1/app/ai/stream?conversationId=4&fromSeq=0`（固定带 `fromSeq=0`）；
- `degraded=false`。

库内核对（真实查询）：

```
id	user_id	title_len	title_hex
4	1	22	E5809FE69DA1E6B2A1        ← "借" 的 UTF-8 字节，说明中文正确落库
id	role	len	hex3	content_id
35	user	22	E5809FE69DA1E6B2A1	NULL   ← user 消息 refs 为 NULL、无 content_id
36	assistant	130	E6A0B9E68DAEE3808A	AI-9eb5378830894a05b85ae12789182472
```

- `ai_conversation` 新增 1 行，`title` 是问题原文（22 字 < 30，不截断），`lang='zh-Hans'`；
- `ai_message` 有 1 条 `role='user'`，原文完整（22 字，HEX 证明是中文不是 `?`），`refs` 为 NULL（D4：user 消息不存 refs）。

## 二、会话标题：前 30 字 + 「…」（40 字问题）

请求：`{"question":"我在公司工作了五年，现在公司要辞退我，经济补偿金应该怎么计算，需要准备哪些材料？"}`（40 字）

真实响应：`{"code":200,"data":{"conversationId":"5",...}}`

库内真实输出：

```
id	len	title
5	31	我在公司工作了五年，现在公司要辞退我，经济补偿金应该怎么计算…
```

判读：`CHAR_LENGTH(title)=31` = 前 30 个字符 + 1 个省略号；按 **code point** 截断，不会把代理对切坏。

## 三、传别人的 `conversationId` → `401008`（HTTP 403，不是 404）

会话 4 属于 user 1；用 user 9227 的 token 提交 `{"conversationId":4,...}`：

```http
HTTP 403
{"code":401008,"data":null,"msg":"无权限访问该资源","traceId":"3c3adf7d-16c5-41a9-b42c-33729c1c3cad"}
```

判读：归属条件进 SQL（`WHERE id=? AND user_id=? AND deleted_at IS NULL`），查不到统一 403，
**不泄露「会话存在但无权」**（工作流 C2 第 5 条）。

## 四、超长问题（1001 字）→ `401001`

```http
HTTP 200
{"code":401001,"data":null,"msg":"question: 问题长度不能超过 1000 字","traceId":"254896d3-1d51-45ae-b62c-21e2e6669f09"}
```

判读：Bean Validation（`@Size(max=1000)`）在进 Service 前拦截；不预扣配额、不建会话。
（1000 字边界此前也实测通过：`code=200`、正常建会话；边界实现见 `SubmitQuestionRequest`。）

## 五、`ai_enabled=false` → 降级（`degraded:true`，HTTP 200，不报错）

操作：`UPDATE sys_config SET config_value='false' WHERE config_key='ai_enabled'`，等 6 秒（AiProperties 5 秒缓存）后提交。

真实响应：

```http
HTTP 200
{"code":200,"data":{"conversationId":null,"degraded":true,"messageId":null,"streamUrl":null},"msg":"ok","traceId":"ec36b5fb-..."}
```

同时核对（真实查询）：

```
会话数 before=2 after=2（不建会话）
配额 used before=4 after=4（不预扣）
```

判读：`ai_enabled=false` 时 **HTTP 200 + code 200**，只带 `degraded:true`，不建会话、不写消息、不扣配额；
F4 据此切纯法条检索（`3-A7` 的 App 侧）。改回 `true` 后等 6 秒恢复。

## 六、C2「算做完」逐条对照

| 判据 | 结果 |
|---|---|
| 新问题 → `conversationId` + `streamUrl`；`ai_conversation` 1 行、`title` 是前 30 字 | ✅ 第一、二节（22 字不截断；40 字 → 31 字含省略号） |
| `ai_message` 有 1 条 `role='user'` | ✅ 第一节（id=35，refs NULL、中文 HEX 正确） |
| 传别人的 `conversationId` → `401008`，不泄漏「会话存在但无权」 | ✅ 第三节（HTTP 403 + 401008） |
| 关掉 `ai_enabled` → `degraded:true`，HTTP 200 | ✅ 第五节（会话/配额均不变） |
| 超长 `question`（1001 字）→ `401001` | ✅ 第四节 |
| 顺序：①开关 ②配额 ③会话 ④user 消息 ⑤输入拦截 ⑥检索 ⑦Prompt ⑧返回 | ✅ 代码顺序见 `AiQuestionService#submit`（拦截在写 user 消息之后，D2 要求原文落库） |
| 输入拦截命中：user 原文已落库 + assistant 固定引导文案、refs NULL、配额回退 | ✅ 代码路径已实现（`insertGuideMessage` + `refund`）；**mock 下未单独构造拦截请求**，见 D2 任务证据 |
| 不加幂等键 | ✅ 无幂等键（连点两次算两次，见 C3 的多条 assistant 消息证据） |

## 七、挂账

1. 路由到 SSE 的端到端（提交 → 订阅 → 收流）见 `C3-stream.md`。
2. 真 key 下 `POST` 不调模型，本节不受 mock 影响；但 `3-A2`/`3-A3`/`3-A4`/`3-A8` 的锚点判定仍挂账。
3. 输入拦截（408004/408005）的端到端证据归 D2（`D2-input-guard.md`），C2 只保证「落库 + 回退配额 + 返回码」的编排位置正确。
