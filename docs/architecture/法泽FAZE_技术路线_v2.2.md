# 法泽 FAZE —— 技术路线（V2.2）

> 版本：v2.2
> 日期：2026-10-27
> 对应页面导航树：`法泽APP_页面导航树_v1.4.md`（65 个页面）
> 项目性质：**正式上线产品（生产标准）**
> 参考工程：`zxz-bi-server`（工程规范）、`wgcloud`（安全细节）、`jxyd200`（RuoYi + Expo 多端实践）
> 工程规则：以仓库根目录 `AGENTS.md` v2.0 为准

## V2.2 变更记录（2026-10-27）

本版本基于对 V2.1 的六维度专家评审（架构数据层 / IM 支付 / AI 合规 / 多语言文书 / 实施计划 / 独立通读）与项目负责人确认，做了以下修改：

**一、项目负责人确认的决策**

| 项 | V2.1 | V2.2 |
| --- | --- | --- |
| 项目定位 | 正式上线 | **保持正式上线**，与 `AGENTS.md` v2.0 统一 |
| PC 管理后台 | 自选 Vue3 + Element Plus | **改用 RuoYi-Vue3 低代码平台**（`E:\itheima\vue\ry_vue3\RuoYi-Vue3`，已在 `jxyd200` 验证） |
| 支付 | 微信/支付宝直连 + 分账 | **改为平台账户余额制**：界面「账户余额」，充值走微信/支付宝，消费用余额抵扣 |
| AI 备案 | 拟做（2–3 个月） | **不做**。依据《生成式人工智能服务管理暂行办法》第 2 条第 3 款 |
| 法条 | 不建法条库，依赖模型知识 | **建本地法条库**（民法典、民诉法等，约 2000–3000 条）+ MySQL 全文检索 + 引用校验 |
| 哈萨克文 | `kk-Arab-CN` | **保持 `kk-Arab-CN`**（中国境内阿拉伯字母版，RTL），已确认 |
| 工期 | 22 周 | **不作为约束**，按质量推进 |

**二、修正的阻断级缺陷**

| # | 缺陷 | 处理 |
| --- | --- | --- |
| B1 | 6.4 接口加密在 RN 上无可用依赖（`expo-crypto` 不支持 AES-GCM/RSA） | **删除 6.4 全节**，改为"依赖 HTTPS + 证书固定" |
| B2 | 推送同时写 `expo-notifications` 与个推，二者互斥 | **统一为个推**（自有 SDK 通道，可控定向投递） |
| B3 | 全文只有 1 个 HTTP 端点，REST 契约缺失 | **新增第四章 4.8「接口清单」** |
| B4 | `case` 是 MySQL 保留字，MyBatis-Plus 不加反引号 | 表名改 **`legal_case`** |
| B5 | 无 APK 构建路线 | **新增 3.8「构建与发布」** |
| B6 | "XXL-Job 随 Boot"表述错误；Redisson 未列入依赖 | 修正表述 + 补依赖 |
| B7 | `openhtmltopdf` 排不出阿拉伯系连写 | **改用 LibreOffice headless / Chromium 打印**，删除 openhtmltopdf |
| B8 | RTL 切换缺 `forceRTL`，只 reload 方向不变 | **补 `forceRTL` + `allowRTL` + 持久化**，见 13.4 |
| B9 | "藏文字体置 fontFamily 主位、不挂 fallback 链尾"是 Web 思维 | 改为 **Unicode 区段切 run + `<LocalizedText>`** |

**三、修正的严重级缺陷**：见第五章 5.2–5.9 的 DDL 修订、第七章 IM、第九章支付、各节的索引补充。

**四、尚未处理、留待 V2.3 的**：见文末「附录 F：待修清单」。

---

## 目录

