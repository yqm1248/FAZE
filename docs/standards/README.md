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

## 与参考项目的关系（必读）

> ⚠️ **本节原写于「纯 UI 复刻」阶段，当时本项目只有移动端、没有后端，所以结论是「不套用 Java 分层」。**
> 项目已转为**真实上线**，现在是 Expo 移动端 + Spring Boot 服务端 + RuoYi PC 后台三端，
> 与参考项目同构，**该结论已部分作废**。以下为现行口径。

参考项目 `D:\gitzxz\zxz-bi-server` 是 Java 后端，`E:\项目文件\jxyd200` 是多端 monorepo。现行规则：

1. **服务端套用 Java 分层**。`controller / dto / entity / mapper / service / config / util` 齐全，
   规则照搬 `zxz-bi-server`，落点为 `services/faze-api/src/main/java/com/faze/`。
2. **移动端不套用 Java 分层**。对应位置是 `features/<domain>/{pages,components,api,types}` + `hooks/`。
3. **注释密度要求三端一致，形式各自适配**：
   - Java：类头 Javadoc（`@Description` / `@Author` / `@Date`）+ 方法注释（动作、参数、返回）
   - TS：文件头块注释 + JSDoc 风格 `@param` / `@returns`
   - 密度不降，只是形式不同。
4. **移动端不引入 `uniwind`/`tailwind`**。参考项目用了 Tailwind v4 类方案，本项目只用 `StyleSheet`，
   原因是样式类字符串无法静态检查是否使用了令牌，与「禁止令牌外字面量」的规则冲突。
5. **PC 后台不改 RuoYi 框架层**。业务页面隔离在 `src/views/biz/`，确需改动走配置覆盖，
   并记录到 `docs/analysis/ruoyi-patches.md`。
