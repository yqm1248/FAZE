# RuoYi 框架层 patch 记录

> **用途**：`AGENTS.md` §3.3 要求「不得修改 RuoYi 框架层代码；确需改动时走配置覆盖或独立扩展，并记录到本文件」。
> **本文件在段 0 任务 A1 创建（此前不存在，但被 AGENTS §3.3 引用）。**
> **位置**：RuoYi 源码在仓库外，见 `pc/README.md`。

## 记录规则

1. **能配置覆盖就不改代码**：优先 `application*.yml`、前端 `.env.*`、路由/菜单配置。
2. 必须改代码时，一次改动一条记录，写清五件事：**改哪个文件 / 为什么必须改 / 影响面 / 回滚方式 / 日期**。
3. 条目 **> 3 条**时停手，按 `pc/README.md` §5 改判为 subtree 或 fork——继续打补丁会让升级不可行。
4. 记录只追加，不改历史条目；回滚后在对应条目补「已回滚 + 日期」。

## 模板

| # | 日期 | RuoYi 文件（相对 RuoYi 根） | 为什么必须改 | 影响面 | 回滚方式 |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

## 当前记录

| # | 日期 | RuoYi 文件（相对 RuoYi 根） | 为什么必须改 | 影响面 | 回滚方式 |
|---|---|---|---|---|---|
| 1 | 2026-11-05 | `ruoyi-admin/src/main/resources/application-druid.yml` | RuoYi 必须用独立库 `faze_admin`（《数据表规范》§6.1 定：与业务库 `faze` **分库**），默认的 `ry-vue` 库不存在 | 只影响本地开发的数据库连接；不改任何框架代码 | 把 `url` 改回 `jdbc:mysql://localhost:3306/ry-vue?...`、`password` 改回 `password` |
| 2 | 2026-11-05 | `pnpm-workspace.yaml`（**新增文件**，前端根） | ① npm 11.13.0 解析 RuoYi 的 `package-lock.json`（lockfileVersion 3，已确认无异常版本）时在 `arborist` 的 dedupe 阶段抛 `Invalid Version`，全新 `node_modules` 下仍复现；② 改用 pnpm 后，其默认严格布局让 `sortablejs` 等「代码直接 `import` 但不在 `package.json`」的包解析不到。<br>该文件内容：`nodeLinker: hoisted` + `allowBuilds`（esbuild / vue-demi / @parcel/watcher） | 只影响**依赖安装方式与 node_modules 布局**，不改任何前端源码；`hoisted` 是为了兼容 RuoYi 依赖 npm 扁平提升的写法 | 删除 `pnpm-workspace.yaml`，改回 npm（需先解决 npm 的 lock 解析问题，或降级到 npm 10） |
| 3 | 2026-11-05 | `vite.config.js`（**前端**根） | 第 1 段 F1 第 3 步：业务页要调 **faze-api（8081）**，而 RuoYi 前端只有一条 `/dev-api → 8080` 的代理。新增一条 `/biz-api → http://127.0.0.1:8081`（转发时去掉前缀）。属「配置覆盖」，不是改框架层 | 只影响开发服务器代理；生产由 Nginx 按同样规则分流。**不影响** RuoYi 自带 8 页 | 删掉 `server.proxy['/biz-api']` 这一条与 `bizBaseUrl` 常量 |
| 4 | — | **不是 RuoYi 改动，是跨服务耦合点（接口规范 §1.3.1 第 6 条要求登记）** | `faze-api` 的 `AdminAuthenticationFilter` 依赖 RuoYi 的登录态：① JWT 用 HS512、密钥 = RuoYi `token.secret`；② 载荷里有 `login_user_key`（UUID）；③ Redis 逻辑库 0 的 `login_tokens:<uuid>` 存 `LoginUser` 的 fastjson2 序列化文本，`faze-api` 从中抽 `userId` / `username` | RuoYi **升级或改 token 配置**会让 PC 后台业务页全部 401。回归手段：`AdminTokenVerifierTest`（真实 token 快照）+ `AdminSessionStoreParseTest`（真实 Redis 值快照），见 `docs/acceptance/seg1/F1-admin-auth-bridge.md` | 换方案 B（faze-api 自签 admin token，PC 业务页单独登录） |

