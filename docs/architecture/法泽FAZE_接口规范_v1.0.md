# 法泽 FAZE · 接口规范（V1.0）

> **用途**：第 0 段起，所有 HTTP / SSE / WebSocket 契约的唯一依据。前后端联调、PC 后台对接、契约测试都以本文件为准。
> **定稿日期**：2026-10-30
> **决策依据**：项目负责人 2026-10-30 拍板「**混合 OpenAPI**」——第 0/1 段的 5 组核心接口契约先行（L1），其余接口由 springdoc 注解生成（L3）。
> **取代范围**：技术路线 v2.3 §4.4 的 11 行表格、§4.8.1 的前缀表、§4.8.4.2 的契约约定条目。**不取代** §4.8.2 / §4.8.4 的接口清单（清单管「有哪些接口」，本文件管「接口长什么样」）。
> **不重复的内容**：错误码见《[法泽FAZE_错误码规范_v1.0.md](法泽FAZE_错误码规范_v1.0.md)》（78 个码已冻结）；表结构见《[法泽FAZE_数据表规范_v1.0.md](法泽FAZE_数据表规范_v1.0.md)》。

---

# 一、先看问题：现在到底有没有接口规范

## 1.1 实测结论

| 项 | 现状 | 证据 |
|---|---|---|
| 「接口规范」正文 | **11 行表格** | 技术路线 L579–593 |
| 字段级契约 | **0 个** | 全仓 `OpenAPI` 关键字 0 次命中、`services/` 目录不存在、无任何 `.yaml`/`.json` 契约文件 |
| 契约的产生方式 | 一句话「以 Swagger UI 为准」 | 技术路线 L684、L799 |
| 接口清单 | §4.8.2 十个域 64 行表格 + §4.8.4 的 87 个 admin 接口 | L697–762、L819–962 |
| 错误码 | 已冻结 78 个，6 位 `[C][DD][NNN]` | 《错误码规范》§2、§3 |
| 分页 | **3 套并行口径** | L585（pageNum）、L587（IM 游标）、L812（后台 pageNum） |
| 废弃策略 | **0 处** | 全仓 `deprecated` / `Sunset` / `废弃策略` 0 次命中 |
| 契约测试 | **0 处** | 无 CI 任务、无快照文件 |

**一句话结论**：目前的「接口规范」只定了**风格**（REST、`/api/v1/`、`R<T>`、页码、整数分、Bearer），没定**契约**。而「字段级契约」在数据表规范里已经被证明是必须的——那一次是 49 张表的名字和字段，这一次是 165 个端点的入参出参。

## 1.2 六处必须今天裁决的冲突

| # | 冲突 | 现状 | 不裁决的后果 |
|---|---|---|---|
| 1 | **错误码示例已被推翻但没标作废** | 技术路线 L779 仍是 `400401`/`500501`/`800801`；《错误码规范》§五 已宣布作废、改用 `401001`/`405001`/`508001` | 写代码的人照 L779 抄，抄出 4 个错误码，与客户端映射表对不上 |
| 2 | **WebSocket 错误码是 5 位** | L2392 `"code": 40301` | 与 6 位冻结方案冲突；客户端 WS 错误分支无法与 REST 共用映射 |
| 3 | **401 语义与 RuoYi 前端对撞** | 《错误码规范》§2.2 冻结「鉴权失败 → HTTP 401 + code 402002」；RuoYi `request.js` **只在 HTTP 200 且 `body.code===401` 时跳登录**，收到 HTTP 401 只弹「系统接口401异常」 | PC 后台 token 过期后**不跳登录页，用户卡死** |
| 4 | **分页三套口径** | C 端 `{list,total,pageNum,pageSize,hasMore}`；RuoYi 列表页读 `res.rows` + `res.total`；IM 用 `seq` 游标 | 每个 PC 列表页都要手写适配，25 个页面 × 一次 |
| 5 | **段 0 探针路径不符合前缀规范** | 总纲 §5.1 与锚点 `0-A2`/`0-A5` 写的是 `/api/v1/ping`、`/api/v1/ops/probe/ai`、`/api/v1/ops/probe/law`；§4.8.1 只有 `pub`/`app`/`admin`/`internal` 四类前缀，**没有 `ops`、也没有裸路径** | 段 0 第一天就违反自己定的前缀表 |
| 6 | **订单动作命名撞车** | §4.8.2 L733 `POST /app/orders/{id}/confirm` 的用途写的是「律师接单/拒单/完成」——**一个路径三种语义** | 后端无法路由，只能靠 body 里的字段再分叉；前端无法区分 |

## 1.3 还有一处文档从没定义过、但会让段 1 的 PC 7 页全卡死

**问题**：PC 登录走 RuoYi 后端（§4.8.4.1 冻结：A-01 走 RuoYi 的 `/captchaImage`、`/login`），但**业务接口由 faze-api 提供**（`/api/v1/admin/**`）。两个独立 Spring Boot 应用，各自的 JWT secret 不同。**文档全仓 0 处定义两者如何互认身份。**

**硬证据**（读的是 RuoYi-Vue3 **3.9.2** 源码 `D:\aiCodeProject\projects\ruoyi-admin\src`；该 clone 有 12 处本地改动，但 `auth.js` 与 `request.js` 的 401 分支均未受影响，与 3.8.7 逐行比对语义一致）：

| 文件 | 行 | 内容 | 推论 |
|---|---|---|---|
| `utils/auth.js` | 1–14 | token 存 Cookie `Admin-Token` | PC 的凭证来源是 Cookie，不是 localStorage |
| `utils/request.js` | 32 | `config.headers['Authorization'] = 'Bearer ' + getToken()` | 每次请求把 Cookie 里的 token 塞进 Authorization 头 |
| `api/login.js` | 11–22 | `url: '/login'` 打到 `VITE_APP_BASE_API` | 签发方是 RuoYi 后端，不是 faze-api |
| `utils/request.js` | 78–96 | `code === 401` 才弹重新登录 | 见 §1.2 冲突 3 |

**三个方案**（必须先拍板才能开段 1 的 PC 部分）：

| 方案 | 做法 | 优点 | 代价 | 风险 |
|---|---|---|---|---|
| **A. 共享凭证**（推荐） | faze-api 与 RuoYi 配**同一个 `token.secret`**、连**同一个 Redis**；faze-api 复刻 RuoYi 的 `JwtAuthenticationTokenFilter` 逻辑：解 JWT → 取 `login_user_key` → 查 `login_tokens:<uuid>` → 反序列化出 userId/permissions | 一处登录，RuoYi 自带 8 页和业务页共用 token；不改 RuoYi 框架层 | faze-api 要引 RuoYi 的 `LoginUser` 结构或做宽松 JSON 解析 | RuoYi 升级改 `LoginUser` 字段会让 faze-api 解析失败；需在 `ruoyi-patches.md` 登记耦合点 |
| **B. faze-api 自签** | PC 业务页另建 axios 实例（`src/api/biz/request.js`，不改框架层），登录调 faze-api `/api/v1/admin/auth/login` | 两端彻底解耦，faze-api 不依赖 RuoYi | **双登录**：RuoYi 自带页要 RuoYi token，业务页要 faze token，用户要登两次或做静默换取 | 体验差；换取接口又要维护一套映射表 |
| **C. 网关统一** | 加一层反代/网关，按路径分流，token 统一在校验 | 最干净 | 多一个部署单元；段 0 的成本上去了 | 与「段 0 不做业务、只做底座」冲突 |

> **✅ 已拍板（2026-10-30）：采用方案 A**——项目负责人选定「共享 `token.secret` + 共享 Redis」。本文件后续条款按 A 写；若日后改 B，§6.2 与附录 C 要重写。

### 1.3.1 方案 A 的实施要点（2026-10-30 拍板后补）

| # | 事项 | 要点 |
|---|---|---|
| 1 | **`token.secret` 必须完全一致** | 两端从**同一个环境变量**注入（`FAZE_TOKEN_SECRET`），不各写一份 yml 值——写两份一定漂移 |
| 2 | **同一个 Redis 库，但 key 前缀隔离** | RuoYi 用 `login_tokens:`；faze-api 的业务缓存放 `faze:` 前缀。否则清业务缓存会误伤登录态 |
| 3 | **复刻 `JwtAuthenticationTokenFilter` 的校验链** | 解 JWT → 取 `login_user_key`（UUID）→ 查 `login_tokens:<uuid>` → 反序列化出 userId / username / permissions → 放进 SecurityContext |
| 4 | **只取必要字段** | faze-api 不需要完整的 `SysUser`。解析时**只读 userId、username、permissions**，其余忽略——降低对 RuoYi 版本的耦合 |
| 5 | **✅ 已实测（2026-11-01）：`LoginUser` 用 JSON 序列化，跨服务可读** | RuoYi-Vue 3.9.2 的 `ruoyi-framework/.../config/RedisConfig.java` L29–33：value 用 `FastJson2JsonRedisSerializer`，**不是 JDK 序列化**；`TokenService` L155 `redisCache.setCacheObject(loginUser)` 直写 `login_tokens:<uuid>`。faze-api 用任意 JSON 库都能读。**但 `serialize()` 带 `JSONWriter.Feature.WriteClassName`，JSON 里会出现 `@type` 字段**——faze-api 用 Jackson 解析必须设 `FAIL_ON_UNKNOWN_PROPERTIES=false`，否则报错 |
| 6 | **登记耦合点** | 在 `docs/analysis/ruoyi-patches.md` 记一条：faze-api 依赖 RuoYi 的 `login_tokens:<uuid>` 结构与 `LoginUser` 字段。RuoYi 升级时要回归 |

