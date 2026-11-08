# 第 5 段工作流：案件中心 + 律师工作台 + 全局搜索

> **上位文档**：《[开发计划总纲](法泽FAZE_开发计划总纲_v1.0.md)》[§5.6](法泽FAZE_开发计划总纲_v1.0.md)（本段边界与锚点）、§4.1 / §4.2（页面归属）、§10（待拍板）。
> **位置**：本段是段 5 的**唯一施工文档**。从第一行读到最后一行即可施工，不需要翻别的文档（技术路线除外）。
> **本段规模**：APP **13 页** / PC **4 页**（A-10、A-13、A-16、A-19）/ 建表 **8 张**（7 张有页面 + `reconciliation_diff` 只写不展示）/ 接口 **41 个**（App 23 + PC 18）/ 锚点 **8 条** / 任务 **8 组 37 个**。
> **写作依据**：仓库 HEAD `f45187d`（分支 `UI设计`），2026-11-01 23:0x 实测；17 屏设计稿逐屏读过（调研记录见本文档 §附）。

---

## ⚠️ 开工前必读：本段与上游文档有 12 处冲突，全部在 §零之二 裁决完毕

段 5 是三份上游文档的**口径第一次正面碰撞**：总纲 §5.6 写于 2026-10-30、技术路线表清单写于 V2.2/V2.3、设计稿画于 2026-10-29。三者不一致的地方，**以本文档 §零之二 的裁决为准**；本文档已按裁决后的口径写死，上游回写动作列在 §九。

**最容易被做错的三件事：**

1. **结算不碰钱。** A-13「律师结算」是**结算批次记录**，唯一动钱的链路是提现（A-10）。设计稿 A-13 那句「确认后金额入账，不可撤销」**不实现**——实现成真的动账，就会出现两条链路重复给律师打款。
2. **送达送的是平台自己的文书**，不是法院文书转达。设计稿 22-service 里的「报送人民法院」「《电子送达地址确认书》」「人民法院可调取回执」**不实现**。
3. **`payment_record` 永远只有「支付 / 退款」两种行。** 平台服务费不写记录，页面直接显示 `orders.platform_fee_cents` 的金额。

### 这三处必须回写，不是本文档自己改完就算

| # | 文档 | 改什么 |
|---|---|---|
| 1 | 总纲 §5.6 | 表清单 8 张 → 7 张、删 `5-A9`、`5-A1` 措辞、`5-A2` 补操作人、`5-A4` 补订单号、`DISPUTED`/`SETTLED` 移段 7 |
| 2 | 技术路线 §4.8.4 / §4.8.2 / §9.6.3 / §5.7 | 旧表名、4 个结算接口、提现规则、`legal_case` 结构 |
| 3 | 数据表规范附录 A | 51 → 53 张、四处字段补登 |

完整 7 项见 **§九**。

---

## 零、开工前必须知道的事（2026-11-01 实测）

### 零.1 本段的真实起点：段 0 ~ 段 4 的施工一次都没跑

| 实测项 | 结果 |
|---|---|
| `services/` 目录 | **不存在**（服务端一行代码都没有） |
| `apps/mobile-app/app/` | 只有 `index.tsx` / `_layout.tsx` / `+not-found.tsx` **3 个文件** |
| `apps/mobile-app/shared/` | 12 个文件（`AppCard` / `AppToast` / `Screen` / `theme/tokens.ts` 等设计系统骨架） |
| `git log` | 全部 `docs:` / `chore:` / `feat(admin)`，**没有一条业务代码提交** |
| `docs/acceptance/` | 只有 `seg1/A3-mp-probe.md` 一份 |
| 设计稿 | APP 71 屏 + PC 28 屏，**齐全** |
| 文档 | 总纲 + 技术路线 v2.3 + 5 份规范 + 段 0~4 工作流，**齐全** |

**结论**：段 5 工作流与段 1~4 同性质，是**纯计划文档**。§二 的开工前置检查里，凡涉及「上游已交付」的条目都是**预测性要求**，不是已实测现状——施工前必须逐条复核。

### 零.2 上游承诺了什么（段 5 可以直接依赖，不许重造）

| 上游 | 承诺 | 段 5 怎么用 |
|---|---|---|
| 段 0 | 可安装包、登录、401 回登录页、四语言渲染、豆包流式 | 本段 13 页直接套 |
| 段 1 | `balance_flow` 账务模型 + `deduct()` 幂等扣费引擎 + `lawyer_balance` 表 + `sys_config` + 审计 AOP | 结算引擎**只读** `balance_flow`；提现走 freeze / withdraw / unfreeze 三种 `biz_type` |
| 段 1 | 余额查询「以流水为真相」 | 66-income 的金额口径与它同源 |
| 段 1 | `lawyer` 表已建、入驻审核通过后有记录 | 服务设置的三个价格 + `region` 直接读写 |
| 段 2 | `orders` 表（**已含 `platform_fee_cents` / `lawyer_income_cents`**）+ 8 态状态机 + `order_status_log` | 结算按 `COMPLETED` 订单算收入；服务费金额从订单读，**不重算** |
| 段 2 | IM 会话（`conversation` / `conversation_message`）+ `GET /app/workbench/summary` | 客户咨询列表复用会话表，不新建消息表 |
| 段 2 | 律师工作台首页 + 律师订单管理 2 页（宫格入口已存在） | 本段 5 页是被宫格跳转的目标页 |
| 段 3 | `LawSearchService.search()` + `ArticleNoValidator` + `GET /pub/law-items` | 案例库 `law_refs` 录入时用 `ArticleNoValidator` 校验条号；案例详情法条引用块按 `law_item` 渲染 |
| 段 4 | `document` 表 + PDF 已在 OSS + `PdfRenderer` / `FileRecordService` 可复用 | **送达对象 = `document` 的 PDF**；批量生成送达文书照 `DOC_GENERATE` handler 写 |

### 零.3 已经定了的（本段不再讨论）

- RuoYi 3.9.2 官方发行版（SB 4.0.3）；MP 用 `mybatis-plus-spring-boot4-starter:3.5.18.1` + `mybatis-plus-jsqlparser:3.5.18.1`（两个都要）
- 金额一律整数分（`BIGINT`），接口传分，前端负责展示转换；**禁止浮点运算**
- 案件状态迁移**由服务端校验**，客户端只发意图、不发状态
- 管理端写接口默认标 `@OperationLog`；审计进库、日志进文件
- 提现审核、结算确认**必须**审计：`WITHDRAW_APPROVE` / `WITHDRAW_REJECT` / `SETTLEMENT_CONFIRM`
- 多语言四语言；**段 5 只需 `zh-Hans` + `ug-Arab-CN`**（段 8 补全四语言，`.i18n-pending.json` 登记缺译）
- 迁移版本号：段 5 = **`V500` ~ `V599`**
- 错误码：案件域 `07`（`407xxx` / `507xxx`）、交易域 `05`（`405xxx`）、内容域 `09`（`409xxx`）
- 表名前缀规则、四类表公共字段、`COLLATE` 全按《数据表规范》
- 接口 URL 前缀、9 个请求头、`R<T>`、三种分页场景、Long 型 ID 序列化为字符串，全按《接口规范》

### 零.4 本段六条「不许」（写在这里防走样）

1. **不许**在结算里改任何余额、写任何流水。`balance_flow` 只在两处被动：收入入账（`income`）、提现链路（`freeze` / `withdraw` / `unfreeze`）。
2. **不许**给 `payment_record` 写服务费行。
3. **不许**在送达模块里出现「人民法院」「报送」「电子送达地址确认书」——只做平台自有文书送达。
4. **不许**为 AI 转律师传递任何 AI 对话内容。段 3 的跳转保持原样，本段不加参数、不做上下文。
5. **不许**建 `lawyer_service_config` 表（并入 `lawyer`）；**不许**把 `lawyer_income_settlement` 当收入真相（真相永远是 `balance_flow`）。
6. **不许**改 A-09 对账报表页（`reconciliation_diff` 只建表写数据，不做页面、不改段 1 的页面）。
   > **段 6 例外（2026-11-02 定）**：段 6 按总纲 §5.7 原文「差异在 PC A-09 可见」，可在 A-09 上**新增一个「渠道对账」Tab**；**Tab1「恒等式巡检」仍原样不动**。本段（段 5）施工时仍按本条执行，例外只对段 6 生效。详见《[法泽FAZE_第6段工作流_v1.0.md](法泽FAZE_第6段工作流_v1.0.md)》§三 B4。

---

## 零之二、负责人 2026-11-01 拍板的 18 项（逐项落到哪一节）

> 这 18 项是本次冲突的**唯一裁决依据**。本文档正文已按裁决写死；施工时若发现正文与本节矛盾，**以本节为准并回改正文**。

| # | 事项 | 裁决 | 落到哪 |
|---|---|---|---|
| 1 | 结算 vs 提现 | `balance_flow` 是唯一资金真相；A-13 结算**只做批次记录**，不碰钱、不转账 | §三 E 组、§四 `5-A4`、§五 5.1 |
| 2 | 送达范围 | 只做**平台自有文书**送达；法院转达不做；「电子送达范围」仍挂 §十 | §三 C 组、§四 `5-A3`、§七 |
| 3 | 表总数 | 51 → **53**（新增 `lawyer_income_settlement`、`case_delivery`）；`lawyer_service_config` 并入 `lawyer` | §五 5.1、§九 第 3 项 |
| 4 | 最低提现 | **0.1 元（10 分）**，做成 `sys_config` 可配项，上线前由负责人调整 | §三 F 组、§十 第 3 项 |
| 5 | 提现手续费 | **0.6%**，保留；计算到分，不足 1 分记 0（所以 10 分提现时手续费为 0） | §三 F 组 |
| 6 | AI 转律师 | 段 3 跳转保持原样；本段**不做上下文转交**；`5-A9` **移除**（段 5 锚点 9 → 8 条，全项目 79 → 78） | §四 顶部说明、§七 第 9 项 |
| 7 | 申诉页 | 移到**段 7**；`DISPUTED` / `SETTLED` 两态**不在本段做** | §七 第 1 项、§八.2、§九 第 1 项 |
| 8 | `payment_record` | **不写服务费行**；PC 订单详情显示 `orders.platform_fee_cents` 金额 | §三 B 组 B5、§六 R6 |
| 9 | `reconciliation_diff` | 建表 + 写入差异，**不做页面**，不改 A-09（**段 6 例外：段 6 在其上加「渠道对账」Tab**，见 §零.4 第 6 条注） | §三 H 组 H1、§七 第 5 项 |
| 10 | 结算回补 | 写幂等回补脚本：扫 `status=completed` 但无 `income` 流水的订单，逐笔补 `balance_flow` + `lawyer_balance` | §三 E 组 E2 |
| 11 | 立案驳回 | `legal_case.status` **加 `rejected`**；设计稿 A-19 的「已驳回」有据 | §三 A 组 A2、§四 `5-A1` |
| 12 | 案件状态 | 按设计稿 4 态 + `rejected`：`filed`(待立案审核) / `accepted`(立案中) / `in_progress`(审理中) / `closed`(已结案) / `rejected`(已驳回)；**删 `waiting_judgment`** | §三 A 组 A2 |
| 13 | 案件类型 vs 案例分类 | **两套独立**：案件 5 类（民事/刑事/行政/劳动仲裁/家事）；案例库 3 类（`civil` / `administrative` / `criminal`）。不强行统一 | §三 A 组 A1、G 组 G1 |
| 14 | 表单快照 | `legal_case` **加 `form_snapshot JSON`**（立案四步的原始表单） | §三 A 组 A1、§四 `5-A1` |
| 15 | 送达记录 | 新增 `case_delivery` 表；送达方式三选一（平台电子送达 / 短信通知 / 邮寄） | §三 C 组、§五 5.1 |
| 16 | 服务设置字段 | `lawyer` **加 4 个布尔字段**（三类服务上下架 + 自动接单）+ 价格区间校验（20 ~ 2000 元）；不建新表 | §三 D 组 D4 |
| 17 | 单号格式 | 按《数据表规范》§3.11 建议：`<前缀2位><yyyyMMdd8位><序号6位>`，大写无分隔符。案件 `CS`、提现 `WD`、结算 `JS`、送达回执 `SD`、案例 `CL`。**设计稿里的横杠格式作废** | §三 A 组 A3 |
| 18 | 操作人与订单号 | `5-A2` 时间轴**增加「操作人」显示**；`66-income` 收入明细**增加订单号**（可逐笔追到订单） | §四 `5-A2` / `5-A4` |

**另有 4 项已确认但不阻塞**：

| 事项 | 处理 |
|---|---|
| 支付密码错误策略 | 段 1 已定，本段不碰 |
| A-02 看板口径 | 段 7，本段不碰 |
| 微信/支付宝小程序 | 不做（AGENTS.md §1） |
| 提现收款方式是否持久绑定 | **持久绑定**：`lawyer` 表加 `withdraw_bank_name` / `withdraw_bank_account`（脱敏展示尾号），提现时引用，不每次填 |

---

## 一、本段目标与出口

**定位**：双角色彻底闭合。律师从「只能接单」变成「能管理全部工作、能拿到钱」；用户从「能咨询」变成「能立案、能跟进度、能查案例」。

做完这一段，平台可以真实运营的第二阶段成立：**律师能挣到钱并取出，用户的案件能流转，内容能沉淀成案例库**。

### 一.1 出口（进段 6 的条件）

1. `5-A1` ~ `5-A8` **8 条全过**，每条都有可复现的证据文件（`docs/acceptance/seg5/`）。
2. 案件状态机、提现状态机的**全部合法/非法迁移**有单测覆盖；非法迁移被服务端拒（不是被前端拦住）。
3. **账务恒等式成立**：任取一位律师，`lawyer_balance.available_cents` = 该律师 `balance_flow` 中 `income` + `unfreeze` + `withdraw` + `freeze` 的净和；差异 = 0。
4. **结算与提现不重复付钱**：走完一次「结算确认」后，`lawyer_balance` 与 `balance_flow` 的数值**一个字节都没变**（这是 `5-A4` 的反向验证）。
5. 四语言：本段涉及的 `case` / `content` / `workbench` / `trade` / `lawyer` 五个 namespace 的 `zh-Hans` + `ug-Arab-CN` key 齐全，RTL 下无布局错乱（`letterSpacing` = 0、无手动反转）。
6. `npm run lint` 与 `mvn -q verify` 全绿；ArchUnit 断言（管理端写接口必须有 `@OperationLog`）通过。
7. 上游回写（§九 共 **11 行 / 8 组**）全部完成，文档基线出新版本。**这 11 行已在 v1.31 提前做完**，收口时只需复核一遍是否还有新的漂移。

### 一.2 反向定义（什么不算出口）

- **「页面画出来了」不算出口**：13 页每页四态（正常/加载/空/错误）都要处理，接口要真连通。
- **「数据能存进库」不算出口**：状态迁移要有服务端校验，非法迁移必须被拒。
- **「A-13 页面能点确认」不算出口**：确认后余额不变才是对的，变了就是错的。
- **「案例库录进去了」不算出口**：`law_refs` 里的条号要经 `ArticleNoValidator` 校验，四语言齐全才允许上线。
- **「全局搜索能返回结果」不算出口**：四类**分域**返回、各自可跳转、空结果有空态。

---

## 二、开工前置检查（逐条勾，不通过不开工）

> 打勾前必须给出证据（命令输出 / 文件路径 / 截图）。凭印象打勾 = 没做。
> 第 1~4 项是**上游交付的复核**；第 5~12 项是**本段自己要先补的资产**。

| # | 检查项 | 通过标准 | 不通过怎么办 |
|---|---|---|---|
| 1 | **段 0 已收口** | `docs/acceptance/seg0/` 有 8 条锚点证据；能装包登录 | 停。段 5 所有页面都跑在段 0 的壳里 |
| 2 | **段 1 已收口** | `balance_flow` / `lawyer_balance` / `lawyer` / `sys_config` / `audit` 五样可用；`docs/acceptance/seg1/` 有证据 | 停。结算引擎没有地基 |
| 3 | **段 2 已收口** | `orders` 8 态 + `order_status_log` + IM 可用；**`orders` 表有 `platform_fee_cents` / `lawyer_income_cents`** | 停。没有这两个字段，结算要重算，口径会漂 |
| 4 | **段 4 已收口** | `document` 有 PDF、`file_record` 有记录、`DOC_GENERATE` handler 可复用 | 停。送达没有对象可送 |
| 5 | **本段 7 个缺接口的契约先冻结** | 在《接口规范》或本文档 §五 5.2 里写明方法/路径/请求体/响应体/错误码 | —— 本段自补，见 §三 A5 |
| 6 | **表迁移版本号已占位** | `V500` ~ `V507` 空号无人占用；`flyway_schema_history` 里段 4 用到的最大号 < `V500` | 段号冲突时按《数据表规范》§8.1 顺延，不许复用号 |
| 7 | **`sys_config` 键已定** | 至少 5 个键：`platform.fee_rate`（0.15）、`withdraw.min_amount_cents`（10）、`withdraw.fee_rate`（0.006）、`settlement.freeze_days`（7）、`lawyer.price.min/max_cents`（2000/200000） | 键名冲突查《数据表规范》§6 命名规则 |
| 8 | **错误码已补登** | 案件域 `407005`~`407008`、交易域 `405023`~`405027`、内容域 `409xxx` 三条（见 §九 第 6 项） | —— 本段自补 |
| 9 | **审计动作码已补登** | `WITHDRAW_APPROVE` / `WITHDRAW_REJECT` / `SETTLEMENT_CONFIRM` / `CASE_AUDIT` / `CASE_PROGRESS_CORRECT` / `DELIVERY_SEND` 六个 | 查《审计与日志规范》§3.2 清单 |
| 10 | **i18n 五个 namespace 已建** | `case` / `content` / `workbench` / `trade` / `lawyer` 五个 json 在 `zh-Hans` 与 `ug-Arab-CN` 下都存在 | 查《i18n 规范》§4.3 映射表 |
| 11 | **PC 4 页的缺失规格已自定** | A-13 / A-16 / A-19 三页设计稿只画了列表，**录入表单、详情 Tab、审核弹窗全部没画**。规格由 §三 各任务写死，不再等设计 | **这是本段最大的隐性工作量**，不要以为「照设计稿实现」就够 |
| 12 | **`_walkthrough` 走查文件已知位置** | `20-filing-steps.html`、`19-22-states.html` 在 `design/ui/_walkthrough/`，不在 `design/ui/` | 找文件时别以为丢了 |

### 第 11 项展开：设计稿没画、由本段自定规格的清单

