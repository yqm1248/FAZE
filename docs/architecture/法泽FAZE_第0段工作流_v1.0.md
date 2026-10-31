# 第 0 段工作流：可安装底座 + 三项探针

> **上位**：《法泽FAZE_开发计划总纲_v1.0.md》§5.1（段 0 边界、交付物、8 条验收锚点、风险出口）、§11.3（12 任务 / 4 组）、§11.1（本文的九段模板）。
> **位置**：本段是第 0 段的**唯一施工文档**。从第一行读到最后一行即可施工，不需要翻别的文档。被引用的规范（数据表 / 接口 / 后端代码 / i18n / 审计日志）只作为**条款来源**，凡本文件已写明的不必回去读原文。
> **不覆盖**：iOS 真机、任何业务功能、段 1 及以后的设计。
> **本文件的写法**：每个任务五段式（做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办）。「算做完」一律二值可判定，不写「基本完成」。
>
> 落款见文末（文档基线 v1.19）。
## 零、开工前必须知道的三件事（2026-10-31 实测）

总纲写于 2026-10-30，其中对仓库与本机环境的假设与今天实测**不一致**。先纠正，否则会照着错误前提施工。

| # | 实测事实 | 与总纲假设的偏差 | 后果 |
|---|---|---|---|
| 1 | `apps/mobile-app` **不是空的**：已有 Expo 54 工程、**16 个路由文件**（全部合规，一行 `export`）、**8 个功能域 / 12 个页面 + 5 个 fixture（共 17 个文件）**、数据全部来自假数据 | 总纲 §11.3 把「B1 Expo 工程与路由树」当成从零搭建 | B1 的工作性质从「搭建」变为「**审计 + 改造 + 处置存量**」；且这 12 个页面是 `0-A7`（全仓搜裸 `<Text>` 为 0）的最大风险源 |
| 2 | **本机没有 Android SDK**，只有 adb（winget 装的 platform-tools）。`ANDROID_HOME` / `ANDROID_SDK_ROOT` 均未设置，`%LOCALAPPDATA%\Android\Sdk` 不存在，无 build-tools / platforms / ndk / cmdline-tools | 总纲 §5.1 风险表第 1 条预判了「本机无 Android SDK」，但实测比预判更彻底：**连 SDK 目录都没有，不是版本不全** | `0-A1` 的**本地**构建通道不可行。**但实测 `eas-cli/22.0.0` 已安装且已登录（`1454132yqm`，Owner），EAS 云构建通道立即可用** → `0-A1` 走云构建，不需要先装 SDK。`adb` 可用，所以「真机安装与验证」不受影响。唯一缺的是 `eas.json`（不存在，D1 要建）与 `expo-dev-client`（未安装，仅 dev-client 通道需要） |
| 3 | **本机没有 Redis**：6379 未监听、无 Windows 服务、`redis-server` / `redis-cli` 不在 PATH、七个常见安装目录都不存在。MySQL 8.0.44 正常（服务 `MySQL80` Running、Automatic、3306 监听） | 总纲 §5.1 「工程」行要求 `deploy/start-services.ps1` 同时拉起 MySQL80 与 Redis | `0-A6`（环境可复现）的 Redis 一半**当前必然失败**。这是开工第一件事，处置见任务 A2 与 §六 风险表 |

**读法**：第 1 条属于「计划要改」，第 2、3 条属于「开工前要补」。三条都已在任务里落成动作，不是提醒而已。

---

## 零之二、施工前必须先收口的 5 处冲突

总纲 §十二 第 5 条：「发现冲突先确认哪个对，再改文件，不允许两份都留着」。以下 5 处**不裁决就无法开工**。

### 冲突 1：段 0 到底有几个接口（文档里能读出 3 / 4 / 6 三种答案）

| 说法 | 出处 | 原文 |
|---|---|---|
| 3 个，全是 `pub` | 《接口规范》§7.1 | 「段 0 明确不做的接口：除上述 3 个 + RuoYi 自带登录相关接口外，**不实现任何业务接口**」 |
| 登录组「落段 0」 | 《接口规范》§2.2 第 1 组 | 「`POST /pub/auth/login/password`、`POST /pub/auth/login/sms`、`POST /app/auth/refresh` …… **落段 = 0**」 |
| 段 0 必须有真实登录 | 《V1基线表结构》§二 | 「`app_user` \| 验收 6 要走「登录 → 拿 token → 401 → 回登录页」，**没有用户表就没有真实登录**」 |
| 段 0 必须有真实登录 + 需鉴权接口 | 《开发计划总纲》`0-A2` | 「登录走真实接口；手动让 token 失效……再请求**任一需鉴权接口**」 |

**裁决：§7.1 的清单漏了登录组，且漏了一个「需鉴权资源」。段 0 的接口是 6 个：**

| # | 方法与路径 | 鉴权 | 作用 | 依据 |
|---|---|---|---|---|
| 1 | `GET /api/v1/pub/ping` | 无 | 首页数据来源，证「真接口」 | §7.1 |
| 2 | `POST /api/v1/pub/auth/login/password` | 无 | 手机号 + 密码登录，签 access + refresh | §2.2 组 1（落段 0） |
| 3 | `POST /api/v1/app/auth/refresh` | refresh token | 换新 access token | §2.2 组 1（落段 0） |
| 4 | `GET /api/v1/app/account/profile` | access token | **唯一目的是提供「需鉴权资源」来验 `0-A2` 的 401** | §7.2 资料组；`0-A2` 要求 |
| 5 | `POST /api/v1/pub/probe/ai` | 无（`@Profile("!prod")`） | 豆包 SSE 首字节 | §7.1 |
| 6 | `GET /api/v1/pub/probe/law?kw=` | 无（`@Profile("!prod")`） | ngram 命中 `law_item` | §7.1 |

- `POST /pub/auth/login/sms` **段 0 不做**（短信通道未定，总纲 §5.2 允许段 1 用固定码）。
- 第 2、3、4 个接口的字段级定义在《接口规范》**附录 A.1**，照抄，不许自己设计。
- **为什么必须加第 4 个**：`0-A2` 的判据是「token 失效后请求**任一需鉴权接口**，自动清 token 回登录页」。原清单 3 个接口全是 `pub`（无鉴权），另加 refresh 用的是 refresh token（失败返回 `402002/402003` 而不是 401）——**没有受保护资源，401 在物理上测不出来**。
- **动作**：同步回写《接口规范》§7.1（3 → 6）。这**不是新增契约**，是把已冻结的 §2.2 落到清单里。

### 冲突 2：`0-A2` 的「首页」与 `0-A3` 的「Tab / 进度条」在段 0 没有载体

| 锚点原文 | 段 0 的实际交付物 | 矛盾 |
|---|---|---|
| `0-A2`：「App 冷启动 → **首页**显示的数据来自 `/pub/ping`」 | 只有登录页 + 语言切换页 + 2 个探针页 | 没有「首页」 |
| `0-A3`：「**Tab 顺序镜像**、**进度条反向**」 | 删掉 `(tabs)` 后没有任何 Tab，也没有进度条 | 没有载体 |

**裁决（两处都不改锚点，改载体）：**

1. **`app/index.tsx` = 启动分流器 + 一行连通状态**。它读 SecureStore 的语言与 token → `replace('/login')` 或停在原地；同时显示 `ping` 返回的 `appName` / `version` / `serverTime`。这样「冷启动后看到的第一个屏上有来自 `/pub/ping` 的数据」成立，**且不新建业务首页**（总纲 §5.1「不做任何业务页面」）。
2. **`/dev/rtl` 探针页内置四组静态元素**作为 `0-A3` 的唯一载体：① 一个带返回箭头的头部；② 一条 30% 的进度条；③ 一排 3 个静态 Tab（纯 `View`，不是真 Tab 导航）；④ 两个分别用 `marginStart` / `marginEnd` 定位的方块。探针页的存在理由本来就是「只用于段 0 验收」（总纲 §5.1 交付物），让它承载这些判据是它的本职。

### 冲突 3：law 探针录几条法条（50 还是 5）

| 说法 | 出处 |
|---|---|
| 「先用 **50 条**手工录入的条文做探针」 | 总纲 §5.1 风险表 |
| `V2__init_law_items.sql` = **5 条**真实法条，属第 0 段 | 《V1基线表结构》§五 |

**裁决：两者都要，但放在不同地方——`V2` 迁移里放 5 条，`database/seed/law_items_probe.sql` 里放 50 条。**

依据：Flyway 迁移**一旦提交就不可变**（《数据表规范》的迁移规则），把「50 条探针数据」写进 `V2` 会让以后想调数据时必须新增 `V3`、`V4`……；而种子数据是可反复执行的。`0-A5` 只要求「命中至少 1 条」，5 条够验通，50 条用于让命中率与排序有统计意义。

### 冲突 4：Redis 缺失（环境缺口，不是文档冲突）

`0-A6` 要求「起 MySQL + Redis」，本机**完全没有 Redis**（6379 未监听、无服务、无二进制、七个常见目录都不存在），且本机无 docker。

**裁决**：装 **Memurai for Windows**（Redis 协议兼容、带 Windows 服务）；同时**段 0 的 `faze-api` 不引入 `spring-boot-starter-data-redis`**——refresh token 的轮换状态用进程内 `ConcurrentHashMap`（段 0 单实例够用），并在 `docs/architecture/依赖清单.md` 登记「段 1 第一个分布式锁用例之前必须迁到 Redis」。

若负责人决定段 0 暂缓 Redis，则 `0-A6` 必须**书面**拆成 `0-A6a`（MySQL + Flyway + RuoYi，本段必过）/ `0-A6b`（Redis 就位，挂账到段 1 开工前置）。**改锚点须负责人签字，技术侧不得自行把「MySQL 通」当成「环境可复现」通过。**

### 冲突 5：构建产物不能进 `docs/acceptance/`

`.gitignore` 忽略 `*.apk`、`*.aab`、`*.ipa`、`*.log`、`tmp/`、`logs/`（实测），AGENTS §5 也禁止提交构建产物。但 `0-A1` 要求「把构建日志与安装截图存证」。

**裁决：证据只入库「指针」，不入库二进制。**

| 入库 | 不入库 |
|---|---|
| `eas-build.json`（EAS 返回的构建记录，含 buildId / 状态 / 完成时间） | APK / AAB 本体（留在 `E:\dshcode	mp\seg0pk\`，`_meta.md` 里记 sha256 + 下载 URL） |
| 真机安装与启动的截图 `.png` | — |
| 命令输出 `.txt`（UTF-8） | **`.log`**（被 `.gitignore` 忽略，进不了 git） |
| `_meta.md`（机型 / 系统 / 构建号 / commit / 结论） | `node_modules`、`.expo`、`dist` |


## 零之三、存量代码的处置（实测规模 + 文件级清单）

### 1.1 裁决

**全部从主线删除。纯 UI 复刻整体归档到一个只读分支 `ui-reference`，主线不留任何一页、不留任何 fixture、不留任何「以后可能用得上」的备用数据。**

保留的只有「工程壳」与「设计系统」：4 个路由壳（其中登录壳重写）、`shared/theme/` 三件、`shared/components/` 8 个组件（逐个改造后保留）。`design/ui/` 的 71 个 HTML 设计稿**不动、不删、不迁**——它才是设计权威，RN 复刻只是它的一次性翻译产物，翻译产物没有第二个用途。

### 1.2 依据：为什么不能留

| 依据 | 出处 | 对本裁决的作用 |
|---|---|---|
| 「**不做任何业务页面**（除登录页、语言切换页两个产品页 + 两个探针页）」 | 总纲 §5.1 明确不做 | 现有 12 个页面全是业务页（首页/律师/智能体/案件/我的/进度/文书表单），逐条越界 |
| 「**空壳页面不提前做**。依赖未就绪的页面提前做，产出的是假界面，反而制造『已经做完了』的错觉」 | 总纲 §4.4 归属规则 3 | 这 12 页的数据全部来自 `__fixtures__`，正是「假界面」的定义 |
| 「**线 B：填充**（译文）在段 8」；中间各段「**不许硬编码中文**」 | 总纲 §7.1 | 保留页面则要么硬编码中文（违规），要么现在就把 8000 字文案译成 4 语言（提前做了段 8） |
| 「禁止把原项目的 HTML 语义标签习惯带进来」等 | `AGENTS.md` §5 | 复刻期产物带着 uni-app 的位图与命名习惯，不是本项目的实现基线 |

### 1.3 实测现状（先纠几个数）

交底材料写「`features/` 9 个域、17 个页面文件」「路由壳 15 个」。实测（`Get-ChildItem` 递归计数）：

| 项 | 交底材料 | 实测 | 说明 |
|---|---|---|---|
| `features/` 域目录 | 9 | **8** | agent / auth / case / home / lawyer / profile / progress / tools |
| `features/` 下文件 | 17「页面文件」 | **17 个文件 = 12 个页面 + 5 个 fixture** | 12 个 `*Page.tsx`，5 个 `*.fixture.ts` |
| `app/` 下路由壳 | 15 | **16** | 见 1.4 清单 |
| 12 个页面里的裸 `<Text>` | 未统计 | **101 处** | 见 1.6 |
| 12 个页面的硬编码中文 | 未统计 | **7,984 个 CJK 字符**（5 个 fixture 另有 3,057） | 同上 |
| 全仓物理方向属性（`marginLeft/right`、`paddingLeft/right`、`borderXxxLeft/Right`、`textAlign` 取 left/right） | 未统计 | **34 处**（窄口径；含 `left/right` 定位的宽口径为 **55 处**，权威统计见 1.9）：11 个页面 29 处 + `shared/` 5 处 | 见 1.6 |

计数口径：`[regex]::Matches($raw, '<Text[ >]')` 与 `[\u4e00-\u9fff]`，文件范围排除 `node_modules`/`dist`/`.expo`。**这不影响任何裁决，只影响文档里写的数字——请以实测数为准回写。**

### 1.4 文件级处置（主线删什么、留什么、加什么）

#### 删除（第 0 段开工第一件事，一次提交）

`features/` 下 **17 个文件全部删**：

```
features/agent/pages/AgentChatPage.tsx            + features/agent/__fixtures__/agent.fixture.ts
features/auth/pages/RegisterPage.tsx              （LoginPage 见下：重写，不删）
features/auth/pages/SplashPage.tsx                （分流逻辑上移到 app/index.tsx）
features/case/pages/CaseListPage.tsx              + features/case/__fixtures__/cases.fixture.ts
features/home/pages/HomePage.tsx                  + features/home/__fixtures__/home.fixture.ts
features/lawyer/pages/{LawyerListPage,LawyerDetailPage,LawyerJoinPage}.tsx
                                                  + features/lawyer/__fixtures__/lawyers.fixture.ts
features/profile/pages/ProfilePage.tsx
features/progress/pages/ProgressPage.tsx          + features/progress/__fixtures__/progress.fixture.ts
features/tools/pages/LawsuitFormPage.tsx
```

`app/` 下 **12 个路由壳删**：

```
app/(auth)/_layout.tsx        app/(auth)/register.tsx
app/(tabs)/_layout.tsx        app/(tabs)/{home,lawyer,agent,case,profile}.tsx   ← 整目录删
app/lawyer/{detail,join}.tsx
app/lawsuit-form.tsx          app/progress.tsx
```

其他：

```
apps/mobile-app/assets/legacy/index.ts        ← 删。它把位图与语义名绑定，引用的图随页面删除已成死代码；
                                                 位图文件（*.png/*.jpg）全部保留、不删（app.json 仍用 legacy/logo.png）。
apps/mobile-app/shared/navigation/routes.ts   ← 重写（不是删）：只留段 0 的 7 条路由常量。
```

#### 保留（改造后留）

```
app/_layout.tsx              保留，重写：字体门控 + ErrorBoundary 导出 + 401 handler 注册
app/index.tsx                保留，重写：只做分流（读 lang/token → replace）
app/+not-found.tsx           保留，改：2 处裸 <Text> → LocalizedText
app/(auth)/login.tsx        → git mv app/login.tsx（取消 (auth) 分组，理由见 B1）
shared/theme/{tokens,typography,index}.ts     不动（tokens.ts 1,846 个 CJK 注释是合法的，令牌文件豁免 lint）
shared/components/*.tsx      8 个全留，逐个改造：4 个含裸 <Text>（AppToast / SearchBar / SectionTitle / TagChip）
                             这 4 处渲染的是调用方传入的字符串，改用 LocalizedText 包裹即可，不产生文案。
shared/components/AppHeader.tsx / SearchBar.tsx / SectionTitle.tsx   共 5 处物理方向属性 → start/end（B3）
```

#### 新增（B 组/D 组产出，详见各任务）

```
app/login.tsx  app/language.tsx  app/dev/probe.tsx  app/dev/rtl.tsx
features/auth/pages/LoginPage.tsx        （重写，不是新页）
features/settings/pages/LanguagePage.tsx （语言切换页，用 profile namespace，据 i18n 规范 §4.3）
features/dev/pages/{ProbePage,RtlProbePage}.tsx
shared/api/{client.ts,authEvents.ts,types.ts,errorCodes.ts,sse.ts}
shared/i18n/**                 （见 B3）
shared/components/{LocalizedText,LtrText,DirectionalIcon,AsyncBoundary,EmptyState,ErrorState,LoadingState}.tsx
shared/components/icons/index.ts
scripts/{fetch-tibetan-font.py,verify-tibetan-font.py,check-i18n.ps1,check-raw-text.ps1}
app.config.ts                  （由 app.json 迁出，见 B1）
```

### 1.5 fixture 数据的处置

**删，不迁、不留备份副本。** 三条理由：

1. `0-A2` 的判据是「**首页数据非硬编码**」。判定方式只能靠「仓库里不存在任何可被误用的假数据」——fixture 留着，这条判定就永远缺一个可信前提。
2. fixture 里沉淀了复刻期的类型形状（`LawyerItem`、`CaseItem` 等）。段 1~5 的字段以《接口规范》附录 A 的冻结契约为准，留着旧类型形状会诱导后来的施工方照抄。
3. 它们不参与 `tsc --noEmit` / `eslint` 判定（都是纯数据文件），但会长期占据 git diff 噪音，且是 3,057 个硬编码中文字符的来源。

### 1.6 直接回答：留着会不会污染 `0-A7`？

**会，而且是硬污染，不是「可能有影响」。**

`0-A7` 的判据原文是「三者零错误；**全仓搜直写 `<Text>` 为 0**（`LocalizedText` 自身除外）；`marginLeft/right`、`paddingLeft/right`、`left/right` 被 lint 报错」。

- 裸 `<Text>` 实测 **107 处**（12 个页面 101 + `shared/` 4 + `app/+not-found.tsx` 2）。保留页面就必须把闸门改成「全仓为 0（12 个页面除外）」——**闸门一旦带 107 条豁免，后面 68 页会持续往名单里挤，`0-A7` 从此失效**。
- 就算把 107 处全换成 `LocalizedText`，这 12 个页面还有 **7,984 个硬编码中文字符**。要同时过 `no-raw-text`，就得给它们建约 800 个 key 并填四语言——这是**段 8 的填充线**，与总纲 §7.1 直接冲突。
- 物理方向属性方面，12 个页面占 29 处 / 34 处。删掉页面后，B3 只需要修 `shared/` 剩余的 5 处，规则可以从第一天起就是 `error` 而不是 `warn`。
- 更根本的一条：**`0-A3` 也一样被污染**。这 12 个页面全部按 LTR 排（29 处物理方向属性），RTL 下会错位；它们既不在 `0-A3` 的验证范围内，又会在「切到 `ug-Arab-CN`」时出现在界面上，让验收人无法判断「镜像对了没」。

### 1.7 归档动作（第 0 段第一个提交前执行）

```powershell
cd E:\dshcode\FAZE
git switch -c ui-reference            # 从当前 UI设计 HEAD 切出，保存现状
git switch UI设计
# 之后在 UI设计 上删除 1.4 列出的文件，一次提交：
#   chore(mobile): 裁剪至第 0 段边界，UI 复刻归档到 ui-reference 分支
```

同时在 `docs/analysis/` 新增一条说明（`AGENTS.md` §7.4 要求文档基线可追溯）：**UI 复刻实现位于 `ui-reference` 分支，非权威；设计权威仍是 `design/ui/` 的 71 个 HTML。** 该分支**只读**，不合并、不 rebase、不再接收提交。

### 1.8 不接受的替代方案（写下来防复发）

| 方案 | 为什么不行 |
|---|---|
| 在 eslint 里排除 `features/`，让 `0-A7`「搜 `<Text>` 为 0」通过 | **自欺**。`0-A7` 的原意是「没有裸 `<Text>`」，不是「扫描器看不见裸 `<Text>`」。闸门一旦带 107 条豁免，后面 68 页会持续往名单里挤，`0-A7` 从此失效 |
| 保留 12 页并逐页改造成 `LocalizedText` + `start/end` | 要改 101 处 `<Text>` + 29 处物理方向属性，还要为 **7,984 个硬编码中文字符**建约 800 个 key 并填四语言——那是段 8 的填充线。**为不验收的东西付这份钱** |
| 保留但注释掉 | 总纲 §6.3 明令「注释掉的代码块、临时开关、`TODO: 以后改`」不许带进下一段 |

**删除是唯一需要负责人签字的动作**（本文件其余裁决技术侧可自行执行）：删除本身可由 `ui-reference` 分支恢复，但一次移除 15 个页面属于不可逆感强的变更，技术侧不得以「反正 git 里有」为由自行执行。

### 1.9 主理人复测：权威数字（专家稿里的 34 / 41 / 54 是三种正则口径）

三位起草者各自数过一遍，得出 **34 处 / 41 行 / 54 处** 三个数。**三个都不是错的，但它们用的正则不同**，放进同一份文档会互相打脸。这里给一次可复现的权威统计（口径写在表里，任何人可复跑）：

| 项 | 权威值 | 统计口径（正则） |
|---|---|---|
| 裸 `<Text>` | **109 次出现 / 107 行 / 19 个文件** | `<Text[\s>]` |
| 物理方向属性合计 | **55 处** | 下列五类之和 |
| ├ `marginLeft/Right` | 24 | `margin(Left\|Right)\s*:` |
| ├ `border*Left/Right` | 15 | `border[A-Za-z]*(Left\|Right)[A-Za-z]*\s*:` |
| ├ `left/right` 定位 | 11 | `(?<![A-Za-z])(left\|right)\s*:\s*[\d'"\`-]` |
| ├ `paddingLeft/Right` | 5 | `padding(Left\|Right)\s*:` |
| └ `textAlign: 'left'/'right'` | **0** | — |
| 只算 margin/padding/border/textAlign（不含 `left/right` 定位） | **44 处** | — |

扫描范围：`apps/mobile-app/{app,features,shared}` 下全部 `.ts`/`.tsx`，排除 `node_modules`、`.expo`、`dist`；共 **45 个文件**。

**裸 `<Text>` 的 19 个文件分布**：12 个待删页面（**101 次**）+ `app/+not-found.tsx`（2 次）+ `shared/components/` 6 个文件各 1 次（AppHeader / AppToast / PrimaryButton / SearchBar / SectionTitle / TagChip，共 6 次）。**删掉 12 个页面后只剩 8 次**，由 B3 与 C1/C2 一并清掉。

**物理方向属性的分布**：12 个待删页面占约 50 处，`shared/components/` 占 5 处，其余在 `app/+not-found.tsx`。**删页面后 B3 只需修 5 处左右**——这就是「删比改便宜」的量化依据（改：101 次 `<Text>` + 50 处方向 + 7,984 个中文字符 ≈ 800 个 key；删：0）。


## 零之四、PC 后台的版本（2026-11-01 已裁决，不再是待决项）

`0-A6` 要求「RuoYi 能登录并看到 `biz` 菜单页」——这需要**后端**能起来。

**裁决结果**：PC 后台统一到 **RuoYi-Vue 3.9.2 官方发行版（tag `v3.9.2`，Spring Boot 4.0.3）+ 前端 RuoYi-Vue3 3.9.2**。原先的 3.8.7 方案作废，`faze-api` 同步从 Spring Boot 3.5 升到 **4.0**。

### 4.1 版本对应关系（读任何文档时的强制解释规则）

`3.5` / `3.9.2` / `4.0` 是**三个不同维度**，混着读必然误判。按下表解释：

| 你看到的字样 | 真实含义 | 常见误读 |
|---|---|---|
| 「RuoYi 3.9.2」 | **RuoYi 平台版本**，唯一合法值 | 误以为还有别的可选版本 |
| 「Spring Boot 4.0」（4.0.3 / 4.0.10） | **Spring Boot 框架版本**：RuoYi 发行版是 4.0.3，faze-api 与 parent 可到 4.0.x | **不是 RuoYi 版本**；也不是「RuoYi 4.0」 |
| 「Vue 3.5.26」「Element Plus 2.13.1」 | 前端依赖版本 | 与 RuoYi、Spring Boot 都无关 |
| 「3.8.7」 | **已废止** | 误以为「还能作为参考实现」 |
| 「3.5.x」 | **已废止**（faze-api 与 RuoYi 都不再是 3.5） | 误以为「Spring Boot 还是 3.5」 |

**三条判读规则（缺一不可）**：

1. **RuoYi 版本一律 3.9.2**——没有例外、没有备选、不再讨论。
2. **Spring Boot 版本是 4.0**——RuoYi 发行版 4.0.3、faze-api 4.0.x。**看到「Spring Boot 3.5」按缺陷处理**（唯一例外：历史 CHANGELOG 里对新旧对照的记述）。
3. **判 RuoYi 版本只看两个字段**：`pom.xml` 的 `<ruoyi.version>`、`package.json` 的 `"version"`。**不要看 git tag**——`springboot3` 分支的 tag 停在 `v3.8.7`，与官方发行线不是一回事；tag 落后**不是**「3.8.7 还能用」的证据。

### 4.2 违规判定（发现即按缺陷处理，不许「两个都留着」）

| 情况 | 判定 |
|---|---|
| 文档 / 代码 / 命令里出现 RuoYi **3.8.7** | 缺陷 → 改为 3.9.2 |
| 出现 **Spring Boot 3.5**（除历史 CHANGELOG 的新旧对照） | 缺陷 → 改为 4.0 |
| 使用 RuoYi 的 **`springboot3` 分支**（Spring Boot 3.5.14） | 缺陷 → 改为官方发行版 `v3.9.2` |
| 使用 RuoYi 的 **`master` 分支** | 缺陷 → 改为官方发行版 `v3.9.2`（master 在发行点之后又升过依赖，**不等于**发行版） |
| 拿 **git tag** 判 RuoYi 版本 | 缺陷 → 只看 `pom.xml` 的 `ruoyi.version` |
| 拿 `RuoYi-Vue` 仓库自带的 `ruoyi-ui` 当项目前端 | 缺陷 → 那是 **Vue 2.6.12 + element-ui** 的老前端；项目前端是 RuoYi-Vue3 **v3.9.2**（Vue 3.5.26 / Element Plus 2.13.1） |

> **本节优先级高于本文其他任何涉及 RuoYi / Spring Boot 版本的描述**，也高于技术路线、PC 计划、设计说明、CHANGELOG 历史条目中的旧表述。冲突时以本节与 `AGENTS.md` §2 为准。

**已落地的源码**（2026-11-01 实测）：

| 项 | 值 |
|---|---|
| 路径 | `E:\itheima\SSM\3、SSM框架（…）\文件\RuoYi-Vue` |
| 分支 / HEAD | `release-3.9.2` / `0e2d75c2`（**= tag `v3.9.2` 指向的提交**，2026-03-26「若依 3.9.2」） |
| 版本 | `ruoyi.version=3.9.2`、`spring-boot.version=4.0.3`、`mybatis-spring-boot.version=4.0.1`、Java 17 |
| 状态 | `git status` 干净；638 个源码文件（265 java / 101 vue）；SQL = `sql/ry_20260320.sql` + `sql/quartz.sql` |
| 原课程作业 | 切出前已备份至同级 `_RuoYi课程作业备份_20261101\`（8 个文件：`course` 模块 6 个 + `application-druid.yml` + `MyTask.java`） |

**由此产生的新约束**：

- `faze-api` 同步升到 **Spring Boot 4.0**（原锁定的 3.5 作废），见 `AGENTS.md` §2。
- **✅ 已实测（2026-11-01，原为「唯一未验证项」）**：`mybatis-plus-spring-boot3-starter:3.5.12` 在 SB4 下**静默失效**（`@AutoConfigureAfter` 引用的旧包名 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` 已被 SB4 移到 `org.springframework.boot.jdbc.autoconfigure`，类不存在 → 排序丢失 → 自动配置跳过，且**不报缺类**）。改用 **`mybatis-plus-spring-boot4-starter:3.5.18.1`** 后完全可用（启动 + `selectCount` + `selectById` + `@TableName` 全通过）。证据 `docs/acceptance/seg1/A3-mp-probe.md`。**因此 A3 从「可能一票否决」降为「复验」**，ORM 方案不需要换。
- RuoYi 代码**不进** `E:\dshcode\FAZE` 仓库（第三方框架，且 AGENTS §3.3 禁止改框架层）。A1 只登记**外部路径 + 版本 + 初始化 SQL 位置 + 启动方式**，写进 `deploy/README.md` 与 `docs/analysis/ruoyi-patches.md`。

**顺带查到的事实**（A1/A3 直接用）：
- RuoYi 前端 vite 代理目标 = `http://localhost:8080`（读 `vite.config.js`）→ 后端默认端口 8080，与 faze-api 的端口规划**必须错开**（faze-api 建议 8081；具体见 A3）。
- MySQL 的 `my.ini`（`C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`）里**没有任何 `ngram` / `innodb_ft` 配置** → `ngram_token_size` 取 MySQL 8.0 默认值 **2**，正好符合《数据表规范》要求的 `ngram_token_size=2`，**A3 不需要改 `my.ini`**（这一点省掉一次 MySQL 重启风险）。

---

## 一、本段目标与出口

**目标**：把「能不能装上手机点一遍」变成可重复的事实，并用三项探针提前打掉三个可能一票否决的技术风险。本段**不产出任何业务功能**。

**出口（进段 1 的条件）**：`0-A1`~`0-A8` 八条全部通过，且 APK 构建路径已文档化（换台机器照文档能做出来）、字体选型结论写进 `docs/standards/`、探针截图与构建日志存进 `docs/acceptance/seg0/`。

**出口的反向定义**（什么不算出口）：八条锚点里有一条是「看起来能用但没验」——不算；三项探针里有一项是 mock 返回而没走真链路——不算；构建路径只在本机能跑——不算。

---

## 二、开工前置检查（逐条勾，不通过不开工）

总纲 §6.1 的六条是**所有段**的通用前置。段 0 是起点，其中两条不适用（没有上一段），替换为段 0 特有的四条。**这张表 14 项，缺一项不许开工。**

| # | 检查项 | 怎么确认 | 不通过怎么办 |
|---|---|---|---|
| 1 | 本工作流文档已产出并读到最后一行为止 | 本文件 | — |
| 2 | 分支已确定：第 0 段在哪个分支施工 | 任务 A1 的裁决 | 先裁决再动手 |
| 3 | **EAS（Expo Application Services）账号可用** | ✅ **已满足（2026-10-31 实测）**：`eas-cli/22.0.0` 已全局安装；`eas whoami` 返回 `1454132yqm`（Role: Owner），另有组织 `yqm1454132s-team`。换机器时必须重新验证（`eas whoami`） | 三条全不通则**整个分期体系必须重设计**；出口见 §六 R1 |
| 4 | 至少 1 台真实 Android 手机可用，且 `adb devices` 能看到它 | `adb devices -l` 输出中有 `device` 状态行 | 段 0 的核心锚点全部要求真机，模拟器不算。没有真机 → 先解决设备，不要开工 |
| 5 | **Redis 可用**（本机没有，见 §零 第 3 条） | `Test-NetConnection 127.0.0.1 -Port 6379` 为 True，或客户端 `PING` 返回 `PONG` | 按任务 A2 的三个方案装；装不上则必须**书面申请改 `0-A6`**（改锚点须负责人确认，技术侧不得自行降级） |
| 6 | MySQL 8.0.44 服务可用 | `Get-Service MySQL80` 为 `Running`；`mysql -u<user> -p -e "select version()"` | 已满足（2026-10-31 实测 Running） |
| 7 | **豆包 API key 可用**（或明确接受 mock） | 能发一次非流式请求拿到响应 | 用官方免费额度；探针只验链路通不通，不验回答质量。**mock 必须在代码里留 `PROBE_STUB` 开关并在 CHANGELOG 登记「待替换」** |
| 8 | 藏文字体来源与商用授权已确认 | 授权文件或书面确认 | 没有授权不得进包。备选 Noto Sans Tibetan（SIL OFL）；覆盖不足则**这是产品范围决策，必须负责人拍板**，不许技术侧自行降级 |
| 9 | 段 0 需要的表结构已定稿 | 《V1 基线表结构_v1.0》含 `app_user`、`law_item` 完整 DDL | 已定稿（基线 v1.18 内含） |
| 10 | 段 0 需要的接口契约已定 | 《接口规范》§7.1 已给出 3 个探针接口的路径 / 方法 / 鉴权 / 响应字段（`ping` → `{appName, version, serverTime, env}`；`probe/law` → `{hits:[{id, lawCode, articleNo, snippet, score}]}`；`probe/ai` → SSE 流），并裁决归 `pub` 且 `@Profile("!prod")` | **字段有了，但 SSE 的事件协议没有**（event 名、data 结构、结束标记、错误帧），`probe/law` 的 `kw` 长度限制与 `score` 算法也没有 → 任务 A3 先补这三项再写代码，否则前端无法按契约实现流式接收 |
| 11 | `law_item` 探针数据来源已定 | 50 条手工录入条文的来源与录入人 | 段 3 才全量入库。没有 50 条 → 探针只能验「表能查」，验不了「ngram 能命中」，`0-A5` 作废 |
| 12 | RuoYi 后端（`faze_admin` 侧）可启动、能登录 | 账号密码、启动方式 | PC 端 `0-A6` 要「RuoYi 能登录并看到 biz 菜单页」，后端起不来这一半过不了 |
| 13 | PC 后台源码的仓库归属已裁决 | **✅ 已裁决（2026-11-01）**：源码在仓库外 `E:\itheima\SSM\3、SSM框架（…）\文件\RuoYi-Vue`（分支 `release-3.9.2` = tag `v3.9.2`），不进本仓库；仓库内只建 `pc/README.md` 指针（详见 §零之四 与「裁决 2」） | 已裁决，A1 可开工 |
| 14 | 包管理器口径已裁决 | 任务 A1 的裁决（现有 `package-lock.json` = npm，本机也装了 pnpm 11.5.0）。**实测补充**：`README.md` L43 已明文写「包管理器：npm」，`apps/mobile-app` 无 `node_modules/.pnpm` → 文档与事实都指向 npm | 未裁决则 `0-A7` 的 `npm run lint` 与锁文件会出现双轨 |
| 15 | **施工用的 shell 具备管理员权限** | `([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrator')` 返回 `True` | **实测当前为 `False`**。`Start-Service` / `Stop-Service` 需要管理员 → `0-A6` 的验收必须在**管理员 PowerShell** 里跑。脚本**不做自动提权**，检测到非管理员时以退出码 3 退出并给出提示 |
| 16 | **MySQL root（或有建库权的账号）凭据可用** | `mysql -u<user> -p -e "select 1"` 能返回 | **实测 `mysql -uroot` 返回 `ERROR 1045 (28000): Access denied`**——没有凭据就建不了 `faze` 库、建不了应用账号、也查不到 `ngram_token_size` 实际值。这条不解决，A3 的一半验收卡死 |

---

## 三、任务分组（4 组 / 12 个任务）

**开工第 0 步：先做 §零之三 的裁剪与归档**（打 `ui-reference` 分支 → 删 15 个页面/路由壳/fixture → 一次提交）。**这一步在任何任务之前**，因为不删掉它们，B3 的 lint 规则一开就全红，`0-A7` 从第一天就不可能过。

**组间设检查点，组内连续推进。** 检查点的含义是「这一组做完才有资格做下一组」，不是「每个任务设闸门」。**B 组全部收口后才允许开 C 组**；D1 允许与 C 组并行（它只依赖 B 组的工程），D2/D3 必须在 D1 之后（都要真机）。

| 组 | 任务 | 为什么在这一组 | 组末检查点 |
|---|---|---|---|
| **A 环境与骨架**（3） | A1 仓库骨架与分支约定；A2 本地服务脚本；A3 服务端骨架 + Flyway V1 | 没有工程与服务，后面都没法开始；且 A1 的三个裁决（分支 / PC 归属 / 包管理器）会决定后面所有文件的落点 | 服务端能起、`V1__baseline.sql` 建出 2 张表、`deploy/start-services.ps1` 能从「MySQL 停 + Redis 未启动」拉起到连通 |
| **B 移动端地基**（4） | B1 工程审计与路由树校准；B2 网络层 + 鉴权态；B3 本地化 + RTL 坐标系；B4 页面四态与错误边界 | 这四样一旦定型后续不可推翻，必须在写任何业务页之前完成（总纲 §7.1 线 A「段 0 定型」） | 五个地基件都在，且 `tsc --noEmit` 通过 |
| **C 两个产品页**（2） | C1 登录页（真接口）；C2 语言切换页（`forceRTL` + 软重启） | 验收锚点 `0-A2` / `0-A3` 直接落在这两页上 | `0-A2` / `0-A3` 在真机上可复现 |
| **D 三项探针**（3） | D1 APK 构建 + 真机安装；D2 藏文渲染；D3 豆包 SSE + ngram 命中 | 三项技术风险必须在段 0 消除，不能压到后面（它们会一票否决整个产品形态） | `0-A1` / `0-A4` / `0-A5` 通过 |

**为什么是 12 个而不是旧 v3.0 的 24 个**：旧版把鉴权、RTL、四态、lint 各设成一个任务并各配一条验收，导致「任务勾完了但没有任何东西被真正证明」。这一版把它们合并成「地基四件事 + 用两个最终页面与三项探针统一证明」。**段末只认 §四 的 8 条锚点，不认任务清单的勾选数。**

---

### 第 1 组　A 环境与骨架（A1~A3）

> 本组只解决一件事：**从「MySQL80 已停、Redis 不存在」的状态，靠一条脚本拉起环境，用 Flyway 建出 2 张表，让 `services/faze-api` 能编译、能启动、能出三个探针响应**。
> 本组不写业务代码、不建段 1 的表、不动 `apps/`（移动端归 B 组）、不改 RuoYi 框架层。
> 本文所有「实测」的取样时间是 **2026-10-31**，取样机器即当前开发机（Windows，非管理员 shell）。

#### 组前必须先拍板的三件事

#### 裁决 1：Redis 怎么办

**结论**

| 项 | 裁决 |
|---|---|
| 用什么 | **Memurai for Windows（Developer 版）**，装成 Windows 服务，服务名 `Memurai`，端口 `6379` |
| 不用什么 | Docker（本机没有）、WSL2（无法无人值守）、微软归档 Redis 3.2.100（停更 10 年）、`tporadowski` fork（仅作降级备选） |
| 段 0 是否可暂缓 | **可以暂缓，但必须负责人签字改锚点**；不改锚点则 `0-A6` 不算过 |
| 服务端是否依赖 Redis | **不依赖**。段 0 的 `faze-api` 不引入 `spring-boot-starter-data-redis`（理由见 A3） |

**依据**

1. 本机实测（2026-10-31）：`Get-Service` 无任何 Redis/Memurai 服务；`redis-server` / `redis-cli` / `memurai` 均不在 PATH；`6379` 端口无监听；题目列的 7 个候选目录（`C:\Program Files\Redis`、`C:\Redis`、`E:\Redis`、`E:\redis`、`C:\ProgramData\chocolatey\lib\redis-64`、`E:\tools\redis`、`D:\Redis`）全部不存在。**Redis 不是「没启动」，是「没安装」。**
2. `0-A6` 原文（总纲 §5.1）：「从『MySQL80 服务已停 + Redis 未启动』开始，跑 `deploy/start-services.ps1` → 两个服务都拉起并打印连通状态」。锚点写死了「两个服务」，所以 Redis 缺失直接卡住这条锚点。
3. 项目要求「真实上线」，Redis 7 在技术栈里是锁定的（AGENTS §2），段 1 起要用它做分布式锁（总纲 §7.5 的任务表方案明确写「加分布式锁（Redis）」）。因此**不能永久不用 Redis**，只能「段 0 暂缓 + 段 1 开工前必须就位」。

**安装步骤（粒度到命令，安装包来源标注待确认）**

1. 下载 Memurai Developer MSI：来源 `memurai.com` 的 get-memurai 页面。**待确认**：具体版本号、是否需要注册邮箱换取下载链接、Developer 版许可是否允许本项目（商业产品，开发环境免费但上线需付费授权）。
2. 静默安装：`msiexec /i Memurai-x.y.z.msi /qn /norestart`（需管理员）。
3. 安装后确认服务：`Get-Service Memurai` → `Status=Running`、`StartType=Automatic`；配置文件默认在 `C:\Program Files\Memurai\memurai.conf`，确认 `port 6379`、`bind 127.0.0.1`（**不绑 0.0.0.0**，本机开发不需要对外）。
4. 启动：`Start-Service Memurai`（需管理员）；停止：`Stop-Service Memurai`。
5. 连通验证：`memurai-cli -h 127.0.0.1 -p 6379 ping` → 期望 `PONG`。**不用「端口 6379 有监听」当判据**——端口通只证明有别的东西在听，不证明是 Redis、更不证明能读写。

> 安装包、许可、版本号均为**待确认**，本机未安装、未实测。凡涉及下载的步骤，本文不写成「已完成」。

**备选为何不行**

| 候选 | 为什么不行 |
|---|---|
| **Docker** | 本机实测没有 docker（`docker` 不在 PATH，无 Docker Desktop）。题目也明确「不用 docker」。排除。 |
| **WSL2 里装 Redis** | 需要：启用虚拟化功能 + 装发行版 + 首次启动设用户密码，三步都要交互与重启；`start-services.ps1` 要写成 `wsl -d Ubuntu -u root service redis-server start`，而 `sudo`/`-u root` 在未配置免密时必失败。**「一条脚本拉起」这个前提直接不成立**，且把故障面从「一个 Windows 服务」扩大到「虚拟化 + 发行版 + systemd」。排除。 |
| **微软归档 Windows 版 Redis（3.2.100）** | 最后更新 2016 年，官方仓库已归档、无安全修复；协议停在 3.2（缺 `ACL`、`RESP3`、部分 `UNLINK` 行为）。作为「真实上线产品」的开发环境，端口暴露的旧版 Redis 是常见的挖矿入口。**安全上不可接受**，排除。 |
| **`tporadowski/redis` 5.0.14.1** | 社区 fork，个人维护、无安全更新，协议 5.0。**能力足够段 0**（PING/SET/GET/锁），所以留作**降级备选**：仅当 Memurai 许可谈不下来时用，且必须在 `docs/architecture/依赖清单.md` 登记「版本与规范的 Redis 7 有差距、上线前必须换」。 |
| **不用 Redis，服务端用进程内缓存替代** | 段 0 可行（本段服务端根本不用 Redis），但段 1 的分布式锁没有替代品（单机进程锁在多实例下失效）。只能作为「段 0 暂缓」的临时状态，不能作为长期方案。 |

**如果段 0 决定暂缓 Redis，`0-A6` 怎么改（只是建议，我不能自己改）**

- 拆成两条：
  - `0-A6a` **环境可复现（MySQL 部分）**：从「MySQL80 已停」开始跑 `deploy/start-services.ps1 -SkipRedis` → MySQL 拉起、Flyway 建出 2 张表、RuoYi 能登录 → 本段必过。
  - `0-A6b` **Redis 就位**：`memurai-cli ping` 返回 `PONG`、`-Stop` 能停 → **挂账到段 1 开工前置**（写清「为什么可以拖」：段 0 服务端不使用 Redis；「最晚什么时候解决」：段 1 第一个分布式锁用例之前）。
- 改动性质：**拆分锚点 = 改验收锚点**，按总纲 §12.3「验收锚点只增不减，删一条必须负责人确认」→ 必须负责人书面确认，并记进 `docs/CHANGELOG.md`。技术侧只能提建议。

---

#### 裁决 2：PC 后台代码放哪

**结论：源码留在仓库外，仓库内只建「指针 + 登记」，不做 submodule、不复制、不做 subtree。**

| 项 | 裁决 |
|---|---|
| 源码位置 | `E:\itheima\SSM\3、SSM框架（…）\文件\RuoYi-Vue`（分支 `release-3.9.2` = tag `v3.9.2`，Spring Boot 4.0.3），**不进本仓库** |
| 仓库内新增 | `pc/README.md`（获取方式、启动命令、路径约定、验收入口）；`docs/analysis/ruoyi-patches.md`（**实测当前不存在，需在段 0 创建**） |
| 业务代码落点 | 仍在 RuoYi 源码树内 `src/views/biz/<domain>/`、`src/api/biz/<domain>/`（AGENTS §3.3），由 `pc/README.md` 用相对路径登记 |
| 框架层 | **不改**；确需改时记 `docs/analysis/ruoyi-patches.md` |
| 何时改判 | 段 1 起 PC 页 ≥ 5，或出现第 2 个开发者 → 改 **subtree**（不是 submodule） |

**依据（全部为本机实测）**

1. 2026-11-01 已把官方发行版切到 `E:\itheima\SSM\3、SSM框架（…）\文件\RuoYi-Vue`（分支 `release-3.9.2`，= tag `v3.9.2`，Spring Boot 4.0.3）。**前后端与 SQL 齐全**：`ruoyi-admin`~`ruoyi-quartz` 6 个 Maven 模块 + `ruoyi-ui`（Vue 2.6.12 + element-ui 2.15.14，**vue-cli 不是 vite**）+ `sql/ry_20260320.sql` + `sql/quartz.sql`。此前「本机只有前端、没有 Java 后端」的阻断**已消除**。
2. 该发行版的默认配置（实测读 `application.yml` / `application-druid.yml` / `.env.development`）：后端端口 **8080**、context-path `/`；数据源 `jdbc:mysql://localhost:3306/ry-vue`（root/password）；**Redis 强依赖**——`localhost:6379` database 0；上传目录 `D:/ruoyi/uploadPath`；`token.secret` 是默认值 `abcdefghijklmnopqrstuvwxyz`（**生产必须换，且它是 A 方案的共享 secret 落点**）。前端 `ruoyi-ui` 走 vue-cli，`VUE_APP_BASE_API = '/dev-api'`。
3. AGENTS §3.3 要求「不得修改 RuoYi 框架层代码；确需改动时记 `docs/analysis/ruoyi-patches.md`」——而实测 `docs/analysis/` 只有 3 个文件（原项目技术债清单、参考项目规范提取、README），**`ruoyi-patches.md` 不存在**。`docs/design/未完成需备注.md` L145 也写明「该文件目前不存在，第一次 patch 时创建」。
4. 段 0 对 PC 的要求只有三件：**能启动、能登录、能显示一个 `biz` 菜单页**（总纲 §5.1 交付物「后台」行 + `0-A6`）。这是「1 个占位页 + 4 个 RuoYi 自带契约接口」的量，**不产生需要版本管理的自研源码**。

**必须先说的坏消息（这条比「代码放哪」重要）**

`0-A6` 里的「RuoYi 能登录」在当前机器上**仍然不能通过**，但原因变了——**不是缺后端，而是缺 Redis**：

- RuoYi 后端（v3.9.2 / Spring Boot 4.0.3）已就位（见 §零之四），前端登录要打 `/dev-api/login`、`/getInfo`、`/getRouters`、`/captchaImage`，代理目标是 `http://localhost:8080`；
- 但 RuoYi 的登录态**存在 Redis 里**（`login_tokens:<uuid>`，`TokenService` 直写 `RedisTemplate`），本机 6379 无监听（实测），且没有 docker、WSL 无已安装分发版（见 A2）；
- 所以 `0-A6` 的通过顺序是：**A2 把 Redis 起来 → 建 `faze_admin` 库 → 执行 `sql/ry_20260320.sql` 与 `sql/quartz.sql` → 起 RuoYi 后端 → 才能验「能登录」**。

`0-A6` 从「不可能」变成「**被 A2 阻塞**」。两条出路：

| 出路 | 内容 | 代价 |
|---|---|---|
| A. 按 A2 装 Redis | 装 Memurai（Windows 原生发行版）或 Docker/WSL 分发版，6379 起来后起 RuoYi 后端 | 一次安装；A2 本来就要做 |
| B. 降锚点 | 把「RuoYi 能登录」降为「RuoYi 前端能启动、能打开登录页（后端未就绪）」 | 改锚点，**必须负责人签字**；`0-A6` 的「后台可用」顺延到段 1 |

**备选为何不行**

| 候选 | 为什么不行 |
|---|---|
| **git submodule** | ② 确定事实：它自带 `.git` 且工作区可能被本地改动，submodule 会显示永久 dirty，而我们无权提交它；③ 确定事实：`.git` 嵌套会让主仓的 `git status` / `git clean` 行为变得难解释。 |
| **复制进 `pc/`** | ① 28 页 PC 设计稿对应源码立刻变两份，改一处要改两处，且没有机制发现漂移；② `node_modules`、`.env.*`、`package-lock.json`+`pnpm-lock.yaml` 会混入本仓；③ 「不许改框架层」这条约束在「框架层就在自己仓里」时几乎守不住。 |
| **git subtree** | 会把 RuoYi 全量历史（含大量与本项目无关的提交）塞进本仓，仓库体积与 clone 时间不可逆上升；段 0 只需要 1 个占位页，收益为负。 |
| **只留外部路径、什么都不登记** | 换机器就复现不了（`0-A6` 的「环境可复现」本意），且 patch 无处记录 → 违反 AGENTS §3.3。 |

**「何时改判」的机械判据**（避免以后靠感觉吵）

满足任一条即从「外部路径」升级为 **subtree**：
1. 段 1 结束时 `pc/` 登记的 biz 业务页 ≥ 5 个；
2. 出现第 2 个需要改 PC 代码的开发者；
3. `docs/analysis/ruoyi-patches.md` 里框架层 patch 条目 > 3 条（此时应改 fork，而不是 subtree）。

选 subtree 而非 submodule 的理由：升级后我们要**直接改** biz 页面并提交，submodule 只能指一个外部 commit、改不了。

---

#### 裁决 3：包管理器口径

**结论：全仓 npm。补 `packageManager` 字段，只在 `apps/mobile-app/package.json` 补；不引入 pnpm workspace。**

| 项 | 裁决 |
|---|---|
| 包管理器 | **npm 11.13.0**（本机实测版本） |
| 锁文件 | `apps/mobile-app/package-lock.json` 为唯一锁文件；一切脚本用 `npm ci`（可复现），不用 `npm install` |
| `packageManager` 字段 | **补**，值 `"npm@11.13.0"`；由 **B 组在 B1 重建工程时加**（A1 不动 `apps/`） |
| 根目录 `package.json` | **不加**。当前不是 workspace，加了会改变 node_modules 提升行为，且 Expo/Metro 需要额外配置才认 |
| `services/faze-api` | Maven 固定版本（`pom.xml`），无「锁文件」概念；版本一律写明确值，不用 `LATEST`/范围版本 |
| `pc/`（仓库外） | 不归本仓管，但在 `pc/README.md` 登记「RuoYi 用 npm 安装」 |

**依据**

1. `README.md` L43 已明文写「包管理器：**npm**」——这是**仓库现行文档依据**，不只是「AGENTS 没写死」。
2. 实测：`apps/mobile-app` 只有 `package-lock.json`，且 `node_modules/.pnpm` **不存在** → 事实上的安装方式就是 npm。改用 pnpm 需要删锁重装，等于让 B 组的 Expo 工程从零再验一遍。
3. 实测：RuoYi-Vue3 同时存在 `package-lock.json` **和** `pnpm-lock.yaml` → 它已经被混用过；再加入 pnpm 会变成三份锁文件共存，谁也不知道哪份有效。
4. 技术风险：Metro 默认不跟随 symlink，pnpm 的默认 node_modules 结构需要 `node-linker=hoisted` 才能跑 Expo。段 0 是地基任务，不承担这个额外变量。

**为什么不是 pnpm（即使它更快）**：pnpm 的收益是磁盘与安装速度，代价是 Expo/Metro 的兼容配置 + 一份需要重新验证的锁文件。段 0 的验收锚点 `0-A7` 要求「`tsc --noEmit`、`eslint` 零错误」，换包管理器会让「零错误」到底是不是因为换了安装方式而成立变得不可判定。**收益小于风险，不换。**

---

#### A 组任务（A1~A3）

> 组内可连续推进，不必逐个设闸门（AGENTS §4 第 14 条）。但 A3 依赖 A1 的目录、A2 依赖 A3 的端口与库名，所以**顺序固定 A1 → A2 → A3**（A2 的脚本要在 A3 之后才能探到 `/api/v1/pub/ping`，脚本本身可以先写）。

#### 任务 A1：仓库骨架与分支约定

**做什么**

1. 建立本段需要的目录：`services/faze-api/`、`database/`、`deploy/`、`docs/acceptance/seg0/`、`pc/`。
2. 修根级配置：`.gitignore` 补 Java/Maven 产物；`.editorconfig` 补 Java 的 4 空格缩进。
3. 补 `README.md` 的「目录职责」一节（**README 已存在**，是修正与追加，不是新建）。
4. 定分支：**见本节 (5)——在 `UI设计` 上施工，不新建 `feat/seg0`，`main` 段 0 期间不动。**

**怎么做**

**(1) 目录**

```text
services/faze-api/          A3 填内容；A1 只建目录与 .gitkeep
database/                   建库脚本（database/00-create-database.sql）+ 种子（database/seed/）
deploy/                     A2 填内容（start-services.ps1、.env.example、README.md、verify-all.ps1）
docs/acceptance/seg0/       本段验收证据（截图、命令输出、日志片段）
pc/                         只放 README.md（裁决 2）
```

- **不建** `tools/`：README L60 已预留，但段 0 没有工具要放，空目录 git 也不跟踪。等有第一个脚本再建。
- **不建** `services/faze-api/src/main/java/com/faze/modules/`：段 0 没有任何业务模块；`modules/*/README.md` 的断言（后端规范 §2.6）挂账到段 1（见第三节）。
- **不动** `apps/mobile-app/`：README L24 说它会「在工作流第一个任务被整体删除重建」，但那属于 **B1 Expo 工程与路由树**，不是 A1。A1 碰它会和 B 组打架。

**(2) `.gitignore` 补什么（逐条给依据）**

实测：`git check-ignore -v` 显示 `deploy/.env` 已被 L22 的 `.env` 规则忽略、`deploy/.env.example` 已被 L24 的 `!.env.example` 救回、`apps/mobile-app/logs/a.log` 已被 L27 的 `logs/` 忽略——**这三条不用改**。但 `services/faze-api/target/x.jar` **没有**被任何规则命中（命令无输出）→ 必须补：

```gitignore
# Java / Maven 构建产物
target/
*.class
```

- 不加 `*.jar` 全局忽略：Flyway 不需要 jar，但将来若有 `libs/*.jar` 会被误伤；`target/` 已覆盖 Maven 的产物。
- 不加 `!**/maven-wrapper.jar`：**本段不引入 Maven Wrapper**（理由见 A3）。
- 不加 `.mvn/`：同上。

**(3) `.editorconfig` 现有够不够**

现有：`[*] indent_size=2`、`[*.md] trim_trailing_whitespace=false`、`[*.json] indent_size=2`。对 TS/JSON/MD 够用，**对 Java 不够**（Java 惯例 4 空格，2 空格会与 IDE 默认打架）。补三段：

```ini
[*.java]
indent_size = 4

