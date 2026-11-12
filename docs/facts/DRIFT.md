# 说辞漂移登记表（DRIFT）

> **【文档基线 v1.69】**
>
> 规则见 [`docs/standards/说辞一致与真相源规范.md`](../standards/说辞一致与真相源规范.md)。
> **本表只装 DRIFT（同一事物、说法不同）。**
> 「计划有、代码没有」→ 去 [`DEFERRED.md`](DEFERRED.md)。「代码对、库里脏」→ 去巡检 SQL。

---

## 这份表不是什么

- **不是缺陷清单**。缺陷清单是修完就没了的；本表是**长期记忆**——一条关掉了，行还在（标 `关`），
  因为下一次改动可能把它重新打开。
- **不是待办清单**。处置值是 `待拍板` 的行不是"待办"，是"等人决策"。
- **不是完整清单**。本表只装**已知**的。不在表里 ≠ 没有漂移，只等于**未发现**。

## 怎么读一行

| 列 | 含义 |
|---|---|
| 事实 | 一句话说清"什么东西有两种说法" |
| 代码实际（P0） | 代码/库里真实是什么。**这列是裁决依据** |
| 另一方说法 | 文档或设计稿怎么说的，注明出处 |
| 处置 | `改文档` / `改设计稿` / `改代码` / `待拍板` / `不处置` |
| 证据强度 | `实测`（真跑过）/ `通读`（读过代码没跑）/ `待复核` |

**证据强度必须诚实。** 把 `通读` 写成 `实测`，下一个人会据此跳过复核——这比不登记更糟。

---

## 统计（2026-11-10 建表）

| 处置 | 条数 | 其中 D1 组（逐条核查） | 其中 D2 组（盘点批量） | 其中 D3 组（脚本量出） |
|---|---|---|---|---|
| 改文档 | 27 | 6 | 15 | 6 |
| 改设计稿 | 3 | 1 | 2 | 0 |
| 改代码 | 18 | 15 | 0 | 3 |
| 待拍板 | 0 | 0 | 0 | 0 |
| 不处置 | 3 | 3 | 0 | 0 |
| **合计** | **51** | **25** | **17** | **9** |

> **已拍板的（不再待定）**：
> - **DRIFT-043**（余额表列名）→ 负责人 2026-11-11 定「**统一列名**」。**2026-11-13 已执行并关闭**：迁移 V712 把 `user_balance.balance_cents` 改名为 `available_cents`，后端实体/Mapper/7 个 Service/5 个测试/巡检与运维脚本/33 处文档同步。选 `available_cents` 而非 `balance_cents` 的三条依据写在 V712 头部注释里（接口层事实标准 / available·frozen 成对 / 避开 `lawyer.balance_cents` 废弃列同名）。
> - **「user 3 实名矛盾」**（`data-integrity.sql` 的 A12 红灯）→ 负责人 2026-11-11 定 **方案 C：不改数据**。等 DRIFT-015 修完（让 `realname_status` 由 `realname_auth` 派生）再一次性收敛。**在这之前 A12 会一直是 1，那是预期，不是漏修。**
> - **DRIFT-049**（接口层 `balanceCents` 是总余额）→ 负责人 2026-11-13 定 **不改名，保留 `balanceCents`，按「改文档」处置**。裁决前实测了改名成本：PC 后台 `pc/admin-web/src/views/biz/wallet/index.vue` 有 **9 处消费**（其中 L457-461 用 `availableCents = balanceCents - frozenCents` 自己算，L18-19 注释已写明「balanceCents 是流水汇总的总余额」→ **PC 端根本没误解语义**），App 端只有 `features/profile/api/profile.ts:33` 一个类型声明、**0 处实际消费**。改名收益仅为「名字与语义一致」，代价是 9 处代码 + 4 处注释 + `api.json` 重生成 + 前端回归，而**没有任何「因误解导致 bug」的证据**。改为在《接口规范》A.5 写清字段语义（同批提交）。**这是判断，不是事实——若将来出现第 10 处消费点或新人误读案例，应重新评估。**

> **2026-11-13 二次拍板：DRIFT-020~023（原「待拍板」4 条）全部结清。**
>
> 负责人当轮授权「按建议默认值往下做」，所以下面四条是**按 agent 的建议值落定的**，不是负责人逐条确认过。
> 每条都写明「改回去要动哪里」——不同意就照着改，不会有一堆东西跟着塌。
>
> - **DRIFT-020（注销冷静期）→ 要**。以设计稿 `45-deactivate.html` 的「7 天后生效」为准，代码的立即生效要改。
>   **本次未实现**：要加「待生效」状态 + 每日生效任务 + 期间撤销入口 + 期间禁止登录的判定，是独立功能，不塞进「边界校验」那条提交里。
>   **改回去 = 说「不要冷静期」，然后改设计稿**（一行字）。
> - **DRIFT-021（有余额能不能注销）→ 代码对，改法务文本**。资金无归属是硬伤，拒绝注销是对的；
>   隐私政策正文「注销后余额不可恢复」要改成「请先提现 / 消费完余额再注销」。
>   **注意隐私政策正文属段八（法务定稿），本次只登记不改正文**——技术侧该做的已做（402012 会把用户导向「去处理余额/订单」而不是「状态非法」）。
>   **改回去 = 允许带余额注销，那必须同时设计退款通道**，成本远高于改一句话。
> - **DRIFT-022（注销查不查在途订单）→ 必须结清，2026-11-13 已实现**。它与 DEF-015 是同一件事的两面：
>   DEFERRED 记「注销没查订单」，DRIFT 记「文档没说要查」。两边一并收口，实测见 §八 关闭记录。
> - **DRIFT-023（签名库加不加「姓名」）→ 不加**。`signature` 表 15 字段是技术路线 §5.6 原文，
>   `Signature.java:4`、`Signature.java:10`、`SignatureService.java:10` 三处注释明文「不许加字段」；
>   文书模板要的是**签名图**，姓名在 `app_user.real_name` / `lawyer.real_name` 已有权威来源，
>   复制进签名表就是第三份真相（改一次名要改三处）。
>   **处置：改验收报告的回写**，写明「签名库只存签名图，不含姓名字段」。
>   **改回去 = 加字段**：要同时改技术路线 §5.6、V400 之后的迁移、文书模板、4 语言文案，成本见原行的记载。
>
> **统计口径提醒**：下表「改代码 18」里有 **1 条未执行**（020 冷静期）；「改文档 27」里有 **1 条待段八执行**（021 隐私政策正文）。
> 这两条的处置已定但与本次提交无关，不要读到「改代码 18」就以为代码侧还有 18 件没做。

