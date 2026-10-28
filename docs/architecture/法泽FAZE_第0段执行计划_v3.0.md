# 法泽 FAZE 第 0 段执行计划（V3.0 · 单线版）

> **这份文档是什么**：第 0 段**唯一的施工文档**。从第一行读到最后一行，按顺序做，中途不需要翻开任何其他文档。
>
> **取代**：`法泽FAZE_第0段执行计划_v2.0.md`（任务细节）与 `法泽FAZE_第0段串行执行序列_v1.0.md`（24 个门禁）。那两份已删除，需要追溯时从 git 历史取。
>
> **上级文档**：`法泽FAZE_分期交付计划_v1.0.md` —— 九段怎么切、现在做哪段。
> **技术依据**：`法泽FAZE_技术路线_v2.3.md`。表结构见 `法泽FAZE_V1基线表结构_v1.0.md`，错误码见 `法泽FAZE_错误码规范_v1.0.md`。
>
> **定稿**：2026-10-28

---

## 一、怎么用这份文档

三句话：

1. **从头读，按顺序做。** 24 个任务排成 6 组，**组内连续推进**，组末过一次检查点。
2. **每节五段**：做什么 → 怎么做 → 产出 → 算做完 → 卡住怎么办。
3. **不许跳步，也不许为跳步而假装通过。** 唯一合法的停下理由是「上游还没给我东西」（缺真机、缺 key）。

**为什么不是一个任务一道门禁**：24 道门会让人把精力花在勾选项上，而不是把事做完。改成 6 个检查点——组内连着做，组末集中验一次。**「不要多阶段并行」约束的是第 0~8 这九个段之间**，段内不必人为切碎。

---

## 二、第 0 段做完，手上有这 8 样东西

| # | 东西 | 落在哪 |
|---|---|---|
| 1 | 一个能装到真机的 APK（`com.faze.app`） | EAS 产物 + `evidence/` 装机照 |
| 2 | 一个能连上的服务端（`/api/v1/ping` 带 traceId） | `services/faze-api` |
| 3 | 一个建好两张表的 MySQL 库（`app_user` / `law_item`，含 ngram 全文索引） | `database/` + Flyway |
| 4 | 一项 **APK 打包结论**（EAS 这条路通不通、证据是什么） | `docs/ops/P-1-决策与证据.md` |
| 5 | 一项 **字体结论**（藏文/阿拉伯文能否正确渲染、用哪个字体） | `docs/ops/P-2-字体结论.md` |
| 6 | 一项 **AI + 法条检索结论**（豆包首字节 ms、ngram 能否命中） | curl 原始输出 |
| 7 | **13 条验收**的客观证据包 | `docs/acceptance/D0-checklist.md` + `evidence/` |
| 8 | 冻结的**不可逆决策清单**（14 条，每条带机器可校验锚点） | `docs/adr/` |

**这 8 样缺任何一样，第 1 段不许开工。**

---

## 三、环境实况（2026-10-28 实测）

### 3.1 已就绪

| 项 | 实测值 |
|---|---|
| MySQL | 8.0.44，Windows 服务名 `MySQL80`，root 密码 `123456` |
| Redis | 5.0.14.1，`E:\Redis-x64-5.0.14.1`，实测 `PING` → `PONG` |
| Node / npm | v24.16.0 / 11.13.0（装在 `E:\uniapp1\`） |
| Java | 17.0.17 |
| adb | platform-tools 已装 |
| eas-cli | 22.0.0 |
| git 推送 | **必须走代理 `http://127.0.0.1:7890`**：`github.com` 被解析到 fake-IP `198.18.0.53`，直连 TLS 握手失败 |
| 包管理器 | **npm**（不用 pnpm） |
| 容器 | **没有 docker，也不用**。服务由 `deploy/start-services.ps1` 依次拉起 |

### 3.2 还没有、到点才要

| 项 | 什么时候要 |
|---|---|
| Android 真机连接 | **T0-31 之前**。第 5 组之前所有任务都不需要它 |
| 豆包 API key | **T0-33 时**提供 |
| 域名 | 装 APK 前购买。第 0 段用 `faze://` scheme，不依赖域名 |

### 3.3 三个已验证的技术事实（别再验一遍）

1. `utf8mb4_0900_ai_ci` + `FULLTEXT ... WITH PARSER ngram` 在 8.0.44 上**可用**，`EXPLAIN` 显示 `type=fulltext`。
2. 但**单字查询 0 命中**（如 `q=婚`）→ 查询串长度 < 2 必须回落 `LIKE`。
3. `@@ngram_token_size` = 2。

---

## 四、一条线

```text
第 1 组  清场与冻结        01 → 02 → 03                       ◆ 检查点 1
第 2 组  骨架与规矩        11 → 12 → 21 → 22                  ◆ 检查点 2
第 3 组  数据库与服务端    13 → 14 → 15                        ◆ 检查点 3
第 4 组  移动端地基        23 → 24 → 25 → 26 → 27 → 28 → 29   ◆ 检查点 4
第 5 组  三项探针          33 → 34 → 31 → 32                  ◆ 检查点 5
第 6 组  验收收口          41 → 42 → 43                        ◆ 全段完成
```

**两处顺序是刻意的，别改**：

- **21/22 在 13/14/15 之前**：Expo 配置与 ESLint 是纯本地活，不依赖数据库；先做掉可以尽早暴露「工具链在 Windows 上跑不跑得动」。
- **33/34 在 31/32 之前**：33、34 纯服务端不需要真机；31、32 必须真机。把不等手机的做完，减少空转。**这意味着第 5 组之前可以完全没有手机。**

---

## 五、第 1 组：清场与冻结

> 这一组的目标：**把旧世界清掉，把不可逆的东西钉死。** 做完之后，后面所有工作都在干净地基上。

### T0-01 环境核对

**做什么**：把八条命令的真实输出抄进文档，作为本段的环境基线。以后出问题先对这份基线。

**怎么做**：

```powershell
$PSVersionTable.PSVersion.ToString()      # 本机是 Windows PowerShell 5.1，不是 pwsh 7
where.exe node; where.exe npm; npm root -g; npm config get registry
java -version
mvn -v
mysql -uroot -p123456 --default-character-set=utf8mb4 `
  -e "SELECT VERSION(), @@ngram_token_size, @@character_set_server, @@collation_server"
