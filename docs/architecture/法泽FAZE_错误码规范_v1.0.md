# 法泽 FAZE 错误码规范定稿（V1.0）

> **用途**：第 0 段服务端 `ErrorCode` 枚举与客户端错误处理的唯一依据
> **定稿日期**：2026-10-28
> **决策依据**：第 0 段决策 D5（鉴权失效走 HTTP 401）、D6（错误码冻结为 6 位 `[C][DD][NNN]`）
> **取代**：技术路线 v2.3 §4.8.3 的正文描述与示例、附录 C 的分段表

---

# 一、先看问题：文档里有三套互相冲突的口径

技术路线 v2.3 关于错误码的三处说法**彼此矛盾**，必须先解决才能写代码：

| 位置 | 说法 | 位数 | 域位 | 问题 |
|---|---|---|---|---|
| **§4.8.3 正文** | `4xxxxx` / `5xxxxx`，第二段标识业务域「**1 位**」 | 6 位 | 1 位 | 若域位 1 位，则 `4` + 域(1) + 序号(3) = 5 位，与 6 位不符 |
| **§4.8.3 示例** | `400401` 登录失败 / `500501` 余额不足 / `500502` 订单状态非法 / `800801` AI 不可用 | 6 位 | 实为 2 位 | **`800801` 以 8 开头，违反「4xxxxx/5xxxxx」规则** |
| **附录 C** | `1xxxx`~`9xxxx`，域位在第 1 位 | **5 位** | 1 位 | 与上两处都不是一套 |

三套里没有一套是自洽的。这就是 D6 要冻结的原因。

---

# 二、冻结方案

```text
code 结构：6 位十进制整数   [C][DD][NNN]

C   = 1 位：4 = 客户端错误   5 = 服务端错误
DD  = 2 位：业务域
NNN = 3 位：域内序号，从 001 起

示例：401001  参数校验失败（4=客户端, 01=通用域, 001）
      402001  登录失败    （4=客户端, 02=账户域, 001）
      508001  AI 服务不可用（5=服务端, 08=AI域,   001）
```

## 2.1 域位分配

| DD | 域 | 对应后端包 `modules/` | 对应附录 C 旧码 |
|---|---|---|---|
| `01` | 通用 | `common` | `1xxxx` |
| `02` | 账户与鉴权 | `account` | `2xxxx` |
| `03` | 律师 | `lawyer` | `3xxxx` |
| `04` | 会话与消息 | `conversation` | `4xxxx` |
| `05` | 交易与资金 | `trade` | `5xxxx` |
| `06` | 文书 | `document` | `6xxxx` |
| `07` | 案件 | `caseflow` | `7xxxx` |
| `08` | AI | `ai` | `8xxxx` |
| `09` | 内容 | `content` | **新域**，附录 C 无（v2.3 才新增 `content` 业务域） |
| `10` | 运营与后台 | `ops` | `9xxxx`（附录 C 标为「运营/投诉」） |

> **注意 `DD` 是两位，所以 `10` 是合法值**（`510001` = 服务端 + 运营域 + 001）。旧附录 C 把 AI 放在 `8xxxx`、运营放 `9xxxx`，新方案里 `09` 给内容、`10` 给运营——因为 v2.3 新增了 `content` 业务域，旧表里没有它。

## 2.2 HTTP 状态码规则（落实 D5）

技术路线 §4.5 写「成功一律 200（含业务失败也返回 200 + code）」，这与第 0 段验收 6 要求「401 拦截」直接冲突。**冻结如下：**

| 情形 | HTTP 状态 | 响应体 |
|---|---|---|
| 成功 | `200` | `{code:200, msg:"ok", data, traceId}` |
| **登录凭证缺失 / 过期 / 无效** | **`401`** | `{code:402002/402003, msg, data:null, traceId}` |
| **无权限 / 账号封禁 / 未实名** | **`403`** | `{code:401008/402004/402007, ...}` |
| **其余一切业务失败** | `200` | `{code:4xxxxx/5xxxxx, msg, data:null, traceId}` |
| 网关级错误（502/503/504） | 由网关返回 | 不经过应用 |

**为什么鉴权要单独破例**：`fetch`、网关、APM、监控、限流全按 HTTP 状态分类。如果鉴权失败也返回 200，客户端拦截器必须解析 body 才知道要不要跳登录——多一层解析、多一处出错点，而且**任何中间件都看不出这是鉴权问题**。

**为什么其余仍返回 200**：业务失败（余额不足、订单状态非法）是**正常的业务语义**，不是传输层错误。用 HTTP 5xx 表达会让监控误报。