> **D1 组（25 条）**：逐条核查建表，每条都有代码或接口层面的直接证据。
> **D2 组（17 条）**：2026-11-11 全仓盘点批量登记，方向是「文档落后于代码」，多数标 `待复核`。
> **D3 组（9 条）**：**跑脚本量出来的**，不是读代码读出来的——`data-integrity.sql` 抓出列名不一致，`api.mjs` 抓出接口数不符，`error-codes.mjs` 抓出错误码数不符（且文档内部自相矛盾），`extract-i18n.mjs` 抓出「i18n key 三个口径」与「门禁自身漏检 1 条缺译」，`render-docs.mjs` 把生成表渲染进规范后，与手写表一并列就暴露 30 个「规范列了、枚举里没有」的码。这一组最能说明 G2 为什么值得做：**修完一条，下一条往往是工具的盲区，而不是业务的错误。** 第 7 条（DRIFT-049）是执行 DRIFT-043 时顺着列名查出来的：**改列名的人才会去逐个问「这个字段到底是什么语义」**。第 8 条（DRIFT-050）是核 DEF-017 撞号时**被自己的实测打脸三次**才查出来的：按 `.env` 的 `FAZE_REDIS_URL` 往 db14 写，`SET` 静默无效，前三次「撞号不会发生」全是误判。第 9 条（DRIFT-051）是给 DEF-014/015 新增两个 msgKey 时**第一次把 pending 从空跑成非空**才暴露的：门禁对 `error.json` 也加了一层 namespace 前缀，写进登记表的路径在库里根本不存在。
>
> **首次建表 44 行，此后每轮新增 ≤3 行。** 超过 3 行说明当轮改动引入了成批新分叉，应停下来先修机制。
> D2 组属于"补齐历史欠账"，不计入每轮配额。

---

## 一、改文档（文档错，追代码）

| ID | 事实 | 代码实际（P0） | 另一方说法 | 处置 | 证据 | 强度 |
|---|---|---|---|---|---|---|
| DRIFT-001 | APP「有多少个页面」有四种口径，无任何文档讲清构成 | 产品页 69 / 路由常量 71 / 实现文件 71 / 设计稿主屏 68 | `docs/README.md:6` 写 69；总纲与《UI 设计顺序》写 68；`docs/README.md:79` 写「71 个 HTML」 | 改文档：在 `docs/README.md` 加口径表 | `node scripts/facts/page-inventory.mjs` 输出 | 实测 |
| DRIFT-002 | 案例「分类」在列表接口与搜索接口里是两个字段名 | 列表 `caseType`、搜索 `category`，同一概念 | 设计稿 `04-case.html` 与 `40-search.html` 各用一套，文档未说明二者关系 | 改文档：补一句「同一枚举的两种传输字段名」 | 字段名通读两处 api 客户端 | 通读 |
| DRIFT-003 | 客服有两条服务、两个时段，文档未区分 | 热线 `09:00-18:00`（`sys_config.service.phone_hours`，V711）、在线客服 `09:00-20:00`（`service.online_hours`） | 验收方按「时段不一致 = 矛盾」报为缺陷 | 改文档：两条服务分开写 | `PubAppConfigController` 同时返回两值 | 实测 |
| DRIFT-004 | 首页「每日热点」横幅是静态展示，不可点击 | 静态渲染，无点击处理 | 验收方按「横幅应可点进专题」判定为缺陷；设计稿未标注"静态" | 改文档：明确标注静态 | 首页实现通读 | 通读 |
| DRIFT-005 | 进度节点词汇表：同一个 `accepted` 在用户侧与律师侧叫法不同 | 用户侧显示「审核通过」，律师侧流程里叫「受理并立案」 | 设计稿与工作流均未给「一码两义」的对照表 | 改文档：补节点词汇对照表 | 时间轴实现通读 | 通读 |

## 二、改设计稿（设计稿错，追代码）

| ID | 事实 | 代码实际（P0） | 另一方说法 | 处置 | 证据 | 强度 |
|---|---|---|---|---|---|---|
| DRIFT-006 | 订单「服务名称」设计稿显示两行不同取值，实现只有一个字段 | 只有 `orders.service_type` 一个枚举，页面按枚举映射出一句名称 | 设计稿 `30-order-detail.html` 同时显示服务名与服务描述两行 | 改设计稿：删掉不存在的那一行 | `IncomeFlowRowVO` 改造后单字段（P2-1 已处理过同类问题） | 通读 |

## 三、改代码（代码命中硬约束 2a 或承诺相反 2b）