| 5 | 2026-11-05 | `vite.config.js`（**前端**根，同 #3 文件的第二处改动） | 写文件的一方用「临时目录 + rename」做原子写（`.index.vue.<pid>.<uuid>.tmpdir/`），Vite 默认的 `fs.watch` 会去监听那个临时文件，Windows 抛 `EBUSY: resource busy or locked`，而 Node 的 `fs.watch` error 事件没人接 → **dev server 进程直接退出**（实测 01:30 崩过一次）。改为 `server.watch = { usePolling: true, interval: 300, ignored: ['**/*.tmpdir/**','**/.*.tmpdir/**','**/*.tmp'] }` | 只影响开发服务器的文件监听方式：改成轮询后 CPU 略升（node_modules 默认不监听，src 很小），换来不再崩 | 删掉 `server.watch` 这一段 |

> **耦合点 4 的两个实测坑（2026-11-05，与接口规范 §1.3.1 第 5 条的表述不符，以这里为准）**：
> 1. **Redis 里的值不是标准 JSON**。fastjson2 开了 `WriteClassName`，落盘形如
>    `{"@type":"...LoginUser","deptId":103L,...,"permissions":Set["*:*:*"],...,"userId":1L,"username":"admin"}`。
>    `103L`（带 `L` 后缀）与 `Set[...]`（集合字面量）**都不是合法 JSON**，Jackson 直接解析会抛异常。
>    规范第 5 条写的「用任意 JSON 库都能读」不成立；`faze-api` 改为按 `"userId":<数字>L?,"username":"..."`
>    相邻对定点抽取，抽不到就 fail closed。
> 2. **密钥派生不是标准 Base64**。RuoYi 用 jjwt 0.9.1，`signWith(HS512, String)` 走
>    `TextCodec.BASE64` → `javax.xml.bind.DatatypeConverter.parseBase64Binary`，它**只解完整的 4 字符组**。
>    `token.secret = abcdefghijklmnopqrstuvwxyz`（26 字符）解出 **18 字节** `69b71d79f8218a39259a7a29aabb2dbafc31`；
>    常规 Base64 解码器（含 Node 的 `Buffer.from(s,'base64')`）会解出 **19 字节**（多一个 `cb`），
>    用它算出的签名**永远对不上**。这是本段最容易被误判成「方案 A 不可行」的地方。

