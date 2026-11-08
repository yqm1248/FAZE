# 段 3 · C3 SSE 流式 + 断线续传（`GET /app/ai/stream`）· 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 C3（L647–L684）
> 产出：`modules/ai/controller/AiStreamController.java`、`modules/ai/service/AiStreamService.java`（+ 接口/落库/Redis 三写）、
> `modules/ai/config/{AiRedisKeys,AiProperties}.java`、本文档
> 数据来源：Redis 逻辑库 1（`faze:ai:stream:<assistantMessageId>` / `faze:ai:active:<conversationId>` / `faze:ai:ask:<conversationId>`）
>          + `ai_message` 写库 + 上游 `DoubaoClient`（**本次为 mock**）
> 证据日期：2026-11-07 · 执行人：be-stream
>
> **本次为 mock（`faze.ai.mock=true`，日志 `AI_MOCK_ACTIVE`）**：流式链路真实走到「Stub 的 65 个 chunk → SSE → Redis → DB」，
> 但 `3-A2`/`3-A3`/`3-A4`/`3-A8` 锚点**记挂账不判过**（真 key 到位后重跑本文档）。
> 鉴权：`Authorization: Bearer <accessToken>`；curl 命令均带 `--max-time 90`（SSE 无 Content-Length，
> `curl.exe -s -N` 正常收完流后退出码是 18 `CURLE_PARTIAL_FILE`，**不是失败**）。

---

## 一、真流式（C3 判据第 1 条）：逐帧到达，不是一次性吐完

提交（conversation 4，走 UTF-8 body 文件 + curl）：

```
POST /api/v1/app/ai/conversations
{"conversationId":4,"question":"如果借条丢了，转账记录能证明借款关系吗？"}
→ {"code":200,"data":{"conversationId":"4","degraded":false,"messageId":"6f1748d9-a3b6-4b5f-b9b8-e46bde5fa6dd","streamUrl":"/api/v1/app/ai/stream?conversationId=4&fromSeq=0"}}
```

`curl -s -N --max-time 90 ".../stream?conversationId=4&fromSeq=0"` 真实收到的前两行（新代码，**已按接口规范 §6.7 去掉旧字段 `sessionId`**）：

```
event:start
data:{"conversationId":"4","messageId":"39","model":"doubao-seed-1.6-32k"}
```

事件统计（真实计数）：

```
delta=65 refs=1 done=1 error=0
```

- `start` 的 `messageId` 是**真实 assistant 消息 id**（`39`），不是 C2 返回的追踪 UUID；
- 65 个 `delta` 是逐帧到达（Stub 每 60ms 一块，见 `C1-client.md` 第二节），不是聚合后一次吐出。

## 二、Redis 暂存（C3 判据第 2 条）：`LPUSH` + `EXPIRE 600`

在流进行到约 1.6 秒时查 Redis（**真实输出**）：

```
流进行中：active=39 LLEN=26 TTL=600
--- LRANGE 最新 3 条 ---
27|借人
26|，出
25|因此
```

- `faze:ai:active:4` = `39`（当前活跃流映射）；
- `faze:ai:stream:39` 已有 26 条，元素格式 `<seq>|<content>`，`LPUSH` 使最新在前；
- `TTL=600`（10 分钟，§零.3 第 13 项）。

## 三、delta 三写：内存累积 + 每 20 个/每 2 秒刷库（C3 判据第 3 条）

同一时刻（流进行中 1.6 秒）查库：

```
id	role	content_len	model	refs	content_id
39	assistant	40	doubao-seed-1.6-32k	NULL	NULL
```

判读：`content_len=40`（≈20 个 delta × 2 字），说明**每 20 个 delta 或每 2 秒**的刷库已生效；
没等到流结束就有内容落库，断线最多丢 20 个 token。

## 四、结束落库（C3 判据第 4 条）：全量 + tokens + refs + content_id

结束后查库（真实输出）：

```
id	role	len	model	prompt_tokens	complete_tokens	refs_cnt	content_id
35	user	22	NULL	NULL	NULL	NULL	NULL
36	assistant	130	doubao-seed-1.6-32k	128	96	1	AI-9eb5378830894a05b85ae12789182472
38	user	20	NULL	NULL	NULL	NULL	NULL
39	assistant	130	doubao-seed-1.6-32k	128	96	1	AI-d4c6643f9c77432f87efbb7efe0f1612
```