| ID | 事实 | 代码实际（P0） | 另一方说法 | 处置 | 依据 | 证据 | 强度 |
|---|---|---|---|---|---|---|---|
| DRIFT-007 | 字号设置承诺「影响全 App」，实际只影响设置页自身 | `scaleTextStyle` / `readFontScale` 只被 `SettingsPage.tsx` 消费 | `zh-Hans/profile.json:265`「字体会影响全 App 的正文与按钮文字」 | 改代码（改文案追实现，规则 2b） | 2b 承诺相反 | grep 全仓消费点 | 通读 |
| DRIFT-008 | 字号说明里含内部表述并上屏 | — | `zh-Hans/profile.json:265` 同句含「目标用户里有不少中老年人」 | 改代码（删内部表述） | 2a 违反「所有面向用户字符串走 t() 且不得含内部用语」 | 同上 | 通读 |
| DRIFT-009 | 案号提示写「FAZE- 开头」，实际单号形如 `CS20261110000003` | 前缀 `CS` | `zh-Hans/case.json:134`「FAZE- 开头的编号是平台业务单号」 | 改代码（改文案） | 2b 承诺相反 | 读 i18n 资源 + 实测建案返回 | 实测 |
| DRIFT-010 | 支付密码剩余次数响应头浏览器读不到 | 服务端返回 `X-Pay-Pwd-Remaining`，但 CORS 未暴露 | 设计稿要求显示剩余次数 | 改代码（补 CORS 暴露） | 2a 违反「跨域头必须暴露」 | `config/CorsConfig.java:63` 只暴露 2 个头 | 实测 |
| DRIFT-011 | `legal_case.court_name` 有读无写，永远不会被填上 | 全仓 0 个写入点（`setCourtName` 在 caseflow 模块 0 处） | 设计稿要求显示「法院案号」与法院名称；页面按"有"渲染 | 改代码（补写入路径或明确不支持） | 2a 数据完整性 | grep 全仓写入点 | 通读 |
| DRIFT-012 | `article.view_count` 有读无自增，永远是 0 | 无自增语句 | 设计稿显示「N 次阅读」 | 改代码（补自增或隐藏该行） | 2a 数据完整性 | grep 自增 | 通读 |
| DRIFT-013 | 幂等键每次请求新生成，与注释承诺相反 | `features/case/api/client.ts` 每次调用 `newIdempotencyKey()` | 同文件注释承诺「重复提交返回原结果」；`RechargePage.tsx` 用 `useRef` 固定才是对的写法 | 改代码（规则 2b） | 2b 承诺相反 | 读两处实现对比 | 通读 |
| DRIFT-014 | 「取消收藏」的归属预检取的是**任意用户**的收藏行，当首行属于别人时误报「无权限」 | `SocialQueryService.unfavorite` 用 `LIMIT 1` 取该目标任一收藏行（**无 `userId` 条件、无 `ORDER BY`**），再拿该行 `userId` 与当前用户比较，不等即抛 `401008`。真正执行删除的 `SocialActionService.unfavorite` 已按 `userId` 过滤，预检本身是多余的 | 注释写「归属校验：先看这个目标当前是否被别人收藏着」——**这个意图本身是错的**；同文件 `unfollow` 无此预检，注释写「归属天然安全：更新条件带 `user_id`」，两种写法并存 | 改代码（规则 2a 权限归属） | 2a 权限归属 | **实测对比**：`DELETE /app/social/favorites?targetType=article&targetId=3`（该目标仅 9324 收藏、9415 未收藏）→ `{"code":401008,"msg":"无权限访问该资源"}`；`targetId=99`（无人收藏）→ `{"code":200,"msg":"ok"}`。**同一接口、同一用户，结果取决于别人有没有收藏** | 实测 |
| DRIFT-015 | 实名状态存在两套真相：**同一用户、同一时刻，两个页面显示两种实名状态** | 提交实名只 upsert `realname_auth`；`app_user.realname_status` 的唯一写入点是后台审核 executor（`AdminVerificationAuditExecutor`），而 4 处 `setRealnameStatus` 全读 `app_user.realname_status`。前端 `PENDING` / `REJECTED` 分支因此是死代码 | 文档与页面按「实名状态」单值理解；`realname.ts` 注释写「大写枚举 NONE/PENDING/APPROVED/REJECTED」，暗示四态都可出现 | 改代码（规则 2a 数据完整性） | 2a 数据完整性 | **实测（用户 9416，同一 token、同一时刻）**：`GET /app/account/realname` → `"status":"PENDING"`（实名页显示「审核中」）；`GET /app/account/profile` → `"realnameStatus":"NONE"`（我的页/设置页显示「未实名」，企业认证前置校验会拦「请先完成实名认证」）。库内：9416/9418 `realname_auth=pending` 而 `realname_status=none`；`realname_status` 真实值只有 `approved`/`none`。**user 3 是矛盾实例（`auth=rejected` 而列=`approved`），负责人 2026-11-11 定方案 C：不改数据，等本行代码修复后由派生逻辑收敛** | 实测 |
| DRIFT-016 | 下单允许自己向自己下单 | **已修（2026-11-13）**：`OrderCreateExecutor.create` 任务 3 比较 `lawyer.user_id` 与下单人 `userId`，相等抛 405031，且该判断在取价之前（不会留下订单、不会入队关单任务） | 业务上不可能存在；文档未声明禁止 | **改代码：已执行并关闭**（规则 2a 数据完整性） | 原实测：`orders` 22、28 的 `user_id` 等于律师自己的 `user_id`——**这两行已在 2026-11-11 清脏数据时删掉**，2026-11-13 复查 `orders JOIN lawyer ON lawyer.id = orders.lawyer_id AND orders.user_id = lawyer.user_id` 结果为 **0 条**；修复后的证据是 `OrderCreateExecutorTest#selfOrderRejected`（+2 条反向用例）与 §八 | 实测 |
| DRIFT-017 | 案例详情路由没有路由常量，两处硬编码裸路径 | `app/case-detail.tsx` 存在，`PAGE_ROUTES` 无 `caseDetail` | `routes.ts:4` 明文「页面导航一律从这里取路径，禁止在页面里直接写路径字符串」 | 改代码（规则 2a 违反本仓明文规范） | 2a 明文规范 | `node scripts/facts/page-inventory.mjs` 差集 B 组 + `SearchPage.tsx:434`、`CaseListPage.tsx:251` | 实测 |
| DRIFT-018 | 案例详情按「法律名称+条号」反查法条，找不到时静默取列表第一条 | `?? list[0]` 兜底，会把错的条文当对的显示 | 页面意图是显示案例引用的那一条 | 改代码（规则 2b：静默兜底与「显示引用条文」的意图相反） | 2b | `features/content/pages/CaseDetailPage.tsx` 兜底；`ArticleService` 同文件用 `lawItemId` 才是对的 | 通读 |
| DRIFT-019 | 验证码校验无尝试次数上限 | `verifyAndConsume(phone, code)` 无 scene 参数、无失败计数，6 位码 5 分钟有效 | 文档按"有防爆破"理解 | 改代码（规则 2a 安全） | 2a 安全 | `SmsCodeService` 通读；键 `faze:sms:code:` 不含 scene | 通读 |

