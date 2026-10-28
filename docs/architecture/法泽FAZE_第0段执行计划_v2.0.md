# 法泽 FAZE 第 0 段执行计划（V2.0 · 可执行版）

> **上位文档**：《法泽FAZE_分期交付计划_v1.0.md》第三节
> **配套定稿**（缺一不可）：
> - 表结构 → [`法泽FAZE_V1基线表结构_v1.0.md`](./法泽FAZE_V1基线表结构_v1.0.md)
> - 错误码 → [`法泽FAZE_错误码规范_v1.0.md`](./法泽FAZE_错误码规范_v1.0.md)
> - **执行顺序 → [`法泽FAZE_第0段串行执行序列_v1.0.md`](./法泽FAZE_第0段串行执行序列_v1.0.md)**
>
> ⚠️ **本文件第四节的「4 批 / 可并行」表述已作废。** 项目负责人 2026-10-28 明确要求**不要多阶段并行**，
> 执行顺序一律以《第0段串行执行序列_v1.0》为准。本文件保留的是**任务细节**（前置 / 步骤 / 产出 / 验收 / 失败出口），顺序以串行序列为准。
>
> **取代**：`法泽FAZE_第0段任务分解_v1.0.md`（v1.0 含 docker 方案，已作废）
> **生成日期**：2026-10-28
> **状态**：可开工。前置状态见 2.3 节（真机与豆包 key 分别到 T0-31 / T0-33 才需要）
> **本版依据项目负责人 2026-10-28 的决定重写**

---

# 一、本版相比 v1.0 的六处改动

| # | v1.0 的写法 | V2.0 改为 | 依据 |
|---|---|---|---|
| 1 | 第 0 段用 `docker compose` 起 MySQL/Redis/RabbitMQ | **完全不引入 docker**。MySQL 用已装的系统服务，Redis 用 `E:\Redis-x64-5.0.14.1`（已实测 `PONG`），都按需依次启动；RabbitMQ 第 0 段用不到 | 项目负责人明确不用 docker |
| 2 | 原型代码归档保留 | **删除，前端从 Expo 官方模板重写** | 项目负责人决定抛弃壳子 |
| 3 | 域名归属待确认，可能要做 App Links | **不做 App Links**。scheme 用 `faze://`，域名等装 APK 时再买（不影响第 0 段，也不影响将来加 App Links） | 项目负责人暂无域名 |
| 4 | 双角色 token 待定 | **方案 A：单 token + `roles` claim** | 项目负责人选定 A |
| 5 | 包管理器待定 | **锁定 npm**，不引入 pnpm | 项目负责人选定 npm |
| 6 | 四项硬阻塞（docker / 真机 / key / 法条数据） | **只剩一项需你提供**（MySQL 密码 + 手机连线）。真机有、豆包 key 有；**法条种子数据我自己写**（民法典条文公开，我录入 5 条真实条文） | 你的答复 + 我的处理 |

**结果：24 个任务里，原先 6 个被阻塞，现在 0 个被技术阻塞，2 个等你给东西（密码、手机）。**

---

# 二、环境实况（2026-10-28 实测）

## 2.1 已就绪

| 组件 | 版本 / 状态 | 位置 |
|---|---|---|
| MySQL | **8.0.44**，服务 `MySQL80` 状态 **Running**，3306 端口通 | `C:\Program Files\MySQL\MySQL Server 8.0\` |
| Java | 17.0.17 LTS | 系统 PATH |
| Node | v24.16.0 | `E:\uniapp1\node.exe` |
| npm | 11.13.0 | `E:\uniapp1\npm.ps1` |
| adb | platform-tools（winget 装） | `%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_...\platform-tools\adb.exe` |
| eas-cli | 22.0.0（提示可升级 24.14.0） | `%APPDATA%\npm\eas.ps1` |
| Redis | **5.0.14.1**（Windows 移植版），6379 已实测 `PING` → `PONG` | `E:\Redis-x64-5.0.14.1\redis-server.exe` |
| 磁盘 | C 25.3 GB 空闲 / D 281.1 GB / E 89.2 GB | — |

## 2.2 缺失但第 0 段不需要

| 组件 | 状态 | 为什么不需要 |
|---|---|---|
| Docker | 无 | 按你要求不用 |
| RabbitMQ | 无 | 第 0 段没有延迟任务 |
| ANDROID_HOME | 空 | 走 EAS 云构建，不需本地 SDK |
| 域名 | 无 | 第 0 段用 `faze://` |

## 2.3 前置状态（2026-10-28 更新）

| 前置 | 状态 |
|---|---|
| **MySQL root 密码** | ✅ **已提供**（`123456`）。T0-13 会另建 `faze_app` 专用账号，应用不用 root |
| **Android 真机** | ⏸ **项目负责人决定暂不连接**。当前 `adb devices` 实测为空。设备级任务（T0-29 真机项、T0-31、T0-32）与验收 4/7/8/9 推到 **T0-31 之前**处理 |
| **豆包 API key** | ✅ 项目负责人表示有，到 T0-33 时提供 |

