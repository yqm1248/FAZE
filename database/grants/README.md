# database/grants —— 数据库账号与权限

## 为什么有这一层

《审计与日志规范》对 `operation_log` 有两条硬要求：

> **只追加，不可改不可删**。
> 落地手段之一：**该表不给应用账号 `UPDATE` / `DELETE` 权限**（MySQL 用单独账号或 `GRANT INSERT, SELECT`）。

第 1 段收口时实测发现这半条**没有落地**：

```text
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER,
      EXECUTE, CREATE VIEW, SHOW VIEW, TRIGGER ON `faze`.* TO `faze_app`@`localhost`
GRANT ALL PRIVILEGES ON `faze_test`.* TO `faze_app`@`localhost`
```

也就是说应用连接可以 `UPDATE operation_log`、可以 `DROP TABLE`，而且**迁移与应用共用同一个账号**。
「审计进数据库、日志进文件」的前提是审计表改不了，这条不成立时审计证据就只能算参考。

## 为什么必须拆成两个账号

MySQL 的权限是**并集**：给了 `faze.*` 的库级 `UPDATE`，就没法再用表级 `REVOKE` 把它从 `operation_log` 上减掉。
所以「既要在库里建表、又不能改审计表」只有一个合法解：

| 账号 | 用途 | 权限 |
|---|---|---|
| `faze_migrate` | Flyway 迁移、人工归档与清理 | `faze.*` 的 DDL + DML（含 `operation_log` 的 `DELETE`） |
| `faze_app` | 应用运行时（MyBatis 连接池） | 逐表 `SELECT, INSERT, UPDATE, DELETE`；`operation_log` **只 `SELECT, INSERT`**；`flyway_schema_history` 只 `SELECT` |

`operation_log` 的 `DELETE` 只留在 `faze_migrate` 上，且按规范 §5.3 必须先归档、有人签字，不能挂在 `@Scheduled` 上自动跑。

## 怎么用

### 首次搭环境 / 每次新增表之后

```powershell
# 以 root 执行：建 faze_migrate、收掉 faze_app 的库级授权、按当前表逐张授权
.\deploy\sync-db-grants.ps1

# 只核对不改（verify-seg1.ps1 的第 8 步会调它）
.\deploy\sync-db-grants.ps1 -Verify
```

口令从 `deploy/.env` 读：

```ini
FAZE_DB_MIGRATE_USER=faze_migrate
FAZE_DB_MIGRATE_PASSWORD=...      # 迁移账号口令
FAZE_DB_ROOT_PASSWORD=...          # 仅这个脚本用；应用进程不读它
FAZE_MYSQL_BIN=                    # 可留空，从 PATH 找 mysql.exe
```

**新增表之后必须重跑。** 忘了重跑的后果不是启动报错，而是新表在运行时抛 `1142 command denied`
——所以别靠记忆，`verify-seg1.ps1` 会兜。

### 生产环境

生产**不要**把 `FAZE_DB_ROOT_PASSWORD` 写进任何 `.env`。由 DBA 按 `01-accounts.sql` 的结构手工建账号，
再用 `sync-db-grants.ps1 -Verify` 这种只读方式核对。账号口令应由密钥管理系统注入。

## 文件

| 文件 | 作用 |
|---|---|
| `01-accounts.sql` | 建 `faze_migrate` 并授库级 DDL/DML。**只做增量授权**，不含 `REVOKE`（撤销不存在的授权在 MySQL 会直接报错，判断逻辑在脚本里） |
| `../deploy/sync-db-grants.ps1` | 幂等收敛：跑上面的 SQL → 撤 `faze_app` 的库级授权 → 按 `information_schema` 逐表授权 → `-Verify` 时实测探针 |

## 实测探针（`-Verify` 会跑）

| 探针 | 期望 | 为什么 |
|---|---|---|
| `faze_app` 库级授权数量 | 0 | 有库级授权就等于审计表可改 |
| `faze_app` 表级授权覆盖全部表 | 是 | 漏表 → 运行时 `1142` |
| `operation_log` 对 `faze_app` 的权限集合 | 只有 `SELECT, INSERT` | 规范原文 |
| `faze_app` 执行 `UPDATE operation_log` | `1142 command denied` | 真改一次才算数（`WHERE 1=0` 不落数据） |
| `faze_app` 执行 `UPDATE app_user` | 成功 | 反向对照：授权过紧会让应用挂 |
| `faze_app` 执行 `CREATE TABLE` | 拒绝 | 运行时账号不该有 DDL |

第 4~6 条是**实测**而不是读 `information_schema`：权限表说没有、实际能改的情况是存在的
（连错库、账号 host 不匹配、连接复用了旧会话），只有真发一条语句才认。

## 已知边界

- 本目录的脚本只覆盖 MySQL 8.0 的本地实例。生产若用云数据库（RDS），账号与授权由控制台管理，
  这些脚本退化为**核对清单**。
- `faze_app` 仍然能 `SELECT` 全部业务表（包括 `app_user.password_hash`、`realname_auth` 密文列）。
  列级授权能进一步收紧，但会让 MyBatis 的 `SELECT *` 直接报错，段 1 不动；要收紧时连同
  Mapper 的显式列清单一起改。
