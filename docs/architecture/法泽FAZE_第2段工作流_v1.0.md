# 第 2 段工作流：律师咨询交易闭环

> **用途**：第 2 段的**唯一施工文档**。拿着它就能施工，施工完能二值判定。
> **上位依据**：《开发计划总纲》§5.3（段边界）、§4.1/§4.2（页面归属）、§六（门禁）；《数据表规范》附录 A（10 张表 + 本段新增 2 张）；《接口规范》§九（联调清单）；《后端代码规范》全部条款；《审计与日志规范》§3.2（18 项必审计动作）。
> **不重复的内容**：10 张表的 DDL 原件在《技术路线》对应章节，本文档只做「取哪一份、补哪些字段、砍哪些表」的裁决；接口字段级定义看《接口规范》；表名与索引规则看《数据表规范》。
> **定稿日期**：2026-11-01
> **前置段**：第 1 段。**本文档假定段 1 已出口**，凡依赖段 1 的地方在 §二 逐条列出。

---

## 零、开工前必须知道的事（2026-11-01 撰写时实测）

### 零.1 本段的真实起点：段 0、段 1 的施工都还没开始

这点必须先说清，否则下面所有「前置检查」会被误读成「现在就这样」：

- **文档**：段 0 工作流（2856 行）、段 1 工作流（1251 行）都已产出。
- **施工**：一次都还没跑。仓库实测（2026-11-01 17:5x）：`services/` 目录**不存在**；`apps/mobile-app` 只有 3 个路由壳 `.tsx`，没有 `features/`；`database/`、`pc/`、`deploy/` 均不存在。
- 因此本文档的写法是「**假定段 1 已出口**」，把它的承诺当已知条件，**开工前必须逐条复查**（§二），不通过不开工、不许拿「假定」当「已验收」。

**后果**：段 2 是**第一个真正串起「人—钱—交易—沟通」的段**。段 1 只把「钱」做成账务内核，段 2 要让钱真正流动。任何一处地基没打好，本段都会以「余额对不上」「状态卡死」的形式暴露，不会以编译错误暴露。

### 零.2 段 1 承诺了什么（段 2 可直接依赖，不许重造）

| 能力 | 段 1 提供的具体形态 | 段 2 怎么用 |
|---|---|---|
| **扣费引擎** | `deduct()`：幂等，基于 `balance_flow` 流水，返回扣款流水 ID | **唯一扣款入口**。下单支付必须调它，**禁止**直接 `UPDATE user_balance` 或另写一套扣款（`2-A3` 专验这条） |
| **余额真相** | `SUM(balance_flow.delta_cents) == user_balance.balance_cents` | 退款也必须是流水（`delta_cents` 正数），不是 UPDATE |
| **JWT + 401** | 过滤器 → `SecurityContext`，未登录统一 401 | 交易与 IM 全部接口挂同一套，不另起鉴权 |
| **`lawyer` 表** | 含 `rating` / `review_count` / `order_count` / `online_status` / `consult_price_cents` / `phone_price_cents` / `document_price_cents` / `balance_cents` | 律师详情、咨询下单取价、接单后的计数与评分回写都落在这张表 |
| **审计 AOP** | `@OperationLog` + `operation_log`（只追加） | PC 端 4 页写操作全部要标；C 端聊天原文查看（本段拍板项）也要写 |
| **`sys_config`** | 键值配置 + PC 参数页 | `platform_fee_rate`（15% 占位）、单号序列、自动关单时长等全放这里，**不写死代码** |
| **OSS + 签名 URL** | 私有桶 + 短时签名 | 退款凭证、评价图片（若做）沿用同一通道 |
| **页面四态 / `t()` / i18n 14 namespace / RTL** | 组件与规范已就绪 | 本段 16 个 APP 页直接复用，不许自建一套加载态 |
| **支付密码** | 已设可验，**连错 5 次锁 30 分钟**（段 1 拍板第 1 项） | 支付页调它校验，**不重写锁定逻辑** |

**段 1 明确不保证、段 2 必须自己解决的**：律师列表的展示形态（排序 / 筛选 / 推荐规则）。段 1 的 `1-Ax` 锚点只要求「律师能入驻、字段能存」，不涉及展示。

### 零.3 三条已经收口的旧冲突（本段不要再讨论）

| 冲突 | 裁决 | 出处 |
|---|---|---|
| 技术路线 §4.8.4.6 / §4.8.4.7 用旧表名（`legal_order` / `law_article` / `law_article_translation` / `document_template`） | **一律以《数据表规范》附录 A 为准**：`orders` / `article` / `article_translation` / `doc_template`。旧名视为笔误，本段不建旧名表 | 数据表规范附录 A |
| 事件/异步用不用 MQ | **不用消息队列**。统一「**数据库任务表 + 定时重试**」 | 总纲 L801-812（2026-10-30 拍板） |
| 订单与会话的绑定关系 | **一个律师一个会话，跨订单复用**（`conversation` 保持 `UNIQUE(user_id, lawyer_id)`，**不加 `order_id`**） | **本段拍板第 6 项（2026-11-01）**，详见 §零之二 |

### 零.4 段 2 的四条「不许」（写在这里防走样）

1. **扣款只有一个入口**：必须调段 1 的 `deduct()`。退款也必须走流水（正数 `delta_cents`）。**禁止**任何形式的 `UPDATE user_balance SET balance_cents = ...`。
2. **支付方式只有一个**：段 2 的支付页**只做余额支付**。微信/支付宝是「充值渠道」，段 6 才接；段 2 的支付页**不许出现渠道选择控件**（设计稿 `31-pay.html` 已按此画，自注「本页与导航树的唯一分歧：支付方式」）。
3. **不提前建别段的表**：`notification`（段 7）、`user_violation`（段 7）、`reconciliation_diff`（段 5）、`settlement`（段 5）一律**不建**。消息中心两页按「**空态 + 入口占位**」交付（本段拍板第 3 项）。
4. **状态只由服务端迁移**：客户端只发意图（`POST /orders/{id}/pay` 之类），**不发 `status` 字段**。任何接口出现「客户端传 status」都是缺陷。

### 零.5 浏览器验收口径（2026-11-06 负责人拍板：真机接入全部改为浏览器验收）

**总口径**：本段不再要求两台 Android 真机。凡原写「两台真机」的锚点，一律按「两个浏览器上下文」执行：

| 原要求 | 浏览器替代方式 | 证据要求 |
|---|---|---|
| 用户 A 与律师 B 两台设备 | 两个**独立 Chrome profile**（或一个普通窗口 + 一个隐身窗口）分饰，分别登录律师账号与普通用户账号 | 两个窗口并排截图（**CDP 截浏览器视口全屏，不要只截手机尺寸**）+ 两侧 token 各自的 `userId` |
| `2-A1`/`2-A2` 双端实时 | 同上；「≤2 秒」按**服务端收发日志时间戳**量，不按界面时间 | 服务端两行时间戳日志 + 两侧截图 |
| `2-A9` 断网 30 秒 | CDP `Network.emulateNetworkConditions`（`offline: true`，30 秒后恢复） | CDP 命令与起止时间戳写入证据 |
| `2-A3` 杀进程收推送 | **无替换**：浏览器没有系统级推送，也没有「杀进程」 | **挂账**（见下） |
| `2-A10` 真 RTL | **无替换**：RN Web 的 `I18nManager` 是空实现（段 1 已实测，`docs/acceptance/seg1/E2E-端到端验收.md:96`） | 改判为代码审查 + 近似布局 |

**两条改判**（证据文件首行必须写明）：

1. **`2-A3` 按 B 方案挂账**：浏览器口径只验「关闭标签页 → 重开 → 会话与未读角标由 `message_offline` + 历史增量拉取恢复」。**这与系统推送不等价**，锚点标「未验收（浏览器口径不可达）」，挂段 8 真机补验。**不许写成通过。**
2. **`2-A10` 改为代码审查 + 近似布局**：验 ①`left/right` 出现 0 处、②`I18nManager.isRTL` 0 处、③阿拉伯文 `letterSpacing: 0`、④RTL 近似布局截图。真 RTL 挂段 8。

**截图规范**：验收截图一律用 CDP 截**浏览器视口全屏**（如 1600×1000）；390×844 手机尺寸只用于「与设计稿逐像素对照」。

---

## 零之二、2026-11-01 负责人拍板的 7 项

这 7 项直接决定本段怎么做。**拍板前它们是「待定」，拍板后就是约束**；后续如需变更，必须回来改这张表并说明原因。

| # | 事项 | 拍板结果 | 落到哪里 |
|---|---|---|---|
| 1 | **`order_status_log` 建不建**（49 张表清单里没有；技术路线 §4.8.4.6 引用了它，且 §9.2 状态机要求可追溯每次迁移） | **建**。作为第 **50** 张表，归段 2。**理由**：`orders` 只存当前状态，技术路线 §9.2.1 有 **16 行迁移记录**（去重后 10 条状态对、14 个业务触发分支），其中 **2 处定时自动迁移**（`PENDING` 15 分钟超时→`CANCELLED`；`PAID` 30 分钟无人接→`REFUNDING`），没有流水表就无法回答「谁在什么时候把它从 PAID 改成 IN_SERVICE」——`2-A4` 要验的正是这个 | 数据表规范附录 A 增补第 50 行；技术路线补 DDL；本段 §三 G2、§四 `2-A4` |
| 2 | **`task_queue` 建不建**（49 张表清单里没有，但《数据表规范》**附录 D 第 3 条**要求给它加 `trace_id`；《后端代码规范》§9.2 L1032 直接写「`task_queue` 表字段（《数据表规范》附录 A）」——**引用的那张表并不存在**，悬空引用） | **建**。作为第 **51** 张表，归段 2。**字段不用新设计**：直接采纳《后端代码规范》§9.2 的 11 个字段（`id` / `task_type` / `biz_no` / `status` / `retry_count` / `next_retry_at` / `last_error` / `lock_owner` / `lock_expire_at` / `created_at` / `updated_at`）+ 附录 D 第 3 条的 `trace_id` = **12 个字段**。**理由**：不用 MQ 的替代方案就是「数据库任务表 + 定时重试」（总纲 L801-812 已拍板），段 2 的关单、自动退款、离线推送重试**全部**搭在它上面，不建等于这三件事没有落地载体 | 数据表规范附录 A 增补第 51 行（**收掉 §9.2 的悬空引用**）；技术路线补 DDL；本段 §三 G2、§四 `2-A3`、`2-A8` |
| 3 | **消息中心两页（`38-messages` / `39-message-detail`）的数据源**：`notification` 表在附录 A 里标的是**段 7**，但这两页在段 2 的 16 页清单里 | **段 2 做空态**：两个页面按「**空态 + 入口占位**」交付，**不建 `notification` 表**、不做已读/删除/分类，真实通知数据等段 7 的 `notification` + `notify_template` 落地后再接。**理由**：段 2 的 IM（`conversation` / `conversation_message`）和消息中心是**两个东西**——前者是「和律师的会话」，后者是「系统通知」。把系统通知提前到段 2，会把段 7 的表设计与推送链路一起压进来，而段 2 的 10 条锚点里没有一条要求系统通知 | 本段 §三 G6、§七「明确不做」；页面状态列标「空态交付」 |
| 4 | **订单状态机做到几个状态**：技术路线 §9.2 定义 **10 个状态值**、§9.2.1 列 **16 行迁移**，但其中 `DISPUTED`（争议）/ `SETTLED`（裁定维持）的触发方是「用户申诉」「平台裁定」，属于段 5 的案件中心与人工裁定 | **段 2 做 8 个状态**：`PENDING` / `PAID` / `IN_SERVICE` / `COMPLETED` / `CANCELLED` / `REFUNDING` / `REFUNDED` / `REFUND_REJECTED`。`DISPUTED` / `SETTLED` **留段 5**。**代价为零**：`orders.status` 是 `VARCHAR(20)` 不是 ENUM，段 5 加值不需要改表结构（`2-A4`） | 本段 §三 G2 的 `OrderStatus` 枚举只放 8 个值；技术路线 §9.2.1 标「段 2 实现前 8 态，后 2 态段 5」；`orders.status` 的列注释同步改 |
| 5 | **`payment_record` 写几条**：设计稿 `11-order-detail.html` 画的是「支付记录·共 **3** 条」（支付 / 退款 / 服务费），但服务费结算属段 5，段 2 没有结算数据 | **只写 2 条**：①**支付**行 ②**退款**行。服务费那一行**留段 5**（结算产生时才写）。**两条怎么区分**（DDL 里没有 `type` 字段）：`channel` 都填 `balance`（段 2 唯一渠道），**退款行 `amount_cents` 记负数**，使 `SUM(amount_cents)` 直接等于净收款；两行的 `transaction_id` 分别取各自 `balance_flow` 的主键（`BAL-<flowId>`），因此 `uk_payment_order_channel_txn` 不冲突。**代价**：不改 DDL，只改 `channel` 列注释（补 `balance`） | 本段 §三 G2、§四 `2-A6`；技术路线 `payment_record` 的 `channel` 注释加 `balance`；PC `11-order-detail` 显示 2 条并在页面上注明「服务费结算见段 5」 |
| 6 | **订单与会话怎么绑定**：`conversation` 现有唯一键是 `UNIQUE(user_id, lawyer_id)`，**没有 `order_id`**；而设计稿 `11-order-detail.html` 画了「关联会话 CV-2026-10387」 | **保持原设计：一个律师一个会话，跨订单复用**。`conversation` **不加 `order_id`**，唯一键不变。订单详情页的「关联会话」**靠 `(user_id, lawyer_id)` 反查 `conversation` 得到**，不是外键。**已知后果（记录在案，不推翻决策）**：同一用户对同一律师的多个订单会指向**同一个**会话窗口，用户无法按订单区分上下文；且「CV-2026-10387」这种编号**当前无字段承载**——`conversation` 没有 `conversation_no`，该编号是设计稿占位，界面上改用会话 `id` 或直接不显示编号 | 本段 §三 G7；PC `11-order-detail` 的「关联会话」改为按 `(user_id, lawyer_id)` 查询；§六 风险表登记 |
| 7 | **管理员能不能看 IM 聊天原文**（《未完成需备注》§5.2 原为「待定」；涉及隐私合规，设计稿自己不能定） | **全量可见，不脱敏**（负责人 2026-11-01 拍板，选项 C：管理员可看完整聊天原文）。**必须同时满足三条**：①**每一次查看都写 `operation_log`**——`module='conversation'`、`action='CONVERSATION_VIEW'`、`description` 写「conversationId=<id> 时间窗口=<from>~<to>」、`request_param` 存查询参数；**这条审计不许被任何参数开关关掉**；②接口**只对 PC 管理端角色开放**，C 端与律师端**一律不可**调用（越权按 `403` + 审计告警）；③**分页读取、禁止整包导出**（无「导出聊天记录」按钮）。**⚠️ 合规风险（如实记录，不替负责人背书）**：聊天内容可能含身份证号、病历、家庭纠纷等《个人信息保护法》第 28 条敏感个人信息，管理员无差别全量可见属于「超出必要范围处理」，与最小必要原则冲突；**建议后续（段 7 前）补「脱敏展示 + 按需解锁原文并留痕」**，本轮按拍板执行 | 本段 §三 G8、§四 `2-A7`；**设计稿缺口**：`11-order-detail.html` **没有**聊天记录入口，需补一屏（抽屉或独立页面，按 `design/admin/tokens.css` 自绘）；《未完成需备注》§5.2 由「待定」改为「已定」；**上游文档一处必改**：《审计与日志规范》§3.2 加 `CONVERSATION_VIEW` 一行（现行 18 项里没有它，且 L108 判定规则是「写接口默认必须标，纯查询不标」——**GET 不会被强制审计**）。**⚠️ 一条自查记录**：本文档初稿曾写「《技术路线》`operation_log` 的 DDL 缺 `detail` 字段」，**该结论错误，已撤回**——`operation_log` 在技术路线里有**两份 DDL**，§5.10 L1806 是**段 1 已宣告作废**的版本（确实没有 `detail`），§5.11.5 L2102 才是正式版，含 `detail JSON` / `target_type` / `target_id` / `result`。**查该表的 DDL 必须认 §5.11.5**，查错版本会得出完全相反的结论 |

