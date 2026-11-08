# `7-A9` 备份恢复演练报告（第 7 段 · G5）

> **执行人**：ops-infra（T4）
> **执行时间**：2026-11-09 01:30 ~ 01:36（Asia/Shanghai）
> **环境**：本机 Windows / MySQL 8.0.44（127.0.0.1:3306）/ PowerShell 5.1
> **结论**：**通过**——备份生成、逐表行数一致（54/54）、抽样 MD5 一致（3/3）、数据可用性等价 SQL 通过、`restore.ps1` 硬保护拒绝生效。
> **未做的部分**：服务级业务验证（改 datasource 连 `faze_restore` 起服务）——需 T1/Lead 重启 faze-api 权限，见 §八。

---

## 一、执行的命令与退出码

| # | 命令 | 退出码 | 说明 |
|---|---|---|---|
| 1 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup\backup.ps1` | **0** | 生成 `E:\faze-backup\faze_20261109_0134.sql`（576,439 字节）+ `.manifest.tsv` |
| 2 | `powershell ... .\scripts\backup\restore.ps1 -File E:\faze-backup\faze_20261109_0134.sql -ReportFile .\tmp\monitor\restore-report-final.txt` | **0** | 恢复到 `faze_restore`，54 张表行数一致 + 3 张表抽样 MD5 一致 |
| 3 | `powershell ... .\scripts\backup\restore.ps1 -File ... -TargetDb faze` | **1** | 硬保护拒绝（预期行为，见 §七） |
| 4 | `powershell ... .\scripts\backup\backup.ps1 -BackupDir <一个文件路径>` | **1** | 失败路径验证（见 §七），`backup.log` 记 FAIL，成功标记不更新 |

> 全部命令用 `powershell`（Windows PowerShell **5.1**）执行；`.ps1` 文件均为 **UTF-8 with BOM**。

---

## 二、备份结果

```text
[backup] 仓库根：E:\dshcode\FAZE
[backup] 备份目录：E:\faze-backup
[backup] 指标文件：E:\dshcode\FAZE\logs\backup-last-success.txt
[backup] mysqldump：C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe
[backup] 上次备份：faze_20261109_0130.sql（575272 字节）
[backup] 执行 mysqldump（--single-transaction）-> E:\faze-backup\faze_20261109_0134.sql
[backup] 备份大小：576439 字节
[backup] 大小校验通过：本次 576439 > 上次 575272 × 0.5 = 287636
[backup] [OK] 已写行数清单：E:\faze-backup\faze_20261109_0134.sql.manifest.tsv（54 张表）
[backup] 已写指标文件：E:\dshcode\FAZE\logs\backup-last-success.txt -> 1794159276（epoch 秒）
[backup] 保留（最近 3 份之一）：faze_20261109_0134.sql
[backup] 保留（最近 3 份之一）：faze_20261109_0130.sql
[backup] 清理完成：删除 0 份，保留 2 份
[backup] [OK] 备份成功：E:\faze-backup\faze_20261109_0134.sql（576439 字节，1 秒）
  恢复演练：.\scripts\backup\restore.ps1 -File "E:\faze-backup\faze_20261109_0134.sql"
