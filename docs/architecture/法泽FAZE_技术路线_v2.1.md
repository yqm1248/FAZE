# 法泽 FAZE —— 技术路线（V2.1 完整版）

> 版本：v2.1
> 日期：2026-10-27
> 对应页面导航树：`法泽APP_页面导航树.md` v1.3（65 个页面）
> 项目性质：正式上线产品（生产标准）
> 参考工程：`zxz-bi-server`（现代工程规范）、`wgcloud`（安全细节）
> 变更：V2.1 补全完整数据库 DDL（30 张表）、WebSocket/SSE 消息格式、AI 合规、管理后台、监控告警、风控、测试体系、业务闭环异常流程

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
| 推送 | expo-notifications + 个推 SDK | 最新 |
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
| 消息队列 | RabbitMQ | 3.13（管理界面） |
| 实时通信 | spring-boot-starter-websocket | 随 Boot |
| HTTP 客户端 | WebClient（spring-webflux） | 随 Boot |
| 接口文档 | springdoc-openapi | 2.8.9 |
| 构建 | Maven | 3.9+ |
| 工具库 | Hutool | 5.8.24 |
| Excel | EasyExcel | 4.0.3 |
| Word 模板 | poi-tl | 1.12.2 |
| PDF 生成 | openhtmltopdf | 1.0.10 |
| JWT | jjwt | 0.12.6 |
| 加密 | BouncyCastle | 1.76 |
| 配置加密 | Jasypt | 3.0.5 |
| 数据库迁移 | Flyway | 10.x |
| 定时任务 | Spring Scheduled / XXL-Job | 随 Boot |
| 对象存储 | 阿里云 OSS | 3.18.1 |
| 短信 | 阿里云短信服务 | 最新 |
| 推送 | 个推 RestAPI | 最新 |
| 内容安全 | 阿里云内容安全 | 最新 |
| 链路追踪 | Micrometer Tracing + Zipkin | 随 Boot |
| 健康检查 | Spring Boot Actuator | 随 Boot |
| 密码哈希 | Spring Security Crypto（BCrypt） | 随 Boot |

## 2.3 管理后台

| 项 | 选型 |
|---|---|
| 框架 | Vue 3 + Vite + TypeScript |
| UI 库 | Element Plus |
| 状态 | Pinia |
| 路由 | Vue Router |
| 请求 | Axios |
| 图表 | ECharts |

## 2.4 明确不采用

| 不采用 | 理由 |
|---|---|
| Flutter | 团队技能不在 Dart；已有 12 页 RN 原型 |
| uni-app / Taro | 不做微信小程序 |
| 微服务 | 单人维护成本不可接受，单体起步 |
| Kubernetes | 单机足够，后期视规模 |
| 托管 IM SDK | 会话数据需落自有库、可审计 |
| 自建 AI 推理 | 无 GPU 预算，直接调豆包 API |
| 向量数据库 | 法条知识依赖豆包模型，不做 RAG |
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
| 微信分享 | 微信开放平台注册 App，分享律师名片/文章/小程序卡片 |
| 网络状态 | `NetInfo` 监听，断网全局提示，恢复后自动重试 |

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
| **ID 生成** | 雪花算法（Snowflake），订单号、消息 ID 全局唯一、趋势递增 |
| **密码哈希** | BCrypt（cost=10），禁止 MD5/SHA 明文 |
| **线程池** | 参数化配置，拒绝策略、异常处理、优雅关闭 |

---

# 五、数据库设计（完整 DDL）

## 5.1 表清单（30 张表）