[*.{xml,sql}]
indent_size = 2

[*.{yml,yaml}]
indent_size = 2
```

**(4) `README.md`：要补，但不是新建**

现状（实测）：README 已有「目录」一节（L48~L63），且已经把 `services/`、`database/`、`deploy/`、`tools/` 写进去了；L43 已写「包管理器 npm」；L56 已写「`deploy/` 放 `start-services.ps1`（按顺序起 MySQL / Redis，不用 docker）」。

所以 A1 的动作是**两处修正 + 一节追加**：

| 位置 | 现在写的 | 要改成 | 为什么 |
|---|---|---|---|
| L55 | `database/ 建库脚本 + Flyway 迁移` | `database/ 建库脚本 + 种子数据（Flyway 迁移在 services/faze-api/src/main/resources/db/migration/）` | Flyway 的 `locations` 是 classpath，迁移必须在 jar 内；放仓库根级 `database/` 需要额外配 `filesystem:` 位置，且打不进 jar |
| L60 | `tools/ 取证、字体检查等工具脚本` | 保留，但补一句「段 0 暂不创建」 | 避免有人建空目录 |
| 新增一节「目录职责」 | 无 | 每目录一句话：谁能改、产物是什么、不进仓库的是什么 | 现在 README 只有目录树，没有「谁负责」，A/B/C/D 四组会互相踩 |

> ⚠️ 本文件属于仓库基线文档，改它要走 `docs/BASELINE.md` §4（指纹 + CHANGELOG）。**A1 只提交改动，不自行升基线版本**，升版由主理人在收口五件套里统一做。

**(5) 分支：第 0 段仍在 `UI设计` 施工（主理人裁决，覆盖原方案）**

> **本节以本裁决为准，原稿的 `feat/seg0` 方案不采用。** 否决理由三条：
> 1. **实测 `docs` 分支本身就含 `apps/`、`design/`、`scripts/`**（`git ls-tree -d docs` 输出这四个目录），它从来不是「纯文档镜像」。原方案的前提——「代码进 `UI设计` 会让 `docs` 也含代码」——与仓库现状不符：`docs` 早就含代码了。
> 2. 现状流程已跑通 6 个基线版本（v1.12→v1.18 全部在 `UI设计` 上迭代后同步 `docs`），换分支模型是**为不存在的一致性问题付迁移成本**。
> 3. 原方案第一步要 `git push origin main`——为段 0 引入一次远端历史操作风险，收益为零。
>
> **裁决**：开工第 0 步先切 `ui-reference`（只读归档，见 §零之三 1.7），然后在 **`UI设计`** 上施工；每段收口时把 `UI设计` 同步到 `docs`，`git diff --quiet refs/heads/UI设计 refs/heads/docs` 必须为空（它本来就在 `deploy/verify-all.ps1` 第 6 步里）。**`main` 在段 0 期间不动。**

**以下是原稿的分支分析，保留备查，`feat/seg0` 不采用。**

**(5') 原方案：第 0 段在 `feat/seg0` 施工**

实测事实：

| 事实 | 值 |
|---|---|
| `main` 的提交数 | **1** |
| `UI设计` 的提交数 | **96** |
| `git merge-base --is-ancestor main UI设计` | **true（main 是 UI设计 的祖先）** |
| `git diff --stat main UI设计` | 162 文件、+70469 / -265 |
| `git diff docs UI设计` | **空**（总纲 §7.4 的「两分支一致」当前成立） |
| `main` 缺的文件 | `.gitattributes`（`UI设计`/`docs` 有） |

机械可执行的步骤：

```powershell
# 1) 先把 main 对齐到现状（可 fast-forward，零冲突，已实测是祖先关系）
git switch main
git merge --ff-only UI设计
git push origin main

# 2) 从 main 切段分支施工
git switch -c feat/seg0

# 3) 段 0 完工合回（保留合并点，便于整段回滚）
git switch main
git merge --no-ff feat/seg0
```

分支定位（写进 `docs/standards/目录与命名规范.md` 或 README 的协作小节）：

| 分支 | 定位 | 允许放什么 | 禁止放什么 |
|---|---|---|---|
| `main` | **唯一可发布分支**，真相源 | 全部代码 + 文档 + 设计 | 未通过验收的半成品 |
| `docs` | 文档镜像 | `docs/`、`design/` | 任何代码（`services/`、`deploy/`、`apps/` 的改动） |
| `UI设计` | 设计镜像，与 `docs` **内容必须一致** | 同 `docs` | 同上 |
| `feat/seg0` | 第 0 段施工分支 | 本段全部产物 | 段 1 的东西 |

**为什么不在 `UI设计` 上施工**（虽然当前 HEAD 就在那儿）：代码一旦进 `UI设计`，按总纲 §7.4「`UI设计` 与 `docs` 必须一致」，每写一行后端代码都要往 `docs` 同步一次，而 `docs` 的定位是文档镜像——两个约束互相打架。先让 `main` 追上（一次 fast-forward，零成本），再在 `main` 的分支上施工，两条约束就都保住了。

**产出（文件路径级清单）**

```text
apps/                          （不动）
services/faze-api/.gitkeep     A1 建占位，A3 换成真实内容
database/.gitkeep              A1 建占位
database/seed/.gitkeep         A1 建占位（种子数据段 0 只建一个空目录声明位置）
deploy/.gitkeep                A1 建占位，A2 填内容
docs/acceptance/seg0/README.md 写清「本目录放什么证据、命名规则」
pc/README.md                   RuoYi 获取方式 / 启动命令 / 路径约定 / patch 纪律
.gitignore                     追加 target/、*.class
.editorconfig                  追加 [*.java] / [*.{xml,sql}] / [*.{yml,yaml}]
README.md                      L55 修正、L60 补注、新增「目录职责」一节
```

**算做完（二值可判定）**

全部为「是/否」：

1. `git ls-tree -r --name-only HEAD | grep -E '^(services|database|deploy|pc|docs/acceptance)/'` 五个前缀全部有命中（不是空目录）。
2. `git check-ignore -v services/faze-api/target/faze-api.jar` 有输出（证明 `target/` 生效）。
3. `git check-ignore -v deploy/.env.example` **有**输出且命中 `!` 例外规则（证明示例文件能进仓库）。
4. `git diff docs UI设计 --quiet` 退出码 0（两分支仍一致，A1 没有破坏 §7.4）。
5. `prettier --check .editorconfig` 不适用——改用人工核对：`.editorconfig` 里有 `[*.java]` 且 `indent_size = 4`。
6. `git diff --quiet refs/heads/UI设计 refs/heads/docs` 退出码 0（两分支仍一致，收口时同步）。

**卡住怎么办（按顺序）**

1. `git merge --ff-only` 失败（说明 main 有独立提交、不是祖先）→ 停下来，用 `git log --graph --oneline main UI设计 | head -30` 看清分叉点，报主理人决定合还是重建 main；**不要 `--force` 推 main**。
2. 推 `main` 被拒（远端有别人的提交）→ 先 `git fetch --all` 再判定，仍冲突则挂起 A1 的「分支」部分，先做目录与配置（它们与本条无关）。
3. `docs` 与 `UI设计` 的 diff 突然非空（有人在别处改了）→ 记 `docs/acceptance/seg0/` 并在遗留挂账里写「哪一侧落后、差哪些文件」，不要自己 merge（文档一致性是主理人的活）。
4. README 的改动与别人正在改的 README 冲突 → README 只改上表三处，冲突时以「保留对方改动 + 追加本文件的三处」为原则手工合。

---

#### 任务 A2：本地服务脚本

**做什么**

写 `deploy/start-services.ps1`（行为规格见下）、`deploy/.env.example`、`deploy/README.md`，并用它跑通「MySQL80 停 → 拉起 → 打印连通状态」与「`-Stop` 只停 Redis」。

**怎么做**

**(0) 先记住三个实测约束，它们决定脚本长什么样**

| 约束 | 实测值 | 对脚本的影响 |
|---|---|---|
| 当前 shell 是否管理员 | **False** | `Start-Service` / `Stop-Service` 对 Windows 服务**需要管理员**。非管理员且服务已停时脚本**不能自己提权**（会弹 UAC，子进程环境与工作目录都变，行为不可预测）→ 打印明确指引并退出码 3 |
| `MySQL80` 服务状态 | **Running / Automatic** | 已运行时**不要重启**（重启会断掉其他项目正在用的连接），打印「已运行，跳过」 |
| MySQL 客户端 | `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` 与 `mysqladmin.exe` 均存在 | 可以用客户端做「认证级」探测，而不只是端口探测 |

**(1) 参数表**

| 参数 | 类型 | 默认 | 行为 | 退出码 |
|---|---|---|---|---|
| （无） | — | — | 启动 MySQL80 + Redis，打印三项连通状态 | 0 全通 |
| `-Status` | switch | off | **只打印**，不启停任何服务 | 0（状态不通也返回 0：它不是门禁，门禁是 `verify-all.ps1`） |
| `-Stop` | switch | off | **只停 Redis**；MySQL80 保持 Running | 0 已停 / 3 非管理员 / 5 未安装 |
| `-SkipRedis` | switch | off | 跳过 Redis 的启动与探测，输出 `WARN`：「0-A6 未满足」 | 0（但警告必打） |
| `-EnvFile` | string | `deploy/.env` | 指定 env 文件；相对路径**按脚本所在目录解析**，不按当前工作目录 | 2 文件不存在 |

参数优先级：`-Stop` > `-Status` > 默认启动；`-SkipRedis` 对 `-Stop` 无效（Redis 没装就没得停，直接返回 5）。

**为什么 `-Stop` 不停 MySQL80**：它是 `Automatic` 启动类型的共享服务，本机可能还有别的项目（RuoYi 的 `faze_admin` 也在同一实例）在用；把它停掉属于对系统状态的破坏性操作，且 0-A6 只要求「`-Stop` 能停 Redis」。

**(2) 退出码表**（唯一判据，便于脚本化）

| 码 | 含义 |
|---|---|
| 0 | 目标状态已达成 |
| 2 | `deploy/.env` 不存在（提示从 `.env.example` 复制） |
| 3 | 需要启动/停止服务但当前不是管理员 |
| 4 | `MySQL80` 服务不存在 |
| 5 | Memurai 未安装（且未加 `-SkipRedis`） |
| 6 | 端口在听，但客户端认证失败（凭据错） |
| 7 | 端口被非目标进程占用（例如 6379 被别的程序占着） |
| 9 | **库名守卫失败**：`FAZE_DB_URL` 里的库名不是 `faze` |

`9` 号是**主动加的安全阀**，对应后端规范里「E2E 库名守卫」那条 100% 落地的规则：段 0 的库名只允许 `faze`，填成 `faze_admin`（RuoYi 库）或任何生产库名一律拒绝启动。

**(3) 行为顺序（编号即执行顺序）**

1. 读 `-EnvFile`；不存在 → 打印「请执行 `Copy-Item deploy\.env.example deploy\.env` 后填 `FAZE_DB_PASSWORD`」→ 退出 2。
2. **库名守卫**：用正则从 `FAZE_DB_URL` 提库名（`jdbc:mysql://host:port/<db>`），不等于 `faze` → 打印「检测到库名 `<x>`，段 0 只允许 `faze`」→ 退出 9。
3. `-Status`：跳到第 6 步。
4. `-Stop`：`Stop-Service Memurai` → 等 6379 释放（≤ 20s）→ 打印「Redis 已停；MySQL80 保持 Running（Automatic，停止会影响同实例的其他项目）」→ 退出 0。
5. 启动阶段：
   - MySQL：`Get-Service MySQL80` → 不存在退出 4；`Running` → 打印「已运行（Automatic），跳过启动」；`Stopped` → 非管理员退出 3，否则 `Start-Service MySQL80` 并轮询 3306（间隔 1s，超时 60s，超时退出 7）。
   - Redis：`Get-Service Memurai` → 不存在时，`-SkipRedis` 则打 `WARN` 继续，否则退出 5；`Running` 跳过；`Stopped` → 非管理员退出 3，否则 `Start-Service Memurai` 并轮询 6379（超时 30s）。
6. **连通状态打印**（三行，每行「项 / 判据 / 结果」）：
   - MySQL：先 TCP 3306，再用 `.env` 里的账号跑 `mysqladmin --connect-timeout=3 -h <host> -P <port> -u <user> -p<pass> ping`。端口不通 → `FAIL`；端口通但认证失败 → `FAIL（凭据错）` 并退出 6。
   - Redis：TCP 6379 + `memurai-cli -h <host> -p <port> ping` 必须等于 `PONG`。只通端口不算通。
   - `faze-api`：`Invoke-WebRequest http://127.0.0.1:<FAZE_API_PORT>/api/v1/pub/ping`。**失败不影响退出码**，只打印「服务端未启动（A3 交付后可用）」——A2 先于 A3 完成时这一行必然是红的，这是预期，不是失败。
7. 汇总：三行表格 + 「0-A6 判据：MySQL ✓ / Redis ✓ / Flyway（需 A3 后验）」。

**(4) `deploy/.env.example` 骨架（含「填错了会写脏哪个库」）**

按后端规范 §12.3.1 的四条要求（默认拒绝、写清后果、本地与测试隔离、`.env` 进 gitignore），但**库名与端口以《数据表规范》§6.1 为准**：`faze` / `3306`。范本里的 `faze_dev` / `3307` 是 jxyd200 的口径，与法泽的库名裁决冲突，**不照抄**。

```bash
# ── 数据库 ─────────────────────────────────────────────────────
# ⚠️ 填错库名的后果（三种，逐条写清）：
#   - 填成 faze_admin：Flyway 会把 app_user / law_item 建进 RuoYi 的库，
#     与 sys_user / sys_menu 等框架表混居；之后任何一次 flyway clean
#     都会连 RuoYi 的表一起清掉，后台直接登不进去。
#   - 填成生产库：本地的 V1、V2 迁移会写进真实业务库，等于线上被灌测试数据。
#   - 填成 faze_test：段 0 的库名守卫会拒绝启动（只允许 faze）。
FAZE_DB_URL=jdbc:mysql://127.0.0.1:3306/faze?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=UTC
FAZE_DB_USER=faze_app
# ⚠️ 必填，留空直接启动失败（不提供默认值）。不要填 root：
#    应用会用 root 的 CREATE/DROP/GRANT 权限跑迁移，一次写错 SQL 能清空整个实例。
FAZE_DB_PASSWORD=

# ── Redis ──────────────────────────────────────────────────────
# ⚠️ 填错库号的后果：/0 是默认库，本机其他项目也常用；
#    段 1 起“清缓存”会误删别人的 key。固定用独立的 /14。
FAZE_REDIS_URL=redis://127.0.0.1:6379/14

# ── 服务端口 ───────────────────────────────────────────────────
# ⚠️ 填成 8080 的后果：与 RuoYi 前端 vite proxy 的目标（localhost:8080）撞车，
#    PC 后台登录会被转发到 faze-api，返回结构与 RuoYi 的 AjaxResult 不兼容。
FAZE_API_PORT=8081

# ── 第三方（留空即走 mock，不花钱、不调用真实服务）─────────────────
FAZE_DOUBAO_API_KEY=
FAZE_DOUBAO_BASE_URL=
```

四条硬性要求（写成 `deploy/README.md` 的检查项）：
1. **默认拒绝**：`FAZE_DB_PASSWORD` 为空 → `faze-api` 启动失败（不设默认值）。
2. **写清后果**，不只写「这里填数据库地址」——每条 `⚠️` 都要能回答「填错了会写脏哪个库/哪个端口」。
3. **本地与测试隔离**：本地 `faze`；测试库 `faze_test`（同一实例，由 `verify-all.ps1` 用独立 env 指定，不在本文件出现）。
4. `.env` 已被 `.gitignore` 的 `.env` 规则覆盖（实测），`.env.example` 被 `!.env.example` 救回（实测）→ 两者都不会混。

**(5) `deploy/README.md` 的目录**