(Test-NetConnection 127.0.0.1 -Port 6379).TcpTestSucceeded
adb devices -l
```

全部输出写入 `docs/ops/env-baseline-2026-10-28.md`，**带原始输出，不要只写「正常」**。

**三个命令级坑（2026-10-28 实测，不写清一定踩）**：

1. `where` 是 `Where-Object` 的别名，`where node` 会报错——必须写 `where.exe`。
2. `curl` 是 `Invoke-WebRequest` 的别名，`curl -N` 对流式毫无意义——SSE 一律写 `curl.exe`（本机有 8.19.0）。
3. PowerShell 5.1 的 `Get-Content` 默认按 ANSI（GBK）读文件，UTF-8 中文会显示成乱码——**读中文文件必须 `-Encoding UTF8`**，输出落盘用 `Out-File -Encoding utf8`。

**Redis 活着没活着只看 6379 端口是否 LISTEN**，不要解析 `redis-cli` 的文本：本机控制台是 GBK，它的报错会乱码。

**产出**：`docs/ops/env-baseline-2026-10-28.md`

**算做完**：八条命令都有输出且无报错，输出已入档，文档里写明「本机是 PowerShell 5.1」和上面三个坑。`adb devices` **为空不算失败**——只记录「当前无设备」，第 5 组才用。

**卡住怎么办**：
- `npm` 不可用 → 先修 node，不要往下走
- MySQL 连不上 → 密码问题，问项目负责人
- `redis-cli` 找不到 → 用全路径（见上面命令）

---

### T0-02 删除原型 + 重建 Expo 工程

**做什么**：删掉 `apps/mobile-app` 下全部原型代码，用 Expo 官方模板重建。

**怎么做**：
1. 先保住两样：`docs/design/screenshots/`（12 张视觉参考）、`design/tokens/tokens.json`（令牌资产）
2. 删 `apps/mobile-app` 整个目录（`app/` `features/` `shared/` `assets/legacy/` `node_modules/` `package-lock.json` 全删）
3. `npx create-expo-app@latest apps/mobile-app --template blank-typescript`
4. `cd apps/mobile-app` → `npm install` → `node -p "require('./package.json').dependencies.expo"` 确认是 `~54.0.x`（**`npx expo --version` 打的是 CLI 版本，不是 SDK 版本，别用它判 SDK**）
5. 复制令牌：`design/tokens/tokens.json` → `apps/mobile-app/shared/theme/tokens.json`
6. **单独一个 commit** 记录删除动作，message 写明「删除原型代码，理由见第 0 段执行计划 v3.0」

**产出**：干净的 Expo 54 工程 + 一次「删除原型」的独立提交

**算做完**：`apps/mobile-app` 下 tsx 文件只剩模板自带那几个；`npx tsc --noEmit` 0 错（**模板不自带 lint 脚本，lint 门在 T0-22 才建，这里不要拿 `npm run lint` 当验收**）；`git log` 能查到删除提交。

**卡住怎么办**：模板版本不对 → 显式指定 `--template blank-typescript@sdk-54`。

> **代码虽删，历史可查。** 原型代码完整留在 git 历史里，不是真丢。

---

### T0-03 冻结不可逆决策（ADR）

**做什么**：把 21 条「以后改起来很贵」的决策写成一张表，每条四栏：**决策 / 备选及否决理由 / 为什么选它 / 什么条件下该推翻**。

**怎么做**：写 `docs/adr/ADR-000-不可逆决策总表.md`，覆盖这 21 条（1–14 是 v2.0 已定的；15–21 是项目更早冻结、旧计划表里漏掉、但后面每一段都要引用的）：

| # | 决策 | 值 |
|---|---|---|
| 1 | Android 包名 | `com.faze.app` |
| 2 | URL scheme | `faze://` |
| 3 | 分享域名 | 暂无，`TBD`，装 APK 前购买 |
| 4 | 仓库形态 | 单仓：`apps/mobile-app` + `apps/admin` + `services/faze-api` |
| 5 | 依赖方向 | `app → features → shared`，反向视为设计错误 |
| 6 | 路由树 | 见 T0-23 |
| 7 | locale 集合 | `zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN` |
| 8 | API 前缀 | `/api/v1` |
| 9 | 包管理器 | **npm**，不引入 pnpm |
| 10 | 鉴权模型 | 单 token + `roles` claim，不拆两套 |
| 11 | 鉴权失败语义 | **HTTP 401**（业务失败仍 200 + code） |
| 12 | 错误码格式 | 6 位 `[C][DD][NNN]` |
| 13 | Flyway 分段 | 第 0 段 `V1–V99`，第 n 段 `V{n}00–V{n}99` |
| 14 | RuoYi 归属 | `apps/admin/`（仓库内，才能机器检查「不改框架层」） |
| 15 | 时区 | 数据库 `DATETIME` **存 UTC**，出参 ISO-8601 带偏移；服务端取时 `LocalDateTime.now(ZoneOffset.UTC)`，**禁系统默认时区** |
| 16 | `is_minor` 语义 | **不满 18 周岁即置 1**；**不限制注册年龄**；<14 岁须监护人单独同意（PIPL 第 31 条） |
| 17 | 金额表示 | 一律**整数分**（`BIGINT`），禁浮点；转换只在 `client.ts` 一处 |
| 18 | 法条检索方案 | 本地 `law_item` + MySQL ngram FULLTEXT，**不用向量库**；查询串 <2 字回落 `LIKE`（单字命中 0 条，实测） |
| 19 | RTL 生效方式 | `I18nManager.forceRTL` + `Updates.reloadAsync()` 软重启（forceRTL 下次启动才生效） |
| 20 | 文字切分 | 按 **grapheme cluster** 切 run；只给 tibetan / arabic 设 `fontFamily`，han / latin 不设 |
| 21 | 方向与样式 | 一律 `start` / `end`（禁 `left` / `right`）；`StyleSheet` + 设计令牌，**不用 Tailwind / Uniwind** |

**产出**：`docs/adr/ADR-000-不可逆决策总表.md`

**算做完**：21 条四栏无空项。业务侧未定的（域名）标 `TBD` + 写明触发条件，**不许留空白，也不许编一个凑数**。

**卡住怎么办**：某条业务侧还没定 → 标 `TBD` 并写「什么时候必须定」。这不是失败，是诚实。

---

### ◆ 检查点 1

- [ ] 环境基线文档存在，五条命令都有**原始输出**
- [ ] `npx expo start` 能起来；`git log` 能查到删除原型的那次提交
- [ ] ADR 21 条齐全，域名那条是 `TBD` 而非空白

---

## 六、第 2 组：骨架与规矩

> 这一组的目标：**把项目结构和质量闸门立起来。** 做完之后，后面写的每一行代码都被自动检查。