> **§3.10 的逐码表优先（2026-11-02 段 7 落地）**：`410008` / `410010` / `410012` 回 **409**、`410014` 回 **400**。这与本表「其余业务失败一律 200」的通则不矛盾——**逐码表是特例，特例优先于通则**。理由：这三类 409 是「状态已被别人改过」的**并发冲突**（审核/申诉已被处置、工单状态不允许），回 409 让前端能识别「刷新后重试」而不是当成业务提示；`410014` 是**入参错误**（导入文件格式），归 400。除这四个码外，第 7 段其余新码仍按通则回 200。

---

# 三、完整错误码表

<!-- FACTS:error-codes -->
> 本区块由 `scripts/facts/render-docs.mjs` 从 `docs/facts/*.json` 生成，**禁止手改**；要改先改代码再跑 `npm run facts`。

共 **96** 个码（重复 0 组、`msgKey` 为空 0 个）。号段分布：401:8 / 402:10 / 404:3 / 405:30 / 406:7 / 407:9 / 408:5 / 409:3 / 410:11 / 501:4 / 502:1 / 506:3 / 508:2。

| 码 | HTTP | msgKey | 中文 |
|---|---|---|---|
| 401001 | 200 | `error.common.param-invalid` | 参数校验失败 |
| 401002 | 200 | `error.common.param-missing` | 缺少必填参数 |
| 401003 | 200 | `error.common.param-format` | 参数格式错误 |
| 401004 | 200 | `error.common.not-found` | 资源不存在 |
| 401005 | 200 | `error.common.already-exists` | 资源已存在 |
| 401006 | 200 | `error.common.illegal-state` | 状态非法，当前操作不允许 |
| 401007 | 200 | `error.common.rate-limited` | 请求过于频繁 |
| 401008 | 403 | `error.common.forbidden` | 无权限访问该资源 |
| 402001 | 200 | `error.auth.login-failed` | 手机号或密码错误 |
| 402002 | 401 | `error.auth.token-expired` | 登录已过期，请重新登录 |
| 402003 | 401 | `error.auth.token-invalid` | 登录凭证无效，请重新登录 |
| 402004 | 403 | `error.auth.account-blocked` | 账号已被封禁 |
| 402005 | 200 | `error.auth.code-invalid` | 验证码错误或已过期 |
| 402006 | 200 | `error.auth.phone-taken` | 手机号已注册 |
| 402008 | 200 | `error.auth.account-cancelled` | 账号已注销 |
| 402009 | 200 | `error.auth.guardian-required` | 未成年人需填写监护人手机号 |
| 402010 | 200 | `error.auth.password-weak` | 密码强度不足 |
| 402012 | 200 | `error.auth.cancel-has-active-order` | 还有未完成的订单，请先处理后再注销 |
| 404001 | 200 | `error.conversation.not-found` | 会话不存在 |
| 404002 | 403 | `error.conversation.forbidden` | 无权访问该会话 |
| 404005 | 200 | `error.conversation.not-purchased` | 未购买该律师的咨询服务，不能发起会话 |
| 405001 | 200 | `error.trade.balance-insufficient` | 账户余额不足 |
| 405002 | 200 | `error.trade.order-not-found` | 订单不存在 |
| 405003 | 200 | `error.trade.order-state-invalid` | 订单状态非法，当前操作不允许 |
| 405004 | 200 | `error.trade.duplicate-submit` | 请勿重复提交 |
| 405005 | 200 | `error.trade.refund-exceeds` | 退款金额超出可退上限 |
| 405006 | 200 | `error.trade.amount-invalid` | 金额非法 |
| 405007 | 403 | `error.trade.not-order-owner` | 非本人订单，无权访问 |
| 405008 | 200 | `error.trade.lawyer-unavailable` | 该律师当前不可接单 |
| 405009 | 200 | `error.trade.service-unavailable` | 该律师未开通此服务 |
| 405010 | 200 | `error.trade.lawyer-offline` | 该律师当前不在线 |
| 405011 | 200 | `error.trade.refund-not-found` | 退款单不存在 |
| 405013 | 200 | `error.trade.channel-disabled` | 该支付渠道未启用 |
| 405014 | 200 | `error.trade.recharge-amount-invalid` | 充值金额超出允许区间 |
| 405015 | 200 | `error.trade.recharge-state-invalid` | 充值单状态非法，当前操作不允许 |
| 405016 | 200 | `error.trade.recharge-daily-limit` | 超出单日充值限额 |
| 405017 | 200 | `error.trade.callback-sign-invalid` | 支付回调验签失败 |
| 405018 | 200 | `error.trade.reconcile-diff-pending` | 对账存在未处理差异 |
| 405019 | 200 | `error.trade.channel-prepay-failed` | 渠道下单失败 |
| 405020 | 200 | `error.trade.recharge-realname-required` | 未实名认证，不能充值 |
| 405021 | 200 | `error.trade.review-not-found` | 评价不存在 |
| 405022 | 200 | `error.trade.review-duplicate` | 该订单已评价，不能重复评价 |
| 405023 | 200 | `error.trade.withdraw-below-min` | 提现金额低于最低限额 |
| 405024 | 200 | `error.trade.withdraw-insufficient` | 可提现余额不足 |
| 405025 | 200 | `error.trade.withdraw-state-invalid` | 提现单状态非法，当前操作不允许 |
| 405026 | 200 | `error.trade.settlement-mismatch` | 结算数据不一致，已停止生成 |
| 405027 | 200 | `error.trade.withdraw-too-frequent` | 提现过于频繁 |
| 405028 | 200 | `error.trade.reconcile-bill-invalid` | 渠道对账单格式非法 |
| 405029 | 200 | `error.trade.recharge-not-found` | 充值单不存在 |
| 405030 | 429 | `error.trade.recharge-rate-limited` | 充值操作过于频繁，请稍后重试 |
| 405031 | 200 | `error.trade.self-order-forbidden` | 不能给自己下单 |
| 406001 | 200 | `error.document.template-not-found` | 模板不存在或已停用 |
| 406002 | 200 | `error.document.form-invalid` | 表单校验失败 |
| 406003 | 200 | `error.document.not-found` | 文书不存在 |
| 406004 | 403 | `error.document.forbidden` | 无权访问该文书 |
| 406005 | 200 | `error.document.signature-limit` | 签名数量已达上限（最多 5 个） |
| 406006 | 200 | `error.document.signature-in-use` | 该签名已用于文书，不能删除 |
| 406007 | 200 | `error.document.signature-not-found` | 签名不存在 |
| 407001 | 200 | `error.case.not-found` | 案件不存在 |
| 407002 | 200 | `error.case.state-invalid` | 案件状态非法，当前操作不允许 |
| 407003 | 403 | `error.case.forbidden` | 无权访问该案件 |
| 407004 | 200 | `error.case.already-filed` | 该案件已立案，不能重复提交 |
| 407005 | 200 | `error.case.no-document` | 本案没有可送达的文书 |
| 407006 | 200 | `error.case.delivery-not-found` | 送达记录不存在 |
| 407007 | 200 | `error.case.delivery-already-signed` | 该文书已签收，不能重复签收 |
| 407008 | 200 | `error.case.delivery-method-invalid` | 送达方式非法 |
| 407009 | 200 | `error.case.resubmit-limit` | 补正次数已用完，请联系平台 |
| 408001 | 200 | `error.ai.no-law-matched` | 未在法条库检索到相关条文 |
| 408002 | 200 | `error.ai.quota-exceeded` | AI 调用次数超限 |
| 408003 | 200 | `error.ai.content-blocked` | 内容被安全策略拦截 |
| 408004 | 200 | `error.ai.out-of-scope` | 超出可咨询范围 |
| 408005 | 200 | `error.ai.prompt-injected` | 检测到 Prompt 注入企图 |
| 409005 | 200 | `error.content.case-no-law-ref` | 案例未填写法条引用，不能上线 |
| 409006 | 200 | `error.content.case-lang-incomplete` | 案例语言版本不齐 |
| 409007 | 200 | `error.content.case-published` | 案例已上线，须先下架再删除 |
| 410005 | 200 | `error.ops.sensitive-word-exists` | 该词条已存在 |
| 410006 | 200 | `error.ops.content-blocked` | 内容含违规信息，未发送 |
| 410007 | 200 | `error.ops.review-not-found` | 审核记录不存在 |
| 410008 | 409 | `error.ops.review-already-handled` | 该条已处置过 |
| 410009 | 200 | `error.ops.appeal-exists` | 该条已申诉过，请勿重复提交 |
| 410010 | 409 | `error.ops.appeal-handled` | 该申诉已处置 |
| 410011 | 200 | `error.ops.ticket-not-found` | 工单不存在 |
| 410012 | 409 | `error.ops.ticket-status-invalid` | 工单状态不允许该操作 |
| 410013 | 200 | `error.ops.announcement-sent` | 公告已发出，不可撤回 |
| 410014 | 400 | `error.ops.import-format` | 导入文件格式错误 |
| 410015 | 200 | `error.ops.export-too-large` | 导出行数超限，已转后台任务 |
| 501001 | 200 | `error.common.internal` | 服务器内部错误 |
| 501002 | 200 | `error.common.third-party-down` | 第三方服务不可用 |
| 501003 | 200 | `error.common.db-error` | 数据库操作失败 |
| 501005 | 200 | `error.common.idempotent-conflict` | 重复请求，请勿重复提交 |
| 502001 | 200 | `error.auth.sms-send-failed` | 短信发送失败 |
| 506001 | 200 | `error.document.generate-failed` | 文书生成失败 |
| 506002 | 200 | `error.document.pdf-failed` | PDF 转换失败 |
| 506003 | 200 | `error.document.font-missing` | 渲染所需字体缺失 |
| 508001 | 200 | `error.ai.service-down` | AI 服务暂不可用 |
| 508002 | 200 | `error.ai.output-invalid` | AI 输出校验失败，请重试 |
<!-- /FACTS:error-codes -->

