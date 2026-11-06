# 第 2 段 · G4「退款」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze` / Redis db1 / RuoYi 8080）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G4（3 个任务）、§四 `2-A6` / `2-A4`
> 说明：每条都附真实 API 返回或库内数据。C 端用自签 JWT（`app_user.id=3`），
> 管理端走真实 RuoYi 登录（`POST http://127.0.0.1:8080/login`，账号 `admin`）拿到的 token，
> 经 `AdminAuthenticationFilter` 校验 Redis db0 的 `login_tokens:<token>`。

---

## 一、交付物

| 任务 | 交付 | 提交 |
|---|---|---|
| G4.1 退款申请 | `POST /app/orders/{id}/refund`、`GET /app/orders/{id}/refund`；`RefundService#apply` / `#latestOfOrder`；`RefundApplyBO` / `RefundVO`；`RefundPage.tsx`（33-refund）、路由壳 `app/refund.tsx` | `5b11b61`、`a112dea` |
| G4.2 PC 审批 | `POST /admin/refunds/{id}/audit`、`GET /admin/refunds?orderId=`；`RefundAuditExecutor`（approve / reject 各标一个 `@OperationLog`）；`AdminRefundAuditBO` / `AdminRefundAuditResultVO` | `5b11b61` |
| G4.3 退款入账 | `OrderRefundService.creditRefund`（入账 + 回写流水号 + payment 负数行）、`settleApprovedRefund`、`rejectRefund`；`RefundCreditService` 幂等键改为**退款单 id** | `5b11b61` |
| 状态机补边 | `OrderStatusMachine` 10 → 13 条：`REFUND_REJECTED→PAID`、`REFUNDING→PAID`、`REFUNDING→IN_SERVICE` | `5b11b61` |
| 历史数据迁移 | `V201__seg2_refund_flow_biz_id.sql`：旧退款流水 `biz_id` 从订单 id 改为退款单 id | `5b11b61` |

---

## 二、G4.1 退款申请（真实 API）

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | PAID 订单申请全额（不传金额） | 200，退款单 `applying`，金额 = 订单实付 | `code=200` `refundId=7` `amount=6000` `status=applying` |
| 2 | 申请后订单状态 | `REFUNDING` | `status=refunding` |
| 3 | 查退款进度 | 返回最新退款单 | `GET /app/orders/11/refund` → `status=applying` |
| 4 | 重复申请 | 405004 | `code=405004`「请勿重复提交」 |
| 5 | 部分退金额超上限 | 405005 | `code=405005`「退款金额超出可退上限」 |
| 6 | 金额 0 / 负数 | 401001（Bean Validation `@Positive`） | `code=401001`「退款金额必须大于 0」 |
| 7 | 原因空白 | 401002 | `code=401002`「缺少必填参数」 |
| 8 | COMPLETED 订单申请 | 405003（段 2 无申诉流程） | `code=405003` |
| 9 | 非本人订单 | 405002（归属进 SQL） | 未单独造数据，`RefundServiceTest#applyOtherUserOrder` 覆盖 |
| 10 | 驳回后重新申请 | 200 | `code=200` `refundId=8` |

> 说明：0 / 负金额被 Bean Validation 先拦（401001），服务层的 405006 是兜底；两层都拒绝，
> 顺序不影响结论。重复申请**先判「已有进行中退款单」再判订单状态**——订单此时已是
> `REFUNDING`，先判状态会把「重复申请」盖成 405003（实测踩过，已调整顺序）。

---

## 三、G4.2 审批（真实 admin token）

### 3.1 驳回 → 回退原状态（订单 11，退款单 7）

```text
POST /admin/refunds/7/audit {"approved": false}                       → code=401002「驳回必须填写原因」
POST /admin/refunds/7/audit {"approved": false, "remark": "服务已开始，不符合退款条件"}
  → code=200 {"refundStatus": "rejected", "orderStatus": "paid", "action": "reject"}
订单 11：refunding → paid（走 REFUND_REJECTED 中转）
退款单 7：applying → rejected，auditedBy/auditedAt/auditRemark 落值
```

### 3.2 通过 → 全额退（订单 11，退款单 8）

