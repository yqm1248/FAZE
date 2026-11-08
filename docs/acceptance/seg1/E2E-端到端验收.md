# 第 1 段 · 端到端验收（`deploy/verify-e2e.js`）

> 为什么单独做一份：G5 只验了「18 页能渲染」，而 1-A1~1-A4、1-A10 的判据全是**写操作全链**。
> 页面截图证明不了「提交之后状态真的变了」。本文记录那份证据。

- 验收时间：2026-11-05 04:0x（Asia/Shanghai）
- 方式：真实 HTTP 打 `faze-api:8081` + 真实 MySQL(`faze`) + 真 PC 管理端接口（RuoYi `/login` 拿 token）
- **不经过 UI**：这是对「页面渲染正常但接口没接通」这类问题的专门防线
- 脚本：`deploy/verify-e2e.js`（可重复跑，每次用时间戳生成全新手机号，避免撞唯一键）
- 报告：`docs/acceptance/seg1/e2e-report.json`
- 结果：**38/38 通过**

## 一、按锚点分组的用例

### 1-A1 账号全链路（10 项）

| 用例 | 判据 |
|---|---|
| 1A1-1 注册成功 | 发码 200 + 注册 200 + 拿到 token 与 userId |
| 1A1-2 登录态可取资料 | `phoneMasked` 形如 `138****0665`（脱敏格式） |
| 1A1-3 有余额时注销被拒 | `DELETE /app/account` → 401006 |
| 1A1-4 忘记密码重置 | 发码 + `POST /pub/auth/reset-password` → 200 |
| 1A1-5 旧密码已失效 | 用旧密码登录 → 402001 |
| 1A1-6 新密码可登录 | → 200 + 新 token |
| 1A1-7 管理端调账清空余额 | `POST /admin/wallets/{userId}/adjust` delta=-10000 → 200 |
| 1A1-8 余额清零后注销成功 | `DELETE /app/account` → 200 |
| 1A1-9 注销后旧 token 失效 | 用旧 token 调 profile → 402003 |
| 1A1-10 同号可立即重新注册 | 注销后用同一手机号注册 → 200 |

> 注：1A1-3 在发充值的步骤之后执行，所以「有余额」是真实的 10000 分，不是构造的。

### 1-A2 实名审核（6 项）

| 用例 | 判据 |
|---|---|
| 1A2-1 三段式上传 | `POST /app/upload/token` → PUT 到 uploadUrl（两次都 204） |
| 1A2-2 实名提交 | → 200 |
| 1A2-3 提交后状态 PENDING | `GET /app/account/realname` |
| 1A2-4 PC 队列出现且脱敏 | 队列行 `idCardMasked` 含 `*`，如 `650***********1234` |
| 1A2-5 PC 审核通过 | `POST /admin/verifications/{id}/audit` action=approve → 200 |
| 1A2-6 App 状态变 APPROVED | 且 `reviewedAt` 非空 |

### 1-A3 律师入驻（5 项）

四步入驻提交 → PENDING → PC 队列（行字段是 `applicantUserId`，不是 `userId`）→ 审核通过 →
APPROVED，最后断言 `profile.lawyerStatus` 跟着变成 `APPROVED`。

### 1-A4 手工充值（3 项）

PC 充值 100 元（`POST /admin/wallets/{userId}/recharge`，带 `Idempotency-Key`）→
App `wallet/balance` = 10000 分 → `wallet/flows` 1 条且 `deltaCents=10000`。

> **充值端点的 userId 在路径里**（`/admin/wallets/{userId}/recharge`），不是 body。
> 第一次写成 `/admin/wallet/recharge` 直接 401001，是这次跑的返工点。

### 1-A5 支付密码（5 项）

设置 → `profile.payPasswordSet` 变 true → 连错 4 次看 `X-Pay-Pwd-Remaining` 依次为 4/3/2/1 →
第 5 次 401007 → 响应头 `Retry-After=1800` → **锁定期内输入正确密码仍被拒 401007**。

### 1-A10 未成年人（2 项）

未成年不填监护人 → 402009；填了监护人 → 注册成功。

### 附带验证：三个被修过的 profile 字段

`FIX-1/1b`（payPasswordSet 未设→设后）、`FIX-2/2b`（lawyerStatus NONE→APPROVED）、
`FIX-3/3b`（balanceCents 0→10000）在三个时点各断言一次，确认不是「恒值恰好对」。

## 二、这次端到端逼出来的一个真缺陷

**注册时验证码被过早消费。** 原实现第一步就 `verifyAndConsume`，于是「未成年漏填监护人」被
正确拒绝之后，验证码已经作废，用户改好重试拿到 402005，还要再等 60 秒（同号发码间隔）。

修法：把消费移到「协议 → 未成年联动 → 密码强度 → 手机号唯一」四道校验之后、落库之前。
防重放语义不变（仍只消费一次）。`loginBySms` 的同一位置**故意不改**——那里先查账号会让人
靠 402001/402005 的差异枚举手机号是否注册，登录路径优先挡探测。

复验：`deploy/verify-a10` 的三段断言 + 端到端 1A10 两项全绿。

## 三、这份证据与 G5 截图的关系

| 层 | 证据 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| 页面层（G5） | 20 路由 + 新账号 13 页截图、错误态 7 页 | 页面能渲染、四态齐全、RTL 近似不串位 | 点按钮之后真的生效 |
| 接口层（本文） | 38 项端到端 | 写操作全链生效、状态真的流转 | 页面按钮绑的是不是这个接口 |

两层都过，才算「页面接的是同一个后端」。缺任一层都有假通过空间——这正是工作流 §四里
给每条锚点写「**可能假通过**」一栏的原因。

## 四、没覆盖到的

| 项 | 说明 |
|---|---|
| 换绑手机 `POST /app/account/change-phone` | 需要给「旧号」和「新号」各发一条验证码，同号发码有 60 秒间隔，端到端一次要多等两轮；本段只做了接口契约核对与页面渲染，**这一步没进自动化**，挂到段 8 前的回归清单 |
| 真 RTL | 浏览器口径下无解（RN Web 的 `I18nManager` 是空实现），挂段 8 |
| 支付密码「锁定到期自动解锁」 | 要等 30 分钟。TTC 由 `PayPasswordLockTest` 用 Redis TTL 单测覆盖，端到端不复跑 |
