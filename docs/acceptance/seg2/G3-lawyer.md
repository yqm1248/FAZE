# 第 2 段 · G3「律师侧与展示」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze` / Redis db1）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G3（4 个任务）、§四 `2-A2` / `2-A4`
> 说明：本文件是**施工时实测记录**，不是计划复述。每条都附真实 API 返回或库内数据。
> 本节所有请求都由脚本直连 `http://127.0.0.1:8081`（真实进程），SQL 直连 `faze` 库，无 mock。

---

## 一、交付物

| 任务 | 交付 | 提交 |
|---|---|---|
| G3.1 律师列表 | `GET /app/lawyers`（分类 / 搜索 / 排序 / 分页）；`LawyerMapper.xml`（`JSON_CONTAINS` + `ORDER BY` 白名单 `<choose>`）；`LawyerBrowseService` / `LawyerBrowseConverter`；`sys_config.lawyer_specialty_enum`、`lawyer_list_sort`；`LawyerListPage.tsx`（13-lawyer-list） | `6d0e7f2` |
| G3.2 律师详情 / 评价 / 关注 | `GET /app/lawyers/{id}`、`GET /app/lawyers/{id}/reviews`、`POST\|DELETE /app/lawyers/{id}/follow`；`LawyerFollowService`；`LawyerDetailPage.tsx`（14-lawyer-detail） | `4908f74` |
| G3.3 律师侧订单 | `GET /app/lawyer/orders`、`POST /app/orders/{id}/accept\|reject\|complete`；`OrderActionResolver.resolveForLawyer`；`OrderRefundService.settleRefund`（拒单退款与超时自动退共用一份实现）；`LawyerOrdersPage.tsx`（64-lawyer-orders） | `bf9cf1a` |
| G3.4 律师工作台 | `GET /app/workbench/summary`、`PUT /app/workbench/online-status`、`PUT /app/workbench/service-settings`；`OrdersMapper#selectWorkbenchStats`；`WorkbenchPage.tsx`（62-workbench） | `bf9cf1a` |
| 段 2 G2.4 遗留缺陷：定时任务静默不执行 | `@EnableScheduling` 移到主类、删自定义 `taskScheduler`、`ProbeController` 加 `@Qualifier`、`spring.task.scheduling.*` 配置、扫描器启动日志 | `1186175` |
| 缺陷：服务设置「部分更新」误清字段 | 价格条件 `set`、开关 `resolveSwitch`（null 保持现值）、`LawyerWorkbenchServiceTest` 6 项 | `ba8fd35` |
| 缺陷：分类 tab 映射词表与入驻页标签不一致 | `lawyer_specialty_enum` 改用入驻页标签原词 | `49622df` |
| 顺带修：`OrderListPage` 未使用 `toast`（G3.1 提交带入的 eslint 红） | 删声明与 import | `72cbc86` |

---

## 二、G3.1 律师列表（真实 API）

测试数据：`lawyer.id=2`（user_id=1，验收律师甲，approved / online，图文 6000 / 电话 8000 / 文书 20000，三个服务都上架，`specialties=["劳动争议","合同审查"]`）、`lawyer.id=11`（价格 0，offline）。

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 不传分类 | 返回全部已审核律师 | `code=200` `total=2` `ids=[2,11]` |
| 2 | `category=民事纠纷` | 含擅长「劳动争议」的律师 | `total=2`（2、11 都命中）✅ 扩展映射生效 |
| 3 | `category=合同纠纷` | 含擅长「合同审查」的律师 | `total=2`（2、11 都命中）✅ |
| 4 | `category=刑事辩护` / `婚姻家事` | 库里没有这类律师 → 空 | `total=0` |
| 5 | `sort=online_rating` | 在线优先 + 评分 + 订单数 | `total=2`，在线律师排在前面 |
| 6 | `sort=bogus`（非法排序键） | 不报错、回退默认排序 | `total=2`（走白名单默认分支，未拼进 SQL） |
| 7 | `keyword=验收` | 按姓名 / 地区模糊搜 | `total=1`（命中「验收律师甲」） |
| 8 | 卡片字段 | 无敏感字段 | 只有 `id/realName/lawFirm/avatarUrl/rating/reviewCount/orderCount/onlineStatus/specialties` 与三个价格；**无手机号、无身份证** |

### 关键缺陷（本组验收中发现并修掉）

- **初版映射把「分类 tab 名」当成了「擅长领域标签值」**：写的是 `民事纠纷/劳动纠纷/合同纠纷`，而入驻页（段 1）提交的 10 个标签真值是 `民商事纠纷/劳动争议/合同审查/…`。
  后果：`category=民事纠纷` 返回 `total=0`（一条都匹配不上）。修法见提交 `49622df`，映射改为标签原词，且注释写明「数组元素必须与入驻页标签原词一致」。