| 序号 | 表名 | 用途 |
|---|---|---|
| 1 | `user` | 用户表 |
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
-- 1. 用户表
CREATE TABLE `user` (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  phone           VARCHAR(20)  NOT NULL,
  password_hash   VARCHAR(100),
  nickname        VARCHAR(50),
  avatar_url      VARCHAR(500),
  gender          TINYINT      DEFAULT 0,        -- 0未知 1男 2女
  role            VARCHAR(20)  DEFAULT 'user',   -- user / lawyer / admin
  lang            VARCHAR(20)  DEFAULT 'zh-Hans',
  realname_status VARCHAR(20)  DEFAULT 'none',   -- none / pending / approved / rejected
  status          VARCHAR(20)  DEFAULT 'active', -- active / blocked / cancelled
  last_login_at   DATETIME,
  last_login_ip   VARCHAR(50),
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL,
  deleted_at      DATETIME,
  UNIQUE KEY uk_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  unread_user   INT DEFAULT 0,
  unread_lawyer INT DEFAULT 0,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  deleted_at    DATETIME,
  UNIQUE KEY uk_conversation_user_lawyer (user_id, lawyer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. IM 消息
CREATE TABLE conversation_message (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT      NOT NULL,
  seq             BIGINT      NOT NULL,
  sender_role     VARCHAR(10) NOT NULL,      -- user / lawyer / system
  sender_id       BIGINT      NOT NULL,
  msg_type        VARCHAR(15) NOT NULL,      -- text / image / file / system / recall
  content         TEXT,
  file_url        VARCHAR(500),
  file_name       VARCHAR(200),
  file_size       BIGINT,
  read_at         DATETIME,
  recalled_at     DATETIME,
  created_at      DATETIME NOT NULL,
  KEY idx_msg_conversation_seq (conversation_id, seq)
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
  channel         VARCHAR(20),               -- wechat / alipay / offline
  account_name    VARCHAR(50),
  account_number  VARCHAR(100),
  status          VARCHAR(20) NOT NULL,       -- applying / approved / rejected / paid
  transaction_id  VARCHAR(64),
  audited_by      BIGINT,
  audited_at      DATETIME,
  paid_at         DATETIME,
  reject_reason   VARCHAR(500),
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

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
CREATE TABLE document (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT NOT NULL,
  template_id  BIGINT,
  title        VARCHAR(200) NOT NULL,
  doc_type     VARCHAR(30),
  form_data    JSON,
  status       VARCHAR(20) DEFAULT 'draft',  -- draft / generated / signed / exported
  word_url     VARCHAR(500),
  pdf_url      VARCHAR(500),
  signed_pdf_url VARCHAR(500),
  lawyer_id    BIGINT,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  deleted_at   DATETIME,
  KEY idx_document_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 15. 电子签章
CREATE TABLE signature (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  image_url     VARCHAR(500) NOT NULL,
  document_id   BIGINT,
  position_x    INT,
  position_y    INT,
  document_hash VARCHAR(128),
  created_at    DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 5.7 案件

```sql
-- 16. 委托案件
CREATE TABLE `case` (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_no       VARCHAR(32) NOT NULL,
  user_id       BIGINT NOT NULL,
  lawyer_id     BIGINT,
  title         VARCHAR(200),
  case_type     VARCHAR(30),
  description   TEXT,
  evidence_urls JSON,
  status        VARCHAR(20) DEFAULT 'filed', -- filed / accepted / in_progress / waiting_judgment / closed
  court_name    VARCHAR(100),
  amount_cents  BIGINT,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  deleted_at    DATETIME,
  UNIQUE KEY uk_case_no (case_no),
  KEY idx_case_user (user_id),
  KEY idx_case_lawyer (lawyer_id)
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

```text
user 1───0..1 lawyer
user 1───0..1 realname_auth
user 1───0..1 enterprise_auth
user 1───* conversation *───1 lawyer
conversation 1───* conversation_message
user 1───* orders *───0..1 lawyer
orders 1───* payment_record
orders 1───0..1 refund_record
orders 1───0..1 review
lawyer 1───* withdraw_record
user 1───* document
document 1───* signature
user 1───* case *───0..1 lawyer
case 1───* case_progress
article 1───* article_translation
user 1───* favorite
user 1───* follow
user 1───* notification
user 1───* complaint
user 1───* feedback
user 1───* ai_session 1───* ai_message
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

| 角色 | 权限 |
|---|---|
| `user` | C 端全部功能 |
| `lawyer` | 继承 user + 律师工作台（`@LawyerOnly`） |
| `admin` | 管理后台 |

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

## 6.4 接口加密

敏感接口（登录、支付、IM）支持 body 加密：

```text
客户端：
  1. 生成随机 AES 密钥（每次会话）
  2. RSA 公钥加密 AES 密钥，放 Header（encrypt-key）
  3. AES 加密请求 Body
服务端：
  1. CryptoFilter 检查 Header
  2. RSA 私钥解出 AES 密钥
  3. AES 解密 Body
  4. 响应按需 AES 加密
```

| 项 | 规则 |
|---|---|
| 注解 | `@ApiEncrypt(request=true, response=false)` |
| 非对称 | RSA 2048（或 SM2） |
| 对称 | AES-128-GCM，每次会话随机 |
| 开关 | `api-decrypt.enabled`，生产开启 |

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

## 7.3 数据隔离（三条硬规则）

1. **拉历史只传 `conversationId`**，服务端用 JWT 的 `userId` 反查成员，不是则 403。
2. 分页用 `seq` 游标（`WHERE conversation_id=? AND seq < ? ORDER BY seq DESC LIMIT 20`），不用 OFFSET。
3. 发送消息时 `senderId` 取自 JWT，不信任客户端。

## 7.4 已读、撤回、重连、心跳

| 功能 | 实现 |
|---|---|
| 已读 | 上报 `lastReadSeq` → 更新 `read_at`、重置 `unread_*` → 推送对端 |
| 撤回 | 2 分钟内可撤回；置 `recalled_at`，前端渲染"消息已撤回" |
| 重连 | 携带 `lastReceivedSeq`，服务端补推之后的消息 |
| 心跳 | 客户端每 30 秒 ping，90 秒无心跳判离线 |
| 输入状态 | 一方输入时推 `chat.typing`，显示"对方正在输入..."，3 秒无输入自动消失 |

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

## 8.3 前端处理

| 项 | 实现 |
|---|---|
| 建立连接 | react-native-sse，GET `/api/v1/ai/stream`，带 `sessionId`、`question`、`lang` |
| 渲染 | 收到 `delta` 追加文本，打字机效果 |
| 中断 | 页面卸载或用户点停止，abort 连接，后端取消上游请求 |
| 失败 | 显示重试按钮，重试走同一 `sessionId` |
| 重连 | 网络中断自动重连，携带已接收长度，避免重复 |

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

## 9.2 订单状态机

```text
                    ┌─────────────┐
                    │  PENDING    │ 待支付
                    └──────┬──────┘
                 支付成功  │  15分钟超时/取消
              ┌─────────┴─────────┐
              ▼                   ▼
        ┌───────────┐       ┌───────────┐
        │  PAID     │       │ CANCELLED │
        │  待接单    │       └───────────┘
        └─────┬─────┘
      律师接单 │  30分钟无人接
              ▼
        ┌───────────┐
        │ IN_SERVICE│ 进行中
        └─────┬─────┘
     服务完成 │  申请退款
       ┌──────┴──────┐
       ▼             ▼
 ┌───────────┐  ┌───────────┐
 │ COMPLETED │  │ REFUNDING │
 │ 资金冻结7天│  └─────┬─────┘
 └───────────┘        │ 退款成功
                      ▼
                ┌───────────┐
                │ REFUNDED  │
                └───────────┘
```

## 9.3 三条铁律

1. 客户端只发意图，状态迁移由服务端判定。
2. 状态迁移用状态机统一校验，非法迁移抛业务异常。
3. 回调幂等：`payment_record` 对 `(order_id, channel, transaction_id)` 唯一，重复回调直接返回成功。

## 9.4 掉单与补单

| 场景 | 处理 |
|---|---|
| 支付成功但回调没到 | 客户端支付后主动查询订单状态，服务端定时（每 5 分钟）主动向支付渠道查询 PENDING 订单，补单 |
| 重复支付 | 同一订单只认第一笔成功支付，其余自动原路退回 |
| 回调验签 | 每个回调必须验签（微信/支付宝签名），验签失败拒绝并告警 |

## 9.5 对账系统

| 项 | 规则 |
|---|---|
| 对账频率 | 每日凌晨 2 点拉取前一日支付渠道账单，与本地 `payment_record` 逐笔比对 |
| 差错类型 | 本地有渠道无（掉单）、渠道有本地无（漏单）、金额不一致 |
| 差错处理 | 自动补单/退款，无法自动处理的生成对账差错工单，财务人工处理 |
| 对账报表 | 每日生成对账报告，留存 3 年 |

## 9.6 分账与资金

| 项 | 规则 |
|---|---|
| 分账比例 | 平台抽成可配置（默认 15%），`lawyer_income = amount - platform_fee` |
| 资金冻结 | 订单完成后律师应得资金冻结 7 天（防退款纠纷），7 天后进入可提现余额 |
| 提现 | 微信商家转账到零钱 / 支付宝转账（二期）；一期线下结算 |
| 提现门槛 | 最低 100 元，T+1 到账 |
| 发票 | 用户可申请电子发票，平台开具"法律咨询服务费"发票 |

## 9.7 支付商户号

| 项 | 要求 |
|---|---|
| 微信支付商户号 | 营业执照、对公账户（个体户可法人个人账户）、已上线 App/网站，审核 1-2 周 |
| 支付宝商户 | 同上，支付宝开放平台申请 |
| 费率 | 一般 0.6% 手续费 |
| 测试 | 沙箱环境联调，上线前小额真实支付验证 |

---

# 十、文书与签章

## 10.1 生成链路

```text
模板(template) ──► 动态字段定义(field_schema JSON)
用户填表 ──► 表单数据(form_data JSON)
                    ▼
        服务端渲染 ──► Word(poi-tl) / PDF(openhtmltopdf)
                    ▼
              OSS 存储 ──► 返回预览 URL
用户签章 ──► 合成签章图 ──► 重新生成带章 PDF
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

## 10.3 PDF 少数民族文字

| 项 | 要求 |
|---|---|
| 字体 | 显式注册 Noto Sans Tibetan / Noto Sans Arabic |
| RTL 段落 | `<span dir="rtl">` 包裹 |
| 混排 | 数字与案号用 `<span dir="ltr">` 隔离 |
| 验证 | 每种语言生成样例 PDF，人工目视（必做） |

## 10.4 电子签章

| 项 | 方案 |
|---|---|
| 手写板 | react-native-skia 绘制，导出 PNG |
| 拖拽定位 | gesture-handler + reanimated |
| 合成 | openhtmltopdf 叠加签章图到指定坐标 |
| 追溯 | `signature` 表存签名图、时间、文书哈希 |
| 法律效力 | 手写图不属于可靠电子签名，不主张法律效力；如需可靠电子签名需接入 CA 机构（CFCA），二期 |

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
| 模型 | doubao-seed-1.6 / doubao-1.5-pro，按成本选择 |
| 接口 | OpenAI 兼容 `/api/v3/chat/completions` |
| 调用 | 服务端 WebClient，API Key 不暴露 |
| 流式 | SSE 逐 token |

## 11.3 系统 Prompt

```text
你是「法泽」的专业法律顾问，请严格按以下要求回答：

1. 结构：结论 → 法律依据（引用具体法条编号）→ 操作步骤 → 风险提示
2. 只回答中国法律相关问题
3. 法条引用必须准确，不确定时明确说明，禁止编造法条编号
4. 语言：按用户当前语言回答（中文/藏文/维文/哈文）
5. 末尾附：「本回答由 AI 生成，不构成正式法律意见，复杂问题建议咨询专业律师」
```

## 11.4 多语言

- 后端按 App 当前语言（`zh-Hans`/`bo`/`ug-Arab-CN`/`kk-Arab-CN`）追加语言指令
- 豆包中文能力强，藏/维/哈文上线前实测，不足走"豆包中文回答 + 机器翻译"中间层
- 法律术语表注入

## 11.5 成本控制

| 机制 | 规则 |
|---|---|
| 轮次限额 | 每用户每天 50 轮（可配） |
| 上下文 | 最多最近 10 轮，超出截断 |
| Token 监控 | 按用户/天统计，异常告警 |
| 重试 | 上游失败重试 2 次，指数退避 |
| 中断 | 用户离开 abort，后端取消 |

## 11.6 幻觉风险

- 豆包可能生成看似合理但错误的法条编号或内容
- Prompt 明确禁止编造，要求不确定时说明
- 涉及具体案件、金额、期限的，UI 提示"请以法院/律师意见为准"
- 高风险领域（刑事、重大财产）强制提示转人工律师

---

# 十二、AI 合规与内容安全

## 12.1 备案要求

| 备案 | 要求 | 周期 |
|---|---|---|
| **生成式 AI 服务备案** | 《生成式人工智能服务管理暂行办法》，提供生成式 AI 服务须向省级网信办备案 | 2-3 个月 |
| **深度合成算法备案** | 互联网信息服务深度合成管理规定，文本生成属于深度合成 | 1-2 个月 |
| **大模型备案** | 豆包模型已由火山引擎备案；基于豆包的应用需确认是否需独立备案，咨询火山引擎 | 视情况 |

注意：AI 备案周期长，**必须在开发启动时同步开始**，不能等开发完。

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

## 13.3 字体加载

| 步骤 | 实现 |
|---|---|
| 1 | 字体文件放 `assets/fonts/`，随包分发 |
| 2 | `useFonts` 根布局加载，`fontsLoaded` 门控首屏 |
| 3 | 藏文字体置 `fontFamily` 主位 |
| 4 | 藏文字体不做子集化（GSUB 复合字形） |
| 5 | 阿拉伯系 `letterSpacing` 必须为 0 |
| 6 | 藏文 `lineHeight ≥ 1.6 × fontSize` |

## 13.4 RTL 切换流程

```text
用户选择语言
   ├─ 方向相同（中↔藏）
   │     └─► changeLanguage() → 全树重渲染，不重启
   └─ 方向不同（LTR↔RTL）
         └─► changeLanguage() → 持久化
             → Updates.reloadAsync()（软重载 1-2 秒）
             → 重启后 I18nManager 生效
```

依据：RN 官方 `I18nManager`：变更下次启动生效。

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

## 15.2 技术实现

- Vue 3 + Vite + TS + Element Plus + Pinia
- 独立部署，`admin.faze.com`
- 仅 admin 角色可登录，二次验证（可选）
- 所有操作留痕，敏感操作二次确认

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
| 经营主体 | 个体工商户或公司 | 四川政务服务网，1-3 天 |
| 经营范围 | 法律咨询服务（不含诉讼代理） | 注册时勾选 |
| 税务 | 小规模纳税人 | 同步办理 |

## 20.2 备案

| 类型 | 周期 |
|---|---|
| ICP 备案 | 2-3 周 |
| App 备案 | 2-3 周 |
| 公安联网备案 | 上线后 30 天内 |
| 生成式 AI 备案 | 2-3 个月（提前启动） |
| 算法备案 | 1-2 个月 |

## 20.3 软著

- 源代码（前 30 + 后 30 页）+ 说明书 + 申请表
- 普通 30-60 天，加急 3-15 天

## 20.4 行业资质

| 方案 | 说明 |
|---|---|
| 方案 A（推荐） | 定位"法律科普 + AI 咨询 + 信息撮合"，不直接诉讼代理，绕开法律服务许可 |
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

| 结论 | 依据 |
|---|---|
| RTL 切换需重启 | RN 官方 I18nManager：变更下次启动生效 |
| 藏文字体须含 GSUB | OpenType：复杂文字依赖 GSUB/GPOS |
| kk 默认西里尔 | BCP 47：kk 无书写系统子标签默认西里尔 |
| 阿拉伯文 letterSpacing 断连 | RN Text：非零字距破坏字母连接 |
| 生成式 AI 须备案 | 生成式人工智能服务管理暂行办法 |
| 可靠电子签名需 CA | 电子签名法第十三条 |

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
