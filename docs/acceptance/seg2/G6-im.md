# 第 2 段 · G6「IM 会话」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze` / Redis db1）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G6（6 个任务）、
> §四 `2-A2`（后半段）/ `2-A9`；协议以《技术路线》§7 为准。
> 说明：C 端 token 用短信固定码登录（`13900000009`），律师侧用 `13800000001` 的登录密码 `123456`。
> WebSocket 客户端用 Node 的内置 `WebSocket`（与 RN 的 WebSocket 同一套 JSON 信封协议）。

---

## 一、交付物

| 任务 | 交付 |
|---|---|
| G6.1 会话与消息 REST | `ConversationController`：`GET/POST /app/conversations`、`GET /app/conversations/{id}/messages`（seq 游标）、`POST /app/conversations/{id}/read`、`GET /app/workbench/conversations`（律师端）；`ConversationService#assertMember` 是全域唯一鉴权入口 |
| G6.2 WebSocket 服务端 | `WebSocketConfig`（`/ws/chat`）、`ChatHandshakeInterceptor`（握手验 JWT）、`ChatWebSocketHandler`（生命周期 + 按 type 分发）、`ws/handler/*` 五个处理器（send/read/recall/typing/history）、`OnlineRegistry` |
| G6.3 聊天页 | `ChatPage.tsx`（17-chat）、`useChatSocket` hook、`app/chat.tsx`、`routes.ts` 的 `chat` / `chatHref`；订单详情「联系律师」改为跳转 |
| G6.4 已读 / 撤回 / typing | WS 三个处理器 + REST 已读兜底，**同一套 `MessageService#markRead`**；撤回四条校验（成员 / 消息归属 / 发送者本人 / 2 分钟） |
| G6.5 断线重连 | `useChatSocket` 指数退避（1/2/4/8/16/30s）+ 重连后 `chat.history(afterSeq=已收最大 seq)` 增量补拉；服务端 `incremental` 按 seq 升序 |
| G6.6 离线消息 | `message_offline` 入队（去重）、连接建立即补推（推一条删一条、每批 50）、`OfflineMessageCleanupTask`（每天 04:30，保留期 `sys_config.im.offline_retention_days` 默认 7 天） |
| 表 / 实体 | `Conversation` / `ConversationMessage` / `MessageOffline` + 三个 Mapper + `SenderRole` / `MsgType` 枚举 + `CursorResult`（接口规范 §5.2 的游标分页结构） |

---

## 二、REST 实测

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | `POST /app/conversations {lawyerId:2}` | 200，返回会话 | `code=200 id=1 peerName=验收律师甲` |
| 2 | 重复调用同一律师 | 返回**同一条**会话（一个律师一个会话） | `sameId=true` |
| 3 | `GET /app/conversations` | C 端列表，对端是律师 | `count=1 firstUnread=0` |
| 4 | `GET /app/workbench/conversations`（律师） | 律师端列表，对端是用户 | `count=1 peerType="user"` |
| 5 | 非成员访问历史（用 `13700007739` 的 token） | **HTTP 403 + 404002** | `http=403 code=404002` |
| 6 | 历史分页 | 按 seq 升序、撤回消息无正文 | `count=5 hasRecalled=true recalledRow=null sorted=true` |
| 7 | `POST /app/conversations/{id}/read`（WS 断线兜底） | 未读归零 | `beforeUnread=3 readCode=200 afterUnread=0` |

---

## 三、WebSocket 实测（`ws://127.0.0.1:8081/ws/chat?token=...`）

### 3.1 `2-A2` 后半段：双向实时（≤2 秒）

```text
用户 WS 发 chat.send → 服务端 chat.ack(seq=1) → 律师 WS 收 chat.message
  latencyMs = 45      ← 从 send 到对端收到（含落库、推送）
律师 WS 发 chat.send → 用户 WS 收 chat.message（senderRole=lawyer）
已读：用户 chat.read(lastReadSeq=2) → 律师 WS 收 chat.read{readerRole=user, lastReadSeq=2}
撤 回：用户 chat.recall(messageId=…) → **双方都收到** chat.recall（lawyerGot=true userGot=true）
```

**判定**：`latencyMs=45 ≤ 2000` → `2-A2` 的「IM 双向 ≤2 秒」通过
（前半段「下单→接单」链路在 G3/G4 的验收里已验；本表补齐 IM 部分）。

### 3.2 `2-A9`：断线 30 秒不丢不重、顺序正确

```text
1. 用户 WS 断开（close）
2. 律师连发 3 条（"E2E 离线消息 1/2/3"），此时用户不在线 → 写入 message_offline
3. 用户重新连接 → 连接建立后服务端自动补推
4. 实测收到 ["E2E 离线消息 1","E2E 离线消息 2","E2E 离线消息 3"]，count=3 allThree=true
```

补推顺序按 `seq` 升序（`idx_offline_user_seq`），客户端按 `messageId` 去重
（实时推送与增量补拉可能同时到达同一条，见 G6.5「卡住怎么办」）。

### 3.3 握手鉴权

- 不带 token / token 非法：`ChatHandshakeInterceptor` 直接拒绝握手（HTTP 403），**不存在「连上但未认证」的连接**。
- 连接建立后会话身份只取自 attributes，`data` 里的 `senderId` / `userId` 一律忽略。

---

## 四、库内数据

