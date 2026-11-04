# G5 · APP 18 页浏览器验收记录

> 口径：按项目负责人 2026-11-05 的决定，**真机全部改为浏览器验收**。本文所有结论都来自
> 真实 Chrome（CDP 驱动，viewport 390×844、deviceScaleFactor 2、mobile）对 expo web dev server
> （`http://127.0.0.1:8090`）的实际渲染，不是看代码推断，也不是截图工具的二手结论。

- 验收时间：2026-11-05 03:0x ~ 04:0x（Asia/Shanghai）
- 环境：Windows 11 / Chrome 156.0.8078.80（`--remote-debugging-port=9222`）/ Node 24.16.0
- 后端：faze-api 8081（dev profile）、RuoYi 8080、MySQL 8.0（库 `faze`）、Redis
- 截图目录：`G5-states/`（老账号 20 路由）、`G5-states/new-user/`（新账号 13 页）、
  `G5-states/bottom/`（滚到底）、`G5-states/rtl-approx/`（RTL 近似）

## 一、为什么不用截图工具而用 CDP

`look_design` / `check_design` 在 RN Web 上有两个硬问题，实测确认：

1. **首屏截成白屏**：expo dev bundle 5.2 MB，工具在 React 挂载完成前就截图，4 张图 sha256
   完全一致（纯白）。同一路由用 CDP 等 6 秒后截图是正常的。
2. **量不到内容高度**：RN Web 的滚动发生在内部 ScrollView，`check_design` 量的是 document 高度，
   把一篇 1437px 的协议正文报成「页面高 844，一屏放得下」——这个结论是错的。

所以本记录的截图与滚动/方向检查全部走 CDP 脚本（`tmp/cdp-app-shot.js` 等，脚本不进仓库）。

## 二、老账号 20 个路由（测试账号 13800000001 / user_id=1）

`/` 会按设计分流到 `/profile`（已登录），其余 19 条落在预期路径。

| 截图 | 路由 | 结果 | 备注 |
|---|---|---|---|
| 01-splash | `/` | → `/profile` | 启动页 900ms 最短展示后分流，符合设计 |
| 02-register | `/register` | 渲染 | 手机号 + 验证码 + 密码 + 协议勾选 + 未成年人勾选 |
| 03-forgot-password | `/forgot-password` | 渲染 | 三步式（手机号 → 验证码 → 新密码） |
| 04-agreement | `/agreement` | 渲染 | **正文来自 sys_config**，非写死 |
| 05-privacy | `/privacy` | 渲染 | 同上 |
| 06-profile | `/profile` | 渲染 | 已实名 + 律师标签 + 余额 + 四组入口 |
| 07~14 设置 8 页 | 见目录 | 渲染 | 账号与安全 / 改密 / 换绑手机 / 注销 / 实名 / 资料编辑 / 通知 / 隐私 |
| 15-balance | `/balance` | 渲染 | 空态「还没有资金记录」+ 类型与时间筛选 |
| 16-pay-password | `/pay-password` | 渲染 | 测试账号未设支付密码，显示「设置」分支 |
| 17-lawyer-join | `/lawyer-join` | 渲染 | 该账号已 APPROVED，正确短路成「不能重复提交 + 查看审核状态」 |
| 18-lawyer-join-status | `/lawyer-join-status` | 渲染 | 已通过状态 |
| 19-language | `/language` | 渲染 | 段 0 页，回归确认未被改坏 |
| 20-login | `/login` | 渲染 | 段 0 页 + 新增「去注册 / 忘记密码」入口 |

**汇总：20/20 渲染成功，疑似空白 0，每页 console error 0。**

## 三、新账号 13 页（13800000777 / user_id=44，本次真实注册产生）

老账号已实名且入驻通过，很多页面被状态短路，看不到表单本身。为此**走了一遍真实注册链路**
（`POST /pub/sms/code` → `POST /pub/auth/register`）拿到一个全新账号，再截一遍：