| 页面 | 设计稿有什么 | 设计稿**没有**什么 | 本段自定在哪 |
|---|---|---|---|
| A-13 律师结算 | 列表 8 列 + 3 统计卡 + 查询栏 | 生成结算单弹窗、明细弹窗、确认二次确认 | §三 E5 |
| A-16 案例库 | 列表 8 列 + 筛选 + 批量下架 | 录入表单、4 语言切换界面、法条引用录入控件 | §三 G1 |
| A-19 案件管理 | 列表 8 列 + 状态 chips | 详情页、材料/进度/送达三个 Tab、立案审核弹窗、推进节点弹窗 | §三 B2 / B4 / C1 |
| A-10 提现审核 | 列表 8 列 + 4 统计卡 | 审核弹窗、凭证上传控件、驳回理由弹窗 | §三 F2 |
| APP 22-service | 完整（含签收框） | 空态/加载态/错误态 | §三 C2（按 §四 四态要求补） |
| APP 66-income | 完整（含提现面板） | 校验错误态、余额不足态 | §三 F1 |

---

## 三、任务分组（8 组 / 37 个任务）

> **组内连续推进**，组间设检查点。每组末尾的「组检查点」不过，不进下一组。
> 每个任务五段式：**做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办**。
> 任务编号 `A1` ~ `H3`，与 §五 产出清单、§四 锚点可以互相追溯。

### A 组：地基（5 个任务）

> 这一组不产出任何页面，但决定了后面 7 组的表结构、状态机、单号与跨域服务骨架。
> **A 组不通过，不要开始 B~G。**

#### A1 · 建 8 张表（`V500` ~ `V507`）

- **做什么**：用 Flyway 在 `V500`~`V507` 建本段的 8 张表，并对 3 张既有表补字段。
- **怎么做**：
  1. **新建 8 张**（DDL 全文写在迁移文件里，不贴本文档）：
     | 版本 | 表 | 说明 |
     |---|---|---|
     | `V500` | `legal_case` | 案件主表，14 字段基线 + **3 个新字段**（`form_snapshot` / `court_case_no` / `reject_reason`）= **17 字段**；`status` 改 5 态 |
     | `V501` | `case_progress` | 进度节点，只追加；**8 字段基线 + `node_code`**（节点编码，用于幂等与去重） |
     | `V502` | `case_delivery` | **新表**（DDL 已在《技术路线》§5.7 末尾补登）：`case_id` / `document_id` / `user_id` / `method`（`platform` / `sms_pickup` / `mail`）/ `sent_at` / `signed_at` / `receipt_no` / `sign_device` / `ip` / `status`，共 **13 字段** |
     | `V503` | `case_library` | 案例主表，19 字段基线 + **2 个新字段**（`category` 细分分类 / `court_case_no` 法院案号）= **21 字段**；`case_no` 存**平台案例编号 `CL`**（对外唯一标识），法院案号在 `court_case_no`（**APP 不展示**） |
     | `V504` | `case_library_translation` | 案例译文，13 字段基线 |
     | `V505` | `withdraw_record` | 提现单，15 字段基线 + **7 个新字段**：`withdraw_no`（业务单号，`WD` 开头，唯一索引）/ `fee_cents` / `actual_cents` / `voucher_file_id` / `bank_name` / `bank_account_tail` → 共 **21 字段** |
     | `V506` | `lawyer_income_settlement` | **新表**（DDL 已在《技术路线》§5.5 末尾补登）：`settlement_no` / `lawyer_id` / `period`(yyyy-MM) / `order_count` / `income_cents` / `fee_cents` / `settle_cents` / `status`(pending/settled/rejected) / `settled_by` / `settled_at` / `remark` |
     | `V507` | `reconciliation_diff` | 对账差错，14 字段基线；本段**只写不展示**（段 6 补 A-09 Tab2 展示）。**⚠️ 本表没有去重唯一键**（只有 `idx_diff_date` / `idx_diff_status`），建议补 `UNIQUE KEY uk_diff_day_channel_txn_type (bill_date, channel, channel_txn_id, diff_type)`；不补则段 6 的比对逻辑要「先查后插」兜底（见《第 6 段工作流》§九 第 7 项） |
  2. **补字段 3 张**（用 `V5xx_1__fix_xxx.sql` 修正迁移，不改已进 main 的迁移）：
     - `legal_case`：加 `form_snapshot JSON`、`case_no` 唯一索引（已有）、状态值改 5 个
     - `lawyer`：加 `service_text_on TINYINT` / `service_phone_on TINYINT` / `service_doc_on TINYINT` / `auto_accept TINYINT` / `withdraw_bank_name VARCHAR(50)` / `withdraw_bank_account VARCHAR(100)`
     - `orders`：**不改**（`platform_fee_cents` / `lawyer_income_cents` 段 2 已有）
  3. **所有表必须过《数据表规范》§十 的 12 问检查清单**：表名、字段名、索引名、四类表公共字段、`COLLATE`、注释、`deleted_at`（A/C 类表）。
- **产出**：8 个 `V5xx__*.sql` + 2 个修正迁移；`docs/acceptance/seg5/A1-schema.txt`（`SHOW CREATE TABLE` 输出）。
- **算做完**：`flyway migrate` 在空库上从 `V0` 跑到最新无错；8 张表 `SHOW CREATE TABLE` 与本文档字段清单逐字段一致；`lawyer` 表 6 个新字段存在；附录 A 已回写（§九 第 3 项）。
- **卡住怎么办**：字段数与附录 A 不一致时，**以本文档 §A1 清单为准**并回改附录 A（不要两处各写一版）。`V500` 号被占 → 按 §8.1 顺延到 `V510` 起，同步改本文档。

#### A2 · 案件状态机（5 态 / 9 条迁移）

- **做什么**：定义 `legal_case.status` 状态机，服务端校验全部迁移，非法迁移拒绝。
- **怎么做**：
  1. 枚举（`CaseStatus`）：
     | 值 | 中文 | 何时进入 |
     |---|---|---|
     | `filed` | 待立案审核 | 用户提交立案材料（`POST /app/cases`）后的初始态 |
     | `accepted` | 立案中 | PC 立案审核通过（材料合格，平台代为提交法院） |
     | `in_progress` | 审理中 | 法院受理后，律师登记法院案号并推进 |
     | `closed` | 已结案 | 律师推进到结案节点 |
     | `rejected` | 已驳回 | PC 立案审核驳回（带 `reject_reason`） |
  2. 迁移表（**9 条**，服务端唯一依据）：
     | from → to | 触发方 | 权限 |
     |---|---|---|
     | `filed → accepted` | 管理员（PC A-19 审核通过） | admin |
     | `filed → rejected` | 管理员（PC A-19 审核驳回） | admin |
     | `rejected → filed` | 用户（补正后重新提交） | 案件所有者 |
     | `accepted → in_progress` | 律师（登记法院案号） | 承办律师 |
     | `in_progress → closed` | 律师（推进结案） | 承办律师 |
     | `accepted → closed` | 律师（特殊情况直接结案） | 承办律师 |
     | `in_progress → rejected` | **不允许** | — |
     | `closed → *` | **不允许**（终态） | — |
     | `rejected → accepted` | **不允许**（必须重新提交） | — |
  3. **每次迁移必须写 `case_progress` 一行**（`node_code` 用枚举，便于去重）；同时写 `order_status_log` 风格的迁移流水？——**不建 `case_status_log`**，`case_progress` 就是它的日志（理由：进度节点本身就是迁移记录，再建一张表会双写）。
  4. 迁移实现：`CaseStateMachine.transit(caseId, from, to, operatorId, operatorRole)`，内部用 `UPDATE legal_case SET status=? WHERE id=? AND status=?`（**CAS**，影响行数 0 即拒绝）。**禁止**先查再改。
- **产出**：`modules/caseflow/enums/CaseStatus.java` + `CaseStateMachine.java` + `CaseStateMachineTest.java`（9 条迁移 + 6 条非法迁移）。
- **算做完**：单测覆盖 5 态之间**全部 20 种组合**（5×4），合法 5 条通过、非法 15 条被拒；并发两个请求同时审核同一案件，只有一个成功。
- **卡住怎么办**：若上游要求「材料补正」单独成态，别加第 6 个状态——补正是 `in_progress` 的一个进度节点 + 一条 `case_progress`（`node_code='supplement'`），状态仍是 `in_progress`。

#### A3 · 单号生成器（5 种）

- **做什么**：实现统一的业务单号生成，5 种前缀，落库前生成、唯一索引兜底、冲突重试。
- **怎么做**：
  1. 格式：`<前缀2位><yyyyMMdd8位><序号6位>`，共 16 位，大写字母 + 数字，**无分隔符**。
     | 业务 | 前缀 | 示例 |
     |---|---|---|
     | 案件 | `CS` | `CS20261101000007` |
     | 提现 | `WD` | `WD20261101000003` |
     | 结算 | `JS` | `JS20261101000024` |
     | 送达回执 | `SD` | `SD20261101000042` |
     | 案例 | `CL` | `CL20261101000031` |
  2. 序号方案：**当日递增**，用 `Redis INCR` 键 `seq:<prefix>:<yyyyMMdd>`，TTL 2 天。Redis 不可用时降级为「毫秒时间戳 6 位 + 随机 2 位」，但**必须**仍满足 16 位长度且唯一索引能兜住。
  3. 冲突重试：插入时捕获 `DuplicateKeyException`，**换号重试最多 3 次**，3 次都撞就抛 `500001`（内部错误，不暴露细节）。
  4. 生成时机：**同一事务内、落库前**生成，不靠 DB 自增回填。
- **产出**：`common/util/BizNoGenerator.java` + `BizNoGeneratorTest.java`。
- **算做完**：并发生成 10,000 个单号无重复；断网（Redis 不可用）时降级路径仍能生成合法单号；单号正则 `^[A-Z]{2}\d{14}$` 全通过。
- **卡住怎么办**：设计稿里 `WD-2026-0091` / `JS-2026-10-0024` / `FAZE-CASE-2026-0087` 都带横杠，**以 §零之二 第 17 项为准**（无分隔符）。前端展示时也不许自己加横杠。

#### A4 · 枚举与常量（5 套）

- **做什么**：把本段所有枚举一次定死，避免各组各写一套。
- **怎么做**：
  1. **案件类型（5 类）**：`civil` 民事 / `criminal` 刑事 / `administrative` 行政 / `labor` 劳动仲裁 / `family` 家事。
  2. **案例分类（3 类，独立）**：`civil` / `administrative` / `criminal`（案例库不收录劳动仲裁与家事，见 G1）。
  3. **送达方式（3 种）**：`platform` 平台内电子送达（推荐）/ `sms_pickup` 短信通知到院自取 / `mail` 邮寄纸质。
  4. **提现状态（4 种）**：`applying` 待审核 / `approved` 已通过 / `rejected` 已驳回 / `paid` 已出款。
  5. **结算状态（3 种）**：`pending` 待结算 / `settled` 已结算 / `rejected` 已驳回。
  6. 每条枚举必须带中文 `label`，且 label **走 i18n key**（`case.status.filed` 等），不在 Java 里写中文。
- **产出**：`modules/caseflow/enums/*`、`modules/trade/enums/WithdrawStatus.java`、`modules/trade/enums/SettlementStatus.java`；`docs/acceptance/seg5/A4-enums.md`（枚举 × 中文 × i18n key 三列对照）。
- **算做完**：全部枚举无重复值、无遗漏值；非法字符串反序列化抛 `400001`（参数错误）而不是静默转 null。
- **卡住怎么办**：DB 里存的是 `VARCHAR(20)` 还是 `TINYINT`？**存字符串**（`VARCHAR`），便于 DBA 直接看数；后端用枚举，MyBatis-Plus 用 `IEnum` 映射。

#### A5 · 跨域服务骨架 + 8 个新增接口的契约冻结

- **做什么**：5 个 Service 接口 + 8 个新增 C 端接口的 DTO 与路径一次定死，各组照着实现。
- **怎么做**：
  1. 5 个服务（`modules/caseflow/service/`、`modules/trade/service/`、`modules/content/service/`）：
     | 服务 | 职责 | 不做什么 |
     |---|---|---|
     | `CaseService` | 立案、审核、进度、查询 | 不碰送达 |
     | `DeliveryService` | 发起送达、签收、回执 | 不碰案件状态 |
     | `SettlementService` | 结算批次聚合与确认 | **不碰余额、不写流水** |
     | `WithdrawService` | 提现申请、审核、打款 | 不碰结算 |
     | `CaseLibraryService` | 案例 CRUD、四语言、上线门禁 | 不碰案件 |
  2. **8 个新增 C 端接口**（另 15 个上游已定义，见 §五 5.2）：
     | 方法 + 路径 | 用途 | 主要表 |
     |---|---|---|
     | `GET /app/workbench/documents?status=` | 律师文书列表（进行中/已完成） | `document` |
     | `GET /app/workbench/income?range=week\|month\|total` | 收入概览三档 | `balance_flow`、`orders` |
     | `GET /app/workbench/income/flows?cursor=&range=` | 收入明细（**含订单号**） | `balance_flow`、`orders` |
     | `PUT /app/workbench/profile` | 律师资料编辑（简介/专长/律所） | `lawyer` |
     | `GET /app/workbench/withdraw-account` | 收款账户（脱敏） | `lawyer` |
     | `PUT /app/workbench/withdraw-account` | 绑定/更换收款账户 | `lawyer` |
     | `POST /app/cases/{id}/progress` | 律师推进进度节点 | `case_progress`、`legal_case` |
     | `PUT /app/case-deliveries/{id}/method` | 选择送达方式 | `case_delivery` |
  3. 每个接口必须按《接口规范》写清：9 个请求头、归属校验字段（`user_id` / `lawyer_id`）、分页场景（C 端列表用 `list`，明细用 `cursor`）、幂等（提现与审核必须幂等）、错误码。
- **产出**：5 个 Service 接口 + 8 个 Controller 骨架（返回 `R.ok()` 空实现）+ 8 个 DTO；契约写入 §五 5.2 并回写《接口规范》。
- **算做完**：Swagger 能打开并列出 8 个新接口；用 `curl` 打过去返回 `401`（未登录）而不是 `404`；归属校验字段已在 SQL 条件里（不是 Controller 里 `if`）。
- **卡住怎么办**：若发现某个接口其实上游已有（如 `/app/workbench/conversations` 段 2 已做），**删掉本段的重复定义**，不要建第二份。

**A 组检查点**：8 张表建完 + 状态机单测过 + 5 种单号可生成 + 枚举对照表 + 8 个接口骨架可访问。不通过不进 B 组。

### B 组：案件中心（6 个任务）

#### B1 · 在线立案（APP `20-filing` 四步表单）

- **做什么**：用户四步填完立案材料并提交，生成 `filed` 状态的 `legal_case` + 完整表单快照。
- **怎么做**：
  1. 页面：`features/case/pages/FilingPage.tsx`，四步一次只显示一步（设计稿是四个 `hidden` 步骤卡）：
     | 步 | 字段（**设计稿原文**） |
     |---|---|
     | 1 案件类型 | 三选一卡「民事 / 行政 / 刑事」+「具体纠纷类型」单选 7 项（民间借贷 / 买卖合同 / 交通事故 / 劳动争议 / 婚姻家事 / 房屋租赁 / 其他）+ 输入框「事发地 / 合同履行地」 |
     | 2 当事人 | 「原告姓名 *」「身份证号 *」（行内校验 18 位）「联系电话 *」（11 位）；「被告信息」下「姓名 / 单位名称」「住址 / 注册地址」 |
     | 3 案情与诉求 | 多行「案情陈述」（`0/2000` 计数）「诉讼请求」；「争议金额」（¥ 前缀 + 元后缀） |
     | 4 证据与提交 | 证据缩略图 +「添加」；「提交前核对」6 行只读；费用说明；「提交材料」 |
  2. **首屏必须有合规说明条**（设计稿原文，逐字照抄）：「本页用于整理立案材料。平台法务完成形式审查后，代为提交至有管辖权的人民法院。平台**不作出受理决定**，是否受理由人民法院裁定；平台仅保证材料格式规范、材料齐备。」
  3. **没有「保存草稿」**（设计稿明确没有），本段也不加。中途退出 = 数据丢失，页面在离开时弹确认。
  4. 提交时：服务端生成 `CS` 单号 → 写 `legal_case`（`status='filed'`、`form_snapshot` = 四步原始 JSON、`evidence_urls` = `file_record` 的 key 数组）→ 写第一条 `case_progress`（`node_code='submitted'`）。
  5. 幂等：`POST /app/cases` 必须带 `Idempotency-Key`，重复提交返回首次结果（不发第二单）。
  6. 四态：正常 / 加载（提交中）/ 空（无）/ 错误（字段校验 + 网络失败）。
- **产出**：`FilingPage.tsx` + `app/(tabs)/case/filing.tsx` 路由壳 + `POST /app/cases` 实现 + 单测。
- **算做完**：四步能走完并提交；重复点「提交材料」只产生 1 条案件；`form_snapshot` 里四步字段齐全且值正确；校验失败时行内提示（不是 `alert`）。
- **卡住怎么办**：证据上传走段 1 的 OSS 直传，**不要**在立案接口里传文件流。

#### B2 · 材料已提交页（`21`）+ PC 立案审核（A-19 详情与审核）

- **做什么**：提交成功页 + PC 端审核立案材料（通过 → `accepted` / 驳回 → `rejected`）。
- **怎么做**：
  1. APP `21-filing-done` 标题**必须是「材料已提交」**，不是「立案成功」。页面元素：状态头卡、案件编号卡（带复制按钮）、信息卡 4 行（案件类型 / 争议金额 / 证据材料 / 提交时间）、后续流程时间轴 4 节点、注意事项 3 条、底部「查看案件进度」「返回首页」。
  2. 编号提示**必须**显示（原文）：「此为平台业务单号，**非人民法院案号**。人民法院立案后将另行编立「(2026) 新 4002 民初 … 号」，两个编号均需留存。」
  3. PC A-19：设计稿**只有列表没画详情页**。本段自定：点「详情」进入**抽屉**（不是新页面，符合 RuoYi 单页范式），抽屉内三个 Tab：
     | Tab | 内容 |
     |---|---|
     | 立案材料 | `form_snapshot` 四个分组的只读渲染 + 证据图片（`file_record` 签名 URL）+ **审核操作区**（通过 / 驳回 + 驳回理由必填） |
     | 进度 | `case_progress` 时间轴（含操作人） |
     | 送达 | `case_delivery` 列表（见 C 组） |
  4. 审核接口 `POST /admin/cases/{id}/audit`，body `{action: approve|reject, reason}`；`reject` 时 `reason` 必填（≤500 字）。**必须**标 `@OperationLog`，动作码 `CASE_AUDIT`；`detail` 存 `before`/`after`。
  5. 驳回后案件进 `rejected`，用户在「我的案件」看到「已驳回」并可重新提交（`rejected → filed`）。
