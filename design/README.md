# design/ —— 设计依据目录

本目录承载**复刻所需的设计依据**，不承载可执行代码。

| 子目录 | 内容 | 是否参与构建 |
| --- | --- | --- |
| `tokens/` | 设计令牌唯一来源 `tokens.json` | 是（供 `apps/mobile-app/shared/theme` 对照实现） |

## 位图素材放在哪里

素材**不在本目录**，而是直接随应用走：`apps/mobile-app/assets/legacy/`。
它们通过 `apps/mobile-app/assets/legacy/index.ts` 统一导出常量，页面不直接写路径字符串。

这样安排的原因：素材是构建输入，放进 `design/` 会导致「同一份文件在两个地方维护」。
`design/` 只保留**决定长什么样**的东西（令牌、页面结构、配色映射），
`assets/` 保留**直接被打包**的东西。

## 令牌与代码的关系

`tokens/tokens.json` 是**取值依据**，`apps/mobile-app/shared/theme/tokens.ts` 是**运行时实现**。
两者必须同步修改：先改 JSON 并记录变更原因，再改 TS，最后在
[docs/design/设计令牌.md](../docs/design/设计令牌.md) 的变更记录中登记。

不引入自动生成脚本，原因是令牌数量有限（约 45 项）且需要逐项写中文说明；
自动生成会丢失注释。若后续令牌规模超过 60 项，再评估引入 `style-dictionary`。

## 原项目素材的对照方式

从原项目 `E:\uniapp1\项目\FazeApp\static\` 迁入的文件**保留原始文件名**，
包括其中的空格（例如 `if-group 2.png`、`fa5-history-fas 3.png`），
以保证与原项目逐文件可对照（`docs/design/组件清单.md` 第 3 节有对照表）。