> **上面这张全量表是机器生成的**（源：`ErrorCode.java` → `docs/facts/error-codes.json` → 本区块）。
> 下面 3.1~3.10 的**分域手写表保留**，用途变了：它们讲的是「这个号段当初为什么这样分配」，
> 属于设计意图；**要查某个码在不在、是多少，以上面那张表为准**。手写表会漏项，机器表不会。

## 3.1 通用域 `01`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `401001` | 200 | `error.common.param-invalid` | 参数校验失败（Bean Validation） |
| `401002` | 200 | `error.common.param-missing` | 缺少必填参数 |
| `401003` | 200 | `error.common.param-format` | 参数格式错误（手机号、日期等） |
| `401004` | 200 | `error.common.not-found` | 资源不存在 |
| `401005` | 200 | `error.common.already-exists` | 资源已存在 |
| `401006` | 200 | `error.common.illegal-state` | 状态非法，当前操作不允许 |
| `401007` | 200 | `error.common.rate-limited` | 请求过于频繁 |
| `401008` | **403** | `error.common.forbidden` | 无权限访问该资源 |
| `401009` | 200 | `error.common.payload-too-large` | 请求体或文件过大 |
| `501001` | 200 | `error.common.internal` | 服务器内部错误（兜底） |
| `501002` | 200 | `error.common.third-party-down` | 第三方服务不可用 |
| `501003` | 200 | `error.common.db-error` | 数据库操作失败 |
| `501004` | 200 | `error.common.cache-error` | 缓存操作失败 |
| `501005` | 200 | `error.common.idempotent-conflict` | 幂等键冲突（重复请求） |