```text
1. 前置条件（逐项带实测版本，缺一项就写清会卡在哪）
   - Java 17.0.17（JAVA_HOME=E:\jdk17）、Maven 3.9.16、Node v24.16.0 / npm 11.13.0
   - MySQL 8.0.44（Windows 服务 MySQL80，Automatic）
   - Memurai（Redis 兼容；未装时用 -SkipRedis 并接受 0-A6 不完整）
   - 不需要：docker、yarn、Android SDK
2. 第一次使用（4 步）
   - 复制 .env.example → .env，填 FAZE_DB_PASSWORD
   - 用管理员 PowerShell 执行 database/00-create-database.sql（建库 + 建 faze_app）
   - 管理员 PowerShell 跑 .\start-services.ps1
   - 核对输出的三项状态
3. 参数说明（表：参数 / 作用 / 退出码）
4. 退出码表（与本文 (2) 节一致）
5. 故障排查（按症状索引）
   - “无法启动服务” → 不是管理员（退出码 3）
   - “Access denied” → FAZE_DB_PASSWORD 或 faze_app 未授权（退出码 6）
   - “6379 被占用” → 别的 Redis 实例在跑（退出码 7）
   - “Memurai 未安装” → 退出码 5，或 -SkipRedis
   - “Flyway 报 Unsupported Database: MySQL” → 缺 flyway-mysql 依赖（见 A3）
6. 与 0-A6 的对应（怎么取证：命令 + 输出存 docs/acceptance/seg0/）
7. 明确不做（不建库、不跑迁移、不启 faze-api、不启 RuoYi）
```

**产出**

```text
deploy/start-services.ps1      脚本（行为规格如上）
deploy/.env.example            env 模板（含「填错写脏哪个库」）
deploy/README.md               上列 7 节
deploy/verify-all.ps1          门禁脚本（步骤定义见第三节）；A2 只建骨架 + preflight 段，
                               前端/后端门禁两步随 A3 完成补齐
docs/acceptance/seg0/          本任务的命令输出证据
```

**算做完（二值可判定）**

1. 管理员 PowerShell 下：`Get-Service MySQL80 | Stop-Service` → `.\deploy\start-services.ps1` → 退出码 **0**，且输出里三行状态齐全（MySQL / Redis / faze-api）。
2. `.\deploy\start-services.ps1 -Status` → 退出码 0，且**未**改变任何服务状态（前后 `Get-Service MySQL80,Memurai` 的 Status 一致）。
3. `.\deploy\start-services.ps1 -Stop` → 退出码 0，`Get-Service Memurai` 变 Stopped，`Get-Service MySQL80` **仍是 Running**。
4. 非管理员 shell 下、且 MySQL80 已停：`.\deploy\start-services.ps1` → 退出码 **3**，输出含「请用管理员 PowerShell 运行」。
5. 把 `.env` 的 `FAZE_DB_URL` 改成 `.../faze_admin` → 退出码 **9**（库名守卫），且**没有**任何服务被启动。
6. `git check-ignore -v deploy/.env` 有输出、`deploy/.env.example` 命中 `!` 例外。
7. 以上 6 条的命令与输出全部存进 `docs/acceptance/seg0/`。

**卡住怎么办（按顺序）**

1. 没有管理员权限（当前会话就是非管理员，实测 `IsInRole(Administrator)=False`）→ 让主理人开一个管理员 PowerShell 跑；**不要**给 `MySQL80` 服务的 SDDL 加当前用户启动权限（改系统安全配置，不可逆，且影响同机其他使用者）。
2. `Start-Service MySQL80` 报「服务无法启动」→ 先看事件查看器「Windows 日志 → 系统」里 MySQL 的报错，再手工 `"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe" --defaults-file="C:\ProgramData\MySQL\MySQL Server 8.0\my.ini" --console` 看前台输出；这条路径只用于诊断，不进脚本。
3. Memurai 装不上（许可/来源问题）→ 用 `-SkipRedis` 让脚本先能跑通 MySQL 部分，Redis 按裁决 1 挂账；**不要**临时改脚本去连一个不存在的 6379。
4. `memurai-cli ping` 通但服务名不叫 `Memurai`（安装时改了服务名）→ 在 `.env` 加 `FAZE_REDIS_SERVICE=<真实服务名>`，脚本读它；**不要**在脚本里硬编码猜。
5. 脚本行为与预期不符但没有报错 → 加 `-Verbose`（脚本用 `Write-Verbose` 打每一步的服务状态），并把 `$ErrorActionPreference='Stop'` 保持在脚本顶部（否则 `Start-Service` 失败会被吞掉，变成「假成功」——这正是 §12.4.1 要防的那类假绿）。

---

#### 任务 A3：服务端骨架 + Flyway V1

**做什么**

建 `services/faze-api` Maven 工程，实现段 0 的最小件（`R`、全局异常、JWT 过滤器、审计四要素、三个探针），打通 Flyway 通道并落 `V1__baseline.sql`。

**怎么做**

**(1) Maven 结构**

```text
services/faze-api/
├─ pom.xml
├─ src/main/java/com/faze/
│  ├─ FazeApiApplication.java
│  ├─ common/        R.java / ErrorCode.java / GlobalExceptionHandler.java / TraceIdFilter.java
│  │                 audit/AuditContext.java（段 0 只进 MDC，不写库）
│  ├─ config/        SecurityConfig / MybatisPlusConfig / JacksonConfig / OpenApiConfig
│  ├─ security/      JwtAuthenticationFilter / JwtTokenProvider / RestAuthenticationEntryPoint
│  ├─ modules/       (段 0 不建；`modules/*/README` 挂账到段 1)
│  └─ infra/         ai/DoubaoClient（探针用，key 为空时走 mock）/ law/LawSearchService
├─ src/main/resources/
│  ├─ application.yml / application-dev.yml / application-prod.yml
│  ├─ db/migration/V1__baseline.sql、V2__init_law_items.sql
│  ├─ mapper/LawItemMapper.xml
│  └─ logback-spring.xml（pattern 含 %X{traceId}）
└─ src/test/java/com/faze/   （段 0 只放 1 个：TraceId + 401 的 MockMvc 冒烟）
```

- **不加 Maven Wrapper**（`mvnw`/`mvnw.cmd`/`.mvn/`）：本机已有 Maven 3.9.16（`E:\google\apache-maven-3.9.16-bin\...\mvn.cmd`），wrapper 会引入一份要联网下载的 `maven-wrapper.jar` 与其分发源，收益为负。Maven 版本写进 `deploy/README.md` 与 `services/faze-api/README.md`。
- **不加根级 `pom.xml`**：单模块，`services/faze-api/pom.xml` 就是工程根，不做聚合。

**(2) `pom.xml` 关键依赖与版本（附本地 `.m2` 实测）**

| 依赖 | 版本 | 本地 `.m2` | 说明 |
|---|---|---|---|
| `spring-boot-starter-parent` | **4.0.10** | ✅ 有（4.0.5 / 4.0.10 / 4.1.0） | 取 4.0.x 最新缓存版本；与 RuoYi 发行版的 4.0.3 **同一大版本**；Java 17 编译目标由 parent 管 |
| `spring-boot-starter-web` / `-validation` / `-security` / `-aop` / `-test` | parent 管 | ✅ 有 | 版本一律不写，交给 parent |
| `mybatis-plus-spring-boot4-starter` **+ `mybatis-plus-jsqlparser`** | **3.5.18.1**（两个都要，版本一致） | ❌ 需联网（首次） | **必须用 `-spring-boot4-` 这个 artifact**。实测：`-spring-boot3-starter:3.5.12` 在 SB4 下**静默失效**——它 `@AutoConfigureAfter` 的 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` 已被 SB4 移到 `org.springframework.boot.jdbc.autoconfigure`，旧类不存在 → 排序丢失 → MP 自动配置整体跳过 → 只报「Mapper 注入失败」。**不报缺类，最难查**。SB4 starter 从 MP **3.5.13** 起提供。**分页插件 `PaginationInnerInterceptor` 已拆到 `mybatis-plus-jsqlparser`，不加会编译失败**（2026-11-01 实测） |
| `mysql-connector-j` | **8.0.33** | ✅ 有（另 9.2.0 / 9.7.0） | 与 MySQL 8.0.44 服务端同代；不用 9.x（驱动 9 对 8.0 服务端虽兼容，但本项目未验证，段 0 不引入变量） |
| `flyway-core` + **`flyway-mysql`** | 由 parent 决定（**Spring Boot 4.0 管的 Flyway 版本未实测**；SB 3.5 管的是 11.x） | ❌ **本地无缓存，必须联网** | Flyway 10+ 把数据库支持拆成独立模块；**只加 `flyway-core` 会报 `Unsupported Database: MySQL`** |
| `jjwt-api` / `-impl` / `-jackson` | **0.12.3** | ✅ 有 | 三件套都要；只加 `jjwt-api` 能编译，但运行时找不到实现类（`NoClassDefFoundError: io/jsonwebtoken/impl/...`） |
| `lombok` | **1.18.38** | ✅ 有 | `provided` scope |
| `spring-boot-starter-data-redis` | **不加** | — | 段 0 服务端不使用 Redis；加了就是「配了没人读的死开关」（后端规范 §11.4）。Redis 连通性由 A2 的 `memurai-cli ping` 证明，服务端接入留到段 1 的第一个使用点 |
| `checkstyle` / `archunit` / `jacoco` | **不加** | — | 见第三节 CI 门禁，全部挂账到段 1 |

网络实测：`repo.maven.apache.org:443` 可达（True），`registry.npmjs.org:443` 可达（True）。所以「Flyway 首次要联网下载」是可行的，但**首次 `mvn verify` 必然比后续慢**，且离线（`mvn -o`）会失败——写进 A3 的「卡住怎么办」。

**(3) 包结构**

严格照 AGENTS §3.2 的五段式：`common/` `config/` `security/` `modules/` `infra/`。段 0 只填 `common/config/security/infra`，`modules/` 留空且**不建子目录**（段 0 没有业务域，建了就是空壳）。

**(4) 本段必须实现的最小件（6 件，多一件都不做）**

| # | 件 | 落点 | 段 0 的边界（写清「做到哪算够」） |
|---|---|---|---|
| 1 | **`R<T>` 统一响应** | `common/R.java` | 字段 `code/msg/data/traceId`，`traceId` 取 `MDC.get("traceId")`；形状照《接口规范》§5.1 **原文**，不自己发明。`data=null` 时字段保留（不省略） |
| 2 | **全局异常处理** | `common/GlobalExceptionHandler.java` | 覆盖《后端规范》§5.1 的 13 类分支；对外只出 `ErrorCode` 的稳定文案；**任何分支不得把 `e.getMessage()`、SQL、堆栈返回客户端**；对内 `log.error("...", e)` 打全栈。HTTP 语义：业务失败仍 200（code 为 6 位业务码）、未认证 401、无权限 403（《错误码规范》§2.2） |
| 3 | **traceId 链路** | `common/TraceIdFilter.java` | 读请求头 `X-Request-Id`（无则生成 UUIDv4）→ 写 MDC → 响应头回 `X-Trace-Id`；`logback-spring.xml` 的 pattern 含 `%X{traceId}`；`R.traceId` 与响应头**同值**（《接口规范》§5.1 三条约束之一） |
| 4 | **JWT 过滤器** | `security/` | 解析 `Authorization: Bearer <token>`；失败**不抛异常**，清空上下文后放行，由 `RestAuthenticationEntryPoint` 统一出 HTTP 401 + `{code:402002}`（过期，可刷新）或 `{code:402003}`（无效，不刷新）。两者必须区分——《错误码规范》§4.3 第 3 条写明：不区分会导致客户端无限刷新循环 |
| 5 | **审计四要素** | `common/audit/AuditContext.java` | **段 0 不写库**：`operation_log` 表按《数据表规范》附录 A 是**段 1 建**。段 0 只把四要素（`operator_id`+`operator_type` / `created_at`(UTC) / `ip`(取 `X-Forwarded-For` 第一段) / `module`+`action`+`target_type`+`target_id`+`detail`）放进 ThreadLocal 与 MDC，供日志打出。**不建 `operation_log` 表**——建了就是越界到段 1 |
| 6 | **三个探针** | `infra/` + 一个 `ProbeController` | `GET /api/v1/pub/ping`、`POST /api/v1/pub/probe/ai`（SSE）、`GET /api/v1/pub/probe/law?kw=`；路径与返回形状照《接口规范》§7 的段 0 表；**三个都加 `@Profile("!prod")`**，生产 profile 下返回 404（理由：《接口规范》§3.1 裁决 1——`probe/ai` 会真实消耗豆包配额且无鉴权） |

> 三个探针归 `pub` 前缀不是随便放的：《接口规范》§3.1 的四类前缀只有 `pub`/`app`/`admin`/`internal`，原来的 `/api/v1/ping`（裸路径）与 `/api/v1/ops/probe/*`（`ops` 不在四类里）都是错的，已裁决改为 `pub` 且仅非生产注册。

**(5) Flyway 通道**

配置键（`application.yml`）：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false      # 库必须由 00-create-database.sql 先建好
    validate-on-migrate: true
    clean-disabled: true            # 本地也不许 clean；要 clean 用单独的 reset-db.ps1
```

- **库名 `faze`，与 RuoYi 的 `faze_admin` 分库**（《数据表规范》§6.1）。分库的理由不是洁癖：Flyway 会对 `faze` 做版本表管理与（被禁止的）clean，与 RuoYi 的 `sys_*` 表同居会让一次 clean 连框架表一起清掉。
- 迁移文件位置：`services/faze-api/src/main/resources/db/migration/`（classpath）。**不放仓库根级 `database/`**——Flyway 的 `locations` 默认是 classpath，放根级要额外配 `filesystem:` 位置，且打包后不在 jar 里，换个目录就找不到。
- 版本号：段 0 占 `V1`~`V99`（《数据表规范》§8.1）。

**(6) 建库 SQL（`database/00-create-database.sql`，不进 Flyway）**

Flyway 连库时**库必须已存在**，所以建库是 Flyway 之前的一次性动作，用 root 手工执行一次，输出存证。

```sql
-- 段 0 唯一允许建的库：faze（RuoYi 的 faze_admin 不在此脚本内）
CREATE DATABASE IF NOT EXISTS `faze`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- 应用账号：只授权 faze.*，不用 root 跑应用（《数据表规范》§6.1）
CREATE USER IF NOT EXISTS 'faze_app'@'localhost' IDENTIFIED BY '<口令>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER,
      REFERENCES, CREATE VIEW, SHOW VIEW, EXECUTE, TRIGGER
  ON `faze`.* TO 'faze_app'@'localhost';
FLUSH PRIVILEGES;
```

- 口令**不落仓库**：写在 `deploy/.env`（已被 gitignore），SQL 文件里只留 `<口令>` 占位，执行时由人替换或走参数。
- 授权清单的取舍：Flyway 需要 `CREATE/ALTER/DROP/INDEX/REFERENCES`；`TRIGGER`/`EXECUTE` 段 0 用不到但段 1 起会用，先给上；**不授 `GRANT OPTION`、不授全局权限**。
- `operation_log` 的「DB 账号无 UPDATE/DELETE」是**段 1 的表级授权**（《审计与日志规范》），段 0 不做表级授权（表还不存在）。

**(7) `ngram_token_size`：先纠正一个错误前提**

任务里写「`ngram_token_size=2` 的**建库语句与 `my.ini` 改法**」——这两半都站不住：

1. **它不是库/表属性**，是 MySQL 的**只读启动参数**（`performance_schema` 之外的服务器级变量）。`CREATE DATABASE` / `CREATE TABLE` 语句里写 `ngram_token_size` 是语法错误，写不进去。
2. **不需要改 `my.ini`**：MySQL 8.0 的默认值就是 `2`；《数据表规范》§5.3 与《V1 基线表结构》§二 都已裁决「默认即 2，**不改 my.ini**（只读参数，改了要重启服务）」，并记录了实测「双字可命中、单字命中 0 条」。

所以 A3 的正确动作是**校验**，不是修改：

```sql
-- 用 faze_app 连上后执行，期望结果：ngram_token_size = 2
SHOW VARIABLES LIKE 'ngram_token_size';
```

若实测 ≠ 2（说明这个实例被人改过），才走下面的**条件分支**：

```ini
; C:\ProgramData\MySQL\MySQL Server 8.0\my.ini —— 仅当实测≠2 时才加这两行
[mysqld]
ngram_token_size=2
```

改完必须：① `Restart-Service MySQL80`（需管理员，**会断开所有现有连接**）；② **重建全文索引**——token size 变化后旧索引的切词结果失效，必须重建，否则 `MATCH ... AGAINST` 的行为按旧切词走：

```sql
ALTER TABLE law_item DROP INDEX ft_law_content,
  ADD FULLTEXT KEY ft_law_content (content) WITH PARSER ngram;
```

> 实测限制：本机 `mysql -uroot` 返回 **`ERROR 1045 (28000): Access denied for user 'root'@'localhost' (using password: NO)`** → 我没有凭据，**无法实测这个实例的 `ngram_token_size` 到底是不是 2**。这一条必须由拿到 MySQL root 凭据的人补测，见第四节不确定项 #1。

**(8) `V1__baseline.sql` 怎么落**

- **逐字抄**《V1 基线表结构_v1.0》§三（`app_user`，19 字段）与 §四（`law_item`，12 字段）的 DDL，**不自己重写、不自己加字段、不自己调索引**。理由：那份文档是「V1 的唯一依据」，重写一遍就会与它产生第二份真相。
- 文件头按《数据表规范》§7.3 的五段式写全：

```sql
-- ============================================================
-- 迁移：V1__baseline.sql
-- 所属段：第 0 段（可安装底座 + 三项探针）
-- 做什么：建 app_user + law_item 两张表结构，不含数据
-- 依赖：无（首个迁移；库由 database/00-create-database.sql 先建好）
-- 可回滚：本地库可 flyway clean 重建；已进 main 后不可改，只能追加 V1_1__fix_*.sql
-- 编写：<日期>
-- ============================================================
```

- `law_item` 的 `FULLTEXT KEY ... WITH PARSER ngram` 与表级 `COLLATE=utf8mb4_0900_ai_ci` **必须原样保留**——前者没了探针查不到东西，后者没了 ngram 索引会随环境默认排序规则变行为（《数据表规范》§6.2 明写「这是硬要求」）。
- 验收时加两条 `SHOW CREATE TABLE` 断言：`law_item` 含 `WITH PARSER ngram`；两张表的 `COLLATE` 都是 `utf8mb4_0900_ai_ci`。

**(9) `V2__init_law_items.sql`：只放 5 条**

- 依据《数据表规范》§8.4 的裁决：`V2` 只放 **5 条**（够 `probe/law` 命中即可），全量 2000~3000 条走 `database/seed/law_items_full.sql`（**不进 Flyway**，段 3 导入）。
- `effective_date NOT NULL` 是唯一键的组成部分，5 条都必须给生效日期（《V1 基线表结构》§4.1 第 3 点）。
- 写法用 `INSERT ... ON DUPLICATE KEY UPDATE`：§8.3 要求「灌数据的迁移必须可重复执行」，§8.4 表格里写的「可重复执行：否」指的是「Flyway 不会重复执行同一个迁移文件」，不是「SQL 不许可重复」。两者不冲突，按 §8.3 的写法执行——本地 `flyway clean` 后重跑同一条 SQL 不会撞唯一键。
- **5 条条文的具体内容：待确认**（需法务或负责人提供，或从权威文本抄；不许自己编法条原文）。

**产出**

```text
services/faze-api/pom.xml
services/faze-api/README.md                     启动/构建/端口/版本说明
services/faze-api/src/main/java/com/faze/...    上表 6 件的实现
services/faze-api/src/main/resources/application*.yml
services/faze-api/src/main/resources/db/migration/V1__baseline.sql
services/faze-api/src/main/resources/db/migration/V2__init_law_items.sql
services/faze-api/src/main/resources/logback-spring.xml
services/faze-api/src/test/java/com/faze/...    1 个冒烟测试
database/00-create-database.sql
database/seed/                                  段 0 空目录（声明位置）
docs/acceptance/seg0/                           命令输出 + 截图
```

**算做完（二值可判定，逐条可跑）**

> 第 1~11 条是签字门槛；第 12 条依赖第四节的 root 凭据（不确定项 #1），凭据到位前**不计入签字**，只作「已补测 / 未补测」登记。

1. `cd services/faze-api && mvn -B verify` → **退出码 0**（首次需联网下载 Flyway；见「卡住」第 1 条）。
2. `mvn spring-boot:run -Dspring-boot.run.profiles=dev` 启动成功，8081 处于监听。
3. `GET /api/v1/pub/ping` → body 含 `"code":200`、`"traceId"` 非空；响应头 `X-Trace-Id` 与 body 的 `traceId` **同值**。
4. `GET /api/v1/pub/probe/law?kw=合同` → `hits` 数组长度 ≥ 1。
5. `POST /api/v1/pub/probe/ai` → 收到 SSE 首字节；key 为空时返回 mock 流，**证据文件里必须注明「本次为 mock」**。
6. `SHOW TABLES FROM faze` → 恰好 `app_user`、`law_item`、`flyway_schema_history`（2 张业务表 + 1 张 Flyway 元数据表）；业务表数 = 2。
7. `SELECT COUNT(*) FROM law_item` → **5**。
8. `SHOW CREATE TABLE law_item` 含 `WITH PARSER ngram`；`SHOW CREATE DATABASE faze` 含 `utf8mb4_0900_ai_ci`。
9. 以 `--spring.profiles.active=prod` 启动 → 三个探针全部 **404**。
10. 造一个过期 token → HTTP **401** + `code=402002`；造一个签名错的 token → HTTP **401** + `code=402003`（两者必须不同）。
11. 故意抛一个带 SQL 片段的异常 → 响应的 `msg` **不含** SQL/堆栈/原文（人工核对一条响应体 + 一条日志）。
12. `SHOW VARIABLES LIKE 'ngram_token_size'` → `2`（**需 root 或 faze_app 有权限；当前无凭据，见不确定项 #1**）。

**卡住怎么办（按顺序）**

1. **`mvn verify` 下载失败 / 超时** → ① 确认 `repo.maven.apache.org:443` 可达（实测可达）；② 在 `~/.m2/settings.xml` 配阿里云镜像 `https://maven.aliyun.com/repository/public` 重试；③ 仍不行则 `mvn -o` 只验证已缓存的编译（**Flyway 不在缓存，必然失败**，此时把「Flyway 通道」拆成独立验收项挂账，不要伪造通过）。
2. **Flyway 报 `Unsupported Database: MySQL`** → 确认 `pom.xml` 里有 `flyway-mysql`（只加 `flyway-core` 必报这个错）。
3. **Flyway 报 `Unknown database 'faze'`** → 建库 SQL 没执行，回到 `database/00-create-database.sql`，用 root 跑一次（凭据见不确定项 #1）。
4. **Mapper 注入失败（`Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`）** → ① **最常见原因**：pom 里用的是 `mybatis-plus-spring-boot3-starter`。它在 SB4 下**能启动但不工作**（见 §零之四 与 A3 证据）；② 改用 `mybatis-plus-spring-boot4-starter:3.5.18.1`；③ 若用了 SB4 starter 仍失败，跑 `--debug` 看 `MybatisPlusAutoConfiguration` 的 `Did not match` 条件。**不许为此降 Java 版本或退回 Spring Boot 3.5**。
5. **`ngram_token_size` ≠ 2** → 按 (7) 的条件分支改 `my.ini` + 重启 + 重建 `ft_law_content`；**不许**把 `probe/law` 改成 `LIKE` 绕过（那会让探针失去「验证 ngram 索引可用」的意义）。
6. **探针在 dev 下也 404** → 检查 `@Profile("!prod")` 是否被写在类上但该类没被组件扫描到（`FazeApiApplication` 的扫描根必须是 `com.faze`）。
7. **审计四要素被要求「写进 `operation_log`」** → 拒绝并回到本文 A3 第 (4) 节第 5 件：表在段 1 建，段 0 写库就是越界；把需求记进段 1 的开工前置。

---

#### CI 门禁（本段只上什么、什么挂账）

**先说边界**：段 0 的 `0-A7` 只硬要求三项（`tsc --noEmit`、`eslint`、`mvn verify`）。后端规范 §十四 的 14 条自动拦、i18n 的六项检查、checkstyle、gitleaks、JaCoCo 全部**不在段 0 的锚点里**，但它们有各自的落地段，不能「不写就不存在」——所以下表把「上不上、挂到哪」写死。

**脚本归属**：`deploy/verify-all.ps1` 由 **A2** 建（它与 `start-services.ps1` 同属 `deploy/`，共用 `.env` 与退出码约定）。本节只定义它跑什么、失败怎么判；A2 只建骨架与 `service-preflight` 段，前端与后端两步随 A3 完成补齐。

| 门禁项 | 段 0 上不上 | 拦什么 | 实现方式 | 挂账到哪里 |
|---|---|---|---|---|
| **`tsc --noEmit`** | ✅ **上**（`0-A7` 硬要求） | 类型错误、`strict` 被偷偷放宽 | `cd apps/mobile-app && npm run typecheck` | 无 |
| **`eslint`** | ✅ **上**（`0-A7` 硬要求） | `any`/`@ts-ignore`、颜色字面量、`rgba()` | `cd apps/mobile-app && npm run lint`（= `tsc --noEmit && eslint .`） | 无。**但**：`0-A7` 另外要求「全仓直写 `<Text>` 为 0」「`marginLeft/right`、`paddingLeft/right`、`left/right` 被 lint 报错」——实测 `eslint.config.mjs` **没有这两条规则**，只有颜色字面量与 `no-explicit-any`。这两条规则由 **B3（本地化 + RTL 坐标系）** 补，规则落地前 `0-A7` **不能签字**（是硬要求，不挂账） |
| **`mvn verify`** | ✅ **上**（`0-A7` 硬要求） | 编译失败、单测失败、打包失败 | `cd services/faze-api && mvn -B verify` | 无 |
| **service-preflight**（服务前置门） | ✅ **上**（本段自加，依据《后端规范》§12.4.1） | 「服务没起 → 用例全跳过 → 退出码仍是 0」的假绿 | `deploy/verify-all.ps1` **第一步**：探活 MySQL 3306（客户端 ping）/ Redis（`PING`）/ `faze-api /api/v1/pub/ping` / RuoYi `http://127.0.0.1:80`，任一不通 → 直接非 0 退出，**不许 skip** | 有真 CI 后迁成独立 job + `needs: preflight` |
| **库名守卫** | ✅ **上**（本段自加） | 迁移/测试连错库，把开发数据写进 `faze_admin` 或生产库 | `start-services.ps1` 的退出码 9 + `verify-all.ps1` 复检 `FAZE_DB_URL` 的库名必须为 `faze` | 永久（段 1 起测试库用 `faze_test`，守卫白名单扩到两个） |
| **`docs` 与 `UI设计` 内容一致** | ✅ **上**（本段自加，护总纲 §7.4） | 两个文档镜像分支漂移 | `git diff --quiet docs UI设计`，非 0 则失败 | 永久 |
| **gitleaks**（明文密钥） | ❌ **不上** | 密钥进仓库（后端规范 §11.4） | 段 0 用 `git grep -nE '(password|secret|api[_-]?key|token)\s*[:=]\s*["''][^"'']+'` 做粗筛（会有误报，人工看一眼） | **段 1 开工前置**（`docs/analysis/依赖清单.md` 登记 gitleaks 版本与许可） |
| **checkstyle** | ❌ **不上** | 类 ≤500 行 / 方法 ≤50 行、禁 `@author`/`@Date` | — | **段 1 开工前置**（段 0 只有 6 个类，行数不可能超） |
| **ArchUnit 14 条** | ❌ **不上** | 分层依赖、Controller 不许注入 Mapper、VO 不含敏感字段 | — | **段 1**，随第一个业务模块一起上（段 0 没有 `modules/`，规则全是空转） |
| **JaCoCo 类级阈值** | ❌ **不上** | 状态机 / 资金链路覆盖率 | — | **段 1**（段 0 没有状态机与资金类，设了也是 0/0） |
| **`modules/*/README.md` 断言** | ❌ **不上** | 业务域缺少说明文档 | — | **段 1**（段 0 不建 `modules/` 子目录，断言会失败） |
| **i18n 六项检查**（四语言目录同构 / key 集合 / 占位符 / 术语 / 硬编码 / 拼接 key） | ❌ **不上**（只借走第 5 项） | 缺译、占位符错位、拼 key | 段 0 只有「硬编码扫描」由 ESLint 规则承担（见上面 `eslint` 行） | **段 8 收口**；但 i18n 规范 §11.1 写「第 0 段就要建」——冲突，裁决见下 |

**关于 i18n 六项与「第 0 段就要建」的矛盾（必须说清）**：《i18n 规范》§11.1 标题写「CI 检查（第 0 段就要建）」，但段 0 的四语言资源目录要到 B3 才建，六项检查里前四项此时没有输入。裁决：**段 0 只落第 5 项（`no-raw-text`，由 B3 提供 ESLint 规则），其余五项挂账到「段 8 收口」**，并在段 0 收口的遗留清单里写明「i18n 规范 §11.1 的『第 0 段就建』未能兑现，原因是资源目录未建，顺延到段 8」。**不假装建了。**

#### 没有 CI 服务器时，这些门禁靠什么跑

实测：仓库**没有 `.github/`**（`Test-Path` = False），也没有任何 CI 配置。远端有两个 remote：`origin` 与 `gh`（从命名看 `gh` 是 GitHub 镜像，但两个 remote 的具体 URL 我**没有核对**）。

**段 0 的答案：一条 `deploy/verify-all.ps1`，退出码是唯一判据。**

```text
deploy/verify-all.ps1 的执行顺序（任一失败即停，退出码非 0）
1. service-preflight    MySQL(客户端 ping) / Redis(PING) / faze-api(/pub/ping) / RuoYi(:80)
2. 库名守卫             FAZE_DB_URL 的库名 == faze
3. 前端门禁             cd apps/mobile-app && npm ci --no-audit --no-fund && npm run lint
4. 后端门禁             cd services/faze-api && mvn -B verify
5. 密钥粗筛             git grep 关键词（命中即失败，人工确认白名单）
6. 文档镜像一致         git diff --quiet docs UI设计
7. 汇总打印 + 写证据     docs/acceptance/seg0/verify-all-<yyyyMMdd-HHmm>.txt
```

三条硬规则：

1. **不许 skip**：任何一步「因为前置没起而跳过」都必须打印 `SKIP` 并让**总退出码非 0**（§12.4.1 的原话是「禁止用『跳过』代替『失败』」）。
2. **证据落盘**：每次运行的完整输出写进 `docs/acceptance/seg0/`，收口五件套的第 1 件（验收证据）直接引它。
3. **脚本是 CI 的等价物，不是替代品**：脚本里的每一步都要能被一个 job 原样搬走（不依赖交互、不依赖本机路径写死）。

**什么时候必须迁到真 CI（硬期限）**

满足任一条即必须迁：

1. **段 1 开工前**（硬期限）。理由：段 1 是账务内核，改一行扣费代码就可能错账，靠人记得跑本地脚本是不可接受的。
2. 出现第 2 个开发者（本地脚本只能证明「我这台机器过了」）。
3. 第一次需要「每次 push 都验证」时（现在只有一个人、一个分支，本地跑够用）。

迁法：`verify-all.ps1` 的 7 步原样搬成 GitHub Actions 的 job，`service-preflight` 独立成 job 并用 `needs:` 串起来；`verify-all.ps1` **保留**，作为「本地等价物」——两边行为不一致时以 CI 为准，并把差异写进 CHANGELOG。

---

### 第 2 组　B 移动端地基

> **组内推进**：B1 → B2 → B3 → B4 严格按序。它们互相引用（B2 用 B1 的 `@/` 别名与路由常量，B3 的 `LocalizedText` 是 B4 四态组件的渲染底座），乱序会把返工摊到两倍。
> **组间检查点**：B 组全部收口后才允许开 C 组；D1 允许与 C 组并行（它只依赖 B 组的工程），D2/D3 必须在 D1 之后（都要真机）。

#### 任务 B1：Expo 工程与路由树

**做什么**：
把现有「UI 复刻工程」裁成第 0 段的地基工程，并定死四件不可推翻的事：包管理器口径、目录约定、路由树、根布局职责。**不建 68 页清单、不建 Tab、不建任何业务页。**

**怎么做**：

1. **包管理器：定为 npm，不给第二个选项。**
   - `package.json` 增加 `"packageManager": "npm@11.13.0"` 与 `"engines": { "node": ">=24" }`；本地与 EAS 一律 `npm ci`。
   - 理由：仓库已有 `package-lock.json`，`node_modules` 是按 npm 扁平布局装出来的（实测存在）；EAS 按 lockfile 探测包管理器，`package-lock.json` 存在即走 npm。切 pnpm 要删掉 `package-lock.json` 重生成 `pnpm-lock.yaml`，而 pnpm 默认的符号链接布局需要 `node-linker=hoisted` 才能与 Metro 的解析行为对齐（**判断，非本仓实测**）——段 0 不引入这种不确定性。**不生成 `pnpm-lock.yaml`，两个 lockfile 共存会让本地与云端装出不同的依赖树。**
   - `scripts/README.md` 说「本目录当前为空」，但实际有 5 个 `.py`（含 `fetch-arabic-font.py`）。**按 README 的命名约定**（`<动作>-<对象>.ps1`）新增检查脚本，不改 README 之外的既有脚本。

2. **目录约定校准**（对齐 `AGENTS.md` §3.1）：
   - `shared/api/`、`shared/i18n/`、`features/dev/`、`features/settings/` 四目录新建。
   - 语言切换页归属裁决：放 `features/settings/pages/LanguagePage.tsx`，用 **`profile` namespace**——i18n 规范 §4.3 已把 `profile` 定义为 `features/profile/` + `features/settings/` 两域共用，不新增第 15 个 namespace（新增要改基线文档 §4.3，代价大于收益）。
   - 探针页放 `features/dev/pages/`，**不建 `dev` namespace**：探针页不进 release 包、段 0 后可能整体删除；为此新增 namespace 要同步改 i18n 规范 §4.3 的映射表并做四语言同构，代价不匹配。改为在 `eslint.config.mjs` 里给 `app/dev/**` + `features/dev/**` 加一条 `no-raw-text` 的 off 覆盖，**豁免理由写在规则注释里**（这是全仓唯一的 lint 豁免，且豁免范围与「不进 release」绑定）。

3. **路由树：段 0 只有 7 个路由文件。**
   ```
   app/_layout.tsx      根：字体门控 + ErrorBoundary + 401 handler 注册 + Stack
   app/index.tsx        分流：读 SecureStore(lang, token) → replace('/login') 或 replace(原路由)
   app/login.tsx        C1 登录页（产品页 1）
   app/language.tsx     C2 语言切换页（产品页 2）
   app/dev/probe.tsx    D3 探针（ping / AI 流 / law 检索）
   app/dev/rtl.tsx      B3+D2 探针（RTL 镜像 / 藏文渲染 / 静态 Tab 样本）
   app/+not-found.tsx   兜底
   ```
   - **取消 `(auth)` 路由分组**：段 0 只有 1 个认证页，分组只为一页共享 `Stack.Screen` 配置，是多余抽象；段 1 出现注册/找回密码多页时再建（写进「留给下一段的接口」）。
   - **不建 `(tabs)`**：Tab 依赖 5 个业务页，段 0 一个都没有。
   - **探针页如何「不进 release 包」**：expo-router 会打包 `app/` 下全部路由，靠运行时 `if` 拦不住「进包」。裁决：
     - 段 0 只构建 **preview** 包（见 D1），production 包不在段 0 范围内，故总纲 §5.1「不进 release 包」在段 0 由「不构建 release 包」满足；
     - 同时新增 `scripts/prepare-release.ps1`：production 构建前把 `app/dev/` 与 `features/dev/` 移到 `.release-excluded/`，构建后还原；`check-raw-text.ps1` 顺带断言 production 构建产物里两者不存在；
     - `app/dev/_layout.tsx` 内加兜底守卫：`__DEV__` 为假且 `Constants.expoConfig?.extra?.enableDevRoutes !== true` 时 `return <Redirect href="/login" />`。

4. **typedRoutes：保持开启（`app.json` → `app.config.ts` 的 `experiments.typedRoutes: true`），并且必须重写 `routes.ts`。**
   - 删除 12 个路由壳后，`shared/navigation/routes.ts` 里 `'/home' as Href` 这类断言指向不存在的路由。**极可能直接触发 `TS2352`（Conversion of type … may be a mistake）；即使 `Href` 的定义宽松到不报错，任何 `router.push(routes.home)` 调用点都必然报错。** 这是「删页面」必须连带改 `routes.ts` 的硬理由，不是整洁问题。
   - 重写后只保留 7 条（`/`、`/login`、`/language`、`/dev/probe`、`/dev/rtl` + 2 个参数化路由的生产形态先不写）。**68 页路由常量随段生长，段 0 一条都不预置。**

5. **68 页清单：段 0 不建。** 依据总纲 §4.4 规则 3（空壳页面不提前做）+ §5.1 明确不做业务页面。清单的权威仍是 `docs/design/法泽APP_页面导航树_v1.5.md`（文档层已存在，代码层按段生长）。**段 0 唯一的「清单动作」是把这张表登记进工作流的「留给下一段的接口」，说明段 1 开工时要按它建第一批路由。**

6. **`app.json` → `app.config.ts`**（为了按 profile 注入 `apiBaseUrl` 与 `enableDevRoutes`）：
   - 保留全部现有键（`name/slug/scheme/newArchEnabled/android.package/edgeToEdgeEnabled/experiments.typedRoutes`）。
   - `plugins` 追加：`["expo-router", ["expo-secure-store", { "configureAndroidBackup": true }], "expo-font", "expo-localization"]`。注意 `expo-font`/`expo-localization` 无必需配置项，写名字即可；`expo-updates` **不加 plugin**（段 0 不接更新服务器，见 D1）。
   - 新增 `extra: { apiBaseUrl, enableDevRoutes, eas: { projectId } }`，值全部从 `process.env.EXPO_PUBLIC_*` 读，缺省即抛错（不静默回退 `localhost`）。
   - `splash.backgroundColor: "#F7F8FA"` 属配置文件、不属业务代码，但 `no-restricted-syntax` 的文件范围是 `**/*.{ts,tsx}`，**会**覆盖 `app.config.ts`（按现配置推断，未实跑）。所以 `app.config.ts` 要进 `no-restricted-syntax` 的豁免列表（与 `shared/theme/**` 同一条），理由：配置文件的颜色是 app 清单要求，无法引 TS 令牌。

7. **依赖口径**：本任务**不新增任何运行时依赖**（新增全在 B2/B3/D1）。已有的 `@expo/vector-icons@^15.0.3` 保留，B3 的图标镜像用它。

**产出**：
- `apps/mobile-app/package.json`（`packageManager`、`engines`、scripts 增加 `check:i18n`、`check:rawtext`、`verify`）
- `apps/mobile-app/app.config.ts`（替换 `app.json`；或保留 `app.json` 作为静态基底 + `app.config.ts` 引用它，取一即可，**裁定：直接用 `app.config.ts` 全量替换，避免两处配置**）
- `apps/mobile-app/app/`：删 12、重写 2（`_layout`、`index`）、改 1（`+not-found`）、改名并重写 1（`(auth)/login` → `login`）、新增 3（`language`、`dev/probe`、`dev/rtl`）= 收口后 7 个路由文件
- `apps/mobile-app/shared/navigation/routes.ts`（重写，7 条）
- `apps/mobile-app/features/`：只剩 `auth/`（LoginPage 重写）+ `settings/`、`dev/`（新增）
- `scripts/prepare-release.ps1`
- `docs/architecture/依赖清单.md`（**新建**：`AGENTS.md` §4.12 要求新增依赖登记在此，实测 `docs/architecture/` 下没有这个文件）

**算做完**：
- [ ] `npx tsc --noEmit` 零错误（`routes.ts` 里不存在指向已删路由的常量）
- [ ] `npx eslint .` 零错误零告警
- [ ] `npm ci` 从零装一遍成功，`package-lock.json` 无改动（`git status` 干净）
- [ ] `npx expo start` 能起，`/`、`/login`、`/language`、`/dev/probe`、`/dev/rtl` 五个路径都能打开不报错（此时页面可以是空壳，只要路由通）
- [ ] 仓库里 `grep -r "fixtures" apps/mobile-app --include=*.ts*` 结果为 0
- [ ] `git log --oneline -1` 是一次只做「裁剪 + 路由树」的提交
- [ ] `docs/architecture/依赖清单.md` 存在且已登记本段新增依赖（本任务为「无新增」，写明确认）

**卡住怎么办**：
1. 删完页面后 `tsc` 大面积报错（找不到模块）→ 不要靠 `// @ts-expect-error` 或改 `tsconfig` 过关（`AGENTS.md` §5 禁止）；按报错顺序把 `routes.ts` 同步到只剩 7 条，删一处路由壳就删一处常量，**一次提交内完成**。
2. `typedRoutes` 生成类型没刷新 → `npx expo customize tsconfig.json` 不解决问题；正确做法是删 `.expo/types`、重启 `npx expo start` 让它重新生成，再跑 `tsc`。
3. `app.config.ts` 报 `no-restricted-syntax` → 把 `app.config.ts` 加进该规则的豁免块（与 `shared/theme/**` 同一块），不要为了过 lint 把颜色写进 TS 常量再引用（那是绕过，不是豁免）。
4. 探针页的 lint 豁免被质疑「开了一个口子」→ 用 `check-raw-text.ps1` 把闸门补上：脚本只扫 `app/**` 与 `features/**` 并**显式排除 `app/dev/**`、`features/dev/**` 两个路径前缀**，排除项写进脚本常量（可审），不依赖 eslint 的豁免块。
5. 有人提「顺手把 68 页路由常量都建了吧」→ 拒绝。建 68 条指向不存在路由的常量会让 `tsc` 立刻红，且违反总纲 §4.4 规则 3。

---

#### 任务 B2：网络层 + 鉴权态

**做什么**：
建 `shared/api/client.ts` 作为**唯一网络出口**，把 8 项请求头 + `traceId` 回读、`R<T>` 解析、401 双通道判定、单飞刷新、「只清被拒 token」、SecureStore 存取全部收敛在这一个文件里；导航与它解耦。

**怎么做**：

1. **依赖（版本口径统一：`npx expo install`，不手写版本号）**
   ```
   cd apps/mobile-app
   npx expo install expo-secure-store expo-constants
   ```
   - `expo install` 会从 SDK 54 的兼容矩阵里挑版本，避免手写 `~15.x` 抄错。落地后把实际版本回写 `docs/architecture/依赖清单.md`。
   - **不装** `axios`：`fetch` 够用，且拦截器逻辑要自己写（axios 的 interceptor 在 RN 下处理 401 并发刷新并不比自己写简单），少一个依赖少一处口径。**判断，非实测。**
   - **不装** `@react-native-async-storage/async-storage`：token 必须进 SecureStore（钥匙串/Keystore），AsyncStorage 是明文。

2. **baseURL 来源**（`shared/api/client.ts` 顶部唯一一处读取）：
   ```ts
   // app.config.ts → extra.apiBaseUrl；client.ts 经 expo-constants 读
   const baseUrl = Constants.expoConfig?.extra?.apiBaseUrl as string | undefined;
   if (!baseUrl) throw new Error('[api] extra.apiBaseUrl 未配置：检查 EXPO_PUBLIC_API_BASE_URL');
   ```
   - 为什么不用 `process.env.EXPO_PUBLIC_*` 直读：`EXPO_PUBLIC_*` 在构建期内联，EAS 上必须写进 `eas.json` 的 `env` 或用 EAS secrets，改地址要重新构建；而 `extra` 会进 manifest，`/dev/probe` 页可以直接把当前 baseURL 显示出来给验收人看（`0-A2`/`0-A5` 都需要这个「当前连的是哪台机器」的可视证据）。
   - **fail-fast，不给默认值**：静默默认 `http://localhost:8080` 会在真机上表现为「一直网络错误」，是最难查的一类故障。
   - 真机联调必须用 PC 的局域网 IPv4（`ipconfig` 取），**不能用 `localhost`（指向手机自己）也不能用 `10.0.2.2`（那是 Android 模拟器专用）**；Windows 防火墙要放行 8080。验收前置：手机浏览器能打开 `http://<PC-IP>:8080/api/v1/pub/ping`。

3. **请求头（8 个必需 + 1 个响应回读）**。注意：`docs/BASELINE.md` 的索引写「§四 请求（**9 个**请求头）」，而接口规范 §4.1 的表实际只有 **8 行**；第 9 个若指 `X-Trace-Id`，它是响应头。**按 8 个实现，并把这条口径差登记到第四节。**

   | Header | 值来源 | 段 0 |
   |---|---|---|
   | `Authorization` | `Bearer ` + SecureStore 里的 accessToken | 除 `pub` 接口外必带 |
   | `Content-Type` | `application/json;charset=UTF-8` | 有 body 时 |
   | `X-Request-Id` | `crypto.randomUUID()` 或自建 UUIDv4；**同一次逻辑请求重试必须复用** | 必带 |
   | `Idempotency-Key` | `<业务类型>:<业务单号>` | **段 0 不传**（§6.4 判据是「会产生资金流水或不可逆状态迁移」，登录与刷新都不属于） |
   | `Accept-Language` | 当前 `lang`（`zh-Hans`/`bo`/`ug-Arab-CN`/`kk-Arab-CN`） | 必带 |
   | `X-Client-Version` | `Constants.expoConfig?.version` | 必带（不引 `expo-application`） |
   | `X-Client-Platform` | `Platform.OS`（`android`/`ios`），Web 为 `web` | 必带 |
   | `X-Device-Id` | 首次启动生成 UUIDv4，SecureStore key `faze.deviceId` | 必带 |
   | （响应）`X-Trace-Id` | 与 body `traceId` 同值 → 存进 `AppError.traceId` | 上报错误时必须带上 |

   > 顺手发现的文档错误：§4.1 表里 `Idempotency-Key` 写「见 §5.4」，而 §5.4 是「空值与缺省语义」，**幂等在 §6.4**。汇编时请改索引（进第四节）。

4. **`R<T>` 解析（`shared/api/types.ts`）**
   ```ts
   type ApiEnvelope<T> = { code: number; msg: string; data: T | null; traceId: string | null };
   type AppError =
     | { kind: 'business'; code: number; msgKey: MessageKey; traceId: string | null }
     | { kind: 'network'; msgKey: MessageKey; traceId: null }
     | { kind: 'parse'; msgKey: MessageKey; traceId: string | null };
   ```
   - `code === 200` → 返回 `data`（`data` 为 `null` 时返回 `null`，**不把 `null` 当错误**：§5.1 约束 1 明确 `data: null` 是保留字段）。
   - 非 200 → `business`，`msgKey` 从 `shared/api/errorCodes.ts` 的 `Record<number, MessageKey>` 查。段 0 只登记 6 个码（`401002`/`401005`/`401007`/`402002`/`402003`/`508001`），**不整表抄 78 个**（那是段 8 的填充线）。
   - **`R.msg` 绝不直接展示**（§5.1 约束 2：只给开发者看）。展示一律走 `t(msgKey)`。
   - `resp.body` 非 JSON / 网络异常 → 归一成 `network`，**不向上抛原始异常**（这是 `0-A8` 的基础）。

5. **401 双通道判定 + 只清被拒 token + 单飞刷新**（严格对齐接口规范 §6.1.1）
   ```
   判据：resp.status === 401 且 body.code ∈ {402002, 402003}
   前置：本次请求显式带了 Authorization 头（否则不判登出 —— 登录失败也会 401，
         若无此条，用户连登录页都进不去）
   402002（过期，可刷）→ 单飞刷新 → 成功则重放原请求【只重放 1 次，防环】
   402003（无效）     → 清 token → onUnauthorized()
   刷新本身返回 401   → 直接登出，不再触发刷新
   迟到 401           → expireStoredSession(rejectedToken) 先比对「被拒的 token 是否就是当前存的 token」，
                        不是就丢弃（否则会把刚登录的新会话清掉，表现为「刚登录就被踢」）
   ```
   - 「单飞」：模块级 `let refreshing: Promise<boolean> | null`，并发请求共享同一个 Promise 并等待结果（§6.1 要求「同一时刻只允许一个刷新请求在飞，其余等待结果」）。
   - 存储 key：`faze.accessToken`、`faze.refreshToken`、`faze.deviceId`、`faze.lang`、`faze.rtlPrevPath`。**用户资料不落盘**（SecureStore 的单值上限对长 JSON 不友好，且资料应每次启动拉取）。**判断，非实测。**

6. **`onUnauthorized` 与导航解耦**
   - `shared/api/authEvents.ts`：`let handler: (() => void) | null = null; export function setUnauthorizedHandler(fn) {...}`
   - `client.ts` **不 import `expo-router`**。理由：`client.ts` 被 `features/*/api` 引用，若它再引用 router，会形成 `router → 页面 → api → router` 的循环；而且单测环境没有导航容器。
   - `app/_layout.tsx` 挂载时注册：`setUnauthorizedHandler(() => { toast.show(t('error.auth.token-expired')); router.replace('/login'); })`（`t` 与 toast 都在根布局里可用）。

7. **PC 互操作（§1.3.1）在本任务里的边界**：B2 **只做 C 端**。PC 走 RuoYi 登录 + 共享 `token.secret`/Redis 的链路属段 1，**且 §1.3.1 第 5 条「`LoginUser` 在 Redis 里怎么序列化」尚未实测**——本任务不碰它，只在 `client.ts` 的 401 分流上预留 `admin` 分支的位置（注释写清「PC 端是 `HTTP 200 + code=401`，段 1 接入」），避免段 1 有人把 C 端判据照抄到 PC。

8. **段 0 的接口缺口必须先裁决（这条会决定 B2 能否验收）**：
   - 接口规范 §7.1 写「段 0 只有 3 个 `pub` 接口，不实现任何业务接口」；但同文件 §2.2 把「登录组 3 接口（`/pub/auth/login/password`、`/pub/auth/login/sms`、`/app/auth/refresh`）」标注「**落段 0**」，而总纲 `0-A2` 的判据是「登录走真实接口 / 失效后自动清 token 并回登录页」。
   - **裁决（施工按此执行，裁决权在主理人）**：段 0 必做 **2 个**鉴权接口——`POST /api/v1/pub/auth/login/password` 与 `POST /api/v1/app/auth/refresh`。没有这两个，`0-A2` 的两条判据**根本无法执行**，段 0 的出口条件不成立。
   - 401 的验收载体用 `/app/auth/refresh`（它已冻结在 §2.2 组 1，无需新增契约）：手动在 Redis 删掉该 refresh token（或改 `FAZE_TOKEN_SECRET`）→ App 调用刷新 → 得 401 → 清 token → 回登录页。**不新增 `GET /app/auth/me` 这类契约外接口。**
   - 若主理人裁决「段 0 不加鉴权接口」→ 退化方案：B2 只交 `client.ts` 骨架（无 401 分支），`0-A2` 的「401 + 自动回登录页」半条**挂账到段 1 首日**，段 0 只验「真接口」。**代价：段 0 的出口条件要改写，`0-A2` 降级为「0-A2a 真接口」。**

**产出**：
- `apps/mobile-app/shared/api/client.ts`（唯一出口：`request<T>()`、`get/post` 包装、`upload` 预留位）
- `apps/mobile-app/shared/api/{types.ts,authEvents.ts,errorCodes.ts}`
- `apps/mobile-app/shared/api/tokenStore.ts`（SecureStore 读写 + `expireStoredSession(rejectedToken)`）
- `apps/mobile-app/features/auth/api/auth.ts`（`loginPassword()` / `refresh()`，页面唯一可调的鉴权封装）
- `apps/mobile-app/package.json` 新增 `expo-secure-store`、`expo-constants`
- `docs/architecture/依赖清单.md` 更新
- `docs/standards/网络层约定.md`（新建：baseURL 来源、8 个头、错误归一、401 分流三条判据，**给段 1~8 的施工方看**）

**算做完**：
- [ ] `grep -rn "fetch(" apps/mobile-app --include=*.ts*` 的结果里，除 `shared/api/client.ts` 与 `shared/api/sse.ts` 外为 0（唯一出口成立）
- [ ] `grep -rn "expo-router" apps/mobile-app/shared/api/` 结果为 0（导航解耦成立）
- [ ] `grep -rn "expo-secure-store" apps/mobile-app/features/` 结果为 0（页面不直接读存储，只经 `tokenStore`/`client`）
- [ ] 真机（或 expo web）上：登录成功 → 杀进程重启 → 仍是登录态（token 从 SecureStore 读回）
- [ ] 手动删 Redis 里的 refresh token 后发起刷新：得到 `402003` → token 被清 → 回登录页，`adb logcat` 无红屏
- [ ] 同一时刻并发 3 个需鉴权请求且 token 已过期：`adb logcat` 里只出现 **1 次** refresh 调用（单飞成立）
- [ ] 断网后点登录：返回 `kind: 'network'` 的 `AppError`，页面进错误态而不是抛异常（与 B4 联合验）
- [ ] 每条请求都能在服务端日志里用 `X-Request-Id` 串起来（服务端已打该字段时）

**卡住怎么办**：
1. SecureStore 在 Android 上写入失败（部分机型/多用户）→ 先确认 `app.json`/`app.config.ts` 里 `expo-secure-store` 的 plugin 已配 `configureAndroidBackup: true`；仍失败则降级为「内存 + AsyncStorage 缓存 + 每次启动重拉」，但**必须在 `docs/standards/` 记一条已知限制**，不许静默降级。
2. 刷新死循环（无限刷新）→ 三条硬约束缺一条都会循环：①只重放 1 次；②刷新自身的 401 不再触发刷新；③只对带 `Authorization` 的请求判登出。逐条自查，别加「最大重试次数」这种掩盖症状的补丁。
3. 真机一直 `network` → 按顺序：手机浏览器开 `http://<PC-IP>:8080/api/v1/pub/ping` → 防火墙 → `ipconfig` 是否取了虚拟网卡（VMware/WSL 的 172.x）→ baseURL 是否被 `app.config.ts` 的 `extra` 覆盖成了旧值（重新 `npx expo start --clear`）。
4. 服务端 401 body 为空/非 JSON → 是 A 组的全局异常处理没生效，**不要在客户端加 `try/catch` 兜**，回报 A 组（`R` 结构是 §5.1 的冻结契约）。
5. 若裁决为「段 0 不加鉴权接口」→ 走上面第 8 条的退化方案，并在 `docs/CHANGELOG.md` 记「0-A2 降级」，不要自己发明接口。