> **⚠️ 2026-11-01 段 5 定稿时推翻第 5 项的一半：服务费行不写了**
>
> 三个理由：① **服务费不是支付**——用户只付一次 `amount_cents`，平台抽成与律师收入是**平台内部分账**，没有任何资金进出；② **会破坏第 5 项刚建立的恒等式**——「`SUM(amount_cents)` = 净收款」是段 2 的核心口径，再插一条服务费行，记负数则 SUM 变负、记正数则虚增收款、记 0 则多一条无意义行，三种都不成立；③ **会留下无流水支撑的孤儿行**——`payment_record` 每一行都对应一条 `balance_flow`（`transaction_id='BAL-<flowId>'`），服务费不产生流水，而段 1 的对账（余额 = SUM(流水)）管不到这张表，孤儿行无法被任何校验发现。
>
> **段 5 的做法**：`payment_record` **永远只有支付 / 退款两种行**；PC `11-order-detail` 的服务费改成**一行说明文字**，金额直接读 `orders.platform_fee_cents`（`orders` 表本来就有这个字段，段 2 每单都算了）。见《[法泽FAZE_第5段工作流_v1.0.md](法泽FAZE_第5段工作流_v1.0.md)》§三 B5、§零之二 第 8 项。
>
> 本条不改上面的拍板记录——**记录是历史，推翻要留痕**。
>
> **⚠️ 2026-11-06 段 7 定稿时推翻第 7 项**：`未完成需备注.md` §5.2 已改为「管理员**只能看「命中片段 + 前后各 3 条上下文摘要」**，不提供全量聊天记录拉取」（2026-11-02，《第 7 段工作流》§零之二 第 13 项）。**本段按段 7 口径执行**：PC A-12 的聊天入口只给「命中片段 + 上下文」，**不做全量查看、不做导出**；`GET /admin/conversations/{id}/messages` 的 `size` 上限压到 7（3+1+3），审计 `CONVERSATION_VIEW` 照写。篡改原文与全量拉取一律视为缺陷。

---

## 一、本段目标与出口

**目标**：让**钱真正流动一次**、让**沟通真正建立一次**。段 1 把「人」和「钱」的地基建好了，但一分钱没真正花出去；段 2 要把整条链路串起来：

> 看律师 → 下单 → 输支付密码扣余额 → 律师接单 → 进 IM 聊 → 律师结束 → 评价 → 评分回到律师页

以及两条旁路：**退款**（用户申请 → PC 审批 → 钱回余额）和**内容**（PC 录文章 → APP 首页显示真实文章）。

**做完这一段，平台可以真实运营**——用户能付费咨询，律师能接单挣钱（收入结算留段 5），平台能抽成（比例是占位值 15%）。

**做完这一段仍然不能做的**（防止误以为可以上线）：真实支付渠道（段 6）、电话咨询与文书服务（段 4）、律师收入结算与提现（段 5）、AI 法律顾问（段 3）、全局搜索（段 5）。

### 一.1 出口（进段 3 的条件）

1. **`2-A1` ~ `2-A10` 十条锚点全部二值通过**，每条有证据文件（不接受「看起来能用」）。
2. **12 张表**（附录 A 的 10 张 + 本段新增的 `order_status_log` / `task_queue` 两张）由 Flyway 建出，`git status` 里**没有手工改库痕迹**，迁移脚本可重放。
3. **接口清单一条不少**（逐条列在 §五，不用「约」字）：App 侧与 PC 侧都有实测记录。
4. **订单状态机可追溯**：8 个状态值、10 条状态对、14 个触发分支，**合法迁移全部有测试、非法迁移全部抛业务异常**（`5xxxx`，不得静默忽略），每次迁移在 `order_status_log` 留痕。
5. **段 1 的余额恒等式仍然成立**：`SUM(balance_flow.delta_cents) == user_balance.balance_cents` 差异为 0（段 1 的巡检脚本重跑一次，不能因为段 2 引入退款就破功）。
6. **推送至少在 2 个厂商 ROM 上验证**（其余厂商段 8 补）；且**杀进程后能收到**。

### 一.2 反向定义（什么不算出口）

| 看似完成 | 为什么不算 |
|---|---|
| 锚点靠 mock / 固定返回通过 | 不算。`2-A1` 必须真的扣余额，`2-A7` 必须真的来自 PC 录入的文章 |
| 12 张表手工 SQL 建、Flyway 事后补 | 不算。迁移必须可重放（段 0 的门禁） |
| 「状态能改」但非法迁移没测 | 不算。`2-A4` 要的是**可追溯 + 拒绝非法**，不是「能 UPDATE」 |
| IM「能发消息」但断网重连丢消息或重复 | 不算。`2-A9` 专门验这个 |
| 推送「能收到」但点开进首页 | 不算。`2-A3` 原文是「**不是首页**」 |
| 文章「能显示」但内容是硬编码 | 不算。`2-A7` 原文是「**不是假数据**」 |
| 退款「能退」但没写 `balance_flow` | 不算。`2-A6` 要求「流水新增 1 条退款记录」 |
| 支付「能付」但支付密码可绕过 | 不算。`2-A5` 验的就是「不放行、不扣款、不生成订单」 |
| 消息中心两页「有页面」但接了假数据 | 不算。本段拍板第 3 项明确是**空态**，接假数据反而算缺陷 |

---

## 二、开工前置检查（逐条勾，不通过不开工）

**15 项。** 第 1~8 项是段 1 的出口承诺（不通过就回段 1），第 9~15 项是本段新增。

| # | 检查项 | 怎么确认 | 不通过怎么办 |
|---|---|---|---|
| 1 | **段 1 已出口**：`1-A1`~`1-A10` 全过 | 读 `docs/acceptance/seg1/` 下 10 条证据；退出码全 0 | 回段 1 补。**不许跳过**——本段的支付密码校验、`deduct()`、`balance_flow`、`sys_config`、`user_device` 全是段 1 产出 |
| 2 | `services/faze-api` 可启动，`/api/v1/pub/ping` 返回 200 | `curl` 实测 | 回段 1 |
| 3 | **段 1 的 12 张表已由 Flyway 建出**，特别是 `lawyer` / `user_balance` / `lawyer_balance` / `balance_flow` / `sys_config` / `operation_log` / `user_device` | `flyway_schema_history` 有 V1~Vn 记录；`SHOW TABLES` 逐张对 | 回段 1。**本段会强依赖这几张表，缺一张就有一组任务不能开工** |
| 4 | **`deduct()` 可用且幂等**：同一 `biz_id` 调两次只扣一次 | 段 1 的单测或手工实测（连续调两次，查 `balance_flow` 只有 1 行） | 回段 1。本段**不许另写扣款**（§零.4 第 1 条），它不可用则本段 `2-A1` 必然失败 |
| 5 | **支付密码可设、可验、连错 5 次锁 30 分钟** | 手工实测：连错 5 次，第 6 次提示「已锁定，请 30 分钟后重试」 | 回段 1。`2-A5` 直接依赖它 |
| 6 | **`user_device` 能写入并更新推送 token** | 调段 1 的设备绑定接口，查表有行；换 token 后能更新 | 回段 1。`2-A3`（杀进程收推送）没有它无法定向推送 |
| 7 | **`operation_log` 可用且只追加**（Mapper 无 update/delete） | 查段 1 的 ArchUnit 断言结果；查 DB 账号权限 | 回段 1。本段拍板第 7 项要求「查看聊天原文」写审计，表不可用则合规要求落空 |
| 8 | **OSS 可用**：能上传 1 KB 文件、能生成 60 秒签名 URL | 段 1 的连通性脚本重跑 | 回段 1。本段退款凭证、评价图片（若做）要用 |
| 9 | **Redis 可用**：`redis-cli ping` 返回 `PONG`；`INCR` 可用 | 启动 `E:\Redis-x64-5.0.14.1`（段 1 已注册服务） | 不起就不能做：IM 的 `seq` 靠 `INCR conv:{id}:seq` 生成；任务表领取的分布式锁也用它。**这两个没有降级方案** |
| 10 | **WebSocket 依赖可用**：`spring-boot-starter-websocket` 已在 `pom.xml`，且一个空 `@ServerEndpoint` 能连上 | 启动后用 `wscat` 或浏览器控制台连一个测试端点 | 加依赖（登记到 `docs/architecture/依赖清单.md`）。**不要让 IM 退化成轮询**——断线重连与实时性都是锚点 |
| 11 | **离线推送通道**（原「凭据可用」） | **2026-11-06 拍板：不阻塞本段**——`2-A3` 按 §零.5 的 B 方案挂账，浏览器口径只验「离线表补推 + 未读角标」，不验系统推送；段 8 真机补验 | 段 8 开工前 |
| 12 | **两个浏览器上下文**（替代原「两台真机」，2026-11-06 拍板） | 两个独立 Chrome profile，见 §零.5 | 起两个 profile 即可，不阻塞 |
| 13 | **两个测试账号可用**：一个普通用户、一个**已审核通过的律师**（且律师有 `consult_price_cents` 价格） | 查 `app_user` + `lawyer` 表，`lawyer.status` 为已通过、价格非空 | 走段 1 的入驻审核流程造一个。**律师价格为空会让下单金额为 0，`2-A1` 会「通过」但毫无意义** |
| 14 | **单号格式已定**：`sys_config.order_no_format = PREFIX2+yyyyMMdd+SEQ6`（实测已存在） | 查 `sys_config` | **生成器本身由本段 G1.3 实现，不是前置**——原表述与 G1.3 重复，已改 |
| 15 | **上游文档 2 处必改已完成**（否则施工方会照错的写）：① 数据表规范附录 A 补 `order_status_log` / `task_queue` 两行；② 审计与日志规范 §3.2 加 `CONVERSATION_VIEW` | 逐份读改后的文件 | 改完再开工。**这两处不改，本段的 `2-A4` / `2-A7` 会照错的文档施工**。**另注**：`operation_log` 的 DDL 有双份（§5.10 L1806 作废 / §5.11.5 L2102 为准），建表时**必须用 §5.11.5**，不要查错版本 |

---

## 三、任务分组（9 组 33 任务）

> **施工顺序**：G1 → G2 → G3 → G4 → G5 → G6 → G7 → G8 → G9。组内任务可连续做，**组间有硬依赖**（G2 需要 G1 的表和状态机；G6 的聊天页需要 G3 的订单跳转入口）。
>
> **每个任务的五段式**：**做什么** / **怎么做** / **产出** / **算做完** / **卡住怎么办**。

| 组 | 名称 | 任务数 | 主要锚点 | 工期占比（参考） |
|---|---|---|---|---|
| G1 | 交易地基 | 4 | `2-A4` / `2-A8` | 15% |
| G2 | 下单与支付 | 4 | `2-A1` / `2-A5` / `2-A8` | 20% |
| G3 | 律师侧与展示 | 4 | `2-A1` / `2-A2` / `2-A4` | 15% |
| G4 | 退款 | 3 | `2-A6` | 12% |
| G5 | 评价 | 2 | `2-A4` | 5% |
| G6 | IM 会话 | 6 | `2-A2` / `2-A9` / `2-A10` | 20% |
| G7 | 离线推送 | 3 | `2-A3` | 8% |
| G8 | 内容与消息中心 | 3 | `2-A7` | 5% |
| G9 | PC 后台 4 页 | 4 | `2-A6` / `2-A7` | 10% |

> **工期占比之和是 110%**。这不是笔误：G6/G7 是**可并行度最低**的两组，实际会挤占其他组的时间；占比只用来提示「哪几组别低估」，不用于排期（本项目不排期，见 AGENTS §1.2）。

---

### G1 交易地基（4 任务）

这一段的所有后续工作都建立在 G1 上。**G1 没做完，不要碰 G2。**

#### G1.1 建 12 张表（10 张既有 + 2 张新增）

- **做什么**：用 Flyway 建段 2 的 12 张表：附录 A 的 10 张（`conversation` / `conversation_message` / `message_offline` / `orders` / `payment_record` / `refund_record` / `review` / `article` / `article_translation` / `social_action`）+ 本段新增 2 张（`order_status_log` / `task_queue`）。
- **怎么做**：
  1. 10 张既有的 DDL **原件在《技术路线》**对应章节（`conversation` L1200 / `conversation_message` L1218 / `message_offline` L1240 / `orders` L1256 / `payment_record` L1281 / `refund_record` L1295 / `review` L1310 / `article` L1542 / `article_translation` L1572 / `social_action` L1693）。**照抄，不要重新设计**。
  2. 但**必须按本段 7 项拍板做四处修正**：① `orders.status` 列注释改成 8 个值（删 `disputed` / `settled`，补 `refund_rejected`）；② `payment_record.channel` 列注释补 `balance`；③ 新增 `order_status_log`；④ 新增 `task_queue`。
  3. 两张新表的字段：
     - `order_status_log`：`id` / `order_id` / `from_status` / `to_status` / `operator_type`（user / lawyer / admin / system）/ `operator_id` / `reason` / `created_at`；索引 `idx_osl_order (order_id, created_at)`。
     - `task_queue`：采纳《后端代码规范》§9.2 的 11 字段 + `trace_id` = 12 字段（见 §零之二 第 2 项）。
  4. 迁移版本号按《数据表规范》§八 的分段规则；种子数据（`sys_config` 的段 2 键）单独一个迁移文件。