## 3.2 账户与鉴权域 `02`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `402001` | 200 | `error.auth.login-failed` | 手机号或密码错误（**不区分是哪个错**，防账号枚举） |
| `402002` | **401** | `error.auth.token-expired` | 登录已过期，需刷新或重新登录 |
| `402003` | **401** | `error.auth.token-invalid` | 凭证无效（签名错、已被拉黑） |
| `402004` | **403** | `error.auth.account-blocked` | 账号已被封禁 |
| `402005` | 200 | `error.auth.code-invalid` | 短信验证码错误或已过期 |
| `402006` | 200 | `error.auth.phone-taken` | 手机号已注册 |
| `402007` | **403** | `error.auth.realname-required` | 未实名认证，不能进行该操作 |
| `402008` | 200 | `error.auth.account-cancelled` | 账号已注销 |
| `402009` | 200 | `error.auth.guardian-required` | 未成年人需监护人同意 |
| `402010` | 200 | `error.auth.password-weak` | 密码强度不足 |
| ~~`402011`~~ | — | ~~`error.auth.account-banned`~~ | **作废，未实现**。段 7 施工时核对代码：`ErrorCode.AUTH_ACCOUNT_BLOCKED` 用的是 **`402004`**（段 1 已登记，见本表上一行），`402011` 在 `ErrorCode.java` 里**从未存在**。`7-A4`「提示不能只是密码错误」由 `402004` 承担（HTTP 403，与 `402001` 的 200 可区分），**不新增码**。段 8 从 `410017` 起编时不要再启用本行 |
| `402012` | 200 | `error.auth.cancel-has-active-order` | **注销被拒：名下还有未完结订单**（2026-11-13 新增，DEF-015 / DRIFT-022）。口径是 `OrderStatus.isTerminal()` 的**补集**：`pending` / `paid` / `in_service` / `refunding` / `refund_rejected`；**同时查两个身份**（作为下单人 `orders.user_id`、作为服务方 `orders.lawyer_id`）。<br>**为什么单独开码**：注销不可逆，被拒时必须让用户知道「去处理订单」——复用 `401006`（状态非法）用户只能反复点注销然后投诉；也不与「有余额」共用（那是 `401006`），两个拒绝原因要能分流到「订单列表」还是「余额页」。**段 8 从 `402013` 起编** |
| `502001` | 200 | `error.auth.sms-send-failed` | 短信发送失败 |
| `502002` | 200 | `error.auth.realname-service-down` | 实名核验服务不可用 |

