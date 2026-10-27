# AGENTS.md —— 本仓库的协作与实现规则

本文件约束所有在本仓库工作的开发者与 AI agent。优先级高于任何单次对话中的口头偏好。

> **v2.0 重大变更（2026-10-27）**：项目定位从「纯 UI 复刻」改为「**真实上线产品**」。
> 旧版本中「不接后端」「只做简体中文」「藏文已废弃」三条全部作废，见第 1 节。

---

## 1. 项目性质（先读这一条）

「法泽」FAZE 是**面向新疆伊犁地区的普惠型法律服务平台**，依托伊犁师范大学法学专业实验基地。目标是**真实上线运营**，不是原型或演示。

| 维度 | 定论 |
| --- | --- |
| 交付目标 | 真实上线，用户可注册、可咨询、可下单、可付费 |
| 平台范围 | 移动端 APP（Android / iOS）+ 服务端 API + **PC 管理后台** |
| 语言 | **4 种全做**：简体中文 `zh-Hans`、藏文 `bo`、维吾尔文 `ug-Arab-CN`、哈萨克文 `kk-Arab-CN`（中国境内阿拉伯字母版，RTL） |
| 支付 | 平台账户余额制：界面展示「账户余额」，**充值走微信/支付宝，消费用余额抵扣** |
| 法条 | **必须有本地法条库**（民法典、民事诉讼法等），AI 只能引用库中存在的条文 |
| AI | 调用豆包大模型 API + 法条检索增强。**不做向量库**，用 MySQL 全文索引 |
| 微信小程序 | 不做 |

### 1.1 已确认的合规决策

| 事项 | 决策 |
| --- | --- |
| AI 备案 | **不做**。依据《生成式人工智能服务管理暂行办法》第 2 条第 3 款，未向境内公众提供服务的主体不适用；上线后需重新评估 |
| 主体注册 / App 备案 / ICP / 软著 / 等保 / 支付商户号 | **由项目负责人负责**（其具备相关经验）。技术侧只需保证功能与文档支撑，不代为决策 |
| 支付合规 | 采用账户余额模式：充值走三方渠道，消费用余额抵扣。仍需评估单用途预付卡备案要求 |

### 1.2 项目周期

**时间不是约束**，按质量为先。所有排期估算仅供参考，不因"要快"而砍范围或降低工程标准。

---

## 2. 技术栈（已锁定）

| 层 | 选型 |
| --- | --- |
| 移动端 | Expo SDK 54 / React Native 0.81 / React 19 / expo-router 6 / TypeScript strict |
| 服务端 | Java 17 / Spring Boot 3.5 / MyBatis-Plus 3.5.12 / MySQL 8.0 / Redis 7 |
| PC 后台 | **RuoYi-Vue3 低代码平台**（本地：`E:\itheima\vue\ry_vue3\RuoYi-Vue3`），已在 `jxyd200` 项目验证 |
| 实时通信 | 自建 WebSocket（`spring-boot-starter-websocket`）+ 第三方离线推送 |
| AI | 豆包 API（SSE 流式）+ 本地法条库检索 |
| 文书 | `poi-tl`（Word）+ **LibreOffice headless 或 Chromium 打印**（PDF）——`openhtmltopdf` 无法排阿拉伯系连写，不得使用 |

禁用清单见技术路线 **2.4 节**（版本以 `docs/architecture/` 下的最新版为准，当前为 v2.3）。

---

## 3. 代码放哪里

### 3.1 移动端

| 内容 | 位置 |
| --- | --- |
| 页面实现 | `apps/mobile-app/features/<domain>/pages/<Name>Page.tsx` |
| 路由壳 | `apps/mobile-app/app/**/*.tsx`，内容只能是一行 `export { default } from ...` |
| 该业务域私有组件 | `features/<domain>/components/` |
| 两个以上业务域复用的组件 | `shared/components/` |
| 数据请求 | `features/<domain>/api/` + `shared/api/client.ts` |
| 类型 | `features/<domain>/types.ts` |
| 设计令牌 | `shared/theme/tokens.ts`（与 `design/tokens/tokens.json` 同步） |

### 3.2 服务端

```text
services/faze-api/src/main/java/com/faze/
├─ common/        R 统一响应 / exception / constants / util / enums
├─ config/        安全、Redis、MyBatis-Plus、WebSocket 配置
├─ security/      JWT 过滤器、当前用户上下文
├─ modules/       业务域（account / lawyer / conversation / trade /
│                 document / caseflow / content / ai / ops）
└─ infra/         OSS、短信、推送、PDF、内容审核
```

### 3.3 PC 后台