> **真机这条要特别提醒**：验收 4（装 APK）、验收 8（杀进程重启仍 RTL）、验收 9（藏文真机渲染）**只能靠真机**。模拟器会被取证脚本判定为不合格（`ro.kernel.qemu=1`），不算通过。

---

# 三、需要现场确认的技术判断（我会在开工时先说结论，不默默改）

| 项 | 待确认内容 | 我会怎么处理 |
|---|---|---|
| MySQL 字符集 | `utf8mb4_0900_ai_ci` 上能否建 `FULLTEXT ... WITH PARSER ngram` | 先实测；失败则退 `utf8mb4_unicode_ci`，并把结论写进 ADR |
| `ngram_token_size` | MySQL 8.0 默认 2，只读参数 | 默认 2 正好够中文双字检索，**不改 my.ini**（改了要重启服务） |
| node 路径 | `E:\uniapp1\node.exe` 是 uniapp 项目自带环境，可能没配套 npm 全局目录 | 开工先跑 `where node` / `where npm` / `npm root -g` 核对，异常则先修再动 |
| eas-cli 版本 | 22.0.0 偏旧，提示可升 24.14.0 | 先不升；构建失败且报版本相关错误再升 |

---

# 四、任务总表（24 个，按执行顺序）

**依赖**：第 0 批 → 第 1 批 →（第 2 批 ∥ 第 3 批）→ 第 4 批

## 第 0 批：清场与冻结（3 个）

| 编号 | 任务 | 产出 |
|---|---|---|
| T0-01 | 环境核对与工程目录清场 | 环境报告 + `apps/mobile-app` 清空 |
| T0-02 | 从 Expo 官方模板重建移动端工程 | 可启动的干净 Expo 54 工程 |
| T0-03 | 不可逆决策冻结 ADR | `docs/adr/ADR-000-不可逆决策总表.md` |

## 第 1 批：仓库与服务端（5 个）

| 编号 | 任务 | 产出 |
|---|---|---|
| T0-11 | 仓库布局定型 | 顶层目录 + 各目录 README |
| T0-12 | 目录边界检查器 | `check-boundaries.mjs` / `check-route-shell.mjs` |
| T0-13 | 建库 + Flyway V1 基线 | `V1__baseline.sql`（`app_user` + `law_item`） |
| T0-14 | 服务端骨架 | `R` / 全局异常 / `TraceIdFilter` / `/api/v1/ping` |
| T0-15 | CI 流水线 | `.github/workflows/ci.yml` + `verify.ps1` |

## 第 2 批：移动端地基（9 个）

| 编号 | 任务 | 产出 |
|---|---|---|
| T0-21 | Expo 工程配置定型 | `app.config.ts` / `tsconfig.json` / scheme |
| T0-22 | ESLint 规则集 + 自检 | 9 条规则 + RuleTester + `gate-selfcheck.mjs` |
| T0-23 | 路由树定稿 | `app/**` 分组 + `routes.ts` |
| T0-24 | 网络层唯一出口 | `shared/api/client.ts` |
| T0-25 | 鉴权态 + 单飞刷新 | `tokenStore.ts` / `refresh.ts` / `useAuth.ts` |
| T0-26 | LocalizedText | 按 grapheme 切 run 的多字体组件 |
| T0-27 | LtrText + Icon 镜像 | bidi 隔离 + 镜像白名单 |
| T0-28 | 错误边界 + 页面四态 | 通用组件 |
| T0-29 | 语言切换页 + 软重启 | `LanguagePage.tsx` / `languageService.ts` |

## 第 3 批：三项探针（4 个）

| 编号 | 任务 | 产出 |
|---|---|---|
| T0-31 | P-1 APK 构建 | `eas.json` + 真机安装 + 证据 |
| T0-32 | P-2 字体 | `check-font.py` + 字体资产 + 真机渲染证据 |
| T0-33 | P-3 豆包 SSE | `/api/v1/ops/probe/ai` |
| T0-34 | P-3 法条检索 | `V2__init_law_items.sql`（5 条真实条文）+ `/probe/law` |

## 第 4 批：验收收口（3 个）

| 编号 | 任务 | 产出 |
|---|---|---|
| T0-41 | 真机取证脚本 | `tools/evidence/collect.ps1` |
| T0-42 | 13 条验收执行 | `docs/acceptance/D0-checklist.md` + `evidence/` |
| T0-43 | 冻结清单签字 + CHANGELOG | `DECISIONS_FROZEN.md` + 推送记录 |

---

# 五、逐任务执行细节

> 写法：每个任务给「前置 / 步骤（命令级）/ 产出 / 验收 / 失败出口」。

## 第 0 批

### T0-01 环境核对与工程目录清场

**前置**：无
**步骤**：
1. 核对 node 链路：`where node`、`where npm`、`npm root -g`、`npm config get registry`
2. 核对 Java：`java -version`、`mvn -v`（若无 mvn，用 Maven Wrapper）
3. 核对 MySQL：`mysql -u<user> -p -e "SELECT VERSION(), @@character_set_database, @@ngram_token_size"`
4. 核对真机：`adb devices -l`，必须看到设备且状态为 `device`（不是 `unauthorized`）
5. 记录全部输出到 `docs/ops/env-baseline-2026-10-28.md`

