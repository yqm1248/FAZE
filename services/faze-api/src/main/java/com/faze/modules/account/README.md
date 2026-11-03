# modules/account —— 账户与鉴权域

> 规范依据：《后端代码规范》§2.6——每个业务模块必须有 README，固定四段（边界 / 数据来源 / 不可改的契约 / 测试入口）。
> 段 0 只建这一个业务域；其余 11 个域（lawyer / trade / conversation …）从段 1 起建。

## 一、边界

**管什么**

- 手机号 + 密码登录（`AuthService.login`）
- refresh token 轮换与单飞（`AuthService.refresh`）
- 当前登录用户资料读取（`AuthService.profile`）

**不管什么**（写下来才会在加功能时发现边界被吃）

| 不在这里 | 在哪 |
|---|---|
| 注册、忘记密码、短信验证码 | 段 1（短信通道未定） |
| 实名认证、企业认证 | 段 1 |
| 注销、设备管理 | 段 1 |
| 支付密码（设置 / 校验 / 重置） | 段 1 |
| 律师身份扩展（执业证、入驻） | 段 2 的 `modules/lawyer` |
| 管理员账号 | RuoYi 的 `sys_user`——**既不在 `app_user` 表，也不在这个域** |
| 风控与异地登录 | 段 1（段 0 只记日志，见 `AuthService.warnIfDeviceMismatch`） |

## 二、数据来源

| 方向 | 对象 | 说明 |
|---|---|---|
| 读 | `app_user` | 按手机号取未软删用户；按主键取资料 |
| 写 | `app_user.last_login_at` / `last_login_ip` | 单条 update，只两个字段，不覆盖整行 |
| 进程内 | `security/RefreshTokenStore` | refresh token 白名单，**段 0 进程内**，重启即失效；段 1 迁 Redis |
| 跨域读 | 无 | 段 0 只有这一个域 |

## 三、不可改的契约

以下字段被前端写死，改任何一个都算**契约变更**，要走《接口规范》§2.6 的流程（`/api/v2/` 或弃用期）。

| 接口 | 冻结内容 | 依据 |
|---|---|---|
| `POST /api/v1/pub/auth/login/password` | 请求 `phone` / `password` / `deviceId`；响应 `accessToken` / `refreshToken` / `expiresIn` / `tokenType` / `user` | 《接口规范》附录 A.1.1 |
| `POST /api/v1/app/auth/refresh` | 请求 `refreshToken`；响应**只有** `accessToken` / `refreshToken` / `expiresIn` | 附录 A.1.2 |
| `GET /api/v1/app/account/profile` | `user` 对象 8 个字段，不多不少 | 附录 A.1.1 |

`user` 对象字段：`id`(string) / `nickname` / `avatarUrl` / `phoneMasked` / `realnameStatus`(大写枚举 NONE|PENDING|APPROVED|REJECTED) / `isMinor`(bool) / `roles`(string[]) / `payPasswordSet`(bool)。

两条容易改错的点：

1. **`id` 是字符串**（`Long` 包装类经 `JacksonConfig` 转字符串），不是数字——改成数字会让 JS 丢尾数精度。
2. **`refresh` 响应不带 `user`**——客户端此时手里已有旧 user，多回一份就是两份真相。

## 四、测试入口

| 测试 | 命令 | 覆盖什么 |
|---|---|---|
| `Seg0SmokeTest` | `mvn -B test`（需 `FAZE_DB_URL` 环境变量） | 6 个接口各一个 200 / 错误码用例 |
| 手动联调 | `deploy/start-api.ps1` 后按 `services/faze-api/README.md` §6 的 curl 清单 | 登录 → 拿 token → 401 → 刷新 |

无 `FAZE_DB_URL` 时测试整类跳过（`@EnabledIfEnvironmentVariable`）——**这不是假绿**：`mvn verify` 的职责是编译/单测/打包，连库测试需要真实 MySQL。本机的正式验收必须带环境变量真跑一次，证据里注明 `Skipped: 0`。