- **产出**：`FilingDonePage.tsx` + `CaseDetailDrawer.vue` + `POST /admin/cases/{id}/audit` + 审计记录。
- **算做完**：APP 页面标题为「材料已提交」且编号提示可见；PC 抽屉三 Tab 都能开；审核通过后 APP「我的案件」状态变「立案中」；驳回后状态「已驳回」且理由可见；`operation_log` 有 `before`/`after`。
- **卡住怎么办**：抽屉在 RuoYi 3.9.2 里用 `el-drawer`，**不许**改成新路由（框架层布局是 `src/layout/`，不许动）。

#### B3 · 我的案件列表（`53`）+ 案件进度页（`54`）

- **做什么**：用户看自己委托的案件列表与时间轴进度。
- **怎么做**：
  1. `53-my-cases`：筛选 chips **4 个**（全部 / 待我处理 / 进行中 / 已结案），卡片字段**只有**：案由 + 平台单号 + 状态 pill + 律师头像/姓名 + 「最近事件 · 月-日」。**没有金额、没有受理法院、没有立案日期**（设计稿明确没有，不要加）。
  2. 状态 pill 映射：`filed`→「审核中」、`accepted`→「立案中」、`in_progress`→「审理中」、`closed`→「已结案」、`rejected`→「已驳回」。
  3. `54-case-progress`：主卡（单号 + 状态 + 信息行：案由 / 受理法院 / 立案日期 / 承办律师）+ 时间轴。**时间轴每节点显示三样**：节点名 + 时间（实际时间或「预计…」「尚未…」）+ 说明；**「人民法院受理并立案」节点额外显示法院案号**；**同时显示操作人**（§零之二 第 18 项，设计稿没画，本段补一行「操作人：张建国律师」）。
  4. 底栏随状态切换（设计稿三态）：正常态「补充材料」「联系律师」；补正态「联系律师」「上传补正材料」；不予受理态「联系律师」「重新提交」。
  5. 页面底部**必须有**说明：「FAZE- 开头的编号是平台业务单号，不是法院案号」。
  6. 四态：正常 / 加载（骨架屏）/ 空（「还没有案件」+「去找律师」）/ 错误（重试）。
- **产出**：`MyCasesPage.tsx` + `CaseProgressPage.tsx` + `GET /app/cases` / `GET /app/cases/{id}` / `GET /app/cases/{id}/progress`。
- **算做完**：列表 5 种状态都能正确显示 pill；时间轴节点顺序与库中 `case_progress.step` 一致；**每节点有操作人**；空态与错误态可达。
- **卡住怎么办**：`case_progress.step` 是 `INT`，插入时用「当前最大 step + 1」，**唯一索引 `(case_id, step)` 兜底**（A1 已建），并发插入冲突时重试。

#### B4 · 律师推进进度（APP + PC 纠错）

- **做什么**：承办律师在 APP 上推进案件节点；管理员在 PC 上纠错。
- **怎么做**：
  1. APP：`54-case-progress` 的律师视角（同一页面，角色判断），底部加「推进节点」按钮 → 弹层选节点（从枚举选，**不许自由输入**）+ 备注 + 可选法院案号（`accepted → in_progress` 时必填）。
  2. `POST /app/cases/{id}/progress`：校验「当前用户是该案承办律师」+「from → to 合法」+ CAS 更新 `legal_case.status` + 插 `case_progress`。
  3. PC A-19 的「推进节点」按钮 = **纠错**用途：`POST /admin/cases/{id}/progress`，可指定任意合法节点，**必须**填纠错原因，审计动作码 `CASE_PROGRESS_CORRECT`。
  4. 节点枚举（`node_code`）至少 8 个：`submitted` 材料已提交 / `review` 法院审查 / `accepted` 受理并立案 / `supplement` 材料补正 / `hearing` 开庭审理 / `judgment` 一审判决 / `effective` 判决生效 / `closed` 结案。
- **产出**：律师视角进度页 + 两个接口 + 单测（律师他人案件被拒）。
- **算做完**：非承办律师调用返回 `407003`（403 无权访问）；推进后用户端（另一个账号）刷新可见新节点且**顺序正确**；PC 纠错必须填原因，不填报 `400001`。
- **卡住怎么办**：若「节点自由文本」需求出现，**拒绝**——自由文本会让时间轴无法排序、无法做 `5-A2` 的顺序验收。

#### B5 · PC 订单详情补服务费显示（跨段改段 2 的页面）

- **做什么**：把段 2 在 `11-order-detail.html` 上写的「服务费结算见段 5」改成**直接显示金额**；**不写 `payment_record` 服务费行**。
- **怎么做**：
  1. 页面「支付记录」保持**2 条**（支付 + 退款），两条的 `channel` 都是 `balance`，退款行金额为负——段 2 已定，本段**不改**。
  2. 在支付记录下方加一行说明（不是表格行）：「平台服务费 ¥9.00（订单金额的 15%，已计入律师收入，结算见律师结算）」，金额读 `orders.platform_fee_cents`。
  3. 删除段 2 留在页面上的那句话「服务费结算见段 5」。
  4. **不新增写表**：`payment_record` 永远只有支付/退款。
- **产出**：`src/views/biz/trade/OrderDetail.vue` 的一处改动 + 截图证据。
- **算做完**：任一订单详情页显示 2 条支付记录 + 1 行服务费说明；`SELECT COUNT(*) FROM payment_record WHERE order_id=X` = 2；`SUM(amount_cents)` = 该订单净收款。
- **卡住怎么办**：如果业务方坚持要「3 条记录」，**回到 §零之二 第 8 项**——已裁决不写，不重新开。

#### B6 · 案件列表（PC A-19 列表部分）

- **做什么**：A-19 的列表页（设计稿已有 8 列），接真数据。
- **怎么做**：
  1. 查询栏：案件号或当事人（`案件号 / 原告 / 被告`）、案件类型下拉（**5 类**：全部/民事/刑事/行政/劳动仲裁/家事）、承办律师。
  2. 工具栏状态 chips：全部 / 待立案审核 / 立案中 / 审理中 / 已结案——**加第 5 个「已驳回」**（设计稿 chips 没有，但 `5-A1` 需要能看到驳回案件）。
  3. 表格 8 列：案件号 / 类型 / 当事人（第二行「被告：X」/「被申请人：X」/「被害人：X」按类型变）/ 承办律师（头像 + 姓名）/ 当前节点 / 立案时间 / 状态 / 操作（详情、推进节点）。
  4. 提示条保留设计稿原文：「案件进度由承办律师在 App 端推进，后台只做立案材料审核与节点纠错」；**但删掉**「电子送达范围待定（见《未完成需备注》）」——本段已裁决只做平台文书送达。
  5. `GET /admin/cases?status=&lawyerId=&type=&kw=`：分页用 `rows` 场景（PC 管理端），`pageNum`/`pageSize`，最大 100。
- **产出**：`src/views/biz/caseflow/CaseList.vue` + `src/api/biz/caseflow/case.js` + 接口实现。
- **算做完**：5 个 chips 都能筛出正确数量；表格 10 行真数据与库一致；分页、排序（`orderBy` 白名单）正常；无「电子送达范围待定」字样。
- **卡住怎么办**：`当前节点` 不在 `legal_case` 上，要按 `case_progress` 取最新一条（`ORDER BY step DESC LIMIT 1`），**不要在 `legal_case` 上加冗余字段**。

**B 组检查点**：`5-A1` 可验（提交 → PC 审核 → APP 看到状态）；`5-A2` 可验（律师推进 → 用户看到时间轴 + 操作人）；B5 的 `payment_record` 仍为 2 条。

### C 组：送达（4 个任务）

> **本组是对设计稿改动最大的一组。** 设计稿 `22-service.html` 画的是「法院文书经平台转达」，含三个法院侧要素：报送人民法院、《电子送达地址确认书》、人民法院可调取回执。按 §零之二 第 2 项，**这三样全部删掉**，只做平台自有文书送达。下面 C2 给出逐句改写对照。

#### C1 · 送达数据模型与发起（PC A-19 送达 Tab）

- **做什么**：管理员从案件详情把一份已生成的文书送达给用户；`case_delivery` 落库。
- **怎么做**：
  1. 前置：`document` 已有 PDF（`pdf_oss_key` 非空、`status='generated'`）。没有 PDF 的文书**不许**发起送达，返回 `407006`。
  2. `POST /admin/cases/{id}/deliveries`，body `{documentId, method}`；服务端：
     - 校验文书属于该案件或该用户（`document.user_id` = `legal_case.user_id`）
     - `method` 只允许 `platform` / `sms_pickup` / `mail`
     - 生成 `SD` 回执号（**送达时就生成，签收时回填时间**，不是签收时才生成）
     - 写 `case_delivery`（`status='pending'`）
  3. 送达动作本身：`platform` = 用户 APP 可见（无需额外动作）；`sms_pickup` = 发短信模板；`mail` = 记一条待寄出（**不做物流对接**）。
  4. 送达后 `case_progress` 插一条 `node_code='delivery'`？——**不插**。送达有独立表和独立页面，不污染案件时间轴。
  5. 审计：动作码 `DELIVERY_SEND`，`detail` 记文书 id + 方式 + 回执号。
- **产出**：`V502` 表 + `DeliveryService` + 接口 + PC A-19 抽屉的「送达」Tab（列表 + 「发起送达」按钮 + 选文书/选方式弹窗——**设计稿没画，本段自定**）。
- **算做完**：选一份有 PDF 的文书能发起送达；`case_delivery` 有 `SD` 回执号且 `status='pending'`；APP 端立刻能看到待签收。
- **卡住怎么办**：若案件还没有任何 `document`，「发起送达」按钮置灰并提示「本案尚无文书，请先在文书中心生成」——**不要**允许空送达。

#### C2 · APP 文书送达页（`22-service`）三选一 + 签收

- **做什么**：用户在 APP 上查看待签收文书、选择送达方式、签收；已签收的可查看回执。
- **怎么做**：
  1. **文案必须逐句改写**（设计稿原文 → 本段实现）：

     | 位置 | 设计稿原文 | 本段改成 |
     |---|---|---|
     | 顶部提示条 | 「下列文书由**伊宁市人民法院**作出，经本平台转达。签收时间将作为送达时间记录，答辩期、举证期、上诉期自送达之日起算。」 | 「下列文书由**平台生成并送达**。签收时间将作为送达时间记录。」 |
     | 送达方式说明 | 「选定后，平台将《电子送达地址确认书》**报送人民法院**。」 | 「选定后，平台按所选方式送达。送达方式可随时变更。」 |
     | 方式 2 | 「短信通知，**到院自取**」 | 「短信通知，**自行查看**」（平台发短信提醒，用户在 APP 内阅读） |
     | 方式 3 | 「邮寄纸质文书：按平台登记的地址邮寄送达，在途约 3–5 日」 | 保留（平台邮寄，不涉及法院） |
     | 同意勾选 | 「本人已阅读并同意《**电子送达地址确认书**》……」 | **整段删除**（无此确认书） |
     | 已签收回执 | 「本回执由平台生成……**人民法院可调取**。该回执不是人民法院盖章的《送达回证》」 | 「本回执由平台生成，记录签收人、签收时间与设备信息，作为平台送达凭证。」 |
     | 诉讼费死线警示 | 「逾期未交诉讼费，按自动撤诉处理……**本平台不代收诉讼费**」 | **整块删除**（平台不送达法院缴费通知） |
  2. 分组：**待签收**（文书名 + 期限提示 + 「签收」按钮）/ **已签收**（文书名 + 回执号 · 签收时间 + 点进预览）。
  3. 签收确认框：标题随文书名变化——「确认签收《受理案件通知书》？」；正文「签收后，系统记录当前时间作为送达时间。」；hint「签收不视为认可文书内容，异议仍可书面提出。签收记录不可撤销。」；按钮「取消」「确认签收」。
  4. 签收接口 `POST /app/case-deliveries/{id}/confirm`：CAS 更新 `status='signed'` + `signed_at=now()` + 记 `sign_device`（设备型号 + OS）与 `ip`；**已签收的再签返回 `407007`**（幂等语义：第一次结果不变，第二次不覆盖时间）。
  5. 方式选择 `PUT /app/case-deliveries/{id}/method`：**只有未签收时能改**；改了写 `method` 字段，不回写历史。
  6. 四态：正常 / 加载（骨架）/ 空（「暂时没有待签收的文书」）/ 错误（重试）。
- **产出**：`CaseDeliveryPage.tsx` + 两个接口 + 签收确认框组件。
- **算做完**：`5-A3` 可验（PC 发起 → APP 待签收出现 → 签收 → `case_delivery` 有 `signed_at`、`receipt_no`、`method`、`sign_device`）；**全页搜索不到「人民法院」「报送」「确认书」三个词**（用脚本 grep 断言）。
- **卡住怎么办**：需要「送达期限/逾期」逻辑时，**只做前端提示**（「已过 N 天」），不在库上加字段；逾期不影响签收能力（可补签）。

#### C3 · 回执与送达凭证

- **做什么**：签收后生成可查看的回执（`SD` 号 + 签收人 + 时间 + 设备）。
- **怎么做**：
  1. 回执**不做 PDF**（V1 不做），只在页面上展示结构化信息 + 可截图。
  2. 回执字段：回执号 / 文书名 / 案由 / 承办律师 / 送达方式 / 送达时间 / 签收时间 / 签收设备 / 「本回执由平台生成」说明。
  3. **不出现「人民法院」**、不出现「送达回证」。
- **产出**：回执区块（嵌在 C2 页面里，不单独成页）。
- **算做完**：签收后能看到回执；回执号符合 `^SD\d{14}$`；设备信息非空；同一文书签两次回执号不变。
- **卡住怎么办**：若要求「回执盖章」——平台无此能力，明确记录到 §八.2。

#### C4 · 送达列表与用户入口

- **做什么**：用户从案件进度页进入送达列表；全局送达列表接口。
- **怎么做**：
  1. `GET /app/case-deliveries?caseId=&status=`：按 `case_delivery.user_id`（冗余一列，避免 join `legal_case`）过滤，游标分页。
  2. 入口：`54-case-progress` 底部操作栏在 `in_progress` 时加「查看送达」；案件详情页也加。
  3. 未读标记：新送达未签收时，案件卡右上角加红点（`5-A3` 的可视化证据）。
- **产出**：`GET /app/case-deliveries` + 两个入口 + 红点。
- **算做完**：用户只能看到自己的送达（越权返回 `407003`）；红点在有未签收时出现、签收后消失。
- **卡住怎么办**：`case_delivery` 的 `user_id` 冗余列如果被反对，改为 join `legal_case`，但**必须在 SQL 条件里带 `user_id`**（不许 Controller 里判断）。

**C 组检查点**：`5-A3` 全流程可验；送达页无任何法院侧表述（grep 断言通过）；签收幂等。

### D 组：律师工作台 5 页（6 个任务）

> 段 2 已交付「工作台首页」「律师订单管理」2 页。本组补剩下 **5 页**：客户咨询、代写文书、收入与提现、服务设置、律师资料。
> 入口都从工作台首页的宫格跳进（段 2 已画宫格 6 项，本段补 5 个目标页）。

#### D1 · 客户咨询列表（`63`）

- **做什么**：律师视角的会话列表，点击进 IM。
- **怎么做**：
  1. 页面：深色导航栏（返回工作台）+ **排序说明一行**（设计稿原文「未读优先，其余按最近消息排序」）+ 会话列表。**没有筛选 Tab、没有搜索框**（设计稿明确没有，不要加）。
  2. 列表项字段（设计稿原文）：头像 + 未读角标 + 姓名 + **订单状态标签**（已付款/未付款）+ 最近消息时间（相对时间）+ 消息摘要 + 未读加粗。
  3. 接口 `GET /app/workbench/conversations`（**上游 §4.8.2 已定义，段 2 未实现**）：按 `lawyer_id` 过滤，游标分页。
  4. 「订单状态标签」需要 join 最新订单——按 `conversation.user_id` + 当前 `lawyer_id` 取最近一笔订单 `status`。**注意**：没有订单时显示「未付款」，不要报错。
  5. 点击 → 段 2 的 IM 聊天页（律师视角，对方是 C 端用户）——**段 2 的 `17-chat` 已知是用户视角占位**，本段不改 IM 页，只在跳转时传 `peerRole=lawyer`。
  6. 四态：正常 / 加载 / 空（「还没有客户咨询」）/ 错误。
- **产出**：`ConsultationsPage.tsx` + `GET /app/workbench/conversations`。
- **算做完**：只看到自己作为律师的会话；未读角标与未读数一致；排序符合说明；空态可达。
- **卡住怎么办**：若 IM 页无法支持律师视角（段 2 的占位），本段**只保证列表与跳转可用**，聊天页的双向校验记到 §八.2「不保证」。

#### D2 · 律师文书管理（`65`）+ 新建 + 发送给客户

- **做什么**：律师看自己代写的文书，从模板新建，发送给客户。
- **怎么做**：
  1. 页面：导航栏 + **顶部一张虚线「新建文书」入口卡**（设计稿明确**没有**右上角按钮）+ 筛选 chips（进行中 / 已完成）+ 列表。
  2. 条目字段：文书类型 · 案由/名称（「民事起诉状 · 房屋租赁合同纠纷」）+ 状态 pill（草稿/待发送/已发送）+ 「委托人 {姓名}」+ 更新时间。
  3. **归属规则（重要）**：`document.user_id` = **委托人（用户）**，`document.lawyer_id` = **律师本人**。律师侧列表按 `lawyer_id` 过滤，用户侧（段 4 的「我的文书」）按 `user_id` 过滤——同一张表两个视角，**不重复存两份**。
     - 这与《接口规范》§6.3「document 全部按 `user_id` 归属」不一致，**按本裁决执行并回写接口规范**（§九 第 4 项）。
  4. 新建 → 跳段 4 的模板列表（`23-doc-templates.html` → `/doc/templates`），生成流程完全复用段 4，**不重写生成器**。
  5. 「发送给客户」：设计稿每条**没有**按钮，只有「待发送」状态；页尾说明写「点进去预览确认后发送」。所以：发送动作在**预览页**里，不在列表上。
  6. 发送实现：写一条 `conversation_message`（`type='doc'`，`biz_id=document.id`）+ 站内通知。**不新建消息表、不新加消息类型枚举之外的字段**（`conversation_message` 段 2 已建）。
  7. 发送后状态 `待发送 → 已发送`；客户可查看阅读状态（读 `conversation_message.read_at`）。
  8. 四态：正常 / 加载 / 空（「还没有代写的文书」）/ 错误。
