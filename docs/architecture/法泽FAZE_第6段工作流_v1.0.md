# 第 6 段工作流：真实充值渠道 + 掉单补单

> **这份文档解决什么**：第 6 段**怎么施工**，从第一行读到最后一行即可，不需要翻别的文档。
> **不回答**：为什么是这一段（看《开发计划总纲》§5.7）、某功能怎么设计（看《技术路线 v2.3》§九）、表字段怎么定（看《数据表规范》）。
>
> **本段位置**：默认排在段 5 之后、段 7 之前。**但本段只依赖段 1 的账务内核**（不依赖段 2~5），所以是可滑动段——总纲 §5.7 允许它滑到段 2 之后任意位置（含最后）。
>
> **写作依据**：仓库 HEAD `b874997`（分支 `UI设计`，基线 v1.31），2026-11-02 实测。技术路线 / 数据表规范 / 接口规范 / 错误码 / 审计 / i18n 六份规范的相关章节逐节读过；设计稿 `design/ui/36-recharge.html`（420 行）逐行读过。
>
> **本段规模**：APP **1 页**（36 充值页）/ PC **新增 0 页，改造 1 页**（A-09 加「渠道对账」Tab，主线 28 页口径不变）/ 建表 **1 张**（`recharge_record` 11 → 13 字段）/ 接口 **8 个**（App 3 + 回调 2 + 管理端 3）/ 锚点 **7 条** / 任务 **5 组 19 个**。
>
> **状态**：待施工。§零之二 的 18 项裁决由项目负责人 2026-11-02 拍板（12 项首轮 + 6 项追加），本文档正文已按裁决写死。

---

## ⚠️ 开工前必读：本段与上游文档有 9 处冲突，全部在 §零之二 裁决完毕

段 6 是三份上游文档**口径第一次正面碰撞在「钱从外部进来」这条线上**：总纲 §5.7 写于 2026-10-30、技术路线 §九 写于 V2.2/V2.3、`recharge_record` 的 DDL 写于更早。三者不一致的地方，**以本文档 §零之二 的裁决为准**；本文档已按裁决后的口径写死，上游回写动作列在 §九。

**最严重的三处必须先看**：

1. **总纲 §5.7 的数据库清单三张表全错。** 写的是 `payment_channel` / `recharge_order` / `reconciliation_diff`：
   - `payment_channel` —— **全仓零命中**，只在总纲这一行出现过，不是一张真实存在的表；
   - `recharge_order` —— 实际叫 **`recharge_record`**（技术路线 L1419 有 DDL，数据表规范附录 A 第 17 行归段 6）；
   - `reconciliation_diff` —— **段 5 已建**（数据表规范附录 A 第 45 行，段 5 工作流 §三 A1 的 `V507`），段 6 只写不建。
2. **「差异在 PC A-09 可见」这句是总纲的本意，保留——但段 6 只加一个 Tab，不改 A-09 已有内容。** 总纲 §5.7 L591 原文要求渠道对账差异在 PC A-09 展示；阻力来自两处：段 5 工作流 L82 曾写「**不许**改 A-09」（那是段 5 自己的施工约束——段 5 只建表不做页），而段 1 交付的 A-09 只有「恒等式巡检」一块、没有渠道差异的位置。**裁决（2026-11-02 追加）：段 6 在 A-09 上加「渠道对账」Tab，Tab1「恒等式巡检」原样不动，不新增 PC 页（仍 28 页）。** 段 5 那条禁令加例外，见 §九 第 7 项。
3. **`balance_flow.biz_id` 是 `BIGINT`，但段 1 说手工充值往里存「业务单号」。** 单号是 `VARCHAR(32)`（如 `RC20261101000045`），存不进 `BIGINT`；而 `uk_flow_biz` 唯一键含 `biz_id`，`biz_id` 为 `NULL` 时 **MySQL 唯一索引不生效**（多行 NULL 不算冲突），幂等直接失效。段 6 的充值入账必须用 `biz_id = recharge_record.id`，段 1 的手工充值缺陷记进 §九（不在本段修）。

### 这三处必须回写，不是本文档自己改完就算

| # | 文档 | 不改的后果 |
|---|---|---|
| 1 | 《开发计划总纲》§5.7 + §11.2 | 施工方照 `payment_channel` / `recharge_order` 建表，会去建一张不存在的表、重名一张已有的表 |
| 2 | 《技术路线》L1460 的注 + §9.1 / §9.4 / §9.5 | 注里写「`payment_record` 仅记录充值的渠道支付」，但段 2 已把 `payment_record` 用作订单余额支付记录；两段各按一句施工就会写重 |
| 3 | 《接口规范》§4.8.4 | 8 个接口没有契约，回调接口的免鉴权规则也不存在 |

---

## 零、开工前必须知道的事（2026-11-02 实测）

### 零.1 本段的真实起点：段 0 ~ 段 5 的施工一次都没跑

| 实测项 | 结果 |
|---|---|
| `services/` 目录 | **不存在**（服务端一行业务代码都没有） |
| `apps/mobile-app/app/` | 只有 `index.tsx` / `_layout.tsx` / `+not-found.tsx` 3 个文件 |
| `git log` | 全部 `docs:` / `chore:` / `feat(admin)`，**没有一条业务代码提交** |
| `docs/acceptance/` | 只有 `seg1/A3-mp-probe.md` 一份 |
| 设计稿 | APP 71 屏 + PC 28 屏，**齐全**（含本段的 `36-recharge.html`） |
| 文档 | 总纲 + 技术路线 v2.3 + 6 份规范 + 段 0~5 工作流，**齐全** |

**结论**：本文档与段 1~5 同性质，是**纯计划文档**。§二 的开工前置检查里，凡涉及「上游已交付」的条目都是**预测性要求**，施工前必须逐条复核。

### 零.2 上游承诺了什么（段 6 可以直接依赖，不许重造）

| 上游 | 承诺 | 段 6 怎么用 |
|---|---|---|
| 段 1 | `balance_flow` 账务模型 + `deduct()` 幂等扣费引擎 + `user_balance` 表 | 充值入账**只写一条** `balance_flow`（`biz_type='recharge'`），再由流水汇总更新 `user_balance` |
| 段 1 | 余额查询「以流水为真相」 | 充值成功后 App 余额变化，必须是 `SUM(delta_cents)` 变了，不是缓存字段被改 |
| 段 1 | 手工充值 `POST /admin/wallets/{userId}/recharge`（`biz_type='recharge'`） | **继续可用**，与真实充值并存；两者写同一个 `biz_type`，靠 `biz_id` 区分来源 |
| 段 1 | `sys_config` 配置中心 + Jasypt 配置加密 | 渠道参数、充值上下限、档位全放这里（§零之二 第 2 项） |
| 段 1 | 审计 AOP + `operation_log` | 管理端补单、对账处理标 `@OperationLog` 即可 |
| 段 1 | 两条账务恒等式（§零.3） | 本段 `6-A5` 直接跑这两条 |
| 段 2 | `task_queue` 表（12 字段）+ 定时任务范式（投递是状态不是删除、领取必须 CAS、必须有租约回收） | 掉单补单、超时关单、每日对账三个任务全搭在它上面，**不新建任务表** |
| 段 2 | 单号生成器 `BizNoGenerator` | 充值单前缀 `RC`，格式 `<前缀2位><yyyyMMdd8位><序号6位>` |
| 段 2 | `payment_record`（**已定为「订单支付记录」**，`channel='balance'`，只写支付/退款两行） | **段 6 不写这张表**（§零之二 第 5 项） |
| 段 5 | `reconciliation_diff` 表（14 字段，只写不展示） | 段 6 的渠道对账差异写这张表，**不建表、不做页面** |
| 段 5 | 「`biz_type` 只新增 `recharge`，不改 income / freeze / unfreeze / withdraw 四个语义」 | 段 6 严格照办，一个老语义都不动 |

### 零.3 已经定了的（本段不再讨论）

- RuoYi 3.9.2 官方发行版（SB 4.0.3）；MP 用 `mybatis-plus-spring-boot4-starter:3.5.18.1` + `mybatis-plus-jsqlparser:3.5.18.1`（两个都要）
- 金额一律整数分（`BIGINT`），接口传分，前端负责展示转换；**禁止浮点运算**
- 状态迁移**由服务端校验**，客户端只发意图、不发状态；非法迁移抛 `4050xx`，不静默忽略
- 管理端写接口默认标 `@OperationLog`；审计进库、日志进文件；**回调原始报文属于 L4 禁入日志字段**（`raw_payload` 只落库，不打日志）
- 多语言四语言；**段 6 只需 `zh-Hans`**（与段 4 一致：段 4 已把范围改为「只做中文」，段 8 收口时补 `ug-Arab-CN`），缺译登记进 `.i18n-pending.json`
- 迁移版本号：段 6 = **`V600` ~ `V699`**
- 错误码：交易域 `05`（`405xxx`）。**已用到 `405027`**（段 5 占了 `405023`~`405027`），段 6 从 **`405013`** 起编
- 审计动作码：已有 `WALLET_MANUAL_RECHARGE`（手工充值）/ `WALLET_ADJUST`（调账）。段 6 新增 4 个（§九 第 5 项）
- 表名前缀规则、四类表公共字段、`COLLATE`、`deleted_at` 全按《数据表规范》
- 接口 URL 前缀、9 个请求头、`R<T>`、三种分页场景、Long 型 ID 序列化为字符串，全按《接口规范》
- **本段要跑的两条账务恒等式**（段 1 §三 D 组定、段 5 §三 E0 复用，原文照抄）：
  1. `available + frozen == 该账户最后一条 balance_flow 的 balance_after`
  2. `available + frozen == SUM(delta_cents)`（`delta = 0` 的行是冻结/解冻的转移记录，只作状态证据、不影响求和）
  其中用户侧总余额 = `user_balance.balance_cents + user_balance.frozen_cents`。

### 零.4 本段六条「不许」（写在这里防走样）

1. **不许**建 `payment_channel` 表。渠道参数全在 `sys_config`，走 Jasypt 加密（§零之二 第 2 项）。
2. **不许**建 `reconciliation_diff` 表，也**不许**改 A-09 对账报表页。差异**只写表、只出接口**（§零之二 第 3 项）。
3. **不许**往 `payment_record` 写任何行。充值的渠道记录只进 `recharge_record`（§零之二 第 5 项）。
4. **不许**改 `balance_flow` 的写入规则与老 `biz_type` 语义。本段只新增 `recharge` 一种用法，且 `biz_id` 必须非空。
5. **不许**把商户号、密钥、证书私钥写进仓库、写进日志、写进前端。配置只从 `sys_config`（Jasypt 加密）读；证书只放文件路径，文件不进仓库。
6. **不许**在回调里「先改状态再入账」。入账（写 `balance_flow` + 更新 `user_balance`）与状态改 `success` **必须同一事务**；先改状态后入账，一旦入账失败就会出现「渠道收到钱、平台显示成功、余额没加」。

---

## 零之二、负责人 2026-11-02 拍板的 18 项（逐项落到哪一节）

> 这 18 项是本次冲突的**唯一裁决依据**（第 1~12 项是首轮，第 13~18 项是同日追加）。本文档正文已按裁决写死；施工时若发现正文与本节矛盾，**以本节为准并回改正文**。

| # | 事项 | 裁决 | 落到哪 |
|---|---|---|---|
| 1 | 总纲 §5.7 的三张表 | **按实际情况改**：不建 `payment_channel`；`recharge_record` 由本段建（**11 字段**基线含 id + 2 个新字段 = **13 字段**）；`reconciliation_diff` 段 5 已建，本段**只写不建** | §三 A 组 A1、§五 5.1、§九 第 1 项 |
| 2 | 渠道参数放哪 | **不建表，全放 `sys_config`** + Jasypt 加密。理由：渠道只有微信/支付宝两个、配置项十几个、后台不需要增删渠道；建表要改 53→54 张的四处口径，收益为零 | §三 A 组 A3、§五 5.4 |
| 3 | PC A-09 归属 | **段 6 在 A-09 上加「渠道对账」Tab，不新增 PC 页（仍 28 页）**。依据是总纲 §5.7 原文「差异写 `reconciliation_diff` 并在 PC A-09 可见」。**Tab1「恒等式巡检」原样不动**（段 1 交付的展示不改）。段 5 的「不许改 A-09」加例外 | §三 B 组 B4、§四 `6-A4`、§五 5.3、§九 第 7 项 |
| 4 | 充值单状态机 | 4 态 6 条迁移：`pending → success / failed / closed`，其余三条为**禁止**；三个终态不可再迁。由本文档定（技术路线 §9.2 那张状态机是**咨询订单**的，与充值单无关） | §三 A 组 A2 |
| 5 | `payment_record` 与 `recharge_record` 职责 | **`payment_record` 归段 2（订单余额支付），段 6 一行都不写**。充值的渠道信息全部落在 `recharge_record`（它自带 `raw_payload` / `channel_txn_id` / `paid_at`，功能完整）。技术路线 L1460 那句「`payment_record` 仅记录充值的渠道支付」**作废** | §三 C 组 C3、§九 第 2 项 |
| 6 | 充值入账的 `biz_id` | `balance_flow.biz_id = recharge_record.id`（BIGINT，天然正确）；**`biz_id` 为 NULL 会破坏 `uk_flow_biz` 幂等**（MySQL 唯一索引不约束多行 NULL）。手工充值的 `biz_id` 缺陷记进 §九，**本段不修段 1** | §三 C 组 C3、§九 第 3 项 |
| 7 | 商户号未申请 | **不阻塞本段。** 用 `MockPaymentGateway` 先跑通 `6-A2 / 6-A3 / 6-A5 / 6-A7`；`6-A1 / 6-A4 / 6-A6` 三条**挂账**，挂账条件：商户号下发 + 真实 1 元支付。挂账项写进 §十 与 §一.1 | §三 A 组 A3、§四 各锚点顶部、§十 第 1 项 |
| 8 | 8 个接口的契约 | **本文档自己写全**（方法 / 路径 / 请求体 / 响应体 / 错误码 / 幂等），并标「待回写《接口规范》§4.8.4」 | §五 5.2、§九 第 4 项 |
| 9 | 回调接口的鉴权 | 新增路径前缀 **`/pub/pay/callback/**`**，免 JWT、免登录、**只靠验签**；必须限制来源 IP + 报文大小 + 频率。这条机制《接口规范》里现在没有，随第 4 项一并补 | §三 C 组 C1、§九 第 4 项 |
| 10 | 错误码 | 交易域从 **`405013`** 起编，本文档新增 **8 个**（`405013`~`405020`，§五 5.5） | §五 5.5、§九 第 5 项 |
| 11 | 审计动作码 | 新增 4 个：`RECHARGE_SYNC`（人工补单）/ `RECHARGE_CLOSE`（超时关单）/ `RECONCILE_RUN`（手动对账）/ `RECONCILE_HANDLE`（差异处理） | §五 5.5、§九 第 5 项 |
| 12 | 充值页 | 设计稿**齐全**（`design/ui/36-recharge.html`，420 行）。本段**不画稿**，只补它没画的 10 项规格（四态 / 校验态 / 渠道禁用态 / 支付中态 / 失败给单号 / RTL 等） | §三 B 组 B2、§二 第 9 项展开 |

**追加裁决（2026-11-02，第 13~18 项）**：