**退路口径变更（2026-11-01）**：方案 A **不再因「序列化不兼容」降级**——第 5 条已实测通过（JSON 可读）。方案 B 只作为「faze-api 无法兼容 `LoginUser` 结构」时的备选。

---

# 二、契约产生方式（混合方案的落地）

## 2.1 三级契约

混合方案不是「一半契约先行一半不写」，而是**按改动代价分三级**：

| 级 | 名称 | 怎么产生 | 什么时候用 | 冻结程度 |
|---|---|---|---|---|
| **L1** | 冻结契约 | 手写 OpenAPI YAML，**先于代码**，双端各按契约实现 | 跨端并行开发、字段会被前端写死、验收锚点直接引用该接口 | 字段名、必填性、错误码**不可改**（改要双端签字） |
| **L2** | 文档契约 | 在本文件或技术路线的表格里写清 方法+路径+入参+出参+错误码 | 单端内部为主、字段可能增删、验收不直接引用字段 | 路径与方法不可改，字段可加不可改 |
| **L3** | 运行时契约 | springdoc 注解 → `/v3/api-docs` → Swagger UI | 探针、内部调试、admin 里低频的查询接口 | 以运行结果为准，改前只要通知 |

**判级判据**（三条满足任一条即升到 L1）：
1. 该接口有**验收锚点**直接依赖它（锚点里点名的路径/操作的）；
2. 该接口的字段会被**至少两端同时写死**（App + PC 都要用）；
3. 该接口涉及**资金**或**状态机**，字段错一位就出事。

## 2.2 5 组 L1 契约（本段冻结）

| # | 组 | 端点 | 为什么必须 L1 | 覆盖锚点 | 落段 |
|---|---|---|---|---|---|
| 1 | **登录** | `POST /pub/auth/login/password`、`POST /pub/auth/login/sms`、`POST /app/auth/refresh` | 全端第一个接口；`0-A2` 直接验「真接口 + 401 + 自动清 token 回登录页」；刷新链路错了整站崩 | `0-A2`、`1-A1` | 0 |
| 2 | **用户列表** | `GET /admin/users` | PC A-03 的母版页，25 个列表页照它抄分页与字段口径 | — | 1 |
| 3 | **实名审核** | `GET /admin/verifications`、`POST /admin/verifications/{id}/audit` | `1-A2` 验「队列记录与提交内容一致、驳回必填理由」；一种队列两种类型（realname/enterprise） | `1-A2` | 1 |
| 4 | **律师入驻审核** | `GET /admin/lawyer-applications`、`POST /admin/lawyer-applications/{id}/audit` | `1-A3` 验「四条申请记录后台可见」；四步申请的表单字段最多 | `1-A3` | 1 |
| 5 | **手工充值** | `POST /admin/wallets/{userId}/recharge`、`GET /admin/wallets/{userId}` | `1-A4` 验「余额显示 100.00、流水新增 1 条」；`1-A6` 验「直接改字段后接口仍返流水汇总值」 | `1-A4`、`1-A6` | 1 |

> 这 5 组的字段级定义在**附录 A**。段 0 的 3 个探针接口不升 L1（只用一次、不跨端、验收只看通不通），走 L3。

## 2.3 OpenAPI 文件位置与命名

```text
api/openapi/                      ← 仓库根，不是 services/ 下
├─ README.md                      ← 怎么校验、怎么改、改完通知谁
├─ account.login.yaml             ← 组 1
├─ admin.users.yaml               ← 组 2
├─ admin.verifications.yaml       ← 组 3
├─ admin.lawyer-applications.yaml ← 组 4
└─ admin.wallet.yaml              ← 组 5
```

**为什么放仓库根 `api/`，不放 `services/faze-api/src/main/resources/`**：
1. 契约是**跨端资产**——`apps/mobile-app` 和 RuoYi 前端（若独立仓库则靠发布包）都要读它，放在后端源码树里在语义上就错了；
2. CI 里前端任务不应该依赖后端目录存在；
3. 数据表规范把「表清单」放在文档里而不是某个模块里，是同一个道理。

**文件命名**：`<前缀>.<资源>.yaml`，前缀取 `pub`/`app`/`admin`/`internal`，资源用 kebab-case。一个文件只写一组（一个 domain 的一个资源族），**不按段切、不按页面切**。

**YAML 写法硬约束**：
- 必须 `openapi: 3.1.0`
- 每个 operation 必须有 `operationId`、`summary`、`tags`、所有 `responses`
- 每个 schema 属性的 `description` 必须写**业务语义**，不准写「用户 ID」这种复述字段名的
- 每个 schema 必须给 `example`（前端 mock 直接用它）
- 金额字段 `type: integer, format: int64`，`description` 必须写「单位：分」

### 2.3.1 一条原则：**API 文档宁缺勿造**（抄 jxyd200 的 `docs/api/README.md:3`）

jxyd200 的原话：

> 「当前 API 的可执行权威来源是 Java Controller、DTO/VO 与 Playwright 回归……仓库尚无独立导出的 OpenAPI 文件，因此本目录先固定 API 文档入口，**不复制或伪造接口定义**。」

**理由**：**第二份过期事实源比没有文档更坏**——它会让人按过期的字段名写代码，而且出错时没人知道该信哪一份。

**法泽的口径**：

| 内容 | 唯一事实源 | 说明 |
|---|---|---|
| 5 组 L1 契约 | `api/openapi/*.yaml`（手写冻结） | 见 §2.2 |
| 其余 L2/L3 接口 | `/v3/api-docs`（springdoc 从代码生成） | 见 §2.5 |
| `docs/` 里的接口文档 | **只放两样**：①怎么访问 Swagger 的一句话；②L1 的 YAML 说明 | **不手抄第二份字段表** |

**禁止**：在 `docs/` 里维护一份「和技术路线 §4.8 一样的接口清单」——技术路线 §4.8 管「有哪些接口」，Swagger 管「接口长什么样」，**再抄一份必然过期**。

## 2.4 L2 契约的表格格式

L2 也要写清，只是不写 YAML。统一用这张表：

| 方法与路径 | 鉴权 | 入参 | 出参 | 错误码 | 备注 |
|---|---|---|---|---|---|

**入参/出参不许写「见 Swagger」**，至少写到字段名一级。

## 2.5 L3 的 springdoc 配置（代码优先那部分）

```java
@Configuration
public class OpenApiConfig {
    @Bean
    public GroupedOpenApi pubApi() {
        // 公开接口：只读、无鉴权，供前端 mock 与第三方对接
        return GroupedOpenApi.builder().group("pub").pathsToMatch("/api/v1/pub/**").build();
    }
    @Bean
    public GroupedOpenApi appApi() {
        return GroupedOpenApi.builder().group("app").pathsToMatch("/api/v1/app/**").build();
    }
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin").pathsToMatch("/api/v1/admin/**").build();
    }
}
```

**四条规则**：
1. **分组必须与前缀一一对应**，不许出现「一个 group 混了 app 和 admin」——否则 PC 端拿到 App 的接口文档没法用；
2. **生产环境 `/v3/api-docs/**` 与 `/swagger-ui/**` 必须关闭**（`springdoc.api-docs.enabled=false`），或加管理员鉴权。理由：admin 分组会暴露全部后台接口形状，是攻击面；
3. `@Operation(summary=...)` 的 summary 必须写**业务用途**，不写「查询列表」；`@Schema(description=...)` 必填；
4. **禁止把 Entity 直接当出参**。出参必须是 VO，否则 `password_hash`、`id_card_enc` 这类字段会随手序列化出去（这与数据表规范 §9 的 L4/L5 直接相关）。

## 2.6 契约变更流程