**产出**：环境基线文档（含每条命令的原始输出）
**验收**：五条命令全部有输出且无报错；真机状态为 `device`
**失败出口**：真机 `unauthorized` → 手机上点「允许 USB 调试」；`npm` 不可用 → 先修 node 安装；MySQL 连不上 → 向你要密码

### T0-02 从 Expo 官方模板重建移动端工程

**前置**：T0-01
**步骤**：
1. 保留 `docs/design/screenshots/` 12 张截图（视觉参考）
2. `apps/mobile-app` 整体删除（含 `app/`、`features/`、`shared/`、`assets/legacy/`、`node_modules`、`package-lock.json`）
3. `npx create-expo-app@latest apps/mobile-app --template blank-typescript`（SDK 54）
4. `npm install`，确认 `npx expo --version` 为 SDK 54
5. 用**独立的 git 提交**记录这次删除，commit message 写明「删除原型代码，理由见第 0 段计划」——**代码虽删，历史可查**
6. 把 `design/tokens/tokens.json` 复制为 `apps/mobile-app/shared/theme/tokens.json`（令牌是资产，要沿用）

**产出**：干净 Expo 54 工程；一次「删除原型」的独立提交
**验收**：`apps/mobile-app` 下 tsx 文件数 = 3（模板自带的 App.tsx 等）；`npm run lint` 无违规；`git log` 能查到删除提交
**失败出口**：模板版本不对 → 指定 `--template blank-typescript@sdk-54`

### T0-03 不可逆决策冻结 ADR

**前置**：无（可立即做）
**步骤**：写 `docs/adr/ADR-000-不可逆决策总表.md`，每条四栏（决策 / 备选及否决理由 / 为什么选它 / 什么条件下该推翻）。至少 12 条：

| 决策 | 值 |
|---|---|
| Android 包名 | `com.faze.app` |
| URL scheme | `faze://`（第 0 段主用；将来加 App Links 不冲突） |
| 分享域名 | 暂无，装 APK 前购买 |
| 仓库形态 | 单仓：`apps/mobile-app` + `apps/admin` + `services/faze-api` |
| 依赖方向 | `app → features → shared`，反向视为设计错误 |
| 路由树 | 见 T0-23 |
| locale 集合 | `zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN` |
| API 前缀 | `/api/v1` |
| 包管理器 | **npm**（不引入 pnpm） |
| 鉴权模型 | **单 token + `roles` claim**，不拆两套 |
| 鉴权失败语义 | **HTTP 401**（业务失败仍 200 + code） |
| 错误码格式 | **6 位 `[C][DD][NNN]`** |
| Flyway 分段 | 第 0 段 `V1–V99`，第 n 段 `V{n}00–V{n}99` |
| RuoYi 归属 | `apps/admin/`（仓库内，才能机器检查「不改框架层」） |

**验收**：14 条全部四栏无空项；每条能被后续段引用
**失败出口**：业务侧未定的（如域名）标 `TBD` + 触发条件

---

## 第 1 批

### T0-11 仓库布局定型

**前置**：T0-02
**步骤**：
1. 建目录：`services/`、`deploy/`、`database/`、`archive/`、`docs/adr/`、`docs/acceptance/`、`docs/ops/`、`tools/`
2. 根 `package.json` 只放编排脚本（`npm run lint`、`npm run api` 等），**不声明 workspaces**（避免 Metro 向上解析出幽灵依赖）
3. `deploy/start-services.ps1`：**按你要求写的「依次启动服务」脚本**（不用 docker，逐个起）：
   - ① 检查 `MySQL80` 服务，未运行则 `Start-Service MySQL80`，轮询 3306 直到可连
   - ② 检查 6379，未监听则 `Start-Process E:\Redis-x64-5.0.14.1\redis-server.exe "redis.windows.conf" -WindowStyle Hidden`，轮询 `redis-cli PING` 直到返回 `PONG`
   - ③ 依次打印每项的端口、版本、连通状态（跑完就能看出谁没起来）
   - ④ 支持 `-Stop` 反向停止（停 Redis 进程；MySQL 是系统服务，只提示不擅自停）
   - RabbitMQ 将来需要时在这里追加，**不做容器**
4. 各目录写 README 说明职责边界

**产出**：目录结构 + 编排脚本 + 各 README
**验收**：`deploy/start-services.ps1` 能从停止状态把 MySQL 拉起来；`npm run lint` 能从根目录调用到移动端
**失败出口**：无

### T0-12 目录边界检查器

**前置**：T0-11
**步骤**：写 `scripts/arch/check-boundaries.mjs`：
1. 扫 `apps/mobile-app` 下所有 `.ts/.tsx` 的 import
2. 规则：`app/` 不得 import `features/*/components`（只能 import `pages`）；`features/*` 不得 import 另一个 `feature`；`shared/` 不得 import `features/` 或 `app/`
3. 违规输出 `文件:行 实际import → 规则`，退出码 1
4. 另写 `check-route-shell.mjs`：`app/**/*.tsx` 的 AST 只允许 ImportDeclaration + 一条 ExportDefaultDeclaration，且源必须是 `features/*/pages/*`

