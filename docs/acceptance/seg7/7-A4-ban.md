# `7-A4` 黑名单 · 验收证据

> **验收人**：Lead（独立复核）
> **时间**：2026-11-09 02:08:17（Asia/Shanghai）
> **方式**：真实 faze-api(8081) 封禁/解封 + 真实短信登录链路 + 旧 token 复放
> **脚本**：`tmp/seg7h/anchor-run.mjs A4`（原始输出 `tmp/seg7h/anchor-A4.log` / `.json`）
> **被验对象**：`POST /admin/users/{id}/ban|unban` + `AuthController` 登录拦截 + `token_version` 失效机制

## 1. 结论：**通过**

拦得住、提示与「密码错误」**可区分**、提示里**有客服电话**、旧 token **立即失效**、解封后能正常登录。

## 2. 逐步实测

| 步 | 动作 | 实测响应 |
|---|---|---|
| 1 | 封禁前用验收账号登录（`13900008888`） | `HTTP 200 code=200` |
| 2 | `POST /admin/users/9324/ban` `{reason:"7-A4 锚点验收（临时封禁，验完解封）"}` | `HTTP 200 code=200` |
| 3 | 封禁后再登录 | **`HTTP 403` `code=402004`** `msg=账号已被封禁，如需申诉请联系客服 002-00854967 或通过 APP 客服中心提交` |
| 4 | 用**封禁前签发的旧 token** 调 `GET /app/account/profile` | **`HTTP 401` `code=402003`**（登录凭证无效） |
| 5 | `POST /admin/users/9324/unban` `{reason:...}` | `HTTP 200 code=200` |
| 6 | 解封后登录 | `HTTP 200 code=200` |

## 3. 逐条对照锚点要求

| 锚点原文要求 | 实测 | 判定 |
|---|---|---|
| 封禁后登录**失败** | HTTP 403 | ✅ |
| 错误码**不能是「密码错误」** | 封禁=`402004`(403)；密码错误=`402001`(**200**)。两者 HTTP 状态与码都不同 | ✅ |
| 提示**含申诉入口或客服电话** | msg 内含 `002-00854967`（`servicePhoneProvider` 从 `sys_config.service.phone` 读，**未配置时降级不提号码**，不写死） | ✅ |
| ≤60 秒内已登录 token 再调接口被拒 | **立即**被拒（`token_version+1`，不等 Redis 缓存过期）——比锚点要求更彻底 | ✅ 优于要求 |
| 解封后能正常登录 | HTTP 200 | ✅ |

## 4. 修复记录（本锚点验出的缺陷）

**修复前**：封禁提示只有「账号已被封禁」，**不含任何联系方式**。按锚点「提示里只有『账号异常』没有联系方式 → 不算」这条，当时是不合格的。

**修复**（be-api 本批）：`ServicePhoneProvider` 从 `sys_config.service.phone` 取值拼进 `402004` 的 msg，未配置时不拼接。实测 msg 已含 `002-00854967`。

## 5. 说明（不构成缺陷）

- `ip` 列在本机 dev 是 `127.0.0.1`，锚点原文允许（「本机开发可接受，但要说明；生产必须是真实 IP」）。生产需确认反向代理 `X-Forwarded-For`（§八.2 第 11 项）。
- 锚点里「≤ 60 秒」是假设 Redis 缓存 60 秒过期；本项目用 `token_version` 版本号，**立即**失效，不依赖缓存过期。

## 6. 复现命令

```powershell
cd E:\dshcode\FAZE
node tmp/seg7h/anchor-run.mjs A4
```

> 脚本会**自己解封**再打印结果；如果中途失败把账号留在 `blocked`，用 `node tmp/seg7h/restore-user.mjs 9324` 恢复。
> 短信验证码：脚本直接写 Redis `faze:sms:code:{phone}`（验收造数，绕开 60 秒同号间隔限流），不是改业务代码。
