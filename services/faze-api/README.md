# faze-api —— 法泽服务端（段 0）

段 0 的服务端骨架。**它不是产品功能**，是「可安装底座 + 三项探针」里的服务端一半：证明「能连、能查、能出字节」，并为 `0-A2` 提供**真实**登录接口与一个需要鉴权的资源。

> 段 0 的出口是「能装、能连、能出字」，不是「有接口」。除本文列出的 6 个接口外，段 0 **不实现任何业务接口**。

---

## 1. 版本（实测锁定，2026-11-02）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | **17** | `java.version` 由 pom 的 parent 决定 |
| Spring Boot | **4.0.6** | 文档原写 4.0.10，central 上 4.0.x 最新是 4.0.9，**4.0.10 不存在**；取本地有真 pom 的最新的 4.0.6 |
| Spring Framework / Security | 7.0.7 / 7.0.5 | 由 parent 管理，不手写版本 |
| Tomcat | 11.0.21 | 内嵌 |
| MyBatis-Plus | **3.5.18.1** | 必须 `mybatis-plus-spring-boot4-starter`（`-spring-boot3-` 在 SB4 下静默失效）+ `mybatis-plus-jsqlparser` |
| Flyway | 11.x（parent 管理） | 需要 `spring-boot-flyway` + `flyway-core` + `flyway-mysql` 三件 |
| jjwt | 0.12.3 | api / impl / jackson 三件套 |
| MySQL | 8.0.44 | 驱动 `mysql-connector-j:8.0.33` |
| Maven | 3.9.16 | `E:\google\apache-maven-3.9.16-bin\...\mvn.cmd`；**不提供 Maven Wrapper** |

**JSON 层是 Jackson 3（`tools.jackson`），不是 Jackson 2。** 代码里写 JSON 用 `tools.jackson.databind.*`；不要写 `spring.jackson.serialization.*` 配置（Jackson 3 没有那些枚举，写了直接启动失败）。注解仍是 `com.fasterxml.jackson.annotation.*`（Jackson 3 继续复用 2.x 的 annotations 包）。

---

## 2. 前置

1. **JDK 17**（`java -version` 显示 17.x）。
2. **Maven 3.9.16** 在 PATH 里。
3. **MySQL 8.0.44 已启动**，且已用 root 执行过一次 `database/00-create-database.sql`（建 `faze` 库 + `faze_app` 账号）。
4. **`deploy/.env` 存在**（从 `deploy/.env.example` 复制并填口令）。口令**不进仓库**，也不进命令行。
5. 首次构建需要联网（Flyway 11 与 `spring-boot-webmvc-test` 本地无缓存）；`~/.m2/settings.xml` 已配 `127.0.0.1:7890` 代理。

---

## 3. 构建

```powershell
cd services\faze-api
mvn -B verify        # 0-A7 的三道门禁之一：编译 + 单测 + 打包
```

- 无 `FAZE_DB_URL` 环境变量时，冒烟测试**整类跳过**（类上有 `@EnabledIfEnvironmentVariable`）。这不是「假绿」：`mvn verify` 的职责是「编译/单测/打包能过」，而连库测试需要真实 MySQL，不能强制任何机器都有。
- 本机的正式验收**必须带环境变量真跑一次**，见 §6。

## 4. 启动

```powershell
# 前台启动（Ctrl+C 停止），自动读 deploy\.env 注入环境变量
.\deploy\start-api.ps1

# 打包后启动（验证 jar 也能跑）
.\deploy\start-api.ps1 -AsPackage
```

启动成功的判据（三条都要）：

1. 控制台出现 `Flyway` 的 `Successfully applied` 或 `Schema "faze" is up to date`；
2. 出现 `Tomcat started on port 8081`；
3. `curl http://127.0.0.1:8081/api/v1/pub/ping` 返回 `{"code":200,...}`。

## 5. 端口

**8081。** 8080 是 RuoYi 的后端（`pc/RuoYi-Vue`，其 `vite.config.js` 代理目标写死 `http://localhost:8080`），两者必须错开，否则 `0-A6` 的「PC 后台能登录」会打到错误的进程上。

## 6. 段 0 的 6 个接口