### T0-11 仓库布局定型

**做什么**：建顶层目录，写「依次启动服务」的脚本，各目录配 README。

**怎么做**：
1. 建目录：`services/` `deploy/` `database/` `archive/` `docs/adr/` `docs/acceptance/` `docs/ops/` `tools/`
2. 根 `package.json` 只放编排脚本，**不声明 `workspaces`**（否则 Metro 会向上解析出幽灵依赖）
3. `deploy/start-services.ps1`，按顺序起（**不用 docker**）：
   - ① 检查 `MySQL80` 服务，未运行则 `Start-Service MySQL80`，轮询 3306 直到可连
   - ② 检查 6379 端口，未监听则 `Start-Process E:\Redis-x64-5.0.14.1\redis-server.exe "redis.windows.conf" -WindowStyle Hidden`，轮询 `redis-cli PING` 直到 `PONG`
   - ③ 逐项打印端口、版本、连通状态（跑完一眼看出谁没起来）
   - ④ 支持 `-Stop`：停 Redis；MySQL 是系统服务，**只提示不擅自停**
4. 每个新目录写 README 说明职责边界

**产出**：目录结构 + `deploy/start-services.ps1` + 各 README

**算做完**：从「MySQL 已停 + Redis 未启动」状态跑 `start-services.ps1` 能把两个都拉起来并打印连通状态；`-Stop` 能停 Redis；根目录 `npm run lint` 能调用到移动端。

**卡住怎么办**：powershell 执行策略拦截 → `Set-ExecutionPolicy -Scope Process Bypass` 后再跑。

---

### T0-12 目录边界检查器

**做什么**：写脚本，机器检查依赖方向，防止 `features` 互相引用、`shared` 反向依赖。

**怎么做**：写 `scripts/arch/check-boundaries.mjs`：
1. 扫 `apps/mobile-app` 下所有 `.ts` / `.tsx` 的 import
2. 三条规则：`app/` 不得 import `features/*/components`（只能 import `pages`）；`features/*` 不得 import 另一个 feature；`shared/` 不得 import `features/` 或 `app/`
3. 违规输出 `文件:行 实际import → 违反的规则`，退出码 1
4. 另写 `scripts/arch/check-route-shell.mjs`：`app/**/*.tsx` 的 AST 里只允许 ImportDeclaration + 一条 ExportDefaultDeclaration，且源必须是 `features/*/pages/*`

两个脚本接进 `npm run lint`。

**产出**：两个检查脚本 + 接入 lint

**算做完**：**注入 3 个违规再撤销**——① feature 互引 ② shared→features ③ `app/` 里写 `useState`。三个都必须被拦且**指到具体行**；撤销后必须全绿。

**卡住怎么办**：AST 解析误报 → 把规则收窄到明确违规形态，**但绝不放过真违规**。

---

### T0-21 Expo 工程配置定型

**做什么**：定 `app.config.ts` 和 `tsconfig.json`，把包名、scheme、strict 模式钉死。

