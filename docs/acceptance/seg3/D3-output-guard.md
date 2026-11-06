# 段 3 · D3 输出侧校验 · 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 D3（L826–L862）、§四 `3-A2` / `3-A3`（L1654–L1699）
> 产出：`modules/ai/service/OutputGuard.java`、`modules/ai/service/OutputGuardRules.java`、
> `modules/ai/dto/GuardOutcome.java`、`src/test/java/com/faze/modules/ai/OutputGuardTest.java`、本文档
> 数据来源：禁语=代码常量（技术路线 §11.7.1 骨架）；条号存在性=B3 `ArticleNoValidator.validate`
> 证据日期：2026-11-07　执行人：be-guard

---

## 〇、验证方式（如实说明）

本段铁律不执行 `mvn`。本文输出由 `javac 17.0.17` 编译真实源码后、独立 main 直接 `new OutputGuard(validator).inspect(...)`
执行得到；`ArticleNoValidator` 用子类覆盖 `validate` 返回**构造好的 missing/valid 列表**（B3 的真实实现另见
`B3-validator.md`，本类只消费其契约，不重复实现条号正则）。单测真实执行：**37 tests / 37 successful**，
其中 `OutputGuardTest` **11 个**。正式 `mvn test` 由 lead 统一执行（挂账）。

---

## 一、五种输入的真实输出对比

| # | 输入 | resultKind | degraded | citationStripped | missing/valid | errorCode | 输出（`\n` 表示换行） |
|---|---|---|---|---|---|---|---|
| 1 | 这个案子胜诉率90%，你放心。 | DEGRADED | true | false | 0/0 | - | 原回答 +「以上内容涉及推测，请以专业律师意见为准」 |
| 2 | 你应该去法院起诉，先把证据准备齐。 | DEGRADED | true | false | 0/0 | - | 原回答 + 同上免责段 |
| 3 | 这种情况大概赔两万元。 | DEGRADED | true | false | 0/0 | - | 原回答 + 同上免责段 |
| 4 | 根据《中华人民共和国民法典》第六百八十条，禁止高利放贷… | OK | false | false | 0/1 | - | 原样返回（库里有 680） |
| 5 | 「…（正常内容约 70 字）…另见《中华人民共和国民法典》第九千九百九十九条。」 | DEGRADED | false | true | 1/1 | - | 删除「第九千九百九十九条」+「（部分条文需人工核实，请以正式发布文本为准）」 |
| 6 | 借款合同是借款人向贷款人借款…（无条号） | OK | false | false | 0/0 | - | 原样返回 |
| 7 | 《中华人民共和国民法典》第九千九百九十九条：质权人可以直接变卖。 | **OUTPUT_INVALID** | false | true | 1/0 | **508002** | 「本次回答未通过校验，请重试」（固定文案） |
| 8 | 「…（正常内容约 70 字）…另见…第九千九百九十九条。」（全不存在但删完仍长） | DEGRADED | false | true | 1/0 | - | 删除条号 + 核实提示（**不是 508002**） |

补充：`保证合同无效的情形有四种…` → **OK**、`rules=[]`（「保证合同」不被 `PATTERN_PROMISE` 误伤）。

真实日志（与上表一一对应）：

```
00:45:52.290 INFO  c.f.m.a.s.OutputGuard - AI 输出校验 messageId=msg-PROMISE result=DEGRADED rules=[PATTERN_PROMISE] missing=0 valid=0
00:45:52.299 INFO  c.f.m.a.s.OutputGuard - AI 输出校验 messageId=msg-PARTIAL_MISS result=DEGRADED rules=[] missing=1 valid=1
00:45:52.300 WARN  c.f.m.a.s.OutputGuard - AI 输出校验不通过 messageId=msg-ALL_MISS_SHORT(508002) result=OUTPUT_INVALID missing=1 valid=0 rules=[]
00:45:52.300 INFO  c.f.m.a.s.OutputGuard - AI 输出校验 messageId=msg-ALL_MISS_LONG result=DEGRADED rules=[] missing=1 valid=0
```

> 说明：日志行的时间戳是某一次真实运行实例，重跑会变；`resultKind` / `rules` / `missing` / `valid` 等字段值稳定。

---

## 二、lead 2026-11-07 裁决 B 的两支（都覆盖）