## 3.3 律师域 `03`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `403001` | 200 | `error.lawyer.not-found` | 律师不存在 |
| `403002` | **403** | `error.lawyer.not-approved` | 律师未通过审核 |
| `403003` | 200 | `error.lawyer.not-available` | 律师当前不可接单（离线/忙） |
| `403004` | 200 | `error.lawyer.application-pending` | 入驻申请审核中，不能重复提交 |
| `403005` | 200 | `error.lawyer.application-not-found` | 入驻申请不存在 |
| `403006` | **403** | `error.lawyer.not-owner` | 非本人律师资料，无权修改 |

## 3.4 会话与消息域 `04`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `404001` | 200 | `error.conversation.not-found` | 会话不存在 |
| `404002` | **403** | `error.conversation.forbidden` | 无权访问该会话 |
| `404003` | 200 | `error.conversation.closed` | 会话已结束，不能继续发送 |
| `404004` | 200 | `error.conversation.not-participant` | 不是会话参与方 |
| `404005` | 200 | `error.conversation.not-purchased` | 未购买该律师的咨询服务，不能发起会话（2026-11-09 新增；**只拦新建会话**，已有会话照常可进） |
| `504001` | 200 | `error.conversation.send-failed` | 消息发送失败 |
| `504002` | 200 | `error.conversation.push-down` | 离线推送服务不可用 |

## 3.5 交易与资金域 `05`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `405001` | 200 | `error.trade.balance-insufficient` | 余额不足 |
| `405002` | 200 | `error.trade.order-not-found` | 订单不存在 |
| `405003` | 200 | `error.trade.order-state-invalid` | 订单状态非法，当前操作不允许 |
| `405004` | 200 | `error.trade.duplicate-submit` | 重复提交（幂等拦截） |
| `405005` | 200 | `error.trade.refund-exceeds` | 退款金额超出可退上限 |
| `405006` | 200 | `error.trade.amount-invalid` | 金额非法（负数、超限、非整数分） |
| `405007` | **403** | `error.trade.not-order-owner` | 非本人订单 |
| `405023` | 200 | `error.trade.withdraw-below-min` | 提现金额低于最低限额（`sys_config.withdraw.min_amount_cents`，当前 10 分） |
| `405024` | 200 | `error.trade.withdraw-insufficient` | 可提现余额不足（`lawyer_balance.available_cents`） |
| `405025` | 200 | `error.trade.withdraw-state-invalid` | 提现单状态非法，当前操作不允许（非法迁移一律 403 语义走 200 + 本码） |
| `405026` | 200 | `error.trade.settlement-mismatch` | 结算数据不一致（流水净额 ≠ 订单收入 − 服务费，**发现即停**，不许写结算单） |
| `405027` | 200 | `error.trade.withdraw-too-frequent` | 提现过于频繁（单日笔数超限） |
| `405013` | 200 | `error.trade.channel-disabled` | 支付渠道未启用（`sys_config.pay.*.enabled=false`）；**不许静默回退到 Mock** |
| `405014` | 200 | `error.trade.recharge-amount-invalid` | 充值金额超出单笔区间（`recharge.min_cents` ~ `recharge.max_amount_cents`，当前 0.1~5000 元） |
| `405015` | 200 | `error.trade.recharge-state-invalid` | 充值单状态非法，当前操作不允许（非法迁移一律 200 + 本码） |
| `405016` | 200 | `error.trade.recharge-daily-limit` | 超出单日充值限额或笔数（`recharge.max_daily_cents` / `max_daily_count`）；**不区分维度，避免被试探** |
| `405017` | 200 | `error.trade.callback-sign-invalid` | 支付回调验签失败（安全事件，必须写审计） |
| `405018` | 200 | `error.trade.reconcile-diff-pending` | 对账存在未处理差异，相关操作需先处理差异 |
| `405019` | 200 | `error.trade.channel-prepay-failed` | 渠道下单失败（`prepay` 调用失败） |
| `405020` | **200** | `error.trade.recharge-realbame-required` | **未实名用户不可充值**（`sys_config.recharge.unverified_daily_cents = 0`，2026-11-02 定：是拦截不是限额） |
| `405028` | 200 | `error.trade.reconcile-bill-invalid` | **渠道对账单非法**（空文件 / 缺表头 / 列数不符 / 金额或时间无法精确转分）——段 6 D3/D4 施工时追加（2026-11-08） |
| `405029` | 200 | `error.trade.recharge-not-found` | **充值单不存在**（人工补单接口 `POST /admin/recharges/{orderNo}/sync` 的单号查不到本地记录）——段 6 D1 施工时追加（2026-11-08）。**不要与「渠道查不到」混用**：后者是正常业务态，接口返回 200 + `syncResult=channel_not_found` |
| `405030` | **429** | `error.trade.recharge-rate-limited` | **充值下单过于频繁**（用户维度限流：`recharge.rate_limit_per_min`，默认 3 次/分钟）——段 6 B3 施工时追加（2026-11-08）。**本码是本规范里唯一「业务失败回 429」的例外**：其他余额类业务失败一律 200（§2.2）。响应带 `Retry-After` 头给重试秒数；不复用 `401007`（那个被短信 / 登录锁定 / 法条检索 / 支付密码锁定共用，且是 200 口径） |
| `405031` | 200 | `error.trade.self-order-forbidden` | **不能给自己下单**（2026-11-13 新增，DEF-014 / DRIFT-016）：下单人的 `app_user.id` 等于承办律师的 `lawyer.user_id` 时拒。<br>**编号不是这次现取的**：`docs/acceptance/guard/G6-后端边界测试.md` §二第 3 行早就把「律师给自己下单返回 405031」写成验收判据，`OrderSelfCreateTest` 与 `PendingBehaviorSpecsTest` 也按这个码描述——**码是既定契约，缺的是实现**，所以按原编号落地，不另起新号（换号会让三份文档同时对不上）。<br>不拦的后果不是体验问题：律师下自己的单 → 余额付款 → 结算后收入流回自己的 `lawyer_balance`，资金转一圈回到原点，而平台按费率记的服务费是凭空多出的一笔应收 |
| `505001` | 200 | `error.trade.channel-down` | 支付渠道不可用 |
| `505002` | 200 | `error.trade.reconcile-diff` | 对账存在差异，需人工处理 |
| `505003` | 200 | `error.trade.flow-write-failed` | 流水写入失败（资金链路致命错误） |

