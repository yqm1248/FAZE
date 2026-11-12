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
---

## 6. 新增表之后必须做的事（`migrate.ps1`）

`faze_app` 是**逐表授权**（库级权限已清空），Flyway 建出新表后它默认**看不到新表**：
应用报「表不存在」，但用 root / `faze_migrate` 查一切正常——很容易被误判成代码 bug。
（2026-11-06 段 2 G1.1 踩过：12 张新表，`information_schema` 完全查不到。）

以后凡是新迁移里有 `CREATE TABLE`，**跑这一条就够**：

```powershell
.\deploy\migrate.ps1              # 重启 API 执行 Flyway → 同步授权 → 复核
.\deploy\migrate.ps1 -SkipRestart # 迁移已执行过，只补授权
```

退出码：`0` 成功 / `2` .env 缺失 / `3` 缺必需变量 / `4` 迁移失败 / `5` 授权同步失败 / `6` 授权复核有差异。

> 它内部就是 `restart-api.ps1` + `sync-db-grants.ps1`（两遍：写库 + `-Verify`），
> 没有重新实现授权逻辑，只是把「三步忘记一步就出事」的流程固化成一条命令。

---

## 8. 版本、灰度与回滚（第 8 段 F-2 产出）

> **这一节是方案，不是演练记录。** 截至 2026-11-13，本仓**没有任何一次真实发版**：
> 应用商店未提交（DEF-004 备案号未下来）、热更新通道未接（`updates.url` 刻意留空）、
> 支付与短信仍是 Mock。所以下面每一步都**没有在真实环境跑过**——第一次真实发版时
> 必须按这里的顺序走一遍并把结果补进本节，别把"写了方案"当成"验证过"。

### 8.1 版本号从哪来

唯一来源是业务库 `sys_config` 的两条：

| 键 | 作用 | 当前值 | 谁改 |
| --- | --- | --- | --- |
| `app.version.latest` | 最新版本号；App 启动时比对，低于它就弹「有新版本」 | `1.0.1`（**占位，发布前必须改**） | 运营在 PC「系统参数」页（A-08）改 |
| `app.version.downloadUrl` | 更新弹窗的下载地址；**当前为空串** | `""` | 同上 |

**为什么版本号不写在 App 里**：写在包里等于每次改版本都要发版，而"提示用户升级"恰恰是
旧版本才能做的事。所以版本号是服务端下发的运行时数据，`app.config.ts` 里不带它。

**改版本号的三步**（顺序不能反）：

1. `apps/mobile-app/app.json` / `app.config.ts` 的 `version` 改成新版本；
2. `sys_config` 的 `app.version.latest` 改成同一个值，`app.version.downloadUrl` 填真实下载页；
3. 打新的 EAS 构建并上传商店（见 8.3）。

漏第 2 步的表现：新包发出去，老用户永远不弹更新提示；漏第 3 步的表现：用户点了更新，
下载地址 404。

### 8.2 灰度策略（计划）

**当前没有灰度基础设施**（没有按用户分桶的后端开关，也没有商店的内测轨道）。所以第一次
发版只能走"商店内测 + 自我放量"，具体分三批：

| 批次 | 范围 | 怎么看没问题 | 出问题怎么办 |
| --- | --- | --- | --- |
| 第 1 批 | 项目组内 5~10 人（商店「内部测试」轨道） | 八个主链路各走一次：注册 / 登录 / 找律师 / 下单 / 支付 / 聊天 / 立案 / 文书 | 撤回测试包，不发第二批 |
| 第 2 批 | 伊犁本地 50~100 人（定向邀请） | 次日留存、崩溃率、下单成功率；**客服工单量**是最灵敏的指标 | 停在第二批，按 8.4 回滚 |
| 第 3 批 | 商店公开 | 同上 + 商店评分 | 同上 |

**为什么不用"按用户百分比放量"**：那要求后端能按用户分桶返回不同配置，本仓的
`sys_config` 是全局单值，做不到按人分桶。硬做一个分桶开关等于在收口段引入新的状态源
——所以这一版**不做**，写进遗留。