- **种子数据不覆盖 `config_value`**：`seg2_config.sql` 用 `ON DUPLICATE KEY UPDATE` 只刷说明，库里已存在的旧值必须手工 `UPDATE` 才会变。本次已手工同步，这一步**必须写进部署清单**，否则线上仍是错映射。

---

## 三、G3.2 律师详情 / 评价 / 关注

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 登录查详情 `GET /app/lawyers/2` | 资料 + 价格 + 上下架 + `followed` | `realName=验收律师甲` `rating=5.0` `reviewCount=0` `orderCount=1` `consult/phone/document=6000/8000/20000` `service*On=true` `followed=false` |
| 2 | 不存在的律师 | 401004 | `code=401004` |
| 3 | 非法 id（`abc`） | 401003 | `code=401003` |
| 4 | 评价列表 `GET /app/lawyers/2/reviews` | 分页结构，匿名评价不返回昵称 | `total=0`（G5 才有评价数据，结构正确） |
| 5 | 关注 | 200，`followed` 变 true | `code=200` → `followed=true` |
| 6 | 重复关注 | 幂等 200 | `code=200` |
| 7 | 取关 | 200，`followed` 变 false | `code=200` → `followed=false` |
| 8 | 未登录关注 | 安全链拦截 | HTTP 401（`402003`） |

### 偏差（已登记）

- 设计稿 `14-lawyer-detail.html` **只有**资料 / 擅长 / 简介 / 联系方式，**没有价格区与评价区**；工作流 G3.2 要求「价格」与「评价列表分页」（后者是 `2-A4` 判据之一），故按工作流补齐，视觉沿用同一套卡片范式。
- 「立即咨询」= 下单，不是进会话（拍板第 6 项「下单与会话解耦」）；设计稿 `href=17-chat` 是原型简化。

---

## 四、G3.3 律师侧订单（接单 / 拒单 / 完成）

测试账号：用户 `app_user.id=3`（手机号 139****0009，余额 31318 分 + 冻结 2000 分）；律师 `app_user.id=1` → `lawyer.id=2`。
支付密码在验收前经「忘记支付密码 + 短信固定码 123456」重置为 `111111`（dev 环境 `sms_fixed_code_enabled=true`）。

### 4.1 接单 → 完成链路（订单 5）

| # | 场景 | 实测 |
|---|---|---|
| 1 | 用户下单（consult_text，律师 2） | `code=200` `id=5` `amount=6000` `status=pending` `actions=[pay,cancel]` |
| 2 | 余额支付（支付密码 111111） | `code=200`；库内 `paid_at=2026-11-06 13:44:52`（UTC） |
| 3 | 律师侧列表 `GET /app/lawyer/orders?status=paid` | `total=1`，命中订单 5，`actions=[accept,reject]`，`userNickname=用户0009` |
| 4 | 工作台看板（接单前） | `todayOrderCount=5` `pendingAcceptCount=1` `inServiceCount=0` `todayIncomeCents=0` |
| 5 | 接单 `POST /app/orders/5/accept` | `code=200` → `status=in_service` `acceptedAt=2026-11-06T13:44:52` |
| 6 | 用户侧详情（接单后） | `actions=[enter_chat, refund]` |
| 7 | `lawyer.order_count` | `0 → 1`（原子自增 SQL） |
| 8 | 看板（接单后） | `pendingAcceptCount=0` `inServiceCount=1` `todayIncomeCents=5100` |
| 9 | 结束服务 `POST /app/orders/5/complete` | `code=200` → `status=completed` `finishedAt=2026-11-06T13:44:52` |
| 10 | 用户侧详情（完成前） | `actions=[review]`（可以评价了） |
| 11 | 看板（完成后） | `inServiceCount=0` `todayIncomeCents=5100` `totalIncomeCents=5100` |

库内 `order_status_log`（订单 5，逐条）：

```text
pending          ← null         operator=user   id=3  「用户下单」
paid             ← pending      operator=user   id=3  「用户余额支付」
in_service       ← paid         operator=lawyer id=1  「律师接单」
completed        ← in_service   operator=lawyer id=1  「律师结束服务」
```

> `2-A2` 第 2 步要求 `order_status_log` 新增 `PAID→IN_SERVICE` 且 `operator_type='lawyer'` —— **已满足**。

### 4.2 拒单 → 自动全额退款（订单 6 / 7 / 8）

同一场景连续执行 3 次（脚本重试），三次结果一致，取订单 8 展示：