```text
POST /admin/refunds/8/audit {"approved": true, "remark": "同意退款"}
  → code=200 {"refundStatus": "refunded", "orderStatus": "refunded", "action": "approve"}
订单 11：refunding → refunded（终态）
余额：19318 → 25318（+6000）
refund_record 8：amount=6000 status=refunded transaction_id=BAL-609 audit_remark=同意退款
payment_record：12 = +6000 BAL-608（支付）、13 = -6000 BAL-609（退款）→ SUM = 0
balance_flow：608 consume -6000 balance_after=21318、609 refund +6000 balance_after=27318
```

### 3.3 通过 → 部分退（订单 12，退款单 9）

```text
POST /app/orders/12/refund {"reason": "重复下单，多付了一次", "amountCents": 2000}
  → code=200 amountCents=2000 status=applying
POST /admin/refunds/9/audit {"approved": true, "remark": "部分退款"}
  → code=200 {"refundStatus": "refunded", "orderStatus": "paid", "action": "approve"}
订单 12：refunding → **paid**（部分退不代表订单作废，不判死）
余额：19318 → 21318（+2000）
```

### 3.4 非法审批

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 审批不存在的退款单 | 405011 | `code=405011`「退款单不存在」 |
| 2 | 重复审批同一退款单 | 405003 | `code=405003` |
| 3 | 驳回不填原因 | 401002 | `code=401002` |

---

## 四、状态流水（订单 11 全链路，逐条）

```text
pending          ← null         user    用户下单
paid             ← pending      user    用户余额支付
refunding        ← paid         user    用户申请退款：服务还没开始，不需要了
refund_rejected  ← refunding    admin   服务已开始，不符合退款条件
paid             ← refund_rejected system 退款驳回，订单回退
refunding        ← paid         user    用户申请退款：律师未在约定时间联系我
refunded         ← refunding    admin   同意退款
```

> `2-A4` 要的「状态流转可追溯 + 拒绝非法」在退款链路上成立：7 条迁移全部有流水，
> 且每次迁移的 operator_type 与实际触发方一致（user / admin / system）。

---

## 五、审计（`operation_log`）

```text
trade / ORDER_REFUND_APPROVED / REFUND_RECORD / target_id=9 / result=success
trade / ORDER_REFUND_APPROVED / REFUND_RECORD / target_id=8 / result=success
trade / ORDER_REFUND_REJECTED / REFUND_RECORD / target_id=7 / result=success
```

- 通过 / 驳回**各标一个** `@OperationLog`：注解的 action 是常量，一个方法只能标一个，
  所以拆成 `RefundAuditExecutor#approve` 与 `#reject`（与 `AdminVerificationAuditExecutor` 同构）。
- `detail` 存 before/after（退款单状态 + 订单状态四元组 + 审批意见），由切面写入。
- 审批人一律取 `CurrentAdmin.id()`（登录态），**不从请求体取**。

---

## 六、本次踩到并修掉的两个真实缺陷

### 6.1 退款入账幂等键必须用「退款单 id」，不能用「订单 id」

**现象**：订单 10 部分退 2000，审批返回成功、退款单显示 `refunded`、`payment_record` 有 -2000 行，
但**用户余额没变**（21318 期望值，实际 19318）。

**根因**：`balance_flow` 的唯一键是 `uk_flow_biz (owner_type, owner_id, biz_type, biz_id)`。
G2.4 的自动退款把 `biz_id` 写成**订单 id**（订单 6 → `biz_id=6`）；G4 把幂等键改成
**退款单 id**，而退款单 id 从 1 开始自增——两边数值区间重叠。本次退款单 id=6 恰好命中
订单 6 的历史退款流水，`RefundCreditService` 判定「幂等命中」直接返回旧 `flowId`，
于是不复入账，`refund_record.transaction_id` 被回写成那条 20000 的旧流水号 `BAL-582`。

**为什么必须改**：一张订单可以多次退款（先部分退、之后再申请）。用订单 id 当幂等键，
第二次退款永远入不了账——钱退不出去，且系统认为已经退了。

**修法**（提交 `5b11b61`）：

1. `RefundCreditService.credit` 的幂等键参数从 `orderId` 改为 `refundId`；
2. `V201` 迁移把旧的 refund 流水 `biz_id` 从订单 id 改成「该订单最早一张已退款单的 id」
   （金额、余额快照、`payment_record.transaction_id` 都不变）；