### 8.3 发版前置检查（每次发版逐条勾）

- [ ] `check:baseline` / `check:facts` / `check:render` / `check:i18n` / `check:copy-tone` 全绿
- [ ] `cd apps/mobile-app; npm run verify` 全绿（tsc + eslint + 三项检查）
- [x] `.i18n-pending.json` 为空（**2026-11-13 已达成**：补齐 bo/ug/kk 各 10 条 → `{"pending": {}}`；译文由 AI 产出、**未母语复核**，见 `docs/BASELINE.md` §7.85）
- [ ] `mvn test` 全绿（先注 `deploy/.env` 环境变量，否则集成测试上下文起不来）
- [ ] `sys_config` 的 `app.version.latest` / `downloadUrl` 已改
- [ ] 数据库备份已做（`.\deploy\backup.ps1`，产物在 `logs/backup/`）
- [ ] **上线前清测试数据**（DEF-011：`deploy/purge-test-data.ps1`，**本仓当前未做**）
- [ ] 商店材料齐（见 `docs/acceptance/seg8/F-1-商店材料清单.md`）

### 8.4 回滚步骤（可执行，不是"看情况"）

**回滚是分层的，先判断坏在哪一层**——三层混在一起回滚会把"能救的"也一起推下去。

| 症状 | 坏在哪层 | 回滚动作 |
| --- | --- | --- |
| App 打不开/闪退，接口正常 | 客户端 | **商店侧回滚**：把上一版 APK 重新提交并置为最新（各商店后台都支持「版本回退/重新发布历史版本」）。**没有热更新通道，只能靠商店审核**，所以这一步有 1~3 天延迟——这也是为什么第 1 批必须有内部测试轨道 |
| 接口 500 / 数据错 | 服务端 | **代码回滚**：`git revert <发版提交>` → `mvn package` → 用上一版 jar 重启（`deploy/start-api.ps1 -Package`）。**不要**用 `git reset --hard`，那会让后来人的工作丢失 |
| 迁移把数据改坏了 | 数据库 | **数据回滚**：从 `logs/backup/` 取发版前备份 → 按表恢复。**迁移本身不回滚**（Flyway 的 undo 在社区版不可用）——所以发版前那次备份是唯一的退路 |
| 只有某个配置错 | 配置 | 直接在 PC「系统参数」页改回（A-08 有 before/after 审计）；**不用发版、不用重启** |

**回滚后的验证**（三步，缺一步不算回滚完成）：

1. `http://127.0.0.1:8081/actuator/health` → `UP`；
2. 登录 + 下单主链路各走一次（用测试账号，不用真实用户）；
3. `scripts/monitor/data-integrity.sql` 的 A01~A20 逐条看 n 是否为 0。

### 8.5 本节没做的事（留给第一次真实发版）

1. **没有演练过**。上面每条都是按现有脚本与商店能力推导的，第一次真发必须补一份演练记录到本节；
2. **没有灰度基础设施**（按用户分桶的开关），第 2 批只能靠"人少"来控制风险；
3. **热更新通道没接**（`app.config.ts` 的 `updates.checkAutomatically='NEVER'` 是刻意设计），
   所以客户端问题只能等商店审核——这是有意的取舍，不是漏做：接了热更新就要接 EAS Update 的
   计费与合规审查，且"热更新绕过审核"本身有政策风险。

---

## 9. 本地测试环境怎么起（2026-11-13 实测通过，给别人测之前照这份走）

> **这一节是操作步骤，不是方案。** 下面 4 条命令都实际跑过，端口与返回码见表。

### 9.1 一共 4 个进程