| # | 场景 | 实测 |
|---|---|---|
| 1 | 下单 document（20000）+ 支付 | 余额 `31318 → 11318`（可用）；`payment_record` 正行 `+20000` `BAL-585` |
| 2 | 律师拒单（带原因） | `code=200` → `status=refunded`，用户侧 `actions=[]` |
| 3 | 余额恢复 | `11318 → 31318`（可用） |
| 4 | `refund_record` | `id=3 order_id=8 amount=20000 status=refunded transaction_id=BAL-586` |
| 5 | `payment_record` 两行 | `+20000 BAL-585`（支付）、`-20000 BAL-586`（退款）→ `SUM=0` |
| 6 | `balance_flow` | `consume -20000`（balance_after=13318）、`refund +20000`（balance_after=33318） |
| 7 | `order_status_log` | `paid→refunding`（lawyer，原因带拒单理由）、`refunding→refunded`（system，「退款到账」） |

**关键口径**：`balance_flow.balance_after` 记的是**总余额（可用 + 冻结）**，不是可用余额。
例：支付前 `available=39318, frozen=2000, total=39318`（当时无冻结），支付 6000 后 `balance_after=33318`；
当前 `available=31318, frozen=2000, total=33318`。`GET /app/wallet/balance` 同时返回三个字段
（`availableCents=31318` / `frozenCents=2000` / `totalCents=33318`）。
`2-A1` 第 4 步「`balance_after = B0 - amount`」中的 `B0` 必须取 `totalCents`，否则会差一个冻结额——
这是**口径说明，不是缺陷**。

### 4.3 权限与状态机

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 非律师账号查律师侧列表 | 403 / 401008 | HTTP **403** `code=401008` |
| 2 | 接单不存在的订单 | 405002 | `code=405002` |
| 3 | 接单已完成订单（5） | 405003 | `code=405003` |
| 4 | 接单已取消订单（1） | 405003 | `code=405003` |

归属校验一律进 SQL（`id + lawyer_id + deleted_at IS NULL`），查不到就 405002；不做「先查出来再比对」。
律师身份一律从 token 的 `userId` 反查 `lawyer.id`，接口不接受任何 `lawyerId` 参数。

---

## 五、G3.4 律师工作台

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 看板 `GET /app/workbench/summary` | 统计 + 在线状态 + 价格与开关 | `todayOrderCount=8` `pendingAcceptCount=0` `inServiceCount=0` `todayIncomeCents=5100` `totalIncomeCents=5100` `onlineStatus=online` `consultPriceCents=6000` `serviceTextOn=true` |
| 2 | 「今日」口径 | 按 `Asia/Shanghai` 切日 | 今日订单 8 = 当天创建的全部订单（含 4 笔已取消） |
| 3 | 预计收入口径 | `lawyer_income_cents` = 金额 − 15% 平台费 | 6000 − 900 = **5100** ✅ |
| 4 | 改在线状态 `busy` | 库内同步 | `code=200`，`lawyer.online_status='busy'` |
| 5 | 非法在线状态 `xxx` | 401001 | `code=401001` |
| 6 | 改回 `online` | 库内同步 | `code=200`，库内 `online` |
| 7 | 服务设置只传图文价格 + 图文开关 | 其它价格与开关**不受影响** | 传 `{consultPriceCents:6500, serviceTextOn:true}` → `consult=6500`，`phone=8000`、`doc=20000` 不变，`phone_on/doc_on` 仍为 1 ✅（修复后） |
| 8 | 0 元价格 + 上架 | 强制下架该服务，其它不动 | 传 `{consultPriceCents:0, serviceTextOn:true}` → `consult=0`、`text_on=0`，`phone/doc` 不变 |
| 9 | 负数价格 | 405006 | `code=405006` |

### 缺陷（本组验收中发现并修掉）

`PUT /app/workbench/service-settings` 初版用**无条件 `set`** + `Boolean.TRUE.equals(null)`：
只传图文价格时，电话与文书的价格被写成 NULL、两个上架开关被置 0——**"没传"被当成"关掉"**，
两个已上架服务被静默下架。修法见提交 `ba8fd35`（价格条件 `set`、开关 `resolveSwitch` 保持现值、
价格有效性用「本次传了用新值，没传用库内现值」判断），并补 6 项单测锁死该行为。

---

## 六、段 2 G2.4 遗留缺陷：`@Scheduled` 静默不执行（已修）

**现象**（2026-11-06）：`OrderCloseTask.scan()` 一次都没跑过。下单 15 分钟后订单仍是 `PENDING`、
`task_queue` 一直 `pending`，而日志里**没有任何异常**，看起来像「代码没被调度」。

**根因链（三处叠加）**：

1. 自定义 `ThreadPoolTaskScheduler` bean + `@EnableScheduling` 一起写在 `ThreadPoolConfig`（普通 `@Configuration`）里，`@Scheduled` 静默不执行；
2. 把 `@EnableScheduling` 移到主启动类后，Boot 的 `TaskSchedulingAutoConfiguration` 才生效，容器里同时存在 `applicationTaskExecutor` 与 `taskScheduler` 两个 `TaskExecutor`；
3. `ProbeController` 用裸 `TaskExecutor` 注入 → `expected single matching bean but found 2` → 启动直接失败。