| 级 | 谁能改 | 改完要做什么 |
|---|---|---|
| L1 | 后端改，**前端确认** | ①改 YAML ②同一次提交改实现 ③更新附录 A ④`CHANGELOG.md` 写明「契约变更」⑤通知 App 与 PC 两侧（在提交信息里点名） |
| L2 | 后端改 | 改本文件表格 + 提交信息说明 |
| L3 | 后端自由 | 只要求提交信息里带 `feat(api)` 前缀 |

**禁止**：先改代码后补契约、一个提交里「顺便」改契约（契约变更必须是提交的主体，混在业务提交里没法审）。

## 2.7 CI 上的契约校验

| 检查 | 命令 | 失败后果 |
|---|---|---|
| L1 YAML 语法与规范 | `npx @stoplight/spectral-cli lint api/openapi/*.yaml --ruleset .spectral.yaml` | 构建失败 |
| L1 与实现一致 | 启动 faze-api → `curl /v3/api-docs/admin` → 与 YAML 用 `openapi-diff` 比对 | 字段缺失/类型不符 → 构建失败；纯新增字段 → 警告 |
| L3 快照防漂移 | `curl /v3/api-docs/{group} > api/snapshot/{group}.json`，CI 里重新生成并 `diff` | 有差异且未提交快照 → 构建失败 |

**为什么要快照**：L3 是「代码即契约」，代码改了契约就变了，但没人会主动去看。快照 diff 就是那条「你改了接口」的强制提醒。

---

# 三、URL 与 HTTP 方法

## 3.1 前缀四类（修正段 0 探针）

| 前缀 | 鉴权 | 谁调用 | 说明 |
|---|---|---|---|
| `/api/v1/pub/**` | 无 | App、前端 mock、探针 | 公开：验证码、协议文本、版本检查、法条检索 |
| `/api/v1/app/**` | App JWT | 移动端 | |
| `/api/v1/admin/**` | PC（RuoYi）token | PC 后台 | |
| `/api/v1/internal/**` | 内网白名单 | 定时任务、服务间 | 不暴露到公网 |

**裁决 1：段 0 的 3 个探针接口改路径**

| 原路径（总纲 §5.1 / 锚点） | 改为 | 理由 |
|---|---|---|
| `GET /api/v1/ping` | `GET /api/v1/pub/ping` | 裸路径不属任何前缀，与 §2.1 冲突；`0-A2` 里它就是「首页显示的数据」，必须无鉴权 → 归 `pub` |
| `POST /api/v1/ops/probe/ai` | `POST /api/v1/pub/probe/ai` | `ops` 不在四类前缀内；探针不面向任何真实用户 |
| `GET /api/v1/ops/probe/law` | `GET /api/v1/pub/probe/law` | 同上 |

**并且**：三个探针接口必须加 `@Profile("!prod")`（非生产 profile 才注册），生产环境下返回 404。理由：`/probe/ai` 会真实消耗豆包配额，且无鉴权。

> 总纲 §5.1 与锚点原文随后改写（见 §八 待办 D-1）。**改之前以本表为准。**

## 3.2 资源命名

| 规则 | 正确 | 错误 |
|---|---|---|
| 资源用**复数名词** | `/lawyers`、`/orders`、`/articles` | `/lawyer`、`/getOrder` |
| 单词用 **kebab-case** | `/lawyer-applications`、`/doc-templates` | `/lawyerApplications`、`/lawyer_applications` |
| 资源嵌套**最多 2 层** | `/conversations/{id}/messages` | `/users/{uid}/orders/{oid}/items/{iid}` |
| 路径参数用 `{id}` 花括号 | `/orders/{id}` | `/orders/:id`、`/orders/{orderId}`（同资源必须同名） |
| 查询参数用 **camelCase** | `?pageNum=1&beginTime=` | `?page_num=1` |
| 查询参数传数组用**重复键** | `?status=1&status=2` | `?status=1,2`（逗号在中文环境会被编码） |

## 3.3 动作接口（非 CRUD）

不是所有操作都能映射成资源。**只在下列三种情况下用动作接口**，其余一律资源化：

| 情况 | 写法 | 例 |
|---|---|---|
| 状态机迁移（不可用 PUT 表达） | `POST /<资源>/{id}/<动词>` | `POST /orders/{id}/pay` |
| 审核类 | `POST /<资源>/{id}/audit`，body `{action, reason}` | `POST /lawyer-applications/{id}/audit` |
| 批量操作 | `POST /<资源>/batch-<动词>` | `POST /admin/sensitive-words/batch-import` |

**动词白名单**（不许自创）：`pay` `cancel` `refund` `accept` `reject` `complete` `confirm` `audit` `publish` `offline` `assign` `reply` `close` `push` `disable` `sign` `generate` `export` `import` `run` `read` `recall`。

**裁决 2：拆开 §4.8.2 L733 的 `confirm`**

原文 `POST /app/orders/{id}/confirm` 的用途写「律师接单/拒单/完成」——一个路径三种语义，后端只能靠 body 字段再分叉。改为：

| 原 | 改为 | 谁调用 |
|---|---|---|
| `POST /app/orders/{id}/confirm`（接单） | `POST /app/orders/{id}/accept` | 律师 |
| （拒单） | `POST /app/orders/{id}/reject` | 律师 |
| （完成） | `POST /app/orders/{id}/complete` | 律师 |
| （用户确认收货/完结，若有） | `POST /app/orders/{id}/confirm` | 用户 |

**理由**：状态机迁移是服务端唯一真相（AGENTS §4 第 7 条「客户端只发意图，不发状态」）。意图必须体现在**路径**上，而不是让服务端从 `action` 字段猜。

## 3.4 HTTP 方法语义

| 方法 | 语义 | 幂等 | 本项目使用 |
|---|---|---|---|
| `GET` | 读，无副作用 | 是 | 全部查询 |
| `POST` | 创建资源 / 执行动作 | 否（需 `Idempotency-Key`） | 创建、动作、回调 |
| `PUT` | **全量替换**（前端必须传全字段）或**保存表单** | 是 | 后台保存、资料修改 |
| `PATCH` | 部分更新 | 否 | **本项目不用**，用 `PUT` + 「只传要改的字段」的语义（见下） |
| `DELETE` | 删除（含软删） | 是 | 注销、删除会话 |

**裁决 3：本项目的 `PUT` 是「部分更新」语义，不是 RFC 的全量替换**

理由：RuoYi 前端（Element Plus 表单）与 App 端的编辑页都是「只提交改动的字段」，要求全量替换会让每个表单都要先读全量再回写，且并发下容易把别人的改动覆盖掉。**但必须遵守**：`PUT` 请求体里未出现的字段视为「不修改」，`null` 视为「显式置空」。这两者的区别见 §4.4。

## 3.5 路径参数 vs 查询参数

| 用路径参数 | 用查询参数 |
|---|---|
| 资源的**主键标识**（`/orders/{id}`） | 过滤条件（`?status=`） |
| RESTful 层级关系（`/conversations/{id}/messages`） | 分页（`?pageNum=`） |
| | 排序（`?orderBy=`） |
| | 时间范围（`?beginTime=`） |

**禁止**把主键放查询参数做「伪路径」（`/orders?id=1`），也**禁止**把过滤条件塞进路径（`/orders/status/paid`）。

---

# 四、请求

## 4.1 请求头

| Header | 必选 | 值 | 说明 |
|---|---|---|---|
| `Authorization` | 除 `pub` 外必选 | `Bearer <token>` | **禁止放 URL query**（会进日志、进 Referer） |
| `Content-Type` | 有 body 时必选 | `application/json;charset=UTF-8` | 文件上传除外 |
| `X-Request-Id` | **必选** | 客户端生成 UUIDv4 | 客户端重试时**复用同一个值**，服务端据此串联；响应头 `X-Trace-Id` 回填服务端链路 id |
| `Idempotency-Key` | 写操作必选 | 见 §6.4 | |
| `Accept-Language` | App 必选 | `zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN` | 服务端据此返回该语言的**通知模板、错误 msgKey**（数据本身不带语言，语言在查询时定） |
| `X-Client-Version` | App / PC 必选 | `1.0.0` | 用于版本闸门（强更）、兼容分支 |
| `X-Client-Platform` | 必选 | `ios` / `android` / `web` | 用于统计与差异化（如推送、支付拉起） |
| `X-Device-Id` | App 必选 | 设备指纹（安装时生成，SecureStore 持久化） | 登录设备管理、异地登录告警、注销时解绑 |

**禁止的请求头**：`X-Forwarded-For` 由网关写，客户端传的一律忽略且**记入风控**（伪造 IP 是攻击特征）。

## 4.2 查询参数通用约定

