# 5-A4 枚举对照表（枚举 × 中文 × i18n key）

> 锚点：`5-A4`（枚举一次定死，避免各组各写一套）
> 依据：《第 5 段工作流》§三 A4 第 6 条 / 《i18n 规范》§4.3
> 生成：2026-11-08 · 段 5 A 组施工
> 证据类型：静态对照（源码位置逐条给出）

## 一、案件状态 `CaseStatus`（5 值）

| 枚举 | code（落库） | 中文 label | i18n key | 源码 |
|---|---|---|---|---|
| `FILED` | `filed` | 待立案审核 | `case.status.filed` | `modules/caseflow/enums/CaseStatus.java` |
| `ACCEPTED` | `accepted` | 立案中 | `case.status.accepted` | 同上 |
| `IN_PROGRESS` | `in_progress` | 审理中 | `case.status.in_progress` | 同上 |
| `CLOSED` | `closed` | 已结案 | `case.status.closed` | 同上 |
| `REJECTED` | `rejected` | 已驳回 | `case.status.rejected` | 同上 |

- 终态：仅 `CLOSED`；`REJECTED` 不是终态（可 `rejected → filed` 补正重提）。
- `waiting_judgment` 已按 §零之二 第 12 项删除，**不得加回**。

## 二、案件类型 `CaseType`（5 类，与案例分类独立）

| 枚举 | code | 中文 label | i18n key |
|---|---|---|---|
| `CIVIL` | `civil` | 民事 | `case.type.civil` |
| `CRIMINAL` | `criminal` | 刑事 | `case.type.criminal` |
| `ADMINISTRATIVE` | `administrative` | 行政 | `case.type.administrative` |
| `LABOR` | `labor` | 劳动仲裁 | `case.type.labor` |
| `FAMILY` | `family` | 家事 | `case.type.family` |

> 依据 §零之二 第 13 项：案件 5 类与案例 3 类是**两套独立**枚举，不强行统一。

## 三、送达方式 `DeliveryMethod`（3 种）

| 枚举 | code | 中文 label | i18n key |
|---|---|---|---|
| `PLATFORM` | `platform` | 平台内电子送达 | `case.delivery.method.platform` |
| `SMS_PICKUP` | `sms_pickup` | 短信通知自行查看 | `case.delivery.method.sms-pickup` |
| `MAIL` | `mail` | 邮寄纸质 | `case.delivery.method.mail` |

- 非法值抛 `407008`（`CASE_DELIVERY_METHOD_INVALID`），**不静默回退**。
- 只送平台自有文书，不做法院转达（§零.4 第 3 条）。

## 四、提现状态 `WithdrawStatus`（4 态）

| 枚举 | code | 中文 label | i18n key |
|---|---|---|---|
| `APPLYING` | `applying` | 待审核 | `trade.withdraw.status.applying` |
| `APPROVED` | `approved` | 已通过 | `trade.withdraw.status.approved` |
| `REJECTED` | `rejected` | 已驳回 | `trade.withdraw.status.rejected` |
| `PAID` | `paid` | 已出款 | `trade.withdraw.status.paid` |

- 合法迁移：`applying → approved` / `applying → rejected` / `approved → paid`。
- 终态：`REJECTED`、`PAID`。

## 五、结算状态 `SettlementStatus`（3 态）

| 枚举 | code | 中文 label | i18n key |
|---|---|---|---|
| `PENDING` | `pending` | 待结算 | `trade.settlement.status.pending` |
| `SETTLED` | `settled` | 已结算 | `trade.settlement.status.settled` |
| `REJECTED` | `rejected` | 已驳回 | `trade.settlement.status.rejected` |

- 合法迁移：`pending → settled` / `pending → rejected`；终态：`SETTLED`、`REJECTED`。
- **结算不碰钱**：`settled` 只改批次状态，不动 `lawyer_balance`、不写 `balance_flow`。

## 六、案例库大类 `CaseLibraryType`（3 类）

| 枚举 | code | 中文 label | i18n key |
|---|---|---|---|
| `CIVIL` | `civil` | 民事 | `content.case.type.civil` |
| `ADMINISTRATIVE` | `administrative` | 行政 | `content.case.type.administrative` |
| `CRIMINAL` | `criminal` | 刑事 | `content.case.type.criminal` |

## 七、案例细分分类 `CaseCategory`（6 值，大类由它推导）

| 枚举 | code | 中文 label | 映射大类 | i18n key |
|---|---|---|---|---|
| `MARRIAGE` | `marriage` | 婚姻家事 | `CIVIL` | `content.case.category.marriage` |
| `LABOR` | `labor` | 劳动争议 | `CIVIL` | `content.case.category.labor` |
| `CONTRACT` | `contract` | 合同纠纷 | `CIVIL` | `content.case.category.contract` |
| `TRAFFIC` | `traffic` | 交通事故 | `CIVIL` | `content.case.category.traffic` |
| `CRIMINAL` | `criminal` | 刑事 | `CRIMINAL` | `content.case.category.criminal` |
| `POLICY` | `policy` | 行政诉讼 | `ADMINISTRATIVE` | `content.case.category.policy` |

> `case_library` 只填 `category`，`case_type` 由本枚举推导，两处不许都填。

## 八、单号前缀（`BizNoGenerator`，5 种）

| 业务 | 前缀 | 示例 | 落库列 |
|---|---|---|---|
| 案件 | `CS` | `CS20261108000001` | `legal_case.case_no` |
| 提现 | `WD` | `WD20261108000001` | `withdraw_record.withdraw_no` |
| 结算 | `JS` | `JS20261108000001` | `lawyer_income_settlement.settlement_no` |
| 送达回执 | `SD` | `SD20261108000001` | `case_delivery.receipt_no` |
| 案例 | `CL` | `CL20261108000001` | `case_library.case_no` |

- 格式：`^[A-Z]{2}\d{14}$`（16 位、无分隔符），依据 §零之二 第 17 项。
- Redis 不可用时降级（毫秒后 4 位 + 随机 2 位），唯一索引兜底。

## 九、自检结果

| 检查项 | 结果 |
|---|---|
| 枚举无重复 code | ✅ 7 个枚举逐个 `Collectors.toUnmodifiableMap` 构造，重复会在类加载时抛异常 |
| 非法字符串反序列化 | ✅ `of()` 抛业务码（407002 / 407008 / 405025），不静默转 null |
| 每条枚举带中文 label | ✅ |
| 每条枚举带 i18n key | ✅（`i18nKey()` 方法） |
| 单测 | `CaseStateMachineTest`（20 组合）、`BizNoGeneratorTest`（10 项） |