> 补充说明（2026-11-05）：
> - 前端 `package.json` 曾被临时加入 `pnpm.onlyBuiltDependencies`（pnpm 11 已不读该字段，仅打警告），**已删除**；原文件备份在 `RuoYi-Vue3/package.json.bak-dsh`。当前 `package.json` 与原始内容的差异仅为 JSON 缩进格式（语义未变）。
> - 本机的 Node.js 曾把 `INSTALLDIR` 装到 `E:\uniapp1\`（HBuilderX 目录），2026-11-05 已卸载并重装到标准位置 `C:\Program Files\nodejs`（v24.16.0 / npm 11.13.0）。此事属**本机环境修复**，不是 RuoYi 改动。

---

## 第 3 段（H3）框架层核对 · 2026-11-07

**结论：0 条新 patch。**

| 核对项 | 结果 |
|---|---|
| `git status --short -- pc/admin-web/src/{layout,components,router,store,utils,directive}` | **空**（本段没有改任何 RuoYi 框架文件） |
| 页面落点 | `pc/admin-web/src/views/biz/ai/law-library/`、`pc/admin-web/src/views/biz/ai/ai-retrieval/`（AGENTS §3.3） |
| 接口封装落点 | `pc/admin-web/src/api/biz/ai/law-library.js`、`ai-retrieval.js`，走 `src/api/biz/request.js` 业务实例（不是 `src/utils/request.js`） |
| 菜单 | 数据库驱动：`database/seed/seg3_admin_menu.sql` 往 `faze_admin.sys_menu` / `sys_role_menu` 插 10 行，**不是源码补丁** |
| 侧边栏高度 | 本段实测 1440×900 展开「内容管理」无滚动条（wrap 850/850）；展开既有「法泽业务」会出滚动条（864 > 850）。**没有为了消滚动条改 `src/assets/styles/sidebar.scss`**（框架样式）；该偏差属既有 IA 债务，记入 `docs/acceptance/seg3/H3-menu.md`，归段 8 收口 |
| `vite.config.js` | 本段未改（历史改动见上面 #3/#5） |

证据：`docs/acceptance/seg3/H3-menu.md`、`docs/acceptance/seg3/_artifacts/h-pc-verify.json`（真实接口模式 31/31 PASS，框架层 git status 为空）。

---

## 第 8 段之后 · PC 后台品牌化与信息架构重整（2026-11-13）

**背景**：负责人 2026-11-13 提出 4 件事——① 侧边栏菜单要按一级/二级分类，若依原生菜单按点名隐藏；
② 左上角「若依管理系统」的名字与叶子图标换成法泽品牌；③ 登录页按 `design/admin/00-login.html` 重做；
④ 登录后直接进数据看板。菜单部分是**数据改动**（`database/seed/admin_menu_ia.sql`），本表只记源码改动。

**⚠️ 与 §记录规则第 3 条的关系（必须知道）**：规则说「条目 > 3 条时停手，改判 subtree 或 fork」。
本节一次新增 **6 条**，累计 11 条，**已越过该阈值**。判断依据：这 6 条不是「框架 bug 补丁」，
而是**产品化必须的品牌与主题定制**（RuoYi 官方文档本身就把主题色、logo、登录页列为「定制项」），
且全部集中在主题变量与入口页，不改任何框架组件的行为逻辑。**若后续再往 `src/layout`、
`src/components`、`src/store` 里加改动，应触发 §3 的 subtree/fork 讨论，不再按本节方式追加。**

| # | 日期 | RuoYi 文件（相对前端根 `pc/admin-web/`） | 为什么必须改 | 影响面 | 回滚方式 |
|---|---|---|---|---|---|
| 6 | 2026-11-13 | `src/views/login.vue` | 原生登录页是「信纸木窗背景 + 居中白卡片 + 若依管理系统」，整页零品牌，底部还印着 `Copyright © 2018-2026 RuoYi`。负责人要求按 `design/admin/00-login.html` 重做（左 760 品牌板 + 右 360 表单 + 贴底左对齐的版权行）。**登录逻辑（验证码、记住密码 Cookie、跳转 redirect）原样保留，只重写模板与样式** | 只影响 `/login` 一个页面 | `git checkout HEAD -- pc/admin-web/src/views/login.vue` |
| 7 | 2026-11-13 | `src/router/index.js` | 登录后落地的 `/index` 原本指向 `views/index.vue`（若依自我介绍页，含 QQ 群、作者微信/支付宝与**收款二维码**）。改成直接指向 `views/biz/ops/Dashboard`，标题「数据看板」。**不采用 redirect 到动态路由**：`/index` 是 constantRoutes 静态路由，redirect 到动态路由存在 addRoute 时序风险 | 只影响登录落地页；`views/index.vue` 保留未删，改回一行即可还原 | 把 `component` 改回 `() => import('@/views/index')`、`meta.title` 改回 `'首页'` |
| 8 | 2026-11-13 | `src/layout/components/Sidebar/Logo.vue` | 只改样式：图标 32→30px 加 `border-radius: 7px`（法泽图标是白底方图，在深色侧边栏上不加圆角是一个刺眼白方块，见 `apps/mobile-app/assets/brand/README.md` §3），标题 14→15px | 只影响侧边栏左上角渲染，模板与逻辑未动 | `git checkout HEAD -- pc/admin-web/src/layout/components/Sidebar/Logo.vue` |
| 9 | 2026-11-13 | `src/components/Breadcrumb/index.vue` | 面包屑首项写死 `title: "首页"`，落地页改成数据看板后会出现「点进去标题叫数据看板、面包屑叫首页」 | 所有非首页页面的面包屑首项文案 | 把 `"数据看板"` 改回 `"首页"` |
| 10 | 2026-11-13 | `src/store/modules/settings.js` | 默认主题色 `#409EFF`（若依蓝）→ `#4a66ee`（法泽品牌蓝，见 `design/admin/tokens.css` 的 `--brand-primary`、《PC后台UI设计说明》§137） | 全局：所有 `el-button--primary` 与通过 `--el-color-primary` 取色的组件。**已被 localStorage 的 `layout-setting` 存过旧色的浏览器不会自动变**，需清该 key 或在设置面板重选一次 | 改回 `'#409EFF'` |
| 11 | 2026-11-13 | `src/assets/styles/variables.module.scss` | 主题/侧边栏配色对齐设计稿：`$menuActiveText`/`$menuLightActiveText`/`$--color-primary` → `#4a66ee`；`$menuBg`/`$base-menu-background` `#1a1f2e` → `#22283a`；`$base-sub-menu-background` `#141824` → `#1b2030`；`$menuHover` → `#2c3350`。目的：从登录页左板（`--bg-sidebar: #22283a`）进后台时视觉不断裂 | 侧边栏底色与菜单激活色；所有引用 `$--color-primary` 的样式 | 逐项改回原值（见本条「为什么必须改」列） |