- **产出**：`LawyerDocsPage.tsx` + `GET /app/workbench/documents` + 发送动作。
- **算做完**：新建 → 生成 → 列表出现「待发送」→ 预览页发送 → 状态变「已发送」→ 客户侧收到一条文书消息。
- **卡住怎么办**：若 `conversation_message` 没有 `type` 字段支持文书，**加值不改结构**（`type` 是 `VARCHAR`）；发消息必须复用段 2 的 WebSocket 推送，不要另起一套。

#### D3 · 收入与提现页（`66`）—— 展示部分

- **做什么**：律师看收入概览、待结算、可提现、明细，并发起提现（提现动作在 F 组）。
- **怎么做**：
  1. 金额三处口径（**必须一致，不许各算各的**）：
     | 页面元素 | 来源 | 口径 |
     |---|---|---|
     | 可提现余额 | `lawyer_balance.available_cents` | 可直接提 |
     | 待结算 | `lawyer_balance.frozen_cents` | 7 天退款期内，`UI 文案「待结算」`（**代码注释注明 = frozen_cents**，全页不出现「冻结」字样，与设计稿一致） |
     | 三档收入（本周/本月/累计） | `balance_flow` 的 `income` 类按 `created_at` 区间汇总 | 不含提现、不含冻结 |
  2. 明细列表：**每行必须有订单号**（§零之二 第 18 项）。行结构：
     - 主行：服务类型 · 客户名（「图文咨询 · 马合木提·艾山」）
     - 副行：`订单 CS…` + 「订单 ¥60.00 · 平台服务费 ¥9.00（15%）」
     - 右侧：`+¥51.00`
     - 提现行：主行「提现到 招商银行（尾号 4821）」+ 副行「时间 · 状态」+ 右侧 `-¥2,000.00`
  3. 规则卡（设计稿 4 条原文保留，但**改两处数字**）：
     - 「平台服务费按订单金额的 15% 计收」——15% 读 `sys_config`，**不写死**
     - 「7 天退款期后自动转为可提现余额」——天数读 `settlement.freeze_days`
     - 「提现 **1 至 3 个工作日**到账」——按设计稿（技术路线的「T+1」作废，见 §九 第 2 项）
     - 「提现账户必须是**本人实名一致的银行卡**」——保留
  4. **数字矛盾的处理**：设计稿余额卡「可提现 8,640 + 待结算 1,200 并列」与面板「可提现 8,640，其中 1,200 仍不可提」冲突。**本段按前者**：`可提现 = available`，`待结算 = frozen`，两者互斥不相含。提现面板提示改成「可提现余额 ¥8,640.00，另有 ¥1,200.00 待结算中的收入 7 天后可提」。
  5. 接口：`GET /app/workbench/income?range=` → `{availableCents, pendingCents, weekCents, monthCents, totalCents}`；`GET /app/workbench/income/flows?cursor=&range=` → 明细列表（含 `orderNo`）。
  6. 四态：正常 / 加载 / 空（「还没有收入，接单后会显示在这里」）/ 错误。
- **产出**：`IncomePage.tsx` + 两个接口。
- **算做完**：`5-A4` 可验——页面金额与 `balance_flow` 汇总**逐笔对得上**（不是只对总额）；明细每条能点出订单号；待结算与可提现不重叠。
- **卡住怎么办**：若历史订单没有 `balance_flow`（段 2 期间完成、段 5 前入账），先跑 E2 回补脚本，否则页面会显示 0。

#### D4 · 服务设置页（`67`）+ `lawyer` 表 4 个开关

- **做什么**：律师开关三类服务、改价、设自动接单、看服务地区。
- **怎么做**：
  1. 三类服务行：服务名 + 价格（「¥60.00 / 24 小时内回复」「¥99.00 / 30 分钟」「¥200.00 / 份」）+ **右侧独立开关**。
     - 开关落库：`lawyer.service_text_on` / `service_phone_on` / `service_doc_on`（A1 已加）
  2. 接单设置 2 行（设计稿**只有这两项**，不要加接单上限）：
     - 「自动接单」→ `lawyer.auto_accept`
     - 「服务地区」→ 只读显示 `lawyer.region`（本平台只覆盖伊犁州，**不允许跨地区**，点开只提示不修改）
  3. 改价面板：只有 ¥ + 数字，**无单位后缀**（设计稿如此，单位显示在行上）；校验 **20 ~ 2,000 元**（`2000 ~ 200000` 分），越界返回 `405006`。
  4. 改价**只影响新订单**：订单下单时已快照 `amount_cents`（段 2 已做），服务端**不需要**做任何历史订单回改。
  5. 下架语义（设计稿原文）：「下架不等于删除。下架后新用户看不到这项服务，但已经付款的订单照常履约，不影响你的评分与结算。」——**订单已付款的不受影响**，这是查询时过滤，不是改数据。
  6. 接口 `PUT /app/workbench/service-settings`（上游已定义）：body 含三个开关 + 三个价格 + `autoAccept`；**部分更新**（没传的不改）。
  7. `5-A6` 要求「改价后用户端律师详情页价格立即变化」——**不许缓存律师详情页价格**（或用 `lawyer:{id}:price` 缓存并主动失效）。
- **产出**：`ServiceSettingsPage.tsx` + `PUT /app/workbench/service-settings` + 律师详情页缓存失效逻辑（改段 2 的页面）。
- **算做完**：`5-A6` 可验（改价 → 用户端详情页立刻变；关掉图文 → 用户无法下单并给出明确提示）；价格 19 元被拒、2001 元被拒、20 元和 2000 元通过。
- **卡住怎么办**：若用户端律师详情页缓存改不动，**至少**保证 `PUT` 后 `GET /app/lawyers/{id}` 返回新价（缓存 TTL ≤ 60 秒），并在 §六 R4 记风险。

#### D5 · 律师资料编辑（`68`）

- **做什么**：律师改自己的公开资料。
- **怎么做**：
  1. 四块（设计稿原文）：
     | 块 | 字段 | 可改 |
     |---|---|---|
     | 头像区 | 头像 + 「更换头像」 | ✅ |
     | 对外展示 | 姓名（**带锁，只读**）、个人简介（≤200 字，带计数） | 简介可改 |
     | 专长领域 | 6 个多选标签（民事纠纷 / 合同纠纷 / 婚姻家事 / 劳动争议 / 刑事辩护 / 继承），副标题「决定谁能在列表里找到你」 | ✅ |
     | 执业信息 | 律所名称（可改）、执业证号（**带锁，只读**） | 律所可改 |
  2. 底部「资质审核状态」行：显示「已认证 · 2026-02-10 通过」+ pill，点击跳段 1 的入驻审核状态页。
  3. 接口 `PUT /app/workbench/profile`：**只允许改** `bio` / `specialties` / `law_firm` / `avatar_url`。姓名、执业证号由服务端**忽略**（不是前端置灰就算）——越权字段直接丢弃并在日志记一条。
  4. 改「专长」是否触发重新审核？——**不触发**（专长是展示标签，不是资质）。但要写进 §六 R5。
- **产出**：`LawyerProfilePage.tsx` + 接口。
- **算做完**：简介超 200 字被拒；专长最多选 6 个；姓名/执业证号即使构造请求也改不了（写单测）。
- **卡住怎么办**：若要求「改律所触发重新审核」，改为：改律所后 `lawyer.audit_status` 保持 `approved`，但写一条 `lawyer_application_log` 待人工复核——**不改状态机**。

#### D6 · 工作台入口联调（跨段改段 2 的页面）

- **做什么**：把段 2 工作台首页宫格的 5 个入口接到本段 5 页。
- **怎么做**：
  1. 宫格 6 项：我的客户 → `63-consultations`；我的订单 → 段 2 的订单管理；代写文书 → `65`；收入提现 → `66`；服务设置 → `67`；律师资料 → `68`。
  2. 段 2 只做了前 2 项，本段补后 4 项 + 客户咨询（共 5 个目标页）。
  3. 权限：5 页都要 `@LawyerOnly`，非律师角色跳回「我的」页并提示。
- **产出**：工作台首页宫格链接改动 + 5 个路由壳文件。
- **算做完**：6 个宫格都能跳；非律师账号访问任一路由被拦截（不是白屏）。
- **卡住怎么办**：若双角色切换（我的页 →「进入律师工作台」）在段 2 没做完，本段**必须补**，否则 5 页都进不去。

**D 组检查点**：`5-A4` / `5-A6` 可验；5 页都能从工作台进入；66 页金额与流水逐笔一致。

### E 组：结算引擎（5 个任务）

> **本组只让 `balance_flow` 动一次钱：律师收入入账。** 结算批次（A-13）**不碰余额、不写流水**。
> 先把资金口径定死，全组按这一个口径写，不再另立。

#### E0 · 资金口径（本组所有任务共用的定义，先看这段）

| 概念 | 字段 | 语义 |
|---|---|---|
| 律师总余额 | `available_cents + frozen_cents` | 律师在这个平台上全部的钱 |
| 可提现 | `lawyer_balance.available_cents` | UI 文案「可提现余额」 |
| 待结算 | `lawyer_balance.frozen_cents` | UI 文案「待结算」，**全页不出现「冻结」字样** |
| 累计收入 | `lawyer_balance.total_income_cents` | 只增不减（提现不减它） |

**`balance_flow` 的口径（与段 1 对齐，本段只补 `biz_type` 的用法）**：

| `biz_type` | `delta_cents` | 谁写 | 说明 |
|---|---|---|---|
| `income` | **+净收入** | E1 | 订单完成后入账，同时 `frozen_until = 完成时间 + 7 天` |
| `unfreeze` | **0** | E3 / F3 | 冻结转可用（转移，总余额不变）；delta=0 但必须写，作为「何时解冻」的证据 |
| `freeze` | **0** | F1 | 提现申请时 可用→冻结（转移，总余额不变） |
| `withdraw` | **−提现金额** | F3 | 出款成功，钱真正离开平台 |

**恒等式（`5-A4` 验的就是这两条）**：

```
① lawyer_balance.available_cents + lawyer_balance.frozen_cents
     == 该律师最后一条 balance_flow 的 balance_after
② lawyer_balance.available_cents + frozen_cents
     == SUM(delta_cents)  （该律师全部流水，delta=0 的行不影响）
```

> `balance_after` 记的是**总余额**（`available + frozen`），不是单记 available。这一点必须写进代码注释，否则后面每个人都会算错。

#### E1 · 律师收入入账（订单完成时）

- **做什么**：订单进入 `COMPLETED` 时，把 `lawyer_income_cents` 记进律师账户，进「待结算」，7 天后转可提现。
- **怎么做**：
  1. **这是改段 2 的代码**：段 2 的 `POST /app/orders/{id}/complete` 在 `COMPLETED` 时只记数字、不写流水。本段在同一事务里追加：
     ```
     ① 读 orders.lawyer_income_cents（段 2 已算好，禁止重算）
     ② 写 balance_flow(owner_type='lawyer', owner_id=lawyer_id, biz_type='income',
                        biz_id=order_id, delta_cents=+lawyer_income_cents,
                        balance_after=总余额, frozen_until=completed_at + 7d)
     ③ CAS 更新 lawyer_balance：frozen_cents += delta, total_income_cents += delta
        （WHERE version = ?，影响行数 0 则重试，最多 3 次）
     ```
  2. **幂等靠唯一键**：`uk_flow_biz (owner_type, owner_id, biz_type, biz_id)` 已存在。重复调用只插一次，靠唯一键冲突捕获后**忽略**（不是报错）。
  3. 冻结天数从 `sys_config.settlement.freeze_days` 读（默认 7），**不写死**。
  4. `balance_after` = 更新后的 `available + frozen`，在同一事务里算出来。
  5. 失败回滚：三条写操作在同一事务，任一步失败整单回滚；**不允许**「订单完成了但没入账」。
- **产出**：`SettlementService.creditIncome(orderId)` + 段 2 `completeOrder` 的一处调用 + 单测。
- **算做完**：完成一单后 `lawyer_balance.frozen_cents` 增加 `lawyer_income_cents`；`balance_flow` 有一条 `income` 且 `frozen_until` = 完成时间 + 7 天；**重复调用 complete 不会重复入账**（`SELECT COUNT(*)` 仍为 1）。
- **卡住怎么办**：若段 2 的 `complete` 里已经有「算 `lawyer_income_cents`」的逻辑，**复用它的结果**，不要重新用 `amount × 15%` 算一遍（费率改过就会不一致）。

#### E2 · 回补脚本（段 2 期间的已完成订单）

- **做什么**：把段 5 上线前已完成、但没有 `income` 流水的订单，逐笔补上入账。
- **怎么做**：
  1. 扫描条件：`orders.status='completed'` **且** `NOT EXISTS (SELECT 1 FROM balance_flow WHERE owner_type='lawyer' AND biz_type='income' AND biz_id=orders.id)`
  2. 逐笔调 `SettlementService.creditIncome(orderId)`——**复用 E1 的同一段逻辑**，不写第二份。
  3. `frozen_until` 用**订单的 `finished_at` + 7 天**（不是脚本执行时间），这样历史订单如果早就过了 7 天，会被 E3 立刻解冻。
  4. **幂等**：跑第二遍时扫描结果为空（因为已有流水）。`uk_flow_biz` 是最后一道兜底。
  5. 脚本形态：`scripts/repair-lawyer-income.sql`（先出**只读的核对 SQL**：列出待回补订单数与金额合计，人工确认后再跑 `POST /admin/settlements/backfill` 接口）。**不许**做成自动执行。
  6. 执行前后各跑一次恒等式校验，把数字记进证据文件。
- **产出**：核对 SQL + `POST /admin/settlements/backfill`（管理员触发，标 `@OperationLog`）+ `docs/acceptance/seg5/E2-backfill.txt`（前后余额与流水数）。
- **算做完**：核对 SQL 跑出的待补订单数，与回补后新增的 `income` 流水数**完全相等**；回补前后总余额差 = 待补订单的 `lawyer_income_cents` 合计；跑第二遍新增 0 条。
- **卡住怎么办**：若历史订单的 `lawyer_income_cents` 是 0（段 2 早期没算），**不要**用现在的费率重算——记录到 §十 第 4 项，人工决定这些订单怎么处理。

#### E3 · 解冻定时任务（7 天后转可提现）

- **做什么**：扫到期的 `income` 流水，把 `frozen_cents` 转成 `available_cents`。
- **怎么做**：
  1. 扫描：`balance_flow WHERE biz_type='income' AND frozen_until <= now() AND unfreezed = ...`——`balance_flow` **没有「已解冻」标记字段**。
     - 解法：用 `NOT EXISTS` 判断是否已有对应的 `unfreeze` 流水（`biz_type='unfreeze' AND biz_id = income.id`）。
  2. 逐笔：写 `unfreeze` 流水（`delta=0`、`biz_id=income.id`、`remark='解冻'`）+ CAS 更新 `lawyer_balance`（`frozen -= income.delta`、`available += income.delta`）。
  3. 调度：`task_queue`（段 2 已建）注册 `LAWYER_UNFREEZE` handler，每 10 分钟跑一次；**不要**用 `@Scheduled` 直连数据库（段 2 的异步任务五条规范要求走任务表）。
  4. 幂等：靠 `uk_flow_biz(owner_type, owner_id, 'unfreeze', income.id)` 唯一键，重复执行只成功一次。
- **产出**：`LAWYER_UNFREEZE` handler + 单测（造 8 天前与 3 天前的两笔收入，只解冻前者）。
- **算做完**：8 天前的收入变成 `available_cents`；3 天前的仍在 `frozen_cents`；重复跑任务不重复解冻；`unfreeze` 流水 `biz_id` 指向原 `income` 流水 id。
- **卡住怎么办**：若 `task_queue` 的 handler 注册机制与段 4 的 `DOC_GENERATE` 不同，**按段 4 的写法照抄**（段 4 已验过）。

#### E4 · 结算批次生成（`lawyer_income_settlement`）

- **做什么**：按「律师 × 账期」生成结算批次，记录订单数、订单收入、服务费、应结金额。
- **怎么做**：
  1. 维度：**一位律师 + 一个账期（`yyyy-MM`）一条**（与设计稿「一行 = 一位律师 + 一个周期」一致）。
  2. 聚合口径（**必须两套来源交叉校验，不许只用一个**）：
     | 列 | 来源 | 口径 |
     |---|---|---|
     | `order_count` | `orders` | 该律师该账期 `status='completed'` 的订单数 |
     | `income_cents`（订单收入） | `orders.amount_cents` | 该律师该账期完成订单金额合计 |
     | `fee_cents`（平台服务费） | `orders.platform_fee_cents` | 同上合计 |
     | `settle_cents`（应结金额） | **`balance_flow` 的 `income` 净额** | 该律师该账期入账的净收入合计 |
  3. **交叉校验**：必须断言 `settle_cents == income_cents − fee_cents`。不成立就**不许写结算单**，抛 `405026` 并把两个数记进错误日志（这是账务口径出问题的最早信号）。
  4. 唯一键：`uk_settlement(lawyer_id, period)`；重复生成返回已有单（`409` → 返回已有数据，不报错）。
  5. 状态：`pending`（生成时）→ `settled`（PC 确认）/ `rejected`（重新生成前先驳回）。
  6. **生成不碰钱**：只写 `lawyer_income_settlement` 一张表。
  7. 批量：`POST /admin/lawyer-settlements/generate` body `{period, lawyerId?}`——不传 `lawyerId` 则生成该账期全部律师（只对有完成订单的律师生成，不生成 0 元单）。
- **产出**：`SettlementService.generate(period, lawyerId?)` + 接口 + 单测。
- **算做完**：任取一位律师一个账期，`settle_cents` == `SUM(orders.lawyer_income_cents)` == `SUM(income 流水)`；`settle_cents + fee_cents == income_cents`；重复生成不产生第二单；0 元单不生成。
- **卡住怎么办**：若 `balance_flow` 与 `orders` 对不上（`settle_cents ≠ income − fee`），**停在这里**——这说明 E1 的入账金额算错了，先修 E1，不要用 `orders` 的数去覆盖。

#### E5 · PC 律师结算页（A-13 四件事）

