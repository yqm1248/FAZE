# 第 2 段 · G7「离线推送」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze` / Redis db1）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G7（3 个任务）、§四 `2-A3`
> **本组结论先说**：`2-A3` **未通过**（外部通道凭据未提供），按工作流 §二 第 11 项**如实挂账**，
> 不写「通过」。可施工的部分（G7.2 服务端 + G7.3 客户端逻辑）已完成并逐项验证。

---

## 一、交付物与完成度

| 任务 | 状态 | 说明 |
|---|---|---|
| G7.1 推送通道接入 | **挂账** | 个推应用 + 四厂商通道凭据未提供（总纲 §九 L855 承诺段 1 结束前交付，至今未到）。已冻结 `PushSender` 接口与 `PushMessage` 载荷，**实现层是 `DisabledPushSender`：明确抛 `PushUnavailableException`，不静默成功**。凭据到位后新增 `GetuiPushSender implements PushSender` 即可，调用方零改动 |
| G7.2 推送任务与重试 | **完成** | `task_queue` 第二个消费者 `PushTask`：消息落库后入队（离线才入队、`hasActiveTask` 判重）→ 领取 → 调 `PushSender` → 失败 `retry_count+1` 且 `next_retry_at = now + 2^retry` → `retry_count >= 5` 进死信 |
| G7.3 点击直达会话 | **客户端逻辑完成，真机验证挂账** | `features/conversation/push.ts`（payload 解析 + 意图注册表 + 未读求和）、`_layout.tsx` 注册处理器、未登录存 `pendingIntent` 且登录后回跳、`ChatPage` 支持 `conversationId` 直达。**没有真实推送 SDK，杀进程收到通知这一步无法验** |
| 支撑改造 | 完成 | `task_queue` 按类型领取（`claimBatch(worker, types)` + XML `task_type IN (...)`）：不筛类型时两个消费者会互相抢任务；`GET /app/conversations/{id}`（直达会话需要） |

---

## 二、G7.2 实测（真实 task_queue）

### 2.1 消息落库后入队

```text
用户离线（不连 WS）→ 律师 WS 发一条消息
  messageId=6 seq=6
task_queue：
  id=39  task_type=push  biz_no=6  status=pending  retry_count=0  next_retry_at=14:54:32(UTC)
message_offline：1 行（等用户重连补推）
```

`biz_no` 存 `conversation_message.id`：一条消息一个推送任务，`hasActiveTask(push, messageId)`
保证重复入队被跳过（重连补推会重复走到入队分支）。

### 2.2 失败退避 → 死信（**通道未启用时真实发生**）

```text
round=1  retry_count=1  last_error=PushUnavailableException: 推送通道未启用（push.enabled=false）
round=2  retry_count=2  ...
round=3  retry_count=3  ...
round=4  retry_count=4  ...
round=5  status=dead    last_error 同上
```

- 退避间隔由 `TaskQueueService#markFailed` 按 `2^retry` 分钟计算（2/4/8/16/32 分钟）；
  验证时用 `UPDATE next_retry_at = UTC_TIMESTAMP()` 手动推进，**没有缩短代码里的退避**。
- 第 5 次失败进死信（`status='dead'`，PC A-26 可见；段 2 只落数据不做页面）。
- **这条路径正是「不假装通过」的证明**：通道没接入 → 任务明确失败 → 重试 → 死信，
  行为可观测；如果 `DisabledPushSender` 返回成功，所有任务都会 done，死信永远为空。

### 2.3 幂等

- 同一 `messageId` 只入队一次（`hasActiveTask` 在 `pending`/`claimed` 两种状态下都判重）。
- 死信任务不再重试（`status='dead'` 不在待领取集合里）。

---

## 三、G7.3 客户端逻辑（可验证部分）

| 设计点 | 实现 |
|---|---|
| payload 解析 | `parsePushPayload`：只认 `conversationId` / `messageId`，非法 id 返回 null（**不跳首页**） |
| 意图注册表 | `setPushIntentHandler` / `emitPushIntent`：SDK 到位后由原生回调调用 `emitPushIntent(notification.data)` |
| 登录判断 | `_layout` 的处理器：`readSession()` 为空 → `savePendingHref(chatHref)` + 跳登录；已登录 → `router.push(chatHref)` |
| 登录后回跳 | `LoginPage` 登录成功先 `takePendingHref()`，有值就 `replace` 到该会话，否则回启动分流器 |
| 直达会话 | `ChatPage` 支持 `conversationId` 参数：`GET /app/conversations/{id}` 取对端 → 拉历史 → 连 WS |
| 未读角标求和 | `unreadTotal(conversations)`（角标上报依赖推送 SDK，挂账） |

**为什么这段不能算 `2-A3` 通过**：`2-A3` 的判据是「杀进程后收到推送，点开进该会话」——
需要真实推送通道（G7.1）与真机。当前只能证明「payload → 路由」的逻辑正确，
没有推送到达这一步。挂段 8 真机补验。

---

## 四、自动化测试与门禁

- `mvn test` **201 项全绿**（32 个测试类），新增 `PushTaskTest` 6 项：
  律师发→推给用户 / 用户发→推给律师 / 接收方在线跳过 / 消息不存在跳过 /
  通道不可用异常上抛 / bizNo 非法跳过。
  断言「通知正文不含聊天内容」（技术路线 §7.5 隐私要求）。
- `TaskQueueServiceTest` 随签名变更同步（`claimBatch` 按类型领取）。
- 前端门禁：`tsc --noEmit`、`eslint .`（0 告警）、`check-raw-text`（92 文件 0 命中）、
  `check-i18n` 全过；新增 `shared/api/pendingIntent.ts` 与 `features/conversation/push.ts` 无新增文案 key。

---

## 五、偏差与遗留（如实记录）

1. **G7.1 通道接入挂账**：个推应用 + 华为/小米/OPPO/vivo 通道凭据未提供；
   `expo-notifications` 被 AGENTS §2 禁用，需原生 SDK 桥（总纲 §5.3 风险表第一行）。
   凭据到位后的接入点已固化：`PushSender` 接口 + `PushMessage` 载荷 + `emitPushIntent`。
2. **`2-A3` 未通过**：见第三节。挂段 8 真机验证（与浏览器口径的推送验收一起）。
3. **推送文案是服务端配置**（`push.message_title` / `push.message_body`，默认中文「您有一条新消息」）：
   系统通知栏在系统层渲染，走不了客户端 i18n。段 8 按用户 `lang` 取多语言配置。
4. **消息合并推送未做**：工作流 G7.2「卡住怎么办」提到「同一用户 3 条未读合并成 1 条」，
   当前一条消息一个任务（通道未接入，合并没有可验证的收益）；接入真实通道后按限流需要再补。
5. **未读角标上报未做**：依赖推送 SDK 的 `setBadgeCount`（Android Badge / iOS badge），随 G7.1 挂账；
   `unreadTotal` 求和逻辑已就绪，可在段 8 直接接。
6. **`task_queue` 类型过滤是段 2 内的一次结构改动**：`selectCandidateIds` 新增 `taskTypes` 参数，
   `claimBatch(workerName)` 保留为「不筛类型」的兼容重载。生产消费者（订单扫描 / 推送）
   都显式传类型，避免互相抢任务。