基于 RuoYi-Vue3：业务页面放 `src/views/biz/<domain>/`，API 放 `src/api/biz/<domain>/`。
**不得修改 RuoYi 框架层代码**；确需改动时走配置覆盖或独立扩展，并记录到
`docs/analysis/ruoyi-patches.md`。

---

## 4. 必须做的事

1. **每个文件顶部必须有块注释**：职责、所属路由或调用方、数据来源、注意事项。写法见
   [docs/standards/代码注释规范.md](docs/standards/代码注释规范.md)。
2. **每个导出的函数/组件必须有注释**：说明输入、输出、副作用。
3. **所有视觉数值来自令牌**：`tokens.color.*`、`tokens.fontSize.*`、`tokens.spacing.*`、`tokens.radius.*`。
4. **所有面向用户的字符串走 `t()`**：包括 placeholder、`accessibilityLabel`、tabBar label、错误提示。
5. **金额一律用整数分**（`BIGINT`）：接口传分，前端负责展示转换。**禁止浮点运算**。
6. **状态迁移由服务端校验**：客户端只发意图，不发状态。
7. **新增依赖必须先说明理由**，并登记到 `docs/architecture/依赖清单.md`。
8. **改动设计令牌必须同步三处**：`tokens.json`、`tokens.ts`、`docs/design/设计令牌.md`。
9. **实施顺序以 `docs/architecture/法泽FAZE_分期交付计划_v1.0.md` 为准**：不得跳过第 0 段（没有可安装包则一切验收失效）；每段开始前先读该段章节并从验收清单逐条勾选；每段结束时在 `docs/CHANGELOG.md` 记录实际完成情况、验收结果与偏差。

## 5. 禁止做的事

- 禁止 `any`、`@ts-ignore`、`as unknown as`。
- 禁止使用 `rgba()` 或 `#RRGGBB` 字面量写颜色（令牌文件除外，ESLint 已强制）。
- 禁止 `left` / `right` 硬编码，一律 `start` / `end`（RTL 必需）。
- 禁止把原项目的 HTML 语义标签习惯带进来（`<div>`、`<h2>`）；RN 只有 `View` / `Text`。
- 禁止使用 `alert()`；统一 `AppToast` 或 `Alert.alert`。
- 禁止在业务代码里手写 `U+2066` 等 BiDi 控制符；一律走 `shared/components/LtrText`。
- 禁止把敏感信息（身份证影像、手写签名、聊天原文）放进日志、错误上报或公开可读的 OSS 路径。
- 禁止使用 `openhtmltopdf` 生成含少数民族文字的 PDF（不做 shaping）。
- 禁止为了让编译通过而放宽 `tsconfig` 的 `strict`。
- 禁止提交 `node_modules/`、`.expo/`、`dist/`、构建产物、`logs/`、真实凭据。

## 6. 提交规则

一次提交只做一件事，提交信息格式：

```text
<类型>(<范围>): <做了什么>

<为什么这么做，影响哪些页面或令牌>
```

类型取 `feat` / `fix` / `docs` / `refactor` / `style` / `chore`。
范围取业务域名（`account`、`lawyer`、`conversation`、`trade`、`document`、`caseflow`、`content`、`ai`、`ops`）
或 `theme`、`docs`、`infra`、`admin`。

## 7. 完成定义

一个页面算"做完"必须同时满足：

- [ ] 视觉与设计稿一致
- [ ] 文件顶部注释齐全，导出物有注释
- [ ] 无令牌外的视觉字面量
- [ ] 四语言文案已补齐（至少 `zh-Hans` + `ug-Arab-CN`）
- [ ] 页面四态（正常 / 加载 / 空 / 错误）全部处理
- [ ] 接口对接完成，错误码已归一化
- [ ] 路由壳已建，能从宿主页面跳进来
- [ ] `pnpm run lint` 通过（`tsc --noEmit` + `eslint` 零告警）
- [ ] 在 `docs/design/页面清单与信息架构.md` 的状态列标记为已完成

## 8. 文档索引

| 文档 | 位置 |
| --- | --- |
| **分期交付计划（最新，实施顺序以此为准）** | `docs/architecture/法泽FAZE_分期交付计划_v1.0.md` |
| 技术路线（最新） | `docs/architecture/法泽FAZE_技术路线_v2.3.md` |
| 页面导航树 | `docs/design/法泽APP_页面导航树_v1.4.md` |
| 变更日志（每次推送必看） | `docs/CHANGELOG.md` |
| 工程规范 | `docs/standards/` |
| 设计依据 | `docs/design/` |
| 决策记录 | `docs/analysis/` |

> 技术路线 v2.3 的 §23「实施计划」已整体废弃（按技术层横切、按周排期），
> **实施顺序只以《分期交付计划_v1.0》为准**。


---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线 v1.0」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
