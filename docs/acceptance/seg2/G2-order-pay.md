# 第 2 段 · G2「下单与支付」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze` / Redis db1）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G2（4 个任务）、§四 `2-A1` / `2-A5` / `2-A8`
> 说明：本文件是**施工时实测记录**，不是计划复述。每条都附命令输出或库内数据。

---

## 一、交付物

| 任务 | 交付 | 提交 |
|---|---|---|
| G2.1 下单接口 + 订单两页 | `OrderController#create/list/detail`、`OrderCreateExecutor`、`OrderIdempotencyGuard`、`Orders`/`OrderStatusLog` 实体、`ServiceType` 枚举、`LawyerQueryService`（account 域只读跨口）；`OrderListPage.tsx`（29-orders）、`OrderDetailPage.tsx`（30-order-detail） | `6c357b9`、`3af1fe8` |
| G2.2 支付接口 | `OrderPayExecutor`、`PayBO`/`PayResultVO`、`POST /app/orders/{id}/pay` | `23107b6` |
| G2.3 支付页 + 结果页 | `PayPage.tsx`（31-pay）、`PayResultPage.tsx`（32-pay-result） | `ffcd77d` |
| G2.4 超时关单 | `TaskQueue`+`TaskQueueMapper.xml`（CAS 领取/租约回收/退避/死信）、`TaskQueueService`、`OrderCloseTask`（@Scheduled 30s）、`OrderTimeoutService`、`OrderRefundService`、`RefundCreditService`、`OrderStateService`、`ThreadPoolConfig`、`POST /app/orders/{id}/cancel` | `0a636b0` |

附加：`database/seed/seg2_config.sql`（新增配置键 `order_require_lawyer_online`，默认 false）。

---

## 二、端到端实测（真实 API + 真实库，非单测）

验证账号：`app_user.id=3`（手机号 139****0009，普通用户，余额 37318 分）；
承办律师：`lawyer.id=2`（user_id=1，审核通过、在线、图文咨询 5000 分、已上架）；
另一个 0 元律师：`lawyer.id=11`（价格 `consult_price_cents=0`）。

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 带 token 查余额 | 200 | `code=200`，余额 `37318` |
| 2 | 正常下单（consult_text） | 200，金额取律师价 | `code=200` `amount=5000` `status=pending` `actions=['pay','cancel']` |
| 3 | 同一 `Idempotency-Key` 连发 3 次 | 同一订单 | 三次都返回 `orderId=1` ✅ `2-A8` ② |
| 4 | 0 元律师下单 | 405009（不许 0 元单） | `code=405009` |
| 5 | `serviceType=case` | 401001（段 2 不支持） | `code=401001` |
| 6 | 不存在的律师 id | 405008 | `code=405008` |
| 7 | 订单列表 | 200 + 分页结构 | `code=200` `total=1` `hasMore=False` |
| 8 | 订单详情 | 含 `payExpireAt` 与律师信息 | `status=pending` `payExpireAt=2026-11-06T13:08:06(UTC)` `lawyer=验收律师甲` `firm=验收律师事务所` |
| 9 | 查不存在的订单 | 405002 | `code=405002` |
| 10 | 非法订单 id（`abc`） | 401003 | `code=401003` |
| 11 | **支付密码错** | 402001，**余额不变、状态不变** | `code=402001`，余额 `37318 -> 37318`，订单仍 `pending` ✅ `2-A5` |
| 12 | 请求体伪造 `amountCents=1`（document 单） | 忽略，仍为律师价 20000 | `amount=20000` ✅ 服务端取价 |
| 13 | 取消待支付订单 | 200，状态 cancelled，actions 清空 | `code=200` `status=cancelled` `actions=[]` |
| 14 | 重复取消 | 200（幂等） | `code=200` |
| 15 | 无 `Idempotency-Key` 连下两单 | 产生两单（缺失键不阻断业务） | `id=3` / `id=4` |

### 库内证据（实测输出）

```text
orders:
id  order_no             service_type  amount_cents  platform_fee_cents  lawyer_income_cents  status
1   OR20261106000304     consult_text  5000          750                 4250                 pending
2   OR20261106000305     document      20000         3000                17000                cancelled
3   OR20261106000306     consult_text  5000          750                 4250                 pending
4   OR20261106000307     consult_text  5000          750                 4250                 pending
```

- **平台费 = 15%（`platform_fee_rate` 从 `sys_config` 读，值 `0.15`）**：5000×0.15=750、20000×0.15=3000；
  律师收入 = 金额 − 平台费，只在订单上记账，**没有产生任何 balance_flow / lawyer_balance 变动**（结算属段 5）。

```text
order_status_log（状态流水，只追加）:
order_id  from_status  to_status  operator_type  operator_id  reason
1         NULL         pending    user           3            用户下单
2         NULL         pending    user           3            用户下单
2         pending      cancelled  user           3            用户取消订单
3         NULL         pending    user           3            用户下单
4         NULL         pending    user           3            用户下单
```