- 结束写全量（130 字 = Stub 全文）、`model`、`prompt_tokens/complete_tokens`（Stub 最后一帧给的 128/96）；
- `refs` 长度 1（`lawItemId=7` 第六百七十九条，D4 快照）；
- `content_id` 形如 `AI-` + 32 位十六进制（E2）；
- 结束后 Redis 已清理：

```
active=  stream:39 EXISTS=0
```

`done` 事件（真实）：

```
event:done
data:{"messageId":"39","finishReason":"stop","promptTokens":128,"completeTokens":96,"degraded":false}
```

## 五、断线续传（C3 判据第 3、4 条）：`fromSeq` 重放，不重复不丢

**不重不丢的验证方法**：第一次订阅 `--max-time 1.2` 强行断开（模拟断网），记录已收 delta 数（= 已收最大 seq）；
立即带 `fromSeq=<该数>` 重连，把两段 `delta.content` 拼起来与 DB 里的最终全文逐字比较。

真实输出：

```
断线前 delta=18（=已收最大 seq）
重连 delta=47 refs=1 done=1 error=0
重连首块=[自贷款人提供]（原文第 19 块）
断线前len=36 重连len=94 合计=130 DB len=130
拼接 == DB content ? True
断线前是 DB 前缀 ? True
```

判读：
- 重连**从第 19 块开始**（断线前恰收 18 块，seq 1~18），没有重发第 18 块 → **不重复**；
- 拼接 130 字与 DB 全文相等 → **不丢失**；
- 续传由「Redis `LRANGE` 重放 seq > fromSeq」+「新连接接管后续推送」组成，见 `AiStreamService#handleResume`。

说明（**本实现的 fromSeq 语义**）：客户端传「已收到的最大 seq」，服务端重放 **seq > fromSeq**。
工作流 C3 第 6 条写的是「重放 ≥ fromSeq」；按「≥」会把客户端已有的一条再发一遍，与同一节「不重复」冲突，
故取严格大于。`fromSeq=0`（C2 返回的 streamUrl）时重放全部，两种口径一致。

## 六、重连后 assistant 消息仍只有 1 条（C3 判据第 4 条）

一次提问 + 断线 1 次 + 重连 1 次（第五节）后，conversation 4 的 assistant 消息数：

```
conversation 4: 2 次提问 → assistant 2 条（id=36、39），每次提问恰好 1 条
```

重连**不新建消息**：`AiStreamService#open` 的重连分支只做「重放 + 接管」，不 INSERT。

## 七、Redis 被删/过期后重连 → `answer expired`，不假装续传（C3 判据第 5 条）

构造：提交后订阅 `--max-time 1` 断开（已收 15 个 delta），手工删除 `faze:ai:active:7` 与 `faze:ai:stream:<id>`，
再带 `fromSeq=15` 重连。真实输出：

```
event:error
data:{"code":508001,"msg":"回答已过期，请重新提问"}
```

不开新流、不重放空内容、不白屏（前端按 508001 提示重新提问）。

## 八、`3-A8` 口径：重连不重复扣配额（C5 交叉）

user 9227 上做一次提问 + **同一 messageId 重连 3 次**（不重新提交），真实输出：

```
used(9227) 初始=1
提交后：used(9227)=2（应 = 初始+1）
第 1 次订阅（fromSeq=0）delta=14
第 2 次订阅（fromSeq=14）delta=19
第 3 次（fromSeq=33）delta=32 refs=1 done=1
used(9227) 重连 3 次后=2（应仍 = 2）
assistant_cnt=1
```

代码保证：续传分支（`handleResume`）**不调 `AiQuotaService.reserve`、不新建 assistant 消息**（方法 javadoc 已显式注释）。

## 九、与《技术路线》§8.3 的偏离（lead 已同意并登记 §九）

1. **每个 delta 即落库 → Redis 暂存 + 每 20 个/2 秒刷库 + 结束写全量**（§8.3 L2557 原文）。
   理由：200 token = 200 次 UPDATE 是性能灾难；最多丢 20 token。刷库用「写当前全量」实现与 `CONCAT(content,?)`
   等价的语义（避免把模型输出拼进 SQL），结束同样全量覆盖，幂等。