## 四、待拍板（已全部拍板，留档备查）

> **本节的 4 条已于 2026-11-13 全部结清**，没有待定项了。裁决原文见上方「2026-11-13 二次拍板」块。
> 保留本节是为了让「当初这 4 条到底卡在哪」可查——不要因为标题里还有「待拍板」就以为还有人没拍。

| ID | 事实 | 代码实际（P0） | 另一方说法 | 已拍什么 | 证据 | 强度 |
|---|---|---|---|---|---|---|
| DRIFT-020 | 注销冷静期：设计稿要等 7 天，实现立即生效 | `cancelAccount` 立即生效，无冷静期 | 设计稿 `45-deactivate.html` 写「7 天后生效」 | **要冷静期**（2026-11-13，按建议默认值）→ 改代码，**本次未排期**：要加「待生效」状态 + 每日生效任务 + 期间撤销入口 | 设计稿 + 服务端实现通读 | 通读 |
| DRIFT-021 | 注销时「有余额」的处置：代码直接拒绝，隐私政策写"余额不可恢复" | 有余额或冻结金额即拒绝注销 | 隐私政策文本写「注销后余额不可恢复」 | **代码对，改法务文本**（2026-11-13）→ 隐私政策正文改成「请先提现 / 消费完余额再注销」。**正文属段八法务定稿，本次只登记不改** | `AccountSecurityService` 通读 | 通读 |
| DRIFT-022 | 注销只检查余额与冻结，不检查是否有进行中的订单/案件 | **已修（2026-11-13）**：`cancelAccount` 任务 2 同时查「作为下单人」与「作为服务方」的非终态订单，命中抛 402012 | 无文档说明 | **必须结清在途业务**（2026-11-13）→ **改代码：已执行并关闭**（与 DEF-015 同一件事，见 §八） | `AccountSecurityService` 通读 + `DeactivateWithActiveOrderTest` 4 例实测 | 实测 |
| DRIFT-023 | 签名字段固定 15 个，代码注释明文「不许加字段」 | `signature` 表 15 字段（V400 建） | 验收方期望签名库显示姓名 | **不加**（2026-11-13）→ 不处置。改验收报告回写「签名库只存签名图，姓名取 `app_user.real_name` / `lawyer.real_name`」。依据：15 字段是技术路线 §5.6 原文（`Signature.java:4`、`:10`、`SignatureService.java:10` 三处注释）；加字段要同改技术路线 + 迁移 + 文书模板 + 4 语言；姓名已有权威来源，复制进签名表就是第三份真相 | 三处注释通读 + 姓名权威来源核对 | 通读 |

## 五、不处置（三方都对，只是没人讲清）

| ID | 事实 | 代码实际（P0） | 另一方说法 | 为什么不动 | 证据 |
|---|---|---|---|---|---|
| DRIFT-024 | 案例详情没有「具体纠纷类型」这一行 | 库中无此字段，页面无此渲染 | 验收方期望有；设计稿 `19-case-detail.html` 也没有这一行 | **三方一致（都没有）**。补文档一句「案例库只到大类 + 细分，详情页不单列」即可 | 设计稿 + 库字段 + 页面三方比对 |
| DRIFT-025 | 消息中心与「通知未读」的关系 | `notification` 表有未读数据，无消费页面；消息中心页不存在 | 段 2 记为「空态交付」，段 7 记为「段 2 已交付」——**两份文档互相假设对方做了** | **这是 GAP 不是 DRIFT**（代码里没有该页），已移入 `DEFERRED.md` DEF-001。此处留行是为了不让读者以为漏登记 | 两份工作流文档原文对读 |

---

## 六、D2 组：2026-11-11 全仓盘点批量登记（17 条）

> **这一组与上面五节的性质不同，读之前必须知道三件事：**
>
> 1. **来源是一次全仓盘点**（覆盖 `docs/` 164 份 Markdown + 代码抽查），不是我逐条排查的。所以除标注 `实测` 的两条外，强度一律 `待复核`。
> 2. **方向与常见挂账相反**：这 17 条都是「**文档落后于代码**」，不是"代码没做"。按旧文档排期会**重复劳动**。
> 3. **处置绝大多数是 `改文档`**，不需要裁决。它们进本表是为了让人知道"文档说的已经不对了"。

### 6.1 改文档（15 条）