**修法**（提交 `1186175`）：

- `@EnableScheduling` 放主启动类；
- 删自定义 `taskScheduler` bean，调度线程数改由 `spring.task.scheduling.pool.size=10` / `thread-name-prefix=faze-task-` 提供；
- `ProbeController` 注入改 `@Qualifier("applicationTaskExecutor")`；
- `OrderCloseTask` 加 `@PostConstruct` 启动登记日志（**`@Scheduled` 失效不报错，必须有一行日志证明调度器活着**）。

**修复后实测**（真实 API + 真实库，不人工干预）：

```text
启动日志：订单超时扫描器已注册：fixedDelay=30000ms initialDelay=15000ms worker=order-close
orders 1/3/4：status=cancelled，cancelled_at=2026-11-06 13:27:07（UTC）
task_queue：三条 order-close 记录 status=done
order_status_log：三条 system / 「超时未支付，系统自动关闭」
```

---

## 七、锚点判定

| 锚点 | 本组覆盖部分 | 判定 |
|---|---|---|
| `2-A2` 接单与会话 | **前半段已验**：律师侧看到订单 → 接单 → `orders.status=in_service` + `accepted_at` 非空 + `order_status_log` 增 `paid→in_service`（`operator_type=lawyer`）→ 用户端状态变「律师已接单」 | **前半段通过；后半段（IM 消息双向 ≤2 秒）未验**——依赖 G6（IM 会话），段 2 尚未开工。**整条不许判通过** |
| `2-A4` 状态机可追溯 / 拒绝非法 | **律师侧分支已验**：4 条迁移有流水；已完成 / 已取消订单再接单均 405003；他人订单 405002 | **律师侧通过**；退款 / 评价分支属 G4 / G5 |
| `2-A1` 下单扣款 | G3 复验了支付（用于造 PAID 单）：余额减少、`payment_record` 正行、`balance_flow` 消费行 | 完整判定见 `docs/acceptance/seg2/G2-order-pay.md` |

---

## 八、偏差与遗留（如实记录）

1. **工作流没给「律师侧订单列表」端点**（计划缺口）。本段补 `GET /app/lawyer/orders`；不放在 `GET /app/orders/{id}` 前缀下，避免 OpenAPI 上出现「`id` 可以是 `lawyer`」的歧义描述。
2. **`category` 查询参数用的是中文分类名**（`民事纠纷` / `刑事辩护` / `婚姻家事` / `合同纠纷`），不是稳定 code。当前功能可用（前端 `value` 硬编码中文，与 i18n 文案分离），但接口契约不理想：URL 里出现中文、分类概念没有 code 化。**挂账**，段 2 收口时决定是否改成 `civil/criminal/family/contract`（改则前后端一起改）。
3. **设计稿与工作流的差异**：`14-lawyer-detail.html` 无价格区 / 评价区（按工作流补齐，见三）；`64-lawyer-orders.html` 只有 4 档筛选（补「全部」档，否则查不到历史单）。
4. **分类映射的部署注意**：`seg2_config.sql` 是种子且 `ON DUPLICATE KEY UPDATE` **不覆盖 `config_value`**，已初始化的库必须手工 `UPDATE` 一次，否则线上仍是旧映射（本次踩过，见二）。
5. **`2-A3`（杀进程收推送）**：浏览器口径不可达，按工作流 §零.5 挂段 8 真机补验，**不许写成通过**。
6. **`2-A10`（真 RTL）**：RN Web 的 `I18nManager` 是空实现，改判代码审查 + 近似布局，挂段 8。

---

## 九、自动化测试

- `mvn test` **145 项全绿**（0 失败 / 0 错误 / 0 跳过），26 个测试类。
  - 新增 `LawyerOrderServiceTest` 7 项（接单 / 拒单退款 / 结束服务 / 越权 405002 / 状态不符 405003 / 非律师 401008 / 列表）
  - 新增 `TaskQueueIntegrationTest` 2 项（任务领取与完成、失败退避与死信）
  - 新增 `LawyerWorkbenchServiceTest` 6 项（部分更新不清价格、开关保持现值、0 价强制下架、负数 405006、非律师 401008、非法在线状态 401001）
- 前端门禁四项通过：`tsc --noEmit`、`eslint --max-warnings=0`、`check-raw-text`（86 个文件 0 命中）、`check-i18n`（四目录同构 + 缺译已登记 + 占位符一致）。
- i18n：新增 `lawyer.orders.*` / `lawyer.workbench.*` 共 33 个 key，仅 `zh-Hans` 有译文，其余三语登记进 `resources/.i18n-pending.json`（段 8 收口时必须清空）。