---

#### 任务 B3：本地化 + RTL 坐标系

**做什么**：
把「线 A：机制」在第 0 段一次定型：i18n 底座、语言状态单一来源、`forceRTL` 软重启链路、`LocalizedText`/`LtrText` 职责边界、图标镜像白名单、3 条 lint 规则、四语言目录同构与缺译登记。**这一段定完，段 1~8 只能按机制用，不许改机制。**

**怎么做**：

1. **i18n 底座：自研轻量（`shared/i18n/`），不引 `i18next` / `react-i18next`。** 这是本任务最重要的裁决。

   | 判据 | 自研 | i18next + react-i18next |
   |---|---|---|
   | 需求面 | 只需「静态 key → 字符串 + `{{camelCase}}` 占位符 + 缺译降级 + 缺 key 登记」 | 带命名空间、上下文、嵌套、后端加载、语言探测、`Intl.PluralRules` 复数——段 0 到段 8 都用不到后四项 |
   | 与既有裁决的关系 | 无 Intl 依赖 | **复数走 `Intl.PluralRules`**，而技术路线 §13.7.1 已裁决「不用 `Intl`」（Hermes 的 locale 数据覆盖不确定，`kk_Arab`/`ug_Arab` 缺失时**静默回退英文**）。引 i18next 等于把已关掉的风险重新打开 |
   | 类型安全 | `type MessageKey = keyof typeof zhHans`，`t()` 的入参**编译期查得到**，缺 key 直接 `tsc` 报错 | 需要手写 `react-i18next.d.ts` 声明 resources，写漏了就不查 |
   | CI 六项检查 | 资源是 `import` 的静态 JSON，`ConvertFrom-Json` 直接比对 | 要走 `getResourceStore()` 或再解析一遍文件，多一层 |
   | 依赖成本 | 0 | 2~3 个新依赖（`AGENTS.md` §4.12 要逐个说明理由并登记） |
   | 退出成本 | `t()` 是唯一入口，将来换 i18next 只改 `shared/i18n/index.ts` 一个文件 | — |

   **结论：自研。** 反方唯一站得住的理由是「生态成熟」，但它在段 8 之前没有任何一条需求命中；而退出成本被「`t()` 单一入口」隔离到 1 个文件。

   目录（严格照 i18n 规范 §4.1）：
   ```
   shared/i18n/
   ├─ index.ts            I18nProvider / useI18n() / t() / setLang()
   ├─ lang.ts             Lang 类型、LANGS（code/nativeName/dir）、初始语言探测
   ├─ rtl.ts              applyDirection(lang)：allowRTL → forceRTL → reloadAsync
   ├─ datetime.ts         formatDate / formatRelative / formatAmount（自建表，不用 Intl）
   └─ resources/
      ├─ zh-Hans/{common,auth,home,lawyer,conversation,trade,document,case,content,ai,profile,ops,workbench,error}.json
      ├─ bo/  ug-Arab-CN/  kk-Arab-CN/      ← 与 zh-Hans 完全同构（14 个文件，内容可为 {}）
      └─ .i18n-pending.json
   ```
   - **14 × 4 = 56 个文件在段 0 一次建全**（其中 10 个 namespace 段 0 用不到，内容写 `{}`）。依据 i18n 规范 §4.2：文件必须存在，缺文件会导致 `bo` 下静默返回原始 key。用 `scripts/` 里的一次性生成脚本建，不手搓。
   - `t(key, vars?)` 行为：缺 key → dev 返回 `⟦key⟧` 并 `console.warn`；prod 返回 `common.fallbackText`。占位符 `{{name}}` 用正则替换，缺 var 时 dev 报警。
   - 复数：先查 `${key}_other`，没有再回落 `${key}`；**不引 `Intl.PluralRules`**。i18n 规范 §3.4 允许目标语言多出 `_one`/`_other`，这条 10 行兼容层就够。
   - 语言状态单一来源（i18n 规范 §2.3）：`SecureStore('faze.lang')` ← 首次启动读系统语言（`expo-localization` 的 `getLocales()`）→ 无匹配则 `zh-Hans`。**不跟随系统实时切换。** 页面只 `useI18n()`，不许自己读 SecureStore。
   - 段 0 的填充策略：`zh-Hans` 只填 4 个页面用到的 key；`bo`/`ug-Arab-CN`/`kk-Arab-CN` 三个目录**全部空对象**，并把全部 key 登记进 `.i18n-pending.json`（由 `check-i18n.ps1` 生成，不手写）。降级链落到 `zh-Hans`，与总纲 §7.1「key 直接上屏也算通过」相比更严（总纲允许上屏 key，我们直接给中文），**按更严的做**。

2. **`forceRTL` + `Updates.reloadAsync()` 软重启链路**（`shared/i18n/rtl.ts`，严格照技术路线 §13.4）
   ```
   同向（zh ↔ bo，都是 LTR）→ changeLanguage()，全树重渲染，不重启
   异向（LTR ↔ RTL）        → 1. SecureStore 写 lang
                              2. SecureStore 写 { pathname, params } → faze.rtlPrevPath
                              3. I18nManager.allowRTL(true)
                              4. I18nManager.forceRTL(true)   ← 必须在 reload 之前，它写原生持久化标记
                              5. Updates.reloadAsync()
                              6. 启动时读 lang → 校验 isRTL 与 lang.dir 一致 → 不一致则纠正后再 reload
                              7. 读 faze.rtlPrevPath → router.replace 回原路由，清掉该 key
   ```
   - 装 `expo-updates`，**只当「软重启原语」用，不接更新服务器**：`app.config.ts` 里**不配** `updates.url`，`runtimeVersion` 用 `{ policy: "appVersion" }`，`checkAutomatically: "NEVER"`。总纲 §5.1「不做热更新通道」按此满足。
   - **`0-A3` 的硬判据不依赖 `reloadAsync`**：原文是「**杀进程重启后仍是 RTL**（证明软重启链路通、状态已持久化）」。`forceRTL` 写的是原生持久化标记，杀进程重启后自然仍生效；`reloadAsync` 只影响「用户是否需要手动杀进程」这一体验层。所以在 D1 里 **`reloadAsync` 是否可用要单独记录，但不能用它卡 `0-A3`**：若它不可用，`0-A3` 用「手动杀进程」验，并在验收记录里注明是「手动」还是「软重启」。
   - 未实测项（技术路线 §13.4 自己标的 V2.2 待实测，加上「未配 `updates.url` 时 `reloadAsync` 会不会被禁用」这一条新问题）：**进第四节，由 D1 实测回写。**

3. **`LocalizedText` 与 `LtrText` 的职责边界（两个组件，不许合并、不许互相嵌套两层逻辑）**

   | | `LocalizedText` | `LtrText` |
   |---|---|---|
   | 唯一职责 | 按 Unicode 区段把字符串切成 run，逐 run 指定 `fontFamily` | 把内容包 `U+2066…U+2069`（LRI…PDI），强制 LTR 隔离 |
   | 切分规则 | `U+0F00–U+0FFF`→藏文；`U+0600–06FF` / `U+0750–077F`→阿拉伯系；其余→系统字体（技术路线 §13.3.1） | 不切 run、不管字体 |
   | 管方向吗 | 不管 | 只管方向，不管字体 |
   | 管 `t()` 吗 | 不管。入参是**已翻译好的字符串**（或任意字符串） | 不管 |
   | 强制约束 | 必须显式传 `numberOfLines`（阿拉伯系折行会断连写，i18n 规范 §7.1） | 内部用 `LocalizedText` 渲染 |
   | 落点 | `shared/components/LocalizedText.tsx`（**全仓唯一允许 import RN `Text` 的文件之一**） | `shared/components/LtrText.tsx`（另一个） |

   - 组合规则：**金额、法条编号、案号、电话、用户输入的含数字串**（UGC）用 `<LtrText>`；其余用 `<LocalizedText>`；`<LtrText>` 内部已经过 `LocalizedText`，**禁止再套一层 `LocalizedText`**。
   - 行高：藏文 `lineHeight ≥ 1.6 × fontSize`；混排取两字体最大值（i18n 规范 §7.2）。写进 `shared/theme/typography.ts` 的辅助函数 `lineHeightFor(lang, fontSize)`，页面不手算。

4. **图标镜像白名单**
   - `shared/components/icons/index.ts`：
     ```ts
     /** 必须随 RTL 镜像的图标白名单。i18n 规范 §8.2。名字以 @expo/vector-icons 的类型为准。 */
     export const DIRECTIONAL_ICONS = ['arrow-back','arrow-forward','chevron-back','chevron-forward','arrow-undo','arrow-redo'] as const;
     export type DirectionalIconName = (typeof DIRECTIONAL_ICONS)[number];
     ```
   - `shared/components/DirectionalIcon.tsx`：只接受 `DirectionalIconName`，内部 `I18nManager.isRTL ? { transform: [{ scaleX: -1 }] } : null`。
   - **白名单是「类型」而不是「文档」**：不在白名单的图标名传不进去，所以「把公司 logo 镜像了」这种错误编译期就拦住。这比「写个文档提醒」强，因为文档没人读。
   - 品牌 logo / 头像 / 播放暂停 / 对勾叉 / 时钟日历**不进白名单**（§8.2 右列）。

5. **3 条门禁规则的具体写法（另附 2 条配套 selector）**，加进 `apps/mobile-app/eslint.config.mjs` 现有 `no-restricted-syntax` 数组，风格与现有「禁颜色字面量」一致。
   ① 物理方向属性；② 裸 `<Text>`；③ i18n 两条（`no-raw-text` + `no-dynamic-i18n-key`）。配套的 ①b（`textAlign`）与 ⑤（镜像落点）不单独成规则，但必须同批上，否则前三条有绕过口子。

   ```js
   // ① 禁物理方向属性（RTL 坐标系的基础；i18n 规范 §8.1）
   {
     selector:
       "Property[key.name=/^(left|right|marginLeft|marginRight|paddingLeft|paddingRight|borderLeftWidth|borderRightWidth|borderLeftColor|borderRightColor|borderTopLeftRadius|borderTopRightRadius|borderBottomLeftRadius|borderBottomRightRadius)$/]",
     message: 'RTL 必需：用 start/end 替代 left/right 物理方向属性（i18n 规范 §8.1）。',
   },
   // ①b textAlign 没有 start/end，RN 只认 auto；写 left/right 在 RTL 下不镜像
   {
     selector: "Property[key.name='textAlign'][value.value=/^(left|right)$/]",
     message: "RN 的 textAlign 无 start/end：用 'auto' 跟随方向，或按 lang 取值。",
   },

   // ② 禁裸 <Text>：用「禁 import」比「查 JSX 标签」准，别名导入也拦得住
   //    单独一条规则，不是 no-restricted-syntax：
   'no-restricted-imports': ['error', {
     paths: [{
       name: 'react-native',
       importNames: ['Text'],
       message: '用户可见文本必须走 <LocalizedText>；原生 Text 只允许出现在 LocalizedText.tsx 与 LtrText.tsx。',
     }],
   }],

   // ③a no-raw-text（i18n 规范 §11.1 第 5 项）：JSX 里的裸字符串 + 三元里的裸字面量
   {
     selector: "JSXText[value=/[^\\s]/]",
     message: '硬编码文本：所有用户可见字符串走 t()（no-raw-text）。',
   },
   {
     selector: "JSXExpressionContainer > Literal[value=/[^\\s]/]",
     message: '硬编码文本：三元/表达式里的字符串字面量也要走 t()。',
   },

   // ③b no-dynamic-i18n-key（i18n 规范 §11.1 第 6 项）：禁拼 key
   {
     selector: "CallExpression[callee.name='t'] TemplateLiteral",
     message: '禁拼 key：t() 实参必须是字面量或已登记常量（no-dynamic-i18n-key）。',
   },
   {
     selector: "CallExpression[callee.name='t'] > BinaryExpression[operator='+']",
     message: '禁拼 key：t() 实参不许字符串拼接。',
   },

   // ⑤ 镜像只能由 DirectionalIcon 做
   {
     selector: "Property[key.name='transform'] Property[key.name='scaleX'][value.value=-1]",
     message: '图标镜像只能由 <DirectionalIcon> 统一处理（i18n 规范 §8.2）。',
   },
   ```
   - 豁免块（现有 `shared/theme/**` 豁免旁边加）：
     ```js
     { files: ['shared/components/LocalizedText.tsx', 'shared/components/LtrText.tsx'],
       rules: { 'no-restricted-imports': 'off', 'no-restricted-syntax': 'off' } },
     { files: ['shared/components/icons/**', 'shared/components/DirectionalIcon.tsx'],
       rules: { 'no-restricted-syntax': 'off' } },
     { files: ['shared/theme/**', 'app.config.ts'],
       rules: { 'no-restricted-syntax': 'off' } },
     // 探针页：段 0 唯一的 lint 豁免区（见 B1 第 2 条的裁决）。
     // 关掉整条 no-restricted-syntax = 颜色/裸文本/拼 key 三条一起关；探针页不进 release 包，代价可控。
     { files: ['app/dev/**', 'features/dev/**'],
       rules: { 'no-restricted-syntax': 'off' } },
     ```
   - **规则 ② 的注意点**：`no-restricted-imports` 的 `importNames` 对 `import { Text }`、`import { Text as T }` 都生效；但 `const { Text } = require('react-native')` 拦不住——**在本项目不构成漏洞**，因为 `AGENTS.md` §4 要求静态 import、且 `assets/legacy/index.ts` 那个 `require()` 文件已删。
   - 一键闸门：`scripts/check-raw-text.ps1`（`AGENTS.md` §7.4 与 `0-A7` 都要「全仓搜」这个动作，人眼搜不算证据）。脚本内容：递归扫 `app/**`、`features/**`、`shared/**` 的 `*.tsx`，正则 `<Text[ >]`，**排除常量 `shared/components/LocalizedText.tsx`、`shared/components/LtrText.tsx`、`app/dev/**`、`features/dev/**`**，输出每个命中的 `文件:行`，有命中即 `exit 1`。排除名单是脚本里的常量（可 code review），不是口头约定。

6. **四语言目录同构与占位符一致的门禁**：`scripts/check-i18n.ps1`，实现 i18n 规范 §11.1 的前 3 项——①四个目录文件名集合相同；②目标语言的 key 集合不缺源语言（`ug` 允许缺但必须在 `.i18n-pending.json` 登记）；③每个 key 的 `{{...}}` 集合四语言一致。失败即 `exit 1`，挂进 `npm run verify`。

**产出**：
- `apps/mobile-app/shared/i18n/{index.ts,lang.ts,rtl.ts,datetime.ts}`
- `apps/mobile-app/shared/i18n/resources/**`（56 个 JSON + `.i18n-pending.json`）
- `apps/mobile-app/shared/components/{LocalizedText.tsx,LtrText.tsx,DirectionalIcon.tsx}`
- `apps/mobile-app/shared/components/icons/index.ts`
- `apps/mobile-app/shared/theme/typography.ts`（加 `lineHeightFor(lang, size)`）
- `apps/mobile-app/eslint.config.mjs`（6 组 selector + 4 个豁免块）
- `scripts/{check-i18n.ps1,check-raw-text.ps1}`
- `scripts/prepare-i18n-skeleton.ps1`（一次性生成 56 个文件，跑完即可删或留作重生工具）
- `apps/mobile-app/assets/fonts/`（字体在 D2 落地，B3 先建目录与 `useFonts` 挂钩）
- `package.json` 新增 `expo-updates`、`expo-font`、`expo-localization`（`npx expo install`）
- 修复 `shared/components/` 里 5 处物理方向属性

**算做完**：
- [ ] `pwsh scripts/check-raw-text.ps1` 退出码 0，输出「命中 0」
- [ ] `pwsh scripts/check-i18n.ps1` 退出码 0（四目录同构 + key 集合 + 占位符三项全过）
- [ ] `npx tsc --noEmit` 零错误；`t('auth.login.不存在的key')` 能编出**编译期错误**（用临时文件验一次后删除）
- [ ] 故意写一处 `marginLeft: 8` → `eslint` 报错；删掉后通过（规则真的在拦，不是被豁免吞掉）
- [ ] 故意写一处 `<Text>测试</Text>` → `eslint` 报错；删掉后通过
- [ ] 真机切到 `ug-Arab-CN`：页面镜像（返回箭头在右、`paddingStart` 生效），杀进程重启后仍为 RTL（**这一条是 `0-A3` 的硬判据**）
- [ ] 重启后回到切换前的路由，而不是回首页（`faze.rtlPrevPath` 生效）
- [ ] 中 ↔ 藏切换**不**触发重启（同向不 reload）
- [ ] `adb logcat` 里 `reloadAsync` 的成败被明确记录下来（成功/抛错/不生效，三选一，写进验收记录）

**卡住怎么办**：
1. `forceRTL` 之后不重启方向不变 → 正常（RN 官方：变更在下次启动生效）。若**重启后**仍不变：①确认 `forceRTL` 调用在 `reloadAsync` 之前（顺序反了标记没写进去）；②确认 `allowRTL(true)` 也调了；③确认构建不是 Expo Go（§13.4：Expo Go 下 `reloadAsync` 不可用，方向也可能不生效）。
2. `reloadAsync` 抛错/无效 → **不要因此判 `0-A3` 失败**（硬判据是「杀进程后仍 RTL」）。按 D1 的出口处理：记「软重启不可用 / 需手动重启」，把结论写进 `docs/standards/`，并在 `/dev/rtl` 页加一行提示文案。若连「杀进程重启后仍 RTL」也不成立 → 这是 `forceRTL` 机制本身失效，**升级到主理人**（因为整段 RTL 方案要重设计）。
3. 自研 `t()` 的 `MessageKey` 类型在资源 JSON 变更后不更新 → 确认 `resources/zh-Hans/*.json` 用 `import` 静态引入（不要 `fetch`/`require` 动态路径），TS 才能推出字面量类型；必要时加 `as const`。
4. 藏文/阿拉伯系在 `LocalizedText` 里仍显示系统字形 → 字体没加载（`useFonts` 的 `fontFamily` 名与文件名不一致，Android 写文件名无扩展名）；D2 会正面验这件事。
5. `check-i18n.ps1` 报「`bo` 缺 200 个 key」→ 不是脚本错，是 `.i18n-pending.json` 没生成。用脚本重新生成登记表，**不要为了让检查通过去关掉第 2 项检查**。

