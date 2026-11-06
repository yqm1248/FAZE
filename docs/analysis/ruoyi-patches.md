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