| # | 方法与路径 | 鉴权 | 用途 | 触发锚点 |
|---|---|---|---|---|
| 1 | `GET /api/v1/pub/ping` | 无 | 真接口（首页数据来源） | `0-A2` |
| 2 | `POST /api/v1/pub/auth/login/password` | 无 | 手机号 + 密码登录 | `0-A2` |
| 3 | `POST /api/v1/app/auth/refresh` | refreshToken（请求体） | 换新令牌（单飞 + 轮换） | `0-A2` |
| 4 | `GET /api/v1/app/account/profile` | access token | **受保护资源**，`0-A2` 验 401 的唯一载体 | `0-A2` |
| 5 | `POST /api/v1/pub/probe/ai` | 无（`@Profile("!prod")`） | 豆包 SSE 探针 | `0-A5` |
| 6 | `GET /api/v1/pub/probe/law?kw=` | 无（`@Profile("!prod")`） | ngram 法条检索探针 | `0-A5` |

**探针的 SSE 事件协议**（本段补定，见《第0段工作流》§七）：

```text
event: meta   data: {"mock": true|false}
event: delta  data: {"text": "..."}
event: done   data: {"totalChars": N}
event: error  data: {"code": 508001, "msgKey": "error.ai.service-down"}
```

**`probe/law` 的 kw 限制**：1~50 字符，空/超长回 `401001`。**单字返回 0 命中是预期结果**（`ngram_token_size=2`，1 字切不出 token）。

### 实测命令

```powershell
# 1. 验证 traceId：body.traceId 必须等于响应头 X-Trace-Id
curl.exe -s -i http://127.0.0.1:8081/api/v1/pub/ping

# 2. 登录（测试账号见 database/seed/test.sql：13800000001 / 123456）
$body = '{"phone":"13800000001","password":"123456","deviceId":"seg0-probe"}'
$login = curl.exe -s -X POST http://127.0.0.1:8081/api/v1/pub/auth/login/password `
  -H "Content-Type: application/json" -H "X-Device-Id: seg0-probe" -d $body | ConvertFrom-Json
$token = $login.data.accessToken

# 3. 带 token 取资料 → 200
curl.exe -s http://127.0.0.1:8081/api/v1/app/account/profile -H "Authorization: Bearer $token"

# 4. 不带 token → HTTP 401 + code 402003
curl.exe -s -i http://127.0.0.1:8081/api/v1/app/account/profile

# 5. ngram 探针（合同 → ≥1 条；婚 → 0 条是预期）
curl.exe -s "http://127.0.0.1:8081/api/v1/pub/probe/law?kw=%E5%90%88%E5%90%8C"

# 6. SSE 探针（首帧 meta 里 mock=true 表示未调真接口）
curl.exe -s -N -X POST http://127.0.0.1:8081/api/v1/pub/probe/ai

# 7. 生产 profile 下探针必须 404
$env:SPRING_PROFILES_ACTIVE='prod'; $env:FAZE_TOKEN_SECRET='<32 字节以上>'
```

### 本机真跑冒烟测试（会连真实 MySQL）

```powershell
# 先让环境变量生效（PowerShell 不会自动读 .env）
Get-Content ..\..\deploy\.env | Where-Object { $_ -match '^\s*[^#].+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2; Set-Item -Path "Env:$($k.Trim())" -Value $v.Trim()
}
mvn -B verify
# 期望：Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

---

## 7. 段 0 的已知限制（不是 bug，是边界）