---

#### 任务 B4：页面四态与错误边界

**做什么**：
把「加载 / 空 / 错误 / 正常」四态与错误边界做成**通用实现**，让段 1 起的每一页只写正常态，其余三态自动有。并保证 `0-A8`（断网不崩）成立。

**怎么做**：

1. **状态模型（`shared/components/AsyncBoundary.tsx`）——四态，没有第五态**
   ```ts
   export type AsyncState<T> =
     | { phase: 'loading' }
     | { phase: 'empty' }
     | { phase: 'error'; error: AppError }
     | { phase: 'success'; data: T };

   export type AsyncBoundaryProps<T> = {
     state: AsyncState<T>;
     onRetry: () => void;
     children: (data: T) => React.ReactNode;   // 只写正常态
     emptyHintKey?: MessageKey;                // 空态文案可覆盖
     loadingRows?: number;                     // 骨架屏行数
   };
   ```
   - **不设 `idle`**：`idle` 与 `loading` 在 UI 上无法区分，多一个状态等于多一处「到底该显示什么」的争论。
   - `error` 分支内部按 `AppError.kind` 选文案：`network` → `error.common.network`；`business` → `t(error.msgKey)`；`parse` → `error.common.server`。**一律带「重试」按钮**，`onRetry` 由调用方传入（通常是重新发起的那个函数）。
   - 空态与错误态都是独立组件：`EmptyState` / `ErrorState` / `LoadingState`（骨架），全部用令牌 + `LocalizedText`。文案分工：空/加载/重试按钮进 `common`（`common.state.empty` / `common.state.loading` / `common.state.retry`）；错误文案进 `error`（`error.common.network` / `error.common.server`，按 i18n 规范 §4.4 用**完整 msgKey**，不拼前缀）。

2. **错误边界落点：`app/_layout.tsx` 导出 expo-router 的 `ErrorBoundary`，不另写 class boundary。**
   ```tsx
   // app/_layout.tsx
   export function ErrorBoundary(props: ErrorBoundaryProps) { /* 渲染 ErrorState + props.retry + 「回登录页」 */ }
   ```
   - 选它的理由：expo-router 已按路由树自动包住子路由，是唯一能捕获**整棵树**渲染异常的位置；再手写一个 `AppErrorBoundary` 会形成两个错误界面，验收时说不清看到的是哪一个。
   - 边界界面里必须做三件事：显示可读文案（走 `t()`，不许出现英文 `TypeError`）、显示 `traceId`（有则显示，便于后端定位）、提供「重试」与「回登录页」两个出口。
   - **不做全局 `ErrorUtils.setGlobalHandler` 吞异常**：它会把崩溃变成白屏，`0-A8` 的「有错误态」反而更难看。只记日志、不吞。

3. **`0-A8`（断网不崩）怎么保证——三条硬约束**
   1. `client.ts` 把网络异常归一成 `AppError{kind:'network'}`，**永不向渲染层抛异常**（B2 已定）。
   2. 所有异步调用走 `runAsync` 包装：`try { setState(success) } catch (e) { setState(error, normalize(e)) }`，页面里**禁止**出现没有 catch 的 `await`（`eslint` 的 `@typescript-eslint/no-floating-promises` 若要开，属段 1 的事；段 0 靠 code review + 四态强制性）。
   3. 首屏（`app/index.tsx`）读 SecureStore 失败也必须落到某个路由，**不许停在空白**：`try { ... } catch { router.replace('/login') }`。
   - 验收动作（必须做，不是「检查代码」）：开飞行模式 → 冷启动 → 登录页显示错误态与重试入口 → 关飞行模式 → 点重试 → 登录成功。三张证据：断网截图、联网后截图、`adb logcat` 无 `FATAL EXCEPTION`。

4. **段 0 只有 2 个产品页，但四态必须做**：`0-A2` 的「不白屏」与 `0-A8` 的「不闪退」都落在这两个页面上。`/dev/probe` 也接四态（它要显示 ping 的结果、AI 流的进行中、law 检索的空/错）。

**产出**：
- `apps/mobile-app/shared/components/{AsyncBoundary.tsx,EmptyState.tsx,ErrorState.tsx,LoadingState.tsx}`
- `apps/mobile-app/app/_layout.tsx`（`ErrorBoundary` 导出 + 字体门控 + 401 handler 注册）
- `apps/mobile-app/shared/i18n/resources/zh-Hans/common.json`（`common.state.*` 4 个 key + `common.fallbackText`）
- `docs/standards/页面四态约定.md`（新建：四态模型、`onRetry` 语义、错误文案映射表、禁止事项）

**算做完**：
- [ ] 飞行模式下冷启动 App：不白屏、不闪退，显示错误态 + 「重试」按钮（截图存证）
- [ ] 关飞行模式点「重试」：进到正常态（截图存证）
- [ ] 服务端停掉（进程 kill）后进登录页：`network` 或 `business` 错误态，**不出现英文报错原文**
- [ ] 故意在 `LoginPage` 渲染里 `throw new Error('x')`：出现错误边界界面（含重试与回登录页），不是红屏/白屏；验完删除
- [ ] `adb logcat *:E` 里没有 `FATAL EXCEPTION`
- [ ] `grep -c "ErrorBoundary" app/_layout.tsx` ≥ 1（导出存在）
- [ ] 四态组件的文案全部来自 `t()`（`check-raw-text.ps1` 为 0 已覆盖）

**卡住怎么办**：
1. 断网后是「一直转圈」而不是错误态 → 是 fetch 没有超时。`client.ts` 必须给每个请求 `AbortController` + 超时（建议 15s；AI 流单独 120s，见 D3），超时也归一到 `network`。
2. expo-router 的 `ErrorBoundary` 不触发（渲染异常仍红屏）→ 确认导出名**大小写完全一致**（`ErrorBoundary`），且写在**路由文件**里（`app/_layout.tsx`），不是普通组件文件。
3. 错误边界自己崩了（文案取 `t()` 时 i18n 未初始化）→ 边界内的文案**必须是硬编码常量或 `t()` 的安全包装**（i18n 未就绪时返回中文常量）。这是唯一允许在 `app/_layout.tsx` 里出现两套文案的地方，注释写清。
4. `0-A8` 通过但 `0-A2` 不通过（能显示错误态，但 401 后不回登录页）→ 是 B2 的 `onUnauthorized` 没注册（B4 与 B2 的交界），查 `app/_layout.tsx` 的 `setUnauthorizedHandler` 是否在 `useEffect` 里、依赖数组是否为空。
5. 若坚持要「全局兜底」→ 允许加，但只能记日志；**任何把异常吞掉变成返回 `null` 的写法都不接受**（那会制造白屏）。

---
### 第 3 组　C 两个产品页

**组内说明**：这两个页是段 0 仅有的两个产品页，也是 `0-A2`（真接口 + 401）与 `0-A3`（RTL 整屏镜像 + 重启保持）的**唯一载体**。它们不是「顺手做的界面」，是验收工具——所以不许为了「好看」偏离 §3.2 的机制。

**C 组开工前提**：B2（`shared/api/client.ts`）与 B3（`t()` + `forceRTL` 封装）已完成。C 组**不允许自己 new axios / 自己读 SecureStore / 自己写 direction 判断**。

---

#### 任务 C1：登录页（真接口）

**做什么**：把仓库里已有的 UI 复刻版登录页 `features/auth/pages/LoginPage.tsx` 改造成接真实接口的登录页。UI 与令牌用法保留，**只换数据源与错误处理**。

**怎么做**：

1. **接口**：`POST /api/v1/pub/auth/login/password`，字段照《接口规范》附录 A.1.1 **逐字照抄**：
   - 请求 `phone`（`^1[3-9]\d{9}$`）、`password`（6–64）、`deviceId`（≤64，**必须与请求头 `X-Device-Id` 相同**）、`smsCode`（段 0 不传）
   - 响应 `data`：`accessToken`、`refreshToken`、`expiresIn`、`tokenType`、`user{id,nickname,avatarUrl,phoneMasked,realnameStatus,isMinor,roles,payPasswordSet}`
2. **`deviceId` 的来源**（容易漏，`0-A2` 会因此 400）：优先 `expo-application` 的 `getAndroidId()`（需新增该依赖）；取不到则生成 UUID 并持久化。**无论哪条路，都必须落到一个 `getDeviceId()` 函数里**，页面不许直接调 `expo-application`。
3. **token 存放**：走 B2 提供的封装（SecureStore），键名在 B2 已定，页面不许自己拼键名。
4. **登录成功**：按导航树「登录拦截」的约定，取 `redirect` 参数回原目标页；没有 `redirect` 进 `/(tabs)/home`。
5. **错误归一化**（照《错误码规范》与 A.1.1 的错误码，**不许把英文或原始 message 上屏**）：
   | 错误码 | 上屏文案的 key |
   |---|---|
   | `402001` | `auth.login.failed`（「手机号或密码错误」——**不区分账号还是密码**，接口就是这么设计的） |
   | `402004` | `auth.login.banned` |
   | `401007` | `auth.login.rateLimited` |
   | 其他 | 走 B4 的统一错误态 |
6. **短信验证码 Tab**：导航树写登录页有「密码登录 / 短信验证码登录」两个 Tab，但段 0 不做短信通道 → **短信 Tab 保留但置灰并提示「暂未开放」**，在 `docs/design/未完成需备注.md` 登记「短信登录待段 1」。不许悄悄删掉 Tab（会让段 1 以为设计里没有）。
7. **多语言**：所有文案走 `t()`；`ug-Arab-CN` 下「手机号 / 密码」输入框必须 `textAlign: start` 且数字键盘对齐正确；**手机号用 `LtrText` 包**（RTL 下 11 位数字不能被重排）。

**产出**：
- `features/auth/pages/LoginPage.tsx`（改造）
- `features/auth/api/login.ts`（请求函数，走 `shared/api/client.ts`）
- `shared/utils/deviceId.ts`（`getDeviceId()`）
- `app/(auth)/login.tsx` 保持一行 `export`（现有已是，不要动）

**算做完**：
- 用真机 + 真后端，输入种子用户的手机号与密码，能登录并进入首页；
- 输错密码，页面出现「手机号或密码错误」，**不是英文、不是 500**；
- 抓包/日志确认请求头 `X-Device-Id` 与 body `deviceId` 一致；
- `0-A2` 的「登录走真实接口」这一半成立（401 那一半在 B2 + C2 后统一验）。

**卡住怎么办**：
1. 后端登录接口未就绪 → 等 A3（A3 的接口清单已含它，见 §零之二 冲突 1）；**不许先用 mock 顶上然后忘记换**。
2. `deviceId` 取不到 → 用 UUID 兜底即可，不要把这一项变成阻塞。
3. 种子用户没插 → 检查 `V2` 种子迁移，段 0 必须有 1 个可登录的测试用户。

---

#### 任务 C2：语言切换页（`forceRTL` + 软重启）

**做什么**：新建语言切换页（导航树「模块 10 设置与其他」第 540 行有这页，属 68 页清单内），4 语言单选，切换后整个 App 镜像且**杀进程重启仍保持**。

**怎么做**：

1. **页面位置**：`features/settings/pages/LanguagePage.tsx` + 路由壳 `app/language.tsx`（一行 `export`）。**路由用 `/language` 而不是 `/language`**：段 0 只有一个语言页，没有设置页，为它造一层 `settings/` 目录是多余抽象（与 B1 取消 `(auth)` 分组同一个理由）；段 1 出现真正的设置页时再收进 `/settings/`，此条写进 §八「留给下一段的接口」。i18n 文案用 `profile` namespace（《i18n 规范》§4.3 已把 `profile` 定义为 `features/profile/` + `features/settings/` 两域共用，不新增第 15 个 namespace）。
2. **4 个选项**：`zh-Hans`（简体中文）/ `bo`（藏文）/ `ug-Arab-CN`（维吾尔文）/ `kk-Arab-CN`（哈萨克文）。**默认 `zh-Hans`**（导航树「语言选择浮层（默认简体中文）」）。
3. **切换流程**（这是 `0-A3` 的核心，四步缺一不可）：
   1. 写入持久化（键名由 B3 定，页面不自己拼键名）；
   2. 调 B3 的 `applyLocale(next)`，其内部负责 `I18nManager.forceRTL(isRtl)`；
   3. 调 `Updates.reloadAsync()` 软重启；
   4. 重启后，`app/_layout.tsx` 在**首帧之前**读持久化值并再 `forceRTL` 一次——**这一步不能省**：`I18nManager.isRTL` 是运行时状态，重启后必须按持久化的语言重新设置，否则只会「切换时镜像、重启后回弹」。
4. **`bo` 是 LTR**：藏文书写方向为从左到右，**不要因为它「是少数民族语言」就设成 RTL**。RTL 只有 `ug-Arab-CN` 与 `kk-Arab-CN` 两个。
5. **不做语言选择浮层**：总纲段 0 的交付物只列了「登录页」和「语言切换页」两个产品页，导航树里的「首次进入语言选择浮层」**段 0 不做**，首次进入直接用默认 `zh-Hans`。此条登记进「明确不做」，段 8 前处理。
6. **页面本身也要能在 RTL 下正确显示**：这是 RTL 的第一次自证——如果连语言切换页自己都镜像错了，说明 B3 的机制是坏的。

**产出**：
- `features/settings/pages/LanguagePage.tsx`
- `app/language.tsx`（路由壳）
- `features/settings/api/locale.ts`（读/写持久化，内部调 B3）
- 真机截图：切到 `ug-Arab-CN` 后整屏镜像；杀进程重启后再截一张

**算做完**：
- `0-A3` 全部现象成立：返回箭头在右、进度条反向、Tab 顺序镜像、`marginStart/End` 全部生效；
- `adb shell am force-stop com.faze.app` 后重新打开，**仍是 RTL**（不是回到 LTR）；
- 从 `ug-Arab-CN` 切回 `zh-Hans`，同样需要软重启且镜像恢复。

**卡住怎么办**：
1. `Updates.reloadAsync()` 在 dev-client 下不生效 → 段 0 的 `0-A3` 允许用「手动杀进程重启」代替软重启链路，但**必须在 §九 记偏差**，且软重启链路要在段 8 前补通（RTL 是用户可感知的，不能靠杀进程）。
2. `I18nManager.forceRTL()` 报「already set」→ 这是 RN 的已知行为，重启后第一次设置才生效；按第 3 步的顺序做，不要在前台反复调。
3. 切到 `bo` 后页面变 RTL → 说明第 4 步被写成了「非中文即 RTL」，改回按语言白名单判定。


### 第 4 组　D 三项探针

> **组间检查点**：D 组开工前置 = B 组 4 个任务全部收口（`0-A7` 的机器门禁已能跑）+ A 组的服务端骨架与 `/api/v1/pub/ping` 已能返回。D1 可与 C 组并行，D2/D3 必须在 D1 拿到真机之后。
> **三项探针的共性**：它们验的是「能不能」，不是「好不好」。**不验回答质量、不验 UI 美观、不验并发。**

#### 任务 D1：APK 构建 + 真机安装

**做什么**：
产出一个能装到真实 Android 手机的包并装上去，同时把「下次换机器怎么再做一个」写成文档。**顺带在真机上验一次 RTL 软重启与持久化**（`0-A3` 的设备侧证据只能在这里拿）。

**怎么做**：

1. **主通道顺序（裁决，不是选项）**：
   | 顺位 | 通道 | 为什么在这个位置 |
   |---|---|---|
   | ① | **EAS 云构建**（`preview` profile，`buildType: apk`） | 本机**没有 Android SDK**（实测：`ANDROID_HOME`/`ANDROID_SDK_ROOT` 均未设、`%LOCALAPPDATA%\Android\Sdk` 不存在、无 build-tools/platforms/ndk/cmdline-tools，只有 winget 装的 `adb`）。云构建只依赖 Expo 账号 + 网络，是唯一不需要改本机环境的路径 |
   | ② | 本机装完整 SDK 后本地构建 | 备选。成本：cmdline-tools + platforms 36 + build-tools 36 + NDK（合计约 10 GB 下载，判断非实测），且 RN 0.81 对 compileSdk/NDK 版本有对齐要求。①失败才上 |
   | ③ | `expo-dev-client` 内测分发 | **最后**。dev-client 包启动要连 Metro，断网必然连不上 → 直接污染 `0-A8`；它也不能证明「可安装」（它证明的是「能连开发服务器」） |

2. **`eas.json` 内容要点**（新建 `apps/mobile-app/eas.json`；仓库实测**没有** `eas.json`）
   ```json
   {
     "cli": { "version": ">= 16.0.0", "appVersionSource": "local" },
     "build": {
       "development": {
         "developmentClient": true,
         "distribution": "internal",
         "android": { "buildType": "apk" },
         "channel": "development"
       },
       "preview": {
         "distribution": "internal",
         "android": { "buildType": "apk" },
         "channel": "preview",
         "env": { "EXPO_PUBLIC_API_BASE_URL": "http://<PC-局域网IP>:8080" }
       },
       "production": {
         "android": { "buildType": "app-bundle" },
         "channel": "production"
       }
     }
   }
   ```
   - `buildType: "apk"` 是 internal distribution 出 APK 的关键键；不加会得到 `.aab`，手机装不上。
   - `production` 段现在只占位（段 0 不构建 production），但要写清 `buildType: app-bundle`，避免段 8 有人拿 preview 的 apk 当上架产物。
   - `env.EXPO_PUBLIC_API_BASE_URL` 必须写死局域网 IP——它是构建期内联的，**不能运行时改**。换网段就要重建，这条写进构建文档。

3. **`expo-dev-client` 要不要装：装，但不作为验收载体。**
   - 装：`npx expo install expo-dev-client`。用途仅限 `development` profile（现场调试、`adb logcat` 折腾）。
   - 不作为 `0-A1`/`0-A4`/`0-A5` 的验收载体：dev-client 包脱网跑不起来，`0-A8` 的判定会被污染；且 `0-A1` 要的是「装上能启动」，release 形态的 JS bundle 才代表真实用户的启动路径。
   - **裁定**：`0-A1` 存证的截图必须来自 `preview` 包的安装与启动，`development` 包的截图只能作为「调试过程」附在 `docs/acceptance/seg0/D1/` 的附录里并标注。

4. **首次构建需要什么（分清「必需」与「不需要」）**
   - 必需：一个 **Expo 账号**。命令统一用 `npx eas-cli@latest <子命令>`（不装全局、也不写 `npx eas`——`eas` 是另一个包名，会装错东西）；`npx eas-cli@latest login` 登录，`npx eas-cli@latest init` 建 project 并把 `projectId` 写进 `app.config.ts` 的 `extra.eas.projectId`。
   - 必需：**首次构建时 EAS 会问「Generate a new Android Keystore?」→ 选 Yes**（由 EAS 托管，keystore 留在 EAS 服务器；`.gitignore` 已忽略 `*.jks`/`*.keystore`，永远不入库）。
   - 不需要：Apple 账号 / iOS 证书（`0-A1` 明确不做 iOS）、Google Play Console、本地 keystore 文件。
   - **额度**：EAS 免费档有月度构建次数与排队限制，具体额度以账号套餐为准——**未实测，待确认**（进第四节）。当前队列时长同为判断，不写进验收判据。

5. **构建产物怎么落到 `docs/acceptance/seg0/`——这里有个硬冲突，必须先解决**
   - 实测 `.gitignore` 含 `*.apk`、`*.aab`、`*.log`、`tmp/`；`AGENTS.md` §5 明确「禁止提交构建产物」。
   - **所以 APK 与构建日志不能进 `docs/acceptance/`**。入库的只能是「证据指针」：
     ```
     docs/acceptance/seg0/D1/
     ├─ README.md           buildId / buildUrl / git commit / APK SHA256 / 设备型号 / 安装时间
     ├─ eas-build.json      npx eas-cli@latest build:view <buildId> --json 的完整输出（.json 不被忽略）
     ├─ install-<型号>.png  真机桌面/应用内的安装与启动截图
     └─ logcat-startup.txt  改名避开 *.log；只保留启动 30 秒，去敏后入库
     ```
   - APK 本体留在 `E:\dshcode\tmp\seg0\apk\faze-<gitsha>.apk`（`tmp/` 被忽略，符合预期），并在 `README.md` 里记 `Get-FileHash` 的 SHA256，供他人核对手上那个包是不是同一个。
   - **裁定：不接受「把 APK 提交进仓库」这条捷径**（会让仓库每次构建涨 60~100 MB，且违反 AGENTS §5）。

6. **真机安装与联调**
   ```powershell
   & "C:\Users\14541\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_*\platform-tools\adb.exe" devices
   #   必须看到 device 状态（不是 offline / unauthorized）
   adb install -r .\faze-<gitsha>.apk
   adb shell pm list packages | Select-String com.faze.app
   adb logcat -c; adb logcat ReactNativeJS:V AndroidRuntime:E *:S
   ```
   - baseURL 用 PC 局域网 IPv4（`ipconfig` 取，避开 VMware/WSL 的 172.x 虚拟网卡）；防火墙放行 8080；先在手机浏览器打开 `http://<PC-IP>:8080/api/v1/pub/ping` 确认通。
   - 装完先验 `0-A3` 的设备侧证据：切 `ug-Arab-CN` → 杀进程（`adb shell am force-stop com.faze.app`）→ 重开 → **仍是 RTL**。同时记录 `reloadAsync` 的实际表现（成功/抛错/不生效）。

**产出**：
- `apps/mobile-app/eas.json`（新建，3 个 profile）
- `apps/mobile-app/app.config.ts` 的 `extra.eas.projectId`、`runtimeVersion`、`updates` 段
- `apps/mobile-app/package.json` 新增 `expo-dev-client`、`expo-updates`
- `docs/standards/构建路径.md`（新建：账号、`eas-cli login`/`init`/`build -p android --profile preview`、keystore 由谁托管、换机器步骤、baseURL 改法、失败退出顺序）
- `docs/acceptance/seg0/D1/{README.md,eas-build.json,install-*.png,logcat-startup.txt}`
- `E:\dshcode\tmp\seg0\apk\faze-<gitsha>.apk`（不入库）

**算做完**：
- [ ] `adb shell pm list packages` 输出里有 `package:com.faze.app`
- [ ] 真机点图标能进登录页，`logcat` 无 `FATAL EXCEPTION`（启动 30 秒内）
- [ ] `docs/acceptance/seg0/D1/README.md` 里 buildId、buildUrl、git commit、APK SHA256、设备型号**五项齐全**，且 SHA256 与本地 APK 的 `Get-FileHash` 一致
- [ ] 装的是 `preview` 包（`eas-build.json` 里 profile 字段为 `preview`），不是 dev-client 包
- [ ] 切 `ug-Arab-CN` → `am force-stop` → 重开仍为 RTL（截图 ×2：切换后、重启后）
- [ ] `reloadAsync` 的实际结果被写成一句话结论（可用/不可用/需手动重启），落在 `docs/standards/构建路径.md`
- [ ] 另一个人只照 `docs/standards/构建路径.md`（不看聊天记录）能独立做完一次构建（由第二人实测确认）

**卡住怎么办**：
1. `eas` 连不上/超时 → 本机网络需代理（`scripts/fetch-arabic-font.py` 里已有 `PROXY = http://127.0.0.1:7890` 的先例）：`$env:HTTPS_PROXY='http://127.0.0.1:7890'; $env:HTTP_PROXY=$env:HTTPS_PROXY` 后重试。
2. 账号/额度不可用 → 走通道 ②：装 cmdline-tools，`sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;27.1.12297006"`（版本按 RN 0.81 的要求核对，写进构建文档），然后 `npx expo run:android --variant release` 或 `npx eas-cli@latest build -p android --profile preview --local`。
3. EAS 构建成功但手机装不上（`INSTALL_FAILED_*`）→ 先 `adb uninstall com.faze.app` 再装；`INSTALL_FAILED_UPDATE_INCOMPATIBLE` 是签名不同（之前装过别的包），卸载即可，**不要去换 keystore**。
4. 装上白屏 → 九成是 baseURL 内联成了 `localhost`，或 JS bundle 崩了；`adb logcat ReactNativeJS:V` 看第一条红字；重新用正确 IP 构建。
5. `reloadAsync` 不可用 → **不阻塞 D1**（`0-A3` 硬判据是杀进程后的持久化）。记结论、写限制、继续。若连持久化也不成立 → 升级主理人（RTL 方案要重设计）。
6. 三条通道全不通 → 触发总纲 §5.1 风险表最后一条：**整个分期体系必须重设计**，这不是工程侧能自己决定的事，报主理人与负责人。

---

#### 任务 D2：藏文渲染

**做什么**：
证明藏文能在真机上正确排版（无豆腐块、无断笔、叠字正确），并把字体选型结论写进 `docs/standards/`。

**怎么做**：

1. **字体来源（开源可商用）**
   - 选 **Noto Sans Tibetan**（Noto 家族，SIL Open Font License 1.1：允许商用、允许嵌入 App、允许再分发；限制是不得单独售卖字体本身、不得用保留字体名发布修改版）。许可证文件 `OFL.txt` 必须随包分发。
   - **要 `.ttf` 或 `.otf`，不要 `.woff2`**：`expo-font` 在原生端不支持 woff2。仓库里 `design/ui/fonts/noto-naskh-arabic.woff2` 是给 HTML 设计稿用的，不能直接搬进 `assets/fonts/`。
   - 落地路径：`apps/mobile-app/assets/fonts/NotoSansTibetan-Regular.ttf` + 同目录 `OFL-NotoSansTibetan.txt`。
   - 取字体用脚本（本机网络要走代理）：新增 `scripts/fetch-tibetan-font.py`，照 `scripts/fetch-arabic-font.py` 的结构写（它已经处理了代理、UA、OFL 下载）。
2. **覆盖校验（客观证据，不靠肉眼）**
   - 新增 `scripts/verify-tibetan-font.py`，照 `scripts/verify-arabic-font.py` 用 `fontTools` 读 cmap 与 GSUB：
     - 输出样本字符串用到的全部码位的 missing 列表（必须为空）
     - 输出 GSUB 的 feature 标签列表（藏文的叠字/上加/下加/后加替换都在 GSUB 里）
   - **前置缺口：本机 `python -c "import fontTools"` 实测 `ModuleNotFoundError`**（Python 3.14.5，`fontTools` 未安装）。所以 D2 的第一步是 `pip install fonttools`，并把这个前置写进脚本头注释与构建文档。
3. **`expo-font` 加载方式**
   - `app/_layout.tsx`：`const [loaded, error] = useFonts({ NotoSansTibetan: require('@/assets/fonts/NotoSansTibetan-Regular.ttf'), NotoSansArabic: require('@/assets/fonts/NotoSansArabic-Regular.ttf') })`
   - `SplashScreen.preventAutoHideAsync()` 在模块顶层调用；`loaded || error` 后 `SplashScreen.hideAsync()`；未加载完不渲染子树（否则首帧闪错字形，技术路线 §13.3 步骤 2）。
   - `fontFamily` 命名：Android 写**文件名**（无扩展名）`NotoSansTibetan`；iOS 写 PostScript 名（段 0 不做 iOS，但注释要写清，避免 iOS 期踩坑——§13.3 步骤 7 明确「写错静默 fallback」）。
   - **不做子集化**：藏文的 GSUB 引用复合字形，裁错即崩排（§13.3 步骤 4）。这条要写进 `docs/standards/字体选型.md`，防止后续有人为了减包体去裁字体。
4. **验证样本（写死在 `/dev/rtl` 页，不走 `t()`，避免译文干扰）**
   | # | 样本 | 验什么 |
   |---|---|---|
   | 1 | `བསྒྲུབས` | 多字素簇：叠字 + 下加字 + 元音符号同时出现 |
   | 2 | `བོད` | 短字素簇、元音符号位置 |
   | 3 | `《民法典》第 12 条 — བོད་ཡིག` | 藏汉混排、标点与数字在藏文旁的位置 |
   | 4 | `བོད་ཡིག 第 12 条 民法典` | 反向混排（藏文在前） |
   | 5 | 一行 ≥40 个字素的藏文长句 | 换行处不断笔、行高不裁字形 |
5. **判定标准（肉眼可判，同时给客观证据）**
   - **无豆腐块**：不出现 `□`、空白、`�`、方框占位。
   - **无断笔**：字母主干与上下加字的笔画连续，无缺笔、无错位。
   - **叠字正确**：上加字/下加字不互相覆盖、不压基线、不丢元音符号。
   - **行高不裁**：`lineHeight ≥ 1.6 × fontSize`（i18n 规范 §7.2）；混排取两字体最大行高。
   - **对照法**（把「观感」变成「可比对」）：用 `design/ui` 的 HTML 在桌面浏览器里用同一字体渲染同 5 个样本（浏览器 shaping 是可靠的参照），截图后与真机截图**并排**放进存证目录。差异用肉眼一扫就能看出是「字体问题」还是「RN 排版问题」。
   - **放大 4 倍**：真机截图后用本地无损切块放大（不改原图）再拍一张局部，用来判「断笔」——正常字号下断笔看不出来。
6. **存证**：`docs/acceptance/seg0/D2/{tibetan-probe-<型号>.png, tibetan-probe-zoom4x.png, tibetan-font-report.json, desktop-reference.png}`。

**产出**：
- `apps/mobile-app/assets/fonts/{NotoSansTibetan-Regular.ttf, OFL-NotoSansTibetan.txt}`（阿拉伯系字体同批落地：`NotoSansArabic-Regular.ttf` + OFL）
- `scripts/{fetch-tibetan-font.py, verify-tibetan-font.py}`
- `apps/mobile-app/features/dev/pages/RtlProbePage.tsx`（5 个样本 + 静态 Tab 样本，见下）
- `docs/standards/字体选型.md`（新建：字体名、版本、来源 URL、许可证、为何不做子集化、`fontFamily` 命名规则、覆盖校验方法）
- `docs/acceptance/seg0/D2/*`
- `/dev/rtl` 页内的**静态 Tab 样本**：一排 5 个「首页/律师/智能体/案件/我的」的假 Tab（纯 `View`，不做导航），用于执行 `0-A3` 里「Tab 顺序镜像」这一判据——**段 0 没有真 Tab**（业务页全删），这是该判据在段 0 的唯一可执行载体。

**算做完**：
- [ ] `adb` 截图里 5 个样本**全部**无豆腐块（人可判）
- [ ] `tibetan-font-report.json` 里 5 个样本用到的全部码位 `missing = 0`
- [ ] GSUB feature 列表里叠字相关的替换特征存在（脚本输出里能看到，具体标签以脚本读出的实际字体为准，不预设）
- [ ] 放大 4 倍局部图里笔画连续、无断笔
- [ ] 混排样本（3、4）在藏文与数字之间的顺序正确、数字没被镜像
- [ ] `docs/standards/字体选型.md` 存在且写明许可证与「不做子集化」
- [ ] `assets/fonts/` 下的 `OFL*.txt` 与字体文件同批提交
- [ ] 首次启动没有「先闪系统字体再变藏文字体」的中间帧（录屏可判）

**卡住怎么办**：
1. 脚本报 `missing > 0`（字形不全）→ 换 **Noto Serif Tibetan**（同 OFL）→ 仍不全 → 试系统字体（Android 13+ 是否内置藏文字体**未实测**）→ 都不行则升级：`AGENTS.md` 与总纲 §5.1 都写明「藏文用图片资源或放弃 bo」是**产品范围决策，必须负责人拍板**，技术侧不许自行降级。
2. 字形能显示但「断笔/叠字错」→ 九成是 shaping 没生效：检查是不是给整段设了一个 `fontFamily`（必须经 `LocalizedText` 逐 run 切，技术路线 §13.3）；确认没用 `letterSpacing`（阿拉伯系必须 0，藏文也建议 0）。
3. 完全看不出字体有没有加载（系统字体也能显示藏文）→ 做一次「反证」：临时把 `fontFamily` 改成一个不存在的名字，如果显示完全不变，说明**从未加载成功**（§13.3 步骤 7：写错静默 fallback）。这是最快的二分法。
4. 真机上字体加载导致首屏白屏 → `useFonts` 的 error 分支没处理；`error` 时也要 `hideAsync()` 并降级到系统字体 + 在 `/dev/rtl` 页显式报错，**不许白屏**（与 `0-A8` 同一原则）。
5. `pip install fonttools` 失败（Python 3.14 太新/无 wheel）→ 用 `py -3.12` 或直接跳过客观证据，改为「仅肉眼证据 + 在验收记录里注明客观证据缺失」。**不许假装跑过脚本。**

---

#### 任务 D3：豆包 SSE + ngram 命中

**做什么**：
打掉第三个一票否决风险：服务端能调豆包 SSE 并出首字节、`law_item` 的 ngram 检索能命中至少 1 条并给出**命中依据**、真机能逐段看到流式出字。

**怎么做**：

1. **后端两个探针**（补在 A 组的 `services/faze-api` 骨架上；实测仓库当前**没有** `services/` 目录）
   - 落点：`services/faze-api/src/main/java/com/faze/modules/probe/{ProbeController,ProbeService,DoubaoClient}.java`
   - **`@Profile("!prod")` 加在 `@RestController` 上**（总纲 §5.1 + 接口规范 §3.1 裁决 1：探针只在非生产注册）。验收要有一条「production profile 下返回 404」的证据，否则「仅非生产注册」只是口头承诺。
   - `POST /api/v1/pub/probe/ai`：`produces = MediaType.TEXT_EVENT_STREAM_VALUE`；用 `SseEmitter`（或直接写 `HttpServletResponse` 输出流并每块 `flush()`）把上游 SSE **逐块透传**，不做聚合（聚合了就验不出「流式」）。
   - 上游：火山方舟（Ark）的 OpenAI 兼容端点 `https://ark.cn-beijing.volces.com/api/v3/chat/completions`，body 至少 `{ "model": "<endpointId>", "stream": true, "messages": [...] }`。**`model` 是控制台创建的推理接入点 ID（形如 `ep-xxxxxxxx`），不是模型名字**——这一条必须写进配置说明，用错会 404。
   - **key 只从环境变量 `FAZE_DOUBAO_API_KEY` 取，禁进仓库**（`AGENTS.md` §4.8）；`.env.example` 里只写变量名。
   - **首字节度量（服务端侧）**：拿到上游第一个数据块时算 `System.nanoTime()` 差值，写进响应头 `X-First-Byte-Ms`，并在流末尾发一条 `event: meta` 带上同一数值。客户端也测一次（见下），两次互证——只看客户端测不出「是服务端慢还是网络慢」。
   - **兜底不许冒充**：`faze.probe.ai.mock=true` 时返回固定分段文本（每 60ms 一块），仅用于链路自检。**mock 状态下 `0-A5` 记「未通过」**，不许拿 mock 当豆包。

