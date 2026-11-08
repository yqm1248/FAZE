# F3 · 流式渲染 + refs 引用块 + 显式标识 · 验收证据

> 任务：工作流 L1114–L1153（F3「流式渲染 + refs 引用块 + 显式标识 + 转人工」）
> 实现：`features/ai/hooks/useAiStream.ts`（407 行）、`shared/components/{LawRefCard,MessageBubble,OfflineBar,AiGeneratedLabel}.tsx`、`features/ai/pages/AiChatPage.tsx`
> 证据日期：2026-11-07　执行人：Lead（CDP 真实浏览器）
> 结论：**核心链路通过；长按复制、转人工按钮、离线条三项未实测**。

---

## 一、实测：一次完整的流式问答

### 1.1 动作

```powershell
node scripts/cdp-shot.mjs --url "http://127.0.0.1:8090/agent" --out "tmp/seg3/f3-stream-answer.png" `
  --width 390 --height 844 --mobile --wait 20000 --eval-file tmp/seg3/f3-ask.js
```

脚本在输入框输入「借钱不还怎么办，只有转账记录」→ 点发送 → 等 6 秒 → 断言 DOM。

### 1.2 真实输出

```json
{
  "ok": true,
  "hasBubbleAssistant": true,
  "hasLawRefCard": true,
  "hasGeneratedLabel": true,
  "lawRefTestIds": ["ai-law-ref-0"],
  "quotaText": "今日还剩 34 次",
  "tail": "法泽智能体\n根据《中华人民共和国民法典》第六百七十九条的规定，自然人之间的借款合同…\n自然人之间的借款合同，自贷款人提供借款时成立。\n《中华人民共和国民法典》第六百七十九条\n本内容由 AI 生成，仅供参考\n借钱不还怎么办，只有转账记录\n今日还剩 34 次\n发送\n..."
}
```

服务端侧（同一周期内 `logs/faze-api.out.log`）：`event:delta` 共 **65** 次、`event:done` 1 次、`event:refs` 1 次。

### 1.3 逐条对照「算做完」

| 工作流判据（L1141–L1148） | 结果 | 依据 |
|---|---|---|
| 真机发问 → 文字**逐段冒出**（不是一次性） | ✅ 流式（服务端 65 次 delta；`useAiStream` 逐 delta append） | 服务端事件计数 + 代码路径；**「逐段」的视觉节奏无法从静态截图证明**（见第四节） |
| 回答引用了条文 → 气泡下方出现 `LawRefCard`，来源行正确 | ✅ `lawRefTestIds=["ai-law-ref-0"]`；页面文本含「自然人之间的借款合同…」+「《中华人民共和国民法典》第六百七十九条」 | 截图 `img/F3-stream-answer-390.png` |
| 每条 AI 回答下方有 `AiGeneratedLabel` | ✅ `hasGeneratedLabel=true`；3 条回答的交替验证见 `3-A5.md` | — |
| 长按气泡 → 复制成功 + toast | ⚠️ **未实测** | 需真机长按手势；CDP 模拟长按与原生 longPress 时序差异大 |
| 断网 → 顶部出现「网络已断开」条 | ⚠️ **未实测**（组件在：`shared/components/OfflineBar.tsx`） | 3-A8 的断网测试没有断言这条 UI |
| 所有 `testID` 都在 | ✅ `ai-law-ref-*`、`ai-generated-label`、`ai-chat-*`、`ai-msg-*` 实测都在 | DOM 断言 |
| 转人工按钮（`degraded=true` 时出现） | ⚠️ **未实测** | 需要 D3 输出防护触发降级；mock 下回答固定合规，触发不到 |

## 二、实现要点核对（读代码）

- **复用段 0 的 SSE 封装**：`useAiStream` 走 `shared/api/sse.ts`，没有在 `features/ai/` 里另写流式读取（工作流 L1120 的硬要求）。
- **两段式**：先 `POST /app/ai/conversations` 拿 `streamUrl`，再 GET 读 SSE（与 C2/C3 契约一致）。
- **不加 60ms 匀速动画**：收到 delta 直接 `setState(prev => prev + content)`（工作流 L1124 的要求）。
- **refs 卡片**：左侧竖线用 `borderStartWidth`（RTL 自动镜像），来源行格式「《中华人民共和国民法典》第六百七十九条」，法条原文不翻译。
- **长按复制**：`expo-clipboard` 的 `setStringAsync` + `AppToast`；「重新生成」未做（工作流 §七 明确不做）。

## 三、没做到的（如实记录）

1. **「逐段冒出」是流式的事实，但没有录屏**：静态截图只能证明「最终回答完整」，证明不了「中间状态是逐步出现的」。服务端的 65 次 `event:delta` 是「分 65 次推送」的事实证据，前端是否每次都渲染需要录屏或 DOM 时间序列。**这是形式缺口，不是功能缺口**。
2. **长按复制、离线条、转人工按钮三项未实测**（各自原因见上表）。
3. **mock 环境的限制**：回答内容是 stub 固定文案，所以「引用是否恰当地命中问题」无法评估（与 3-A2/3-A3 同一限制）。
