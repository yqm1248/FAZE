# trade 域（交易与资金）

> 《后端代码规范》§2.6 要求每个业务域有 README，固定四段：边界 / 数据来源 / 不可改的契约 / 测试入口。

## 边界

**管**：
- 余额缓存（`user_balance` / `lawyer_balance`）与唯一真相 `balance_flow`
- 扣款（`DeductService.deduct`）、充值（`RechargeService`）、后台调账
- 对账巡检（余额 = `SUM(流水)`）
- **段 2 起**：订单主表与状态机（`orders` / `order_status_log`）、支付记录（`payment_record`）、退款单（`refund_record`）、评价（`review`）

**不管**：
- 会话与消息 → `conversation` 域
- 律师资料与审核 → `lawyer` 域
- 系统参数定义 → `ops` 域（本域只读 `sys_config`）

## 数据来源

| 方向 | 表 / 来源 |
|---|---|
| 写 | `user_balance`、`lawyer_balance`、`balance_flow`（只追加） |
| 读 | `sys_config`（`platform_fee_rate` 等）、`lawyer`（下单取价） |
| 跨域 | 走对方 `QueryService`，**禁止注入他域 Mapper**（后端规范 §2.3） |

## 不可改的契约

1. **`DeductService.deduct(...)` 是唯一扣款入口**。任何地方不许 `UPDATE user_balance` 或另写一套扣款 SQL（`2-A3` 专验这条）。
2. **余额真相永远是 `balance_flow`**：`SUM(delta_cents) == user_balance.available_cents`，巡检差异必须为 0。
3. 幂等键 `(owner_type, owner_id, biz_type, biz_id)` 唯一，重复扣款由数据库兜底。
4. **退款也必须写流水**（`delta_cents` 正数），不是 UPDATE 余额。
5. 金额一律整数分（`*_cents`，`BIGINT`），计算走 `BigDecimal`，禁止 `double`/`float`。

## 测试入口

```bash
cd services/faze-api
mvn -Dtest=DeductServiceTest test
mvn -Dtest=BalanceSourceOfTruthTest test
```

段 2 新增的状态机与资金链路必须补单测（`OrderStatusMachineTest`、支付/退款链路），不设全局覆盖率数字，只覆盖这三类（后端规范 §12.4）。