**产出**：两个检查脚本 + 接入 `npm run lint`
**验收**：**注入 3 个违规再撤销**——① feature 互引 ② shared→features ③ `app/` 里写 `useState`。三个都必须被拦且指到行；撤销后必须绿
**失败出口**：AST 解析边界误报 → 先把规则收窄到明确违规形态，不放过任何一条真违规

### T0-13 建库 + Flyway V1 基线

**前置**：T0-01（要 MySQL 密码）
**步骤**：
1. 建两个库（**分库，避免 Flyway 与 RuoYi 的 `sys_*` 表打架**）：
   - `faze` —— 业务库，字符集 `utf8mb4`，排序规则先试 `utf8mb4_0900_ai_ci`
   - `faze_admin` —— RuoYi 专用库（第 1 段才用，本段先建空库）
2. 建专用账号（**不用 root 跑应用**）：`faze_app`@`localhost`，只授权 `faze.*`
3. `V1__baseline.sql` 只建两张表：
   - `app_user`（19 字段，含 `birth_date` / `is_minor` / `guardian_phone` / `deleted_at`）
   - `law_item`（12 字段，含 `FULLTEXT KEY ft_law_content (content) WITH PARSER ngram`）
4. 配 `spring.flyway.locations=classpath:db/migration`、`baseline-on-migrate=false`、`clean-disabled=true`

**产出**：`V1__baseline.sql` + 建库脚本 `database/init-db.sql`
**验收**：空库启动 Spring Boot 后 `flyway_schema_history` 恰 1 行 `success=1`；二次启动 0 新增；`SHOW CREATE TABLE law_item` 含 `WITH PARSER ngram`；`SELECT @@ngram_token_size` = 2
**失败出口**：`0900_ai_ci` 建 FULLTEXT 失败 → 改用 `utf8mb4_unicode_ci`（记入 ADR）；Flyway 因库非空报错 → **不用 `baselineOnMigrate` 兜底**，它会跳过建表掩盖故障，改清库重跑

### T0-14 服务端骨架

**前置**：T0-13
**步骤**：
1. `services/faze-api`（Spring Boot 3.5，Maven）
2. 包结构只建 `common`（`R` / `exception` / `constants` / `enums`）、`config`、`security`、`modules/ops`。**其余 9 个域不建空包**——空包只会诱导人往里塞代码
3. `R`：`{code, msg, data, traceId}`
4. `TraceIdFilter`：生成 traceId → MDC → 响应头 `X-Trace-Id`
5. `GlobalExceptionHandler`：覆盖参数校验、业务异常、鉴权异常、未捕获异常
6. `GET /api/v1/ping` 返回 `R.ok({pong:true})`

**产出**：可运行的服务端骨架
**验收**：`curl -i http://localhost:8080/api/v1/ping` 返回四字段，**且响应头 traceId 与 body 一致**；故意抛异常返回统一错误体而非 Spring 默认页
**失败出口**：MyBatis-Plus 3.5.12 与 Boot 3.5 不兼容 → 先只用 spring-jdbc，第 1 段再引入 MP

### T0-15 CI 流水线

**前置**：T0-02、T0-14
**步骤**：`.github/workflows/ci.yml` 四个 job：
- `mobile-lint`：`npm ci` → `tsc --noEmit` → `eslint . --max-warnings=0` → `gate-selfcheck`
- `arch-boundary`：跑 T0-12 两个脚本
- `api-build`：`mvn -B verify`
- `smoke`：起 MySQL service container → 跑 Flyway → `curl /api/v1/ping`

另写 `scripts/ci/gate.ps1`，在本地跑同一组命令（CI 与本地必须一致）

**产出**：CI 配置 + 本地等价脚本
**验收**：**负向证明**——故意写一个 `any` → CI 红；故意删掉任一关键 lint 规则 id → CI 红；把 tsconfig 的 `strict` 改 false → `tsc` 报错。三者都必须红，才算「门会拦」
**失败出口**：仓库未启用 Actions → 用 `gate.ps1` 做硬门 + 人工阻断，并明确记录降级（这是能力下降，要让你知道）

---

## 第 2 批

### T0-21 Expo 工程配置定型

