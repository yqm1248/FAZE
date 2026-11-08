# 第 3 段 · F2「AI 对话页骨架」验收证据

> 施工日期：2026-11-07 · 分支 `UI设计` · 施工成员：`fe-ai`
> 对应工作流：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` L1077–1110（F2）；设计稿 `design/ui/03-agent.html`
> 环境：Windows / PowerShell 5.1 / Node v24.16.0 / Expo SDK 54
> 结论：**空态、消息列表（`FlatList inverted`）、输入条、清空二次确认、5 个 `testID` 都已落地；
> 本批次按计划不接流式、不落库、不调接口（F3/F4 的活）。`npm run verify` 全绿。
> 真机/截图无法执行，挂账 Lead 用 CDP/模拟器复核「气泡左右、清空浮层、键盘避让」。**

---

## 一、交付物

| 文件 | 状态 | 说明 |
|---|---|---|
| `apps/mobile-app/features/ai/pages/AiChatPage.tsx` | 新建 | 页面骨架（顶栏 / 消息区 / 输入条 / 清空浮层） |
| `apps/mobile-app/shared/components/MessageBubble.tsx` | 新建 | 共享消息气泡（`role` / `content` / `refs?` / `pending?` / `onLongPress?`） |
| `apps/mobile-app/shared/components/icons/AiIcons.tsx` | 新建 | 顶栏「对话历史」「清空」线性图标（设计稿 03-agent L153–173） |
| `apps/mobile-app/shared/i18n/resources/zh-Hans/ai.json` | 修改 | `ai.chat.*` 13 个 key（原文件是 `{}`） |
| `apps/mobile-app/shared/i18n/resources/.i18n-pending.json` | 修改 | 脚本生成，三语言各登记（含本批 13 条） |
| `apps/mobile-app/app/(tabs)/agent.tsx` | 新建 | 路由壳一行导出（见 F1 文档） |

> **本批次不挂 `AiGeneratedLabel`**：显式标识属于 F3（工作流 F3 第 5 条），批次 2 挂到每条
> assistant 气泡最下方；`MessageBubble` 已预留 `refs?`，F3 在其下方插入 `LawRefCard`（同上第 4 条）。

---

## 二、页面结构（设计稿 `03-agent.html` 对照）

| 设计稿 | 实现 | 源码行号 |
|---|---|---|
| 顶栏标题「法泽智能体」 | `t('ai.chat.title')`，`typography.h2` + `color.text.primary`（设计 `.appbar__title`） | L110、L213–217 |
| 历史图标（跳 G1） | `testID="ai-chat-history"`，`accessibilityLabel={t('ai.chat.history')}`；**G1 页面批次 2 才建**，本批点按给 `ai.chat.historySoon` 提示 | L113–121、L103 |
| 清空图标 + 二次确认浮层 | `testID="ai-chat-clear"` → `ConfirmDialog`（标题/正文/hint/取消/清空），`danger` 红色确认 | L122–130、L188–197 |
| 空白引导态（设计 L178） | `t('ai.chat.emptyHint')` =「描述你遇到的法律问题，例如「借钱不还怎么办」」，居中 + `color.text.muted` | L138–143、L233–244 |
| 消息区 `FlatList` 倒序 | `inverted`，`messages` 最新在前；**不加推荐问题胶囊**（工作流 F2 第 4 条） | L145–156 |
| 输入条 46px、multiline 最多 4 行 | `TextInput multiline`，`minHeight: size.inputHeight`（45dp，设计 46px 的最近令牌值），`maxHeight: 4 × typography.body.lineHeight + spacing.md × 2` | L159–170、L271–281 |
| 发送按钮 | 按 `17-chat.html` 的 `.composer__send`（56×44 胶囊、主色底、文案「发送」）；空输入时 `disabled` + 透明度 0.4 | L171–184、L282 起（`send` / `sendDisabled` / `sendText`） |

`testID` 清单（真实行号）：

```text
$ Select-String -Path apps\mobile-app\features\ai\pages\AiChatPage.tsx -Pattern 'testID='
LineNumber Line
---------- ----
       114             testID="ai-chat-history"
       123             testID="ai-chat-clear"
       150               <View testID={`ai-msg-${index}`} style={styles.messageRow}>
       162               testID="ai-chat-input"
       172               testID="ai-chat-send"
