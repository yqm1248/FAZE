# AGENTS.md —— 本仓库的协作与实现规则

本文件约束所有在本仓库工作的开发者与 AI agent。优先级高于任何单次对话中的口头偏好。

## 1. 项目性质（先读这一条）

这是**纯 UI 复刻**工程。目标是让界面、版式、信息密度与原 uni-app 项目 `FazeApp` 一致，
同时用合规的工程结构承载它。当前阶段：

- **不接后端**。所有数据来自 `features/<domain>/__fixtures__/`。
- **不做微信小程序**。目标平台为 Android / iOS / Web 预览。
- **只做简体中文**。原项目的藏文（bo）语言包与 `tibetan.ttf` 已废弃，不得重新引入。
- **配色基线唯一**：`design/tokens/tokens.json` 的 `color` 段。原项目其余分散配色（青绿 `#2C9696`、
  红棕 `#A52A2A`、深蓝 `#154A94`、紫粉渐变 `#6A82FB→#FC5C7D`）**不作为主题色**，仅在
  `color.state` 中保留状态语义。

## 2. 代码放哪里

| 内容 | 位置 |
| --- | --- |
| 页面实现 | `apps/mobile-app/features/<domain>/pages/<Name>Page.tsx` |
| 路由壳 | `apps/mobile-app/app/**/*.tsx`，内容只能是一行 `export { default } from ...` |
| 该业务域私有组件 | `apps/mobile-app/features/<domain>/components/` |
| 两个以上业务域复用的组件 | `apps/mobile-app/shared/components/` |
| 假数据 | `apps/mobile-app/features/<domain>/__fixtures__/<name>.fixture.ts` |
| 类型 | `apps/mobile-app/features/<domain>/types.ts` |
| 设计令牌 | `apps/mobile-app/shared/theme/tokens.ts`（与 `design/tokens/tokens.json` 同步） |

禁止：在 `app/` 下写业务逻辑；在页面内写颜色/字号/间距字面量；在页面内内联超过 5 行的数组常量。

## 3. 必须做的事

1. **每个文件顶部必须有块注释**：职责、所属路由或调用方、数据来源、注意事项。写法见
   [docs/standards/代码注释规范.md](docs/standards/代码注释规范.md)。
2. **每个导出的函数/组件必须有注释**：说明输入、输出、副作用。
3. **所有视觉数值来自令牌**：`tokens.color.*`、`tokens.fontSize.*`、`tokens.spacing.*`、`tokens.radius.*`。
4. **新增依赖必须先说明理由**，并登记到 [docs/architecture/README.md](docs/architecture/README.md) 的依赖清单。
5. **改动设计令牌必须同步三处**：`tokens.json`、`tokens.ts`、`docs/design/设计令牌.md` 的变更记录。

## 4. 禁止做的事

- 禁止 `any`。接口与假数据必须有显式类型。
- 禁止使用 `rgba()` 字面量写颜色；透明度需求使用令牌中的既有色值或 `opacity` 样式属性。
- 禁止把原项目的 HTML 语义标签习惯带进来（`<div>`、`<h2>`、`<section>`）；RN 只有 `View` / `Text`。
- 禁止使用 `alert()` 作为提示；统一使用 `shared/components/AppToast` 或原生 `Alert.alert`。
- 禁止提交 `node_modules/`、`.expo/`、构建产物、`logs/`。
- 禁止为了让编译通过而放宽 `tsconfig` 的 `strict`。

## 5. 提交规则

一次提交只做一件事，提交信息格式：

```text
<类型>(<范围>): <做了什么>

<为什么这么做，影响哪些页面或令牌>
```

类型取 `feat` / `fix` / `docs` / `refactor` / `style` / `chore`。
范围取 feature 名（`home`、`lawyer`、`agent`、`case`、`profile`、`auth`、`progress`、`tools`）
或 `theme`、`docs`、`infra`。

## 6. 完成定义

一个页面算"做完"必须同时满足：

- [ ] 视觉与原页面逐项目视一致（对照 `E:\uniapp1\项目\FazeApp\pages\` 原文件）
- [ ] 文件顶部注释齐全，导出物有注释
- [ ] 无令牌外的视觉字面量
- [ ] 假数据已抽到 `__fixtures__`
- [ ] 路由壳已建，能从宿主页面跳进来
- [ ] `pnpm run lint` 通过（`tsc --noEmit` + `eslint`）
- [ ] 在 `docs/design/页面清单与信息架构.md` 的完成状态列标记为已完成