**前置**：T0-02
**步骤**：
1. `app.config.ts`：`name` / `slug` / `scheme: 'faze'` / `android.package: 'com.faze.app'` / `android.intentFilters`（custom scheme，`autoVerify: false`）
2. `tsconfig.json`：`strict` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` + `noImplicitOverride` + 路径别名 `@/*`
3. 装依赖：`expo-router`、`expo-secure-store`、`expo-updates`、`expo-font`、`expo-localization`、`react-native-safe-area-context`
4. `typedRoutes` 开启

**产出**：工程配置 + 依赖清单（登记到 `docs/architecture/依赖清单.md`）
**验收**：`npx expo config --type public` 的 `scheme` / `android.package` 与 ADR 一字不差；`tsc --noEmit` 通过
**失败出口**：`expo-updates` 与 dev client 冲突 → 先不启用 updates，第 3 批 P-1 时一起配

### T0-22 ESLint 规则集 + 自检

**前置**：T0-21
**步骤**：按**白名单思路**建规则（比黑名单可靠）：
1. 禁从 `react-native` 导入 `Text` / `TextInput`（`no-restricted-imports`）——精确、零误报
2. 自定义规则 `faze/no-raw-text`：兜底 JSX `<Text>`（白名单仅 `shared/components/LocalizedText.tsx`、`AppTextInput.tsx`）
3. 自定义规则 `faze/no-physical-direction`：`left|right|marginLeft|marginRight|paddingLeft|paddingRight|border*Left*` 在 `StyleSheet.create` 第一层对象与 JSX `style` 内报错
4. `faze/no-color-literal`：`rgba(` / `#RRGGBB`（白名单 `shared/theme/tokens.ts`）
5. `faze/no-bidi-control`：扫 U+200E/F、U+202A–202E、U+2066–2069
6. `@typescript-eslint/no-explicit-any`、`ban-ts-comment`、`no-double-assertion`（禁 `as unknown as`）
7. `faze/file-header`：文件首注释块须含「职责/调用方/数据来源/注意事项」
8. `switch-exhaustiveness-check`（错误码到文案必须穷尽）
9. `no-floating-promises`、`react-hooks/exhaustive-deps`

配套：`tools/eslint/__tests__/` 每条规则 ≥1 valid + 2 invalid；`scripts/gate-selfcheck.mjs` 对每个违规 fixture 断言 lint 退出码非 0

**产出**：规则实现 + 用例 + 自检脚本
**验收**：`npm run test:lint-rules` 全绿；`gate-selfcheck` 通过；`rg 'marginLeft|paddingRight' apps/mobile-app` 命中 0
**失败出口**：`eslint-plugin-react-native` 在 ESLint 9 flat config 下不可用 → 全部改自研规则（工作量大但可控）

### T0-23 路由树定稿

**前置**：T0-21
**步骤**：定 `app/` 分组树：`(auth)/`（登录/注册/忘记密码）、`(tabs)/`（首页/律师/AI/我的）、`lawyer/`、`case/`、`document/`、`order/`、`settings/`（含 `language`）。所有页面在 `features/<domain>/pages/` 实现，`app/` 只放一行 re-export。写 `shared/navigation/routes.ts` 汇总全部路由常量 + 参数类型。

**验收**：`tsc --noEmit` 通过；`check-route-shell.mjs` 通过；`npx expo start` 能进到语言切换页
**失败出口**：expo-router 分组与 typedRoutes 冲突 → 关 typedRoutes 但保留 `routes.ts` 常量

### T0-24 网络层唯一出口

**前置**：T0-23
**步骤**：`shared/api/client.ts`：
1. `request<T>(path, options)` 统一出口，注入 baseUrl（`app.config.ts` 的 extra）
2. 错误码 → i18n key 映射表（与 T0-14 的错误码对齐）
3. 金额格式化（整数分 ↔ 展示）
4. `beforeSend` 脱敏（token、手机号、身份证不进日志）
5. 暴露钩子 `setRefreshHandler()` / `setOnAuthLost()`（供 T0-25 注册，**避免 shared 反向依赖 features**）

**验收**：全仓除 `client.ts` 外无直接 `fetch`（lint 强制）；断网时抛结构化错误而非裸异常
**失败出口**：无

### T0-25 鉴权态 + 单飞刷新

**前置**：T0-24
**步骤**：
1. `tokenStore.ts`：SecureStore 分 key 存 `faze.v1.access` / `faze.v1.refresh` / `faze.v1.deviceId`；非敏感的 `activeRole` / `displayName` 放 AsyncStorage
2. 读写走**模块级串行队列**（Keychain 解密慢，冷启动并发读多 key 会拉长首屏）
3. `refresh.ts`：模块级 `inflightRefresh`——所有 401 await 同一 Promise，**只刷一次**
4. 原请求最多重放 1 次；**POST/PATCH/DELETE 不自动重放**（防重复下单），直接抛给业务层
5. 刷新失败：先删 refresh 再删 access → 广播 `auth:lost` → 取消 in-flight → `router.replace('/login')`
6. 白名单不拦截：`/auth/login`、`/auth/refresh`
7. **token 黑名单存 Redis**（`faze:auth:blacklist:{jti}`，TTL = token 剩余有效期）。用 `StringRedisTemplate` 即可；**第 0 段不引入 Redisson** — 只在需要分布式锁时才需要它，留到那时再验兼容性

**验收**：mock 端 5 个并发请求同时 401，`/auth/refresh` **只被调用 1 次**；refresh 返回 401 时只跳登录 1 次且无闪屏；POST 收到 401 不被重放
**失败出口**：SecureStore 在真机偶发解密失败 → 重试 1 次，仍失败则清 key 要求重新登录（**绝不静默降级到明文**）

### T0-26 LocalizedText

**前置**：T0-21
**步骤**：
1. `shared/text/scriptRun.ts`：**按 grapheme cluster 切分，不按码点**（码点级会拆散藏文 base+下加字、阿拉伯 harakat，症状是「断笔」而非豆腐，会让人误判成字体缺 GSUB 白换一轮字体）
2. 每个 cluster 取首码点 Unicode Script；`Common/Inherited`（空格/数字/标点）并入前一个 run；合并同 script
3. `LocalizedText.tsx`：外层 `<Text>` 承接 `style`/`numberOfLines`/`onPress`，每个 run 一个子 `<Text>`；**只给 tibetan/arabic run 设 fontFamily，han/latin 不设**（不设即走系统 fallback，这是绕开「RN 无回退链」的正解）
4. `useMemo` by `[text, lang]`；列表场景加 LRU
5. 探针：`typeof Intl.Segmenter === 'undefined'` 则降级自带 `grapheme-splitter`

**验收**：单测覆盖 `བསྒྲུབས`、`بسم`、中藏混排、emoji ZWJ、纯数字；run 边界断言通过；`numberOfLines`/`onPress` 透传不丢
**失败出口**：`Intl.Segmenter` 在 Hermes 不可用 → 降级实现（已留探针）

### T0-27 LtrText + Icon 镜像

**前置**：T0-26
**步骤**：
1. `LtrText.tsx`：优先方案 A——不加控制符，把「标签/值」拆成两个 Text，值单独成段；不行才退方案 B（内部注入 U+2066/2069，代价是复制文本被污染，记 ADR）
2. `Icon.tsx` + `iconMirror.ts` 白名单：**镜像**（返回/前进箭头、send、reply、undo/redo、text-align-start/end）；**绝不镜像**（play/pause、plus、close、check、search、clock、camera、lock、heart、trash、settings、logo）；未登记默认不镜像（fail-safe）

**验收**：RTL 下 `1,234.56`、`2026-10-28`、`FAZE20261028001` 顺序正确（真机 + `adb shell` 复制结果双验）；返回箭头 RTL 下朝右；黑名单图标 RTL/LTR 像素一致
**失败出口**：方案 A/C 实测失效 → 降到 B 并在 ADR 记录复制污染

### T0-28 错误边界 + 页面四态

**前置**：T0-24
**步骤**：通用 `ErrorBoundary`（捕获渲染异常，展示「出错了 + 重试」）+ 四态容器组件 `PageState`（正常 / 加载 / 空 / 错误），暴露「鉴权失效」特例出口（401 登出不应落进通用错误页）

**验收**：断网进错误边界，logcat 无 `FATAL EXCEPTION`，进程 pid 未变
**失败出口**：无

### T0-29 语言切换页 + 软重启

**前置**：T0-25、T0-26
**步骤**：
1. `LanguagePage.tsx`：4 语言单选，当前选中态
2. `languageService.ts`：写持久化 → 算 wantRTL → **仅当 `wantRTL !== I18nManager.isRTL` 才** `allowRTL(true)` + `forceRTL(wantRTL)` + 写 `faze.rtl_applied` → `Updates.reloadAsync()`
3. 启动时只比对 `rtl_applied` 与 `isRTL`，**不一致才 reload 一次**（防死循环）
4. reload 期间**屏蔽 401 拦截跳登录**（防竞态把人踢回登录页）
5. `catch` 时弹「需要手动重启」提示

**验收**：切 ug 后杀进程重启仍 RTL；**连切 5 次无循环**；断网切换不崩
**失败出口**：`reloadAsync` 在 dev build 下 no-op（不确定）→ 只在 release APK 验收，dev 期用 `adb shell am force-stop` 手工验证

---

## 第 3 批

### T0-31 P-1 APK 构建

**前置**：T0-21、真机连接
**步骤**：
1. `eas.json` 加 `preview` profile：`distribution: internal`、`android.buildType: apk`
2. `eas login` → `eas init`（写 `extra.eas.projectId`）
3. `npx eas build -p android --profile preview --non-interactive`
4. 下载 APK → 记 sha256 → `adb install -r` → `adb shell dumpsys package com.faze.app | findstr versionName`
5. 全程记录到 `docs/ops/P-1-决策与证据.md`

**⚠ 先纠正一个说法**：`dev-client` **不是** EAS 之外的第三条路，它只是产物形态，仍要靠 EAS 或本地 Gradle 出包。真正的备选只有两条：**EAS 云构建** 或 **本地装 Android SDK + Gradle**。本机无 SDK，所以先走 EAS。

**失败模式与判断**：EAS 排队/额度/网络/登录态失效 → 收集**可区分**的错误再决定；仅当连续两次是「网络/额度」类失败才转本地 SDK；配置类错误先修 EAS 路线
**验收**：`adb install -r` 成功；`dumpsys package` 的 versionName 与产物一致；APK sha256 存档
**失败出口**：EAS 连续两次额度/网络失败 → 装 cmdline-tools（下载后先读 `npx expo prebuild` 生成的 `android/` 里实际要求的 compileSdk/buildTools/NDK 版本再装，**不猜版本**）

### T0-32 P-2 字体

**前置**：T0-31（要真机）
**步骤**：
1. `scripts/check-font.py`（fontTools）：断言每个字体文件含 `GSUB` + `GPOS` 表、字形数、以及覆盖我们需要的码点（藏文叠字组合、阿拉伯 4 种形态）
2. 候选字体（**均需脚本 PASS 才准进包**）：藏文 Noto Serif/Sans Tibetan；维/哈 Noto Naskh/Sans Arabic
3. **先跑脚本，再上真机**——脚本不过就不用试真机，省时间
4. 打包进 `assets/fonts/`，`shared/theme/fonts.ts` 按语言映射
5. 真机渲染 `བསྒྲུབས` / `བོད`，截图存证

**验收**：字体脚本全 PASS；真机藏文**无豆腐块、无断笔**（截图 + 人工签字）；维文词首/词中/词尾三形态正确连写
**失败出口**：候选全不合格 → 换 UKIJ 等来源，或降级「藏文用系统字体」并记为已知限制（**会直接冲击验收 9，需要你重新拍板 bo 是否保留**）

### T0-33 P-3 豆包 SSE

**前置**：豆包 key
**步骤**：
1. `infra/ai/AiStreamClient` 接口 + `DoubaoAiStreamClient` 实现
2. `GET /api/v1/ops/probe/ai?q=` 用 `SseEmitter` + 专用线程池
3. 事件：`meta`（含 `ttfbMs`）/ `delta` / `done` / `error`
4. 开关：`faze.probe.enabled` + `X-Probe-Token` 头，关闭时返回 404
5. **不写 `ai_session` 表**（本段只是探针）

**验收**：`curl -N` 能收到事件流且含 ttfbMs；上游 4xx 时收 `event: error` 而不抛 500；开关关闭后两条探针均 404
**失败出口**：key 未配置 → 返回 `510001 PROBE_NOT_CONFIGURED`，算「未就绪」不算「失败」

### T0-34 P-3 法条检索 + 法条种子

**前置**：T0-13
**步骤**：
1. **我自己写法条种子**：`V2__init_law_items.sql` 录入 **5 条真实条文**（民法典合同的订立、婚姻家庭、劳动相关各若干，附条文号、法律名称、生效版本）。法律法规文本不受著作权保护，可直接入库，但要标来源
2. `GET /api/v1/ops/probe/law?q=&limit=`：`MATCH(content) AGAINST(? IN BOOLEAN MODE)`；q 长度 <2 走 `LIKE` 兜底并在响应标 `engine:"like"`；入参剔除 `+ - > < ( ) ~ * " @`
3. 返回 `{hits:[{id,lawCode,articleNo,score,snippet}],total,costMs,engine}`

**验收**：`q=合同` 命中 ≥1；`q=合` 走 `like` 且非空；`q=++++` 返回空数组不 500；**且 `EXPLAIN` 显示走 FULLTEXT 索引**（防全表扫描假通过）
**失败出口**：ngram 检索质量不够 → 加「法条标题/章节号精确匹配」混合策略（仍不引入向量库）

> **全量法条（2000–3000 条）不属第 0 段**。那是第 3 段的事，届时需要你确认数据来源与人工校对责任人。第 0 段只要 5 条能证明链路通即可。

---

## 第 4 批

### T0-41 真机取证脚本

**前置**：T0-31
**步骤**：`tools/evidence/collect.ps1`：一条命令产出 `evidence/<case-id>/`，含截图、logcat 片段、UTC+本地时间戳、git rev、APK sha256、**设备指纹（`ro.kernel.qemu` 必须为 0，`ro.product.model` 不得是 `sdk_*`/`Emulator`）**

**验收**：单命令产出完整证据包；重复执行不出错；**能排除模拟器**
**失败出口**：adb 无线调试配对问题 → 改 USB 连接

### T0-42 13 条验收执行

**前置**：T0-31~T0-34
**步骤**：按第六节判定式逐条执行，每条的原始输出落到 `evidence/` 对应目录，汇总成 `docs/acceptance/D0-checklist.md`（13 行勾选 + 证据路径 + 签字栏 + 干净 clone 重跑记录）

**验收**：13 条全部有客观证据；不留「目视通过」类判定
**失败出口**：某条无法判定 → 明确写「未通过」并记录原因，**不改判定标准**

### T0-43 冻结清单签字 + CHANGELOG

**步骤**：
1. `docs/adr/DECISIONS_FROZEN.md`：每条冻结决策必须指向**机器可校验锚点**（lint 规则名 / CI job 名 / 文件路径），纯文字不算冻结
2. `docs/CHANGELOG.md` 记录本段做了什么、验收结果、与计划的偏差、遗留问题
3. 推送到 `docs` 分支

**验收**：`DECISIONS_FROZEN.md` 每条都能被 grep 到对应锚点；CHANGELOG 有「与本计划的偏差」一栏且非空

---

# 六、13 条验收判定式

| # | 判定式（客观可判定） | 证据 |
|---|---|---|
| 1 | MySQL80 服务 Running + `flyway_schema_history` success 行数 = 脚本文件数 | 命令输出 |
| 2 | `/api/v1/ping` 返回四字段，**且响应头 traceId 与 body 一致** | curl 输出 |
| 3 | RuoYi 后台 biz 菜单路由返回 200 **且 DOM 含菜单文本** | 截图（测试 profile 关闭验证码） |
| 4 | `adb shell pm list packages` 含 `com.faze.app` **且** 设备指纹 `ro.kernel.qemu=0` | 输出 + 装机实物照 |
| 5 | **nonce 法**：`/ping` 返回 8 位 nonce，首页渲染它 → 截图 nonce 与同刻 curl 一致；改 nonce 前缀冷启，界面随变 | 两段截图 |
| 6 | 服务端作废 token 触发 401 → 触发/跳登录/再请求被拒三段截图；杀进程后仍免登录 | 三段截图 |
| 7 | 拆三条：① 返回箭头 bbox 中心 x > W/2 ② 进度 30% 填充在右半区 ③ Tab1 中心 x > Tab2 中心 x（`uiautomator dump`） | dump XML + 截图 |
| 8 | `am force-stop` 后冷启重跑第 7 条三条；**必须 standalone APK**（Expo Go 会假阳性） | 同上 |
| 9 | 机器初筛（非空像素率 + 连通域数 + tofu 模板相似度）+ **人工终判签字** | 截图 + 签字 |
| 10 | 范围限 `apps/mobile-app/**/*.tsx`；**负向测试**：加一个违规 fixture，eslint 必须报错 | 命令输出 |
| 11 | `/probe/ai` 首字节 + ttfbMs；`/probe/law` 命中 ≥1 **且 EXPLAIN 走 FULLTEXT** | 命令输出 |
| 12 | 四 job 全绿无 skip；**且删掉任一关键规则 id 后 CI 必须红** | CI 记录 |
| 13 | `svc wifi disable` + `svc data disable` → 边界 UI 截图 + logcat 无 FATAL + 进程 pid 未变 + 恢复网络可重试 | 截图 + logcat |

---

# 七、我判断风险最高、最可能卡住的五处

| # | 风险 | 为什么危险 | 出口 |
|---|---|---|---|
| 1 | **EAS 构建失败** | 本机无 Android SDK，EAS 是唯一路径；且 `expo-updates`/`secure-store` 需要 dev build，与验收 7/8 是同一条命 | 失败即转本地 SDK（先 prebuild 看真实要的版本再装） |
| 2 | **藏文字体无合格方案** | 旧项目那套 234 字形无 GSUB 不可用；候选字体是否含 GSUB 我没验证过 | 脚本先筛，全不合格则 bo 是否保留要重新拍板 |
| 3 | **LocalizedText 切分做不对** | 切错→断笔，症状和「字体缺 GSUB」一模一样，会白换一轮字体 | 先写单测覆盖藏文/阿拉伯再上真机 |
| 4 | **`reloadAsync` 在 dev build 下 no-op** | 那就只能靠 release APK 验 RTL 重启，迭代变慢 | dev 期用 `am force-stop` 手工验 |
| 5 | **RuoYi 后台跑不起来** | RuoYi 有配套后端（`sys_*` 20+ 张表），第 0 段要它登录；它与 faze-api 的 Boot/MyBatis-Plus 版本可能冲突 | **分库**（`faze` / `faze_admin`）+ 两后端并联，第 1 段再做合流 spike |
| 6 | **Redis 5.0 与 Spring Boot 3.5 的兼容性** | 本机是 Windows 移植版 5.0.14.1（官方已到 7.x），我只实测了 `PING`/`SET EX`/`GET`/`TTL`/`DEL`，**没测过 `spring-boot-starter-data-redis` 在它上面的全部行为** | 第 0 段只用到基础命令，风险低；若后续 Redisson 报版本不支持，分布式锁改用 MySQL 行锁或 `SETNX` 手写 |

---

# 八、开工检查清单

| 项 | 状态 |
|---|---|
| MySQL root 密码 | ✅ 已提供（`123456`） |
| MySQL80 服务在跑 | ✅ 已确认 |
| Redis 可用 | ✅ 已确认（5.0.14.1，实测 `PONG`） |
| node / npm | ✅ 已确认（v24.16.0 / 11.13.0） |
| Java 17 | ✅ 已确认（17.0.17） |
| eas-cli | ✅ 已确认（22.0.0） |
| 删除原型 + 前端重写 | ✅ 已确认 |
| 不用 docker / 锁 npm / 单 token / 无域名用 `faze://` | ✅ 均已确认 |
| **豆包 API key** | ⏳ 到 **T0-33** 时提供 |
| **Android 真机** | ⏸ 暂不连接，**必须在 T0-31 之前连上** |

**技术侧开工阻塞已清零。** 剩下两项分别在 T0-33 和 T0-31 才需要，不影响 T0-01 起步。

**执行顺序**：严格按 [《法泽FAZE_第0段串行执行序列_v1.0.md》](./法泽FAZE_第0段串行执行序列_v1.0.md) 的 24 个任务**串行**推进，不并行。前一个任务的门禁全部勾满，才允许开始下一个。