- **产出**：`services/faze-api/src/main/resources/db/migration/V*.sql` 12 个（或 1 个合并 + 12 个 `CREATE TABLE`）；`docs/acceptance/seg2/G1-tables.md`（记录 12 张表建后的 `SHOW CREATE TABLE` 摘要）。
- **算做完**：`flyway_schema_history` 有本段记录；12 张表 `SHOW TABLES` 都在；**清库重放一次迁移，结果一致**；`git status` 干净（没有手工改库）。
- **卡住怎么办**：若某张表的 DDL 与附录 A 的字段数对不上，**以技术路线 DDL 为准并回改附录 A 的字段数**，不要两处各写一版。**实测（2026-11-01 脚本逐表比对）：段 2 这 10 张表的附录 A 字段数与技术路线 DDL 全部一致（10/10）**——`conversation` 10、`conversation_message` 15、`message_offline` 6、`orders` 17、`payment_record` 9、`refund_record` 11、`review` 11、`article` 14、`article_translation` 12、`social_action` 8。所以这条只是兜底规则，**当前不存在这个冲突**。

#### G1.2 状态机枚举与校验器

- **做什么**：实现 8 态 `OrderStatus` 枚举 + 合法迁移表 + 校验器，**服务端唯一依据**。
- **怎么做**：
  1. 枚举只放 8 个值：`PENDING` / `PAID` / `IN_SERVICE` / `COMPLETED` / `CANCELLED` / `REFUNDING` / `REFUNDED` / `REFUND_REJECTED`。**不许提前放** `DISPUTED` / `SETTLED`（段 5 加，`VARCHAR` 加值零 DDL 代价）。
  2. 迁移表按《技术路线》§9.2.1，**10 条状态对、14 个触发分支**。**换算过程必须写清楚，否则对不上 §9.2.1 的 16 行**：16 行 − 3 行（`COMPLETED→DISPUTED`、`DISPUTED→SETTLED`、`DISPUTED→REFUNDING`，属段 5）− 1 行（`COMPLETED→COMPLETED` 冻结期资金解冻，属段 5 结算）＝ 12 行；再把 1 行「`REFUNDING` 审核拒绝 → 回到前一状态」在段 2 **拆成 3 行**（`REFUNDING→REFUND_REJECTED`、`REFUND_REJECTED→IN_SERVICE`、`REFUND_REJECTED→COMPLETED`）→ 12 − 1 + 3 ＝ **14**。触发方相同的合并成一条状态对（如 `PENDING→CANCELLED` 有超时与用户取消两个触发），最终 **10 条状态对**；但校验时按触发方分别判权限。
   3. 校验器接口 `OrderStatusMachine.assertCanTransit(from, to, operatorRole)`；非法迁移**抛业务异常 `5xxxx`**，不许静默忽略。
   4. **`REFUND_REJECTED` 的回退目标要显式记录**：技术路线写「回到前一状态」，但 `orders` 表**没有** `refund_from_status` 字段。两种做法：① 加字段；② 查 `order_status_log` 最后一条进入 `REFUNDING` 之前的记录。**本段选 ②**（不加字段，`order_status_log` 已经要建），但**必须在服务层封装成 `resolveRefundOrigin(orderId)`**，不许在 Controller 里查。
- **产出**：`modules/trade/enums/OrderStatus.java`、`modules/trade/rule/OrderStatusMachine.java`（**纯规则层，可脱离 Spring 单测**，见《后端代码规范》§三）、单测 `OrderStatusMachineTest`（覆盖 10 条合法 + 全部非法组合）。
- **算做完**：单测全绿；**对每个状态穷举 8×8=64 个组合，验证只有 10 条合法**；`REFUND_REJECTED` 回退测试通过。
- **卡住怎么办**：若 64 组合测试与迁移表对不上，**以迁移表为准改测试**（迁移表是业务定义），但要记录哪一条对不上、为什么。

#### G1.3 单号生成器

- **做什么**：生成 `<业务前缀2位><yyyyMMdd><序号6位>` 格式的单号（如 `OR20261101000001`）。
- **怎么做**：
  1. 前缀：订单 `OR`、退款 `RF`（其余如充值 `RC` 段 6 用）。
  2. 序号用 Redis `INCR order:seq:OR:20261101`，**按天分桶**，`EXPIRE` 2 天。
  3. 数据库 `orders.order_no` 有 `UNIQUE KEY uk_orders_no` 兜底；插入冲突时**重试一次**（取新号），不许死循环。
  4. **禁止 `MAX(order_no)+1`**（并发必撞，且表大了会慢）。
- **产出**：`modules/trade/service/OrderNoGenerator.java` + 单测（100 并发不重复）。
- **算做完**：100 并发生成 100 个号**无重复**；跨天测试（模拟 23:59 → 00:00）序号从 1 重新开始。
- **卡住怎么办**：Redis 挂了就**不能下单**（这是硬依赖，不要退化成时间戳随机数，那会撞 `UNIQUE`）。

#### G1.4 扣款适配层（包一层，不重写）

- **做什么**：在 `trade` 域内包一层 `payFromBalance(userId, amountCents, bizType, bizId, payPassword)`，内部调段 1 的 `deduct()`。
- **怎么做**：
  1. **顺序必须是**：① 校验支付密码（段 1 的校验接口，连错计数也在段 1）→ ② 调 `deduct()` → ③ 返回扣款流水 ID。
  2. **`deduct()` 的幂等键用订单 id**（`biz_type='consume'`, `biz_id=orderId`），连点两次只扣一次。
  3. **不许**在 `trade` 里 `UPDATE user_balance`；**不许**另写扣款 SQL。
  4. 支付密码错误时**不产生任何流水、不产生订单状态变化**（`2-A5`）。
- **产出**：`modules/trade/service/BalancePayAdapter.java` + 单测（正常扣、余额不足、密码错、重复扣）。
- **算做完**：四个单测全绿；`balance_flow` 里 `biz_type='consume'` 且 `biz_id` 相同只出现 1 行。
- **卡住怎么办**：若段 1 的 `deduct()` 签名不接受支付密码（密码校验是独立步骤），**不要把密码校验塞进 `deduct()`**，保持两步调用；若 `deduct()` 内部也校验密码会二次扣锁定计数，**明确以段 1 的接口为准并记录**。

---

### G2 下单与支付（4 任务）

#### G2.1 下单接口 `POST /app/orders` + 订单两页

- **做什么**：①创建订单（状态 `PENDING`，**不扣款**）；②做订单列表页（`29-orders.html`）与订单详情页（`30-order-detail.html`）。
- **怎么做**：
  1. 入参：`lawyerId`、`serviceType`（`consult_text` / `consult_phone` / `document`）、`remark`。**金额不接受客户端传**，由服务端按 `lawyer` 表的 `consult_price_cents`（等）取价——客户端传金额是最常见的篡改面。
  2. 价格快照写进 `orders.amount_cents`（律师后续改价不能影响已下订单）。
  3. 幂等：客户端带 `Idempotency-Key` 请求头（按《接口规范》），同 key 重复提交返回**同一订单**。锚点 `2-A8` ②「连点 3 次只产生 1 个订单」。
  4. 服务端算 `platform_fee_cents = amount_cents * platform_fee_rate`（15% 占位，从 `sys_config` 读），`lawyer_income_cents = amount_cents - platform_fee_cents`。**只在订单上记这两个数，不产生流水**（结算段 5 做）。
  5. 写 `order_status_log`：`from_status = NULL`、`to_status = PENDING`、`operator_type = user`。
  6. **订单列表页**：`GET /app/orders?status=` 分页；tab 按状态分组（全部 / 待支付 / 进行中 / 已完成 / 已取消 / 退款中）；`PENDING` 订单显示剩余支付时间倒计时。
  7. **订单详情页**：`GET /app/orders/{id}`；按状态显示不同操作按钮（`PENDING`→「去支付」「取消」；`PAID`→「等待接单」；`IN_SERVICE`→「进会话」「申请退款」；`COMPLETED`→「去评价」；`REFUNDING`→「退款审核中」）。**设计稿 `30-order-detail.html` 没有申诉入口**，本段也不加（申诉属段 5）。
- **产出**：`OrderController#create` / `#list` / `#detail`、`OrderService#create`、DTO 校验、单测；`features/trade/pages/OrderListPage.tsx`、`OrderDetailPage.tsx`。
- **算做完**：同 `Idempotency-Key` 连发 3 次，`orders` 只有 1 行；不传金额也能算出正确金额；律师价格为空时**明确报错**（不许下单成 0 元）；列表页按状态筛选正确；详情页按钮随状态正确切换。
- **卡住怎么办**：若 `lawyer.audit_status != 'approved'`，**不许下单**（字段名是 `audit_status`，`pending` / `approved` / `rejected`）。「不在线」（`online_status` 为 `offline`）是否阻断**要在 `sys_config` 留开关，不要写死**——律师可能只是没开 App，写死会误杀正常业务。

#### G2.2 支付接口 `POST /app/orders/{id}/pay`

- **做什么**：校验支付密码 → 扣余额 → 状态 `PENDING → PAID` → 写 `payment_record`。
- **怎么做**：
  1. **顺序严格**：① 校验订单归属与状态（必须 `PENDING` 且 `user_id` = 当前用户）→ ② 校验支付密码（段 1 接口，连错计数在段 1）→ ③ 调 `BalancePayAdapter.payFromBalance()` → ④ CAS 更新 `orders.status`（`WHERE status='PENDING'`）→ ⑤ 写 `payment_record`（`channel='balance'`、`transaction_id='BAL-<flowId>'`、`amount_cents` 正数）→ ⑥ 写 `order_status_log`。
  2. **全部在一个事务里**，但 `deduct()` 若有自己的事务要确认传播行为（`REQUIRED`，不能是 `REQUIRES_NEW`，否则扣了钱订单没变）。
  3. `2-A5`：支付密码错 → **不扣款、不生成订单状态变化、返回可读错误**（不许把异常堆栈返回）。
  4. `2-A8` ①：超时关单后不能再支付（状态已 `CANCELLED`）。
- **产出**：`OrderController#pay`、`OrderService#pay`、单测（正常/密码错/余额不足/重复支付/状态不对）。
- **算做完**：五个单测全绿；**重复支付**（连发两次 pay）第二次返回「订单已支付」且**只扣一次钱**。
- **卡住怎么办**：若 `deduct()` 与订单更新无法同事务（跨域），**必须写补偿任务**进 `task_queue`（退款回滚），不能「扣了钱就当成功」。

#### G2.3 支付页 + 支付结果页（APP）

- **做什么**：`31-pay.html` 与 `32-pay-result.html` 两页，**只做余额一种支付方式**。
- **怎么做**：
  1. 支付页：显示订单金额、账户余额、余额不足时的引导（去充值——但充值页段 6 才有，**段 2 只显示文案不许跳转死链**）、支付密码输入。
  2. **不许出现微信/支付宝选项**（`31-pay.html` 自注：「本页与导航树的唯一分歧：支付方式」）。
  3. 支付结果页：成功显示订单号 + 金额 + 「查看订单」；失败显示原因（密码错/余额不足）。
  4. 四态齐全（正常/加载/空/错误），文案全走 `t()`。
- **产出**：`features/trade/pages/PayPage.tsx`、`PayResultPage.tsx` + 路由壳。
- **算做完**：设计稿逐像素对照通过；`2-A5` 的错误提示可读；点「去充值」不会跳到不存在的页面。
- **卡住怎么办**：充值入口目前无页面 → **显示「充值功能即将开放」并禁用按钮**，不要留一个点了报错的按钮。

#### G2.4 超时关单（`task_queue` 第一个消费者）

- **做什么**：`PENDING` 超过 15 分钟自动 `CANCELLED`；`PAID` 超过 30 分钟无人接自动 `REFUNDING` 并全额退。
- **怎么做**：
  1. 下单时**同时**往 `task_queue` 插一条 `task_type='order_close'`、`biz_no=orderNo`、`next_retry_at = now + 15min`。
  2. 定时任务每 30 秒扫 `next_retry_at <= now AND status='pending'`，**CAS 领取**（`UPDATE ... SET status='claimed', lock_owner=?, lock_expire_at=? WHERE id IN (...) AND status='pending'`，按影响行数判断）。
  3. 执行时**先查订单当前状态**：已不是 `PENDING` 就标记任务完成（**已投递是状态不是删除**，《后端代码规范》§9.2 规则 1）。
  4. 租约过期（`lock_expire_at < now`）的任务放回待领取，`retry_count+1`；`retry_count >= 5` 进死信。
  5. **`PAID` 30 分钟无人接 → `REFUNDING` 后立刻走自动退款**（段 2 的「自动全额退」不需要人工审批，但要写 `refund_record` + `payment_record` 负行 + `balance_flow`）。**这一条很容易做成「只改状态不退钱」**——`2-A8` ① 的原文是「余额退回且有流水」。
- **产出**：`modules/trade/task/OrderCloseTask.java`、`TaskQueueMapper`（CAS 领取 SQL）、`@Scheduled` 配置（线程池 ≥10，见《后端代码规范》§9.3）。
- **算做完**：`2-A8` ① 通过——订单自动关闭且**余额真的退回、流水真的有**；并发领 3 次只成功 1 次（单测）；租约回收单测通过。
- **卡住怎么办**：若定时任务与 WebSocket 争同一线程池，**分开线程池**（`taskScheduler` vs `bizExecutor`），别让慢任务饿死推送。

---

### G3 律师侧与展示（4 任务）

#### G3.1 律师列表页（`02-lawyer.html`）

- **做什么**：分类 tab（全部/民事纠纷/刑事辩护/婚姻家事/合同纠纷）+ 搜索 + 列表。
- **怎么做**：
  1. 接口 `GET /app/lawyers`：游标分页（按《接口规范》的 IM `cursor` 场景？**不，这里是列表场景**——用 C 端的 `list` 分页）。
  2. **排序规则本段自定**（段 1 明确不保证）：建议「在线优先 + 评分降序 + 订单数降序」，**规则写进 `sys_config` 便于调**。
  3. 设计稿 `02-lawyer.html` **没有排序控件** → 本段**不加排序控件**，避免与设计稿不一致。
  4. 头像走签名 URL（头像字段是 `lawyer.avatar_url`，若存 OSS key 则先取签名 URL）。
  5. **分类筛选用 `lawyer.specialties`（`JSON` 数组，L1120）**，匹配用 `JSON_CONTAINS(specialties, '"婚姻家事"')`，**不要 `LIKE`**（会误命中）。
