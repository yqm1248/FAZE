# 第 2 段 G1 · 交易地基 · 证据

> 对应任务：G1.1 建 12 张表 / G1.2 状态机 / G1.3 单号生成器 / G1.4 扣款适配层。
> 实测时间：2026-11-06 19:51 ~ 19:57（Asia/Shanghai）。
> 环境：MySQL 8.0.44（库 `faze`）+ Redis 5.0.14.1 + Spring Boot 4.0.6 / Java 17。

---

## 一、G1.1 建 12 张表

### 1.1 迁移

| 项 | 值 |
|---|---|
| 文件 | `services/faze-api/src/main/resources/db/migration/V200__seg2_tables.sql` |
| 版本号依据 | 《数据表规范》§8.1：第 2 段 = `V200~V299` |
| 执行方式 | 应用启动时 Flyway 自动执行（迁移账号 `faze_migrate`） |
| 结果 | `Migrating schema faze to version "200 - seg2 tables"` → `Successfully applied 1 migration ... now at version v200`（0.653s） |

> **偏差记录（不擅自改历史迁移）**：段 1 实际用了 `V3~V12`，占的是段 0 的 `V1~V99` 区间，未按 §8.1 的 `V100~V199`。已执行的迁移不改；段 2 起按分段规则用 `V200`。

### 1.2 字段数（`information_schema.columns`，实测）

| 表 | 实测 | 附录 A | 一致 |
|---|---|---|---|
| `conversation` | 10 | 10 | ✅ |
| `conversation_message` | 15 | 15 | ✅ |
| `message_offline` | 6 | 6 | ✅ |
| `orders` | 17 | 17 | ✅ |
| `payment_record` | 9 | 9 | ✅ |
| `refund_record` | **12** | 12（v1.45 补 `audit_remark`） | ✅ |
| `review` | 11 | 11 | ✅ |
| `article` | 14 | 14 | ✅ |
| `article_translation` | 12 | 12 | ✅ |
| `social_action` | 8 | 8 | ✅ |
| `order_status_log` | 8 | 8 | ✅ |
| `task_queue` | **12** | 12（11 规范字段 + `trace_id`） | ✅ |

### 1.3 索引数（含 PK / UK，实测）

`article` 4 / `article_translation` 3 / `conversation` 4 / `conversation_message` 4 / `message_offline` 4 / `order_status_log` 2 / `orders` 6 / `payment_record` 2 / `refund_record` 2 / `review` 4 / `social_action` 5 / `task_queue` 4 —— 全部 **≤ 6**（《数据表规范》§5.2）。

### 1.4 运行时授权

`faze_app` 是**逐表授权**（不是库级），新表必须同步，否则应用查不到（实测：授权前 `information_schema` 查不到新表）。
执行 `deploy/sync-db-grants.ps1` → `已执行 31 条语句（27 张表）`；之后 `faze_app` 可读 `orders` / `task_queue` / `conversation`（实测 `SELECT COUNT(*)` 均返回 0）。

### 1.5 未做（如实记录）

- **清库重放未做**：G1.1 的「算做完」要求「清库重放一次迁移，结果一致」。本次未执行（会清掉段 1 的验收数据），记为待办；当前依据是 Flyway 版本链完整、12 张表字段数逐个核对一致。

---

## 二、G1.2 状态机

| 项 | 值 |
|---|---|
| 文件 | `modules/trade/enums/OrderStatus.java`、`enums/OperatorRole.java`、`rule/OrderStatusMachine.java`（纯规则类，不注入 Spring） |
| 状态 | 8 个：`pending / paid / in_service / completed / cancelled / refunding / refunded / refund_rejected`（`disputed` / `settled` 留段 5） |
| 迁移表 | 10 条状态对 / 14 个触发分支 |
| 单测 | `OrderStatusMachineTest`：**6 个用例全绿**，含 8×8=64 组合穷举（断言只有 10 条合法）、每条边的角色逐个校验、非法迁移抛 405003、终态无出边 |
| 实测 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.077 s` |

**已知缺口（记在 `OrderStatusMachine` 类注释，不擅自补）**：`PAID` 阶段发起退款被驳回时，迁移表没有 `REFUND_REJECTED → PAID` 这条边（工作流拆分只给了回退到 `IN_SERVICE` / `COMPLETED`）。业务上应避免 `PAID` 直接进审批，或由业务层直接退款不审批。

---

## 三、G1.3 单号生成器

| 项 | 值 |
|---|---|
| 文件 | `modules/trade/service/OrderNoGenerator.java` |
| 格式 | `OR` / `RF` / `RC` + `yyyyMMdd` + 6 位序号，如 `OR20261101000001` |
| 实现 | Redis `INCR faze:order:seq:{前缀}:{yyyyMMdd}`，TTL 2 天；日期按**东八区**取（单号给人看） |
| 硬依赖 | Redis 不可用即下单失败（不退化成随机数，避免撞 `uk_orders_no`） |
| 单测 | `OrderNoGeneratorTest`：格式 1 例 + **100 并发 100 个唯一号** 1 例 + 跨天从 `000001` 重开 1 例，**3 个全绿**（`Tests run: 3, Failures: 0`，7.351s，含 Spring 上下文启动） |

跨天用 2099 年的固定时钟（`Clock.fixed`），不污染当天序号 key；实测 `OR20990101000001` → `OR20990101000002` → `OR20990102000001`。

---

## 四、G1.4 扣款适配层

| 项 | 值 |
|---|---|
| 文件 | `modules/trade/service/BalancePayAdapter.java`、`vo/PayFromBalanceResult.java` |
| 顺序 | ① `PayPasswordService.verify`（段 1）→ ② `DeductService.deduct`（段 1 唯一扣款入口，幂等键 = 订单 id）→ ③ 返回流水 ID |
| 事务 | **本类不加 `@Transactional`**：密码校验写 Redis（失败计数），包进数据库事务违反「事务内禁远程调用」；扣款自带事务 |
| 单测 | `BalancePayAdapterTest`（Mockito，不启动 Spring）：正常扣（含 InOrder 断言顺序）/ 密码错（断言 deduct **一次都没被调用**）/ 余额不足（405001 原样上抛）/ 重复支付（幂等命中按成功返回），**4 个全绿** |

---

## 五、G1 汇总

| 检查 | 结果 |
|---|---|
| `mvn -Dtest="OrderStatusMachineTest,BalancePayAdapterTest,OrderNoGeneratorTest" test` | **Tests run: 13, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS** |
| 12 张表 | 全部建出，字段数与附录 A 逐个一致，索引均 ≤6 |
| 状态机 | 8 态 / 10 条状态对 / 64 组合穷举通过 |
| 单号 | 100 并发无重复、跨天重置 |
| 扣款 | 只走 `DeductService`，无第二套扣款 SQL |
| 未完成 | 清库重放（1.5）、`PAID` 退款驳回缺一条边（二） |

> 复跑命令：先在已加载 `.env` 的 shell 里跑（`FAZE_DB_URL` 等必需），再执行上面的 `mvn`。