### 3.5.1 段 2 施工登记（2026-11-06）

> **✅ 冲突已裁决（2026-11-06，负责人）：段 5 的 5 个交易域码整体顺延。**
> 段 2 施工时实际落地的 `405008`~`405011` 与 §3.5 表原本给段 5（提现 / 结算）的预留号重叠；
> 负责人选择**不动已交付的段 2 码**（改段 2 会牵动 C 端文案映射与已出的 4 份验收证据），
> 把段 5 的 5 个码从 `405008`~`405012` **顺延到 `405023`~`405027`**（§3.5 表已同步）。
> 段 6 的 `405013`~`405020` 与段 2 无交集，**保持不动**。
> 段 5、段 6 工作流与《接口规范》的相关表述已同步更新。

| code | HTTP | msgKey（代码实际值） | 段 2 用途 | 说明 |
|---|---|---|---|---|
| `405008` | 200 | `error.trade.lawyer-unavailable` | 律师当前不可接单（G2.1） | 段 2 占用；段 5 原定此号已顺延到 `405023` |
| `405009` | 200 | `error.trade.service-unavailable` | 律师未开通该服务（G2.1） | 同上（段 5 原定 → `405024`） |
| `405010` | 200 | `error.trade.lawyer-offline` | 律师当前不在线（G3.3） | 同上（段 5 原定 → `405025`） |
| `405011` | 200 | `error.trade.refund-not-found` | 退款单不存在（G4.1） | 同上（段 5 原定 → `405026`） |
| `405021` | 200 | `error.trade.review-not-found` | 评价不存在（G5.1 律师回复） | 段 2 占用（段 6 文档曾写「段 7 从 405021 起编」，段 7 实际用 `410xxx`，故无冲突） |
| `405022` | 200 | `error.trade.review-duplicate` | 该订单已评价（G5.1） | 同上 |

> 代码侧对应 `com.faze.common.exception.ErrorCode`；C 端文案在
> `apps/mobile-app/shared/i18n/resources/zh-Hans/error.json`，其余三语已登记缺译。



## 3.6 文书域 `06`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `406001` | 200 | `error.document.template-not-found` | 模板不存在或已停用 |
| `406002` | 200 | `error.document.form-invalid` | 表单校验失败 |
| `406003` | 200 | `error.document.not-found` | 文书不存在 |
| `406004` | **403** | `error.document.forbidden` | 无权访问该文书 |
| `506001` | 200 | `error.document.generate-failed` | 文书生成失败 |
| `506002` | 200 | `error.document.pdf-failed` | PDF 转换失败 |
| `506003` | 200 | `error.document.font-missing` | 渲染所需字体缺失（**中文场景指仿宋 `simfang.ttf` 不存在或 `doc.font.path` 配错**）。2026-11-07 改：段 4 起文书只做中文，本码不再表示「少数民族文字缺失」 |