- **产出**：`features/lawyer/pages/LawyerListPage.tsx`、`LawyerController#list`、SQL（含分类过滤）。
- **算做完**：5 个分类 tab 都能筛出正确结果；搜索命中姓名/擅长领域；空结果有空态；分页到底有「没有更多」。
- **卡住怎么办**：**这是一个真实的跨段缺陷，不是假设**——`lawyer.specialties` 是自由填写的 `JSON` 数组，而设计稿的分类 tab 是**固定 5 类**（全部 / 民事纠纷 / 刑事辩护 / 婚姻家事 / 合同纠纷）。两者不对齐，律师填了「劳动纠纷」就永远筛不到。**处理**：① 把 5 个分类值固化成枚举（写进 `sys_config` 键 `lawyer_specialty_enum`）；② **段 1 的入驻页尚未施工**，回头要求它把 `specialties` 改成这 5 类的**多选**，不许自由输入；③ 若段 1 已按自由输入施工，**段 2 加一个数据清洗脚本**把已有值映射到 5 类。**不要**为了让筛选能跑就新建分类关联表（那会与段 1 的 JSON 字段双真相）。

#### G3.2 律师详情页（`14-lawyer-detail.html`）

- **做什么**：律师资料、评分、评价列表、价格、「立即咨询」下单入口。
- **怎么做**：
  1. 接口 `GET /app/lawyers/{id}` + `GET /app/lawyers/{id}/reviews`。
  2. 详情页显示 `rating`（评分）、`review_count`、`order_count`、服务价格。
  3. 「立即咨询」→ 下单（G2.1）→ 支付页（G2.3）。**若已有会话，必须先建会话再下单？不——下单与会话解耦**（拍板第 6 项：一个律师一个会话，不代表下单时要建会话）。**下单不建会话；进 IM 时才 `POST /app/conversations`**。
  4. 关注按钮 `POST /app/lawyers/{id}/follow`（`social_action` 表，页面在段 7，**本段只做数据结构与接口**）。
- **产出**：`LawyerDetailPage.tsx`、`LawyerController#detail` / `#reviews`、`SocialController#follow`。
- **算做完**：`2-A4` 的后半段（评价后评分与评价数**即时更新**）在详情页可见；评价列表分页正确。
- **卡住怎么办**：若评分更新有缓存，**允许 1 秒内最终一致**，但 `2-A4` 要求「即时」→ 干脆不做缓存，直接查表。

#### G3.3 律师接单 / 拒单（`64-lawyer-orders.html`）

- **做什么**：律师侧订单管理页 + 接单/拒单接口。
- **怎么做**：
  1. 接口 `POST /app/orders/{id}/accept`、`POST /app/orders/{id}/reject`（技术路线 §4.8.2 L744 已拆分，不许合成 `/confirm`）。
  2. 接单：`PAID → IN_SERVICE`，写 `orders.accepted_at`、`lawyer.order_count + 1`、`order_status_log`。
  3. 拒单：`PAID → REFUNDING` + **自动全额退款**（同 G2.4 的退款路径），记律师违规（写 `operation_log`？**不，这是律师行为不是管理端行为** → 写 `order_status_log` 的 `reason`，`user_violation` 表段 7 才有）。
  4. **拒单率**若要做限制，放 `sys_config`；本段不做自动封禁。
  5. 只有 `lawyer_id` = 当前律师的订单能操作（越权校验，按《后端代码规范》§11.2）。
- **产出**：`LawyerOrdersPage.tsx`、`OrderController#accept` / `#reject`。
- **算做完**：`2-A2` 前半段（B 的「律师订单管理」收到新订单 → 接单 → A 端状态变「律师已接单」）；越权测试（别的律师调用返回 403）。
- **卡住怎么办**：律师侧如何知道有新订单？**本段用轮询**（页面每 10 秒拉一次列表），推送留给 G7；不要为了「实时」提前做 WebSocket 订单事件。

#### G3.4 结束服务（`/complete` + 律师工作台）

- **做什么**：律师结束服务 → 订单 `COMPLETED`；律师工作台首页看板。
- **怎么做**：
  1. 接口 `POST /app/orders/{id}/complete`：`IN_SERVICE → COMPLETED`，写 `finished_at`、`order_status_log`。
  2. **只有律师能结束**（用户侧没有「我完成了」按钮——用户的对应动作是「申请退款」）。
  3. `GET /app/workbench/summary`：今日订单数、收入（注：**收入只是订单上的 `lawyer_income_cents` 汇总，不是真到账**，页面上要写清「预计收入，结算见段 5」）。
  4. `PUT /app/workbench/online-status`、`PUT /app/workbench/service-settings`：改 `lawyer.online_status` 与价格。
- **产出**：`62-workbench.html` 页面、`OrderController#complete`、`WorkbenchController`。
- **算做完**：`2-A2` 后半段（结束会话 → 订单变「已完成」）；看板数字与订单表一致。
- **卡住怎么办**：若「结束服务」后用户还想追问，**订单已完成就不再允许新消息？**——**允许**（会话与订单解耦，拍板第 6 项的必然结果），但订单状态不再回退。

---

### G4 退款（3 任务）

#### G4.1 退款申请（APP `33-refund.html` + `POST /app/orders/{id}/refund`）

- **做什么**：用户发起退款申请，生成 `refund_record`（`status='applying'`），订单进 `REFUNDING`。
- **怎么做**：
  1. 允许发起的状态：`PAID` / `IN_SERVICE` / `COMPLETED`（`COMPLETED` 的退款需要后台裁定，但段 2 没有申诉环节 → **本段只允许 `PAID` / `IN_SERVICE` 发起**，`COMPLETED` 后的争议留段 5）。
  2. 入参：`reason`（必填）、`amountCents`（**不传 = 全额**；传了 = 部分退，且必须 ≤ 订单金额）。
  3. 页面 `33-refund.html`：**退款金额不可编辑**、**4 个预设原因**（照设计稿，不许自由发挥）。
  4. 写 `order_status_log`：`from = 原状态`、`to = REFUNDING`、`operator_type = user`、`reason`。
  5. **同一订单同时只能有一个进行中的退款单**（`refund_record` 里 `status IN ('applying','approved')` 的唯一性用应用层保证）。
- **产出**：`features/trade/pages/RefundPage.tsx`、`OrderController#refund`、`RefundService#apply`。
- **算做完**：`2-A6` 后半段（A 端订单状态与退款状态正确）；部分退时金额校验生效（传超额被拒）。
- **卡住怎么办**：`refund_record` **没有审批意见字段**（只有 `reason` 是申请人填的）。审批拒绝的理由要么塞进 `reason` 追加一段（丑），要么加 `audit_remark VARCHAR(500)`。**本段选加字段**——它是新表，还没建，加字段零成本；不留这个坑给段 5。

#### G4.2 PC 退款审批（`11-order-detail.html` + `POST /admin/refunds/{id}/audit`）

- **做什么**：管理员在 A-12 订单详情页审批退款。
- **怎么做**：
  1. 入参：`approved`（布尔）、`remark`（拒绝时必填）。
  2. 通过：`refund_record.status = 'refunded'`、`refunded_at = now()`、`audited_by` / `audited_at` 落值 → 调 G4.3 退款入账 → 订单 `REFUNDED`（**全额**）或回原状态（**部分退**，见 G4.3 的裁决）。
  3. 拒绝：`refund_record.status = 'rejected'`，订单 `REFUNDING → REFUND_REJECTED`，再按 `resolveRefundOrigin(orderId)` 回退到 `PAID` 或 `IN_SERVICE`。
  4. **必须标 `@OperationLog(module="trade", action="ORDER_REFUND")`**（《审计与日志规范》§3.2 段 2 明确列了这条）。
  5. `11-order-detail.html` 已有「退款审批入口」区块，按设计稿做，不新增控件。
- **产出**：`src/views/biz/trade/order/` 下的审批弹窗、`AdminRefundController#audit`。
- **算做完**：`2-A6` 全流程通过；审批动作在 `operation_log` 有 1 行且含 `before`/`after`；非法状态（如已 `REFUNDED` 再审批）被拒。
- **卡住怎么办**：若 `operation_log` 的 `detail` 字段还没补（§二 第 15 项），**先补 DDL 再做这个任务**——不然审计要求落空。

#### G4.3 退款入账（`balance_flow` + `payment_record` 负行）

- **做什么**：把钱退回用户余额，写两条流水痕迹。
- **怎么做**：
  1. `balance_flow`：`owner_type='user'`、`biz_type='refund'`、`biz_id = refundRecordId`、`delta_cents = +退款金额`、`balance_after` 快照。**幂等**：`uk_flow_biz` 保证同一退款单只入账一次。
  2. `payment_record`：插一行 `channel='balance'`、`transaction_id='BAL-<flowId>'`、**`amount_cents` 为负数**（拍板第 5 项），`status='success'`、`paid_at=now()`。
  3. `user_balance.balance_cents` **通过流水推算更新**，不许直接 UPDATE 出一个与流水不符的值（§零.4 第 1 条）。
  4. **律师余额不扣**：段 2 没有结算，订单上的 `lawyer_income_cents` 只是数字，未真实入账到 `lawyer_balance`。**所以退款时不动律师余额**——这一点必须写进代码注释，否则段 5 做结算的人会以为漏了。
  5. **部分退的订单状态裁决**：全额退 → `REFUNDED`；部分退 → **回到退款前状态**（`PAID` / `IN_SERVICE`），因为「部分退款」不代表订单作废。这个裁决**技术路线没写**，是本段补的。
- **产出**：`RefundService#refund`、单测（全额/部分/重复退款）。
- **算做完**：`2-A6` 说「余额恢复、流水新增 1 条退款记录」——跑完对账脚本，余额恒等式仍为 0 差异；重复退款只入账一次。
- **卡住怎么办**：若退款与订单状态更新跨事务，**入账后状态更新失败会导致「钱退了但订单还在 REFUNDING」** → 把状态更新放在入账**之前**并用 `task_queue` 补偿；或干脆同一个事务（同一库，可以）。

---

### G5 评价（2 任务）

#### G5.1 评价提交（`34-review.html` + `POST /app/orders/{id}/review`）

- **做什么**：订单 `COMPLETED` 后用户评价，`review` 表插一行。
- **怎么做**：
  1. 前置：订单必须 `COMPLETED` 且属于当前用户，且 `review` 无同 `order_id` 记录（`uk_review_order` 唯一键兜底）。
  2. 入参：`rating`（1-5，必填）、`tags`（JSON 数组）、`content`（≤500）、`isAnonymous`。
  3. 页面 `34-review.html`：有匿名开关、标签选择、「律师可以回复你的评价」的提示文案。
  4. **律师回复**：`review.lawyer_reply` 字段存在（L1318）。接口 `POST /app/reviews/{id}/reply`（技术路线 §4.8.2 **没列这个接口** → 本段新增，登记到《接口规范》）。
  5. 写 `order_status_log` 不需要（评价不改订单状态）。
- **产出**：`ReviewPage.tsx`、`OrderController#review`、`ReviewController#reply`。
- **算做完**：一单只能评一次；匿名评价在律师详情页不显示用户信息；律师回复能落库并展示。
- **卡住怎么办**：若「律师回复」被判定超出段 2 范围，可砍——但 `34-review.html` 已画、字段已存在，**砍了会造成设计稿与实现不符**，建议保留。

#### G5.2 评分回写（`2-A4` 的关键判据）

- **做什么**：评价后 `lawyer.rating` 与 `lawyer.review_count` **即时更新**。
- **怎么做**：
  1. 同一事务内：插 `review` → `SELECT AVG(rating), COUNT(*) FROM review WHERE lawyer_id=?` → `UPDATE lawyer SET rating=?, review_count=?`。
  2. **不许用「新评分 = (旧总分 + 新分)/新条数」的增量算法**——历史数据一改就永久错。每次都重算聚合。
  3. `rating` 是 `DECIMAL(2,1)`，四舍五入到 1 位小数。
  4. **不做缓存**（`2-A4` 要求「即时更新」，缓存会引入延迟）。
- **产出**：`ReviewService#recalcLawyerRating`、单测（3 条评价后评分正确）。
- **算做完**：`2-A4` 后半段——评价后律师详情页评分与评价数**立刻**正确。
- **卡住怎么办**：若评价量大导致 `AVG` 慢，加 `idx_review_lawyer (lawyer_id)` 索引（若没有），**不要**改增量算法。

---

### G6 IM 会话（6 任务）

> **这一组最容易做成「能发消息就算完」**。段 2 的 IM 验收是三条硬锚点：`2-A2`（实时 ≤2 秒）、`2-A9`（断线 30 秒不丢不重）、`2-A10`（RTL 镜像）。协议细节一律以《技术路线》§7 为准，本文档不重抄。

#### G6.1 会话与消息 REST 接口

- **做什么**：会话列表、创建/获取会话、历史消息、标记已读四个 REST 接口。
- **怎么做**：
  1. `GET /app/conversations`：按 `last_msg_at DESC` 分页（走 `idx_conversation_lawyer_msg` 的另一侧，用户侧需补索引或走 `uk_conversation_user_lawyer` 最左前缀）。
  2. `POST /app/conversations`：**按 `(user_id, lawyer_id)` 唯一**——已存在则返回已有会话（拍板第 6 项「一个律师一个会话」的直接体现）。`UNIQUE KEY uk_conversation_user_lawyer` 兜底，冲突时 `SELECT` 返回。
  3. `GET /app/conversations/{id}/messages?cursor=<seq>&limit=20`：**游标分页，不用 OFFSET**（`WHERE conversation_id=? AND seq < ? ORDER BY seq DESC LIMIT 20`）。
  4. 已撤回消息返回 `{recalled: true}`，**不回传 `content`**（§7.4）。
  5. **每个接口先过 `assertMember(conversationId, currentUserId)`**——越权是 IM 最常见的漏洞（§7.3）。
- **产出**：`conversation` 域 Controller / Service / Mapper，单测（非成员访问返回 403）。
- **算做完**：非成员拿别人会话 id 访问返回 403；历史分页用 `seq` 游标翻到底不重不漏。
- **卡住怎么办**：若律师侧会话列表需要不同排序，**加独立接口**（`GET /app/workbench/conversations` 已存在），不要给同一接口加 `role` 参数硬分支。

#### G6.2 WebSocket 服务端（握手鉴权 + 协议分发）

