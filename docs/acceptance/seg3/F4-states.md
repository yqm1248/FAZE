# F4 · 四态 + 降级为纯法条检索 · 验收证据

> 任务：工作流 L1155–L1196（F4「四态 + 降级为纯法条检索（`3-A7` 的实现）」）
> 实现：`features/ai/pages/AiChatPage.tsx`（四态分支）、`features/ai/pages/AiLawSearchView.tsx`（220 行降级视图）、后端 `PubSysConfigController`
> 证据日期：2026-11-07　执行人：Lead（CDP 真实浏览器）
> 结论：**降级链路通过（详见 `3-A7.md`）；四态里的「正常/加载/空」有证据，「错误态」未实测**。

---

## 一、降级链路（这是 F4 的主线，锚点 `3-A7`）

完整实测见 `3-A7.md`，这里只放结论与截图索引：

| 阶段 | 实测 |
|---|---|
| `ai_enabled=false` → 重进 `/agent` | 显示「法条检索」+「AI 顾问暂不可用，可先查询法条」+ 搜索框；`ai-degraded-search` 钩子在；不白屏 |
| 搜「借条」 | 命中 1 条（679），`ai-degraded-result-0` |
| 降级状态不调豆包 | 日志 0 条上游请求（mock 环境下证据强度有限，见 `3-A7.md` §1.5） |
| `ai_enabled=true` → 重进 | 恢复 AI 模式（`ai-chat-*` 回来、`ai-degraded-search` 消失） |

截图：`img/F4-degraded-search-390.png`、`img/F4-degraded-result-390.png`、`img/F4-recovered-390.png`。

## 二、四态

| 态 | 触发 | 实测 |
|---|---|---|
| **正常** | 有消息 | ✅ `img/F3-stream-answer-390.png`（问答完整展示） |
| **加载** | 发送后未出首字 | ⚠️ **未单独截图**：`F5-quota-390.png` 之前的空态与发送瞬间已经跨过；代码里有「AI 思考中…」+ 三点动画（`MessageBubble` 的 `pending` 分支） |
| **空** | 无消息 | ✅ `img/F2-agent-empty-390.png`（空态 hint「描述你遇到的法律问题，例如『借钱不还怎么办』」） |
| **错误** | `event:error` / 网络失败 | ⚠️ **未实测**：mock 下服务端不产生 error 事件；网络失败态在 3-A8 的断网测试里页面表现是「继续重连」而不是「错误」，没有走到重试按钮 |

## 三、公开配置接口（F4 的后端支撑）

工作流 L1173 要求新增 `GET /pub/sys-config` 且**只允许读白名单键**。实测（`H1-a27.md` 同一轮，admin 无需鉴权）：

```text
GET /api/v1/pub/sys-config?keys=ai_enabled,ai_daily_quota
→ {"code":200,"data":{"ai_enabled":true,"ai_daily_quota":50}}

GET /api/v1/pub/sys-config?keys=db.password
→ {"code":200,"data":{}}          ← 非白名单键静默忽略、不报 500、不泄漏

GET /api/v1/pub/sys-config
→ {"code":200,"data":{"ai_enabled":true,"ai_daily_quota":50}}   ← 只返回白名单全集
```

页面侧：`ai-quota-text` 的「今日还剩 N 次」就是从这个接口的 `ai_daily_quota` + `/app/ai/quota` 算出来的（N 次实测随使用递减：35→34→33→30）。

## 四、判定

| 判据（工作流 L1186–L1190） | 结果 |
|---|---|
| 四态各有截图，错误态可重试 | ⚠️ 正常/空有截图；加载与错误态未单独取证 |
| A-08 关 `ai_enabled` → 重进显示「法条检索」，输入「借条」能出结果 | ✅ 见第一节 |
| 降级状态下不调豆包 | ✅ 日志 0 命中（mock 下证据强度有限） |
| 重开 `ai_enabled` → 重进恢复 AI 模式 | ✅ |
| 公开配置接口只返回白名单键（`keys=db.password` 返回空、不 500） | ✅ |

**结论：降级主线（即锚点 `3-A7`）通过；四态里「加载/错误」两态缺证据。** F4 任务本身**不判完整通过**（工作流要求「四态各有截图」）。