| # | 事项 | 裁决 | 落到哪 |
|---|---|---|---|
| 13 | 渠道手续费由谁承担 | **平台承担**。用户充 1 元余额就是 1.00 元；手续费不进用户余额、不写用户流水。**对账口径按「用户支付金额」比** | §三 A3、§六 R10、§十 第 3 项 |
| 14 | 单笔金额区间 | **0.1 元 ~ 5000 元**（`recharge.min_cents=10` / `recharge.max_amount_cents=500000`）。**设计稿「单笔最低 10 元」作废，稿要改** | §三 A3、§三 B2-3、§九 第 10 项 |
| 15 | 单日累计上限 | **5000 元**（`recharge.max_daily_cents=500000`） | §三 A3、§三 B3 |
| 16 | 未实名用户充值额度 | **0 = 完全不可充值**。是**拦截**不是限额，新错误码 `405020`，App 引导实名 | §三 B3 第 4 条、§四 `6-A7`、§五 5.5 |
| 17 | 充值单超时时长 | **30 分钟**（`recharge.expire_minutes=30`，已确认为上线值不是测试值） | §三 A3、§三 D2 |
| 18 | 回调域名与证书 | **先保留配置接口**：回调地址 = `pay.callback_base_url` + 固定路径，域名未定可留空，Mock 不依赖它；真实上线只改这一个值 | §三 A3、§三 C1 第 2 条、§十 第 2 项 |

---

## 一、本段目标与出口

**定位**：把段 1 的账务内核接上真实资金通道。用户能在 App 里真的往账户里充钱，钱到账后能消费；运营方能对得上账、能补回掉单。

做完这一段，平台可以真实经营的第三步成立：**用户自己能把钱充进来**（段 1 只有管理员代充）。这是余额制真正成立的前提——在此之前，用户想花钱必须先找客服转账。

### 一.1 出口（进段 7 的条件）

1. **`6-A2` / `6-A3` / `6-A5` / `6-A7` 四条全过**，每条都有可复现的证据文件（`docs/acceptance/seg6/`），在 **Mock 通道**下验。
2. **`6-A1` / `6-A4` / `6-A6` 三条挂账**，挂账记录写进 `docs/acceptance/seg6/挂账.md`，含「挂账条件 / 最晚解决时间 / 影响范围」。挂账不等于通过——收口时必须在 CHANGELOG 里写明「本段有 3 条锚点未验」。
3. **充值单状态机**的全部合法/非法迁移有单测覆盖；非法迁移被**服务端**拒（不是被前端拦住）。
4. **回调幂等可证**：同一回调报文重放 3 次，`recharge_record` 只有 1 条 `success`、`balance_flow` 只有 1 条 `recharge` 流水、余额只加一次。
5. **账务恒等式成立**（§零.3 那两条）：任取一位充过值的用户，`balance_cents + frozen_cents` == 该用户最后一条 `balance_flow.balance_after` == `SUM(delta_cents)`；差异 = 0。
6. **对账可在无人工干预下每日跑**：`task_queue` 里有对应任务类型，跑了有日志、有结果落库（`reconciliation_diff`），失败会重试并进死信。
7. **密钥零泄漏**：全仓 grep 不到商户号、API v3 密钥、证书私钥；日志里 grep 不到 `raw_payload` 内容。
8. `npm run lint` 与 `mvn -q verify` 全绿；ArchUnit 断言（管理端写接口必须有 `@OperationLog`）通过。
9. 上游回写（§九 共 **10 行**）全部完成：**第 1~9 行已于 2026-11-02 落地（基线 v1.32，两分支已推送）**；第 10 行是设计稿门槛值，**待负责人决定是否改稿**（不阻塞施工，以本文档为准）。

### 一.2 反向定义（什么不算出口）

- **「用 Mock 通道充值成功」不算 `6-A1` 过**：`6-A1` 的原文是「**真实支付 1 元**」，Mock 不是真实支付。用过即挂账。
- **「页面画出来了」不算出口**：1 页的四态（正常/加载/空/错误）都要处理，接口要真连通。
- **「回调能收到」不算出口**：要能用同一报文重放 3 次仍然只入账一次，才算 `6-A2` 过。
- **「余额加上了」不算出口**：必须同时满足「`balance_flow` 一条 `recharge` + `biz_id` 指向该充值单 + 余额等于流水汇总」三件事。
- **「对账脚本跑通了」不算出口**：必须真的**下载到账单文件**（Mock 通道用本地造的对账单）并**比对出差异**，差异能落进 `reconciliation_diff`。
- **「商户号下来了所以能跑」不算出口**：三条挂账锚点要按各自的通过标准逐条验，不是「能支付了就算过」。

---

## 二、开工前置检查（逐条勾，不通过不开工）

> 打勾前必须给出证据（命令输出 / 文件路径 / 截图）。凭印象打勾 = 没做。
> 第 1~5 项是**上游交付的复核**；第 6~12 项是**本段自己要先补的资产**。

| # | 检查项 | 通过标准 | 不通过怎么办 |
|---|---|---|---|
| 1 | **段 1 已收口** | `balance_flow` / `user_balance` / `sys_config` / 审计 AOP 四样可用；`docs/acceptance/seg1/` 有 10 条锚点证据 | 停。本段的钱只能落进段 1 的账务模型，模型没验收就接渠道等于在沙上盖楼 |
| 2 | **`balance_flow.biz_id` 的类型已确认** | 实测 `SHOW COLUMNS FROM balance_flow LIKE 'biz_id'` 返回值 `bigint`；且 `uk_flow_biz` 存在 | 若被改成 `VARCHAR`，本文档 §三 C3 的入账逻辑要跟着改；若 `uk_flow_biz` 不存在，**停**——幂等没有兜底 |
| 3 | **段 1 的手工充值能跑** | `POST /admin/wallets/{userId}/recharge` 能给用户加 100 元，App 余额变成 `100.00`、流水 1 条 | 停。手工充值是本段「真实渠道不可用」时的对照基线，它不通就没法证明「真实充值」多做了什么 |
| 4 | **段 2 的 `task_queue` 可用** | 表存在、字段 12 个、有一个能跑的 handler（如 `order_close`）、领取用 CAS、有租约回收 | 停。本段三个定时任务全搭在它上面，没有任务表就只能用 `@Scheduled` 裸扫，多实例会重复执行 |
| 5 | **段 5 的 `reconciliation_diff` 已建** | `SHOW CREATE TABLE reconciliation_diff` 有 14 字段；段 5 工作流 §三 A1 的 `V507` 已执行 | 段 5 未施工时：本段**不代建**，把「对账差异落库」标为待段 5 交付，`6-A4` 顺延（记进 §十） |
| 6 | **迁移版本号已占位** | `V600`~`V602` 空号无人占用；`flyway_schema_history` 里段 5 用到的最大号 < `V600` | 按《数据表规范》§8.1 顺延到 `V610` 起，同步改本文档 |
| 7 | **`sys_config` 的渠道键已定** | 至少 21 个键：`pay.mock.enabled` / `pay.wechat.enabled` / `pay.alipay.enabled` / 两个渠道的 `appid` / `mch_id` / `secret`（加密）/ `cert_path` / `cert_serial`，以及 `recharge.min_cents`(1000) / `recharge.max_daily_cents` / `recharge.amount_options` / `recharge.expire_minutes`(30) / `recharge.max_daily_count`(10) / `recharge.unverified_daily_cents`(0=不可充值) / `recharge.max_amount_cents`(500000)，外加 `pay.callback_base_url`（可留空）/ `pay.callback_ips`（空=不校验） | 键名冲突查《数据表规范》§6 命名规则；加密字段必须 `ENC(...)` 包裹 |
| 8 | **错误码已补登** | 交易域 `405013`~`405019` 七个（§五 5.5 已列） | —— 本段自补，见 §九 第 5 项 |
| 9 | **审计动作码已补登** | §零之二 第 11 项的 4 个 | —— 本段自补，见 §九 第 5 项 |
| 10 | **i18n 的 `trade` namespace 已建** | `zh-Hans/trade.json` 存在；本段新增文案 key 见 §三 B2 清单 | 查《i18n 规范》§4.3 映射表 |
| 11 | **充值页的设计稿已确认版本** | `design/ui/36-recharge.html` 420 行、`<title>充值</title>`、含「立即充值」按钮 | 找不到就说明设计稿被人动过，**停下来问清楚**，不要自己画一版 |
| 12 | **回调域名的方案已定** | 真实渠道需要一个**可公网访问的 HTTPS 域名**（微信/支付宝都要配「支付回调地址」）。本段开工前必须确认：有域名 / 用内网穿透 / 用 Mock 通道先跳过 | 无域名且不打算穿透 → **只用 Mock 通道验四条，其余三条挂账**（§零之二 第 7 项已批准） |

### 第 11 项展开：设计稿没画、由本段自定规格的清单

`36-recharge.html` **画了正常态**，但下列分支稿里没有，规格由本文档写死（对应 §零之二 第 12 项）。

| 规格项 | 稿里有什么 | 稿里**没有**什么 | 本段自定在哪 |
|---|---|---|---|
| 金额档位 | 三列网格 6 档（100/200/500/1000/2000/其他）+ 自定义输入，`data-recharge` 属性齐全 | 档位是否可配、档位从哪读 | §三 B2-2（读 `recharge.amount_options`） |
| 金额校验 | 提示文案「单笔最低 10 元，单日累计上限以实名校验结果为准」 | 校验失败态（< 下限 / > 上限 / 非数字 / 两位以上小数）怎么显示、CTA 是否禁用。**且稿的门槛值已作废**：真实下限 0.1 元、上限 5000 元（§零之二 第 14 项） | §三 B2-3 |
| 页面四态 | 只有正常态 | 加载态（余额与配置未回来）、错误态（下单失败）、空态（余额为 0 首次充值） | §三 B2-4 |
| 渠道禁用 | 两个渠道都是可选态 | 某渠道被 `sys_config` 关掉时怎么表现（置灰还是隐藏） | §三 B2-5 |
| 提交后链路 | 只有「立即充值 ¥200.00」按钮 | 点下去之后：下单 → 拉起渠道 → 回前台 → 查单 → 跳 `32-pay-result` | §三 B2-6 |
| 支付中态 | 只有须知里一句「不要重复支付」 | 用户从微信切回来时页面显示什么 | §三 B2-7 |
| 失败给单号 | 无 | 失败时显示 `RC...` 单号方便客服查 | §三 B2-8 |
| RTL | 无 | 维吾尔/哈萨克文镜像（本段只做中文，但布局必须用 `start/end`） | §三 B2-9 |

---

## 三、任务分组（5 组 / 19 个任务）

> **组内连续推进**，组间设检查点。每组末尾的「组检查点」不过，不进下一组。
> 每个任务五段式：**做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办**。
> 任务编号 `A1` ~ `E3`，与 §五 产出清单、§四 锚点可以互相追溯。

### A 组：地基（4 个任务）

> 这一组不产出页面，但决定了后面 4 组的表结构、状态机、渠道抽象与配置口径。
> **A 组不通过，不要开始 B~E。**
>
> **组前一个必须说清的事实：单号生成器 `BizNoGenerator` 是段 5 的 A3 任务建的，不是段 2。**
> 段 6 默认排在段 5 之后，可以直接复用（前缀 `RC`）。但段 6 是**可滑动段**（总纲 §5.7）——
> 若它被滑到段 5 之前施工，`BizNoGenerator` 还不存在。此时的处理：
> **本段按段 5 A3 的签名自己实现最小版本**（只做 `RC` 一个前缀、格式 `<前缀2位><yyyyMMdd8位><序号6位>`、Redis `INCR` + 唯一索引兜底 + 冲突重试 3 次），
> 并在 §十一 记「与段 5 A3 重复实现，段 5 施工时合并」。**不许**为了等段 5 把充值单号改成 UUID 或时间戳。

#### A1 · 建 `recharge_record` 表（`V600`，13 字段）

- **做什么**：用 Flyway 建段 6 的 1 张表。**只建这一张**（`payment_channel` 不建、`reconciliation_diff` 段 5 已建）。
- **怎么做**：
  1. 基线 DDL 在《技术路线》L1419（`-- 12d. 充值记录`），**照抄不重新设计**，11 个字段（含 id）：`id` / `user_id` / `order_no` / `channel` / `channel_txn_id` / `amount_cents` / `status` / `paid_at` / `raw_payload` / `created_at` / `updated_at`。
  2. **本段补 2 个字段**（`V600_1__fix_recharge_record.sql` 修正迁移，或直接在建表时带上）：
     | 新字段 | 类型 | 为什么必须加 |
     |---|---|---|
     | `expire_at` | `DATETIME NOT NULL` | 超时关单任务按它扫，而不是按 `created_at + 固定 30 分钟` 现算——超时时长要可配（`recharge.expire_minutes`），写死在 SQL 里改一次就得发一次版 |
     | `channel_prepay_id` | `VARCHAR(64)` | 渠道下单后返回的 prepay_id（微信）/ trade_no（支付宝）。**回调要拿它对上**，光有 `order_no` 不够——渠道回调里两个都给，但对不上时要用 prepay_id 反查 |
  3. **`channel` 列注释补 `mock`**：原文写 `wechat / alipay`，段 6 的 Mock 通道要落库，注释必须与实际取值一致（`wechat / alipay / mock`）。
  4. **三个索引必须存在**（原文已有，建表时不许删）：
     - `uk_recharge_order_no (order_no)` —— 对外单号唯一
     - `uk_recharge_channel_txn (channel, channel_txn_id)` —— **回调幂等的数据库兜底**，这是 `6-A2` 的硬依赖
     - `idx_recharge_user (user_id, created_at)` —— App 充值记录列表
  5. 再补一个查询索引：`KEY idx_recharge_status_expire (status, expire_at)` —— 超时关单任务扫 `status='pending' AND expire_at<=now()`。
  6. 过一遍《数据表规范》§十 的 12 问检查清单：表名 `recharge_*` 合规、字段名蛇形、索引名 `uk_`/`idx_` 前缀、C 类状态机表（有 `status`、`created_at` + `updated_at`）、`COLLATE utf8mb4_0900_ai_ci` 显式写、表与字段注释齐全、**C 类表要有 `deleted_at` 吗**——查 §4.1：充值单**不软删**（财务凭证，只追加状态机），所以**不加 `deleted_at`**，在表注释里写明理由。
- **产出**：`V600__recharge_record.sql`（+ 必要时 `V600_1__fix_recharge_record.sql`）；`docs/acceptance/seg6/A1-schema.txt`（`SHOW CREATE TABLE` 输出）。
- **算做完**：`flyway migrate` 在空库上从 `V0` 跑到 `V601` 无错；`SHOW CREATE TABLE recharge_record` 有 **13 个字段**、3 个唯一/普通索引 + 1 个状态索引；`channel` 列注释含 `mock`；附录 A 第 17 行的字段数已回写为 13（§九 第 1 项）。
- **卡住怎么办**：若段 5 施工时已经建过 `recharge_record`（不该发生，附录 A 归段 6），**不要建第二张**——改用修正迁移补字段，并在 §十一 记一笔「段归属已被上游施工打乱」。

#### A2 · 充值单状态机（4 态 / 6 条迁移）

