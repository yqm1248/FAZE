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

---

# 三、完整错误码表

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
| `505001` | 200 | `error.trade.channel-down` | 支付渠道不可用 |
| `505002` | 200 | `error.trade.reconcile-diff` | 对账存在差异，需人工处理 |
| `505003` | 200 | `error.trade.flow-write-failed` | 流水写入失败（资金链路致命错误） |

## 3.6 文书域 `06`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `406001` | 200 | `error.document.template-not-found` | 模板不存在或已停用 |
| `406002` | 200 | `error.document.form-invalid` | 表单校验失败 |
| `406003` | 200 | `error.document.not-found` | 文书不存在 |
| `406004` | **403** | `error.document.forbidden` | 无权访问该文书 |
| `506001` | 200 | `error.document.generate-failed` | 文书生成失败 |
| `506002` | 200 | `error.document.pdf-failed` | PDF 转换失败 |
| `506003` | 200 | `error.document.font-missing` | 所需字体缺失（少数民族文字） |

## 3.7 案件域 `07`

| code | HTTP | msgKey | 场景 |
|---|---|---|---|
| `407001` | 200 | `error.case.not-found` | 案件不存在 |
| `407002` | 200 | `error.case.state-invalid` | 案件状态非法 |
| `407003` | **403** | `error.case.forbidden` | 无权访问该案件 |
| `407004` | 200 | `error.case.already-filed` | 已立案，不能重复提交 |
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

**合计 78 个码**（各域：01=14、02=12、03=6、04=6、05=10、06=7、07=5、08=7、09=4、10=7），覆盖第 0～8 段会出现的全部业务失败。

---

# 四、客户端处理规则

## 4.1 三个分流维度

```text
收到响应 →
  ① HTTP 401 → 走 token 刷新链路（T0-25 的单飞刷新）
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

4 语言的文案存 i18n 资源文件，key 与本表一一对应。**缺任一语言的 key，构建期就该失败**（这是第 2 段 T0-43 的 i18n 完整性检查）。

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
- [ ] 每个 `msgKey` 都有 4 语言资源（第 2 段起检查）
- [ ] 服务端 `ErrorCode` 枚举与本表逐行对应
- [ ] 客户端 `no-hardcoded-error-msg` lint 规则能拦住直接展示 `msg` 的写法（T0-22 实现）
- [ ] 单元测试：故意抛 `BusinessException(402001)` → 响应 HTTP 200 + code 402001；故意让 token 过期 → 响应 HTTP 401 + code 402002