2. **`GET /api/v1/pub/probe/law?kw=` 与「命中依据」**
   ```sql
   SELECT id, law_code, law_name, article_no, content,
          MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) AS score
   FROM law_item
   WHERE MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE)
   ORDER BY score DESC
   LIMIT 5
   ```
   - 依赖 `FULLTEXT KEY ft_law_content (content) WITH PARSER ngram`（技术路线 L1649 已定义 `law_item` 时带了这一行）。
   - 返回 `{ hits: [{ id, lawCode, articleNo, snippet, score, matchedTerms }] }`（接口规范 §7.1 的 `{id,lawCode,articleNo,snippet,score}` **加一个 `matchedTerms`**——「命中依据」需要一个字段承载，不加这个字段就只剩一个 `score` 数字，验收时人看不出「凭什么命中」）。
   - `matchedTerms` 的算法：用与 MySQL **同口径**的 ngram 切分（`ngram_token_size`，默认 2）把 `kw` 切成 token，再筛出在 `content` 里实际出现的那些。**必须与服务端的 `ngram_token_size` 一致**，否则「依据」和「命中」对不上。
   - `ngram_token_size` 是 MySQL 的**只读启动变量**：要改只能改 `my.ini` 后重启 MySQL80 服务（由 A 组的 `deploy/start-services.ps1` 管，D3 不单独重启服务）。默认 2 时，`kw` 少于 2 字必然 0 命中——这不是 bug，是分词器行为，写进文档。
   - **法条数据（5 条 vs 50 条，两处文档打架，裁决如下）**：
     - 《数据表规范》§8.4 裁决：`V2__init_law_items.sql` **只放 5 条**（最小必要、不可变、进 Flyway）；
     - 总纲 §5.1 风险出口：「本地没有 `law_item` 数据 → 先用 **50 条**手工录入的条文做探针」。
     - **两条不冲突，分工执行**：`V2__init_law_items.sql` 保持 5 条（含探针必须能命中的那几条）；探针用的 50 条放 `database/seed/law_items_probe.sql`，**不进 Flyway**、可重复执行（`INSERT ... ON DUPLICATE KEY UPDATE`，靠 `uk_law_article(law_code, article_no, effective_date)` 去重）。理由与 §8.4 的裁决一致：迁移不可变，种子天然要迭代。
   - 关键词样本：至少 3 组必须命中（如「借款」「民间借贷」「利息」），并留 1 组**故意命不中**（验空结果不崩、返回空数组而不是 500）。

3. **前端怎么收流（关键：RN 的 `fetch` 不支持流式，这一条接口规范写错了）**
   - **实测事实（本仓证据，不是记忆）**：RN 0.81 的全局 `fetch` 来自 `whatwg-fetch`——`node_modules/react-native/Libraries/Network/fetch.js` 第 17 行 `require('whatwg-fetch')`（把 fetch/Headers/Request/Response 挂到全局），而 `node_modules/whatwg-fetch/fetch.js` 第 534 行 `var xhr = new XMLHttpRequest()`，**整条链路建在 XHR 上，没有 `ReadableStream`**，所以 `response.body` 是 `undefined`、没有 `getReader()`。接口规范 §6.7 写的「RN 的 `fetch` 支持 `response.body.getReader()`」是**未经验证的推测**，照它实现会拿到 `undefined`。
   - **`EventSource` 也不行**：它不能带自定义头（token 只能进 URL，违反 §6.1「禁止 token 放 URL query」），而且我们的探针是 `POST`，`EventSource` 只支持 GET。
   - **主选方案：`expo/fetch`**（无需新增依赖，已实测本仓存在）
     - 实测证据：`apps/mobile-app/node_modules/expo@54.0.38` 内有 `expo/fetch.js`/`fetch.d.ts`，其实现 `node_modules/expo/src/winter/fetch/FetchResponse.ts` 第 24 行 `get body(): ReadableStream<Uint8Array<ArrayBuffer>> | null`，数据由原生模块 `ExpoFetchModule` 通过 `didReceiveResponseData` 逐块推给 JS。
     - 用法轮廓（**不给整段实现代码，只给结构**）：`import { fetch } from 'expo/fetch'` → `const reader = res.body!.getReader()` → `TextDecoder` 解码 → 按 `\n\n` 切事件、取 `data: ` 后的 JSON → `data: [DONE]` 结束。
     - 落点：`apps/mobile-app/shared/api/sse.ts`（**放 `shared/` 而不是 `features/dev/`**：段 3 的 AI 问答要复用同一套流式读取，放 features 里将来要搬）。
   - **备选方案：`react-native-sse`**（新增依赖，XHR 的 `onreadystatechange` 读 `responseText` 增量）。**只在 `expo/fetch` 在真机上不逐块回调时才启用**，且要登记到 `docs/architecture/依赖清单.md`。
   - **`TextDecoder` 的不确定性**：RN/Hermes 是否暴露全局 `TextDecoder` **未在本机实测**。先跑 `console.log(typeof TextDecoder)`；没有就用 `expo` 内部的实现（`node_modules/expo/src/winter/TextDecoder.ts` 存在）或手写 UTF-8 解码。这一条进第四节。
   - **首字节度量（客户端侧）**：`const t0 = Date.now()`（点按钮那一刻）→ 收到第一块非空 `data:` 那一刻 → 差值。页面显示「首字节 XXXms / 服务端 XXXms（响应头）」。**连续 3 次取中位数**写进截图，单次抖动不作数。

4. **`/dev/probe` 页要显示什么（验收人要能一眼看完）**
   - 当前 `baseURL`、`platform`、`appVersion`（证明打到哪台机器）
   - ping 结果（`appName/version/serverTime/env`）——这是 `0-A2`「数据非硬编码」的证据落点（段 0 没有首页，见第四节）
   - AI 流的**逐段文本** + 首字节毫秒数（客户端/服务端两个数）
   - law 检索的 `kw` 输入框、命中条数、每条的 `articleNo` / `score` / `matchedTerms`

**产出**：
- `services/faze-api/src/main/java/com/faze/modules/probe/{ProbeController,ProbeService,DoubaoClient}.java`
- `services/faze-api/src/main/resources/application-dev.yml` 的 `faze.probe.*`（`mock`、`timeout`）；`.env.example` 加 `FAZE_DOUBAO_API_KEY`
- `database/seed/law_items_probe.sql`（50 条）+ `V2__init_law_items.sql`（5 条，A 组做）
- `apps/mobile-app/features/dev/pages/ProbePage.tsx`、`app/dev/probe.tsx`
- `apps/mobile-app/shared/api/sse.ts`
- `docs/acceptance/seg0/D3/{ai-first-byte-<型号>.png, law-hit.png, probe-log.json}`
- `docs/standards/探针与流式约定.md`（新建：SSE 读取的唯一入口、首字节口径、ngram 口径、`@Profile("!prod")` 规则）

**算做完**：
- [ ] 真机上点「开始」后，文字**逐段增加**（录屏 ≥3 秒可判），不是一次性出现
- [ ] 首字节中位数 **≤ 5000ms**（页面显示的数字 + 截图；客户端与服务端两个数都记）
- [ ] `GET /api/v1/pub/probe/law?kw=借款` 返回 `hits.length ≥ 1`，每条带 `articleNo`、`snippet`、`score`、`matchedTerms`，且 `matchedTerms` 非空
- [ ] 故意命不中的 `kw` 返回 `{hits: []}`，HTTP 200，不是 500
- [ ] 以 `prod` profile 启动服务端后，`GET /api/v1/pub/probe/law` 返回 404（`@Profile("!prod")` 生效）
- [ ] `grep -rn "FAZE_DOUBAO_API_KEY"` 只命中 `.env.example` 与 yml 里的 `${FAZE_DOUBAO_API_KEY}` 占位，**仓库内无明文 key**
- [ ] `sse.ts` 是唯一读取流的地方（`getReader` 只在这个文件里出现）

**卡住怎么办**：
1. 豆包 key / endpoint id 未就绪 → 开 `faze.probe.ai.mock=true` 验链路（`0-A5` 记「未通过」并挂账），**不许拿 mock 冒充**。同时把「key 与 `ep-` 接入点 ID 由谁提供」写成待确认（第四节）。
2. 首字节 > 5s → 先分责：看响应头 `X-First-Byte-Ms`。服务端自己就 > 5s → 上游慢（换推理接入点/降 `max_tokens`/换 region/换模型）；服务端 < 5s 而客户端 > 5s → 网络与首包（换 Wi-Fi/4G、关代理、确认没走 172.x 虚拟网卡）。
3. `expo/fetch` 在真机一次性吐完（没有逐块）→ 切 `react-native-sse`（记得登记依赖与说明理由）。若两者都不行 → 退回「轮询分段接口」只能证明「能出字」不能证明「流式」，**`0-A5` 记未通过**，报主理人（总纲把它列为一票否决风险）。
4. `TextDecoder` 不存在 → 用 `expo` 内部实现或手写解码；**不要在 Hermes 里引 `buffer` 包**（体积与兼容性都不值）。
5. ngram 命中 0 条 → 按序查四项：`SHOW VARIABLES LIKE 'ngram_token_size'`（默认 2，`kw` 必须 ≥2 字）；`EXPLAIN` 是否走了 `ft_law_content`；`law_item` 是否有数据（跑 seed）；`content` 的 `COLLATE` 是否 utf8mb4（ngram 对非 utf8mb4 行为异常）。
6. `PROBE` 接口在真机 404 → 服务端 profile 是 `prod` 或没配 `dev`；探针只在 `!prod` 注册，先用 `curl http://localhost:8080/api/v1/pub/ping` 确认服务端的 active profile。

---
## 四、本段验收锚点（`0-A1` ~ `0-A8` 逐条展开成可执行验收）

> **本节的位置**：对应总纲 §5.1「验收锚点」表。总纲给的是 8 行「怎么验 / 通过标准」，本节把它展开成**别人拿着就能执行、执行完能二值判定**的动作。
>
> **一条不许改的东西**：每条锚点下方「原通过标准」引号内是**总纲原文逐字照抄**（总纲 §十二 第 3 条：锚点只增不减，删改须负责人确认）。我只展开，不改标准。唯一的形式改动是给 `<Text>` 加了反引号，否则 Markdown 会把它当 HTML 标签吞掉。
>
> **一条必须记住的东西**：本节里有 **1 条锚点按当前机器状态必然过不了**（`0-A6`：本机没有 Redis，也没有 docker），另有 **4 条当前状态不满足**（`0-A1` 缺 `eas.json` / `projectId` / 真机，`0-A2` 缺 `services/` 与 `client.ts`，`0-A5` 缺 `services/` 与 `law_item` 数据，`0-A7` 两条 lint 规则未实现且存量未清）。我在对应锚点下如实标注「当前不满足」，**没有擅自改标准**。改锚点须负责人书面确认（总纲 §6.4）。

---

### 4.0 每条锚点都适用的八条统一口径（先读这段，否则后面的判定会被自己绕进去）

| # | 口径 | 具体 |
|---|---|---|
| 1 | **二值，不许「基本通过」** | 每条只有一个结论：`通过` / `不通过`。一条锚点内含多个子项（如 `0-A3` 四组现象）时，任一子项不过 = 整条不过，且必须在证据里写明**是哪一子项**。 |
| 2 | **真机口径** | 凡是要「看屏幕」的判定，必须在**物理 Android 设备**上完成；模拟器/云真机截图一律无效（总纲 §6.3「只在模拟器验证过的功能不许带进下一段」）。真假机识别命令见 `0-A1` 步骤 6。 |
| 3 | **对照实验，单张截图不算证据** | 凡是「改前 / 改后」类结论（`0-A2` 的非硬编码、`0-A3` 的镜像、`0-A8` 的恢复），改前与改后**都必须有证据**，且两处证据的文件名带同一个时间戳批次。只有「改后」= 无法排除「本来就这样」。 |
| 4 | **时间戳统一格式** | `yyyyMMdd-HHmmss`（本机时区 Asia/Shanghai）。生成命令：`Get-Date -Format 'yyyyMMdd-HHmmss'`。命令里凡是写 `<ts>` 的地方都替换成它。 |
| 5 | **命令的默认工作目录** | `E:\dshcode\FAZE`（除显式 `cd` 的步骤）。PowerShell 命令直接可复制。 |
| 6 | **多设备必须指定序列号** | 本机 `adb devices -l` 当前**为空**（2026-10-31 实测），接上手机后先把 serial 记进 `_meta.md`；命令里的 `<serial>` 一律替换成真实 serial，不许省成裸 `adb`（多设备时裸 `adb` 直接报错或选错设备）。 |
| 7 | **证据文件后缀避开 `.log`** | 仓库 `.gitignore` 里有 `*.log`（实测），用 `.log` 后缀的证据**进不了 git**。本节统一用 `.txt` 存命令输出；只有确需保留原样的构建日志才用 `.log`，并在 `docs/acceptance/seg0/.gitignore` 里加例外。 |
| 8 | **每条锚点一个证据目录** | `docs/acceptance/seg0/0-An/`，目录内必须有 `_meta.md`（字段见 §4.9）。缺 `_meta.md` 的锚点按「未验」处理。 |

**另外一条总纲要求，容易被漏**：`0-A1`~`0-A8` 全部通过后还要过「出口三件」（总纲 §5.1「出口」）：APK 构建路径文档化、字体选型结论进 `docs/standards/`、探针截图存档。**这三件不是锚点，但没过就不算收口**（总纲 §6.2 收口五件套）。

---

### 4.1 `0-A1` 可安装（原通过标准：安装成功、能启动、不闪退；把构建日志与安装截图存证）

**验前准备**

| 前置 | 当前状态（2026-10-31 实测） | 不满足怎么办 |
|---|---|---|
| EAS 账号已登录 | ✅ **已登录**：`eas whoami` 输出 `1454132yqm`（Owner），`eas-cli 22.0.0` 全局可用 | — |
| `apps/mobile-app/eas.json` 存在且含 `preview` profile（`android.buildType = apk`） | ❌ **不存在** | 由任务 D1 产出。本条验收只检查它存在且关键字段对，不检查内容写法 |
| `app.json` 的 `extra.eas.projectId` 已绑定 | ❌ **不存在** | 首次需 `eas init`（在 `apps/mobile-app` 下） |
| 真机已连 | ❌ `adb devices -l` 当前为空 | 插线 → 手机开「开发者选项 + USB 调试」→ 手机上点「允许」→ 再跑一次 |
| 本地 Android SDK | ❌ `ANDROID_HOME` 未设、无 build-tools/platforms（实测只装了 winget 的 platform-tools，即 adb） | **本地构建通道不可用，只走 EAS 云构建**（总纲 §5.1 风险表出口 ①） |
| 网络可达 expo.dev | 未测 | 无网 = 本条阻塞，记挂账 |

**操作步骤**

1. 确认账号（预期输出账号名）：
```powershell
eas whoami
```
2. 确认构建配置存在：
```powershell
cd E:\dshcode\FAZE\apps\mobile-app
Get-Content .\eas.json -Raw
```
   本条只要求：存在、有 `build.preview` 段、`build.preview.android.buildType` 为 `"apk"`（否则 EAS 默认出 `.aab`，`adb install` 装不上）。
3. 建证据目录并提交云构建：
```powershell
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A1'
New-Item -ItemType Directory -Force -Path $out | Out-Null
cd E:\dshcode\FAZE\apps\mobile-app
eas build -p android --profile preview 2>&1 | Tee-Object -FilePath "$out\0-A1_eas-build-$ts.txt"
```
   - 想无人值守跑（CI 用）：加 `--non-interactive`，但需环境变量 `EXPO_TOKEN`（本机未设）。
   - 记下日志末尾的 build id（形如 `https://expo.dev/accounts/<owner>/projects/faze/builds/<uuid>`）。
4. 取构建详情与产物地址：
```powershell
eas build:view <build-id> --json | Tee-Object -FilePath "$out\0-A1_build-view-<build-id>.json"
```
   从 JSON 里的 `artifacts.buildUrl` 下载 `.apk`（该链接需登录态；用浏览器下载后手动移到证据目录），命名 `0-A1_<ts>_preview.apk`。同时把构建详情页的完整日志另存为 `0-A1_eas-build-full-<build-id>.txt`（EAS 网页上「View build details → Logs」）。
5. 记录 APK 指纹（后续所有「装的是不是这一版」都靠它）：
```powershell
Get-FileHash "$out\0-A1_$($ts)_preview.apk" -Algorithm SHA256 | Tee-Object -FilePath "$out\0-A1_apk-sha256-$ts.txt"
```
6. **确认装的是真机不是模拟器**（这一步是 `0-A1` 最容易自欺的地方）：
```powershell
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.product.manufacturer
adb shell getprop ro.kernel.qemu       # 模拟器通常为 1；真机为空或 0
adb shell getprop ro.hardware          # 模拟器常见 goldfish / ranchu
adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id
adb shell wm size
```
   全部输出存 `0-A1_device-props-$ts.txt`。
   **判为模拟器的三种特征**（命中任一 → 本条不通过）：`ro.kernel.qemu` = `1`；`ro.hardware` ∈ {`goldfish`,`ranchu`}；`ro.product.model` 含 `sdk`、`Emulator`、`Android SDK built for`。
7. 安装：
```powershell
adb -s <serial> install -r "$out\0-A1_$($ts)_preview.apk" 2>&1 | Tee-Object -FilePath "$out\0-A1_install-$ts.txt"
```
   期望末行 `Success`。若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：先 `adb -s <serial> uninstall com.faze.app`，再装，并在 `_meta.md` 记「因签名不同卸载过一次」。
8. 启动并观察（activity 名不许猜，先解析出来）：
```powershell
adb -s <serial> logcat -c
adb -s <serial> shell cmd package resolve-activity --brief com.faze.app
adb -s <serial> shell am force-stop com.faze.app
adb -s <serial> shell monkey -p com.faze.app -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 30
adb -s <serial> shell pidof com.faze.app
adb -s <serial> logcat -d | Select-String -Pattern 'FATAL EXCEPTION' | Tee-Object -FilePath "$out\0-A1_logcat-$ts.txt"
adb -s <serial> exec-out screencap -p > "$out\0-A1_launch-$ts.png"
```
   若 `monkey` 启动失败，改用 `adb -s <serial> shell am start -n <package>/<activity>`（activity 取步骤 8 第二行的输出）。
9. 冷启动复验（排除「热启动正常、冷启动崩」）：
```powershell
adb -s <serial> shell am force-stop com.faze.app
adb -s <serial> shell pidof com.faze.app          # 期望为空 → 证明真被杀
adb -s <serial> shell monkey -p com.faze.app -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 30
adb -s <serial> shell pidof com.faze.app
adb -s <serial> exec-out screencap -p > "$out\0-A1_launch-cold-$ts.png"
```
10. 记录装的是哪一版：
```powershell
adb -s <serial> shell dumpsys package com.faze.app | Select-String -Pattern 'versionName','versionCode','lastUpdateTime','codePath','installerPackageName' | Tee-Object -FilePath "$out\0-A1_pkg-info-$ts.txt"
```

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过** | ① 步骤 7 输出含 `Success`；② 步骤 6 四条 getprop 证明是物理机（三条模拟器特征全未命中）；③ 步骤 8、9 两次启动后 `pidof` 都非空、且 30 秒后仍非空；④ 两次 `logcat -d` 均**无** `FATAL EXCEPTION` 行；⑤ 两张截图都不是纯色/白屏（能看见登录页或首屏任意文字）；⑥ `0-A1_apk-sha256` 与 `_meta.md` 记录的 SHA256 一致。 |
| **不通过（任一命中）** | ① 只在模拟器上装成功；② 装成功但首帧白屏且 30 秒无变化；③ `FATAL EXCEPTION` 出现一次（哪怕重启后能进）；④ 只有一张启动截图（无法区分冷/热启动）；⑤ 手工双击 APK 安装、没有 `adb install` 输出（无法证明装的就是那个 SHA256 文件）；⑥ `eas.json` 缺 `preview` 或 `buildType` 不是 `apk`，实际装的是 `.aab` 或 Expo Go。 |

**证据形式**（`docs/acceptance/seg0/0-A1/`）

| 文件 | 内容要求 |
|---|---|
| `_meta.md` | 见 §4.9 的必填字段（机型 / 系统 / ROM 构建号 / 包名版本 / EAS build id / commit / 时间 / 是否物理机 / 结论） |
| `0-A1_eas-build-<ts>.txt` | 完整构建命令回显 + 全量输出 + 末尾 build id URL |
| `0-A1_build-view-<build-id>.json` | `eas build:view --json` 原样 |
| `0-A1_eas-build-full-<build-id>.txt` | 网页端完整日志（含 Gradle 阶段与 APK 产出路径） |
| `0-A1_apk-sha256-<ts>.txt` | SHA256 + 文件名 + 下载 URL（APK 本体不入 git，见 §附 不确定项） |
| `0-A1_device-props-<ts>.txt` | 步骤 6 全部命令与输出 |
| `0-A1_install-<ts>.txt` | `adb install` 全量输出 |
| `0-A1_launch-<ts>.png` / `0-A1_launch-cold-<ts>.png` | 原始分辨率，不压缩不裁切 |
| `0-A1_pkg-info-<ts>.txt` | `dumpsys package` 的五个关键行 |
| `0-A1_logcat-<ts>.txt` | `FATAL EXCEPTION` 的搜索结果（空也要留文件，空文件本身是证据） |

**可能假通过**

1. **模拟器冒充真机**——最常见。跑 AVD 甚至云真机，截图一样好看。**防**：必须贴 `ro.kernel.qemu` / `ro.hardware` / `ro.product.model` 原始输出；机型串要能在网上查到是实体机型号。
2. **装的是上次遗留的旧 APK**：`dist/` 或桌面上的旧包也能「启动成功」。**防**：安装用的文件名带时间戳 + SHA256，并与 `dumpsys package` 的 `versionCode` / `lastUpdateTime` 对齐。
3. **在 Expo Go 里扫码跑通就宣称「可安装」**：那是 Expo Go 客户端在跑你的 JS，不是你的 APK；而且 `Updates.reloadAsync()` 在 Expo Go 里**不可用**（技术路线 §13.4 已写明），`0-A3` 也过不了。**防**：`adb shell pm list packages | Select-String faze` 必须只有 `com.faze.app`，且 `codePath` 指向 `/data/app/...com.faze.app...`。
4. **只看热启动**：内存里有进程，首帧崩溃被掩盖。**防**：步骤 9 的冷启动截图与 `pidof` 为空的那一步是必交件。
5. **构建日志只截最后一行 `Build successful`**：看不出 profile、build id、产物类型。**防**：日志必须含构建命令回显与 build id URL；`0-A1_eas-build-full-*.txt` 必须包含 Gradle 产 APK 的那段。

---

### 4.2 `0-A2` 真接口 + 401（原通过标准：首页数据非硬编码；失效后自动清 token 并回登录页，不白屏、不弹英文报错）

**验前准备**

| 前置 | 当前状态（实测） | 说明 |
|---|---|---|
| `services/faze-api` 可起 | ❌ 仓库**无 `services/` 目录** | 本条整体阻塞在 A3 组 |
| MySQL80 Running | ✅ 已满足（3306 监听） | — |
| `GET /api/v1/pub/ping` 可访问 | ❌ 未实现 | 接口规范 §7.1 冻结响应 `{appName, version, serverTime, env}` |
| 真实登录接口 | ⚠️ 契约已冻结（接口规范 附录 A.1.1 `POST /api/v1/pub/auth/login/password`），实现未做 | 段 0 的接口清单已由 §零之二 冲突 1 裁决为 6 个（含登录 + 刷新 + 一个需鉴权资源）；《接口规范》§7.1 待回写，见 §附 不确定项 #6 |
| 测试账号 | ⚠️ 段 0 **没有注册接口**，`app_user` 表本身是空的；§零之二 冲突 1 已定「`V2` 种子迁移插 1 个可登录的测试用户」 | 种子数据算不算总纲 §6.3 禁带的「硬编码数据」、密码强度、与段 3 全量法条的 `id` 冲突，见 §附 不确定项 #7 |
| `shared/api/client.ts` | ❌ **不存在**（实测 `shared/` 只有 components / navigation / theme / utils） | 唯一网络出口，B2 产出 |
| `expo-secure-store` | ❌ **不在 `package.json` 依赖里**（实测） | 鉴权态持久化要用它，属于本条前置 |
| 后端日志可读、端口已知 | ⚠️ 端口未在任何文档里定死，下文用 `8080` 占位 | 见 §附 不确定项 #10 |

**操作步骤**

**A. 证明「首页数据非硬编码」（改前/改后对照）**

1. 取服务端当前真实返回并落盘：
```powershell
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A2'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
curl.exe -s http://localhost:8080/api/v1/pub/ping | Tee-Object -FilePath "$out\0-A2_ping-before-$ts.json"
```
2. 把 `ping` 的 `env`（或 `version`）改成一眼能认出的串，例如 `PING-CHECK-<ts>`，重启服务端后再取一次：
```powershell
curl.exe -s http://localhost:8080/api/v1/pub/ping | Tee-Object -FilePath "$out\0-A2_ping-after-$ts.json"
```
   **必须把值改成人工不可能猜中的串**——如果只把 `"dev"` 改成 `"test"`，页面上一堆 `test` 字样，判不出是不是接口来的。
3. 冷启动 App 并截图首页（`app/index.tsx`，即 §零之三 裁决改造后的「启动自证页」，由现有 `SplashPage` 改造）：
```powershell
adb -s <serial> shell am force-stop com.faze.app
adb -s <serial> shell monkey -p com.faze.app -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 8
adb -s <serial> exec-out screencap -p > "$out\0-A2_home-after-change-$ts.png"
```
   首页上显示的版本/env 必须**出现 `PING-CHECK-<ts>` 这个串**。
4. 后端日志里找到这两次请求（含 `X-Trace-Id`），存 `0-A2_server-log-$ts.txt`。

**B. 证明登录走真接口 + 登录态持久化**

5. 在 App 登录，成功后进首页；后端日志里必须有 `POST /api/v1/pub/auth/login/password` 的 200 记录（贴进 `0-A2_server-log-$ts.txt`）。
6. 杀进程重启后仍是登录态（否则无法证明 token 落了盘）：
```powershell
adb -s <serial> shell am force-stop com.faze.app
adb -s <serial> shell monkey -p com.faze.app -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 8
adb -s <serial> exec-out screencap -p > "$out\0-A2_relaunch-still-logged-in-$ts.png"
```
   （`expo-secure-store` 的值是 Keystore 加密的，去 `shared_prefs` 里找明文是不可行也不必要的；「杀进程后仍登录」本身就是持久化的充分证据。）

**C. 让 token 失效：三种做法，选最干净的那一种**

| 做法 | 怎么执行 | 影响面 | 干净度 |
|---|---|---|---|
| **① 把该 token 写进 Redis 黑名单**（走服务端的登出/改密路径，旧 token 进黑名单，TTL = 剩余有效期 —— 接口规范 §6.1） | 调 App 的登出入口，或直接调后端登出接口 | **只废这一个 token** | ✅ **首选** |
| ② 改 JWT 密钥 `token.secret` 并重启服务端 | 改 `.env` / 配置 → 重启 | 全站所有 token 一起失效，**包括 RuoYi 管理端**（方案 A 共享 `token.secret`，接口规范 §1.3.1） | 彻底但会污染 `0-A6` 的登录态；仅在 ① 不可用时用 |
| ③ 把 access 过期时间改短（如 60 秒）+ 重启，等它自然过期 | 改配置重启 → 等待 | 命中 `402002`（过期，**可刷新**）→ 会走自动 refresh，可能被静默刷新糊过去 | ❌ 最不干净，验不到「清 token 回登录页」 |

**关键前提**：做法 ① 成不成立，取决于服务端是「黑名单模型」还是「会话/白名单模型」，**不许猜**：
```powershell
redis-cli keys "*<token 前 8 位>*"      # 有键 → 会话/白名单模型：删该键即失效
redis-cli exists <key>
```
- **黑名单模型**（无状态 JWT + 黑名单）：**删 Redis 键无效**，必须走「写黑名单」（登出）才会失效；
- **白名单模型**：删 `login_tokens:<uuid>` 类键即失效。
**本机当前没有 Redis**（6379 未监听，实测）→ 做法 ① 必须等 `0-A6` 的 Redis 缺口处置完才能用。

**D. 确认「失效确实发生了」（这一步不做，前面全白做）**

7. **失效前**，用同一枚 token 直接打一个需鉴权接口，拿到 200：
```powershell
$tok = '<ACCESS_TOKEN>'
curl.exe -s -i -H "Authorization: Bearer $tok" http://localhost:8080/api/v1/app/account/profile | Tee-Object -FilePath "$out\0-A2_auth-before-$ts.txt"
```
   期望首行 `HTTP/1.1 200`。
8. 执行失效动作（做法 ①）。
9. **失效后**，用**同一枚 token 字符串**再打一次：
```powershell
curl.exe -s -i -H "Authorization: Bearer $tok" http://localhost:8080/api/v1/app/account/profile | Tee-Object -FilePath "$out\0-A2_auth-after-$ts.txt"
```
   期望首行 `HTTP/1.1 401`，且 body 的 `code` ∈ {`402002`,`402003`}（接口规范 §6.1）。记下响应头 `X-Trace-Id` 与 body 的 `traceId`（两者同值，接口规范 §5.1）。
10. 回 App 触发任一需鉴权请求（下拉刷新 / 进任一需登录页），观察是否回登录页：
```powershell
adb -s <serial> exec-out screencap -p > "$out\0-A2_401-kickback-$ts.png"
adb -s <serial> logcat -d | Select-String -Pattern '401','traceId','Trace' | Tee-Object -FilePath "$out\0-A2_logcat-401-$ts.txt"
```
11. **把 traceId 对齐**：步骤 9 响应头里的 `X-Trace-Id` 必须能在步骤 10 的客户端日志里找到同一个值。**这才证明「App 是因为收到了这次 401 才回登录页」，而不是因为别的原因跳的。**

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过** | ① 步骤 3 的首页截图出现 `PING-CHECK-<ts>`，且与 `0-A2_ping-after` 一致、与 `0-A2_ping-before` 不同；② 步骤 5/6 的后端日志有 `login/password` 200 记录，且杀进程重启后仍登录；③ 步骤 7 是 `HTTP 200`、步骤 9 是 `HTTP 401` + `code ∈ {402002,402003}`，且两次是**同一枚 token**；④ 步骤 11 的 traceId 在服务端响应头与客户端日志里一致；⑤ 回登录页后无白屏（截图有可读文案）、无英文原始异常（如 `Request failed with status code 401` / `Network Error` / `TypeError`）；⑥ 观察 30 秒无 401 刷屏（不无限循环）。 |
| **不通过（任一命中）** | ① 改了服务端 `ping` 的值，首页**没变**（硬编码或缓存）；② 失效前后都是 401（步骤 7 之前 token 就没生效，因果链断）；③ App 回登录页是因为「重启后本来就没登录态」，无法证明拦截器工作；④ 出现 RN 红屏 / `LogBox` / 英文异常栈；⑤ 清 token 时把刚登录的新 token 也清了（接口规范 §6.1.1 ① 的坑）：**验法**——登录 → 立刻让一枚旧 token 触发 401 → 新会话应仍在；新会话被踢 = 不过。 |

**证据形式**（`docs/acceptance/seg0/0-A2/`）：`_meta.md`、`0-A2_ping-before/after-<ts>.json`、`0-A2_home-after-change-<ts>.png`、`0-A2_relaunch-still-logged-in-<ts>.png`、`0-A2_auth-before/after-<ts>.txt`（`curl -i` 全量含响应头）、`0-A2_401-kickback-<ts>.png`、`0-A2_logcat-401-<ts>.txt`、`0-A2_server-log-<ts>.txt`、`0-A2_token-fingerprint.txt`（token 前 8 位 + 后 8 位，中间打码；**禁止存完整 token**）、`0-A2_expire-method.txt`（用了哪种失效做法、为什么）。

**可能假通过**

1. **用做法 ③（改短过期时间）制造「失效」**：实际命中 `402002` → 自动 refresh 成功 → App 表现「什么都没发生」，却被写成「401 处理正常」——方向完全反了。**防**：`0-A2_expire-method.txt` 必须写清做法；并且必须证明 refresh 也失败（`POST /api/v1/app/auth/refresh` 同样返回 401），才算「会话真没了」。
2. **「非硬编码」用改文案来敷衍**：改了 `appName` 但首页那行其实是本地常量，恰好字面相同就误判通过。**防**：把 after 值改成人工不可能猜中的串（`PING-CHECK-<ts>`），截图必须出现该串。
3. **卸载重装 / 清数据冒充 401 登出**：两者都会回登录页。**防**：同一 token 的 `curl` 前后对照 + traceId 对齐，一样都不能少。
4. **只在 Metro 连着的 debug 包里验**：热重载会让状态错乱，「看着回登录页了」可能是 Metro 重载导致。**防**：本条必须在 `0-A1` 那份 preview APK 上复验一次。
5. **英文报错一闪而过没截到**：错误提示存在 2 秒，截图没抓到就说「没有英文报错」。**防**：录 `logcat`，并额外截一张停留 10 秒后的图。

---

### 4.3 `0-A3` RTL 整屏镜像 + 重启保持（原通过标准：返回箭头在右、进度条反向、Tab 顺序镜像、marginStart/End 全部生效；**杀进程重启后仍是 RTL**（证明软重启链路通、状态已持久化））

**验前准备**

| 前置 | 当前状态（实测） | 说明 |
|---|---|---|
| 语言切换页存在（`ug-Arab-CN` 选项） | ❌ 未做（C2 产出） | — |
| `expo-updates` 已装并配置 | ❌ **不在 `package.json` 依赖里** | `Updates.reloadAsync()` 是软重启链路的核心，**当前不具备** |
| 「有返回箭头 + 有进度条 + 有 Tab」的页面 | ❌ 段 0 只有登录页与语言切换页，**没有 Tab、没有进度条** | 这是锚点与页面的错配，见 §附 不确定项 #4：建议由 `/dev/rtl` 探针页承载这四组元素 |
| 系统级 RTL 未开启 | 未测 | 必须证明不是系统在起作用，见步骤 3 附注 |
| 设备分辨率已知 | — | `adb shell wm size` |

**操作步骤**

1. **建立 LTR 基线**：起 App 进语言切换页（路径 `/language`，与 §三 任务 C2 一致），截图（基线是后面「镜像」的唯一对照物）：
```powershell
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A3'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
adb -s <serial> shell wm size | Tee-Object -FilePath "$out\0-A3_wm-size.txt"
adb -s <serial> shell settings get global debug.force_rtl | Tee-Object -FilePath "$out\0-A3_force-rtl-setting.txt"   # 必须为 0/null
adb -s <serial> exec-out screencap -p > "$out\0-A3_1-lang-before-$ts.png"
```
2. 选 `ug-Arab-CN` → 观察启动屏（`reloadAsync` 约 1~2 秒）：
```powershell
adb -s <serial> exec-out screencap -p > "$out\0-A3_2-reloading-$ts.png"
```
3. 进 `/dev/rtl` 探针页，**逐项截图 + 逐项判定四个现象**（各项都要单独一张图，不要一张全景图糊过去）：

   | 子项 | 探针页上要有什么 | 判定（可量） | 存成 |
   |---|---|---|---|
   | a 返回箭头位置 | 一个返回箭头 icon | 箭头水平中心 x 落在屏幕宽度**右半区**（如宽 1080 → 中心 x > 540） | `0-A3_3-back-arrow-<ts>.png` |
   | b 进度条方向 | 固定 **30%** 的进度条（值写死在屏幕上，便于对照） | 填充区从**右边缘**起、向左占 30%（LTR 下从左侧起） | `0-A3_4-progress-<ts>.png` |
   | c Tab 顺序镜像 | 3 个 Tab，各带唯一文本（如 `首页/律师/我的`），且代码里 `routeName` 顺序不变 | RTL 后**最左**的 Tab 文本 = LTR 时**最右**那个（即左右翻转，不是改标签） | `0-A3_5-tabs-<ts>.png` |
   | d `marginStart/End` 生效 | 两个带编号的方块：A 用 `marginStart:16`、B 用 `marginEnd:16`，各叠一条 2px 红色 `borderStartWidth` 竖线 | RTL 下 A 贴**右**、B 贴**左**，两条红线位置对调（是**布局位置**翻转，不是图标翻转） | `0-A3_6-margin-<ts>.png` |

   **附注（必做）**：探针页必须把 `I18nManager.isRTL` 的运行时值直接显示在屏幕上并截图。如果屏幕上是 `false` 而布局却镜像了，说明是系统 `debug.force_rtl` 在起作用（步骤 1 已记录该项，必须为 0/null），本子项不通过。
4. **杀进程重启**（包名 `com.faze.app`，`app.json` 实测确认）：
```powershell
adb -s <serial> shell am force-stop com.faze.app
adb -s <serial> shell pidof com.faze.app        # 期望为空 → 证明进程真被杀
adb -s <serial> exec-out screencap -p > "$out\0-A3_7-killed-$ts.png"   # 期望是桌面/无该 App
adb -s <serial> shell monkey -p com.faze.app -c android.intent.category.LAUNCHER 1
```
5. **重启后先看首屏**（不许先进设置页、不许重新选语言）：
```powershell
Start-Sleep -Seconds 8
adb -s <serial> exec-out screencap -p > "$out\0-A3_8-after-restart-$ts.png"
```
   然后直接进 `/dev/rtl`，把步骤 3 的 a~d 四项**全部重做一遍**，存 `0-A3_9-after-restart-{a,b,c,d}-<ts>.png`。
