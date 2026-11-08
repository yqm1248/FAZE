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
| 未完成 | ~~清库重放（1.5）~~ → **2026-11-07 已在临时库重放补齐，见 §六**；`PAID` 退款驳回缺一条边（二） |

> 复跑命令：先在已加载 `.env` 的 shell 里跑（`FAZE_DB_URL` 等必需），再执行上面的 `mvn`。

---

## 六、出口条件 2 的补验：全链迁移在临时库重放（2026-11-07）

### 6.1 为什么补

《第 2 段工作流》§一.1 出口第 2 条要求「迁移脚本可重放」，§五 里记的「清库重放未做」是当时唯一的缺口（怕清掉段 1/2 的验收数据）。本次改为**建临时库重放、主库只读**，既补上验证又不洗数据。

### 6.2 怎么做

| 项 | 值 |
|---|---|
| 临时库 | `faze_replay_seg2`（`utf8mb4` / `utf8mb4_0900_ai_ci`，与 `database/00-create-database.sql` 一致） |
| 执行方式 | `mysql` 客户端按 **Flyway 版本号数字序** 逐文件 `source`（不是文件名字符串序——否则会变成 V1,V10,V11,V12,V2...） |
| 账号 | root（仅建/删临时库用；业务库本身仍是 `faze_app` 运行时 + `faze_migrate` 迁移两账号） |
| 主库 | **只读**，仅用于对比；全程未写 |
| 脚本 | 一次性脚本，跑完即删；命令见 §6.5 |

### 6.3 结果（实测输出）

```
== 2. 重放 14 个迁移（版本数字序）==
   V1    V1__baseline.sql                    OK  419 ms
   V2    V2__init_law_items.sql              OK   68 ms
   V3    V3__account_auth.sql                OK  242 ms
   V4    V4__lawyer.sql                      OK  195 ms
   V5    V5__wallet.sql                      OK  346 ms
   V6    V6__ops_config.sql                  OK  155 ms
   V7    V7__account_b_group.sql             OK  146 ms
   V8    V8__cancel_phone_width.sql          OK   83 ms
   V9    V9__realname_mask.sql               OK  129 ms
   V10   V10__realname_cipher_width.sql      OK  170 ms
   V11   V11__pay_password.sql               OK  135 ms
   V12   V12__ops_config_value_text.sql      OK  176 ms
   V200  V200__seg2_tables.sql               OK  671 ms
   V201  V201__seg2_refund_flow_biz_id.sql   OK   60 ms

== 3. 与主库对比 ==
   临时库表数 = 26；主库表数（不含 flyway_schema_history）= 26
   表清单一致 ✅
   列数逐表一致（26 张表）✅
   索引数逐表一致（26 张表）✅

== 4. 关键抽样 ==
   段 2 的 12 张表全部重放成功 ✅
```

结论：**全链 14 个迁移在空库上按序重放成功，产出的 26 张表在表清单、列数、索引数三个维度与主库逐表一致**，出口条件 2 的「可重放」已闭合。

### 6.4 方法与局限（不隐瞒）

1. 本次用 `mysql` 客户端逐文件执行，**Flyway 自身的 `flyway_schema_history` 没有在临时库生成**。也就是说本证据证明的是「SQL 链按版本序在空库可执行且结果与主库结构一致」；Flyway 的版本号解析/校验和/幂等记录由另两处证据覆盖：V200 首次应用日志（§2「执行方式」行）、`V201` 在真实库上的应用（G4 证据）。
2. `V201__seg2_refund_flow_biz_id.sql` 是**数据修正**（把旧退款流水的 `biz_id` 从订单 id 改成退款单 id），在空库上 UPDATE 命中 0 行、无报错，这是预期行为，不是「没生效」。
3. 临时库已 `DROP`，主库数据未受影响。

### 6.5 复跑方式

```powershell
# 1) 建临时库 → 2) 按版本号数字序 source 14 个 SQL → 3) 与 faze 对比表/列/索引 → 4) DROP 临时库
# 关键命令（没有现成脚本，属一次性验证）：
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$env:MYSQL_PWD = '<root 口令，见 deploy\.env 的 FAZE_DB_ROOT_PASSWORD>'
& $mysql -h 127.0.0.1 -P 3306 -u root --default-character-set=utf8mb4 -e "CREATE DATABASE faze_replay_seg2 DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_0900_ai_ci;"
Get-ChildItem services\faze-api\src\main\resources\db\migration\*.sql |
  Sort-Object { [int]($_.Name -replace '^V(\d+)__.*$','$1') } |
  ForEach-Object { & $mysql -h 127.0.0.1 -P 3306 -u root --default-character-set=utf8mb4 faze_replay_seg2 -e "source $($_.FullName.Replace('\','/'))" }
# 对比：information_schema.TABLES / COLUMNS / STATISTICS 三张视图，TABLE_SCHEMA 分别为 faze_replay_seg2 与 faze
& $mysql -h 127.0.0.1 -P 3306 -u root -e "DROP DATABASE faze_replay_seg2;"
```