```sql
SELECT id, user_id, lawyer_id, last_message, last_msg_at, unread_user, unread_lawyer FROM conversation;
-- 1  3  2  E2E 离线消息 3  …  unread_user=3 → REST 已读后 0
SELECT id, conversation_id, seq, sender_role, sender_id, msg_type, recalled_at FROM conversation_message ORDER BY seq;
-- seq 1 用户消息（已撤回，recalled_at 非空、content 仍保留）、seq 2 律师回复、seq 3~5 离线三条
SELECT user_id, conversation_id, message_id, seq FROM message_offline;
-- 补推成功后应被清空（推一条删一条）
```

- `seq` 由 Redis `faze:conv:seq:{id}` INCR 生成，并与库内最大 seq 取 max（Redis 重启不会撞 `uk_msg_conversation_seq`）。
- `client_msg_id` 去重：同 id 重发返回已有消息（`duplicated=true`），不插第二行、不再推对端。
- 撤回只写 `recalled_at`，`msg_type` 不变；历史接口对该行 `recalled=true` 且 `content=null`。

---

## 五、自动化测试与门禁

- `mvn test` **195 项全绿**（31 个测试类，新增 3 个测试类共 25 项）：
  - `ConversationServiceTest`（8）：成员三种身份 / 非成员 403 / 会话不存在 404001 /
    创建或获取幂等 / 律师不可见 / 律师端非律师空页 / C 端未读取 `unread_user`
  - `MessageServiceTest`（13）：发送落库与摘要 / clientMsgId 幂等 / 空文本 / 客户端发 system 拒绝 /
    Redis seq 落后对齐 / 历史升序 / 撤回超 2 分钟 / 撤回他人消息 / 撤回成功出参裁正文 /
    重复撤回幂等 / 已读重算未读 / 超前游标 clamp / 历史隐藏撤回正文
  - `OfflineMessageServiceTest`（4）：入队 / 去重 / 取批 / 保留期非法按默认
- 前端门禁：`tsc --noEmit`、`eslint .`（0 告警）、`check-raw-text`（92 文件 0 命中）、
  `check-i18n`（四目录同构 + 缺译已登记 + 占位符一致）全过。
- i18n：`conversation` namespace 21 个 key（原来空文件）+ `error.conversation.not-found` /
  `error.conversation.forbidden`；删除占位 key `trade.order.chatPending`；三语登记缺译。
- 错误码：新增 `CONVERSATION_NOT_FOUND(404001)`、`CONVERSATION_FORBIDDEN(404002, 403)`，
  前端 `errorCodes.ts` 同步登记（G4/G5 的 405011/405021/405022 一并补登记）。

---

## 六、APP 聊天页（17-chat）

| 设计稿要求 | 实现 |
|---|---|
| 头部：返回 + 头像 + 名字 + 状态 | `AppHeader`（标题=对方名字）+ 顶部副行显示连接状态 |
| 消息列表：in/out 气泡、时间、撤回 | `MessageBubble`：左侧白底 / 右侧主色底、`HH:mm`、撤回占位「消息已撤回」、已读小字 |
| 图片消息 | 服务端已支持（`msgType=image` + fileUrl）；客户端仅占位渲染（见偏差 1） |
| 快捷问句 | 空会话时显示 3 条，点选填入输入框 |
| 输入 + 发送 | `TextInput` + 发送按钮（空文本禁用）；`chat.typing` 3 秒节流 |
| 断线提示 | 顶部橙色条「连接已断开，正在重连」/「正在连接…」 |
| 消息菜单（复制/撤回/删除） | 长按自己的消息 → 确认框撤回（复制/删除本段不做，见偏差 2） |
| 四态 | loading / error（重试）/ 空会话提示 / 正常 |

---

## 七、偏差与遗留（如实记录）

1. **图片/文件消息的客户端选择器未做**：服务端接口与消息类型已支持（`msgType=image|file`，
   `chat.send` 补了可选 `fileUrl/fileName/fileSize`），但选图/选文件需要
   `expo-image-picker` / `expo-document-picker`（**新增依赖须先说明理由并登记《依赖清单》**）。
   段 2 不新增依赖：聊天页对文本消息完整可用，收到的图片消息按占位渲染。
2. **消息菜单只做撤回**：设计稿的「复制 / 删除」中，复制属纯前端能力（可用 `expo-clipboard`，
   同样是新增依赖），删除本段不做（消息不可删，工作流 §七）。
3. **`chat.send` 的 data 比技术路线多三个可选字段**（`fileUrl/fileName/fileSize`）：
   技术路线 §7.2.1 只列了 4 个字段，但 §四 要求支持图片/文件消息，原表缺字段。
   这是**补字段、不改语义**，已在 `ChatSendHandler` 注释与本节记录。
4. **会话列表页（38-messages）不做**：工作流 G8.3 把它定为「空态 + 入口占位」，段 2 不做真实列表。
   聊天入口目前从订单详情「联系律师」进入（按 lawyerId 建/取会话）。
5. **WS 权限模型假设单实例**：`OnlineRegistry` 的内存表是本进程真相，Redis 只做在线标记；
   多实例部署时需改成「发布订阅到目标实例」，段 8 部署时处理。
6. **没有 IM 频控**：`chat.send` 未做每分钟条数限制（段 2 没有 IM 频控配置项），
   客户端节流 + 服务端 `maxLength` 校验是当前唯一约束。
7. **`2-A3` 离线推送（G7）不在本组**：本组只做 `message_offline` 与重连补推；
   真正的系统级推送（个推/厂商通道）属 G7，且通道凭据未提供（挂段 8）。