| 参数 | 类型 | 说明 |
|---|---|---|
| `pageNum` | int，≥1，默认 1 | 页码分页 |
| `pageSize` | int，默认 10，**最大 100** | 超 100 按 100 处理，不报错 |
| `cursor` | string | 游标分页（IM 消息、律师列表） |
| `limit` | int，默认 20，最大 50 | 游标分页的页大小 |
| `orderBy` | string | **白名单校验**，格式 `<字段名>` 或 `<字段名>,asc\|desc`；不合法直接 401001，**禁止拼进 SQL** |
| `beginTime` / `endTime` | ISO-8601 带偏移 | 闭区间；只传一个视为单边 |
| `lang` | 枚举 | 内容类查询用；缺省按 `Accept-Language` |

**裁决 4：分页参数的三种场景**

| 场景 | 参数 | 响应结构 | 理由 |
|---|---|---|---|
| C 端列表（绝大多数） | `pageNum` + `pageSize` | `{list, total, pageNum, pageSize, hasMore}` | 需要总页数做「共 N 条」与跳页 |
| C 端**无限滚动 / 高并发写列表** | `cursor` + `limit` | `{list, nextCursor, hasMore}` | IM 消息、律师列表——新数据不断插入时 OFFSET 会重复/漏数据 |
| **PC 管理端列表** | `pageNum` + `pageSize` | **`{code, msg, rows, total}`** | RuoYi 列表页读 `res.rows` / `res.total`（`views/system/user/index.vue` L422–423 实测），不改框架层就必须这么返回 |

**这条是硬裁决**：管理端 `/api/v1/admin/**` 的列表接口返回 `rows`/`total`，不返回 `list`。否则 25 个 PC 列表页每页都要写一次 `res.list ?? res.rows` 的适配。

## 4.3 请求体

- 只接受 `application/json`；**禁止 form-urlencoded**（RuoYi 例外：其自带页仍走 RuoYi 后端，不在本规范范围）。
- 文件不走请求体，走 OSS 直传（§5.6）。
- 单个请求体上限 **2 MB**（网关层拦截）；超过按 401009 返回。

## 4.4 参数校验

| 要求 | 做法 | 反例 |
|---|---|---|
| Controller 入参必须 `@Valid` / `@Validated` | `@RequestBody @Valid LoginRequest req` | 直接收 `Map<String,Object>` |
| DTO 与 Entity 分离 | `LoginRequest` / `UserVO` | 拿 `AppUser` 当入参或出参 |
| 长度必须封顶 | `@Size(max = 200)`，富文本 `max = 20000` | 不设上限 → 一个 1 MB 的昵称 |
| 枚举必须白名单 | `@Pattern(regexp="^(text|image|file)$")` 或自定义 `@EnumValue` | 用 `String msgType` 不校验 → 脏数据入库 |
| 禁止用 `String` 收金额 | `@NotNull @Min(1) Long amountCents` | `String amount` 然后 `new BigDecimal(s)` |
| 禁止用 `String` 收时间 | `@JsonFormat` + `LocalDateTime`/`OffsetDateTime` | `String createdAt` 然后 `SimpleDateFormat` |
| 手机号 | `@Pattern(regexp="^1[3-9]\\d{9}$")` | 不校验 |
| 身份证 | 必须过**校验位**算法，不只正则 | 只 `^\d{17}[\dXx]$` |
| 路径变量 `{id}` | 必须 `@Positive`（或 `@Min(1)`） | `@PathVariable Long id` 不校验 → `id=0`/`id=-1` 查到脏数据 |
| 分页参数 | `pageSize` 必须在服务端 clamp 到 100 | 信客户端 |

## 4.5 内容类型与编码

- 一律 UTF-8。
- 响应 `Content-Type: application/json;charset=UTF-8`。
- **禁止**对 JSON 做 gzip 之外的编码转换（历史文档 §6.4 的「接口加密」已作废，见技术路线 L2243–2248 的理由：`expo-crypto` 不支持 AES-GCM，RN 侧无可用加密库）。

---

# 五、响应

## 5.1 `R<T>` 统一结构（修正技术路线 §4.5 的缺字段）

技术路线 §4.5 的 `R` 类**没有 `traceId`**，而 §4.8.3 的 `R` 有——两处不一致。《错误码规范》§五 裁决「保留 code/msg/data/traceId」。**以此为准**：

```java
/**
 * 统一响应体。
 *
 * 成功：code=200，data 为业务数据。
 * 业务失败：code 为 6 位业务错误码（见《错误码规范》），HTTP 仍为 200。
 * 鉴权失败：HTTP 401/403（见《错误码规范》§2.2），body 的 code 为 402002/402003/401008 等。
 *
 * traceId 与响应头 X-Trace-Id 同值，前端上报错误时必须带上，后端据此在日志里定位整条链路。
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {
    public static final int SUCCESS = 200;

    /** 200 或 6 位业务错误码 */
    private int code;
    /** 开发者可见的调试信息，**客户端禁止直接展示**，必须按 msgKey 走 t() */
    private String msg;
    private T data;
    /** 服务端链路 id，与响应头 X-Trace-Id 相同 */
    private String traceId;

    public static <T> R<T> ok()   { return build(null, SUCCESS, "ok"); }
    public static <T> R<T> ok(T d){ return build(d, SUCCESS, "ok"); }
    public static <T> R<T> fail(ErrorCode e) { return build(null, e.getCode(), e.getMsg()); }
    public static <T> R<T> fail(ErrorCode e, String detail) { return build(null, e.getCode(), detail); }

    private static <T> R<T> build(T data, int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        r.setTraceId(MDC.get("traceId"));   // 链路追踪自动注入
        return r;
    }
}
```

**三条约束**：
1. `data` 为 `null` 时，**响应体里仍保留 `"data": null`**（不省略字段）——前端按固定形状解析，`undefined` 与 `null` 混用会在 TS 里炸出类型错误。
2. `msg` 只给开发者看。**客户端展示的文案必须由 `msgKey` → `t()` 生成**（《错误码规范》§4.2）。
3. 分页响应**不用 `R<PageResult>`** 之外的形状；`PageResult` 的字段见 §5.2。

## 5.2 分页响应结构

**C 端（`pub`/`app`）**：

```java
@Data
public class PageResult<T> {
    private List<T> list;
    private long total;        // 总条数，-1 表示未统计（游标分页时为 -1）
    private int pageNum;
    private int pageSize;
    private boolean hasMore;   // 服务端算好，前端别自己拿 total 推
}
```

**游标分页**（IM 消息、律师列表）：

```java
@Data
public class CursorResult<T> {
    private List<T> list;
    /** 下一页的游标；hasMore=false 时为 null */
    private String nextCursor;
    private boolean hasMore;
}
```

**PC 管理端**（RuoYi `TableDataInfo` 契约）：

```java
@Data
public class TableDataInfo<T> {
    private int code;      // 200
    private String msg;    // "查询成功"
    private long total;
    private List<T> rows;  // ← 注意是 rows，不是 list
}
```

> 前端的 `getList()` 直接 `return request({url, method:'get', params})`，页面里 `res.rows` / `res.total`。这条不改。

## 5.3 字段命名与类型

| 类型 | 规则 | 例 |
|---|---|---|
| JSON 字段名 | **camelCase** | `lawyerApplyStatus`、`balanceCents` |
| **主键 / 所有 Long 型 ID** | **序列化为字符串** | `"id": "1234567890123456789"` |
| 时间 | ISO-8601 带偏移，字段名以 `At` 结尾 | `"createdAt": "2026-10-30T10:00:00+08:00"` |
| 金额 | **整数分**，字段名以 `Cents` 结尾 | `"amountCents": 10000`（=100.00 元） |
| 布尔 | `is*` / `has*` / `can*` | `"isMinor": false`、`"hasMore": true` |
| 枚举 | **传枚举名字符串**（大写下划线），传码值需在契约里写明码表 | `"msgType": "IMAGE"` |
| 集合 | 空集合返回 `[]`，**不返回 `null`** | `"list": []` |

**裁决 5：所有 Long 型 ID 必须序列化为字符串**

依据：MySQL 主键是 `BIGINT`（数据表规范），Java `Long` 最大值远超 JavaScript `Number.MAX_SAFE_INTEGER`（2^53−1）。一旦服务端直接返回 `1234567890123456789`，JS 端 `JSON.parse` 会静默丢精度——**不出错，但 ID 变了**，表现为「查不到刚创建的记录」这种极难排查的 bug。

做法（三选一，全项目统一用一种）：`@JsonSerialize(using = ToStringSerializer.class)` 打在字段或全局 `ObjectMapper` 上；或 DTO 字段直接声明为 `String`。**推荐全局配置**，漏一个字段就是一个坑。

> 注意：RuoYi 的 `AjaxResult` 里 `Long` 也是这种处理（`ToStringSerializer`），所以 PC 端本来就能接受字符串 ID。

**裁决 6：枚举传名不传码**