**怎么做**：
1. `app.config.ts`：`name` / `slug` / `scheme: 'faze'` / `android.package: 'com.faze.app'` / `android.intentFilters`（custom scheme，`autoVerify: false`）
2. `tsconfig.json`：`strict` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` + `noImplicitOverride` + 路径别名 `@/*`
3. 装依赖用 `npx expo install`（**不要用 `npm i`**，它才会按 SDK 54 选兼容版本）：`expo-router` `expo-secure-store` `expo-updates` `expo-font` `expo-localization` `react-native-safe-area-context`
4. 开 `typedRoutes`
5. **切路由入口**（漏了这步，T0-23 建的路由树根本不会被挂载）：`package.json` 的 `"main"` 改成 `expo-router/entry`，删掉模板根目录的 `App.tsx`，新建 `app/_layout.tsx`（`<Stack />`）与 `app/index.tsx`
6. `shared/theme/tokens.ts`：把 `tokens.json` 转成强类型常量，业务代码只认 `tokens.color.*` / `tokens.spacing.*` 这种写法

**产出**：工程配置 + 入口切换 + `shared/theme/tokens.ts` + 依赖登记到 `docs/architecture/依赖清单.md`

**算做完**：`npx expo config --type public` 输出的 `scheme` / `android.package` 与 ADR **一字不差**；`tsc --noEmit` 通过。

**卡住怎么办**：`expo-updates` 与 dev client 冲突 → 先不启用 updates，第 5 组 P-1 时一起配。

---

### T0-22 ESLint 规则集 + 自检

**做什么**：用**白名单思路**建 10 条规则，比黑名单可靠。

**怎么做**：
1. `no-restricted-imports` 禁从 `react-native` 导入 `Text` / `TextInput`——精确、零误报
2. 自研 `faze/no-raw-text`：兜底 JSX 里的 `<Text>`，白名单仅 `shared/components/LocalizedText.tsx`、`AppTextInput.tsx`
3. 自研 `faze/no-physical-direction`：`left|right|marginLeft|marginRight|paddingLeft|paddingRight|border*Left*` 在 `StyleSheet.create` 第一层对象与 JSX `style` 内报错
4. 自研 `faze/no-color-literal`：`rgba(` / `#RRGGBB`，白名单 `shared/theme/tokens.ts`
5. 自研 `faze/no-bidi-control`：扫 U+200E/F、U+202A–202E、U+2066–2069
6. `@typescript-eslint/no-explicit-any`、`ban-ts-comment`、`no-double-assertion`（禁 `as unknown as`）
7. 自研 `faze/file-header`：文件首注释块须含「职责 / 调用方 / 数据来源 / 注意事项」
8. `switch-exhaustiveness-check`：错误码到文案必须穷尽
9. `no-floating-promises` + `react-hooks/exhaustive-deps`
10. 自研 `faze/no-direct-fetch`：只有 `shared/api/client.ts` 能写 `fetch`——这是 T0-24「网络层唯一出口」唯一能机器验证的地方

配套：`tools/eslint/__tests__/` 每条规则 ≥1 valid + 2 invalid（用 `node:test` + `RuleTester`，**不引 jest**）；`scripts/gate-selfcheck.mjs` 对每个违规 fixture 断言 lint 退出码非 0，并断言 10 个规则名都在 config 里（防「规则被删了但 lint 还是绿的」）。

**产出**：10 条规则实现 + 用例 + 自检脚本

**算做完**：`npm run test:lint-rules` 全绿；`gate-selfcheck` 通过；`Select-String -Path (Get-ChildItem apps/mobile-app -Recurse -Include *.ts,*.tsx | Where-Object { $_.FullName -notmatch 'node_modules' }) -Pattern 'marginLeft|paddingRight'` 命中 0（**本机没装 `rg`，别照抄 `rg` 命令**）；在别的文件写一个 `fetch(` 会报错。

**卡住怎么办**：`eslint-plugin-react-native` 在 ESLint 9 flat config 下不可用 → 全部改自研规则。工作量变大但可控，**不要为此降级成「不检查」**。

---

### ◆ 检查点 2

- [ ] `start-services.ps1` 启动/停止两条路径都跑通
- [ ] 边界检查器注入 3 个违规全被拦住，撤销后全绿
- [ ] `npx expo config` 的包名/scheme 与 ADR 一致；`tsc --noEmit` 零报错
- [ ] `npm run test:lint-rules` 全绿，`gate-selfcheck` 通过

---

## 七、第 3 组：数据库与服务端

> 这一组的目标：**有一个真能跑起来的后端和真库。** 不需要手机。

### T0-13 建库 + Flyway V1 基线

**做什么**：建两个库、建专用账号、写 V1 迁移脚本（只建两张表）。

**怎么做**：
1. 建两个库：`faze`（业务库，`utf8mb4`，排序规则先试 `utf8mb4_0900_ai_ci`）、`faze_admin`（RuoYi 专用，第 1 段才用，先建空库）。**分库是为了避免 Flyway 与 RuoYi 的 `sys_*` 表打架**
2. 建专用账号 `faze_app`@`localhost`，只授权 `faze.*`。**应用不用 root 跑**
3. `V1__baseline.sql` 只建两张表：
   - `app_user`（19 字段，含 `birth_date` / `is_minor` / `guardian_phone` / `deleted_at`）
   - `law_item`（12 字段，含 `FULLTEXT KEY ft_law_content (content) WITH PARSER ngram`）
4. 配 `spring.flyway.locations=classpath:db/migration`、`baseline-on-migrate=false`、`clean-disabled=true`
5. 依赖里**同时加 `flyway-core` 和 `flyway-mysql`**：Flyway 10 起 MySQL 支持拆成独立模块，只加 core 会在启动时直接报 `Unsupported Database: MySQL`，表一张都建不出来

**产出**：`database/init-db.sql` + `V1__baseline.sql`

**算做完**：空库启动 Spring Boot 后 `flyway_schema_history` 恰 1 行 `success=1`；二次启动 0 新增；`SHOW CREATE TABLE law_item` 含 `WITH PARSER ngram`；`SELECT @@ngram_token_size` = 2。

**卡住怎么办**：
- `0900_ai_ci` 建 FULLTEXT 失败 → 改 `utf8mb4_unicode_ci`，记入 ADR
- Flyway 报「库非空」→ **不要用 `baselineOnMigrate` 兜底**，它会跳过建表掩盖故障。清库重跑

---

### T0-14 服务端骨架

**做什么**：能跑的 Spring Boot 3.5 骨架，统一响应体、traceId、全局异常。

**怎么做**：
1. `services/faze-api`（Spring Boot 3.5，Maven）
2. 包结构**只建** `common`（`R` / `exception` / `constants` / `enums`）、`config`、`security`、`modules/ops`。**其余 9 个业务域不建空包**——空包只会诱导人往里塞错位置的东西
3. `R`：`{code, msg, data, traceId}`
4. `TraceIdFilter`：生成 traceId → 塞 MDC → 响应头 `X-Trace-Id`
5. `GlobalExceptionHandler`：覆盖参数校验、业务异常、鉴权异常、未捕获异常
6. `GET /api/v1/ping?noncePrefix=` 返回 `R.ok({ pong: true, nonce: "<8 位>", serverTime: "<ISO-8601>" })`；nonce = 前缀 + 随机数。**没有 nonce，验收第 5 条（首页数据非硬编码）永远判不了**
7. **最小鉴权端点**（本段必须有，否则验收第 6 条判不了）：`POST /api/v1/auth/login`（phone + password → `accessToken` / `refreshToken` / `roles`）、`POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout`（把 `jti` 写 `faze:auth:blacklist:{jti}`，TTL = 剩余有效期）。refresh token 存 Redis，**不建表**。密码校验用 `spring-security-crypto` 的 `BCryptPasswordEncoder`，不引整套 Security
8. 只建 `modules/auth` 与 `modules/ops` 两个域包；查询用 `JdbcTemplate`，**本段不引 MyBatis-Plus**（只有 2 张表 3 条 SQL，少一个兼容风险；第 1 段再引）

**产出**：可运行的服务端骨架 + 4 个端点（ping / login / refresh / logout）

**算做完**：① `curl.exe -i http://localhost:8080/api/v1/ping` 返回四字段，**且响应头 traceId 与 body 里的 traceId 一致**；② 故意抛 `BusinessException(402001)` → HTTP 200 + code 402001；③ 被 logout 拉黑的 token 再请求 → **HTTP 401 + code 402003**；④ login 拿到的 refresh 能换出新 access；⑤ 未登记异常返回统一错误体，不是 Spring 默认错误页。

**卡住怎么办**：MyBatis-Plus 3.5.12 与 Boot 3.5 不兼容 → 先只用 spring-jdbc，第 1 段再引 MP。

---

### T0-15 CI 流水线

**做什么**：四个 CI job + 本地等价脚本。

**怎么做**：`.github/workflows/ci.yml`：
- `mobile-lint`：`npm ci` → `tsc --noEmit` → `eslint . --max-warnings=0` → `gate-selfcheck`
- `arch-boundary`：跑 T0-12 两个脚本
- `api-build`：`mvn -B verify`
- `smoke`：起 MySQL service container → 跑 Flyway → `curl /api/v1/ping`

另写 `scripts/ci/gate.ps1`，本地跑同一组命令。**CI 与本地必须一致**，否则本地绿 CI 红会浪费大量时间。

**产出**：CI 配置 + 本地等价脚本

**算做完**：**负向证明**——故意写一个 `any` → CI 红；删掉任一关键 lint 规则 id → CI 红；把 `strict` 改 false → `tsc` 报错。三者都必须红，才算「门真的会拦」。

**卡住怎么办**：仓库未启用 Actions → 用 `gate.ps1` 做硬门 + 人工阻断，**并明确记录这是能力下降**，不要假装仍有 CI。

---

### ◆ 检查点 3

- [ ] Flyway 迁移成功且可重复（二次启动无新增）
- [ ] `law_item` 表确认带 ngram 全文索引
- [ ] `/api/v1/ping` 返回四字段，traceId 头与 body 一致
- [ ] CI 三条负向测试（`any` / 删规则 / 关 strict）都能让流水线变红

---

## 八、第 4 组：移动端地基

> 这一组是**全项目最硬的一块**，也是第 0 段的核心。七个任务全在移动端，前四个是基础构件，后三个是多语言与 RTL——**RTL 是四种语言能同时存在的前提**。

### T0-23 路由树定稿

**做什么**：定 `app/` 分组树，页面实现放 `features/`，路由壳只放一行 re-export。

**怎么做**：

1. 分组：`(auth)/`（登录 / 注册 / 忘记密码）、`(tabs)/`（首页 / 律师 / AI / 我的）、`lawyer/` `case/` `document/` `order/` `settings/`（含 `language`）。写 `shared/navigation/routes.ts` 汇总全部路由常量 + 参数类型。
2. **建三个「验收承载页」**——验收第 5 / 6 / 7 条要有具体界面才能取证，而第 0 段又不做业务页面，所以这三个页面是**为验收存在的最小页**，第 1 段重写或删除，必须在文件头注明这一点：
   - `app/(tabs)/index.tsx` → `features/home/pages/HomePage.tsx`（渲染 `/ping` 的 nonce，验收 5）
   - `app/(auth)/login.tsx` → `features/account/pages/LoginPage.tsx`（真登录，验收 6）
   - `app/settings/rtl-probe.tsx` → `features/settings/pages/RtlProbePage.tsx`（返回箭头 + 30% 进度条 + 两个 Tab，验收 7）
3. 其余路由只放占位页：用 `PageState` 的空态 + 「待建设」文案（走 `t()`），**不许写假数据**——假数据会让验收 5 直接失效。

**产出**：`app/**` 路由树 + `shared/navigation/routes.ts` + 三个承载页的壳

**算做完**：`tsc --noEmit` 通过；`check-route-shell.mjs` 通过；`npx expo start` 能进到语言切换页。

**卡住怎么办**：expo-router 分组与 `typedRoutes` 冲突 → 关 `typedRoutes`，但**保留 `routes.ts` 常量**（常量才是真正被业务引用的东西）。

---

### T0-24 网络层唯一出口

**做什么**：全项目只有一个地方发请求——`shared/api/client.ts`。

**怎么做**：
1. `request<T>(path, options)` 统一出口，注入 baseUrl（来自 `app.config.ts` 的 `extra`）
2. 错误码 → i18n key 映射表（与 T0-14 的错误码对齐）
3. 金额格式化（整数分 ↔ 展示），**禁浮点**
4. `beforeSend` 脱敏：token、手机号、身份证**不进日志**
5. 暴露钩子 `setRefreshHandler()` / `setOnAuthLost()`，供 T0-25 注册——**这样 `shared` 不需要反向依赖 `features`**

**产出**：`shared/api/client.ts`

**算做完**：全仓除 `client.ts` 外无直接 `fetch`（由 lint 强制）；断网时抛**结构化错误**而不是裸异常。

**卡住怎么办**：无。这是纯设计活，做不对就是设计没想清楚。

---

### T0-25 鉴权态 + 单飞刷新

**做什么**：token 存取 + 401 时只刷新一次（并发 401 不能各刷各的）。

**怎么做**：
1. `tokenStore.ts`：SecureStore 分 key 存 `faze.v1.access` / `faze.v1.refresh` / `faze.v1.deviceId`；非敏感的 `activeRole` / `displayName` 放 AsyncStorage
2. 读写走**模块级串行队列**——Keychain 解密慢，冷启动并发读多个 key 会拉长首屏
3. `refresh.ts`：模块级 `inflightRefresh`，**所有 401 await 同一个 Promise，只刷一次**
4. 原请求最多重放 1 次；**POST / PATCH / DELETE 不自动重放**（防重复下单），直接抛给业务层
5. 刷新失败：先删 refresh 再删 access → 广播 `auth:lost` → 取消 in-flight → `router.replace('/login')`
6. 白名单不拦截：`/auth/login`、`/auth/refresh`
7. token 黑名单存 Redis：`faze:auth:blacklist:{jti}`，TTL = token 剩余有效期。用 `StringRedisTemplate` 即可，**第 0 段不引入 Redisson**（只在需要分布式锁时才要，留到那时再验兼容性）

**产出**：`shared/auth/tokenStore.ts` + `refresh.ts` + `useAuth.ts` + `features/account/pages/LoginPage.tsx`（验收 6 的「触发」段靠它：手机号 + 密码真登录，错误按 `msgKey` 展示）

**算做完**：mock 端 5 个并发请求同时 401，`/auth/refresh` **只被调用 1 次**；refresh 返回 401 时只跳登录 1 次且无闪屏；POST 收到 401 **不被重放**。

**卡住怎么办**：SecureStore 在真机偶发解密失败 → 重试 1 次，仍失败则清 key 要求重新登录。**绝不静默降级到明文存储。**

---

### T0-26 LocalizedText（多字体组件）

**做什么**：把一段文字按**书写系统**切成若干段，每段套对应字体。这是「四种语言 + RN 没有字体回退链」的解法。

**怎么做**：
1. `shared/text/scriptRun.ts`：**按 grapheme cluster 切分，不按码点**。码点级切分会拆散藏文 base+下加字、阿拉伯 harakat，症状是「断笔」而不是豆腐块——**这会让人误判成字体缺 GSUB，白换一轮字体**
2. 每个 cluster 取首码点的 Unicode Script；`Common` / `Inherited`（空格、数字、标点）并入前一个 run；合并同 script
3. `LocalizedText.tsx`：外层 `<Text>` 承接 `style` / `numberOfLines` / `onPress`，每个 run 一个子 `<Text>`；**只给 tibetan / arabic 的 run 设 fontFamily，han / latin 不设**——不设即走系统 fallback，这是绕开「RN 无回退链」的正解
4. `useMemo` by `[text, lang]`；列表场景加 LRU
5. 探针：`typeof Intl.Segmenter === 'undefined'` 则降级到自带 `grapheme-splitter`

**产出**：`shared/text/scriptRun.ts` + `shared/components/LocalizedText.tsx`

**算做完**：单测覆盖 `བསྒྲུབས`、`بسم`、中藏混排、emoji ZWJ、纯数字；run 边界断言通过；`numberOfLines` / `onPress` 透传不丢。

**卡住怎么办**：`Intl.Segmenter` 在 Hermes 不可用 → 用降级实现（探针已留）。

---

### T0-27 LtrText + Icon 镜像

**做什么**：解决「阿拉伯语环境下数字、日期、编号会被 bidi 重排」和「哪些图标该镜像」。

**怎么做**：
1. `LtrText.tsx`：**优先方案 A**——不加控制符，把「标签 / 值」拆成两个 Text，值单独成段。不行才退**方案 B**（内部注入 U+2066 / U+2069，代价是复制文本被污染，记 ADR）
2. `Icon.tsx` + `iconMirror.ts` 白名单：
   - **镜像**：返回/前进箭头、send、reply、undo/redo、text-align-start/end
   - **绝不镜像**：play/pause、plus、close、check、search、clock、camera、lock、heart、trash、settings、logo
   - **未登记默认不镜像**（fail-safe：宁可少镜像，不要错镜像）

**产出**：`shared/components/LtrText.tsx` + `Icon.tsx` + `iconMirror.ts`

**算做完**：RTL 下 `1,234.56`、`2026-10-28`、`FAZE20261028001` 顺序正确（真机 + `adb shell` 复制结果**双验**）；返回箭头 RTL 下朝右；黑名单图标在 RTL/LTR 下像素一致。

**卡住怎么办**：方案 A 实测失效 → 降到 B，并在 ADR 记录「复制文本污染」这个代价。

---

### T0-28 错误边界 + 页面四态

**做什么**：渲染崩溃不白屏；每个页面统一处理「正常 / 加载 / 空 / 错误」。

**怎么做**：`ErrorBoundary`（捕获渲染异常，展示「出错了 + 重试」）+ `PageState` 四态容器组件。**要留「鉴权失效」特例出口**——401 登出不该落进通用错误页，否则用户会看到「出错了」而不是被送回登录。

**产出**：`shared/components/ErrorBoundary.tsx` + `PageState.tsx`

**算做完**：断网进错误边界，**logcat 无 `FATAL EXCEPTION`，进程 pid 未变**（说明是捕获了，不是崩了重启）。

**卡住怎么办**：无。

---

### T0-29 语言切换页 + 软重启

**做什么**：四种语言可切，切换后 RTL 方向真正生效。

**怎么做**：
1. `LanguagePage.tsx`：4 语言单选，显示当前选中态
2. `languageService.ts`：写持久化 → 算 `wantRTL` → **仅当 `wantRTL !== I18nManager.isRTL` 时**才 `allowRTL(true)` + `forceRTL(wantRTL)` + 写 `faze.rtl_applied` → `Updates.reloadAsync()`
3. 启动时只比对 `rtl_applied` 与 `isRTL`，**不一致才 reload 一次**（防死循环）
4. reload 期间**屏蔽 401 拦截跳登录**（防竞态把人踢回登录页）
5. `catch` 时弹「需要手动重启」提示

**产出**：`features/settings/pages/LanguagePage.tsx` + `languageService.ts`

**算做完**：切到 ug 后**杀进程重启仍是 RTL**；**连切 5 次不进入循环重启**；断网切换不崩。

**卡住怎么办**：`reloadAsync` 在 dev build 下可能是 no-op（**我没验证过**）→ 只在 release APK 上做最终验收，dev 期用 `adb shell am force-stop` 手工验证。

> **为什么必须软重启**：`I18nManager.forceRTL` 只在**下次启动**生效，这是 RN 的机制，没有别的办法。

---

### ◆ 检查点 4

- [ ] 路由壳全是单行 re-export，边界检查器通过
- [ ] 除 `client.ts` 外无直接 `fetch`
- [ ] 5 个并发 401 只触发 1 次 refresh
- [ ] LocalizedText 单测全绿（含藏文、阿拉伯文、emoji ZWJ）
- [ ] RTL 下数字/日期/编号顺序正确，黑色单图标未被镜像
- [ ] 连切语言 5 次不循环重启

---

## 九、第 5 组：三项探针

> 这一组的目标：**把三个高风险的不确定性提前打死**。这三个不验证，第 1 段以后做的所有东西都可能建立在不成立的前提上。

### T0-33 P-3 豆包 SSE 探针

**做什么**：打通「服务端 → 豆包 API → SSE 流式返回」，并测首字节时间。

**怎么做**：
1. `infra/ai/AiStreamClient` 接口 + `DoubaoAiStreamClient` 实现
2. `GET /api/v1/ops/probe/ai?q=` 用 `SseEmitter` + 专用线程池
3. 事件类型：`meta`（含 `ttfbMs`）/ `delta` / `done` / `error`
4. 开关 `faze.probe.enabled` + `X-Probe-Token` 头；关闭时返回 404
5. **不写 `ai_session` 表**——本段只是探针，不是功能

**产出**：SSE 探针接口

**算做完**：`curl -N` 能收到事件流且含 `ttfbMs`；上游 4xx 时收到 `event: error` 而不是抛 500；开关关闭后两条探针都返回 404。

**卡住怎么办**：key 未配置 → 返回 `510001 PROBE_NOT_CONFIGURED`，算「未就绪」，**不算「失败」**。

---

### T0-34 P-3 法条检索探针 + 种子

**做什么**：证明 ngram 全文索引能真正命中，并录入 5 条真法条。

**怎么做**：
1. 手写 `V2__init_law_items.sql`，录 **5 条真实条文**（民法典合同的订立、婚姻家庭、劳动相关各若干，含条文号、法律名称、生效版本）。**法律法规文本不受著作权保护，可直接入库，但要标来源**
2. `GET /api/v1/ops/probe/law?q=&limit=`：`MATCH(content) AGAINST(? IN BOOLEAN MODE)`
   - `q` 长度 < 2 → 走 `LIKE` 兜底，响应里标 `engine:"like"`
   - 入参剔除 `+ - > < ( ) ~ * " @`
3. 返回 `{hits:[{id,lawCode,articleNo,score,snippet}], total, costMs, engine}`

**产出**：`V2__init_law_items.sql` + `/probe/law` 接口

**算做完**：
- `q=合同` 命中 ≥1
- `q=合` 走 `like` 且非空
- `q=++++` 返回空数组，**不 500**
- **`EXPLAIN` 显示走 FULLTEXT 索引**（防「碰巧走全表扫描」的假通过）

**卡住怎么办**：ngram 检索质量不够 → 加「法条标题 / 章节号精确匹配」的混合策略。**仍然不引入向量库。**

> **全量法条（2000–3000 条）不属第 0 段。** 那是第 3 段的事，届时需要确认数据来源与人工校对责任人。第 0 段只要 5 条能证明链路通即可。

---

### T0-31 P-1 APK 构建（**需要真机**）

**做什么**：打出一个能装到真机上的 APK，并留证。

**怎么做**：
1. `eas.json` 加 `preview` profile：`distribution: internal`、`android.buildType: apk`
2. `eas login` → `eas init`（写 `extra.eas.projectId`）
3. `npx eas build -p android --profile preview --non-interactive`
4. 下载 APK → 记 sha256 → `adb install -r` → `adb shell dumpsys package com.faze.app | findstr versionName`
5. 全程记录到 `docs/ops/P-1-决策与证据.md`

**产出**：可安装 APK + `docs/ops/P-1-决策与证据.md`

**算做完**：`adb install -r` 成功；`dumpsys package` 的 versionName 与产物一致；APK sha256 已存档。

**卡住怎么办**：

> **先纠正一个常见误解**：`dev-client` **不是** EAS 之外的第三条路，它只是产物形态，仍然要靠 EAS 或本地 Gradle 出包。真正的备选只有两条：**EAS 云构建** 或 **本地装 Android SDK + Gradle**。

本机无 SDK，所以先走 EAS。EAS 排队/额度/网络/登录态失效时，**先收集可区分的错误再决定**：只有连续两次是「网络 / 额度」类失败才转本地 SDK；配置类错误先修 EAS 路线。转本地时装 cmdline-tools 前**先读 `npx expo prebuild` 生成的 `android/` 里实际要求的 compileSdk / buildTools / NDK 版本再装，不猜版本**。

---

### T0-32 P-2 字体（**需要真机**）

**做什么**：确认藏文、维吾尔文、哈萨克文能正确渲染，选定字体。

**怎么做**：
1. `scripts/check-font.py`（用 fontTools）：断言每个字体文件含 `GSUB` + `GPOS` 表、字形数、以及覆盖我们需要的码点（藏文叠字组合、阿拉伯 4 种形态）
2. 候选字体（**均需脚本 PASS 才准进包**）：藏文 Noto Serif / Sans Tibetan；维/哈 Noto Naskh / Sans Arabic
3. **先跑脚本，再上真机**——脚本不过就不用试真机，省时间
4. 打包进 `assets/fonts/`，`shared/theme/fonts.ts` 按语言映射
5. 真机渲染 `བསྒྲུབས` / `བོད`，截图存证

**产出**：合格字体资产 + `docs/ops/P-2-字体结论.md`

**算做完**：字体脚本全 PASS；真机藏文**无豆腐块、无断笔**（截图 + 人工签字）；维文词首 / 词中 / 词尾三形态正确连写。

**卡住怎么办**：候选全不合格 → 换 UKIJ 等来源，或降级「藏文用系统字体」并记为已知限制。**这会直接冲击验收第 9 条，必须让你重新拍板 `bo` 是否保留。**

---

### ◆ 检查点 5

- [ ] `/probe/ai` 能收到 SSE 事件流且含真实 `ttfbMs`
- [ ] `/probe/law` 的 `q=合同` 命中 ≥1，且 `EXPLAIN` 走 FULLTEXT
- [ ] APK 已装到真机，`pm list packages` 含 `com.faze.app`
- [ ] 字体脚本全 PASS，真机截图无豆腐块、无断笔

---

## 十、第 6 组：验收收口

### T0-41 真机取证脚本

**做什么**：一条命令产出完整证据包，替代「截图后手工整理」。

**怎么做**：`tools/evidence/collect.ps1`，一条命令产出 `evidence/<case-id>/`，内含：截图、logcat 片段、**UTC + 本地时间戳**、git rev、APK sha256、**设备指纹**（`ro.kernel.qemu` 必须为 0，`ro.product.model` 不得是 `sdk_*` / `Emulator`）。

**产出**：`tools/evidence/collect.ps1`

**算做完**：单命令产出完整证据包；重复执行不出错；**能自动排除模拟器**（否则证据可能来自模拟器，等于没验）。

**卡住怎么办**：adb 无线调试配对问题 → 改 USB 连接。

---

### T0-42 执行 13 条验收

**做什么**：按第十一节的判定式逐条执行，留下客观证据。

**怎么做**：每条的原始输出落到 `evidence/` 对应目录，汇总成 `docs/acceptance/D0-checklist.md`（13 行 + 证据路径 + 签字栏 + **干净 clone 重跑记录**）。

**产出**：`docs/acceptance/D0-checklist.md` + `evidence/`

**算做完**：13 条全部有客观证据；**不留「目视通过」这类判定**。

**卡住怎么办**：某条无法判定 → **明确写「未通过」并记录原因，不改判定标准**。改标准等于骗自己。

---

### T0-43 冻结清单 + CHANGELOG

**做什么**：把第 0 段确定的决策登记成可机器校验的锚点，并留完整的段末记录。

**怎么做**：
1. `docs/adr/DECISIONS_FROZEN.md`：每条冻结决策必须指向**机器可校验锚点**（lint 规则名 / CI job 名 / 文件路径）。**纯文字不算冻结**
2. `docs/CHANGELOG.md` 记录本段做了什么、验收结果、**与计划的偏差**、遗留问题
3. 推送到 `docs` 分支（走代理，见下）

**产出**：`DECISIONS_FROZEN.md` + CHANGELOG 条目

**算做完**：`DECISIONS_FROZEN.md` 每条都能被 `grep` 到对应锚点；CHANGELOG 有「与本计划的偏差」一栏**且非空**（空说明没如实记录）。

**卡住怎么办**：`git push` 失败 → 加代理：
```powershell
git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 push origin docs
```
**不要**用本机 git 全局代理配置，那会污染其他仓库。

---

## 十一、13 条验收判定式（全段硬标准）

每一条都必须**客观可判定**，不能靠感觉。

| # | 判定式 | 证据 |
|---|---|---|
| 1 | MySQL80 服务 Running + `flyway_schema_history` 的 success 行数 = 脚本文件数 | 命令输出 |
| 2 | `/api/v1/ping` 返回四字段，**且响应头 traceId 与 body 一致** | curl 输出 |
| 3 | RuoYi 后台 biz 菜单路由返回 200 **且 DOM 含菜单文本** | 截图（测试 profile 关闭验证码） |
| 4 | `adb shell pm list packages` 含 `com.faze.app` **且** 设备指纹 `ro.kernel.qemu=0` | 输出 + 装机实物照 |
| 5 | **nonce 法**：`/ping` 返回 8 位 nonce，首页渲染它 → 截图 nonce 与同刻 curl 一致；改 nonce 前缀冷启，界面随变 | 两段截图 |
| 6 | 服务端作废 token 触发 401 → 触发 / 跳登录 / 再请求被拒三段截图；杀进程后仍免登录 | 三段截图 |
| 7 | 拆三条：① 返回箭头 bbox 中心 x > W/2 ② 进度 30% 填充在右半区 ③ Tab1 中心 x > Tab2 中心 x（`uiautomator dump`） | dump XML + 截图 |
| 8 | `am force-stop` 后冷启重跑第 7 条三条；**必须 standalone APK**（Expo Go 会假阳性） | 同上 |
| 9 | 机器初筛（非空像素率 + 连通域数 + tofu 模板相似度）+ **人工终判签字** | 截图 + 签字 |
| 10 | 范围限 `apps/mobile-app/**/*.tsx`；**负向测试**：加一个违规 fixture，eslint 必须报错 | 命令输出 |
| 11 | `/probe/ai` 首字节 + ttfbMs；`/probe/law` 命中 ≥1 **且 EXPLAIN 走 FULLTEXT** | 命令输出 |
| 12 | 四 job 全绿无 skip；**且删掉任一关键规则 id 后 CI 必须红** | CI 记录 |
| 13 | `svc wifi disable` + `svc data disable` → 边界 UI 截图 + logcat 无 FATAL + 进程 pid 未变 + 恢复网络可重试 | 截图 + logcat |

> 第 3 条涉及 RuoYi 后台，属于第 0 段的「后台菜单可访问」最低验证，不是第 1 段的入驻功能。

---

## 十二、必须冻结的东西（第 0 段结束前）

| 冻结物 | 位置 | 为什么必须冻结 |
|---|---|---|
| 14 条不可逆决策 | `docs/adr/ADR-000-不可逆决策总表.md` | 改包名/改错误码格式的代价随时间指数上升 |
| 决策锚点清单 | `docs/adr/DECISIONS_FROZEN.md` | 每条必须能被 grep 到机器锚点，否则「冻结」是空话 |
| 13 条验收证据 | `docs/acceptance/D0-checklist.md` + `evidence/` | 第 1 段开工的准入条件 |
| 环境基线 | `docs/ops/env-baseline-2026-10-28.md` | 出问题时第一个对照物 |
| 三项探针结论 | `P-1-决策与证据.md` / `P-2-字体结论.md` / curl 输出 | 决定第 3 段能否按原计划做 |
| 段末记录 | `docs/CHANGELOG.md` | 含「与计划的偏差」，不是流水账 |

---

## 十三、风险与出口（6 条，按危险程度排）

| # | 风险 | 为什么危险 | 出口 |
|---|---|---|---|
| 1 | **EAS 构建失败** | 本机无 Android SDK，EAS 是唯一路径；且 `expo-updates` / `secure-store` 需要 dev build，与验收 7/8 是同一条命 | 失败即转本地 SDK（先 `prebuild` 看真实要的版本再装） |
| 2 | **藏文字体无合格方案** | 旧项目那套 234 字形无 GSUB 不可用；候选字体是否含 GSUB **我没验证过** | 脚本先筛，全不合格则 `bo` 是否保留要重新拍板 |
| 3 | **LocalizedText 切分做不对** | 切错 → 断笔，症状和「字体缺 GSUB」一模一样，**会白换一轮字体** | 先写单测覆盖藏文 / 阿拉伯再上真机 |
| 4 | **`reloadAsync` 在 dev build 下 no-op** | 那就只能靠 release APK 验 RTL 重启，迭代变慢 | dev 期用 `am force-stop` 手工验 |
| 5 | **RuoYi 后台跑不起来** | RuoYi 有配套后端（`sys_*` 20+ 张表），第 0 段要它登录；与 faze-api 的 Boot / MyBatis-Plus 版本可能冲突 | **分库**（`faze` / `faze_admin`）+ 两后端并联，第 1 段再做合流 spike |
| 6 | **Redis 5.0 与 Spring Boot 3.5 兼容性** | 本机是 Windows 移植版 5.0.14.1（官方已到 7.x），只实测了 `PING` / `SET EX` / `GET` / `TTL` / `DEL`，**没测过 `spring-boot-starter-data-redis` 的全部行为** | 第 0 段只用基础命令，风险低；若 Redisson 报版本不支持，分布式锁改用 MySQL 行锁或 `SETNX` 手写 |

---

## 十四、本段明确不做

| 不做 | 为什么 | 什么时候做 |
|---|---|---|
| 全量法条（2000–3000 条） | 第 0 段只需 5 条证明链路通 | 第 3 段 |
| AI 对话功能（`ai_session` 表、多轮上下文） | 本段只验 SSE 通不通 | 第 3 段 |
| 真实支付渠道 | 探针不涉及钱 | 第 6 段 |
| RuoYi 的业务页面 | 本段只验后台能登录、菜单能进 | 第 1 段起 |
| 四个业务域的空包 | 空包只会诱导人往里塞错位置的东西 | 需要时再建 |
| 任何形式的 docker | 项目负责人明确不要 | — |

---

## 十五、开工前最后确认

| 项 | 状态 |
|---|---|
| MySQL root 密码 | ✅ 已有（`123456`） |
| MySQL80 服务在跑 | ✅ 已确认 |
| Redis 可用 | ✅ 已确认（5.0.14.1，`PONG`） |
| node / npm | ✅ 已确认（v24.16.0 / 11.13.0） |
| Java 17 | ✅ 已确认（17.0.17） |
| eas-cli | ✅ 已确认（22.0.0） |
| 删除原型 + 前端重写 | ✅ 已确认 |
| 不用 docker / 用 npm / 单 token / 无域名用 `faze://` | ✅ 均已确认 |
| **豆包 API key** | ⏳ 到 **T0-33** 时提供 |
| **Android 真机** | ⏸ 暂不连接，**必须在 T0-31 之前连上** |

**技术侧开工阻塞已清零。** 剩下两项分别在 T0-33 和 T0-31 才需要，**不影响从 T0-01 起步**。

---

## 十六、这份文档的变更规则

1. 执行中发现计划有问题 → **先按计划做完，再在 CHANGELOG 记「偏差 + 原因 + 影响」**，不要边做边改计划。
2. 计划需要实质修改 → 升 v3.1，在文首记录改了什么、为什么。
3. **验收判定式（第十一节）不许在执行中放宽。** 做不到就写「未通过」。

---

> **【文档基线 v1.2】** 本文件属于「法泽 FAZE 文档基线 v1.2」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
