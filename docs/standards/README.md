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

1. **服务端套用 Java 分层**。`controller / bo / vo / converter / entity / mapper / service / config / util` 齐全，
   落点为 `services/faze-api/src/main/java/com/faze/`。**细则（分层禁令、行数上限、异常、事务、持久层、安全）
   以 [../architecture/法泽FAZE_后端代码规范_v1.0.md](../architecture/法泽FAZE_后端代码规范_v1.0.md) 为准**，
   本文只留三端通用的部分。
2. **移动端不套用 Java 分层**。对应位置是 `features/<domain>/{pages,components,api,types}` + `hooks/`。
3. **注释密度要求三端一致，形式各自适配**：
   - **Java：类头三行（职责 + 边界 + 约束），禁止 `@author` / `@Date` / `@version` / `@ClassName` / `@Copyright`**；
     方法注释写前置条件、副作用、参数来源。详见《后端代码规范》§四。
   - TS：文件头块注释 + JSDoc 风格 `@param` / `@returns`
   - 密度不降，只是形式不同。

   > **2026-10-30 修正**：本条原写「Java：类头 Javadoc（`@Description` / `@Author` / `@Date`）」，
   > 与《后端代码规范》§4.1 的禁令直接冲突。理由是三个参考项目里这套元数据全部腐坏：
   > ZXZ 的 `@Author` 248 处（**大写 A，非标准注解**）、`@Date` 4 种格式并存（含占位符 `@Date 2025/01/XX`），
   > wgcloud 的 `@date` 恒为「2021年1月16日」、从不随修改更新。**作者看 git blame，时间看 git log，不要在注释里重复一遍。**
4. **移动端不引入 `uniwind`/`tailwind`**。参考项目用了 Tailwind v4 类方案，本项目只用 `StyleSheet`，
   原因是样式类字符串无法静态检查是否使用了令牌，与「禁止令牌外字面量」的规则冲突。
5. **PC 后台不改 RuoYi 框架层**。业务页面隔离在 `src/views/biz/`，确需改动走配置覆盖，
   并记录到 `docs/analysis/ruoyi-patches.md`。
6. **后端细则不在本目录**（2026-10-30 新增）。服务端的分层、注释形式、异常、参数校验、事务、持久层、
   并发与任务表、日志、安全、工程性，以及**每条规则的承接手段（ArchUnit / checkstyle / gitleaks / JaCoCo）**，
   全部在 [../architecture/法泽FAZE_后端代码规范_v1.0.md](../architecture/法泽FAZE_后端代码规范_v1.0.md)。
   本目录的三份规范继续管三端通用部分（目录命名、注释密度、审查清单）。


---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线 v1.0」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