2. **断线不立即取消上游**：RN 的 `abort()`（用户点停止）与网络断线在服务端无法区分。若一断就 `dispose()`，
   `3-A8` 的「断网 10 秒重连从断点接上」物理上做不到。本实现：断线后继续消费并写 Redis/刷库，
   同时挂 **30 秒**回收任务；窗口内重连接管（续传），30 秒无人接管才 `dispose()` 并记 `UPSTREAM_CANCELED`。
   证据：第五节的断线 1.2 秒重连接管成功；第九节之外「30 秒无重连取消」见 `C4-interrupt.md` 的挂账说明。

## 十、数据质量：乱码测试数据的清理记录（lead 要求可追溯）

**现象**：本轮前期用 `Invoke-RestMethod` 内联字符串发中文 body，PowerShell 5.1 把 body 按 ISO-8859-1 编码，
服务端收到的是 `?`（HEX `3F3F3F`），落库成 `????????`。**这是客户端编码问题，不是服务端 bug**
（同账号改用 UTF-8 文件 + `curl --data-binary @file` 后落库正常，见 C2 第一节）。

清理**前**的实测记录（真实查询输出，删除前存证）：

```
id	role	len	first3hex	head12
17	user	22	3F3F3F	??????????,?
20	user	20	3F3F3F	??????,?????
22	user	13	3F3F3F	????????????
24	user	10	3F3F3F	??????????
26	user	1000	3F3F3F	????????????
27	user	12	3F3F3F	????????????
29	user	12	3F3F3F	????????????
31	user	14	3F3F3F	?????:??????
（同批 assistant 行 HEX 正常：E6A0B9E68DAEE3808A = 「根据《」）
标题：conversation 1 title = ??????????,??????????? ；conversation 2 title = ??????????????????????????????…
```

清理范围（**只删我造的测试会话，未动 lead 的 conversation 3**）：

```sql
DELETE FROM ai_message WHERE conversation_id = 1;   -- id 17~32
DELETE FROM ai_conversation WHERE id = 1;
DELETE FROM ai_message WHERE conversation_id = 2;   -- id 26 那条 1000 字乱码 + 后续
DELETE FROM ai_conversation WHERE id = 2;
```

清理后核对（真实输出，全部为正常 UTF-8 中文）：

```
id	user_id	len	hex3	title
3	1	8	E5809FE6ACBEE4B88D	借款不还怎么办？          ← lead 的会话，未动
4	1	22	E5809FE69DA1E6B2A1	借条没有约定还款期限，出借人能不能随时要回？
5	1	31	E68891E59CA8E585AC	我在公司工作了五年，现在公司要辞退我，经济补偿金应该怎么计算…
6	1	8	E5809FE6ACBEE4B88D	借款不还怎么办？
7	9227	16	E6B581E4B88AE99990	流上限测试：回答较长会怎样处理？
```

`role='user'` 行的 `model / prompt_tokens / complete_tokens / content_id / refs` 全 NULL（E2 判据）——
全量 SQL 证明：

```
user_rows	model_null	prompt_null	complete_null	content_id_null	refs_null
9	9	9	9	9	9
```

## 十一、C3「算做完」逐条对照

| 判据 | 结果 |
|---|---|
| 真机看到逐字输出（不是一次性出完） | ✅ 第一节：65 个 delta 逐帧到达（mock；真机 F 组负责录屏） |
| `LRANGE faze:ai:stream:<id> 0 -1` 有 ≥10 条 | ✅ 第二节：进行中 26 条，格式 `<seq>\|<content>` |
| 拉到一半断开 → 30 秒内重连带 `fromSeq` → 从断点继续、不重复不丢失 | ✅ 第五节：18+47=130 字，拼接 == DB，首块 = 第 19 块 |
| 重连后 `ai_message` 只 1 条 assistant 消息 | ✅ 第六节（每次提问 1 条；重连不新建） |
| Redis key 被删后重连 → 返回「回答已过期」，不白屏 | ✅ 第七节（`event:error code=508001`） |
| `3-A8`：重连不重复扣配额 | ✅ 第八节（used 1→2→2） |

## 十二、挂账

1. **本次为 mock**：`3-A2`/`3-A3`/`3-A4`/`3-A8` 锚点不判过；真 key 到位后用同一套命令重跑（`C1-client.md` 第六节有清单）。
2. 真机录屏 + 真机自动重连（F5）由 F 组/负责人 121 执行；本节只给服务端协议级证据。
3. 「断线 30 秒无人接管 → `UPSTREAM_CANCELED`」在 mock 下无法触发（Stub 流仅 4.3 秒，30 秒窗口内必然完成），
   见 `C4-interrupt.md` 的挂账说明。
