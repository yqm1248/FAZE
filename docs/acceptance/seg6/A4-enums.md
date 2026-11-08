# 段 6 · A4 验收证据：枚举与 i18n key 对照

- 生成时间：2026-11-08（Asia/Shanghai）
- 依据：《第 6 段工作流》§三 A4（3 套枚举，一次定死，避免各组各写一套）
- 结论：3 套枚举全部落地，**无重复值、无遗漏值**，i18n key 与《i18n 规范》§3 的 camelCase 一致。

---

## 一、支付渠道 `PayChannel`（3 种）

文件：`services/faze-api/src/main/java/com/faze/modules/trade/enums/PayChannel.java`

| 常量 | code（落库） | label | i18n key | 本段是否可跑 |
|---|---|---|---|---|
| `WECHAT` | `wechat` | 微信支付 | `trade.pay.channelWechat` | ⚠️ 代码就绪，联调等商户号 |
| `ALIPAY` | `alipay` | 支付宝 | `trade.pay.channelAlipay` | ⚠️ 同上 |
| `MOCK` | `mock` | Mock 通道 | `trade.pay.channelMock` | ✅ 四条可验锚点全在它上面 |

- 与 `recharge_record.channel` 列注释一致（`wechat / alipay / mock`），A1 已验。
- `isReal()` 区分 Mock 与真实渠道；`configPrefix()` 生成 `pay.<channel>`。
- 非法值抛 **401001**（参数错误），**不抛 405013**——405013 的语义是「渠道存在但被关掉」，两者不能混。
- **i18n key 形态在 B1 修正**：原写法 `trade.pay.channel.wechat` 是四段点号，违反《i18n 规范》的
  三段式「域.场景.元素 + 元素段 camelCase」（既有 `trade.pay.methodBalance` 即此形态）。现改为
  `trade.pay.channelWechat`，用枚举 switch 写字面量而**不拼 key**（规范禁拼 key）。

## 二、充值单状态 `RechargeStatus`（4 态）

文件：`.../modules/trade/enums/RechargeStatus.java`
状态机：`.../modules/trade/rule/RechargeStateMachine.java`

| 常量 | code（落库） | label | i18n key | 终态 |
|---|---|---|---|---|
| `PENDING` | `pending` | 待支付 | `trade.recharge.statusPending` | 否 |
| `SUCCESS` | `success` | 充值成功 | `trade.recharge.statusSuccess` | ✅ |
| `FAILED` | `failed` | 渠道失败 | `trade.recharge.statusFailed` | ✅ |
| `CLOSED` | `closed` | 已关闭 | `trade.recharge.statusClosed` | ✅ |

> i18n key 形态同 `PayChannel`：B1 从四段点号改为三段式 camelCase，理由见 §一 末条。

合法迁移 3 条：

| from → to | 触发方 | 是否入账 |
|---|---|---|
| `pending → success` | 支付回调 / 主动查询 | ✅ 同一事务入账 |
| `pending → failed` | 回调 / 查单 / 渠道下单失败 | ❌ 只置状态 |
| `pending → closed` | 定时任务（`expire_at <= now`） | ❌ 只置状态 |

> 工作流说的「6 条迁移」= 3 条合法边 + 3 条终态禁令，**不是 6 条合法边**。
> 4 态两两组合 12 种：合法 3、非法 9（含同态 4 条）。

## 三、对账差错类型 `ReconcileDiffType`（4 种）

文件：`.../modules/trade/enums/ReconcileDiffType.java`

| 常量 | code（落库） | label | i18n key | 允许自动补单 |
|---|---|---|---|---|
| `LOCAL_ONLY` | `local_only` | 平台有渠道无 | `trade.reconcile.diffType.localOnly` | ❌ 疑似伪造回调，人工核 |
| `CHANNEL_ONLY` | `channel_only` | 渠道有平台无 | `trade.reconcile.diffType.channelOnly` | ✅ **唯一允许自动补单的类型** |
| `AMOUNT_MISMATCH` | `amount_mismatch` | 金额不一致 | `trade.reconcile.diffType.amountMismatch` | ❌ 人工 |
| `IDENTITY_MISMATCH` | `identity_mismatch` | 账务恒等式不平 | `trade.reconcile.diffType.identityMismatch` | ❌ 告警人工 |

- `autoRecoverable` 是「哪些类型能自动补」的**唯一判据**，D 组不许再写第二份判断。
- code 是下划线（对齐 `reconciliation_diff.diff_type` 的既有取值），i18n key 用 camelCase（《i18n 规范》§3）。

---

## 四、单测覆盖

命令：`mvn -Dtest=RechargeStateMachineTest test`
实测：`Tests run: 7, Failures: 0, Errors: 0`

| 用例 | 覆盖点 | 结果 |
|---|---|---|
| 12 种状态组合 | 合法 3 通过、非法 9 被拒（断言计数） | PASS |
| 终态无出边 | success / failed / closed 的 9 条出边全非法 | PASS |
| 同态迁移 | 4 条同态（含 `pending→pending`）全非法 | PASS |
| **晚到回调** | `closed→success` / `failed→success` **必须非法**（走补偿新单） | PASS |
| null 输入 | 一律拒绝 | PASS |
| check 抛码 | 非法抛 405015，合法不抛 | PASS |
| 合法边清单 | 恰好 3 条且与业务定义一致 | PASS |

---

## 五、与其他模块的边界

| 事项 | 结论 |
|---|---|
| 实体 status 字段类型 | `String`（与段 2 / 段 5 一致），**不实现 MP `IEnum`**——工作流 A4 第 5 条写 IEnum 映射，但项目既有枚举全部是 String + `of()`，本段跟随现状；记 §十一 偏差 |
| `payment_record.channel` | 语义是「支付方式」（`balance`），**不动**；与 `recharge_record.channel`（资金渠道）是两回事 |
| 中文 label 位置 | 枚举里带中文 label 供开发者/后台看；**前端展示走 i18n key**，Java 不拼展示文案（与 `WithdrawStatus` 同构） |