| 截图 | 验证到的形态 |
|---|---|
| n01-profile | 未实名 + 普通用户 + 余额 0 + 「完成实名认证后可提交立案材料」提示 |
| n02-verify-realname | **实名表单本体**：用途说明卡、姓名、身份证号、人像面/国徽面两个上传位、隐私政策勾选 |
| n03-pay-password | 设置支付密码分支（6 位数字 + 确认） |
| n04-lawyer-join | **四步表单第 1/4 步**：步骤指示器、真实姓名、执业证号、「每一步都可保存草稿」、下一步 + 保存草稿 |
| n05-lawyer-join-status | 从未提交时的空状态 |
| n06~n13 | 资料编辑 / 换绑手机 / 改密 / 注销 / 账号与安全 / 余额 / 隐私设置 / 通知设置 |

**汇总：13/13 渲染成功，疑似空白 0，console error 0。**
另外这也顺带证明了**注册链路端到端可用**（发码 200 → 注册 200 → 登录成功 → 新用户数据正确）。

## 四、固定底栏是否压住内容（G5 第 3 项）

RN Web 的滚动在内部容器里，所以用 CDP 把每个 `overflowY: scroll/auto` 且内容超高的容器
滚到最底再截图：

| 路由 | 可滚动容器 | scrollHeight / clientHeight | 滚到底截图结论 |
|---|---|---|---|
| `/verify-realname` | 1 | 840 / 800 | 「我已阅读并同意《隐私政策》」+「查看隐私政策」完整可见，未被底栏遮挡 |
| `/agreement` | 1 | 1437 / 800 | 末尾「（以上为占位条款…）」+ 底部版权完整可见 |
| `/register` `/lawyer-join` `/deactivate` `/edit-profile` | 0 | — | 内容一屏放得下，不涉及 |

结论：`ActionBar` 的底部预留生效，**没有「滚到底仍被压住」的情况**。

## 五、RTL / 维吾尔文（近似验证，不等价于原生判据）

**必须先说清楚局限**：RN Web 下 `I18nManager.forceRTL` 是空实现，浏览器里做不出真 RTL 环境。
`0-A3` 的硬判据（杀进程后仍 RTL）只能在原生跑。本次做的是**近似验证**：给根节点设
`dir="rtl"`，看 RN Web 输出的逻辑属性（`padding-inline-start` 等）是否镜像。

| 路由 | 镜像结果 |
|---|---|
| `/profile` | 头像到右侧、标签顺序反转、余额按钮到左侧、`›` 图标变 `‹`、分组标题右对齐 |
| `/register` `/balance` `/lawyer-join` `/agreement` | 同上，无重叠、无超框、无文字截断 |

旁证：5 页每页只有 2 处物理 `left/right` 内联样式，且都是 RN Web 自身生成的容器样式
（不是业务代码里的 `left`/`right`）——业务侧 `start/end` 已由 ESLint 拦住。

**挂账**：真 RTL 判据（原生 `I18nManager.isRTL` + 杀进程重启）在浏览器口径下无法验，
记入段 8 上线清单，若届时仍无真机则明确标注「该项未验」。

## 六、i18n 与质量门禁（可复现的命令）

| 检查 | 命令 | 结果 |
|---|---|---|
| 类型 | `npx tsc --noEmit` | **0 error** |
| 静态检查 | `npx eslint .` | **0 告警** |
| 硬编码中文 | `scripts/check-raw-text.ps1` | **命中 0** |
| 四语言同构 + 占位符 | `scripts/check-i18n.ps1` | 通过；bo / ug / kk 各缺 517 条，已登记进 `.i18n-pending.json` |
| key 引用完整性 | `tmp/check-i18n-refs.js`（只读脚本） | 代码里 298 条字面量 key **全部命中**资源（资源共 517 条） |

> 「登记式缺译」是 i18n 规范允许的中间状态，段 8 收口时该表必须为空。段 1 不做译文填充。

## 七、验收过程中发现并修掉的后端缺陷（3 个）

这 3 个都是**页面验收逼出来的**，只看代码不会发现：