- **做什么**：定义 `recharge_record.status` 状态机，服务端校验全部迁移。**这张状态机技术路线里没有**（§9.2 那张是咨询订单的 `legal_order` 状态机，与充值单无关），由本文档定。
- **怎么做**：
  1. 枚举（`RechargeStatus`）：
     | 值 | 中文 | 何时进入 |
     |---|---|---|
     | `pending` | 待支付 | 用户下单成功（`POST /app/wallet/recharges`）后的初始态 |
     | `success` | 充值成功 | 回调验签通过并入账 / 主动查询确认渠道已收款 |
     | `failed` | 渠道失败 | 渠道明确返回失败（用户取消、支付失败、渠道拒单） |
     | `closed` | 已关闭 | 超时未支付，由关单任务置位 |
  2. 迁移表（**6 条**，服务端唯一依据）：
     | from → to | 触发方 | 说明 |
     |---|---|---|
     | `pending → success` | 支付回调 / 主动查询 | **同时入账**（同一事务） |
     | `pending → failed` | 支付回调 / 主动查询 / 渠道下单失败 | 只置状态，**不入账** |
     | `pending → closed` | 定时任务（`expire_at <= now()`） | 只置状态，**不入账** |
     | `success → *` | **不允许**（终态） | — |
     | `failed → *` | **不允许**（终态） | — |
     | `closed → *` | **不允许**（终态） | — |
  3. **三个终态一律不可再迁**：晚到的回调打到 `closed` 单上（用户超时后才付款成功）**不许原地改 `success`**——这是充值场景最危险的边界：钱真的到了渠道，但本地已关单。处理方式见 A2 第 4 条。
  4. **晚到回调的处置（必须实现，不是可选）**：
     - 回调到达时单已 `closed` 或 `failed` → **仍然入账**，但走**新开一张补偿单**的方式：`INSERT` 一条新的 `recharge_record`，`channel_txn_id` 用回调里的渠道交易号，状态直接 `success`，`raw_payload` 记录「原单 XXX 已关闭，补入账」；同时写 `balance_flow`（`biz_id` = 新单 id）。
     - **为什么不会撞 `uk_recharge_channel_txn`**：被关闭/失败的原单是 `pending` 时建的，`channel_txn_id` **一直为空**（NULL）——所以新单占用该渠道交易号不冲突。**原单关单时不许回填 `channel_txn_id`**，回填了就会把补偿单挤掉。
     - 不许直接 `UPDATE ... SET status='success'` 绕过状态机——那样就出现了「迁移表之外的合法路径」，状态机形同虚设。
     - 若 `uk_recharge_channel_txn` 说明同一渠道交易号已入过账，则**幂等返回成功、不重复入账**。
  5. 迁移实现：`RechargeStateMachine.transit(id, from, to, operatorId, operatorRole)`，内部用 `UPDATE recharge_record SET status=? WHERE id=? AND status=?`（**CAS**，影响行数 0 即拒绝，抛 `405015`）。**禁止**先查再改。
- **产出**：`modules/trade/enums/RechargeStatus.java` + `RechargeStateMachine.java` + `RechargeStateMachineTest.java`（6 条迁移 + 6 条非法迁移）。
- **算做完**：单测覆盖 4 态之间**全部 12 种组合**（4×3），合法 3 条通过、非法 9 条被拒；并发两个回调打到同一张 `pending` 单，只有一个成功迁移、另一个被 CAS 拒绝且**不报错给渠道**（返回成功让渠道停止重试，但不再入账）；`closed` 单收到晚到回调时能正确走「补偿新单」路径。
- **卡住怎么办**：若发现渠道回调里无法区分「用户取消」与「支付失败」，**两者都归 `failed`**，不要为此新增第 5 个状态。失败原因放 `raw_payload`，不建 `fail_reason` 字段。

#### A3 · 渠道配置 + 网关抽象（3 个实现 + 1 个工厂）

- **做什么**：定义支付网关接口与三个实现（微信 / 支付宝 / Mock），渠道参数从 `sys_config` 读、走 Jasypt 加密。
- **怎么做**：
  1. 接口照《技术路线》§9.1 **原文**，不自己改签名：
     ```java
     public interface PaymentGateway {
         PrepayResult prepay(RechargeOrder order, PayChannel channel);   // 原签名是 Order，段 6 传充值单
         CallbackResult parseCallback(String rawBody, Map<String, String> headers);
         RefundResult refund(RefundRequest request);
         QueryResult query(String orderNo);
     }
     ```
     **`refund()` 本段只实现「重复支付自动退回」一个用途**，不做用户主动退款（退款在段 2 的余额体系里，不经过渠道）。
  2. 三个实现：
     | 实现类 | 何时启用 | 本段是否必须能跑 |
     |---|---|---|
     | `MockPaymentGateway` | `sys_config.pay.mock.enabled = true` | ✅ **必须**——本段 4 条锚点全在它上面验 |
     | `WechatPaymentGateway` | `pay.wechat.enabled = true` 且商户号下发 | ⚠️ 代码写完、单测用桩，**联通要等商户号** |
     | `AlipayPaymentGateway` | `pay.alipay.enabled = true` 且商户号下发 | ⚠️ 同上 |
  3. **工厂按配置选实现**：`PaymentGatewayFactory.get(channel)`，若该渠道未启用则抛 `405013`（渠道未启用），不许静默回退到 Mock——**开发环境误开 Mock 会让线上出现假到账**。
  4. **`MockPaymentGateway` 的行为必须可配**，否则 4 条锚点验不了：
     | 配置项 | 作用 | 验哪条锚点 |
     |---|---|---|
     | `mock.delay_ms` | 回调延迟（模拟真实到账时间） | `6-A2`（重放窗口） |
     | `mock.result` | `success` / `fail` / `callback_lost`（**下单成功但回调不发**） | `6-A3` 掉单、`6-A7` 失败可读 |
     | `mock.amount_offset_cents` | 回调金额与本地不一致（模拟对账差异） | `6-A4` |
     这三个是**测试开关，只在 `pay.mock.enabled=true` 时生效**，生产不允许出现。
  5. **`sys_config` 键清单**（21 个，加密字段用 `ENC(...)`）：
     | 键 | 默认值 | 加密 | 说明 |
     |---|---|---|---|
     | `pay.mock.enabled` | `true`（开发）/ `false`（生产） | — | Mock 通道总开关 |
     | `pay.wechat.enabled` | `false` | — | 微信通道开关 |
     | `pay.wechat.appid` | — | — | 应用 ID |
     | `pay.wechat.mch_id` | — | — | 商户号 |
     | `pay.wechat.api_v3_key` | — | ✅ | APIv3 密钥（验签用） |
     | `pay.wechat.cert_path` | — | — | 商户证书路径（**文件不进仓库**） |
     | `pay.wechat.cert_serial` | — | — | 证书序列号 |
     | `pay.alipay.enabled` | `false` | — | 支付宝开关 |
     | `pay.alipay.appid` | — | — | 支付宝应用 ID |
     | `pay.alipay.mch_id` | — | — | 支付宝商户号 |
     | `pay.alipay.private_key` | — | ✅ | 应用私钥（签名用） |
     | `pay.alipay.public_key` | — | — | 支付宝公钥（验签用） |
     | `pay.callback_base_url` | —（可留空） | — | **回调域名前缀**，拼成 `{base}/pub/pay/callback/wechat`。域名未定可留空，Mock 通道不依赖它；真实渠道上线时只改这一个值（§零之二 第 18 项） |
     | `pay.callback_ips` | —（空=不校验） | — | 回调来源 IP 白名单，逗号分隔；留空则只靠验签（C1 第 2 条） |
     | `recharge.min_cents` | `10` | — | 单笔最低 **0.1 元**（负责人 2026-11-02 定）。**设计稿「单笔 10 元起」作废**，稿要改，见 §九 第 10 项 |
     | `recharge.max_amount_cents` | `500000` | — | 单笔最高 **5000 元**（负责人 2026-11-02 定） |
     | `recharge.max_daily_cents` | `500000` | — | 单日累计上限 **5000 元**（负责人 2026-11-02 定） |
     | `recharge.amount_options` | `[10000,20000,50000,100000,200000]` | — | 档位（JSON 数组，**单位分**） |
     | `recharge.expire_minutes` | `30` | — | 充值单超时分钟数（负责人已确认 **30 分钟**） |
     | `recharge.max_daily_count` | `10` | — | 单日充值笔数上限（B3 第 2 条） |
     | `recharge.unverified_daily_cents` | `0` | — | 未实名用户额度 **0 = 完全不可充值**（负责人 2026-11-02 定，见 B3 第 4 条） |
  6. **密钥读取红线**：`sys_config` 里加密的值由 Jasypt 解密，**解密后的值只进网关对象，不进任何 DTO / 日志 / 响应**；`raw_payload` 落库前要**脱敏**（渠道报文里可能带 `openid`、卡号尾号），脱敏规则见《审计与日志规范》L4/L5 清单。
- **产出**：`infra/pay/PaymentGateway.java` + 三个实现 + `PaymentGatewayFactory.java` + `PrepayResult` / `CallbackResult` / `QueryResult` / `RefundResult` 四个 DTO；`config/SysConfigKeys.java` 常量；`docs/acceptance/seg6/A3-config.md`（21 个键的取值与加密状态）。
- **算做完**：`pay.mock.enabled=true` 时下单返回 Mock 的 prepay 参数；改成 `false` 且微信/支付宝都 `false` 时下单抛 `405013`；`sys_config` 里 `api_v3_key` 的库值以 `ENC(` 开头；日志中 grep `api_v3_key`、`private_key`、`mch_id` **零命中**。
- **卡住怎么办**：若 Jasypt 加密的配置项在 `sys_config`（DB）而不是 `application.yml`（文件）里读不到——Jasypt 只处理配置文件，**DB 里的密文要自己调 `StringEncryptor` 解密**。不要为此把密钥挪回 `application.yml`（那会进仓库）。

#### A4 · 枚举与常量（3 套）

- **做什么**：把本段所有枚举一次定死，避免各组各写一套。
- **怎么做**：
  1. **支付渠道（3 种）**：`wechat` / `alipay` / `mock`。**与 `recharge_record.channel` 的列注释必须一致**（A1 第 3 条）。
  2. **充值单状态（4 种）**：见 A2。
  3. **差错类型（4 种，写进 `reconciliation_diff.diff_type`）**：
     | 值 | 含义 | 自动处理 |
     |---|---|---|
     | `local_only` | 本地有 `success`，渠道账单无 | **不自动补**——疑似伪造回调，必须先人工核 |
     | `channel_only` | 渠道账单有，本地无对应充值单 | **自动补单**（建新 `recharge_record` + 入账） |
     | `amount_mismatch` | 两边都有但金额不等 | **不自动处理**，落差异等人工 |
     | `identity_mismatch` | 账务恒等式不平（E1 巡检产出，不是渠道对账产出） | **不自动处理**，告警等人工 |
  4. 每条枚举带中文 `label`，label 走 i18n key（`trade.recharge.status.pending` 等），**不在 Java 里写中文**。
  5. 存字符串不存 `TINYINT`，后端用枚举 + MyBatis-Plus `IEnum` 映射（与段 5 A4 第 5 条一致）。
- **产出**：`modules/trade/enums/PayChannel.java`、`RechargeStatus.java`、`ReconcileDiffType.java`；`docs/acceptance/seg6/A4-enums.md`（枚举 × 中文 × i18n key 对照）。
- **算做完**：全部枚举无重复值、无遗漏值；非法字符串反序列化抛 `400001`（参数错误）而不是静默转 null。
- **卡住怎么办**：若发现 `payment_record.channel` 的注释里也有 `mock`（段 2 那边），**不要动段 2 的列注释**——两张表的 `channel` 语义本来就不同（那张是支付方式，这张是资金渠道）。

**A 组检查点**：`recharge_record` 建完（13 字段 + 4 索引）+ 状态机单测过（含晚到回调）+ 三个网关实现可切换 + 3 套枚举对照表 + 21 个 `sys_config` 键登记。不通过不进 B 组。

### B 组：充值下单与页面（4 个任务）

#### B1 · 充值下单与查询接口（3 个）

- **做什么**：App 侧能下单、能查单、能查记录。**这是本段唯一的前端入口**。
- **怎么做**：
  1. **`POST /app/wallet/recharges`** —— 充值下单
     - 请求：`{amountCents: number, channel: "wechat"|"alipay"|"mock"}`
     - 校验顺序（**顺序不能换**）：① 登录态与用户 → ② 金额合法性（`>= recharge.min_cents`、`<= 剩余单日额度`、整数分）→ ③ 渠道是否启用 → ④ 单日笔数/金额限流 → ⑤ 生成 `RC` 单号 → ⑥ 落 `pending` 单 → ⑦ 调网关 `prepay()` → ⑧ 回写 `channel_prepay_id`。
     - 响应：`{orderNo, amountCents, channel, payParams, expireAt}`。`payParams` 是拉起渠道的参数（微信是 `prepay_id` 等），**不含密钥**。
     - **幂等**：请求头 `Idempotency-Key`（《接口规范》要求），Redis 24 小时去重；同一 key 重复提交返回**同一张单**，不新建第二张。
     - 错误码：`405013` 渠道未启用 / `405014` 金额低于最低 / `405016` 超出单日上限 / `405019` 渠道下单失败。
  2. **`GET /app/wallet/recharges/{orderNo}`** —— 单笔查询（App 从渠道返回后轮询用）
     - 响应：`{orderNo, status, amountCents, channel, paidAt, balanceAfterCents}`。`balanceAfterCents` 取**流水汇总**，与段 1 的 D1 同源。
     - **必须支持「查不到就现查渠道」**：若本地 `pending` 且距下单超过 5 秒，接口内部先调 `gateway.query()` 一次再返回——这条是 `6-A3` 的用户侧兜底（用户付了钱，不能因为回调丢失就一直显示待支付）。
     - 归属校验：`user_id` 必须等于当前用户，否则 `403` + `405007` 同语义（非本人订单）。
  3. **`GET /app/wallet/recharges?cursor=&limit=`** —— 充值记录列表
     - 分页按《接口规范》C 端 `cursor` 场景（不用 `pageNum`）。
     - 字段：单号、金额、渠道、状态、时间。**状态用 i18n key 返回，不返回中文**。
     - **与 `GET /app/wallet/flows` 的关系**：这张表只是「充值单」视图，钱的真相仍然看流水列表。两者不合并——流水里还有消费/退款/手工充值。
  4. 三个接口的 DTO 全部 `Long` → `String` 序列化（金额除外，金额是 `long` 原样）。
  5. 契约写进 §五 5.2，并标「待回写《接口规范》§4.8.4」（§九 第 4 项）。
- **产出**：`modules/trade/controller/RechargeController.java` + 3 个 DTO + `RechargeService`（下单 / 查询 / 列表）+ `RechargeServiceImpl` + 单测。
- **算做完**：Swagger 列出 3 个接口；未登录打过去返回 `401` 不是 `404`；`Idempotency-Key` 相同重复提交 3 次只产生 1 张单（`SELECT COUNT(*) FROM recharge_record WHERE user_id=? AND created_at>?` = 1）；金额 `999` 分（低于 1000）被拒并返回 `405014`；别人的单号查返回 403。
- **卡住怎么办**：若「单日累计上限」需要跨多笔求和，**不要**在应用层循环查——用一条 `SELECT COALESCE(SUM(amount_cents),0) FROM recharge_record WHERE user_id=? AND status IN ('success','pending') AND created_at>=?`；`pending` 也要算进去，否则用户可以连续开多张单绕过上限。

#### B2 · 充值页实现（APP `36-recharge`）