| # | 是什么 | 端口 | 起法 | 谁去开 |
| --- | --- | --- | --- | --- |
| 1 | MySQL 80 + Redis | 3306 / 6379 | 管理员 PowerShell：`.\deploy\start-services.ps1` | 一次就够，常驻 |
| 2 | 业务 API `faze-api` | **8081** | `cd services\faze-api` → 先注入 `deploy/.env` 的变量（见 9.2）→ `mvn -o spring-boot:run -Dspring-boot.run.profiles=dev` | 第一个起 |
| 3 | PC 后端 RuoYi | **8080** | `cd pc\admin-server\ruoyi-admin` → `mvn -o spring-boot:run` | 可与 2 并行 |
| 4 | PC 前端 `admin-web` | **80** | `cd pc\admin-web` → `pnpm dev` | 3 起来之后 |
| 5 | APP（Expo Web） | **8090** | `cd apps\mobile-app` → `npx expo start --web --port 8090` | 最后 |

### 9.2 起 `faze-api` 前必须先注入环境变量（最常见的卡点）

`mvn test` / `spring-boot:run` 都需要 `deploy/.env` 里的变量（`FAZE_DB_URL` / `FAZE_DB_USERNAME` / `FAZE_DB_PASSWORD` / JWT 密钥等），否则上下文起不来。一条命令搞定：

```powershell
Get-Content deploy/.env -Encoding UTF8 | Where-Object { $_ -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $kv = $_ -split '=', 2; Set-Item -Path "env:$($kv[0].Trim())" -Value $kv[1].Trim()
}
cd services\faze-api
mvn -o spring-boot:run "-Dspring-boot.run.profiles=dev"
```

**不带 `-Dspring-boot.run.profiles=dev` 会直接启动失败**（`AdminTokenVerifier` 构造抛错），这是踩过的坑。

### 9.3 访问地址与账号

| 端 | 地址 | 账号 / 说明 |
| --- | --- | --- |
| PC 管理后台 | `http://localhost/`（**80 端口，不是 8080**） | `admin` / `admin123`（登录的是 `faze_admin` 库，**不是**业务库 `faze`）。验证码当前是**关闭**的（`/captchaImage` 返回 `captchaEnabled=false`），不用输 |
| APP | `http://localhost:8090/` | 走 Expo Web，浏览器即可；改语言去「我的 — 设置」 |
| 业务 API | `http://127.0.0.1:8081/api/v1/**` | PC 前端通过 `/biz-api` 前缀由 vite 代理到这里，**不需要**单独开跨域 |
| 健康检查 | `http://127.0.0.1:8081/actuator/health` | 返回 `{"status":"UP"}` 才算起好 |

**真机测 APP**：手机要能访问 PC 局域网 IP，`apps/mobile-app/.env` 的 `EXPO_PUBLIC_API_BASE_URL` 填 `http://<PC-IP>:8081`（当前填的是 `http://192.168.43.186:8081`，换网络要跟着改），Windows 防火墙要放行 8081。测不通时先在**手机浏览器**打开 `http://<PC-IP>:8081/api/v1/pub/ping`。

### 9.4 三个必踩的坑

1. **Expo 默认想占 8081**，而 8081 被 `faze-api` 占了 → 会弹「端口被占用，换一个？」。**必须显式 `--port 8090`**，否则一路错下去。
2. **PC 前端是 80 端口**，不是 8080。8080 是 RuoYi 后端，浏览器打开 8080 看到的是接口 JSON 不是页面。
3. **改完库里的菜单（`sys_menu`）要刷新浏览器**，RuoYi 的动态路由由 `getRouters` 现查数据库，不会热更新。

### 9.5 怎么关

按启动的逆序 `Ctrl+C` 停掉 2/3/4/5 各自的窗口；MySQL 与 Redis 是 Windows 服务，不用管（要停用 `.\deploy\start-services.ps1 -Stop`，只停 Redis）。

---

## 10. PC 后台的菜单怎么改（含「隐藏项怎么恢复」）

> 2026-11-13 加了这一节，因为当天重整过一次菜单结构，而**菜单是数据库数据、不是代码**——
> 接手的人如果不知道恢复路径，会以为菜单只能靠改代码。

### 10.1 菜单存在哪、谁在管