| ID | 事实 | 代码实际（P0） | 文档怎么说 | 处置 | 出处 | 强度 |
|---|---|---|---|---|---|---|
| DRIFT-026 | 缺译条数：文档说还有 1818 条，实际早就清零 | `.i18n-pending.json` 的 `pending` 为 `{}`（0 条）；`CHANGELOG.md:434` 记「1818×3 → 0」 | `seg7/挂账.md:144`「本批已累到 **1818 条**，段 8 收口时必须为空」 | 改文档 | `apps/mobile-app/shared/i18n/resources/.i18n-pending.json` | 实测 |
| DRIFT-027 | `region` 字段：文档说无采集点恒为 NULL，实际已全线实现 | `V709` 地区枚举 + `GET /pub/regions` + `RegisterBO.region` 白名单校验 + `RegisterPage` 地区选择器；库内已有「奎屯市」等真实值 | `seg7/挂账.md:68`「该列**无采集点、恒为 NULL**……采集入口段 8 补」 | 改文档 | 同上 | 通读 |
| DRIFT-028 | 业务错误率指标：文档说「已交给 be-api 落地」，实际已落地 | `BizMetricsAdvice` + `FazeMetrics`（`faze_biz_errors_total`）+ `alert-rules.yml:50` 表达式均已存在 | `seg7/挂账.md:47`「已交给 be-api 落地：新增业务错误指标 + 改 `FazeHighErrorRate` 表达式」 | 改文档 | 同上 | 通读 |
| DRIFT-029 | 技术路线 §5.11 标题写「新增 15 张」，正文是 17 张 | 迁移脚本可数为 17 | `seg7/挂账.md:98` 已登记该不一致，但**标题本身未改** | 改文档 | `docs/architecture/法泽FAZE_技术路线_v2.3.md` | 待复核 |
| DRIFT-030 | 数据表规范「已建 53 张」与清单 55 张并列，单读会被当成总数 | 已建 53（`invoice` / `share_poster` 未建），清单 55 | `seg7/挂账.md:99` | 改文档 | `docs/architecture/法泽FAZE_数据表规范_v1.0.md` | 待复核 |
| DRIFT-031 | `case_library` 附录 A 写 21 字段，实际 24 | 实际 24（补了争议焦点 / 原告 / 被告） | `第5段工作流:1322`「附录 A 需在 H3 回写（21 → 24）」，未回写 | 改文档 | `docs/architecture/法泽FAZE_数据表规范_v1.0.md` 附录 A | 待复核 |
| DRIFT-032 | `balance_flow.biz_type` 规范写 4~5 值，实际全表 **9 个不同值** | **实测**：`SELECT DISTINCT biz_type` = 9。用户侧 4：`adjust` / `consume` / `recharge` / `refund`；律师侧 5：`freeze` / `income` / `income_unfreeze` / `unfreeze` / `withdraw` | `第5段工作流:1321`「`biz_type` 现为 **5 值**」（只数了律师侧） | 改文档：写明是"全表 9 值 / 律师侧 5 值"两种口径 | `faze.balance_flow` | 实测 |
| DRIFT-033 | 段 5 工作流 A2「5 合法 / 非法 15」与 F3「5 合法 + 4 非法」两处数字与各自表格不符 | — | `第5段工作流:1323` | 改文档 | `docs/architecture/法泽FAZE_第5段工作流_v1.0.md` | 待复核 |
| DRIFT-034 | 段 3 承诺给段 4 的 `ArticleNoValidator` + `refs` schema，段 4 并未使用 | 段 4 未使用该契约 | `seg4/遗留与挂账.md:16`「该契约落空」 | 改文档：标为已废弃 | 同上 | 待复核 |
| DRIFT-035 | i18n 规范说「六项 CI 检查段 0 就建」，实际只落了 1 项，现在有 3 项 | `check-i18n.ps1` 现含三项检查，仍非六项 | `第0段工作流:1127` 记「其余五项挂账到段 8 收口」 | 改文档：对齐实际项数 | `docs/architecture/法泽FAZE_i18n规范_v1.0.md` | 待复核 |
| DRIFT-036 | `operation_log.detail` 段 1 建成了 TEXT 而不是 JSON | 列类型 TEXT | `第7段工作流:2315`「段 1 建错字段……段 8 收口统一」 | 改文档 | `docs/architecture/法泽FAZE_数据表规范_v1.0.md` | 待复核 |
| DRIFT-037 | `doc_template` / `file_record` 两表偏离《数据表规范》 | `doc_template` 用 `status` 三态不用 `is_enabled`；`file_record` 14 字段不加 `updated_at` | `seg4/遗留与挂账.md:10` 已登记偏离 | 改文档：把偏离写进规范 | 同上 | 待复核 |
| DRIFT-038 | 本机 Redis 是 5.0.14.1，规范要求 7.x | 实测 `redis_version` 为 5.x | `seg0/README.md:48`（`0-A6` 判定要求 7.x） | 改文档：登记降级接受，或升级环境 | `docs/acceptance/seg0/README.md` | 待复核 |
| DRIFT-039 | 段 1 `G-11` 的文档落款与 `BASELINE.md` §1.1 规则冲突 | 落款随内容升级，违反「落款不随内容变更」 | `第1段工作流:1302` | 改文档：全库核对落款 | 各文档落款行 | 待复核 |
| DRIFT-040 | `7-A2` 把「包打赢」当 `block` 级示例，实际它在种子词库里是 `review` 级 | 种子词库中「包打赢」= `review`；`block` 30 条全是 `【占位】…` | `第7段工作流:2455` | 改文档 | `docs/architecture/法泽FAZE_第7段工作流_v1.0.md` | 待复核 |

### 6.2 改设计稿（2 条）

