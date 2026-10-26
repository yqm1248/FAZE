# 架构说明

## 1. 运行时形态

单一 Expo 应用，三种运行目标：

| 目标 | 命令 | 用途 |
| --- | --- | --- |
| Web | `pnpm run web` | 开发期主要验证方式，无需 Android SDK |
| Android | `pnpm run android` | 真机 / 模拟器调试 |
| iOS | `pnpm run ios` | 需 macOS |

无后端、无数据库、无鉴权服务。所有数据是 `features/<domain>/__fixtures__/` 里的常量。

## 2. 目录职责边界

```text
app/         路由壳。每个文件一行 export。不写逻辑、不写样式、不写状态。
   │        ────────► 只能 import：expo-router、shared/、features/*/pages/
features/    业务页面与私有组件。可以 import 本域内部物与 shared/。
   │        ────────► 不得 import 其他 feature
shared/      跨域复用与基础能力。不得 import features/。
assets/      静态资源。legacy/ 存原项目素材并统一导出常量。
```

依赖方向单向：`app` → `features` → `shared`。反向依赖视为设计错误。

## 3. 数据流

当前（纯 UI）：

```text
__fixtures__/*.fixture.ts  ──►  page  ──►  渲染
                                  │
                                  └──►  本地 state（筛选、输入、tab 选中）
```

接入后端后（不改页面结构）：

```text
API ──► Hook（features/<domain>/hooks/useXxx.ts）──► page ──► 渲染
                │
                └──►  loading / error 状态进 shared/components
```

页面只认 Hook 返回的形状，不直接 fetch。这样假数据换真接口时页面零改动。

## 4. 状态管理

**不引入全局状态库**。理由：

1. 当前各页之间无共享可变状态（登录态是唯一候选，用 `expo-secure-store` + 一个 Context 即可）。
2. 引入 Redux/Zustand 会产生"为将来准备"的空转代码，属于技术债。

需要时按此顺序升级：`useState` → `Context` → `Zustand`。升级前必须在本文档登记理由。

## 5. 依赖清单

所有运行时依赖及其引入理由。新增依赖必须先在此登记。

| 依赖 | 版本 | 理由 | 是否必需 |
| --- | --- | --- | --- |
| `expo` | SDK 54 | 应用框架 | 必需 |
| `expo-router` | ~6.0 | 文件路由；`app/` 薄壳 + `(tabs)` 分组能力的直接来源 | 必需 |
| `react` / `react-native` | 19.1 / 0.81 | Expo SDK 54 配套版本 | 必需 |
| `expo-linear-gradient` | ~15.0 | 首页 banner、详情页头图、个人中心头部三处渐变 | 必需（原项目大量使用 `linear-gradient`） |
| `expo-status-bar` | ~3.0 | 状态栏样式随页面底色变化 | 必需 |
| `react-native-safe-area-context` | ~5.6 | 刘海屏与底部安全区；`expo-router` 已间接依赖 | 必需 |
| `@expo/vector-icons` | ^15.0 | 矢量图标，替代原项目 24px 位图 | 必需 |
| `expo-image` | ~3.0 | 律师头像与素材的渐进加载、内存缓存 | 可选（也可用 RN `Image`） |
| `expo-splash-screen` | ~31.0 | 控制原生启动屏隐藏时机，避免与自制启动页重复 | 必需 |

**明确不引入**：

| 依赖 | 不引入理由 |
| --- | --- |
| `tailwindcss` / `uniwind` | 类名无法静态校验令牌使用，与规范冲突 |
| `zustand` / `redux` | 当前无全局状态需求 |
| `axios` | RN 内置 `fetch` 够用 |
| UI 组件库（react-native-paper 等） | 复刻需要精确控制视觉，组件库会带来覆盖成本 |
| `i18n-js` / `lingui` | 本期只做简体中文 |

## 6. 性能与包体

- 位图素材总量约 260 KB（`ls1-4.jpg` 占 222 KB），可直接随包。
- `ls1.jpg` 原图 1279×1698 而显示尺寸仅 65×80，后续接入 `expo-image` 时可交给服务端按需缩放。
- tab 图标 24×24 位图在高分屏会发虚，这是沿用原素材的已知代价，已在
  [../design/组件清单.md](../design/组件清单.md) 记录。

## 7. 扩展点

| 将来要做的 | 在哪里加 |
| --- | --- |
| 接入后端 | `shared/api/client.ts` + `features/<domain>/hooks/`；页面不改 |
| 登录态 | `shared/auth/AuthContext.tsx` + `expo-secure-store` |
| 多语言 | 引入 i18n 后，文案从 `features/<domain>/constants/` 抽取；禁止页面内建翻译表 |
| 新页面 | 先更新 `docs/design/页面清单与信息架构.md`，再建 feature 目录与路由壳 |
| 设计令牌调整 | 三处同步：`tokens.json`、`tokens.ts`、`docs/design/设计令牌.md` |
