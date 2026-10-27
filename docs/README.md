# FAZE 文档索引

本目录是法泽 APP 复刻工程的文档唯一入口。所有文档使用简体中文，术语与代码中的标识符保持一致。

## 按用途查找

| 我想知道 | 去哪里 |
| --- | --- |
| 每一版改了什么 | [CHANGELOG.md](CHANGELOG.md) |
| 这个仓库要做什么、技术栈是什么 | [../README.md](../README.md) |
| 有哪 12 个页面、怎么跳转 | [design/页面清单与信息架构.md](design/页面清单与信息架构.md) |
| 颜色/字号/间距取什么值、为什么 | [design/设计令牌.md](design/设计令牌.md) |
| 有哪些可复用组件 | [design/组件清单.md](design/组件清单.md) |
| 代码怎么写注释 | [standards/代码注释规范.md](standards/代码注释规范.md) |
| 文件放哪个目录、怎么命名 | [standards/目录与命名规范.md](standards/目录与命名规范.md) |
| 合并前要检查什么 | [standards/代码审查清单.md](standards/代码审查清单.md) |
| 运行时架构、目录职责边界、依赖清单 | [architecture/README.md](architecture/README.md) |
| 为什么不用原项目那套代码 | [analysis/原项目技术债清单.md](analysis/原项目技术债清单.md) |
| 这三份规范从哪来 | [analysis/参考项目规范提取.md](analysis/参考项目规范提取.md) |

## 文档目录结构

```text
docs/
  README.md                     本索引
  standards/                    工程规范（必须遵守）
    README.md
    目录与命名规范.md
    代码注释规范.md
    代码审查清单.md
  design/                       复刻依据（设计侧）
    README.md
    页面清单与信息架构.md
    设计令牌.md
    组件清单.md
  architecture/                 架构与依赖
    README.md
  analysis/                     决策依据与背景
    原项目技术债清单.md
    参考项目规范提取.md
```

## 文档维护规则

1. **文档先行**：新增页面或组件前，先在 `design/` 里写清它的用途、令牌取值和跳转关系。
2. **代码与文档同一提交**：改了行为就同时改文档，不允许"代码先合，文档下次补"。
3. **不做推测记录**：拿不准的内容标注"待确认"，不要写成结论。
4. **不复述代码**：文档写"为什么"和"边界"，不抄代码实现细节。