| ID | 事实 | 代码实际（P0） | 设计稿怎么说 | 处置 | 出处 | 强度 |
|---|---|---|---|---|---|---|
| DRIFT-041 | 日志中心「保留 90 天」低于合规下限 | 实现按「≥3 年」渲染 | `design/admin/25-logs.html` 仍写「保留 90 天」；`seg7/挂账.md:97` 记「设计稿原件未改」 | 改设计稿 | `design/admin/25-logs.html` | 待复核 |
| DRIFT-042 | 涨跌配色：设计稿涨绿跌红，实现涨红跌绿（已拍板涨红跌绿） | 涨红跌绿 | `design/admin/components.css` 原件仍是涨绿跌红，`seg7/挂账.md:2466` 记「以后照稿重画仍会画错」 | 改设计稿 | `design/admin/components.css` | 待复核 |

---

## 七、D3 组：工具化 / 清脏数据时发现的（9 条）

这一组不是"读代码读出来的"，是**跑脚本量出来的**——也正是 G2 存在的理由：人肉比对看不到的东西，一行命令就能看到。

| ID | 事实 | 代码实际（P0） | 另一方说法 | 处置 | 出处 | 强度 |
|---|---|---|---|---|---|---|
| DRIFT-043 | 两张余额表用两个列名表示同一个概念（可用余额） | **已统一（2026-11-13）**：两张表都叫 `available_cents`，V712 由 `user_balance.balance_cents` 改名而来 | 《数据表规范》原未规定统一列名，本次补上「可用/冻结成对用 `available_`/`frozen_`」一条；写巡检/对账 SQL 时按旧名写会在另一张表上报 `Unknown column` | **改代码：已执行并关闭**（见 §八 关闭记录） | 实测：`scripts/monitor/data-integrity.sql` 首次运行即因此报错；迁移后 A08/A09/A10 全 0 | 实测 |
| DRIFT-044 | 「管理端接口有多少个」：文档说 **87**，代码实测 **123** | `node scripts/facts/api.mjs` → 按访问面分布 `{"admin":123,"app":114,"pub":22,"dev":1}`，全仓 **260** 个端点 / 78 个 Controller | **5 处文档写 87**：`docs/README.md:121`、`docs/design/未完成需备注.md:171`、`docs/design/法泽PC后台UI设计说明_v1.0.md:226`、`docs/architecture/法泽FAZE_开发计划总纲_v1.0.md:977`、`docs/BASELINE.md:385` | 改文档：把 87 改成生成值，并改为"引用 `docs/facts/api.json`"而不是写死数字 | 实测脚本输出 | 实测 |
| DRIFT-045 | 「错误码有多少个」：文档同时写 **78** 和 **91**，代码实测 **94**。**同一份 `docs/README.md` 内部就自相矛盾** | `node scripts/facts/error-codes.mjs` → 94 个。号段：401:8 / 402:9 / 404:3 / 405:29 / 406:7 / 407:9 / 408:5 / 409:3 / 410:11 / 501:4 / 502:1 / 506:3 / 508:2。**无重复码** | `docs/README.md:61` 写「91 个码」；同一文件 `:128` 写「78 个错误码」；`docs/BASELINE.md:146`、`docs/architecture/法泽FAZE_开发计划总纲_v1.0.md:981` 写 78 | 改文档：统一改为引用 `docs/facts/error-codes.json`。**78 是 2026-10-28 冻结时的数，不是错；错的是没人更新** | 实测脚本输出 | 实测 |
| DRIFT-046 | 「i18n 有多少个 key」有三个数，引用时都没标口径 | 三口径**同时成立**：**总节点 1965**（含中间对象）/ **叶子条目 1880**（真正可翻译）/ 带 namespace 前缀去重后 1880（与叶子相等，说明无覆盖） | `tmp/fixplan2/GUARDRAIL-PLAN.md:75` 写「1965 个 zh-Hans i18n key」——1965 是**总节点**口径，不是 key 数。该文档另写「APP 代码 71 页 / 79 个 JUnit 文件 / 164 份 md」，同类数字都缺口径 | 改文档：引用数字必须连口径一起引；机器口径以 `docs/facts/i18n-keys.json` 的 `totals` 为准 | `node scripts/facts/extract-i18n.mjs` 三个数同时输出 | 实测 |
| DRIFT-047 | i18n 门禁把不同 namespace 的**同名 key 并成一条**，导致 1 条缺译永远不报警 | `profile.settings.title`=「设置」与 `workbench.settings.title`=「服务设置」是**两条不同译文**；旧口径扁平化后只剩一条（1880 → 1879），前者缺译不会被 `check-i18n.ps1` 报出。**2026-11-11 已修**：`check-i18n.ps1` 与 `extract-i18n.mjs` 同时改为带 namespace 前缀；改时 `.i18n-pending.json` 为空，零迁移成本 | 无文档提过这一点（属**未发现**缺陷，不是文档写错）；修后 key 数 1879 → 1880 | 改代码：已修。**两个脚本必须同时改**，只改一边就会出现两套数字 | `scripts/check-i18n.ps1` 的 `Read-LangFlat` | 实测 |
| DRIFT-048 | 错误码规范手写表列了 **130** 个码，枚举只有 **94** 个；**方向是单向的**：代码有、文档无 = **0**，文档有、代码无 = 36（去重后 30 个真码） | `ErrorCode.java` 实测 94 个。规范 §3 手写表里 **30 个码在枚举里不存在**：401009、402007、403001~403006、404003/404004、409001~409004、410001~410004、410016/410017、501004、502002、504001/504002、505001~505003、507001、510002/510003 | 规范 §3.1~§3.10 手写表按「这是要实现的码」的口气编写。其中 `402007`（未实名 403）在 §2.2 的 HTTP 规则表里也被引用，但代码里未实名场景改抛 `405020 TRADE_RECHARGE_REALNAME_REQUIRED` | 改文档：手写表降级为「设计意图」，全量以生成表为准；**30 个未落地码逐个归因后再决定删表还是补码**（`402011` 已标作废、4 个是示例/号段说明、1 个语义被别的码覆盖，都不是缺陷） | 比对 `docs/facts/error-codes.json` 与规范手写表（`scripts/facts/render-docs.mjs` 把生成表渲染进同一节后，两表并列即暴露） | 实测 |
| DRIFT-049 | 接口层把**总余额**叫 `balanceCents`，而库里 `available_cents` 是**可用余额**——同一个词两处指相反的东西 | **实测四个 VO 的 `balanceCents` 全部来自 `SUM(delta_cents)`（含冻结，即总余额）**：`ProfileVO.balanceCents` ← `getTotalCentsFromFlows()`；`RechargeResultVO.balanceCents` ← `flow.balanceAfter` 或 `totalBefore + delta`；`AdminWalletVO.balanceCents` ← `sumDeltaByOwner()`；`AdminRechargeResultVO.balanceCents` ← 同样的流水净额 | `BalanceVO` 用 `{availableCents, frozenCents, totalCents}` 三个字段把同一件事说清楚了；**另四个 VO 只有一个 `balanceCents` 装总余额**，读代码的人会以为它就是 `user_balance` 里那一列（可用余额） | **已裁决（2026-11-13，负责人）：不改名，保留 `balanceCents`**。裁决前实测了影响面：PC 后台 `pc/admin-web/src/views/biz/wallet/index.vue` 有 **9 处消费**（L457-461 用 `availableCents = balanceCents - frozenCents` 自己算，L18-19 注释已写明「balanceCents 是流水汇总的总余额」→ **PC 端没误解**），App 端只有 `features/profile/api/profile.ts:33` 一个类型声明、**0 处实际消费**（`MyPage` 读的是 `BalanceVO.availableCents`）。改名收益仅为「名字与语义一致」，代价是 9 处代码 + 4 处注释 + `api.json` 重生成 + 前端回归，而**没有「因误解导致 bug」的证据**。改为在《接口规范》A.5 写清字段语义 | 读 `ProfileService:63`、`RechargeService:223,249`、`AdminWalletService:84`、`AdminRechargeResultVO` 类注释 + **2026-11-13 实测消费点** | 实测 |
| DRIFT-050 | Redis **库号**有三处说法：`.env` 的 `FAZE_REDIS_URL` 写 `/14`、`redis-faze.conf` 注释写「段 0/1 用独立 db 14」、**实际代码用 db 1** | 实测：`application-dev.yml:11` 是 `database: ${FAZE_REDIS_DATABASE:1}` → **db 1**；`.env` 里**没有** `FAZE_REDIS_DATABASE` 这个键；`FAZE_REDIS_URL` 只被 `deploy/start-services.ps1:146` 与 `deploy/verify-all.ps1:74` 用正则 `^redis://([^/:]+):(\d+)` 取 **host:port**，**`/14` 段被正则丢弃**，对任何组件都不生效；RuoYi 侧 `pc/admin-server/ruoyi-admin/src/main/resources/application.yml` 明确写 `database: 0` | `deploy/.env.example:39`、`deploy/redis-faze.conf:15` 两处都指向 14 | **改代码 + 改文档（2026-11-13 已执行）**：`redis-faze.conf` 的库号注释改正为「db 0 = RuoYi / db 1 = faze 业务，库号由 `FAZE_REDIS_DATABASE` 决定，不由 URL 的 path 决定」；`.env` 与 `.env.example` 补 `FAZE_REDIS_DATABASE=1` | 实测：按 `.env` 往 db14 `SET` 静默无效，连续 3 次误判「撞号不会发生」；db14 实际只有 21 个 **2.5 天前**从旧 `dump.rdb` 加载的 `sys_dict:*` 残留（`OBJECT IDLETIME` 全部 ≈220005 秒 = Redis 进程寿命） | 实测 |
| DRIFT-051 | i18n 门禁给 **`error.json` 也加了一层 namespace 前缀**，写进缺译登记表的 key 在库里根本不存在 | `check-i18n.ps1` 的 `Read-LangFlat` 与 `extract-i18n.mjs` 的 `readLang` 都按「文件名即 namespace」加前缀，`error.json` 不例外 → 登记表里出现 `error.error.auth.cancel-has-active-order`。但 `error.json` 的顶层 key **本身就是完整 msgKey**（`error.auth.cancel-has-active-order`），且 `shared/i18n/index.ts:110-115` 查 `error.*` 是**直接拿完整 msgKey 当 key 查 `resource.error[key]`**，从不加前缀 | i18n 规范 §4.4 明文「`error` namespace 的 key 本身即完整 msgKey」；`shared/i18n/resources/index.ts:97` 注释同口径 | **改代码（2026-11-13 已修）**：两个脚本同时对 `error` 跳过前缀（`$prefix = if ($ns -eq 'error') { '' } else { $ns }` / `flatten(parsed, ns === 'error' ? '' : ns)`）。**两个脚本必须同时改**，只改一边两套数字就会打架（与 DRIFT-047 同一个坑） | 实测：`pending` 从 0 变成非空时才暴露——**这个缺陷在登记表为空时完全不可见**，而它只在「新增 msgKey」时才会被写出来，所以下一次新增还会踩 | 实测 |