- **做什么**：`/ws/chat` 端点，JWT 握手鉴权，按 `type` 分发到处理器的统一入口。
- **怎么做**：
  1. **握手阶段必须验 JWT**（`HandshakeInterceptor` 里从 query 或 header 取 token，放进 `attributes`）。**不许「先连上再发 login 消息」**——那会有一个未鉴权窗口。
  2. 客户端 type 与服务端 type 见《技术路线》§7.2 的两张表。**不要自己发明 type**；`chat.kicked`、`chat.error` 等都要实现。
  3. **每条业务消息先 `assertMember`**，`chat.recall` 还要额外三条（消息属于该会话 + 发送者是自己 + 2 分钟内）。
  4. `chat.send`：先 `INCR conv:{id}:seq` 拿 seq → 插 `conversation_message`（`client_msg_id` 去重，冲突则返回已有消息的 `chat.ack`）→ 推 `chat.message` 给对端 → 回 `chat.ack` 给发送端。
  5. 在线表：Redis `Set` 或 `Hash` 存 `userId → sessionId`，连接建立写、断开删。
  6. **会话结束时（律师 `/complete`）不关连接**（会话与订单解耦，拍板第 6 项）。
- **产出**：`config/WebSocketConfig.java`、`ws/ChatEndpoint.java`、`ws/handler/*`（每种 type 一个处理器）、`ws/OnlineRegistry`。
- **算做完**：未带 token 的连接被拒；`chat.send` → 对端收到 `chat.message` → 发送端收到 `chat.ack`（含 seq）；`2-A2` 的「≤2 秒」实测达标。
- **卡住怎么办**：若 `spring-boot-starter-websocket` 的 `@ServerEndpoint` 与 Spring 依赖注入冲突（常见坑），用 `ServerEndpointExporter` + 静态 `ApplicationContext` 取 bean，或改用 Spring 原生 `WebSocketHandler`。**不要退回轮询**。

#### G6.3 聊天页（`17-chat.html`）

- **做什么**：一对一聊天界面，连 WS、收发消息、下拉加载历史、对方输入状态。
- **怎么做**：
  1. 进入页面先 `GET /app/conversations/{id}/messages` 拉最近 20 条，再连 WS。
  2. 发送时生成 `clientMsgId`（UUID），**先本地插入「发送中」气泡**，收到 `chat.ack` 后改为已发送；超时重发时带**同一个** `clientMsgId`（去重靠它）。
  3. 下拉到顶用 `seq` 游标加载更早消息（不要用 `offset`）。
  4. `chat.typing`：对方输入时显示「正在输入」，3 秒无输入自动消失。
  5. 断线时顶部显示「连接已断开，正在重连」，**不许静默**。
  6. **图片/文件消息**：`POST /app/upload/token` 直传 OSS → 发 `msg_type='image'/'file'`。
- **产出**：`features/conversation/pages/ChatPage.tsx`、`useChatSocket` hook（封装重连与消息去重）。
- **算做完**：`2-A2` 双方实时送达；发图片能显示；历史下拉不重不漏；发送失败有明确状态。
- **卡住怎么办**：若 RN 的 WS 在后台被杀，**App 内前台必须可用**；后台推送归 G7，两条路互不阻塞。

#### G6.4 已读、撤回、输入状态

- **做什么**：已读回执、2 分钟撤回、typing 状态。
- **怎么做**：
  1. 已读：`chat.read`（含 `messageId` / `seq`）→ 更新 `conversation_message.read_by_user_at` 或 `read_by_lawyer_at`（**双向字段**，L1230-1231）→ 清零 `conversation.unread_*` 并按 `seq` 推 `chat.read` 给对方。
  2. 撤回：`chat.recall` → 校验三条（成员 / 消息归属 / 发送者本人 / 2 分钟内）→ 置 `recalled_at`（**`msg_type` 不改**）→ 推 `chat.recall` 给对方。
  3. typing：`chat.typing` → 转推对方，**服务端不落库**。
  4. `POST /app/conversations/{id}/read` 是 **REST 兜底**（WS 断线时用），两条路径要**同一套逻辑**，不许各写一份。
- **产出**：`ws/handler/ReadHandler` / `RecallHandler` / `TypingHandler`。
- **算做完**：撤回超过 2 分钟被拒；撤回他人消息被拒；已读后未读角标归零；断线时 REST 已读也生效。
- **卡住怎么办**：若 `unread_*` 计数与 `conversation_message` 实际未读数对不上，**以 `read_by_*` 字段为准重算**，不要让计数器成为第二真相。

#### G6.5 断线重连（`2-A9` 硬锚点）

- **做什么**：断网 30 秒后恢复，期间 5 条消息不丢、不重、顺序正确。
- **怎么做**：
  1. 客户端维护 `lastSeq`（收到的最大 `seq`）。
  2. 重连成功后发 `chat.history` 请求 `seq > lastSeq` 的增量消息，**服务端按 seq 升序返回**。
  3. 服务端侧：消息落库成功即视为「已送达存储」，对端离线时写 `message_offline`（G6.6）。
  4. 客户端对 `clientMsgId` 去重（自己发的），对 `messageId` 去重（对方发的）。
  5. **重连要有退避**（1s / 2s / 4s / 8s / 最大 30s），不许死循环猛连。
- **产出**：`useChatSocket` 的重连逻辑 + `chat.history` 处理器。
- **算做完**：`2-A9` 通过——5 条全部到达、不重复、顺序正确。
- **卡住怎么办**：若增量拉取与实时推送**同时到达**造成重复，按 `messageId` 去重（客户端）；服务端保证「先入库再推送」，不许「先推送再入库」。

#### G6.6 离线消息（`message_offline`）

- **做什么**：对端不在线时消息进 `message_offline`，重连补推，推送成功后清理。
- **怎么做**：
  1. 插消息时查在线表：在线 → WS 直推；离线 → 写 `message_offline`（`user_id` / `conversation_id` / `message_id` / 状态）。
  2. 登录或重连时批量补推。
  3. **推送成功后清理**（附录 A L520 写的是「推送成功后清理」）→ 清理动作进 `task_queue`，不要同步删。
  4. 保留期：`sys_config` 给 7 天，超期清理任务。
- **产出**：`MessageOfflineMapper`、补推任务、清理任务。
- **算做完**：对端离线期间发的 3 条，重连后全部收到；清理任务跑过后表里没有已推成功的残留。
- **卡住怎么办**：若补推量大会拖慢登录，**分页补推**（每次 50 条），不要一次全量。

---

### G7 离线推送（3 任务）

> **外部依赖最重的一组**。总纲 §九 L855 把「个推应用 + 四个厂商通道」定在「**段 1 结束前**由负责人提供」，卡的正是 `2-A3`。若通道还没下来，**先做 G7.2 的服务端部分和 G7.3 的跳转逻辑，把 G7.1 挂账**——不许假装通过（§二 第 11 项）。

#### G7.1 推送通道接入

- **做什么**：接入 `user_device` 里存的推送标识，能向指定用户发一条推送。
- **怎么做**：
  1. 通道按段 1 结束前拍板的结果（统一**个推**：Android 走四厂商通道，iOS 走 APNs）。**不用 `expo-notifications`**（AGENTS §2 与技术路线已定）。
  2. 客户端启动时把 `deviceToken` / `clientId` 上报到 `user_device`（段 1 的表，字段已存在）。
  3. 服务端封装 `PushSender.send(userId, title, body, payload)`，**推送内容只带「您有一条新消息」，不带正文**（隐私：锁屏可见）。
  4. `payload` 带 `conversationId` 与 `messageId`（供 G7.3 直达）。
- **产出**：`infra/push/PushSender.java`、`infra/push/GetuiClient.java`（或对应 SDK 封装）、客户端上报逻辑。
- **算做完**：向测试机发一条推送，**锁屏能看到、点开能进 App**。
- **卡住怎么办**：SDK 不兼容 RN 0.81 要写原生桥——**这是已知风险**（总纲 §5.3 风险表第一行），预留时间，**不要因此砍掉 `2-A3`**。

#### G7.2 推送任务与重试（`task_queue` 第二个消费者）

- **做什么**：消息落库后异步发推送，失败按退避重试，超限进死信。
- **怎么做**：
  1. **推送不在消息事务内发**（《后端代码规范》§7.2：事务内禁止远程调用）。消息入库成功后往 `task_queue` 插一条 `task_type='push'`。
  2. 消费者取任务 → 调 `PushSender` → 成功标完成；失败 `retry_count+1`、`next_retry_at = now + 2^retry_count 分钟`。
  3. `retry_count >= 5` 进死信（`status='dead'`），PC A-26 可见（段 7 的页面；**段 2 只落数据，不做页面**）。
  4. **幂等**：同一 `messageId` 只推一次（用 `task_queue` 的 `biz_no` 唯一约束或应用层判重）。
- **产出**：`modules/ops/task/PushTask.java`（或 `trade` 域）、`TaskQueueMapper` 复用 G2.4 的 CAS 领取。
- **算做完**：故意让一次推送失败，验证重试 5 次后进死信；同一消息不重复推送。
- **卡住怎么办**：若推送服务商限流，**批量合并**（同一用户的 3 条未读合并成 1 条推送），但**不要**延迟超过 10 秒。

#### G7.3 点击直达会话（`2-A3` 硬锚点）

- **做什么**：点推送 → 打开 App → **直达该会话**（不是首页）。
- **怎么做**：
  1. 推送 `payload` 带 `conversationId`、`messageId`。
  2. App 冷启动 / 后台唤醒时读 payload → `router.push('/chat/[id]')`。
  3. **未登录时先跳登录，登录后再跳会话**（不能丢目标）；已登录直接进。
  4. 未读角标：`conversation.unread_*` 汇总到 App 图标角标（Android `Badge`）。
  5. `2-A3` 原文要求「未读角标正确」——角标数与 `GET /app/conversations` 的未读求和必须一致。
- **产出**：`app/+native-intent.tsx` 或推送回调处理、角标更新逻辑。
- **算做完**：`2-A3` 通过——杀进程后收到推送，点开进**该会话**；未读角标正确。
- **卡住怎么办**：若 Expo Router 的深链在冷启动时拿不到 payload，用 `getLastNotificationResponseAsync()` 兜底；**不要退化成「进首页自己找」**。

---

### G8 内容与消息中心（3 任务）

#### G8.1 文章接口（四语言）

- **做什么**：`GET /app/articles`、`GET /app/articles/{id}`，按语言返回译文。
- **怎么做**：
  1. `article` 存简体源内容（14 字段），`article_translation` 存非中文译文（12 字段）。
  2. 请求带 `Accept-Language`（或 `lang` 参数）→ 有译文返回译文，无译文**降级返回简体**并在响应里带 `langFallback: true`（前端显示「暂无该语言版本」）。
  3. 列表只返回 `status='published'` 的文章。
  4. 详情返回法条引用块（`article` 里的法条绑定字段，供 `2-A7` 的「法条引用块正常渲染」）。
- **产出**：`content` 域 Controller / Service / Mapper。
- **算做完**：`2-A7` 前半段——PC 录入的文章能在 APP 显示；切 `ug-Arab-CN` 有译文则显示译文、无则降级。
- **卡住怎么办**：若 `article` 表没有「法条引用」字段，查 DDL 确认；**没有就只做正文**，法条引用块显示为空并挂账（不许伪造引用）。

#### G8.2 文章列表页 + 详情页（`12-articles.html` / `13-article.html`）

- **做什么**：首页「每日学法」区块、文章列表页、文章详情页。
- **怎么做**：
  1. `01-home.html` 的「每日学法」区块接 `GET /app/articles?limit=3`（`2-A7` 验的就是这里）。
  2. 列表页分类筛选 + 分页；详情页正文 + 法条引用块。
  3. **四态齐全**；空态文案走 `t()`；RTL 下正文 `letterSpacing: 0`（阿拉伯系规范）。
- **产出**：`features/content/pages/ArticleListPage.tsx`、`ArticleDetailPage.tsx`、首页区块。
- **算做完**：`2-A7` 全过——PC 录文章 → APP 刷新首页显示该文章（**不是假数据**）。
- **卡住怎么办**：若首页区块原来是硬编码，**必须替换掉**，不是并排显示。

#### G8.3 消息中心两页空态（`38-messages.html` / `39-message-detail.html`）

- **做什么**：两页按「空态 + 入口占位」交付（拍板第 3 项）。
- **怎么做**：
  1. 页面结构按设计稿做，但**数据源为空**：显示空态插图 + 「暂无通知」+ 一句「通知功能即将开放」。
  2. **不建 `notification` 表**、不调接口、不做已读/删除/分类。
  3. 入口保留（首页/我的页有入口，点进去是空态页），**不能因为没数据就隐藏入口**——隐藏会让段 7 接数据时又要改导航。
  4. 四态里的「加载」「错误」两态**不适用**（无请求），但「空态」必须像样，不能是白屏。
- **产出**：`MessagesPage.tsx`、`MessageDetailPage.tsx`（结构占位）。
- **算做完**：点入口能进空态页，文案完整，切 RTL 不塌。
- **卡住怎么办**：若产品要求「至少显示系统消息」（如「欢迎使用法泽」），**可以在空态里写一句静态文案**，但仍然不建表。

---

### G9 PC 后台 4 页（4 任务）

> **前置**：PC 端的登录、菜单、`R` 响应、`@OperationLog` 注解全部来自 RuoYi 3.9.2 底座（段 0/段 1 已建）。本段**只写 `src/views/biz/` 下的业务页**，**不许改框架层**（AGENTS §3.3）。确需改动记 `docs/analysis/ruoyi-patches.md`。

#### G9.1 A-11 订单列表（`10-order-list.html`）

- **做什么**：订单列表，筛选（状态 / 时间 / 律师）、分页、导出。
- **怎么做**：
  1. 接口 `GET /admin/orders?status=&startDate=&endDate=&lawyerId=&pageNum=&pageSize=`。
  2. 表格列按设计稿；金额从分转元显示；状态用标签色（8 个状态各自的颜色在 `tokens`）。
  3. **导出走任务表**（《后端代码规范》§9.2/§9.3：>5000 行走异步），段 2 的导出量小可同步，但**代码里留异步分支**。
  4. 分页用 RuoYi 的 `rows` 格式（PC 管理端分页场景，按《接口规范》）。
- **产出**：`src/views/biz/trade/order/index.vue`、`src/api/biz/trade/order.js`。
- **算做完**：8 种状态都能筛出正确结果；分页正常；空态有提示。
- **卡住怎么办**：若 RuoYi 的分页组件与我们的 `R<T>` 冲突，**在 API 层适配**，不改框架。

#### G9.2 A-12 订单详情（`11-order-detail.html`）