- **做什么**：把 `design/ui/36-recharge.html` 实现成 `features/trade/pages/RechargePage.tsx`，并补上稿里没有的 8 项分支规格。
- **怎么做**：
  1. **页面结构照稿，逐块对应**（稿的 DOM 顺序就是页面顺序）：
     | 稿里的块 | 实现 | 数据来源 |
     |---|---|---|
     | `.bal-strip` 当前可用余额 | 余额条 | `GET /app/wallet/balance`（取 `availableCents`） |
     | `.amount-grid` 6 档 | 档位网格 | `sys_config.recharge.amount_options`（**不写死**，稿里那 6 个是默认值） |
     | `.custom-amount` 自定义输入 | 金额输入框 | 用户输入，`inputmode="decimal"` |
     | `.amount-hint` 最低/上限提示 | 提示文案 | 从配置拼文案，`t('trade.recharge.hint.limit', {min, max})` |
     | `.channel` × 2 支付方式 | 渠道单选 | 只渲染 `enabled=true` 的渠道 |
     | `.notice` 充值须知 4 条 | 静态文案 | i18n，**4 条一条都不能删**（含 3 条合规文案） |
     | `.actionbar__cta` 立即充值 | 底部 CTA | 金额联动，`refreshRechargeSubmit` 的等价逻辑 |
  2. **档位与自定义金额互斥**（稿的交互）：选档位 → 清空输入框；输入自定义 → 取消档位选中。CTA 文案 = `立即充值 ¥{选中金额}`，**未选金额时禁用并显示「请选择充值金额」**。
  3. **金额校验态**（稿里没有）：
     | 情况 | 表现 |
     |---|---|
     | `< recharge.min_cents`（**10 分 = 0.1 元**） | 输入框下方红字错误「单笔最低 ¥0.10」，CTA 禁用 |
     | `> recharge.max_amount_cents`（**500000 分 = 5000 元**） | 同上，文案「单笔最高 ¥5,000」 |
     | `> 剩余单日额度` | 同上，文案带「今日还可充值 ¥N」 |
     | 非数字 / 多于 2 位小数 | 输入时就过滤（`inputmode=decimal` + 正则），不允许提交 |
     | 合法 | 无错误，CTA 可用 |
  4. **页面四态**：
     - 加载：余额与配置未回来 → 骨架屏，CTA 禁用
     - 正常：如上
     - 空：`余额 = 0` 且无充值记录 → 不特殊处理（余额条显示 `¥0.00` 即可），**不要**做「空态插画」，稿里没有
     - 错误：余额接口失败 → 余额条显示「--」+ 重试按钮；下单失败 → `AppToast` + 停在当前页
     - **未实名不可充值**：`realnameStatus != APPROVED` 时页面顶部显示「请先完成实名认证」+「去实名」入口，**CTA 直接禁用**（负责人 2026-11-02 定：未实名额度为 0，不是限额而是拦截）。这是本页第 5 种状态，稿里没有
  5. **渠道禁用态**（稿里没有）：`enabled=false` 的渠道**不渲染**（不是置灰）——置灰会引导用户去问「为什么支付宝不能用」；两个都不可用时页面顶部显示提示「当前无可用的充值方式」并禁用 CTA。
  6. **提交链路**（稿只画到按钮）：
     ```text
     点 CTA → POST /app/wallet/recharges（带 Idempotency-Key）
       → 成功：拉起渠道 SDK（微信 Pay / 支付宝 SDK）
       → 渠道返回 → get 当前单 → 跳 32-pay-result（带 orderNo）
       → 用户从渠道切回来但没跳转 → 页面 onFocus 时主动查一次
     ```
  7. **支付中态**：拉起渠道后 CTA 变「处理中…」并禁用；用户从渠道切回 App 时（`AppState` 变 `active`）**主动查一次单**，查到 `success` 自动跳结果页，查到 `pending` 显示「支付结果确认中，稍后自动到账」+ 刷新按钮。
  8. **失败给单号**：下单/支付失败时，`AppToast` 文案末尾带单号（`RC...`）+ 一句「如需客服协助，请提供此单号」。单号从下单响应拿。
  9. **RTL 与令牌**：全部用 `start/end`，禁 `left/right`；颜色字号间距全走 `tokens.*`，禁字面量（稿里的 `#A3A9AE` 是占位符色，实现要用 `tokens.color.textPlaceholder`，若令牌表里没有就登记补令牌，**不许直接写十六进制**）。
  10. **i18n**：本段只做 `zh-Hans`，缺译登记；key 清单见下。
- **产出**：`features/trade/pages/RechargePage.tsx`、`app/(tabs)/wallet/recharge.tsx`（1 行 re-export）、`features/trade/api/recharge.ts`、`features/trade/types.ts`；i18n key 约 18 个（`trade.recharge.*`）。
- **算做完**：四态都能复现（用 mock 开关切）；金额校验 4 种情况都出正确提示；两个渠道都关掉时 CTA 禁用；点 CTA 能拉起 Mock 渠道并跳结果页；从渠道切回来能自动查单；`tsc --noEmit` + `eslint` 零告警。
- **卡住怎么办**：若渠道 SDK（微信/支付宝 RN 插件）在 Expo SDK 54 下不兼容（`expo prebuild` 与原生插件的常见冲突）→ **段 6 不解决**，把「拉起渠道」抽象成 `payment/invoke.ts` 一个函数，Mock 走页面内模拟确认框，真实 SDK 的接入记为「段 6 之后的基础设施任务」，`6-A1` 一并挂账。**不要为了接 SDK 把 Expo 换成裸 RN**。

#### B3 · 单日限额与风控（1 个）

- **做什么**：充值不是无限制的——单日额度、单日笔数、异常频次要拦住。
- **怎么做**：
  1. 单日累计：`SUM(amount_cents) WHERE user_id=? AND status IN ('success','pending') AND created_at >= 今日 0 点`，超过 `recharge.max_daily_cents` 抛 `405016`。
  2. 单日笔数：超过 `recharge.max_daily_count`（新增配置，默认 10）抛 `405016` 同一个码（不给攻击者区分维度）。
  3. **限流**：按《接口规范》的限流规则对下单接口加**用户维度**限流（如 1 分钟 3 次），超限返回 `429`。
  4. **未实名用户不可充值**（负责人 2026-11-02 定：`recharge.unverified_daily_cents = 0`）：这是**拦截**不是限额——`realnameStatus != APPROVED` 时下单直接拒绝，抛新码 **`405020`（未实名不可充值）**，App 侧提示「请先完成实名认证」并给跳转。**不要**实现成「额度为 0 的限额计算」（那会让用户看到一个永远不涨的「今日还可充值 ¥0.00」，是更差的体验）。已实名用户用完整上限。
  5. 时间口径：按服务器 `Asia/Shanghai` 自然日算，不用 UTC（用户看到的是本地日）。
- **产出**：`RechargeLimitService` + 单测（边界：正好等于上限 / 超 1 分 / 跨日重算）。
- **算做完**：连开 11 张单，第 11 张被拒；**未实名用户下单被直接拒绝（`405020`）且 App 引导实名**；单笔 0.09 元被拒（低于 10 分）、0.10 元通过；单笔 5000.01 元被拒；跨 0 点后额度重置；同一用户 1 分钟内第 4 次下单被限流。
- **卡住怎么办**：「剩余额度」需要实时算，用户点开页面就要显示 → 放进 `GET /app/wallet/balance` 的响应（加字段 `rechargeRemainingCents`），不要为它新开一个接口。

#### B4 · PC A-09「渠道对账」Tab（改造已有页，不新增页）

- **做什么**：在 A-09 对账报表页加一个「渠道对账」Tab，把 `reconciliation_diff` 展示出来，让运营能看差异、能标记已处理。**这是三条挂账锚点里唯一原本没有屏幕的那块**（`6-A1` 靠 A-07、`6-A6` 靠 App+PC+渠道后台，都有地方看）。
- **怎么做**：
  1. 页面文件：`src/views/biz/finance/reconcile/index.vue`（A-09 的现有实现，段 1 交付），在外层包 `el-tabs`：
     | Tab | 内容 | 允许改吗 |
     |---|---|---|
     | Tab1「恒等式巡检」 | 段 1 交付的余额=SUM(流水) 巡检结果（内存态差异列表） | ❌ **一个字都不许改**（外观、字段、交互、按钮全保留） |
     | Tab2「渠道对账」 | 本段新增：`reconciliation_diff` 差异工单 | ✅ 本段实现 |
  2. **Tab1 的嵌入方式**：把段 1 的原组件**原样**放进 Tab1 的插槽（`<el-tab-pane><BalanceIdentityPanel /></el-tab-pane>`），**不要复制它的代码再改**——复制会在两处维护，早晚分叉。若段 1 交付时 A-09 不是组件而是整页，就先把那段 template 抽成组件再包 Tab，**抽出时保持 DOM 与样式完全一致**。
  3. **Tab2 筛选栏**（4 个条件）：账单日期（`bill_date` 区间，默认昨日）+ 渠道（微信/支付宝/Mock）+ 差异类型（4 类）+ 状态（`pending` / `auto_fixed` / `handled`）。
  4. **Tab2 表格 8 列**：账单日期 / 渠道 / 差异类型 / 渠道交易号 / 本地单号 / 差异金额（元，两位小数）/ 状态 / 发现时间。**金额后端传分、前端除 100 显示**，禁浮点运算。
  5. **差异类型的中文与颜色**（4 类，见 §三 A4）：
     | 值 | 中文 | 颜色语义 |
     |---|---|---|
     | `channel_only` | 渠道有、本地无 | 红（可能是漏单） |
     | `local_only` | 本地有、渠道无 | 橙（可能是伪造回调） |
     | `amount_mismatch` | 金额不一致 | 橙 |
     | `identity_mismatch` | 恒等式不平 | 红 |
  6. **行操作**：`status != 'handled'` 时显示「标记已处理」→ 弹窗填处理备注（**必填**，≤ 500 字）→ 调 `PUT /admin/reconciliation/diffs/{id}` → 刷新列表。已处理的行走只读态。
  7. **空表**：显示「暂无差异」——这是**真实状态**（对账没跑或真的没差异），**不许塞假数据占位**。
  8. **权限**：财务角色可见，权限点 `finance:reconcile:list` / `finance:reconcile:handle`（RuoYi 权限体系，`@PreAuthorize`）。
  9. **不改 RuoYi 框架层**；业务页面放 `src/views/biz/finance/`，API 放 `src/api/biz/finance/`。
  10. 本段只做 `zh-Hans`（与段 4 一致）。
- **产出**：改造后的 `reconcile/index.vue`（含 Tab 壳 + Tab2）；`src/api/biz/finance/reconcile.js` 加 2 个方法（`listDiffs` / `handleDiff`）；若段 1 的 A-09 是整页则抽出 `BalanceIdentityPanel.vue`。
- **算做完**：① Tab2 能拉到 3 类差异（Mock 账单造的）并正确显示中文与颜色；② 标记已处理后 `status` 变 `handled`、备注落库、列表刷新；③ **Tab1 与段 1 交付时的截图逐像素一致**（切换前后各截一张对比）；④ 空表显示「暂无差异」；⑤ 无权限账号看不到「标记已处理」按钮。
- **卡住怎么办**：若段 1 的 A-09 页面结构与 Tab 冲突（例如用了 `el-container` 占满高度）→ 把 Tab 放在卡片内、Tab1 内容加 `overflow: auto`，**不要为了塞 Tab 去改段 1 的布局骨架**。若段 1 尚未交付 A-09（段 6 滑到段 1 之前？——不可能，段 6 最低依赖段 1）→ 按 §二 第 1 项停。

**B 组检查点**：3 个接口可用 + 页面四态齐 + 金额校验 4 种 + 限额 3 种 + 能拉起 Mock 渠道跳结果页 + **A-09 Tab2 能拉到差异且 Tab1 未变**。不通过不进 C 组。

### C 组：回调与入账（4 个任务）

> **本组是整段最危险的地方**：钱从渠道进来，只有一条正确路径。顺序错一步，就会出现「渠道收到钱、平台没加余额」或「加了两次」。
> 本组四个任务必须**按 C1 → C2 → C3 → C4 顺序做**，C3 依赖 C1/C2 能把回调解析成统一结构，C4 是对 C1~C3 的加固。

#### C1 · 微信支付回调（含免鉴权白名单）

- **做什么**：接收微信支付异步通知，验签、解密、转成统一 `CallbackResult`。
- **怎么做**：
  1. **路径**：`POST /pub/pay/callback/wechat`（前缀 `/pub/pay/callback/` 是 §零之二 第 9 项新定的免鉴权域）。
  2. **安全配置**（这条现在三份文档都没有，本段定）：
     - Spring Security 放行 `/pub/pay/callback/**`，**但只放行 POST**；
     - 不依赖 JWT、不依赖 session，**唯一信任来源是验签**；
     - **回调地址拼装**（§零之二 第 18 项）：`{pay.callback_base_url}/pub/pay/callback/wechat` 与 `.../alipay`。**域名不写死在代码里，也不进 `application.yml`**——只从 `sys_config` 读。域名未定时可留空，Mock 通道不读这个值；真实渠道上线时只改这一个配置项，**不改一行代码、不重新发版**。
- 限制来源 IP：微信支付回调 IP 段从官方文档拿，配成 `sys_config.pay.callback_ips`（逗号分隔，**允许为空表示不校验**——换来的是必须靠验签兜住，这是可接受的降级，但要写进 §六 风险表）；
     - 限制报文大小：`server.max-http-request-size` 之外的**应用层**也要限（如 64KB），超限直接 413；
     - 频率限制：同一 IP 每秒请求数上限，超限返回 429 但**不落库**（防刷）。
  3. **验签**（微信 APIv3）：
     - 从请求头取签名相关字段（`Wechatpay-Signature` / `Wechatpay-Timestamp` / `Wechatpay-Nonce` / `Wechatpay-Serial`，**具体字段名以官方文档为准**，本段不硬编码猜测）
     - 用平台证书公钥验签；证书按 `Wechatpay-Serial` 选
     - 验签失败 → 返回 4xx + 写 `operation_log` 一条「回调验签失败」（这是安全事件，必须留痕），**不入账**
     - **时间戳容差**：超过 5 分钟的报文拒绝（防重放的第一道）
  4. **解密**：APIv3 的 `resource` 字段是 AES-256-GCM 密文，用 `sys_config.pay.wechat.api_v3_key` 解密。**解密后的明文只用于解析，不落日志。**
  5. **转统一结构** `CallbackResult`：
     ```java
     { orderNo, channelTxnId, amountCents, status(success|failed), paidAt, rawPayload(已脱敏) }
     ```
     **`amountCents` 必须与本地单比对**：不等 → 不入账 + 写 `reconciliation_diff`（`diff_type='amount_mismatch'`）+ 告警。**这是防篡改的关键**。
  6. **响应格式**：渠道要求的成功响应体必须**按官方格式**返回（微信是 `{"code":"SUCCESS"}` 之类），否则渠道会一直重试。**不许返回 `R.ok()`**——渠道不认识统一响应体。
  7. **落库**：`recharge_record.raw_payload` 存脱敏后的原始报文；`channel_txn_id` 存渠道交易号。
- **产出**：`modules/trade/controller/PayCallbackController.java`、`infra/pay/wechat/WechatCallbackParser.java` + 单测（正常验签 / 验签失败 / 金额不符 / 重放 / 超大报文 5 个用例）；`config/SecurityConfig` 的放行规则。
- **算做完**：用微信官方提供的**验签测试报文**（官方文档给了样例，没商户号也能验签名逻辑）打通解析；伪造签名的报文被拒且写审计；金额被改大的报文被拒且落差异；同一报文连发 3 次只入账 1 次（C4 验）。
- **卡住怎么办**：没有商户号时拿不到平台证书 → **先用 Mock 通道的回调解析器验整条链路**（C3/C4 不依赖真实渠道），微信解析器写成「验签接口 + 桩实现」，`6-A1` 挂账。**不要**为了让链路过就把验签改成 `return true`。

#### C2 · 支付宝回调