> **DRIFT-044 的差异原因未逐条核对。** 87 是 V2.3（2026-10-30）时 §4.8.4 列出的计划接口数；实现比计划多了 36 个，与技术路线 §二十六 自己登记的「PC 18→24、App 23→26」是同一类现象。**在把 87 改成 123 之前，要先把多出来的 36 条归因**（合同外新增？通用 auth 端点？CRUD 拆分？），否则只是把一个数字换成另一个数字。
>
> **DRIFT-045 同理**：78 → 94 的 16 个增量应该能逐条归因（段 5/6/7 各自新增了多少码）。改数字前先归因，否则下一个人还是不知道该信哪个。

---

## 八、关闭记录

| ID | 关闭日期 | 关闭依据 | 提交 |
|---|---|---|---|
| DRIFT-043 | 2026-11-13 | 迁移 `V712__fix_balance_column_name.sql` 把 `user_balance.balance_cents` 改名为 `available_cents`（DROP CHECK → CHANGE COLUMN → ADD CHECK；**实测 MySQL 8.0.44 不允许直接重命名被 CHECK 引用的列**，报 ERROR 3959）。同步：实体 `UserBalance`、`UserBalanceMapper.xml`、7 个 Service 的 13 处访问器、5 个测试、`data-integrity.sql` A08/A09、`verify-balance.ps1`、`purge-test-data.ps1`、33 处文档。回归：后端 532 用例全绿 + 巡检 A08/A09/A10 全 0 + `verify-balance.ps1` 两条恒等式差异 0 | 见 CHANGELOG 2026-11-13「DRIFT-043 落地」条目 |
| DRIFT-014 | 2026-11-11 | 删除 `SocialQueryService.unfavorite` 的「先查任意用户再判 403」预检（它既拦不住越权——真正的 UPDATE 已带 `user_id`——又会误伤合法用户）。回归：`SocialFavoriteMultiUserTest` 3 例全绿（含「别人收藏着时我取消自己的不报 403」）。证据见 `docs/acceptance/guard/G6-后端边界测试.md` | 见 CHANGELOG 2026-11-11 G6 条目的提交 |
| DRIFT-016 | 2026-11-13 | `OrderCreateExecutor.create` 新增「任务 3：不许给自己下单」——比较 `lawyer.user_id` 与下单人 `userId`（**不是 `lawyer.id`**），命中抛 `405031 TRADE_SELF_ORDER_FORBIDDEN`；null 的 `lawyer.user_id` 不拦（历史数据保护）。回归：`OrderCreateExecutorTest` 3 例（拒自下单 / 别人下单放行 / `user_id` 为空不 NPE）+ 全量 537 用例全绿 | 见 CHANGELOG 2026-11-13「DEF-014 / DEF-015」条目 |
| DRIFT-022 | 2026-11-13 | `AccountSecurityService.cancelAccount` 新增「任务 2：在途订单校验」——同时查「作为下单人」`orders.user_id` 与「作为服务方」`orders.lawyer_id`，口径为 `OrderStatus.isTerminal()` 的补集（pending / paid / in_service / refunding / refund_rejected 五态），命中抛 `402012 AUTH_CANCEL_HAS_ACTIVE_ORDER`。回归：`DeactivateWithActiveOrderTest` 4 例（服务中拦 / 待支付拦 / 律师侧已支付拦 / 只有已完成放行） | 同上 |
| DRIFT-051 | 2026-11-13 | `check-i18n.ps1` 的 `Read-LangFlat` 与 `extract-i18n.mjs` 的 `readLang` 同时对 `error` namespace 跳过前缀（`error.json` 的顶层 key 本身就是完整 msgKey）。改后 `.i18n-pending.json` 里是 `error.auth.cancel-has-active-order` 而不再是 `error.error.auth.*`；`docs/facts/i18n-keys.json` 重新生成，`registeredTotal = computedTotal = 6`、`consistent: true` | 同上 |

