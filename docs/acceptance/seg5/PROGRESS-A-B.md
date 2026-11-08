# 第 5 段 A/B 组施工进度（2026-11-08）

> 本文件是**进度与实测记录**，不是收口证据。完整八条锚点证据待 A~H 全部完成后补齐。

## 一、A 组（地基）——已完成

| 任务 | 产出 | 实测 |
|---|---|---|
| A1 建 8 张表 | `V500`~`V508` 九个迁移 | ✅ 主库 `faze` 迁移成功；**空库全量迁移 42 张表 / 30 个迁移 / 0 失败** |
| A2 案件状态机 | `CaseStatus`（5 态）+ `CaseStateMachine`（6 条合法边） | ✅ 单测 5 个用例：20 种组合 6 合法 / 14 非法、错误角色被拒、终态无出边、null 拒绝、407002 |
| A3 单号生成器 | `infra/seq/BizNoGenerator`（CS/WD/JS/SD/CL） | ✅ 单测 5 个用例：格式、并发 10000 无重复、Redis 降级、跨天日期段、号段用尽 501001 |
| A4 枚举 5 套 | `CaseType`(5) `DeliveryMethod`(3) `WithdrawStatus`(4) `SettlementStatus`(3) `CaseLibraryType`(3) `CaseCategory`(6) | ✅ 对照表见 `A4-enums.md` |
| A5 跨域骨架 + 8 接口 | workbench 域 6 接口 + caseflow 2 接口 | ✅ 无 token 调用 **8/8 全部 401**（不是 404），路径与《第 5 段工作流》§五 5.2 一致 |
| 错误码补登 | `ErrorCode` 补 12 个（407005~407008 / 405023~405027 / 409005~409007） | ✅ 编译通过，枚举自检无重复码 |

## 二、B 组（案件中心）——后端已完成，前端未做

| 任务 | 后端 | 前端 |
|---|---|---|
| B1 在线立案 | ✅ `POST /app/cases`：幂等（`Idempotency-Key` → Redis 24h）、CS 单号、四步表单冻结、首条 `submitted` 进度 | ❌ `FilingPage.tsx` 未做 |
| B2 材料已提交 + PC 审核 | ✅ `POST /admin/cases/{id}/audit`（CAS + `@OperationLog CASE_AUDIT`） | ❌ `FilingDonePage.tsx`、`CaseDetailDrawer.vue` 未做 |
| B3 我的案件 + 进度页 | ✅ `GET /app/cases`（4 个 chip 聚合）、`GET /app/cases/{id}`、`GET /app/cases/{id}/progress` | ❌ 两个页面未做 |
| B4 律师推进 + PC 纠错 | ✅ `POST /app/cases/{id}/progress`（归属进 SQL + 状态机 CAS）、`POST /admin/cases/{id}/progress`（必填原因） | ❌ 页面未做 |
| B5 订单详情服务费 | ❌ 未做 | ❌ 未做 |
| B6 PC 案件列表 | ✅ `GET /admin/cases`（rows/total，5 个状态 chip 可筛） | ❌ `CaseList.vue` 未做 |

## 三、冒烟实测（2026-11-08 03:2x，dev profile，8081）

```text
login ok
create code=200 caseNo=CS20261108000004
idem same=True caseNo=CS20261108000004     ← 重复提交只产生 1 条案件
list total=1
detail status=filed
progress nodes=1 first=submitted
```

## 四、施工中发现的环境问题（已修）

| 问题 | 根因 | 处理 |
|---|---|---|
| `INSERT/SELECT command denied ... legal_case` | `faze_app` 权限是**按表**授的，新表不在名单 | 改为库级 `GRANT ... ON faze.*`；**新表上线必须同步授权**，已记入待办 |
| 空库迁移 `Access denied for faze_migrate` | 临时库只给 `faze_app` 授权 | 临时库补授 `faze_migrate` 后全量迁移通过（验证完已删库） |
| 测试编译报「程序包不存在」 | `target/classes` 被并发启动进程清空 | `clean compile` 后恢复；**以后编译前先停 8081** |

## 五、与工作流的偏差（待 ABCD 完成后统一上报）

1. `lawyer` 表 6 个字段 V4 已建，A1 **未重复 ALTER**（工作流 A1 写的"补字段"已过时）。
2. `sys_config` 平台费率沿用已有键 `platform_fee_rate`（负责人 2026-11-08 确认），未新增 `platform.fee_rate`。
3. 工作流 A2 写「合法 5 条 / 非法 15 条」，但同节表格是 **6 条合法边**；按表格实现 6 合法 / 14 非法。
4. 项目**无 springdoc**，A5 的「Swagger 可打开」无法验收，改用「8 接口无 token 返回 401」验收。
5. `BizNoGenerator` 落在 `infra/seq/`（不是工作流写的 `common/util/`）——`common/util` 只放纯函数，带 Redis 的类不能放。
6. 工作流 A3 写降级为「毫秒时间戳 6 位 + 随机 2 位」是 8 位，超 16 位单号长度；实际实现为 4+2=6 位。
