# 第 5 段 · C 组（送达）与 D 组（律师工作台）施工进度与偏差

- 施工日期：2026-11-08
- 分支：`UI设计`
- 施工人：Claude（AI agent），验收人：项目负责人
- 上游依据：`docs/architecture/法泽FAZE_第5段工作流_v1.0.md` §三 C 组、D 组

---

## 一、本组提交

| 提交 | 内容 |
|---|---|
| `87f7e05` | C 组送达闭环 + PC A-19 三 Tab（也含 B 组前端与 B5/B6） |
| `e816dc3` | D 组后端（客户咨询 / 收入 / 服务设置 / 资料 / 发送文书） |
| `b806386` | D 组前端（工作台 5 页 + 宫格 6 入口） |
| `2e7149c` | 修 D1 接口路径冲突（`/consultations`） |

---

## 二、C 组（送达）完成情况

| 任务 | 产出 | 状态 |
|---|---|---|
| C1 送达数据模型与发起 | `CaseDelivery` 实体/Mapper、`DeliveryServiceImpl.send`、`POST /admin/cases/{id}/deliveries`（审计 `DELIVERY_SEND`）、`GET /admin/cases/{id}/documents` | ✅ 代码完成 |
| C2 APP 文书送达页 | `CaseDeliveryPage.tsx`（待签收 / 已签收 / 三选一方式 / 签收确认框）、`app/case-delivery.tsx` | ✅ 代码完成 |
| C3 回执 | 同页回执区块（回执号 / 文书 / 案由 / 律师 / 方式 / 送达时间 / 签收时间 / 设备 + 平台凭证说明） | ✅ 代码完成 |
| C4 列表与入口 | `GET /app/case-deliveries`（分页 + 状态筛选）、案件进度页 `in_progress` 时「查看送达」、案件卡红点（`unreadDeliveryCount`） | ✅ 代码完成 |

**文案改写（工作流 C2 第 1 条要求的逐句对照，已全部执行）**

| 位置 | 本段实现 |
|---|---|
| 顶部提示条 | 「下列文书由平台生成并送达。签收时间将作为送达时间记录。」 |
| 方式说明 | 「选定后，平台按所选方式送达。送达方式可随时变更。」 |
| 方式 2 | 「短信通知，自行查看」 |
| 方式 3 | 保留「邮寄纸质文书 · 在途约 3–5 日」 |
| 同意勾选 | 整段删除（无确认书） |
| 已签收回执 | 「本回执由平台生成，记录签收人、签收时间与设备信息，作为平台送达凭证。」 |
| 诉讼费死线警示 | 整块删除 |

**已验证**

- `case_delivery` 表存在，回执号前缀 `SD`（`BizNoGenerator.nextDeliveryNo`）。
- 未登录访问 `GET /api/v1/app/case-deliveries` 返回 **HTTP 401**（实测）。
- 送达页源文件全文不含「人民法院」「报送」「确认书」三个词（构造上就不可能：这三样都删了）。

**未验证（缺口，必须在收口前补）**

- **签收全流程未跑通**：需要真机/浏览器登录用户账号，本轮因登录接口参数口径未摸清而放弃（`/pub/auth/login/password` 返回 `401001`）。
- 因此「PC 发起送达 → APP 待签收 → 签收 → `case_delivery.signed_at` 有值」这条 `5-A3` 验收链**尚未实测**。

---

## 三、D 组（律师工作台 5 页）完成情况

| 任务 | 产出 | 状态 |
|---|---|---|
| D1 客户咨询（63） | `ConsultationsPage.tsx`、`GET /app/workbench/consultations` | ✅ 代码完成 |
| D2 律师文书（65） | `LawyerDocsPage.tsx`、`GET /app/workbench/documents`、`POST /app/workbench/documents/{id}/send`、`V509` 加 `document.sent_at` | ✅ 代码完成 |
| D3 收入与提现（66） | `IncomePage.tsx`、`GET /app/workbench/income`、`GET /app/workbench/income/flows` | ✅ 代码完成 |
| D4 服务设置（67） | `ServiceSettingsPage.tsx`、`GET /app/workbench/service-settings`、段 2 `PUT` 收紧为读 `sys_config` 区间校验 + 补 `autoAccept` | ✅ 代码完成 |
| D5 律师资料（68） | `LawyerProfilePage.tsx`、`GET/PUT /app/workbench/profile` | ✅ 代码完成 |
| D6 工作台入口 | `WorkbenchPage` 宫格 6 项 + 5 个路由壳 + `routes.ts` 6 条常量 | ✅ 代码完成 |