```

| 项 | 实测值 |
|---|---|
| 备份文件 | `E:\faze-backup\faze_20261109_0134.sql`（576,439 字节） |
| 行数清单 | `E:\faze-backup\faze_20261109_0134.sql.manifest.tsv`（54 行，表名<TAB>行数） |
| 成功标记 | `E:\dshcode\FAZE\logs\backup-last-success.txt` 内容 = `1794159276`（单行 epoch 秒，无 BOM） |
| 日志 | `E:\dshcode\FAZE\logs\backup.log`：`2026-11-09T01:34:36+08:00  SUCCESS  file=... size=576439 bytes elapsed=1s user=faze_app` |

> `faze_backup_last_success_timestamp` 的契约：G1 的 Gauge（T1 交付）读这个文件。**已实测**：
> T1 修复 `faze.backup.last-success-file` 绝对路径后，Prometheus 查询
> `faze_backup_last_success_timestamp` 返回 **1794159276**，与文件内容一致（见 `7-A7-metrics.md` 附录）。

---

## 三、恢复结果（`restore.ps1` 输出摘要）

```text
[restore] 源库：faze   目标库：faze_restore（硬保护已通过）
[restore] 备份文件：E:\faze-backup\faze_20261109_0134.sql（576439 字节）
[restore] 目标库字符集跟随源库：utf8mb4 / utf8mb4_0900_ai_ci
[restore] [OK] 已重建目标库：faze_restore
[restore] 导入 faze_20261109_0134.sql -> faze_restore ...
[restore] [OK] 导入完成
[restore] 源库表数：54  恢复库表数：54
[restore] 行数基准：备份时点清单 E:\faze-backup\faze_20261109_0134.sql.manifest.tsv（54 张表）
[restore] [OK] 逐表行数全部一致（54 张表，基准=备份时点清单）
[restore] [OK] 抽样一致：app_user（9 行）
[restore] [OK] 抽样一致：orders（10 行）
[restore] [OK] 抽样一致：balance_flow（10 行）
[restore] [OK] 数据可用性抽检（登录等价 SQL）通过
[restore] [OK] 恢复校验全部通过：恢复库 faze_restore 与源库 faze 逐表行数一致、抽样 MD5 一致
```

---

## 四、逐表行数对比（54 张表，全部一致）

> 基准 = **备份时点清单**（`<dump>.manifest.tsv`），不是「当前源库」。
> 原因见 §六 偏差 #3：活库在备份与恢复之间被并发写入是正常的（本段 T1 正在并行开发）。

| 表名 | 备份时点清单 | 恢复库行数 | 当前源库（参考） | 一致 |
|---|---:|---:|---:|:---:|
| ai_conversation | 14 | 14 | 14 | ✅ |
| ai_message | 39 | 39 | 39 | ✅ |
| announcement | 0 | 0 | 0 | ✅ |
| app_user | 9 | 9 | 9 | ✅ |
| article | 3 | 3 | 3 | ✅ |
| article_translation | 7 | 7 | 7 | ✅ |
| balance_flow | 70 | 70 | 70 | ✅ |
| case_delivery | 2 | 2 | 2 | ✅ |
| case_library | 1 | 1 | 1 | ✅ |
| case_library_translation | 0 | 0 | 0 | ✅ |
| case_progress | 6 | 6 | 6 | ✅ |
| complaint | 5 | 5 | 5 | ✅ |
| content_review | 3 | 3 | 3 | ✅ |
| conversation | 1 | 1 | 1 | ✅ |
| conversation_message | 12 | 12 | 12 | ✅ |
| doc_template | 10 | 10 | 10 | ✅ |
| document | 40 | 40 | 40 | ✅ |
| enterprise_auth | 1 | 1 | 1 | ✅ |
| faq | 7 | 7 | 7 | ✅ |
| feedback | 8 | 8 | 8 | ✅ |
| file_record | 191 | 191 | 191 | ✅ |
| flyway_schema_history | 45 | 45 | 45 | ✅ |
| i18n_glossary | 51 | 51 | 51 | ✅ |
| law_item | 34 | 34 | 34 | ✅ |
| lawyer | 2 | 2 | 2 | ✅ |
| lawyer_application | 3 | 3 | 3 | ✅ |
| lawyer_application_log | 7 | 7 | 7 | ✅ |
| lawyer_balance | 1 | 1 | 1 | ✅ |
| lawyer_income_settlement | 1 | 1 | 1 | ✅ |
| legal_case | 2 | 2 | 2 | ✅ |
| login_log | 477 | 477 | 477 | ✅ |
| message_offline | 1 | 1 | 1 | ✅ |
| notification | 3 | 3 | 3 | ✅ |
| notify_template | 6 | 6 | 6 | ✅ |
| operation_log | 258 | 258 | 258 | ✅ |
| order_status_log | 56 | 56 | 56 | ✅ |
| orders | 16 | 16 | 16 | ✅ |
| payment_record | 16 | 16 | 16 | ✅ |
| realname_auth | 3 | 3 | 3 | ✅ |
| recharge_record | 40 | 40 | 40 | ✅ |
| reconciliation_diff | 9 | 9 | 9 | ✅ |
| refund_record | 11 | 11 | 11 | ✅ |
| review | 2 | 2 | 2 | ✅ |
| sensitive_word | 50 | 50 | 50 | ✅ |
| sensitive_word_appeal | 0 | 0 | 0 | ✅ |
| service_ticket | 13 | 13 | 13 | ✅ |
| signature | 20 | 20 | 20 | ✅ |
| social_action | 2 | 2 | 2 | ✅ |
| sys_config | 79 | 79 | 79 | ✅ |
| task_queue | 191 | 191 | 191 | ✅ |
| user_balance | 4 | 4 | 4 | ✅ |
| user_device | 74 | 74 | 74 | ✅ |
| user_violation | 11 | 11 | 11 | ✅ |
| withdraw_record | 4 | 4 | 4 | ✅ |

**统计**：54 张表，源库（备份时点）总行数 **1,921**，恢复库总行数 **1,921**，不一致 0 张。

---

## 五、抽样 MD5 对比（3 张表 × 10 行）

口径：`MD5(CONCAT_WS('#', 该表全部列))`，`ORDER BY 1 LIMIT 10` —— 按 MD5 值排序取**同一批行**，两边可比。

| 表名 | 抽样行数（源/恢复） | 源库 MD5（前 2 条） | 恢复库 MD5（前 2 条） | 一致 |
|---|---|---:|---|---|:---:|
| `app_user` | 9 / 9 | `315d091caebfa1a3b26dd4b9f782aa58`<br>`58b2bef4ad4cbd2b34a38d89f0b2da6d` | `315d091caebfa1a3b26dd4b9f782aa58`<br>`58b2bef4ad4cbd2b34a38d89f0b2da6d` | ✅ |
| `orders` | 10 / 10 | `0a59321491a62b75ea9688751bc72077`<br>`21107a9400a070a1cba066bc7759cf8f` | `0a59321491a62b75ea9688751bc72077`<br>`21107a9400a070a1cba066bc7759cf8f` | ✅ |
| `balance_flow` | 10 / 10 | `002b5ed2cd92b7d296ddec12c4d0d4cb`<br>`053bab2af0cb96f7e7861c79e1b98b14` | `002b5ed2cd92b7d296ddec12c4d0d4cb`<br>`053bab2af0cb96f7e7861c79e1b98b14` | ✅ |

---

## 六、数据可用性抽检（登录 / 查单等价 SQL，**非服务级验证**）

```text
- 登录等价 SQL（app_user WHERE phone='139****0009'）：源库 1 行 / 恢复库 1 行，内容一致=True；样本 id=3
- 查单等价 SQL（orders WHERE user_id=3）：源库 13 单 / 恢复库 13 单
```

- 选样逻辑：取 `orders` 里订单最多的 `user_id`（本库 = 3，13 单），再取该用户手机号做登录等价查询；
- 手机号在报告里脱敏（`139****0009`），符合《审计与日志规范》§4.3；
- 这两个 SQL 证明**数据本身可用**；**不证明**「服务连恢复库能跑」——见 §八。

---

## 七、关键保护与失败路径（实跑输出原文）

### 7.1 硬保护：目标库 = 生产库时拒绝

```powershell
PS> .\scripts\backup\restore.ps1 -File E:\faze-backup\faze_20261109_0134.sql -TargetDb faze
[restore] [DENY] 拒绝执行：目标库名 = 'faze'，这是生产/源库！
[restore] [DENY] 本脚本会 DROP DATABASE，只允许恢复到独立库（默认 faze_restore）。
[restore] [DENY] 退出码 1，未对数据库做任何操作。
PS> $LASTEXITCODE
1
```

> 保护在脚本**第一段**（任何数据库操作之前）执行；同时拒绝 `faze_admin` / `faze_test`。

### 7.2 备份失败路径（非 0 退出 + 日志 + 不更新成功标记）

```text
PS> .\scripts\backup\backup.ps1 -BackupDir 'E:\dshcode\FAZE\tmp\monitor\dl\download.log'
[backup] [FAIL] 备份失败：mysqldump: Can't create/write to file 'E:\dshcode\FAZE\tmp\monitor\dl\download.log\faze_20261109_0135.sql' (OS errno 2 - No such file or directory)
PS> $LASTEXITCODE
1
```

- `logs\backup.log` 新增：`2026-11-09T01:35:28+08:00  FAIL  error=mysqldump: Can't create/write ... elapsed=0s`；
- `logs\backup-last-success.txt` **仍为 1794159276（未更新）** —— 这正是 `FazeBackupStale` 36h 后能响的前提。