- **做什么**：列表 + 生成 + 明细 + 确认，**确认后余额一个字节都不变**。
- **怎么做**：
  1. 3 个统计卡（设计稿原文）：「待结算总额」= `SUM(settle_cents) WHERE status='pending'`；「本月已结算」= 本月 `settled` 的合计；「涉及律师」= 待结算单的律师去重数。
  2. 查询栏：律师（姓名/律所）、结算周期下拉、结算状态下拉（全部/待结算/已结算/已驳回）。
  3. 表格 8 列（设计稿原文）：结算单号 + 状态 tag（压在第二行）/ 律师（头像 + 姓名 + 律所）/ 结算周期 / 订单数 / 订单收入 / 平台服务费 / 应结金额 / 操作。
     - **结算单号**：`JS` + 日期 + 序号，**无横杠**（§零之二 第 17 项）
     - 平台服务费显示为负数（`− ¥540.00`），与设计稿一致
  4. 四个动作（**设计稿一个弹窗都没画，本段自定**）：
     | 动作 | 弹窗内容 |
     |---|---|
     | 生成结算单 | 选账期（`yyyy-MM` 下拉，可选最近 12 个月）+ 选范围（全部律师 / 指定律师）→ 生成中态 → 结果「成功 N 条 / 跳过 M 条（已存在）」 |
     | 明细 | 抽屉：该结算单内的**逐单列表**（订单号 / 完成时间 / 订单金额 / 服务费 / 净收入），数据从 `orders` + `balance_flow` 实时查 |
     | 确认结算 | **二次确认框**，文案必须写清：「确认后本批次标记为已结算。**本操作不改动律师余额、不产生资金流水**，实际打款由财务在银行渠道完成。」→ 确认 |
     | 重新生成（已驳回行） | 确认框「重新生成会作废当前批次并以最新数据重算」→ 生成新单 |
     | 导出 / 下载结算单 | V1 **不做 PDF**，按钮导出 CSV（`settlement_no, lawyer, period, order_count, income, fee, settle`） |
  5. 确认接口 `POST /admin/lawyer-settlements/{id}/confirm`：
     - CAS 更新 `status='pending' → 'settled'`，写 `settled_by` / `settled_at`
     - **不写 `balance_flow`、不改 `lawyer_balance`**（这是 §一.1 第 4 条出口的反向验证）
     - 标 `@OperationLog`，动作码 `SETTLEMENT_CONFIRM`
  6. 只读提示条（设计稿原文，**改一句**）：「应结金额 = 订单收入 − 平台服务费；结算需财务在银行渠道完成转账后点确认」→ 后面加「**系统只记录结算状态，不做转账**」。
- **产出**：`src/views/biz/trade/LawyerSettle.vue` + 4 个接口实现 + `docs/acceptance/seg5/E5-no-money-moved.txt`（确认前后 `lawyer_balance` 与 `balance_flow` 的行数与合计对照）。
- **算做完**：`5-A4` 可验（结算页显示的数字与 66-income 页、与 `balance_flow` 汇总**三者一致**）；确认一次后 `lawyer_balance` 与 `balance_flow` **完全不变**（这是必验项，截图 + SQL 输出留证）。
- **卡住怎么办**：若有人要求「确认后律师可提现余额增加」——**回到 §零之二 第 1 项**。那是提现链路的语义，不是结算。

**E 组检查点**：`5-A4` 全过；E5 的「确认不动账」验证留证；E2 回补前后恒等式成立。

### F 组：提现闭环（4 个任务）

> **唯一的资金出口。** 申请 → 冻结 → 审核 → 出款（扣减）或驳回（解冻）。
> 最低提现 **10 分（0.1 元）**、手续费 **0.6%** 向下取整到分（10 分时手续费 0 分）。

#### F1 · 提现申请（APP `66` 的提现面板）

- **做什么**：律师从可提现余额里申请提现，冻结对应金额。
- **怎么做**：
  1. 面板元素（设计稿原文）：金额输入框、「全部提现」、到账账户（脱敏）、账户姓名（与实名一致）、预计到账、`确认提现 ¥X`、关闭。
  2. 校验（**服务端必须重做一遍，前端校验只是体验**）：
     | 规则 | 不通过 |
     |---|---|
     | `amount ≥ sys_config.withdraw.min_amount_cents`（默认 10 分） | `405023` 低于最低提现额 |
     | `amount ≤ lawyer_balance.available_cents` | `405024` 可提现余额不足 |
     | 已绑定收款账户 | `405025` 未绑定收款账户 |
     | 单日提现笔数 ≤ 3（防刷） | `405027` 提现过于频繁 |
  3. 手续费：`fee = floor(amount × 0.006)`，`actual = amount − fee`。**10 分时 fee = 0**，写进页面提示「本次手续费 ¥0.00（不足 1 分按 0 计）」。
  4. 事务内：
     ```
     ① 生成 WD 单号
     ② 写 withdraw_record(status='applying', amount_cents, fee_cents, actual_cents,
                          bank_name, bank_account_tail)
     ③ CAS 更新 lawyer_balance：available -= amount, frozen += amount
     ④ 写 balance_flow(biz_type='freeze', biz_id=withdraw_id, delta_cents=0,
                       balance_after=总余额)   ← 总余额不变，delta=0
     ```
  5. 幂等：`POST /app/withdrawals` 必须带 `Idempotency-Key`，重复提交返回同一张单（不发第二单、不冻第二次）。
  6. 四态：正常 / 加载 / 空（无提现记录）/ 错误（余额不足、低于最低额、未绑卡、单日超限，各自明确文案）。
- **产出**：提现面板 + `POST /app/withdrawals` + 单测（4 条校验 + 幂等）。
- **算做完**：申请 100 元后 `available` 减 100、`frozen` 加 100、总余额不变；`withdraw_record` 有 `fee_cents=60`、`actual_cents=9940`；重复点只产生一张单；10 分提现时 `fee_cents=0`。
- **卡住怎么办**：若「全部提现」按钮的取整逻辑与最低额冲突（余额 < 10 分），按钮置灰并提示「可提现余额低于最低提现额」。

#### F2 · PC 提现审核（A-10）

- **做什么**：财务看提现队列、审核通过/驳回、上传出款凭证。
- **怎么做**：
  1. 4 个统计卡（设计稿原文）：待审笔数 / 待审金额 / 今日已审 / 本月已出款。
  2. 查询：律师（姓名/手机号/执业证号）、提现状态下拉（全部/待审核/已通过/已驳回/已出款）、申请时间。
  3. 表格 8 列（设计稿原文）：提现单号 / 律师（头像 + 姓名 + 脱敏手机）/ 申请金额 / 手续费 / 实际到账 / 收款账户（银行 + 尾号）/ 申请时间 + 状态 tag / 操作。
  4. **审核弹窗（设计稿没画，本段自定）**：
     - 「通过」→ 弹窗含：提现单摘要（只读）+ **出款凭证上传**（必填，走 `POST /app/upload/token` 直传 OSS，写 `file_record`，`biz_type='withdraw'`）+ 出款流水号（选填）+ 确认按钮
     - 「驳回」→ 弹窗含：驳回理由（必填 ≤500 字）+ 确认
     - 已通过/已出款行：「凭证」按钮打开凭证预览（签名 URL）
     - 已驳回行：「驳回理由」按钮显示理由
  5. 接口：
     - `POST /admin/withdrawals/{id}/audit` body `{action: approve|reject, reason?, voucherFileId?}`；`approve` 时 `voucherFileId` **必填**
     - `POST /admin/withdrawals/{id}/voucher` 补传凭证（已通过但漏传时用）
  6. 审计：`WITHDRAW_APPROVE` / `WITHDRAW_REJECT`，`detail` 存 `before`/`after` + 凭证 file id。
  7. 顺序强调（设计稿原文）：「提现为人工出款：**先在银行渠道完成转账，再点「通过」**，并上传出款凭证留档。」——弹窗提示照此写。
- **产出**：`src/views/biz/trade/WithdrawAudit.vue` + 审核弹窗 + 凭证上传 + 3 个接口。
- **算做完**：通过时必须传凭证（不传 `400001`）；驳回必须填理由；驳回后 `lawyer_balance` 的 `frozen` 回到 `available`；`operation_log` 有两条完整审计记录。
- **卡住怎么办**：凭证上传若卡在 OSS 直传，退路是「先通过、后补传」（设计稿的「已通过行有『凭证』按钮」就是这个退路），但**不许**允许永久无凭证的 `paid` 单。

#### F3 · 出款扣减与驳回回滚

- **做什么**：把 `approved` 推进到 `paid`，以及驳回时的解冻回滚。
- **怎么做**：
  1. **通过后出款**（`approved → paid`）：
     ```
     ① CAS：withdraw_record.status='approved' → 'paid'，写 paid_at
     ② CAS 更新 lawyer_balance：frozen -= amount
     ③ 写 balance_flow(biz_type='withdraw', biz_id=withdraw_id,
                       delta_cents=-amount, balance_after=总余额)  ← 总余额真减少
     ```
     注意：**第 ③ 步 delta 是 −amount，不是 −actual**。手续费 0.6% 是渠道扣的，律师拿到 `actual`，但平台账上是全额扣减；手续费记在 `withdraw_record.fee_cents` 上，**不单独写流水**（否则恒等式要多一个科目）。
  2. **驳回**（`applying → rejected`）：
     ```
     ① CAS：status='rejected'，写 reject_reason
     ② CAS 更新 lawyer_balance：frozen -= amount, available += amount
     ③ 写 balance_flow(biz_type='unfreeze', biz_id=withdraw_id,
                       delta_cents=0, balance_after=总余额)  ← 总余额不变
     ```
  3. 两个动作都是 **CAS + 同一事务**，非法状态（如对 `paid` 单再审核）返回 `405025`。
  4. 全流程状态机（4 态 / 5 条迁移）：
     | from → to | 触发 |
     |---|---|
     | `applying → approved` | 财务审核通过 + 传凭证 |
     | `applying → rejected` | 财务驳回 |
     | `approved → paid` | 财务出款完成（点「已出款」或自动在通过时一起做——**本段选分开**：通过只到 `approved`，出款完成点「已出款」才到 `paid`） |
     | `rejected → *` | 不允许（律师要重新申请） |
     | `paid → *` | 不允许（终态） |
  5. 设计稿 A-10 有「已出款」状态与「通过」两个动作，所以**必须**有 `approved → paid` 这一步，不能合并。
- **产出**：`WithdrawService.approve / reject / markPaid` + 状态机单测（5 条合法 + 4 条非法）。
- **算做完**：`5-A5` 全流程可验（申请 → 冻结正确 → 通过 → 出款 → `available` 按规则减少、流水完整）；驳回后 `frozen` 归零、`available` 恢复；恒等式两条都成立。
- **卡住怎么办**：若财务希望「点通过就等于出款完成」（少一步），改成 `applying → paid` 一条直达，但**必须**在页面提示「点通过表示已在银行完成转账」，且状态机少一个态——这要回到负责人（§十 第 5 项）。

#### F4 · 收款账户绑定

- **做什么**：律师绑定/更换收款银行卡，提现时引用。
- **怎么做**：
  1. 字段（A1 已加）：`lawyer.withdraw_bank_name` / `lawyer.withdraw_bank_account`。
  2. 脱敏：列表与提现面板只显示「招商银行 · 尾号 4821」，**完整卡号任何接口都不返回**（连管理员接口也只返回尾号 + 哈希后 6 位）。
  3. `PUT /app/workbench/withdraw-account`：校验银行卡号格式（Luhn + 长度 16~19）、开户行名称非空；**记录变更日志**（`operation_log`，动作 `WITHDRAW_ACCOUNT_CHANGE`）。
  4. 实名一致：V1 **只做提示不做校验**（设计稿写「必须与实名一致」），因为平台没有银行卡实名核验能力——这一条记进 §六 R7。
- **产出**：绑定页（嵌在 66 页的提现面板里，不单独成页）+ 2 个接口。
- **算做完**：绑定后提现面板显示脱敏卡号；未绑定时提现按钮给出明确提示；接口任何响应体里都不含完整卡号（用脚本 grep 断言）。
- **卡住怎么办**：若要求真实核验实名一致，需要第三方银行鉴权接口——本段**不做**，记账 §八.2。

**F 组检查点**：`5-A5` 全流程可验；恒等式两条成立；任何接口响应不含完整卡号。

### G 组：案例库与全局搜索（4 个任务）

#### G1 · 案例库数据模型 + PC A-16（列表 + 录入表单）

- **做什么**：案例库的增改查、四语言管理、法条引用录入；PC 列表页接真数据。
- **怎么做**：
  1. **补一个字段**：`case_library` 加 `category VARCHAR(30)`（细分分类）。理由见第 2 条。用 `V503_1__fix_case_library_category.sql` 修正迁移。
  2. **两级分类（必须理清）**：
     | 级 | 字段 | 取值 | 谁用 |
     |---|---|---|---|
     | 大类 | `case_type` | `civil` / `administrative` / `criminal`（**3 类**） | APP 案例列表的三个分组 |
     | 细分 | `category` | `marriage` 婚姻家事 / `labor` 劳动纠纷 / `contract` 合同纠纷 / `traffic` 交通事故 / `criminal` 刑事 / `policy` 民族政策（**6 类**） | PC 案例库的筛选下拉 |
     - 设计稿 A-16 的筛选是 6 类，APP `04-case` 的分组是 3 类——**不是矛盾，是两级**。
     - 大类由细分**映射得到**（`marriage|labor|contract|traffic → civil`、`criminal → criminal`、`policy → administrative`），在服务端写死映射，**不许**让运营两处都填。
  3. **案例编号**：`case_no` 存**平台案例编号** `CL` + 日期 + 序号（§零之二 第 17 项），是对外唯一标识。设计稿的 `CASE-2026-031` 作废。
  4. **法院案号与法院名不对外**：`case_library.court_case_no` / `court_name` 两个字段**后台可见、APP 不展示**（设计稿 `19-case-detail` 的红线：「不显示案号、不显示审理法院全名」；来源写「已脱敏改编」）。
  5. PC 列表 8 列（设计稿原文）：案例编号 / 标题 + 当事人 / 分类 / 适用法条 / 语言 / 状态 / 更新时间 / 操作。
     - 状态 4 种：已上线 / 待审核 / 审核驳回 / 已下架
     - 语言列显示 `x/4`（缺的用 warning 色）
  6. **录入表单（设计稿没画，本段自定）**——用抽屉，分 3 个 Tab：
     | Tab | 字段 |
     |---|---|
     | 基本信息 | 标题（必填）/ 细分分类（6 选 1，大类自动算）/ 关键词标签 / 原告（脱敏姓名）/ 被告（脱敏姓名）/ 来源（默认「已脱敏改编」）/ 案号（内部用，**不对外**）/ 法院（内部用） |
     | 正文 | 案情简介 / 争议焦点 / 裁判结果 / 案例启示 四个多行框 |
     | 法条引用 | 搜索框调 `GET /pub/law-items?kw=` → 选中后显示「民法典 第 1092 条」，存入 `law_refs` JSON `[{lawName, articleNo, lawItemId}]`；**保存前必须调 `ArticleNoValidator` 校验**（段 3 已交付），条号不存在则拒绝保存 |
  7. **四语言切换（设计稿没画，本段自定）**：表单抽屉顶部一排语言 Tab `zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN`；`zh-Hans` 是源内容（主表字段），其余三个写 `case_library_translation`。**切换 Tab 时正文四块与标题一起切**。
  8. 接口 4 个（上游已定义）：`GET/POST /admin/case-library`、`GET/PUT /admin/case-library/{id}`；写接口全部标 `@OperationLog`。
  9. 批量下架 / 批量导出：V1 只做**批量下架**（导出按钮置灰并提示「V1 不做导出」）。
- **产出**：`V503_1` 迁移 + `src/views/biz/content/CaseLibrary.vue` + 录入抽屉 + 4 个接口。
- **算做完**：能录一条案例并自动算大类；法条引用能搜到并保存；条号不存在时保存被拒；四语言 Tab 能切并分别保存；列表 8 列与库一致；批量下架生效。
- **卡住怎么办**：若 `ArticleNoValidator` 在段 3 没交付，**停**——`5-A7` 要求「法条引用正常渲染」，没有校验器就无法保证引用有效。

#### G2 · APP 案例列表（`04`）+ 案例详情（`19`）

- **做什么**：用户浏览公开案例，点进详情看法条引用；可收藏、可跳 AI。
- **怎么做**：
  1. `04-case`（Tab 4）：顶部搜索框（`role=button`，占位「请输入关键词」，点击跳全局搜索）+ 渐变 banner「每日热点案例」+ **三个分组**（民事纠纷 / 行政纠纷 / 刑事纠纷）+ 案例条目（**只有标题一个字段**，设计稿明确没有来源/日期/阅读量/缩略图）。
     - 支持下拉刷新、上拉分页、骨架屏、空态（设计稿未画空态，按 §四 补齐）。
     - **Tab 4 的标签文字**：设计稿底部 Tab 写「案件」，但页面是「案例列表」。**本段统一为「案例」**（TabBar 配置同步改，见 §九 第 2 项）。
  2. `19-case-detail`：透明导航栏（返回 + 分享）+ 标题区（标题 / 标签 / 来源·年份·阅读量）+ 五个分节（**案情简介 → 争议焦点 → 裁判结果 → 相关法条 → 案例启示**）+ 免责声明 + 底部固定栏（收藏 / 我有类似问题）。
     - **相关法条块**：按 `law_refs` 渲染，**点击条文**调 `GET /pub/law-items?kw=` 查原文并弹层显示（复用法条库，不重写检索）。
     - **收藏**：用段 2 的 `social_action` 表（`biz_type='case_library'`）——**不新建收藏表**。
     - **我有类似问题** → 跳段 3 的 AI 对话页（`03-agent`），**不带案例内容**（避免把案例当咨询上下文）。
     - 免责声明原文照抄：「本案例经脱敏改编，用于普法说明，不涉及具体案号，不作为个案处理依据。」
  3. **四态齐全**（设计稿已画全）：正常 / 加载（标题 + 正文骨架）/ 空（「这个案例已经下架了」+ 返回）/ 错误（「案例没有加载出来」+ 重新加载）。
  4. 接口：`GET /app/case-library?type=&category=&cursor=`、`GET /app/case-library/{id}`；**只返回 `status='published'`**。
- **产出**：`CaseListPage.tsx`（替换段 3 的 tab4 占位页）+ `CaseDetailPage.tsx` + 2 个接口。
- **算做完**：`5-A7` 可验（PC 录一条 → APP 列表可见 → 详情四个正文块 + 法条引用正常渲染）；三分组正确；收藏可用；四态可达。
- **卡住怎么办**：段 3 建的 tab4 占位页文案是「案件功能开发中」——本段**必须替换**，不能两页并存。

#### G3 · 全局搜索（`40`）四域分域

