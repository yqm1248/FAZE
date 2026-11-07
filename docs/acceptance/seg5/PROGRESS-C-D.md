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

**补充实测（2026-11-08 04:20，摸清登录口径后跑通）**

登录口径：`POST /api/v1/pub/auth/login/password`，body 必须含 `deviceId`（缺了返 `401001`）。
用 `13800000001 / 123456`（`app_user.id=1`、`lawyer.id=2`）拿到律师 token；
管理员用 RuoYi `POST http://127.0.0.1:8080/login`（`admin/admin123`）拿 token，两端共享 Redis 可直打 `/admin/**`。

| 验收链 | 实测 | 结果 |
|---|---|---|
| C1 发起送达 | `POST /admin/cases/1/deliveries {documentId:38, method:platform}` | ✅ `code=200`，生成 `SD20261108000001`，`status=pending` |
| C1 送达列表 | `GET /admin/cases/1/deliveries` | ✅ 1 条，回执号与文书 id 正确 |
| C2 待签收 | `GET /app/case-deliveries?status=pending` | ✅ total=1 |
| C2 签收 | `POST /app/case-deliveries/1/confirm` | ✅ `code=200` |
| C2 幂等 | 再签一次 | ✅ `code=407007`，首次签收时间未被覆盖 |
| C3 回执 | `GET /app/case-deliveries/1` | ✅ `receiptNo=SD20261108000001`、`status=signed`、`signedAt` 有值、`signDevice=web seg5-ddevice`、文书名正确 |
| C1 库内证据 | `SELECT receipt_no,status,signed_at,sign_device,ip FROM case_delivery WHERE id=1` | ✅ `signed / 1 / web seg5-ddevice / 127.0.0.1` |

`5-A3`（送达全链）**已通过**。

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

**补充实测（2026-11-08 04:20）**

| 验收链 | 实测 | 结果 |
|---|---|---|
| D1 客户咨询 | `GET /app/workbench/consultations` | ✅ 真实数据：1 条会话，`unreadCount=2`、`orderPaid=true`、`peerPhoneMasked=139****0009` |
| D2 文书列表 | `GET /app/workbench/documents` | ⚠️ 200 但 total=0：库里 36 份文书全是**用户委托**（`lawyer_id` 为空），没有一份是「律师代写」；**发送动作因此未实测** |
| D3 收入概览 | `GET /app/workbench/income?range=month` | ✅ 200，字段形状正确（全 0，因为 E 组收入入账未做） |
| D3 明细 | `GET /app/workbench/income/flows?range=month` | ✅ 200，`list` 空、`nextCursor=null`、`hasMore=false` |
| D4 读设置 | `GET /app/workbench/service-settings` | ✅ 真实价格 6000/8000/20000，上下限 2000/200000 |
| D4 价格 19 元 | `PUT {consultPriceCents:1900}` | ✅ `code=405006` |
| D4 价格 20 元 | `PUT {consultPriceCents:2000}` | ✅ `code=200` |
| D4 价格 2001 元 | `PUT {consultPriceCents:200100}` | ✅ `code=405006` |
| D4 自动接单 | `PUT {consultPriceCents:6000, autoAccept:true}` → 回读 | ✅ 回读 `autoAccept=true` |
| **5-A6 改价即生效** | 改成 7000 → `GET /app/lawyers/2` | ✅ 立即返回 `consultPriceCents=7000`（无缓存，符合「不缓存律师价格」） |
| D5 读资料 | `GET /app/workbench/profile` | ✅ 真实姓名 / 执业证号 / 8 项字段齐全，`specialties` JSON 数组解析正确 |
| D5 写资料 | `PUT {bio:"...seg5-d-smoke"}` → 回读 | ✅ 写成功且回读包含标记 |

`5-A6` **已通过**。

**仍未验证**

- **D2 发送文书**：需要一份 `lawyer_id` 非空的文书（段 4 从未产生过这种数据）——要么先做一次「律师代写」的完整流程，要么造一条测试数据。**`5-A2` 的「客户侧收到文书消息」因此未验。**
- **`5-A4` 收入逐笔对账**：依赖 E 组（律师收入入账）先落地，当前 `balance_flow` 没有律师 `income` 流水。
- **`workbench/documents/{id}/send` 的 403/406 分支**：没有可用的 `lawyer_id` 文书，非本人文书的拒绝分支未跑。

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

## 五、本轮实测中新发现并修掉的缺陷

| # | 缺陷 | 怎么发现的 | 修法 |
|---|---|---|---|
| 1 | **审计 `detail` 全是 NULL**：`CASE_AUDIT` / `DELIVERY_SEND` 两条日志只有动作码，没有 before/after | 跑完审核与送达后查 `operation_log` 发现 detail 为空 | 在 `AdminCaseService.audit` 与 `AdminDeliveryService.send` 里显式调 `AuditContext.mark(...)` 写入变更明细；复测两条 detail 分别为 `{"before":{"status":"filed"},"after":{"status":"accepted"},"reason":null}` 与 `{"caseId":2,"documentId":38,"method":"platform","receiptNo":"SD20261108000002"}` |
| 2 | **`GET /app/workbench/conversations` 与段 2 冲突**，API 启动直接 `Ambiguous mapping` 失败 | 重启 API 时启动失败 | D1 改走 `/app/workbench/consultations`，段 2 旧契约不动 |
| 3 | **律师工作台首页没有宫格**（段 2 只做了一个按钮） | 对照设计稿 D6 | `WorkbenchPage` 补 6 项宫格 |

**教训**：前两条都属于「编译通过 ≠ 能跑」。`mvn compile` 不会发现映射冲突（那是 Spring 启动期），
也不会有任何静态检查告诉你「审计 detail 没写」。这两件事只有真的启动一次、真的打一次接口才能发现。

---

## 六、下一轮必须做的（按优先级）

1. **造一份 `lawyer_id` 非空的文书**（或跑通「律师代写」流程），验证 `POST /workbench/documents/{id}/send`
   真的写出一条 `msg_type=doc` 消息且 `document.sent_at` 被回填 —— 这是 `5-A2` 的最后一段。
2. 做 E 组（律师收入入账），让 `balance_flow` 有 `owner_type=lawyer, biz_type=income` 的流水，
   再回来验 `5-A4`（页面金额与流水逐笔对账）。
3. 补 `correctProgress` 的审计 detail（本轮只补了审核与送达两处）。
4. 上述任一条跑不通，先修接口再做 F~H 组。