- **做什么**：单笔订单全量信息：订单信息、**状态流转（6 节点时间线）**、**支付记录（段 2 显示 2 条）**、关联会话、**退款审批入口**、**查看聊天记录入口（新增）**。
- **怎么做**：
  1. 数据源三个接口：`GET /admin/orders/{id}`、`GET /admin/orders/{id}/status-log`、`GET /admin/refunds?orderId=`。
  2. **状态流转**从 `order_status_log` 读，显示 `from → to` + 操作人 + 时间；**不许用 `orders` 的四个时间戳拼**（那样缺 CANCELLED / REFUNDING 的节点）。
  3. **支付记录显示 2 条**（拍板第 5 项），页面上注明「服务费结算见段 5」。
  4. **关联会话**：按 `(user_id, lawyer_id)` 查 `conversation`（拍板第 6 项），**不显示 `CV-2026-10387` 这种不存在的编号**，显示会话 `id`。
  5. **查看聊天记录**：新增入口（拍板第 7 项），分页展示，**每次调用写 `operation_log`**。设计稿没有这个区块 → 按 `tokens.css` 自绘抽屉。
  6. 退款审批按 G4.2。
- **产出**：`src/views/biz/trade/order/detail.vue`、状态流转时间线组件、聊天记录抽屉。
- **算做完**：`2-A4` 的「状态流转可追溯」在页面上看得见；`2-A6` 审批入口可用；聊天记录能看且审计有记录。
- **卡住怎么办**：若 `order_status_log` 早期记录缺失（如从旧数据导入），**显示「历史记录缺失」**，不要补假数据。

#### G9.3 A-14 文章列表（`13-article-list.html`）

- **做什么**：文章列表，显示 **4 语言完成度**、上架/下架。
- **怎么做**：
  1. 接口 `GET /admin/articles?category=&status=&lang=`。
  2. **语言完成度列**：查 `article_translation` 有几条（`zh-Hans` 来自 `article` 本身），显示「3/4」这种徽标。
  3. 上架/下架：`POST /admin/articles/{id}/publish` / `offline`，**都必须标 `@OperationLog`**。
- **产出**：`src/views/biz/content/article/index.vue`。
- **算做完**：完成度徽标正确；上下架在 `operation_log` 有记录。
- **卡住怎么办**：若语言完成度查询 N+1 严重，**列表页用聚合 SQL 一次查**，不要逐行查。

#### G9.4 A-15 文章编辑（`14-article-edit.html`）

- **做什么**：新建/编辑文章，含四语言字段与法条引用绑定。
- **怎么做**：
  1. 接口 `POST /admin/articles`、`PUT /admin/articles/{id}`、`GET /admin/articles/{id}`。
  2. **四语言 tab**：简体（源）+ 藏/维/哈（译文），每个 tab 独立保存。
  3. **「四语言字段齐全才允许上架」**（总纲 §5.3 L375 的硬要求）→ 上架按钮在未填齐时**禁用并提示缺哪个语言**。
  4. 法条引用绑定：从 `law_item` 选条文（段 0 已建表），存引用 id 列表。
  5. 草稿可保存不完整内容（`status='draft'`）。
- **产出**：`src/views/biz/content/article/edit.vue` + 语言 tab 组件。
- **算做完**：`2-A7` 的前半段（「四语言字段填齐」才能上架）在 UI 上真的拦住；保存后 APP 能读到。
- **卡住怎么办**：若富文本编辑器与 RTL 冲突（阿拉伯文编辑体验差），**先用 textarea 保证能用**，富文本增强挂账——不要为了编辑器好看阻塞上架流程。

---

## 四、10 条锚点逐条（二值判定，不接受「看起来能用」）

> **格式**：每条给出 ①总纲原文 ②怎么验 ③通过标准（二值）④证据文件 ⑤依赖任务 ⑥**最容易糊弄过去的地方**。
> 证据文件统一放 `docs/acceptance/seg2/`。

### `2-A1` 下单扣款

- **总纲原文**：「两台真机（用户 A、律师 B）：A 在律师详情页下单图文咨询 → 支付页选余额 → 输支付密码」→ 通过标准：「余额按订单金额减少，`balance_flow` 新增 1 条消费流水；支付结果页显示成功与订单号」。
- **怎么验**：
  1. 记录用户 A 支付前余额 `B0`（`GET /app/wallet/balance`）。
  2. A 从律师详情页选律师 B → 下单 → 支付页 → 输正确支付密码 → 提交。
  3. 查 `orders` 该单：`status='PAID'`、`amount_cents` 等于律师 `consult_price_cents`、`order_no` 符合 `<OR><yyyyMMdd><6位>`。
  4. 查 `balance_flow`：`biz_type='consume'`、`biz_id=orderId`、`delta_cents = -amount_cents`、`balance_after = B0 - amount`。
  5. 查 `GET /app/wallet/balance` = `B0 - amount`。
  6. 支付结果页显示订单号。
- **通过标准（二值）**：六步全部为真 → 通过；任一步为假 → 不通过。
- **证据文件**：`docs/acceptance/seg2/2-A1.md`（含两张截图、三条 SQL 输出、余额前后值）。
- **依赖任务**：G1.3、G1.4、G2.1、G2.2、G2.3、G3.2。
- **最容易糊弄的地方**：
  - 只截「支付成功」页面**不查库**——页面可能只是前端跳转。
  - `balance_flow` 的 `delta_cents` 写成正数（那就是加钱了）。
  - `order_no` 用 `FAZE-2026-0087` 旧格式（技术路线里是占位，段 1 拍板已改成 `OR...`）。

### `2-A2` 接单与会话

- **总纲原文**：「B 的『律师订单管理』收到新订单 → 接单 → 双方进入 IM」→ 通过标准：「A 端状态变『律师已接单』；双方消息实时送达（**≤2 秒**）」。
- **怎么验**：
  1. A 下单支付后，B 打开「律师订单管理」页，**看到该订单**（不是要手动刷新才有的话也算，但要说明是轮询）。
  2. B 点「接单」→ 查 `orders.status='IN_SERVICE'`、`accepted_at` 非空、`order_status_log` 新增一条 `PAID→IN_SERVICE`（`operator_type='lawyer'`）。
  3. A 端订单详情状态变「律师已接单」。
  4. 双方进 IM，A 发一条消息，**用秒表/日志时间戳**量 B 收到的时间差 ≤ 2 秒。
  5. B 回一条，A 收到 ≤ 2 秒。
- **通过标准（二值）**：B 能看到订单 + 状态流转正确 + 消息双向 ≤2 秒 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A2.md`（含两台设备截图 + 服务端日志的时间戳对比）。
- **依赖任务**：G3.3、G6.1、G6.2、G6.3。
- **最容易糊弄的地方**：
  - 「实时」用轮询 1 秒也算过——**不许**。原文是 WebSocket 长连接，轮询不满足 `2-A9` 的断线重连语义。
  - 时间差用客服端显示时间（可能不准），要用**服务端收发日志**的时间戳。
  - 接单只改 `orders.status` 不写 `order_status_log`——`2-A4` 会因此无法验。

### `2-A3` 杀进程收推送

- **总纲原文**：「A 杀掉 App 进程 → B 发消息 → 点系统推送」→ 通过标准：「收到推送且点开进入**该会话**（不是首页）；未读角标正确」。
- **怎么验**：
  1. A 的 App **从最近任务里划掉**（不是按 Home 键）。
  2. B 发一条消息。
  3. A 手机**锁屏**状态下看通知栏：有推送，内容**不含聊天正文**（只写「您有一条新消息」）。
  4. 点推送 → App 启动 → **直接进入 B 的会话**（不是首页）。
  5. 未读角标 = `GET /app/conversations` 里该会话的未读数。
  6. 至少 **2 个厂商 ROM** 各验一次（如小米 + 华为）。
- **通过标准（二值）**：推送到达 + 点开**直达会话** + 角标正确 + 2 个 ROM 各一次 → 通过。
- **2026-11-06 改判（B 方案）**：浏览器口径下无系统推送、无「杀进程」，本条**挂账段 8 真机补验**，证据标「未验收」。可验部分（关标签页→重开→离线表补推→角标）按 §零.5 验并单列。
- **证据文件**：`docs/acceptance/seg2/2-A3.md`（每个 ROM 各一张锁屏截图 + 一张点开后截图 + 服务端推送发送日志）。
- **依赖任务**：G7.1、G7.2、G7.3、G6.6。
- **最容易糊弄的地方**：
  - **压到后台 ≠ 杀进程**。划掉才是杀进程。
  - 点开进首页再手动找会话 → **不通过**（原文明确「不是首页」）。
  - 推送内容带正文 → 虽然原文没禁，但本段定了「不带正文」（隐私），算缺陷。
  - 只在一个 ROM 上验 → 不通过（原文要求 2 个）。

### `2-A4` 结束与评价

- **总纲原文**：「B 结束会话 → 订单变『已完成』→ A 评价」→ 通过标准：「完成后 A 可评价；评价后律师详情页的评分与评价数**即时更新**」。
- **怎么验**：
  1. B 在订单详情点「结束服务」→ `orders.status='COMPLETED'`、`finished_at` 非空、`order_status_log` 新增 `IN_SERVICE→COMPLETED`。
  2. A 的订单详情出现「去评价」按钮。
  3. A 评价（评分 5 + 标签 + 匿名开关）→ `review` 插 1 行。
  4. **立刻**（不刷新缓存）打开律师详情页：评分与评价数已变。
  5. 查 `lawyer.rating` = `SELECT AVG(rating) FROM review WHERE lawyer_id=?`；`lawyer.review_count` = `COUNT(*)`。
  6. 同一订单再评一次 → 被拒（`uk_review_order`）。
- **通过标准（二值）**：六步全真 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A4.md`（含订单详情「状态流转」时间线截图 —— 这张同时是 `order_status_log` 的实证）。
- **依赖任务**：G1.1、G1.2、G3.4、G5.1、G5.2。
- **最容易糊弄的地方**：
  - 评分用「(旧总分+新分)/新条数」增量算法 → 数据一改就永久错，且本次验证看不出问题。
  - 「即时更新」实际是 5 分钟后缓存过期 → 要**当场**看。
  - 状态流转时间线用 `orders` 的四个时间戳拼 → 缺 `CANCELLED`/`REFUNDING` 节点，`2-A6` 时会露馅。

### `2-A5` 支付密码错误

- **总纲原文**：「支付时故意输错密码」→ 通过标准：「不放行、不扣款、不生成订单；错误提示可读（不是异常堆栈）；连续错误按 §十 的策略锁定」。
- **怎么验**：
  1. 下单后在支付页输**错误**支付密码 → 提交。
  2. **不扣款**：余额不变，`balance_flow` 无新增。
  3. **不生成订单**：原文这句有歧义，**负责人已于 2026-11-01 确认解释权归属**——下单接口本身已生成 `PENDING` 订单，因此「不生成订单」指**不产生已支付订单**（即不新增 `payment_record`、订单状态不变、不产生扣款流水）；`PENDING` 订单不算。证据文件里必须写清采用此解释。
  4. 错误提示可读（如「支付密码错误，还可尝试 4 次」），**不是** `NullPointerException` 或堆栈。
  5. 连续输错 5 次 → 第 6 次提示「已锁定，请 30 分钟后重试」；查 `sys_config` 的 `pay_pwd_max_fail=5` / `pay_pwd_lock_minutes=30` 生效。
  6. 锁定期内即使输对密码也不放行。
- **通过标准（二值）**：不扣款 + 提示可读 + 5 次锁定生效 + 锁定期正确密码也不放行 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A5.md`（含 6 次尝试的响应截图 + 余额不变的 SQL 输出 + 锁定状态查询）。
- **依赖任务**：G1.4、G2.2、G2.3。
- **最容易糊弄的地方**：
  - **「不生成订单」这句原文有歧义**（见上第 3 步）。不许含糊过去，必须在证据里写明采用的解释。
  - 只测「密码错」不测「锁定」——`2-A5` 明写「连续错误按 §十 的策略锁定」。
  - 锁定计数存在内存（重启就清零）→ 必须存 Redis，且重放验证。

### `2-A6` 退款闭环

- **总纲原文**：「A 在订单详情发起退款申请 → PC A-12 审批通过」→ 通过标准：「余额恢复、流水新增 1 条退款记录；A 端订单状态与退款状态正确；**全额 / 部分退两种都验**」。
- **怎么验**（全额）：
  1. A 对一笔 `IN_SERVICE` 订单发起全额退款（不填金额）→ `refund_record.status='applying'`、`orders.status='REFUNDING'`。
  2. PC A-12 打开该订单，看到退款单 → 点「通过」。
  3. 查 `balance_flow`：`biz_type='refund'`、`delta_cents = +全额`、`biz_id = refundRecordId`。
  4. 查 `payment_record`：新增一行 `channel='balance'`、`amount_cents` 为**负数**。
  5. 查 `refund_record.status='refunded'`、`refunded_at` 非空、`audited_by`/`audited_at` 有值。
  6. 查 `orders.status='REFUNDED'`。
  7. A 端订单状态与退款状态正确显示。
  8. 跑对账脚本，余额恒等式差异 = 0。
- **怎么验**（部分退）：同上，但第 1 步填一个小于订单金额的数；第 6 步 `orders.status` **回到退款前状态**（`PAID` 或 `IN_SERVICE`），**不是** `REFUNDED`。
- **怎么验**（拒绝路径，虽然原文没要求但必须测）：PC 点「驳回」→ `refund_record.status='rejected'`、`orders.status` 从 `REFUNDING` → `REFUND_REJECTED` → 回退到 `PAID`/`IN_SERVICE`。
- **通过标准（二值）**：全额 + 部分 + 拒绝三条路径全真，且对账差异 0 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A6.md`（三笔订单各一套 SQL 输出 + PC A-12 审批截图 + 对账脚本输出）。
- **依赖任务**：G1.2、G4.1、G4.2、G4.3、G9.2。
- **最容易糊弄的地方**：
  - 只验全额不验部分退——原文明确「两种都验」。
  - `payment_record` 的退款行写成**正数** → 净收款算错（拍板第 5 项定的是负数）。
  - 部分退后订单直接置 `REFUNDED` → 语义错（订单还活着）。
  - 退款不写 `balance_flow` 直接 UPDATE 余额 → 对账立刻破功。
  - **`REFUND_REJECTED` 回退到哪**没测——这是 8 态里最容易被漏掉的一条边。

### `2-A7` 首页真实数据

- **总纲原文**：「PC A-15 录入一篇文章（四语言字段填齐）→ A 刷新首页」→ 通过标准：「首页『每日学法』显示该文章（**不是假数据**）；文章详情法条引用块正常渲染」。
- **怎么验**：
  1. PC A-15 新建文章，**四种语言字段全部填齐**（简体 + 藏 + 维 + 哈）。
  2. 执行「上架」→ 若任一语言缺失，**上架按钮被禁用**（这是 G9.4 的验收点，也间接验了「齐全才能上架」）。
  3. A 在 APP 首页**下拉刷新** → 「每日学法」区块出现该文章标题。
  4. 点进去看正文正确；**法条引用块**显示（若文章绑定了法条）。
  5. 切 `ug-Arab-CN` → 显示维吾尔文版本（或降级提示）。
  6. 查 `article.status='published'`、`article_translation` 有 3 行（非中文）。