- **做什么**：跨律师 / 文章 / 案例 / 文书四类检索，分域返回、各自可跳转。
- **怎么做**：
  1. 页面结构：搜索栏（返回 + 输入框 + 清空 + 取消）+ **5 个类型 Tab**（综合 / 律师 / 文章 / 案例 / 文书）+ 四组结果（每组带命中数 + 「查看全部」）。
  2. 四组各自字段（**设计稿原文**）：
     | 域 | 条目字段 | 跳转 |
     |---|---|---|
     | 律师 | 首字头像 + 姓名（「王建国律师」）+「律所 · 擅长X、Y」（命中词高亮） | 段 2 的律师详情页 |
     | 文章 | 标题 +「每日学法 · 日期」 | 段 2 的文章详情页 |
     | 案例 | 标题 +「分类 · 来源」 | 本段的案例详情页 |
     | 文书 | **模板名 + 分类**（「民事起诉状 · 民间借贷」） | 段 4 的文书表单页（带 `templateId`） |
  3. **「文书」这一类搜的是「文书模板」（`doc_template`），不是用户自己的文书。** 设计稿示例「按模板生成 · ¥30.00」里的价格是占位——**V1 不作文书付费**（段 4 的文书是用户自助免费生成），实现时**不显示价格**。
  4. 三个隐藏态：
     | 态 | 内容 |
     |---|---|
     | `idle`（未输入） | 「搜索历史」（带「清空」，5 个 chip）+「大家都在搜」（5 个带序号 chip，前三序号着色） |
     | `empty`（无结果） | 「没搜到「{kw}」相关的内容」+「也许你想找」4 个推荐 chip |
     | `loading` | 骨架屏 |
     | 错误 | **设计稿未画，本段补**：提示 + 重试按钮 |
  5. 接口 `GET /app/search?kw=&type=`：
     - `type` 缺省 = 综合（四域各返回前 3 条 + 总数）
     - `type=lawyer|article|case|document` = 单域分页
     - **响应必须分域**：`{lawyers:{list,total}, articles:{...}, cases:{...}, documents:{...}}`——**不许**拼成一个大列表（这是总纲 §5.6「必须做」第 4 条的原话）
  6. 搜索实现：四域各一条 SQL，**律师/文章/案例用全文索引或 `LIKE` 兜底**；**不引入 Elasticsearch**（AGENTS.md 禁向量库，且 V1 数据量小）。
  7. 搜索历史：本地存储（`AsyncStorage`），最多 10 条；不上服务端。
- **产出**：`SearchPage.tsx` + `GET /app/search` + 四域查询。
- **算做完**：`5-A8` 可验（搜一个同时命中四类的词 → 四组都出现、点击都能跳；空结果有空态；`idle` 态有历史与热门）。
- **卡住怎么办**：若某域没有数据（如还没有案例），该组**不显示**而不是显示空组；总数 0 时整组隐藏。

#### G4 · 四语言与上线门禁

- **做什么**：案例的四语言门槛、缺译登记、RTL 表现。
- **怎么做**：
  1. **上线门禁（本段口径）**：`status='published'` 的**必要条件**是 `zh-Hans` 正文四块齐全；其余三语言**允许缺**，但必须在 `case_library_translation` 的 `status` 里标 `pending`，并在 `resources/.i18n-pending.json` 登记。
     - **设计稿写「4 语言齐全才公开展示」**，但段 8 才做全量译文——本段按「源语言齐全即可上线 + 缺译登记」，段 8 收口时 `.i18n-pending.json` 必须为空（这是《i18n 规范》的既有规则，不是本段新造）。
  2. PC 列表的「语言 x/4」照实显示；缺译的行用 warning 色。
  3. RTL：详情页正文在 `ug-Arab-CN` 下必须镜像（用 `start/end`），法条引用块里的中文条号走 `LtrText`，`letterSpacing = 0`。
- **产出**：门禁逻辑 + 缺译登记 + RTL 走查截图。
- **算做完**：只填中文能上线；切到乌尔都方向（`ug-Arab-CN`）时版式不错乱；`.i18n-pending.json` 有登记项且格式合法。
- **卡住怎么办**：若业务方坚持「4/4 才上线」，则段 5 **一条案例都上不了线**（没有译文），`5-A7` 无法验收——这要回到负责人（§十 第 6 项）。

**G 组检查点**：`5-A7` / `5-A8` 可验；案例的四语言 Tab 能切；四域搜索分域返回。

### H 组：收口（3 个任务）

#### H1 · `reconciliation_diff` 落表（只写不展示）

- **做什么**：把对账巡检发现的差异写进表，供排查留档；**不做页面**。
- **怎么做**：
  1. 在段 1 的巡检逻辑里追加：每发现一条差异，写 `reconciliation_diff` 一行（`check_type` / `biz_type` / `biz_id` / `expected_cents` / `actual_cents` / `diff_cents` / `status='open'` / `found_at`）。
  2. **不改 A-09 对账报表页**（段 1 建的页面仍显示恒等式巡检结果）。**段 6 例外**：段 6 可加「渠道对账」Tab，但 Tab1 恒等式巡检不动。
  3. 巡检触发：复用段 1 的现有触发方式（手动 `POST /admin/reconciliation/run` + 定时任务），**不新增任务表 handler**。
  4. 差异处置：V1 **只记录不处置**（没有 `handled_by` / `handled_at` 的写入口），`status` 保持 `open`。段 7 治理时再加。
- **产出**：巡检逻辑的一处追加 + 表。
- **算做完**：人为造一条余额与流水不一致的数据 → 跑巡检 → `reconciliation_diff` 有 1 行且 `diff_cents` 正确；A-09 页面**外观与段 1 交付时一致**（不改）。
- **卡住怎么办**：若段 1 的巡检是「内存态一次性比对」没有可插入点，改成在巡检结束后统一批量插入，**不改巡检的判定逻辑**。

#### H2 · 四语言与 RTL 走查（13 页）

- **做什么**：把本段 13 页的文案补齐两个语言，并走查 RTL。
- **怎么做**：
  1. 五个 namespace 的 key 全部补齐 `zh-Hans` + `ug-Arab-CN`：`case` / `content` / `workbench` / `trade` / `lawyer`。
  2. 走查清单：13 页逐页在 `ug-Arab-CN` 下截图，检查：文字不被截断、`start/end` 无错位、金额与单号走 `LtrText`（不镜像）、`letterSpacing = 0`、无手动 `I18nManager` 反转。
  3. 金额格式：`¥1,200.00`，千分位；RTL 下**货币符号仍在数字左侧**（用 `LtrText` 包住整个金额）。
- **产出**：两份 json 补齐 + 13 张 RTL 截图 + `docs/acceptance/seg5/H2-rtl.md`。
- **算做完**：`npm run lint` 全绿（含 i18n 静态检查 6 条）；13 页 RTL 截图无截断/错位。
- **卡住怎么办**：乌尔都文翻译没人做时，用机翻 + 标注「待人工校对」，并登记缺译——**不许**留中文硬编码在代码里。

#### H3 · 验收证据 + 文档回写 + 偏差记录

- **做什么**：8 条锚点逐条留证；§九 的 7 项回写完成；§十一 偏差表填写。
- **怎么做**：
  1. 每条锚点一个证据文件：`docs/acceptance/seg5/5-A1.md` ~ `5-A8.md`，含操作步骤、截图、SQL 输出、结论。
  2. §九 7 项逐项改完，每项在 CHANGELOG 有一行说明。
  3. §十一 偏差表：计划 vs 实际、偏差原因、是否影响段 6。
  4. 文档基线出新版本（按 BASELINE §4 的四件事）。
- **产出**：8 个证据文件 + 基线 + CHANGELOG + 偏差表。
- **算做完**：8 个证据文件齐全且每条都有可复现步骤；7 项回写全部落地；基线版本号递增且指纹重算。
- **卡住怎么办**：证据不全时**不许**标段落完成——按总纲 §6.2「收口五件套，缺一件不算收口」。

**H 组检查点**：8 条锚点证据齐全；7 项回写落地；基线出新版本。

---

## 四、验收锚点（`5-A1` ~ `5-A8` 逐条展开）

> 编号沿用《开发计划总纲》§5.6。**`5-A9` 已删除**（§零之二 第 6 项）：AI 转律师的跳转由段 3 交付，本段不做上下文转交，因此这条锚点在段 5 无事可验。
> 每条都有：**怎么验（照着做）** / **证据形式** / **通过标准（带数字）** / **失败长什么样**。

### `5-A1` 在线立案

- **怎么验**：用测试账号在 APP 完成四步立案（民事 · 民间借贷 · 争议金额 32,000 元 · 2 份证据）→ 提交 → 记下案件编号 → 用管理员登录 PC → A-19 列表找到该案件 → 点「详情」→ 在「立案材料」Tab 核对四步字段 → 点「通过」→ 回到 APP 看「我的案件」。
- **证据形式**：APP 四步各 1 张 + 提交成功页 1 张 + PC 详情抽屉 1 张 + APP「我的案件」1 张；SQL：`SELECT case_no,status,JSON_LENGTH(form_snapshot) FROM legal_case WHERE id=?`、`SELECT COUNT(*) FROM case_progress WHERE case_id=?`。
- **通过标准**：
  1. 案件编号匹配 `^CS\d{14}$`；
  2. `form_snapshot` **非空**且含四步的全部字段（案件类型、纠纷类型、事发地、原告姓名/身份证/电话、被告、案情、诉求、争议金额、证据数）；
  3. PC 抽屉能看到**每一字段的值**（不是只显示字段名）；
  4. 审核通过后 APP 状态变「立案中」，`legal_case.status='accepted'`；
  5. `case_progress` 有 2 条（提交 + 审核通过）。
- **失败长什么样**：PC 只能看到「案件类型：民事」看不到当事人与案情 → **不算过**（`form_snapshot` 没存全）；或提交成功页写「立案成功」→ 措辞错，改回「材料已提交」。

### `5-A2` 进度同步

- **怎么验**：律师账号在 APP 打开案件 →「推进节点」→ 选「人民法院受理并立案」→ 填法院案号 `(2026) 新 4002 民初 1234 号` → 提交；同时用用户账号打开「案件进度」页刷新。
- **证据形式**：两个账号的时间轴截图（标注同一节点）+ SQL：`SELECT step,title,operator_id,operator_role,created_at FROM case_progress WHERE case_id=? ORDER BY step`。
- **通过标准**：
  1. 用户端时间轴**多出**该节点；
  2. 节点名、时间（精确到分）正确；
  3. **显示操作人**（§零之二 第 18 项，UI 上有一行「操作人：X」）；
  4. 节点顺序与 `step` 升序一致，**不可乱**；
  5. `accepted → in_progress` 迁移成功，且法院案号已写入 **`legal_case.court_case_no`**（它和平台单号 `case_no` 是两个字段，不要混）。
- **失败长什么样**：节点出现了但没有操作人 → 不过（这是 §零之二 第 18 项明确要补的）；或两个节点时间相同看不出先后 → 时间精度不够，改到秒。

### `5-A3` 案件送达

- **怎么验**：管理员在 PC A-19 详情 →「送达」Tab → 选一份有 PDF 的文书 → 选「平台内电子送达」→ 发起；用户账号打开「文书送达」页 → 看到待签收 → 点「签收」→ 在确认框点「确认签收」。
- **证据形式**：PC 送达 Tab 1 张 + APP 待签收 1 张 + 签收框 1 张 + 已签收/回执 1 张；SQL：`SELECT method,sent_at,signed_at,receipt_no,sign_device FROM case_delivery WHERE id=?`。
- **通过标准**：
  1. 送达记录含**送达方式**与**送达时间**；
  2. 回执号匹配 `^SD\d{14}$`；
  3. 签收后 `signed_at` 与 `sign_device` 非空；
  4. 同一条再签一次，`signed_at` **不变**（幂等）；
  5. **送达页全文搜索不到「人民法院」「报送」「确认书」三个词**（脚本 grep 断言，0 命中）。
- **失败长什么样**：页面上还有「人民法院可调取」字样 → 不过；或签收只改了前端没落库 → `signed_at` 为空，不过。

### `5-A4` 收入对账

- **怎么验**：取一位有 3 笔以上已完成订单的律师，把三处数字抄下来：① APP「收入与提现」页的三档统计 + 明细逐笔；② PC A-13 结算页该律师该账期的订单数/订单收入/服务费/应结金额；③ SQL 汇总 `balance_flow`。
- **证据形式**：两个页面截图 + 一张三列对照表（页面 / 结算页 / SQL），逐笔列出订单号 + 净收入；SQL 三条。
- **通过标准**：
  1. **逐笔**金额一致（不是只有总额一致）；
  2. 差异 = 0（页面金额 − SQL 汇总 = 0，精度到分）；
  3. 明细条数 == `balance_flow` 的 income 条数；
  4. **每条明细都能点出订单号**（§零之二 第 18 项）；
  5. 结算页的「应结金额」== 收入页的对应合计。
- **失败长什么样**：页面总额对得上但笔数不同 → 不过（说明有汇总行或漏行）；或明细没有订单号 → 不过。

### `5-A5` 提现闭环

- **怎么验**：分三步走完并记录每一步余额：
  - ① 律师申请提现 100.00 元；
  - ② 管理员在 PC A-10 驳回一次（填理由）→ 检查余额恢复；
  - ③ 律师重新申请 100.00 元 → 管理员通过（上传凭证）→ 点「已出款」。
- **证据形式**：每步的 `lawyer_balance` 与 `balance_flow` 快照（3 组 SQL 输出）+ APP 提现面板 + PC 审核弹窗 + 凭证预览。
- **通过标准**：
  1. 申请后 `available` −10000 分、`frozen` +10000 分、**总余额不变**；
  2. 驳回后 `frozen` −10000、`available` +10000（回到原值）；
  3. 出款后 `frozen` −10000、总余额 −10000；
  4. `balance_flow` 有 `freeze`（delta=0）/ `unfreeze`（delta=0）/ `withdraw`（delta=−10000）三条，`biz_id` 都指向该提现单；
  5. `withdraw_record` 的 `fee_cents=600`、`actual_cents=9400`；
  6. 恒等式两条都成立（§三 E0）。
- **失败长什么样**：驳回后 `frozen` 没回滚 → 不过（钱被永久锁死）；或出款时 `available` 又扣了一次 → 重复扣款，不过。

### `5-A6` 服务设置

- **怎么验**：律师在 APP「服务设置」把图文咨询从 60 元改成 200 元，并把电话咨询**下架**；另一台设备用用户账号打开该律师详情页。
- **证据形式**：两端截图（带时间戳）+ SQL：`SELECT service_text_on,service_phone_on,service_doc_on,consult_price_cents FROM lawyer WHERE id=?`。
- **通过标准**：
  1. 用户端律师详情页价格**立即**显示 200 元（不刷新也算，最长 60 秒内）；
  2. 电话咨询在用户端**不可下单**，且给出明确提示（不是静默消失）；
  3. 已付款的历史订单**不受影响**（订单金额是快照）；
  4. 设 19 元被拒（低于下限）、2001 元被拒（高于上限）、20 元与 2000 元通过。
- **失败长什么样**：用户端还是 60 元 → 缓存没失效，不过；或价格改了但历史订单金额也跟着变 → 严重错误，不过。

### `5-A7` 案例库

- **怎么验**：管理员在 PC A-16 新建一条案例：标题「房屋租赁押金返还争议」、细分分类「合同纠纷」、四个正文块都填、法条引用选「民法典 第 733 条」、只填简体中文 → 保存 → 设为上线；用户账号打开 APP Tab 4 → 找到该案例 → 点进详情 → 点法条引用。
- **证据形式**：PC 录入抽屉三个 Tab 各 1 张 + APP 列表 1 张 + 详情 1 张 + 法条弹层 1 张；SQL：`SELECT case_no,category,case_type,status,JSON_LENGTH(law_refs) FROM case_library WHERE id=?`。
- **通过标准**：
  1. APP 案例列表对应分组（民事纠纷）里可见；
  2. 详情页四个正文块齐全；
  3. **法条引用正常渲染**成「《中华人民共和国民法典》第七百三十三条」而不是 JSON 原文；
  4. 点法条能看到条文原文；
  5. 大类由细分自动算出（合同纠纷 → `civil`）；
  6. 页面**不显示案号、不显示法院全名**。
- **失败长什么样**：法条显示成 `[{"lawName":...}]` → 不过（没做渲染）；或显示「第 733 条」但点不开原文 → 不过。

### `5-A8` 全局搜索

- **怎么验**：准备四条数据（律师名含「张」、文章标题含「民间借贷」、案例标题含「民间借贷」、文书模板名含「民间借贷」），在 APP 搜索框输入「民间借贷」。
- **证据形式**：结果页 1 张（四组都在）+ 单域 Tab 1 张 + 空结果 1 张 + idle 态 1 张；接口响应 JSON（看到分域结构）。
- **通过标准**：
  1. 四类结果**分域展示**（四个分组，每组有命中数与「查看全部」）；
  2. 每组都能跳转到正确目标页（律师 → 详情、文章 → 详情、案例 → 详情、文书 → 模板表单）；
  3. 搜索一个不存在的词 → 有空态 + 推荐词；
  4. 未输入时有「搜索历史」与「大家都在搜」；
  5. 接口响应是**分域对象**，不是一个大数组。
- **失败长什么样**：四类混在一个列表里 → 不过（违反总纲「不做大杂烩列表」）；或点「查看全部」跳错页 → 不过。

---

## 五、产出清单（交付时逐项勾）

### 5.1 数据库（建 8 张表 + 3 张表补字段）

| # | 表 | 版本 | 有页面 |
|---|---|---|---|
| 1 | `legal_case` | `V500` | ✅ |
| 2 | `case_progress` | `V501` | ✅ |
| 3 | `case_delivery`（新） | `V502` | ✅ |
| 4 | `case_library` | `V503` + `V503_1`（补 `category` / `court_case_no`，共 **21 字段**） | ✅ |
| 5 | `case_library_translation` | `V504` | ✅ |
| 6 | `withdraw_record` | `V505`（**21 字段**：基线 15 + 7 新字段，其中含 `withdraw_no` 单号） | ✅ |
| 7 | `lawyer_income_settlement`（新） | `V506` | ✅ |
| 8 | `reconciliation_diff` | `V507` | ❌ 本段只写不展示（段 6 补 A-09 Tab2） |

**补字段 3 张**：`legal_case`（+`form_snapshot` / `court_case_no` / `reject_reason`，**17 字段**；`status` 改 5 值）、`lawyer`（+4 个布尔 + 2 个银行卡字段，**29 字段**）、`case_library`（+`category` / `court_case_no`，**21 字段**）。`orders` 不改。

### 5.2 接口（41 个：App 23 + PC 18）

**App 已定义 15 个**（上游 §4.8.2 有路径，本段实现）：
`GET /app/cases`、`GET /app/cases/{id}`、`POST /app/cases`、`GET /app/cases/{id}/progress`、`POST /app/cases/{id}/materials`、`GET /app/case-deliveries`、`POST /app/case-deliveries/{id}/confirm`、`GET /app/case-library`、`GET /app/case-library/{id}`、`GET /app/search`、`PUT /app/workbench/online-status`、`PUT /app/workbench/service-settings`、`GET /app/workbench/conversations`、`POST /app/withdrawals`、`GET /app/withdrawals`

