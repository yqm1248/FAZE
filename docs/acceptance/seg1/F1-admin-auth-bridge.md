# F1 管理端鉴权桥（方案 A）验收证据

> 段：第 1 段 · F 组 · 任务 F1
> 日期：2026-11-05
> 结论：**通过**。RuoYi 登录一次，faze-api 的 `/api/v1/admin/**` 直接认这个 token；C 端 token 与它互不串。
> 本文件是**验收证据**，不在《文档基线》登记范围内（基线只登记 `architecture/` 与 `design/` 下的文档）。

## 一、F1 的 4 条二值验收（《第1段工作流》§F1）

| # | 验收项 | 判定 | 证据 |
|---|---|---|---|
| 1 | `npm run dev`（本项目改用 `pnpm dev`）打开登录页 | ✅ | 见 §四；前端 `http://localhost:80` 起来，VITE v6.4.1 |
| 2 | 登录成功，进主框架（侧边栏 + 顶栏 + 标签页） | ✅ | 见 §四截图；侧边栏出现「法泽业务」 |
| 3 | 能看到一个 `biz` 菜单页（占位） | ✅ | `/biz/dashboard` 页显示服务端时间 + 当前管理员 + 7 页清单 |
| 4 | `docs/analysis/ruoyi-patches.md` 已创建 | ✅ | 已有 4 条记录（数据源分库 / pnpm / vite 代理 / 跨服务耦合点） |

## 二、为什么 F1 的真正工作量在「鉴权桥」而不是「前端底座」

《接口规范》§1.3 指出：PC 登录走 RuoYi（8080），业务接口在 faze-api（8081），**两个独立应用、两套 JWT**，
全仓 0 处定义两者如何互认。§1.3 已拍板**方案 A**（共享 `token.secret` + 共享 Redis）。
这一段没有被写进 F1 的步骤里，但**不做它，A-03~A-09 二十个接口一个都调不通**。

实现（`services/faze-api/.../security/` + `infra/adminauth/`）：

| 环节 | 实现 | 关键点 |
|---|---|---|
| 取 token | `Authorization: Bearer <RuoYi token>` | 前端 `src/api/biz/request.js` 从 Cookie `Admin-Token` 取，与 RuoYi 自己的 `request.js` 第 32 行同源 |
| 验签 | `AdminTokenVerifier`：HS512 + `SecretKeySpec` 手写 HMAC | **不能**用 jjwt 的 `Keys.hmacShaKeyFor`（强类型密钥要求 ≥256 bit，RuoYi 的密钥只有 18 字节） |
| 取登录态 | `AdminSessionStore`：独立 Lettuce 工厂连 **Redis db0** 的 `login_tokens:<uuid>` | 工厂**不注册成 Bean**，否则 Boot 的 Redis 自动配置会让 faze-api 自己在 db1 的验证码/限流/支付密码计数全部失效 |
| 还原身份 | 从 fastjson2 文本抽 `userId` / `username` → `AdminLoginUser`，授 `ROLE_ADMIN` | 只取必要字段，降低对 RuoYi 版本的耦合 |
| 失败出口 | `RestAuthenticationEntryPoint`：admin 前缀回 **HTTP 200 + `{code:401}`** | 接口规范 §6.2 裁决 9；RuoYi 前端只认 `body.code === 401` |

## 三、两处与文档不符的实测（**以本节为准**）

### 3.1 Redis 里的 `LoginUser` 不是标准 JSON

《接口规范》§1.3.1 第 5 条写「用任意 JSON 库都能读」。**实测不成立。**

```text
{"@type":"com.ruoyi.common.core.domain.model.LoginUser","browser":"Chrome 156","deptId":103L,
 "expireTime":1793813144523,"ipaddr":"127.0.0.1","loginLocation":"内网IP","loginTime":1793811344523,
 "os":"Windows10","permissions":Set["*:*:*"],"token":"53a0209d-...","user":{"admin":true,
 "userId":1L,"userName":"admin"},"userId":1L,"username":"admin"}
```

- `103L` / `1L`：Long 带 `L` 后缀，**不是合法 JSON 数字**；
- `Set["*:*:*"]`：fastjson2 的集合字面量，**不是 JSON 数组**。

Jackson 直接解析会抛 `JsonParseException`。处理：按 `"userId":<数字>L?,"username":"<名字>"` 这一对**相邻锚点**定点抽取
（`user` 子对象里是 `"userId":1L,"userName":"admin"`，大写 N，不会误命中）；抽不到 → **fail closed**（未登录），
并打 error 日志带样本前缀。回归测试：`AdminSessionStoreParseTest`（4 个用例，含真实快照）。

### 3.2 密钥派生不是标准 Base64（**这是最容易误判「方案 A 不可行」的地方**）

RuoYi 用 jjwt **0.9.1**，`signWith(SignatureAlgorithm.HS512, String)` 走
`TextCodec.BASE64` → `javax.xml.bind.DatatypeConverter.parseBase64Binary`，
它**只解完整的 4 字符组，尾部余数直接丢弃**。

| 派生方式 | `abcdefghijklmnopqrstuvwxyz`（26 字符）解出的字节 |
|---|---|
| `DatatypeConverter.parseBase64Binary`（RuoYi 实际用的） | **18 字节** `69b71d79f8218a39259a7a29aabb2dbafc31` |
| 常规 Base64 解码（Node `Buffer.from(s,'base64')`、Java `Base64.getDecoder()` 补 padding） | **19 字节** `69b71d79f8218a39259a7a29aabb2dbafc31cb` |

用后者算出的签名**永远对不上**，且报错只是「验签失败」，不会提示密钥派生错。