- **通过标准（二值）**：六步全真 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A7.md`（PC 编辑页四语言 tab 截图 + APP 首页截图 + 详情页截图 + RTL 截图）。
- **依赖任务**：G8.1、G8.2、G9.3、G9.4。
- **最容易糊弄的地方**：
  - 首页区块原本是硬编码，接接口后**并排显示**或保留 fallback 假数据 → 原文要求「不是假数据」，必须删掉假数据。
  - 「四语言填齐」只在文档里要求，UI 不拦 → 必须真的禁用上架按钮。
  - 法条引用块显示占位文字 → 若 `article` 表确实没有引用字段，要如实挂账，不许伪造。

### `2-A8` 超时与幂等

- **总纲原文**：「① 下单后不支付等超时；② 连点『提交订单』3 次」→ 通过标准：「① 订单自动关闭、**余额退回且有流水**；② 只产生 1 个订单」。
- **怎么验**（① 超时）：
  1. A 下单（`PENDING`）后**不支付**，等 15 分钟（或把 `sys_config` 的超时时间临时改成 1 分钟来测，但要在证据里写明改了配置并改回）。
  2. 到点后查 `orders.status='CANCELLED'`、`cancelled_at` 非空、`order_status_log` 新增 `PENDING→CANCELLED`（`operator_type='system'`）。
  3. **余额退回且有流水**：`PENDING` 阶段本来就没扣钱 → 这一条指的是**「`PAID` 30 分钟无人接」那条自动退款**。**原文这一句有歧义，负责人已于 2026-11-01 确认**：「余额退回」指 `PAID` 后 30 分钟无人接单的自动退款（钱已扣、需退回）；`PENDING` 15 分钟超时本就无款可退。因此**两条都验**：`PENDING` 超时关闭（无退款）+ `PAID` 超时自动退款（有退款流水），且退款必须真的写 `balance_flow`。
  4. 查 `balance_flow` 对 `PAID` 超时那笔：`biz_type='refund'`、`delta_cents` 正数。
- **怎么验**（② 幂等）：
  1. A 在支付页**快速连点**「提交订单」3 次（或并发发 3 个相同 `Idempotency-Key` 的请求）。
  2. 查 `orders`：只有 **1** 行。
  3. 查 `balance_flow`：只有 1 条 `consume` 流水（不能扣 3 次）。
- **通过标准（二值）**：`PENDING` 超时关闭 + `PAID` 超时自动退款有流水 + 连点只 1 单 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A8.md`（超时前后 SQL、退款流水 SQL、连点后 `COUNT(*)` 输出、改配置与改回的两张截图）。
- **依赖任务**：G2.1、G2.4、G4.3。
- **最容易糊弄的地方**：
  - **`PENDING` 超时根本没扣钱**，却声称「余额已退回」——这是最典型的假验收。
  - 连点 3 次产生 3 个订单但只支付了 1 个 → `COUNT(*)` 会露馅。
  - 幂等靠前端防抖（按钮置灰）而**后端没做** → 用接口直接打 3 次就破。

### `2-A9` 断线重连

- **总纲原文**：「会话中断网 30 秒再恢复，期间 B 连发 5 条」→ 通过标准：「5 条全部到达、不重复、顺序正确」。
- **怎么验**：
  1. A 与 B 进入同一会话，连接正常。
  2. A 开启飞行模式 / 关 WiFi（**断网 30 秒**）。
  3. 期间 B 连发 5 条消息（内容带序号 1~5）。
  4. A 恢复网络。
  5. 观察 A 端：**5 条全部出现**、**没有重复**、**顺序 1→2→3→4→5**。
  6. 服务端日志：重连后有一次 `chat.history` 增量拉取（`seq > lastSeq`）。
  7. 检查 `message_offline`：这 5 条在 A 离线期间写入，重连后补推，**推送成功后清理**。
- **通过标准（二值）**：5 条全到 + 不重复 + 顺序正确 → 通过。
- **证据文件**：`docs/acceptance/seg2/2-A9.md`（断网前后双方截图 + 服务端 WS 日志 + `message_offline` 前后行数）。
- **依赖任务**：G6.2、G6.5、G6.6。
- **最容易糊弄的地方**：
  - 断网 2 秒就恢复 → 不算 30 秒。
  - 5 条到了但顺序乱（先推新的再补旧的）→ 顺序错也算不通过。
  - 重复到达靠客户端 dedup 掩盖服务端重复推送 → 要查服务端日志确认没重复发。
  - **只测在线补拉不测离线表** → `message_offline` 是附录 A 里明确列的表，要有数据。

### `2-A10` RTL 会话页

- **总纲原文**：「切 `ug-Arab-CN` 进 IM」→ 通过标准：「气泡左右位置镜像正确（自己右侧、对方左侧的反向），时间戳与头像不重叠」。
- **怎么验**：
  1. 把 App 语言切成 `ug-Arab-CN`。
  2. 进入一个已有消息的会话。
  3. 检查：**自己发的消息在「开始侧」**（RTL 下是右侧）、对方在「结束侧」（左侧）——注意 RTL 下 start/end 会镜像，**用的是 `start`/`end` 而不是 `left`/`right`**。
  4. 时间戳与头像**不重叠**（气泡内的 padding 在 RTL 下正确）。
  5. 输入框、发送按钮位置正确。
  6. 检查代码里**没有** `I18nManager.isRTL` 手动反转（会与 RN 自动镜像叠加成「翻两遍」）。
  7. 检查阿拉伯文字 `letterSpacing: 0`（非 0 会导致连写断开）。
- **通过标准（二值）**：七步全真 → 通过。
- **2026-11-06 改判**：真 RTL 在浏览器不可达（RN Web `I18nManager` 空实现），改为「①`left/right` 0 处 ②`isRTL` 0 处 ③阿拉伯文 `letterSpacing: 0` ④近似布局截图」，真 RTL 挂段 8。见 §零.5。
- **证据文件**：`docs/acceptance/seg2/2-A10.md`（RTL 截图 + 代码 grep `isRTL` 为空 + 气泡 `start/end` 的代码片段）。
- **依赖任务**：G6.3。
- **最容易糊弄的地方**：
  - 只看截图「看起来对称」→ 要查代码是否用 `start/end`。
  - 手动 `flexDirection: 'row-reverse'` 硬翻 → 与 RN 自动镜像叠加，在英文环境下反而错。
  - 阿拉伯文字 `letterSpacing` 非 0 → 连写断开，视觉上像乱码。

---

## 五、产出清单（交付时逐项勾）

### 五.1 数据库（12 张表）

| # | 表名 | 来源 | 本段改动 |
|---|---|---|---|
| 1 | `conversation` | 技术路线 L1200 | 照抄（唯一键 `uk_conversation_user_lawyer` 不动） |
| 2 | `conversation_message` | 技术路线 L1218 | 照抄 |
| 3 | `message_offline` | 技术路线 L1240 | 照抄 |
| 4 | `orders` | 技术路线 L1256 | **改 `status` 列注释为 8 个值** |
| 5 | `payment_record` | 技术路线 L1281 | **改 `channel` 列注释补 `balance`** |
| 6 | `refund_record` | 技术路线 L1295 | **加 `audit_remark VARCHAR(500)`**（拒绝理由，原表没有） |
| 7 | `review` | 技术路线 L1310 | 照抄（已有 `lawyer_reply` / `tags` / `is_anonymous`） |
| 8 | `article` | 技术路线 L1542 | 照抄 |
| 9 | `article_translation` | 技术路线 L1572 | 照抄 |
| 10 | `social_action` | 技术路线 L1693 | 照抄 |
| 11 | `order_status_log` | **本段新增（第 50 张）** | 全新：`id` / `order_id` / `from_status` / `to_status` / `operator_type` / `operator_id` / `reason` / `created_at` |
| 12 | `task_queue` | **本段新增（第 51 张）** | 全新：11 字段（《后端代码规范》§9.2）+ `trace_id` |

### 五.2 接口（App 26 个 HTTP + 1 个 WS 端点；PC 11 个）

**App 侧（27 个端点，其中 1 个是 WebSocket）**

| # | 方法与路径 | 用途 | 任务 |
|---|---|---|---|
| 1 | `GET /app/lawyers` | 律师列表（分类/搜索/分页） | G3.1 |
| 2 | `GET /app/lawyers/{id}` | 律师详情 | G3.2 |
| 3 | `GET /app/lawyers/{id}/reviews` | 评价列表 | G3.2 |
| 4 | `POST /app/lawyers/{id}/follow` | 关注 | G3.2 |
| 5 | `DELETE /app/lawyers/{id}/follow` | 取消关注 | G3.2 |
| 6 | `GET /app/workbench/summary` | 律师工作台看板 | G3.4 |
| 7 | `GET /app/workbench/conversations` | 律师侧会话列表 | G6.1 |
| 8 | `PUT /app/workbench/online-status` | 在线状态 | G3.4 |
| 9 | `PUT /app/workbench/service-settings` | 服务开关与定价 | G3.4 |
| 10 | `GET /app/conversations` | 会话列表 | G6.1 |
| 11 | `POST /app/conversations` | 创建/获取会话 | G6.1 |
| 12 | `GET /app/conversations/{id}/messages` | 历史（`seq` 游标） | G6.1 |
| 13 | `POST /app/conversations/{id}/read` | 标记已读（REST 兜底） | G6.4 |
| 14 | **`WS /ws/chat`** | 长连接 | G6.2 |
| 15 | `GET /app/orders` | 订单列表 | G2.1 |
| 16 | `GET /app/orders/{id}` | 订单详情 | G2.1 |
| 17 | `POST /app/orders` | 下单 | G2.1 |
| 18 | `POST /app/orders/{id}/pay` | 支付 | G2.2 |
| 19 | `POST /app/orders/{id}/cancel` | 取消 | G2.4 |
| 20 | `POST /app/orders/{id}/refund` | 申请退款 | G4.1 |
| 21 | `POST /app/orders/{id}/accept` | 接单 | G3.3 |
| 22 | `POST /app/orders/{id}/reject` | 拒单 | G3.3 |
| 23 | `POST /app/orders/{id}/complete` | 完成 | G3.4 |
| 24 | `POST /app/orders/{id}/review` | 评价 | G5.1 |
| 25 | `GET /app/articles` | 文章列表 | G8.1 |
| 26 | `GET /app/articles/{id}` | 文章详情 | G8.1 |
| 27 | `POST /app/reviews/{id}/reply` | **律师回复评价（新增，技术路线未列）** | G5.1 |

**PC 侧（11 个）**

| # | 方法与路径 | 用途 | 页面/任务 |
|---|---|---|---|
| 1 | `GET /admin/orders` | 订单列表 | A-11 / G9.1 |
| 2 | `GET /admin/orders/{id}` | 订单详情 | A-12 / G9.2 |
| 3 | `GET /admin/orders/{id}/status-log` | 状态流转 | A-12 / G9.2 |
| 4 | `POST /admin/refunds/{id}/audit` | 退款审批 | A-12 / G4.2 |
| 5 | `GET /admin/articles` | 文章列表 | A-14 / G9.3 |
| 6 | `POST /admin/articles` | 新建文章 | A-15 / G9.4 |
| 7 | `GET /admin/articles/{id}` | 文章详情 | A-15 / G9.4 |
| 8 | `PUT /admin/articles/{id}` | 保存文章 | A-15 / G9.4 |
| 9 | `POST /admin/articles/{id}/publish` | 上架 | A-15 / G9.4 |
| 10 | `POST /admin/articles/{id}/offline` | 下架 | A-14 / G9.3 |
| 11 | `GET /admin/conversations/{id}/messages` | **查看聊天原文（新增，拍板第 7 项）** | A-12 / G9.2 |

> **合计 38 个端点**（App 27 + PC 11）。其中 2 个是**技术路线没有、本段因拍板而新增**的：`POST /app/reviews/{id}/reply`（律师回复评价）、`GET /admin/conversations/{id}/messages`（查看聊天原文）。这两个必须登记进《接口规范》。

### 五.3 APP 页面（16 页）

| # | 页面 | 设计稿 | 任务 |
|---|---|---|---|
| 1 | 首页 | `01-home.html` | G8.2 |
| 2 | 文章列表页 | `12-articles.html` | G8.2 |
| 3 | 文章详情页 | `13-article.html` | G8.2 |
| 4 | 律师列表页 | `02-lawyer.html` | G3.1 |
| 5 | 律师详情页 | `14-lawyer-detail.html` | G3.2 |
| 6 | IM 一对一聊天页 | `17-chat.html` | G6.3 |
| 7 | 消息中心页 | `38-messages.html` | G8.3（**空态**） |
| 8 | 消息详情页 | `39-message-detail.html` | G8.3（**空态**） |
| 9 | 订单列表页 | `29-orders.html` | G2.1 |
| 10 | 订单详情页 | `30-order-detail.html` | G2.1 / G4.1 |
| 11 | 支付页 | `31-pay.html` | G2.3 |
| 12 | 支付结果页 | `32-pay-result.html` | G2.3 |
| 13 | 退款申请页 | `33-refund.html` | G4.1 |
| 14 | 评价页 | `34-review.html` | G5.1 |
| 15 | 律师工作台首页 | `62-workbench.html` | G3.4 |
| 16 | 律师订单管理 | `64-lawyer-orders.html` | G3.3 |

### 五.4 PC 页面（4 页）

| # | 页面 | 设计稿 | 任务 |
|---|---|---|---|
| 1 | A-11 订单列表 | `design/admin/10-order-list.html` | G9.1 |
| 2 | A-12 订单详情 | `design/admin/11-order-detail.html` | G9.2 + G4.2 |
| 3 | A-14 文章列表 | `design/admin/13-article-list.html` | G9.3 |
| 4 | A-15 文章编辑 | `design/admin/14-article-edit.html` | G9.4 |

### 五.5 证据与文档

| 项 | 位置 |
|---|---|
| 10 条锚点证据 | `docs/acceptance/seg2/2-A1.md` ~ `2-A10.md` |
| 建表证据 | `docs/acceptance/seg2/G1-tables.md` |
| 本工作流回写（§七 实际做了什么、§六 实际遇到什么风险） | 本文档 |
| `CHANGELOG.md` 条目 | `docs/CHANGELOG.md` |
| 文档基线更新 | `docs/BASELINE.md` |
| RuoYi 框架层改动登记 | `docs/analysis/ruoyi-patches.md`（预期 0 条，非 0 逐条说明） |
| 新增依赖登记 | `docs/architecture/依赖清单.md`（WebSocket starter、推送 SDK 等） |