- **做什么**：接收支付宝异步通知，RSA2 验签、转统一结构。
- **怎么做**：
  1. 路径：`POST /pub/pay/callback/alipay`。
  2. 支付宝回调是**表单格式**（`application/x-www-form-urlencoded`），不是 JSON——`@RequestBody` 收不到，要用 `HttpServletRequest.getParameterMap()`。**这是最常见的坑**。
  3. 验签：用支付宝公钥 + RSA2 对「去掉 `sign` 与 `sign_type` 后的参数按字典序拼接」验签。**参数里有中文要按 `UTF-8` 解码**，否则验签必失败。
  4. 转 `CallbackResult`：`out_trade_no` → `orderNo`，`trade_no` → `channelTxnId`，`total_amount` **元 → 分**（`BigDecimal` 乘 100 取整，**禁 `double`**）。
  5. 响应：返回纯文本 `success`（支付宝要求），否则重试。
  6. 其余（金额比对、落库、失败留痕）与 C1 相同，**同一个 `CallbackHandler` 处理，不写第二套**。
- **产出**：`infra/pay/alipay/AlipayCallbackParser.java` + 单测（表单解析 / 元转分精度 / 验签失败 3 个用例）。
- **算做完**：表单报文能解析出正确金额（`0.01` 元 → `1` 分，`1234.56` → `123456` 分）；签名错被拒；响应体是 `success` 纯文本。
- **卡住怎么办**：若发现支付宝沙箱与生产验签规则不同 → **以生产规则实现**，沙箱只在 §六 风险表里记一笔。**不许**为了沙箱能过就跳过验签。

#### C3 · 入账链路（`balance_flow` + `user_balance`，同一事务）

- **做什么**：把「回调成功」变成「用户余额真的加上了」，且只加一次。
- **怎么做**：
  1. **严格顺序**（顺序错了就会丢钱或重账）：
     ```text
     ① 按 order_no 查单（FOR UPDATE 或 CAS 前置）
     ② 校验：单存在、归属正确、状态 = pending、金额一致
     ③ 写 balance_flow（biz_type='recharge'，biz_id=recharge_record.id，delta=+amount_cents）
     ④ 由流水汇总更新 user_balance.balance_cents（不是 +=，是 SUM 回写）
     ⑤ CAS 更新 recharge_record.status = 'success'（WHERE status='pending'）
     ⑥ 更新 recharge_record.paid_at / channel_txn_id / expire_at 置空
     ```
     **③④⑤ 必须在同一事务内**。步骤 ⑤ 的 CAS 如果返回 0 行（说明另一个并发回调已置位），**整个事务回滚**，然后按幂等成功处理（不再入账）。
  2. `balance_after` 取**账户总余额**（`balance_cents + frozen_cents`），不是可用余额——这是段 1 §三 D 组定、段 5 §三 E0 复用的口径，段 6 照做（§零.3）。
  3. **`biz_id` 必须非空**：`biz_id = recharge_record.id`。若为 `NULL`，`uk_flow_biz(owner_type, owner_id, biz_type, biz_id)` 在 MySQL 下**不拦多行 NULL**，幂等失效（§零之二 第 6 项）。
  4. **手工充值与本段并存的区分**：两者 `biz_type` 都是 `recharge`，靠 `biz_id` 指向不同东西——真实充值的 `biz_id` 是 `recharge_record.id`；手工充值的 `biz_id` 走段 1 的逻辑（**段 1 的实现有缺陷，见 §九 第 3 项，本段不修**）。段 6 要保证的是：**自己的入账一定能被 `uk_flow_biz` 拦住重复**。
  5. **异常处理**：
     - 入账失败（DB 异常）→ 事务回滚 → **返回 500 给渠道**，让渠道重试（这是正确策略：宁可让渠道重试，也不要吞掉）
     - 唯一键冲突（`uk_recharge_channel_txn` 或 `uk_flow_biz`）→ 说明已入过账 → **返回成功给渠道**，不再入账
  6. **入账后必须发一条事件**（本段用 `task_queue`，不引入 MQ）：`task_type='recharge_success_notify'`，供段 7 的运营通知用。段 6 只投递不消费，**消费实现留给段 7**——如果本段就把消费写了，段 7 会重复通知。
- **产出**：`RechargeServiceImpl.applyCallback()` + `BalanceFlowService.recharge()`（复用段 1 的流水服务，**不新写一套**）+ 单测（正常入账 / 金额不符 / 重复回调 / 入账异常回滚 4 个用例）。
- **算做完**：Mock 通道充值 100 元 → `balance_flow` 1 条（`biz_type='recharge'`、`biz_id` = 充值单 id、`delta_cents=10000`、`balance_after` = 总余额）→ `user_balance.balance_cents` 增加 10000 → `recharge_record.status='success'` + `paid_at` 非空；同一回调再打 3 次，`balance_flow` 仍 1 条、余额仍只加一次；模拟 DB 异常时事务全回滚（单仍 `pending`）。
- **卡住怎么办**：若段 1 的 `BalanceFlowService` 没有「按流水汇总回写缓存」的方法 → **本段在段 1 的服务上加方法，不改其语义**；若段 1 的 `balance_flow` 写入强制要求 `remark` 非空，用 `remark='充值 RC...'` 填。**不许**绕过段 1 的流水服务直接 `INSERT INTO balance_flow`。

#### C4 · 幂等三重保障（对 C1~C3 的加固与验证）

- **做什么**：把「重放 3 次只入账 1 次」做成可证明的事实，而不是靠开发者记得。
- **怎么做**：
  1. **三重保障**（《技术路线》§9.3 原文，段 6 落到充值场景）：
     | 层 | 机制 | 段 6 的具体落法 |
     |---|---|---|
     | 数据库 | `uk_recharge_channel_txn (channel, channel_txn_id)` | 同一渠道交易号只能有一行 |
     | 数据库 | `uk_flow_biz (owner_type, owner_id, biz_type, biz_id)` | 同一充值单只能有一条 `recharge` 流水 |
     | 应用 | CAS 状态迁移 `WHERE status='pending'` | 并发回调只有一个能把 `pending → success` |
     | 应用 | `Idempotency-Key` + Redis 24h | **下单**接口的重复提交（不是回调用） |
  2. **回调侧不额外加 Redis 去重**：渠道回调本来就是重放的，加 Redis 去重会引入「Redis 挂了就丢单」的新故障面。**靠 DB 两把唯一键 + CAS 足够**，这比加缓存更可靠。
  3. **必须写一个「幂等证据生成器」**：一个可重复执行的脚本/测试，把同一报文打 3 次，输出三组 SQL 结果（`recharge_record` 行数、`balance_flow` 行数、余额值），存进 `docs/acceptance/seg6/C4-idempotent.txt`。**这是 `6-A2` 的证据来源。**
  4. 并发场景也要验：用两个线程同时打同一回调，结果与串行重放一致（一个成功、一个幂等返回）。
- **产出**：`RechargeIdempotentTest`（串行重放 3 次 + 并发 2 线程）+ `docs/acceptance/seg6/C4-idempotent.txt`。
- **算做完**：串行重放 3 次、并发 2 次，两种情况下 `recharge_record` 成功行 = 1、`balance_flow` recharge 行 = 1、余额增量 = 1 份额。
- **卡住怎么办**：若发现并发下两个线程都进了 ③（写了流水）→ 说明 `FOR UPDATE` 或 CAS 没生效。**停下修事务边界**，不要用「应用层加锁」绕过去（多实例时应用锁无效）。

**C 组检查点**：两个渠道解析器可解析 + 免鉴权白名单生效（不登录能打到回调、登录态接口仍要 JWT）+ 入账链路事务正确 + 幂等证据文件生成。不通过不进 D 组。

### D 组：掉单补单与对账（4 个任务）

> 这一组解决「回调不可靠」这个客观事实：网络会断、渠道会延迟、服务会重启。**补单是把「用户付了钱但没到账」从事故变成流程。**
> 时间口径统一按服务器 `Asia/Shanghai`。

#### D1 · 主动查询补单（掉单链路一）

- **做什么**：定时扫「本地还 `pending` 但可能已经付了」的单，主动向渠道查，查到成功就补入账。
- **怎么做**：
  1. **任务类型**：`task_queue` 的 `task_type='recharge_pending_query'`，**不新建任务表**。
  2. **投递时机**：下单成功后投递一条，`next_retry_at = now + 5 分钟`（《技术路线》§9.4 定的「每 5 分钟查一次」）。
  3. **执行逻辑**（每个任务处理一张单）：
     ```text
     ① 按 biz_no 查 recharge_record
     ② 若状态不是 pending → 直接结束（已被回调处理）
     ③ 调 gateway.query(orderNo)
     ④ success → 走 C3 的入账链路（同一份代码，不复制）
        failed  → 置 failed
        pending/查不到 → 重排：retry_count+1，next_retry_at = now + 5min × 2^retry_count
     ⑤ retry_count > 6（约 5 小时后）→ 进死信（status='dead'），不再自动查
     ```
  4. **扫表兜底**：任务表之外的补偿——一个**每小时**的低频扫描，找出「`status='pending'` 且 `created_at` 超过 10 分钟且任务表里没有对应任务」的单（任务丢了的场景），补投递任务。**这条必须有**，因为任务表本身也可能因异常丢记录。
  5. **死信可见性**：死信任务在 PC 端 A-26 日志中心可见——**但 A-26 属段 7**。段 6 先只落库 + 写告警日志，页面留到段 7（记进 §八）。
  6. **审计**：自动补单**不写 `operation_log`**（不是人工操作），但要写应用日志：单号、渠道、查询结果、入账流水 ID。
- **产出**：`RechargePendingQueryHandler`（实现 `task_queue` 的 handler 接口）+ 每小时兜底扫描的 `@Scheduled` + **管理端人工补单接口 `POST /admin/recharges/{orderNo}/sync`**（复用同一 handler 逻辑，标 `@OperationLog`，审计码 `RECHARGE_SYNC`）+ 单测（Mock 返回 success / failed / 查不到 / 死信 4 个用例）。
- **算做完**：用 `mock.result=callback_lost` 下单成功（回调不发）→ 5 分钟后任务自动查出成功并补入账 → 余额加上、单变 `success`、日志可查；连续 6 次查不到后进死信不再重试；人为删掉任务表记录后兜底扫描能补投递。
- **卡住怎么办**：若 `task_queue` 的重试语义是「失败才重排」，而「查不到」不算失败——**把「查不到」当成功处理并在 handler 内部重排**，不要改成抛异常（抛异常会让它进死信太快）。

#### D2 · 超时关单

- **做什么**：用户下单后没付，`expire_at` 到期置 `closed`，避免无限堆积 `pending` 单。
- **怎么做**：
  1. 任务类型 `task_type='recharge_close'`，下单时投递，`next_retry_at = expire_at`。
  2. 执行：① 查单 ② 状态必须仍是 `pending` ③ 先 `gateway.query()` 确认渠道确实没付（**不能只按时间关单**——用户可能在最后一秒付了，直接关单会造成「渠道有钱、本地关闭」的掉单）④ 确认未付 → CAS 置 `closed` ⑤ 审计动作 `RECHARGE_CLOSE`（若由任务触发则不写 `operation_log`，只写日志；人工关闭才审计）。
  3. **`6-A7`「不占配额」的落实**：关单后 `pending` 不再计入单日额度（B3 第 1 条的 SQL 只算 `success` + `pending`，`closed`/`failed` 自然不计）。要验证：下 3 单不付，额度恢复。
  4. 关单**不产生任何流水**。
- **产出**：`RechargeCloseHandler` + 单测（正常关单 / 已付单不关 / 已付但回调丢失时查询能救回 3 个用例）。
- **算做完**：不付的单 30 分钟后变 `closed`；把 `mock.result` 设为成功但延迟到达时，关单前会先查渠道、查到已付则不关闭而是补入账。
- **卡住怎么办**：若手工造「最后一秒付款」很难 → 直接把 `recharge.expire_minutes` 配成 1 分钟，用 Mock 延迟 2 分钟回调来复现，**不要跳过这条测试**（这是掉单最典型的发生方式）。

#### D3 · 渠道对账单下载

- **做什么**：每天把渠道的账单文件拉下来存好，作为「钱的另一本账」。
- **怎么做**：
  1. 任务类型 `task_type='recharge_reconcile_download'`，**每日 02:00**（《技术路线》§9.5 定的时间）投递，`biz_no = 昨日日期 yyyyMMdd`。
  2. 频率与幂等：`biz_no` 用日期，同一日的任务重复投递只执行一次（任务表 `biz_no` 唯一或 handler 内先查）。**《技术路线》§9.5 说「对账报表留存 3 年」**，所以账单文件不能覆盖。
  3. **文件落地**：下载的原始账单存 `file_record`（段 0/1 的 OSS 通道）+ `reconciliation_diff` 不存文件，只存差异行。**账单文件本身是财务凭证，必须留档。**
  4. **Mock 通道的对账单**：没有真实账单时，`MockPaymentGateway` 要能生成一份**格式与真实账单同构的本地 CSV**（字段：渠道交易号、商户单号、金额、交易时间、状态）。生成的账单要能被 D4 的解析器读。
  5. **解析器抽象**：`ReconcileBillParser` 接口 + `WechatBillParser` / `AlipayBillParser` / `MockBillParser`。三个渠道的账单格式都不同（微信是 CSV 含表头与汇总行，支付宝是 zip 里的 CSV），**解析器各自处理自己的格式，输出统一的 `List<BillRow>`**。
  6. 账单里的金额单位：微信/支付宝账单多以**元**为单位 → 转分用 `BigDecimal`，**禁 `double`**。这一条与 C2 第 4 条同一个坑。
- **产出**：三个解析器 + `BillRow` DTO + 单测（Mock 账单 5 行解析 / 元转分精度 / 空账单 / 格式异常 4 个用例）+ 下载任务 handler。
- **算做完**：Mock 通道跑一次，OSS 里有账单文件、`file_record` 有记录；解析出的 `BillRow` 条数 = 账单数据行数（不含表头汇总行）；金额转分无精度丢失（`0.1` 元 → `10` 分）。
- **卡住怎么办**：无商户号时真实账单格式拿不到 → **先只实现 `MockBillParser`**，另两个解析器写成「按官方文档格式的骨架 + TODO」，`6-A4` 挂账。**不要**凭猜测写微信账单解析然后声称能跑。

#### D4 · 逐笔比对与差异落库

- **做什么**：把本地充值记录与渠道账单逐笔比，差异写成 `reconciliation_diff` 工单。
- **怎么做**：
  1. 任务类型 `task_type='recharge_reconcile_compare'`，**在 D3 完成后触发**（D3 投递 D4，或 D3 的 handler 末尾投递）。
  2. **比对口径**（以「商户单号 = `order_no`」为主键匹配，`channel_txn_id` 做辅助）：
     | 情况 | 落库 | 自动处理 |
     |---|---|---|
     | 两边都有、金额与状态一致 | 不落 | — |
     | **渠道有、本地无**（`channel_only`） | 落 `reconciliation_diff` | **自动补单**：建新 `recharge_record`（`status='success'`）+ 走 C3 入账 + 差异行标记 `status='auto_fixed'` |
     | **本地有、渠道无**（`local_only`） | 落差异 | **不自动处理**（疑似伪造回调，必须人工核） |
     | 两边都有、金额不等（`amount_mismatch`） | 落差异 | **不自动处理** |
  3. **`reconciliation_diff` 的字段映射**（表是段 5 建的 14 字段，本段只写）：
     | 字段 | 本段填什么 |
     |---|---|
     | `bill_date` | 账单日期 |
     | `channel` | `wechat` / `alipay` / `mock` |
     | `diff_type` | `channel_only` / `local_only` / `amount_mismatch` |
     | `channel_txn_id` | 渠道交易号（`channel_only` 时是唯一线索） |
     | `recharge_id` | 本地单 id（`channel_only` 时为空，补单后回填） |
     | `amount_cents` | 差异金额（渠道金额 − 本地金额；本地无则渠道金额） |
     | `detail` | JSON：渠道行原文（脱敏）+ 本地行快照 |
     | `status` | `pending` / `auto_fixed` / `handled` |
     | `handler_id` / `handle_note` / `handled_at` | 人工处理时填 |
  4. **`6-A4` 的证据口径**（§零之二 第 3 项裁决）：
     - `GET /admin/reconciliation/diffs?date=` 的响应体
     - `SELECT * FROM reconciliation_diff WHERE bill_date=?` 的输出
     - **A-09 页面截图，与段 1 交付时的截图逐像素一致**（证明本段没动它）
     - 差异报表能导出（CSV），导出的数据与接口一致
  5. **人工处理接口**：`GET /admin/reconciliation/diffs`（已有）+ `PUT /admin/reconciliation/diffs/{id}`（新增，标记 `handled` + `handle_note`）。**第二个接口是本段新增的第 8 个接口**，必须审计（`RECONCILE_HANDLE`）。
