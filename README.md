# 法泽 FAZE

> 面向新疆伊犁地区的普惠型法律服务平台。**目标是真实上线运营**，不是原型、不是演示、不是参赛作品。
> 平台范围：移动端 APP（Android 优先）+ 服务端 API + PC 管理后台；**4 种语言**（简体中文 / 藏文 / 维吾尔文 / 哈萨克文）。

---

## 现在在做什么

项目按 **9 个段（第 0~8 段）串行推进**，段与段之间不并行。当前处于 **第 0 段：可安装底座 + 三项探针**。

| 想知道 | 看哪里 |
|---|---|
| 9 段怎么切、现在第几段、每段怎么算做完 | [docs/architecture/法泽FAZE_开发计划总纲_v1.0.md](docs/architecture/法泽FAZE_开发计划总纲_v1.0.md) |
| **第 0 段具体怎么做**（单线，从第一行读到最后一行） | `docs/architecture/法泽FAZE_第0段工作流_v1.0.md`（**已产出**，2026-10-31；12 任务 / 4 组 / 8 条锚点逐条可执行） |
| 哪些文档是现行版 | [docs/BASELINE.md](docs/BASELINE.md) |

---

## ⚠️ 仓库当前状态（读代码前必看）

**第 0 段已开工：主线已裁剪到段 0 边界（提交 `9d589d3`）。**

- 旧的 12 个 UI 复刻页面、12 个路由壳与全部 fixture **已从 `UI设计` 删除**，完整快照归档在**只读分支 `ui-reference`**（已推送远端）。该分支只用于对照设计稿，**不合并、不 rebase、不再接收提交**。
- 设计权威仍是 `design/ui/` 的 71 个 HTML，RN 复刻只是它的一次性翻译产物，不作为实现基线。
- 不要相信任何文档里的「已完成」字样，除非总纲 §5.1 的 8 条验收锚点已全绿（证据见 `docs/acceptance/seg0/`）。

工程规范以 [AGENTS.md](AGENTS.md) 为准，不以任何历史代码为准。

---

## 技术栈（已锁定）

| 层 | 选型 |
|---|---|
| 移动端 | Expo SDK 54 / React Native 0.81 / React 19 / expo-router 6 / TypeScript strict |
| 服务端 | Java 17 / Spring Boot 4.0 / MyBatis-Plus 3.5.18.1 / MySQL 8.0 / Redis |
| PC 后台 | RuoYi 3.9.2（后端 RuoYi-Vue tag `v3.9.2` / Spring Boot 4.0.3，前端 RuoYi-Vue3 3.9.2；业务代码隔离在 `src/views/biz/`） |
| 实时通信 | 自建 WebSocket + 第三方离线推送 |
| AI | 豆包 API（SSE 流式）+ 本地法条库检索（MySQL ngram 全文索引，**不用向量库**） |
| 文书 | HTML 模板 + `openhtmltopdf` 1.0.10（LGPL；**不做 Word 导出**；含少数民族文字的 PDF 不得使用它，中文场景可用） |
| 包管理器 | **npm** |
| 容器 | **不用 docker**，本地依次启动服务 |

---

## 目录

```text
apps/
  mobile-app/       APP（Expo + expo-router）；app/ 只放薄路由，页面实现在 features/
services/
  faze-api/         Spring Boot 服务端（Flyway 迁移在 src/main/resources/db/migration/）
database/           建库脚本 + 种子数据（**Flyway 迁移不在这里**，见上一行）
deploy/             start-services.ps1（按顺序起 MySQL / Redis，不用 docker）、.env.example、verify-all.ps1
design/             设计令牌唯一来源
docs/               全部文档，入口是 docs/README.md
pc/                 PC 后台指针（RuoYi 源码在仓库外，见 pc/README.md）
scripts/            静态检查与验证脚本
tools/              取证、字体检查等工具脚本（段 0 暂不创建）
```

> 除 `apps/`、`design/`、`docs/` 外，其余目录在第 0 段逐步建立（见《第 0 段工作流》）。

## 目录职责（谁能改、产物是什么、什么不进仓库）

| 目录 | 负责人 | 产物 | 不进仓库 |
|---|---|---|---|
| `apps/mobile-app/` | 移动端 | APK / JS bundle / 四语言资源 | `node_modules/`、`.expo/`、`dist/`、`*.apk` |
| `services/faze-api/` | 服务端 | `faze-api` jar、Flyway 迁移、接口 | `target/`、`*.class`、本地口令配置 |
| `database/` | 服务端 | 建库脚本、种子数据（可重复执行） | 真实凭据 |
| `deploy/` | 运维 / 服务端 | `start-services.ps1`、`verify-all.ps1`、`.env.example` | `deploy/.env`（含口令）、运行日志 |
| `pc/` | PC 后台 | 只放 README 指针与 patch 记录 | RuoYi 源码本体（在仓库外） |
| `docs/acceptance/` | 全体 | 验收证据（截图 / 命令输出 / `_meta.md`） | APK / AAB / `*.log` 等构建产物 |
| `design/` | 设计（**只读**） | 71 个 HTML 设计稿 + 令牌 | — |

---

## 常用入口

| 目的 | 位置 |
|---|---|
| 文档总索引 | [docs/README.md](docs/README.md) |
| **文档基线（哪些文档现行有效、指纹）** | [docs/BASELINE.md](docs/BASELINE.md) |
| 68 个页面清单与跳转关系 | [docs/design/法泽APP_页面导航树_v1.4.md](docs/design/法泽APP_页面导航树_v1.4.md)（文件名 v1.4，内容 v1.5） |
| APP 设计稿本体（71 个 HTML） | [design/ui/](design/ui/) —— 从 `01-home.html` 起看 |
| 设计顺序与每屏依据 | [docs/design/法泽APP_UI设计顺序_v1.0.md](docs/design/法泽APP_UI设计顺序_v1.0.md)（内容 V3.0） |
| 设计令牌取值依据 | [docs/design/设计令牌.md](docs/design/设计令牌.md) |
| 工程规范 | [docs/standards/](docs/standards/) |
| 协作与 agent 规则 | [AGENTS.md](AGENTS.md) |
| 每一版改了什么 | [docs/CHANGELOG.md](docs/CHANGELOG.md) |

---

## 启动

```powershell
# 1) 起本地服务（MySQL / Redis），需要管理员 PowerShell（见 deploy/README.md）
.\deploy\start-services.ps1

# 2) 起服务端（段 0 A3 交付后可用；端口 8081，不是 8080）
cd services\faze-api
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 3) 起移动端（真机验收用 EAS 云构建的 preview 包，见 docs/standards/构建路径.md）
cd apps\mobile-app
npm ci
npx expo start
```

具体步骤与故障排查见 `deploy/README.md`（A2 产出）；APK 构建路径见 `docs/standards/构建路径.md`（D1 产出）。

---

## 工程约定（摘要，完整见 AGENTS.md）

- 页面实现放 `features/<domain>/pages/`，`app/` 下的路由文件只能是一行 re-export。
- 颜色、字号、间距、圆角禁止在页面内写字面量，一律来自 `shared/theme`。
- 所有面向用户的字符串走 `t()`，**4 种语言全做**（含两个 RTL 语言）。
- 禁止 `any`、`@ts-ignore`、`as unknown as`；禁止 `left` / `right`，一律 `start` / `end`。
- 金额一律整数分（`BIGINT`），禁浮点。
- 每个文件顶部必须有块注释：职责、调用方、数据来源、注意事项。

---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线 v1.0」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