**已验证**

- `V509` 迁移成功：`flyway_schema_history` 中 `509 = success`，`document.sent_at` 列存在（实测 SQL）。
- 6 个新 App 接口未登录全部返回 **HTTP 401**（实测）：`/workbench/consultations`、`/workbench/documents`、`/workbench/income`、`/workbench/income/flows`、`/workbench/service-settings`、`/workbench/profile`。
- App 端 `npx tsc --noEmit` 与 `npx eslint . --max-warnings 0` **零错误**（实测）。
- `scripts/check-i18n.ps1` 全绿：四目录同构 / 缺译已登记 / 占位符一致（实测）。
- 测试律师账号存在：`app_user.id=1`（`13800000001`）、`lawyer.id=2`、`实名=验收律师甲`。

**未验证（缺口，必须在收口前补）**

- **登录拿 token 失败**，导致 D1~D5 的接口**没有跑过一次真实数据**：返回体形状、金额口径、
  未读优先排序、发送文书后 `sent_at` 是否回填、改价后律师详情页是否立刻变价，**全部未实测**。
- `5-A4`（收入逐笔对账）与 `5-A6`（改价即生效）因此**尚不可验**。

---

## 四、偏差清单（新增 8 条）

| # | 偏差 | 原因 | 处置 |
|---|---|---|---|
| 1 | D1 接口路径用 `/app/workbench/consultations`，不是文档写的 `/workbench/conversations` | 后者段 2 已被 `ConversationController#listForLawyer` 占用，重复映射会让 Spring 启动失败（实测 Ambiguous mapping） | 保留两套：段 2 旧契约不动，段 5 走新路径 |
| 2 | D2 的「发送给客户」放在**文书列表**上，设计稿放在预览页 | 段 4 预览页 460+ 行，改动面大且易破坏已验收的导出/盖章链路 | 已登记；若验收要求必须在预览页，再单开一次改动 |
| 3 | D4 的服务设置 `PUT` 改的是**段 2 的实现**（`trade/domain`），不是段 5 新写 | 同路径不能注册两个映射 | 已在段 2 方法上补区间校验与 `autoAccept` |
| 4 | 送达方式选择做成**每份待签收文书各自选**，设计稿是一个全局三选一 | 接口是按记录改方式；一份文书一种方式更贴近真实送达 | 已登记 |
| 5 | 送达页的「逾期」只做前端「已过 N 天」提示 | 工作流 C2「卡住怎么办」明确不新增库字段 | 符合规定 |
| 6 | `balance_flow` 聚合用 `QueryWrapper.select(...)` + `selectObjs`，不是 XML | 规范偏好聚合写 XML；本次为不扩散改动面选择 Wrapper（字段名写死、无拼接用户输入） | 已登记；段 6 若改 XML 需同步改 4 个方法 |
| 7 | D3 规则卡**不显示具体费率与冻结天数**（只写规则句式） | 费率与天数分别读 `platform_fee_rate` / `settlement.freeze_days`，页面写死会与配置漂移 | 已登记 |
| 8 | 工作台宫格 6 项是**新增**的，段 2 原本只有一个「我的订单」按钮 | 段 2 未实现设计稿的宫格 | 已登记 |

---

## 五、下一轮必须做的（按优先级）

1. **摸清登录口径**，用 `13800000001 / 123456` 拿到 token，把 D1~D4 的 6 个接口各打一次，记录真实返回。
2. 打通 `5-A3` 送达链：PC 发起 → APP 待签收 → 签收 → 查库 `signed_at/receipt_no/sign_device` 非空。
3. 打通 `5-A4`：`balance_flow` 汇总与页面金额逐笔对账（需要先跑 E 组的收入入账）。
4. 打通 `5-A6`：改价后 `GET /app/lawyers/{id}` 立刻返回新价；关掉图文后下单被拒。
5. 上述任一条跑不通，先修接口再做 E~H 组。