---

## 六、风险（施工前先看，别到出口才发现）

| # | 风险 | 概率 | 后果 | 应对 | 出处 |
|---|---|---|---|---|---|
| 1 | **推送通道没下来**（个推 + 四厂商） | 中 | `2-A3` 无法验收，本段出口卡住 | 先做 App 内长连接（G6），推送挂账；**但不许假装通过** | 总纲 §九 L855 |
| 2 | **推送 SDK 不兼容 RN 0.81 / Expo 54**，需写原生桥 | 中高 | G7.1 工期不可控 | 预留原生开发时间；优先用官方 RN SDK；实在不行换通道 | 总纲 §5.3 风险表 |
| 3 | **`lawyer.specialties` 自由 JSON vs 固定 5 类分类** | **高（已确认缺陷）** | 分类筛选筛不全 | 段 1 入驻页改多选（段 1 未施工，来得及）；否则段 2 加清洗脚本 | 本段 G3.1 |
| 4 | **「一个律师一个会话」导致多订单共享一个聊天窗口** | **确定发生** | 用户无法按订单区分上下文；客服排查混淆 | **拍板已定，不改**；在界面上显示「本会话可能涉及多个订单」的提示 | 本段拍板第 6 项 |
| 5 | **聊天原文全量可见的合规风险** | **确定存在** | 违反个保法最小必要原则的可能 | 按拍板执行 + 强制审计 + 建议段 7 前补脱敏 | 本段拍板第 7 项 |
| 6 | **`2-A5`「不生成订单」与「先下单再支付」的语义冲突** | 确定存在 | 验收时扯皮 | **已定（2026-11-01 负责人确认）**：指「不产生已支付订单」；证据文件里写明 | 本段 §四 `2-A5` |
| 7 | **`2-A8` ①「余额退回」在 `PENDING` 超时场景不成立**（没扣过钱） | 确定存在 | 验收时扯皮 | **已定（2026-11-01 负责人确认）**：指 `PAID` 30 分钟无人接的自动退款；两条都验 | 本段 §四 `2-A8` |
| 8 | **退款与订单状态跨事务** | 中 | 钱退了订单卡在 `REFUNDING` | 同库同事务；或入账后补偿任务 | 本段 G4.3 |
| 9 | **施工方查错 `operation_log` 的 DDL 版本**（该表在技术路线有双份：§5.10 L1806 作废 / §5.11.5 L2102 为准） | 中 | 照作废版建表 → 缺 `detail`/`target_type`/`target_id`/`result`，审计要求落空 | 建表时**必须**用 §5.11.5 L2102；本文档初稿就曾查错版本得出「缺 detail」的错误结论 | 本段 §零之二 第 7 项 |
| 10 | **律师供给不足，没有真人可下单** | 中 | 无法真实运营验证 | 用测试账号模拟；技术侧不阻塞 | 总纲 §5.3 |
| 11 | **段 1 的 `deduct()` 与订单更新不在同一事务** | 中 | 扣钱成功但订单没变 | 确认传播行为；补偿任务兜底 | 本段 G2.2 |
| 12 | **`orders.status` 注释只列 7 个值** | 确定存在 | 施工方照抄会漏 `refund_rejected` | G1.1 建表时改注释 | 本段 §零之二 第 4 项 |

---

## 七、明确不做（本段不许碰，碰了就是超范围）

| 不做的事 | 为什么 | 归哪段 |
|---|---|---|
| **真实支付渠道**（微信 / 支付宝） | 支付页只做余额；渠道是「充值」的事 | 段 6 |
| **电话咨询、文书服务** | 本段只做图文咨询的交易闭环 | 段 4 |
| **律师收入结算与提现** | `lawyer_income_cents` 只记账，不入 `lawyer_balance` | 段 5 |
| **`DISPUTED` / `SETTLED` 状态** | 争议与裁定属案件中心 | 段 5 |
| **`notification` 表与系统通知** | 消息中心两页做**空态**；不做已读/删除/分类 | 段 7 |
| **`user_violation` 违规处理** | 拒单只记 `order_status_log` 的 `reason`，不做处罚分级 | 段 7 |
| **`reconciliation_diff` 对账差错** | 只跑对账脚本验证差异 0，不落差错工单 | 段 5 |
| **收藏 / 关注页面** | `social_action` 表和一两个接口做，页面不做 | 段 7 |
| **AI 法律顾问** | 完全不碰 | 段 3 |
| **全局搜索** | 完全不碰 | 段 5 |
| **投诉 / 举报 / 客服工单** | 完全不碰 | 段 7 |
| **内容审核**（聊天与评价的敏感词） | 段 2 的聊天原文「只看不审」；审核队列 A-20 是段 7 | 段 7 |
| **评价的删除 / 修改 / 申诉** | 一单只能评一次，评了不能改 | 未定（可能段 7） |
| **聊天记录的导出** | 拍板第 7 项明确「禁止整包导出」 | 永远不做 |

---

## 八、留给下一段的接口（我保证什么 / 我不保证什么）

### 八.1 我保证（段 3~8 可以直接依赖）

1. **余额扣款只有一个入口**：`trade` 域的所有扣款都走段 1 的 `deduct()`，没有第二套。后续段（AI 计费、文书付费）照这个模式接。
2. **状态迁移可追溯**：每次 `orders.status` 变化都在 `order_status_log` 有对应行，段 5 做争议与结算时可以据此复盘。
3. **8 态 + 10 条状态对 + 14 个触发分支有单测**，段 5 加 `DISPUTED`/`SETTLED` 时有测试可扩展。
4. **`payment_record` 的语义清晰**：正数=收款、负数=退款，`SUM(amount_cents)` = 净额。段 5 做结算可直接用。
5. **`task_queue` 的领取是 CAS + 租约回收**，段 4 文书生成、段 7 内容审核可直接复用这套消费模式。
6. **IM 的越权防护**：所有会话接口都过 `assertMember`，段 7 的客服工单若要读会话，必须复用这个校验而不是绕过。
7. **消息中心两页是空态、**不是假数据**，段 7 接 `notification` 时只需要换数据源、不用改页面结构。
8. **PC 4 页没改 RuoYi 框架层**（预期 0 条 patch），`docs/analysis/ruoyi-patches.md` 如实登记。

### 八.2 我不保证（后续段可能推翻或补做）

1. **律师列表的排序规则**是本段自定的（在线优先 + 评分降序 + 订单数降序），段 5 的「推荐」可能换成别的算法。
2. **部分退后的订单状态语义**（回原状态）是本段裁决，技术路线没写；段 5 若要求「部分退也算退款完成」，要改。
3. **推送只在 2 个厂商 ROM 验证**，其余厂商段 8 补。
4. **聊天原文不脱敏**（按拍板），段 7 前若合规要求变化，要加脱敏层。
5. **评价不审核**（`content_review` 是段 7）；本段评了就能显示。
6. **律师收入只是订单上的数字**，`lawyer_balance` 在段 2 **不会**因成交而增加——段 5 做结算时才动。
7. **`lawyer.specialties` 与固定分类的映射**依赖段 1 入驻页改动；若段 1 已按自由输入施工，段 2 会留一批筛不到的数据。

---

## 九、回写到哪些文档（本段结束必须完成，缺一条算未收口）

> 按 `docs/BASELINE.md` §4 的「四件事」办：**落款 / 登记指纹 / `docs/README.md` 加索引 / `CHANGELOG.md` 说明**。只做一件等于没做。

| # | 文档 | 改什么 | 什么时候 |
|---|---|---|---|
| 1 | 《数据表规范》附录 A | 加 `order_status_log`（第 50）、`task_queue`（第 51）两行；段分布「段 2 = 10」改 **12**；合计「49 张 / 建 48 张」改 **51 / 建 50**；`refund_record` 字段数 **11 → 12**（加 `audit_remark`）；附录 D 第 3 条不再悬空 | **开工前**（§二 第 15 项） |
| 2 | ~~《技术路线》`operation_log` 补 `detail JSON` 列~~ **已撤回（不动文档）** | 该表在技术路线有双份 DDL：§5.10 L1806（**作废**）/ §5.11.5 L2102（**为准**，已含 `detail JSON` / `target_type` / `target_id` / `result`）。文档不用改，只需施工时用对版本 | — |
| 3 | 《技术路线》`orders` DDL | `status` 列注释改 8 个值 | 开工前 |
| 4 | 《技术路线》`payment_record` DDL | `channel` 注释补 `balance` | 开工前 |
| 5 | 《技术路线》§9.2.1 | 标「段 2 实现前 8 态；`DISPUTED`/`SETTLED` 段 5」 | 开工前 |
| 6 | 《技术路线》§4.8.4.6 / §4.8.4.7 | 旧表名（`legal_order` / `law_article` / `law_article_translation` / `document_template` / `lawyer_income_settlement`）改附录 A 的新名；`lawyer-settlements` 三个接口标明属段 5 且无表 | 开工前 |
| 7 | 《技术路线》4 处 MQ | 改为「数据库任务表 + 定时重试」（总纲 §7.5 已列具体行号） | **段 2 开工前**（本段强依赖） |
| 8 | 《审计与日志规范》§3.2 | 加 `CONVERSATION_VIEW` 一行；注明「虽是 GET 但必须审计」 | 开工前 |
| 9 | 《审计与日志规范》§3.2 | 段 2 的 `ORDER_PRICE_EDIT` / `ORDER_REFUND` / `LAWYER_VIOLATION` 已在列，**核对是否都要实现**（本段只做 `ORDER_REFUND`，另两个视情况） | 开工前 |
| 10 | 《接口规范》 | 登记 2 个新增接口：`POST /app/reviews/{id}/reply`、`GET /admin/conversations/{id}/messages` | 施工中 |
| 11 | 《依赖清单》 | 登记 `spring-boot-starter-websocket`、推送 SDK；两者都必须写理由 | 施工中 |
| 12 | 《未完成需备注》§5.2 | 「聊天记录可见性」由**待定**改为**已定：全量可见 + 强制审计 + 脱敏建议挂账** | 开工前 |
| 13 | 《开发计划总纲》§5.3 L365 | 数据库清单 13 张旧名 → **12 张新名**（10 + 2 新增） | 开工前 |
| 14 | 《开发计划总纲》§11.2 | 段 1、段 2 工作流状态由「待产出」改为「已产出」 | 收口时 |
| 15 | `docs/CHANGELOG.md` | 记录本段全部文档变更 | 收口时 |
| 16 | `docs/BASELINE.md` | 新增本文档指纹、更新资产数与基线版本 | 收口时 |

---

## 十、待拍板（本段开工前或施工中必须有人回答，不许「假设已定」）

| # | 事项 | 本段暂用的处理 | 最晚什么时候定 | 负责人 |
|---|---|---|---|---|
| 1 | **律师列表排序规则** | 暂定「在线优先 + 评分降序 + 订单数降序」，写进 `sys_config` | 段 2 施工中（G3.1 前） | 负责人 / 产品 |
| 2 | **部分退后订单状态** | 暂定「回退款前状态」（不是 `REFUNDED`） | 段 2 施工中（G4.3 前） | 负责人 |
| 3 | **`lawyer.specialties` 是否改固定 5 类多选** | 暂定「改」，段 1 入驻页未施工来得及 | **段 1 施工前** | 负责人 |
| 4 | **推送通道（个推 + 四厂商）凭据** | **2026-11-06 拍板：走 B 方案**——`2-A3` 降级为浏览器口径并**挂账段 8**，不阻塞本段施工 | 段 8 开工前 | 负责人 |
| 5 | **聊天原文脱敏** | 按拍板「全量可见」执行 | 段 7 开工前可重议 | 负责人 |
| 6 | **评价能否删除 / 修改** | 暂定「不能」 | 段 7 前 | 负责人 |
| 7 | **订单取消的边界**（`PAID` 后用户取消是否一律全额退） | 暂定「是」（技术路线 §9.2.1 已定 `PAID` + 用户取消 → `REFUNDING`） | 段 2 施工中 | 负责人 |
| 8 | **拒单是否需要人工审批** | 暂定「不需要，自动全额退 + 记 `order_status_log`」 | 段 2 施工中 | 负责人 |
| 9 | ~~**`2-A5`「不生成订单」的解释**~~ **已定（2026-11-01 负责人确认）**：指「不产生已支付订单」，`PENDING` 订单不算 | — | 已定 | 负责人 |
| 10 | ~~**`2-A8` ①「余额退回」的解释**~~ **已定（2026-11-01 负责人确认）**：指 `PAID` 30 分钟无人接单的自动退款；`PENDING` 超时本就无款可退 | — | 已定 | 负责人 |

---

## 十一、实际完成与偏差（施工后回写，不删原文）

> 施工完成后在这里追加，**不改上面的内容**。每条写：计划 vs 实际、偏差原因、是否影响下一段。

| # | 计划 | 实际 | 偏差原因 | 影响 |
|---|---|---|---|---|
| | | | | |

---

## 附：本文档的生成方式与已知局限

- **生成日期**：2026-11-01。
- **依据的实测**：仓库 HEAD `bb824c4`（分支 `UI设计`）；数据表规范附录 A 49 张表逐行读过；技术路线 10 张表 DDL 行号逐张核对（10/10 准确）；字段数比对 10/10 一致；`conversation` / `orders` / `payment_record` / `refund_record` / `review` / `lawyer` 的 DDL 逐个读过；设计稿 `11-order-detail.html` / `31-pay.html` / `02-lawyer.html` 的关键区块读过。
- **已知局限**：
  1. **段 0、段 1 的施工都没开始**，所以本文档的「前置检查」是**对段 1 出口的预测性要求**，不是实测现状。开工前必须逐条复查。
  2. **接口数量是按技术路线 §4.8.2 清点的**（App 27 + PC 11），若《接口规范》有增减，以那份为准并回改本文档 §五.2。
  3. **推送通道与 SDK 兼容性未验证**（网络受限，未能查证个推 RN SDK 对 Expo 54 的支持情况）——这是本段最大的未知。
  4. **`2-A5` 与 `2-A8` 两条锚点原文存在语义歧义**（见 §四对应条目）——**已于 2026-11-01 由负责人确认解释**，即 `2-A5` 指「不产生已支付订单」、`2-A8` ① 指 `PAID` 超时自动退款。
  5. 本文档**不重复** DDL 全文、接口字段定义、i18n key 命名——那三份看各自规范。

---

> **【文档基线 v1.25】** 本文件属于「法泽 FAZE 文档基线 v1.25」（2026-11-01 纳入）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