- **产出**：`ReconcileCompareHandler` + `ReconcileDiffMapper`（写段 5 的表）+ `PUT /admin/reconciliation/diffs/{id}` + 单测（三类差异各 1 个 + 自动补单 1 个 + 幂等重跑 1 个）。
- **算做完**：造 3 类差异各 1 条，比对后 `reconciliation_diff` 3 行且 `diff_type` 正确；`channel_only` 那条自动补单且余额正确；**同一天重跑比对，不产生重复差异行**（按 `bill_date + channel + channel_txn_id + diff_type` 去重）；A-09 页面截图与段 1 交付时一致。
- **卡住怎么办**：若 D3 没拿到账单（无商户号）→ 用 Mock 账单验，`6-A4` 挂账。若发现 `reconciliation_diff` 没有去重唯一键（段 5 建表时没加）→ **本段不擅自加索引**（改了段 5 的表结构），改为在比对逻辑里先查后插，并把「缺唯一键」记进 §九 第 7 项。

**D 组检查点**：掉单能自动补 + 超时能关单且不误关已付单 + 账单能下载与解析 + 三类差异都能落库且可重跑不重复。不通过不进 E 组。

### E 组：收口（3 个任务）

#### E1 · 账务恒等式巡检

- **做什么**：把两条恒等式做成可定时执行的巡检，差异为 0 时静默、不为 0 时告警。
- **怎么做**：
  1. **巡检范围**：本段只做**用户侧**（`owner_type='user'`）；律师侧 `lawyer_balance` 的恒等式属段 5（已交付），本段不重复做但**要跑一遍确认没被自己影响**。
  2. **两条校验 SQL**（§零.3 原文口径）：
     ```sql
     -- ① available + frozen == 最后一条流水的 balance_after
     SELECT ub.user_id,
            ub.balance_cents + ub.frozen_cents AS total_cents,
            (SELECT bf.balance_after FROM balance_flow bf
              WHERE bf.owner_type='user' AND bf.owner_id=ub.user_id
              ORDER BY bf.id DESC LIMIT 1) AS last_after
     FROM user_balance ub
     HAVING total_cents <> COALESCE(last_after, 0);

     -- ② available + frozen == SUM(delta_cents)
     SELECT ub.user_id,
            ub.balance_cents + ub.frozen_cents AS total_cents,
            (SELECT COALESCE(SUM(bf.delta_cents),0) FROM balance_flow bf
              WHERE bf.owner_type='user' AND bf.owner_id=ub.user_id) AS sum_delta
     FROM user_balance ub
     HAVING total_cents <> sum_delta;
     ```
  3. **任务**：`task_type='finance_identity_check'`，每日 **03:00**（对账之后）跑一次。
  4. **差异处置**：
     - = 0 → 只写一行「巡检通过」日志（不写库、不告警、不打扰）
     - ≠ 0 → ① 写 `reconciliation_diff`（`diff_type` 用 `identity_mismatch`，**这是第 4 种差异类型**，需在 A4 枚举里补）② 写 ERROR 日志 ③ 告警（告警通道用段 7 的监控；**段 7 未就绪时先写「告警级别日志 + 后台红标接口」**，§零之二 第 7 项同源处理）
  5. **手动触发**：`POST /admin/reconciliation/run`（技术路线 §4.8.4 已有，段 1 实现的）→ 本段要保证它跑的是**两条**恒等式，不只是段 1 的那一条。
  6. 巡检只读，不改任何数据。**发现差异后不许自动「修平」**——自动改账会让真正的原因永久消失。
- **产出**：`FinanceIdentityCheckHandler` + 两条 SQL 的 Mapper + 单测（差异 0 静默 / 人为造 1 条差异后能检出）。
- **算做完**：正常数据下巡检**无输出、无告警**；手工 `UPDATE user_balance SET balance_cents = 99999900` 后巡检能检出 1 条且写进 `reconciliation_diff`；`6-A5` 的证据是这两次巡检的输出对比。
- **卡住怎么办**：若发现「最后一条流水」的子查询在数据量大时慢（每用户一次子查询）→ 本段数据量小，直跑；在 §六 记「用户量上来后改为按时间窗口增量巡检」。

#### E2 · Mock 端到端演练 + 三条挂账锚点评估

- **做什么**：在 Mock 通道下把 7 条锚点能验的全验一遍，把验不了的三条写清挂账条件与工作量估算。
- **怎么做**：
  1. **演练脚本**（一条命令能跑完）：
     ```text
     ① 注册/登录测试用户（段 1）
     ② 下单 100 元（mock 通道）→ 拿到单号
     ③ 触发 Mock 回调（mock.result=success）→ 查余额 == 100.00
     ④ 同一回调再打 3 次 → 余额仍 100.00
     ⑤ 下单 200 元（mock.result=callback_lost）→ 等任务补单 → 余额 300.00
     ⑥ 下单 50 元（mock.result=fail）→ 单变 failed、余额不变、App 显示可读失败原因
     ⑦ 下单 30 元不付 → 等超时 → 单变 closed、余额不变、额度恢复
     ⑧ 跑对账（Mock 账单含 1 条本地无的记录）→ 自动补单 → 余额 +该金额
     ⑨ 跑恒等式巡检 → 差异 0
     ```
  2. **三条挂账的评估**（每条写：现在缺什么 / 商户号下来后要做几步 / 工作量级 / 影响）
     | 锚点 | 现在缺什么 | 商户号下来后 | 影响 |
     |---|---|---|---|
     | `6-A1` | 真实商户号、真实 App 授权、证书 | 配 5 个 `sys_config` 键 + 真机走一次 1 元 | 不做则「真实到账」永远未证 |
     | `6-A4` | 真实账单文件格式 | 两个 parser 的格式对齐 + 跑一次真实对账 | 不做则对账只对 Mock 有效 |
     | `6-A6` | 渠道侧账单/后台 | 三方数字对抄 | 不做则无法证明三方一致 |
  3. 演练结果存 `docs/acceptance/seg6/E2-e2e.txt`（每步的命令 + 关键 SQL 输出）。
- **产出**：`docs/acceptance/seg6/E2-e2e.txt` + `docs/acceptance/seg6/挂账.md`。
- **算做完**：演练脚本一次跑通；每一步都有证据；三条挂账写清「条件 / 步骤 / 工作量 / 影响」四要素。
- **卡住怎么办**：若某个 Mock 分支实现不出来（如 `callback_lost`）→ **先实现 Mock 的这个能力**，它是验 `6-A3` 的唯一手段。不许用「手动删一条任务表记录」代替。

#### E3 · 文档与证据收口

- **做什么**：按总纲 §6.2 的「收口五件套」办。
- **怎么做**：
  1. **验收证据**：`docs/acceptance/seg6/` 下按锚点分文件（`6-A2-idempotent.txt` / `6-A3-recover.txt` / `6-A5-identity.txt` / `6-A7-failure.txt` / `挂账.md`）。
  2. **工作流回写**：在本文档 §十一 追加「实际完成 / 偏差 / 未做」，**不删原文**。
  3. **CHANGELOG 一节**：`docs/CHANGELOG.md` 写「2026-XX-XX · 段 6 施工完成」，含验收结果（4 过 3 挂账）、与计划偏差、新增遗留。
  4. **文档基线**：本文档 §九 的 10 行回写完成（第 1~9 行已于 v1.32 落地；第 10 行设计稿待负责人定），出**新基线版本**（落款 / `BASELINE.md` 指纹 / `docs/README.md` 索引 / `CHANGELOG.md`）。
  5. **遗留挂账**：三条锚点 + 段 5 未交付时顺延的对账 + 渠道 SDK 兼容问题，写进 §十 或段 7 开工前置。
- **产出**：证据目录 + §十一 回写 + 新基线版本。
- **算做完**：`docs/acceptance/seg6/` 有 5 个文件；`git log` 有一条 `docs(plan): 段 6 施工完成`；基线版本号比施工前大。
- **卡住怎么办**：若 §九 的某行回写改了别的段工作流（如段 1 的账务口径）→ **先问负责人**，不要自行改别人已定稿的文档。

**E 组检查点**：巡检可跑且差异 0 + 演练脚本全通 + 证据文件齐 + 回写完成。**三条挂账必须在 CHANGELOG 里显式写明「未验」**，不许含糊成「基本完成」。

## 四、验收锚点（`6-A1` ~ `6-A7` 逐条展开）

> 编号沿用《开发计划总纲》§5.7，共 **7 条**（总纲 L241 已计入「全项目 78 条」）。
> 每条都有：**怎么验（照着做）** / **证据形式** / **通过标准（带数字）** / **失败长什么样**。
>
> **⚠️ 三条在本段无法验完，必须挂账**（§零之二 第 7 项）：`6-A1`、`6-A4`、`6-A6`。挂账不是通过——收口时 CHANGELOG 必须写明「7 条中 4 条已验、3 条挂账」。
>
> | 锚点 | 状态 | 依赖 |
> |---|---|---|
> | `6-A1` 真实到账 | ⛔ **挂账** | 商户号 + 真实 App 授权 |
> | `6-A2` 回调幂等 | ✅ 可验（Mock） | — |
> | `6-A3` 掉单补单 | ✅ 可验（Mock） | — |
> | `6-A4` 对账差异 | ⚠️ **部分挂账**：差异识别可验（Mock 账单），真实账单比对挂账 | 商户号 |
> | `6-A5` 恒等式巡检 | ✅ 可验 | — |
> | `6-A6` 三方一致 | ⛔ **挂账** | 商户号 + 渠道后台 |
> | `6-A7` 渠道失败可读 | ✅ 可验（Mock） | — |

### `6-A1` 真实到账 ⛔ 挂账

- **怎么验**：真机装上包 → 登录 → 进充值页 → 选 1 元（自定义输入）→ 微信支付 → 完成真实付款 → 回到 App 看余额与流水 → 管理员登录 PC A-07 看该用户流水 → 微信商户后台看该笔交易。
- **证据形式**：手机付款截图 + App 余额页 + App 流水页 + PC A-07 流水查询 + 商户后台交易详情页；SQL：`SELECT order_no,channel,channel_txn_id,amount_cents,status,paid_at FROM recharge_record WHERE id=?`、`SELECT biz_type,delta_cents,balance_after FROM balance_flow WHERE biz_id=?`。
- **通过标准**：
  1. 余额增加 **1.00**（100 分），不是 0.99 或 1.01；
  2. `recharge_record` 有 `channel_txn_id`（渠道交易号，非空）；
  3. `balance_flow` 有 1 条 `biz_type='recharge'`、`delta_cents=100`、`biz_id` = 该充值单 id；
  4. 流水类型显示「充值」、渠道显示「微信」、时间与付款时间一致（±1 分钟）；
  5. PC A-07 能按单号查到同一条。
- **失败长什么样**：App 余额变了但 `balance_flow` 没有对应行 → 不过（有人直接改了缓存）；或 `channel_txn_id` 为空 → 不过（回调没存渠道号，后续无法对账）；或金额差 1 分 → 不过（元转分有精度问题）。
- **挂账条件**：商户号下发 + 微信开放平台应用审核通过 + 证书配置完成。
- **挂账期间的状态**：本锚点**未验**。`6-A7` 的 Mock 失败态不能替代它——Mock 证明的是「链路能跑」，不是「钱真能进来」。

### `6-A2` 回调幂等

- **怎么验**：Mock 通道下单 100 元 → 触发成功回调 → 记下余额 → **把同一份回调报文原样再打 3 次**（含一次并发双打）→ 再查余额。
- **证据形式**：`docs/acceptance/seg6/C4-idempotent.txt`（三次重放前后的 `SELECT COUNT(*)`、`SUM(delta_cents)`、余额值三组对照）+ 应用日志片段（同一 `channel_txn_id` 出现 4 次处理记录，只有 1 次入账）。
- **通过标准**：
  1. 余额**只加一次**（100.00，不是 200/300/400）；
  2. `recharge_record` 里该 `channel_txn_id` **只有 1 行**（`uk_recharge_channel_txn` 生效）；
  3. `balance_flow` 里 `biz_id` = 该单 id 的 `recharge` 流水**只有 1 条**（`uk_flow_biz` 生效）；
  4. 第 2~4 次回调**返回成功给渠道**（不能返回失败，否则渠道会无限重试）；
  5. 并发双打时，一个成功、一个幂等返回，**都不报 500**。
- **失败长什么样**：余额变成 200 → 不过（入账没走唯一键兜底）；或第 2 次回调返回 500 → 不过（渠道会无限重试，最终拖垮接口）；或 `recharge_record` 出现 4 行 → 不过（每次回调都新建了单）。

### `6-A3` 掉单补单

- **怎么验**：Mock 通道设 `mock.result=callback_lost` → 下单 200 元（渠道记为成功但**不回调**）→ App 显示「待支付」→ 等补单任务跑（5 分钟，或手动触发 `POST /admin/recharges/{orderNo}/sync`）→ 看余额与单状态。
- **证据形式**：下单前后截图 + 补单任务日志（含调用 `query()` 的返回、入账流水 ID）+ SQL：`SELECT status,paid_at FROM recharge_record WHERE order_no=?`、`SELECT id,biz_type,delta_cents,created_at FROM balance_flow WHERE biz_id=?` + `docs/acceptance/seg6/6-A3-recover.txt`。
- **通过标准**：
  1. 余额**正确补上 200.00**；
  2. 单状态从 `pending` 变 `success`，`paid_at` 有值（取渠道返回的付款时间，不是补单时间）；
  3. **补单有日志可查**：谁触发的（定时任务 / 人工）、依据什么（哪个渠道查询接口、返回了什么）、补了几条流水；
  4. 补单路径与正常回调走**同一份入账代码**（不是复制的一套）；
  5. 再补一次不会重复入账（补单也幂等）。
- **失败长什么样**：余额补上了但 `paid_at` 是补单时间 → 不过（时间口径错，对账会错）；或补单日志只有「补单成功」没有渠道返回原文 → 不过（无法证明依据）；或自动补单后又来一个迟到回调导致余额加两次 → 不过（幂等没覆盖补单路径）。

### `6-A4` 对账差异 ⚠️ 部分挂账