6. **「持久化证据在哪」——这是本条最容易被说糊的地方，按下面三层取证**：
   - **第一层（必需，最强）**：`force-stop` 后 `pidof` 为空 + 重启首屏即 RTL 的那对截图。**内存态不可能跨进程存活**，这一对就足以证明持久化。
   - **第二层（加做，更硬）**：**连做两次** force-stop + 启动，三次截图都是 RTL → 排除「残留 Activity 栈」的干扰。
   - **第三层（可选，仅当 preview 包 `debuggable: true`）**：`adb -s <serial> shell run-as com.faze.app ls shared_prefs` 与 `cat shared_prefs/SecureStore.xml`。注意 `expo-secure-store` 落盘的是**密文**，只能证明「有落盘的 lang 键」，不能读出明文——**它是佐证，不是主证**，不要把它当唯一证据。
7. **反向往回切**（证明不是「一旦 RTL 就回不去」的单向 bug）：切 `zh-Hans` → reload → a~d 四项必须全部回到 LTR，存 `0-A3_10-back-to-ltr-<ts>.png`。
8. **藏文不是 RTL**（防「非中文即 RTL」的低级错误，§三 任务 C2 已点过这个坑）：切到 `bo` → reload → 页面必须仍是 **LTR**（`isRTL` 为 false、返回箭头仍在左）。存 `0-A3_11-bo-still-ltr-<ts>.png`。RTL 只有 `ug-Arab-CN` 与 `kk-Arab-CN` 两个。

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过** | ① 四个子项都「可量地」正确（箭头中心在右半区、进度条从右起 30%、Tab 左右顺序与基线相反而 `routeName` 顺序不变、A/B 方块与红线起止侧对调）；② 探针页显示 `I18nManager.isRTL === true`，且 `debug.force_rtl` 为 0/null；③ `force-stop` 后 `pidof` 为空（有截图或命令输出），重启首屏即 RTL；④ 连做两次重启仍 RTL；⑤ 能切回 `zh-Hans` 并恢复 LTR；⑥ reload 后回到切换前的页面（技术路线 §13.4 第 4、6 步要求恢复原路由），不是被扔回首页；⑦ 切到 `bo` 时**仍是 LTR**（藏文是从左到右）。 |
| **不通过（任一命中）** | ① 任一子项拿不出**基线对照**（只有 RTL 后截图）；② 系统 `debug.force_rtl` 为 1（结论无效）；③ 只有「重启后还是 RTL」的截图、没有 `pidof` 为空那一步（证明不了真杀过进程）；④ reload 后回首页（软重启链路部分不成立，按技术路线 §13.4 记不过）；⑤ 进度条/箭头是用图片左右翻转实现的（机制错，见下一节「可能假通过」）。 |

**证据形式**（`docs/acceptance/seg0/0-A3/`）：`_meta.md`、`0-A3_wm-size.txt`、`0-A3_force-rtl-setting.txt`、`0-A3_1-lang-before-<ts>.png`、`0-A3_2-reloading-<ts>.png`、`0-A3_3..6-{back-arrow,progress,tabs,margin}-<ts>.png`、`0-A3_7-killed-<ts>.png`、`0-A3_8-after-restart-<ts>.png`、`0-A3_9-after-restart-{a,b,c,d}-<ts>.png`、`0-A3_10-back-to-ltr-<ts>.png`、`0-A3_11-bo-still-ltr-<ts>.png`、`0-A3_restart-log-<ts>.txt`（force-stop/pidof/monkey 的命令与输出）、`0-A3_isRTL-value-<ts>.png`（探针页显示 isRTL 的那张）。

**可能假通过**

1. **用系统「Force RTL layout direction」让整屏镜像**：观感完美，但 App 自己的 `forceRTL` 链路根本没跑，换台机器就退化成 LTR。**防**：先证明 `settings get global debug.force_rtl` 为 0/null，再要求探针页显示 `isRTL` 由 App 置位。
2. **只看「箭头在右」**：一个 `transform: scaleX(-1)` 的假箭头也能过。**防**：四组子项同时验；`marginStart/End` 那组必须是**布局位置对调**（方块贴左还是贴右），不是图标翻转。
3. **「重启后仍是 RTL」其实是 Metro 热重载**：改代码触发 reload，画面变 RTL，但进程根本没死。**防**：`pidof` 必须为空 + 截图时间戳与 force-stop 时刻对齐 + 连做两次。
4. **Tab「镜像」靠直接改标签顺序**：现象一样，机制错（以后换语言会错乱）。**防**：证据里附切换前后**同一个 Tab 的 `routeName`**，代码里的顺序必须不变，只有渲染位置变。
5. **进度条用两张图（左起一张、右起一张）**：看着对，其实没有 RTL 能力。**防**：进度条必须是代码按百分比渲染，证据里附那个 30% 的值。

---

### 4.4 `0-A4` 藏文渲染（原通过标准：无豆腐块、无断笔、叠字正确；**截图存证**）

**验前准备**

| 前置 | 当前状态（实测） | 说明 |
|---|---|---|
| 藏文字体文件进 `assets/fonts/` | ❌ **仓库源码目录里 `.ttf/.otf/.ttc` 一个都没有**（实测扫描，已排除 `node_modules`） | `scripts/fetch-arabic-font.py` / `verify-arabic-font.py` 只处理**阿拉伯系**，藏文没有对应脚本 |
| 字体商用授权已确认 | ❌ 未落实 | 总纲 §九 并行轨道、§5.1 风险表：授权未定**不得进包**；覆盖不足时的降级是产品范围决策，须负责人拍板 |
| `expo-font` 已装 | ❌ 不在 `package.json` 依赖里 | 技术路线 §13.3 要求 `useFonts` 在根布局加载 |
| `/dev/probe` 探针页有藏文区块 | ❌ 未做（D2 产出） | 探针页必须显示 `fontFamily` 实值与字号档位 |

**藏文样本（必须逐字出现，不许换成「看起来差不多」的字）**

```text
བསྒྲུབས
བོད
```

- `བསྒྲུབས`：含下加字 `ྲ`、其下的元音 `ུ` 与**叠写**的 `ས`——这是断笔与叠字最容易被暴露的字形，首选它做判定。
- `བོད`：含元音符号 `ོ`，验元音定位（是否上移、是否压到基字）。

**混排段（藏汉混排 + 数字 + 标点，一段即可）**

```text
བོད་ཡིག་གི་ཁྲིམས་ལུགས། 《民法典》第12条 2026-10-31 金额 ¥100.00
```

> 说明：混排段**只验字形与排版**，不验语义/译文正确性（译文在段 8 收口，总纲 §7.1 线 B）。`Noto Sans Tibetan` 不含 CJK/拉丁字形，混排时中文出豆腐块是技术路线 §13.3 明确点过的典型坑。

**操作步骤**

1. 进 `/dev/probe`，找到藏文区块。探针页**必须自带字号标注**（如 `16 / 24 / 32` 三档），否则不同字号下的「断笔」结论不可比。逐档截图（原始分辨率，禁缩放）：
```powershell
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A4'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
adb -s <serial> exec-out screencap -p > "$out\0-A4_probe-16sp-$ts.png"
adb -s <serial> exec-out screencap -p > "$out\0-A4_probe-24sp-$ts.png"
adb -s <serial> exec-out screencap -p > "$out\0-A4_probe-32sp-$ts.png"
```
2. **局部放大核对**（判定必须基于放大图，缩略图上看不出断笔）：
```powershell
node C:\Users\14541\.dsh\tools\slice-image.js "<截图路径>" --max-edge 900
```
   把藏文那几行裁出放大图存 `0-A4_zoom-bsgrubs-<ts>.png`、`0-A4_zoom-bod-<ts>.png`、`0-A4_zoom-mixed-<ts>.png`。
3. **生成 PC 参照图**（没有参照就没有「正确」）：在 PC 上用**同一字体**（Noto Sans Tibetan，SIL OFL）以**同样字号**渲染同一字符串，存 `0-A4_reference-pc-<ts>.png`。判定时逐字形对齐比对。
4. 记录字体实值（证明不是系统字体在帮忙）：
```powershell
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> pull /sdcard/ui.xml "$out\0-A4_ui.xml"
adb -s <serial> logcat -d | Select-String -Pattern 'font','Font' | Tee-Object -FilePath "$out\0-A4_logcat-$ts.txt"
```
   `0-A4_ui.xml` 里必须能搜到 `བསྒྲུབས` 与 `བོད` 的 **`text` 属性**——这同时证明它是**文本节点**而不是贴图。
5. 探针页把 `fontFamily` 与 `lineHeight/fontSize` 比值显示出来并截图（技术路线 §13.3 第 6 条、i18n 规范 §7.2 要求藏文 `lineHeight ≥ 1.6 × fontSize`）。存 `0-A4_font-meta-<ts>.png`。

**判定标准**（逐项二值，任一项不过即整条不过）

| 子项 | 过 | 不过 |
|---|---|---|
| **无豆腐块** | 放大图里不出现空心方框 / 问号方框 / `U+FFFD`；混排段里中文也不出方框；`logcat` 无「fontFamily not found」类告警 | 任一字号下出现方框或替换字符 |
| **无断笔** | `བསྒྲུབས` 每一笔连续；`ྲ` 与 `ས` 的接笔处不断开、不出现半个字形；与 PC 参照图逐笔对齐 | 出现笔画缺失、断开、被裁掉一半 |
| **叠字正确** | `བསྒྲུབས` 的 `ས` 叠写在基字**下方**且位置正确（不左右错位、不糊成一团）；`བོད` 的 `ོ` 居上不偏移 | 叠写字形错位、重叠、上下颠倒 |
| **行高不裁切** | 三档字号下藏文的上下伸出部分（如 `ུ`、`ོ`）都不出血、不被上一行压住 | 出现上下被裁、与相邻行粘连 |
| **是文本不是图** | `0-A4_ui.xml` 里能搜到两个藏文串的 `text` 属性 | 搜不到（说明是 ImageView / 贴图） |

**证据形式**（`docs/acceptance/seg0/0-A4/`）：`_meta.md`（含字体文件名、字体来源与授权结论一句话）、`0-A4_probe-{16,24,32}sp-<ts>.png`、`0-A4_zoom-{bsgrubs,bod,mixed}-<ts>.png`、`0-A4_reference-pc-<ts>.png`、`0-A4_ui.xml`、`0-A4_font-meta-<ts>.png`、`0-A4_logcat-<ts>.txt`。

**可能假通过**

1. **系统自带藏文字体替你渲染了**：部分 Android ROM 自带 Tibetan 字体，App 没装字体也「看着正常」，换台设备就崩。**防**：探针页必须显示 `fontFamily` 实值；并在**两台不同品牌**真机上各验一次（至少一台接近原生 ROM）。
2. **只在 32sp 大字号下看**：断笔在 16sp 才明显。**防**：三档都要有，判定以**最小档**为准。
3. **截屏缩放掩盖问题**：把 1080 宽压到 600 宽，插值直接把断笔糊平。**防**：判定只用**原始分辨率局部放大图**，缩放图只能当索引，不能当证据。
4. **用图片/emoji 贴上去**：所见即所得，但「真机文本渲染」这个风险根本没验。**防**：`0-A4_ui.xml` 必须有文本节点（步骤 4）。
5. **「无断笔」靠一句人眼结论**：没有参照图就没法判定谁对谁错。**防**：`0-A4_reference-pc-*.png` 是必交件，缺它按未验处理。

---

### 4.5 `0-A5` AI 探针（原通过标准：首字节返回 ≤ 5 秒（不要求回答质量）；law 探针返回至少 1 条 law_item 记录且附命中依据）

**验前准备**

| 前置 | 当前状态 | 说明 |
|---|---|---|
| 豆包 API key 可用 | ❓ 未验 | 总纲 §5.1 风险表：可用官方免费额度或 mock；**但 mock 通过不算本条通过**（见下） |
| `law_item` 有数据 | ⚠️ 未入库；按 §零之二 冲突 3 裁决为 **`V2` 迁移插 5 条真实法条**（不是总纲风险表说的 50 条） | 5 条足以验通 ngram；命中率不足再补条目进新迁移，**不改 `V1`** |
| ngram 全文索引已建 | ❌ 未建 | MySQL `FULLTEXT ... WITH PARSER ngram` |
| 探针按钮的落点 | ✅ **已裁决：APP 的 `/dev/probe` 页**（§零之二 冲突 2：总纲「后台点按钮」是措辞错误；PC 侧段 0 不设任何探针入口，否则等于做了第二个 PC 页面） | 本节按此执行，不再讨论 |
| 服务端可起 | ❌ 无 `services/` | A3 组前置 |

> **mock 的界线（必须写清，否则最容易自欺）**：本节验的是「豆包能不能流式出字」。用 `PROBE_STUB` 返回假数据时，链路里**没有豆包**，要验的风险原地没动。此时本条只能记 **`未验 / 挂账`**，**不许记通过**。

**操作步骤**

**A. 首字节 ≤ 5 秒（三路测量，以前端侧为判定依据）**

1. **App `/dev/probe` 页点按钮计时（锚点原文口径，主证据）**：在探针页点「AI 探针」按钮，页面必须显示「首字节 1234 ms」这类读数；截图存 `0-A5_frontend-ttfb-<ts>.png`。
   （「后台点按钮」已按 §零之二 冲突 2 裁决为 APP 探针页，PC 侧不设入口；不要把 F12 Network 当主证据，它只能验到浏览器侧、验不到 App 的流式渲染。）
2. **服务端侧独立测（交叉验证，不作为判定主依据）**：
```powershell
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A5'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
## 请求体先落成文件，避免 PowerShell 把引号吃掉（本机 curl 是 Windows 版）
'{"prompt":"测试"}' | Set-Content -Encoding UTF8 "$out\probe-body.json"
curl.exe -N -sS -X POST "http://localhost:8080/api/v1/pub/probe/ai" `
  -H "Content-Type: application/json" `
  --data-binary "@$out\probe-body.json" `
  --max-time 30 -o NUL `
  -w "http_code=%{http_code} ttfb=%{time_starttransfer}s total=%{time_total}s`n" `
  | Tee-Object -FilePath "$out\0-A5_curl-ttfb-$ts.txt"
```
   这里看 **`ttfb`**（`time_starttransfer` = 收到第一个字节的时刻），不是 `total`。
3. **服务端日志（第三路）**：服务端在「收到请求」「向豆包发出请求」「收到豆包首帧」三处各打一行带时间戳与 traceId 的日志；后两行的时间差 = 服务端视角的首字节耗时。存 `0-A5_server-timing-<ts>.txt`。
4. **确认是真流式渲染（不是等全文再一次性显示）**：点按钮后**连拍 3 张**（间隔约 1 秒），存 `0-A5_streaming-1/2/3-<ts>.png`；三张的文字长度必须递增。若三张完全一致、最后一次性出全文 → 说明前端在等 `done` 才渲染，按「不是流式」记不过。
5. **连测 3 次**（排除首次冷启动、连接池未热）：App 页面上连点 3 次，把 3 次的读数各截一张（`0-A5_frontend-run{1,2,3}-<ts>.png`）；同时用 curl 连测 3 次留档：
```powershell
1..3 | ForEach-Object {
  curl.exe -N -sS -X POST "http://localhost:8080/api/v1/pub/probe/ai" `
    -H "Content-Type: application/json" `
    --data-binary "@$out\probe-body.json" `
    --max-time 30 -o "$out\0-A5_sse-run$($_)-$ts.txt" `
    -w "run=$_ ttfb=%{time_starttransfer}s`n"
} | Tee-Object -FilePath "$out\0-A5_3runs-$ts.txt"
```

**B. law 探针命中（命中依据长什么样）**

6. 先看库里有什么（建立对照）：
```powershell
mysql -u<user> -p -e "select id, law_code, article_no, left(content,30) as c from faze.law_item limit 5;" | Tee-Object -FilePath "$out\0-A5_law-table-$ts.txt"
```
7. 调探针：
```powershell
curl.exe -sS "http://localhost:8080/api/v1/pub/probe/law?kw=合同" | Tee-Object -FilePath "$out\0-A5_law-hit-$ts.json"
```
8. **命中依据在返回 JSON 的哪个字段**（接口规范 §7.1 冻结形状）：`{hits:[{id, lawCode, articleNo, snippet, score}]}`
   - **命中证据 = `hits` 长度 ≥ 1**，且每项的 `id` / `lawCode` / `articleNo` 能用同一个 `id` 从 `law_item` 查回来（步骤 9），且 `snippet` 是表里 `content` 的子串（说明是真检索出来的片段，不是拼的），且 `score` > 0。
9. 回查数据库，建立「同一行」证据：
```powershell
mysql -u<user> -p -e "select id, law_code, article_no, left(content,40) as c from faze.law_item where id='<hits[0].id>';" | Tee-Object -FilePath "$out\0-A5_law-row-$ts.txt"
```
10. **换关键词必须换结果**（防硬编码，见「可能假通过」）：
```powershell
curl.exe -sS "http://localhost:8080/api/v1/pub/probe/law?kw=离婚" | Tee-Object -FilePath "$out\0-A5_law-hit-2-$ts.json"
```
   两次返回的 `hits[].id` 集合必须**不同**。
11. 探针页把「查询词 + 命中的词 + score」显示出来并截图：`0-A5_probe-page-<ts>.png`。

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过** | ① **App `/dev/probe` 页读数**首字节 3 次都 ≤ 5000 ms（读数显示在页面上、截图可读）；② `curl` 的 `ttfb` 与服务端日志与之同数量级（差值 > 1.5 秒必须解释原因，否则记不过）；③ 真流式：`0-A5_sse-run*.txt` 里有多帧 `data:`（不是一次性一个完整 JSON）、以 `event: done` 结束（接口规范 §6.7），且 App 上连拍 3 张的文字长度递增；④ `hits` ≥ 1，且 `id/lawCode/articleNo` 与 `law_item` 行一致、`snippet` 是 `content` 子串、`score` > 0；⑤ 换关键词后 `hits[].id` 集合变化。 |
| **不通过（任一命中）** | ① 用 `PROBE_STUB` mock 返回（**数字再漂亮也不算通过**，只能记「未验」并挂账）；② 只报最快的一次；③ `hits` 有内容但库里查不到（写死的假命中）；④ 不是流式（`Content-Type` 不是 `text/event-stream`，或无多帧、无 `event: done`，或 App 等全文才一次性渲染）；⑤ 三个关键词的 `score` 完全相同（疑似常量）。 |

**证据形式**（`docs/acceptance/seg0/0-A5/`）：`_meta.md`、`0-A5_frontend-ttfb-<ts>.png`、`0-A5_frontend-run{1,2,3}-<ts>.png`、`0-A5_streaming-{1,2,3}-<ts>.png`、`0-A5_curl-ttfb-<ts>.txt`、`0-A5_3runs-<ts>.txt`、`0-A5_sse-run{1,2,3}-<ts>.txt`、`0-A5_server-timing-<ts>.txt`、`0-A5_law-table-<ts>.txt`、`0-A5_law-hit-<ts>.json`、`0-A5_law-hit-2-<ts>.json`、`0-A5_law-row-<ts>.txt`、`0-A5_probe-page-<ts>.png`、`0-A5_key-source.txt`（key 来源与额度消耗，**禁止贴 key 本身**）。

**可能假通过**

1. **mock 冒充真链路**（本条最大风险）：数字完美，风险没消除。**防**：证据里必须有可与豆包侧核对的东西——服务端日志里的豆包请求 id，或 `0-A5_key-source.txt` 里额度消耗次数前后 +1。
2. **把「服务端拿到豆包首帧」当成「前端首字节」**：中间还有网关、序列化、网络。**防**：三路都测，判定以**前端侧**为准。
3. **`hits` 是硬编码的一条**：很难一眼看出来。**防**：`hits[0].id` 必须能在库里查回 + `snippet` 是子串 + **换关键词结果必须变**（步骤 10）。
4. **`score` 是常量**：换三个关键词 `score` 一样 → 记不过。
5. **预热后报最好成绩**：反复点按钮再报数。**防**：服务端冷启动后的**第一次**必须计时并落盘（可以不过，但必须存在）。

---

### 4.6 `0-A6` 环境可复现（原通过标准：两个服务都拉起并打印连通状态；-Stop 能停 Redis；Flyway 迁移成功建出 2 张表；RuoYi 能登录并看到 biz 菜单页）

> ### ⚠️ 当前不满足，且 `0-A6` 是 8 条里唯一「按当前机器状态必然失败」的一条
>
> 实测 2026-10-31：
>
> | 锚点要求 | 当前状态（实测） | 结论 |
> |---|---|---|
> | `deploy/start-services.ps1` 存在 | 仓库**无 `deploy/` 目录** | 不满足 |
> | MySQL 能拉起 | 服务 `MySQL80` 已 Running、3306 监听、Automatic | 已满足 |
> | **Redis 能拉起** | **本机没有 Redis**：6379 未监听、无 Windows 服务、`redis-server`/`redis-cli` 不在 PATH、常见安装目录均不存在 | **不可能满足** |
> | `-Stop` 能停 Redis | 无 Redis 可停 | **不可能满足** |
> | `.env.example` | 仓库无 | 不满足 |
> | Flyway 建出 2 张表 | `services/` 不存在、无 `pom.xml` | 不满足 |
> | RuoYi 登录 + `biz` 菜单 | 源码已就位（`release-3.9.2` = tag `v3.9.2` / SB 4.0.3），但 **Redis 未装**（A2 阻塞） | 未验 |
>
> **缺口性质**：这不是「脚本写得不对」，是**机器上根本没有 Redis**。而且三条常见替代路径当前都不通（实测）：本机**没有 docker**；`wsl.exe` 在，但 `wsl -l -v` 返回「**没有已安装的分发版**」（exit 1）；`redis-server` / `redis-cli` 均不在 PATH。
>
> **处置（我不改锚点，只给替代验收 + 挂账，改锚点须负责人确认 —— 总纲 §6.4「砍锚点必须负责人确认」）**：
>
> 1. 技术侧先按任务 A2 装 Redis，三条路径按可行性排序：① Windows 版 Redis 兼容实现（Memurai 或 tporadowski 的 redis-for-windows 移植版，解压即用）；② 装 WSL2 发行版后在 WSL 里跑官方 Redis 7；③ 本机装 docker（当前没有）。**注意版本口径**：技术栈锁定 **Redis 7**（AGENTS §2），Windows 移植版通常停在旧版本，WSL2 里才能拿到官方 7.x —— 若只能装到非 7.x，`redis_version` 那一项要么挂账、要么走替代验收，**不许写成「Redis 7 已就绪」**。
> 2. 装不上时，**唯一合法的替代验收方式**（须负责人书面签字，技术侧不得自行降级）：
>    - 脚本仍必须实现「起 MySQL + 起 Redis」**两段逻辑**；Redis 段在检测到二进制缺失时**必须显式失败并打印缺失原因**（不许静默跳过、不许 `try/catch` 吞掉、不许打印假的「已连接」）；
>    - 替代验收只验四件事：MySQL 从 **Stopped** 拉起到 3306 通、Flyway 建出 2 张表、`-Stop` 幂等、RuoYi 登录 + `biz` 菜单；Redis 两项记 **`未验 / 阻塞`**；
>    - 挂账写进 CHANGELOG 与「待决事项」，写明**最晚解决时点：段 1 开工前**（段 1 的短信固定码、幂等键、分布式锁都要 Redis）。

**验前准备**：`services/faze-api` 与 `deploy/start-services.ps1` 存在；MySQL 账号密码就绪；RuoYi 前后端能起；一个可用的 `faze` 库。

**操作步骤**

1. **证明起点状态**（锚点原文要求「从 MySQL80 服务已停 + Redis 未启动开始」，没有这一步，后面全是自欺）：
```powershell
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A6'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
Stop-Service MySQL80 -Force
Get-Service MySQL80 | Select-Object Name,Status
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 6379 | Select-Object TcpTestSucceeded
```
   全部输出存 `0-A6_before-$ts.txt`（期望 MySQL `Stopped`、3306 `False`、6379 `False`）。
2. 跑脚本：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-services.ps1 2>&1 | Tee-Object -FilePath "$out\0-A6_start-$ts.txt"
```
3. **独立校验连通（脚本自己打印的不算证据）**：
```powershell
Get-Service MySQL80 | Select-Object Status
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 6379 | Select-Object TcpTestSucceeded
redis-cli ping            # 期望 PONG
redis-cli info server | Select-String redis_version
```
   全部存 `0-A6_ports-$ts.txt`（`redis_version` 必须是 **7.x**，技术栈锁定 Redis 7 —— AGENTS §2）。
4. Flyway 与表数：
```powershell
mvn -f services/faze-api/pom.xml -DskipTests flyway:info 2>&1 | Tee-Object -FilePath "$out\0-A6_flyway-$ts.txt"
mysql -u<user> -p -e "show tables from faze;" | Tee-Object -FilePath "$out\0-A6_tables-$ts.txt"
```
   期望业务表**恰好 2 张**：`app_user`、`law_item`。`flyway_schema_history` 是 Flyway 自己的元数据表，**要在证据里明确标注「不算业务表」**，防止用它凑数。
5. `-Stop`：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-services.ps1 -Stop 2>&1 | Tee-Object -FilePath "$out\0-A6_stop-$ts.txt"
Test-NetConnection 127.0.0.1 -Port 6379 | Select-Object TcpTestSucceeded   # 期望 False
Get-Service MySQL80 | Select-Object Status                                 # 记录 MySQL 是否也被停（脚本语义必须写明）
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\start-services.ps1 -Stop   # 再跑一次，必须幂等不报错
```
6. RuoYi：登录后台，截一张**含 `biz` 菜单的侧边栏 + 用户信息**的图 `0-A6_ruoyi-biz-menu-$ts.png`；URL、账号写进 `_meta.md`（密码不入证据，写「见密码管理」）。

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过**（Redis 已装前提下） | ① 步骤 1 起点成立（MySQL Stopped + 6379 False）；② 步骤 2 退出码 0；③ 步骤 3 两个端口都 True、`redis-cli ping` 返回 `PONG`、`redis_version` 为 7.x；④ 步骤 4 业务表**恰好 2 张**；⑤ 步骤 5 Redis 端口变 False 且 `-Stop` 幂等；⑥ 步骤 6 截到 `biz` 菜单；⑦ 全流程**一条命令可重复**：再跑一次仍成功且不报「已存在」。 |
| **不通过（任一命中）** | ① 脚本打印「已连接」但独立探测为 False；② 业务表多于 2 张（混进了别的 DDL）；③ 必须手工先起服务、再跑脚本（那就不是「可复现」）；④ Redis 用非 Redis 7 的兼容实现却宣称 Redis 7（版本号不符要么挂账、要么负责人书面接受，**不许含糊**）。 |
| **当前实际结论** | **不通过**（Redis 缺口 + `deploy/` 与 `services/` 都不存在）。 |

**证据形式**（`docs/acceptance/seg0/0-A6/`）：`_meta.md`、`0-A6_before-<ts>.txt`、`0-A6_start-<ts>.txt`、`0-A6_ports-<ts>.txt`、`0-A6_flyway-<ts>.txt`、`0-A6_tables-<ts>.txt`、`0-A6_stop-<ts>.txt`、`0-A6_ruoyi-biz-menu-<ts>.png`、`0-A6_gap.md`（Redis 缺口的说明 + 替代验收范围 + **负责人签字**）。

**可能假通过**

1. **脚本 `Start-Service` 后只 `Start-Sleep 3` 就打印「已连接」**：进程在跑但端口没通。**防**：脚本自己的打印一律不作证据，只认独立 `Test-NetConnection` + `redis-cli ping`。
2. **先手工起好服务再跑脚本**：脚本看起来「成功」。**防**：步骤 1 的起点证据（MySQL Stopped、6379 False）是必交件。
3. **用 `flyway_schema_history` 凑够 2 张表**：实际只有 1 张业务表。**防**：证据里明确标注哪张是 Flyway 元数据表。
4. **`-Stop` 只杀了自己起的进程，没管 Windows 服务**：换台机器行为就变。**防**：`-Stop` 连跑两次必须幂等。
5. **RuoYi 截图只截了登录页**：`biz` 菜单没出来也敢说「能登录」。**防**：截图必须同时看到侧边栏 `biz` 菜单项与右上角用户信息。

---

### 4.7 `0-A7` 工程门禁（原通过标准：三者零错误；全仓搜直写 `<Text>` 为 0（LocalizedText 自身除外）；marginLeft/right、paddingLeft/right、left/right 被 lint 报错）

> ### ⚠️ 当前不满足（实测 2026-10-31，四项全红）
>
> | 锚点要求 | 现状（实测） |
> |---|---|
> | `tsc --noEmit` 零错误 | 未验（`strict: true` 已开、`tsconfig` 排除 `node_modules/.expo/dist`） |
> | `eslint` 零错误 | 未验；但**两条关键规则不存在** |
> | `mvn verify` 零错误 | ❌ `services/` 不存在，没有 `pom.xml`，命令必然失败 |
> | 全仓直写 `<Text>` = 0 | ❌ 现有 **17 个文件共 107 处**含裸 `<Text>`（扫描范围 `app/` `features/` `shared/`，排除 `node_modules/.expo/dist`，与 §零之三 台账一致） |
> | 物理方向属性被 lint 报错 | ❌ 现有 **54 处**（§零之三 分项：`marginLeft/Right` 24 处 / 13 文件、`border*Left/Right` 15 处 / 4 文件、`left|right` 定位 10 处 / 6 文件、`paddingLeft/Right` 5 处 / 4 文件）；且 `eslint.config.mjs` 里**根本没有这条规则**。**统计口径注**：本节步骤 4 的搜索命令只匹配 `margin*`/`padding*`/`left:`/`right:` 四类，不匹配 `borderStartWidth` 系的 `border*Left`，所以它会数出约 41 行（含 4 行注释、1 个名为 `right` 的样式键名），**比 54 少**——收口时以 lint 输出为准，不以任何人工搜索计数为准 |
> | CI 三件套 | ❌ 仓库无 CI 配置（无 `.github/`、无 `.gitlab-ci.yml`）；远端是 `github.com/yqm1248/FAZE`（实测） |
>
> **结论**：`0-A7` 的「规则」还没有，「存量」也没清。要过这条，得先加 2~3 条 lint 规则（`no-raw-text`、禁物理方向属性、i18n 规范 §11.1 第 6 条的 `no-dynamic-i18n-key`）+ 处置存量。**存量怎么处置已由 §零之三 裁决**（删 12 个业务页、保留 4 个文件改造，`shared/components` 8 个组件逐个改造），本条只负责验「改完是 0」。

**操作步骤**（三条门禁命令 + 一条搜索 + 一条反证）

1. 类型检查（两种写法等价，任选；证据里必须出现其中一种的**全量输出**）：
```powershell
cd E:\dshcode\FAZE\apps\mobile-app
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A7'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
npx tsc --noEmit 2>&1 | Tee-Object -FilePath "$out\0-A7_tsc-$ts.txt"
## 等价写法：npm run typecheck   （package.json 实测：typecheck = tsc --noEmit）
```
   （`package.json` 实测：`typecheck` = `tsc --noEmit`。）
2. Lint：
```powershell
npm run lint 2>&1 | Tee-Object -FilePath "$out\0-A7_eslint-$ts.txt"
```
   （`package.json` 实测：`lint` = `tsc --noEmit && eslint .`。要求 **0 error、0 warning**。）
3. 服务端：
```powershell
mvn -f services/faze-api/pom.xml verify 2>&1 | Tee-Object -FilePath "$out\0-A7_mvn-$ts.txt"
```
4. **「全仓搜直写 `<Text>` 为 0」的具体命令**（排除 `node_modules` / `.expo` / `dist`，只扫 `app/ features/ shared/`，并排除 `LocalizedText` 自身）：
```powershell
$root = 'E:\dshcode\FAZE\apps\mobile-app'
$raw = Get-ChildItem -Path "$root\app","$root\features","$root\shared" -Recurse -Include *.tsx,*.ts -File |
  Where-Object { $_.FullName -notmatch '\\node_modules\\|\\\.expo\\|\\dist\\' -and $_.Name -ne 'LocalizedText.tsx' } |
  Select-String -Pattern '<Text[\s>]'
$raw | Measure-Object | Select-Object -ExpandProperty Count
```
   期望输出 **`0`**。同时把命中清单落盘（期望为空文件）：
```powershell
$raw | ForEach-Object { "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" } |
  Tee-Object -FilePath "$out\0-A7_raw-text-$ts.txt"
```
   - `<TextInput` 不会被误伤（`<Text[\s>]` 要求 `T` 后是空白或 `>`，`<TextInput` 的 `T` 后是 `I`）。
   - 只搜 `app/ features/ shared/` 是**锚点原文的范围**（「全仓」指这三个源码目录，不含构建产物与依赖）。
5. **证明「物理方向属性被 lint 报错」的规则真的存在**（关键：要证明规则**能报**，而不是全仓恰好没有违规）：
```powershell
## 造一个临时 canary，故意违反三条规则
@'
/* 临时文件：验证 lint 规则存在，验完即删 */
import { View, Text } from 'react-native';
export function Canary() {
  return <View style={{ marginLeft: 1, paddingRight: 2, left: 0 }}><Text>x</Text></View>;
}
'@ | Set-Content -Encoding UTF8 "$root\shared\__lintcanary.tsx"
npx eslint "shared/__lintcanary.tsx" 2>&1 | Tee-Object -FilePath "$out\0-A7_lint-canary-$ts.txt"
```
   期望：报出 `marginLeft`、`paddingRight`、`left`、裸 `<Text>` **四处**问题。
6. **反证**（防止规则是「凡 margin/padding 一律报」的假规则）：把 canary 里的 `marginLeft: 1` 改成 `marginStart: 1`、`paddingRight: 2` 改成 `paddingEnd: 2`、去掉 `left: 0`、把 `<Text>` 换成 `<LocalizedText>`，再跑一次 eslint，**必须不报**。存 `0-A7_lint-canary-negative-$ts.txt`。
7. 清理并确认工作区干净：
```powershell
Remove-Item "$root\shared\__lintcanary.tsx" -Force
git -C E:\dshcode\FAZE status --porcelain | Tee-Object -FilePath "$out\0-A7_git-status-$ts.txt"
```
8. 压规则的痕迹核查（必须有，否则「全绿」可能是压出来的）：
```powershell
Get-ChildItem -Path "$root\app","$root\features","$root\shared" -Recurse -Include *.ts,*.tsx,*.js,*.mjs -File |
  Where-Object { $_.FullName -notmatch '\\node_modules\\|\\\.expo\\|\\dist\\' } |
  Select-String -Pattern 'eslint-disable' |
  Tee-Object -FilePath "$out\0-A7_eslint-disable-$ts.txt"