| # | 限制 | 原因 | 何时消除 |
|---|---|---|---|
| 1 | **进程重启后所有人都要重新登录** | refresh token 存进程内 `ConcurrentHashMap`（段 0 不引 Redis，加 Redis 就是「配了没人读的死开关」） | 段 1（第一个用到分布式状态的用例） |
| 1b | **同一 refresh token 在 5 秒窗口内可换取同一对新令牌** | 单飞复用窗口（覆盖并发抖动与一次网络重试）；窗口过长会成为旧 token 的重放窗口 | 段 1 接 Redis 后做「重放检测 + 撤销 token family」（OAuth 2.0 BCP） |
| 2 | **登录不防时序侧信道**：账号不存在时立刻返回，账号存在时跑一次 BCrypt | 段 0 无风控模块 | 段 1 接风控时补「恒定时间比对」 |
| 3 | **设备标识不一致只记日志，不拦截** | `风控策略表` 段 1 才建；没有规则时拦截会误伤换机用户 | 段 1 |
| 4 | **`payPasswordSet` 恒为 false** | 支付密码表属段 1 | 段 1 |
| 5 | **探针在生产 profile 下不存在（404）** | `probe/ai` 无鉴权且烧配额 | 永久（这是设计，不是限制） |
| 6 | **`probe/ai` 无 key 时走 mock** | 豆包 key 由项目负责人提供，尚未拿到 | D3 组拿到 key 后跑真实链路 |
| 7 | **`probe/law` 只有 5 条法条** | 全量 2000~3000 条是段 3 的事，`V2` 只放 5 条验链路 | 段 3 |

## 8. 卡住怎么办

| 现象 | 原因 | 处理 |
|---|---|---|
| `mvn verify` 下载超时 | 首次要下 Flyway 11 / webmvc-test | 确认 `repo.maven.apache.org:443` 可达；配阿里云镜像；**不许**用 `mvn -o` 伪造通过 |
| `Unsupported Database: MySQL` | 缺 `flyway-mysql` | 补依赖（只加 `flyway-core` 必报这个） |
| `Unknown database 'faze'` | 建库 SQL 没执行 | 用 root 跑 `database/00-create-database.sql` |
| 应用起了但表是 0 张 | 缺 `spring-boot-flyway` | SB4 把 Flyway 自动配置拆成独立模块，只加 `flyway-core` 不会执行迁移 |
| `Mapper 注入失败` | 用了 `mybatis-plus-spring-boot3-starter` | 换 `mybatis-plus-spring-boot4-starter:3.5.18.1`；**不许降 Java 或退回 SB3.5** |
| `Unsupported character encoding 'utf8mb4'` | JDBC URL 写了 `characterEncoding=utf8mb4` | 改成 `characterEncoding=UTF-8` |
| 探针在 dev 下也 404 | `@Profile("!prod")` 的类没被扫到 | `FazeApiApplication` 的扫描根必须是 `com.faze` |
| `faze.jwt.secret 未配置或不足 32 字节` | 生产 profile 无默认密钥 | 设 `FAZE_TOKEN_SECRET`（≥32 字节） |

## 9. 目录约定

> **唯一权威是《后端代码规范 v1.0》§2.1 / §3.4 / §3.5。** 本节只是速查，与规范冲突时以规范为准。

```text
src/main/java/com/faze/
├─ FazeApiApplication.java
├─ common/                   跨域共用，不含业务
│  ├─ R.java                 统一响应
│  ├─ exception/             ErrorCode / BusinessException / GlobalExceptionHandler
│  ├─ audit/                 AuditContext（段 0 只进 MDC，不写库）
│  └─ util/                  RequestUtils（客户端 IP / 设备 id）
├─ filter/                   TraceIdFilter（链路 id）
├─ config/                   一个领域一个类
│  └─ properties/            FazeJwtProperties / FazeProbeProperties（构造绑定，§12.3）
├─ security/                 JwtTokenProvider / RefreshTokenStore / LoginUser
│                            JwtAuthenticationFilter / RestAuthenticationEntryPoint / RestAccessDeniedHandler
├─ modules/account/          段 0 唯一业务域
│  ├─ controller/ service/ mapper/ entity/
│  ├─ bo/                    入参（LoginBO / RefreshTokenBO）
│  ├─ vo/                    出参（UserVO / LoginVO / TokenVO）
│  └─ README.md              四段：边界 / 数据来源 / 不可改的契约 / 测试入口（§2.6 强制）
└─ infra/                    law（ngram 检索）/ probe（三个探针）
```

**两条段 0 权宜（已挂账）**：`infra/law/` 与 `infra/*/dto/` 不在规范的 infra 子目录清单里，
段 0 只有 account 一个业务域、法条检索无处可放；段 3 法条库落地时移入业务域一并收敛。

---

> 本文件属「法泽 FAZE 文档基线」，落款见 `docs/BASELINE.md`。