### #12 后端：Druid 控制台与库口令（2026-11-13，W13 剩余项）

| 项 | 内容 |
|---|---|
| 文件 | `pc/admin-server/ruoyi-admin/src/main/resources/application-druid.yml`（**后端**，不在前端根下） |
| 为什么必须改 | 盲测 S51：`/druid/login.html` 匿名可达（实测 HTTP 200），控制台账号口令是若依默认的 `ruoyi/123456`，`allow` 白名单为空（= 允许所有 IP）。进去能看到全部 SQL、数据源连接串、会话，等于数据库后门；同一个文件里库口令还是明文 `123456` |
| 改了什么 | ① 库用户名/口令、Druid 控制台用户名/口令、`allow` 全部改成 `${环境变量:本机默认值}` 形式——本机不设变量仍能直接跑，生产由部署脚本注入；② `allow` 默认 `127.0.0.1`（原来空）；③ `resetEnable: false`（原默认 true，「重置全部统计」会掩盖真实压力） |
| 影响面 | 只影响 `/druid/**` 与数据源连接；**不设环境变量时行为与本机原来完全一致**，所以本地启动方式无需改动 |
| 生产必须做 | 注入 `FAZE_ADMIN_DB_PASSWORD` / `FAZE_DRUID_PASSWORD`；若不需要控制台，直接把 `statViewServlet.enabled` 设 false 最干净 |
| 回滚 | `git checkout HEAD -- pc/admin-server/ruoyi-admin/src/main/resources/application-druid.yml` |

**同批改动的非代码资源**（不算 patch，但影响面一样大，一并列出便于回滚）：
| 资源 | 改动 | 回滚 |
|---|---|---|
| `pc/admin-web/.env.development` / `.env.production` / `.env.staging` | `VITE_APP_TITLE` 若依管理系统 → 法泽管理后台（侧边栏左上角与浏览器标签页共用）。**这三个文件此前被 `.gitignore` 的 `.env.*` 规则忽略、且从未入库**，已于同批修 `.gitignore` 加白名单 | 改回 `若依管理系统` |
| `pc/admin-web/public/favicon.ico` | 若依叶子 → 法泽图标（Pillow 生成 16/32/48 三档真 ICO） | `git checkout HEAD -- pc/admin-web/public/favicon.ico` |
| `pc/admin-web/src/assets/logo/logo.png` | 若依叶子 → 法泽图标 96×96（显示 32px，留高 DPI 余量） | `git checkout HEAD -- pc/admin-web/src/assets/logo/logo.png` |

**没有动的若依痕迹（负责人 2026-11-13 明确「没提的地方保留」）**：顶栏右上角的 gitee 源码图标、
文档图标、右上角用户名「若依」（`sys_user.nick_name`）、`src/views/index.vue` 若依介绍页本体、
`src/components/RuoYi/` 目录、`src/views/tool/**` 与 `src/views/monitor/**` 页面。