> **关闭时必须三条齐全**：日期 / 可复核的关闭依据 / 提交号。**只写「已修复」不算关闭。**

---

## 九、本表自身的已知局限

1. **51 条不是全部。** D1 组覆盖「第三方验收报告提到的问题」+「代码通读中发现的同类问题」；D2 组覆盖一次全仓盘点；D3 组是跑脚本量出来的。**没有做过全库漂移扫描**，那是 G2/G4 的事。
2. **D2 组的 17 条里 15 条是 `待复核`**：它们来自盘点报告的二手结论，我只亲自复核了 DRIFT-026（缺译清零）与 DRIFT-032（`biz_type` 9 值）两条。**引用 D2 组前先验。**
3. **DRIFT-011 / DRIFT-012 的处置值可能被改成 `改文档`**：如果产品决定「法院名称本来就不显示」，那就不是补写入路径而是改文档。**处置值可改，但必须在 CHANGELOG 记明是谁拍的。**
4. **两张表之间可能串门。** 例如 DRIFT-011（`court_name` 永远为空）如果最后决定"这个字段不做"，它就该变成 GAP 移进 `DEFERRED.md`。**串门不丢人，但必须在 CHANGELOG 写清为什么改归类别。**
5. **同一件事可能在本表和 `DEFERRED.md` 各有一份，且看一边看不出另一边**（2026-11-13 实测）：DRIFT-016（下单允许自己向自己下单）与 DEF-014 是同一件事，DRIFT-022 与 DEF-015 也是。前者说「文档没说禁止」，后者说「代码没实现」，两个角度都成立，所以两边都登记了——**代价是读 DRIFT 的人不知道 GAP 台账里已有它，读 DEFERRED 的人也不知道漂移表里已经拍过板**。现在两处已互相引用并同步关闭，但**这个结构性问题没有根治**：可选的修法是给两张表加一列「对照 ID」，或在 `DEFERRED.md` 每条注明「对应 DRIFT-xxx」。下次整理台账时先定这个，不要继续各自加行。