## 3.7 案件域 `07`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `407001` | 200 | `error.case.not-found` | 案件不存在 |
| `407002` | 200 | `error.case.state-invalid` | 案件状态非法 |
| `407003` | **403** | `error.case.forbidden` | 无权访问该案件 |
| `407004` | 200 | `error.case.already-filed` | 已立案，不能重复提交 |
| `407005` | 200 | `error.case.no-document` | 本案没有可送达的文书（或该文书未生成 PDF） |
| `407006` | 200 | `error.case.delivery-not-found` | 送达记录不存在 |
| `407007` | 200 | `error.case.delivery-already-signed` | 该文书已签收，不可重复签收（首次结果不变，不覆盖签收时间） |
| `407008` | 200 | `error.case.delivery-method-invalid` | 送达方式非法（只允许 `platform` / `sms_pickup` / `mail`） |
| `507001` | 200 | `error.case.transition-failed` | 案件状态推进失败 |

## 3.8 AI 域 `08`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `408001` | 200 | `error.ai.no-law-matched` | 未在法条库检索到相关条文（**不编造，直接告知**） |
| `408002` | 200 | `error.ai.quota-exceeded` | AI 调用次数超限 |
| `408003` | 200 | `error.ai.content-blocked` | 内容被安全策略拦截（输入侧） |
| `408004` | 200 | `error.ai.out-of-scope` | 超出可咨询范围（如要求预测判决结果） |
| `408005` | 200 | `error.ai.prompt-injected` | 检测到 Prompt 注入企图 |
| `508001` | 200 | `error.ai.service-down` | 豆包服务不可用 |
| `508002` | 200 | `error.ai.output-invalid` | AI 输出校验失败（引用了不存在的条文号） |

## 3.9 内容域 `09`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `409001` | 200 | `error.content.article-not-found` | 文章不存在或未发布 |
| `409002` | 200 | `error.content.case-not-found` | 案例不存在或未发布 |
| `409003` | 200 | `error.content.review-rejected` | 内容审核未通过 |
| `409004` | 200 | `error.content.sensitive-word` | 内容含敏感词（原文落库，展示层拦截） |
| `409005` | 200 | `error.content.case-no-law-ref` | 案例未填法条引用（上线前必填；条号须经 `ArticleNoValidator` 校验） |
| `409006` | 200 | `error.content.case-lang-incomplete` | 案例语言版本不齐（门禁口径见《第 5 段工作流》§三 G4） |
| `409007` | 200 | `error.content.case-published` | 案例已上线，不能直接删除（须先下架） |

## 3.10 运营与后台域 `10`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `410001` | 200 | `error.ops.complaint-not-found` | 投诉不存在 |
| `410002` | 200 | `error.ops.ticket-closed` | 工单已关闭 |
| `410003` | 200 | `error.ops.faq-not-found` | FAQ 不存在 |
| `410004` | **403** | `error.ops.not-admin` | 非管理员 |
| `510001` | 200 | `error.ops.probe-not-configured` | 探针未配置（第 0 段用） |
| `510002` | 200 | `error.ops.job-failed` | 后台任务执行失败 |
| `510003` | 200 | `error.ops.export-failed` | 导出失败 |
| `410005` | 200 | `error.ops.sensitive-word-exists` | 词条已存在（同词同语言，段 7） |
| `410006` | 200 | `error.ops.content-blocked` | 内容命中 block 级敏感词，未发送/未提交（段 7） |
| `410007` | 200 | `error.ops.review-not-found` | 审核记录不存在（段 7） |
| `410008` | 409 | `error.ops.review-already-handled` | 该条审核记录已处置过（段 7） |
| `410009` | 200 | `error.ops.appeal-exists` | 该条命中已申诉过（段 7） |
| `410010` | 409 | `error.ops.appeal-handled` | 申诉已处置（段 7） |
| `410011` | 200 | `error.ops.ticket-not-found` | 工单不存在（段 7） |
| `410012` | 409 | `error.ops.ticket-status-invalid` | 工单状态不允许该操作（段 7） |
| `410013` | 200 | `error.ops.announcement-sent` | 公告已发出，不可撤回（段 7） |
| `410014` | 400 | `error.ops.import-format` | 导入文件格式错误（段 7） |
| `410015` | 200 | `error.ops.export-too-large` | 导出行数超限，已转后台任务（段 7） |
| `410016` | 200 | `error.ops.retention-archive-failed` | 归档失败，已停止清理（段 7） |

**合计 92 个有效码**（各域：01=14、**02=13**、03=6、04=6、**05=11**、06=7、07=5、08=7、09=4、**10=19**），覆盖第 0～8 段会出现的全部业务失败。
另在 §3.2 保留 **1 行已作废的 `402011`**（计划期拟新增、施工时确认从未实现），**不计入 92**，保留是为了让读到旧文档的人能查到「这个码去哪了」。