实现顺序：**先删不存在的条号 → 再判剩余是否还有实质内容**。

1. **删完 < 20 字 → 508002**：上表 #7。`OutputGuardRules.MIN_REMAINING_CHARS = 20`（注释引用该裁决）。
   `GuardOutcome.getAnswer()` 返回固定文案 `OutputGuard.OUTPUT_INVALID_CONTENT`，`getErrorCode()` = `AI_OUTPUT_INVALID(508002)`。
2. **删完 ≥ 20 字 → 删除版 + 核实提示**：上表 #5、#8。`degraded` 仍为 false（条号删除本身不置 degraded），
   由 `citationStripped=true` 与 `resultKind=DEGRADED` 表示「处理过」。

「≥20 字」的度量：去掉空白与全部标点/符号（`\p{P}` `\p{S}` `\s`）后的字符数。
用 `OutputGuardRules.meaningfulLength()` 实现（不裸写阈值）。

**与 `3-A3` 口径的关系**：工作流 L845 的「所有条号都不存在**且无法降级**」= 删完没内容（#7）；
L1691–L1693 的「若模型仍输出一段文本则删掉并追加」= 删完还有内容（#8）。两支都有真实输出，见上表。

---

## 三、逐条对照「算做完」

| # | 判据（工作流 L854–L858） | 结果 | 证据 |
|---|---|---|---|
| 1 | 「胜诉率90%」→ 追加免责、`degraded=true`、回答**没有被删** | ✅ | 上表 #1：原句仍在，仅追加免责 |
| 2 | 「第 9999 条」→ 该条号被删、追加核实提示 | ✅ | 上表 #5（输入里的条号文本被精确删除，前后文不动） |
| 3 | 「第六百七十九条/第六百八十条」（库里有）→ 保留 | ✅ | 上表 #4：原样返回，`valid=1` |
| 4 | 回答整段编条号 → 508002 | ✅ | 上表 #7：`resultKind=OUTPUT_INVALID`、`errorCode=508002`、落库文案为固定文案 |
| 5 | 校验过程**不写日志原文** | ✅ | 日志只有 `messageId / result / rules / missing / valid`（见 §一）；代码 5 处日志均无回答内容 |

---

## 四、与 C3（be-stream）的契约（已对齐）

- `inspect()` **不抛异常**，永远返回 `GuardOutcome`；
- `OUTPUT_INVALID` 时 C3 必须**先落库**：`content = OutputGuard.OUTPUT_INVALID_CONTENT`、`refs = []`，
  再按 `getErrorCode()` 回 508002（lead 要求「不要只抛异常什么都不写」）；
- `done` 事件的 `degraded` 用 `isDegraded()`（只看禁语）；`resultKind` 只用于分流三态。
- `3-A2` 批处理脚本统计口径可直接用 `getMissingArticleCount()`（`PARTIAL_MISS` 的条数），
  该数字由脚本汇总，本类不汇总。`3-A2` 要求为 0，最终判定挂账（需真模型 + 30 题）。

---

## 五、挂账与风险（重要）

| 项 | 状态 | 说明 |
|---|---|---|
| `mvn test` 正式执行 | 挂账 | 铁律不执行 mvn；由 lead 统一跑 |
| `3-A2`（30 题引用条号不存在数 = 0） | 挂账 | 需真豆包 + golden-30 脚本；本文件只证机制与五种输入的确定行为 |
| `3-A3`（真机问第 9999 条） | 挂账 | 需真模型；本文件已证「含 9999 条的整段编造 → 508002、不返回编造文本」与「夹在正常内容里 → 删条号 + 提示」两支 |
| **B3 条号正则不容忍空格写法** | ⚠️ 风险（记入文档） | B3 的抽取正则为 `第([零一二三四五六七八九十百千0-9]+)条`，**「第 9999 条」（数字与「第/条」之间有空格）抽不出来**。本类按任务书「不重复实现条号正则」只消费 B3 结果，因此该写法目前**不会被删/判无效**。若 `3-A3` 真机回答恰好带空格，需要 B3 侧放宽（段 4 复用同一份，改一处两处都生效）——**不在本任务范围**，已报 lead |
| 降级阈值 20 字 | 已按 lead 裁决实现 | 阈值是经验值；20 字以下的合法短回答被误判为无效的概率低但不为零，需 golden-30 观察 |