理由：4 语言的文案要按枚举名查（`status.LAWYER_APPROVED`），传码（`3`）客户端要先维护一张码表，码表与后端 `enum` 一旦不同步就显示错。**但**：状态机迁移的入参可以传动作名（`accept`/`reject`），不传目标状态——这是 AGENTS §4 第 7 条的要求。

## 5.4 空值与缺省语义

| 值 | 含义 | 何时用 |
|---|---|---|
| 字段**缺省**（key 不存在） | 不修改 / 不适用 | **仅出现在请求体** |
| `null` | 显式置空 | 请求体里表示「把该字段清空」；响应体里表示「无此数据」 |
| `""` | 空字符串，是**有效值** | 用户主动清空昵称（但与 `null` 的语义必须二选一，见下） |
| `[]` | 空集合 | 响应里替代 `null` |
| `0` | 有效数值 | 金额 0 分、计数 0 |

**裁决 7：响应体里不省略字段**

即使 `data` 为 `null` 也保留 key。理由：TS 的类型推导依赖固定形状；`undefined` 会让 `exactOptionalPropertyTypes` 报错，也会让前端 `if (x.field !== undefined)` 的判断逻辑分叉。**代价**：响应体略微变大（可忽略）。

**例外**：`PUT` 请求体里**必须**用「缺省 = 不修改」表达，不能用 `null`——两者语义不同，`null` 是「清空」。

## 5.5 时间与时区

| 项 | 规则 |
|---|---|
| 传输格式 | ISO-8601 带偏移：`2026-10-30T10:00:00+08:00`；**不允许** `2026-10-30 10:00:00`（无时区、无 T） |
| 数据库存储 | `DATETIME` 存 **UTC**（数据表规范 §6.3） |
| 服务端时区 | JVM `-Duser.timezone=UTC`，MySQL `time_zone='+00:00'` |
| 接口输出 | 统一转成 **`+08:00`** 输出（目标用户在新疆，用乌鲁木齐时间 `Asia/Shanghai` 与 `Asia/Urumqi` 在中国境内实际一致） |
| 字段命名 | `*At`：`createdAt` / `updatedAt` / `expiredAt` / `paidAt` / `deletedAt` |
| 日期（无时间） | `yyyy-MM-dd`，字段名 `*Date`（如 `serviceDate`） |
| 客户端 | **禁止**自行格式化后当数据传回；时区转换只在展示层做 |

**为什么数据库存 UTC、接口出 +08:00**：存 UTC 是为了跨时区部署不出错（虽然当前单机房，但改起来是无底洞）；出 +08:00 是因为目标用户全在伊犁，出 UTC 会让前端每次都要转。**两者之间的转换只在 `Jackson` 的 `ObjectMapper` 里做一次**，业务代码里禁止手写时区。

## 5.6 金额

| 项 | 规则 |
|---|---|
| 传输 | **整数分**（`BIGINT`），字段名 `*Cents` |
| 禁止 | 浮点数、`BigDecimal` 直接序列化（会变成 `100.00` 字符串或 `100`，两种都容易误解） |
| 前端展示 | 只在展示层 `/100`，且必须用「分转元」工具函数，禁止每处手写 |
| 舍入 | 服务端一律 `BigDecimal` 计算，`RoundingMode.HALF_UP`，保留 0 位小数后转 `long` |
| 校验 | `@Min(0)` + 上限校验（单笔上限存 `sys_config`，不在代码里写死） |

> 与数据表规范一致：`balance_flow` 是唯一真相，`user_balance.balance_cents` 是缓存。**接口返回的余额必须来自 `SUM(balance_flow)` 或已校验的缓存**，锚点 `1-A6` 验的就是这一点。

## 5.7 敏感字段输出（衔接数据表规范 §9）

数据表规范 §9 把敏感字段分 L1–L5 五级。**接口层的呈现规则**：

| 级别 | 字段例 | 响应里怎么出现 | 额外要求 |
|---|---|---|---|
| L1 可明文 | 昵称、头像 URL | 原样 | — |
| L2 脱敏输出 | 手机号 | `138****8888` | **本人看自己的手机号也脱敏**（防截屏泄露给他人看）；需要完整号码的场景（换绑验证页）走单独接口 + 二次验证 |
| L3 脱敏输出 | 身份证号 | `110***********1234` | 同上；且**不得出现在列表接口**，只能在详情且本人/管理员可见 |
| L4 私有资源 | 身份证影像、律师执业证、聊天附件 | **不返回 URL，返回 `ossKey`**；客户端另调 `/app/upload/url?key=` 换短时签名 URL | 签名 URL 有效期 **≤ 5 分钟**，且不写日志 |
| L5 仅审计可查 | 密码哈希、支付密码哈希、聊天原文 | **任何常规接口都不得返回** | 管理端审核只能看「命中片段」，见 §4.8.4.12 |

**裁决 8：脱敏在序列化层做，不在业务代码里做**

做法：VO 字段加 `@Desensitize(type = PHONE)` 注解 + 自定义 `JsonSerializer`。理由：业务代码里散着 `phone.substring(0,3)+"****"` 一定会漏；放进序列化层后，**只要 VO 上加注解就不会漏**，且能加静态检查（见 §7 检查清单第 9 条）。

**禁止**：把 `id_card_enc`、`password_hash` 这类字段放进 VO。VO 里根本没有这些字段，才不会「忘了脱敏」。

## 5.8 响应头

| Header | 说明 |
|---|---|
| `X-Trace-Id` | 与 body 的 `traceId` 同值 |
| `Deprecation` | 该接口已废弃：`true`（或 RFC 8594 的日期形式） |
| `Sunset` | 计划下线时间，ISO-8601 |
| `Retry-After` | 限流时给出重试秒数（仅网关级 429 用） |

---

# 六、横切能力

## 6.1 鉴权：C 端（`pub`/`app`）

| 项 | 规则 |
|---|---|
| 载体 | `Authorization: Bearer <JWT>` |
| 令牌时长 | access **2 小时**，refresh **30 天** |
| 刷新 | `POST /app/auth/refresh`，**单飞**（同一时刻只允许一个刷新请求在飞，其余等待结果） |
| 失效 | 登出/改密/注销 → 旧 token 进 Redis 黑名单，TTL = 剩余有效期 |
| 401 处理 | `402002`（过期，可刷）→ 触发刷新；`402003`（无效）→ 直接登出。**两者必须区分**，否则无限刷新循环（《错误码规范》§4.2 第 3 条） |
| 禁止 | token 放 URL query、放 Cookie（App 端无 Cookie 语义）、写进日志 |

### 6.1.1 客户端实现的两条细节（抄 jxyd200 的实战踩坑）

**① 401 只清「被拒的那个 token」**

并发场景：用户点了刷新/重新登录，新 token 已存好；此时**旧请求的 401 迟到**。如果收到 401 就无条件清 token，会把**刚登录的新会话清掉**，用户表现为「刚登录就被踢」。

```ts
/** 只有当被拒的 token 就是当前存的 token 时才清；否则说明已经换过了，忽略这个迟到 401。 */
export async function expireStoredSession(rejectedToken: string): Promise<boolean> {
  const current = await getStoredToken();
  if (!current || current !== rejectedToken) return false;   // ← 关键这一行
  await clearToken();
  return true;
}
```

**② 401 判定必须「双通道」**

不能只看 HTTP 状态码，要同时看 body 的 `code`——因为**管理端按 §6.2 裁决 9 返回的是 `HTTP 200 + code=401`**。只看 HTTP 状态的话，PC 端永远触发不了登出。

| 通道 | 判什么 |
|---|---|
| C 端 | `HTTP 401` **且** body `code ∈ {402002, 402003}` |
| PC 端 | `HTTP 200` **且** body `code === 401`（RuoYi 契约，见 §6.2 裁决 9） |

**并且只对「显式带了 `Authorization` 头」的请求做登出判定**——登录失败、验证码错误这类「还没拿到 token」的请求返回 401 时不能清会话，否则用户连登录页都进不去。

## 6.2 鉴权：PC 端（`admin`）

**前提**：见 §1.3 的方案 A（待拍板）。

| 项 | 规则 |
|---|---|
| 载体 | `Authorization: Bearer <RuoYi 签发的 token>`；RuoYi 前端从 Cookie `Admin-Token` 取出后自动塞进头 |
| 校验 | faze-api 解 JWT → 取 `login_user_key` → 查 Redis `login_tokens:<uuid>` → 校验存在与过期 |
| **401 特例** | 见下方裁决 9 |
| 权限 | 只有一个管理员角色（AGENTS 决策）；`@PreAuthorize("hasRole('admin')")` 只校验角色，**不做数据级权限细分** |
| 越权 | 即便只有一个角色，也要校验「操作对象存在」与「状态允许」（如不能审核已审核过的申请） |

**裁决 9：管理端鉴权失败返回 HTTP 200 + `body.code = 401`（唯一例外）**