> **v1.35 补 2 个（2026-11-13，GAP 收口）**：`402012`（注销被拒：有未完结订单）+ `405031`（不能给自己下单）。
> 这两个码的来源不同，必须分清：
> - **`405031` 是既定契约补实现**——G6 台账与两条测试规格早就按这个号写「律师自下单返回 405031」，
>   代码里从来没实现过（DEF-014）。**不是新增设计，是补上欠的。**
> - **`402012` 是真新增**——注销被拒原本复用 `401006`（状态非法），文案对用户毫无指导性；
>   既然要给「去处理订单」的指引，就必须能与「有余额」分流（那条仍是 `401006`）。
>   `402011` 是作废空号，故从 `402012` 起编。
>
> **v1.34 补 12 个（2026-11-02 计划 / 2026-11-09 施工核对，段 7）**：运营域 `410005`~`410016` 共 12 个。
> **账户域没有新增码**：计划里写的 `402011 error.auth.account-banned` **施工时确认从未实现**，
> `7-A4`（账号封禁的提示必须与「密码错误」可区分）由段 1 就有的 **`402004 error.auth.account-blocked`（HTTP 403）** 承担，
> 见 §3.2 的作废说明。**段 8 从 `410017` 起编**。

---

# 四、客户端处理规则

## 4.1 三个分流维度

```text
收到响应 →
  ① HTTP 401 → 走 token 刷新链路（第 0 段的「鉴权态 + 单飞刷新」）
  ② HTTP 403 → 提示无权限/未实名/被封禁，按 msgKey 展示，不跳登录
  ③ HTTP 200 + code≠200 → 按 code 的 DD 域位查 msgKey，走 t() 展示
  ④ HTTP 200 + code=200 → 正常返回 data
```

## 4.2 必须遵守的四条

1. **禁止直接展示后端 `msg`。** 后端 `msg` 是给开发者看的调试信息（中文），必须换成 `msgKey` → `t()` 出来的本地化文案。否则 4 语言里三种语言的用户会看到中文报错。
2. **未知 `code` 有兜底。** 客户端遇到表里没有的 code，展示 `error.common.internal`，同时上报。**不允许白屏或崩溃。**
3. **`402002` 会触发刷新**（token 过期，可刷新）；**`402003` 不触发刷新**（token 无效，刷了也没用，直接登出）。这两个必须区分，否则会无限刷新循环。
4. **错误码即契约。** 服务端不允许临时新增码而不登记本文件；客户端不允许硬编码判断 `msg` 字符串。

## 4.3 `msgKey` 命名规范

```text
error.<域>.<场景>

域：common / auth / lawyer / conversation / trade / document / case / ai / content / ops
场景：kebab-case，动词或名词短语

例：error.auth.token-expired
    error.trade.balance-insufficient
    error.ai.output-invalid
```

4 语言的文案存 i18n 资源文件，key 与本表一一对应。**缺任一语言的 key，构建期就该失败**（这是第 0 段建 CI 时就要做的 i18n 完整性检查）。

---

# 五、与旧文档的处置

| 旧内容 | 处置 |
|---|---|
| §4.8.3 的「域位 1 位」 | 作废，本文件用 2 位 |
| §4.8.3 的四个示例（`400401`/`500501`/`500502`/`800801`） | 作废，重编（客户端代码尚为 0，现在是改写成本最低的时刻） |
| §4.8.3 的「成功一律 200，仅网关错误用 HTTP 错误码」 | **部分作废**：增加 401/403 例外（见 §2.2） |
| 附录 C 的 `1xxxx`~`9xxxx` 分段表 | 作废，由本文件 §2.1 域位表取代 |
| `R` 响应体结构（code/msg/data/traceId） | **保留不变** |

> **为什么现在改成本最低**：客户端代码为 0（原型已决定删除），服务端代码为 0。此刻重编零成本；等第 2 段写完再改，要动所有调用点。

---

# 六、这份定稿的验收

- [ ] 78 个码无重复、无断号（客户端码 `4xx` 与服务端码 `5xx` 各自在域内连续）
- [ ] 每个码都有唯一的 `msgKey`
- [ ] 每个 `msgKey` 都有 4 语言资源（第 0 段起强制检查，第 8 段做全量补齐）
- [ ] 服务端 `ErrorCode` 枚举与本表逐行对应
- [ ] 客户端 `no-hardcoded-error-msg` lint 规则能拦住直接展示 `msg` 的写法（第 0 段的 lint 规则集里实现）
- [ ] 单元测试：故意抛 `BusinessException(402001)` → 响应 HTTP 200 + code 402001；故意让 token 过期 → 响应 HTTP 401 + code 402002


---

> **【文档基线 v1.1】** 本文件属于「法泽 FAZE 文档基线 v1.1」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