3. dev 环境里本轮测试造成的脏数据（退款单 6、支付行 id=11）已清理后重跑验证。

**迁移踩坑（务必记住）**：第一版 V201 用 `CREATE TEMPORARY TABLE` 做映射，启动直接失败——
`Error Code: 1044`，因为 `faze_migrate` 是**逐表授权**，没有 `CREATE TEMPORARY TABLES` 权限。
同时也不能在 `UPDATE ... WHERE` 里子查询 `balance_flow` 自己（MySQL 1093）。
最终方案：只 JOIN `refund_record`，用「该订单最早一张已退款单」锁定目标行；若目标 `biz_id`
已被占用会因唯一键冲突而失败——**这是有意暴露**，那种数据必须人工看，不许静默跳过。

### 6.2 状态机缺 3 条边（G1 记录的已知缺口 + G4 新需求）

| 缺的边 | 为什么会缺 | 不补的后果 |
|---|---|---|
| `REFUND_REJECTED → PAID` | G1 只设计了回退到 IN_SERVICE / COMPLETED | G4.1 允许 PAID 状态申请退款，驳回后订单卡死在 REFUND_REJECTED |
| `REFUNDING → PAID` | 原表没有「部分退款通过后回退」 | 部分退只能先置 REFUNDED（终态、无出边），订单被误判死 |
| `REFUNDING → IN_SERVICE` | 同上 | 服务中的订单部分退后回不到服务中 |

补后 13 条状态对，`OrderStatusMachineTest` 的穷举断言同步改为 13（64 组合里 13 合法 / 51 非法）。

---

## 七、锚点判定

| 锚点 | 本组覆盖 | 判定 |
|---|---|---|
| `2-A6` 退款（全额 + 部分） | 全额：余额恢复、`balance_flow` 新增 1 条 refund、`payment_record` 负行、订单 REFUNDED；部分：只退申请金额、订单回退款前状态 | **接口级通过**；PC 审批**页面**属 G9.2（工作流 G9.2 第 6 条已把退款审批入口归到订单详情页），页面级证据在 G9 后补 |
| `2-A4` 状态机可追溯 / 拒绝非法 | 退款链路 7 条迁移全有流水；非法迁移（重复审批、已完成申请）被拒 | **退款分支通过** |
| `2-A1` | 不含（G2/G3 已验） | — |

---

## 八、偏差与遗留（如实记录）

1. **PC 审批 UI 在 G9.2 交付**：G4.2 的接口与审计已完成并用真实 admin token 验过；
   `11-order-detail.html` 的审批弹窗依赖订单详情页（G9.2），本组不重复造页面。
2. **C 端退款金额只读、一律全额**：设计稿 33-refund 明确「金额由平台核定」；
   部分退款只走后台审批（`amountCents` 入参是后台能力）。
3. **COMPLETED 之后不能申请退款**：段 2 没有申诉/裁定流程，留段 5。
4. **退款不改律师余额**：段 2 没有结算，`orders.lawyer_income_cents` 只是数字；
   段 5 做结算时不要以为这里是漏写（代码注释已写明）。
5. **历史数据**：V201 只修 `biz_type='refund'` 的 user 流水；若某环境的旧流水目标 `biz_id`
   已被占用，迁移会失败并在启动时报出来（不静默跳过）。

---

## 九、自动化测试

- `mvn test` **156 项全绿**（27 个测试类，0 失败 / 0 错误 / 0 跳过）
  - 新增 `RefundServiceTest` 11 项：全额 / 部分 / 超额 / 零元 / 非本人 / 已完成 / 重复申请 /
    退款前状态反查（有流水 + 兜底）/ 退款单不存在 / 原因为空
  - `OrderStatusMachineTest` 6 项：穷举断言 13 条
- 前端门禁：`tsc --noEmit`、`eslint --max-warnings=0`、`check-raw-text`（88 文件 0 命中）、
  `check-i18n`（四目录同构 + 缺译已登记 + 占位符一致）全过。
- i18n：新增 `trade.refund.*` 23 个 key 与 `error.trade.refund-not-found`，其余三语登记缺译（段 8 清空）。