```

`ai-msg-<index>` 挂在每条消息的**外层 View**（不塞进气泡组件的 props 里，保持
`MessageBubble` 的 props 与工作流 F2 第 3 条一致）。

---

## 三、`MessageBubble`（props 与边界）

```text
$ Select-String -Path shared/components/MessageBubble.tsx -Pattern 'export type MessageBubbleProps|role:|content:|refs|pending|onLongPress|ANSWER_MAX_LINES'
48 export type MessageBubbleProps = {
50   role: 'user' | 'assistant';
52   content: string;
54   refs?: readonly MessageRef[];
56   pending?: boolean;
58   onLongPress?: () => void;
62 const ANSWER_MAX_LINES = 500;
72 export function MessageBubble({ role, content, pending = false, onLongPress }: MessageBubbleProps) {
```

- 用户消息：靠结束侧 + `color.brand.primary` 底 + `color.text.onBrand` 字；AI 消息：靠起始侧 +
  `color.background.surface` 底 + `color.text.body` 字；圆角用 `borderTopStartRadius` /
  `borderTopEndRadius`（逻辑属性，RTL 自动镜像）。
- `refs?` 的类型按工作流 §零 第 10 项的 `refs` 快照 schema 定义（`lawItemId` 必填 +
  `lawName`/`articleNo`/`content`/`effectiveDate?`/`status?`），**本批次只保留类型不渲染**（F3 渲染）。
- `onLongPress?` 不传时 `Pressable disabled`、`accessibilityRole="text"`；F3 的长按复制挂这里。
- `numberOfLines`：`LocalizedText` 必填，AI 回答用 `ANSWER_MAX_LINES = 500`（语义上不截断，不砍正文）。
- **段 2 的 `features/conversation/pages/ChatPage.tsx` 未改**（工作流 F2 第 3 条）：
  `git status` 里没有该文件；段 2 收口时改为引用本组件的要求已在本文件头注释与交付报告登记。

---

## 四、交互行为（本批次的边界）

| 行为 | 实现 | 说明 |
|---|---|---|
| 发一条消息 | 本地追加「用户消息 + AI 思考中占位」，清空输入框 | 列表最新在前；**不调接口、不落库**（F3/F5 才接） |
| AI 思考中 | 左侧气泡、`t('ai.chat.thinking')`、斜体 + `color.text.muted` | 占位气泡，F3 改为流式追加 |
| 清空 | `ConfirmDialog` 二次确认 → 只清视图；**没有** `DELETE /app/ai/conversations/{id}` | 后端删除属 G2 |
| 键盘避让 | `KeyboardAvoidingView`（iOS `padding`） | 真机表现挂账 CDP/真机复核 |
| 历史入口 | toast `ai.chat.historySoon` | G1 页面批次 2 建好后改为 `router.push`（代码注释已写明） |

**五态分工**（工作流 F2 判据里写了五态，但本批次还没有接口）：

| 态 | 本批次 | 归属 |
|---|---|---|
| 空 | ✅ 设计稿 hint | F2（本批） |
| 正常 | ✅ 本地消息列表 | F2（本批） |
| 进行中（思考中） | ✅ `pending` 气泡 | F2（本批） |
| 加载 | ❌ 不伪造 | F4（接接口后才有意义） |
| 错误 | ❌ 不伪造 | F4（D3 降级/网络错误） |

---

## 五、i18n（13 个新 key，全部走 `t()`）

`zh-Hans/ai.json`（真实内容）：

```json
{
  "chat": {
    "title": "法泽智能体",
    "history": "对话历史",
    "clear": "清空当前对话",
    "clearTitle": "清空当前对话？",
    "clearBody": "这一轮对话的内容会被清空，也不会保留在「对话历史」里。",
    "clearHint": "如需更换问题，可直接继续提问，不必清空 —— 保留上下文时回答更准确。",
    "clearCancel": "取消",
    "clearConfirm": "清空",
    "placeholder": "输入你的法律问题…",
    "send": "发送",
    "emptyHint": "描述你遇到的法律问题，例如「借钱不还怎么办」",
    "thinking": "AI 思考中…",
    "historySoon": "对话历史将在下一批次接入"
  }
}
```

- 对应 `ai.chat.<key>`（namespace `ai` ↔ `features/ai/`，i18n 规范 §4.3）。
- `bo` / `ug-Arab-CN` / `kk-Arab-CN` 未硬翻：由 `check-i18n.ps1` 登记（三语言各 869 个缺 key 中
  含本批 21 条 = F1 的 8 条 + F2 的 13 条）。
- 清空浮层的 hint 与正文用 `\n` 合并传给 `ConfirmDialog.description`（该组件只有一个说明位），
  未新造组件。

---

## 六、门禁（真实输出）

`npm run verify`（`tsc --noEmit` + `eslint .` + `check-raw-text` + `check-i18n`）完整输出见
`docs/acceptance/seg3/F1-tabs.md` 第六节（同一次运行，exit=0）。其中与本页直接相关的两条：

```text
[check-raw-text] 已扫描 115 个文件
[check-raw-text] 命中 0
（eslint 无输出 = 0 告警，no-raw-text / no-dynamic-i18n-key 规则均通过）
```

「所有文案走 `t()`」的静态证据：
1. 页面/气泡组件里**没有任何 JSX 文本节点或表达式字符串字面量**（ESLint `JSXText`、
   `JSXExpressionContainer > Literal` 两条规则 0 告警）；
2. 全部文案都来自上表 13 个 `ai.chat.*` key（`Select-String 't\('ai\.chat'` 逐处可见）；
3. `check-raw-text` 全仓 0 命中。

**路由树可打包**：web 导出成功（`Web Bundled 9350ms … expo-router/entry.js (1031 modules)`），
bundle 中能搜到 `ai-chat-input / ai-chat-send / ai-chat-clear / ai-chat-history`（各 1 次），
证明本页确实进了路由包。导出命令与 `.env` 注意事项见 F1 文档第九节。

---

## 七、F2「算做完」清单

| 工作流判据（L1101–1106） | 状态 | 证据 / 说明 |
|---|---|---|
| 进页面看到空态 hint；输入文字后发送按钮可点 | **静态通过；点击挂账** | L138–143 空态；`canSend = input.trim() !== ''`（L70）驱动 `disabled`（L174）。**本机无真机/模拟器，未实际点按**，待 Lead CDP |
| 发一条消息 → 用户气泡在右，占位「AI 思考中」在左 | **静态通过；视觉挂账** | L84–85/L151 追加顺序；`MessageBubble` 左右对齐与配色见第三节。需 CDP 复核视觉 |
| 点清空 → 二次确认浮层出现，确认后列表清空（只清视图） | **静态通过；交互挂账** | L188–197 `ConfirmDialog` + `handleClear` 只 `setMessages([])`。需 CDP 复核 |
| 所有文案走 `t()`，`grep` 中文 0 处 | **通过** | 第六节三条证据；`check-raw-text` 0、`eslint` 0 |
| 五态都有处理 | **部分（3/5）** | 空/正常/进行中 ✅；加载/错误按任务书属 F4，**本批次不伪造**（见第四节） |

---

## 八、已知偏差与挂账

1. **真机/模拟器截图不可执行** → 挂账：需 Lead 在 I 组用 CDP/模拟器验「气泡左右、清空浮层、
   键盘避让、空态视觉」；本节只提供静态证据与 `testID` 清单。本机 `adb devices` 为空、
   `emulator` 命令不存在（同 F1 文档）。
2. **对话历史按钮暂为提示**：G1 的 `AiHistoryPage` 属批次 2；`ai.chat.historySoon` 是临时文案，
   批次 2 接入后删该 key（会同步更新 `.i18n-pending.json`）。
3. **设计稿缺口（记录，不自行发挥）**：
   - `03-agent.html` 的输入条是「相机 / 按住说话 / 音量 / 加号」静态稿，**没有文字输入与发送按钮**；
     工作流 F2 要求 multiline 输入条，故按 `17-chat.html` 的 `.composer`（输入框 + 发送按钮）实现；
   - `placeholder` 设计稿原文是「发消息和按住说话」——本段**不做语音输入**（无接口、无任务），
     用「输入你的法律问题…」避免给出做不到的暗示；
   - 空态 hint 与清空浮层文案逐字取自设计稿 03-agent L178 / L266–276。
4. **未接 `AiGeneratedLabel`**：F3 的事（批次 2）；本批次不挂显式标识，属计划内。
5. **无头像/时间戳**：设计稿 03-agent 未画消息区，`17-chat.html` 的 `.msg__avatar` 与 `.msg__time`
   本批次不实现（AI 对话页无对端头像语义；时间戳等 F3 落库后再补），如 Lead 要求再补。

---

## 九、复现命令

```powershell
cd apps/mobile-app
npm run verify
# 只看本页与气泡的 testID：
Select-String -Path features\ai\pages\AiChatPage.tsx -Pattern 'testID='
Select-String -Path shared\components\MessageBubble.tsx -Pattern 'export type MessageBubbleProps'
```