**验证过程（不是推断，是实测）**：
1. 从已登录的浏览器用 CDP 取到真实 token；
2. 用 `E:\dshcode\tmp\ProbeJjwt.java` + jjwt 0.9.1 + jaxb-api 直接调
   `Jwts.parser().setSigningKey("abcdefghijklmnopqrstuvwxyz").parseClaimsJws(token)` → **`JJWT_0.9.1_OK`**；
3. 同一程序打印 `DatatypeConverter` 解出的字节 = 上面的 18 字节；
4. 反推：Node 的 19 字节版本 HMAC-SHA512 与真实签名不匹配，18 字节版本匹配。

实现：`AdminTokenVerifier.deriveKey()` 显式按「剔除非法字符 → 只取完整 4 字符组 → 标准 Base64 解码」派生，
并在启动日志打一行 `管理端 token 校验密钥已派生：18 字节`。回归测试：`AdminTokenVerifierTest`（7 个用例，含真实 token 快照、篡改载荷、换密钥、算法降级）。

## 四、端到端实测（2026-11-05 01:15~01:20）

### 4.1 接口层（`curl.exe`，非 PowerShell）

| 场景 | 请求 | HTTP | 响应体 |
|---|---|---|---|
| 管理端无 token | `GET /api/v1/admin/dashboard/ping` | **200** | `{"code":401,"msg":"登录状态已过期","data":null,"traceId":...}` |
| 管理端带 RuoYi token | 同上 + `Authorization: Bearer <token>` | **200** | `{"code":200,"data":{"adminId":"1","adminName":"admin","serverTime":"2026-11-05T01:15:37+08:00"},"msg":"ok"}` |
| 管理端带伪造 token | 同上（载荷改了、签名没重算） | **200** | `{"code":401,...}` |
| C 端无 token（对照，不能被改坏） | `GET /api/v1/app/account/profile` | **401** | `{"code":402003,"msg":"登录凭证无效，请重新登录",...}` |

> 注：`Invoke-WebRequest` 发这个 `Authorization` 头会失败（实测返回 401，`curl.exe` 同 token 返回 200）。这是 PowerShell 的行为，不是接口问题；排障脚本一律用 `curl.exe`。

### 4.2 前端层

`http://localhost/biz/dashboard` 页面文本（`cdp-shot2.js` 抓取）：

```text
若依管理系统 / 首页 / 系统管理 / 法泽业务 / 业务总览 / 用户列表 / 实名认证审核 / 律师入驻审核 /
律师列表 / 余额与充值 / 系统参数 / 对账报表 / ...
法泽业务后台
faze-api  8081（/biz-api 前缀经 vite 代理）
服务端时间  2026-11-05T01:17:48+08:00
当前管理员  admin
管理员 ID   1
```

侧边栏 1 个目录 + 8 个页面全部出现（`sys_menu` 2100~2108），说明
**RuoYi 登录态 → faze-api 验签 → Redis 登录态 → 管理端业务接口** 四环全通。
截图：`docs/acceptance/seg1/_artifacts/f1-biz-dashboard.png`。

## 五、过程中修掉的三个环境缺陷（都不是 F1 的任务，但不修就走不下去）

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `deploy/start-api.ps1` **从不设置 profile** | `application.yml` 的 `faze.jwt.secret` 是 `${FAZE_TOKEN_SECRET:}`（无默认值），dev 默认密钥只在 `application-dev.yml`；按脚本启动**必然**在 `JwtTokenProvider` 构造期抛「secret 未配置」。也就是说这个脚本从来没真正起过 dev 实例 | 加 `-Profile`（默认 `dev`）并显式设 `SPRING_PROFILES_ACTIVE`；prod 下强制要求 `FAZE_TOKEN_SECRET` / `FAZE_ADMIN_TOKEN_SECRET` |
| 2 | 缺「重启 API」的标准动作 | 手工找 PID 容易停错进程（机器上还有 RuoYi 的 java、Maven 本体） | 新增 `deploy/restart-api.ps1`：只匹配命令行含 `com.faze.FazeApiApplication` 的 java 进程 → 脱离式重启 → 阻塞等 `/api/v1/pub/ping` 200（退出码 0/1/2） |
| 3 | `sys_menu.is_frame` 取值搞反 | 一级目录写 `is_frame=0`（=是外链）时，RuoYi 的 `getRouterPath` 不给 `path` 补前导 `/`，前端 `vue-router` 抛 `Route paths should start with a "/": "biz" should be "/biz"` → **整页白屏** | 种子 `database/seed/seg1_admin_menu.sql` 全部改成 `is_frame=1`，并在文件里写明这个坑 |

## 六、遗留与挂账

1. **管理端角色只做「有登录态即管理员」**：任何持有有效 RuoYi 登录态的账号都能调 `/api/v1/admin/**`。
   依据是后台只有一个管理员角色（接口规范 §6.2 / AGENTS 决策），RuoYi 也只允许 `sys_user` 登录。
   **若将来出现非管理员的后台账号，这里必须改成校验角色**（`LoginUser` 缓存里 `roles[].roleKey`）。
2. **方案 A 的耦合点**已登记到 `docs/analysis/ruoyi-patches.md` 第 4 条，回归靠两个测试类；
   RuoYi 升级改动 `LoginUser` 结构或 jjwt 版本时这两处会先红。
3. **`docs/analysis/` 的相关度**：本文件 §3 的两条与《接口规范》§1.3.1 第 5 条表述不一致，**尚未回写**到规范正文
   （第 1 段 H 组「§九 回写」时统一处理）。