1. [项目概述](#一项目概述)
2. [技术栈总览](#二技术栈总览)
3. [移动端技术路线](#三移动端技术路线)
4. [后端技术路线](#四后端技术路线)
5. [数据库设计（完整 DDL）](#五数据库设计完整-ddl)
6. [安全方案](#六安全方案)
7. [WebSocket 与 IM 实时通信](#七websocket-与-im-实时通信)
8. [SSE 与 AI 流式传输](#八sse-与-ai-流式传输)
9. [支付与订单](#九支付与订单)
10. [文书与签章](#十文书与签章)
11. [AI 接入（豆包 API）](#十一ai-接入豆包-api)
12. [AI 合规与内容安全](#十二ai-合规与内容安全)
13. [多语言与 RTL](#十三多语言与-rtl)
14. [律师工作台](#十四律师工作台)
15. [管理后台](#十五管理后台)
16. [风控与内容审核](#十六风控与内容审核)
17. [运维监控体系](#十七运维监控体系)
18. [部署架构](#十八部署架构)
19. [测试体系](#十九测试体系)
20. [资质与合规](#二十资质与合规)
21. [工程规范](#二十一工程规范)
22. [业务闭环与异常流程](#二十二业务闭环与异常流程)
23. [实施计划](#二十三实施计划)
24. [交付物清单](#二十四交付物清单)
25. [附录](#二十五附录)

---

# 一、项目概述

## 1.1 产品定位

法泽 FAZE 是一款面向多民族地区的**移动法律服务平台**，核心能力：

- AI 智能法律顾问（接入豆包大模型，7×24 小时问答）
- 律师在线咨询（一对一 IM、图文/电话/文书服务）
- 法律文书生成（起诉状、证据清单、合同等，支持 PDF/Word 导出与电子签章）
- 案件委托与进度跟踪（在线立案、进度时间轴、案件送达）
- 多语言支持（简体中文 / 藏文 / 维吾尔文 / 哈萨克文）

## 1.2 产品形态

- **原生移动 App**（Android 优先，iOS 二期）
- 同一 App 内双角色：C 端用户 / 律师（身份切换）
- 管理后台：Web 端（运营、客服、审核、财务）
- 不做微信小程序

## 1.3 页面规模（65 页面）

| 模块 | 页面数 |
|---|---|
| 0 启动与认证 | 7 |
| 1 首页 | 6 |
| 2 律师服务 | 5 |
| 3 AI 智能体 | 2 |
| 4 案件中心 | 5 |
| 5 我的 | 0（入口聚合） |
| 6 文书中心 | 6 |
| 7 订单与支付 | 6 |
| 8 消息中心 | 2 |
| 9 全局搜索 | 1 |
| 10 设置与其他 | 18 |
| 11 律师工作台 | 7 |
| **合计** | **65** |

---

# 二、技术栈总览

## 2.1 移动端

| 项 | 选型 | 版本 |
|---|---|---|
| 框架 | Expo | SDK 54（`expo@~54.0.38`） |
| 运行时 | React Native | `0.81.5` |
| UI 框架 | React | `19.1.0` |
| 路由 | expo-router | `~6.0.24` |
| 语言 | TypeScript（strict） | `~5.8.3` |
| 图标 | @expo/vector-icons | `^15.0.3` |
| 渐变 | expo-linear-gradient | `~15.0.8` |
| 图片 | expo-image | `~3.0.11` |
| 动画 | react-native-reanimated | `~4.1.1` |
| 手势 | react-native-gesture-handler | `~2.28.0` |
| 手写签章 | @shopify/react-native-skia | `2.2.12` |
| 国际化 | i18next / react-i18next | `^25.6.0` / `^16.2.0` |
| SSE 流式 | react-native-sse | `^1.2.1` |
| 触感反馈 | expo-haptics | `~15.0.8` |
| 安全存储 | expo-secure-store | `~15.0.8` |
| 热更新 | expo-updates | `~29.0.12` |
| 相机 | expo-camera | `~17.0.10` |
| 相册 | expo-image-picker | `~17.0.11` |
| 文件选择 | expo-document-picker | `~14.0.7` |
| 文件系统 | expo-file-system | `~19.0.21` |
| 字体加载 | expo-font | `~14.0.11` |
| 推送 | **个推 SDK（Android 厂商通道 + iOS APNs 聚合）** | 最新 |
| 崩溃监控 | Sentry React Native | 最新 |
| 本地存储 | expo-sqlite | `~15.3.10` |

## 2.2 后端

| 项 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17（LTS） |
| 框架 | Spring Boot | 3.5.3 |
| Web 容器 | Undertow（替换 Tomcat） | 随 Boot |
| ORM | MyBatis-Plus | 3.5.12 |
| 数据库 | MySQL | 8.0（utf8mb4） |
| 缓存 | Redis | 7.x |
| 分布式锁 | **Redisson** | 3.35.x |
| 消息队列 | RabbitMQ | 3.13（管理界面） |
| 实时通信 | spring-boot-starter-websocket | 随 Boot |
| HTTP 客户端 | WebClient（spring-webflux） | 随 Boot |
| 接口文档 | springdoc-openapi | 2.8.9 |
| 构建 | Maven | 3.9+ |
| 工具库 | Hutool | 5.8.24 |
| Excel | EasyExcel | 4.0.3 |
| Word 模板 | poi-tl | 1.12.2 |
| **PDF 生成** | **LibreOffice headless（`soffice --convert-to pdf`）** | 7.x |
| PDF 叠图/水印 | Apache PDFBox | 2.0.x |
| JWT | jjwt | 0.12.6 |
| 加密 | BouncyCastle | 1.76 |
| 配置加密 | Jasypt | 3.0.5 |
| 数据库迁移 | Flyway | 10.x |
| 定时任务 | Spring `@Scheduled`（单机）；**XXL-Job 需独立部署调度中心 + 独立库，非"随 Boot"** | — |
| 对象存储 | 阿里云 OSS | 3.18.1 |
| 短信 | 阿里云短信服务 | 最新 |
| 推送 | **个推 RESTAPI（唯一推送通道）** | 最新 |
| 内容安全 | 阿里云内容安全 | 最新 |
| 链路追踪 | Micrometer Tracing + Zipkin | 随 Boot |
| 健康检查 | Spring Boot Actuator | 随 Boot |
| 密码哈希 | Spring Security Crypto（BCrypt） | 随 Boot |

## 2.3 管理后台

**采用 RuoYi-Vue3 低代码平台**，本地位置 `E:\itheima\vue\ry_vue3\RuoYi-Vue3`。已在 `jxyd200` 项目验证过完整落地。

| 项 | 选型 | 说明 |
|---|---|---|
| 基础平台 | **RuoYi-Vue3** | 自带用户/角色/菜单/字典/部门/操作日志/代码生成器 |
| 框架 | Vue 3 + Vite + TypeScript | RuoYi-Vue3 内置 |
| UI 库 | Element Plus | RuoYi-Vue3 内置 |
| 状态 | Pinia | RuoYi-Vue3 内置 |
| 请求 | Axios | 复用 RuoYi 的 `request.js` 封装 |
| 图表 | ECharts | RuoYi 已集成 |
| 权限 | RuoYi 菜单权限 + 数据权限注解 | **业务侧不重写权限体系** |

落地规则：

1. 业务页面放 `src/views/biz/<domain>/`，API 放 `src/api/biz/<domain>/`，与框架代码隔离。
2. **不得修改 RuoYi 框架层**；确需改动走配置覆盖或独立扩展，并记录到 `docs/analysis/ruoyi-patches.md`。
3. 后端鉴权与 RuoYi 的 `@PreAuthorize` / `@DataScope` 注解对接，不另起权限模型。
4. 代码生成器用于生成 CRUD 骨架，生成后必须按本仓库注释规范补齐注释。

## 2.4 明确不采用

| 不采用 | 理由 |
|---|---|
| Flutter | 团队技能不在 Dart；已有 12 页 RN 原型 |
| uni-app / Taro | 不做微信小程序 |
| 微服务 | 单人维护成本不可接受，单体起步 |
| Kubernetes | 单机足够，后期视规模 |
| 托管 IM SDK | 会话数据需落自有库、可审计 |
| 自建 AI 推理 | 无 GPU 预算，直接调豆包 API |
| **向量数据库** | **法条检索用 MySQL 全文索引（`ngram`）即可满足 2000–3000 条法规规模；但法条库必须建，见 11.6** |
| **`openhtmltopdf`** | **基于 PDFBox 2，不做 GSUB/GPOS shaping，阿拉伯文出孤立字形、藏文叠字错位。改用 LibreOffice headless** |
| **`expo-notifications`** | 与个推通道互斥，统一用个推 |
| **`expo-crypto` 做接口加密** | 只提供摘要与随机数，不支持 AES-GCM/RSA 加解密；HTTPS + 证书固定已足够 |
| **RuoYi 之外自建后台框架** | 已选定 RuoYi-Vue3，不重复造轮子 |
| Tailwind / Uniwind | 类名无法静态校验设计令牌 |
| Redux / Zustand | 全局状态仅登录态 + 语言态，Context 足够 |

---

# 三、移动端技术路线

## 3.1 工程结构

```text
apps/mobile-app/
├─ app/                          路由壳层，每个文件一行 re-export
│  ├─ _layout.tsx                根 Stack + Provider 聚合
│  ├─ index.tsx                  → SplashPage
│  ├─ (auth)/                    认证流 Stack（无 tab）
│  │  ├─ login.tsx register.tsx forgot-password.tsx
│  │  └─ agreement.tsx privacy.tsx
│  ├─ (tabs)/_layout.tsx         底部 5 tab
│  │  ├─ home.tsx lawyer.tsx agent.tsx case.tsx profile.tsx
│  ├─ article/                   文章列表 + 详情
│  ├─ lawyer/                    律师详情、入驻、审核
│  ├─ chat/[conversationId].tsx  IM 聊天页
│  ├─ agent-history.tsx          AI 对话历史
│  ├─ case/                      案例详情
│  ├─ filing/                    在线立案 + 成功
│  ├─ delivery.tsx               案件送达
│  ├─ document/                  文书模板/表单/预览/签章
│  ├─ order/                     订单/支付/退款/评价
│  ├─ message/                   消息中心 + 详情
│  ├─ search.tsx                 全局搜索
│  ├─ settings/                  设置/安全/认证/资料/收藏/客服/FAQ/投诉/反馈/关于/语言
│  ├─ workbench/                 律师工作台 7 页
│  └─ +not-found.tsx
├─ features/                     业务域（13 个）
│  ├─ auth/ home/ lawyer/ agent/ case/ document/
│  ├─ payment/ message/ search/ profile/ settings/
│  ├─ support/ workbench/
│  └─ _template/                 新域脚手架
├─ shared/
│  ├─ theme/       tokens.ts typography.ts shadows.ts
│  ├─ components/  Screen AppHeader AppCard PrimaryButton SectionTitle
│  │               TagChip SearchBar AppToast EmptyState LoadingOverlay
│  │               ListItem FormField BottomSheet ConfirmDialog
│  │               ImageViewer LtrText RtlText Skeleton
│  ├─ navigation/  routes.ts
│  ├─ i18n/        index.ts resources/ useLocale.ts
│  ├─ api/         client.ts crypto.ts error.ts types.ts
│  ├─ auth/        AuthContext.tsx tokenStorage.ts
│  ├─ monitor/     sentry.ts crash.ts
│  └─ utils/       date.ts money.ts validate.ts format.ts logger.ts
├─ assets/
│  ├─ legacy/      原项目位图素材
│  └─ fonts/       NotoSansTibetan/ NotoSansArabic/
├─ locales/        4 语言资源
├─ app.json app.config.ts package.json tsconfig.json
└─ eslint.config.mjs
```

## 3.2 路由约定

| 约定 | 规则 |
|---|---|
| 路由常量 | 集中在 `shared/navigation/routes.ts`，页面不写路径字符串 |
| 参数传值 | `useLocalSearchParams`，参数名与后端字段一致 |
| 详情页 | 动态段 `[id]` |
| 启动页 | `/`（`app/index.tsx`），2.5 秒后按登录态 `replace` |
| 鉴权重定向 | 未登录访问 🔒 页面，重定向到登录页并携带 `redirect` 参数 |
| 深链接 | URL Scheme：`faze://chat/123`；Universal Link：`https://m.faze.com/...` |

## 3.3 状态与数据流

```text
页面 ──► Hook（features/<domain>/hooks/useXxx.ts）──► shared/api/client.ts ──► 后端
              │
              └──► loading / error / empty / data 四态进 shared/components
```

规则：

1. 页面不直接 `fetch`，只调 Hook。
2. 全局状态只有两项：登录态（`AuthContext`）、语言态（`I18nProvider`）。
3. 其余状态用 `useState` + Hook 封装。
4. 接口返回统一形状 `{ code, msg, data }`，异常在 `shared/api/error.ts` 归一化。

## 3.4 设计令牌

| 令牌组 | 内容 |
|---|---|
| `color` | brand（primary `#4E6EF2` / primarySoft `#EEF2FF`）、background、text（四级）、border、state、banner |
| `gradient` | headerBanner `#D2E3FB → #F7F8FA`、splash、profileHeader |
| `fontSize` | 8 档（11 / 12 / 13 / 14 / 15 / 16 / 17 / 24） |
| `fontScale` | 适老化四档倍率：`0.875 / 1 / 1.15 / 1.3` |
| `spacing` | 9 档（2 / 6 / 8 / 12 / 15 / 20 / 25 / 30 / 50） |
| `radius` | 5 档（4 / 6 / 12 / 20 / 999） |
| `shadow` | card / cardRaised / pill |

约束：

- 页面禁止 `#RRGGBB`、`rgba()` 字面量，ESLint 强制拦截。
- 禁止 `left/right` 硬编码，一律 `start/end`（RTL 必需）。
- 字号使用 `fontSize × fontScale`。

## 3.5 原生能力映射

| 能力 | 依赖 | 用途 |
|---|---|---|
| 相机 | expo-camera | 身份证、执业证、证据拍照 |
| 相册 | expo-image-picker | 选择图片 |
| 文件 | expo-document-picker | PDF / Word 上传 |
| 文件系统 | expo-file-system | 下载、导出 |
| 安全存储 | expo-secure-store | token、加密密钥 |
| 本地数据库 | expo-sqlite | 离线缓存、历史记录 |
| 字体 | expo-font | 藏文、阿拉伯系加载 |
| 软重载 | expo-updates | RTL 切换后 `reloadAsync()` |
| 手写签章 | @shopify/react-native-skia | canvas 手写板 |
| 图片 | expo-image | 头像与素材缓存 |
| SSE | react-native-sse | AI 流式 |
| 推送 | expo-notifications + 个推 | 通知栏 |
| 触感 | expo-haptics | 按钮反馈、签章 |
| 崩溃监控 | Sentry | 崩溃上报 |

## 3.6 版本更新机制

| 类型 | 实现 |
|---|---|
| 检查更新 | 启动时 + 每次进入前台时调用 `/api/v1/app/version` |
| 可选更新 | 弹窗提示"发现新版本"，可跳过 |
| 强制更新 | 后端标记 `forceUpdate=true`，弹窗不可关闭，必须升级 |
| 热更新 | expo-updates 下发 JS bundle（不涉及原生模块变更时） |
| 版本号 | 语义化版本 `major.minor.patch`，如 `1.0.0` |

## 3.7 移动端其他生产能力

| 能力 | 说明 |
|---|---|
| 首次启动引导 | 3-4 页 Onboarding 介绍核心功能，可跳过 |
| 埋点统计 | 页面曝光、按钮点击、核心漏斗、自定义事件 |
| 图片压缩 | 上传前压缩到 1080p、质量 0.8，控制在 500KB 以内 |
| 键盘处理 | `KeyboardAvoidingView`、键盘类型匹配、回车跳焦 |
| 权限引导 | 拒绝后弹窗说明用途，引导去系统设置开启 |
| 无障碍 | `accessibilityLabel`、`accessibilityRole`、焦点顺序 |
| 微信分享 | 微信开放平台注册 App，分享律师名片/文章。**不分享小程序卡片**（本项目不做小程序，V2.1 此处与 2.4 矛盾） |
| 网络状态 | `NetInfo` 监听，断网全局提示，恢复后自动重试 |

## 3.8 构建与发布（V2.2 新增）

> **V2.1 的问题**：全文没有 APK 构建路线。而本机实测：Windows、**无 Android SDK**、C 盘仅剩约 20 GB。
> 这是一个会在第一天就爆的阻塞项。

### 3.8.1 三条可选路径

| 路径 | 前提 | 适用 |
|---|---|---|
| **A. EAS 云构建** | Expo 账号 + 构建额度（免费额度有限，超出付费） | **推荐起步**，本机无需 Android SDK |
| **B. 本地构建** | 装 Flutter 无关；需 Android SDK（约 10–15 GB）+ JDK 17，**必须装到 D/E 盘** | 长期主力，构建快、可控 |
| **C. 真机调试直连** | 仅需 Expo Go（无自定义原生模块时） | 开发期预热，不能出包 |

### 3.8.2 关键约束

| 约束 | 说明 |
|---|---|
| `Expo Go` 的边界 | 一旦接入**支付 SDK、个推、Skia 签名板**任一原生模块，Expo Go 立即不可用，必须切 development build |
| development build | `eas build --profile development`，装到真机后才能热重载 |
| 正式包 | `eas build --platform android --profile production` 出 AAB（上架）或 APK（内测） |
| keystore | **首次生成后必须离线备份**，丢失后无法覆盖更新已发布应用 |
| 版本号 | `app.json` 的 `version`（展示）+ Android `versionCode`（递增整数），两者都在提交里可追溯 |
| iOS | 需 Apple Developer 账号（$99/年）+ macOS 或 EAS 云构建 |

### 3.8.3 首个可验证目标

**S0 阶段的验收标准**：能产出一个可安装到真机的 APK，且能连上本地开发的后端。
**这一条不通，后面所有工作都无法验证。**

---

# 四、后端技术路线

## 4.1 工程结构

```text
services/faze-api/
├─ pom.xml
└─ src/main/java/com/faze/
   ├─ FazeApplication.java
   ├─ annotation/          @ApiEncrypt @LawyerOnly @UserOnly @OperationLog @RateLimit
   ├─ bo/                  业务对象
   ├─ common/
   │  ├─ R.java            统一响应
   │  ├─ constants/        常量、Redis key、Header 名
   │  ├─ enums/            通用枚举
   │  ├─ exception/        BusinessException + GlobalExceptionHandler
   │  └─ pojo/             PageQuery PageResult
   ├─ config/
   │  ├─ WebMvcConfig WebSocketConfig RedisConfig MybatisPlusConfig
   │  ├─ ThreadPoolConfig CryptoConfig OpenApiConfig WebClientConfig
   │  ├─ RabbitMQConfig RateLimitConfig FlywayConfig
   ├─ security/
   │  ├─ JwtFilter JwtUtil CurrentUser SecurityContext
   ├─ filter/
   │  ├─ CryptoFilter DecryptRequestBodyWrapper EncryptResponseBodyWrapper
   ├─ interceptor/
   │  ├─ AuthInterceptor RoleInterceptor
   ├─ modules/
   │  ├─ account/ lawyer/ workbench/ conversation/ trade/
   │  ├─ document/ caseflow/ content/ ai/ ops/ admin/
   ├─ converter/           MapStruct + 手写转换
   ├─ factory/             网关工厂、策略工厂
   ├─ strategy/            支付策略、状态机策略
   ├─ properties/          @ConfigurationProperties
   └─ infra/
      ├─ storage/ sms/ push/ pdf/ ai/ mq/ monitor/
src/main/resources/
├─ application.yml application-dev.yml application-prod.yml
├─ logback-spring.xml
├─ db/migration/           Flyway 版本化 SQL（V1__init.sql、V2__xxx.sql）
├─ mapper/                 MyBatis XML
└─ template/               Word 模板
```

## 4.2 业务域划分（11 域）

| 域 | 职责 | 关键约束 |
|---|---|---|
| `account` | 登录、注册、验证码、认证、注销、令牌 | 认证状态只在本域流转 |
| `lawyer` | 入驻四步、审核状态机、列表、收藏关注 | 审核状态只在本域流转 |
| `workbench` | 律师工作台：客户、订单、文书、收入、服务设置 | 仅 lawyer 角色 |
| `conversation` | 会话、消息、已读、撤回、附件 | `(user_id, lawyer_id)` 唯一 |
| `trade` | 订单状态机、支付回调、退款、评价 | 金额以回调为准，幂等 |
| `document` | 模板、动态字段、生成、草稿/成品、签章 | 生成走服务端 |
| `caseflow` | 立案、进度时间轴、送达 | 时间轴只追加 |
| `content` | 文章、案例、法条、多语言内容 | 多语言翻译表 |
| `ai` | 问答、豆包调用、会话记录 | 回答全量落库 |
| `ops` | 消息中心、投诉、FAQ、反馈、通知 | — |
| `admin` | 管理后台接口：用户/律师/订单/内容/数据看板 | 仅 admin 角色 |

## 4.3 分层架构

```text
Controller（参数校验、鉴权、路由）
    │
    ▼
Service（业务逻辑、事务边界、状态机）
    │
    ├──► Mapper（数据访问）
    ├──► Strategy / Factory（可扩展点）
    ├──► MQ（异步消息）
    └──► Infra（第三方：OSS、短信、推送、豆包）
    │
    ▼
Converter（Entity ↔ DTO ↔ BO）
```

依赖方向：`controller → service → mapper / infra`，禁止反向依赖、禁止跨域直接访问 mapper。

## 4.4 接口规范

| 项 | 规范 |
|---|---|
| 风格 | REST，`/api/v1/<domain>/<resource>` |
| 统一响应 | `{ code: number, msg: string, data: T }`；`code=200` 成功 |
| 分页请求 | `pageNum`（从 1 开始）+ `pageSize`（默认 10，最大 100） |
| 分页响应 | `{ list, total, pageNum, pageSize, hasMore }` |
| IM 消息分页 | 例外，用 `seq` 游标（不用 OFFSET） |
| 时间 | 统一 ISO 8601 字符串，库内存 `DATETIME` |
| 金额 | **整数分**（`BIGINT`），接口传分 |
| 鉴权 | `Authorization: Bearer <JWT>`，无状态；Redis 存黑名单 |
| 幂等 | 支付/下单接口带 `Idempotency-Key` 头，Redis 存 24 小时 |
| 版本 | URL 前缀 `/api/v1/`，不兼容升级走 v2 |

## 4.5 统一响应 R

```java
@Data
@NoArgsConstructor
public class R<T> implements Serializable {
    public static final int SUCCESS = 200;
    public static final int FAIL = 500;

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() { return restResult(null, SUCCESS, "操作成功"); }
    public static <T> R<T> ok(T data) { return restResult(data, SUCCESS, "操作成功"); }
    public static <T> R<T> fail(String msg) { return restResult(null, FAIL, msg); }
    public static <T> R<T> fail(int code, String msg) { return restResult(null, code, msg); }

    private static <T> R<T> restResult(T data, int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setData(data);
        r.setMsg(msg);
        return r;
    }
}
```

## 4.6 全局异常处理

`@RestControllerAdvice` 统一拦截，共 12 类：

| 异常 | HTTP 语义 | 处理 |
|---|---|---|
| `HttpRequestMethodNotSupportedException` | 405 | 返回不支持 |
| `MissingPathVariableException` | 400 | 提示缺失路径变量 |
| `MethodArgumentTypeMismatchException` | 400 | 参数类型不匹配 |
| `NoHandlerFoundException` | 404 | 路由不存在 |
| `IllegalArgumentException` | 400 | 参数非法 |
| `BindException` | 400 | 表单校验，错误信息拼接 |
| `ConstraintViolationException` | 400 | 约束校验 |
| `MethodArgumentNotValidException` | 400 | `@Valid` 校验 |
| `DuplicateKeyException` | 400 | 唯一键冲突 |
| `BusinessException` | 自定义码 | 业务异常 |
| `RuntimeException` | 500 | 兜底，记录完整堆栈 |
| `Exception` | 500 | 系统异常 |

原则：所有异常记录日志（含 URI），业务异常不打印完整堆栈，系统异常必须打印堆栈。

## 4.7 后端工程化能力

| 能力 | 说明 |
|---|---|
| **Flyway 数据库迁移** | 表结构变更版本化，`V1__init.sql`、`V2__add_xxx.sql`，启动自动执行，禁止手动改库 |
| **定时任务** | 订单超时取消（每分钟扫描）、提现处理（每小时）、每日统计（凌晨）、IM 离线消息清理 |
| **RabbitMQ 消息队列** | 异步发短信、推送、生成文书、内容审核，主流程不阻塞，失败重试 + 死信队列 |
| **Actuator 健康检查** | `/actuator/health`、`/actuator/metrics`，容器和负载均衡依赖 |
| **分布式锁** | Redisson，防止并发重复下单、重复提现、重复接单 |
| **服务降级** | 豆包 API 故障时返回兜底文案，不白屏；推送/短信故障不影响主流程 |
| **链路追踪** | Micrometer Tracing + Zipkin，traceId 贯穿 App→后端→豆包 |
| **ID 生成** | 雪花算法（Snowflake），订单号、消息 ID 全局唯一、趋势递增。**主键仍用 `BIGINT AUTO_INCREMENT`，雪花只用于业务单号**（V2.1 此处与 5.2 冲突，已明确） |
| **密码哈希** | BCrypt（cost=10），禁止 MD5/SHA 明文 |
| **线程池** | 参数化配置，拒绝策略、异常处理、优雅关闭 |

## 4.8 接口清单（V2.2 新增）

> **V2.1 的问题**：全文只有 1 个 HTTP 端点（SSE），支付/订单/回调/退款/提现的 REST 契约全部缺失。
> 本节列出全部接口分组；详细字段定义由 `springdoc-openapi` 生成，以 Swagger UI 为准。

### 4.8.1 路径与鉴权约定

| 前缀 | 鉴权 | 说明 |
|---|---|---|
| `/api/v1/pub/**` | 无 | 公开：验证码、协议文本、版本检查 |
| `/api/v1/app/**` | JWT | 移动端 |
| `/api/v1/admin/**` | JWT + `@PreAuthorize` | PC 后台（RuoYi 权限体系） |
| `/api/v1/internal/**` | 内网白名单 | 定时任务、服务间调用 |

### 4.8.2 接口分组

| 域 | 方法与路径 | 说明 |
|---|---|---|
| **account** | `POST /pub/sms/code` | 发送验证码（1/5/10 限流） |
| | `POST /pub/auth/register` | 注册 |
| | `POST /pub/auth/login/password` | 密码登录 |
| | `POST /pub/auth/login/sms` | 短信登录 |
| | `POST /app/auth/refresh` | 刷新令牌 |
| | `POST /pub/auth/reset-password` | 忘记密码三步 |
| | `GET /app/account/profile` · `PUT /app/account/profile` | 资料 |
| | `POST /app/account/realname` · `GET /app/account/realname` | 实名认证 |
| | `POST /app/account/enterprise` | 企业认证 |
| | `POST /app/account/logout` · `DELETE /app/account` | 退出 / 注销申请 |
| | `GET /app/account/devices` | 登录设备列表 |
| **lawyer** | `GET /app/lawyers` | 列表（分类、搜索、游标分页） |
| | `GET /app/lawyers/{id}` | 详情 |
| | `POST /app/lawyers/{id}/follow` · `DELETE` | 关注 |
| | `POST /app/lawyer-applications` | 入驻申请（四步） |
| | `GET /app/lawyer-applications/current` | 审核状态 |
| | `GET /app/lawyers/{id}/reviews` | 评价列表 |
| | `GET /admin/lawyer-applications` · `POST /admin/lawyer-applications/{id}/audit` | 后台审核 |
| | `GET /app/workbench/summary` | 律师工作台看板 |
| | `GET /app/workbench/conversations` | 律师侧会话列表 |
| | `PUT /app/workbench/online-status` | 在线状态 |
| | `PUT /app/workbench/service-settings` | 服务开关与定价 |
| **conversation** | `GET /app/conversations` | 会话列表 |
| | `POST /app/conversations` | 创建/获取会话（按 user+lawyer 唯一） |
| | `GET /app/conversations/{id}/messages` | 历史（`cursor` = seq） |
| | `WS /ws/chat` | 长连接（send / read / recall / typing） |
| | `POST /app/conversations/{id}/read` | 标记已读（REST 兜底） |
| **trade** | `GET /app/wallet/balance` | 账户余额 |
| | `GET /app/wallet/flows` | 资金流水 |
| | `POST /app/wallet/recharge` | 发起充值 → 返回支付参数 |
| | `POST /pub/pay/callback/{channel}` | 支付回调（微信/支付宝） |
| | `GET /app/orders` · `GET /app/orders/{id}` | 订单 |
| | `POST /app/orders` | 下单（扣余额或拉起充值） |
| | `POST /app/orders/{id}/pay` · `/cancel` · `/refund` | 意图接口 |
| | `POST /app/orders/{id}/confirm` | 律师接单/拒单/完成 |
| | `POST /app/orders/{id}/review` | 评价 |
| | `POST /app/withdrawals` · `GET /app/withdrawals` | 提现申请与记录 |
| | `GET /admin/orders` · `POST /admin/refunds/{id}/audit` | 后台审核 |
| **document** | `GET /app/doc-templates` | 模板列表 |
| | `POST /app/documents` | 创建/保存草稿 |
| | `POST /app/documents/{id}/generate` | 生成（Word/PDF） |
| | `GET /app/documents/{id}/preview` | 预览（签名 URL） |
| | `POST /app/documents/{id}/sign` | 提交签名笔迹 |
| | `GET /app/documents/{id}/export?format=pdf` | 导出 |
| | `GET /app/signatures` · `POST` · `DELETE` | 签名管理 |
| **caseflow** | `GET /app/cases` · `GET /app/cases/{id}` | 案件 |
| | `POST /app/cases` | 在线立案 |
| | `GET /app/cases/{id}/progress` | 进度时间轴 |
| | `POST /app/cases/{id}/materials` | 补充材料 |
| | `GET /app/case-deliveries` · `POST /{id}/confirm` | 送达 |
| **content** | `GET /app/articles` · `GET /app/articles/{id}` | 文章 |
| | `GET /app/case-library` · `GET /app/case-library/{id}` | 案例库 |
| | `GET /pub/law-items?kw=` | 法条检索（AI 与搜索共用） |
| | `GET /app/search?kw=&type=` | 全局搜索 |
| **ai** | `POST /app/ai/sessions` | 提交问题 |
| | `GET /app/ai/stream?sessionId=&fromSeq=` | SSE 订阅 |
| | `GET /app/ai/sessions` · `GET /{id}/messages` | 历史 |
| | `DELETE /app/ai/sessions/{id}` | 会话删除 |
| **ops** | `GET /app/notifications` · `POST /{id}/read` | 消息中心 |
| | `POST /app/complaints` · `POST /app/feedback` | 投诉 / 反馈 |
| | `GET /pub/faq` | 常见问题 |
| | `GET /pub/app/version` | 版本检查 |
| **infra** | `POST /app/upload/token` | 获取 OSS 直传凭证 |
| | `GET /app/upload/url?key=` | 获取私有资源签名 URL |

### 4.8.3 错误码统一（V2.2 修正）

V2.1 存在三套口径（HTTP 语义 / `1xxxx` 模块分段 / `R` 只有 200-500）。**V2.2 统一为一套**：

```text
code = 200        成功
code = 4xxxxx     客户端错误（参数、权限、状态非法）
code = 5xxxxx     服务端错误

第二段标识业务域（1 位）：
  1 通用    2 账户    3 律师    4 会话
  5 交易    6 文书    7 案件    8 AI
  9 内容    0 运营/后台

后三位为域内序号。
示例：400401 登录失败；500501 余额不足；500502 订单状态非法；800801 AI 服务不可用
```

`R` 统一响应体：

```java
public class R<T> {
    private int code;      // 200 或上述错误码
    private String msg;
    private T data;
    private String traceId;   // 便于排查
}
```

HTTP 状态码：**成功一律 200**（含业务失败也返回 200 + code），仅网关级错误（502/503/504）使用 HTTP 错误码。

---

# 五、数据库设计（完整 DDL）

## 5.1 表清单（V2.2：34 张表）

> V2.1 为 30 张。V2.2 新增 4 张：`user_balance`、`lawyer_balance`、`balance_flow`、`recharge_record`（见 5.5）；
> 另有 `law_item`（法条库）计入 5.8 内容层，`message_offline` 计入 5.4。

| 序号 | 表名 | 用途 |
|---|---|---|
| 1 | `app_user` | C 端用户表（后台管理员在 RuoYi 的 sys_user） |
| 2 | `lawyer` | 律师表 |
| 3 | `lawyer_application` | 律师入驻申请 |
| 4 | `realname_auth` | 实名认证 |
| 5 | `enterprise_auth` | 企业认证 |
| 6 | `conversation` | IM 会话 |
| 7 | `conversation_message` | IM 消息 |
| 8 | `orders` | 订单 |
| 9 | `payment_record` | 支付记录 |
| 10 | `refund_record` | 退款记录 |
| 11 | `review` | 评价 |
| 12 | `withdraw_record` | 律师提现 |
| 13 | `doc_template` | 文书模板 |
| 14 | `document` | 文书记录 |
| 15 | `signature` | 电子签章 |
| 16 | `case` | 委托案件 |
| 17 | `case_progress` | 案件进度 |
| 18 | `article` | 文章 |
| 19 | `article_translation` | 文章翻译 |
| 20 | `case_library` | 公开案例库 |
| 21 | `favorite` | 收藏 |
| 22 | `follow` | 关注律师 |
| 23 | `notification` | 消息通知 |
| 24 | `complaint` | 投诉 |
| 25 | `feedback` | 意见反馈 |
| 26 | `login_log` | 登录日志 |
| 27 | `operation_log` | 操作日志 |
| 28 | `ai_session` | AI 会话 |
| 29 | `ai_message` | AI 消息 |
| 30 | `faq` | 常见问题 |

## 5.2 通用约定

- 所有表：`id BIGINT AUTO_INCREMENT PRIMARY KEY`、`created_at`、`updated_at`、`deleted_at`（逻辑删除）。
- 字符集 `utf8mb4` / `utf8mb4_0900_ai_ci`。
- 索引命名：普通 `idx_<表>_<字段>`，唯一 `uk_<表>_<字段>`。
- 金额字段统一 `BIGINT`（单位：分），禁止 `FLOAT/DOUBLE`。
- 状态字段用 `VARCHAR(20)` 存枚举名。
- JSON 字段用 MySQL 8 原生 `JSON` 类型。

## 5.3 用户与认证

```sql
-- 1. 用户表（C 端用户）
-- V2.3 变更：表名 user → app_user。两个原因：
--   ① `USER` 在 MySQL 是函数名，容易与关键字混淆；
--   ② RuoYi-Vue3 自带 `sys_user`（PC 后台管理员），两者必须明确区分。
-- 本项目 app_user 承载 C 端用户与律师；PC 后台管理员一律走 RuoYi 的 sys_user。
CREATE TABLE app_user (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone           VARCHAR(20)  NOT NULL,
  password_hash   VARCHAR(100),
  nickname        VARCHAR(50),
  avatar_url      VARCHAR(500),
  gender          TINYINT      DEFAULT 0,        -- 0未知 1男 2女
  birth_date      DATE,                          -- 出生日期：未成年人识别（见 F-3-23）
  role            VARCHAR(20)  DEFAULT 'user',   -- user / lawyer / customer_service / admin
  lang            VARCHAR(20)  DEFAULT 'zh-Hans',
  realname_status VARCHAR(20)  DEFAULT 'none',   -- none / pending / approved / rejected
  is_minor        TINYINT      NOT NULL DEFAULT 0,  -- 14–18 岁标记
  guardian_phone  VARCHAR(20),                   -- 监护人手机号（未成年人必填）
  guardian_consent_at DATETIME,                  -- 监护人同意时间
  status          VARCHAR(20)  DEFAULT 'active', -- active / blocked / cancelled
  last_login_at   DATETIME,
  last_login_ip   VARCHAR(50),
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  deleted_at      DATETIME,
  UNIQUE KEY uk_app_user_phone (phone),
  KEY idx_app_user_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='C 端用户表；PC 后台管理员见 RuoYi 的 sys_user，两者不混用';

-- 2. 律师表
CREATE TABLE lawyer (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  real_name       VARCHAR(50) NOT NULL,
  license_number  VARCHAR(50) NOT NULL,
  license_image   VARCHAR(500),
  law_firm        VARCHAR(100),
  practice_years  INT,
  region          VARCHAR(100),
  specialties     JSON,                          -- ["婚姻家事","合同纠纷"]
  bio             TEXT,
  avatar_url      VARCHAR(500),
  consult_price_cents BIGINT DEFAULT 0,
  phone_price_cents   BIGINT DEFAULT 0,
  document_price_cents BIGINT DEFAULT 0,
  rating          DECIMAL(2,1) DEFAULT 5.0,
  review_count    INT DEFAULT 0,
  order_count     INT DEFAULT 0,
  online_status   VARCHAR(20) DEFAULT 'offline', -- online / busy / offline
  audit_status    VARCHAR(20) DEFAULT 'pending', -- pending / approved / rejected
  balance_cents   BIGINT DEFAULT 0,
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  deleted_at      DATETIME,
  UNIQUE KEY uk_lawyer_user (user_id),
  UNIQUE KEY uk_lawyer_license (license_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 律师入驻申请
CREATE TABLE lawyer_application (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  step            INT DEFAULT 1,                 -- 1-4
  form_data       JSON,
  real_name       VARCHAR(50),
  license_number  VARCHAR(50),
  license_image   VARCHAR(500),
  law_firm        VARCHAR(100),
  specialties     JSON,
  bio             TEXT,
  audit_status    VARCHAR(20) DEFAULT 'pending', -- pending / approved / rejected
  reject_reason   VARCHAR(500),
  audited_by      BIGINT,
  audited_at      DATETIME,
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  KEY idx_application_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 实名认证
CREATE TABLE realname_auth (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  real_name       VARCHAR(50) NOT NULL,
  id_card_number  VARCHAR(20) NOT NULL,
  id_card_front   VARCHAR(500) NOT NULL,
  id_card_back    VARCHAR(500) NOT NULL,
  status          VARCHAR(20) DEFAULT 'pending', -- pending / approved / rejected
  reject_reason   VARCHAR(500),
  audited_by      BIGINT,
  audited_at      DATETIME,
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  UNIQUE KEY uk_realname_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 企业认证
CREATE TABLE enterprise_auth (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL,
  company_name    VARCHAR(100) NOT NULL,
  credit_code     VARCHAR(30) NOT NULL,
  legal_person    VARCHAR(50) NOT NULL,
  license_image   VARCHAR(500) NOT NULL,
  contact_phone   VARCHAR(20),
  status          VARCHAR(20) DEFAULT 'pending',
  reject_reason   VARCHAR(500),
  audited_by      BIGINT,
  audited_at      DATETIME,
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  UNIQUE KEY uk_enterprise_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.4 IM 会话与消息

```sql
-- 6. IM 会话
CREATE TABLE conversation (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT      NOT NULL,
  lawyer_id     BIGINT      NOT NULL,
  last_message  VARCHAR(200),
  last_msg_at   DATETIME,
  unread_user   INT NOT NULL DEFAULT 0,
  unread_lawyer INT NOT NULL DEFAULT 0,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  deleted_at    DATETIME,
  UNIQUE KEY uk_conversation_user_lawyer (user_id, lawyer_id),
  KEY idx_conversation_lawyer_msg (lawyer_id, last_msg_at)   -- 律师侧会话列表（uk 最左前缀是 user_id，用不上）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. IM 消息
-- V2.2 变更：① 新增 client_msg_id 用于客户端去重；② seq 改唯一键（游标分页必需）；
--            ③ 已读改为双向字段；④ 撤回统一由 recalled_at 表达，msg_type 不再有 recall 值
CREATE TABLE conversation_message (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT      NOT NULL,
  seq             BIGINT      NOT NULL,         -- 会话内递增序号，由 Redis INCR conv:{id}:seq 生成
  client_msg_id   VARCHAR(64) NOT NULL,         -- 客户端生成的幂等键，用于重发去重
  sender_role     VARCHAR(10) NOT NULL,         -- user / lawyer / system / customer_service
  sender_id       BIGINT      NOT NULL,
  msg_type        VARCHAR(15) NOT NULL,         -- text / image / file / system（撤回不改此字段）
  content         TEXT,
  file_url        VARCHAR(500),
  file_name       VARCHAR(200),
  file_size       BIGINT,
  read_by_user_at   DATETIME,                   -- 用户侧已读时间
  read_by_lawyer_at DATETIME,                   -- 律师侧已读时间
  recalled_at     DATETIME,                     -- 非空即表示已撤回
  created_at      DATETIME NOT NULL,
  UNIQUE KEY uk_msg_conversation_seq (conversation_id, seq),
  UNIQUE KEY uk_msg_client_id (conversation_id, client_msg_id),
  KEY idx_msg_conversation_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7b. IM 离线消息（用于重连补推；V2.1 缺失）
CREATE TABLE message_offline (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT      NOT NULL,
  conversation_id BIGINT      NOT NULL,
  message_id      BIGINT      NOT NULL,
  seq             BIGINT      NOT NULL,
  created_at      DATETIME    NOT NULL,
  KEY idx_offline_user_seq (user_id, seq),
  KEY idx_offline_created (created_at)          -- 定时清理用
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.5 订单、支付、退款、评价、提现

```sql
-- 8. 订单
CREATE TABLE orders (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no           VARCHAR(32) NOT NULL,
  user_id            BIGINT NOT NULL,
  lawyer_id          BIGINT,
  service_type       VARCHAR(20) NOT NULL,   -- consult_text / consult_phone / document / case
  amount_cents       BIGINT NOT NULL,
  platform_fee_cents BIGINT DEFAULT 0,
  lawyer_income_cents BIGINT DEFAULT 0,
  status             VARCHAR(20) NOT NULL,   -- pending / paid / in_service / completed / cancelled / refunding / refunded
  paid_at            DATETIME,
  accepted_at        DATETIME,
  finished_at        DATETIME,
  cancelled_at       DATETIME,
  remark             VARCHAR(500),
  created_at         DATETIME NOT NULL,
  updated_at         DATETIME NOT NULL,
  deleted_at         DATETIME,
  UNIQUE KEY uk_orders_no (order_no),
  KEY idx_orders_user (user_id),
  KEY idx_orders_lawyer (lawyer_id),
  KEY idx_orders_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. 支付记录
CREATE TABLE payment_record (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id       BIGINT NOT NULL,
  channel        VARCHAR(20) NOT NULL,       -- wechat / alipay / mock
  transaction_id VARCHAR(64) NOT NULL,
  amount_cents   BIGINT NOT NULL,
  status         VARCHAR(20),                -- success / failed / pending
  raw_payload    TEXT,
  paid_at        DATETIME,
  created_at     DATETIME NOT NULL,
  UNIQUE KEY uk_payment_order_channel_txn (order_id, channel, transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 10. 退款记录
CREATE TABLE refund_record (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id       BIGINT NOT NULL,
  reason         VARCHAR(500),
  amount_cents   BIGINT NOT NULL,
  status         VARCHAR(20) NOT NULL,       -- applying / approved / rejected / refunded
  transaction_id VARCHAR(64),
  audited_by     BIGINT,
  audited_at      DATETIME,
  refunded_at    DATETIME,
  created_at     DATETIME NOT NULL,
  updated_at     DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 11. 评价
CREATE TABLE review (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  lawyer_id    BIGINT NOT NULL,
  rating       INT NOT NULL,                 -- 1-5
  tags         JSON,                         -- ["专业","响应快"]
  content      VARCHAR(500),
  lawyer_reply VARCHAR(500),
  is_anonymous TINYINT DEFAULT 0,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  UNIQUE KEY uk_review_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12. 律师提现
CREATE TABLE withdraw_record (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  lawyer_id       BIGINT NOT NULL,
  amount_cents    BIGINT NOT NULL,
  channel         VARCHAR(20),               -- wechat / alipay / bank / offline
  account_name    VARCHAR(50),
  account_number  VARCHAR(100),
  status          VARCHAR(20) NOT NULL,       -- applying / approved / rejected / paid
  transaction_id  VARCHAR(64),
  audited_by      BIGINT,
  audited_at      DATETIME,
  paid_at         DATETIME,
  reject_reason   VARCHAR(500),
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  deleted_at      DATETIME,
  KEY idx_withdraw_lawyer (lawyer_id, created_at),   -- 律师工作台提现记录
  KEY idx_withdraw_status (status, created_at)       -- 后台审核队列
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ══════════════════════════════════════════════════════════════
-- V2.2 新增：账户余额体系（支撑 9.6 的余额制支付）
-- 设计铁律：余额只能由 balance_flow 推导，不允许业务代码直接加减余额字段
-- ══════════════════════════════════════════════════════════════

-- 12a. 用户账户余额
CREATE TABLE user_balance (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  balance_cents BIGINT NOT NULL DEFAULT 0,   -- 可用余额（分）
  frozen_cents  BIGINT NOT NULL DEFAULT 0,   -- 冻结余额（分），如进行中订单占用
  version       INT NOT NULL DEFAULT 0,      -- 乐观锁版本号
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  UNIQUE KEY uk_user_balance_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12b. 律师账户余额（V2.1 只有 lawyer.balance_cents 单一字段，无法表达冻结）
CREATE TABLE lawyer_balance (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  lawyer_id          BIGINT NOT NULL,
  available_cents    BIGINT NOT NULL DEFAULT 0,  -- 可提现
  frozen_cents       BIGINT NOT NULL DEFAULT 0,  -- 冻结中（7 天）
  total_income_cents BIGINT NOT NULL DEFAULT 0,  -- 累计收入（含已提现）
  version            INT NOT NULL DEFAULT 0,
  created_at         DATETIME NOT NULL,
  updated_at         DATETIME NOT NULL,
  UNIQUE KEY uk_lawyer_balance_lawyer (lawyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12c. 资金流水（用户与律师共用，唯一的余额变动凭证）
CREATE TABLE balance_flow (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_type    VARCHAR(10) NOT NULL,        -- user / lawyer
  owner_id      BIGINT NOT NULL,
  biz_type      VARCHAR(20) NOT NULL,        -- recharge / consume / refund / income / withdraw / freeze / unfreeze / adjust
  biz_id        BIGINT,                      -- 关联的业务单号（订单/充值/提现/文书）
  delta_cents   BIGINT NOT NULL,             -- 正为入账，负为出账
  balance_after BIGINT NOT NULL,             -- 变动后余额快照，便于对账
  frozen_until  DATETIME,                    -- 冻结到期时间（income / freeze 用）
  remark        VARCHAR(200),
  created_at    DATETIME NOT NULL,
  UNIQUE KEY uk_flow_biz (owner_type, owner_id, biz_type, biz_id),  -- 幂等：同一业务同一类型只记一次
  KEY idx_flow_owner_time (owner_type, owner_id, created_at),
  KEY idx_flow_frozen (frozen_until)        -- 解冻定时任务扫描
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12d. 充值记录
CREATE TABLE recharge_record (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  order_no      VARCHAR(32) NOT NULL,        -- 平台充值单号
  channel       VARCHAR(20) NOT NULL,        -- wechat / alipay
  channel_txn_id VARCHAR(64),                -- 渠道交易号
  amount_cents  BIGINT NOT NULL,
  status        VARCHAR(20) NOT NULL,        -- pending / success / failed / closed
  paid_at       DATETIME,
  raw_payload   TEXT,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  UNIQUE KEY uk_recharge_order_no (order_no),
  UNIQUE KEY uk_recharge_channel_txn (channel, channel_txn_id),
  KEY idx_recharge_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **注**：`payment_record`（第 9 张表）在余额制下仅记录**充值**的渠道支付，`channel` 增加 `mock` 值用于沙箱；
> `orders` 的支付方式字段 `pay_method` 取值改为 `balance`（余额抵扣）/ `recharge`（直接拉起充值）。

## 5.6 文书与签章

```sql
-- 13. 文书模板
CREATE TABLE doc_template (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  code         VARCHAR(50) NOT NULL,
  name         VARCHAR(100) NOT NULL,
  category     VARCHAR(50),
  template_url VARCHAR(500),
  field_schema JSON,
  preview_url  VARCHAR(500),
  lang         VARCHAR(20) DEFAULT 'zh-Hans',
  sort_order   INT DEFAULT 0,
  status       VARCHAR(20) DEFAULT 'active',
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  UNIQUE KEY uk_template_code_lang (code, lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 14. 文书记录
-- V2.2 变更：① 补 lang（文书语言，多语言模板必需）；② 补 template_version 与 form_snapshot（历史文书可复现）；
--            ③ 补 rendered_snapshot（渲染时的字段快照，模板改动不影响历史文书）
CREATE TABLE document (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id          BIGINT NOT NULL,
  template_id      BIGINT,
  template_version INT,                          -- 生成时使用的模板版本
  lang             VARCHAR(20) NOT NULL DEFAULT 'zh-Hans',
  title            VARCHAR(200) NOT NULL,
  doc_type         VARCHAR(30),
  form_data        JSON,                         -- 当前表单数据
  form_snapshot    JSON,                         -- 生成时的表单快照（不可变）
  status           VARCHAR(20) NOT NULL DEFAULT 'draft',  -- draft / generated / signed / exported
  word_url         VARCHAR(500),
  pdf_url          VARCHAR(500),
  signed_pdf_url   VARCHAR(500),
  lawyer_id        BIGINT,
  created_at       DATETIME NOT NULL,
  updated_at       DATETIME NOT NULL,
  deleted_at       DATETIME,
  KEY idx_document_user (user_id, created_at),
  KEY idx_document_lawyer (lawyer_id),
  KEY idx_document_template (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 13 补充：模板版本控制（V2.1 的 UNIQUE(code,lang) 会让覆盖更新破坏历史文书）
ALTER TABLE doc_template ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE doc_template ADD COLUMN oss_key VARCHAR(500);   -- 不可变的对象键，每次修改生成新 key
-- 唯一键改为 (code, lang, version)，同一语言可多版本并存

-- 15. 电子签章（签名笔迹）
-- V2.2 变更：① 坐标补页号、单位、基准尺寸（原来的 position_x/y 无单位无法映射到 A4）；
--            ② document_hash 明确为「盖签前」内容流的哈希；③ 图片走私有 OSS
CREATE TABLE signature (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id        BIGINT NOT NULL,
  image_oss_key  VARCHAR(500) NOT NULL,      -- 私有 bucket 的对象键，不存公开 URL
  document_id    BIGINT,
  page_no        INT,                        -- 第几页（从 1 开始）
  pos_x_pt       INT,                        -- 相对页面左上角，单位：pt（1pt = 1/72 inch）
  pos_y_pt       INT,
  width_pt       INT,                        -- 签名图在页面上的宽度（pt）
  height_pt      INT,
  base_page_w_pt INT,                        -- 渲染时页面宽度（A4 = 595pt），用于校验缩放
  base_page_h_pt INT,                        -- A4 = 842pt
  document_hash  VARCHAR(128),               -- 对「盖签前」规范化内容流计算的 SHA-256
  hash_algo      VARCHAR(20) DEFAULT 'SHA-256',
  signed_at      DATETIME NOT NULL,          -- 同一文书多次签署时区分
  created_at     DATETIME NOT NULL,
  KEY idx_signature_document (document_id),
  KEY idx_signature_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.7 案件

```sql
-- 16. 委托案件
-- V2.2 变更：表名 case → legal_case。`case` 是 MySQL 保留字，
--            MyBatis-Plus 默认不给表名加反引号，@TableName("case") 生成的 SQL 会语法错误
CREATE TABLE legal_case (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_no       VARCHAR(32) NOT NULL,
  user_id       BIGINT NOT NULL,
  lawyer_id     BIGINT,
  title         VARCHAR(200),
  case_type     VARCHAR(30),
  description   TEXT,
  evidence_urls JSON,
  status        VARCHAR(20) NOT NULL DEFAULT 'filed', -- filed / accepted / in_progress / waiting_judgment / closed
  court_name    VARCHAR(100),
  amount_cents  BIGINT,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  deleted_at    DATETIME,
  UNIQUE KEY uk_legal_case_no (case_no),
  KEY idx_legal_case_user (user_id),
  KEY idx_legal_case_lawyer (lawyer_id),
  KEY idx_legal_case_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 17. 案件进度
CREATE TABLE case_progress (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id     BIGINT NOT NULL,
  step        INT NOT NULL,
  title       VARCHAR(100) NOT NULL,
  description TEXT,
  operator_id BIGINT,
  operator_role VARCHAR(20),
  created_at  DATETIME NOT NULL,
  KEY idx_progress_case (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.8 内容

```sql
-- 18. 文章
CREATE TABLE article (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  category          VARCHAR(30),
  cover_url         VARCHAR(500),
  source_updated_at DATETIME NOT NULL,
  status            VARCHAR(20) DEFAULT 'published',
  sort_order        INT DEFAULT 0,
  created_at        DATETIME NOT NULL,
  updated_at        DATETIME NOT NULL,
  deleted_at        DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 19. 文章翻译
CREATE TABLE article_translation (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  article_id      BIGINT NOT NULL,
  lang            VARCHAR(20) NOT NULL,
  title           VARCHAR(200),
  content         MEDIUMTEXT,
  status          VARCHAR(20),               -- missing / draft / reviewed
  translated_from DATETIME,
  UNIQUE KEY uk_article_lang (article_id, lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 20. 公开案例库
CREATE TABLE case_library (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_no         VARCHAR(50),
  title           VARCHAR(200) NOT NULL,
  court_name      VARCHAR(100),
  case_type       VARCHAR(30),
  judgment_date   DATE,
  summary         TEXT,
  content         MEDIUMTEXT,
  lang            VARCHAR(20) DEFAULT 'zh-Hans',
  view_count      INT DEFAULT 0,
  status          VARCHAR(20) DEFAULT 'published',
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  deleted_at      DATETIME,
  KEY idx_caselib_type (case_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.9 收藏、关注、通知、投诉、反馈、FAQ

```sql
-- 21. 收藏
CREATE TABLE favorite (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  target_type VARCHAR(20) NOT NULL,          -- article / case / document
  target_id   BIGINT NOT NULL,
  created_at  DATETIME NOT NULL,
  UNIQUE KEY uk_favorite_user_target (user_id, target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 22. 关注律师
CREATE TABLE follow (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  lawyer_id   BIGINT NOT NULL,
  created_at  DATETIME NOT NULL,
  UNIQUE KEY uk_follow_user_lawyer (user_id, lawyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 23. 消息通知
CREATE TABLE notification (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  type        VARCHAR(30) NOT NULL,          -- system / order / im / case / promotion
  title       VARCHAR(100) NOT NULL,
  content     VARCHAR(500),
  biz_type    VARCHAR(30),
  biz_id      BIGINT,
  is_read     TINYINT DEFAULT 0,
  read_at     DATETIME,
  created_at  DATETIME NOT NULL,
  KEY idx_notification_user (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 24. 投诉
CREATE TABLE complaint (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  target_type VARCHAR(20),                   -- lawyer / order / platform
  target_id   BIGINT,
  reason      VARCHAR(500) NOT NULL,
  images      JSON,
  status      VARCHAR(20) DEFAULT 'pending', -- pending / processing / resolved / rejected
  handler_id  BIGINT,
  result      VARCHAR(500),
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 25. 意见反馈
CREATE TABLE feedback (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  type        VARCHAR(30),
  content     VARCHAR(500) NOT NULL,
  images      JSON,
  contact     VARCHAR(50),
  status      VARCHAR(20) DEFAULT 'pending',
  created_at  DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 30. 常见问题
CREATE TABLE faq (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  category    VARCHAR(30),
  question    VARCHAR(200) NOT NULL,
  answer      TEXT NOT NULL,
  lang        VARCHAR(20) DEFAULT 'zh-Hans',
  sort_order  INT DEFAULT 0,
  status      VARCHAR(20) DEFAULT 'active',
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.10 日志与 AI

```sql
-- 26. 登录日志
CREATE TABLE login_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT,
  phone       VARCHAR(20),
  ip          VARCHAR(50),
  device      VARCHAR(200),
  platform    VARCHAR(20),
  success     TINYINT NOT NULL,
  fail_reason VARCHAR(100),
  created_at  DATETIME NOT NULL,
  KEY idx_loginlog_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 27. 操作日志
CREATE TABLE operation_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT,
  module      VARCHAR(30),
  action      VARCHAR(50),
  description VARCHAR(200),
  method      VARCHAR(200),
  request_url VARCHAR(500),
  request_param TEXT,
  ip          VARCHAR(50),
  cost_ms      BIGINT,
  created_at   DATETIME NOT NULL,
  KEY idx_oplog_user (user_id),
  KEY idx_oplog_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 28. AI 会话
CREATE TABLE ai_session (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT NOT NULL,
  title       VARCHAR(200),
  lang        VARCHAR(20),
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  deleted_at  DATETIME,
  KEY idx_ai_session_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 29. AI 消息
CREATE TABLE ai_message (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id      BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  role            VARCHAR(10) NOT NULL,       -- user / assistant / system
  content         MEDIUMTEXT,
  model           VARCHAR(50),
  prompt_tokens   INT,
  complete_tokens INT,
  refs            JSON,                       -- 引用的法条
  lang            VARCHAR(20),
  safety_passed   TINYINT DEFAULT 1,
  created_at      DATETIME NOT NULL,
  KEY idx_ai_message_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.11 ER 关系说明

> **V2.3 更新**：本文的实体名已按 v2.3 的改名同步——
> `user` → `app_user`，`case` → `legal_case`，`favorite`/`follow` → `social_action`。

```text
# ── 账户与身份 ──
app_user 1───0..1 lawyer
app_user 1───0..1 realname_auth
app_user 1───0..1 enterprise_auth
app_user 1───* user_device
app_user 1───* user_violation
app_user 1───1 user_balance
app_user 1───* balance_flow          （owner_type='user'）

# ── 律师 ──
lawyer 1───* lawyer_application 1───* lawyer_application_log
lawyer 1───1 lawyer_balance
lawyer 1───* balance_flow            （owner_type='lawyer'）
lawyer 1───* withdraw_record
lawyer 1───* review

# ── 会话 ──
app_user 1───* conversation *───1 lawyer
conversation 1───* conversation_message
conversation 1───* message_offline
conversation_message 1───0..* content_review

# ── 交易 ──
app_user 1───* orders *───0..1 lawyer
orders 1───* payment_record
orders 1───0..1 refund_record
orders 1───0..1 review
orders 1───0..1 invoice
app_user 1───* recharge_record
recharge_record 1───0..* reconciliation_diff

# ── 文书 ──
app_user 1───* document
doc_template 1───* document          （按 template_id + template_version）
document 1───* signature

# ── 案件 ──
app_user 1───* legal_case *───0..1 lawyer
legal_case 1───* case_progress

# ── 内容 ──
article 1───* article_translation
case_library 1───* case_library_translation
law_item （独立，被 AI 与文书引用）

# ── 社交 ──
app_user 1───* social_action          （favorite / follow / like / history 统一表）

# ── 运营 ──
app_user 1───* notification
app_user 1───* complaint
app_user 1───* feedback
app_user 1───* ai_session 1───* ai_message
app_user 1───* service_ticket *───0..1 agent

# ── 基础设施 ──
app_user 1───* file_record
app_user 1───* login_log
operator → operation_log              （operator_type 区分 user/lawyer/admin）
```

---

# 六、安全方案

## 6.1 认证（JWT）

| 项 | 方案 |
|---|---|
| Token 类型 | Access Token（2 小时）+ Refresh Token（30 天） |
| 签名算法 | HS256（或 RS256） |
| Payload | `userId`、`role`、`jti`、`iat`、`exp` |
| 存储 | 移动端 expo-secure-store；后端 Redis 黑名单 |
| 刷新 | Access 过期后用 Refresh 静默换新，失败跳登录 |
| 注销 | JWT `jti` 加入黑名单 |

## 6.2 授权（角色权限）

> **命名说明**：下表第一列是**角色枚举值**（对应 `app_user.role` 字段），不是表名。
> 表名是 `app_user`（C 端用户），角色值才是 `'user'`。

| 角色 | 权限 |
|---|---|
| `user` | C 端全部功能 |
| `lawyer` | 继承 user + 律师工作台（`@LawyerOnly`） |
| `customer_service` | 继承 user + 客服工单与会话 |
| `admin` | 管理后台（实际走 RuoYi 的 `sys_user`，一般不建 `app_user` 记录） |

实现：
- `JwtFilter` 解析 token，写入 `SecurityContext`
- `@LawyerOnly` 注解 + AOP 拦截，非律师返回 403
- 数据级权限：查询强制带 `userId`/`lawyerId`

## 6.3 登录安全

| 机制 | 规则 |
|---|---|
| 失败锁定 | 同一手机号连续 5 次密码错误，锁定 10 分钟 |
| 短信限流 | 1 分钟 1 次、1 小时 5 次、1 天 10 次 |
| 密码复杂度 | 8-16 位，必须含字母+数字 |
| 密码哈希 | BCrypt（cost=10） |
| 密码过期 | 90 天提示修改 |
| 登录日志 | 账号、IP、设备、成功/失败 |
| 多端登录 | 默认单设备，新登录踢旧设备 |

## 6.4 传输安全（原「接口加密」已移除）

> **V2.2 变更**：V2.1 的 6.4「接口加密（RSA+AES body 加密）」**已删除**。
> 原因：所选技术栈上无法实现——`expo-crypto` 只提供摘要与随机数，**不支持 AES-GCM / RSA 加解密**，
> React Native 侧没有可用的加密库；且自研包体加密与 SSE 流式、文件上传、大响应都会冲突，
> 收益远低于复杂度。全站 HTTPS 已覆盖传输安全。

替代方案：

| 项 | 措施 |
|---|---|
| 传输层 | 强制 TLS 1.2+，禁用弱套件，HSTS |
| 证书固定 | App 端对 API 域名做 certificate pinning（`expo-network`/原生网络库配置） |
| 敏感字段二次保护 | 身份证号、手机号在服务端加密存储（AES-256-GCM），**传输层不额外加密** |
| 令牌 | JWT 走 `Authorization` 头，不使用 URL query 或 Cookie |
| 防重放 | 关键写接口带 `Idempotency-Key` + 时间戳，Redis 校验 |

## 6.5 配置加密（Jasypt）

数据库密码、Redis 密码、豆包 API Key、短信密钥、OSS 密钥禁止明文：

```yaml
spring:
  datasource:
    password: ENC(密文)
doubao:
  api-key: ENC(密文)
```

主密钥通过环境变量 `JASYPT_ENCRYPTOR_PASSWORD` 注入。

## 6.6 接口限流

| 接口 | 规则 |
|---|---|
| 发送短信 | 1 分钟 1 次 / 1 天 10 次 |
| 登录 | 1 分钟 10 次（按 IP） |
| AI 对话 | 每用户每天 50 轮 |
| 通用 | 单 IP 每秒 20 次 |

## 6.7 其他安全

| 项 | 措施 |
|---|---|
| SQL 注入 | MyBatis 参数化，禁止拼接 SQL |
| XSS | 富文本 Jsoup whitelist 过滤 |
| 文件上传 | 白名单后缀、魔数校验、大小限制、重命名 |
| 越权 | 数据查询强制带当前用户 ID |
| 敏感数据 | 身份证、手机号脱敏，日志脱敏 |
| HTTPS | 全站强制 HTTPS，WebSocket 走 wss |
| 依赖扫描 | OWASP Dependency-Check，CI 中扫描第三方依赖漏洞 |

---

# 七、WebSocket 与 IM 实时通信

## 7.1 链路

```text
用户 App ──WebSocket(wss)──► Spring WebSocketHandler
                                │
                                ├─ 握手：JWT 校验 → 绑定 userId / role
                                ├─ 在线表：Redis im:online:{userId}
                                ├─ 消息落库：conversation_message
                                └─ 推送给对端（在线直推，离线走推送）
```

## 7.2 WebSocket 消息格式

所有 WebSocket 消息统一 JSON 信封，通过 `type` 字段区分：

```json
{
  "type": "message",
  "id": "1730000000000-0",
  "timestamp": "2026-10-27T10:00:00.000+08:00",
  "data": { }
}
```

### 7.2.1 客户端 → 服务端

| type | 用途 | data 字段 |
|---|---|---|
| `chat.send` | 发送消息 | `conversationId`、`msgType`（text/image/file）、`content`、`clientMsgId` |
| `chat.read` | 上报已读 | `conversationId`、`lastReadSeq` |
| `chat.recall` | 撤回消息 | `messageId` |
| `chat.typing` | 输入状态 | `conversationId`、`isTyping`（true/false） |
| `chat.history` | 拉历史 | `conversationId`、`beforeSeq`、`limit` |
| `ping` | 心跳 | 无 |

**发送消息示例：**

```json
{
  "type": "chat.send",
  "id": "1730000000000-0",
  "timestamp": "2026-10-27T10:00:00.000+08:00",
  "data": {
    "conversationId": 12,
    "msgType": "text",
    "content": "你好，我想咨询离婚财产分割",
    "clientMsgId": "c-abc123"
  }
}
```

### 7.2.2 服务端 → 客户端

| type | 用途 | data 字段 |
|---|---|---|
| `chat.ack` | 消息回执（发送成功） | `clientMsgId`、`messageId`、`seq`、`createdAt` |
| `chat.message` | 新消息（对端发来） | `messageId`、`conversationId`、`seq`、`senderRole`、`senderId`、`msgType`、`content`、`fileUrl`、`createdAt` |
| `chat.history` | 历史消息列表 | `conversationId`、`messages[]`、`hasMore` |
| `chat.read` | 已读回执 | `conversationId`、`readerRole`、`lastReadSeq` |
| `chat.recall` | 撤回通知 | `messageId`、`conversationId` |
| `chat.typing` | 对方输入状态 | `conversationId`、`isTyping` |
| `chat.error` | 错误 | `code`、`msg`、`clientMsgId` |
| `pong` | 心跳响应 | 无 |

**新消息推送示例：**

```json
{
  "type": "chat.message",
  "id": "1730000001000-0",
  "timestamp": "2026-10-27T10:00:01.000+08:00",
  "data": {
    "messageId": 8801,
    "conversationId": 12,
    "seq": 105,
    "senderRole": "lawyer",
    "senderId": 9,
    "msgType": "text",
    "content": "您好，请问房产是婚前还是婚后购买？",
    "fileUrl": null,
    "createdAt": "2026-10-27T10:00:01.000+08:00"
  }
}
```

**错误示例：**

```json
{
  "type": "chat.error",
  "id": "1730000002000-0",
  "timestamp": "2026-10-27T10:00:02.000+08:00",
  "data": {
    "code": 40301,
    "msg": "您不是该会话成员",
    "clientMsgId": "c-abc123"
  }
}
```

## 7.3 数据隔离（V2.2 扩展为「全部操作」校验）

> **V2.1 的问题**：三条规则只覆盖 `history` 与 `send` 两个操作。
> `chat.read` / `chat.recall` / `chat.typing` **没有成员校验**——客户端只要拿到别人的 `messageId`
> 就能撤回他人消息、篡改他人未读数。

### 7.3.1 统一鉴权入口

**所有 WebSocket 消息与 REST 接口，进入业务逻辑前必须先过同一个成员校验函数：**

```java
/**
 * 校验当前用户是否为该会话的合法成员，并返回其角色。
 * 任何 IM 操作都不得绕过此方法。
 *
 * @param conversationId 会话 ID
 * @param currentUserId  从 JWT 解析出的用户 ID（绝不从请求体取）
 * @return 该用户在此会话中的角色（user / lawyer / customer_service）
 * @throws BizException 会话不存在、或该用户不是成员时抛出 4004xx
 */
ConversationMember assertMember(Long conversationId, Long currentUserId);
```

### 7.3.2 逐操作校验要求

| 操作 | 必须校验 | 说明 |
|---|---|---|
| `chat.history` | 成员 + 游标范围 | 只传 `conversationId` |
| `chat.send` | 成员 | `senderId` 取自 JWT，**不信任客户端** |
| `chat.read` | 成员 + **消息属于该会话** | V2.1 缺失：未校验 messageId 归属 |
| `chat.recall` | 成员 + **消息归属该会话** + **发送者是自己** + **2 分钟内** | V2.1 缺失：只传 messageId，可撤回他人消息 |
| `chat.typing` | 成员 | V2.1 缺失 |
| REST `GET /conversations/{id}/messages` | 成员 | — |
| REST `POST /conversations/{id}/read` | 成员 | — |

### 7.3.3 分页与发送

- 分页用 `seq` 游标（`WHERE conversation_id=? AND seq < ? ORDER BY seq DESC LIMIT 20`），**不用 OFFSET**
- `seq` 由 Redis `INCR conv:{id}:seq` 生成，配合 `UNIQUE(conversation_id, seq)` 保证不重复
- 客户端重发时带 `client_msg_id`，服务端靠 `UNIQUE(conversation_id, client_msg_id)` 去重

## 7.4 已读、撤回、重连、心跳（V2.2 修正）

| 功能 | 实现 |
|---|---|
| 已读 | 上报 `lastReadSeq` → 更新 `read_by_user_at` / `read_by_lawyer_at`（**双向字段**，V2.1 单字段无法表达双方已读）→ 推送对端 |
| 撤回 | 2 分钟内且**发送者本人**可撤回；置 `recalled_at`，**`msg_type` 不改**（V2.1 两处语义矛盾）；历史接口对已撤回消息返回 `{ recalled: true }` 且**不回传原文** |
| 重连 | 携带 `lastReceivedSeq`，服务端从 `message_offline` 与 `conversation_message` 补推；补推完成后清 `message_offline` |
| 心跳 | 客户端每 30 秒 ping；**服务端 90 秒无心跳则主动关闭连接并清理在线表**（V2.1 只判离线，没有清理与踢旧设备） |
| 多端登录 | 同一账号在新设备登录时，**踢掉旧连接**并推送 `chat.kicked`，避免消息错乱 |
| 输入状态 | 一方输入时推 `chat.typing`，3 秒无输入自动消失 |

## 7.5 离线推送

| 平台 | 通道 |
|---|---|
| Android | 华为/小米/OPPO/vivo 厂商通道 + 个推聚合 |
| iOS | APNs（经个推） |

推送只带"您有一条新消息"，不携带正文（隐私）。

---

# 八、SSE 与 AI 流式传输

## 8.1 链路

```text
App ──SSE(GET /api/v1/ai/stream)──► 后端
                                      │
                                      ├─ 拼装 Prompt（系统指令 + 历史 + 语言）
                                      ├─ 调用豆包 API（SSE 流式）
                                      ├─ 逐 token 转发给 App
                                      └─ 结束后全量落 ai_message
```

## 8.2 SSE 消息格式

SSE 每条消息为标准 `event: xxx\ndata: {...}\n\n`：

### 8.2.1 事件类型

| event | 用途 | data 字段 |
|---|---|---|
| `start` | 会话开始 | `sessionId`、`messageId`、`model` |
| `delta` | 文本增量（逐 token） | `content`（增量片段） |
| `refs` | 法条引用（模型输出引用时） | `refs[]`（lawName、articleNo、content） |
| `done` | 完整结束 | `messageId`、`finishReason`、`promptTokens`、`completeTokens` |
| `error` | 错误 | `code`、`msg` |

### 8.2.2 消息示例

```text
event: start
data: {"sessionId":1024,"messageId":5001,"model":"doubao-seed-1.6"}

event: delta
data: {"content":"根据"}

event: delta
data: {"content":"《民法典》"}

event: delta
data: {"content":"第一千零六十二条，夫妻共同财产包括..."}

event: refs
data: {"refs":[{"lawName":"中华人民共和国民法典","articleNo":"第一千零六十二条","content":"夫妻在婚姻关系存续期间所得的下列财产，为夫妻的共同财产..."}]}

event: done
data: {"messageId":5001,"finishReason":"stop","promptTokens":820,"completeTokens":310}
```

### 8.2.3 错误事件

```text
event: error
data: {"code":80001,"msg":"AI 服务暂时不可用，请稍后重试"}
```

## 8.3 前端处理（V2.2 修正）

> **V2.1 的问题**：SSE 用 `GET /api/v1/ai/stream?question=...` 把问题放在 query 里——
> 问题文本可达数百字，放 URL 会超长、被日志记录、且中文需反复编码。另外「重连带已接收长度」缺少续传端点。

**改为两步：先提交，再订阅。**

| 步骤 | 接口 | 说明 |
|---|---|---|
| 1. 提交问题 | `POST /api/v1/ai/sessions` body: `{ sessionId?, question, lang, attachments? }` | 返回 `{ sessionId, messageId, streamUrl }` |
| 2. 订阅流 | `GET /api/v1/ai/stream?sessionId=&fromSeq=0` | SSE 长连接，服务端把该会话的回答逐段推送 |
| 3. 断线重连 | 重连时带 `fromSeq=<已收到的事件序号>` | 服务端从该序号继续推送，**不重复不丢失** |

| 项 | 实现 |
|---|---|
| 库 | `react-native-sse` |
| 渲染 | 收到 `delta` 追加文本，打字机效果 |
| 中断 | 页面卸载或用户点停止 → `abort()`，后端同步取消上游豆包请求 |
| 失败 | 显示重试按钮，重试复用同一 `sessionId` 与新 `messageId` |
| 落库时机 | **每个 delta 事件到达即落库**（V2.1 只在 `done` 落库，断线会丢整段回答） |

---

# 九、支付与订单

## 9.1 网关抽象

```java
public interface PaymentGateway {
    PrepayResult prepay(Order order, PayChannel channel);
    CallbackResult parseCallback(String rawBody, Map<String, String> headers);
    RefundResult refund(RefundRequest request);
    QueryResult query(String orderNo);
}
```

| 实现类 | 说明 |
|---|---|
| `WechatPaymentGateway` | 微信支付（生产） |
| `AlipayPaymentGateway` | 支付宝（生产） |
| `MockPaymentGateway` | 开发/测试，1 秒回调成功 |

## 9.2 订单状态机（V2.2 补全出边）

> **V2.1 的问题**：状态机缺多条出边——`REFUNDING` 只能到 `REFUNDED`（退款被拒无处可去）、
> `PAID` 没有用户取消、`IN_SERVICE` 没有律师放弃、没有 `DISPUTED` 态；
> 且「15 分钟超时置 CANCELLED」后再无出边，会导致**已付款用户被标成已取消、钱留在渠道**。

```text
                    ┌─────────────┐
                    │  PENDING    │ 待支付
                    └──────┬──────┘
        支付成功（余额扣减） │  15 分钟超时 / 用户取消
              ┌──────────┴──────────┐
              ▼                     ▼
        ┌───────────┐         ┌───────────┐
        │  PAID     │         │ CANCELLED │
        │  待接单    │         └───────────┘
        └─────┬─────┘
              │
    ┌─────────┼──────────────┬────────────────┐
    │         │              │                │
 律师接单   用户取消        30 分钟无人接      律师拒单
    │         │              │                │
    ▼         ▼              ▼                ▼
┌─────────┐ ┌──────────┐ ┌───────────┐  ┌───────────┐
│IN_SERVICE│ │REFUNDING │ │REFUNDING  │  │REFUNDING  │
│ 进行中   │ │（用户侧） │ │（超时自动）│  │（拒单）    │
└────┬────┘ └────┬─────┘ └───────────┘  └───────────┘
     │           │
     │   ┌───────┴────────┐
     │   ▼                ▼
     │ ┌───────────┐  ┌───────────┐
     │ │ REFUNDED  │  │ REFUND_   │  ← V2.1 缺失：退款被拒后回到原状态
     │ │  已退款    │  │ REJECTED  │
     │ └───────────┘  └─────┬─────┘
     │                      │ 回到 IN_SERVICE 或 COMPLETED
     │                      └──────────►
     │
     ├── 服务完成 ──► ┌───────────┐
     │                │ COMPLETED │ 资金冻结 7 天
     │                └─────┬─────┘
     │                      │ 用户申诉
     │                      ▼
     │                ┌───────────┐
     │                │ DISPUTED  │ 争议中（V2.1 缺失）
     │                └─────┬─────┘
     │                      │ 平台裁定
     │            ┌─────────┴─────────┐
     │            ▼                   ▼
     │      ┌───────────┐       ┌───────────┐
     │      │ SETTLED   │       │ REFUNDING │
     │      │ 维持原判   │       │  裁定退款  │
     │      └───────────┘       └───────────┘
     │
     └── 律师主动放弃 ──► ┌───────────┐
                          │ REFUNDING │（全额退，记律师违规）
                          └───────────┘
```

### 9.2.1 状态迁移表（服务端唯一依据）

| 当前状态 | 允许操作 | 目标状态 | 触发方 |
|---|---|---|---|
| `PENDING` | 支付成功 | `PAID` | 支付回调 |
| `PENDING` | 15 分钟超时 | `CANCELLED` | 定时任务 |
| `PENDING` | 用户取消 | `CANCELLED` | 用户 |
| `PAID` | 律师接单 | `IN_SERVICE` | 律师 |
| `PAID` | 律师拒单 | `REFUNDING` | 律师 |
| `PAID` | 30 分钟无人接 | `REFUNDING` | 定时任务（**自动全额退，不是 CANCELLED**） |
| `PAID` | 用户取消 | `REFUNDING` | 用户 |
| `IN_SERVICE` | 服务完成 | `COMPLETED` | 律师 |
| `IN_SERVICE` | 用户申请退款 | `REFUNDING` | 用户 |
| `IN_SERVICE` | 律师放弃 | `REFUNDING` | 律师（记违规） |
| `REFUNDING` | 审核通过 | `REFUNDED` | 后台/自动 |
| `REFUNDING` | 审核拒绝 | 回到前一状态 | 后台 |
| `COMPLETED` | 用户申诉 | `DISPUTED` | 用户（7 天内） |
| `DISPUTED` | 平台裁定维持 | `SETTLED` | 后台 |
| `DISPUTED` | 平台裁定退款 | `REFUNDING` | 后台 |
| `COMPLETED` | 冻结期结束 | `COMPLETED`（资金解冻） | 定时任务 |

**非法迁移一律抛业务异常（`5xxxx`），不得静默忽略。**

## 9.3 三条铁律

1. 客户端只发意图（`POST /orders/{id}/pay`、`/cancel`、`/refund`），**状态迁移由服务端判定**。
2. 状态迁移用状态机统一校验（对照 9.2.1 的迁移表），非法迁移抛业务异常。
3. **幂等三重保障**：
   - 支付回调：`payment_record` 对 `(order_id, channel, transaction_id)` 唯一
   - 余额变动：`balance_flow` 对 `(owner_type, owner_id, biz_type, biz_id)` 唯一
   - 客户端重复提交：请求头 `Idempotency-Key` + Redis 24 小时去重

## 9.4 掉单与补单

| 场景 | 处理 |
|---|---|
| 支付成功但回调没到 | 客户端支付后主动查询订单状态，服务端定时（每 5 分钟）主动向支付渠道查询 PENDING 订单，补单 |
| 重复支付 | 同一订单只认第一笔成功支付，其余自动原路退回 |
| 回调验签 | 每个回调必须验签（微信/支付宝签名），验签失败拒绝并告警 |

## 9.5 对账系统

> **V2.2 调整**：改为「**充值对账**」。消费走账户余额，不经过支付渠道，因此对账对象是**充值流水**。

| 项 | 规则 |
|---|---|
| 对账频率 | 每日凌晨 2 点拉取前一日微信/支付宝账单，与本地 `recharge_record` 逐笔比对 |
| 差错类型 | 本地有渠道无（掉单）、渠道有本地无（漏单）、金额不一致 |
| 差错处理 | 自动补记/冲正，无法自动处理的生成 `reconciliation_diff` 工单，财务人工处理 |
| 对账报表 | 每日生成，留存 3 年 |
| 余额校验 | 每日校验 `sum(recharge) - sum(consume) - sum(refund) == user_balance.balance_cents`，不平则告警 |

## 9.6 账户余额与资金（V2.2 重写）

> **模式说明**：采用**平台账户余额制**。用户充值走微信/支付宝，到账后计入 `user_balance`；
> 下单时从余额扣减。界面统一显示为「**账户余额**」（对应 jxyd200 的「锦绣余额」，本平台改名）。

### 9.6.1 三方资金流

```text
充值：用户 → 微信/支付宝 → 平台商户号 → 记账到 user_balance（+N）
消费：user_balance（-N）→ 订单成立
律师收入：订单完成 → lawyer_balance.frozen（+N×(1-抽成)）→ 7 天后转 available
提现：lawyer_balance.available（-N）→ 平台商户号 → 律师银行卡/零钱
退款：user_balance（+N）或原路退回（若未消费）
```

### 9.6.2 账户与流水（必须成对，缺一不可）

| 表 | 职责 |
|---|---|
| `user_balance` | 用户余额快照（`balance_cents`），**只由流水推导，不直接 UPDATE 加减** |
| `lawyer_balance` | 律师余额（`frozen_cents` / `available_cents` / `total_income_cents`） |
| `balance_flow` | **资金流水主表**（用户与律师共用）：`biz_type`(recharge/consume/refund/income/withdraw/freeze/unfreeze)、`biz_id`、`delta_cents`、`balance_after`、`frozen_until` |
| `recharge_record` | 充值记录：渠道、渠道单号、金额、状态 |
| `withdraw_record` | 提现记录：金额、渠道、状态、审核人 |

**铁律：余额的每一次变动都必须先写 `balance_flow`，再由流水汇总更新余额表，且在同一事务内。**

### 9.6.3 平台抽成

| 项 | 规则 |
|---|---|
| 抽成比例 | 可配置（默认 15%），存 `sys_config`，按服务类型可覆写 |
| 律师应得 | `lawyer_income = amount_cents - platform_fee_cents` |
| 计算时机 | 订单进入 `COMPLETED` 时计算并冻结，写入 `balance_flow` |
| 冻结期 | **7 天**（防退款纠纷），`frozen_until = completed_at + 7d` |
| 解冻 | 定时任务扫描 `frozen_until <= now()` 的流水，转 `available` |
| 提现 | 最低 100 元，T+1 到账；提现需财务审核（管理后台） |
| 发票 | 用户可申请电子发票，平台开「法律咨询服务费」 |

### 9.6.4 合规提醒（技术侧不决策，但必须记录）

账户余额模式意味着**用户资金先进入平台账户再结算给律师**，属于平台持有并清算他人资金。上线前必须由项目负责人确认以下两项的适用性：

1. **单用途商业预付卡备案**（商务部）——若余额可长期持有且可跨业务消费
2. **资金存管或第三方支付机构分账**——若规模达到监管关注阈值

技术侧需要保证：`balance_flow` 完整可审计、余额可对账、用户可查明细、注销时可退余额。

## 9.7 支付商户号

| 项 | 要求 |
|---|---|
| 微信支付商户号 | 营业执照、对公账户、已上线 App，审核 1-2 周 |
| 支付宝商户 | 同上，支付宝开放平台申请 |
| 费率 | 一般 0.6% |
| 测试 | 沙箱环境联调，上线前小额真实支付验证 |

> **由项目负责人负责申请**（其具备相关经验）。技术侧只需按沙箱文档完成对接与回调处理。

---

# 十、文书与签章

## 10.1 生成链路

```text
模板(template) ──► 动态字段定义(field_schema JSON)
用户填表 ──► 表单数据(form_data JSON)
                    ▼
        1) Word 渲染：poi-tl 填充 {{field}} → .docx
        2) PDF 渲染：LibreOffice headless（soffice --convert-to pdf）
           └─ 或 Chromium 打印 HTML→PDF
           ★ 不用 openhtmltopdf：PDFBox 2 不做 GSUB/GPOS shaping，
             阿拉伯文会出孤立字形、藏文叠字错位
                    ▼
              OSS 私有存储 ──► 返回 5 分钟签名 URL
用户签章 ──► PDFBox 叠加签名图到 (page_no, x_pt, y_pt) ──► 生成带章 PDF
```

## 10.2 模板机制

| 项 | 方案 |
|---|---|
| 模板存储 | `doc_template` 表，OSS 上的 .docx 模板 |
| 占位符 | Word 用 `{{field}}`，poi-tl 替换 |
| 动态字段 | `field_schema` JSON 定义字段名、类型、校验、必填 |
| 多原告/被告 | `field_schema` 支持 `array`，前端渲染可增删行 |
| 多语言 | 每种语言一份模板 |
| 草稿 | 表单自动保存 |

## 10.3 PDF 少数民族文字（V2.2 重写）

> **V2.1 的问题**：只写了「显式注册字体 + `<span dir="rtl">`」。
> 但 `openhtmltopdf` 底层是 PDFBox 2，**按 cmap 逐字符取字形，不执行 GSUB/GPOS shaping** ——
> 阿拉伯文会渲染成孤立字形甚至整体逆序，藏文上下叠字错位。**注册字体和加 `dir` 都改变不了这一点。**

### 10.3.1 正确的渲染路径

| 方案 | 说明 | 推荐度 |
|---|---|---|
| **LibreOffice headless** | `soffice --headless --convert-to pdf in.docx`，自带 HarfBuzz + ICU，系统装好字体即可 | ⭐ 推荐 |
| **Chromium 打印** | 生成 HTML → `chrome --headless --print-to-pdf`，同样自带 shaping | ⭐ 备选 |
| `openhtmltopdf` | ❌ **不做 shaping，禁止用于含少数民族文字的文书** | 淘汰 |

### 10.3.2 落地要求

| 项 | 要求 |
|---|---|
| 字体 | 服务器上安装 Noto Sans Tibetan / Noto Sans Arabic（系统级，`fc-cache` 生效） |
| Word 模板 | docx 需预置 `w:bidi`（段落）+ `w:rtl`（run）属性，poi-tl 只替换文本、不改这些属性 |
| RTL 段落 | 段落级 `dir="rtl"`；不能靠单个 `<span>` 解决整段方向 |
| 混排 | 数字、案号用 `<span dir="ltr">` 隔离；金额负号必须在隔离范围内 |
| 验证 | **每种语言（zh/bo/ug/kk）各生成一份样例 PDF，人工目视确认为必做验收项** |
| 性能 | LibreOffice 转换有进程启动开销（约 1–2 秒），高并发时用进程池或队列 |

## 10.4 签名笔迹（演示能力）

> **V2.2 改名**：「电子签章」→「**签名笔迹**」。"签章"在中文语境暗示公章效力，有伪造印章的联想；
> 而本功能只是把手写笔迹叠加到 PDF 上，与《电子签名法》第 13 条的"可靠电子签名"无关。

| 项 | 方案 |
|---|---|
| 手写板 | react-native-skia 绘制，导出 PNG（透明底） |
| 拖拽定位 | gesture-handler + reanimated；**RTL 下 canvas 坐标不会自动镜像，需手工换算** |
| 坐标存储 | `(page_no, x_pt, y_pt, width_pt, height_pt, base_page_w_pt, base_page_h_pt)`，单位 pt（A4 = 595×842pt） |
| 合成 | **PDFBox**（只做图像叠加，不涉及文字排版，不受 shaping 限制） |
| 哈希 | 对**盖签前**的规范化内容流计算 SHA-256，写入 PDF 元数据与 `signature` 表 |
| 隐私 | 签名图存**私有 OSS bucket**，通过 5 分钟签名 URL 访问；下载留 `operation_log` |
| 法律效力 | **明确不主张法律效力**。手写笔迹图不满足《电子签名法》第 13 条可靠电子签名要件；如需可靠电子签名，需接入持《电子认证服务许可证》的 CA（二期） |

---

# 十一、AI 接入（豆包 API）

## 11.1 链路

```text
用户提问
   ├─► 后端接收（会话 ID、当前语言）
   ├─► 拼装 Prompt（系统指令 + 历史 + 语言）
   ├─► 调用豆包 API（SSE 流式）
   ├─► 边接收边转发 App（SSE）
   └─► 全量落 ai_message
```

## 11.2 模型与平台

| 项 | 方案 |
|---|---|
| 平台 | 火山引擎方舟（Ark） |
| 模型 | **`doubao-seed-1.6`（主力）/ `doubao-seed-1.6-flash`（低成本档）**。⚠️ **model id 必须带完整上下文后缀（如 `-32k`），以火山引擎控制台当前列表为准**；写不完整会导致调用失败且无法核价 |
| 接口 | OpenAI 兼容 `/api/v3/chat/completions` |
| 调用 | 服务端 WebClient，API Key 走 Jasypt 加密，不暴露到客户端 |
| 流式 | SSE 逐 token |

## 11.3 系统 Prompt

> **V2.2 变更**：原 Prompt 自称「专业法律顾问」并强制输出「操作步骤」，可能被认定为**在出具法律意见**，触及《律师法》关于无证不得以律师名义执业的限制。改为「法律信息整理助手」，并增加禁用清单。

```text
你是「法泽」平台的法律信息整理助手。你不是律师，不得自称律师、法律顾问或律所。

回答结构：结论 → 法律依据 → 建议路径 → 风险提示

【法条引用（强制）】
1. 只允许引用【检索到的法条】区块中出现的条文，不得引用该区块之外的任何法条编号
2. 若检索结果中没有相关条文，只回答「暂无明确法律依据」，不得编造
3. 引用格式：只写「《法律名称》第 X 条」，不臆造条文内容

【禁止内容】
1. 不得给出诉讼策略建议、胜诉率或结果承诺
2. 不得预测具体赔偿金额、刑期、判决结果
3. 不得代写可直接向法院递交的诉讼文书
4. 不得指导规避法律、伪造证据或规避监管
5. 不得回答与中华人民共和国法律无关的问题

【语言】
按用户当前语言回答（zh-Hans / bo / ug-Arab-CN / kk-Arab-CN）

【固定结尾】
本回答由 AI 生成，仅供了解法律信息，不构成正式法律意见，具体案件请咨询执业律师。
```

## 11.4 多语言

- 后端按 App 当前语言（`zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN`）追加语言指令
- **豆包对藏文/维吾尔文/哈萨克文的生成能力需上线前实测**；若质量不足，降级策略为：
  **豆包用中文生成 → 走机器翻译层输出到目标语言**（此时 13.6「AI 回答不预翻译」原则对这三语不适用，须在此处显式例外）
- 注入法律术语表，保证四语言术语一致
- 法条原文**永不翻译**，始终以中文原文引用，避免术语偏差

## 11.5 成本控制

| 机制 | 规则 |
|---|---|
| 轮次限额 | 每用户每天 50 轮（可配） |
| 上下文 | 最多最近 10 轮，超出截断 |
| Token 监控 | 按用户/天统计，异常告警 |
| 重试 | 上游失败重试 2 次，指数退避 |
| 中断 | 用户离开 abort，后端取消 |

## 11.6 幻觉防护：本地法条库 + 检索增强（V2.2 重写）

> **V2.1 的错误**：原文写「法条知识依赖豆包模型，不做 RAG」，同时又承认「豆包可能生成错误法条编号」。
> 只靠 Prompt 禁止编造是无效的——模型为满足输出格式会硬造条号。**V2.2 改为建本地法条库。**

### 11.6.1 法条库

| 项 | 方案 |
|---|---|
| 表 | `law_item(id, law_code, law_name, article_no, article_seq, content, effective_date, expire_date, status, keywords)` |
| 首批范围 | 《民法典》（1260 条）、《民事诉讼法》、《劳动合同法》、《劳动争议调解仲裁法》、《消费者权益保护法》、《婚姻家庭编司法解释》 |
| 规模 | 约 **2000–3000 条** |
| 检索 | MySQL 全文索引 + `ngram` 分词器（中文）；辅以 `law_name` + `article_no` 精确匹配 |
| 不做的 | **不上向量库**。这一规模下关键词检索 + 同义扩展够用，向量库带来的运维成本不值得 |
| 维护 | 法条有修订时，旧版本置 `expire_date`，不删除（AI 历史回答要可追溯） |

### 11.6.2 链路（替代原「仅靠 Prompt」）

```text
用户提问
  │
  ├─ 1. 抽取法律关键词（规则 + 轻量分类）
  ├─ 2. 检索 law_item：全文索引 top-5 + law_name 精确命中
  ├─ 3. 拼装 Prompt：【检索到的法条】区块 + 系统指令 + 语言指令
  ├─ 4. 调用豆包（SSE）
  ├─ 5. 输出侧校验：正则抽「第 X 条」→ 回查 law_item 是否存在
  │     ├─ 全部命中 → 保留引用，写入 ai_message.refs
  │     └─ 有未命中 → 降级：删掉未命中的条号，追加「部分条文需人工核实」
  └─ 6. 全量回答落 ai_message（含 refs 快照）
```

### 11.6.3 补充措施

- 涉及具体案件、金额、期限时，UI 提示「请以法院/律师意见为准」
- 高风险领域（刑事、重大财产、人身安全）**强制提示转人工律师**，并在回答前置顶风险提示
- 每月抽样 50 条 AI 回答做人工复核，统计法条引用准确率，低于 95% 则调整检索策略

## 12.1 备案要求（V2.2 调整）

> **V2.2 变更**：项目负责人已确认 **AI 备案不做**。

依据《生成式人工智能服务管理暂行办法》：

- **第 17 条**：安全评估与算法备案义务针对「**具有舆论属性或者社会动员能力的**」生成式 AI 服务
- **第 2 条第 3 款**：教育、科研等主体**未向境内公众提供**生成式 AI 服务的，**不适用本办法**

| 项 | 决策 |
|---|---|
| 生成式 AI 服务备案 | **不做** |
| 深度合成算法备案 | **不做** |
| 大模型备案 | 豆包由火山引擎备案，调用方不重复备案 |
| 上线后复评 | ⚠️ **若真实面向公众开放服务，第 17 条的适用性需重新评估**，届时以属地网信办答复为准 |

注意：本节结论仅适用于当前阶段。上线前须由项目负责人复核一次。

## 12.2 AI 内容安全

| 环节 | 措施 |
|---|---|
| 输入审核 | 用户提问先过内容安全（政治、暴恐、色情、违法），违规直接拒绝并提示 |
| 输出审核 | 豆包返回再过一层内容安全，违规内容拦截，不展示给用户 |
| 敏感词 | 阿里云内容安全文本审核 API，实时 |
| 高风险问题 | 涉刑、涉政、自残等问题，不提供具体方法，引导联系律师/官方 |
| 记录 | 每次审核结果落库（`safety_passed`），可追溯 |

## 12.3 AI 内容标识

- AI 回答页面顶部/底部明确标注"AI 生成"
- 导出的 AI 文书包含 AI 生成标识
- 符合《人工智能生成合成内容标识办法》要求

## 12.4 Prompt 注入防护

| 攻击 | 防护 |
|---|---|
| 忽略之前指令 | 系统 Prompt 与用户输入分离，系统指令不可被覆盖 |
| 诱导输出违法内容 | 输入内容安全审核 + 输出审核双层拦截 |
| 角色扮演绕过 | 系统 Prompt 固定角色，不接受用户重新定义角色 |
| 越狱 | 收集常见越狱模式，输入侧拦截；模型设置低 temperature |

## 12.5 AI 责任边界

- 用户协议明确：AI 回答仅供参考，不构成法律意见
- 平台不对 AI 回答的准确性承担责任（但仍需尽到合理审核义务）
- 重要决策强制建议咨询执业律师

---

# 十三、多语言与 RTL

## 13.1 语言与代码

| 语言 | BCP 47 代码 | 方向 | 字体 |
|---|---|---|---|
| 简体中文 | `zh-Hans` | LTR | 系统字体 |
| 藏文 | `bo` | LTR | Noto Sans Tibetan |
| 维吾尔文 | `ug-Arab-CN` | RTL | Noto Sans Arabic / Noto Naskh Arabic |
| 哈萨克文（中国） | `kk-Arab-CN` | RTL | Noto Sans Arabic / Noto Naskh Arabic |

## 13.2 资源组织

```text
locales/
├─ zh-Hans/  common.json court.json article.json ai.json profile.json
├─ bo/
├─ ug-Arab-CN/
└─ kk-Arab-CN/
```

规则：
1. key 用扁平点号：`auth.login.phonePlaceholder`
2. 所有面向用户字符串走 `t()`，包括 placeholder、accessibilityLabel、错误提示
3. tabBar / 导航栏用函数形式，否则语言切换不更新

## 13.3 字体加载（V2.2 修正）

> **V2.1 的错误**：原文写「藏文字体置 `fontFamily` 主位，不能挂 fallback 链尾」。
> 但 **React Native 的 `fontFamily` 只接受单个字体族名，根本没有 CSS 式 fallback 链**——这个表述无法移植。
> 而且 Noto Sans Tibetan 不含 CJK/拉丁字形，全局设置后中文夹藏文会出豆腐块。

| 步骤 | 实现 |
|---|---|
| 1 | 字体文件放 `assets/fonts/`，随包分发 |
| 2 | `useFonts` 在根布局加载，`fontsLoaded` 门控首屏（否则首帧闪错字形） |
| 3 | **按 Unicode 区段把字符串切成 run，逐段指定字体**，封装 `<LocalizedText>` 组件 |
| 4 | 藏文字体**不做子集化**（GSUB 引用复合字形，裁错即崩排） |
| 5 | 阿拉伯系 `letterSpacing` 必须为 **0**（非零会导致阿拉伯字母断连） |
| 6 | 藏文 `lineHeight ≥ 1.6 × fontSize`；混排时取两字体行高的**最大值** |
| 7 | `expo-font` 的 `fontFamily`：Android 写**文件名**（无扩展名），iOS 写 **PostScript 名**，写错静默 fallback |

### 13.3.1 `<LocalizedText>` 的切分规则

```text
U+0F00–U+0FFF  藏文区段        → fontFamily: NotoSansTibetan
U+0600–U+06FF  阿拉伯区段      → fontFamily: NotoSansArabic
U+0750–U+077F  阿拉伯补充区段  → fontFamily: NotoSansArabic
其余（含 CJK、拉丁、数字）      → 系统字体
```

同一段文字里可能包含多个 run（例如「《民法典》第 12 条」+ 藏文），必须逐 run 渲染，
不能给整个 Text 设一个 fontFamily。

## 13.4 RTL 切换流程（V2.2 修正）

> **V2.1 的错误**：原文只有 `changeLanguage → 持久化 → Updates.reloadAsync()`，
> **全文没有 `forceRTL` / `allowRTL`**。而 `I18nManager` 必须显式 `forceRTL(true)` 才改方向，
> **只调 `reloadAsync` 方向不会变**。

```text
用户选择语言
   │
   ├─ 方向相同（中 ↔ 藏，都是 LTR）
   │     └─► changeLanguage() → 全树重渲染，不重启
   │
   └─ 方向不同（LTR ↔ RTL：中/藏 ↔ 维/哈）
         └─► 1. 持久化 lang 到 SecureStore
             2. I18nManager.allowRTL(true)
             3. I18nManager.forceRTL(true)      ← 关键，缺这一步方向不变
             4. 记录当前路由，防止重启后回首页
             5. Updates.reloadAsync()（软重载，约 1–2 秒启动屏）
             6. 重启后读 lang → I18nManager.isRTL 已生效 → 恢复原路由
```

| 关键约束 | 说明 |
|---|---|
| `forceRTL` 必须在 reload **之前**调用 | 它把标记写入原生持久化存储 |
| `reloadAsync` 在 **Expo Go 不可用** | 必须用 development build / EAS 构建 |
| 应用启动时**必须**校验方向一致性 | 若 `I18nManager.isRTL` 与当前 lang 的方向不符（例如被外部修改），主动纠正并 reload |
| 恢复路由 | reload 会丢内存态，切换前把 `pathname + params` 存 SecureStore，启动后 `router.replace` 回去 |

依据：RN 官方 `I18nManager` 文档——`allowRTL` / `forceRTL` 的变更「Changes take effect on the next application start, not immediately」，且明确「Avoid forcing RTL in production apps as it requires a full app restart」。

> **V2.2 待实测项**：`reloadAsync` 是否真的重建原生 `layoutDirection`，需在 Android 13/14 与 iOS 真机各验证一次。若不成立，则 RTL 切换需回退为「提示用户手动重启 App」。

## 13.5 混排处理

| 场景 | 处理 |
|---|---|
| 法条编号 `第12条` | `<LtrText>` 包裹，加 `U+2066…U+2069` 隔离符 |
| 案号 `(2023)伊民初字第005号` | 同上，不透明字符串 |
| 金额 `-¥500` | 同上 |
| 数字 | 一律 0-9，不本地化为阿拉伯-印度数字 |

## 13.6 内容层翻译

| 内容 | 方式 |
|---|---|
| UI 文案 | 人工翻译 + 母语者校对 |
| 每日学法 | 机翻初稿 + 人工校对 |
| 案例、法条 | 人工翻译，法条原文不机翻 |
| AI 回答 | 不预翻译，按当前语言生成 |

`translated_from < source_updated_at` 视为译文过期，后台标红。

---

# 十四、律师工作台

## 14.1 页面结构（7 页）

```text
律师工作台
├─ 工作台首页
│   ├─ 在线状态（在线/忙碌/离线）
│   ├─ 数据卡片（今日咨询/待回复/进行中/本月收入）
│   ├─ 待办（未读 IM、待接单、待补材料）
│   └─ 快捷宫格
├─ 客户咨询列表（律师视角 IM 会话）
│   └─ 点击 → IM 聊天页
├─ 律师订单管理
│   ├─ Tab：待接单/进行中/已完成/已取消
│   └─ 接单 / 完成 / 退款处理
├─ 律师文书管理
│   ├─ 新建文书（复用模板）
│   └─ 发送给客户
├─ 收入与提现
│   ├─ 统计（本周/本月/累计）
│   ├─ 明细
│   └─ 提现
├─ 服务设置
│   ├─ 服务开关（图文/电话/文书）
│   ├─ 价格修改
│   └─ 自动接单
└─ 律师资料编辑
    ├─ 头像/简介/专长
    ├─ 律所
    └─ 审核状态
```

## 14.2 权限控制

- 仅 `lawyer` 角色可访问，`@LawyerOnly` 拦截
- 数据按 `lawyer_id` 过滤
- 入驻审核通过后自动开通

## 14.3 数据看板

| 指标 | 来源 |
|---|---|
| 今日新咨询 | 当日新建会话 |
| 待回复 | 未读 IM |
| 进行中订单 | `IN_SERVICE` 订单 |
| 本月收入 | 当月完成订单 `lawyer_income` 合计 |
| 好评率 | 评价平均 |

## 14.4 律师服务规则

- 接单上限：同时进行中订单可配置上限（默认 10）
- 响应时限：新咨询 5 分钟内响应提醒，30 分钟未响应降级
- 服务设置：下架后用户端不展示；价格修改对已下单不生效
- 违规处理：警告、限流、暂停接单、清退

---

# 十五、管理后台

## 15.1 模块结构

```text
管理后台（Web）
├─ 数据看板
│   ├─ 核心指标（DAU/新增/订单量/收入/留存）
│   ├─ 实时在线
│   └─ 趋势图表
├─ 用户管理
│   ├─ 用户列表（搜索/筛选/详情）
│   ├─ 封禁/解封
│   └─ 实名认证审核
├─ 律师管理
│   ├─ 入驻审核（通过/驳回）
│   ├─ 律师列表、详情
│   ├─ 违规处理（警告/下架/清退）
│   └─ 提现审核
├─ 订单管理
│   ├─ 订单列表、详情
│   ├─ 退款审批
│   └─ 订单干预
├─ 内容管理
│   ├─ 文章发布/编辑/下架
│   ├─ 案例库管理
│   ├─ 多语言翻译管理
│   └─ 文书模板管理
├─ 投诉工单
│   ├─ 工单列表
│   ├─ 分配/处理/关闭
│   └─ 反馈处理
├─ 消息管理
│   ├─ 全员公告
│   ├─ 定向推送
│   └─ 通知模板
├─ 财务管理
│   ├─ 对账报表
│   ├─ 收入明细
│   ├─ 提现记录
│   └─ 发票管理
└─ 系统设置
    ├─ 角色权限
    ├─ 系统参数（抽成/限额/价格）
    ├─ 操作日志
    └─ 登录日志
```

## 15.2 技术实现（V2.2 改为 RuoYi-Vue3）

- **基础平台：RuoYi-Vue3**（`E:\itheima\vue\ry_vue3\RuoYi-Vue3`），已在 `jxyd200` 项目验证
- 内置 Vue 3 + Vite + TS + Element Plus + Pinia + ECharts，**不另起框架**
- 业务代码隔离：页面放 `src/views/biz/<domain>/`，API 放 `src/api/biz/<domain>/`
- 权限复用 RuoYi 的菜单权限 + `@PreAuthorize` + `@DataScope`，**不另建权限模型**
- 代码生成器生成 CRUD 骨架后，按本仓库注释规范补齐注释
- 独立部署 `admin.faze.com`，仅 `admin` 角色可登录，二次验证
- 所有操作留痕（复用 RuoYi `@Log` 注解），敏感操作二次确认
- **禁止修改 RuoYi 框架层**；确需 patch 时记录到 `docs/analysis/ruoyi-patches.md`

---

# 十六、风控与内容审核

## 16.1 内容审核体系

| 内容类型 | 审核方式 |
|---|---|
| 律师资料（头像/简介） | 先审后发，人工 + 机审 |
| 律师执业证 | 人工核验真伪 |
| IM 消息 | 实时机审（文本/图片），违规拦截，不触达对方 |
| 评价 | 机审 + 人工，恶意/违规评价删除 |
| 文章评论 | 先审后发 |
| 投诉/反馈图片 | 机审 |

技术：阿里云内容安全（文本/图片/视频），机审不通过转人工。

## 16.2 敏感词过滤

- 敏感词库分类（政治、暴恐、色情、辱骂、广告、违法）
- 实时替换/拦截，记录命中
- 词库可后台更新
- 多语言敏感词（藏/维/哈）

## 16.3 风控系统

| 场景 | 规则 |
|---|---|
| 异常注册 | 同设备/IP 批量注册、虚拟号段，验证码/人机验证 |
| 刷单 | 同设备多账号、异常下单频率、无真实服务，拦截 |
| 盗号 | 异地登录、设备变更，二次验证 |
| 薅羊毛 | 异常提现、优惠券滥用 |
| 设备指纹 | 采集设备特征，识别黑产设备 |
| 支付风控 | 大额、高频、异常时间交易，人工审核 |

## 16.4 人工客服与工单

| 项 | 规则 |
|---|---|
| 入口 | App 内"客服中心"，在线客服 + 工单 |
| 工单系统 | 问题分类、优先级、分配、跟踪、SLA（24 小时响应） |
| 客服时段 | 在线客服 9:00-21:00，非时段留言 |
| 知识库 | 客服共享 FAQ，快速回复 |

## 16.5 黑名单与违规

| 类型 | 处理 |
|---|---|
| 用户黑名单 | 恶意用户封禁，封禁后无法登录 |
| 律师/用户双向拉黑 | 拉黑后不再匹配、不接收消息 |
| 律师违规 | 分级：警告 → 限流 → 暂停接单 → 清退，记录留档 |
| 评价申诉 | 律师对恶意评价申诉，人工核实后可删除 |

---

# 十七、运维监控体系

## 17.1 监控分层

| 层级 | 内容 | 工具 |
|---|---|---|
| 基础设施 | CPU、内存、磁盘、网络、负载 | Node Exporter + Prometheus |
| 容器 | 容器状态、资源占用 | cAdvisor |
| 应用（JVM） | GC、堆内存、线程、连接池 | Micrometer + Prometheus |
| 接口 | QPS、延迟、错误率 | Micrometer |
| 业务 | 支付成功率、AI 成功率、IM 送达率、订单量 | 自定义指标 |
| 移动端 | 崩溃率、ANR、启动耗时、页面耗时 | Sentry |
| 可用性 | 拨测（每分钟模拟访问） | Uptime Kuma / 云监控 |
| 日志 | 应用日志、访问日志、错误日志 | ELK / Loki |
| 链路 | 分布式追踪 | Zipkin |

## 17.2 告警规则

| 告警 | 阈值 | 通道 |
|---|---|---|
| 服务宕机 | health check 失败 | 电话 + 短信 |
| 接口错误率 | > 5% 持续 3 分钟 | 短信 + 企业微信 |
| 接口延迟 | P95 > 1s 持续 5 分钟 | 企业微信 |
| CPU/内存 | > 85% 持续 5 分钟 | 企业微信 |
| 磁盘 | > 80% 预警，> 90% 紧急 | 短信 |
| 支付失败率 | > 10% | 电话 |
| 崩溃率 | > 1% | 企业微信 |
| 豆包 API | 连续失败 | 企业微信 |

## 17.3 日志收集

- 应用日志统一 JSON 格式，带 traceId
- Filebeat 采集 → Elasticsearch → Kibana 查询
- 日志保留 30 天，错误日志 90 天
- 敏感字段脱敏

## 17.4 发布策略

| 策略 | 说明 |
|---|---|
| 滚动更新 | 默认，逐容器替换 |
| 灰度发布 | 新版本先 5% → 20% → 100%，观察指标 |
| 回滚 | 一键回滚上一版本，数据库变更需兼容 |
| 移动端 | 分渠道灰度下发，先小范围用户 |

## 17.5 灾备与应急预案

| 项 | 规则 |
|---|---|
| 故障分级 | P0（全站不可用）/ P1（核心功能受损）/ P2（局部问题） |
| 响应 | P0 5 分钟内响应，值班 + 通讯机制 |
| 备份 | MySQL 每日全量 + binlog，保留 30 天 |
| 恢复演练 | 每季度演练一次数据库恢复 |
| 应急预案 | 服务宕机、数据库故障、网络攻击、资金故障各有预案 |

---

# 十八、部署架构

## 18.1 服务器

| 项 | 配置 |
|---|---|
| 规格 | 4C8G（按要求保留） |
| 系统 | Ubuntu 22.04 LTS |
| 磁盘 | 100GB SSD |
| 带宽 | 5Mbps（CDN 分担大部分流量） |

## 18.2 Docker Compose

```yaml
services:
  mysql:
    image: mysql:8.0
    restart: always
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: faze
    volumes:
      - mysql-data:/var/lib/mysql
    ports: ["3306:3306"]

  redis:
    image: redis:7-alpine
    restart: always
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes: [redis-data:/data]
    ports: ["6379:6379"]

  rabbitmq:
    image: rabbitmq:3.13-management
    restart: always
    environment:
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}
    ports: ["5672:5672", "15672:15672"]

  api:
    image: faze-api:latest
    restart: always
    environment:
      JASYPT_ENCRYPTOR_PASSWORD: ${JASYPT_PASSWORD}
      SPRING_PROFILES_ACTIVE: prod
    depends_on: [mysql, redis, rabbitmq]
    ports: ["8080:8080"]

  admin:
    image: faze-admin:latest
    restart: always
    ports: ["8081:80"]

  prometheus:
    image: prom/prometheus
    restart: always
    volumes: [./prometheus:/etc/prometheus]
    ports: ["9090:9090"]

  grafana:
    image: grafana/grafana
    restart: always
    ports: ["3000:3000"]

  nginx:
    image: nginx:alpine
    restart: always
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf
      - ./nginx/certs:/etc/nginx/certs
    depends_on: [api, admin]
    ports: ["80:80", "443:443"]

volumes:
  mysql-data:
  redis-data:
```

## 18.3 Nginx

- 反向代理：`/api/` → api:8080
- WebSocket：`/ws/` 升级连接
- 管理后台：`admin.faze.com` → admin:80
- TLS：Let's Encrypt 免费证书，自动续期
- 静态资源：OSS + CDN，不经过 Nginx

## 18.4 CDN

| 内容 | 策略 |
|---|---|
| 图片、头像、文书 | 阿里云 CDN 加速，边缘缓存 |
| App 下载包 | CDN 分发 |
| 回源 | OSS 私有 bucket，CDN 鉴权 |

## 18.5 对象存储

| 用途 | 存储 |
|---|---|
| 图片、文书、签章 | 阿里云 OSS |
| 访问 | 客户端直传（STS 临时凭证），私有读、签名 URL |
| 生命周期 | 临时文件 7 天自动删除，冷数据归档 |

## 18.6 备份

| 项 | 策略 |
|---|---|
| MySQL | 每日全量 + binlog，保留 30 天，异地存储 |
| OSS | 版本控制，跨区域复制（二期） |
| 配置 | Git 管理 |

---

# 十九、测试体系

## 19.1 测试分层

| 层 | 覆盖 |
|---|---|
| 单元测试 | 工具类、状态机、策略、Service 核心逻辑 |
| 组件测试 | 共享组件 × 四态 |
| 接口测试 | 全部 API，含鉴权、异常 |
| 集成测试 | 登录→咨询→支付、建案→进度、文书→签章 |
| E2E | 核心链路 |
| 真机测试 | 主流机型逐页验证 |

## 19.2 专项测试

| 类型 | 内容 |
|---|---|
| **弱网测试** | 2G/3G/高延迟/丢包，验证加载、超时、重试 |
| **中断测试** | 来电、短信、通知、切后台、锁屏打断 |
| **Monkey** | 随机事件 10 万次，不崩溃/ANR |
| **压力测试** | 峰值并发，定位瓶颈 |
| **安全测试** | 渗透测试、越权、注入、上传 |
| **兼容性** | 主流机型、Android 版本、刘海屏/折叠屏 |
| **安装升级** | 全新安装、覆盖安装、跨版本升级、数据迁移 |
| **多语言** | 4 语言真机逐页，RTL 全流程 |
| **权限测试** | 授权/拒绝/永久拒绝 |
| **无障碍** | TalkBack 焦点、标签 |

## 19.3 必测六类

期限计算、金额（整数分）、状态机（含非法迁移）、权限判定、身份资质校验、脱敏。

## 19.4 验收标准

- 核心功能 100% 覆盖
- 崩溃率 < 0.5%
- ANR 率 < 0.2%
- 核心页面 P95 加载 < 2 秒
- 支付成功率 > 99%

---

# 二十、资质与合规

## 20.1 主体资质

| 项 | 要求 | 办理 |
|---|---|---|
| 经营主体 | 个体工商户或公司 | **新疆本地政务服务网**（项目在伊犁，非四川），1-3 天 |
| 经营范围 | 法律咨询服务（不含诉讼代理） | 注册时勾选 |
| 税务 | 小规模纳税人 | 同步办理 |

> **V2.2 修正**：V2.1 写的是「四川政务服务网」，属模板残留，已改为新疆本地。

## 20.2 备案

| 类型 | 周期 | 状态 |
|---|---|---|
| ICP 备案 | 2-3 周 | 需办（项目负责人负责） |
| App 备案（工信部） | 2-3 周 | 需办（项目负责人负责） |
| 公安联网备案 | 上线后 30 天内 | 需办 |
| ~~生成式 AI 备案~~ | ~~2-3 个月~~ | **不做**（见 12.1，项目负责人已确认） |
| ~~算法备案~~ | ~~1-2 个月~~ | **不做**（同上） |

> **V2.2 变更**：AI 相关备案全部移除，依据见 12.1。上线后如面向公众开放，需重新评估。

## 20.3 软著

- 源代码（前 30 + 后 30 页）+ 说明书 + 申请表
- 普通 30-60 天，加急 3-15 天

## 20.4 行业资质

| 方案 | 说明 |
|---|---|
| 方案 A（推荐） | 定位「法律科普 + AI 法律信息整理 + 律师信息撮合」；**不从事诉讼代理与律师执业业务**（这是合规的业务边界声明，不是"绕开"许可） |
| 方案 B | 与正规律所合作，律师持执业证入驻，平台只做信息服务 |

## 20.5 等保

| 项 | 要求 |
|---|---|
| 等级 | 涉及用户数据、在线交易，一般二级 |
| 测评 | 专业测评机构，周期 1-2 个月，费用 2-5 万 |
| 实施 | 上线后开展，或与上线并行 |

## 20.6 隐私合规

| 项 | 要求 |
|---|---|
| 隐私政策 | 收集字段、用途、第三方 SDK 清单、存储期限，律师审查 |
| 权限 | 运行时申请，最小必要 |
| 账号注销 | 注销入口，7 天冷静期，数据删除 |
| 第三方共享 | 清单化披露 |
| 个人信息保护影响评估（PIA） | 处理敏感信息前完成 |
| 未成年人 | 不面向未成年人，年龄识别 |
| SDK 合规 | 每个 SDK 名称、用途、收集字段，列入隐私政策 |

## 20.7 应用商店

| 商店 | 要求 |
|---|---|
| 华为/小米/OPPO/vivo | 软著、备案、隐私政策、账号注销、权限说明 |
| 苹果 App Store | 法律类 App 可能要求合作证明；虚拟服务走 IAP |
| 应用宝 | 同上 |

---

# 二十一、工程规范

## 21.1 目录与依赖方向

```text
移动端：app/ ──► features/ ──► shared/
后端：controller ──► service ──► mapper / infra
```

- features/A 不得 import features/B
- shared 不得 import features
- 后端跨模块走对方 Service

## 21.2 注释分级

| 级别 | 范围 | 要求 |
|---|---|---|
| A 级 | 金额/期限/状态机/权限、共享工具 | 文件头 + 导出物 + 阶段注释 |
| B 级 | 展示组件、types | 文件头 + 导出物 |
| C 级 | 路由壳、_layout | 免注释 |

## 21.3 质量门

| 阶段 | 命令 | 门限 |
|---|---|---|
| 类型检查 | tsc --noEmit | 零错误 |
| Lint | eslint --max-warnings 0 | 零告警 |
| 单元测试 | 关键模块通过 |
| 后端构建 | mvn clean package | 成功 |
| 依赖扫描 | Dependency-Check | 无高危 |

## 21.4 分支与提交

| 项 | 规范 |
|---|---|
| 分支 | GitHub Flow：main 恒绿 + 短分支 |
| 文档分支 | `docs` 分支维护技术文档 |
| 提交 | Conventional Commits |
| 合并 | Squash merge |
| 版本 | 语义化 tag |

## 21.5 CI/CD

| 阶段 | 内容 |
|---|---|
| 提交触发 | 自动 lint + 类型检查 + 测试 |
| 构建 | 自动构建后端镜像、管理后台、App（EAS） |
| 部署 | 合并 main 后自动部署测试环境，生产手动确认 |
| 回滚 | 保留最近 5 个版本，一键回滚 |

---

# 二十二、业务闭环与异常流程

## 22.1 订单超时

| 场景 | 处理 |
|---|---|
| 待支付超时 | 下单 15 分钟未支付，自动取消，释放库存 |
| 待接单超时 | 支付后 30 分钟无律师接单，自动退款 + 推荐其他律师 |
| 服务超时 | 超过约定服务期限，提醒律师，用户可申请退款 |

## 22.2 律师失联

| 时限 | 处理 |
|---|---|
| 5 分钟未响应 | 系统提醒律师 |
| 30 分钟未响应 | 通知用户，可申请退款或更换律师 |
| 服务中失联 | 平台介入，核实后退款，律师记违规 |

## 22.3 AI 转律师

- AI 对话中提供"转人工律师"入口
- 转人工时携带 AI 对话上下文，律师可查看
- 律师接单后继续解答，不重复描述问题

## 22.4 纠纷与仲裁

| 场景 | 处理 |
|---|---|
| 服务纠纷 | 暂停服务，平台介入核实 |
| 退款争议 | 平台根据聊天记录、服务进度判定 |
| 无法调解 | 建议走司法途径，平台提供证据 |

## 22.5 资金异常

| 场景 | 处理 |
|---|---|
| 重复支付 | 自动退回多余款项 |
| 掉单 | 主动查询补单 |
| 金额差错 | 对账发现，冻结相关资金，人工核实 |
| 提现异常 | 审核拦截，核实身份后处理 |

---

# 二十三、实施计划

## 23.1 阶段划分

| 阶段 | 内容 | 产出 |
|---|---|---|
| **S0 基础设施** | 仓库、Expo、Spring Boot、Docker、CI | 空壳可跑 |
| **S1 设计系统 + i18n** | 令牌、组件、i18n 框架 | 组件库 |
| **S2 认证流** | 启动、登录、注册、忘记密码、协议 | 7 页 |
| **S3 首页与内容** | 首页、文章列表、详情 | 3 页 |
| **S4 律师域** | 列表、详情、入驻、审核 | 4 页 |
| **S5 IM 通信** | WebSocket、消息、已读撤回 | 1 页 + 服务端 |
| **S6 AI 智能体** | 豆包接入、流式、历史 | 2 页 + 服务端 |
| **S7 文书中心** | 模板、表单、生成、预览、签章 | 6 页 + 服务端 |
| **S8 支付订单** | 状态机、网关、退款、评价 | 6 页 + 服务端 |
| **S9 案件中心** | 案例、立案、进度、送达 | 7 页 |
| **S10 律师工作台** | 工作台 7 页 | 7 页 |
| **S11 设置与其他** | 设置、安全、认证、客服等 | 18 页 |
| **S12 管理后台** | 后台全部模块 | Web 后台 |
| **S13 风控与监控** | 风控、内容审核、监控告警 | 上线保障 |
| **S14 多语言校对** | 4 语言、字体、RTL、母语验收 | — |
| **S15 测试验收** | 全量测试、真机、性能、渗透 | 上线版本 |
| **S16 上架** | 资质、备案、商店提交 | 上线 |

## 23.2 并行事项

| 事项 | 启动时间 |
|---|---|
| 主体注册 | S0 同步 |
| ICP/App 备案 | S0 同步（周期长） |
| 软著 | S1 完成后 |
| AI 备案 | S0 同步（2-3 个月） |
| 支付商户号 | S7 前完成 |
| 等保测评 | S12 后 |

## 23.3 里程碑

| 里程碑 | 时间 | 标志 |
|---|---|---|
| M1 内部演示 | 第 6 周 | S0-S5 |
| M2 功能完整 | 第 16 周 | 全部页面 + 后台 |
| M3 上线候选 | 第 19 周 | 多语言、测试、资质 |
| M4 正式上线 | 第 22 周 | 商店上架 |

---

# 二十四、交付物清单

| 类别 | 内容 |
|---|---|
| 移动端 | `apps/mobile-app/`（Expo） |
| 后端 | `services/faze-api/`（Spring Boot） |
| 管理后台 | `apps/admin/`（Vue 3） |
| 数据库 | Flyway 迁移脚本、种子数据 |
| 部署 | docker-compose、nginx、.env.example |
| 监控 | Prometheus、Grafana 配置、告警规则 |
| 文档 | 导航树、技术路线、设计令牌、架构、部署、规范 |
| 产物 | Android APK、后台镜像 |
| 资质 | 软著、备案、主体、AI 备案、合作协议 |

---

# 二十五、附录

## 附录 A：关键技术依据

| 结论 | 依据 | 核实状态 |
|---|---|---|
| RTL 切换需重启 | RN 官方 `I18nManager`：`allowRTL`/`forceRTL` 变更「take effect on the next application start, not immediately」 | ✅ 已核实原文 |
| 藏文字体须含 GSUB | OpenType 规范：复杂文字依赖 GSUB/GPOS，由系统排版引擎执行 | ✅ 规范确定 |
| `kk` 默认西里尔 | BCP 47：`kk` 无书写系统子标签时默认西里尔；中国境内哈萨克文须写 `kk-Arab-CN` | ✅ 规范确定 |
| `ug-Arab-CN` 的 `Arab` 冗余 | `ug` 默认书写系统即 Arab，CLDR 惯例写 `ug-CN`；本项目保留 `ug-Arab-CN` 以显式声明 | ✅ 规范确定 |
| 阿拉伯文 `letterSpacing` 断连 | RN `Text`：非零字距破坏阿拉伯字母连接 | ⚠️ 需实测 |
| **`openhtmltopdf` 不做 shaping** | PDFBox 2 按 cmap 逐字符取字形，不执行 GSUB/GPOS | ⚠️ 需实测维/哈文样例 |
| **生成式 AI 备案是有条件的** | 《生成式人工智能服务管理暂行办法》第 17 条（舆论属性/社会动员能力）+ 第 2 条第 3 款（未向公众提供服务不适用） | ✅ 已核实原文 |
| 可靠电子签名需 CA | 《电子签名法》第 13 条四项要件、第 14 条效力 | ⚠️ 条号需以法规库为准 |
| 手写笔迹属敏感个人信息 | 《个人信息保护法》第 28 条 | ⚠️ 条号需以法规库为准 |
| 律师费代收代付构成二清 | 需支付业务许可证；具体认定口径以主管部门为准 | ⚠️ 需咨询 |

> **免责**：本表法条编号按记忆标注，正式发文前须到国家法律法规数据库逐条核对。本文不是法律意见。

## 附录 F：V2.3 待修清单（V2.2 未处理的）

以下问题已在评审中确认，但因涉及大面积 DDL 改造，留待 V2.3 统一处理：

### F-1 数据库（严重）

| # | 问题 | 影响表 |
|---|---|---|
| 1 | 12 张表缺 `deleted_at` / `updated_at`，与 5.2「所有表都有」的声明矛盾 | `realname_auth` `enterprise_auth` `payment_record` `refund_record` `review` `notification` `complaint` `feedback` `favorite` `signature` `conversation_message` |
| 2 | 关键外键字段无索引，律师侧与会话侧列表全表扫 | `review.lawyer_id` `complaint.user_id` `lawyer.audit_status/online_status/region` `lawyer_application.audit_status` |
| 3 | `favorite` / `follow` 划归 `lawyer` 域，但 target 跨 article/case/document，与「禁止跨域访问 mapper」冲突 | 建议独立 `social` 域或归 `content` |
| 4 | `article` 主表无 `title`/`content`，中文也依赖翻译表；`article_translation.status` 与 `article.status` 两套状态机 | 建议 zh-Hans 也建行 + content 改 JSON 块 |
| 5 | `case_library` 用行内 `lang` 单语，无法维护四语言 | 建议拆 translation 表 |

### F-2 缺失的表

| # | 表 | 用途 |
|---|---|---|
| 6 | `user_device` | 推送 token、设备指纹、定向推送 |
| 7 | `sys_config` | 平台参数（抽成比例、提现门槛） |
| 8 | `notify_template` | 通知模板 |
| 9 | `blacklist` / `violation` | 违规记录（期限、原因、处罚） |
| 10 | `reconciliation_diff` | 对账差错工单 |
| 11 | `invoice` | 发票 |
| 12 | `file_record` | 上传文件归属、孤儿清理、鉴权下载 |
| 13 | `lawyer_application_log` | 入驻审核流水（当前是单行 UPDATE，无历史） |
| 14 | `sensitive_word` | 敏感词库（来源、更新人、误杀申诉） |
| 15 | `login_log` / `operation_log` | 安全审计（V2.1 提到但未在 DDL 中定义） |

### F-3 其他

| # | 问题 |
|---|---|
| 16 | 业务缓存策略缺失（律师列表、热门文章、未读数的 key/TTL/失效时机） |
| 17 | 深色模式：3.4 令牌只有一套 color，无 dark 方案 |
| 18 | 日期/相对时间本地化方案缺失（"3 天前""答辩期 15 日"） |
| 19 | 法律术语 glossary 只服务 AI，模板与文章翻译无统一术语表 |
| 20 | 敏感词"实时替换"在法律业务下有害——IM 记录可能作为证据，替换即篡改。应改为"原文落库 + 命中标注 + 不展示" |
| 21 | AI 内容标识只做显式，缺隐式（文件元数据） |
| 22 | AI 缺禁用清单（诉讼策略、胜诉率承诺、赔偿额预测、代写可递交文书）—— V2.2 已在 11.3 部分补齐，仍需落实到 UI 与后端拦截 |
| 23 | 未成年人与监护人同意的专门机制 |
| 24 | 文书后处理链：Word 下载/分享、打印 A4 边距、文书微信分享 |
| 25 | 客服角色在 `role` 枚举与 `sender_role` 枚举中不存在 |
| 26 | `qr_code` 等运营素材、分享海报生成方案 |
| 27 | 性能基线：首屏时间、列表滚动帧率、APK 体积目标 |
| 28 | 演示/测试数据 seed 方案（本期真实上线，需要初始化与回归数据） |

## 附录 B：端口规划

| 服务 | 端口 |
|---|---|
| Nginx HTTP/HTTPS | 80 / 443 |
| API | 8080 |
| Admin | 8081 |
| MySQL | 3306 |
| Redis | 6379 |
| RabbitMQ | 5672 / 15672 |
| Prometheus | 9090 |
| Grafana | 3000 |

## 附录 C：错误码分段

| 段位 | 模块 |
|---|---|
| 1xxxx | 通用 |
| 2xxxx | 账户 |
| 3xxxx | 律师 |
| 4xxxx | 会话 |
| 5xxxx | 交易 |
| 6xxxx | 文书 |
| 7xxxx | 案件 |
| 8xxxx | AI |
| 9xxxx | 运营/投诉 |

## 附录 D：TabBar 配置

| 序号 | Tab | 选中色 |
|---|---|---|
| 1 | 首页 | #4E6EF2 |
| 2 | 律师 | #4E6EF2 |
| 3 | 智能体 | #4E6EF2 |
| 4 | 案件 | #4E6EF2 |
| 5 | 我的 | #4E6EF2 |

青绿 `#2C9696` 仅用于在线/进行中/成功状态。

## 附录 E：月成本估算

| 项 | 月费用 |
|---|---|
| 服务器 4C8G | 150-250 元 |
| 域名 | 5 元/月 |
| OSS + CDN | 30-80 元 |
| 短信 | 0.045 元/条，按量 |
| 豆包 API | 按量，约 50-200 元 |
| 推送 | 免费版 |
| **合计** | **约 300-600 元/月** |

---

> 文档结束。V2.1 与页面导航树 v1.3 配套，后续变更同步更新。