**依据（实测，RuoYi-Vue3 3.9.2）**：`src/utils/request.js`：
- 第 78 行 `const code = res.data.code || 200`
- 第 85 行 `if (code === 401) { ...弹重新登录... }` —— 判断的是 **body 里的 code**
- 第 111–123 行是 HTTP 非 2xx 的错误分支，**只弹「系统接口 XXX 异常」，没有任何跳登录逻辑**

所以：若 faze-api 按《错误码规范》§2.2 返回 **HTTP 401 + body `{code:402002}`**，RuoYi 前端会走 error 分支，用户看到「系统接口401异常」而**不会被引导重新登录**，页面卡死。

**裁决**：
| 前缀 | 鉴权失败响应 | 理由 |
|---|---|---|
| `/api/v1/app/**`、`/api/v1/pub/**` | **HTTP 401** + `{code:402002/402003}` | App 端拦截器按 HTTP 状态分流，符合《错误码规范》§2.2 |
| `/api/v1/admin/**` | **HTTP 200** + `{code:401, msg:"登录状态已过期"}` | 只认 RuoYi 前端契约；改框架层被 AGENTS §3.3 禁止 |

**代价（必须写清）**：管理端的鉴权失败在网关/APM 里显示为 200，监控上要单独按 `code=401` 统计，不能按 HTTP 状态统计。**这是全项目唯一的 HTTP 语义例外**，写在这里是为了不再有人去「统一」它。

## 6.3 越权防护（IDOR）

**规则**：任何「按 id 取/改资源」的接口，SQL 条件里必须同时带 `user_id`（或 `lawyer_id`）归属条件，**不能只 `WHERE id = ?` 然后靠 Controller 里 `if` 判断**。

```java
// ❌ 反例：先查再判断，中间有 TOCTOU 窗口，且漏判就 0 提示
Order o = orderMapper.selectById(id);
if (!o.getUserId().equals(currentUserId)) throw new BusinessException(TRADE_NOT_ORDER_OWNER);

// ✅ 正例：归属条件进 SQL，查不到就是「不存在」
Order o = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
        .eq(Order::getId, id)
        .eq(Order::getUserId, currentUserId));
if (o == null) throw new BusinessException(TRADE_ORDER_NOT_FOUND);
```

**必须做归属校验的接口**（清单，段 1 起逐个对）：

| 类别 | 接口 | 归属字段 |
|---|---|---|
| 账户 | `GET/PUT /app/account/profile`、`DELETE /app/account` | `user_id` |
| 实名/企业 | `GET /app/account/realname`、`POST /app/account/enterprise` | `user_id` |
| 设备 | `GET /app/account/devices` | `user_id` |
| 入驻 | `GET /app/lawyer-applications/current` | `applicant_user_id` |
| 会话 | 全部 `conversation` 接口 | `(user_id, lawyer_id)` 成员校验 |
| 订单 | `GET /app/orders/{id}` 及全部动作接口 | `user_id` / `lawyer_id` |
| 文书 | `document` 全部 | `user_id` |
| 案件 | `caseflow` 全部 | `client_id` / `lawyer_id` |
| 资金 | `GET /app/wallet/*`、提现 | `user_id` |

**管理端例外**：管理员可见全部数据，但**必须写 `operation_log`**（谁、何时、从哪 IP、做了什么、对哪条记录）——这就是数据表规范 §1.3「审计四要素」的用法。

## 6.4 幂等

| 项 | 规则 |
|---|---|
| 哪些接口必须幂等 | 下单、支付、充值、提现、退款、审核、发放、回调。**判据：会产生资金流水或不可逆状态迁移的** |
| 键 | `Idempotency-Key: <业务类型>:<业务单号>`，长度 ≤ 64，**客户端生成**（重试必须复用同一个键） |
| 存储 | Redis，key = `idempotent:<namespace>:<key>`，TTL **24 小时**（跨天重试不可能，24h 足够） |
| 重复请求 | **返回首次结果**（相同 body），HTTP 200。**不返回错误** |
| 并发同键 | 后来的请求返回 `401005 idempotent-conflict` 或等待首次完成（**推荐等待，超时 3 秒后返回冲突**） |
| 缺键 | 必须幂等的接口缺 `Idempotency-Key` → `401002 param-missing` |
| 服务端校验 | 幂等键的 namespace 必须与「业务单号」的业务类型一致，防跨业务串用 |

**裁决 10：重复请求返回首次结果，不报错**

理由：客户端在弱网下重试是常态，报错会让用户以为失败而重复操作。代价是服务端要在 Redis 里存「首次响应体」，对大响应不友好——只对**幂等接口**存（这些接口的响应都很小）。

## 6.5 限流

| 维度 | 阈值 | 超限响应 |
|---|---|---|
| 发送短信 | 1 分钟 1 次 / 1 天 10 次（**按手机号，不按 IP**） | HTTP 200 + `401007 rate-limited`，`data.retryAfterSeconds` |
| 登录 | 1 分钟 10 次（**按 IP + 账号双维度**） | 同上 |
| AI 对话 | 每用户每天 50 轮 | HTTP 200 + `408002 ai-quota-exceeded` |
| 通用 | 单 IP 每秒 20 次 | **网关级 HTTP 429** + `Retry-After` |
| 文件上传 | 每用户每分钟 10 次 | 200 + 401007 |

**裁决 11：业务限流返回 200 + 错误码，网关限流返回 429**

理由：业务限流（短信/AI 配额）要告诉用户「再等多久」「今天用完了」，走统一响应体才能带 `retryAfterSeconds`；网关限流是基础设施层，429 是标准语义。**两者不能混**，否则客户端要处理两条路径。

**注意**：技术路线 L2278 与 L2236 都写了短信限流，两处阈值不一致（`1/5/10` vs `1 分钟 1 次 / 1 天 10 次`）。**以本表为准**，L2278 与 L2236 待回写（§八 D-2）。

## 6.6 文件上传：三段式

```text
① POST /app/upload/token        → { ossKey, uploadUrl, formFields, expireAt, maxSizeBytes }
② 客户端直传 OSS（不经过 faze-api）   → 上传成功后拿到 ossKey
③ 业务接口提交 ossKey            → 如 POST /app/account/realname { "idCardFrontKey": "..." }
```

| 项 | 规则 |
|---|---|
| 后缀白名单 | `jpg` `jpeg` `png` `webp` `pdf`；**可执行、脚本、压缩包一律拒绝** |
| 大小 | 图片 ≤ 10 MB，PDF ≤ 20 MB；`token` 接口按用途返回 `maxSizeBytes` |
| 魔数校验 | 服务端必须读文件头校验真实类型，**不能只信 `Content-Type` 与后缀** |
| 重命名 | 服务端生成 `ossKey`，格式 `{用途}/{yyyy}/{MM}/{uuid}.{ext}`；**禁止用原名**（原名可能含个人信息、可能含路径穿越字符 `../`） |
| 路径 | 身份证影像、执业证、聊天附件 → **私有 bucket**；公开内容（头像、文章配图）→ 公开 bucket |
| 绑定 | `ossKey` 必须与「用途」绑定，业务接口提交时要校验这个 key 确实是本用户、本用途上传的（防把别人的 key 挂到自己记录上） |
| 签名 URL | `GET /app/upload/url?key=` → 有效期 ≤ 5 分钟；**禁止把签名 URL 落库**（过期即失效，落库没意义且泄露） |

## 6.7 长连接契约边界

### WebSocket（`/ws/chat`）

消息格式已在技术路线 §7.2 定义，**本规范只管握手与鉴权**：

| 项 | 规则 |
|---|---|
| 握手鉴权 | **不能**用 HTTP Header（浏览器 WS API 不支持自定义头）。做法：连接 URL 带一次性 `ticket`（`GET /app/ws-ticket` 取得，60 秒有效、用完即废），服务端在握手时校验 ticket 换出 userId |
| 禁止 | 直接把 access token 拼在 WS URL 上（会进代理日志、Referer） |
| 心跳 | 客户端 30 秒 `ping`，服务端 `pong`；60 秒无消息服务端断开 |
| 重连 | 指数退避 1s→2s→4s→8s→16s→30s 封顶；重连后按 `lastReadSeq` 拉增量 |
| 错误码 | **改用 6 位码**（技术路线 L2392 的 `40301` 作废，→ `404002 conversation.forbidden`） |

### SSE（AI 流式）