| 事实 | 说明 |
| --- | --- |
| 菜单表 | `faze_admin.sys_menu`（**不是**业务库 `faze`） |
| 角色绑定 | `faze_admin.sys_role_menu` |
| 谁渲染 | RuoYi 前端登录后调 `GET /getRouters`，**现查数据库**生成动态路由。改完库**刷新浏览器**才看得到 |
| 图形入口 | 后台「系统管理 → 菜单管理」。**这个菜单按要求故意保留没藏**，就是留着改菜单用的 |
| 批量改的脚本 | `database/seed/admin_menu_ia.sql`（幂等，可反复执行） |

### 10.2 现在的结构（2026-11-13 之后）

一级 11 个：数据看板 / 用户管理 / 律师管理 / 订单管理 / 财务管理 / 内容管理 / 案件管理 /
投诉工单 / 消息管理 / 系统设置 / 系统管理 / 系统监控。前 9 个是法泽业务，最后 2 个是若依原生。
每个一级下面是页面（二级）。完整 URL 清单在 `database/seed/admin_menu_ia.sql` 文末 §17。

### 10.3 被隐藏的 7 项（数据还在，只是 `visible='1'`）

| menu_id | 名称 | 为什么藏 |
| --- | --- | --- |
| 3 | 系统工具 | 负责人点名隐藏（表单构建 / 代码生成 / 系统接口，开发工具，生产不该给管理员看到） |
| 4 | 若依官网 | 外链到 `http://ruoyi.vip`，负责人点名隐藏 |
| 100 | 用户管理 | 若依的**管理员账号**管理，不是法泽的 C 端用户（C 端在「用户管理 → 用户列表」）。负责人点名隐藏 |
| 101 | 角色管理 | 负责人点名隐藏 |
| 103 | 部门管理 | 负责人点名隐藏 |
| 2100 | 法泽业务 | 旧的一级容器，子项已全部拆到新目录，留着会出现空目录 |
| 2101 | 业务总览 | 登录后 `/index` 已经是同一个数据看板，留着会出现两个入口 |

**恢复任意一项**（把 `visible` 改回 `'0'`，再刷新浏览器）：

```sql
UPDATE faze_admin.sys_menu SET visible = '0' WHERE menu_id = 100;
```

**注意这是「隐藏」不是「禁止访问」**：RuoYi 的超级管理员（`role_key='admin'`）本身绕过菜单可见性校验，
所以直接敲 URL（如 `http://localhost/system/user`）仍然能进。这是框架行为，不是漏配。
真要封死得换非超管角色或改框架，不建议——留着这些入口，万一「菜单管理」本身出问题还能兜底。

### 10.4 改菜单会不会弄断页面跳转

**会。** RuoYi 的前端 URL = 一级目录 `path` + 页面 `path`（Vue Router 嵌套路由）。
2026-11-13 重整时所有法泽页面的 URL 前缀从 `/biz/**` 换成了各自目录的 path
（如 `/biz/trade/order` → `/order/list`），因此同步改了 **2 处**硬编码：

1. 前端 14 处 `router.push('/biz/...')` —— 见 `admin_menu_ia.sql` §17；
2. `services/faze-api` 的 `AdminDashboardService` 里 5 条 `link` 字段（「待我处理」的跳转地址）。

**以后再改菜单 path，这两处都得跟着改**，否则点了会跳 404。

### 10.5 起新目录时先查一个坑：path 会不会撞 RuoYi 的内置路由

RuoYi 的 `constantRoutes` 里有这些**静态**路径：`/login` `/register` `/lock` `/redirect` `/401`
`/system/user-auth` `/system/role-auth` `/system/dict-data` `/monitor/job-log` `/tool/gen-edit`
以及**个人中心 `/user`**。

Vue Router 同 path 的路由**按注册顺序匹配**，静态路由先注册，所以新建一级目录若用了 `user`，
`/user/list` 会先落到静态 `/user`（个人中心，children 只有 `profile/:activeTab?`）→ **直接 404**，
而且菜单上看起来一切正常，只有点进去才发现。2026-11-13 就踩了这个：23 个页面里只有 2 个 404。

所以用户管理目录的 path 定为 **`members`**。起新目录时按这个清单先排一遍。

---