**App 新增 8 个**（本段冻结）：
`GET /app/workbench/documents`、`GET /app/workbench/income`、`GET /app/workbench/income/flows`、`PUT /app/workbench/profile`、`GET /app/workbench/withdraw-account`、`PUT /app/workbench/withdraw-account`、`POST /app/cases/{id}/progress`、`PUT /app/case-deliveries/{id}/method`

**PC 18 个**：

| 页 | 接口 |
|---|---|
| A-10 提现审核（3） | `GET /admin/withdrawals`、`POST /admin/withdrawals/{id}/audit`、`POST /admin/withdrawals/{id}/voucher` |
| A-13 律师结算（4） | `GET /admin/lawyer-settlements`、`POST /admin/lawyer-settlements/generate`、`GET /admin/lawyer-settlements/{id}`、`POST /admin/lawyer-settlements/{id}/confirm` |
| A-16 案例库（4） | `GET /admin/case-library`、`POST /admin/case-library`、`GET /admin/case-library/{id}`、`PUT /admin/case-library/{id}` |
| A-19 案件管理（6） | `GET /admin/cases`、`GET /admin/cases/{id}`、`POST /admin/cases/{id}/audit`、`POST /admin/cases/{id}/progress`、`GET /admin/cases/{id}/deliveries`、`POST /admin/cases/{id}/deliveries` |
| 回补（1） | `POST /admin/settlements/backfill` |

### 5.3 APP 页面（13 页）

| # | 页 | 设计稿 | 任务 |
|---|---|---|---|
| 1 | 案例列表 | `04-case` | G2 |
| 2 | 案例详情 | `19-case-detail` | G2 |
| 3 | 在线立案 | `20-filing` | B1 |
| 4 | 材料已提交 | `21-filing-done` | B2 |
| 5 | 文书送达 | `22-service` | C2 |
| 6 | 客户咨询列表 | `63-consultations` | D1 |
| 7 | 律师文书管理 | `65-lawyer-docs` | D2 |
| 8 | 收入与提现 | `66-income` | D3 + F1 |
| 9 | 服务设置 | `67-service-settings` | D4 |
| 10 | 律师资料编辑 | `68-lawyer-profile` | D5 |
| 11 | 我的案件 | `53-my-cases` | B3 |
| 12 | 案件进度 | `54-case-progress` | B3 + B4 |
| 13 | 全局搜索 | `40-search` | G3 |

**跨段改的页面（3 个，不在 13 页内）**：段 2 的律师详情页（价格缓存失效，D4）、段 2 的工作台首页（宫格 5 个入口，D6）、段 3 的 tab4 占位页（替换为案例列表，G2）。

### 5.4 PC 页面（4 页）

A-10 提现审核（F2）、A-13 律师结算（E5）、A-16 案例库（G1）、A-19 案件管理（B2 + B4 + B6 + C1）。
**跨段改的 PC 页（1 个）**：段 2 的订单详情（补服务费显示，B5）。

### 5.5 脚本、配置与文档

| 项 | 内容 |
|---|---|
| `scripts/repair-lawyer-income.sql` | 回补核对 SQL（只读） |
| `sys_config` 6 个键 | `platform.fee_rate` / `withdraw.min_amount_cents` / `withdraw.fee_rate` / `settlement.freeze_days` / `lawyer.price.min_cents` / `lawyer.price.max_cents` |
| `task_queue` handler | `LAWYER_UNFREEZE`（每 10 分钟） |
| 错误码补登 | `407005`~`407008`、`405023`~`405027`、`409xxx` 3 条 |
| 审计动作码 | `CASE_AUDIT` / `CASE_PROGRESS_CORRECT` / `DELIVERY_SEND` / `SETTLEMENT_CONFIRM` / `WITHDRAW_APPROVE` / `WITHDRAW_REJECT` / `WITHDRAW_ACCOUNT_CHANGE` |
| 证据文件 | `docs/acceptance/seg5/` 下 8 条锚点 + `A1-schema.txt` / `A4-enums.md` / `E2-backfill.txt` / `E5-no-money-moved.txt` / `H2-rtl.md` |
| 依赖 | **本段不新增任何依赖**（全部用段 0~4 已有的） |

---

## 六、已知风险与出口

| # | 风险 | 为什么真实存在 | 出口 |
|---|---|---|---|
| R1 | **结算口径漂移**：`balance_flow` 与 `orders` 两套数对不上 | E1 入账用 `lawyer_income_cents`，结算页展示用 `orders` 汇总，两处算错就漂 | E4 的交叉校验断言（`settle == income − fee`）不成立就不写单；`5-A4` 要求逐笔一致 |
| R2 | **回补脚本误伤**：历史订单的 `lawyer_income_cents` 为 0 或费率不同 | 段 2 早期可能没算这个字段 | 回补前先跑只读核对 SQL，人工确认；`lawyer_income_cents=0` 的订单**跳过并单独列表**，交负责人 |
| R3 | **法院表述残留**：设计稿里 7 处法院侧文字，漏改一处就出现「报送人民法院」 | 设计稿是照法院转达画的，改文案比改代码容易漏 | `5-A3` 用 grep 断言三个词 0 命中 |
| R4 | **律师详情页价格缓存**：改了价用户端不变 | 段 2 可能给律师详情加了缓存 | 改价后主动失效 `lawyer:{id}:price`；最差保证 TTL ≤ 60 秒 |
| R5 | **专长修改不触发审核**：`specialties` 是展示标签，改它不重新审核 | 但运营可能认为改专长要审 | 本段裁决不触发，写进 `lawyer_application_log` 留痕；要改口径回 §十 |
| R6 | **`payment_record` 语义被再次用错**：有人图省事把服务费塞进去 | 设计稿画了「3 条」 | B5 已明确改写；`5-A4` 不依赖 `payment_record`，即使被塞也不影响对账 |
| R7 | **银行卡实名无法核验**：设计稿写「必须与实名一致」，平台没这能力 | 需要银行鉴权接口 | 只做格式校验 + 提示；记 §八.2 |
| R8 | **案例四语言门禁与段 8 冲突** | 设计稿要 4/4 才上线，段 8 才全量译文 | 本段按「源语言齐全 + 缺译登记」；若坚持 4/4，`5-A7` 无法验收 → §十 第 6 项 |
| R9 | **律师文书双视角归属**：`document` 一个表两个所有者 | 律师代写时 owner 是委托人，查询者是律师 | `user_id` = 委托人、`lawyer_id` = 律师；两侧查询各自带归属条件，`5-A7` 之外单测覆盖越权 |
| R10 | **解冻任务把已提现的钱又解冻** | `unfreeze` 靠 `NOT EXISTS` 判断，逻辑写错会重复 | 单测：已提现的 income 流水不再产生 `unfreeze`；`uk_flow_biz` 唯一键兜底 |

---

## 七、明确不做（本段不许碰，碰了就是超范围）

| # | 不做 | 归哪 |
|---|---|---|
| 1 | **订单争议 `DISPUTED` / `SETTLED`** | **段 7**（挂到投诉工单） |
| 2 | **法院文书转达**、报送人民法院、《电子送达地址确认书》、人民法院案号展示 | 不做（无法院合作） |
| 3 | **结算真实打款**（A-13 只记录状态） | 不做，打款在 A-10 提现 |
| 4 | `payment_record` 服务费行 | 不做（B5 已改显示方式） |
| 5 | `reconciliation_diff` 的页面 | 不做（只写表） |
| 6 | **AI 转律师上下文转交**（`5-A9` 已删） | 不做；段 3 的跳转保持原样 |
| 7 | **文书付费** / 文书模板价格 | 不做（搜索「文书」不显示价格） |
| 8 | 案例批量导出 | 不做（按钮置灰） |
| 9 | 银行卡实名核验 | 不做（只做格式校验） |
| 10 | 邮寄物流对接 / 快递单号 | 不做（只记「已寄出」） |
| 11 | 短信通道本身 | 复用段 1，不重造 |
| 12 | 文书批量生成（一次多份） | 不做（段 4 也未做） |
| 13 | 案件材料在线公证 / 司法存证 | 不做 |
| 14 | 结算单 PDF 下载 | 不做（导 CSV） |
| 15 | 修改 A-09 对账报表页 | 本段不做（**段 6 例外**：段 6 加「渠道对账」Tab，Tab1 不动） |

---

## 八、留给下一段的接口（我保证什么 / 我不保证什么）

### 八.1 我保证（段 6~8 可以直接依赖）

1. **`balance_flow` 的 `biz_type` 用法已定死**：`income`（+净收入）/ `freeze`（0）/ `unfreeze`（0）/ `withdraw`（−金额）。段 6 接真实充值渠道时只新增 `recharge`，**不改这四个的语义**。
2. **两条恒等式成立**（§三 E0），且写成了可执行的校验 SQL。段 6 / 段 7 的任意账务改动都要重新跑这两条。
3. **结算批次表只读流水、不动钱**：`lawyer_income_settlement` 是状态记录，任何时候删掉它，律师的钱一分不少。
4. **提现状态机 4 态 + 申请幂等 + 冻结/回滚完整**：段 7 做治理时可以直接按 `withdraw_record.status` 做统计，不需要再对账。
5. **案件状态机 5 态 + `case_progress` 就是迁移日志**：段 7 的工单可以按 `case_id` 关联，不需要新的案件日志表。
6. **`case_delivery` 送达记录结构稳定**：段 7 若做「送达争议」可直接查这张表。
7. **单号生成器可复用**：段 6 的充值单、段 7 的工单都能用 `BizNoGenerator`，前缀加一个即可（要登记进《数据表规范》§3.11 的表里）。
8. **案例库的两级分类（`case_type` + `category`）与四语言结构**：段 8 只需补译文，不改结构。
9. **全局搜索的四域响应结构**：段 7 若要加「治理对象搜索」，扩一个域即可，不改协议形状。

### 八.2 我不保证（后续段可能推翻或补做）

| 不保证 | 最晚什么时候解决 |
|---|---|
| **真实银行打款**（A-10 的「已出款」是人工在网银操作后回来点按钮） | 若要做企业付款到卡，需支付渠道能力（段 6 之后评估） |
| **银行卡实名核验**（设计稿写「必须与实名一致」，本段只做格式校验） | 需要银行鉴权接口，二期 |
| **法院侧任何能力**（转达、电子送达确认书、送达回证） | 需要法院合作协议，且要先改 `case_delivery` 结构 |
| **案例四语言全齐**（本段允许源语言齐全 + 缺译登记） | 段 8 收口时 `.i18n-pending.json` 必须为空 |
| **结算单 PDF 下载**（只有 CSV） | 若财务需要盖章结算单，二期 |
| **提现手续费的税务与票据合规** | 平台抽成、提现手续费的发票问题归负责人（AGENTS.md §1.1） |
| **文书在 IM 里的阅读状态完整性**（本段写 `conversation_message.read_at`，但推送失败时可能不准） | 段 7 做消息治理时校准 |
| **邮寄送达的物流轨迹**（只记「已寄出」） | 若要做，接快递 API |
| **批量生成送达文书**（一次生成多份） | 段 4 列为「不保证」，本段也未做 |
| **案件材料的司法效力 / 在线公证** | 不做 |
| **订单争议与申诉**（`DISPUTED` / `SETTLED`） | **段 7**（本段已明确不做，§七 第 1 项） |
| **iOS 端的页面验证** | 段 8（本段只验 Android） |

---

## 九、回写到哪些文档（本段结束必须完成，缺一条算未收口）

> 按 `docs/BASELINE.md` §4 的「四件事」办：**落款 / 登记指纹 / `docs/README.md` 加索引 / `CHANGELOG.md` 说明**。只做一件等于没做。
> **11 行全部已落地（2026-11-01，基线 v1.31）**：第 1 行（总纲）随本文档定稿时改完；第 2~8 行（技术路线 / 数据表规范 / 接口规范 / PC 计划 / 错误码 + 审计规范 / 段 2 与段 3 工作流 / 段 1 账务口径）随后一并回写；第 9~11 行（README / CHANGELOG / BASELINE）随 v1.31 收口。**下表的「什么时候」列保留原计划值，状态以本行为准。**

| # | 文档 | 改什么 | 什么时候 |
|---|---|---|---|
| 1 | ~~**《开发计划总纲》§5.6 + §11.2**~~ ✅ **已在定稿时改完（基线 v1.30）** | ①数据库清单 8 张 → 7 张（删 `case_service_record` / `lawyer_service_config` / `lawyer_income_settlement` 的错误写法，补 `case_delivery` / `case_library_translation` / `reconciliation_diff`）；②**删 `5-A9`**，锚点总数 79 → 78；③`5-A1` 的「立案成功」→「材料已提交」；④`5-A2` 补「显示操作人」；⑤`5-A4` 补「明细含订单号」；⑥`DISPUTED`/`SETTLED` 移段 7 的说明；⑦§11.2 清单里本文件状态改「已产出」 | **✅ 已完成** |
| 2 | **《技术路线》** | ①§4.8.4.6 的 4 个结算接口「主要表」从 `lawyer_income_settlement` 改为「`lawyer_income_settlement`（批次记录，非资金真相）」；②§4.8.4.5 提现三接口的表名 `withdraw_order` → `withdraw_record`；③§4.8.4.8 案件送达两接口的表名 `case_service_record` → `case_delivery`；④§4.8.2 补 8 个本段新增 C 端接口；⑤**§9.6.3 提现规则改**：到账「T+1」→「1~3 个工作日，16:00 前当日处理」、最低 100 元 → **0.1 元**、加「手续费 0.6%」；⑥§5.7 `legal_case` 加 `form_snapshot` / `court_case_no`、状态改 5 值；⑦§5.5 `withdraw_record` 加 `fee_cents` / `actual_cents` / `voucher_file_id` / `bank_name` / `bank_account_tail`；⑧附录 D TabBar「案件」→「案例」 | ✅ **已完成（v1.31）** |
| 3 | **《数据表规范》附录 A + §3.11** | ①表数 51 → **53**（加 `lawyer_income_settlement`、`case_delivery`）；②改 6 行的建表段（`withdraw_record`/`legal_case`/`case_progress`/`case_library`/`case_library_translation`/`reconciliation_diff` 已在段 5，核对字段数）；③§3.11 单号表补 `SD`（送达回执）/ `JS`（结算）/ `CL`（案例）三个前缀 | ✅ **已完成（v1.31）** |
| 4 | **《接口规范》§6.3** | ①案件归属字段 `client_id` → **`user_id`**（`legal_case` 的 DDL 里叫 `user_id`）；②新增一行：**律师侧文书接口按 `lawyer_id` 归属**（与「document 全部按 `user_id`」并列，说明双视角规则） | ✅ **已完成（v1.31）** |
| 5 | **《PC 后台开发计划》§2.3 / §2.4 / §2.5 / §2.6** | A-10 的「主要数据表」`withdraw_order` → `withdraw_record`；A-13 的 `lawyer_income_settlement` 标注「批次记录，非资金真相」；A-19 的 `case_service_record` → `case_delivery`；四页的「admin 接口」列从「缺」改为「已在技术路线 §4.8.4 定义」 | ✅ **已完成（v1.31）** |
| 6 | **《错误码规范》+《审计与日志规范》** | ①错误码补：案件域 `407005`（无法送达：无文书/无 PDF）、`407006`（送达记录不存在）、`407007`（重复签收）、`407008`（送达方式非法）；交易域 `405023`（低于最低提现额）、`405024`（可提现余额不足）、`405025`（提现状态非法）、`405026`（结算数据不一致）、`405027`（提现过于频繁）；内容域 `409001`（案例无法条引用）、`409002`（案例语言不全）、`409003`（案例已上线不可删除）；②审计动作码补 7 个（§五 5.5 已列） | ✅ **已完成（v1.31）** |
| 7 | **《第 2 段工作流》§零之二 拍板第 5 项 + 《第 3 段工作流》§八** | ①段 2 的「`payment_record` 服务费行留段 5」改为「**段 5 决定不写服务费行**，订单详情直接显示 `orders.platform_fee_cents`」；②段 3 §八 第 4 条「段 5 的全局搜索可以复用（跨域搜索里的『法条』一类）」**删除「法条一类」**——设计稿的四类里没有法条 | ✅ **已完成（v1.31）** |
| 8 | **《第 1 段工作流》§三 D 组的账务说明** | 明确 `balance_flow.balance_after` 记的是**总余额**（`available + frozen`），不是单记 `available`；并补上本文档 §三 E0 的两条恒等式校验。段 1 当时没写这一条，段 6 接真实充值时会按错的口径再算一次 | ✅ **已完成（v1.31）** |
| 9 | **`docs/README.md`** | 加本文件索引（一行：段 5 工作流，含 8 组 37 任务 / 8 锚点 / 7 表 / 41 接口） | ✅ **已完成（v1.31）** |
| 10 | **`docs/CHANGELOG.md`** | 新增一条「2026-11-01 · 新增《第 5 段工作流 v1.0》」，写明 18 项裁决与 12 处冲突 | ✅ **已完成（v1.31）** |
| 11 | **`docs/BASELINE.md`** | 基线 v1.29 → v1.30（资产 33 → 34）→ **v1.31**（内容变更 10 份）、登记指纹 | ✅ **已完成（v1.31）** |

> **为什么这些必须在上游改而不是只写在这里**：不改完就开工，会出现「文档说 8 张表、代码建 7 张」的漂移。段 4 的教训（§九 有 6 项开工前改）同理。
>
> **回写过程中又发现两个缺陷**（不在原计划里，已一并修掉）：
> 1. **`withdraw_record` 没有业务单号字段**——《数据表规范》§3.11 要求所有对外编号走 `xxx_no`，但提现单号 `WD` 无处存。已补 `withdraw_no`（字段数 15 → 21），本文档 §三 A1 的字段清单也同步补上。
> 2. **内容域错误码 `409001`~`409004` 已被占用**（`article-not-found` / `case-not-found` / `review-rejected` / `sensitive-word`）。本文档原写「内容域 `409xxx` 三条」若按 001 起编会撞号，实际用 **`409005`~`409007`**。

---

## 十、待负责人拍板（本段开工前或施工中必须有人回答，不许「假设已定」）

