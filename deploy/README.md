# deploy/ —— 本地服务编排（段 0 A2）

> **职责**：一条脚本把本地环境从「MySQL80 已停 + Redis 未启动」拉到可用，并打印**独立探测**的连通状态。
> **不做**：不建库、不跑 Flyway、不启 `faze-api`、不启 RuoYi（这些是 A3 / 手工步骤）。
> **调用方**：根 README「启动」、`0-A6` 验收、段 1 起的开工前置。

## 1. 前置条件（实测版本，缺一项会卡在哪）

| 项 | 版本 / 状态 | 缺了卡在哪 |
|---|---|---|
| Java | 17.0.17（`JAVA_HOME=E:\jdk17`） | A3 编译与运行 |
| Maven | 3.9.16 | A3 |
| Node / npm | v24.16.0 / 11.13.0 | B 组 |
| MySQL | 8.0.44（Windows 服务 `MySQL80`，Automatic） | 本脚本退出码 4 |
| Redis | **tporadowski 移植版 5.0.14.1**（`E:\Redis-x64-5.0.14.1`，Windows 服务名 `Redis`） | 本脚本退出码 5；`0-A6` 的 `redis_version` 7.x 判定 |
| docker / yarn / Android SDK | **不需要** | — |

> **Redis 版本差距（必须知道）**：技术栈锁定 Redis 7（AGENTS §2），本机是 **5.0.14.1**（社区移植、无安全更新，协议 5.0）。段 0 的服务端不使用 Redis（`faze-api` 不引 `spring-boot-starter-data-redis`），本机只用于 `0-A6` 的「起得来 / `-Stop` 能停」与 RuoYi 登录态。**`0-A6` 的 `redis_version 7.x` 判定不满足**，处置见 `0-A6_gap.md`（需负责人书面接受或挂账），**不许写成「Redis 7 已就绪」**。

## 2. 第一次使用（4 步）

```powershell
# 1) 复制 env 模板并填口令（deploy/.env 已被 .gitignore 忽略）
Copy-Item deploy\.env.example deploy\.env
#    编辑 deploy\.env，填 FAZE_DB_PASSWORD（faze_app 的口令）

# 2) 建库与账号（只需一次；用 root 执行，口令按实际替换）
#    详见 docs/acceptance/seg0/A3/ 的建库记录

# 3) 注册 Redis 为 Windows 服务（只需一次，管理员 PowerShell）
& "E:\Redis-x64-5.0.14.1\redis-server.exe" --service-install "E:\dshcode\FAZE\deploy\redis-faze.conf" --service-name Redis

# 4) 起服务（管理员 PowerShell）
.\deploy\start-services.ps1
```

## 3. 参数说明

| 参数 | 类型 | 默认 | 行为 | 退出码 |
|---|---|---|---|---|
| （无） | — | — | 启动 MySQL80 + Redis，打印三项连通状态 | 0 全通 |
| `-Status` | switch | off | **只打印**，不启停任何服务 | 0（状态不通也返回 0，它不是门禁） |
| `-Stop` | switch | off | **只停 Redis**；MySQL80 保持 Running | 0 已停 / 3 非管理员 / 5 未安装 |
| `-SkipRedis` | switch | off | 跳过 Redis 的启动与探测，输出 `WARN`：「0-A6 未满足」 | 0（警告必打） |
| `-EnvFile` | string | `.env`（同脚本目录） | 指定 env 文件；相对路径**按脚本所在目录解析** | 2 文件不存在 |

参数优先级：`-Stop` > `-Status` > 默认启动；`-SkipRedis` 对 `-Stop` 无效（Redis 未装直接返回 5）。

## 4. 退出码（唯一判据）

| 码 | 含义 |
|---|---|
| 0 | 目标状态已达成 |
| 2 | `deploy/.env` 不存在，或 `FAZE_DB_PASSWORD` 为空 |
| 3 | 需要启停服务但当前不是管理员 |
| 4 | `MySQL80` 服务不存在 |
| 5 | Redis 服务未安装（且未加 `-SkipRedis`） |
| 6 | 端口在听，但 MySQL 认证失败（凭据错） |
| 7 | 端口被非目标进程占用（轮询超时） |
| 9 | **库名守卫失败**：`FAZE_DB_URL` 的库名不是 `faze` |

## 5. 故障排查（按症状索引）

| 症状 | 原因 / 处置 |
|---|---|
| 「停止/启动服务需要管理员」 | 当前不是管理员（退出码 3）。用管理员 PowerShell；脚本**不自动提权** |
| `Access denied` | `FAZE_DB_PASSWORD` 错或 `faze_app` 未授权（退出码 6）。用 root 复核 `SHOW GRANTS` |
| `6379 被占用` | 别的 Redis 实例在跑（退出码 7）。先 `Get-NetTCPConnection -LocalPort 6379` 找 PID |
| 「Redis 服务不存在」 | 未注册服务（退出码 5）。按 §2 第 3 步注册，或 `-SkipRedis` 先跑 MySQL |
| `Unsupported Database: MySQL` | 缺 `flyway-mysql` 依赖（A3 的 pom 必须有两个 flyway 构件） |
| `-Stop` 后 6379 仍通 | 不是本服务在听，退出码 7；不要直接 `Stop-Process` 猜 |

## 6. 与 `0-A6` 的对应（取证方式）

| 锚点要求 | 命令 | 证据 |
|---|---|---|
| 起点：MySQL 停 + Redis 未启动 | `Stop-Service MySQL80 -Force`；`Test-NetConnection 127.0.0.1 -Port 6379` | `docs/acceptance/seg0/0-A6/0-A6_before-<ts>.txt` |
| 两个服务都拉起并打印连通状态 | `.\deploy\start-services.ps1` | `0-A6_start-<ts>.txt` + 独立探测 `0-A6_ports-<ts>.txt` |
| `-Stop` 能停 Redis | `.\deploy\start-services.ps1 -Stop`（连跑两次验幂等） | `0-A6_stop-<ts>.txt` |
| Flyway 建出 2 张表 | `mvn -f services/faze-api/pom.xml -DskipTests flyway:info` + `show tables from faze` | `0-A6_flyway-<ts>.txt` / `0-A6_tables-<ts>.txt` |
| RuoYi 能登录并看到 `biz` 菜单 | 见 `pc/README.md` 的启动方式 | `0-A6_ruoyi-biz-menu-<ts>.png` |

**脚本自己的打印不作为证据**，证据只认独立探测（`Test-NetConnection` / `redis-cli PING` / `mysqladmin ping`）。

## 7. 明确不做

- 不建库、不建账号、不跑迁移（A3 与 root 手工步骤）；
- 不启 `faze-api` / RuoYi（A3、PC 侧各自启动）；
- 不用 docker / WSL；
- 不自动提权（UAC 子进程环境不可控，会制造「看起来成功」的假绿）。
