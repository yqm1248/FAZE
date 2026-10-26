# 工程规范

本目录三份规范共同约束本仓库的代码质量。**全部为强制项，不是建议**。

| 文档 | 约束对象 | 违反后果 |
| --- | --- | --- |
| [目录与命名规范.md](目录与命名规范.md) | 文件放哪里、怎么命名、依赖方向 | 代码评审退回 |
| [代码注释规范.md](代码注释规范.md) | 类/文件/函数/常量/复杂逻辑的注释密度与写法 | 代码评审退回 |
| [代码审查清单.md](代码审查清单.md) | 合并前的逐项校验 | 不予合并 |

## 规范的来源

本规范不是凭空制定，是从三个既有项目提炼后适配到前端 Expo 场景：

| 参考项目 | 借鉴内容 |
| --- | --- |
| `D:\gitzxz\zxz-bi-server` | 类头 Javadoc 三段式（`@Description` / `@Author` / `@Date`）、方法注释的"动作+参数+返回"结构、复杂流程的阶段注释（`// 任务 n`）、金额与精度口径就地说明、规则类拆分到 `rule/` 子包 |
| `C:\Users\14541\Desktop\2026.7.13实习\wgcloud` | 根目录按 `config / log / src / 技术资料` 划分边界、Java 按 `controller / dto / entity / mapper / service / config / util` 分层、`CLAUDE.md` 形式的项目说明文件 |
| `E:\项目文件\jxyd200` | `apps/<端>/features/<域>/{pages,components,api,types}` 的 feature-first 目录、`app/` 薄路由 re-export、`features/_template` 模板目录、每个 feature 一份 README、`docs/` 按 `architecture/api/database/operation` 分类 |

详细的对应关系见 [../analysis/参考项目规范提取.md](../analysis/参考项目规范提取.md)。

## 与参考项目的差异（必读）

参考项目是 Java 后端 + 多端 monorepo，本项目是**单一 Expo 应用**，因此：

1. **不套用 Java 分层**。没有 Mapper、Service、Entity；对应位置是 `features/<domain>/` + `__fixtures__`。
2. **保留注释密度要求**，但把"类头 Javadoc"换成"文件头块注释"，把"`@param/@return`"换成
   JSDoc 风格的 `@param` / `@returns`。密度不降，形式适配。
3. **不引入 `uniwind`/`tailwind`**。参考项目用了 Tailwind v4 类方案，本项目只用 `StyleSheet`，
   原因是样式类字符串无法静态检查是否使用了令牌，与"禁止令牌外字面量"的规则冲突。