| 项 | 规则 |
|---|---|
| 路径 | `GET /api/v1/app/ai/stream?sessionId=&fromSeq=` |
| 鉴权 | **RN 用 `fetch` 读流，不用 `EventSource`**——`EventSource` 不能带自定义头，token 只能进 URL。RN 的 `fetch` 支持 `response.body.getReader()`，可以带 `Authorization` 头。**这是本项目的硬裁决**，不接受 `EventSource` |
| 断线续传 | 客户端记录 `fromSeq`，重连带 `fromSeq` 从断点续；服务端按 `seq` 重放 |
| 首字节 | 服务端必须在 5 秒内出首字节（锚点 `0-A5`），否则返回 `508001 ai.service-down` |
| 结束 | 服务端发 `event: done` 后关闭；客户端收到 `done` 才把消息标记为完整 |
| 超时 | 单次流最长 120 秒，超时服务端主动 `done` + 原因 |

> 技术路线 §4.8.2 L754 写的是 `/app/ai/stream`（相对 `/api/v1/`），§8 写的是 `/api/v1/ai/stream`（缺 `app`）。**以本表为准**：`/api/v1/app/ai/stream`。

## 6.8 版本与废弃

| 项 | 规则 |
|---|---|
| 版本位 | URL 前缀 `/api/v1/`；**破坏性变更走 `/api/v2/`**，不兼容的语义变更不许原地改 |
| 向后兼容的新增 | 加可选字段、加可选查询参数、加新错误码 → **不算破坏性**，留在 v1 |
| 破坏性变更 | 改字段名/类型、改必填性、改语义、删字段/接口 → 必须走 v2 或先走弃用流程 |
| 弃用流程 | ①响应头 `Deprecation: true` + `Sunset: <date>` ②文档标 `⚠️ 已废弃` ③`CHANGELOG.md` 登记 ④**至少保留一个段的周期**（即下一个段收口前不删）⑤小程序/App 版本闸门保证旧版本用户不会调到 |
| 强制升级 | 通过 `GET /api/v1/pub/app/version` 返回 `minSupportedVersion`，低于则 App 弹强更 |

---

# 七、每段要交付的接口

## 7.1 段 0（6 个）

| # | 方法与路径 | 用途 | 鉴权 | 响应 | 锚点 |
|---|---|---|---|---|---|
| 1 | `GET /api/v1/pub/ping` | 首页数据来源，验「真接口」 | 无 | `{appName, version, serverTime, env}` | `0-A2` |
| 2 | `POST /api/v1/pub/auth/login/password` | 手机号 + 密码登录，签发 access + refresh | 无 | 见附录 A.1.1 | `0-A2` |
| 3 | `POST /api/v1/app/auth/refresh` | 用 refreshToken 换新 accessToken | refresh token | 见附录 A.1.2 | `0-A2` |
| 4 | `GET /api/v1/app/account/profile` | **唯一目的是提供一个「需鉴权资源」，让 `0-A2` 能验 401** | access token | 见 §7.2 资料组 | `0-A2` |
| 5 | `POST /api/v1/pub/probe/ai` | 触发豆包 SSE 探针，验首字节 | 无（`@Profile("!prod")`） | SSE 流 | `0-A5` |
| 6 | `GET /api/v1/pub/probe/law?kw=` | 用 ngram 检索 `law_item`，验命中 | 无（`@Profile("!prod")`） | `{hits: [{id, lawCode, articleNo, snippet, score}]}` | `0-A5` |

**2026-10-31 修正（本节原写「3 个」，是漏项，不是契约变更）**：原文只列了 3 个 `pub` 探针接口，并写「除上述 3 个 + RuoYi 自带登录外不实现任何业务接口」。这与本文件 §2.2 第 1 组「登录组 **落段 = 0**、覆盖锚点 `0-A2`」直接矛盾；也不满足 `0-A2` 的判据——它要求「token 失效后请求**任一需鉴权接口**，自动清 token 回登录页」，而原 3 个接口**全部是 `pub`（无鉴权）**，物理上无法验出 401。`POST /pub/auth/login/sms` **段 0 不做**（短信通道未定，段 1 才允许固定码）。结论：**以 §2.2 为准补回登录组，并补一个受保护资源作为 401 的验证载体**。

**段 0 明确不做的接口**：除上述 6 个外，**不实现任何业务接口**（`sms/code`、注册、忘记密码、实名、入驻、余额流水等全部属段 1）。段 0 的出口是「能装、能连、能出字」，不是「有接口」。

## 7.2 段 1（C 端 20 个 + PC 端 20 个）

**C 端（`app`/`pub`）**：

| 组 | 接口 | 来源 |
|---|---|---|
| 注册登录（5） | `POST /pub/sms/code`、`POST /pub/auth/register`、`POST /pub/auth/login/password`、`POST /pub/auth/login/sms`、`POST /pub/auth/reset-password` | §4.8.2 account |
| 令牌（1） | `POST /app/auth/refresh` | 同上 |
| 资料（2） | `GET /app/account/profile`、`PUT /app/account/profile` | 同上 |
| 实名/企业（3） | `POST /app/account/realname`、`GET /app/account/realname`、`POST /app/account/enterprise` | 同上 |
| 注销与设备（3） | `POST /app/account/logout`、`DELETE /app/account`、`GET /app/account/devices` | 同上 |
| 入驻（2） | `POST /app/lawyer-applications`、`GET /app/lawyer-applications/current` | §4.8.2 lawyer |
| 余额与流水（2） | `GET /app/wallet/balance`、`GET /app/wallet/flows` | §4.8.2 trade |
| 上传（2） | `POST /app/upload/token`、`GET /app/upload/url?key=` | §4.8.2 infra |
| 支付密码（3） | `POST /app/account/pay-password`、`PUT /app/account/pay-password`、`POST /app/account/pay-password/reset` | **§4.8.2 缺，本规范补** |

> **支付密码 3 个接口在技术路线里没有**，但总纲 §5.1 的「必须做」写了「支付密码（设置/校验/锁定/重置）」，锚点 `1-A5` 直接验它。**这是清单漏项**，本规范补上（见 §八 D-3）。

**PC 端（`admin`）**：A-03~A-09 共 20 个，见 §4.8.4.4 与 §4.8.4.5，其中 4 组升 L1（见 §2.2）。

## 7.3 段 2~8

接口数量与归属见技术路线 §4.8.2 / §4.8.4，**不由本规范重复列**。本规范只规定「长什么样」，不管「有哪些」。

---

# 八、待决事项（不拍板不许假装已定）

| # | 事项 | 影响 | 不拍板的处理 |
|---|---|---|---|
| **P-1** | ~~PC 端 token 互认方案~~（§1.3 的 A/B/C） | 段 1 的 PC 7 页联调 | **✅ 已拍板 2026-10-30：方案 A（共享 `token.secret` + 共享 Redis）**。实施要点与「序列化待实测」见 §1.3.1 |
| P-2 | 管理端鉴权失败返回 `code=401`（裁决 9）是否接受 | 若不接受，必须新建独立 axios 实例（方案 B 的一部分） | 按裁决 9 执行 |
| P-3 | 幂等重复请求「返回首次结果」（裁决 10） | 影响所有资金接口 | 按裁决 10 执行 |
| P-4 | 限流阈值（短信 1/分钟、AI 50 轮/天）是否合适 | 影响运营策略，不是技术问题 | 先用本表值，写进 `sys_config` 可调 |
| P-5 | 是否上 `PATCH` | 当前裁决「不用」 | 按裁决 3 执行 |

---

# 九、联调检查清单（每个接口合并前逐条过）

1. 路径符合四类前缀？资源复数 kebab-case？**没有**裸路径？
2. 是动作接口的话，动词在 §3.3 白名单里？
3. 幂等判据命中的话，`Idempotency-Key` 逻辑有吗？
4. 出参是 VO 不是 Entity？`password_hash` / `id_card_enc` / `phone` 会不会漏出去？
5. 所有 `Long` 型 ID 序列化成字符串了吗？
6. 金额字段是 `*Cents` 整数？没有浮点？
7. 时间字段是 `*At` + ISO-8601 带偏移？
8. 空集合返回 `[]` 不是 `null`？响应不省略字段？
9. 按 id 取/改资源的，归属条件进了 SQL 吗？（不是只靠 Controller 里 if）
10. 分页用的是不是本场景对应的那一种（C 端 list / IM cursor / PC rows）？
11. 错误码用的是《错误码规范》里已有的？没有临时新码？
12. 鉴权路径正确？`admin` 前缀的 401 返回的是 `body.code=401`？

---

# 附录 A：5 组 L1 契约的字段级定义

> 下面每组先给**冻结的核心字段**；完整 YAML 在 `api/openapi/` 下。**新增可选字段不算变更**（但要同步 YAML）；改字段名/类型/必填性 = 破坏性变更。

## A.1 组 1：登录（段 0）