| # | 缺陷 | 证据 | 修法 |
|---|---|---|---|
| 1 | `ProfileVO.lawyerStatus` 恒 `NONE` | 实测 `GET /app/account/profile`：用户已有 APPROVED 入驻申请，仍返回 `NONE`；「我的」页显示「未入驻」 | 新增 `ProfileService` 取 `lawyer_application` 最新一条状态 |
| 2 | `ProfileVO.balanceCents` 恒 `0` | 同上，余额为零与「接口没接」在页面上无法区分 | 走 `BalanceQueryService.getTotalCentsFromFlows`（SUM 流水，锚点 1-A6） |
| 3 | `ProfileVO.payPasswordSet` 恒 `false` | 「账号与安全」页永远显示「未设置」，用户会去走「重置」多花一次验证码 | 从 `app_user.pay_password` 派生真实值 |

另修一处 G1 的**功能缺口**：用户服务协议 / 隐私政策正文要求「来自配置不是写死」，但后端
没有匿名可读的配置接口，`sys_config.config_value` 还是 `varchar(500)`
（实测导入占位正文就 `ERROR 1406 Data too long`）。已补
`GET /api/v1/pub/config/{configKey}`（白名单 2 个键，白名单外一律 401004）+ V12 迁移扩到 TEXT +
两条种子正文，并加 4 个集成测试钉住白名单边界。

## 八、错误态与「点重试能恢复」（后续补齐，1-A9 的后两态）

正常态与空态之外，错误态由 `deploy/verify-error-states.js` 验证：用 CDP 的 `Fetch.enable`
**只拦 `*/api/v1/*` 请求**，页面资源照常加载，于是接口全部失败——这才是客户端真实会遇到的
「接口挂了」。**不用 `Network.emulateNetworkConditions offline`**：那样连页面导航都被拦掉，
浏览器显示自己的离线页（恐龙游戏），验的是 Chrome 不是 APP（实测 7/7 全 FAIL 才发现）。

7 个有异步数据的页，逐个「拦接口 → 截图错误态 → 断言有可读文案与重试按钮 → 放行 → 点重试 → 截图」：

| 页 | 被拦请求数 | 错误态文案 | 重试按钮 | 点后恢复 |
|---|---|---|---|---|
| `/profile` | 3 | 有 | 「重试」 | 有 |
| `/balance` | 2 | 有 | 「重试」 | 有 |
| `/account-security` | 1 | 有 | 「重试」 | 有 |
| `/verify-realname` | 1 | 有 | 「重试」 | 有 |
| `/pay-password` | 1 | 有 | 「重试」 | 有 |
| `/agreement` | 1 | 有 | 「重试」 | 有 |
| `/lawyer-join-status` | 1 | 有 | 「重试」 | 有 |

**7/7 通过**，截图在 `G5-states/error-states/*-error.png` 与 `*-recovered.png`。
错误态文案是「网络连接失败，请检查网络后重试」+ 蓝色重试按钮，品牌头部保留。

纯表单页（注册/忘记密码/改密/换绑/资料编辑/通知/隐私/律师入驻）没有数据请求，
不存在错误态；它们用「提交中 + 内联校验错误 / ErrorState」表达失败，这个口径写在各页注释里。

## 九、未验项（明确列出，不假装覆盖）

| 项 | 为什么没验 | 何时补 |
|---|---|---|
| 真 RTL（原生 `I18nManager`） | 浏览器口径下无法产生真 RTL，只能做 dir=rtl 的近似验证 | 段 8 上线清单 |
| `ug` 语言下**文案** | 三语译文为空（登记式缺译），切过去仍是中文 fallback | 段 8 |

> 原本列在这里的「端到端写操作」已由 `deploy/verify-e2e.js` 补齐（38 项全绿），
> 「错误态截图」已由 `deploy/verify-error-states.js` 补齐（7 页全绿），两项都不再挂账。
| 律师入驻四步的**完整填写与提交** | 提交会创建真实申请记录 | 同「端到端提交」 |

## 九、其它观察（不阻塞验收）

1. 协议页顶部有 AppHeader 标题「用户服务协议」+ 正文自带标题「法泽 FAZE 用户服务协议」，
   轻微重复。法务正文自带标题是惯例，未改。
2. 「我的」页与「账号与安全」页都显示支付密码状态，两处都正确跟随 `payPasswordSet`。
3. `features/auth/pages/LoginPage.tsx` 在本次新增了「去注册 / 忘记密码」入口
   （原来 G1 的注册页只能手敲 URL 进入，「能从宿主页面跳进来」不成立）。
