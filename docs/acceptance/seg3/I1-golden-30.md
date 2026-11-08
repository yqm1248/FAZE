# I1 · 黄金 30 题问题集与批处理脚本 · 验收证据

> 任务：工作流 L1474–L1544（I1「黄金 30 题问题集与批处理脚本（`3-A2`）」）
> 证据日期：2026-11-07　执行人：Lead
> 结论：**交付物齐全，但「跑完 30 题」未执行** —— 阻塞在 `FAZE_ARK_API_KEY` 缺失，工作流明令不许用 mock 跑。

---

## 一、交付物

| # | 交付物 | 路径 | 状态 |
|---|---|---|---|
| 1 | 问题集（30 题，含题号/问题/预期引用/类型） | `docs/acceptance/seg3/golden-30-questions.json` | ✅ 30 条，逐条照工作流 §I1 表格，未改一字 |
| 2 | 批处理脚本 | `scripts/golden-30.ps1` | ✅ 登录 → 逐题 POST → 读 SSE 到 done → 抽条号 → 核存在性 → 出 result/summary |
| 3 | 结果文件 | `golden-30-result.json` / `golden-30-summary.md` | ⏸ **未生成**（没跑） |
| 4 | 锚点证据 | `3-A2.md` | ✅ 写明未通过及原因 |

脚本的关键设计（都是为了不制造假证据）：
- **§0 前置检查**：若 `FAZE_AI_MOCK=true` 直接 `exit 2` 并打印「工作流禁止用 mock 跑」——这道闸门本身已实测生效；
- **判据内置**：`nonexistentArticleTotal`、`blockedAsExpected`（27~29 题）、`noRefHasArticleNo`（30 题）由脚本判定，不靠人眼；
- **不合格项逐条打印**：每题的题号 + 原始回答 + 不存在的条号，不许只报总数（工作流 L1524）。

## 二、mock 拒绝的实测

```text
[拒绝执行] FAZE_AI_MOCK=true（当前是 mock 模式，不调豆包）。
工作流 I1 明写：豆包 key 没有 → I1 不能开工，3-A2 记「未通过」，不许用 mock 跑。
拿到 FAZE_ARK_API_KEY 并把 FAZE_AI_MOCK 置 false 后重跑本脚本。
exit=2
```

执行过程中修掉的两个真 bug（证明脚本确实被执行过）：
1. 只读 `deploy/.env` 的 `FAZE_AI_MOCK`，但 `.env` 里**没有这一项**（默认值在 `application-dev.yml` 的 `${FAZE_AI_MOCK:true}`）→ 会把 mock 误判成非 mock。已加回退读 yml 默认值。
2. 登录请求体漏 `deviceId`（`LoginBO` 的 `@NotBlank`）→ 登录必失败。已补 `-DeviceId`。

## 三、为什么不能用 mock 跑（写给后来人）

mock 的 `DoubaoClientStub` **固定输出**「根据《中华人民共和国民法典》第六百七十九条的规定…」，与问题无关。
用它跑 30 题会出现：

- 第 1~26 题（正向题）：每题都引 679，「引用条号不存在数」恒等于 0 —— **看起来完美通过**；
- 第 27~29 题（应被 408004 拦）：mock 在输入防护之后，**能正确拦**，但这与真模型无关；
- 第 30 题（天气，应 NO_REF）：mock 仍会引 679 → **不通过**。

也就是说 mock 会让「引用条号不存在数 = 0」这个核心判据**恰好成立但没有意义**。
这正是工作流禁止的事。

## 四、拿到 key 后的执行方式

```powershell
# deploy/.env: FAZE_ARK_API_KEY=<火山方舟 key>，并加 FAZE_AI_MOCK=false
.\deploy\restart-api.ps1
.\scripts\golden-30.ps1          # 约 30 次豆包调用，注意成本；不进 CI
```

判定：`nonexistentArticleTotal = 0`、27~29 题 `blockedAsExpected`、30 题 `noRefHasArticleNo=false`。

## 五、判定

| 判据（工作流 L1534–L1539） | 结果 |
|---|---|
| 30 题全部跑完，result.json 有 30 条记录 | ⏸ 未跑（key 缺失） |
| 引用条号不存在数 = 0 | ⏸ 未判 |
| 27~29 题被 408004 拦；30 题 NO_REF 且无条号 | ⏸ 未判 |
| 问题集与脚本入库归档 | ✅ |
| 有不合格项时逐条列出题号+原文+不存在条号 | ✅ 脚本已实现（未运行） |

**结论：I1 的交付物完成，验收未通过**（`3-A2` 记「未通过」）。