### A.1.1 `POST /api/v1/pub/auth/login/password`

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `phone` | string(11) | ✅ | 中国大陆手机号，`^1[3-9]\d{9}$` |
| `password` | string(6–64) | ✅ | 明文传输（TLS 内），服务端 BCrypt 校验 |
| `deviceId` | string(≤64) | ✅ | 与请求头 `X-Device-Id` 必须一致（不一致记风控） |
| `smsCode` | string(6) | ❌ | 仅异地登录风控命中时要求 |

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `accessToken` | string | JWT，2 小时 |
| `refreshToken` | string | 30 天 |
| `expiresIn` | int | accessToken 剩余秒数 |
| `tokenType` | string | 固定 `Bearer` |
| `user` | object | 见下 |

`user` 对象：`id`(string) / `nickname` / `avatarUrl` / `phoneMasked`（`138****8888`）/ `realnameStatus`(枚举 `NONE\|PENDING\|APPROVED\|REJECTED`) / `isMinor`(bool) / `roles`(string[]) / `payPasswordSet`(bool)。

**错误码**：`402001` 登录失败（不区分账号还是密码错，防枚举）/ `402004` 已封禁 / `401007` 限流。

### A.1.2 `POST /api/v1/app/auth/refresh`

**请求**：`{ "refreshToken": "..." }`
**响应**：同 A.1.1 的 `accessToken`/`refreshToken`/`expiresIn`
**错误码**：`402002` 过期可重登 / `402003` 无效。

**单飞要求**：同一 `refreshToken` 并发刷新时，只允许一个真正执行，其余等待并复用结果。**否则会因轮换导致旧 refresh 失效、其余请求全失败。**

### A.1.3 `POST /api/v1/pub/sms/code`

**请求**：`{ "phone": "...", "scene": "REGISTER\|LOGIN\|RESET_PASSWORD\|REBIND" }`
**响应**：`{ "expiresIn": 300, "retryAfterSeconds": 60 }`
**错误码**：`401007` 限流（1 分钟 1 次 / 1 天 10 次）。

## A.2 组 2：用户列表（段 1，PC A-03）

### `GET /api/v1/admin/users`

**查询参数**：`pageNum` / `pageSize` / `nickname` / `phone`（精确或后 4 位）/ `realnameStatus` / `accountStatus` / `beginTime` / `endTime` / `orderBy`

**响应（RuoYi `TableDataInfo`）**

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | 200 |
| `msg` | string | |
| `total` | long | |
| `rows` | array | 每项见下 |

`rows[]` 字段（**冻结**）：`id`(string) / `nickname` / `phoneMasked` / `avatarUrl` / `realnameStatus`(枚举) / `accountStatus`(枚举 `NORMAL\|BANNED\|CANCELLED`) / `totalConsumeCents`(long) / `orderCount`(int) / `createdAt` / `lastLoginAt`。

**注意**：`phoneMasked` 是脱敏后的，**列表页不提供完整手机号**（要查完整号走详情 + 审计留痕）。

## A.3 组 3：实名审核（段 1，PC A-04）

### `GET /api/v1/admin/verifications`

**查询参数**：`type`（`realname\|enterprise`）/ `status`（`PENDING\|APPROVED\|REJECTED`）/ `pageNum` / `pageSize` / `beginTime` / `endTime`

**响应 rows[]**：`id`(string) / `type`(枚举) / `userId`(string) / `userNickname` / `realnameMasked` / `idCardMasked` / `submittedAt` / `status` / `reviewedBy` / `reviewedAt` / `rejectReason`。

### `POST /api/v1/admin/verifications/{id}/audit`

**请求**：`{ "action": "approve\|reject", "reason": "驳回时必填，≤200" }`
**响应**：`{ "id", "status" }`
**错误码**：`401006` 状态非法（已审过）/ `401001` 参数校验失败（reject 无 reason）。
**留痕**：必须写 `operation_log`（管理员 / 时间 / IP / 动作 / 目标 id / 结论）。

## A.4 组 4：律师入驻审核（段 1，PC A-05）

### `GET /api/v1/admin/lawyer-applications`

**查询参数**：`status` / `applyType` / `beginTime` / `endTime` / `pageNum` / `pageSize`

**响应 rows[]**：`id`(string) / `applicantUserId`(string) / `applicantName` / `phoneMasked` / `licenseNoMasked` / `lawFirm` / `practiceYears`(int) / `submittedAt` / `status`（`PENDING\|APPROVED\|REJECTED\|CANCELLED`）/ `reviewedAt` / `rejectReason`。

> 「四条申请记录（资质 / 执业证 / 领域 / 服务设置）在后台可见」对应锚点 `1-A3`：详情接口必须返回这四块，四块的具体字段见附录 A.4 的详情 schema（`GET /admin/lawyer-applications/{id}`，L2 级）。

### `POST /api/v1/admin/lawyer-applications/{id}/audit`

**请求/响应/错误码/留痕**：同 A.3.2。

## A.5 组 5：手工充值（段 1，PC A-07）

### `GET /api/v1/admin/wallets/{userId}`

**响应 `data`**：`userId`(string) / `balanceCents`(long，**来自流水汇总**) / `frozenCents`(long) / `totalRechargeCents` / `totalConsumeCents` / `lastFlowAt`。

### `POST /api/v1/admin/wallets/{userId}/recharge`

**请求**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `amountCents` | long | ✅ | >0，上限由 `sys_config` 定 |
| `reason` | string(≤200) | ✅ | **必填**，写清为什么充 |
| `bizNo` | string(≤64) | ✅ | 线下转账的凭证号/流水号，用于对账 |
| `remark` | string(≤500) | ❌ | |

**响应 `data`**：`flowId`(string) / `balanceCents`(long，充值后) / `bizNo` / `createdAt`。

**错误码**：`405006` 金额非法 / `401005` 重复（`bizNo` 已存在）/ `401002` 缺原因。

**硬约束**：
1. **必须写 `balance_flow`**，禁止直接 `UPDATE user_balance.balance_cents`（总纲 §5.2 与 `1-A6`）；
2. 必须写 `operation_log`；
3. `bizNo` 唯一索引，重复充值被数据库拦住（不是只靠应用层查）。

---

# 附录 B：现有接口路径需修正清单

| 现路径（技术路线） | 问题 | 改为 |
|---|---|---|
| `GET /api/v1/ping` | 裸路径，不属四类前缀 | `GET /api/v1/pub/ping` |
| `POST /api/v1/ops/probe/ai` | `ops` 前缀不存在 | `POST /api/v1/pub/probe/ai` |
| `GET /api/v1/ops/probe/law` | 同上 | `GET /api/v1/pub/probe/law` |
| `POST /app/orders/{id}/confirm`（律师接单/拒单/完成） | 一个路径三种语义 | 拆 `accept` / `reject` / `complete` |
| `GET /app/lawyers`（称「游标分页」） | 与 §4.4 页码分页冲突 | 明确用 `cursor` + `limit` |
| `GET /ai/stream`（§8，缺 `app`） | 与技术路线 §4.8.2 的 `/app/ai/stream` 不一致 | `/api/v1/app/ai/stream` |
| `POST /app/account/realname`（技术路线无支付密码接口） | 段 1 需要但不存于任何清单 | 补 3 个支付密码接口（§7.2） |
| WS `chat.error` 的 `code: 40301` | 5 位，与 6 位冻结方案冲突 | `404002`（会话域·无权访问） |

# 附录 C：RuoYi 契约兼容矩阵

| 契约点 | RuoYi 期望 | faze-api 管理端返回 | 是否一致 |
|---|---|---|---|
| 成功判定 | `res.data.code === 200` | `{code: 200}` | ✅ |
| 列表分页 | `res.rows` + `res.total` | `{rows, total}` | ✅（裁决 4） |
| 鉴权过期 | `res.data.code === 401` → 跳登录 | `HTTP 200 + {code:401}` | ✅（裁决 9） |
| 无权限 | `code !== 200` → `ElNotification.error(msg)` | 6 位码 `401008` | ✅（能弹，但只显示 msg） |
| 业务失败 | `code !== 200` → 通知 | 6 位码 | ✅ |
| 表单提交 | `Content-Type: application/json` | 同 | ✅ |
| 文件上传 | RuoYi 自带 `/common/upload`（form-data） | faze-api 走 OSS 直传 | ⚠️ **不一致**：PC 的文件上传若走 faze-api，需另写封装 |
| 时间格式 | Element Plus 日期控件给 `yyyy-MM-dd HH:mm:ss` | 要求 ISO-8601 带 T | ⚠️ **不一致**：PC 侧提交前要转换，或后端同时接受两种 |

> 最后两行是**已知不一致**，写在这里是为了让做 PC 的人提前知道，不要等联调才发现。

---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线」（2026-10-30 新增）。
> 基线与文件指纹见 `docs/BASELINE.md`；本文件新增时须按 `docs/BASELINE.md` §4 的「四件事」办理。