- **怎么验**（可验部分）：Mock 通道造 5 笔充值，其中 3 笔成功入账 → 生成一份 Mock 账单，包含：① 3 笔正确记录 ② **1 条渠道有、本地无**（金额 88 元）③ **1 条金额不一致**（本地 50 元、账单 55 元）→ 跑对账 → 查差异与余额。
- **证据形式**：① `GET /admin/reconciliation/diffs?date=` 响应体；② `SELECT * FROM reconciliation_diff WHERE bill_date=?`；③ **PC A-09「渠道对账」Tab 的截图**（Tab2 能列出差异）+ **Tab1「恒等式巡检」的截图，与段 1 交付时逐像素对比**；④ 导出的 CSV；⑤ `docs/acceptance/seg6/6-A4-reconcile.txt`。
- **通过标准**：
  1. **差异报表能输出**（接口返回 2 条，CSV 也是 2 行）；
  2. `diff_type` 正确：`channel_only` 1 条、`amount_mismatch` 1 条；
  3. **人为改一条本地记录后差异能被识别**（这是锚点原文的要求）：把某笔 `success` 单的 `amount_cents` 改掉 → 重跑对账 → 多出 1 条 `amount_mismatch`；
  4. `channel_only` 那条**已自动补单**（余额 +88.00）且差异行 `status='auto_fixed'`；
  5. **同一天重跑对账不产生重复差异行**；
  6. **A-09 页面外观与段 1 交付时逐像素一致**（证明本段没动它）。
- **失败长什么样**：差异报表是空的但对账「跑成功」→ 不过（比对逻辑没生效）；或自动补单后余额没变 → 不过；或 A-09 上出现了新的 Tab/列 → 不过（违反 §零之二 第 3 项）。
- **挂账条件**：真实商户号下发后，用**真实账单格式**跑一次并比对真实交易。挂账的是「真实账单解析与比对」，Mock 账单的比对已验。

### `6-A5` 恒等式巡检

- **怎么验**：① 正常数据下跑巡检 → 记下输出（应为空）；② 手工 `UPDATE user_balance SET balance_cents = 99999900` 造一条差异 → 再跑巡检；③ 恢复数据 → 再跑一次。
- **证据形式**：三次巡检的命令与输出 + `SELECT * FROM reconciliation_diff WHERE diff_type='identity_mismatch'` + 告警日志（第 2 次应命中）+ `docs/acceptance/seg6/6-A5-identity.txt`。
- **通过标准**：
  1. 差异 = 0 时**静默**（不写库、不告警、不打 ERROR）；
  2. 人为制造差异时**立即检出**并写 `reconciliation_diff`（`diff_type='identity_mismatch'`）；
  3. 差异能被定位到**具体用户 ID 与差值金额**（不是只说「有差异」）；
  4. 恢复数据后巡检回到静默，`reconciliation_diff` 里那条历史行**不被自动删除**（保留痕迹）；
  5. 巡检**只读**：跑完 `balance_flow` 与 `user_balance` 一个字节都没变（对比跑前跑后的 `CHECKSUM TABLE`）。
- **失败长什么样**：巡检报「0 差异」但手工改的数据明明不平 → 不过（SQL 写错，比如把 `frozen_cents` 忘了加）；或巡检自动把差异修平了 → 不过（掩盖问题）。

### `6-A6` 三方一致 ⛔ 挂账

- **怎么验**：取同一笔真实充值，把三个地方的金额抄下来：① App 余额页；② PC A-07 该用户流水与余额；③ 微信/支付宝商户后台该笔交易。逐笔对照。
- **证据形式**：三处截图 + 一张三方对照表（单号 / App 金额 / PC 金额 / 渠道金额 / 时间）；SQL 两条。
- **通过标准**：
  1. **三方金额一致**（精确到分）；
  2. **可逐笔对照**：任取 App 上的一笔充值，能在 PC 和渠道后台各找到同一条（靠 `order_no` 与 `channel_txn_id`）；
  3. 时间差在合理范围（渠道付款时间与本地 `paid_at` 一致）；
  4. 渠道手续费**不体现在用户余额里**（用户充 1 元，余额就是 1.00，手续费是平台承担或另有约定——**这条要在 §十 第 3 项定清楚**）。
- **失败长什么样**：App 与 PC 一致但与渠道后台差一笔 → 不过（有掉单没补）；或渠道后台有 2 笔、App 只显示 1 笔 → 不过（漏单）。
- **挂账条件**：同 `6-A1`，且需要渠道后台的查询权限。

### `6-A7` 渠道失败可读

- **怎么验**：分三种失败各走一次：① 用户在渠道页**主动取消**（Mock 模拟 `fail`）② 渠道返回**支付失败** ③ 渠道**超时无响应**。
- **证据形式**：三种情况的 App 截图（含错误文案与重试入口）+ 单号 + SQL：`SELECT status,amount_cents FROM recharge_record WHERE order_no=?` + 余额前后对比 + `docs/acceptance/seg6/6-A7-failure.txt`。
- **通过标准**：
  1. App 显示**可读的失败原因**（不是「未知错误」、不是错误码数字、不是堆栈）；
  2. 有**重试入口**（保留所选金额与渠道，一键重试或用同一 Idempotency-Key 重新下单）；
  3. **不产生成功流水**（`balance_flow` 无该单的 `recharge` 行）；
  4. **不占配额**（单日额度恢复到失败前——因为 `failed`/`closed` 不计入 B3 的 SQL）；
  5. 失败单在充值记录里可见且状态正确（「支付失败」/「已取消」）；
  6. **失败时显示单号**（方便客服定位）。
- **失败长什么样**：失败后余额没变但额度被扣了 → 不过（配额统计把 `pending` 和 `failed` 混了）；或页面停在 loading → 不过（没有失败态）；或文案直接抛错误码 → 不过。

---

## 五、产出清单（交付时逐项勾）

### 5.1 数据库（建 1 张表，共 13 字段）

| 版本 | 表 | 说明 |
|---|---|---|
| `V600` | `recharge_record` | 基线 **11 字段**（含 id，技术路线 L1419）+ **本段补 2**（`expire_at` / `channel_prepay_id`）= **13 字段**；`channel` 注释补 `mock`；索引 4 个：`uk_recharge_order_no` / `uk_recharge_channel_txn` / `idx_recharge_user` / `idx_recharge_status_expire` |

**不建的表（写在这里防返工）**：

| 表 | 为什么不建 |
|---|---|
| `payment_channel` | 全仓只在总纲出现过一次，不是真实设计；渠道参数走 `sys_config`（§零之二 第 2 项） |
| `reconciliation_diff` | 段 5 已建（`V507`），本段只写 |

**不改的表**：`balance_flow`（只新增 `recharge` 用法）、`user_balance`（只由流水驱动更新）、`payment_record`（**一行都不写**）、`orders`（充值不做订单）。

### 5.2 接口（8 个：新增 7 + 复用 1）

| # | 方法 + 路径 | 用途 | 归属 | 幂等 |
|---|---|---|---|---|
| 1 | `POST /app/wallet/recharges` | 充值下单 | B1 | `Idempotency-Key` + Redis 24h |
| 2 | `GET /app/wallet/recharges/{orderNo}` | 单笔查询（含现查渠道） | B1 | 只读 |
| 3 | `GET /app/wallet/recharges?cursor=` | 充值记录列表 | B1 | 只读 |
| 4 | `POST /pub/pay/callback/wechat` | 微信异步通知 | C1 | 靠 DB 唯一键 + CAS |
| 5 | `POST /pub/pay/callback/alipay` | 支付宝异步通知 | C2 | 同上 |
| 6 | `POST /admin/recharges/{orderNo}/sync` | **人工触发单笔查询补单**（`6-A3` 的人工兜底） | D1 | CAS |
| 7 | `PUT /admin/reconciliation/diffs/{id}` | 差异处理（标记 `handled` + 备注） | D4 | 状态 CAS |
| 8 | `GET /admin/reconciliation/diffs?date=` | 差异列表（**复用，段 5/技术路线已有，不改**） | D4 | 只读 |

> 第 1~3 个是 App 端；第 4~5 个是免鉴权回调（前缀 `/pub/pay/callback/`）；第 6~8 个是管理端（默认标 `@OperationLog`）。
> 第 6、7 两个是本段新增的管理端接口：第 7 个供 A-09「渠道对账」Tab 调用（B4）；第 6 个（人工补单）**只做接口不做页面**——它是 `6-A3` 的人工兜底，用 Swagger 或 curl 打即可。

### 5.3 APP 页面（1 页）

| 页 | 文件 | 路由 | 设计稿 | 四态 |
|---|---|---|---|---|
| 充值页（第 36 页） | `features/trade/pages/RechargePage.tsx` | `app/(tabs)/wallet/recharge.tsx` | `design/ui/36-recharge.html`（420 行，**齐全**） | 正常/加载/空/错误 + 校验态 + 支付中态 |

**PC 页面：新增 0 页，改造 1 页**（总纲「PC 28 页」口径不变）。

| 页 | 文件 | 改造内容 | 设计稿 |
|---|---|---|---|
| A-09 对账报表 | `src/views/biz/finance/reconcile/index.vue` | 加「渠道对账」Tab（B4）；**Tab1「恒等式巡检」原样保留** | `design/admin/08-reconcile.html`（只有恒等式巡检，渠道对账 Tab 由本段自定规格，见 B4） |

### 5.4 脚本、配置与文档

| 项 | 内容 |
|---|---|
| `sys_config` 配置键 | 21 个（§三 A3 第 5 条），加密字段 `ENC(...)` 包裹 |
| Flyway 迁移 | `V600__recharge_record.sql` +（若需）`V600_1__fix_recharge_record.sql` |
| `task_queue` 新 handler | 4 个：`recharge_pending_query` / `recharge_close` / `recharge_reconcile_download` / `recharge_reconcile_compare`；另投递 1 个 `recharge_success_notify`（**只投递不消费**，留给段 7） |
| 定时任务 | 每小时 pending 单兜底扫描；每日 02:00 对账下载；每日 03:00 恒等式巡检 |
| 错误码 | 交易域 `405013`~`405020`（8 个，含 `405020` 未实名不可充值） |
| 审计动作码 | `RECHARGE_SYNC` / `RECHARGE_CLOSE` / `RECONCILE_RUN` / `RECONCILE_HANDLE`（4 个） |
| i18n | `trade.recharge.*` 约 18 个 key（`zh-Hans`），缺译登记 `.i18n-pending.json` |
| 验收证据 | `docs/acceptance/seg6/`：`A1-schema.txt` / `A3-config.md` / `A4-enums.md` / `C4-idempotent.txt` / `6-A2` / `6-A3-recover.txt` / `6-A4-reconcile.txt` / `6-A5-identity.txt` / `6-A7-failure.txt` / `E2-e2e.txt` / `挂账.md` |

---

## 六、已知风险与出口

| # | 风险 | 概率 | 影响 | 出口 |
|---|---|---|---|---|
| R1 | **商户号迟迟不下来** | **高**（1~2 周审核，且要营业执照与对公账户） | `6-A1` / `6-A4`（真实部分）/ `6-A6` 三条验不了 | 已按 §零之二 第 7 项挂账；Mock 通道验完四条；本段**不因此阻塞段 7**（总纲 §5.7 已定「可延后」） |
| R2 | **渠道 SDK 在 Expo SDK 54 下不兼容** | 中高 | 无法在真机拉起微信/支付宝 | B2「卡住怎么办」已定出口：抽象 `payment/invoke.ts`，Mock 走页面内模拟；真实 SDK 接入记为独立基础设施任务 |
| R3 | **没有可公网访问的回调域名** | 高（本地开发环境） | 真实渠道收不到回调 | 段 6 全部用 Mock 验；真实回调靠内网穿透（如 ngrok）或等服务器就绪；不影响 4 条可验锚点 |
| R4 | **密钥与证书管理出错** | 中 | 泄漏 = 资金风险 | 全在 `sys_config` + Jasypt；证书只存路径不进仓库；E3 的证据里含「grep 零命中」检查 |
| R5 | **渠道文档与沙箱行为不一致** | 高（微信 APIv3 与沙箱差异大） | 解析器按沙箱写完，上生产要重改 | C1/C2 已定「以生产规则实现」；沙箱差异记 §十一 |
| R6 | **段 5 未施工 → `reconciliation_diff` 不存在** | 中 | 差异无处落库，`6-A4` 顺延 | 开工前置第 5 项已拦；段 5 未交付时把 `6-A4` 记为顺延（不是挂账），写进 §十 |
| R7 | **段 1 的 `biz_id` 缺陷**（手工充值存单号进 BIGINT） | **已存在** | 不影响段 6（段 6 用自己的 `biz_id`），但段 1 的手工充值长期来看会踩幂等坑 | 记 §九 第 3 项，**本段不修**；段 1 收口时一并处理 |
| R8 | **晚到回调的补偿单逻辑出错** | 中 | 用户付了钱但单已关 → 余额永久不到 | A2 第 4 条已定补偿方案；D2 第 2 条要求关单前先查渠道；两处都做就不容易漏 |
| R9 | **Jasypt 只处理配置文件，DB 里的密文读不到** | 高（这是个真实坑） | 密钥读出来是乱码 | A3 第 6 条已写明：DB 密文要自己调 `StringEncryptor` 解密 |
| R10 | **渠道费率导致「用户充 1 元、平台实收 0.994 元」** | 必然 | 对账时按用户金额还是实收金额比？ | ✅ **已定（2026-11-02）：手续费平台承担**。对账口径按「用户支付金额」比；手续费单独记账（平台成本），**不进用户余额、不写用户流水** |
| R11 | **对账单留存 3 年的存储成本** | 低 | OSS 费用 | 账单文件是 CSV/zip，体量小；按《技术路线》§9.5「留存 3 年」执行 |
| R12 | **生产误开 Mock 通道** | 中（人为） | 出现「假到账」——用户付了 0 元，余额加了 100 | A3 第 3 条已定「不许静默回退」；再加一条：**启动时若 `pay.mock.enabled=true` 且 `spring.profiles.active=prod`，直接启动失败**，不许带着开关跑起来 |
| R13 | **改 A-09 与段 5「不许改 A-09」的禁令冲突** | 已发生（口径冲突） | 施工方两边都读，不知道听谁的 | 已在 §九 第 7 项登记：段 5 那句加例外（段 5 施工时仍不许改；段 6 按总纲 §5.7 加 Tab2）。**B4 只加 Tab，Tab1 一字不改**，冲突面已压到最小 |

---

## 七、明确不做（本段不许碰，碰了就是超范围）

| # | 不做 | 理由 | 归属 |
|---|---|---|---|
| 1 | 微信**分账** | 平台余额制下不需要分账（钱先进平台） | 不做 |
| 2 | **自动打款到卡** | 提现是人工出款（段 5 已定） | 不做 |
| 3 | **提现审核** | 提现属段 5 | 段 5 |
| 4 | 改 `balance_flow` 的写入规则与 `income`/`freeze`/`unfreeze`/`withdraw` 四个语义 | 段 5 §八 已承诺不改 | 不做 |
| 5 | **改 A-09 的 Tab1「恒等式巡检」，或为对账另开一个新 PC 页** | §零之二 第 3 项：只加 Tab2，Tab1 原样；不新增页 | 不做（Tab2 除外） |
| 6 | 建 `payment_channel` 表 | §零之二 第 2 项 | 不做 |
| 7 | 往 `payment_record` 写行 | §零之二 第 5 项 | 不做 |
| 8 | **渠道侧用户主动退款** | 退款走段 2 的余额体系（钱没出过平台） | 段 2 |
| 9 | 重复支付时调用渠道 `refund()` | 只实现「自动原路退回」这一个用途，且**只在 `channel_only` 重复支付场景触发** | 本段最小实现 |
| 10 | 发票开具 | 充值属预付款，消费后才产生发票 | 段 8 |
| 11 | **A-26 日志中心 / 死信页面** | 属段 7 | 段 7 |
| 12 | 四语言文案 | 段 6 只做 `zh-Hans`（与段 4 一致） | 段 8 |
| 13 | 真实账单的两个 parser 完整实现 | 无商户号拿不到格式，写成骨架 + TODO | 商户号下发后 |
| 14 | 充值优惠/赠送（充 100 送 20 之类） | 运营功能，本段无 | 不做（要有也是段 7 之后） |
| 15 | 充值记录导出（用户侧） | 流水已可查，导出无需求 | 不做 |