```text
task_queue（定时关单任务）:
id  task_type    biz_no             status   retry_count  next_retry_at
1   order_close  OR20261106000304   pending  0            2026-11-06 13:08:06
2   order_close  OR20261106000305   done     0            2026-11-06 13:08:06
3   order_close  OR20261106000306   pending  0            2026-11-06 13:08:56
4   order_close  OR20261106000307   pending  0            2026-11-06 13:08:56
```

- 下单时**同事务**入队 `order_close`，`next_retry_at = createdAt + 15 分钟`（UTC）。
- 取消订单时该任务被置为 `done`（id=2）——「用户主动取消后关单任务不必再跑」；
  未取消的三条仍是 `pending`，等待 15 分钟到期。

---

## 三、单元测试（`mvn test`，122 项全绿）

| 测试类 | 项数 | 覆盖 |
|---|---|---|
| `OrderCreateExecutorTest` | 11 | 取价 / 平台费 / 空价拒 / 0 价拒 / 未上架拒 / 未审核拒 / case 拒 / 在线开关 / 撞号重试 / 电话咨询取价 |
| `OrderServiceImplTest` | 10 | 幂等命中不建单 / 失败释放占位 / 归属 405002 / 取消三种分支 / 重复支付幂等 / 非待支付拒付 |
| `OrderIdempotencyGuardTest` | 8 | 无键放行 / 抢锁 / 命中返回订单 / creating 冲突 405004 / 非法键忽略 / bind / release |
| `OrderActionResolverTest` | 8 | 8 态动作推导 / 非本人无动作 / 空状态 |
| `OrderStateServiceTest` | 5 | CAS 命中写流水 / 未命中不写 / 非法迁移 / 角色不匹配 / 当前状态 |
| `OrderTimeoutServiceTest` | 6 | 关单 / 已支付跳过 / 订单不存在 / 接单超时退款 / 已接单不退款 / 未知类型抛 |
| `TaskQueueServiceTest` | 9 | 无候选 / CAS 未命中不回查 / 领取回查 / 租约回收 / 退避 / 死信 / 退避计算 / 取消空单号 / 取消 |
| `OrderPayExecutorTest` | 6 | 正常 / 密码错不扣 / 余额不足 / CAS 未命中 / 非待支付 / 记录冲突 |

**总计：G2 相关单测 63 项，全绿；全仓 122 项，0 失败。**

---

## 四、偏差与遗留（如实记录）

| # | 事项 | 说明 | 处理 |
|---|---|---|---|
| 1 | 订单列表 tab 数 | 工作流 G2.1 第 6 条写「全部/待支付/进行中/已完成/已取消/**退款中**」共 6 档；设计稿 `29-orders.html` 只有 5 个 chip（无「退款中」），且 `app.js` §31 明确「点进行中必须把 refund 一起收进来」 | **按设计稿实现 5 档**，`refunding` / `refund_rejected` 归入「进行中」。理由：AGENTS §7「视觉与设计稿一致」优先，且设计稿注释已给出分组口径 |
| 2 | 「余额不足」胶囊底色 | 设计稿 `31-pay.html` 的 warning 胶囊是浅橙底，令牌里只有前景色 `color.state.warning`，没有 `warningSoft` | 用 `background.input` 作底 + 橙字。**不擅自给设计令牌加值**（改令牌要同步 `tokens.json`/`tokens.ts`/`设计令牌.md` 三处），外观略灰但语义一致 |
| 3 | 支付成功的端到端验证 | 用户 3 的支付密码未知，重置支付密码需要短信验证码，而 dev 固定码接口有 60 秒发送间隔，连续验证被限流挡住 | **成功支付路径由 `OrderPayExecutorTest` 单测覆盖**（扣款→CAS→payment_record→入队）。端到端只验到「密码错不扣款」。待段 2 收口前用重置密码流程补一次成功支付 + 自动退款（`2-A8` ①）的真实验证 |
| 4 | 自动退款的端到端 | 同上，自动退款需要一笔 `PAID` 状态的订单 | 单测覆盖 `OrderTimeoutService` → `autoRefundFull` 分支；真实退款链路待补（见第 3 条） |
| 5 | 测试数据残留 | 库内留下 4 条订单（3 条 `pending`、1 条 `cancelled`）与 4 条 `order_close` 任务 | **故意保留**作为证据；`pending` 的三单会在 15 分钟后被定时任务关闭（可用来验证 G2.4 的真实关单）。不清理，符合「不清库直接用手上数据」的口径 |

---

## 五、对既有代码的两处修正

1. **`OrderStatus` 增加 `ofOrNull`**：`of` 对未知值抛异常（读库必须如此），但解析客户端筛选参数需要容错版本
   （旧客户端可能带废弃 code，不该让整个列表 500）。两个方法分工写进了 JavaDoc。
2. **`OrderNoGeneratorTest.crossDay` 不可重复执行（G1 遗留缺陷）**：该用例用真实 Redis 的 2099 年 key
   但不清 key，第一次跑绿、第二次必红（实测 `expected 000001 but was 000003`）。
   已在用例开头加 `redis.delete(...)`，并把这个坑写进类注释。