```
   期望：**空**（除 `eslint.config.mjs` 自身，若有必要需在 `_meta.md` 说明）。
9. 留一份配置快照（防止「忽略目录」偷偷变宽）：
```powershell
Get-Content "$root\eslint.config.mjs" -Raw | Tee-Object -FilePath "$out\0-A7_eslint-config-$ts.txt"
Get-Content "$root\package.json" -Raw | Tee-Object -FilePath "$out\0-A7_package-json-$ts.txt"
```

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过** | ① `npx tsc --noEmit`（或 `npm run typecheck`）、`npm run lint`、`mvn -f services/faze-api/pom.xml verify` 三条退出码均为 0，eslint 输出 0 error 0 warning；② 步骤 4 计数为 **`0`**；③ 步骤 5 的 canary 报出四处问题；④ 步骤 6 的反证不报；⑤ `eslint-disable` 计数为 0（或在 `_meta.md` 有逐条说明）；⑥ canary 已删、`git status --porcelain` 为空；⑦ `deploy/verify-all.ps1` 有一次完整的运行记录（七步全绿、退出码 0、输出落盘到 `docs/acceptance/seg0/`）。**段 0 用本地等价物，不要求 GitHub Actions 的运行链接**——真 CI 的硬期限是段 1 开工前。 |
| **不通过（任一命中）** | ① 任一命令非 0；② 步骤 4 计数 > 0；③ canary 不报（规则不存在）；④ 反证也报（规则写错，会误伤合法代码）；⑤ 靠 `eslint-disable` / 扩大 `ignores` 压掉；⑥ 只有本机跑通、没有 CI 记录。 |

**证据形式**（`docs/acceptance/seg0/0-A7/`）：`_meta.md`、`0-A7_tsc-<ts>.txt`、`0-A7_eslint-<ts>.txt`、`0-A7_mvn-<ts>.txt`、`0-A7_raw-text-<ts>.txt`、`0-A7_lint-canary-<ts>.txt`、`0-A7_lint-canary-negative-<ts>.txt`、`0-A7_eslint-disable-<ts>.txt`、`0-A7_eslint-config-<ts>.txt`、`0-A7_package-json-<ts>.txt`、`0-A7_git-status-<ts>.txt`、`0-A7_ci-run.txt`（CI run 链接或本地全量输出）。

**可能假通过**

1. **扩大 `ignores`**（把 `features/**` 也忽略）→ 全绿。**防**：`0-A7_eslint-config-*.txt` 必须贴全文；步骤 4 的搜索**独立于 eslint**，不受 `ignores` 影响。
2. **逐行 `eslint-disable-next-line`**：CI 绿但规则形同不存在。**防**：步骤 8 的 disable 计数必须为 0。
3. **`lint` 脚本被改成只跑 eslint**（悄悄去掉 `tsc`）：类型检查形同没跑。**防**：贴 `package.json` 的 `scripts` 全文。
4. **`mvn verify` 靠 `-DskipTests` 或跳过插件**：过得太顺。**防**：贴命令原文 + 输出里 `BUILD SUCCESS` 之上的插件执行列表（要有 `surefire` 等实际执行行）。
5. **只在本机跑通就宣称 CI 通过**：段 0 要求的是 CI 三件（总纲 §5.1「工程」行）。**防**：`0-A7_ci-run.txt` 必须是真实运行记录；当前仓库无 CI 配置 → 现状即不满足。

---

### 4.8 `0-A8` 断网不崩（原通过标准：进入错误边界或错误态，不白屏、不闪退、有重试入口）

**验前准备**：页面四态 + 错误边界已实现（B4 产出，**当前未实现**）；至少 2 个「会发请求」的页面可验（可用：登录页、启动自证页、`/dev/probe`，任选 2 个且必须含一个非首屏页面）；真机可断网；设备 serial 已知。

**断网怎么断**（三选一，必须在 `_meta.md` 写明用了哪种）

```powershell
## 方式 1：飞行模式（Android 11+）
adb -s <serial> shell cmd connectivity airplane-mode enable
## 方式 2：关 WiFi
adb -s <serial> shell svc wifi disable
## 方式 3：关移动数据
adb -s <serial> shell svc data disable
```

恢复：
```powershell
adb -s <serial> shell cmd connectivity airplane-mode disable
adb -s <serial> shell svc wifi enable
```

- 部分 ROM / 低版本没有 `cmd connectivity` → 兜底：`adb shell settings put global airplane_mode_on 1` + `adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true`；再不行就人工关 WiFi + 数据并截图。
- **禁止用「关掉服务端」冒充断网**：那是「连得上但被拒」，验的是 5xx 不是断网，结论完全不同。

**操作步骤**

1. 有网状态进目标页，确认正常态：
```powershell
$out = 'E:\dshcode\FAZE\docs\acceptance\seg0\0-A8'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$ts  = Get-Date -Format 'yyyyMMdd-HHmmss'
adb -s <serial> exec-out screencap -p > "$out\0-A8_1-online-$ts.png"
```
2. 断网并**证明真断**：
```powershell
adb -s <serial> shell ping -c 2 8.8.8.8 | Tee-Object -FilePath "$out\0-A8_netcheck-$ts.txt"
```
   期望 `100% packet loss` 或 `Network is unreachable`。
3. 在断网状态下**触发请求**（两种都要验）：
   - 页内触发：回到页面下拉刷新 / 点重试；
   - 冷启动：`adb -s <serial> shell am force-stop com.faze.app` 后重新启动。
4. 截图，并**等够时间再截第二张**（排除「先错误后白屏」和「无限 loading 冒充错误态」）：
```powershell
adb -s <serial> exec-out screencap -p > "$out\0-A8_2-offline-$ts.png"
Start-Sleep -Seconds 10
adb -s <serial> exec-out screencap -p > "$out\0-A8_3-offline-10s-$ts.png"
```
5. **判定「错误边界 vs 白屏」的机器可读证据**：
```powershell
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> pull /sdcard/ui.xml "$out\0-A8_ui-offline-$ts.xml"
adb -s <serial> shell pidof com.faze.app | Tee-Object -FilePath "$out\0-A8_pid-$ts.txt"
adb -s <serial> logcat -d | Select-String -Pattern 'FATAL EXCEPTION','ReactNativeJS' | Tee-Object -FilePath "$out\0-A8_logcat-$ts.txt"
```
   判别规则（写死，不靠感觉）：
   - **白屏** = `ui.xml` 里所有 `text=""` 全空 **且** 截图接近纯色（单色占比高）；
   - **错误态** = `ui.xml` 里存在**可读的错误文案节点**（中文，如「网络不可用，请检查网络后重试」）**且**存在**重试按钮节点**（`clickable="true"` 且 text 含「重试」）。
6. 恢复网络 → **点重试** → 页面恢复：
```powershell
adb -s <serial> shell cmd connectivity airplane-mode disable
Start-Sleep -Seconds 3
## 人工点一次「重试」
adb -s <serial> exec-out screencap -p > "$out\0-A8_4-recovered-$ts.png"
```
   只看到按钮不算过，**必须点下去真的恢复**。
7. 换第二个页面（`/dev/probe`）重复 2~6，文件前缀 `0-A8_probe_*`。

**判定标准**（二值）

| 结论 | 必须同时成立 |
|---|---|
| **通过** | ① 断网后进入错误态（有可读错误文案 + 可点重试）；② 不是白屏（`ui.xml` 有非空 text + 截图非纯色）；③ 不闪退（`pidof` 非空、`logcat` 无 `FATAL EXCEPTION`）；④ 错误文案是当前语言的可读文案，**不是** `Network request failed` / `TypeError: Failed to fetch` 这类英文原始异常；⑤ 点重试后恢复为正常态；⑥ **两个页面都过**；⑦ 断网保持 ≥ 30 秒（保证超时路径真的走到）。 |
| **不通过（任一命中）** | 白屏；闪退；无重试入口；有按钮但点了没反应；英文异常原样上屏；只验了一个页面；用「关服务端」代替断网。 |

**证据形式**（`docs/acceptance/seg0/0-A8/`）：`_meta.md`（含断网方式）、`0-A8_netcheck-<ts>.txt`、`0-A8_1-online-<ts>.png`、`0-A8_2-offline-<ts>.png`、`0-A8_3-offline-10s-<ts>.png`、`0-A8_ui-offline-<ts>.xml`、`0-A8_pid-<ts>.txt`、`0-A8_logcat-<ts>.txt`、`0-A8_4-recovered-<ts>.png`、`0-A8_probe_*`（第二页全套）。

**可能假通过**

1. **把「WiFi 图标变灰」当成断网**：WiFi 关了但移动数据还在，请求照样通。**防**：必须 `ping` 出 100% loss 的证据。
2. **「错误态」其实是无限 loading**：骨架屏 / 转圈看着像有内容，其实永远不结束。**防**：等 10 秒再截一张；`ui.xml` 里必须有「重试」。
3. **重试按钮不可点**（只是文案）。**防**：`ui.xml` 里该节点 `clickable="true"`，且实际点击后恢复（步骤 6）。
4. **`ErrorBoundary` 兜住了但显示英文堆栈**：技术上「没白屏」，用户看到 `TypeError: ...`。**防**：判定明确禁止英文原始异常串上屏。
5. **断网时间太短**：请求还没超时就恢复了，超时路径一次没走到。**防**：断网 ≥ 30 秒。

---

### 4.9 验收证据目录规范（`docs/acceptance/seg0/`）

#### 4.9.1 目录结构

```text
docs/acceptance/seg0/
├─ README.md                     # 索引：8 条锚点一屏看完（编号/结论/证据目录/执行人/日期/签字）
├─ .gitignore                    # 例外规则：允许 *.txt 证据；*.log 按需放行（见 4.9.2）
├─ 0-A1/                         # 每条锚点一个目录，目录名与锚点编号完全一致
│   ├─ _meta.md                  # 必填元数据（见 4.9.3）
│   └─ 0-A1_eas-build-20261031-152003.txt
├─ 0-A2/
├─ 0-A3/
├─ 0-A4/
├─ 0-A5/
├─ 0-A6/
├─ 0-A7/
├─ 0-A8/
└─ _artifacts/                   # 体积大的二进制（APK 等）不入 git 时放这里
                                  # 必须在对应 _meta.md 里记 sha256 + 来源 URL，否则不算证据
```

**三条硬规则**

1. **目录名只能是 `0-A1` ~ `0-A8`**，不许出现 `0-A1-可安装`、`seg0-a1` 这类变体（否则无法脚本化核对「8 条齐不齐」）。
2. **`docs/acceptance/` 当前不存在**（实测）→ 本条由任务 A1 建目录时一并建出。
3. 截图**存原始分辨率 PNG**，不许压缩、不许裁到只剩一个角（裁切图只能作为**补充**，不能替代原图）。

#### 4.9.2 文件命名规则

`<锚点编号>_<证据含义>-<yyyyMMdd-HHmmss>.<扩展名>`

| 段 | 规则 | 例 |
|---|---|---|
| 锚点编号 | `0-A1` ~ `0-A8`，与总纲完全一致 | `0-A3_...` |
| 证据含义 | 英文 kebab-case，描述「这份证据是什么」 | `eas-build` / `install` / `launch-cold` / `auth-after` / `401-kickback` / `lint-canary` / `ui-offline` |
| 时间戳 | `yyyyMMdd-HHmmss`（Asia/Shanghai），用 `Get-Date -Format 'yyyyMMdd-HHmmss'` 生成 | `20261031-152003` |
| 扩展名 | 截图 `.png`；命令输出/日志 `.txt`（UTF-8）；结构化返回 `.json`；UI 树 `.xml` | — |

**后缀选择的坑（实测踩到）**：仓库 `.gitignore` 里有 `*.log`，用 `.log` 后缀的证据**进不了 git**。所以本节统一用 `.txt`；确实要保留原样构建日志时才用 `.log`，并在 `docs/acceptance/seg0/.gitignore` 里加一条放行规则，同时在 `_meta.md` 说明为什么。

**禁止的命名**：`1.png`、`截图.png`、`新建文本文档.txt`、`0-A1.png`（无时间戳 = 无法证明是这一次的）、`0-A1_最终版2.png`。

#### 4.9.3 元数据要求（一条，缺就不算验过）

**每个锚点目录下必须有一份 `_meta.md`，写明「谁的哪台机器、什么系统与构建号、什么时间、对着哪个 commit 与哪个构建」——缺任一项，该条锚点按「未验」处理，不算通过。**

| 字段 | 必填 | 取值示例 | 怎么拿到 |
|---|---|---|---|
| 执行人 | ✅ | 泰莎 | — |
| 执行时间 | ✅ | `2026-10-31T15:20:03+08:00` | `Get-Date -Format o` |
| 机型 | ✅ | `23113RKC6C` / Xiaomi | `adb shell getprop ro.product.model` + `ro.product.manufacturer` |
| 系统版本 | ✅ | `Android 14` | `adb shell getprop ro.build.version.release` |
| ROM 构建号 | ✅ | `ro.build.display.id` 原值 | `adb shell getprop ro.build.display.id` |
| 设备序列号 | ✅ | `adb devices` 里的 serial | `adb devices -l` |
| 是否物理机 | ✅ | 是（`ro.kernel.qemu` 为空） | `adb shell getprop ro.kernel.qemu` |
| 包名 / 版本 | ✅ | `com.faze.app` / `1.0.0 (1)` | `adb shell dumpsys package com.faze.app` |
| 应用构建号 | ✅ | EAS build id `xxxxxxxx-…` | `eas build:view` |
| 代码版本 | ✅ | `5318d79` | `git -C E:\dshcode\FAZE rev-parse --short HEAD` |
| 结论 | ✅ | `通过` / `不通过` / `阻塞（挂账）` | — |
| 偏差说明 | ⭕ 结论为不通过/阻塞时必填 | 如「Redis 未装，替代验收见 `0-A6_gap.md`」 | — |

**汇总入口**：`docs/acceptance/seg0/README.md` 用一张表把 8 条锚点列全：`编号 | 锚点名 | 结论 | 证据目录 | 执行人 | 日期 | 备注`。段末收口时这张表就是总纲 §6.2「验收证据」那一件的交付物本体。

---

## 五、本段产出清单

> 只列**第 0 段真正会产出的东西**。判据是总纲 §5.1「交付物」+ 为满足 8 条锚点所必需的最小集合；**段 1 及以后的东西一件都不列**（总纲 §5.1「明确不做」）。
>
> 「段末状态」一列的写法：**新建** / **改造** / **新建（不完整，见注）** / **阻塞**。凡标「阻塞」或「不完整」的，必须在总纲 §6.2 第 5 件「遗留挂账」里出现。

| 类别 | 名称 | 位置 | 段末状态 |
|---|---|---|---|
| APP 页面 | 登录页（真接口） | `apps/mobile-app/features/auth/pages/LoginPage.tsx`（已有，改造成真接口）+ 路由壳 `apps/mobile-app/app/(auth)/login.tsx` | 改造：去掉 `__fixtures__`，走 `POST /api/v1/pub/auth/login/password`；四态齐全 |
| APP 页面 | 启动自证页（**改造 `SplashPage`，不新增页**） | `apps/mobile-app/features/auth/pages/SplashPage.tsx`（已有，§零之三 裁决改造）+ `app/index.tsx` | 改造：显示 `/api/v1/pub/ping` 的真实返回值（`0-A2`「首页数据非硬编码」的落点）；**不计入 68 页清单的新增页** |
| APP 页面 | 语言切换页 | `apps/mobile-app/features/settings/pages/LanguagePage.tsx`（新建）+ 路由壳 `apps/mobile-app/app/language.tsx`（与 §三 任务 C2 一致） | 新建：四语言选项（默认 `zh-Hans`）+ `forceRTL` + `Updates.reloadAsync` + 启动时按持久化值再置一次 |
| 非产品页 | `/dev/probe` 探针页 | `apps/mobile-app/app/dev/probe.tsx` + `features/dev/pages/ProbePage.tsx` | 新建：藏文区块（三档字号）+ AI 首字节计时 + `ping` 回显；**不进 release 包**（`__DEV__` 门控 + preview 独立 profile） |
| 非产品页 | `/dev/rtl` 探针页 | `apps/mobile-app/app/dev/rtl.tsx` + `features/dev/pages/RtlProbePage.tsx` | 新建：返回箭头 / 30% 进度条 / 3 个 Tab / `marginStart-End` 两方块 / `isRTL` 显示；**不进 release 包** |
| PC 页面 | A-01 登录 | RuoYi 自带登录页（不改框架层）+ 新增 `src/views/biz/**` 一个 `biz` 菜单页 | 新建：能登录 + 侧边栏出现 `biz` 菜单；仅主题令牌层改造，不动框架层 |
| 接口 | `GET /api/v1/pub/ping` | `services/faze-api/.../modules/**`（域待 A3 定） | 新建：`@Profile("!prod")`，返回 `{appName, version, serverTime, env}` |
| 接口 | `POST /api/v1/pub/auth/login/password` | 同上 | 新建：手机号 + 密码登录，签发 access + refresh；字段照《接口规范》附录 A.1.1 照抄 |
| 接口 | `POST /api/v1/app/auth/refresh` | 同上 | 新建：换新 access token；**单飞**（接口规范 §6.1） |
| 接口 | `GET /api/v1/app/account/profile` | 同上 | 新建：**唯一目的是提供一个「需鉴权资源」让 `0-A2` 能验 401**（§零之二 冲突 1 的裁决理由） |
| 接口 | `POST /api/v1/pub/probe/ai`（SSE） | 同上 | 新建：`@Profile("!prod")`，流式，`event: done` 收尾 |
| 接口 | `GET /api/v1/pub/probe/law?kw=` | 同上 | 新建：`@Profile("!prod")`，返回 `{hits:[{id, lawCode, articleNo, snippet, score}]}` |
| 表 | `app_user` | `services/faze-api/src/main/resources/db/migration/V1__baseline.sql` | 新建：按《V1 基线表结构_v1.0》DDL 抄，不改字段 |
| 表 | `law_item` | 同上 | 新建：结构完整；**数据另由 `V2` 迁移灌 5 条真实法条**（§零之二 冲突 3 裁决，不是 50 条），全量入库在段 3 |
| 表 | `V2` 数据迁移（种子） | `services/faze-api/src/main/resources/db/migration/V2__*.sql` | 新建：1 个可登录测试用户（`password_hash` 为 BCrypt）+ 5 条法条；**只灌数据、不建表**（所以 `0-A6` 的「业务表恰好 2 张」仍成立） |
| 配置 | `apps/mobile-app/eas.json` | `apps/mobile-app/eas.json` | 新建：`build.preview.android.buildType = "apk"`；`0-A1` 的硬前置 |
| 配置 | `app.json` 的 `extra.eas.projectId` | `apps/mobile-app/app.json` | 改造：`eas init` 后回填（当前缺失） |
| 配置 | `eslint.config.mjs` 新增三条规则 | `apps/mobile-app/eslint.config.mjs` | 改造：`no-raw-text`（禁裸 `<Text>`）、禁物理方向属性（`marginLeft/Right`、`paddingLeft/Right`、`left/right`）、`no-dynamic-i18n-key`（i18n 规范 §11.1 第 6 条）。**当前一条都没有** |
| 配置 | `.env.example` | `deploy/.env.example` | 新建：DB / Redis / 豆包 key / 端口，**不含真实凭据** |
| 配置 | 字体文件 | `apps/mobile-app/assets/fonts/` | **阻塞**：当前仓库零字体文件；藏文授权未落实前不得进包（`0-A4` 前置） |
| 脚本 | `deploy/start-services.ps1` | `deploy/start-services.ps1` | **阻塞**：脚本可写，但「起 Redis」在本机做不到（无 Redis、无 docker）；脚本必须在 Redis 缺失时显式失败，不许假装成功 |
| 脚本 | i18n 完整性检查脚本 | `scripts/check-i18n-keys.py`（或 `.ps1`） | 新建：四语言目录同构 + key 集合一致 + 占位符一致（i18n 规范 §11.1 第 1~3 条） |
| 脚本 | 本地门禁脚本（CI 的等价物） | `deploy/verify-all.ps1` | **段 0 不建 GitHub Actions**：改为 `deploy/verify-all.ps1`（七步、任一失败即非 0 退出、**不许 skip**、证据落盘）；真 CI 的硬期限是**段 1 开工前**（段 1 是账务内核）。详见第 1 组的 CI 门禁表 |
| 文档 | 本段工作流 | `docs/architecture/法泽FAZE_第0段工作流_v1.0.md` | 新建（本文是其 §四 + §五） |
| 文档 | APK 构建路径文档 | `docs/architecture/法泽FAZE_APK构建路径_v1.0.md` | 新建：换机器照做能出包（含 `eas.json`、`projectId`、凭据说明、常见失败）—— 出口硬要求 |
| 文档 | 字体选型结论 | `docs/standards/字体选型结论_v1.0.md` | **阻塞**：需负责人给授权结论；覆盖不足时的降级（图片 / 放弃 `bo`）是产品范围决策 |
| 文档 | 验收证据索引 | `docs/acceptance/seg0/README.md` | 新建：8 条锚点一览表（结论 / 目录 / 执行人 / 日期） |
| 文档 | CHANGELOG 段 0 一节 | `docs/CHANGELOG.md` | 改造：总纲 §6.2 第 3 件 |
| 证据 | `0-A1` ~ `0-A8` 证据目录 | `docs/acceptance/seg0/0-A1/` ~ `0-A8/` | 新建：每条含 `_meta.md` + 本节列出的全部文件；`0-A6` 另含 `0-A6_gap.md`（Redis 缺口 + 负责人签字） |

**这张表的三条自检**

1. **数量核对**：APP 页 **2 个产品页（登录页 + 语言切换页）+ 1 个改造页（启动自证页，非新增）**、非产品页 2、PC 页 1、表 2（+1 条 `V2` 数据迁移，不建表）、接口 **6 个**（3 探针 + 登录 + 刷新 + 一个需鉴权资源）—— 与总纲 §5.1「交付物」的「2 个 APP 页 / 1 个 PC 页 / 2 个非产品页 / 2 张表」对得上；接口 6 个按 **§零之二 冲突 1 的裁决**，不是我自己改的口径。
2. **没有偷偷多做**：没有 iOS、没有 49 张表、没有个推、没有热更新通道、没有支付、没有 RuoYi 主题外的后台页（总纲 §5.1「明确不做」+ 本段 §七）。**`expo-updates` 只用于 `reloadAsync` 软重启，不是热更新通道**——这一点要在 CHANGELOG 里写明，否则下一段会以为热更新已经通了。
3. **砍掉的和留下的一样要记账**：§零之三 裁决要删的 12 个业务页面 + 12 个路由壳 + 4 个 `__fixtures__/` 目录，属于「不产出」，但必须出现在 CHANGELOG（删了什么、从哪个分支能找回），否则下一个人会以为这些页面从来没存在过。

---

## 六、已知风险与出口

按「会不会一票否决」排序。**出口要按顺序试，不许跳步**；三条全不通才升级给负责人。

| # | 风险 | 一票否决？ | 出口（按顺序） | 最晚处理时点 |
|---|---|---|---|---|
| R1 | **出不了可安装包**：本机只有 adb、无 Android SDK | **原本会**（总纲称之为唯一会推翻全盘的风险）。**实测降级**：本机虽无 SDK，但 `eas-cli/22.0.0` 已装且**已登录**（`1454132yqm`，Owner），EAS 云构建通道**已就绪**，不需要本机 SDK | ① **EAS 云构建（已就绪，首选）**：需新建 `eas.json`；`expo-dev-client` 当前未装（仅走 dev-client 通道时才需要）② 本机装完整 SDK：`cmdline-tools` + `build-tools` + `platforms;android-35`（`platform-tools` 已有），JDK 17 已有 → 本地 Gradle ③ dev-client 内测分发 ④ 三条全不通才需要重设计分期体系 | D1 第一天 |
| R2 | **Redis 装不上**（本机完全没有） | 不会，但 `0-A6` 过不了 | ① Memurai for Windows（与 Redis 协议兼容、有 Windows 服务）② 微软归档的 Windows 版 redis ③ WSL2 内装 Redis ④ 书面申请改 `0-A6`（**改锚点须负责人确认**，技术侧不得自行把「MySQL 通」当「环境可复现」签字） | A2 第一天 |
| R3 | **藏文字体无可用开源方案 / 无商用授权** | **会**（`0-A4` 直接失败，段 4 的文书 PDF 也依赖它） | ① Noto Sans Tibetan（SIL OFL，可商用）② 字形覆盖不足 → 「藏文改用图片资源」③ 放弃 `bo` 语言 ④ **②③ 是产品范围决策，必须负责人拍板**，技术侧自行降级不可接受 | D2，且必须在段 4 之前定 |
| R4 | 豆包 key / 配额未就绪 | 不会 | 官方免费额度；仍不行 → `PROBE_STUB` 开关返回固定流，**并在 `CHANGELOG.md` 登记「待替换」**（不许 Mock 后忘记） | D3 |
| R5 | `law_item` 探针数据不足 | 不会 | `V2` 迁移的 5 条真实法条（《V1基线表结构》§五）；命中率不足再补，**补的条目进新迁移，不改 `V1`/`V2`** | D3 |
| R6 | **存量 15 个页面的删除未获批准** | 会卡 B1 | 先打 `ui-prototype-archive` 分支再请示；若负责人不同意删 → 只剩两条路：逐页改造（+150 处）或降低 `0-A7` 标准（须书面确认并记 CHANGELOG），**不许偷偷加 eslint ignore** | B1 开工前 |
| R7 | 没有可用的真实 Android 手机 | 会卡 `0-A1`/`0-A2`/`0-A3`/`0-A8` | 借一台；**模拟器不算通过**（总纲 `0-A1` 明令「不是模拟器」） | 开工前（前置检查第 4 项） |
| R8 | RuoYi 后端起不来 | 会卡 `0-A6` 后半 | 确认数据库指向 `faze_admin`；RuoYi 自带 SQL 初始化脚本要跑；Redis 未装会影响 RuoYi（RuoYi 默认用 Redis 存会话）→ 与 R2 同一出口 | A1 组末 |
| R9 | `forceRTL` + `Updates.reloadAsync()` 在 dev-client / Expo Go 下不生效 | 不会（`0-A3` 允许用「手动杀进程重启」代替软重启链路） | 按 §3.3 C2 的「卡住怎么办」第 1 条执行，**并在 §九 记偏差**；软重启链路必须在段 8 前补通 | C2 |
| R10 | RuoYi `LoginUser` 在 Redis 里的序列化格式不可读（《接口规范》§1.3.1 标「待实测」，若为 JDK 序列化则 `faze-api` 读不出） | **段 0 不阻塞**，但会卡段 1 的 PC 联调 | 段 1 开工前实测一次；不通则退 B 方案（双登录），退路已在 §1.3.1 写明 | **挂账到段 1 开工前置**，写进段 1 工作流 |

**风险与「不许带进下一段」的关系**：R1/R3 若未消除，第 0 段**不算通过**（它们正好对应 `0-A1` 与 `0-A4` 两条锚点）；R2 若未消除，`0-A6` 不通过；R9/R10 允许挂账，但必须在 §九 与 `CHANGELOG.md` 写明「为什么可以拖、最晚什么时候解决」。

## 七、明确不做

照总纲 §5.1「明确不做」，并补上实测后新增的边界。

| 不做 | 为什么 | 有人做了怎么办 |
|---|---|---|
| iOS 真机 / TestFlight | iOS 单独一期 | 打回，本段只验 Android |
| 49 张表（只建 2 张：`app_user`、`law_item`） | 段 1 起才需要其余表 | 打回，多余的 DDL 不进 `V1__baseline.sql` |
| 任何业务页面 | 本段交付的是判定能力，不是功能 | 打回。存量 17 个 UI 复刻页面的处置见任务 B1，**不许顺手把它们接到真接口** |
| 68 页导航树全量落成路由壳 | 同上；页归属在段 1~8 逐步落地 | 打回 |
| 个推 / 热更新通道 | 与段 0 锚点无关 | 打回（`forceRTL` 软重启用 `expo-updates` 的 `reloadAsync`，**这不是热更新通道**，见 B3） |
| 支付任何东西 | 段 6 | 打回 |
| RuoYi 主题改造以外的任何后台页面 | 段 0 只做 A-01 登录 | 打回 |
| 段 8 的译文填充 | §7.1 线 B 在段 8 | `key` 直接上屏（未翻译）算通过，但**不许硬编码中文** |
| 真实短信通道 | 段 1，且开发期允许固定码 | — |
## 八、留给下一段的接口（我保证什么、我不保证什么）

**我保证（段 1 可以直接依赖，不再重做）**：

| 承诺 | 段 1 怎么用 |
|---|---|
| `R<T>` 统一响应可用，且**含 `traceId`** | 段 1 的 40 个接口直接返回 `R`，不重新设计响应壳 |
| 全局异常处理器可用，**不把 `e.getMessage()` 返回客户端** | 段 1 的业务异常继承同一套异常基类即可 |
| JWT 签发 → 过滤器校验 → `SecurityContext` 全链路可用 | 段 1 的注册登录只负责「发 token」，校验链路不再改 |
| `shared/api/client.ts` 是**唯一**网络出口，401 已按「只清被拒 token」处理 | 段 1 的所有业务 API 走它，不许新开 axios 实例 |
| `app_user` 表已建、Flyway 通道可用 | 段 1 新建 12 张表直接加 `V2__...`，不改迁移框架 |
| 审计四要素已定义（`operation_log` 字段口径） | 段 1 建 `operation_log` 表时照此落，不重新定义 |
| 页面四态组件 + 错误边界可用 | 段 1 的 18 个 APP 页直接用 |
| `t()` + 14 个 namespace 目录结构可用，缺译登记机制可用 | 段 1 加文案只往 json 里加，不改机制 |
| RTL 机制（`forceRTL` + 软重启 + `LocalizedText` / `LtrText` + 3 条 lint）可用 | 段 1 第 1~18 页必须在新机制下不出布局错 |
| `deploy/start-services.ps1` 可复现环境 | 段 1 开工前置第一条就跑它 |

**我不保证（段 1 起必须自己解决，别指望段 0 顺手做了）**：

| 不保证 | 最晚什么时候解决 |
|---|---|
| 真实短信通道 | 段 1 上线前；段 1 内允许固定验证码 |
| 身份证 OCR / 实名自动核验 | 段 1 只做「上传 + 人工审核」 |
| 私有 OSS 与签名 URL | 段 1 的资质上传前 |
| `law_item` 全量法条数据（段 0 只有 50 条探针数据） | 段 3 全量入库 |
| 支付渠道、退款、提现 | 段 1 只做手工充值；段 6 接支付 |
| PC 后台除 A-01 以外的任何页面 | 段 1 起逐段落地 |
| UAT / 生产环境、域名、HTTPS 证书 | 段 8 |
| iOS | 单独一期 |
| 真实导出的 release 包（段 0 用 preview / dev-client 均可） | 段 8 上架 |
## 九、实际完成与偏差（施工后回写，不删原文）

> 本节的表格在段 0 收口时填写。**没填 = 没按总纲 §6.2 收口。**

### 9.1 八条锚点的实际结果

| 锚点 | 结果（通过 / 不通过 / 挂账） | 证据文件 | 偏差说明 |
|---|---|---|---|
| `0-A1` 可安装 | | | |
| `0-A2` 真接口 + 401 | | | |
| `0-A3` RTL 整屏镜像 + 重启保持 | | | |
| `0-A4` 藏文渲染 | | | |
| `0-A5` AI 探针 | | | |
| `0-A6` 环境可复现 | | | |
| `0-A7` 工程门禁 | | | |
| `0-A8` 断网不崩 | | | |

### 9.2 未做 / 降级 / 替代（逐条写清）

| 项 | 原计划 | 实际 | 为什么 | 最晚何时补 |
|---|---|---|---|---|
| | | | | |

### 9.3 新增的遗留问题

| 问题 | 影响 | 挂到哪 |
|---|---|---|
| | | |

### 9.4 与前后段的接口变动（如果 §八 的承诺没兑现）

| 承诺 | 实际 | 段 1 要怎么办 |
|---|---|---|
| | | |
## 十、开工前必须由负责人拍板的事项

三位专家各自列了不确定项（SRE 12 条、测试 14 条、架构 11 条）。去重后**真正需要人拍板的只有下面 10 条**，按「不定下来会卡住什么」排序。

### 10.1 不定就开不了工（4 条）

| # | 事项 | 卡住什么 | 建议 |
|---|---|---|---|
| 1 | **存量 15 个页面的删除授权** | 开工第 0 步；不删则 `0-A7` 与 `0-A3` 都无法验（见 §零之三） | 同意删，先打 `ui-reference` 分支保底 |
| 2 | **MySQL root（或有建库权的账号）凭据** | A3 的建库、建应用账号、查 `ngram_token_size`（实测 `mysql -uroot` 返回 `ERROR 1045 Access denied`） | 提供凭据，或给一个能建库的账号 |
| 3 | ~~RuoYi 后端版本选型~~ **已裁决（2026-11-01）** | — | 定为 **RuoYi-Vue 3.9.2 官方发行版（tag `v3.9.2` / Spring Boot 4.0.3）**，`faze-api` 同步升 Spring Boot 4.0。**原未决项已关闭（2026-11-01）**：MP 用 `mybatis-plus-spring-boot4-starter:3.5.18.1`，见 §零之四 与 `docs/acceptance/seg1/A3-mp-probe.md` |
| 4 | **藏文字体来源与商用授权** | `0-A4`；段 4 的文书 PDF 也依赖它 | 给授权结论。若 Noto Sans Tibetan 覆盖不足，「藏文改用图片」或「放弃 `bo`」是**产品范围决策**，不许技术侧自行降级 |

### 10.2 段内可实测，但结果会改锚点（3 条）

| # | 事项 | 为什么要实测 | 失败时怎么办 |
|---|---|---|---|
| 5 | `Updates.reloadAsync()` 在 preview（release）包里是否可用 | 技术路线 §13.4 自标「待实测」；不配 `updates.url` 时可能被禁用 | `0-A3` 允许退「手动杀进程重启」，但**必须在 §九 记偏差**，软重启链路列入段 8 前必补 |
| 6 | `expo/fetch` 在真机上是否逐块回调 | RN 0.81 的全局 `fetch` 是 XHR 实现、没有 `response.body`；`expo/fetch` 存在但未在真机跑过 | 退回 `react-native-sse`（多一个依赖，需登记） |
| 7 | `ngram_token_size` 本实例的实际值 | 只读参数，必须连库查；若被改过，命中行为与文档假设不一致 | 不为 2 时改 `my.ini` 并重启 MySQL，再重建全文索引 |

### 10.3 允许挂账（3 条）

| # | 事项 | 挂到哪 | 为什么可以拖 |
|---|---|---|---|
| 8 | `0-A6` 是否拆成 `0-A6a`/`0-A6b`（Redis 拆出） | 段 1 开工前置 | 段 0 服务端不用 Redis；但**拆锚点须书面确认** |
| 9 | 豆包 key / 推理接入点 ID（`ep-xxxxxxxx`）/ 配额 | D3 当天 | 可用 `PROBE_STUB` mock，但 mock 只能记「链路未验」，不能记「通过」 |
| 10 | 真 CI 平台与触发策略 | **段 1 开工前（硬期限）** | 段 0 用 `deploy/verify-all.ps1` 等价；段 1 是账务内核，必须上真 CI |

### 10.4 顺带记录：本次汇编发现的既有文档缺陷（已在产出后修正）

| 位置 | 缺陷 |
|---|---|
| `docs/BASELINE.md` 头部 | 标题 / 基线编号 / 资产数**停在 v1.13**（资产数写 24），而正文 §7.18~§7.22 已记到 v1.18、§二 已写「28 份」。**每版只更新正文，没回头改头部** |
| `docs/BASELINE.md` §五 | 验证脚本的 `$files` 数组只有 24 项，漏了**早已纳入基线**的 4 份：接口规范、后端代码规范、i18n 规范、审计与日志规范 |
| `docs/architecture/法泽FAZE_接口规范_v1.0.md` §4.1 | `Idempotency-Key` 行写「见 §5.4」，而幂等定义在 §6.4 |
| `docs/architecture/法泽FAZE_接口规范_v1.0.md` §7.1 | 段 0 接口清单漏登录组（3 应为 6，见 §零之二 冲突 1） |
| `scripts/README.md` | 写「本目录当前为空」，实际有 5 个 `.py` |
| `README.md` L55 | 写「`database/` 建库脚本 + Flyway 迁移」，但 Flyway 迁移必须在 `services/faze-api/src/main/resources/db/migration/`（classpath）才能打进 jar |
| `docs/analysis/ruoyi-patches.md` | **被 AGENTS §3.3 引用，但文件不存在**（第一次 patch 时才建，需在 A1 建空文件并写使用规则） |
| `docs/architecture/依赖清单.md` | **被 AGENTS §4.12 要求，但文件不存在**（A1 建） |


---

> **【文档基线 v1.21】** 本文件属于「法泽 FAZE 文档基线 v1.21」（2026-11-01 纳入）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
