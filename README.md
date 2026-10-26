# 法泽 FAZE

> 普惠型法律服务平台 APP 的 UI 复刻工程。
> 本仓库是对既有 uni-app 项目 `FazeApp`（2026-04 打包）的**界面复刻**，不继承其代码与技术债。

## 这是什么

一份**纯 UI 复刻**：12 个页面、5 个底部 tab、完整页面跳转关系全部还原，数据为本地假数据，
后端接口在后续迭代中接入。当前目标只有一个——**让观感、版式、信息密度与原项目一致，但工程结构合规**。

原项目遗留的技术债清单见 [docs/analysis/原项目技术债清单.md](docs/analysis/原项目技术债清单.md)。

## 技术栈

| 项 | 选型 |
| --- | --- |
| 应用端 | Expo SDK 54 / React Native 0.81 / React 19 |
| 路由 | `expo-router` 6（`app/` 只放薄路由壳，页面实现位于 `features/`） |
| 语言 | TypeScript strict，禁止 `any` |
| 样式 | `StyleSheet` + 设计令牌（`shared/theme`），不引入 Tailwind 类方案 |
| 图标 | `@expo/vector-icons`（矢量），业务位图沿用原项目 PNG 素材 |
| 渐变 | `expo-linear-gradient` |
| 国际化 | 仅简体中文，不含藏文（原项目的藏文字体为死代码，已废弃） |
| 目标平台 | Android / iOS / Web 预览。**不做微信小程序** |

## 根目录导航

```text
apps/
  mobile-app/        APP（Expo Router）；app/ 只放薄路由，页面实现在 features/

design/
  tokens/            设计令牌唯一来源（颜色、字号、间距、圆角）
  legacy-assets/     从原项目迁入的位图素材（保持原文件，不做二次压缩）

docs/
  README.md          文档总索引
  standards/         目录命名、代码注释、代码审查三类工程规范
  design/            页面清单与信息架构、设计令牌说明、组件清单
  architecture/      运行时架构与目录职责边界
  analysis/          原项目技术债清单、参考项目规范提取

scripts/             启动、验证、静态检查的唯一脚本入口
```

## 常用入口

| 目的 | 位置 |
| --- | --- |
| 文档总索引 | [docs/README.md](docs/README.md) |
| 页面清单与跳转关系 | [docs/design/页面清单与信息架构.md](docs/design/页面清单与信息架构.md) |
| 设计令牌取值依据 | [docs/design/设计令牌.md](docs/design/设计令牌.md) |
| 12 页运行截图 | [docs/design/screenshots/](docs/design/screenshots/) |
| 代码注释规范 | [docs/standards/代码注释规范.md](docs/standards/代码注释规范.md) |
| 目录与命名规范 | [docs/standards/目录与命名规范.md](docs/standards/目录与命名规范.md) |
| 协作与 agent 规则 | [AGENTS.md](AGENTS.md) |

## 当前状态

12 个页面全部实现，`tsc --noEmit` 与 `eslint` 零错误零告警，Web 产物可构建、可运行。

| 项 | 数值 |
| --- | --- |
| 页面 | 12（5 个 tab + 3 个认证页 + 4 个二级页） |
| TypeScript 代码 | 约 4,650 行（含注释） |
| 文档 | 10 份，约 875 行 |
| 共享组件 | 8 个 |
| 设计令牌 | 颜色 22 项、字号 8 档、间距 9 档、圆角 5 档、尺寸 16 项 |

已知限制见 [apps/mobile-app/README.md](apps/mobile-app/README.md) 末节「尚未实现」。

## 启动

```bash
cd apps/mobile-app
pnpm install
pnpm run web        # 浏览器预览（无需 Android SDK）
pnpm run lint       # tsc --noEmit + eslint
```

真机调试使用 `pnpm run android` / `pnpm run ios`，需要本机 Android SDK 或 Expo Go。

## 工程约定（摘要）

- 页面实现放 `features/<domain>/pages/`，`app/` 下的路由文件只能是一行 re-export。
- 颜色、字号、间距、圆角禁止在页面内写字面量，一律来自 `shared/theme`。
- 每个页面文件顶部必须有块注释：页面用途、路由路径、数据来源、跳转去向。
- 假数据集中在 `features/<domain>/__fixtures__/`，页面内不得内联长数组。
- 详见 [docs/standards/](docs/standards/)。