| # | 事项 | 影响 | 最晚什么时候 |
|---|---|---|---|
| 1 | **平台服务费比例真实值**（现占位 15%） | A-13 结算、66-income 展示、`sys_config` | 段 1 就该定，仍未定 → **本段开工前** |
| 2 | **提现手续费 0.6% 是否真的按提现金额收** | A-10 的「手续费 / 实际到账」两列、`withdraw_record.fee_cents` | 本段开工前 |
| 3 | **最低提现 0.1 元是测试值还是上线值** | 已按 10 分实现，但上线前要复核 | 上线前 |
| 4 | `lawyer_income_cents = 0` 的历史订单怎么处理 | E2 回补脚本会跳过它们 | 段 5 收口前 |
| 5 | **提现是否合并「通过」与「出款完成」两步** | 拆着做是 4 态（applying/approved/rejected/paid），合并是 3 态 | 本段 E5 开工前 |
| 6 | **案例上线门禁是「源语言齐全」还是「4/4」** | 选 4/4 则本段一条案例都上不了线，`5-A7` 无法验收 | 本段 G4 开工前 |
| 7 | 律师改「专长领域」是否触发重新审核 | D5 的实现方式 | 本段 D5 开工前 |
| 8 | **要不要做银行卡实名核验** | 影响 F4 能否闭环 | 上线前 |
| 9 | **法院电子送达是否作为二期能力立项** | 影响 §七 第 2 项的长期结论 | 不阻塞本段 |

---

## 十一、实际完成与偏差（施工后回写，不删原文）

> 施工完成后在这里追加，**不改上面的内容**。每条写：计划 vs 实际、偏差原因、是否影响下一段。
>
> **回写时间**：2026-11-08 18:00（Asia/Shanghai），分支 `UI设计`，HEAD `b77759f`。
> **收口判定**：**7 条出口条件中 5 条满足、1 条部分满足、1 条不满足**（逐条见 §11.2）。
> **不满足的那条（四语言 + RTL 运行时）已由负责人 2026-11-08 裁决推段 8**，
> 证据见《技术路线》§二十六 第 1 项与 `docs/acceptance/seg5/H2-rtl.md`。

### 11.1 偏差清单（32 条，编号规则：A = A 组；CD = C/D 组；EH = E~H 组；R = 收口轮新发现）

| # | 计划 | 实际 | 偏差原因 | 影响 |
|---|---|---|---|---|
| A-1 | A1 给 `lawyer` 补 6 个字段 | V4 迁移已建，**未重复 ALTER** | 工作流写 A1「补字段」时点已过时 | 无 |
| A-2 | `sys_config` 新增 `platform.fee_rate` | 沿用既有键 `platform_fee_rate` | 负责人 2026-11-08 确认不新增同义键 | 无（页面按既有键读） |
| A-3 | A2「合法 5 条 / 非法 15 条」 | **6 条合法边 / 14 条非法** | 同一节表格列了 6 条合法边（含 `accepted→closed`），正文数字与表格不一致 | 按表格实现；工作流正文数字待下一版修正 |
| A-4 | A5「Swagger 可打开」 | 项目**无 springdoc**，改验「8 接口无 token 全 401」 | 本段不新增依赖（§5.5 末行） | 契约可见性靠文档，不靠 Swagger |
| A-5 | `BizNoGenerator` 放 `common/util/` | 落 `infra/seq/` | `common/util` 只放纯函数，带 Redis 的类不能放 | 无 |
| A-6 | A3 降级单号「毫秒 6 位 + 随机 2 位」 | 实际 **4 + 2 = 6 位** | 原文 8 位会把单号撑过 16 位上限 | 无 |
| CD-1 | D1 路径 `/workbench/conversations` | `/workbench/consultations` | 后者被段 2 的 `ConversationController#listForLawyer` 占用，重复映射使 Spring **启动失败**（实测 Ambiguous mapping） | 两套契约并存：段 2 不动，段 5 走新路径 |
| CD-2 | D2「发送给客户」在文书**预览页** | 放在**文书列表**行上 | 段 4 预览页 460+ 行，改动面大且会碰已验收的导出/盖章链路 | 若验收坚持预览页，需单开一次改动 |
| CD-3 | D4 服务设置是新写接口 | **改的是段 2 的实现**（同路径不能注册两个映射） | 该路径段 2 已占用 | 已在段 2 方法上补区间校验与 `autoAccept` |
| CD-4 | C2 送达方式「全局三选一」 | **每份待签收文书各自选** | 接口按记录改方式；一份文书一种方式更贴近真实送达 | 已登记 |
| CD-5 | C2「逾期」需落库判断 | 只做前端「已过 N 天」提示 | 工作流 C2「卡住怎么办」明确不新增库字段 | 符合规定 |
| CD-6 | 聚合查询写 XML | `QueryWrapper.select(...)` + `selectObjs` | 为不扩散改动面；字段名写死、无拼接用户输入（不违反 XML 禁 `${}` 的实质） | 段 6 若改 XML 需同步改 4 个方法 |
| CD-7 | D3 规则卡显示费率与冻结天数 | 只写规则句式 | 费率/天数分别读 `platform_fee_rate` / `settlement.freeze_days`，页面写死会与配置漂移 | 已登记 |
| CD-8 | 段 2 工作台首页有宫格 | 段 2 **只有一个「我的订单」按钮**，本段补 6 项宫格 | 段 2 未按设计稿实现 | 已补 |
| EH-1 | E0 规定解冻流水 `biz_type='unfreeze'`，E3 与 F3 共用 | E3 用 **`income_unfreeze`**，F3 驳回仍用 `unfreeze` | **计划缺陷**：两者 `biz_id` 分别指向 `balance_flow.id` 与 `withdraw_record.id`，两张自增表必然重叠 → 同 `(owner_type, owner_id, biz_type, biz_id)` 撞 `uk_flow_biz`，后果是「提现驳回成功但某笔收入永远解冻不了」 | 段 6/7 统计「所有解冻」需并查两个值；`balance_flow.biz_type` 现为 **5 值** |
| EH-2 | `case_library` 21 字段 | **24 字段**（+`dispute_focus` +`plaintiff_name` +`defendant_name`） | 技术路线 DDL 缺「争议焦点」与当事人，而 G1 第 6 条、5-A7、A-16 列表都要用 | 《数据表规范》附录 A 需在 H3 回写（21 → 24） |
| EH-3 | `reconciliation_diff` 按技术路线建（`bill_date`/`channel` NOT NULL） | 加 **7 个恒等式字段**（`check_type`/`biz_type`/`biz_id`/`expected_cents`/`actual_cents`/`diff_cents`/`found_at`）+ 放宽两列为 NULL | H1 要求恒等式差异也写这张表，但没有任何「账单日期/渠道」可填 | 段 6 渠道对账仍可用（两列有值时走原语义） |
| EH-4 | F2 凭证「走 `POST /app/upload/token` 直传」 | 改为**管理员把 base64 交给服务端**，服务端落盘 + 登记 `file_record(biz_type='withdraw')` | 上传凭证接口是 C 端用户身份入口，管理员 token 走不通，PC 也没有直传链路 | 新增接口 `POST /admin/withdrawals/{id}/voucher`（清单外补充） |
| EH-5 | 接口清单 PC 18 个 | **24 个**（+A-13 summary、+A-16 publish/offline/批量下架、+凭证预览、+提现已出款、+回补只读核对） | 清单漏了「统计卡 / 上线下架 / 凭证预览 / 已出款 / 核对」这些页面必需动作 | 段 6 接口统计按实际数 |
| EH-6 | 接口清单 App 23 个 | **26 个**（+`GET /app/withdrawals/config`、+收藏两个、+`GET /app/case-library/{id}`） | 提现面板要显示手续费率（不能写死）；G2 要求收藏可用 | 同上 |
| EH-7 | G1 的 4 个接口 | **7 个**（+publish/offline/批量下架） | 列表要「上线/下架」操作、G1 第 9 条要批量下架 | 无 |
| EH-8 | H2 要求五 namespace 补 `zh-Hans` + `ug-Arab-CN` | **只补 `zh-Hans`**；`bo`/`ug`/`kk` 各缺 **1462 key**（登记式缺译） | 无翻译人员；机翻 1462 条会污染资源且与段 8 全量翻译重复；段 4 收口时是同一口径（当时缺 1074 key） | **已裁决**：段 8 统一翻译，收口时 `.i18n-pending.json` 必须为空 |
| EH-9 | 设计稿 A-16 列表「标题 + 当事人」 | 表里原来没有当事人列 → 补 `plaintiff_name` / `defendant_name` | DDL 与页面需求不一致 | 同 EH-2 |
| EH-10 | 证据文件 `E2-backfill.txt` / `E5-no-money-moved.txt` | 内容直接写进 `PROGRESS-E-H.md` §2.1 / §2.4 | 内容重复，维护两处容易漂 | 无（H3 允许合并证据文件） |
| EH-11 | `uploadVoucher` 只允许 `approved`/`paid` 补传 | 允许 **`applying` 预上传**，仅拒 `rejected` | **实测缺陷**：F2 主路径是「通过时上传凭证」，只允许通过后补传 → 第一次通过就没有 `voucherFileId` 可传，**405025 把主路径堵死**（2026-11-08 16:57 实测踩到） | 无（修复后审批流程可走通） |
| EH-12 | F1 四条校验各自独立报错 | **先校验单日笔数**：额度已满时申请大额先报 `405027` 而不是 `405024` | 校验顺序（最低额 → 绑卡 → 单日笔数 → 锁余额后查余额）里，余额检查要等锁行，只能排最后 | 页面按返回码展示；**不改顺序**（先锁行再报余额不足会白占一次行锁） |
| R-1 | A2 的 `accepted → in_progress` 由 `POST /app/cases/{id}/progress` 落地 | **原实现只写 `court_case_no`、不迁状态**——案件停在 `accepted` | 状态机表里有这条边，但 `CaseServiceImpl#addProgress` 漏了 `assertCanTransit` + `casUpdateStatus` | **已修**（2026-11-08，重测 `status=in_progress`）；见 `5-A2.md` |
| R-2 | 段 2 的 `LawyerWorkbenchServiceTest` 应保持全绿 | **4 个用例 NPE**：`SysConfigQueryService` 为 null | `e816dc3` 给 `LawyerWorkbenchService` 加了价格区间依赖（读 `sys_config`），**漏改本测试**；`mvn test` 从 367 项里红 4 项 | **已修**（补 `@Mock` + stub 区间）；`mvn test` 368/368 |
| R-3 | 段 2 语义「0 价强制下架」 | 与 D4「价格 20~2000 元、越界 `405006`」冲突：0 元在 `assertPrice` 就被拒 | 两段口径不同：段 2 用 0 价表「下架」，段 5 改由**开关**控制上下架 | **已对齐 D4**：0 价用例改为期望 `405006`；另加「库内现价 0 时即使请求上架也强制下架」覆盖 `resolveSwitch` 的存活路径 |
| R-4 | F3「状态机单测（5 条合法 + 4 条非法）」 | 抽 `WithdrawStateMachine`（4 态 / 3 条合法边）+ 单测 **6 用例**：12 组合（3 合法 / 9 非法）、终态无出边、同态非法、null 拒绝、`check` 抛 405025、合法边清单 | **工作流数字不自洽**：4 态两两组合只有 12 种，`5+4=9` 凑不出任何自洽划分；实际合法边只有 3 条 | 按实际状态数实现；本文档 §三 F3 的数字待下一版修正 |
| R-5 | §5.5 证据文件 `A1-schema.txt` / `H2-rtl.md` | 原**完全不存在** | 施工时漏产出，收口复核发现 | **已补**：`A1-schema.txt`（9 张表 `SHOW CREATE TABLE` 原样输出、22 KB、flyway=510）、`H2-rtl.md`（静态核验通过 + **如实标注运行时未验**） |
| R-6 | 出口第 6 条「`npm run lint` 全绿」 | PC 的 `package.json` **只有 `dev` / `build:prod` / `build:stage` / `preview`**，没有 `lint` 脚本 | PC 前端从未配 lint（不是本段新欠） | 未修；段 6 需决定是否补 ESLint 到 PC |
| R-7 | 出口第 6 条「ArchUnit 断言通过」 | **全仓 0 处 ArchUnit**（`pom.xml` 无该依赖） | 《后端代码规范》§十四 的承接手段矩阵一直未落地，`CHANGELOG.md:1058` 早有记录，属**段 0 CI 欠账** | 未修；建议把出口第 6 条的 ArchUnit 部分改口径为「段 0 CI 未落地，段 5 不承担」，而不是假称满足 |

### 11.2 出口条件逐条结论

| # | 出口条件（§一.1） | 结论 | 依据 |
|---|---|---|---|
| 1 | `5-A1`~`5-A8` 8 条全过、各有证据文件 | ✅ **满足** | `docs/acceptance/seg5/5-A1.md`~`5-A8.md` 8 个文件，逐条含判据 + 原始响应/SQL |
| 2 | 案件状态机、提现状态机全部迁移有单测 | ✅ **本轮补齐** | 案件 6 合法 / 14 非法（`CaseStateMachineTest`）；提现 3 合法 / 9 非法（`WithdrawStateMachineTest`，6 用例）；提现申请 4 条校验 + 幂等 + 手续费（`WithdrawServiceTest`，8 用例） |
| 3 | 账务恒等式成立 | ✅ **满足** | `PROGRESS-E-H.md` §2.9 实测：`cache_total` / `last_balance_after` / `SUM(delta)` 三者相等 |
| 4 | 结算确认后一个字节都没变 | ✅ **满足** | `PROGRESS-E-H.md` §2.4：`0/10200/10200/version2` 前后不变 |
| 5 | 四语言 key 齐全 + RTL 无错乱 | ❌ **不满足**（已裁决推段 8） | `H2-rtl.md`：只有 `zh-Hans`；`bo`/`ug`/`kk` 各缺 1462 key；RTL **运行时未验**（静态：0 处 left/right、letterSpacing 全 0、无手动反转） |
| 6 | `npm run lint` + `mvn -q verify` 全绿 + ArchUnit 通过 | ⚠️ **部分满足** | `mvn test` 368/368 全绿（注入 `FAZE_*` 后；不注入会有 5 个 `@SpringBootTest` 报 `FAZE_DB_URL`）；APP `npm run lint` 零错误；**PC 无 lint 脚本**（R-6）；**ArchUnit 全仓不存在**（R-7） |
| 7 | 上游 11 行回写完成、基线出新版本 | ✅ **满足** | v1.31 已完成 11 行；本轮新增 §11 回写与基线 v1.55（偏差表 + 证据齐全） |

### 11.3 遗留挂账（交段 6 / 段 8）

1. **四语言翻译 + RTL 运行时核验** → 段 8，收口时 `.i18n-pending.json` 必须为空（《技术路线》§二十六 第 1 项）。
2. **PC 前端无 lint 脚本** → 段 6 决定是否补 ESLint（R-6）。
3. **ArchUnit 未落地** → 段 0 CI 欠账，建议改出口口径而不是硬补（R-7）。
4. **`balance_flow.biz_type` 五值** → 段 6 加 `recharge` 时不要改这五个的语义（EH-1）。
5. **`case_library` 24 字段** → 《数据表规范》附录 A 需回写 21 → 24（EH-2）。
6. **本文档 §三 A2「5 合法 / 非法 15」与 F3「5 合法 + 4 非法」两处数字** → 与各自表格/状态数不符，下一版修正（A-3、R-4）。

---

## 附：本文档的生成方式与已知局限

- **生成日期**：2026-11-01（同日 18 项裁决与本文档一起定）。
- **依据的实测**：仓库 HEAD `f45187d`（分支 `UI设计`）；分支 `UI设计` 与 `docs` 一致；工作区干净。
- **读过的文档**：总纲 §4.1/§4.2/§5.6/§6.1/§10/§11.1/§11.2；技术路线 §4.8.2/§4.8.4/§5.3/§5.5/§5.7/§5.8/§9.6/§14/§22.3/附录 C/附录 D；数据表规范 附录 A/§3.11/§8.1；接口规范 §6.3/§6.4；错误码规范 §3.6/§3.7；审计与日志规范 §3.2；i18n 规范 §4.3；段 1/2/3/4 工作流（各自 §八 的交接与 §九 的回写清单）；PC 后台开发计划 §2.3~§2.6 + §八；导航树 模块 4/9/10/11；未完成需备注 §四/§五/§七/§八。
- **读过的设计稿**：APP 13 屏 + PC 4 屏，**逐屏读过正文**（`04-case` / `19-case-detail` / `20-filing` / `21-filing-done` / `22-service` / `53-my-cases` / `54-case-progress` / `40-search` / `63-consultations` / `65-lawyer-docs` / `66-income` / `67-service-settings` / `68-lawyer-profile` / `09-withdraw-audit` / `12-lawyer-settle` / `15-case-library` / `18-case-manage`），另读走查文件 `_walkthrough/19-22-states.html`、`_walkthrough/20-filing-steps.html`。逐屏客观描述留存于调研记录（含字段、按钮原文、四态情况、假数据清单）。
- **已知局限**：
  1. **段 0~4 的施工一次都没跑**，§二 的前 4 项是「预测性要求」，不是实测现状。开工前必须逐条复核。
  2. **PC 4 页里有 3 页设计稿只画了列表**（A-13/A-16/A-19），录入表单、详情 Tab、审核弹窗的规格是本文档自定的，**没有设计稿可对照**。这是本段最大的隐性工作量。
  3. **设计稿里的金额、单号、日期全是假数据**（`FAZE-2026-0087` 在三屏对应三个不同案由），且单号格式全部带横杠——本文档已改为无分隔符，**实现时不要照抄假数据**。
  4. **设计稿自身有 5 处内部矛盾**（66 的余额口径、40 的 3 篇 vs 2 条、12 的统计卡与可见行合计、`FAZE-2026-0087` 跨屏不一致、04 的错字「情亲」），本文档已逐条指明处理方式。
  5. **`22-service` 的法院表述改写是本文档的裁决，不是设计稿的意图**。设计稿画的是真实法院转达场景，本文档把它降级为平台自有文书送达——**若将来有了法院合作，这一页要整体重做**。
  6. **`5-A9` 的删除改变了总纲的锚点总数**（79 → 78），这需要在 §九 第 1 项里同步到总纲，否则总纲的「79 条」会对不上。
  7. **本文档不重复** DDL 全文之外的规范条款（i18n key 命名、接口 9 个请求头、审计四要素、事务五条）——那些看各自的规范文档。
  8. **结算引擎的资金口径（§三 E0）是本文档新立的**，段 1 工作流没有明确 `balance_after` 记总余额还是可用余额。这是一个**跨段一致性缺口**，必须在 §九 **第 8 项**里回写进段 1 的账务说明，否则段 6 接充值时会再算错一次。

---

> **【文档基线 v1.30】** 本文件属于「法泽 FAZE 文档基线 v1.30」（2026-11-01 纳入）。
>
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