---

## 八、未做项与偏差（必须让 Lead 知道）

| # | 项 | 状态 | 原因 / 补验方式 |
|---|---|---|---|
| 1 | **服务级业务验证**（服务 datasource 指向 `faze_restore` → 能登录、能查单） | ❌ **未做** | 需要改 datasource 并**重启 faze-api**；重启是 T1/Lead 的权限，且会打断并行后端开发。本段用 SQL 等价验证替代（§六）。补验：`deploy/.env` 的 `FAZE_DB_URL` 库名改 `faze_restore`（或启动参数覆盖）→ `deploy\restart-api.ps1` → 登录 + `GET /api/v1/app/orders` → 改回 `faze` → 重启 → 打接口确认 |
| 2 | 工作流抽样表 `legal_order` | ⚠️ **偏差** | 本项目**没有 `legal_order` 表**，实际订单表名是 **`orders`**（`legal_case` 是案例库，不是订单）。`restore.ps1` 默认抽样表已用 `app_user / orders / balance_flow` |
| 3 | 工作流 `phone_hash` | ⚠️ **偏差** | `app_user` **没有 `phone_hash` 列**，登录标识列是 **`phone`**（明文，另有 `original_phone`）。登录等价 SQL 用 `phone`，报告脱敏 |
| 4 | 行数基准 = 备份时点清单（`.manifest.tsv`） | ⚠️ **设计决定** | 首次演练用「当前源库」做基准时，`flyway_schema_history`/`sys_config` 行数不一致——**不是恢复失败**，是 T1 在备份之后并发跑了 Flyway 迁移 + 改 sys_config。恢复校验的基准必须是「备份文件内容」，故 `backup.ps1` 增加备份时点行数清单，`restore.ps1` 自动以它为基准、当前源库作参考列 |
| 5 | 备份执行账号 | 说明 | 使用 `.env` 的 `FAZE_DB_USER=faze_app`（表级授权即可 dump，实测成功）；恢复的 DROP/CREATE 用 `root`（`FAZE_DB_ROOT_PASSWORD`），因为需要库级权限。凭据只从 `.env` 读，**未写死** |
| 6 | Windows 计划任务 | ❌ **未注册** | 未经用户同意不动系统级配置。`schtasks /Create` 命令已写进运维手册 §4.6，并把「本段未实际注册 + 脚本无 3 次重试」记挂账 |
| 7 | 保留期 30 天 / 最后 3 份 | ✅ 已实现但**未制造 30 天前的文件**验证 | `backup.ps1 -RetentionDays 30 -KeepMinimum 3` 的清理逻辑已跑（输出「删除 0 份，保留 2 份」）；要验证删除分支可造一个旧日期的 `.sql`。工作流 `7-A9` 不要求验证保留期 |