---

## 八、留给下一段的接口（我保证什么 / 我不保证什么）

### 八.1 我保证（段 7~8 可以直接依赖）

1. **充值通道可插拔**：加第三个渠道只需实现 `PaymentGateway` + 注册一个 `PayChannel` 枚举值 + 加 `sys_config` 键，**不改入账链路**。
2. **`recharge_record` 结构稳定**（13 字段），状态机 4 态 6 条迁移；段 7 做统计可直接按 `status` 聚合。
3. **`reconciliation_diff` 有真实数据**（至少 Mock 账单的对账结果），段 7 做治理页面时不用造数据。
4. **`task_queue` 新增 4 个 handler 范式**：段 7 的定时任务照这个写（投递是状态、领取 CAS、有租约回收）。
5. **恒等式巡检可复用**：`finance_identity_check` 段 7 可以直接跑，不用重写。
6. **单号 `RC` 可用**：`BizNoGenerator` 段 2 已交付，本段只是加了一个前缀值。
7. **错误码 `405013`~`405020` 已登记**（8 个），段 7 从 `405021` 起编。
8. **金额一律整数分**，渠道报文解析后的元转分用 `BigDecimal`，不引入浮点。
9. **充值成功事件已投递**（`task_type='recharge_success_notify'`），段 7 只需写消费者。
10. **A-09 上已有「渠道对账」Tab**：段 7 做治理时可以直接用它查看平台与渠道的差异，不需要再造一个对账页；「标记已处理」的动作已带审计（`RECONCILE_HANDLE`）。

### 八.2 我不保证（后续段可能推翻或补做）

| 不保证 | 最晚什么时候解决 |
|---|---|
| **真实渠道联调**（`6-A1` / `6-A4` 真实部分 / `6-A6`） | 商户号下发后，**最晚段 8 上架前**（没有真实支付不能上架） |
| **渠道侧退款能力** | 若将来允许「充值后退款到卡」，需段 2 与段 6 一起改 |
| **发票开具** | 段 8 |
| **A-26 死信页面** | 段 7 |
| **告警通道**（恒等式差异、验签失败、对账差异的主动推送） | 段 7（本段只写日志 + 红标接口） |
| **账单留存 3 年的归档与清理策略** | 上线前（属运维策略，技术侧只需保证文件不覆盖） |
| **充值页四语言与 RTL 真机验证** | 段 8 |
| **渠道 SDK 的原生集成**（Expo 下） | 若 R2 成立，作为独立基础设施任务，最晚段 8 前 |
| **未实名/未成年人充值限制的完整规则** | 本段只按实名校验结果分档限额，更细规则（监护人同意等）段 8 合规收口时定 |
| **iOS 端支付验证** | 段 8（本段只验 Android） |

---

## 九、回写到哪些文档（本段结束必须完成，缺一条算未收口）

> 按 `docs/BASELINE.md` §4 的「四件事」办：**落款 / 登记指纹 / `docs/README.md` 加索引 / `CHANGELOG.md` 说明**。只做一件等于没做。
> **✅ 状态（2026-11-02）：第 1~9 行已全部落地，基线 v1.32，两分支已推送；第 10 行（设计稿）待负责人决定改不改稿。**
> 落地内容见 `docs/BASELINE.md` §7.36 与 `docs/CHANGELOG.md` 同日条目；下表「状态」列以此为准。

| # | 文档 | 改什么 | 为什么必须改 |
|---|---|---|---|
| 1 | **《开发计划总纲》§5.7 + §11.2** ✅ 已完成（v1.32） | ①数据库清单三张表 → 「`recharge_record`（本段建，13 字段）；渠道参数走 `sys_config` 不建表；`reconciliation_diff` 段 5 已建，本段只写」；②**「差异写 `reconciliation_diff` 并在 PC A-09 可见」这句保留不改**（它是本段 B4 的依据），只补一句「可见的方式 = A-09 新增「渠道对账」Tab，Tab1 恒等式巡检不动」；③接口行「App 侧充值 / 回调接口 + 既有 `recharge_order` 查询」→「8 个接口（新增 7 + 复用 1），契约见第 6 段工作流 §五 5.2」；④§11.2 清单第 6 行状态改「已产出」；⑤补一句「`6-A1` / `6-A4`（真实部分）/ `6-A6` 需商户号，可挂账」 | 不改，施工方会照 `payment_channel` / `recharge_order` 建表，并去改 A-09 |
| 2 | **《技术路线》** ✅ 已完成（v1.32） | ①**L1460 那句注作废**：「`payment_record` 在余额制下仅记录充值的渠道支付」→ 改为「`payment_record` 只记**订单**的余额支付与退款；充值的渠道记录在 `recharge_record`」；②§9.1 的 `prepay(Order order, ...)` 签名加注「充值场景传 `RechargeOrder` 视图，不传订单实体」；③§9.4 补「晚到回调走补偿单，不原地改状态」；④§9.5 补「对账差异落 `reconciliation_diff`，字段映射见第 6 段工作流 §三 D4」；⑤§5.5 `recharge_record` 补 2 字段（`expire_at` / `channel_prepay_id`）+ `channel` 注释补 `mock`；⑥§4.8.2 补 **5 个**接口（3 个 App 充值 + 2 个回调）并把 `/app/wallet/recharge` 改正为 `/recharges`，§4.8.4 补 **2 个**管理端接口 | L1460 与段 2 的实际用法矛盾，两段各按一句施工必写重 |
| 3 | **《第 1 段工作流》§三 D4** ✅ 已完成（v1.32） | 补一条实测缺陷：「`balance_flow.biz_id` 是 `BIGINT`，手工充值往里存 `VARCHAR(32)` 业务单号存不进去；且 `biz_id` 为 `NULL` 时 `uk_flow_biz` 不拦多行 NULL，幂等失效。处置：手工充值的 `biz_id` 改为「该笔手工充值流的自增占位」或引入手工充值单；**段 6 的真实充值不受影响**（它用 `recharge_record.id`）」 | 段 1 若不改，手工充值第二次就会被唯一键拦或幂等失效 |
| 4 | **《接口规范》§4.8.4 + 鉴权矩阵** ✅ 已完成（v1.32） | ①新增 §7.3.1「段 6 的 8 个接口」简表（路径 / 鉴权 / 幂等 / 错误码）；**字段级契约留在本文档 §五 5.2 与 §三 B1/C1/C2/D4**，接口规范不重复（其 §7.3 明文「本规范只规定长什么样，不管有哪些」）；②**新增回调七条约束**：只 POST / 免 JWT 靠验签 / IP 白名单 / 64KB / 限频 / 5 分钟时间窗 / **响应体不是 `R<T>`** / 靠 DB 唯一键而非 Redis 去重 | 现在接口规范里**没有**任何第三方回调的鉴权与响应约定 |
| 5 | **《错误码规范》+《审计与日志规范》** ✅ 已完成（v1.32） | ①错误码交易域补 **8 个**（`405013` 渠道未启用 / `405014` 金额超出单笔区间 / `405015` 充值单状态非法 / `405016` 超出单日限额 / `405017` 回调验签失败 / `405018` 对账差异待处理 / `405019` 渠道下单失败 / `405020` 未实名不可充值）；②审计动作码补 4 个（`RECHARGE_SYNC` / `RECHARGE_CLOSE` / `RECONCILE_RUN` / `RECONCILE_HANDLE`）；③敏感字段清单补 `recharge_record.raw_payload`（L4 禁入日志） | 错误码已用到 `405027`（段 5 占用），不登记会撞号；`raw_payload` 含渠道报文，必须进禁入清单 |
| 6 | **《数据表规范》附录 A 第 17 行** ✅ 已完成（v1.32） | `recharge_record` 字段数 11 → **13**（补 `expire_at` / `channel_prepay_id`）；备注「段 6 建，含 `mock` 渠道」 | 附录 A 是表清单唯一权威，字段数不对施工时会少建 |
| 7 | **《第 5 段工作流》§三 A1**（条件触发） ✅ 已完成（v1.32） | 若 `reconciliation_diff` 建表时**没有去重唯一键**，补记一条：「缺 `(bill_date, channel, channel_txn_id, diff_type)` 唯一键，段 6 的比对逻辑改为先查后插兜底；建议后续迁移补唯一键」 | 没有唯一键，同一账单重跑会堆重复差异行 |
| 8 | **`docs/README.md`** ✅ 已完成（v1.32） | 加本文档索引（一行：段 6 工作流，含 5 组 19 任务 / 7 锚点（3 挂账）/ 1 表 / 8 接口 / 改造 A-09 1 页） | 索引是入口 |
| 9 | **`docs/CHANGELOG.md` + `docs/BASELINE.md`** ✅ 已完成（v1.32） | CHANGELOG 新增一条「2026-11-02 · 新增《第 6 段工作流 v1.0》」，写明 9 处冲突与 **18 项裁决**、**3 条锚点挂账**；BASELINE 基线 v1.31 → v1.32（资产 34 → 35），登记指纹 | 基线不做等于文档没有版本 |
| 10 | **设计稿 `design/ui/36-recharge.html`（需负责人决定改不改稿）** ⏸ **未做，等负责人决定** | 稿里两句门槛值已作废：① `.amount-hint` 的「单笔最低 10 元，单日累计上限以实名校验结果为准」→ 应改为「单笔最低 0.1 元，单日累计上限 5000 元」；② `.custom-amount input` 的 placeholder「输入充值金额，单笔 10 元起」→ 同上。**另外稿里 3 列 × 6 档（100/200/500/1000/2000/其他）在 0.1 元下限下可保留**，但建议补一档低额（如 10 元） | 稿与实现不一致时，验收「视觉与设计稿一致」这条永远过不了；不改稿就必须在本文档写明「以本文档为准」 |

> **为什么这些必须回写**：段 4/段 5 的教训一样——本文档与上游不一致的地方，施工方会**照着上游**施工（因为上游是「规范」，本文档是「计划」）。第 1、2 两项不改，段 6 一开工就会建错表、写重表。

---

## 十、待负责人拍板（本段开工前或施工中必须有人回答，不许「假设已定」）

| # | 事项 | 影响 | 最晚什么时候 |
|---|---|---|---|
| 1 | **支付商户号（微信 + 支付宝）** | `6-A1` / `6-A4`（真实）/ `6-A6` 三条锚点 | 段 6 收口前（可挂账）；**段 8 上架前必须有** |
| 2 | ~~回调域名与 HTTPS 证书~~ ✅ **已定（2026-11-02）：先保留配置接口** | 回调地址 = `sys_config.pay.callback_base_url` + 固定路径；**域名未定可留空**，Mock 通道不依赖它；真实上线只改这一个值 | 已定，见 §零之二 第 18 项 |
| 3 | ~~渠道手续费由谁承担~~ ✅ **已定（2026-11-02）：平台承担** | 用户充 1 元余额就是 1.00 元；对账口径按「用户支付金额」比；手续费不进用户余额、不写用户流水 | 已定，见 §零之二 第 13 项 |
| 4 | ~~单笔最低 / 单日上限~~ ✅ **已定（2026-11-02）：单笔 0.1~5000 元，单日累计 5000 元** | `recharge.min_cents=10` / `max_amount_cents=500000` / `max_daily_cents=500000`；**设计稿「单笔最低 10 元」作废，稿要改**（§九 第 10 项） | 已定，见 §零之二 第 14/15 项 |
| 5 | ~~未实名用户的充值额度~~ ✅ **已定（2026-11-02）：额度 0 = 完全不可充值** | 拦截而非限额，新错误码 `405020`，App 引导去实名 | 已定，见 §三 B3 第 4 条 |
| 6 | ~~充值单超时时长~~ ✅ **已定（2026-11-02）：30 分钟** | `recharge.expire_minutes=30`（上线值） | 已定 |
| 7 | **微信与支付宝是否都要做** | 若只做一个，另一个的实现与配置可省，接口不变 | 段 6 开工前 |
| 8 | **对账单留存 3 年是否照办** | 存储策略 | 上线前 |
| 9 | **充值发票何时开、开什么**（预付款 vs 消费后） | 设计稿须知第 4 条已写「消费后开具」，是否最终口径 | 段 8 合规收口前 |
| 10 | **充值成功通知要不要发给用户**（App 内通知 / 短信） | 本段只投递事件不消费（§八.1 第 9 条） | 段 7 开工前 |

---

## 十一、实际完成与偏差（施工后回写，不删原文）

> 施工完成后在这里追加，**不改上面的内容**。每条写：计划 vs 实际、偏差原因、是否影响下一段。

| # | 计划 | 实际 | 偏差原因 | 影响 |
|---|---|---|---|---|
| | | | | |

---

## 附：本文档的生成方式与已知局限

- **生成日期**：2026-11-02。负责人同日拍板 **18 项**裁决（§零之二：12 项首轮 + 6 项追加）。
- **依据的实测**：仓库 HEAD `b874997`（分支 `UI设计`，基线 v1.31）；工作区干净；`design/ui/36-recharge.html` 420 行逐行读过。
- **读过的文档**：总纲 §3.1 / §4.2 / §5.7 / §6.1~§6.3 / §7.5 / §11.1 / §11.2 / §十；技术路线 §2.2 / §4.8.2 / §4.8.4 / §5.5（`recharge_record` / `payment_record` / `balance_flow` / `user_balance` 的 DDL）/ §5.11.4（`reconciliation_diff` 的 DDL）/ §9.1~§9.7；数据表规范 §3.11 / §4.1 / §八 / 附录 A；接口规范 §4.8.4 相关行 / §6.3 / 附录 A 组 5；错误码规范 §3.x（交易域 `405001`~`405027`）；审计与日志规范 §3.2；段 1 工作流 §三 D 组（账务内核，含两条恒等式口径）/ §八；段 2 工作流 §零之二（第 2 项 `task_queue`、第 5 项 `payment_record`）/ §三 G 组；段 5 工作流 §零~§二 / §三 A1 / §三 E0 / §四 / §八 / §九；PC 后台开发计划 §2.3 / §八。
- **读过的设计稿**：`design/ui/36-recharge.html`（全文 420 行，含设计说明注释块）。
- **已知局限**：
  1. **微信 / 支付宝的接口细节（验签头字段名、回调报文结构、账单文件格式）没有实测**——本机网络无法访问官方文档（`web_fetch` 对 `pay.weixin.qq.com` / `opendocs.alipay.com` 返回非公网地址）。本文档凡涉及具体报文格式处，一律写「以官方文档为准」，**不猜测字段名**。这三块的实现风险已列 §六 R5。
  2. **`reconciliation_diff` 是否真的没有唯一键，未实测**——段 5 还没施工，表还不存在。本文档按「可能没有」处理（§九 第 7 项），施工时实测后要么删掉这条、要么补索引。
  3. **段 1 的 `biz_id` 缺陷是**读文档 + 读 DDL **推断出来的**，没有跑过代码验证（服务端还没开工）。推断依据：`balance_flow.biz_id BIGINT`（技术路线 L1407）vs 段 1 工作流 L714「`biz_id` 用生成的内部单号」（单号是 `VARCHAR(32)`）。段 1 施工时若已解决，本条可作废。
  4. **21 个 `sys_config` 键的具体名字**是按现有命名习惯拟的，可能与段 1 已实现的键名冲突，开工前置第 7 项要求实测核对。
  5. **§九 的 10 行回写：第 1~9 行已于同日完成**（基线 v1.32，`UI设计` + `docs` 两分支已推送，35/35 指纹一致、117 链接 0 死链、桌面镜像 47 文件同步）；**第 10 行（设计稿门槛值）未做**——`design/ui/` 不在本次授权范围，等负责人决定改不改稿。