---

## 九、本次演练产出/改动的文件

| 文件 | 说明 |
|---|---|
| `scripts/backup/backup.ps1` | 备份脚本（本段新建） |
| `scripts/backup/restore.ps1` | 恢复脚本（本段新建，含硬保护） |
| `E:\faze-backup\faze_20261109_0134.sql` | 备份文件（不进仓库） |
| `E:\faze-backup\faze_20261109_0134.sql.manifest.tsv` | 备份时点行数清单 |
| `logs\backup.log` | 备份日志（SUCCESS/FAIL 各一行） |
| `logs\backup-last-success.txt` | epoch 秒（T1 的 Gauge 读它） |
| `docs\ops\运维手册.md` §4 | 备份策略与恢复步骤（本文档的上位说明） |

---

## 十、复现命令（任何人可在本机重跑）

```powershell
Set-Location E:\dshcode\FAZE

# 1) 备份
.\scripts\backup\backup.ps1

# 2) 取最新备份并恢复
$sql = (Get-ChildItem E:\faze-backup -Filter 'faze_*.sql' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
.\scripts\backup\restore.ps1 -File $sql -ReportFile .\tmp\monitor\restore-report.txt

# 3) 硬保护（应 exit 1）
.\scripts\backup\restore.ps1 -File $sql -TargetDb faze
```
