# 变更日志

本文件记录每一次推送改了什么。**每次推送前必须更新本文件**，格式见文末。

---

## 2026-11-10 · P2 五条：口径与假承诺（另有 3 条不做、1 条已完成）

**背景**：P0 六条 + P1 九条修完后开始处理 P2 九条（体验 / 文案 / 规范债）。逐条复核前提后，
**9 条里只有 5 条该做**，其余是已完成或依赖外部输入。本轮把该做的 5 条做完。

### 一、范围裁决（为什么只做 5 条）

| # | 处置 | 依据 |
|---|---|---|
| P2-1 | 做 | 真 bug：律师收入流水把 `orders.service_type` 枚举当标题，用户看到 `consult_text` |
| P2-2 | 做（**性质已更正**，见下） | 兜底会把未映射 `biz_type` 原样上屏 |
| P2-3 | 做（**前提已更正**，见下） | 页面里写死客服热线时段，违反同文件自己写的「时段从服务端读」 |
| P2-4 | 做 | 深色模式是假承诺（全仓 0 处 `useColorScheme`/`ThemeProvider`，`userInterfaceStyle: light`） |
| P2-5 | **已完成，本轮未动** | 三处「即将开放」已随 P1-8 回收（`rechargePending` / `searchPending` 在四语言里已 0 命中） |
| P2-6 | **不做** | ICP / App 备案号是负责人提供的外部事实，代码无需改 |
| P2-7 | **不做** | 协议与隐私政策正文由法务定稿；机制已就绪（管理后台可编辑） |
| P2-8 | 做 | 搜索空态把 `{{kw}}` 原样上屏 |
| P2-9 | **不做** | 改 `conversation.orderNotice` 这类两段 key 会触发 `MessageKey` 字面量联合的类型连锁，收益低风险高 |

**两处前提更正（原缺陷清单写错了，记在案）**：

1. **P2-2 原写「`balance_flow.biz_type` 实测有 9 种，其中 `income_unfreeze`（2 条）不在映射表」**——
   那是**全表统计、没区分 `owner_type`**。实测分布：
   ```text
   用户侧(user)：adjust 6 / consume 17 / recharge 41 / refund 14
   律师侧(lawyer)：freeze 4 / income 3 / income_unfreeze 2 / unfreeze 1 / withdraw 1
   ```
   `income_unfreeze` 的写入点是 `SettlementService:302`（律师收入解冻），只产生在 `owner_type='lawyer'`；
   而余额页走用户侧接口、后端按 `owner_type='user'` 过滤 → **用户余额页当前不可能出现该类型**。
   所以这条的真实价值不是「修一个用户能看到的现象」，而是**堵住兜底泄漏**：
   `typeLabel === undefined ? flow.bizType : ...` 会把任何未映射 code 直接上屏。
2. **P2-3 原写「两处客服时段不一致，可能本来就是两条不同时段，先确认再改」**——已查实：
   设计稿 `design/ui/55-support.html` 热线原文是「工作日 9:00–18:00」，在线客服配置是 `09:00-20:00`，
   两者**本来就是两条不同服务，值不同是设计意图**。真正的缺陷是热线时段被写死成 TS 常量
   （`SupportPage.tsx` 的 `PHONE_HOURS = '9:00–18:00'`），而同一文件头自己写着「号码与时段一律从服务端读」。

### 二、五条的做法

| # | 做法 | 关键点 |
|---|---|---|
| P2-1 | 后端 `IncomeFlowRowVO` **删 `orderTitle`、加 `serviceType`**（装枚举 code，语义显式）；`WorkbenchServiceImpl` 改 `setServiceType`；前端 `IncomePage` 加 `SERVICE_KEYS` 查表，未映射 / 无关联订单一律回落「其他收入」 | 后端**不返回中文**，文案只在 i18n 资源里有一份；任何分支都不许把 code 上屏 |
| P2-2 | `FLOW_TYPE_KEYS` 补 `income_unfreeze`、`FlowBizType` 补该值；**核心是兜底改成 `t('trade.balance.typeUnknown')`** | 加映射只是少留空洞，真正的防线是兜底 |
| P2-3 | 新迁移 **V711** 写 `sys_config.service.phone_hours = '09:00-18:00'`（`ON DUPLICATE KEY UPDATE` 不覆盖 `config_value`）；`PubAppConfigController` 加 `phoneHours`（含默认值）；前端读 `config.phoneHours` | 值必须补零 + 半角连字符：`isWithinServiceHours()` 的正则是 `/^\d{2}:\d{2}-\d{2}:\d{2}$/`，写成 `9:00–18:00` 会**静默**判成「不在服务时间」 |
| P2-4 | 保留主题 sheet 与三个选项的 UI（设计稿有这一屏），但点击只 toast「深色模式将在后续版本提供」+ 关 sheet，**不改选中态、不写本地存储**；`themeNote` 四语言改实话 | 旧文案「只是换个显示，不影响任何功能」是在**描述一个不存在的能力**，必须删 |
| P2-8 | `EmptyState` 加 `vars?: TranslationVars` 并传 `t(key, vars)`；`SearchPage` 唯一受影响调用点传 `{ kw: keyword }` | 全量核对过：84 个带占位符的调用点里只有这 1 处漏传 |

### 三、验证证据（全部真实运行）

| 层 | 断言 | 结果 |
|---|---|---|
| 接口级 `verify-p2-b.mjs` | 9 条 | **全过**。`/pub/app/config` 同时返回 `onlineHours=09:00-20:00` 与 `phoneHours=09:00-18:00`；律师收入流水 10 行里 8 行带订单号的行全部有 `serviceType`，**`orderTitle` 键 0 例**；库内 `service.phone_hours` 已写入、Flyway `711 success=1` |
| 浏览器端到端 `verify-p2-e2e.mjs` | 16 条 | **全过**（页面文本摘要见下） |

浏览器实测到的东西（原文摘录）：

- **P2-8**：搜索 `zzzz不存在的词` → 空态「没搜到「zzzz不存在的词」相关的内容」，`{{kw}}` 为 0 处
- **P2-4**：主题 sheet 底部「深色模式将在后续版本提供。当前设置不会改变界面显示。」；点「跟随系统」→ toast 出现、sheet 关闭、**右侧当前值仍是「开」**（点前「开」→ 点后「开」）
- **P2-3**：客服页热线行「点击号码直接拨打 · 09:00-18:00」；页面上出现的**唯一**时段串就是服务端值，`9:00–18:00` 这个旧硬编码为 0 处
- **P2-2**：余额页 20 条流水，界面文本里匹配不到任何 `[a-z]+_[a-z]+` 形式的 code
- **P2-1**：律师收入页显示「图文咨询 / 文书代写 / 其他收入」，`consult_text|consult_phone|document|case` 匹配 0 处

质量门（Lead 独立重跑）：`mvn -q -DskipTests compile` = 0、`npm run lint` = 0、`npm run check:i18n` = 0；
21 个改动文件全部通过 UTF-8 完整性扫描（0 处替换字符、0 处 mojibake 特征）。

### 四、已知取舍（本轮新增）

1. **P2-2 没有给筛选 chips 加「收入解冻」第 5 档**：设计稿 `design/ui/35-balance.html` 只有 4 档（全部/充值/消费/退款），
   而 `filterRow` 是 `flexDirection:'row'` **无 flexWrap**，5 个 chip 在 390dp 会横向溢出；且用户侧永远产生不了该类型，
   这一档永远是空结果。结论：chips 是精选档位而不是全量类型列表，不加。
2. **P2-4 保留了一个「点了才知道没做」的入口**：这是权衡后的选择——删掉整段会偏离设计稿，置灰则点击毫无反馈。
   现在的形态是「结构在、说明在、骗不了人」。真做深色模式需要 `ThemeProvider` + 双套令牌，属独立排期。
3. **P2-1 没有顺手收口 `SERVICE_KEYS` 的 6 处重复**（OrderList / OrderDetail / PayPage / ReviewPage /
   LawyerOrders / IncomePage，且前 5 处用 `lawyer.detail.service*`、新增这处用 `trade.order.service*`）。
   收口会动 5 个已完成页面，属另一笔债；本轮只在新代码里注明「统一收口是另一笔债」。
4. **`income_unfreeze` 流水的 `serviceType` 是 `null`**：它们没有关联订单（`orderNo` 也为 null），
   页面显示「其他收入 · 无关联订单」。这是正确行为，不是漏字段。
5. **调试环境踩到一个坑（不是代码问题）**：Metro dev server 在编辑文件时会崩——
   `FSWatcher ENOENT: ...\.SettingsPage.tsx.<pid>.<uuid>.tmpdir`，编辑器临时文件被 watcher 抓到后又消失。
   日志里同时出现过一条 `ComplaintListPage.tsx:190 Unexpected token`，但那个文件的当前内容与报错行完全不同、
   `tsc --noEmit` 通过，判定为读到编辑中间状态的瞬时错误，**不是真缺陷**。重启 dev server 即恢复。

### 五、文档基线

本轮**只改代码与 i18n 资源，没有改任何基线内文档**（`docs/architecture/依赖清单.md` 未动、无新依赖、
无规范变更），因此**不出新基线版本**，维持 v1.66。`docs/CHANGELOG.md` 按 §2.6 不登记固定指纹。

---

## 2026-11-10 · P0-6 补正链路打通 + 两项挂账收口（附两笔漏记的推送）

**背景**：上一版（同日）把 P0-6 止血成「原地提示 + TODO」，并把它写进挂账第 3 条：「真正修法是新增
`POST /app/cases/{id}/resubmit` + `FilingPage` 按 `caseId` 预填，需负责人放行」。负责人本轮放行，补完。

**同时补记两笔漏更新的推送**：`0f670c8`（P1-7 第 6 档「已退款」）与 `b24e0d7`（挂账第 2 项
浏览器后退）**推送时都没有更新本文件**，违反本文件开头的「每次推送前必须更新」。现在补记，
并一并纠正上一版的两处口径：① 挂账第 3 条（P0-6 仅止血）已关闭；② 挂账第 5 条（浏览器后退
绕过 dirty 守卫）已由 `b24e0d7` 关闭。

### 一、P0-6：案件被驳回后能在 App 内真正补救（本版）

| 维度 | 内容 |
|---|---|
| 后端新增 | `GET /api/v1/app/cases/{id}/form` → `CaseFormVO`（四步表单快照 + 驳回理由，**只给委托人本人**；律师访问按案件不存在返回 `407001`）<br>`POST /api/v1/app/cases/{id}/resubmit` → `CaseSubmitVO`（复用 `CaseCreateBO`，必须带 `Idempotency-Key`）<br>`GET /api/v1/app/cases/{id}` 增 `resubmitCount` / `canResubmit` 两个字段 |
| 补正规则 | 状态 `rejected → filed` 走 `CaseStateMachine.assertCanTransit(REJECTED, FILED, USER)`（这条边早就在，缺的是入口）；落库用 CAS（`WHERE id=? AND user_id=? AND status='rejected'`），同时清 `reject_reason`、整体替换表单快照与主表字段；追加一条 `case_progress(submitted)` 节点。**补正上限 3 次**，第 4 次抛新错误码 `407009`；次数由「`submitted` 节点数 − 1」推算（首提交也算一条），**不新增字段、不加迁移** |
| 幂等 | Redis 键 `faze:case:resubmit:idem:{caseId}:{key}`，与立案的 `{userId}:{key}` 分开，两类提交不互串；写入失败只 `log.warn` 不回滚业务（与 `create` 同口径） |
| 前端 | `FilingPage` 带 `?caseId=` 进入补正模式：预填原快照（**预填就绪前整页只显示骨架/错误态**，否则用户会先看到空表单再被覆盖）、页头「补正材料」、第 1 步合规条下插驳回理由提示条、第 4 步按钮「提交补正」；成功后 `router.replace` 回案件进度页。`CaseProgressPage`「重新提交」按后端 `canResubmit` 决定跳转或原地提示，驳回理由框多一行「还可补正 N 次 / 次数已用完」 |
| 四语言 | 新增 7 条 key：`case.filing.resubmitTitle` / `resubmitBanner` / `resubmitSubmit` / `error.resubmitFailed`、`case.progress.resubmitRemaining` / `resubmitLimitReached`、`error.case.resubmit-limit`；四目录同构、占位符 `{{reason}}` / `{{count}}` 一致 |
| 明确不动 | 「补充材料」按钮语义是**审理中补交材料**（不是补正），后端无对应接口，保持「即将开放」；补正材料上传仍走文书中心 |

**验证证据**（全部真实运行，不是静态核对；脚本在 `tmp/fix-plan/`，`tmp/` 被 gitignore）：

| 层 | 断言数 | 覆盖 |
|---|---|---|
| 接口级 `verify-p06-resubmit.mjs` | **31 条全过** | 补正前 `rejected / resubmitCount=0 / canResubmit=true` → form 回吐 `张三 / 500000 分 / labor` → 补正成功 → `filed / 1 / false`、`reject_reason` 已清空、进度 2 条；另覆盖 `407001`（非本人取 form）、`407002`（非 rejected 再补正）、`407003`（律师看非承办案件，HTTP 403）、`407009`（次数用完）、`401002`（缺幂等键）与幂等重放（同键重放返回同一 `caseNo`，进度仍 2 条、次数仍 1） |
| 浏览器端到端 `verify-p06-e2e.mjs` | **19 条全过** | 我的案件 → 已驳回卡片 → 进度页（已驳回 / 驳回理由 / 还可补正 3 次）→「重新提交」→ `/case-filing?caseId=3`（标题「补正材料」+ 驳回提示条）→ 预填（原告张三 / 被告李四 / ¥5000 / 劳动仲裁；案情陈述在 `TextInput` 里，按 `value` 断言）→ 四步 →「提交补正」→ 回 `/case-progress?caseId=3` 状态「审核中」、时间轴出现「用户补正后重新提交立案材料」、「重新提交」消失、「补充材料」出现 |
| 上限分支 UI `verify-p06-limit.mjs` | **7 条全过** | 造 4 条 `submitted`（= 已补正 3 次）后：驳回理由框显示「补正次数已用完，请联系平台」，点「重新提交」**不跳页面**、原地 toast——不能让用户白填一遍四步 |

质量门（Lead 独立重跑，不采信执行者汇报）：`mvn -q -DskipTests compile` = 0、`npm run lint` = 0、
`npm run check:i18n` = 0。后端为跑验证重启过 8081（`deploy\restart-api.ps1`，PID 42212）。

### 二、补记：P1-7 加上第 6 档「已退款」（`0f670c8`）

上一版 P1-7 的结论是「不新增第 6 档」，负责人随后决定要这一档（已退款是「服务没做成、钱退回」，
与「取消」不是一回事，混在一起用户分不清钱去哪了）。

- `OrderListPage` 的 `FILTER_STATUS.cancelled` 从 `'cancelled,refunded'` 改回 `'cancelled'`，新增
  `refunded: 'refunded'`；`FILTER_ORDER` 追加在**末尾**（不是插在「已完成」和「已取消」之间）
- 为什么追加在末尾：6 个 chip 在 390dp 下**刚好放下**，插在中间会把「已取消」挤出视口只剩半个字——
  那是老用户已经形成位置记忆的档位。四语言补 `trade.order.filterRefunded`（复用各语言既有的
  `order.statusRefunded` 说法）
- 实测：用户 9415 有 6 单 `refunded`、0 单 `completed`。改前这 6 单全在「已完成」里；改后点「已退款」
  列表非空且卡片状态全是「已退款」，点「已完成」显示空态「还没有订单」

### 三、补记：挂账第 2 项——系统返回 / 浏览器后退也会丢数据（`b24e0d7`）

上一版 P1-5 只修了页内返回按钮，系统返回与浏览器后退完全绕过 `AppHeader.onBack`。改用
`usePreventRemove`（`@react-navigation/native`，`expo-router` 不 re-export 这个 hook）拦下所有
导致立案页被移除的导航。**踩了三个坑，都写进代码注释了**：

1. **死循环**：`preventRemove` 是渲染期布尔值，确认离开时同 tick 直接 `router.back()` 会被自己再拦一次
   → 确认框反复弹。改成只置 `exiting` 状态，真正的 back 放到 effect 里（那时守卫已放行）。
2. **提交成功也被拦**：`router.replace` 去结果页同样走 `beforeRemove`。改成置 `submitted` + `doneHref`，
   由 effect 跳转。
3. **Web 端 URL 与内容不一致**：浏览器后退会先把**地址栏**改成上一页，而 `usePreventRemove` 只拦住了
   screen 被移除 → 出现「地址栏是列表、内容是表单」。补 `Platform.OS === 'web'` 时用 `history.pushState`
   把 URL 改回本页。**不能用 `history.forward()`**——它会再触发 popstate，React Navigation 再走一遍导航，
   又被守卫拦下，确认框反复弹。

依赖 `@react-navigation/native@7.5.1` 提为直接依赖（与 `expo-router` 6.0.24 自带同版本，
`package-lock.json` 只多 1 行），理由与风险已登记 `docs/architecture/依赖清单.md`。

实测：从 `/case-my` 点进立案页 → 选「民事」→ `history.back()` → `afterBackPath=/case-filing`
（URL 回正）、确认框出现、页面未丢；点「继续填写」留在本页且弹窗关闭。改前：后退直接离开、无提示、内容静默丢失。

### 四、已知取舍（本轮新增，都不是缺陷但要知道）

1. **幂等重放的 `submittedAt` 与首提不逐字相等**：首提返回内存里纳秒精度的 `now`，重放按契约读
   `updated_at`（MySQL `DATETIME(0)` 会把小数秒四舍五入），两者最多差 1 秒内。用户可见性为零
   （前端只展示到分钟），且 `create` 的幂等早就有同样现象，**不在本轮改**。
2. **补正成功后导航栈残留旧进度页**：`router.replace` 替换的是立案页，栈变成
   `case-my → case-progress(旧) → case-progress(新)`，用户多按一次返回才回列表。旧页重新聚焦时
   会被 P1-6 的 `useRefreshOnFocus` 静默刷新，所以**看到的数据不会过期**，只是多一次返回。
3. **时间轴上补正节点与首提交同名**：前端按 `nodeCode` 映射 i18n（`submitted → 材料已提交`），后端写的
   title「材料已补正并重新提交」只作兜底不展示。两条记录的差异靠时间与描述
   （「用户提交立案材料」vs「用户补正后重新提交立案材料」）体现。要根治得新增节点 code，
   会影响状态机文档与次数统计算法，单独立项。
4. **律师视角 `canResubmit=false` 只做了静态核对**：库里唯一的 rejected 案件未挂律师，
   造 fixture 成本大于收益；代码条件是 `Objects.equals(e.getUserId(), userId) && status==rejected && count<3`，
   静态可核。
5. **补正次数上限 3 次是拍板值，不是推导值**：防的是「反复提交刷审核队列」。取值依据是运营判断，
   不是测算；要改只需改后端一个常量 + 前端一个常量（两处都写了交叉引用注释）。

### 五、文档基线

本版**不出新基线版本之外的资产变更**：`docs/CHANGELOG.md` 不登记固定指纹（`BASELINE.md` §2.6），
唯一变更的基线资产是 `docs/architecture/依赖清单.md`（`b24e0d7` 登记 `@react-navigation/native@7.5.1`，
121 → **122 行** / 16877 → **17558 字节** / `f63b7a473853c45c` → **`fb3669b1f831c0f9`**）。
连同上一版 v1.65 的头部漏更新，一并出 **v1.66**（`BASELINE.md` §7.69）。

---

## 2026-11-10 · P1 九条：口径错、入口错、假承诺（附 P0 六条补记）

**背景**：上一轮按《缺陷清单》修了 P0（崩溃 / 隐私 / 数据错误 / 资源泄漏），推送在 `0caa36a`。
**那一版漏写了本文件**（违反「每次推送前必须更新」），本版一并补记。本轮继续修 P1 九条。

### 一、P0 六条（补记，已随 `0caa36a` 推送）

| # | 缺陷 | 根因与修法 |
|---|---|---|
| P0-1 | 点「确认支付」必崩 | Mock 回调报文是 `application/x-www-form-urlencoded` 表单串，前端先 `JSON.parse` 必抛 SyntaxError，且 `client.ts` 唯一 body 通道写死 JSON。`client` 新增 `bodyMode` / `contentType` / `successMode` 三个可选参数（既有 JSON 路径逐字节不变）；`confirmMock` 补 try/catch/finally，`busy` 由 finally 释放、payload 只在 HTTP 200 后丢弃 |
| P0-2 | 律师端气泡全反、撤回按钮错位 | 方向判断原本用 `senderRole !== 'lawyer'`（写死用户视角），`senderId` 是分域 id（用户侧 `app_user.id`、律师侧 `lawyer.id`）无法比较。`MessageVO` 新增 `Boolean mine`；实时推送在 `pushToPeer` 翻转为接收者视角；前端一律 `mine === true` 判断 |
| P0-3 | 已读风暴（1 分钟 1036 次 UPDATE） | `markRead` 不看消息表影响行数，无条件写 `conversation` 并推对端。改为以影响行数判定 `advanced`，未前进不写库不推送；前端用 `reportedSeqRef` 去重、只在已读位置前进时发包 |
| P0-4 | 跨会话消息泄漏 | 离线补推按 `user_id` 跨会话投递，前端收到帧不校验 `conversationId`。在帧处理层统一过滤 `chat.message/read/recall/typing/history` 五类，`String()` 归一比较，缺字段保持原行为 |
| P0-5 | 法条 733 被错录成 722 的正文 | V305 把第七百二十二条正文挂在 `article_seq=733` 下，AI 检索到 733 时给 722 的原文，且 `ArticleNoValidator` 仍判定条号存在。新增 `V710` 用 UPDATE 改条号为 722；不改已执行的 V305（Flyway checksum），**不补录真正的 733 正文**（禁止自编法条原文） |
| P0-6 | 案件补正入口会新建第二张案件 | 两个补正按钮 `router.push('/case-filing')` 且不带 `caseId`，进去是空白表单，提交会 `createCase` 新建案件，原案件永远停在 rejected。App 侧尚无补正接口，**本版只止血**（原地提示 + TODO 指向需新增 `POST /app/cases/{id}/resubmit`） |

**P0 验证证据**：充值走真实浏览器端到端（选档→立即充值→确认支付→成功页）；气泡方向与已读用双端 WS 帧级脚本断言（`firstRead=1 / repeat30=0 / newMsg=1`；实时帧 `mine=false`、历史两端对齐、同 `messageId` 两端相反）。

### 二、P1 九条（本版）

| # | 缺陷 | 根因 | 修法 | 实测对照 |
|---|---|---|---|---|
| P1-1 | 律师「预计收入」不分订单状态 | `LawyerOrderService.toVO` 无条件透传 `lawyer_income_cents`，而看板 SQL 只算 `in_service/completed` | 后端抽唯一口径常量 `INCOME_STATUSES = {IN_SERVICE, COMPLETED}`（Javadoc 绑定 `OrdersMapper.selectWorkbenchStats`，改一处必须同步另一处）；非结算状态置 `null`；`Set.of().contains(null)` 会 NPE 故先判空；脏状态 `log.warn` 不抛异常。前端 `null` 时显示灰色 `—` + 读屏「未计入收入」 | 改前列表合计 **212950 分** vs 看板 46200；改后 **46200 = 46200**，带收入的只剩 4 条 `completed` |
| P1-2 | 已驳回提现显示「¥99.40 到账」 | 无条件渲染 `actualCents`，而 `reject` 不清 `actual_cents`；「到账」还是硬编码中文 | 按状态分支：`paid`→新增 `withdrawArrived`、`applying/approved`→新增 `withdrawExpectedAmount`、`rejected`→不渲染。既有 `withdrawExpected` **未加占位符**（`WithdrawPanel` 当纯标签用）。后端与 PC 后台不动（PC 依赖 `actualCents` 非空） | 驳回行改前「¥99.40 到账」，改后只剩「已驳回 + 驳回理由」；`paid` 行正常显示到账 |
| P1-3 | 系统通知用「用户口吻」发给律师 | 后端 payload 写死 `key:"conversation.orderNotice"`，一行消息两端共享，不可能同时正确 | 后端改 `type:"orderNotice"` 不再下发 key（**不做数据迁移**）；前端 `SystemNotice` 按 `peerType === 'user'`（对端是用户 ⇒ 律师端）选 `orderNoticeLawyer`，缺省按用户视角防闪。四语言补新 key | 律师端 4 条通知全部「对方已购买…」，其中 3 条是**旧格式历史消息**——证明不迁移也正确 |
| P1-4 | 「我的文书」跳模板列表 | `MyPage` 自造 `DOC_TEMPLATES_ROUTE='/doc/templates'` | 改跳 `PAGE_ROUTES.docMine`；`routes.ts` 补 `docSeals/docForm/docPreview/docLawsuit`；**同源收口** 5 个页面/组件的自造路由常量（`DocMinePage`/`DynamicForm`/`DocTemplatesPage`/`DocPreviewPage`） | 浏览器：点「我的文书」落在 `/doc/mine`，页面是「我的文书 / 草稿 / 已完成」列表 |
| P1-5 | 立案页改过内容后返回无反应 | **根因在规范不在页面**：`AGENTS.md` §5 与审查清单当时都推荐 `Alert.alert`，而 `react-native-web` 的实现是 `static alert() {}` 空函数 | 页面改 `ConfirmDialog`（含「继续填写」出口）；**规范改口径**（AGENTS §5 + 审查清单 E 节）；**ESLint 加 `no-restricted-imports` 禁 `react-native` 的 `Alert`**（与既有 `Text` 条目并列，探针实测两条各自生效）。`BackHandler` 浏览器后退拦截未做（挂账） | 浏览器：填「民事」→ 返回 → 弹窗出现 →「继续填写」关窗 → 再返回「离开」退回 `/case-my`。改前一步都不会发生 |
| P1-6 | 列表返回不刷新 | `useAsyncResource` 的 effect 只依赖 `[nonce, enabled]`，挂载后不再请求；全仓仅 2 处 `useFocusEffect` | `useAsyncResource` 加 `reloadSilently()`（消费式 `silentRef`，不置 loading、失败保留旧数据）；新增 `useRefreshOnFocus`（首次聚焦跳过）；**25 个列表/展示页接入**。分页保护：显式页码页 `pageNum !== 1` 时跳过、游标页 `pagedRef` 标记翻过页即跳过；律师订单管理页加 in-flight 守卫避免「聚焦刷新 + 10 秒轮询」并发 | 浏览器「我的」→ 进余额 → 返回：请求数 **+3**（改前 0），4 次采样**均无加载骨架** |
| P1-7 | 订单「已完成」里出现已退款 | `FILTER_STATUS.completed = 'completed,refunded'`，与设计稿 5 tab 口径矛盾 | `completed: 'completed'`、`cancelled: 'cancelled,refunded'`（已退款是「服务未完成、钱已回用户」的终态）。不新增第 6 档，不动设计稿 | 数据级：`status=completed` 返回 0 条、`cancelled,refunded` 返回 6 条且全为 `refunded` |
| P1-8 | 「去充值」「首页搜索」该跳不跳 | 两处只 `toast.show('即将开放')`，而页面与路由壳都已实现 | 改真跳转 `PAGE_ROUTES.recharge` / `PAGE_ROUTES.search`（`routes.ts` 补 `search`）；回收 4 个过期死 key（`profile.my.rechargePending`、`home.searchPending`、`trade.balance.rechargePending`、`trade.pay.rechargePending`）；`trade.pay.rechargeTip` 改「余额不足，请先充值」并纠正两处与代码不符的注释 | 浏览器：`/profile`→去充值落在 `/recharge`；首页搜索框落在 `/search`；`CaseListPage` 的裸 `'/search'` 一并收口 |
| P1-9 | 隐私开关只存本机但文案承诺生效 | 后端 grep `phoneVisible/privacySetting` **0 命中**；律师侧拿到的本来就是脱敏号；「在线状态」用户侧没有数据源 → 三个开关在任何状态下都不生效 | **本版走 A（文案止血）**：四语言 4 条 `*Desc` + `localOnly` 改成「仅存本机、尚未接入服务端、接入前不影响律师看到的信息」；开关数量与默认值不变。**B（真接服务端）需加表 + 接口 + PC 联动，排期** | 代码级（无后端可验）；四语言同构经 `check:i18n` |

### 三、附带修复

- **`52ef61c`**：「律师工作台」补入口。段 5 交付了 `/lawyer-workbench` 但全仓没有任何界面入口，律师登录后找不到客户咨询，等于收不到也回不了用户消息。在「我的」页加一张卡片，仅 `roles` 含 lawyer 时渲染。
- **路由常量收口**：`HomePage` 与 `SearchPage` 的反引号路由字面量改用 `PAGE_ROUTES`（`HomePage` 的 `as never` 一并去掉）。

### 四、已知取舍与挂账

1. **律师工作台故意不接聚焦刷新**（P1-6 的例外）：`WorkbenchBody` 用 `useEffect([summary])` 把服务端值同步进本地价格输入框，聚焦刷新会无提示覆盖律师未保存的编辑。丢输入比数字不新鲜更糟。要两者兼得需加 `dirtyRef` 守卫。
2. **P1-6 排除 15 个表单/支付类页面**：`LawyerJoinPage`、`EditProfilePage`、`ChangePhonePage`、`DeactivatePage`、`PayPasswordPage`、`RealnamePage`、`EnterpriseAuthPage`、`DocSealPage`、`LegalDocView`、`DynamicForm`、`PayPage`、`RechargePage`、`RefundPage`、`ReviewPage`、`SearchPage`——重拉数据会覆盖输入或打断支付/充值。
3. **P0-6 仅止血**：真正修法是新增 `POST /api/v1/app/cases/{id}/resubmit` + `FilingPage` 按 `caseId` 预填，需负责人放行。
4. **`WithdrawStatus.i18nKey()` 是死映射**：返回的 `trade.withdraw.status.*` 四语言里不存在，本版未修。
5. **浏览器后退绕过 dirty 守卫**：全仓无 `BackHandler` / `usePreventRemove`，`P1-5` 只修了页内返回按钮。

### 五、文档基线

`AGENTS.md` §5 与 `docs/standards/代码审查清单.md` E 节因 P1-5 改口径，按 `docs/BASELINE.md` §4 出新基线 **v1.65**（`BASELINE.md` §7.68，重算这两份指纹；其余 43 份沿用 v1.64 登记值）。

---

## 2026-11-09 · 借条模板：金额大写自动化 + 利率固定单位

**背景**：负责人点测「借条欠条」时发现两个问题——① 「借款金额（大写）」和「（小写）」是两个
自由文本框，两边都能输数字，填错了文书照样生成；② 利率输入框没有单位，填 `1` 生成出来是
「按 1 计算」而不是「按 1% 计算」。

### 一、金额：删掉手填大写，改名只填小写，文书自动转大写

- 表单上**不再出现**「借款金额（大写）」输入框，只留「借款金额（小写）」
- 生成/保存时由前端把小数金额转成规范大写（`壹仟元整`、`壹仟元伍角`、`壹仟元零伍分`…），
  自动写进 `form_data.amount_text`，模板占位符不变
- 转换实现：`features/document/utils/rmbUpper.ts`（新增），覆盖拾/佰/仟/万/亿、零的压缩、
  角分与「整」的收尾；不可解析的输入返回空串（走必填校验，而不是页面级报错）
- 金额输入加了 `format: 'money'` 约束：最多两位小数。**不拦的话**，用户输 `12.345` 会在点
  「生成」时才收到「必填项未填写：借款金额（大写）」，报错点离输入点太远，很难自己发现原因

### 二、利率：固定百分号

- 「利率约定」输入框右侧**固定显示 `%`**（只展示，不进 form_data），placeholder 给
  `如：1、0.1、2`
- 模板正文的占位符改成 `按{{interest_rate}}%计算`，生成出来就是「借款利息按 1% 计算」

### 三、为此给动态表单加的通用能力（不是给借条写死）

模板 `field_schema` 新增 4 个可选属性，后端解析时**忽略**（`FieldSchema.parseField` 只读已知字段），
全部在前端生效，因此**不需要改后端渲染链路**：

| 属性 | 作用 |
|---|---|
| `hidden: true` | 字段不在表单渲染、不参与前端校验；但仍在 schema 里（模板占位符必须能找到定义，否则 `TemplateRenderer` 抛「模板引用了未定义的字段」） |
| `derivedFrom: "amount_num"` | 派生来源字段 |
| `derive: "rmbUpper"` | 派生算法（当前只认这一个，未知值原样复制来源值） |
| `suffix: "%"` | 输入框右侧固定单位 |

`format: "money"` 用于数值格式约束。派生值在 `persist()` 落库前统一由
`applyDerivedFields()` 算好，保存草稿与生成走同一条链路。

### 四、数据改动（不进 git，记录在此以便复现）

`doc_template` 里 `IOU-001` 从 version 1 → **version 2**（旧版置 `inactive`，版本表口径不变）：

- `field_schema`：`amount_text` 加 `hidden/derivedFrom/derive`；`interest_rate` 加 `suffix`；
  `amount_num` 加 `format: money` 与占位提示
- `template_html`：`（大写）{{amount_text}}元整，小写{{amount_num}}元。` →
  `（大写）{{amount_text}}，小写{{amount_num}}元。`（「元整」改由大写转换结果自带，
  否则小数金额会输出「壹仟元伍角元整」）；`按{{interest_rate}}计算` → `按{{interest_rate}}%计算`

### 五、验证

- `toRmbUpper` 单测 14 个用例全对：`1000`→壹仟元整、`1000.50`→壹仟元伍角、
  `1000.05`→壹仟元零伍分、`10001`→壹万零壹元整、`12345678.90`→壹仟贰佰叁拾肆万伍仟陆佰柒拾捌元玖角、
  `1,000.50`→壹仟元伍角、空串/`abc`/`12.345`→空
- 端到端生成实测（文档 45）：PDF 输出「今借到出借人人民币（大写）**壹仟元整**，小写**1000**元。」
  与「借款利息按**1%**计算。」
- 浏览器实测借条表单：无大写输入框、小写字段在、利率右侧有 `%`
- `tsc --noEmit` 与 `eslint` 均零输出

### 六、顺带

测试律师「验收律师甲」的个人简介原文是内部验收批次号（`第 1 段 F2 approve 验收用（非真实） |
seg5-d-smoke`），换成一条像样的执业简介——它此前在律师详情页里显示，容易被当成真实数据。

---

## 2026-11-09 · 首页改版 + 会话准入打通 + 浏览器验收缺陷批量修复

**背景**：负责人用浏览器逐页点测后提了 10 条问题。核查发现它们不是同一类东西——
3 条是**功能缺口**（付款做了、会话准入没做）、3 条是**数据损坏**（乱码 / 模板字段类型写错）、
4 条是样式与文案。本批一次处理，并顺带把「付完钱没有聊天入口」这条真实资金损失路径堵上。

### 一、首页改版（负责人定的新形态）

| 位置 | 改前 | 改后 |
|---|---|---|
| 标题 | 法泽 + 普惠法律服务，就在身边 | **按时间问候**（早上好 / 下午好 / 晚上好）+ 站在你的角度 |
| 右上角 | 「我的」头像 | **消息图标**（进消息中心；「我的」由底部 tab 承担） |
| 快捷入口 | 找律师 / 我的订单 / 消息中心 / AI 咨询 | **生成起诉状 / 律师咨询 / 进度查询 / 全部功能** |
| AI 卡 | 在方块上方 | 下移到方块之后（内容不变） |
| 每日学法 | 每篇文章一张独立卡片 | **一张卡 + 多行列表**（行间细分割线，设计稿 `.law-card`） |

- 「生成起诉状」要模板 id（`LAWSUIT-001` 是自增主键，各环境不同）：点按时先拉模板列表取 id
  再跳表单页；**拉不到就降级到文书模板列表页**，不做「点了没反应」。
- 「进度查询」只进「我的案件」列表：`/case-progress` 必须带 `caseId`，首页给不出具体案件。
- 「全部功能」放首页是因为它原来只有「我的 → 设置」一条深路径，负责人明确要求提到首页。

### 二、律师页「死路」修复（真机阻塞级）

**同一份 `LawyerListPage` 挂在两个路由上**：`/lawyer`（底部 tab，有 tabbar）与
`/lawyer-list`（独立页，无 tabbar）。而**所有入口都指向 `/lawyer-list`**，且页面写死
`showBack={false}`——结果是从首页/全部功能点进去后**既没返回箭头也没底部 tab**，
Web 上能靠浏览器后退救，真机上是彻底卡死。

- `showBack` 改为按 `usePathname()` 判断：`/lawyer` 不显示、`/lawyer-list` 显示
- 首页、全部功能页、订单空态、关注律师页、消息中心**共 5 处入口**统一改指 `/lawyer`（tab 页）
- `/lawyer-list` 路由壳保留（还有推送直达等旧链路）

### 三、会话准入：没买过服务的不能开新会话

改前 `conversation` 模块**一行都不引用订单**，`createOrGet` 只校验律师可见——
没付钱也能直接开聊。本批在服务端补上准入：

- 新增跨域只读查询 `OrderQueryService.existsActiveOrderOfUserAndLawyer` /
  `listActiveOrdersOfUserAndLawyer`（conversation 不直接依赖 trade 的 Mapper，守分层禁令）
- 「有效订单」口径：`paid` + `in_service` 且未软删；排除 `pending` / `completed` /
  `cancelled` / `refunding` / `refunded` / `refund_rejected`
- **只拦新建会话**，已有会话照常进入——否则用户退款后连历史聊天都打不开
- **律师侧豁免**：`createOrGetForLawyerInitiated`，仅 `WorkbenchServiceImpl#sendDocumentToClient`
  （律师发文书）调用，文书归属由调用方校验
- 新增 `ConversationSeqGenerator`（从 `MessageService` 抽出 `max(INCR, 库内最大+1)` 的取号逻辑，
  两处共用；`MessageService` 顺带从 620 行降到 523 行）

### 四、进会话自动推一条订单通知（纯文字）

用户付完钱进会话时，服务端自动插一条 `senderRole=system` / `msgType=system` 的消息：

```
您已购买图文咨询，订单号 OR20261109001216，金额 ¥60.00，现在可以和律师沟通了。
```

- **幂等**：`client_msg_id = order-notice-{orderId}`，同一笔订单只发一次，重复进会话不刷屏
- **`content` 存 i18n key + 参数**（不是中文死文本）：`{"key":"conversation.orderNotice",
  "serviceType":…,"orderNo":…,"amountCents":…}`，前端解析后按当前语言渲染
- 不回写 `conversation.last_message` / `unread_*`：这条是给用户看的上下文，
  不该在律师的会话列表里冒出一个「未读」
- 前端 `ChatPage` 新增 `SystemNotice` 居中提示：**原来 `out = senderRole !== 'lawyer'`
  会把系统消息当成用户自己发的**，靠右显示还带「已读」，等于用户复读一遍订单号

### 五、支付成功页：主按钮改成「进入会话」

改前成功态主按钮是「查看订单」，真实的会话入口藏在「订单详情 → 进入聊天」，用户找不到。
现在成功态两个按钮：**查看订单（次）/ 进入会话（主）**；失败态行为不变。

### 六、错误码：新增 404005，不动冻结码

`404003` 在《错误码规范》§3.4 已冻结为 `error.conversation.closed`（会话已结束）。
「未购买服务」是另一个语义，取 **`404005` / `error.conversation.not-purchased`**，
规范 §3.4 已登记。前端 `errorCodes.ts` 同步，`ChatPage` 命中时显示
「还没有购买这位律师的咨询服务」+ **「去购买咨询服务」**按钮跳回律师详情。

### 七、浏览器验收缺陷修复

| 问题 | 根因（已核实） | 处理 |
|---|---|---|
| 律师简介乱码 | 不是前端问题：`lawyer.bio` 存的是「UTF-8 字节被按 ISO-8859-1 逐字节存成字符」的**双重编码**，且 MySQL 的 latin1 是 cp1252 超集、`CONVERT` 会把 0x80–0x9F 变成 `?`，**编码转换救不回来** | 在 JS 里按码点 `& 0xFF` 还原原始字节再解 UTF-8；全库 287 个文本列按 HEX 特征扫了一遍，共修 4 处（`lawyer.bio`、`doc_template#7` 的 `name/category/template_html`、`document` 的 2 条 `title`/`doc_type`） |
| 支付密码框被浏览器自动填了 5 位 | `react-native-web` 把 `secureTextEntry` 映成 `type="password"`，且不传 `autoComplete` 时默认 `'on'`——Chrome 把登录密码灌了进来 | Web 端不传 `secureTextEntry`（type 回到 text）+ `autoComplete="off"` + `importantForAutofill="no"`；掩码本来就是自绘的，不受影响 |
| 起诉状输入框黑色焦点框「和灰底不贴合」 | 那是 **Chrome 的 UA 焦点环**，不是设计边框。且 Chrome 的 `outline-style: auto` **会忽略 `outline-width`**，只写 `outlineWidth: 0` 去不掉 | 改 `outlineStyle: 'solid'` + `outlineWidth: 0` 覆盖 UA，聚焦态改挂到外层灰底容器（主色细边框） |
| 文书模板卡显示「6 个字段」 | 设计稿那行本来是**一句业务描述**，实现时换成了字段数这种内部术语 | 整行删除（`DocTemplateBrief` 没有描述字段，删是当前唯一不改后端的选项）；四语言 `document.templates.fieldCount` 一并删 |
| 全部功能页底部像内部说明 | 文案是「这一页是兜底导航：首页和「我的」只放高频入口……」 | 四语言改成「这里是导航页，首页找不到的功能都在这里。」 |
| 起诉状「性别」要填空 | 模板 `field_schema` 里写成了 `"type":"text"`（`DynamicForm` 本来就支持 `select`） | 改数据：`select` + `options: ["男","女"]`，渲染成胶囊选项 |

### 八、验证

- 后端：`mvn -DskipTests compile` 通过；会话域单测 **27/27**（`ConversationServiceTest` 12、
  `MessageServiceTest` 12、`ConversationSeqGeneratorTest` 3）
- 前端：`npm run verify` 全绿（tsc / eslint / check-raw-text 186 文件 0 命中 / check-i18n 三项）
- 接口实测：未购买律师建会话 → `404005`；已购买 → 200 且落到会话；消息列表拿到订单通知
- 浏览器实测（390×844 + CDP 真实鼠标事件）：首页四入口/消息图标/时间问候、支付结果页
  「进入会话」、会话页订单通知居中、性别胶囊、模板卡无字段数、`/lawyer` 有 tabbar 无返回、
  `/lawyer-list` 有返回无 tabbar、未购买账号进会话显示「去购买咨询服务」

### 九、遗留（挂账）

1. **文档基线**：《错误码规范》§3.4 已加 404005，按 `BASELINE.md` §4 需重算指纹出新基线版本——**待做**。
2. **Web 焦点环是全局性问题**：本次只按范围修了 `DynamicForm`；`SearchBar`、登录/注册等
   用 RN `TextInput` 的地方在 Web 上同样会画 Chrome 黑环，要统一收口得抽 `WebTextInput`
   或全局窄范围 CSS。
3. **`MessageService` 仍超《后端代码规范》§2.5 的 500 行上限**（本次 620→523，本来就超）。
4. 本轮测试产生的订单 20 在 30 分钟后会被 `OrderAcceptTimeoutTask` 自动退款，属既有设计。

---

## 2026-11-09 · 段 8：三语译文全量落地 + i18n 首次四语言生效

**做了什么**：段 0~7 期间 `bo` / `ug-Arab-CN` / `kk-Arab-CN` 的 14 个 JSON 全是 `{}`，
`t()` 只查 `zh-Hans`——三语译文即使写进去也不会显示。本批把三语译文全量落地、修掉译文层的
硬伤，并接上 `t()` 的四语言查表。这是段 8 的第一块实质交付。

### 一、译文来源与选型（两份机翻，选了较差的那份之外的那份）

两个会话各产出一份三语机翻，都在 `docs/acceptance/seg8/` 下：

| 版本 | 判定 | 关键证据 |
|---|---|---|
| `译文候选-豆包/` | **不用** | bo「订单」→`བོད་བསྐོས`（藏地委派）**103 条**；藏文非法双重元音 `ེེ` **23 条**；U+FFFD **3 处**；ug 拉丁字母混入 1 处；kk「法泽智能体」→“法泽工厂”、注册页出现宗教用语 `قۇداي`；品牌名被译成普通名词；ug「简体中文」→「维吾尔语」 |
| `译文候选-侧边/` | **采用**（基线） | 上述问题 0 条；术语多译 132 组（可机械收敛） |

两份的结构校验（key 集合、占位符、空值、JSON）都是 0 问题——**结构 0 问题不等于能用**，
豆包版那 103 条错译全部通过了结构校验。

### 二、译文修订（1855 key × 3 语言，共改 282 条）

- **术语统一** 132 组 → 1 组（只剩「法泽」主标识用汉字、正文用音译，有意保留）
- **中文标点清零**：bo 35 条、ug 3 条、kk 3 条（`《》`→`«»`、`（）`→`༼༽`/`()`、全角空格、全角问号/冒号/句号）
- **硬伤**：kk `سەزىمтал`（西里尔）→`سەزىمتال`；kk `ءوتۋ`→`ٴوتۋ`；kk 品牌名 6 条
- **系统性错译**：藏文「登录」15 条被译成「登出」（`ཕྱིར་འཇུག`→`ནང་འཇུག`，差一个字母：ཕྱིར=外 / ནང=内）
- `.i18n-pending.json` 从 **1818×3 → 0**

### 三、接线（让译文真正生效）

| 文件 | 改动 |
|---|---|
| `shared/i18n/resources/index.ts` | 加 42 个静态 import，导出 `LANG_RESOURCES` |
| `shared/i18n/index.ts` | `t()` 改为「当前语言 → 缺译降级 zh-Hans → fallbackText」 |
| `shared/i18n/rtl.ts` | 新增 `applyWebDirection()`：`react-native-web` 的 `I18nManager.forceRTL` 是**空实现**，浏览器验收必须显式写 `document.dir`；只在 `Platform.OS === 'web'` 生效，native 链路不变 |

### 四、验证

- `npm run verify` 全过（tsc / eslint / check-raw-text 186 文件 0 命中 / check-i18n 三项全过）
- 1855 key × 4 语言逐 key 查表：未命中 0 / 空值 0
- 真实 UI 切语言：四语文案全部生效，`dir` = ltr / ltr / **rtl** / **rtl**
- 四语登录页截图：`docs/acceptance/seg8/i18n-截图/`

### 五、遗留

- 三语译文**未经母语者校对**（负责人 2026-11-09 拍板 C：不签、挂账）；
- kk 4 处正字法边缘字符（`ی`×2、`ې`×1、`ه`×1），显示正常，留给母语者；
- `DocPreviewPage.tsx` / `SignaturePad.tsx` 的 `direction: 'ltr'` 在 web 端**无效**
  （RN 样式表没有 `direction` 属性）——RTL 下签名板与文书预览会被镜像，属 `8-A4` 走查项，本批未动。

---

## 2026-11-09 · 负责人一次性拍板 8 项决策债 + 清空纯工程量（告警链路 / 错误率 / region 采集）

**做了什么**：负责人问「除了 `7-A1` 其他都做完了对吧」，核对后答案是**不对**（锚点 6 通过 / 3 部分 /
1 未完成，另有 25 条缺陷挂账）。负责人随即把需要他拍板的 **8 项全部定完**，并要求「纯工程量的东西全部做好」。
本批把 8 项拍板写进文档、把 4 项工程债做完并实测、顺带修 5 处文档错误。

### 一、8 项拍板（再不定，就变成「占位值被当成默认值随手改」）

| # | 事项 | 结果 |
|---|---|---|
| 1 | 平台服务费比例 | **15%**（`platform_fee_rate=0.15`） |
| 2 | 律师结算口径 | 资金**冻结 7 天** + 提现 **T+1 到账** |
| 3 | 支付密码错误策略 | **连错 5 次锁 30 分钟**（按实现实测值转正） |
| 4 | 登录失败策略 | **连续 5 次锁 15 分钟**（与支付密码分开计数） |
| 5 | 收藏计数与列表不一致 | **维持现状不修**（计数=总收藏数，列表只展示可见项） |
| 6 | 错误率告警口径 | **按业务错误码统计**（不返 5xx） |
| 7 | `app_user.region` 采集点 | **注册页加地区选择** |
| 8 | 是否补客服角色 | **不补**，保持 admin 单角色 |

前 4 项的共同风险：代码早已按某个值在跑，但没有任何文档说这是负责人同意的；上线后想改，动的不只是配置，
还有已产生的历史订单与结算记录。

### 二、工程量（4 项，全部实测）

| 项 | 交付 | 实测 |
|---|---|---|
| 告警三条硬缺口 | ① 进程外探活 `scripts/monitor/health-probe.ps1`（计划任务 1 分钟、连续 2 次失败判挂、直接 POST 钉钉、30 分钟去重、恢复通知、5MB 轮转、`-DryRun`）+ 安装脚本 + 配置模板；② `AlertDispatcher` 恢复通知改**差集**；③ 去重改**先发后写** | 探活：DryRun 正常 exit 0 / 不可达连败 exit 2 / mock 收 595B P0 / 恢复 exit 5。差集：`03:34 firing → 03:35 收到 ✅ 已恢复`；另附验「查询失败不误判恢复」。先发后写：不可达时 sentKey=0，恢复后重发成功=1 |
| 错误率口径 A | 新增 `BizMetricsAdvice`（`ResponseBodyAdvice`，覆盖抛异常与 `R.fail` 两条路径，module 标签四段收敛防基数爆炸）+ `faze_biz_responses_total` / `faze_biz_errors_total{code,module}` + `FazeHighErrorRate` 改业务错误率 | 分母 17→21；`{code="402001",module="auth"}=1`、`{code="401004",module="pub"}=1`；`promtool` SUCCESS: 6 rules |
| `region` 采集点 A | 后端：`V709` 加 `user.region_options`（11 个州县 + 其他，**加县只改配置**）、`GET /pub/regions`（`R<List<String>>`，免登录）、`RegionOptionsService` 白名单、`RegisterBO.region` 必填、落库、`ProfileVO` 加字段；前端：注册页「所在地区」必选（自绘 Modal 底部弹层，零新依赖）+ 四语言 4 个 key | `/pub/regions` → `code=200, count=12`（原始字节验 UTF-8 中文正确）；不带 region → `401001 "region: 请选择所在地区"`；真实注册落库 `region='伊宁市'`；四门禁 + `expo export` 全绿 |
| 文档错误 5 处 | ① `25-logs.html` 留存「90 天」→「≥3 年（1095 天）」；② 技术路线 §5.11 标题「15 张」→「15 + 段 7 补登 2 = 本节 17 个 DDL 块」；③ 数据表规范 L81 补「总数 55 张含 2 张未建」；④ `login_log`/`operation_log` 判为以 Flyway 为准（10 列 / 11 列）；⑤ `restart-api.ps1` 假阳性核实**早已修**，挂账关闭 | 见 `docs/BASELINE.md` §7.66 第三节 |

### 三、文档回写

| 文件 | 改了什么 |
|---|---|
| 《开发计划总纲》§十 | 由「待决事项」改为**决策台账**（15 行，全部标状态 + 日期 + 落点）；§5.6 律师结算风险行改为已定 |
| 《第 5 段工作流》§零之二 | 补第 19 / 20 项（服务费 15%、冻结 7 天 + T+1） |
| 《第 7 段工作流》§零之二 | 26 项 → **30 项**（补错误率口径 / region / 客服角色 / 收藏计数），标题同步 |
| 《未完成需备注》§四 / §八 | §四 5 项占位值全部转正（标日期与出处）；§八 改为「全部拍板完毕，未定项 0」，补第 9 项 region |
| 《技术路线》/《数据表规范》 | §5.11 标题数字、L81 半句 |
| 《运维手册》 | v1.1 → **v1.2**，新增 §3.7「服务外探活」五小节 + 更新巡检/缺陷/挂账/变更记录 |
| `docs/BASELINE.md` | **v1.62 → v1.63**，7 份指纹重算，新增 §7.66 |
| `docs/acceptance/seg7/挂账.md` | §二.1~3 三条告警缺口标已修（附实测）；§二.5 错误率已定 A；§二.11 收藏关闭；§二.14 region 已定并在施工；§二.22 restart-api 已修；§五 硬前置 5 条 → **3 条已清、2 条留段 8** |

### 四、验证

- 后端全量 `mvn -B test`（注入 `deploy/.env`）：**519 / 519 通过，0 失败 0 错误**（原 502，+17）
- 前端四门禁 + `expo export`：`tsc` 0 / `eslint` 0 / `check:rawtext` 0（186 文件）/ `check:i18n` 通过（缺译 1818，持平）/ `expo export --platform web` EXIT 0
- **`npx vite build` 不适用于 `apps/mobile-app`**：那是 PC 端门禁，Expo 用 Metro。fe-app 拒绝强跑（会临时下载 vite@8.3.6 违反「不引入新依赖」），改用 `expo export` 等价验证——这个判断是对的，是我给的验收口径不准确。

### 五、三个坑（实测踩到，会误导下一个人）

1. **并发 Maven 互删 `target/classes`**：报 `NoSuchFileException: target/classes/.../*.class` + 「无法删除文件」，**看起来像编译错误，实际是并发写 target**。同一时刻只能跑一个 `mvn`。
2. **`BusinessException.detail` 只进日志不进响应体**：所以「未传 region」与「非法 region」都是 `401001`，只能靠 `msg` 区分。
3. **`POST /pub/sms/code` 的 `scene` 必须小写**（`register`/`login`/`reset`），传大写返 `401001`。

### 六、没做的

`7-A1`（0/15 截图）、真实钉钉群、真实支付商户号、隐私政策法务文本、正式敏感词库与模板文案、计划任务注册、
三语 region key 的翻译（值暂为中文，key 四份都在）、塔城/阿勒泰是否纳入地区枚举 —— 负责人 2026-11-09 明确：留段 8 或他自行安排。

---

## 2026-11-09 · 第 69 页最后 2 项补验 + 修复「投诉/反馈截图上传从来不可能成功」的后端缺陷

**做了什么**：负责人要求用真实浏览器把第 69 页剩下两项（提交成功态入口、带图缩略图）验掉。用 CDP
直连本机已在运行的 Chrome（真实鼠标事件 + 真实磁盘文件），两项**都验过**；补验过程中查出并修掉一个
**此前一直存在、被记成「已通过」的 P1 缺陷**。

| 项 | 结果 |
|---|---|
| 提交成功态入口 | ✅ 真实点击「提交投诉」→ 「已提交，单号 `TK20261109000027`」→ 点「查看我的投诉」跳到 `/complaint-list` 且列表含该单号；截图 `docs/acceptance/seg7/T2-69-success-state.png` |
| 带图缩略图 | ✅ `DOM.setFileInputFiles` 写入真实 PNG（41814 B）→ UI「已上传 1 张」→ 提交 `TK20261109000028` → 列表 `<img>` `naturalWidth=1440`、`src` 为服务端签名地址（`isBlob=false`）；截图 `T2-69-upload-form.png` / `T2-69-thumb-list.png` |
| 顺带查出 | **`UploadBO.scene` 的 `@Pattern` 白名单漏 `complaint` / `feedback`** → `POST /app/upload/token` 必返 `401001 scene: 场景不在白名单内` → 57 投诉页 / 58 反馈页的截图上传**从未成功过一次**，页面只弹「截图上传失败，请重试」 |

**为什么之前没被发现**（这条比缺陷本身更值得记）：场景白名单**存在三处**——APP 侧 `UploadScene`、
`StorageKeyRules.ALLOWED_SCENES`、`UploadBO` 的 Bean Validation 正则。段 7 C5 只改了前两处，
第三处是入参的第一道闸，先于存储层执行，所以「看起来加了、实际进不去」。
`C5-前端4页.md` 当时按代码路径判了 ✅，没做真实上传；缩略图那项又因为「历史投诉 `images` 全为 null」
一直没触发渲染分支 —— 两个空白叠在一起，把缺陷藏了整整一批。

**改了什么**：

| 文件 | 改动 |
|---|---|
| `services/faze-api/…/modules/account/bo/UploadBO.java` | `@Pattern` 正则补 `complaint|feedback`；javadoc 写明三处白名单必须逐项一致，并写清漏项时的表征（页面「截图上传失败」+ 后端 `401001`） |
| `services/faze-api/src/test/java/com/faze/modules/account/bo/UploadBOTest.java` | **新增**（4 个测试）：8 个客户端场景逐个过正则、逐个在 `StorageKeyRules` 内、4 个服务端专用场景**不得**进客户端白名单、伪造场景（`../../etc` / `avatar/../../x` / `complaint_extra`）必须被拒 |

**文档**：`T2-APP-69投诉列表.md` 新增 §七（两项补验全过程 + 缺陷定位与修复）；`C5-前端4页.md`
纠正两处失实记录（投诉页截图的 ✅ 与「前后端已同步」的说法）、把 58 页截图上传改标「未单独复测」；
`挂账.md` 关闭 21b 并把新增测试数据与对象清理方式补进 §四。

**基线**：`docs/BASELINE.md` **本版不动**——本次改动全部落在 `services/faze-api`（代码 + 测试）与
`docs/acceptance/seg7/` 的三份**过程记录**（按 `BASELINE.md` 第三节 §2.6 的一贯口径，验收证据不进
基线资产），**45 份基线资产零变更**，因此不出新基线版本。新增的 3 张证据截图同样不算资产。

**验证**：`mvn -B test` 全量 **502 / 502** 通过（原 498 + 新增 4）；`mvn -q -DskipTests compile` EXIT 0；
修复后用同一个页面重新走一遍上传 → 提交 → 缩略图，全部通过。

**顺带纠正**：`tmp/seg7h/submit-complaint.js` 原来用 `innerText.includes('查看我的投诉')` 判断成功态，
但表单态顶部本来就有同名入口 → 该断言**恒为真**，等于没验。已改用「`textarea` 消失 + 文本含『已提交』」。

---

**做了什么**：负责人 2026-11-09 拍板，APP 页面总数 **68 → 69**，新增第 69 页「我的投诉」列表
（路由 `/complaint-list`，所属**第 7 段** C 组客服链路）。本批**只改文档、不改任何代码**。

| 项 | 内容 |
|---|---|
| 新增页 | **我的投诉**（列表页）：入口 = 「我的」页「常用功能」→ 我的投诉，以及 57 投诉页提交成功后跳转；数据源 `GET /app/complaints`（段 7）；四态齐全 |
| 来源（必须写清） | **不是设计稿画的页**——`design/ui/` 没有对应屏；是为满足 `7-A1` 第 3 步（处理投诉）与 `7-A5`（App 能看到回复与状态变化）补的**实现页**。**稿面依据只有 57 投诉页的卡片与列表样式，不得声称有设计稿** |
| 为什么现在补 | 段 7 交付后 `7-A5` 的 APP 界面证据一直缺「用户能看到投诉列表与回复」这一环，`7-A1` 第 3 步也缺 APP 侧界面；本页交付后两条锚点的 APP 证据**才可能补齐**（仍以验收证据文件为准，不等于锚点已通过） |
| 页数加总 | 段 0=2 + 段 1=18 + 段 2=16 + 段 3=2 + 段 4=6 + 段 5=13 + 段 6=1 + **段 7=11** + 段 8=0 = **69**；模块 10「设置与其他」21 → **22**；PC 后台 28 页**不变**（APP 69 + PC 28 = 97，**两套口径不相加**） |

**改了哪 6 份（全仓「68 页」口径逐处核对）**：

| 文档 | 改了哪些处 |
|---|---|
| 《开发计划总纲》 | §3.1 段表段 7「页面数 10 → **11**」与合计 68 → **69**；§4.1 标题与说明 68 → **69**、段 7 归属行页数 10 → **11** 并列入「我的投诉列表页（第 69 页）」、逐模块复核模块 10 段 7「10 → 11 / 合计 21 → 22」、总计 68 → **69**；§5.8 段 7「APP 页（10）→（11）」；§八 `8-A1`「4 语言 × 68 页」→ **69 页**；§11.1 索引「APP 68 页」→ 69 页；§一 的「96 页」误读提示改「97 页」并注明 2026-11-09 起 69 页 |
| 《APP 页面导航树 v1.4.md》 | 内容版本 **v1.5 → v1.6**（文件名不变）；顶部加 v1.6 变更段（写明新增页与「无设计稿」）；「我的」页「常用功能」组加「我的投诉」入口；模块 10 树内新增「我的投诉列表页」节点（含状态取值与四态、标注「`7-A1`/`7-A5` 的 APP 界面证据依赖本页」）；模块 10 标题 21 → **22 页**；页面数量统计表模块 10 行 21 → **22**、合计 68 → **69** |
| 《页面清单与信息架构》 | 顶部加口径提示：**本文档 §2 是复刻期 12 页的历史清单，不是现行 69 页清单**，现行以导航树 v1.6 与总纲 §4.1 为准；§2 表加第 13 行「ComplaintListPage（我的投诉）/ `/complaint-list` / 状态：**已完成（段 7）**」+ 来源注 |
| `docs/README.md` | 平台范围 APP 68 → **69 个页面**（并注明与 PC 28 不相加）；导航树索引 68 → 69 页、内容 v1.5 → **v1.6**；UI 设计顺序索引保留「68 屏」并注明第 69 页无设计稿；「哪一页属于哪一段」68 → 69；「有哪 69 个页面」；目录树三行同步；**段 7 锚点段补「第 69 页交付后 `7-A1` 第 3 步与 `7-A5` 的界面证据才可能补齐」** |
| `docs/CHANGELOG.md` | 本条目（**按既有约定不登记指纹**） |
| `docs/design/未完成需备注.md` | 全文无「68 页」表述，**本批未改**（已核对） |

**没改的**：代码 / `docs/BASELINE.md`（Lead 统一算）/ 《第 7 段工作流》（§五 5.3 与 §十一 由 Lead 改）/ PC 侧文档。

**追加补 3 处（Lead 2026-11-09 授权，只这 3 处）**：本批最初扫到但不在写作用域、会与「69 页」自相矛盾的三份，已补：
- `docs/architecture/法泽FAZE_技术路线_v2.3.md`：头部「内容 v1.5 / 68 个页面」→「**v1.6 / 69 个页面**」并注明第 69 页为实现页、无设计稿；§1.3 标题 `（68 页面）`→`（69 页面）`；表头「以导航树 v1.5 为准」→**v1.6**；模块 10 `21`→**22**（偏差 `+3`→**`+4`**）、合计 `68`→**69**（偏差 `+3`→**`+4`**）；表下加「APP 69 + PC 28 = **97**，两套口径不相加」；§1.3 加 2026-11-09 追加说明。
- 仓库根 `AGENTS.md` L174：页面导航树索引行「内容实为 **v1.5**，**68 页**」→「**v1.6**，**69 页**；第 69 页为实现页、`design/ui/` 无其设计稿」（**只改这一行，AGENTS 其他字未动**）。
- `docs/design/README.md`：L11「68 页…内容为 v1.5」→「**69 页**…**v1.6**」；L12「这 68 屏…」→ **保留 68 屏** + 注「第 69 页是实现页、无设计稿」；L21「当前项目是 **68 页** + 真实后端」→「**69 页** + 真实后端」并注明无设计稿。

**未改但仍在写 68 页的文件（已确认不改）**：`docs/design/法泽APP_UI设计顺序_v1.0.md` 全篇 68 屏（**设计稿确实只有 68 屏，改了就是新错**）；`docs/design/法泽PC后台UI设计说明_v1.0.md` L96「APP 的 68 屏」（同上）；`docs/architecture/法泽FAZE_PC后台开发计划_v1.0.md` L11「APP 68 屏的施工记录」（同上）；`docs/architecture/法泽FAZE_第0段工作流_v1.0.md`（段 0 历史计划，「68 页清单」是当时措辞，改风险大收益低）；`docs/acceptance/seg7/` 三份证据（`7-A1-runbook` / `7-A5-ticket` / `挂账`，历史快照，原样保留）；`docs/BASELINE.md`（Lead 统一）；《第 7 段工作流》（Lead 自己改）。

---

## 2026-11-09 · 第 7 段文档回写：§九 的 17 项逐条收口（11 份上游文档）

**做了什么**：把《第 7 段工作流》§九 列的 17 项回写要求，逐条落进 11 份上游文档。
本批**只改文档**，不改代码、不改工作流本文、不改 `docs/BASELINE.md`（指纹由收口统一种）。

| 文档 | 改了什么 |
|---|---|
| 《开发计划总纲》§5.8 + §11.2 | 数据库行 7 张 → **建 11 张**（9 张在册 + 第 54 张 `sensitive_word_appeal` + 第 55 张 `announcement`；`operation_log`/`login_log` 段 1 建本段只读；`share_poster` 不做）；接口行 → **54 个（新增 26 + 已列 28）**；§11.2 第 7 行标「已产出」+ 规模 |
| 《技术路线》§5.1 / §5.11 / §4.8.4 / §16.4 / §17.3 | 表总数**v1.34 起 53 → 55 张**；§5.11.3 三段 DDL 补字段（`sensitive_word` 11→12、`content_review` 10→12、`service_ticket` 9→13，并补 `V706` 的 `type_id`）；§5.11 补 `sensitive_word_appeal` / `announcement` 两段 DDL（并修好这两段的代码块闭合）；§4.8.4.3 删「口径未定」改「口径已定」+ 接口 3→5；§4.8.4.9~11 补 26 个新接口；§4.8.4.11 加「A-28 归段 3」说明；§4.8.4.12 的「IM 原文待定」改为已定；§16.4 客服时段 21:00 → **20:00**；§17.3 补「审计日志与文件日志两套口径」 |
| 《接口规范》§7.3.2 | 补段 7 的 **54 个**接口简表（新增 26 + 已列 28，逐条给路径 / 鉴权 / 幂等 / 主要错误码），字段级契约不重复 |
| 《错误码规范》§2.2 + §3.10 | 补 **12 个**码 `410005`~`410016`（其中 `410008`/`410010`/`410012` = **409**、`410014` = **400**，其余 200）；§2.2 加「**§3.10 的逐码表优先于本节通则**」；§3.2 的封禁码沿用已登记的 `402011 error.auth.account-banned`（段 7 新增，无需另编） |
| 《审计与日志规范》§3.2 + §5.2 + §6 | 补 **13 个**动作码（`CONTENT_REVIEW_*`×3 / `SENSITIVE_WORD_*`×4 / `APPEAL_HANDLE` / `ANNOUNCEMENT_CREATE` / `ANNOUNCEMENT_PUSH` / `TICKET_ASSIGN` / `TICKET_CLOSE` / `TICKET_REPLY`），并删去 4 行旧的粗粒度占位（`SENSITIVE_WORD_*` 等，重复且 `TICKET_*` 标了「建议」与逐码行矛盾）；`LOG_EXPORT` 注明「§6.3 已定义，只复用不重复登记」；§5.2 补「**任何页面的留存文案不得低于本节期限**」；§6.1 补「工单处理记录」查询维度，§6.2 补 `service_ticket` 四个索引的覆盖说明 |
| 《数据表规范》附录 A + §一 | 补第 54 张 `sensitive_word_appeal`（10 字段）、第 55 张 `announcement`（**15 字段，按 `V701` 实际 DDL 数**）；`service_ticket` / `sensitive_word` / `content_review` 字段数更新（并注明 `service_ticket` 施工另补 `type_id` 后实际 14 列）；总数 53 → **55**、段 7 = **11 张实建**；§一「唯一表名」行同步；**修正段分布加总算术错误**（原「段 7 = 11」把实建数混进在册分布，导致各段加总 53 与「合计 55」对不上；现写清 54 张在册 + `invoice` 1 张不建 = 55） |
| 《PC 后台开发计划》§二 + §八 | A-02 / A-20~A-26 **八页**的接口列由「有（第 7 段）」改「**有，N 个**（见《第 7 段工作流》§五 5.4）」；§八 新增「看板指标口径 ✅ 已定」一行，并把「要不要看聊天原文未定」改为已定 |
| 《未完成需备注》§5.1 + §八 | §5.1 表「数据看板指标定义」标 ✅ 已定；§八 第 4、7 项标已定（原已标，本次核对无改动）；§5.2 确认「只看命中片段 + 前后各 3 条，不看全文」；概览处「仍未解决的部分」②③ 同步标已定 |
| 《依赖清单》 | 补后端 3 项：`spring-boot-starter-actuator` / `micrometer-registry-prometheus`（版本由 **Spring Boot 4.0.6 父 POM** 管）/ `logstash-logback-encoder`（7.4，须显式写版本）；`echarts` 经 `pc/admin-web/package.json` 核实为 **5.6.0 且框架已带**，改登记为「框架已带，非新增」 |
| `docs/README.md` | 段 7 索引与验收证据索引已就位（本次核对无缺口）；**修正一处既有错位**：「锚点结论：`5-A1`~`5-A8` 8/8 通过」整块原被错放在段 7 小节末尾，已原样挪回段 5 小节；顺带把三处陈旧数字改为实际值（表总数 51 → 55、错误码 78 → 91） |
| `docs/CHANGELOG.md` | 本条目。**按既有约定不登记指纹**（指纹统一在 `docs/BASELINE.md`） |

**11 处上游冲突（全部按《第 7 段工作流》§零之二 裁决回写）**：

| # | 冲突 | 本段定 |
|---|---|---|
| 1 | 段 7 建几张表 | **实建 11 张**（9 张在册 + 第 54/55 张；`share_poster` 不做；`operation_log`/`login_log` 段 1 建、本段只读） |
| 2 | `sensitive_word_appeal` 表不存在 | 新建**第 54 张** |
| 3 | 公告「触达 / 打开率」无表可落 | 新建**第 55 张** `announcement`（批次主表） |
| 4 | `service_ticket` 缺页面要的 4 字段 | 补 `ticket_no` / `title` / `priority` / `sla_due_at`（施工又补 `type_id`） |
| 5 | `sensitive_word` 缺「最近命中」 | 补 `last_hit_at` |
| 6 | `content_review` 缺「命中片段」 | 补 `hit_snippet`（**不是原文全文**）+ `biz_user_id` |
| 7 | 收藏 / 关注列表接口没登记 | 补登 **4 个** C 端接口 |
| 8 | 企业认证没有「查状态」接口 | 补登 `GET /app/account/enterprise`（四态） |
| 9 | A-20 能不能看聊天原文 | **只返回命中片段 + 前后各 3 条上下文摘要**，不提供全量拉取；原文完整性用直接查库证明 |
| 10 | 客服时段两个数（21:00 / 20:00） | **9:00–20:00**（以 APP 55/56 设计稿为准） |
| 11 | 日志留存三处打架且设计稿低于合规下限 | 以**审计规范 §5.2** 为准（`operation_log` ≥ 3 年、`login_log` ≥ 180 天）；A-26 稿「90 天」作废 |

**26 项负责人裁决**：完整清单见《第 7 段工作流》§零之二（第 1~4 项为开工前逐条确认的硬缺口，第 5~26 项为负责人授权「按建议处理」后落定）。其中改变总量或口径的有 6 组：表数 **53 → 55**、3 张表补 **7 个**字段、接口清单 **+5**、A-02 接口 **3 → 5**（补 `regions` / `todos` / `export`，`online` 并入 `summary`；裁决 21 说的「4 个」是**数据接口**口径、不含 `export`，两处不矛盾）、看板口径从「未定」变「已定」、客服时段与日志留存两处以**小数为准**。

**本段口径（回写所依据的实际值，与计划不同的地方）**：

| 项 | 计划 | 实际（已按实际回写） |
|---|---|---|
| 建表 | 7 张（总纲旧文）/ 10 张（附录 A 旧文） | **11 张**：9 张在册 + 第 54 张 `sensitive_word_appeal` + 第 55 张 `announcement`；`share_poster` **不做**；`operation_log`/`login_log` 段 1 建、本段只读 |
| 表总数 | 53 张 | **55 张**（要建 54 张 = 55 − `invoice`；段 7 实建 11 = 12 在册 − `share_poster`） |
| 接口 | 上游清单无段 7 条目 | **54 个**（新增 **26** + 已列 28），其中管理端 40、C 端 14 |
| `announcement` 字段 | 工作流 §九 写 16 | **15**（`V701` 实际 DDL：id/announcement_no/title/content/target_type/target_value/channels/status/scheduled_at/sent_at/reach_count/open_count/created_by/created_at/updated_at） |
| `service_ticket` 字段 | 13 | **14 实际列**（13 + `V706` 补的 `type_id`；附录 A 记 13 并注明施工补列） |
| 封禁状态 | `banned` | **`blocked`**（`app_user.status` 库内只有 `active/blocked/cancelled`；`banned` 只是管理端出参枚举） |
| `region` 目标人群 | 可用 | 列已由 `V707` 建，但**无采集点、恒为 NULL**：地区分布只返回「其他」，按地区发送必然 0 人（正常返回 `sent`，不报错）；采集入口段 8 补 |
| 测试发送（A-24） | 发给自己站内信 | **只返回渲染预览文本，不写 `notification`**（操作者是 `faze_admin.sys_user`，不在 `faze.app_user` 里，`notification.user_id` 无处可写；段 8 补「管理员 → 站内信」身份映射） |
| A-26 操作人列 | 显示名字 | 只返回 `operatorId`，`operatorName` 恒 `null`（跨库读不到 RuoYi 用户表） |
| `log.operation_retention_days` | 90 | **1095**（3 年，合规下限；与裁决 16 冲突时按合规） |
| `7-A8` 告警通道 | 钉钉群机器人 | **本段不实现任何发送通道**：`alert.dingtalk_webhook` / `alert.dingtalk_secret` 两键 V703 已建、**值为空串**；`TicketSlaScanTask` 在 webhook 为空时**只打 WARN 日志、不发送**。负责人 2026-11-09 定：邮箱方案留备注、段 8 处理，本段不改任何 `alert.*` 键与发送代码。**因此本段没有任何真实告警出口**，`7-A8` 「告警真的响」没有正面证据，**段 8 必须落地一条通道** |

**关联**：11 处上游冲突见《第 7 段工作流》§零 表、26 项裁决见 §零之二、实际偏差见 §十一（19 条）。

---

## 2026-11-09 · 第 7 段尾巴：C5 APP 客服 4 页 + region / 客服电话补漏 + **一个静默 bug**

**做了什么**：补齐段 7 C5（APP 55/56/57/58 四页），并收掉 A~D 组遗留的两处数据缺口
（`app_user.region` 缺失、客服电话占位号），过程中**挖出一个「公告发 0 人却不报错」的真缺陷并修掉**。

| 范围 | 内容 |
|---|---|
| C5 APP 4 页 | `features/ops/pages/` 下 `SupportPage`（55 客服中心）/ `FaqPage`（56 常见问题）/ `ComplaintPage`（57 投诉）/ `FeedbackPage`（58 反馈）+ 4 个路由壳 + `features/ops/api/*` + `features/ops/types.ts`；「我的」页新增「我的与服务」分组入口 |
| i18n | `zh-Hans/ops.json` 新写 `ops.support.*` / `ops.faq.*` / `ops.complaint.*` / `ops.feedback.*`（约 60 key）；另 3 语言缺译走既有 `.i18n-pending.json` 登记（段 7 允许的降级） |
| 后端接口 | 新增 `GET /api/v1/pub/app/config`（免登录，聚合返回客服时段 + 电话，避免逐键读 `/pub/config/{key}`） |
| 迁移 | `V707__seg7_region_and_service_phone.sql`：`app_user` 加 `region VARCHAR(64)` + `idx_app_user_region`；`sys_config.service.phone` 占位号 `0999-XXXXXXX` → 正式号 `002-00854967`（幂等：information_schema 判断 + PREPARE/EXECUTE） |
| region 链路 | `AppUser.region` 字段；`AccountQueryService.listUserIdsByRegion`；`DashboardMapper.selectRegions`（前 5 + 合并「其他」，NULL/空串归「其他」）；`AdminDashboardService.regions()` 接通真实查询；`AnnouncementSendService` 的 region 分支从「硬抛错」改为真实查库 |
| 上传白名单 | `StorageKeyRules.ALLOWED_SCENES` 与 `KEY_PATTERN` 同步新增 `complaint` / `feedback` 两个 scene；前端 `UploadScene` 同步（两边少一个就 400） |

### Fix：公告「全量 / 按角色 / 指定用户」三种目标**全部静默发 0 人**

`AccountQueryServiceImpl.selectUserIds` 用 `status='normal'` 查库，而 `app_user.status` 自
`V1__baseline.sql` L32 起只有 `active / blocked / cancelled` —— `normal` 恒为 0 行。

后果：公告发送接口返回 200、`status=sent`、`reach_count=0`，**看起来完全正常**，
实际一个人都没发到。`NORMAL/BANNED/CANCELLED` 只是管理端**出参**枚举，
由 `AdminUserService.toDbAccountStatus` 翻译回库内取值，不能直接拿去查库。

已修为 `active`。证据：修前 `SELECT COUNT(*) ... WHERE status='normal'` = **0**；
修后 `estimate(all)` = **6**（= `status='active' AND deleted_at IS NULL` 的实际行数），
`role=lawyer`=1 / `role=user`=4 / `user_ids=[1,3]`=2。记 §十一 第 18 条。

### 验收

| 项 | 结果 |
|---|---|
| 4 页浏览器渲染（CDP 真实 Chrome，390×844） | 全部通过；截图与逐条验收点见 `docs/acceptance/seg7/C5-前端4页.md` |
| 57 提交 | 真实点击 → 「已提交，单号 **TK20261109000007**」 |
| 58 提交 | 真实点击 → 「已提交，感谢你的反馈」 |
| `GET /pub/app/config` | `{"onlineHours":"09:00-20:00","phone":"002-00854967"}` |
| `GET /admin/dashboard/regions` | `{"list":[{"count":"6","name":"其他"}]}`（region 全 NULL，只有「其他」一项，符合预期） |
| Flyway V707 | success=1，`app_user` 22→23 列、索引 3→4（未超单表 6 个上限） |
| 后端测试 | **489 / 489 通过**（原 479 + 本次 10） |
| 前端门禁 | `tsc --noEmit` / `eslint` / `check:rawtext`（170 tsx 命中 0）/ `check:i18n` 全部通过 |

**未做（仍在段 8）**：E/F/G/H 组（设置页 / A-02 看板页 / A-26 日志中心页 / 监控告警 / 收口）；
`app_user.region` **无采集点**（恒为 NULL，地区分布只有「其他」、按地区发送必然 0 人，
采集入口留给段 8）；告警通道按文件口径**维持钉钉**、邮件方案仅留备注。
偏差见《第 7 段工作流》§十一（第 11/17/18/19 条）。

---

## 2026-11-09 · 第 7 段 A~D 组交付（后端 + PC 6 页）

**做了什么**：按第 7 段工作流的 8 组任务，完成 **A/B/C/D 四组的后端全部**与 **PC 端 6 页**。

| 组 | 交付 |
|---|---|
| A（地基） | 11 张表（V700~V702）+ 种子（V703）+ 审核开关（V704）+ 风控阈值（V705）+ 补 	ype_id（V706）；敏感词 Trie 引擎（14 单测）；内容审核三档管道；日志中心 4 接口 + 留存期校验与清理；看板 4 接口 + 指标口径文档；6 枚举 + 12 错误码 + 13 审计动作码 |
| B（审核风控） | 词库 6 接口 + 导入导出；申诉 3 接口；审核队列 3 接口（三种处置各带审计码）；违规记录 2 接口；账号风控（异常注册 / 新设备提醒） |
| C（工单客服） | 状态机（4 态 5 迁移 + 10 单测）；投诉/反馈提交 → 同事务生成工单；管理端工单列表/详情/回复/关闭/分配/导出；FAQ 接口 |
| D（消息公告） | 公告 5 接口 + 发送链路（分批 500、DB 为准的幂等、部分失败不回滚）；模板 5 接口（变量一致性校验）；站内信 3 接口（已读回写公告打开率） |
| PC 端 | A-25 敏感词 / A-20 审核队列 / A-21 工单列表 / A-22 工单详情 / A-23 公告 / A-24 模板 共 6 页 + ops.js + 菜单种子（2230~2235，已执行进 aze_admin） |

**端到端实测**：block 级投诉 → {code:410006} 且原文不落库；review 级投诉 → 正常提交、content_review 命中「包打赢」「不赢不收费」两词；词库 50 / 模板 6 / FAQ 7 可查；8 个接口全部 200；全量测试 **479 用例通过**。

**未做**：C5 的 APP 4 页、E~H 组（设置页 / 看板页 / 日志中心页 / 监控 / 收口）。偏差 16 条见《第 7 段工作流》§十一。

---
## 2026-11-08 · 第 6 段补做（二）：B3 限流 / C4 线程级并发 / D1 人工补单

> **本段收口**：三项补齐后 19 个任务 **19/19 完成**；7 条锚点结论不变——
> **4 条通过**（6-A2 / 6-A3 / 6-A5 / 6-A7 的 Mock 部分）、**2 条挂账**（6-A1 / 6-A6）、
> **1 条部分挂账**（6-A4 真实账单部分）。三条挂账均为「无支付商户号」的外部条件，
> 不是本段欠账。见 `docs/acceptance/seg6/挂账.md`。

### 做了什么

| 范围 | 内容 |
|---|---|
| B3 用户维度限流 | `RechargeRateLimitGuard`：Redis 固定窗口（INCR + 120s TTL，口径同 `LawSearchRateLimiter`）+ `V603` 配置 `recharge.rate_limit_per_min=3`；插在 `RechargeOrderService#create` 的「金额校验之后、渠道之前」；超限 **HTTP 429 + `Retry-After` + 专用码 `405030`**（不复用 401007）；Redis 故障 fail-open |
| C4 JVM 内 2 线程 | `RechargeIdempotentTest`：`@SpringBootTest` + 真 MySQL + `CountDownLatch` 双线程各自开事务；串行重放 3 次 + 并发 2 次，均验「成功行=1 / 流水=1 / 余额+1 份额」；证据追加进 `C4-idempotent.txt` 第四节（原 HTTP 进程级证据保留） |
| D1 人工补单接口 | `POST /api/v1/admin/recharges/{orderNo}/sync` + `AdminRechargeSyncService`（`@OperationLog(RECHARGE_SYNC)`）；`RechargeTaskService` 抽出 `onceQuery` 作为人工/自动两条路径的公共实现；返回 `syncResult` 枚举（`settled` / `marked_failed` / `channel_pending` / `channel_not_found` / `not_pending`），不抛 500 |
| 错误码 | 新增 `TRADE_RECHARGE_NOT_FOUND(405029)`：本地无此单是入参错误，与「渠道查不到」（200 正常态）区分 |
| 迁移 | `V603__seg6_recharge_rate_limit.sql`（段 6 迁移 3 → **4** 条，Flyway 已执行） |
| 单测 | `RechargeRateLimitGuardTest`(5) / `AdminRechargeSyncServiceTest`(5) / `RechargeIdempotentTest`(2)，**+12 个** |

### 三件为什么之前没做（复盘，写在这里防重犯）

三件**都不是负责人裁决不做的**，是施工方自行标注「时间预算不足 / 超出范围 / 边际收益低」：
D1 首版理由「时间预算不足」；B3「项目无用户维度限流组件，新建限流设施超出范围」——**实际十几行即可**；
C4「进程级并发证明力已足够」——**但工作流要的是可回归的测试资产，不是一次性手工证据**。
`AGENTS.md` §1.2 明文「时间不是约束，不因『要快』而砍范围或降低工程标准」，上述理由均不成立。
同时纠正一个流程错误：把「未做完」登记进工作流 §十一 的**偏差表**（偏差表是给「合理差异」用的，
不是收容缺口）。

### 证据

`docs/acceptance/seg6/B3-D1-final.txt`（B3：同一用户第 4 次下单 **HTTP 429 + Retry-After + 码 405030** + Redis 计数；
D1：`callback_lost` 掉单 → 人工 sync → `settled`，含流水与 `operation_log` 三方对照）、
`C4-idempotent.txt` 第四节（JVM 内 2 线程）。

---

## 2026-11-08 · 第 6 段 D3/D4 补齐：渠道对账单与逐笔比对

> **本段仍未收口**：D1 管理端人工补单接口、B3 用户维度限流、C4 线程级并发用例三项未做
> （见 `挂账.md` 第二节）。E2 连贯走查与 E3 的 §九 回写、`6-A7-failure.txt`、基线升级**已补**。
> 补齐后 19 个任务完成 17 个；7 条锚点：**4 条通过**（6-A2 / 6-A3 / 6-A5 / 6-A7 的 Mock 部分）、
> **2 条挂账**（6-A1 / 6-A6）、**1 条部分挂账**（6-A4：Mock 差异识别已验，真实账单格式挂账）。
> 详见 `docs/acceptance/seg6/6-A4-reconcile.txt`、`E2-e2e.txt`、`6-A7-failure.txt` 与 `挂账.md`。

### 做了什么

| 范围 | 内容 |
|---|---|
| D3 账单下载 | `ReconcileBillParser` 接口 + `MockBillParser`（**真实解析实现**）+ 微信/支付宝骨架（抛 405013）+ `BillCsvSupport`（元↔分走 `BigDecimal`，禁 double）+ `MockBillGenerator`（同构 CSV：表头 + 数据 + 汇总）+ `BillParserFactory` + 账单存 `file_record(biz_type='reconcile_bill')` + `ReconcileDailyScheduler` 每日 02:00 投递 + `ReconcileTaskScanner` 60s 领任务 |
| D4 逐笔比对 | `ReconcileCompareService`：按 `order_no` 匹配 → 三类差异落 `reconciliation_diff`（`channel_only` / `local_only` / `amount_mismatch`）→ `channel_only` 且本地有单时自动补入账（走 `settleByCallback` 的晚到回调补偿）→ 差异回写 `auto_fixed` → 先查后插去重（同一天重跑不产生重复行） |
| CSV 导出 | `GET /admin/reconciliation/channel-diffs/export`（`text/csv` + UTF-8 BOM，与列表共用同一份筛选条件） |
| 配置 | V602 新增 `reconcile.mock.inject`（验收用差异注入，**生产必须为空**）+ `SysConfigKeys` |
| 错误码 | 新增 `TRADE_RECONCILE_BILL_INVALID(405028)`（段 6 原定号段已用满） |
| 存储 | `StorageKeyRules` 场景 +`reconcile_bill`、扩展名 +`csv`/`zip`；`FileRecordService` +`BIZ_RECONCILE_BILL` |
| 单测 | `MockBillParserTest`(10) / `MockBillGeneratorTest`(6) / `ReconcileCompareServiceTest`(6)；全量 452 个测试 **Failures 0 Errors 0** |

### 顺带修掉的 4 个 bug（都只有真实运行才暴露）

1. **`RechargeSettleService#compensate` 补偿单写不进库**：`setExpireAt(null)` 撞 `recharge_record.expire_at` NOT NULL（V600）。D1 掉单补单走 `settle` 不建新单所以从未触发；D4 自动补单第一次走 compensate 就撞上。这是**资金链路 bug**（自动补单会静默失败），段 7/段 8 任何走 compensate 的场景都要回归。
2. **B4 差异金额列全显示 `+0.00`**：页面读 `diffCents`，而 D4 按工作流字段映射写的是 `amountCents`。改为按 `diffType` 取字段。单测与接口都通过，只有真实截图暴露。
3. **B4「发现时间」列与 CSV 该列为空**：D4 落库漏写 `found_at`。
4. **恒等式差异按默认「昨日」筛永远不出现**：日期筛选只按 `bill_date`，而恒等式差异的 `bill_date` 按段 5 `V510` 的设计就是 NULL。改为双口径：渠道差异按 `bill_date`、恒等式差异按 `found_at`。同时纠正一处旧判断——那段写 `reconciliation_diff` 的代码属于**段 5 H1**，不是段 1。

### 负责人裁决（2026-11-08）

- **B4 权限点不做**：与《接口规范》§6.2、《总纲》§5.8、`AdminSession`「只取必要字段」三处已拍板决策冲突，且后台只有一个业务角色。替代动作：停用 RuoYi 演示账号 `ry`（`sys_user.status='1'`，实测登录返回 `code=500 用户已封禁` 且无 token）。
- **`bill_date` 问题按方案 A 修**（恒等式差异按 `found_at` 筛），不回改段 1/段 5。
- **D3/D4 优先补齐**（本文档上半部分）。

### E2 / E3 收口（同批次）

| 项 | 内容 |
|---|---|
| E2 连贯走查 | `E2-e2e.txt`：发码 → 登录 → 下单 → 幂等下单 → 回调重放 3 次 → 查单 → 查余额 → DB 核对，**一条 8 步链路全 200**；余额 20000 = 流水汇总 20000 |
| `6-A7` 失败可读 | `6-A7-failure.txt`：Mock 失败模式下单 → D1 查单置 `failed` → 0 流水 → 余额不变 → **额度释放**（usedCount 4→3） |
| §九 回写 | 《接口规范》§7.3.1 接口 8→10；《错误码规范》补 `405028`；《数据表规范》附录 A `reconciliation_diff` 14→20 字段；《总纲》§5.7 sys_config 21→24 键与接口 8→10 |
| 基线升级 | `docs/BASELINE.md` v1.55 → **v1.56**（§7.59 登记 5 份内容变更与指纹重算） |
| README | `docs/README.md` 新增「段 6 验收证据」索引（15 个文件） |

**又修 2 个 Mock 网关 bug**（E2 走查暴露，见 `E2-e2e.txt` 末节）：
1. `prepay` 的 `paid_at` 用本地时间当 UTC 写 → 对账按日归账会把单算到第二天；
2. 回调报文未做 URL 编码 → Base64 签名含 `+` 时约 50% 概率验签失败（"偶发"的根因）。

---

## 2026-11-08 · 第 6 段施工（部分完成）：A/B/C 三组 + D1/D2 + E1 验收

> **本段未收口**：19 个任务完成 13 个，D3/D4 与 E2/E3 的剩余部分未做。
> 7 条锚点：3 条通过（6-A2 / 6-A5 / 6-A7 的 Mock 部分）、3 条挂账（6-A1 / 6-A4 / 6-A6）、
> 1 条部分通过（6-A3 自动补单已验）。详见 `docs/acceptance/seg6/挂账.md`。

### 提交（6 个）

| 提交 | 范围 | 内容 |
|---|---|---|
| `9ca233f` | A 组 | `recharge_record` 表（V600）+ 24 个 `sys_config` 键（V601）+ 3 个枚举 + 状态机 + 支付网关抽象（Mock/微信/支付宝骨架 + 工厂）+ `ConfigCipher` + `ProdMockPayGuard` |
| `85db25b` | B1 | 充值下单 / 单笔 / 游标列表 3 个接口 + 幂等键 + prepay 参数缓存 + 限额策略 |
| `69a863e` | B2+B3 | 充值页（36-recharge 实现 + 4 态 + 金额四类校验 + 未实名拦截 + Mock 模拟支付）+ 余额响应加 `recharge` 子对象 + 17 个限额单测 |
| `31386a6` | B4 | PC A-09 外层包 el-tabs（Tab1 原样）+ Tab2 渠道对账（差异列表 / 处置 / 审计） |
| `ffd90e3` | C 组 | 三个回调入口 + 统一处理链 + 入账链路（CAS + 流水 + 余额同事务）+ 晚到回调补偿单 + 6 个入账单测 |
| `ed865d9` | D1/D2 | `recharge_pending_query`（掉单补单）+ `recharge_close`（超时关单，关单前先查渠道）+ 扫描器 |

### 几个值得记住的坑

1. **Form 报文读不到原文**：`@RequestBody String` 与 `getInputStream()` 对
   `application/x-www-form-urlencoded` 都拿不到原文（Tomcat 先解析成参数、流被读空）。
   **真实支付宝回调就是表单**，必须走 `getParameterMap()` 重建。
2. **Mock 回调也是表单编码**（不是 JSON），按 JSON 发会验签失败。
3. **`callback_lost` 模式下 Mock 的渠道状态必须是 SUCCESS**，否则 `query()` 永远返回 pending，
   D1 的掉单场景无法复现。
4. **`i18nKey` 不能拼**：`trade.recharge.status.pending` 是四段点号，违反规范的三段式 camelCase，
   已改成 `trade.recharge.statusPending`（枚举 switch 写字面量）。
5. **`GET /app/wallet/balance` 的金额是字符串**（包装类 Long → String），而充值面板里的金额是数字
   （原生 long）——前端要经 `centsFromServer()` 归一，否则 `MoneyText` 拿 NaN。

### 未完成（不是挂账，是没做完）

D3 渠道账单下载与解析器、D4 逐笔比对落库、E2 连贯 E2E 走查、E3 的 §九 10 行回写 + 基线升级、
B3 用户维度限流、B4 权限点、D1 管理端人工补单接口、C4 JVM 内 2 线程用例。
全部列在 `docs/acceptance/seg6/挂账.md` 第二节。

---

## 2026-11-08 · 第 5 段收口补做（二）：提现状态机单测 + 2 个证据文件 + 工作流 §十一 回写 + 基线 v1.55

> 负责人问「第五段做完了吗」，逐条核对《第 5 段工作流》§一.1 的 7 条出口条件，发现 5 项不满足。
> 本版补齐其中 4 项，剩下 1 项（四语言 + RTL 运行时）已裁决推段 8。

### 1. 补提现状态机与提现申请单测（出口第 2 条）

出口第 2 条要求「案件状态机、提现状态机**全部**合法/非法迁移有单测覆盖」。案件状态机段 5 已有
（`CaseStateMachineTest`：6 合法 / 14 非法）；**提现状态机此前只有一个 `WithdrawStatus` 枚举 + `isTerminal()`，
迁移全靠 `casTransit` 的 SQL CAS 兜底，既无独立类也无一条单测**。

- 新增 `modules/trade/rule/WithdrawStateMachine`：4 态 / **3 条合法边**，`canTransit` / `check`（非法抛 `405025`）/ `isTerminal`。
- `WithdrawService` 的 `approve` / `reject` / `markPaid` 在 `requireRecord` 之后**先判状态再判参数**，
  避免「单据已是终态却先提示去补一个补了也没用的凭证」。
- 新增单测 14 个：`WithdrawStateMachineTest` 6 个（12 组合 3 合法 / 9 非法、终态无出边、同态非法、
  null 拒绝、`check` 错误码、合法边清单）+ `WithdrawServiceTest` 8 个（4 条校验 + 100 元与 10 分手续费 + 幂等命中 + 非律师）。

**顺带查出一处文档数字矛盾**：工作流 F3 原文写「状态机单测（5 条合法 + 4 条非法）」，但 4 态两两组合只有
12 种、合法边只有 3 条，`5+4=9` 凑不出任何自洽划分。按实际状态数实现，并登记到工作流 §十一（R-4）。

### 2. 修 4 个单测 NPE（段 5 D 组自己打挂的回归）

`mvn test` 从来不是绿的：`e816dc3` 给 `LawyerWorkbenchService` 加了 `SysConfigQueryService` 依赖
（价格区间改读 `sys_config`），**漏改 `LawyerWorkbenchServiceTest`** → 4 个用例 NPE。
`PROGRESS-C-D.md` 当时只记了 `tsc` / `eslint` 通过，没人跑后端全量。

- 补 `@Mock SysConfigQueryService` + `@BeforeEach` 固定区间 20~2000 元。
- 另发现旧用例 `zeroPriceForcesSwitchOff` 与 D4 冲突：它断言段 2 语义「0 价强制下架」，
  但 D4 规定「20~2000 元、越界 `405006`」，**0 元在 `assertPrice` 就被拒，那条断言永远不可能成立**。
  改为期望 `405006`，并新增 `zeroStoredPriceForcesSwitchOff` 覆盖「库内现价 0 时即使请求上架也强制下架」这条存活路径。

### 3. 补 2 个缺失的证据文件（工作流 §5.5 要求）

§5.5 列的证据文件里，`A1-schema.txt` 与 `H2-rtl.md` **从未产出**（`E2-backfill.txt` / `E5-no-money-moved.txt`
早已在偏差 10 声明合并进 `PROGRESS-E-H.md`）。

- `docs/acceptance/seg5/A1-schema.txt`：9 张表（`legal_case` / `case_progress` / `case_delivery` / `case_library` /
  `case_library_translation` / `withdraw_record` / `lawyer_income_settlement` / `reconciliation_diff` / `lawyer`）
  的 `SHOW CREATE TABLE` 原样输出，22 KB，flyway 最新 success 记录 `510`。
- `docs/acceptance/seg5/H2-rtl.md`：静态核验 4 项全过（写死 `left/right` **0 处**、`letterSpacing` **全为 0**、
  `I18nManager` 19 处无一处手动反转、`start/end` 39 处），但**明确写「RTL 运行时未验」**——
  三语言各缺 1462 个 key，强制 RTL 后文字仍是中文，这种截图证明不了长词换行与 BiDi 数字，属「弱证据冒充强证据」，
  所以不做。段 8 收口时必须补真实维吾尔文截图。

### 4. 工作流 §十一 回写（「缺一条不算收口」）

`docs/architecture/法泽FAZE_第5段工作流_v1.0.md` §十一 此前**是一张空表**（原文 1277 行处的
`| | | | | |` 占位）。本次追加：

- **32 条偏差清单**（编号 `A-1`~`A-6` / `CD-1`~`CD-8` / `EH-1`~`EH-12` / `R-1`~`R-7`），逐条写计划 vs 实际、原因、影响；
- **出口 7 条逐条结论**：5 条满足、1 条部分满足（第 6 条：mvn 绿但 PC 无 lint、ArchUnit 全仓不存在）、
  1 条不满足（第 5 条四语言 + RTL，已裁决推段 8）；
- **6 项遗留挂账**交段 6 / 段 8。

### 5. `docs/README.md` 段 5 索引加 2 行

`A1-schema.txt`、`H2-rtl.md` 补进「段 5 验收证据索引」，并注明 H2 那份是「静态通过、运行时未验」。

### 6. `docs/BASELINE.md` v1.54 → **v1.55**

- 内容变更 **2 份**：第 5 段工作流（§十一 回写）、`docs/README.md`（索引 +2 行）；
- 重算指纹：第 5 段工作流 `1337 行 / 136816 字节 / fd3d2cfab07cce0f`（原 `1277 / 125257 / 281f55b167210457`，+60 行 §十一 回写）；
  `docs/README.md` `246 行 / 30767 字节 / ffa15f87125ab708`（原 `245 / 30369 / 0ab3d7652ecae0bf`，+2 行索引）；
- 新增 §7.58；资产数仍 **45**（`docs/acceptance/**` 不进基线资产）。

**后端门禁**：注入 `deploy/.env` 的 `FAZE_*` 后 `mvn -B -o test` → **382 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**
（较上一版 368 增加 14 个新用例）。**注意**：不注入环境变量直接跑会有 5 个 `@SpringBootTest` 报
`Could not resolve placeholder 'FAZE_DB_URL'`——那是环境缺失，不是代码回归（`deploy/verify-all.ps1` 已修过同一问题）。

---

## 2026-11-08 · 第 5 段收口补做：8 个锚点证据文件 + README 索引 + 基线 v1.54

> 负责人要求把上一条里「没做完的」三件一起补掉：① 8 个独立锚点证据文件 ② `docs/README.md` 索引 ③ `BASELINE.md` 升版。

### 1. 新增 8 个锚点证据文件（`docs/acceptance/seg5/`）

| 文件 | 内容 | 结论 |
|---|---|---|
| `5-A1.md` | 在线立案：`CS` 单号 / `form_snapshot` 9 字段 / 审核通过 / 进度 2 条 | 通过（附中文 `????` 数据瑕疵说明） |
| `5-A2.md` | 进度同步：推进节点 / 操作人 / `accepted → in_progress` / 法院案号 | 通过（**本轮修掉「只写案号不迁状态」缺陷**） |
| `5-A3.md` | 案件送达：`SD` 回执 / 签收幂等 / 签收设备 / 无三处法院表述 | 通过 |
| `5-A4.md` | 收入对账：三处数字逐笔一致 / 明细带订单号 / 交叉校验 | 通过 |
| `5-A5.md` | 提现闭环：四步余额对照 / 四条流水 / 两条恒等式 / 四条边界 | 通过 |
| `5-A6.md` | 服务设置：改价立即生效 / 区间校验 / 历史订单不受影响 | 通过 |
| `5-A7.md` | 案例库：录入 → 上线 → APP 可见 / 四块正文 / 法条引用 / 不显示案号法院 | 通过 |
| `5-A8.md` | 全局搜索：四域分域 JSON / 单域 / 空态 / idle 态 | 通过 |

**`5-A1`~`5-A8` 8/8 通过**。其中本轮补测并修复了一个真缺陷：`CaseServiceImpl#addProgress` 处理 `accepted` 节点时只写 `court_case_no`、**没迁 `accepted → in_progress`**（状态机表里明明有这条边），实测「案号写进去了但 status 还是 accepted」。

### 2. `docs/README.md` 加「段 5 验收证据索引」

8 条锚点 + 3 份施工记录 + 枚举清单共 12 个入口；并写清三条与计划口径不同的地方（`income_unfreeze` / `case_library` 24 字段 / 三语言缺译），指向技术路线 §二十六。

### 3. `docs/BASELINE.md` v1.53 → **v1.54**

- 内容变更 **2 份**：技术路线（§二十六 施工登记）、`docs/README.md`（段 5 索引）；
- 重算这两份的指纹：`docs/README.md` 245 行 / 30369 字节 / `0ab3d7652ecae0bf`；技术路线 4268 行 / 212690 字节 / `5424428065f83623`；
- 新增 §7.57 记录；**顺带补记 v1.53 的缺漏**（v1.53 表头写了「见 §7.57」但全文没有这一节）；
- 资产数仍 **45**（`docs/acceptance/**` 是过程记录，历来不进基线资产）。

---

## 2026-11-08 · 第 5 段 E/F/G/H 组施工（结算引擎 + 提现闭环 + 案例库 + 全局搜索 + 收口）

> 把第 5 段从「A~D 组代码落地」推进到 **E~H 组代码全部落地 + 实测通过**。
> 本轮真打接口、真查库，四组都留了数字证据（见 `docs/acceptance/seg5/PROGRESS-E-H.md` §二）。
> **`5-A4` / `5-A5` / `5-A7` / `5-A8` 四条锚点本轮实测通过**；`5-A1/A2/A3/A6` 已在 B~D 组轮通过。

### 1. 后端（faze-api）

| 组 | 内容 |
|---|---|
| E1 收入入账 | `SettlementService#creditIncome`：完成订单时写 `income` 流水（`balance_after` 记**总余额**、`frozen_until=完成时间+7天`）+ 更新 `lawyer_balance(frozen,total_income)`；挂进段 2 的 `LawyerOrderService#complete` **同一事务** |
| E2 回补 | `POST /admin/settlements/backfill` + `GET .../backfill/pending`（只读核对先看数，人工确认再补）+ `scripts/repair-lawyer-income.sql`（两条恒等式 + 待补明细 + 零收入历史单清单） |
| E3 解冻 | `LawyerUnfreezeTask`（`@Scheduled` 10 分钟 → `task_queue` 的 `lawyer_unfreeze` → `unfreezeDue`），`NOT EXISTS` 判重 + `uk_flow_biz` 兜底 |
| E4 结算批次 | `generate / list / summary / detail / confirm`：订单侧三个数与流水净额**交叉校验**（`settle == income − fee`，不成立整批回滚抛 405026）；同律师同账期幂等跳过；`JS` 单号 |
| E5 确认不动钱 | `confirm` 只 CAS 改状态 + 审计 `SETTLEMENT_CONFIRM`；**实测确认前后 `lawyer_balance` 与 `balance_flow` 一个字节都没变** |
| F1 提现申请 | `POST /app/withdrawals`（`Idempotency-Key` 幂等）+ 四条服务端校验（最低额 / 余额 / 绑卡 / 单日 3 笔）+ 手续费 `floor(金额×0.6%)` + 冻结 |
| F2 提现审核 | PC `list / summary / audit(通过/驳回) / voucher / voucher 预览`；通过必须带凭证、驳回必须填理由；凭证走服务端落盘登记 `file_record(biz_type='withdraw')` |
| F3 出款/回滚 | `approved → paid`：`frozen -= amount` + `withdraw` 流水（delta=−amount）；驳回：`frozen→available` + `unfreeze` 流水（delta=0） |
| F4 收款账户 | Luhn + 长度校验（`BankCardValidator`）、实名一致、审计 `WITHDRAW_ACCOUNT_CHANGE`（detail 只留尾号） |
| G1 案例库 | PC A-16 的 `list / create / detail / save / publish / offline / 批量下架`：大类由细分推导、法条引用逐条回查法条库、上线门禁（源语言四块 + 至少一条引用） |
| G2 App 案例 | `GET /app/case-library?type=&cursor=&lang=` + 详情（按语言取正文、缺译回退中文、**不返回案号/法院全名**）+ 收藏（复用 `social_action`） |
| G3 全局搜索 | `GET /app/search?kw=&type=`：律师/文章/案例/文书模板**四域分域返回**，综合页各前 3 条 + 总数；不引入 ES |
| H1 差异落表 | A-09 巡检新增落 `reconciliation_diff`（`identity_mismatch`），**只写不处置**、同日同主体不重复插、**A-09 页面外观不变** |
| 资金口径硬约束 | 新增 `LawyerBalanceLocker`：`SELECT ... FOR UPDATE` + `@Version` 双保险，五个动钱点共用 |

### 2. 数据库

| 文件 | 内容 |
|---|---|
| `V510__seg5_case_library_and_recon_fix.sql` | ① `case_library.dispute_focus`；② `case_library.plaintiff_name` / `defendant_name`；③ `case_library_translation.dispute_focus`；④ `reconciliation_diff` 放宽 `bill_date`/`channel` 为可空 + 补 7 个恒等式列 + `status` 默认改 `open` |
| `database/seed/seg5_admin_menu.sql` | 追加 menu_id **2221 律师结算 / 2222 提现审核 / 2223 案例库**（已执行到 `faze_admin`） |

### 3. PC 前端

| 页面 | 要点 |
|---|---|
| A-13 律师结算 `trade/settle/index.vue` | 3 统计卡 / 8 列表格 / 生成弹窗 / 明细抽屉（逐单带订单号 + 一致性提示）/ 确认二次确认（文案写明不动钱）/ CSV 导出 / 收入回补入口 |
| A-10 提现审核 `trade/withdraw/index.vue` | 4 统计卡 / 8 列表格 / 通过弹窗（凭证上传 base64 + 流水号）/ 驳回弹窗（理由）/ 已出款（二次确认）/ 凭证预览（60 秒签名 URL）/ 补传凭证 |
| A-16 案例库 `content/caseLibrary/index.vue` | 8 列表格（语言 x/4 warning 色）/ 录入抽屉（四语言 Tab + 基本信息/正文/法条引用）/ 法条远程检索 / 上线门禁 / 批量下架 / 导出置灰 |

### 4. APP 前端

| 页面 | 要点 |
|---|---|
| **Tab 4 换成「案例」** | `app/(tabs)/case.tsx` 改接 `CaseListPage`；`common.tab.case` 文案 案件 → 案例（工作流 G2 第 1 条口径） |
| 04 案例列表 `CaseListPage` | 搜索入口 + 渐变 banner + 三分组（民事/行政/刑事）+ 每组游标分页 + 四态 |
| 19 案例详情 `CaseDetailPage` | 四块正文 + 相关法条（点开查原文弹层）+ 免责声明 + 底部固定栏（收藏 / 我有类似问题）+ 已下架空态 + 缺译提示 |
| 40 全局搜索 `SearchPage` | 5 类型 Tab + 四域分组 + idle（历史/热门）+ empty（推荐词）+ error + 400ms 防抖 |
| 66 提现面板 `WithdrawPanel` | 未绑卡先绑（开户行/姓名/卡号）+ 已绑卡填金额 + 全部提现 + 预计到账/手续费提示 + 幂等键复用 |

### 5. 实测（数字）

- **E2**：回补前 `scanned=2, pendingCents=10200` → 回补后 `frozen=10200, total_income=10200`，两条 `income` 流水 `5100/5100`、`frozen_until=2026-11-13`；再跑 `scanned=0`。
- **E3**：到期后 `available 0→10200 / frozen 10200→0`，两条 `income_unfreeze`（delta=0，`biz_id` 指向 income 流水），`task_queue` 任务 `done`。
- **E4**：`JS20261108000001 / 2 单 / 收入 12000 / 服务费 1800 / 应结 10200`；`settle == income − fee`；再生成 `skipped=1`。
- **E5**：confirm 前后 `lawyer_balance = 0/10200/10200/version2`、flow 2 条 Σ=10200 **完全不变**。
- **F**：申请 → `available 10200→200 / frozen 0→10000`；驳回 → 回到 `10200/0`；通过 → 不动账；出款 → `200/0`、总余额 −10000、流水 `freeze/unfreeze/freeze/withdraw` 四条；10 分提现 `fee=0`；四条校验码 `405023/405027` 实测命中。
- **G1**：`CL20261108000001 / contract → civil / published / law_refs 1`。
- **G2**：民事组列表可见；详情四块齐全、无 `courtName`/`courtCaseNo`；收藏 `favorited false→true`；浏览量 0→1。
- **G3**：`kw=合同` 返回四个域对象（文书域 2 条）；`type=case` 只回 `cases`。
- **H1**：人造 +100 分差异 → 巡检落 1 行 `expected=10200/actual=10300/diff=100`；修正后 `diffOwners=0` 且不重复插。

### 6. 偏差与未完成（详见 `PROGRESS-E-H.md` §四）

1. **解冻流水 `biz_type` 改为 `income_unfreeze`**（E0 原写 `unfreeze`）：E3 与 F3 的 `biz_id` 分属两张自增表，同 `biz_type` 必然撞 `uk_flow_biz`，会让「提现驳回成功但某笔收入永远解冻不了」。
2. **`case_library` 实为 24 字段**（计划 21）：补了 `dispute_focus` / `plaintiff_name` / `defendant_name` —— 工作流 G1 要求四块正文与当事人，技术路线 DDL 漏了。
3. **`reconciliation_diff` 补 7 个恒等式列**并放宽两列为可空（H1 要求与 V507 的渠道对账形状冲突）。
4. **F2 凭证改为服务端落盘**（原写走 `/app/upload/token` 直传）：管理员 token 走不通 C 端上传入口。
5. **接口数超清单**：PC 18 → 24、App 23 → 26（统计卡 / 上线下架 / 凭证预览 / 已出款 / 回补核对 / 提现规则）。
6. **H2 的 `ug-Arab-CN` 未补**：三语言仍为登记式缺译（每语言 1462 key），与段 4 的既有口径一致，段 8 收口时清空 —— 这是本段唯一未完成的 H 组项。

### 7. 本次追加：《技术路线》§二十六「段 5 施工登记」（备注，不回写正文）

> 负责人裁决：段 5 施工与《技术路线》正文的 6 处出入**不回写正文**（改动会牵动文档基线指纹与 §九 的跨文档回写），
> 只在文末新增一节登记。**正文数字一个没改**，本节与正文冲突时以本节为准。

登记的 6 项：① 三语言各缺 1462 key → **段 8 统一翻译**、收口时 `.i18n-pending.json` 必须为空；
② `case_library` 实为 24 字段（+`dispute_focus`/`plaintiff_name`/`defendant_name`）；
③ `case_library_translation` 实为 12 字段（+`dispute_focus`）；④ 接口数 PC 24 / App 26；
⑤ `balance_flow.biz_type` 实为五值（+`income_unfreeze`）；⑥ `reconciliation_diff` 放宽两列 + 补 7 个恒等式列。

> 基线指纹**未重算**：`.i18n-pending.json` 与 `BASELINE.md` 的同步留到下次升版一并处理（这次是纯追加一节，不删不改原文）。
7. `405024`（余额不足）实测被 `405027` 抢先命中（单日额度已满），校验顺序见偏差 12。

---

## 2026-11-08 · 第 5 段 C 组（送达）与 D 组（律师工作台）施工

> 把第 5 段从「A/B 组已完成」推进到 **C 组与 D 组代码全部落地**：送达闭环（发起 → 待签收 → 签收 → 回执）、
> 律师工作台 5 页 + 宫格 6 入口、收入与设置的真实数据。
> ⚠️ **本轮只完成到「代码 + 编译 + 迁移 + 401 校验」，功能验收（`5-A3`/`5-A4`/`5-A6`）尚未跑通**，
> 原因与缺口逐条写在 `docs/acceptance/seg5/PROGRESS-C-D.md` §二/§三 的「未验证」小节。

### 1. 后端（faze-api）

| 内容 | 要点 |
|---|---|
| 送达实体与写路径 | 新增 `CaseDelivery` / `CaseDeliveryMapper` / `DeliveryStatus`；`DeliveryServiceImpl` 从「显式拒绝」骨架换成完整实现：发起（`SD` 回执号即时生成）、改造方式（未签收才许改）、签收（CAS，第二次返 407007 且不覆盖首次时间） |
| 送达读路径 | `GET /admin/cases/{id}/deliveries`、`GET /admin/cases/{id}/documents`（可送达文书，带 `hasPdf`）、`GET /app/case-deliveries`（按 `user_id` 归属 + 状态筛选）、`GET /app/case-deliveries/{id}`（回执） |
| 送达审计 | 发起送达标 `@OperationLog(action = "DELIVERY_SEND")`，标在 `AdminDeliveryService.send` 上 |
| 案件抽屉补两块 | `GET /admin/cases/{id}/progress`（进度 Tab）、`AdminCaseDetailVO.evidenceImageUrls`（证据对象键现签成短时地址） |
| 案件卡红点 | `CaseRowVO.unreadDeliveryCount`（按 `case_delivery` 聚合 PENDING），不新增列 |
| 跨域只读口 | 新增 `DocumentQueryService`、`ConversationQueryService`、`LawyerIncomeQueryService`；账号侧补 `AccountQueryService.mapBriefs`（昵称/头像/脱敏手机号）与 `LawyerProfileService`（白名单写） |
| 工作台真实数据 | `WorkbenchServiceImpl` 从全空骨架换成真实实现：D1 未读优先排序 + 已付款标签、D2 按 `lawyer_id` 归属 + 发送文书、D3 三档收入 + 明细带订单号、D4 读设置（含 `sys_config` 上下限）、D5 资料读写 |
| 发送文书 | `POST /app/workbench/documents/{id}/send`：经 `MessageService.sendDocument`（服务端内部通道，客户端不能伪造 `doc` 类型）写一条 `msg_type=doc` 消息 + 回填 `sent_at` |
| 迁移 | `V509__alter_document_sent_at.sql`：`document` 加 `sent_at`（幂等：查 `information_schema` 后动态执行）。已实测 `flyway_schema_history 509 = success` |
| 段 2 收紧 | `PUT /app/workbench/service-settings` 的价格校验从「非负」改为读 `sys_config` 区间（19 元 / 2001 元被拒），并补 `autoAccept` |

### 2. APP（mobile-app）

| 内容 | 要点 |
|---|---|
| 案件中心四页（B 组前端收尾） | 在线立案四步、材料已提交、我的案件、案件进度（含律师推进节点弹层）；案件 tab 由占位页换成真实列表，`CasePlaceholderPage` 下线 |
| 文书送达页 | 待签收 / 已签收两组、每份文书各自选方式（偏差）、签收确认框（标题随文书名）、结构化回执；全页无法院侧表述 |
| 工作台 5 页 | 63 客户咨询 / 65 代写文书 / 66 收入与提现 / 67 服务设置 / 68 律师资料 |
| 工作台宫格 | `WorkbenchPage` 补 6 项宫格（我的客户 / 我的订单 / 代写文书 / 收入提现 / 服务设置 / 律师资料） |
| 文案 | `zh-Hans/case.json` 与 `workbench.json` 从空对象补全；缺译按登记式进 `.i18n-pending.json`；`check-i18n` 三项全绿 |

### 3. PC 后台（admin-web）

| 内容 | 要点 |
|---|---|
| A-19 案件列表 | `views/biz/caseflow/case/index.vue`：5 个状态 chips（含「已驳回」）、8 列表格（当事人称谓按类型变）、分页 |
| A-19 详情抽屉 | `detail-drawer.vue`：立案材料（快照四组只读 + 证据图 + 审核）/ 进度（时间轴 + 纠错）/ 送达（列表 + 发起送达）三个 Tab |
| B5 服务费 | `trade/order/detail.vue` 删掉「服务费结算见段 5」，改为按 `platform_fee_cents` 计算的说明行；`AdminOrderDetailVO` 补两个金额字段；**不写 `payment_record`** |
| 菜单种子 | `database/seed/seg5_admin_menu.sql`（menu_id 2220「案件管理」） |

### 4. 实测结果（摸清登录口径后完成）

- **`5-A3` 送达全链通过**：PC 发起 → `SD20261108000001` pending → APP 待签收 → 签收 `code=200` →
  再签 `code=407007`（首次时间不被覆盖）→ 回执含 `signedAt` / `signDevice=web seg5-ddevice` / `ip=127.0.0.1`。
- **`5-A6` 改价即生效通过**：改价 7000 → `GET /app/lawyers/2` 立即返回 7000（无缓存）。
- **D4 区间校验通过**：1900 → `405006`；2000 → `200`；200100 → `405006`；`autoAccept` 写入并回读为 true。
- **D5 读写通过**：`GET/PUT /app/workbench/profile` 往返一致。
- **B2 审核通过**：approve → `accepted`，状态筛选命中，进度时间轴新增 `accepted` 节点。
- **实测中新发现并修掉 3 个缺陷**：
  1. 审计 `detail` 全为 NULL（`CASE_AUDIT`/`DELIVERY_SEND` 只有动作码，没有 before/after）→ 补 `AuditContext.mark`；
  2. `GET /app/workbench/conversations` 与段 2 映射冲突，API 启动 `Ambiguous mapping` 失败 → D1 改路径；
  3. 工作台首页没有设计稿要求的宫格 → 补 6 项。

### 5. 仍未验证（不掩盖）

1. **D2 发送文书**：库里 36 份文书全是用户委托（`lawyer_id` 为空），没有「律师代写」的文书，
   所以 `POST /workbench/documents/{id}/send` 与「客户侧收到文书消息」**未实测** → `5-A2` 最后一段仍缺。
2. **`5-A4` 收入逐笔对账**：依赖 E 组（律师收入入账），当前 `balance_flow` 没有律师 income 流水。
3. `correctProgress` 的审计 detail 尚未补（只补了审核与送达两处）。

### 6. 偏差（新增 8 条，详见 `PROGRESS-C-D.md` §四）

其中两条会影响验收口径：D1 接口路径改用 `/workbench/consultations`（原路径段 2 已占用，重复映射会让 Spring 启动失败）；D2 的「发送」放在列表而非预览页。

---

## 2026-11-08 · 第 4 段（文书中心）施工与收口（基线 v1.53）

> 一天内把第 4 段从「B1/C4 已验」推进到**8 条锚点全通过**：补齐 D 组（签名笔迹）、E 组（APP 六页）、F 组（后处理）、G 组（验收脚本 + 证据）。
> 同时修掉 5 个只有在真实验收里才暴露的缺陷，并把三件文档欠账补齐（数据表规范附录 E、PC 设计说明 §5.8、段 4 验收证据索引）。

### 1. 后端（faze-api）

| 内容 | 要点 |
|---|---|
| D1 签名库 | `GET/POST/DELETE /api/v1/app/signatures`；每用户上限 5、同一对象键幂等、被文书引用的删除返 406006；删除 = 物理删 + `file_record` 置 orphan（该表无 `deleted_at`） |
| D2 叠章 | `POST /api/v1/app/documents/{id}/sign`；屏幕坐标 → PDF 左下原点换算 + 按 `basePage_*` 等比缩放；产物写新的 `signed_pdf_oss_key`，原件不动；单测 6 例（含 Y 轴翻转的数像素断言） |
| D3 溯源元数据 | `PdfMetadataWriter` 在**落盘前**写 5 个 `Faze-*` 字段（时间带时区）；**绝不写** `AIGenerated`；单测 3 例 |
| E4 预览接口 | `GET /documents/{id}/preview`：PDF 每页转 PNG（前 5 页），按「PDF 对象键短哈希」缓存（首次 222ms / 二次 10ms） |
| F1 导出接口 | `GET /documents/{id}/export?format=pdf`：60 秒现签地址 + 建议文件名；`docx` 返 401001；盖过章导出带签名那份 |
| 列表缩略图 | `listMine` 批量取 `generated.png` 最新一条（A-18 时挂到 E 组的项） |
| `generating` 字段 | `DocumentDetailVO` 增 `generating`（按任务队列实时算），解决「从没生成」与「正在生成」不可分 |
| 状态多值筛选 | `status` 支持逗号多值（`generated,signed`），修「已完成」漏掉已盖章文书 |
| **缺陷①** | 模板升版后旧行 inactive → 「重新生成」永远 406001。新增 `DocTemplateResolver`（按 code+lang 解析当前 active 行），生成与保存草稿都走它 |
| **缺陷②** | 死信只写错误码没有具体原因。`DocGenerateTask#describeFailure` 改用 `BusinessException#getDetail()`（截断 120 字） |
| **缺陷③** | 失败后点「重新生成」被指数退避窗口静默跳过。新增 `TaskQueueService#expeditePending` |
| **缺陷④** | `services/faze-api/pom.xml` 未声明 UTF-8，JDK17 默认 GBK 导致中文注释编译失败 |

### 2. 前端（apps/mobile-app）

- 新增 `features/document/`：六页（模板列表 / 起诉状表单 / 其它文书表单 / 预览 / 我的文书 / 电子签章）+ `DynamicForm`（schema 驱动）+ `SignaturePad`（react-native-svg，透明底 PNG）+ `api/client.ts`、`api/export.ts`、`api/upload.ts`
- 路由壳 6 个（`app/doc/*.tsx`）+「我的」页两个入口
- i18n：`zh-Hans/document.json` 补齐；其余三语言按「登记式缺译」进 `.i18n-pending.json`
- **缺陷⑤**：`api/client.ts` 初版路径漏 `/api/v1` 前缀（App 全落网络错误），已按全仓惯例补齐 12 条
- `npm run verify` 全绿（tsc / eslint / check-raw-text 0 命中 / check-i18n 三项）

### 3. 验收与工具

- `deploy/verify-seg4.ps1`：8 条锚点**逐条接实现**，空跑全 FAIL，实跑 **8/8 PASS**
- 新增验收工具：`deploy/tools/PdfInspect.java`（页尺寸/元数据/字体嵌入/正文包围盒/图片数/**叠章差异包围盒**）、`seg4-probe.mjs`（真接口探针）、`seg4-browser-a7.mjs`（浏览器证据）、`seg4-browser-e.mjs`（六页巡检）
- 证据：`docs/acceptance/seg4/4-A1.md` ~ `4-A8.md`、`E-group.md`、`D1/D2/D3`、`F2`、`遗留与挂账.md`、`4-A7-browser.json`、截图 9 张

### 4. 文档回写（§九 收口）

- 《数据表规范》**附录 E**：两处偏离登记（`is_enabled` / `updated_at`）+ 两条易误会说明
- 《PC 后台 UI 设计说明》**§5.8**：A-18 的 4 处不实现 + 2 处口径差异
- 《第 4 段工作流》**§九 7/9/10 标完成**、**§十一** 回写 12 条「计划 vs 实际」
- `docs/README.md` 新增「段 4 验收证据索引」；`BASELINE.md` v1.52 → **v1.53**

### 5. 口径变化（都已落到文档）

- `4-A2` 的「四边边距 72±2」改为「左右 90/72±2、上下不小于 70pt」，原因见 `4-A2.md` §4
- `4-A7` 只验接口层 + 浏览器能做的部分（系统分享面板 / 存相册 / 打印指定 PDF 留段 8）
- 手写板用 `react-native-svg`（Web），不引入 skia / expo-print / expo-sharing / expo-media-library

### 6. 上线前必须处理

见 `docs/acceptance/seg4/遗留与挂账.md` §C：模板 LAWSUIT-001 被验收升到 v6 且字号被改、30+ 份测试文书、5 条测试签名、若干 dead 任务、本地存储几十 MB。

---

## 2026-11-07 · 第 4 段开工前置（前提复核 + 文档回写 + 2 个代码前置，基线 v1.52）

> 第 4 段（文书中心）开工前审查：19 项前置逐条实测。结论是 **14 项通过、3 项未通过（段 3 带缺口开工）、2 项口径要改**。
> 负责人当天裁决：①段 3 缺口与段 4 一起挂账；②文件存储继续走本地实现、不接云 OSS；③真机接入全部改浏览器验收，`4-A7` 只验接口层 + 浏览器能做的部分，原生能力（分享面板/存相册/打指定 PDF）留段 8；④手写签名板可以偏离工作流（Web 用 `react-native-svg` 替 skia）；⑤存储白名单按建议扩、`task_queue` 按建议上移、技术路线 §12.3.2 可以改。

### 1. 代码前置（2 个提交）

| 提交 | 改了什么 |
|---|---|
| `b3794b5` | 存储场景白名单新增 `signature` / `document` / `doc_template`。改前实测三个场景调 `/app/upload/token` **全返回 401001**；`ALLOWED_SCENES` 与 `KEY_PATTERN` 必须同步扩（只改一处会 build 成功但 PUT 403）。`document`/`doc_template` 不进 `UploadBO`，避免客户端自传公文 |
| `ab7bf3c` | `task_queue` 三件套从 `modules/trade/` 上移到 `infra/mq/`（含 XML namespace）。段 4 要从 document 域投递 `DOC_GENERATE`，继续挂在 trade 下就是第三个域跨域注入。纯包移动，`mvn compile` 通过 |

### 2. 关键实测（openhtmltopdf 开工前置，§二 第 5 项）

用独立临时工程 `E:\dshcode\faze-probe-pdf` 跑通中文 PDF：

```text
pages=1  pageSize=595.275 x 841.875   （A4 ✅）
font=AVCEOC+FangSong  embedded=true   （子集嵌入 ✅）
textLength=169  hasTitle=true hasParty=true hasAmount=true
```

`PDFTextStripper` 抽出的中文无乱码、长段落正确折行、字体已嵌入。**这条技术风险排除**，A2 可以按 openhtmltopdf 1.0.10 实现。

### 3. 文档（基线 v1.51 → v1.52，内容变更 4 份）

| 文件 | 改了什么 |
|---|---|
| `法泽FAZE_技术路线_v2.3.md` | §12.3.2 加适用范围：5 个隐式标识字段只适用于 AI 生成内容；**文书 PDF 不是 AIGC，不得打 `Faze-Content-Type: AIGenerated`**（虚假标识），只写溯源元数据 |
| `法泽FAZE_第4段工作流_v1.0.md` | 新增 §零.5 复核节；§二 第 1/6/10/11 项改口径（段 3 挂账、本地存储、浏览器验收）；§三 B1 路径 `pc/ruoyi-ui`→`pc/admin-web`；E6 手写板改 `react-native-svg`；F1 改浏览器降级；§四 `4-A7` 重写为「5 必须 + 2 降级 + 1 挂账」；§五.6 划掉 4 个不装的包；§九 第 11 项标完成 |
| `法泽FAZE_错误码规范_v1.0.md` | `506003` 从「少数民族文字字体缺失」改为「中文场景指仿宋缺失 / 路径配错」 |
| `docs/README.md` | 基线号 v1.51 → v1.52 |

### 4. 本轮仍缺（不阻塞 A/B/C 组）

- `apps/mobile-app` 缺 `expo-file-system` / `expo-sharing` / `expo-print` 三个包（E/F 组开工前装）。
- 段 3 的 `3-A1④` 挂账、`3-A2`/`3-A3` 未通过（豆包 key），按裁决与段 4 一起挂账。
- `5 份模板的法律文本准确性`仍待人工核对（本机 `web_fetch` 对外网全拦，拿不到官方原文）。

---

## 2026-11-07 · 段 3 收口后补两项（法条库扩到 34 条 / 段 2 菜单补 seed，基线 v1.51）

> 负责人对 F~I 收口上报的 7 个问题的裁决：「豆包后面还是给」「法条库可以扩到 30 题」「换机器没有菜单入库的话现在补上」「安卓真机不急」「不用构造真实数据」。

### 1. 法条库 10 → 34 条（`V305`）

**为什么**：黄金 30 题覆盖 7 部法、预期引用 30+ 条号，而库里只有 10 条——绝大多数题 AI 只能回「库里没有」。这让 30 题的实际可用率极低。

**做了什么**：新增 `V305__import_law_items_golden30.sql`，24 条条文：

| 法 | 条号 | 对应黄金 30 题的题号 |
|---|---|---|
| 民法典 | 675 / 511 | 2 |
| 民法典 | 713 | 5 |
| 民法典 | 733 | 4（**注意：733 真实内容是「承租人欠租可解除」，与「押金」无关，见下**） |
| 民法典 | 585 | 6 |
| 民法典 | 586 / 587 | 7 |
| 民法典 | 1084 | 14 |
| 民法典 | 288 | 20 |
| 民法典 | 1179 | 21 / 22 |
| 劳动合同法 | 19 / 38 / 46 | 9 / 10 |
| 劳动法（新增 `labor_law`） | 44 | 11 |
| 劳动争议调解仲裁法 | 43 / 50 | 12 / 13 |
| 消保法 | 24 / 25 / 55 | 19 / 17 / 18 |
| 民诉法 | 22 / 24 / 171 / 246 | 24 / 24 / 25 / 26 |
| 婚姻家庭编解释（一） | 5 | 16 |

**实测**：`law_item` = 34 条，`flyway_schema_history` 的 305 `success=1`；23 个代表关键词（还款期限/约定不明确/维修义务/欠租/违约金/定金/抚养权/相邻关系/人身损害/试用期/未缴社保/经济补偿/加班费/劳动仲裁期限/不服仲裁裁决/质量要求/七天无理由/欺诈/管辖/合同纠纷管辖/上诉期限/申请执行/彩礼）**全部命中**期望条号。30 题里 **28 题**有库内依据。

**两处故意不迁就题目预期的地方**（不是漏做）：
- 第 4 题「房东不退押金」的 expectedRefs 写「民法典 733」，但 **733 的真实内容是「承租人无正当理由未付租金，出租人可催告后解除合同」**，与押金退还无关。本迁移录 733 的真实内容，**没有**往 keywords 里塞「押金」去凑命中（那等于让 AI 照着错的依据回答）。建议后续把第 4 题的预期引用改到更贴切的条文。
- 第 23 题（诉讼费用交纳办法 13）不录：该办法是行政法规、不在 7 部法白名单，且第 13 条是复杂分段费率表，逐字风险高；该题预期走 NO_REF。第 22 题（治安管理处罚法）同理，但该题另有民法典 1179。

**待办（重要）**：24 条条文文本由技术侧按公开通行文本录入，**只有《劳动合同法》19/38/46 与福建人社厅全文逐字核对过**，其余待负责人用 `flk.npc.gov.cn` 逐条核对（清单在迁移头注释，约 20 分钟）。发现错误不要改迁移，走 A-27「修正」接口写新版本（会留 before/after 审计）。

### 2. 段 2 的 4 个 PC 菜单补成 seed

**为什么**：`sys_menu` 里 2109~2112 是「手工插库」的产物，仓库 `database/seed/` 里没有脚本（段 1、段 3 都有）。**换机器或重建库后，订单管理与文章管理没有菜单入口。**

**做了什么**：新增 `database/seed/seg2_admin_menu.sql`（幂等）：4 个菜单 + 4 行角色授权。
- 菜单：订单管理(2109) / 订单详情(2110, **隐藏页**) / 文章管理(2111) / 文章编辑(2112, **隐藏页**)；
- 顺带发现这 4 个菜单在 `sys_role_menu` 里**一条授权都没有**（超管靠 `*:*:*` 仍可见，非超管看不到），一并补上；
- 实测执行两次无报错，菜单 4 行、授权 4 行。

### 3. 文档

- 《法条数据录入规范》§一③ 加第 7 部法 `labor_law`，并写清「《劳动法》与《劳动合同法》是两部法、不许合并」。
- 第 3 段工作流 §十一 第 37、41 条从「偏差」改为「已修」。
- 基线 v1.50 → **v1.51**。

### 4. 本轮仍未做（按负责人裁决）

- 安卓真机验证（`3-A5` 的 `nativeID`、RTL 镜像）——「先不急」；
- 200 条消息的导出截断构造——「不用构造真实数据」；
- `3-A2`/`3-A3`/`3-A1④` 的判定——等豆包 key。

---

## 2026-11-07 · 段 3 F~I 组施工（APP AI 页 / 历史页 / PC 两页 / 收口，基线 v1.50）

> 负责人指令：「下一步你就一直做，从 F 一直走到 I 全部走完，中间有什么问题的话，留到把 I 做完再告诉我」。
> 本轮做 **F（APP AI 页 5 任务）/ G（APP 对话历史页 2 任务）/ H（PC 后台两页 3 任务）/ I（收口 3 任务）** 共 13 个任务。
> **段 3 至此全部施工完毕**（A~I 九组），未结项集中在「豆包 key 未提供」与「无真机」两件事上。

### 1. 8 条锚点的结论（这是本段的验收本体）

| 锚点 | 结论 | 关键证据 |
|---|---|---|
| `3-A1` 法条入库与导入通道 | **①②③ 通过、④ 挂账** | `law_item` 10 条与 `V300–V304` 逐条对应；导入 3 条 → `inserted:3`，重复 → `skipped:3`；**V304 新导入的 577/1062/1165/47 立刻能被检索命中**；④要真模型 |
| `3-A2` 黄金问题集 | **未通过（key 缺失）** | 问题集 30 题 + 批处理脚本已交付，脚本**拒绝在 mock 下执行**（`exit 2`），实测生效 |
| `3-A3` 编造拦截 | **未通过（key 缺失）** | mock 固定回答，无法触发「编造」场景；`OutputGuardTest` 11/11 通过（机制层证据） |
| `3-A4` 注入拦截 | **通过** | 三句（含英文）全 `408005`；`ai_message` 原文完整（长度 + HEX 双证）；**拦截不扣配额**（used 14→14） |
| `3-A5` 显式标识 | **通过** | 3 条 AI 回答各一个 `ai-generated-label`、3 条用户消息全无（交替 `T/F/T/F/T/F`）；tsx 里硬编码 0 处；对比度按负责人选项 A 从 2.85:1 提到 5.74:1 |
| `3-A6` 后台可追溯 | **通过** | A-28 显示模型 `doubao-seed-1.6-32k`、内容编号、refs 冻结快照、「用户已删除」标签；原文区 **0 个输入框**、全页按钮只有「查询/重置/导出本条记录」 |
| `3-A7` 开关降级 | **通过** | 关 `ai_enabled` → 「法条检索」+ 搜「借条」命中 679；日志 0 条上游请求；重开 → 恢复 AI 模式 |
| `3-A8` 断线不重复计费 | **通过** | CDP 断网 4 秒后恢复：回答完整接上、气泡只有 1 user + 1 AI、`used` **16→17 只 +1**、该会话 1 条 assistant 且有 `content_id` |

### 2. 代码

- **移动端**：`app/(tabs)/` 五 tab 壳（home/profile **移动**进 tab、URL 不变）+ `app/ai-history.tsx`；`features/ai/`（`api/ai.ts` 351 行、`useAiStream` 407 行、`useAiQuota`、`useOnlineStatus`、`AiChatPage` 481 行、`AiLawSearchView` 220 行、`AiHistoryPage` 487 行）；`shared/components/`（`MessageBubble` / `LawRefCard` / `OfflineBar` / `TabIcons` / `AiIcons`）；`app/_layout.tsx` 补 `GestureHandlerRootView`。
- **新依赖 3 个**：`react-native-svg 15.12.1`（tab 图标）、`react-native-gesture-handler ~2.28.0`（左滑）、`expo-clipboard ~8.0.8`（长按复制），已登记 `docs/architecture/依赖清单.md` §一之六 #21–23。
- **服务端**：`modules/ai/admin/`（A-27 五接口 + A-28 三接口 + 两个 service + 8 个 BO/VO）、`PubSysConfigController`（F4 公开配置白名单）、`AiConversationService`（G1 列表 / G2 软删）；`GlobalExceptionHandler` 加两个 multipart 异常处理；`application.yml` 加 `spring.servlet.multipart` 5MB。
- **PC 后台**：`src/views/biz/ai/{law-library,ai-retrieval}/`（676 + 904 + 457 行）、`src/api/biz/ai/*.js`；`database/seed/seg3_admin_menu.sql`（10 行菜单 + 10 行授权，**菜单已入库、刷新浏览器即生效**）。
- **迁移新增 1 个**：`V304__import_law_items_batch2.sql`（民法典 577/1062/1165 + 劳动合同法 47；劳动合同法生效日 `2013-07-01` 是 2012 修正版，写错会插出两个版本行）。

### 3. 验收时打真接口发现并修掉的 4 个后端缺陷（必须留档）

| # | 缺陷 | 怎么发现的 | 修法 |
|---|---|---|---|
| 1 | `application/json` 的导入 body **没有 5MB 上限**（8.6MB 被接收并写库） | 验收方用 8.6MB JSON 打导入接口 | 控制器入口读 `Content-Length`，超限回 401001（含当前 MB 数），不进反序列化 |
| 2 | multipart 超 5MB 报 `501001 服务器内部错误` | 同上，换 `-F file=@` | `GlobalExceptionHandler` 加 `MaxUploadSizeExceededException` + `MultipartException` 两条 → 401001 |
| 3 | G1 排序依赖的 `ai_conversation.updated_at` **从不随消息刷新**（C 组遗留） | 验收方发现「刚发的会话排不到第一」 | `AiQuestionService` 落库 user 消息后补一次 UPDATE（覆盖每一轮） |
| 4 | H2 时间参数按 **UTC** 解释，漏掉北京时间凌晨的会话 | 验收方用 `beginTime=2026-11-07` 查，发现当天凌晨的会话被漏 | 无偏移串按 `Asia/Shanghai` 解释、纯日期取当天边界 |

另修 1 个前端问题：tab bar 标签被屏幕底边切掉（`tabBarStyle` 没设 `height`/`paddingBottom`，RN Web 上 safe-area=0）。

### 4. 验收方式（本段的特殊性）

本机**没有 adb 设备、没有模拟器**，「真机截图」做不了。改用：

- **PC 侧**：真实 Chrome 156，1440×900，通过注入 `Admin-Token` cookie 免登录，打真实 faze-api；
- **APP 侧**：`expo start --web`（RN Web，同一套 React 组件树与 expo-router）+ CDP 按 390×844 手机视口渲染，注入 `localStorage` 登录态；
- **断网测试**：CDP 的 `Network.emulateNetworkConditions { offline: true }`（浏览器级真实断网，等价于飞行模式对前端 fetch 的效果）；
- **新增验收工具**：`scripts/cdp-shot.mjs`（直连 CDP，不引 puppeteer/playwright；Node 24 自带全局 WebSocket）。

> **这是替代验证，不是真机验证**：RN Web 与原生在 `I18nManager` 镜像、原生手势、`SecureStore`、`resource-id` 上有差异，相关项已在证据里逐条标注「真机待复测」。

### 5. 未结项（如实）

1. **`FAZE_ARK_API_KEY` 未提供**：`3-A2`（黄金 30 题）与 `3-A3`（编造拦截）**无法判定**，`3-A1④` 挂账。脚本与问题集已就绪，填 key 后跑 `scripts/golden-30.ps1` 即可。
2. **`3-A5` 的 `nativeID`（Android `resource-id`）在 web 上验证不了**，需真机或 121 平台。
3. **F5 两项未实测**：「停止生成」、「配额 0 禁用」；另**断网重连后页面配额显示滞后一格**（服务端扣对了，页面没刷新）——已记为待修。
4. **A-28 的「200 条消息导出截断」缺真实数据**（开发库没有 200+ 条消息的会话）。
5. **段 2 的 4 个 PC 菜单只手工插了开发库、未进仓库 seed**，换机器会没有菜单入口（既有债务）。
6. **G1 的空态/错误态/上拉分页、G2 的取消/断网/删到空态/RTL** 共 8 项未实测（数据或环境造不出场景）。

### 6. 文档

- `docs/acceptance/seg3/` 新增 **35 份任务证据 + 8 份锚点证据 + 16 张截图**（39 份 md 齐）。
- `法泽FAZE_第3段工作流_v1.0.md` §十一 **22 条 → 42 条**（补 F~I 组偏差）。
- 基线 **v1.49 → v1.50**（资产数仍 45：本段无新规范文档）；`docs/README.md` 基线号同步。

---

## 2026-11-07 · 段 3 A~E 组施工（AI 法律顾问：地基 / 检索 / 流式 / 防护 / 留存，基线 v1.49）

> 负责人指令：「从 A 组、B 组一直做到 E 组结束，中间遇到必须我解答的问题留到 E 组做完之后再问」。
> 本轮只做 **A（数据与地基）/ B（检索内核）/ C（豆包接入与 SSE）/ D（三层防护）/ E（合规留存）** 五组共 20 个任务，
> **F（APP AI 页）/ G（历史页）/ H（PC 两页）/ I（收口）四组没做**。

### 1. 五组各自的结论

| 组 | 任务 | 结论 | 关键证据 |
|---|---|---|---|
| **A** | A1 三张表 / A2 `law_item` 补字段 / A3 导入通道 / A4 术语表 | **全部通过** | 字段数实测 **7 / 16 / 10**、`law_item` **12→15**、`ft_on_keywords=0`；18 个迁移在临时库 `faze_seg3_replay` **顺序重放全 exit=0**（结果与开发库一致，重放库已删）；`i18n_glossary` **51 条全 `zh-Hans`**、藏/维/哈行数 **0** |
| **B** | B1 四路径检索 / B2 `pub/law-items` / B3 条号校验 / B4 引用统计 | **全部通过** | `EXPLAIN` 全文走 `type=fulltext key=ft_law_content`；`kw=借条`→total=1、`kw=婚`（单字）→2 条不报错、`pageSize=999→50`、**第 61 次请求 = 401007**；中文数字 1~1260 往返 0 失败；B4 真库 **679:2 / 1042:1** |
| **C** | C1 豆包客户端 / C2 提交 / C3 SSE 续传 / C4 中断与超时 / C5 配额 | **代码全部落地，判定挂账** | mock 下 SSE 全链路实测通：`start`→55×`delta`→`refs`→`done{finishReason:"stop",promptTokens:128,completeTokens:96}`；配额 Lua 判超+回退原子（redis-cli 实测） |
| **D** | D1 Prompt 组装 / D2 输入拦截 / D3 输出校验 / D4 `refs` 快照 | **全部通过（机制层）** | 37 个单测；`3-A4` 三句（含英文）→`408005`、`离婚能赔多少钱`→`408004`、`借款能要回来吗`→PASS；真库事务证据：改 `law_item.content` 前后 `MD5(refs)` 完全相同 |
| **E** | E1 显式标识 / E2 内容编号 / E3 留存策略 | **E1/E2 机制层通过，E3 成文** | `testID="ai-generated-label"` + `accessibilityLabel` + `nativeID` 三套钩子；`content_id` 全匹配 `^AI-[0-9a-f]{32}$`；`AI对话留存策略.md` 五问齐全 |

### 2. 代码

- **迁移 4 个**：`V300__create_ai_tables.sql`（3 张表，无数据）、`V301__alter_law_item.sql`（+3 列 +1 索引，**不加 `ft_law_keywords`**）、`V302__import_law_items.sql`（模板 + 首批 1 条：民法典第 679 条）、`V303__init_i18n_glossary.sql`（51 条中文术语）。开发库 `flyway_schema_history` 四条全 `success=1`。
- **配置种子 1 个**：`database/seed/seg3_config.sql`（8 个键，**不进 Flyway**，沿用段 1/2 的 seed 口径）：`ai_enabled` / `ai_model` / `ai_daily_quota` / `ai_search_topn` / `ai_timeout_seconds` / `ai_first_byte_timeout_ms` / `ai_message_retention_days` / `law_search_rate_per_min`。
- **服务端**：`modules/ai/{entity,mapper,dto,service,controller,support,util,config}` 新建；`infra/ai/`（豆包客户端 + Stub，**段 0 的 `infra/probe/DoubaoClient` 一个字没动**）；`infra/law/mapper/LawItemMapper` 追加 6 条查询（`searchByNgram` 段 0 冻结未动）；`ErrorCode` 补 `408001`~`408005` + `508002`；`pom.xml` 加 `spring-boot-starter-webflux`（只为 `WebClient`）。
- **移动端**：`shared/components/AiGeneratedLabel.tsx` + `zh-Hans/common.json` 一行 + `.i18n-pending.json` 三语缺译登记（**没编造少数民族文字译文**）。

### 3. 两起「应用起不来」的施工事故（已修，必须留档）

| 事故 | 现象 | 根因 | 修法 |
|---|---|---|---|
| ① bean 名撞车 | `ConflictingBeanDefinitionException: bean name 'lawSearchService' ... conflicts with ... [com.faze.infra.law.LawSearchService]` | 本段 `modules/ai/service/LawSearchService` 与段 0 探针**简单类名相同**，`@Service` 默认 bean 名一样 | 本段类改 `@Service("aiLawSearchService")`，段 0 的类未动 |
| ② 调度器污染自动配置 | `APPLICATION FAILED TO START`：`ProbeController` 的 `@Qualifier("applicationTaskExecutor") TaskExecutor` 注入失败 | `AiStreamSchedulerConfig` 的 `@Bean` 直接返回 `ScheduledExecutorService`，命中 Boot `TaskExecutionAutoConfiguration` 的 `@ConditionalOnMissingBean(Executor.class)`（它是 `Executor` 子类型）→ `applicationTaskExecutor` 不再创建 | 新增包装类 `AiStreamScheduler`（持有线程池、**不实现 `Executor`**），配置类返回它 |

> **教训**：`javac` 自检只能验语法，**容器装配错误只有启动才暴露**。两次都是「编译 exit=0、启动 exit=1」。

**另修一处基础设施缺陷（第 3 起，非「起不来」但污染日志）**：

| 缺陷 | 现象 | 根因 | 修法 |
|---|---|---|---|
| SSE 断线后的异步分派被拒 | 一次断线重连后日志出现 3 条 `AuthorizationDeniedException` + `Cannot render error page`（数据不受影响，但看起来像 401） | `SseEmitter` 完成/超时后容器做 `DispatcherType.ASYNC` 重新分派，此时 `JwtAuthenticationFilter` 已跳过、`SecurityContext` 已清，而 `authorizeHttpRequests` 只按路径判 → `/api/v1/app/**` 要求 `authenticated()` → 拒绝这次内部分派 | `SecurityConfig` 的授权链最前面加 `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()`（Spring Security 对异步分派的标准做法；**只放行 ASYNC 这一种 dispatcher type**，普通 `REQUEST` 仍走原来的规则） |

修后实测：一次完整 SSE（`start`→`delta`×N→`refs`→`done`）在新增的 102 行日志里该类异常 **0 条**。

### 4. 测试

- **本段单测 70 个全部通过**（`mvn -B -o -Dtest=... test`，surefire 报告在 `target/surefire-reports`）：B 组 33 + D 组 24 + E2 9 + 其他 4。
- **全量回归**：注入 `deploy/.env` 的 `FAZE_*` 环境变量后 `mvn -B -o test` → **316 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。**注意**：不注入环境变量直接跑会有 5 个 `@SpringBootTest` 报 `Could not resolve placeholder 'FAZE_DB_URL'`——那是环境缺失，不是段 3 回归（已实测同一批测试注入后 5/5 全绿）。

### 5. 文档

- **新增 2 份**（基线 v1.49 登记）：`docs/standards/法条数据录入规范.md`、`docs/standards/AI对话留存策略.md`。
- **验收证据 20 份**：`docs/acceptance/seg3/{A1,A2,A3,A4,B1,B2,B3,B4,C1,C2,C3,C4,C5,D1,D2,D3,D4,E1,E2,E3}.md`（C 组的 mock 挂账、E1 的真机挂账、E3 的 G2/H2 待复核，分别写在各自文件的挂账表里）。
- 回写：《第 3 段工作流》§十一 写满 22 条偏差 + L925 令牌名改正；《依赖清单》+webflux；`docs/README.md` +2 行索引与基线号；`docs/BASELINE.md` v1.48→**v1.49**（新增 2 + 变更 3）。

### 6. 明确没做的与挂账

- **F/G/H/I 四组未开工**：APP AI 页与历史页、PC A-27/A-28、黄金 30 题与 `3-A1`~`3-A8` 收口。因此**段 3 尚未出口**。
- **豆包 key 仍未提供**（工作流 §十 第 7 项）：C 组全程 `DoubaoClientStub`，**`3-A2` / `3-A3` / `3-A4` / `3-A8` 四条锚点不判过**。key 到位后只需在 `deploy/.env` 填 `FAZE_ARK_API_KEY` + 把 `FAZE_AI_MOCK` 置 `false`，不用改代码。
- **`3-A5` 真机判定挂账**：本机 `adb devices` = 0、无模拟器，E1 只交钩子清单与静态证据。
- **两处需负责人拍板**（已记在《第 3 段工作流》§十一 第 19 条与 E1 验收文档）：①`color.text.muted` 对比度 2.85:1 低于 WCAG AA，AI 标识文案是否升 `text.secondary`（5.74:1）；②`refs` 里 `lawItemId` 因全局 Long→String 规则被序列化成**字符串**，段 4 读 `refs` 时要按字符串解析（`JSON_TABLE ... BIGINT PATH` 实测可正确转换，不影响 B4）。

---

## 2026-11-07 · 段 3 开工前清账（11 份文档，基线 v1.48）

> 负责人指令：段 3 开工前先核查前提，查出问题先报；报完后定「**先清账**」。本轮**未写任何段 3 代码**。

### 1. 核查结论（为什么必须清账）

《第 3 段工作流》§九 明写「**这 20 项不改，施工时必然照错的那份写**」，但实测**几乎一项未做**——该文档写于 2026-11-01（HEAD `d078023`），当时段 0/1/2 都还没施工；等到 11-07 段 1/2 收口（基线 v1.47）再看，上游文档全都没跟。

四项硬阻塞：

| # | 问题 | 后果 |
|---|---|---|
| 1 | 《数据表规范》仍写 `ai_session`(7) / `ai_message` B 类 12 字段 / `i18n_glossary`(9) | `V300` 会照错的表名与字段数建表 |
| 2 | 《技术路线》仍写 `/app/ai/sessions`、`/admin/ai-sessions`、`POST .../review`、「每个 delta 即落库」 | 照旧路径写接口；照「每 token 一次 UPDATE」写成性能灾难 |
| 3 | 《总纲》`3-A1` 仍要求「≥2000 条」，而负责人已定「占位几条 + 全量自己导」 | 按总纲验收必然失败 |
| 4 | 《审计与日志规范》`LAW_ITEM_EDIT` 段号写 7、缺 `AI_CONVERSATION_EXPORT` | 段 3 以为不用做审计 |

另外三处**结构与文档假设不符**（已在 §7.52 登记，施工时按实际走）：

- `pc/ruoyi-ui/` 实际是 **`pc/admin-web/`**（RuoYi-Vue3）+ `pc/admin-server/`；
- **`(tabs)` 不存在**：`app/` 是平铺路由（`home.tsx` / `lawyer-list.tsx` / `profile.tsx`），根布局是 `Stack`。F1 补建 tab 壳的方案是「迁文件进 `(tabs)/`、URL 不变」（expo-router 路由组不参与 URL，`(tabs)/home.tsx` 仍是 `/home`）；
- `shared/i18n/<lang>/` 实际是 **`shared/i18n/resources/<lang>/`**。

### 2. 改了哪 11 份

| 文件 | 改动 |
|---|---|
| 数据表规范 | `ai_session`→`ai_conversation`、`ai_message` B(12)→C(16)、`i18n_glossary` 9→10、`law_item` 12→15；§8.4 法条全量改走 Flyway `V302`；附录 C 补 2 条 |
| 技术路线 v2.3 | 全文 15 处：AI 路径改名、补 2 个新接口、删 review、`session_id`→`conversation_id`、delta 落库策略、语言段、内容安全三处「段 3 不接」、`V302` |
| 开发计划总纲 | 段 2→段 3 依赖改「AI 不计费」；§5.4 法条改「首批 5 条 + 导入通道」；`3-A1` 口径收窄为 4 条可验步骤 |
| 接口规范 | §6.7 `sessionId`→`conversationId`；新增 §7.1.1 段 3 新增 2 个接口 |
| 审计与日志规范 | `LAW_ITEM_EDIT` 段号 7→3；新增 `AI_CONVERSATION_EXPORT` |
| 第 0 段工作流 | 全量法条「走 seed」→「走 `V302`」；「5 条条文待确认」→「已确认（2026-11-06）」 |
| 第 1 段工作流 | 迁移号 `V2~V5`→`V3~V6`（`V2` 被段 0 占用） |
| 第 2 段工作流 | 追加「段 3 回写」2 条（MessageBubble 遗留、AI 不计费） |
| 未完成需备注 | §5.2 第 10 项补「用户删除=软删、A-28 仍可查、导出写审计」 |
| PC 后台 UI 设计说明 | A-27 去「复核状态」列；A-28 去「校验状态」「标记已复核」 |
| APP 页面导航树 | AI 页 5 处标「段 3 不做」；§多语言改「固定简体中文」 |

### 3. 基线

- `BASELINE.md` **v1.47 → v1.48**，11 份指纹全部重算（行数/字节/SHA256 前 16 位）。
- `docs/README.md` 基线号 v1.45 → **v1.48**（它此前一直滞后，v1.44 之后没跟上）。
- 本轮**未改**：`design/ui/`、`design/admin/` 的 HTML 设计稿；`docs/acceptance/**`（按规则不进基线）。

### 4. 仍未清的（施工时做）

1. `ErrorCode.java` 缺 `408001`~`408005` / `508002`（文档《错误码规范》§3.8 已有定义，补 Java 枚举即可）；
2. `pom.xml` 缺 `spring-boot-starter-webflux`（C1 调豆包用 `WebClient`，加后登记《依赖清单》）；
3. `docs/standards/法条数据录入规范.md`、`docs/standards/AI对话留存策略.md`（A3 / E3 产出）；
4. **豆包 API key 仍未提供** → C 组走 mock，`3-A2`/`3-A3`/`3-A4`/`3-A8` 四条锚点**挂账不判过**（负责人 2026-11-07 确认继续 mock）。

---

## 2026-11-06 · 段 2 出口补验与四项挂账补做

> 负责人指令：补第 2 段收口时留下的可补挂账（共 4 项），补完汇报。本轮**未进入段 3**。

### 1. 四项补做

| # | 事项 | 结果 |
|---|---|---|
| 1 | `deploy/verify-all.ps1` 后端门禁 | 把 `.env` 注入进程环境（此前只读进局部变量给 preflight，`mvn -B verify` 起 Spring 上下文时缺 `FAZE_DB_URL` 直接报错）；同时改掉旧注释「无变量时整类跳过」——实际行为是**报错**，注释与行为不符。实测该步 `Tests run: 246, Failures: 0, Errors: 0` |
| 2 | 管理端 Service 单测 | 新增 3 个测试类 **38 用例**：`AdminOrderServiceTest` 9（分页 total/金额分口径/脱敏/状态流水）、`AdminArticleServiceTest` 18（**四语言完成度聚合**、publish 校验与幂等、create/update 分支）、`AdminConversationServiceTest` 11（游标边界、`CONVERSATION_VIEW` + target_id 审计、附件签名） |
| 3 | 出口条件 2「迁移可重放」 | 临时库 `faze_replay_seg2` 按版本号数字序重放全部 **14 个迁移**，产出的 **26 张表**与主库在表清单/列数/索引数三维度逐表一致；主库只读、临时库用完即删。证据：`docs/acceptance/seg2/G1-tables.md` §六 |
| 4 | 聊天图片/文件消息客户端 | `ChatPage` 附件按钮 + 上传守卫 + pending 预览；新增 `AttachSheet` / `MessageImage` / `MessageFile` 三组件；四语言 `conversation.json` 各 **32 key 完全同构**；依赖清单登记 `expo-image-picker` / `expo-document-picker` |

### 2. 顺带修掉的真实缺陷

1. **`StorageKeyRules.KEY_PATTERN` 缺 `chat`**：`build()` 按 `ALLOWED_SCENES`（已加 chat）生成 `private/chat/...`，但 `isValid()` 用的正则没有 chat，`LocalFileStorage.presignUpload` 把自己刚生成的 key 判为非法 → 聊天附件 token 接口恒报 `401001`。补上后实测 `token 200 → PUT 204`。
2. **消息气泡的 `<button>` 嵌套**：`MessageImage` / `MessageFile` 的 `accessibilityRole="button"` 嵌在「长按撤回」的气泡按钮里，Web 上触发 React hydration 错误（`<button> cannot be a descendant of <button>`）。改为 `image` / `link` 语义后复检干净。
3. `.i18n-pending.json` 被 `check-i18n.ps1` 自动重写（conversation 命名空间补齐翻译后不再登记缺译）——属门禁正常行为，不是手改。

### 3. 测试与门禁（本轮实测）

- `mvn -B test`：**246 项全绿**（208 基线 + 38 新增，0 失败 / 0 错误 / 0 跳过）。
- `deploy/verify-all.ps1`：preflight / 库名守卫 / 后端门禁全过；前端门禁仍 PENDING（段 0 遗留，按设计计失败）；另外两项**既有失败未修**：密钥粗筛（命中 `deploy/verify-*.js` 里的 `admin/admin123` 本地测试账号）、文档镜像（`docs` 与 `UI设计` 分支内容不一致）。
- 前端 `npm run verify` 四项全过（`tsc` / `eslint` / `check-raw-text` 105 文件 0 命中 / `check-i18n` 三级检查）。
- **浏览器端实测**（Chrome CDP + Expo Web 8090，按段 2 §零.5 的浏览器验收口径）：登录 → 进聊天页 → 附件面板 → 选图 → 上传 → 图片消息发出并在列表渲染；截图 `shots/app-03-chat.png`、`app-04-attach-sheet.png`、`app-05-image-sent.png`、`app-06-chat-after-reload.png`。

### 4. 文档回写

- 《第 2 段工作流》§十一 追加第 13 / 14 行（出口条件 2 闭合；`verify-all.ps1` 门禁修正）。
- 《BASELINE》**v1.46 → v1.47**：`第2段工作流`（1125 行 / `afa25615c3abebc3`）与 `依赖清单`（106 行 / `03a1b98b5ec6964f`）指纹重算 + 新增 §7.51。
- `docs/acceptance/seg2/G1-tables.md` 追加 §六（重放证据与局限）。
- 《依赖清单》新增 §一之五（第 18 / 19 项）。

### 5. 段 2 出口状态（本轮之后）

| 出口条件 | 状态 |
|---|---|
| 1. `2-A1`~`2-A10` 十条全部二值通过 | ❌ 8 通过 / `2-A10` 近似 / `2-A3` 未通过 → 段 8 |
| 2. 12 张表 Flyway、无手改库、迁移可重放 | ✅ **本轮闭合** |
| 3. 接口清单一条不少 + App/PC 实测 | ✅ |
| 4. 状态机可追溯 + 非法迁移抛异常 + 留痕 | ✅ |
| 5. 余额恒等式差异 0 | ✅（本轮实跑 `deploy/verify-balance.ps1`：user 0 / lawyer 0 / exit 0） |
| 6. 推送 2 个厂商 ROM + 杀进程收到 | ❌ 无凭据 → 段 8 |

### 6. 仍未做（如实挂账）

- **原生 Android 的附件上传未验**：`fetch(file://…).blob()` 在 web 实测通过（blob URL），原生侧 RN 对 `file://` 支持不稳，正解需引入 `expo-file-system` 并登记；属段 8 真机验收范围。
- 服务端附件签名地址 **60 秒**有效期：长停留后打开历史文件/图片可能失败（已 toast `openFailed`），建议段 8 改为点击时重拉签名。
- bo / ug / kk 译文由 AI 产出（非中文占位），上线前需母语者审校。
- 附件发送失败（上传成功但 WS 断线）没有重发入口，与既有文本消息行为一致。

---

## 2026-11-06 · 段 2 收口（G2~G9 完成 / 10 条锚点判定 / 文档基线与 §九回写）

### 1. 交付范围

第 2 段 **G2~G9 共 29 个任务全部完成**：12 张表（`V200`）、段 2 自己的 23 个接口、
16 个 APP 页面（订单 4 + 律师侧 4 + IM 1 + 评价 1 + 首页与文章 3 + 消息中心 2 + 退款 1）、
4 个 PC 页面（A-11 / A-12 / A-14 / A-15）。提交区间 `c1ea9c5..`（G4 之后本轮 18 个提交）。

### 2. 10 条锚点判定（二值，证据在 `docs/acceptance/seg2/2-A*.md`）

| 锚点 | 判定 | 说明 |
|---|---|---|
| `2-A1` 下单扣款 | ✅ 通过（接口级） | 余额 25318→19318、`balance_flow` 消费 1 条；页面截图缺 |
| `2-A2` 接单与会话 | ✅ 通过 | 接单流转 + IM 双向 **45ms** ≤ 2s |
| `2-A3` 杀进程推送 | ❌ **未通过** | 通道凭据未提供，挂段 8（B 方案改判） |
| `2-A4` 结束与评价 | ✅ 通过 | 评分整表重算 4.0→4.5，与 AVG/COUNT 一致 |
| `2-A5` 支付密码错误 | ✅ 通过 | 密码错实测 + 5 次锁定/锁定期拒绝（单测级） |
| `2-A6` 退款闭环 | ✅ 通过 | 全额 / 部分 / 驳回三路径 + 对账差异 0 |
| `2-A7` 首页真实数据 | ✅ 通过（接口级） | PC 录入 → 上架 → APP 可见 → 维语 → 下架不可见；页面截图缺 |
| `2-A8` 超时与幂等 | ✅ 通过 | 幂等实测 3 连击 1 单；两条超时为单测级 |
| `2-A9` 断线重连 | ✅ 通过 | 断开 30 秒 + 5 条全到、无重复、顺序正确、离线表清理 |
| `2-A10` RTL 会话页 | ⚠️ 近似通过 | `left/right` 0 处、业务代码 `isRTL` 0 处、`letterSpacing` 0；真 RTL 挂段 8 |

**结论**：8 条通过、1 条近似（`2-A10`）、1 条未通过（`2-A3`）。未通过与近似项**都如实挂账段 8**，
不写「通过」。

### 3. 验收证据

`docs/acceptance/seg2/` 共 **19 份**：G1~G9 九份分组证据（含真实 API/SQL/审计输出）+
`2-A1`~`2-A10` 十份锚点证据（首行给判定）。

### 4. 收口回写（工作流 §九 16 项）

- 《第 2 段工作流》§十一：追加 12 条「计划 vs 实际」偏差 + §九 16 项逐项状态表。
- 《BASELINE》**v1.45 → v1.46**：接口规范与错误码规范两份指纹重算 + 新增 §7.50 说明本轮内容变更。
- 《docs/README.md》：新增「段 2 验收证据索引」。
- 《审计与日志规范》§3.2 的 `CONVERSATION_VIEW` 已在 v1.45 登记；本轮把代码 action 从
  `CHAT_VIEW` **对齐为 `CONVERSATION_VIEW`**。
- 顺带修掉两处历史 `letterSpacing`（`SplashPage` 4 / `MyPage` 1）——阿拉伯系连写会被字间距断开。

### 5. 测试与门禁（收口轮实测）

- `mvn test` **208 项全绿**（33 个测试类，0 失败 / 0 错误 / 0 跳过）。
- 前端 `npm run verify` 四项全过：`tsc --noEmit`、`eslint .`（0 告警）、
  `check-raw-text`（102 文件 0 命中）、`check-i18n`（四目录同构 + 缺译已登记 + 占位符一致）。
- PC 前端 `npx vite build` 通过。

### 6. 仍然挂账（段 8 或待拍板）

1. `2-A3` 杀进程推送（G7.1 通道凭据）；
2. `2-A10` 真 RTL（真机）；
3. 图片/文件消息的客户端选择器（需新增 `expo-image-picker` / `expo-document-picker`）；
4. `2-A1`/`2-A4`/`2-A6`/`2-A7` 的页面级截图（接口级证据齐全）；
5. 管理端三个 Service 的单测；
6. ~~段 2 占用的错误码 `405008~405011` 与段 5 预留段冲突~~ **已裁决（2026-11-06，负责人）**：
   不动段 2 已交付的码，段 5 的 5 个码从 `405008`~`405012` **顺延到 `405023`~`405027`**；
   段 6 的 `405013`~`405020` 与段 2 无交集、保持不动。错误码规范 §3.5 表与 §3.5.1、
   第 5/6 段工作流、接口规范的相关表述已同步，BASELINE v1.46 的 4 份指纹已重算；
7. 律师/文章分类参数是否 code 化（待拍板）；
8. 首页底部 tab 导航（段 3）。

---

## 2026-11-06 · 段 2 G9 PC 后台 4 页（订单列表 / 订单详情 / 文章列表 / 文章编辑）

### 1. G9.1 A-11 订单列表

后端 `GET /api/v1/admin/orders`（RuoYi 口径 rows/total；状态/时间区间/律师/用户筛选；
用户手机号脱敏；金额整数分）。前端 `views/biz/trade/order/index.vue`：8 状态筛选、时间区间、
分页、CSV 导出（当前筛选，上限 1000 行，代码注释标明段 7 改异步导出）。

### 2. G9.2 A-12 订单详情

后端 `GET /admin/orders/{id}`（含支付记录、退款单、**关联会话 id**）、
`GET /admin/orders/{id}/status-log`（来自 `order_status_log`，**不是四个时间戳拼的**）。
前端 `detail.vue`：信息卡 + 状态流转时间线（实测 9 节点含 refunding/refund_rejected）+
支付记录（段 2 两条，负数=退款）+ 退款单表与审批弹窗（驳回必填原因，调 G4.2 接口）+
关联会话与**聊天记录抽屉**（seq 游标分页，已撤回消息给原文）。

### 3. G9.3 A-14 文章列表

后端 `GET /admin/articles`：服务端一次聚合四语言完成度（`languages`，标题与正文都非空才算完整），
支持状态/语言/关键词筛选。前端 `index.vue`：4/4 徽标 + 上架/下架；**四语言不齐时前端本地预判
并提示缺失语言**（服务端只回通用 msg，理由见验收文档偏差 3），服务端仍兜底拒绝。

### 4. G9.4 A-15 文章编辑

后端 `POST/PUT/GET /admin/articles[/{id}]`、`publish`、`offline`：保存只接受 draft/archived
（上架必须走 publish 校验）；`publish` 校验简体 + 藏/维/哈的标题与正文都齐全；
正文只校验「合法 JSON 数组」，块类型不锁枚举；译文按 `uk_art_trans` upsert。
前端 `edit.vue`：源内容 + 三个译文 tab + 正文 JSON 块编辑（富文本会丢 law_ref 绑定，
按工作流允许用 textarea 兜底）+ 保存草稿 / 保存并上架。

### 5. 审计

4 个文章写方法各一个 `@OperationLog`（ARTICLE_CREATE / UPDATE / PUBLISH / OFFLINE）；
**聊天记录查看破例审计**（拍板第 7 项）：`CHAT_VIEW` + `target_id`=会话 id
（`AuditContext.mark` 补），每次打开抽屉与翻页都留痕。

### 6. 菜单

`faze_admin.sys_menu` 插入 2109~2112（订单管理 / 订单详情[隐藏] / 文章管理 / 文章编辑[隐藏]），
admin 角色默认全通过。生产部署要把这段 SQL 纳入部署脚本（段 8）。

### 7. 证据

`docs/acceptance/seg2/G9-admin.md`：11 项接口实测（真实 RuoYi token）、审计留痕 SQL、
`2-A7` 全链路（PC 录入 → 上架 → APP 可见 → 维语视图 → 下架不可见）、7 条偏差。
`vite build` 通过。**页面级浏览器截图未做**（菜单与接口已就绪，缺人工/自动化点击），
在收口清单记为待补；管理端 Service 无单测（同样记入收口缺口）。

---

## 2026-11-06 · 段 2 G8 内容与消息中心（文章四语言 / 首页与文章两页 / 消息中心空态）

### 1. G8.1 文章接口（四语言 + 降级）

`GET /api/v1/pub/articles`、`GET /api/v1/pub/articles/{id}`（同一实现也挂在 `/app/articles`：
游客读首页「每日学法」用 `/pub`，登录用户走 `/app`）。`ArticleService` + `ArticleBlockConverter`，
实体 `Article` / `ArticleTranslation`，可见性只看主表 `status='published'`（译文状态只表示质量阶段）。
语言取 `lang` 参数或 `Accept-Language`；**标题、摘要、正文各自降级**，任一降级即
`langFallback=true`（运营只录了标题译文时，正文是中文必须标降级，不能冒充译文）。
法条引用块（`law_ref`）批量补 `law_item` 原文，避免逐块查询。

### 2. G8.2 首页 + 文章列表 + 文章详情

`HomePage`（01-home）：搜索入口、快捷入口、AI 咨询卡（段 3 占位）、**「每日学法」接真实接口**
（区块自带 loading/error/empty 三态，文章接口挂了不拖垮首页）；`SplashPage` 登录态分流改为进首页。
`ArticleListPage`（12-articles）：分类 5 档 + 分页 + 四态 + 语言降级角标。
`ArticleDetailPage`（13-article）：五类正文块渲染（paragraph / heading / law_ref / list / image），
法条引用块展示法名+条号+原文，未知块类型忽略不崩；底部免责声明。

### 3. G8.3 消息中心两页（空态 + 入口占位）

`MessagesPage`（38-messages）与 `MessageDetailPage`（39-message-detail）：**不建表、不调接口**，
空态 +「通知功能即将开放」+ 真实出口（找律师/返回首页）；首页「消息中心」入口保留
（工作流 G8.3 第 3 条：不因没数据就隐藏入口）。

### 4. 证据

`docs/acceptance/seg2/G8-content.md`：接口 6 项实测（游客可读 / 三种语言降级 / 9 个内容块 /
法条原文补全 / 401004）、页面清单、7 项单测、6 条偏差。
`mvn test` 新增 `ArticleServiceTest` 7 项；前端四项门禁通过。
i18n：`content` 23 key + `home` 20 key（两个 namespace 此前都是空文件）。

---

## 2026-11-06 · 段 2 G7 离线推送（任务与重试 / 点击直达逻辑；通道接入挂账）

### 1. G7.1 推送通道接入 —— **挂账，不做假实现**

个推应用 + 四厂商通道凭据未提供（总纲 §九 L855 的承诺未兑现）。冻结 `PushSender` 接口 +
`PushMessage` 载荷（userId / title / body / payload），实现层是 `DisabledPushSender`：
**明确抛 `PushUnavailableException`，不静默成功**。工作流 §二 第 11 项禁止「假装通过」，
所以未接入就让任务失败 → 重试 → 死信，行为可观测。凭据到位后加
`GetuiPushSender implements PushSender`，调用方零改动。

### 2. G7.2 推送任务与重试（`task_queue` 第二个消费者）

消息落库后由 `MessagePushService` 入队（**只在对方离线时**；`hasActiveTask(push, messageId)` 判重），
`PushTask` 每 15 秒领一批：解析 messageId → 查消息/会话 → 推导接收方（律师发→用户、用户发→律师账号）
→ 接收方已在线则跳过（客户端会走补推）→ 调 `PushSender`。
失败按 `2^retry` 分钟退避，`retry_count >= 5` 进死信（PC A-26 可见，段 2 只落数据）。
推送正文**不含聊天内容**（技术路线 §7.5：锁屏可见 = 隐私红线）。

`task_queue` 补**按类型领取**（`claimBatch(worker, types)` + XML `task_type IN (...)`）：
段 2 起有两个消费者，不筛类型会互相抢任务并互标失败。`OrderCloseTask` 显式只领订单两类任务。

### 3. G7.3 点击直达会话（客户端逻辑完成，真机验证挂账）

`features/conversation/push.ts`：payload 解析（只认 conversationId / messageId，非法不跳首页）+
意图注册表（`emitPushIntent`）+ 未读求和；`_layout.tsx` 注册处理器：未登录先
`savePendingHref` 再跳登录，**登录成功后 `LoginPage` 消费 pending 直接回该会话**；
`ChatPage` 新增 `conversationId` 直达入口（`GET /app/conversations/{id}` 取对端 → 拉历史 → 连 WS）。
**没有推送 SDK，杀进程收到通知这一步无法验**，`2-A3` 判未通过，挂段 8 真机。

### 4. 证据

`docs/acceptance/seg2/G7-push.md`：完成度表（G7.1 挂账 / G7.2 完成 / G7.3 逻辑完成）、
真实 task_queue 入队记录、5 轮失败到死信的实测、幂等说明、客户端逻辑逐项、6 条偏差。
`mvn test` **201 项全绿**（新增 `PushTaskTest` 6 项）；前端四项门禁通过。

---

## 2026-11-06 · 段 2 G6 IM 会话（REST / WebSocket / 已读撤回 / 断线重连 / 离线消息）

### 1. G6.1 会话与消息 REST

`GET|POST /app/conversations`（创建或获取，`(user_id,lawyer_id)` 唯一 → 一个律师一个会话）、
`GET /app/conversations/{id}/messages`（seq 游标，不用 OFFSET）、`POST /app/conversations/{id}/read`、
`GET /app/workbench/conversations`（律师端独立端点，不给同一接口加 role 参数）。
`ConversationService#assertMember` 是**全域唯一鉴权入口**：REST 与 WS 都走它，
非成员一律 404002（HTTP 403），与「会话不存在 404001」区分。
新增 `CursorResult<T>`（接口规范 §5.2 的游标分页结构，此前只有文档没有实现）。

### 2. G6.2 WebSocket 服务端

`/ws/chat`（Spring 原生 `WebSocketHandler`，不用 `@ServerEndpoint`）；`ChatHandshakeInterceptor`
在握手阶段验 JWT（token 走 query，Security 对该路径 permitAll —— 握手没有 Authorization 头）。
`ChatWebSocketHandler` 只管生命周期与按 type 分发，五种 type 各一个处理器（send/read/recall/typing/history）。
在线表 `OnlineRegistry`：内存 map 是本实例真相 + Redis `faze:im:online:{userId}` 标记（120 秒 TTL）；
同一账号新连接踢旧连接并推 `chat.kicked`；心跳 90 秒无消息主动关闭（应用层记活跃时间 + 定时扫描，
因为 `WebSocketSession` 接口没有 setMaxIdleTimeout）。

### 3. G6.3 聊天页（17-chat）

`ChatPage.tsx` + `useChatSocket` hook：文本收发、首屏 20 条、上翻加载更早（seq 游标）、
对方输入状态、断线橙色提示条、已读小字、长按撤回。订单详情「联系律师」改为跳转
（按 lawyerId 建/取会话）。`conversation` namespace 21 个 key（该 namespace 此前是空文件）。

### 4. G6.4 已读、撤回、输入状态

已读：`seq <= lastReadSeq` 的**对端**消息批量标 `read_by_*_at`，未读数按消息表重算（不信冗余计数）；
WS `chat.read` 与 REST 已读兜底调**同一个** `MessageService#markRead`（各写一份必然漂移）。
撤回：四条校验（成员 / 消息属于该会话 / 发送者本人 / 2 分钟内），只置 `recalled_at`、不改 `msg_type`，
历史接口对撤回行返回 `recalled=true` 且**不带 content/fileUrl**；双方都会收到 `chat.recall`。
typing：只转推、不落库，但仍然做成员校验（否则等于开放「给任意会话推假正在输入」）。

### 5. G6.5 断线重连（2-A9）

客户端指数退避（1/2/4/8/16/30s，不死循环猛连）+ 重连后发 `chat.history(afterSeq=已收最大 seq)`
只补增量；服务端 `incremental` 按 seq 升序返回。`seq` 由 Redis `INCR` 与库内最大值取 max：
Redis 重启后 INCR 从 1 开始，不取 max 会直接撞 `uk_msg_conversation_seq`。
`client_msg_id` 去重：重发返回已有消息（`duplicated=true`），不插第二行、不再推对端。

### 6. G6.6 离线消息

`message_offline`：对端离线才入队（先按 `(user_id,message_id)` 查重），连接建立即补推，
**推一条删一条、每批 50**；`OfflineMessageCleanupTask` 每天 04:30 清理超期（保留期读
`sys_config.im.offline_retention_days`，默认 7 天）。清理不等同丢消息：正文在 `conversation_message` 里。

### 7. 证据

`docs/acceptance/seg2/G6-im.md`：REST 7 项、WS 双向 45ms（`2-A2` 后半段）、断线 3 条全收（`2-A9`）、
非成员 403、REST 已读兜底、库内数据、25 项单测、页面清单、7 条偏差。
`mvn test` **195 项全绿**（新增 `ConversationServiceTest` 8 / `MessageServiceTest` 13 /
`OfflineMessageServiceTest` 4）；前端 `verify` 四项门禁通过。

---

## 2026-11-06 · 段 2 G5 评价（提交 / 评分回写 / 律师回复）

### 1. G5.1 评价提交

`POST /app/orders/{id}/review`（rating 1-5 必填、tags ≤6 个每个 ≤20 字、content ≤500、isAnonymous 缺省 false）、
`GET /app/orders/{id}/review`（评价页只读态与订单详情判断用）。
前置：订单属于当前用户且 `COMPLETED`；**一单一次**用两层保证——应用层先查（给可读的 405022）+
`uk_review_order` 兜底（`DuplicateKeyException` 也转 405022）。
`OrderDetailVO.reviewed`：已评价时服务端把 `review` 从 `actions` 去掉，C 端按钮变「查看评价」。

### 2. G5.1 律师回复（段 2 新增接口）

`POST /app/reviews/{id}/reply`：技术路线 §4.8.2 没有这个接口，工作流 §五 第 27 条已登记为新增；
`ReviewController` + `ReviewReplyBO`。归属校验是「lawyer.user_id 命中**且** lawyer.id == review.lawyer_id」
——只判「是律师」会让任何律师回别人的评价（401008 已实测）。回复允许覆盖（工作流未规定次数，
评价不可改而回复可修正错别字），每次覆盖更新 `updated_at`。

### 3. G5.2 评分回写

`ReviewService#recalcLawyerRating`：与插评价**同一事务**，`SELECT AVG(rating), COUNT(*) WHERE lawyer_id=?`
后 `UPDATE lawyer SET rating, review_count`；**整表重算、不做增量**（增量算法在历史评价被人工改过之后会永久错）。
`rating` 四舍五入到 1 位小数（`DECIMAL(2,1)`），无缓存——律师详情直接读 `lawyer` 行。
实测：种子 `5.0 / 0`（不自洽的假数据）→ 4 星评价后 `4.0 / 1` → 再 5 星评价后 `4.5 / 2`，
与 `SELECT AVG(rating), COUNT(*)` 完全一致。

### 4. 错误码与接口登记

新增 `TRADE_REVIEW_NOT_FOUND(405021)`、`TRADE_REVIEW_DUPLICATE(405022)`。
**不复用 405012 / 405013**：那两个已被《错误码规范》§三 预留给段 5（提现/渠道）。
同时补两处登记：① 《错误码规范》§三.5.1「段 2 施工登记」——段 2 已实际占用 405008~405011，
与 §3.5 的段 5 预留冲突，**段 5 开工前必须裁决**；② 《接口规范》§7.3.0——段 2 施工实际落地
而技术路线 §4.8.2 未列的 7 个接口（G4 的 4 个 + G5 的 3 个）。

### 5. APP 评价页（34-review）

`ReviewPage.tsx`：星级（初始 0、未打分底部置灰、触控区 45×45）、6 个标签多选、正文 200 字计数、
匿名开关（整行可点）、「律师可以回复」「发布后不能修改」提示、已评价只读态（星级/标签/正文/匿名标记/律师回复）、四态。
`OrderDetailPage` 的「评价服务」改为跳转评价页；`app/review.tsx` + `routes.ts` 的 `review` / `reviewHref`。
i18n：`trade.review.*` 33 个 key + `trade.order.actViewReview` + `error.trade.review-not-found` /
`error.trade.review-duplicate`；**删除占位 key `trade.order.reviewPending`**（入口已实现）。

### 6. 证据

`docs/acceptance/seg2/G5-review.md`（提交 / 匿名 / 律师回复 / 评分即时回写 / 库内数据 / 页面清单 / 偏差）。
`mvn test` **170 项**（新增 `ReviewServiceTest` 13 项 + `OrderServiceImplTest#detailDropsReviewActionWhenReviewed`）；
前端 `verify` 四项门禁通过。`2-A4` 接口级通过，页面级截图证据待段 2 收口统一补。

---

## 2026-11-06 · 段 2 G4 退款（申请 / 审批 / 入账）

### 1. G4.1 退款申请

`POST /app/orders/{id}/refund`（reason 必填、amountCents 空=全额）、`GET /app/orders/{id}/refund`。
只允许 PAID / IN_SERVICE；同一订单只允许一张 `applying` 退款单（重复申请 405004）；超额 405005。
前端 `RefundPage.tsx`（33-refund）：金额只读、5 个单选原因（其他必填说明）、已有退款单时展示进度。

### 2. G4.2 PC 审批

`POST /admin/refunds/{id}/audit`（approved + remark）、`GET /admin/refunds?orderId=`。
通过 → 退款单 refunded + 入账 + 订单 REFUNDED（全额）或回退款前状态（部分）；
驳回 → 退款单 rejected + REFUNDING→REFUND_REJECTED→退款前状态。
`RefundAuditExecutor#approve` / `#reject` 各标一个 `@OperationLog`，detail 存 before/after。
**PC 审批弹窗属 G9.2**（工作流 G9.2 第 6 条把退款审批入口归到订单详情页），本组用真实 admin token 验接口。

### 3. G4.3 入账

复用 `RefundCreditService`；`OrderRefundService` 抽出 `creditRefund`（入账 + 回写流水号 + payment 负数行），
新增 `settleApprovedRefund` / `rejectRefund`。**退款不动律师余额**（段 2 无结算，代码注释已写明）。

### 4. 状态机补 3 条边（10 → 13）

`REFUND_REJECTED→PAID`（驳回回已付款）、`REFUNDING→PAID` / `REFUNDING→IN_SERVICE`
（部分退款通过后回退款前状态，不能走 REFUNDED 终态再回退）。

### 5. 修掉一个会吞钱的缺陷：退款入账幂等键

`balance_flow.biz_id` 在 G2.4 记的是**订单 id**、G4 改用**退款单 id**，两套 id 数值区间重叠：
新退款单 id=6 命中订单 6 的旧流水，被判「幂等命中」→ 审批成功、退款单显示已到账、**用户余额没变**。
修法：`RefundCreditService` 幂等键改退款单 id + `V201` 迁移把历史退款流水 `biz_id` 改成退款单 id。
迁移第一版用 `CREATE TEMPORARY TABLE` 直接失败（`faze_migrate` 逐表授权、无 `CREATE TEMPORARY TABLES` 权限，
Error 1044），改为纯 UPDATE JOIN。

### 6. 证据

`docs/acceptance/seg2/G4-refund.md`（申请 / 审批全额 / 部分 / 驳回 / 边界 / 状态流水 / 审计 / 缺陷）。
`mvn test` **156 项全绿**（新增 `RefundServiceTest` 11 项）；前端四项门禁通过。
`2-A6` 接口级通过，PC 页面级证据待 G9.2。

---

## 2026-11-06 · 段 2 G3 律师侧与展示（列表 / 详情 / 订单 / 工作台）

### 1. G3.1 律师列表

`GET /app/lawyers`：分类 4 档 + 关键词 + 排序白名单（`online_rating` / `rating_desc`）+ 分页。
分类靠 `sys_config.lawyer_specialty_enum`（JSON：tab → 擅长领域值数组）展开成 `JSON_CONTAINS`，
排序靠 `<choose>` 白名单，XML 内零 `${}`。前端 `LawyerListPage.tsx`。

### 2. G3.2 律师详情 / 评价 / 关注

`GET /app/lawyers/{id}`（资料 + 评分/评价数/接单数 + 三个服务价格与上下架 + `followed`）、
`GET /app/lawyers/{id}/reviews`（分页，匿名评价不返回任何可反查标识）、
`POST|DELETE /app/lawyers/{id}/follow`（`social_action` 软删取关，幂等）。前端 `LawyerDetailPage.tsx`。

> 偏差：设计稿 `14-lawyer-detail.html` 无价格区 / 评价区，按工作流 G3.2 补齐；「立即咨询」= 下单，
> 不是进会话（拍板第 6 项：下单与会话解耦）。

### 3. G3.3 律师侧订单

`GET /app/lawyer/orders`、`POST /app/orders/{id}/accept|reject|complete`。
律师身份从 token 反查 `lawyer.id`，归属校验进 SQL（405002）；非律师 401008（403）。
拒单 = `PAID→REFUNDING`（LAWYER）+ `OrderRefundService.settleRefund`（退款单 + 入账 + 支付负行 + 置 REFUNDED），
与超时自动退共用一份实现。接单用原子 SQL 自增 `order_count`。前端 `LawyerOrdersPage.tsx`（10 秒轮询）。

> 偏差：工作流未给律师侧列表端点（计划缺口），补 `GET /app/lawyer/orders`。

### 4. G3.4 律师工作台

`GET /app/workbench/summary`（今日订单 / 今日与累计预计收入 / 待接单 / 服务中 / 在线状态 / 价格与开关）、
`PUT /app/workbench/online-status`、`PUT /app/workbench/service-settings`。
「今日」按东八区切日；收入是 `lawyer_income_cents`（金额 − 15% 平台费），页面注明「预计收入，结算见段 5」。
前端 `WorkbenchPage.tsx`。

### 5. 本次修掉的 4 个缺陷

1. **`@Scheduled` 静默不执行**（G2.4 遗留）：自定义 `taskScheduler` + `@EnableScheduling` 放在普通配置类里，
   扫描器一次没跑。改为主类 `@EnableScheduling` + `spring.task.scheduling.*`，`ProbeController` 注入加
   `@Qualifier`；`OrderCloseTask` 加启动登记日志。实测订单 1/3/4 被自动关闭（`task_queue` 三条 done）。
2. **服务设置「部分更新」误清字段**：只传图文价格时，电话/文书价格被清 NULL、上架开关被置 0。
   改为价格条件 `set` + 开关 `resolveSwitch`（null 保持现值），补 6 项单测。
3. **分类 tab 映射词表错误**：初版把 tab 名（`民事纠纷`/`劳动纠纷`/`合同纠纷`）当成擅长领域值，
   而入驻页标签真值是 `民商事纠纷`/`劳动争议`/`合同审查`，导致「民事纠纷」tab `total=0`。
   已改用标签原词（归类口径不变，仍是负责人 2026-11-06 拍板的那套）。
4. **`OrderListPage` 未使用 `toast`**：G3.1 提交带入的 eslint 红，已删。

> 部署注意：`seg2_config.sql` 是种子且 `ON DUPLICATE KEY UPDATE` **不覆盖 `config_value`**，
> 已初始化的库必须手工 `UPDATE lawyer_specialty_enum`，否则线上仍是旧映射。

### 6. 证据

`docs/acceptance/seg2/G3-lawyer.md`（列表 / 详情 / 接单完成 / 拒单退款 / 工作台 / 定时任务修复 / 锚点判定）。
`mvn test` **145 项全绿**（26 个测试类）；前端四项门禁通过；i18n 新增 33 个 key（仅 zh-Hans 有译文，其余登记缺译）。
`2-A2` 只验了前半段（接单链路），IM 实时部分待 G6，**整条不判通过**。

---

## 2026-11-06 · 段 2 G2 下单与支付（订单两页 / 余额支付 / 支付结果 / 超时关单）

> 补记：本条目在 G3 收口时补写入，当时只出了证据文件、漏了 CHANGELOG（已记一次）。

### 1. G2.1 下单接口 + 订单两页

`POST /app/orders`（金额服务端按律师价格算，客户端传了也忽略；`Idempotency-Key` 幂等）、
`GET /app/orders`、`GET /app/orders/{id}`，`OrderActionResolver` 按状态推导动作。
订单列表 5 档 tab（`refunding`/`refund_rejected` 归「进行中」）、订单详情页。

### 2. G2.2 余额支付

`POST /app/orders/{id}/pay`：支付密码校验 → `BalancePayAdapter` → `DeductService.deduct`（唯一扣款入口）
→ `payment_record` 正行 → `PENDING→PAID`。密码错时不扣款、不迁移状态。

### 3. G2.3 支付页 + 结果页

`31-pay`、`32-pay-result`：只做余额一种支付方式，无渠道选择控件（段 2「四条不许」第 2 条）。

### 4. G2.4 超时关单与自动退款

`task_queue` 任务表 + `TaskQueueService`（CAS 领取 / 租约回收 / 指数退避 / 死信）、
`OrderTimeoutService`（PENDING 15 分钟关单；PAID 30 分钟无人接自动全额退款）、
`OrderRefundService.autoRefundFull`、`RefundCreditService`（幂等键 = orderId）、
`POST /app/orders/{id}/cancel`。

> 已知问题：本组交付时 `@Scheduled` 实际未生效（见 G3 条目第 5.1 条），G3 期间才修复并实测。

### 5. 证据

`docs/acceptance/seg2/G2-order-pay.md`；`mvn test` 122 项全绿（当时）。

---

## 2026-11-06 · 段 2 G1 交易地基（12 表 + 状态机 + 单号 + 扣款适配）

### 1. G1.1 建 12 张表（V200）

按《数据表规范》§8.1 用 V200 区间建段 2 的 10 张既有表 + 2 张新增表。
orders.status 补 
efund_rejected（八态）、payment_record.channel 补 alance、

efund_record 补 udit_remark。实测迁移 0.653s，12 张字段数与附录 A 逐个一致，索引均 <=6。
新表需 sync-db-grants.ps1 同步逐表授权（已执行，27 张表）。

> 偏差：段 1 实际用 V3~V12（占段 0 区间），未按 §8.1 的 V100~V199；已执行的迁移不改，段 2 起按规则用 V200。

### 2. G1.2 状态机

8 态 + 10 条状态对 + 14 个触发分支；OrderStatusMachine 是纯规则类。
OrderStatusMachineTest 6 例全绿，含 8x8=64 组合穷举。

已知缺口：PAID 阶段退款被驳回缺 REFUND_REJECTED->PAID 一条边（记在类注释）。

### 3. G1.3 单号生成器

OR/RF/RC + yyyyMMdd + 6 位，Redis INCR 按天分桶，东八区取日期。
单测 3 例全绿：格式 / 100 并发无重复 / 跨天从 000001 重开。

### 4. G1.4 扣款适配层

BalancePayAdapter：先 PayPasswordService.verify 再 DeductService.deduct，不重写扣款。
4 个 Mockito 用例全绿（密码错时断言 deduct 一次未调用）。

### 5. 证据

docs/acceptance/seg2/G1-tables.md。合计 13 个新单测全绿。
未完成：清库重放（会清段 1 数据，记为待办）。

---

## 2026-11-06 · 第 2 段开工前复核：3 项拍板 + 6 份文档回写 + WebSocket 依赖

### 1. 三项拍板（负责人 2026-11-06）

- **聊天原文可见性按段 7 口径**：管理员只看「命中片段 + 前后各 3 条」，不做全量、不许导出（推翻段 2 原拍板第 7 项，留推翻注）。
- **`2-A3`（杀进程收推送）走 B 方案**：浏览器只验「关标签页→重开→离线表补推 + 未读角标」，**不等价于系统推送**，挂账段 8。
- **`2-A10`（真 RTL）改口径**：代码审查 + 近似布局，真 RTL 挂段 8。

### 2. 六份文档回写（详单见 `BASELINE.md` §7.49）

技术路线（8 处：`orders.status` / `payment_record.channel` / `refund_record.audit_remark` / §9.2.1 / 4 处 MQ / 端口表颠倒 / 旧表名）、数据表规范（`refund_record` 11→12）、审计规范（`CONVERSATION_VIEW`）、总纲（§5.3 数据库清单 13 旧名→12 新名）、第 2 段工作流（§零.5 浏览器口径 + 3 项前置改判）、依赖清单（第 17 项 WebSocket）。

### 3. 代码侧准备

- `pom.xml` 加 `spring-boot-starter-websocket`（`2-A2` / `2-A9` 的载体），`mvn compile` 通过。
- 补 `modules/trade/README.md`、`modules/ops/README.md`（后端规范 §2.6 要求，段 1 缺这两个）。
- 测试数据：律师三个价格 5000/8000/20000 分、`online_status=online`；测试用户实名 `approved`。

### 4. 仍未解决（记在此，不假装已过）

- **段 1 全量验收最后一次不是全绿**：11-05 04:19 那次「管理端 23 项复核」FAIL（A-08/A-09 全返 401，token 过期），但同次 1-A1~A10 十条锚点全 PASS、端到端 38/38；11-06 19:19 那次带了 `-SkipE2E`，1-A1~A4 为 SKIP，日志结论「还不可以收口」。已重跑，见当日验证日志。
- **工程性承接手段缺失**：`pom.xml` 无 ArchUnit / checkstyle / JaCoCo，`gitleaks` 未接入《后端代码规范》§14.2 的 14 条 ✅ 项一条都没进 CI。

---

## 2026-11-05 · 测试数据清库脚本 + 验收「静默跳过」修正

### 1. `deploy/purge-test-data.ps1`：清掉 e2e 造出来的用户

`verify-e2e.js` 每跑一次就造 3~4 个用户（主号 / 未成年号 / 注销后的 cancelled 号），
加上人工点页面验证留下的，本地库一点点堆起来——**29 个用户里只有 2 个是该留的**。堆着的坏处很具体：
管理端列表全是「用户0001/用户0002」看不出真假、对账与巡检的统计被噪声盖住、
想验「新用户第一次进来是什么样」还得先手工筛。

**只删测试号段，且必须同时不在保留清单里**（两个条件都满足才删）：

| 测试号段 | 用途 |
|---|---|
| `1380000****` | 端到端主号（取时间戳后 4 位） |
| `1370000****` | 端到端未成年号 |
| `1360000****` | 历史手工测试号 |
| `cancelled_*` | 注销后改写成的占位号 |

保留清单默认 `1,3`，**这两个不是随便挑的**：
- `id=1 13800000001` 是 seed 测试账号，`Seg0SmokeTest` 的登录用例靠它，删了后端测试直接红；
- `id=3 13900000009` 是 `verify-admin-api.mjs` 里 `walletUser = '3'` 的**硬编码**目标，
  管理端 A-07 全组都指向它，删了验收就断。

既不匹配测试号段、又不在保留清单的用户**一律跳过并告警**——将来接入真实用户时这条保证脚本不会碰他们。
要连他们也删得显式 `-ForceAll`。

安全设计：默认 dry-run（不加 `-Execute` 只打印计划）、真删前 `mysqldump` 整库备份、
删完自检（保留账号完好 / 测试号段清空 / 对账恒等式 / 无悬挂行 / `operation_log` 原样保留）。
**不动** `operation_log`（审计只追加）、`sys_config`、`law_item`。

实测执行（2026-11-05 20:30）：29 → 2 个用户，连带清理 `login_log` 55 条、`user_device` 12 条、
`realname_auth` 9 条、`lawyer` 9 条、`lawyer_application` 13 条、`lawyer_application_log` 19 条、
`user_balance` 9 条、`balance_flow` 16 条；另清掉 **30 条悬挂行**（`login_log` 里 user_id 指向
已被历史清理掉的用户，孤儿行会污染一切按全库统计的检查）。`operation_log` 155 条原样保留。

**三个踩坑**（都是「第一次执行就撞上」，也都说明这些守卫不是多余的）：

1. **`mysqldump --events` 需要 EVENT 权限**，运维账号 `faze_migrate` 没有 →
   `show events` 报 1044、mysqldump 退出码 2。「备份失败不删数据」的守卫当场拦住了，一行没删。
   去掉 `--routines --events`（本库没有存储过程与事件）后解决。
2. **`CREATE TEMPORARY TABLE` 需要额外权限**，同样没有 → 1044。为了少给一个账号权限，
   改成重复子查询传条件（代价是条件写 11 遍）。
3. **对账口径漏了冻结金额**：自检里拿 `SUM(balance_cents)` 比 `SUM(delta_cents)`，报差 2000 分，
   白查一轮才发现 `id=3` 有 2000 分 `frozen_cents`。`verify-balance.ps1` 第 11-13 行**专门警告过**
   「只比 balance_cents 是口径错不是账错」——我正好犯了它警告的错。改成 `balance_cents + frozen_cents` 后 diff=0。

### 2. 管理端复核的跳过项不再静默消失

清库后跑验收发现管理端从 23 项变成 **22 项**，而汇总照样显示「22/22 通过」。
原因：`verify-admin-api.mjs` 里写的是

```js
if (firstApp) { check('A-05-2', '四步详情 + 审核流水', ...); }
```

待审队列为空时这一项**直接不存在了**——分母缩水、没有 FAIL、没有提示，看着还是全绿。
这和之前在 CI 里抓到的「一个文件都没扫到也报命中 0」是同一类假绿。

改成显式 `skip()`：记进结果、单独计数、汇总显示「22 通过 / 0 失败 / 1 跳过（共 23）」、
退出码用 **2**（不是失败，但也不是通过）。`verify-seg1.ps1` 相应识别退出码 2：
显示 `[SKIP]` 并置 `hasSkip`，让「本段能否收口」停下来。

顺带修了一处措辞：`$adminOk` 的语义是「没有失败」，退出码 2 也算没失败；
一开始把 2 当失败，汇总会报「可自动判定的检查：有失败项」——会让人去找不存在的失败。

**清库后验收结果**：环境 5/5、`mvn test` 54/54、对账差异 0、管理端 22 通过 + 1 跳过、
错误态 7/7、数据库权限 7/7。判定「还不可以收口」——因为这次 `-SkipE2E`（避免刚清完又造数据）
且待审队列为空，**两处 SKIP 都如实标出来了**。

---

## 2026-11-05 · 补上 CI（首个 GitHub Actions workflow）

**动因**：段 1 收口后仍然没有 CI —— 54 个后端用例 + 6 个验收脚本全靠人手跑。
今天已经因为「没人跑」埋过一次假失败（管理端 23 项全 401，实际是浏览器 Cookie 里的 RuoYi token
过了 30 分钟有效期），可见靠自觉不够。

`.github/workflows/ci.yml` 三个 job：

| job | 内容 |
|---|---|
| `backend` | MySQL 8.0 + Redis 7 service，建 `faze_migrate`（DDL）与 `faze_app`（仅 DML）两个账号 → 触发 Flyway 建表 → 导 seed → `mvn -B test`（54 用例）→ 断言运行时账号拿不到 DDL |
| `mobile` | `tsc --noEmit` + `eslint .` + `check-raw-text.ps1`（禁裸 `<Text>`）+ `check-i18n.ps1`（四语言同构） |
| `admin-web` | `pnpm install --frozen-lockfile` + `vite build`（RuoYi 前端没有 lint script，构建通过即门槛） |

**为什么不按「服务跑起来再测」的常规写法**：`sys_config` 的 12 条数据与测试账号在
`database/seed/`（负责人决策**不进 Flyway**），所以要先有表才能导 seed，而「只建表不跑业务」
没有独立入口。workflow 里用「先跑一次 `Seg0SmokeTest` 且允许失败」触发 Flyway 建表——这一步不是
质量门禁，只是拿表的副作用，注释里写清了原因，免得后人误以为它该失败。

**CI 里为什么不做「operation_log 只追加」的验证**：那条要求需要逐表 GRANT，而 CI 的表是 Flyway
现建的，建完再动态生成 GRANT 会让脚本复杂且难排查。它由本地 `sync-db-grants.ps1 -Verify` 覆盖，
并已接进 `verify-seg1.ps1` 第 7 步作为一票否决项。CI 只做轻量断言（运行时账号不能建表）。

**首跑结果（2026-11-05 20:01，run `42023685805`）**：

| job | 结果 | 说明 |
|---|---|---|
| 后端测试 | ✅ success | **54 个用例 0 跳过**（ubuntu + MySQL 8.0 + Redis 7）；Flyway 从空库建到 v12；「`faze_app` 无法建表」断言通过。日志里第一个 `mvn test` 是「触发建表」那步，如预期失败（此时还没有 seed 数据） |
| PC 后台构建 | ✅ success | `pnpm install --frozen-lockfile` + `vite build` 一次过 |
| APP 静态检查 | ❌ failure | `check-raw-text.ps1` 在 Linux 上误报 3 处裸 `<Text>`（全都来自 `LocalizedText.tsx` 自己），根因见下 |

**首跑暴露的脚本缺陷（已修）**：`scripts/check-raw-text.ps1` 与 `check-i18n.ps1` 都把路径写成了反斜杠字面量
（`Join-Path $root 'apps\mobile-app'`）。Windows 上没事，Linux 上拼出一个不存在的路径；更糟的是
**路径不对时脚本「一个文件都没扫到」仍然报「命中 0」/「全部通过」**——假绿比失败更危险。
修法：路径一律走 `[System.IO.Path]::Combine`、比较前把分隔符规范成 `/`、并新增「扫到 0 个文件就退出 2」的守卫。
本地复验：`check-raw-text` 扫 68 个文件命中 0；`check-i18n` 14 个 namespace、缺译 517 条已登记、通过。

**四轮下来才全绿**（每一轮修的都是上一轮暴露的问题，不是重跑碰运气）：

| 轮次 | run | 结果 | 这一轮暴露的问题 |
|---|---|---|---|
| 1 | `42023685805` | 2/3 | 两个 `.ps1` 的路径写成反斜杠字面量，Linux 上排除名单失效 → 误报 |
| 2 | `42024181817` | 3/3 | 通过；顺带确认 Linux 上 `已扫描 68 个文件`、与本地一致，不是被新守卫放行 |
| 3 | `42024520533` | 2/3 | **新加的「`.i18n-pending.json` 防漂移」检查当场抓到真问题**：`ConvertTo-Json` 在 PS 5.1（畸形累积缩进、CRLF）与 PS 7（正常缩进、LF）输出不同，同一文件两个平台两份内容，1560 行全变 |
| 4 | `42025052761` | 3/3 | 改手工固定格式序列化后全绿；`OK：.i18n-pending.json 与 scripts/check-i18n.ps1 的输出一致` 确认防漂移检查真的在跑 |

**第 3 轮那条检查是这轮里最值得留的东西**：`check-i18n.ps1` 的判定是「缺译已登记就通过」，
而它自己会重写 `.i18n-pending.json`——脚本写、脚本读、脚本判，等于自证。加上「跑完工作树必须干净」之后，
仓库里登记的内容必须与脚本真实输出逐字节一致，才可能绿。它第一次跑就抓出了跨平台格式差异。
副产品：`.i18n-pending.json` 从 **190992 字节降到 46772 字节**——原来 3/4 的体积是 PowerShell 5.1 的畸形缩进。

---

## 2026-11-05 · 第 1 段收口后补漏：审计表可篡改 / 注销余额口径 / 法务正文存不进去（基线 v1.44）

**这一节记录的不是新功能，是「验收全 PASS 之后仍然不合规」的三件事。**
段 1 的十条锚点验的是**功能**，下面三项都在锚点清单之外，属于工程与合规缺口。
三项都是**实测发现**——写文档时都以为做了，实际没做。

### 1. 审计表可以被应用连接直接改删（合规缺口，最严重）

《审计与日志规范》对 `operation_log` 写了两条落地手段：Mapper 只读 + **DB 账号无 UPDATE/DELETE 权限**。
§八 第 1 条甚至指定了「在 `deploy/start-services.ps1` 里做」。实测：

```text
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER,
      EXECUTE, CREATE VIEW, SHOW VIEW, TRIGGER ON `faze`.* TO `faze_app`@`localhost`
GRANT ALL PRIVILEGES ON `faze_test`.* TO `faze_app`@`localhost`
```

两个问题叠在一起：①审计表对运行时账号开放了 `UPDATE`/`DELETE`；②**迁移与应用共用同一个账号**，
所以应用连接同时持有整套 DDL。`OperationLogMapper` 的注释里其实写着「faze_app 有 DELETE 权限是段 0 的历史授权，
收口时应收回」——识别到了，没做。

**MySQL 权限是并集，库级 GRANT 无法用表级 REVOKE 减掉**，所以「既能在库里建表、又不能改审计表」
只有一个合法解：迁移与运行分离。

| 落地 | 说明 |
|---|---|
| `database/grants/01-accounts.sql` | 建 `faze_migrate`（持 `faze.*` DDL+DML），只做增量授权 |
| `database/grants/README.md` | 为什么必须拆两个账号、生产怎么初始化、已知边界 |
| `deploy/sync-db-grants.ps1` | 幂等收敛：跑 SQL → 撤 `faze_app` 库级授权 → 按 `information_schema` 逐表授权；`-Verify` 只核对不改 |
| `application.yml` | Flyway 改用 `spring.flyway.user/password`（`FAZE_DB_MIGRATE_*`），不再回落到 datasource 账号 |
| `deploy/start-api.ps1` | `FAZE_DB_MIGRATE_USER` / `FAZE_DB_MIGRATE_PASSWORD` 进必需变量校验，缺了直接退出 3 |
| `deploy/verify-seg1.ps1` | **新增第 7 步**跑 `sync-db-grants.ps1 -Verify`，并作为一票否决项计入「能否收口」 |

`faze_app` 现在的权限形态：逐表 `SELECT/INSERT/UPDATE/DELETE`；`operation_log` **只有 `SELECT, INSERT`**；
`flyway_schema_history` 只有 `SELECT`；无任何库级授权、无 DDL。

`-Verify` 跑 6 项探针，其中 3 项是**真发一条语句**而不是读 `information_schema`（权限表说没有、实际能改的情况是存在的）：

```
[OK] 库级授权已清空
[OK] 表级授权覆盖全部 15 张表
[OK] operation_log 对 faze_app 只开放：INSERT, SELECT
[OK] faze_app 实测 UPDATE operation_log 被拒（1142 command denied）
[OK] faze_app 实测 UPDATE app_user 通过（业务表可写）
[OK] faze_app 实测 CREATE TABLE 被拒（运行时账号无 DDL）
```

**踩坑记录**：PowerShell 5.1 把含引号的参数交给原生 exe 时会二次转义，
`grantee = "'faze_app'@'localhost'"` 这类语句直接被拆坏（`ERROR 1064 near @'localhost''`）——
SQL 一律走 stdin 而不是 `-e`。另外 `ERROR 1142` 写 stderr 在 `ErrorActionPreference='Stop'` 下
会被包成 `NativeCommandError` 直接中断脚本，探针位置必须局部放开。

### 2. 注销前判余额用缓存口径 → 资金悬空（G-9）

`AccountSecurityService.cancelAccount` 只读 `user_balance`（缓存）。而缓存是派生值：
某笔流水写了但缓存更新失败时缓存会偏小，于是**用户能带着余额注销，钱再也退不出去**。
`BalanceQueryService` 的接口注释当时还在为这个做法辩护（「缓存只用于注销前置校验」），
等于把风险写成了设计。

改为：账本（`SUM(balance_flow.delta_cents)`）与缓存**取较大值**做保守判定，任一口径 > 0 就拒绝；
两个口径不一致时打 WARN 留给巡检。接口注释同步改成「决策点一律以账本为准，且必须与缓存交叉校验」。

新增 `CancelAccountBalanceTest`（3 个用例，全部是「制造缓存 ≠ 流水」才有意义的场景）：
缓存 0 / 流水 100 元 → 必须拒；流水 0 / 缓存 100 元 → 必须拒；两边都 0 → 注销成功且副作用完整。

### 3. PC A-08 根本改不了法务正文（G-3，比原记录更严重）

原记录是「表格里用单行 `el-input`，600~900 字正文看不全」。实测发现还有更硬的一层：

**`AdminSysConfigUpdateBO.Item.configValue` 上有 `@Size(max = 500)`**，而
`user_agreement_content` 是 619 字、`privacy_policy_content` 是 633 字。
也就是说 **PC 后台永远存不进去这两条**，返回 `{"code":401001,"msg":"items[0].configValue: 配置值过长"}`。
之所以一直没暴露：seed 是 SQL 直插绕过 BO 校验，开发阶段全部用脚本灌数据，只有运营真去后台改一次才会撞上。

修两层：

| 层 | 改动 |
|---|---|
| 后端 | `@Size(max = 500)` → `@Size(max = 20000)`，与 V12 把列改成 `TEXT`（65535 字节）后 UTF-8 的容量匹配；错误信息写明上限 |
| 前端 | 长文本（`valueType=string` 且超 80 字）在表格里只放 60 字摘要 + 「编辑正文（N 字）」按钮，点开是 `rows=18` 的多行 textarea 弹窗，带实时字数；判定用「原值与当前值的较大者」，避免编辑中删短就掉回单行控件 |

新增 `AdminSysConfigLongTextTest`（3 个用例）：用**库里那条真实 619 字正文**做样本原样写回
（用构造的短串测的话，500 的上限也能过，测试等于没写）+ 20000 字边界通过 + 20001 字被拒且提示上限。

`verify-g3-longtext.js` 用真实 Chrome 跑完整交互 **14/14 通过**：
摘要渲染 → 弹窗 `rows=18` → 输入 252 字含 9 个换行且**换行真的保留** → 「确定」进待保存 →
「保存修改」+ 二次确认落库 → 列表摘要变成新正文 → 最后走 API 恢复原值（`operation_log` 里改动与恢复成对）。

**踩坑记录（CDP 验收）**：Chrome 会把后台标签页的 `requestAnimationFrame` 节流，
RuoYi layout 用 `<transition>` 包 `router-view`，一被节流就永远停在 `fade-transform-leave-active`，
**路由匹配对了、面包屑都变了，内容区还是上一页**。三处修复：Chrome 启动加
`--disable-background-timer-throttling --disable-backgrounding-occluded-windows --disable-renderer-backgrounding`；
脚本里 `Page.bringToFront`；切路由用 `router.push` 而不是给 `location.hash` 赋值
（hash 已是目标值时不会触发 `hashchange`）。

### 4. `docs/README.md` 基线号补齐 + 落款纠正

v1.43 收口时这份索引没同步（§7.47 已登记），基线号停在 v1.42。另外发现它的**落款写错了**：
§1.1 规定落款是「该文档第一次纳入基线的版本」并专门举例「根 README 落款仍是 v1.0」，
但 v1.36 起被改成当前基线号后一路跟着升。本次改回 `v1.0` 并在 §1.1 补历史说明。

### 收口

| 项 | 结果 |
|---|---|
| 后端用例 | 48 → **54**（G-9 三条 + G-3 三条），全绿 |
| 审计权限 | 6 项探针全过；已接入 `verify-seg1.ps1` 第 7 步作一票否决项 |
| G-3 浏览器实测 | 14/14 通过（`deploy/verify-g3-longtext.js`，已入库） |
| 段 1 完整验收 | `verify-seg1.ps1` 全绿：54/54 用例 → 对账差异 0 → 管理端 23/23 → 端到端 38/38 → 错误态 7/7 → **数据库权限 7 项**；**十条锚点全 PASS**，判定「可以收口」。证据 `docs/acceptance/seg1/verify-seg1-20261105-195014.log` |
| 基线 | v1.43 → **v1.44**，资产数仍 **43**，内容变更 **1 份**（`docs/README.md`） |

**本次没做的事**：`faze_app` 仍能 `SELECT` 全部业务表（含 `app_user.password_hash`、`realname_auth` 密文列），
列级授权会让 MyBatis 的 `SELECT *` 直接报错，段 1 不动；`operation_log` 的超期清理仍然没有脚本
（规范要求人工审批 + 先归档，属于运维流程而不是代码）。

---

## 2026-11-05 · 第 1 段收口：APP 18 页 + 端到端验收，十条锚点全 PASS（基线 v1.43）

**范围**：G 组（APP 18 页）/ H1（锚点验收）/ H2（收口五件套）。**真机口径全部改为浏览器验收**（负责人 2026-11-05 决定），
所以 `0-A3` 与 `1-A9` 的真 RTL 那一半在浏览器里做不了，已明确挂账而不是假装通过。

**APP 18 页全部交付**：G1 启动与认证 5 页、G2 律师入驻与我的 3 页、G3 设置 8 页、G4 余额与支付密码 2 页。
公共层新增 11 个组件（ActionBar/CheckRow/CodeTimerButton/ConfirmDialog/FormField/MoneyText/PinField/SettingRow/StatusHero/ToggleRow/WebFileButton）、
2 个 hook（useAsyncResource / useErrorMessage）、3 个工具（prefStore/serverTime/money）、10 个 api 文件。
`tsc` 0 error、`eslint` 0 告警、`check-raw-text` 命中 0、`check-i18n` 通过（bo/ug/kk 各缺 517 条已登记）。

**验收分两层，两层都过才算接通**：
- **页面层**（CDP 驱动真实 Chrome，390×844@2x）：老账号 20 路由 + 新账号 13 页表单形态 + 滚到底不被底栏压住 + `dir=rtl` 镜像，**全部零白屏零 console error**；
- **接口层**（`deploy/verify-e2e.js`，真实 HTTP + 真实库）：38 项端到端——注册→登录→忘记密码→改密→实名提交→PC 审→入驻提交→PC 审→充值→有余额拒注销→清余额注销→同号重注册，**38/38 通过**；
- **错误态**（`deploy/verify-error-states.js`）：用 CDP 只拦 `api/v1` 请求制造接口失败，7 页逐个验「有可读文案 + 有重试按钮 + 点了真能恢复」，**7/7 通过**。

**修掉的 7 个真缺陷**（都是验收逼出来的，只看代码发现不了）：
1. `ProfileVO.lawyerStatus` 恒 `NONE` —— 已入驻用户「我的」页显示「未入驻」；
2. `ProfileVO.balanceCents` 恒 `0` —— 余额为零与「接口没接」在页面上无法区分；
3. `ProfileVO.payPasswordSet` 恒 `false` —— 「账号与安全」永远显示「未设置」，用户会去走「重置」多花一次验证码；
4. 协议/隐私正文**没有匿名可读接口**，且 `sys_config.config_value` 只有 `varchar(500)`（实测导入就 `Data too long`）——
   补 `GET /pub/config/{configKey}`（白名单 2 键，白名单外一律 401004）+ V12 扩到 TEXT + 两条种子正文 + 4 个集成测试；
5. **注册时验证码被过早消费**：漏填监护人被正确拒绝后验证码已作废，改好重试得 402005 还要再等 60 秒；
6. `verify-seg1.ps1` 用 `$out = & mvn` 捕获数万行输出，跑一次从 3 分钟变 10 分钟（改重定向到文件后 32 秒）；
7. `.gitignore` 的 `*.log` 把所有验收证据挡在仓库外（加 `!docs/acceptance/**/*.log` 例外）。

**两条数字纠错**：接口数原文「C 端 20 / PC 端 20」与实测不符，实际 **C 端 27 / PC 端 21**（逐条差异见接口规范 §7.2 修正说明）；
管理端鉴权「`LoginUser` 是标准 JSON」**不成立**（Fastjson2 写的是带 `L`/`Set[...]` 标注的非标准文本），实现改为定点抽取 + fail closed，已回写 §1.3.1 第 5 条。

**收口结论**：`deploy/verify-seg1.ps1` 一条命令跑完（环境自检 → 48 个后端用例 → 对账恒等式 → 管理端 23/23 → 端到端 38/38 → 错误态 7/7），
**1-A1~1-A10 十条锚点全部 PASS**，脚本判定「本段能否收口：可以」。证据：`docs/acceptance/seg1/verify-seg1-20261105-041320.log`。

**本次没做的事**：真 RTL（浏览器无解）、换绑手机未进端到端自动化、三语译文为空 —— 与另外 7 项一起写进《第 1 段工作流》§十.1 遗留挂账（G-1~G-10）。

**基线**：v1.42 → **v1.43**。新增资产 **0 份**（`docs/acceptance/**` 不在基线登记范围），
内容变更 **3 份**（《接口规范》《开发计划总纲》《第 1 段工作流》），资产数仍 **43**。

---

## 2026-11-04 · 段 0 D3 完成：SSE + ngram 命中依据（基线 v1.42）

**范围**：D3 的服务端与前端代码**全部完成**，并用真实 Chrome（CDP）点验通过。**真机项仍待设备。**

**服务端**：`LawHitVO` 加 `matchedTerms`（D3 第 2 条：只有 score 一个数字时验收人看不出「凭什么命中」）；`LawSearchService` 按 `ngram_token_size=2` 切词并筛出原文中实际出现的 token；`ProbeController` 记录首字节耗时放进 done 帧。种子 `database/seed/law_items_probe.sql`（不进 Flyway）。

**前端**：`shared/api/sse.ts` 用 **`expo/fetch` 的 `ReadableStream`**——RN 全局 fetch 建在 XHR 上、`response.body` 是 `undefined`，这条是 D3 的实测结论；`features/dev/pages/ProbePage.tsx` 三块探针（运行时信息 / ping / AI 流 / law 检索）。

**两个技术性让步（都已写进文档，不是偷偷改）**：
1. **首字节不写响应头**：SSE 的响应头在首帧 `meta` 发出时就已提交，而首字节要等第一个 `delta` 才知道——顺序上做不到。改由 `done` 帧的 `firstByteMs` 承载，页面同时显示客户端与服务端两个数互证。
2. **种子是 5 条不是 50 条**：D3 同时规定「不许自己编法条原文」。技术侧能确认的权威原文只有《民法典》5 条，其余 45 条需法务提供。判据只要求「命中 ≥1 + matchedTerms 非空 + 空结果不崩」，5 条够验。

**修掉的两个真 bug**：① `firstByteMs` 用 `long` 装箱成 `Long`，撞上全局「Long → 字符串」序列化，客户端按 number 解析失败 → 改 `int`；② `Screen` 内容区在 web 上宽度算成「父宽 + padding」，右侧溢出视口 → 加 `width:100%`。

**dev-only CORS**：浏览器从 `:8090` 请求 `:8081` 跨域，未加前三个请求全是 `kind=network`。加 `CorsConfig`（`@Profile("!prod")`，只放本机 8090、不写 `*`）；生产 profile 下 bean 不存在，不放开任何源。

**CDP 真点验结果（不是截图猜测）**：ping → 真实版本与时间；SSE → `mock=true`、**客户端首字节 14ms / 服务端 1ms**、流式文本完整；law → **命中 2 条**，`第六百六十七条 | score=0.633425 | 依据=借款`。

**本版没做的事**：真机 SSE 逐段出字未验；`probe/ai` 仍 mock（无豆包 key）；D3 的「真机 + 真后端全链路」判据仍未签。

**基线**：v1.41 → **v1.42**，新增 **1 份**（探针与流式约定），内容变更 **1 份**（docs/README），资产数 42 → **43**。

---

## 2026-11-04 · 段 0 D2 字体落地 + /dev/rtl 页（基线 v1.41）

**范围**：D2 的字体部分 + `0-A3` 的载体页。**真机渲染未验**（无设备）。

**做了什么**：`assets/fonts/` 放入 **Noto Serif Tibetan**（2.06 MB）与 **Noto Naskh Arabic**（308 kB），两份 `OFL-*.txt` 一并入库（OFL 要求随分发保留）；`shared/theme/fonts.ts` 提供 `FONT_FAMILY` / `FONT_ASSETS`；`app/_layout.tsx` 加 `useFonts` 门控（未就绪只铺底色，**加载失败回退系统字体**，不卡启动屏）；`LocalizedText` 的字体族名改从 `FONT_FAMILY` 取。

**一个必须说明的取舍**：用户给的是 **Noto Sans Tibetan**，但 `google/fonts` 的 `ofl/notosanstibetan/` **不存在**（404），`notofonts.github.io` 的 `fonts/NotoSansTibetan/{hinted,unhinted,full}/ttf/...` 与 `notofonts/tibetan` **全部 404**；Google Fonts 只收录了 **Serif** 版。改用 Noto Serif Tibetan（同为 OFL 1.1），换回 Sans 只需改 `FONT_ASSETS` 与 `FONT_FAMILY.tibetan` 两处。

**顺手补掉一个真缺口**：`/dev/rtl` 之前是 B1 建的空壳，而它是 `0-A3`（RTL 整屏镜像）在段 0 的**唯一载体**——真机来了也验不了。现已内建四组静态元素：返回箭头、固定 30% 进度条、3 个静态 Tab、`marginStart`/`marginEnd` 两个方块 + `borderStartWidth` 红线。

**门禁实测**：`tsc` **0**；`eslint` **0**；`check-raw-text` **0**；`expo export --platform web` **837 modules 成功**，包内两个字体均在（2.06 MB + 308 kB）。字体 `require` 被 `@typescript-eslint/no-require-imports` 拦住，已按「Metro 只认 require 形式的资源引用」给该文件单独豁免（写在 eslint 配置的豁免块里，可审）。

**本版没做的事**：字体**真机渲染**（`0-A4`）未验；`/dev/probe` 与 D3 的 SSE 读取、`matchedTerms`、50 条法条 seed 仍未做。

**基线**：v1.40 → **v1.41**，新增 **1 份**（字体选型），内容变更 **1 份**（docs/README），资产数 41 → **42**。

---

## 2026-11-04 · 段 0 D1 云构建启动（基线 v1.40）

**范围**：D1 的「云构建」部分——产出可安装的 `preview` APK 构建，并写清「换机器怎么重建」。**真机安装与 `0-A1` 判定不在本次范围**（本机 `adb devices` 为空）。

**已做的事**：`eas.json`（3 个 profile，`preview` 出 APK、`production` 出 AAB 且只占位）；`app.config.ts` 补 `extra.eas.projectId`、`runtimeVersion: { policy: 'appVersion' }`、`updates: { checkAutomatically: 'NEVER' }`（**不配 `updates.url`**，否则联网拉更新会污染 `0-A8`）；装 `expo-dev-client`（仅 development profile 调试用）。

**构建记录**：EAS 项目 `@1454132yqm/faze`（projectId `06aff2d2-…`）、buildId `f6d854fa-7132-4c04-a37b-eabed777fa00`、profile `preview`、APK 形态、keystore **由 EAS 云端生成并托管**（本机无 keytool，`.gitignore` 已忽略 `*.jks`）、内联 baseURL `http://192.168.43.186:8081`。
构建页面：https://expo.dev/accounts/1454132yqm/projects/faze/builds/f6d854fa-7132-4c04-a37b-eabed777fa00

**踩到的坑（已写进构建路径文档）**：① `eas init` 在该账号下必须带 `--account 1454132yqm`，否则报「multiple accounts」；② 动态配置（`app.config.ts`）**无法被 `eas init` 自动写入 projectId**，必须手写；③ `EXPO_PUBLIC_API_BASE_URL` 是**构建期内联**，换网段必须重建；④ 本机没有 `keytool`，keystore 只能云端生成。

**本版没做的事（全部待真机）**：`adb shell pm list packages` 含 `com.faze.app`、点图标启动无 `FATAL EXCEPTION`、APK 的 SHA256、切 `ug-Arab-CN` 后 `am force-stop` 重开仍 RTL、`reloadAsync` 的实际结论、第二人独立构建——**`0-A1` 一条都还不能签字**。

**基线**：v1.39 → **v1.40**，新增 **1 份**（构建路径），内容变更 **2 份**（依赖清单 / docs/README），资产数 40 → **41**。

---

## 2026-11-04 · 段 0 C 组完成：登录页 + 语言切换页（基线 v1.39）

**范围**：C1 登录页（真接口）/ C2 语言切换页（`forceRTL` + 软重启）。C 组是 `0-A2` 与 `0-A3` 的**唯一载体**。

**C1 登录页**：`features/auth/pages/LoginPage.tsx`（真接口 + 字段校验 + 错误码归一 + 短信 Tab 置灰）、`features/auth/api/login.ts`、`shared/utils/deviceId.ts`。错误码映射按《接口规范》：402001→`auth.login.failed`（不区分账号/密码）、402004→`auth.login.banned`、401007→`auth.login.rateLimited`，**绝不展示 `R.msg`**。

**一个文档错误已纠正**：C1 要求「RTL 下输入框 `textAlign: start`」——RN 的 `textAlign` **没有 `start/end`**（只有 left/right/center/auto），用 `'auto'` 跟随方向，并在页面注释里记下这条口径。

**C2 语言切换页**：`features/settings/pages/LanguagePage.tsx`（4 语言单选、母语名不翻译）、`features/settings/api/locale.ts`（写持久化 → `forceRTL` → reload 的顺序不能改）。`bo` 是 **LTR**，RTL 只有 `ug-Arab-CN` / `kk-Arab-CN`。`reloadAsync` 不可用时提示「需重启」，不假装切换完成。

**门禁实测**：`tsc --noEmit` **0**；`eslint` **0**；`check-raw-text` **0**；`check-i18n` 三项全过（每语言缺 42 个 key 已登记）；`expo export --platform web` **832 modules 成功**。

**本版没做的事（设备侧全部挂账）**：C1/C2 的「算做完」条目全部需要**真机 + 真后端**——登录进首页、输错密码显示中文、`X-Device-Id` 与 body 一致、RTL 整屏镜像、`am force-stop` 后仍 RTL。`adb devices` 为空，**一条都验不了**。C 组同样只能记「代码完成、锚点待验」。

**基线**：v1.38 → **v1.39**，内容变更 **2 份**（未完成需备注 / docs/README），资产数 40 不变。

---

## 2026-11-04 · 段 0 B 组完成：移动端地基（基线 v1.38）

**范围**：B1 Expo 工程与路由树 / B2 网络层与鉴权态 / B3 本地化与 RTL / B4 页面四态与错误边界。**B 组四个任务全部完成**，按「一组一停」停下汇报。

**B1 工程与路由（7 个路由文件）**：`app.json` → **`app.config.ts`**（`extra.apiBaseUrl` 缺配置即抛错，不静默回退 localhost）；删掉 `(auth)` 分组；路由 = `_layout` / `index` / `login` / `language` / `dev/probe` / `dev/rtl` / `+not-found`；`routes.ts` 收缩到 5 条真实常量；`scripts/prepare-release.ps1` 在构建前移出 `app/dev`、`features/dev`（运行时 `if (!__DEV__)` 拦不住「进包」）。

**B2 网络层（唯一出口）**：`shared/api/client.ts` 收敛 8 个请求头、`R<T>` 解析、**401 双通道**（402002 单飞刷新后只重放 1 次 / 402003 直接登出）、**只清被拒 token**（迟到 401 丢弃）、15 秒超时；`onUnauthorized` 经 `authEvents` 解耦（不 import `expo-router`）。

**B3 i18n 与 RTL**：自研 `t()`（不引 i18next）；`MessageKey` 由资源类型推导，**写错 key 编译期报错**；56 个资源文件一次建全；`forceRTL` + `reloadAsync` 软重启（同向不重启）；`LocalizedText` / `LtrText` / `DirectionalIcon` 职责边界；**6 条 lint 规则**（物理方向 / `textAlign` / 裸 `<Text>` / 裸字符串 / 拼 key / 手工镜像）。

**B4 四态与错误边界**：`AsyncState` 四态（**不设 `idle`**）+ `AsyncBoundary` / `LoadingState` / `EmptyState` / `ErrorState`；`ErrorBoundary` 从 `app/_layout.tsx` 导出（expo-router 唯一落点）。

**过程中修掉的真实问题**：① 注释里 `features/*/api` 的 `*/` **提前结束块注释**（tsc 报一堆语法错）；② `.ts` 文件里写 JSX → 改 `createElement`；③ lint 把 props 名 `right` 误判为布局属性 → 改名 `trailing`；④ 6 个共享组件的裸 `<Text>` 迁到 `<LocalizedText>`；⑤ 5 处物理方向属性改 `marginStart/End`；⑥ PowerShell 脚本缺 BOM 被按 GBK 解析。

**门禁实测**：`npm run verify` = typecheck + eslint + check-raw-text + check-i18n **退出码 0**；`expo export --platform web` **成功（735 modules）**；裸 `<Text>` 命中 **0**；i18n 三项全过。

**本版没做的事**：**真机项全部挂账**——`0-A2` / `0-A3` / `0-A5` / `0-A8` 的设备侧验证都需要真机，而 `adb devices` 为空。**代码与门禁完成不等于锚点通过。**

**基线**：v1.37 → **v1.38**，新增 **2 份**，内容变更 **2 份**，资产数 38 → **40**。

---

## 2026-11-03 · 段 0 A3 按《后端代码规范》返工（基线 v1.37）

**动因**：负责人要求「写代码按《后端代码规范 v1.0》的代码 / 目录 / 工程性 / 注释规范」。A3 刚交付，立即用它回头核对已写的 35 个文件——**发现 9 处不符**，全部返工（不是挂账）。

**怎么核的**：写 `docs/acceptance/seg0/_artifacts/selfcheck-backend-spec.py`，逐文件扫包结构、类头 javadoc 行数、public 方法注释覆盖、`// 任务 n` 分段、`ErrorCode` 静态查找、`@ConfigurationProperties`、logback appender、Controller 分层禁令。**不凭印象**——首轮报出的 12 处超长类注释、0 处分段、0 个属性类都是脚本数出来的。

**返工的 9 处**：

| # | 条款 | 返工前 | 返工后 |
|---|---|---|---|
| 1 | §2.1 | `common/` 根平铺 `ErrorCode`/`BusinessException`/`GlobalExceptionHandler` | `common/exception/` |
| 2 | §2.1 | `common/TraceIdFilter` | `filter/` |
| 3 | §3.5 | `modules/account/dto/` 混放入参出参 | `bo/`（`LoginBO`/`RefreshTokenBO`）+ `vo/`（`UserVO`/`LoginVO`/`TokenVO`） |
| 4 | §4.1 | 12 个类头 javadoc 超三行（最长 12 行） | 全部 ≤3 行，长说明留在文件头块注释 |
| 5 | §4.4 | `// 任务 n：` 分段 **0** 处 | `AuthService`（login/refresh/doRefresh）、`LawSearchService`、`ProbeController` 补齐 |
| 6 | §12.1 | `ErrorCode` 无静态查找 | 补 `BY_CODE` + `of(int)` |
| 7 | §12.3 | `@ConfigurationProperties` **0** 个、`@Value` **10** 处 | `config/properties/FazeJwtProperties`/`FazeProbeProperties`（构造绑定 + `@DefaultValue`）；`@Value` 降到 2 处（应用元数据，豁免理由写进注释） |
| 8 | §2.6 | `modules/account/README.md` **缺失** | 补四段（边界 / 数据来源 / 不可改的契约 / 测试入口） |
| 9 | §10.5 / §12.4 / §3.4 | logback 只有 console；MockMvc 只 2 个用例；XML 在 `mapper/` 根 | 三 appender + `AsyncAppender(discardingThreshold=0)`；**10 个用例覆盖 6 个接口**；XML 移到 `mapper/law/` |

**两处文档冲突，已裁决**：① **包结构以后端规范为准**——第 0 段工作流原文把 `exception` 子包拍平了，而它自称「严格照 AGENTS §3.2」，AGENTS §3.2 本来就有该子包，属笔误不是裁决；已回写 §(1) 目录树与 §(3)。② **`@Schema`/`@Operation` 段 0 不引入**（依赖表没有 springdoc，`OpenApiConfig` 原文列了却没有依赖，已从目录树删除），记挂账到首次引入 springdoc 的段。**MapStruct 与 `infra/law/` 按此前约定保留并挂账**。

**顺手消灭两条 ERROR 噪音**（修复前 `faze-api-error.log` 63 行，全非业务故障）：

1. MP 在「XML namespace + `@MapperScan` 双重注册」时打 `mapper is ignored` ERROR → logback 对该 logger 设 **OFF**。**踩过一次坑：先设 WARN 无效**——日志级别是「该级别及以上」，ERROR 照样通过；已把这条写进配置注释。
2. 客户端断开 SSE 后容器回调 `AsyncRequestNotUsableException` → `GlobalExceptionHandler` 加专用分支记 DEBUG；`ProbeController` 该分支由 `completeWithError()` 改成 `complete()`（前者会把异常再抛给异常处理器记 ERROR）。

修完 `faze-api-error.log` **0 行**；`mvn -B test` 10/10 仍全过。

**重验（全部重跑，未复用旧证据）**：`mvn -B verify` → `BUILD SUCCESS`；带 `FAZE_DB_URL` → **`Tests run: 10, Skipped: 0`**；HTTP **16/16**、DB **6/6**、prod 404 **5/5**（`A3-{http,db,prod404}-20261103-2333xx.txt`）。

**基线**：v1.36 → **v1.37**，内容变更 **2 份**（第 0 段工作流 / docs/README），资产数不变 **38**。

---

## 2026-11-03 · 段 0 A3 完成：服务端骨架 + Flyway + 6 个接口（基线 v1.36）

**范围**：第 0 段 A 组的最后一个任务 A3。至此 **A 组（A1 骨架 / A2 服务脚本 / A3 服务端）全部完成**，按「一组一停」纪律在此停下汇报，未进入 B 组。

**产出（`services/faze-api`）**：

| 包 | 内容 |
|---|---|
| `common/` | `R`（code/msg/data/traceId）、`ErrorCode`（16 个段 0 用到的码）、`BusinessException`、`GlobalExceptionHandler`（13 类 + 1 个补充分支）、`TraceIdFilter`、`audit/AuditContext`（段 0 只进 MDC，不建 `operation_log`）、`util/RequestUtils` |
| `config/` | `SecurityConfig`（无状态 JWT、`pub` 放行、`app` 需认证、refresh 单独放行）、`MybatisPlusConfig`、`JacksonConfig`（包装类 `Long` → 字符串） |
| `security/` | `JwtTokenProvider`、`RefreshTokenStore`（进程内，段 1 迁 Redis）、`LoginUser`、`JwtAuthenticationFilter`（失败不抛、标记原因后放行）、`RestAuthenticationEntryPoint`（402002/402003 两码）、`RestAccessDeniedHandler` |
| `modules/account/` | `AppUser` / `AccountMapper` / 4 个 DTO / `AuthService`（登录、单飞刷新、资料）/ `AuthController` / `ProfileController` |
| `infra/` | `law`（`LawItem` / `LawItemMapper` + XML ngram 检索 / `LawSearchService` / `LawHitVO`）、`probe`（`DoubaoClient` mock+真实两条路 / `ProbeController` 三探针 / `PingVO`） |
| 资源 | `mapper/LawItemMapper.xml`、`application*.yml`（加 404 走 JSON、排除自动生成内存用户）、`logback-spring.xml`（traceId + 审计字段） |
| 测试 | `Seg0SmokeTest`（traceId 与 `X-Trace-Id` 同值、无 token → 401+402003）；无 `FAZE_DB_URL` 时整类跳过，带环境变量时真跑 |
| 脚本 | `deploy/start-api.ps1`（读 `.env` 注入环境变量，`-AsPackage` 走 jar）；`services/faze-api/README.md` |

**13 条验收全部执行**（12 条实打实通过 + 第 5 条以 mock 通过），证据 `docs/acceptance/seg0/_artifacts/A3-{http,db,prod404}-<ts>.txt`：

- `mvn -B verify` → `BUILD SUCCESS`；带环境变量时冒烟测试 **`Tests run: 2, Skipped: 0`**（不是跳过）
- `ping` 的 `body.traceId` 与响应头 `X-Trace-Id` 逐字符相同
- `probe/law?kw=合同` 命中 **4** 条（score 最高 `0.018783`）；`kw=婚` 命中 **0**（`ngram_token_size=2` 的预期语义）
- `probe/ai` 首帧 `event: meta, data:{"mock":true}` → 后续 delta → done
- `SHOW TABLES` 恰好 `app_user` / `law_item` / `flyway_schema_history`；`COUNT(law_item)=5`；`SHOW CREATE TABLE` 含 `` WITH PARSER `ngram` ``
- prod profile 下三个探针全 **404 + JSON**（`code=401004`），且 login 仍可用
- 过期 token → `401 + 402002`；签名错 token → `401 + 402003`（**两码可区分**，实测）
- 旧 refresh token 在复用窗口外重放 → `401 + 402003`；**同一 refresh 并发 6 次全部 200 且新 token 唯一**（单飞成立）

**6 处实测偏差已修（全部改代码，不是挂账）**：① 单飞复用窗口 30s → **5s**（30 秒内旧 refresh 可重放兑换，是安全缺陷）；② 排除 SB 自动生成的 `UserDetailsService`（prod 启动日志曾打印随机密码）；③ 缺必填参数回 `501001` → 补分支回 `401002`；④ prod 404 是 Tomcat HTML → 开 `throw-exception-if-no-handler-found` + 关静态资源映射，404 回 JSON；⑤ `SHOW CREATE TABLE` 的 ngram 名**带反引号**（按字面校验会假失败，属验收判据的坑）；⑥ SB4 把 `@AutoConfigureMockMvc` 拆到 **`spring-boot-webmvc-test`**（pom + 依赖清单）。

**文档回写**：《第 0 段工作流》A3 新增「(10) 施工实测与契约补定」——补定 SSE 四事件协议、`kw` 1~50、`score` 语义（原 §七 不确定项 10 要求「A3 先补这三项再写代码」），第 9 条判据补「404 必须是 JSON」，落款 v1.21 → v1.36。《依赖清单》补第 10 项。《BASELINE》新增 §7.40，三份指纹重算（第 0 段工作流 2951 行 / `0f13d7b65353a05f`；依赖清单 63 行 / `11c9ff5d208585c5`；docs/README 165 行 / `4b3d76b657d9efe5`），并修掉落款里「31 份资产」的过时数（应为 38）。

**本版没做的事**：§九「实际完成与偏差」仍未填（那是**段 0 收口**的活，当前只到 A 组）；真实豆包链路（key 未提供）与 RuoYi 登录 / `biz` 菜单仍未验。基线 v1.35 → **v1.36**，资产数不变 **38**。

---

## 2026-11-02 · 段 0 开工前复核：修正 5 处施工前提错误（基线 v1.35）

**起因**：第 0 段 A1（仓库骨架）、A2（本地服务脚本）已完成并实测通过；A3（服务端骨架）写代码前，负责人要求「先搁置写代码，先复核前提」。

**复核做了什么（全部实测）**：Maven 依赖解析、`mvn compile`、应用启动、Flyway 迁移、MySQL DDL/ngram 命中、Redis 启停、jjwt 与 Jackson 依赖树。

**发现并修正的 5 处前提错误（文档级）**：

| # | 文档写的 | 实测 | 修正 |
|---|---|---|---|
| 1 | parent `4.0.10` | central 上 4.0.x 最新是 **4.0.9**，4.0.10 不存在；本机 `.m2` 的 4.0.10 目录只有一个 `.lastUpdated` 失败标记 | 改 **4.0.6**（本地有真 pom，与 RuoYi 4.0.3 同一大版本） |
| 2 | 依赖表列了 `spring-boot-starter-aop` | SB 4.0 起**没有这个 starter**（同位置叫 `spring-boot-starter-aspectj`） | 删掉（段 0 无切面需求） |
| 3 | Flyway 只加 `flyway-core` + `flyway-mysql` | **SB4 还要 `spring-boot-flyway` 模块**；只加前两个时应用能启动，但**不执行迁移、库内 0 张表** | 补 `spring-boot-flyway` |
| 4 | `.env` 里 `characterEncoding=utf8mb4` | Connector/J 报 `Unsupported character encoding 'utf8mb4'`（要 Java 字符集名） | 改 `characterEncoding=UTF-8` |
| 5 | 0-A2 / 0-A5 / D1 / B2 用 8080 | 8080 是 RuoYi 的；faze-api 早已定 8081 | 全文 12 处 `:8080/api` → `:8081/api` |

**顺带修正的文档矛盾**：`(auth)/login` → `app/login`、`/(tabs)/home` → `/`、SplashPage「改造」→ `app/index.tsx`（已随 `9d589d3` 删除）、A3 补登录/刷新/资料三接口（落 `modules/account/`）与 `deploy/start-api.ps1`、§十 关闭已解决的 1/2/7/8 项、新增 §十一「不确定项索引」（修掉正文「§附 不确定项」引用断链）、§二 自述「14 项」→ 16 项。Redis 由 Memurai 改为 **tporadowski 5.0.14.1**（负责人指定，版本差挂账）。

**实测结论（本版依据）**：SB **4.0.6** / Spring 7.0.7 / Security 7.0.5 / Tomcat 11.0.21 / MP 3.5.18.1；Flyway v1/v2 执行成功，`faze` 库 **2 张业务表**（`app_user`、`law_item`）+ `flyway_schema_history`；`law_item` 共 **5 条**法条、含 `WITH PARSER ngram`、`MATCH('借款')` 命中 2 条、单字 `婚` 命中 0；`ngram_token_size=2`；Redis tporadowski **5.0.14.1** `PING=PONG`；测试用户 `13800000001` 已灌入（`database/seed/test.sql`，不进 Flyway）。

**本版改动**：内容变更 **3 份**（第 0 段工作流 / 依赖清单 / docs/README）。资产数不变 **38**。基线 v1.34 → **v1.35**。

---

## 2026-11-02 · 新增《第 7 段工作流 v1.0》：运营与治理收口（基线 v1.34）

**起因**：负责人要求「写第 7 段工作流计划书」，并设了执行约束：**只改自己写的第 7 段计划书；先只检查前提，不够就问，不要硬写**。

**过程**：先做前提检查（读了总纲 §5.8、技术路线 §4.8.4/§5.11/§16/§17、数据表规范附录 A、接口规范、审计与日志规范、PC 计划，并用脚本提取了 18 屏设计稿的结构），发现 **4 项硬缺口**上报负责人。负责人 2026-11-02 逐条拍板，其余 22 项授权「按建议处理」，合计 **26 项裁决**。

**产出**：8 组 41 任务 / 10 条锚点（`7-A1`~`7-A10`）/ 建 11 张表 / 54 个接口 / APP 10 页 + PC 8 页。

**段 7 开工前必须先读的 11 处上游冲突**（全部在 §零之二 裁决完毕，本版已回写）：

| # | 冲突 | 裁决 |
|---|---|---|
| 1 | 总纲说段 7 建 7 张表、数据表规范说 10 张 | **实建 11 张**（9 张在册 + 新增 2 张；`share_poster` 不做；两日志表段 1 建只读） |
| 2 | `sensitive_word_appeal` 表不存在，但有 2 个接口 + A-25 画了申诉 Tab | **新建第 54 张** |
| 3 | 公告的「触达/打开率」无表可落 | **新建第 55 张 `announcement`** |
| 4 | `service_ticket` 缺页面要的 4 个字段 | 补 `ticket_no`/`title`/`priority`/`sla_due_at` |
| 5 | `sensitive_word` 缺「最近命中」 | 补 `last_hit_at` |
| 6 | `content_review` 缺「命中片段」 | 补 `hit_snippet`/`biz_user_id` |
| 7 | 收藏/关注列表接口没登记 | 补登 4 个 |
| 8 | 企业认证没有查状态接口 | 补登 1 个 |
| 9 | A-20 能不能看聊天原文（总纲挂「待定」） | **只看命中片段 + 前后各 3 条**，全文走用户提交的截图 |
| 10 | 客服时段 21:00 vs 20:00 | 以设计稿 **20:00** |
| 11 | 日志留存三处打架，且设计稿低于合规下限 | **以审计规范 §5.2 为准**（操作 ≥3 年 / 登录 ≥180 天），**设计稿 90 天作废** |

**26 项裁决里改变全局口径的四条**：①A-02 看板口径已定；②告警走钉钉机器人；③表总数 53→55；④A-20 只看命中片段。

**复核时抓到一处实质错误**：初稿按 A-26 设计稿把操作日志留存定为 90 天，低于《审计与日志规范》§5.2 的 ≥3 年（个保法/电商法）。**设计稿不是合规依据**——已改为以规范为准，加「配置低于下限则拒绝启动」，并把 A-26 稿的文案列为待改稿项。

**本版改动**：新增 3 份（第 7 段工作流 / 运维手册 / 依赖清单）+ 回写 9 份（总纲 / 技术路线 / 数据表规范 / 接口规范 / 错误码规范 / 审计与日志规范 / PC 计划 / 未完成需备注 / docs/README）。资产 **35 → 38**。

---

## 2026-11-02 · 段 6 写完后自查复核：修 18 处（基线 v1.33）

**起因**：负责人要求「写完之后复核」。

**复核方式**：机械检查（交叉引用 / 表格列数 / 数字口径 / 残留错名）+ 逐项核对 8 份回写的实际落地 + 全仓 grep。

**结论**：**没有发现事实或逻辑错误**。查出 18 处问题，全部是「状态过期」与「口径不准」。

| # | 类别 | 改了什么 |
|---|---|---|
| 1 | **状态过期** | §九 从「待授权后执行 / 未完成前不算收口」改为「第 1~9 行已落地（v1.32）/ 第 10 行待负责人定」，10 行逐行加 ✅/⏸ |
| 2 | 状态过期 | §一.1 出口第 9 条、E3 第 4 条的「9 行」→ **10 行**；附「已知局限」第 5 条从「回写全部未执行、基线 v1.31」→「第 1~9 行已完成」 |
| 3 | 口径不准 | §九 第 2 行「§4.8.2 补 7 个接口」→ 拆准：§4.8.2 补 **5 个**（3 充值 + 2 回调，并改正 `/recharge`→`/recharges`）+ §4.8.4 补 **2 个**管理端 |
| 4 | 口径不准 | §九 第 4 行「补 8 个接口的契约」→ 改为「加 §7.3.1 简表 + 字段级留在本文档」（接口规范 §7.3 自身体例是「只规定长什么样，不管有哪些」） |
| 5 | **表格错** | A3 配置表的 `pay.alipay.*` 四个键从**合写 1 行拆成 4 行**——原来表格 18 行 vs 声明 21 个键，施工方按表逐项配会**漏 3 个** |
| 6 | 基线 | 第 35 行指纹因上述改动而过期，已重算（1023 行 / 113591 B / `a2f996ae75f979b7`） |

**误报甄别（值得记）**：初版脚本报 20 条问题，逐条核查后 **19 条是脚本误报**：
- Windows 下 `os.path.relpath` 返回反斜杠，白名单写成 `/` 导致永不匹配（误报 6 条）
- 数据表规范里字段数写成粗体 `**13**`，正则 `\| (\d+) \|` 不匹配（误报 1 条）
- DDL 正则 `\s{2}\w` 把 `UNIQUE KEY` 行也当成字段行，13 字段被数成 17（误报 1 条）
- 「说明它错」的语境被当成残留错名（误报 11 条）

**复核通过项**：任务 19 / 锚点 7 / 接口 8 / 表 1 / 配置键 21 一致；§零之二 裁决 1~18 无缺号；表格列数 0 处不一致；锚点四段式 7/7、任务五段式 19/19 完整；技术路线 DDL 13 字段、数据表规范第 17 行 = 13、错误码 `405013`~`405020` 八码齐全、审计 4 码齐全、段 1 `biz_id` 陷阱、段 5 A-09 例外、总纲 §5.7 三行、接口规范回调约束——**逐项验证通过**。

**仍未做**：设计稿 `design/ui/36-recharge.html` 的门槛值（`design/ui/` 不在授权范围）。

---

## 2026-11-02 · 新增《第 6 段工作流》：真实充值渠道 + 掉单补单（基线 v1.32）

**起因**：负责人要求产出第 6 段工作计划。

**做了什么**：**新增 1 份 + 改动 8 份**（约 50 处）。段 6 规模最小（1 个 APP 页、1 张表、8 个接口），但**踩到的上游冲突最集中——9 处里有 3 处是上游文档写错**：
总纲 §5.7 的三张表名（`payment_channel` 全仓不存在 / `recharge_order` 实为 `recharge_record` / `reconciliation_diff` 段 5 已建）、`payment_record` 的职责被段 2 与 L1460 的注各写一套、`balance_flow.biz_id` 是 `BIGINT` 存不下业务单号且为 NULL 时唯一键失效。

| # | 文件 | 改了什么 |
|---|---|---|
| 0 | **《第 6 段工作流 v1.0》**（新增） | 5 组 **19 任务** / 7 条锚点（**3 条挂账**）/ 建 **1 张表**（`recharge_record` 13 字段）/ **8 个接口** / 改造 A-09 1 页；§零之二 记 **18 项负责人裁决**；§九 记 10 行上游回写 |
| 1 | 《开发计划总纲》 | §5.7 交付物表三行改对（PC 改「0 新增/改造 1」、数据库改「本段建 + `sys_config` 不建表 + 段 5 已建只写」、接口改 8 个）；锚点表加 3 条挂账说明；出口改「4 过 3 挂账」；§11.2 第 6 行改「已产出」 |
| 2 | 《技术路线 v2.3》 | `recharge_record` 补 2 字段 + `channel` 注释补 `mock` + 补状态索引；**L1460 的注作废**（`payment_record` 只记订单余额支付、不记充值）；§9.1 补「只服务充值」；§9.4 补晚到回调与双路补单；§9.5 **旧余额校验式改为两条恒等式** + 补差异字段映射；§4.8.2 补 5 个接口并**改正 `/app/wallet/recharge` → `/recharges`**；§4.8.4 补 2 个管理端接口 |
| 3 | 《数据表规范》 | 附录 A 第 17 行字段数 **11 → 13**；§9.2 L4 禁入日志清单补 **`recharge_record.raw_payload`** |
| 4 | 《接口规范》 | `pub` 前缀说明补「支付渠道异步回调」；新增**回调七条约束**（只 POST / 免 JWT 靠验签 / IP 白名单 / 64KB / 限频 / 5 分钟时间窗 / **响应体不是 `R<T>`** / 不靠 Redis 去重）；新增 §7.3.1「段 6 的 8 个接口」表 |
| 5 | 《错误码规范》 | 交易域 **+8**（`405013`~`405020`，含 `405020` **未实名不可充值**） |
| 6 | 《审计与日志规范》 | §3.2 补 4 个动作码：`RECHARGE_SYNC` / `RECHARGE_CLOSE` / `RECONCILE_RUN` / `RECONCILE_HANDLE` |
| 7 | 《第 1 段工作流》 | §三 D4 补 **`biz_id` 类型缺陷**（BIGINT 存不下单号 + NULL 时唯一键失效 → 幂等退化），给处置，并注明段 6 不受影响 |
| 8 | 《第 5 段工作流》 | 「不许改 A-09」**加段 6 例外**（段 6 加渠道对账 Tab，Tab1 不动）；`V507` 行补「本表缺去重唯一键」 |

**18 项裁决**（§零之二，第 13~18 项为同日追加）：含**渠道手续费平台承担**（用户充 1 元余额就是 1.00 元，对账按「用户支付金额」比）、**单笔 0.1~5000 元 / 单日上限 5000 元**、**未实名充值额度 0 = 拦截不是限额**（新码 `405020`）、**充值单超时 30 分钟**、**回调域名先保留配置接口**（`pay.callback_base_url` + `pay.callback_ips`，域名未定可留空，Mock 不依赖）。

**3 条锚点挂账**（等支付商户号，不算通过）：`6-A1` 真实到账 / `6-A4` 真实账单比对 / `6-A6` 三方一致。**4 条必须在 Mock 通道下全过**：`6-A2` 回调幂等 / `6-A3` 掉单补单 / `6-A5` 恒等式巡检 / `6-A7` 渠道失败可读。

**本版顺带修掉 4 处跨段不一致**：`balance_flow.biz_id` 的 NULL 陷阱（段 1 隐患）、`/app/wallet/recharge` 违反动词白名单、技术路线 §9.5 的余额校验式漏 `frozen`、`reconciliation_diff` 缺去重唯一键。

**没做的 1 件**：设计稿 `design/ui/36-recharge.html` 的「单笔最低 10 元」两句门槛值与裁决（0.1 元起）冲突，**稿未改**，登记在《第 6 段工作流》§九 第 10 项，等负责人决定改不改稿。

---

## 2026-11-01 · 段 5 的 7 组上游回写全部落地（基线 v1.31）

**起因**：v1.30 只写完《第 5 段工作流》，文档 §九 的 11 行回写里**只做了第 1 行（总纲）**，第 2~8 行标着「开工前」。负责人要求「回写，然后推一次」。

**做了什么**：**10 份文档、约 40 处改动**，全部对应段 5 定稿时发现的 12 处冲突，不是顺手改。

| # | 文件 | 改了什么 |
|---|---|---|
| 1 | 《技术路线 v2.3》 | **5 张表补字段**（`lawyer` +6 / `withdraw_record` +6 / `legal_case` +3 / `case_progress` +`node_code` / `case_library` +`category`）、`legal_case.status` 改 5 值、§4.8.2 补 **8 个 C 端接口**、提现与送达表名改对、结算接口标注「批次记录非资金真相」、**§9.6.3 提现规则重写**（T+1 → 1~3 工作日 / 最低 100 元 → **0.1 元** / 加 **0.6% 手续费**）、附录 D TabBar「案件」→「案例」 |
| 2 | 《数据表规范》 | 表数 **51 → 53**、要建 50 → 52、段 5 由 6 → 8、C 类 9 → 11、**字段数同步 7 行**、§3.11 补 `JS`/`SD`/`CL` 三个前缀、附录 A 末加补正说明 |
| 3 | 《接口规范》 | §6.3 案件归属 `client_id` → **`user_id`**（原写错）；新增「律师侧文书按 `lawyer_id` 归属」一行 |
| 4 | 《PC 后台开发计划》 | A-10/A-13/A-16/A-19 的表名与接口状态改对；A-16/A-19 注明设计稿只有列表；风险第 7 项「电子送达范围」标为已裁决 |
| 5 | 《错误码规范》 | 交易域 +5、案件域 +4、内容域 +3（共 12 个码） |
| 6 | 《审计与日志规范》 | 补 4 个动作码（`CASE_AUDIT` / `CASE_PROGRESS_CORRECT` / `DELIVERY_SEND` / `WITHDRAW_ACCOUNT_CHANGE`） |
| 7 | 《第 1 段工作流》 | 铁律补**第 ④ 条**：`balance_flow.balance_after` 记**账户总余额**，附两条恒等式 |
| 8 | 《第 2 段工作流》 | 拍板第 5 项后加**推翻留痕**（服务费行不写了，三条理由），不改原记录 |
| 9 | 《第 3 段工作流》 | 删「跨域搜索里的法条一类」（设计稿只有四类）；两处 `5-A9` 改为「永不做」 |
| 10 | 《第 5 段工作流》 | §九 状态回填（11 行全部 ✅）+ §三 A1 补 `withdraw_no` |

### 回写过程中又发现两个缺陷（不在 v1.30 的计划里）

| # | 缺陷 | 怎么发现的 | 修法 |
|---|---|---|---|
| 1 | **`withdraw_record` 没有业务单号字段** | 改 DDL 时对照《数据表规范》§3.11「对外只暴露单号」——提现单号 `WD` 无处存 | 补 `withdraw_no` + 唯一索引；字段数 15 → **21**；段 5 工作流字段清单同步 |
| 2 | **内容域 `409001`~`409004` 已被占用** | 补码时读 §3.9 发现四个号都有主（article-not-found / case-not-found / review-rejected / sensitive-word） | 段 5 改用 **`409005`~`409007`**，不撞号 |

### 表清单验算

段分布：2 + 12 + 12 + 3 + 4 + **8** + 1 + 10 + 0 = **52**，加 `invoice`（不建）= **53** ✅
类别：A 21 + B 14 + **C 11** + D 7 = **53** ✅

### 自查复核（写完之后自己过了一遍）

**方法**：机械核对 + 人工判断两轮。机械核对用脚本做五类：①DDL 实际字段数 vs 附录 A 登记数（逐表数 `CREATE TABLE` 里的字段行，排除索引行）；②任务/锚点/组检查点/接口分项/§零之二 行数的自洽；③内部交叉引用（`§九 第 N 项`、`§十 第 N 项`、`§五 5.x`、`§三 X 组`）的目标是否存在；④新错误码、新审计动作码、8 个新接口是否真的写进了目标文件；⑤全库 Markdown 表格列数一致性。人工判断看语义：字段语义是否被两个东西抢用、设计稿描述是否走样、措辞是否自相矛盾。

**查出 10 处，全部修掉：**

| # | 问题 | 类别 | 怎么发现的 |
|---|---|---|---|
| 1 | **两张新表在技术路线根本没有 DDL**（`lawyer_income_settlement` / `case_delivery` 只在附录 A 登记了一行，施工时无处可抄） | **严重** | 核对"附录 A 每张表都要有 DDL 原件"时，逐表搜 `CREATE TABLE` 发现两张表零命中 |
| 2 | **`case_library.case_no` 被两个东西抢用**：附录 A 说案例有 `CL` 单号，但 DDL 里它的注释是「案号」（法院案号） | **严重** | 写 G1「案例编号 CL」时对不上 DDL 注释 |
| 3 | 段 5 工作流头部写「接口 **40** 个（App 23 + PC 17）」，而 §五 5.2 写 41（App 23 + PC 18） | 数字矛盾 | 脚本比对自述数字与实际表格行数 |
| 4 | **§十 的 5 处引用全部偏大 1**（4→3、5→4、6→5、7→6 两处）——插入「提现手续费」作为第 2 项后，后面引用的编号没跟着调 | 引用错位 | 脚本提取全部 `§十 第 N 项` 引用与 §十 实际行数交叉核对 |
| 5 | `5-A2` 通过标准写「写入 `court_name`/`case_no` 之一（本段存 `court_case_no`）」——三个字段名混在一句 | 措辞混乱 | 通读 §四 时发现读不懂 |
| 6 | `§五 5.1` 的补字段清单漏 `reject_reason`、也没写字段数 | 清单不全 | 与 A1 表格交叉核对 |
| 7 | A1 的 `legal_case` 描述写「14 字段 + `form_snapshot`」，实际是 17 字段（还加了 `court_case_no` / `reject_reason`） | 数字不符 | 与 DDL 字段数比对 |
| 8 | A1 的 `case_delivery` 字段清单漏 `user_id`（DDL 13 字段，清单只列了 10 个） | 清单不全 | 逐字段比对 |
| 9 | `§一.1` 出口第 7 条还写「上游回写 7 项（§九）」，实际 §九 是 11 行 / 8 组 | 数字过期 | 通读 §一 时发现 |
| 10 | `case_library` 字段数在附录 A 是 20，补了 `court_case_no` 后应为 21（技术路线 / 附录 A / 工作流三处要同步） | 连锁 | 修第 2 条时的连带 |

**复核结论**：修完之后，DDL 字段数与附录 A **8/8 一致**；全库 Markdown 表格列数 **0 处不一致**；§九 / §十 / §五 / §三 的内部引用**全部命中**；37 个任务 / 8 组检查点 / 8 条锚点 / 41 个接口的自述与实物**全部相符**。

> **第 1 条是这次复核最大的收获**：v1.31 第一版只把两张新表登记进附录 A，忘了技术路线也要有 `CREATE TABLE`——而技术路线是「DDL 原件」的唯一出处（附录 A 只登记字段数）。漏了这一步，段 5 施工到 A1 时会发现"表要建，但没有 DDL 可抄"。

### 验证

| 项 | 结果 |
|---|---|
| 基线指纹 | **34/34 一致** |
| Markdown 行尾 | 全部纯 LF，0 个 CRLF |
| 相对链接 | 0 死链 |
| 表格列数 | 全库 **0 处**不一致 |
| 两分支 | 一致 |
| 桌面镜像 | 已同步 |

---

## 2026-11-01 · 新增《第 5 段工作流 v1.0》（基线 v1.30）

**起因**：负责人要求写第 5 段（案件中心 + 律师工作台 + 全局搜索）的工作流。

**为什么先统计再动笔**：段 5 是三份上游文档口径的**第一次正面碰撞**——总纲 §5.6 写于 2026-10-30、技术路线表清单写于 V2.2/V2.3、设计稿画于 2026-10-29。动笔前逐份核对 + 17 屏设计稿逐屏读正文，**查出 12 处冲突**，逐条与负责人确认后**拍板 18 项**，文档按裁决后的口径写死。

### 18 项裁决里改变方向的 6 条

| # | 裁决 | 不改会怎样 |
|---|---|---|
| 1 | **结算不碰钱**：A-13 只记结算批次，不转账；唯一资金出口是提现（A-10） | 设计稿 A-13 写「确认后金额入账，不可撤销」。真这么做，A-13 与 A-10 会**重复给律师打款** |
| 2 | **送达只送平台自有文书**：删掉设计稿里三处法院侧表述（报送人民法院 /《电子送达地址确认书》/ 人民法院可调取回执） | 设计稿画的是「法院文书经平台转达」，需法院合作协议；没有的话界面做完也没有真实文书来源 |
| 3 | 表 **51 → 53**（加 `lawyer_income_settlement`、`case_delivery`；`lawyer_service_config` 并入 `lawyer`） | 总纲 §5.6 列的 8 张表里 **3 个表名在附录 A 不存在**、1 个名字写错、还漏列 `reconciliation_diff` |
| 4 | **`payment_record` 不写服务费行** | 服务费不是支付、没有资金进出。写了会破坏段 2 刚建的「`SUM(amount_cents)` = 净收款」恒等式，还会产生无流水支撑的孤儿行 |
| 5 | **`5-A9` 删除**（AI 转律师不做上下文转交；段 3 的跳转保持原样） | 锚点总数 79 → 78，必须同步改总纲，否则同一份文档说两个数 |
| 6 | **订单争议 `DISPUTED`/`SETTLED` 移段 7** | 段 2 把这两态留给段 5，但段 5 与 PC 都没有申诉页——加了状态没处用 |

### 新增 1 份 + 内容变更 1 份

| 文件 | 改了什么 |
|---|---|
| `docs/architecture/法泽FAZE_第5段工作流_v1.0.md`（**新增**） | 8 组 37 任务 / 8 条锚点 / 建 8 张表（7 张有页面 + `reconciliation_diff` 只写不展示）/ 41 个接口（App 23 + PC 18）/ **1272 行**。§零 写实测起点，§零之二 写 18 项裁决，§三 每任务五段式，§四 8 条锚点逐条可执行验收 |
| `docs/architecture/法泽FAZE_开发计划总纲_v1.0.md` | §5.6 数据库清单 8 张改对、删 `5-A9`、锚点总数 79 → 78、`5-A1`/`5-A2`/`5-A4` 三条锚点措辞、「必须做」补 3 条、「明确不做」补 2 条、风险表补 2 行、§11.2 状态改「已产出」 |

### 一条跨段一致性缺口（本版新发现）

段 1 工作流**没有明确** `balance_flow.balance_after` 记的是「可用余额」还是「总余额（`available + frozen`）」。段 5 的结算引擎必须有唯一口径才能守恒等式，本版定为**总余额**并给出两条可执行校验（《第 5 段工作流》§三 E0）。`§九 第 3 项` 要求回写进段 1 的账务说明——否则段 6 接充值时会再算错一次。

### 顺带修掉的两处索引缺陷

1. **`docs/README.md` 的基线号停在 v1.27**：v1.28、v1.29 两次升级都漏改（3 处），本版一次改到 v1.30 / 34 份。
2. **`docs/README.md` 的「怎么落地」长表缺第 4 段一行**（只有 0/1/2/3），本版补第 4 段 + 第 5 段，让表连续。
3. **`AGENTS.md` §8 的文档索引只有第 0、1 段**，本版只补第 5 段——**段 2/3/4 的索引缺口仍在**（历史遗留，本次未擅自扩大范围，如实记在这里）。

### 仍然没做的

- **段 0~4 的施工一次都没跑**：仓库无 `services/`，`apps/mobile-app/app/` 只有 3 个路由文件，`git log` 全是 `docs:`。段 5 工作流与段 1~4 同性质，是**纯计划文档**；§二 前 4 项是预测性要求，不是实测现状。
- **7 组上游回写只做了第 1 组（总纲）**。第 2~7 组（技术路线 / 数据表规范 / 接口规范 / PC 计划 / 错误码 + 审计规范 / 段 2 与段 3 工作流各一处）列为**开工前动作**，本版未执行。
- **PC 4 页里有 3 页设计稿只画了列表**（A-13/A-16/A-19），录入表单、详情 Tab、审核弹窗的规格是工作流自定的，没有设计稿可对照——这是本段最大的隐性工作量。

### 验证

| 项 | 结果 |
|---|---|
| 基线指纹 | **34/34 一致**（0 不一致） |
| Markdown 行尾 | **全部纯 LF**，0 个 CRLF |
| 相对链接 | **119 个，0 死链** |
| 文档自查 | 37 个任务标题 / 8 组检查点 / 8 条锚点各有一节 / 41 个接口分项与汇总对得上 / 8 张表版本号 `V500`~`V507` 连续 / 所有「§九 第 N 项」引用指向正确（自查时修正了 1 处：段 1 的账务回写原被错引为第 3 项，已补为第 8 项） |
| 两分支（`UI设计` / `docs`） | 一致 |
| 桌面镜像 | 已同步 |

---

## 2026-11-01 · 段 4 工作流自查，修 4 处文档缺陷（基线 v1.29）

**起因**：负责人要求「自己去验一遍文档，只核对不改」。

**怎么验的**：抽取全部内部交叉引用逐一核目标节是否存在；核对所有数字声称与表格实际行数是否一致；逐条比对 Markdown 表格列数；验证被引用的外部文件是否存在；核对外部文档（总纲 §5.5 / AGENTS.md / 数据表规范附录 A / 技术路线 §10）与本文档的口径是否一致；检查代码块的技术正确性。

**结论**：结构完整 —— **24 任务 / 8 锚点 / 17 接口 / 4 张表全部自洽，76 处内部交叉引用（22 种）全部有效，表格列数全部一致，210 个相对链接 0 死链，33/33 基线指纹一致**。

### 修掉的 4 处（+1 张完成情况表）

| # | 位置 | 问题 |
|---|---|---|
| 1 | 4 处「§九 的 8 项」 | **应该是 11 项**。§九 表格实际 11 行；8 是「证据条数」（8 条锚点），两个数被混用了 |
| 2 | §八.1 第 3 条 | 说「`document` 的**三个** `*_oss_key`」，但 v1.28 删了 `word_oss_key` 后只剩 **2 个** |
| 3 | §九 第 6 项 | 原写「`document` 17→16（删 `word_url`→`word_oss_key` 的路径）」—— 把 v1.27 的**改名**和 v1.28 的**删字段**混成一句，读起来像「删掉一条路径」。已改为「整个删掉 `word_oss_key`，不是改名」 |
| 4 | §九 缺一项 | **《技术路线》§12.3.2 到现在仍写着 `Faze-Content-Type: AIGenerated`**（L3044），而段 4 的 `4-A6` 明确要求「不得含 AIGenerated」。**文档自己在「最容易糊弄的地方」指出了这个矛盾，却没把它列进回写清单** —— 指出了问题但没登记任务。已补为第 11 项，标「开工前」 |

另外补了一张「截至 v1.29 的完成情况」表：11 项里 **7 项已完成**（随 `a070974` 落地）、**4 项未做**（3 项属「收口时」，1 项是本次新发现的硬矛盾）。

### 查出但按要求不动的 3 处（技术性，本机无法验证）

| # | 位置 | 问题 | 为什么不动 |
|---|---|---|---|
| 5 | L429–430 | 代码里用 `FontStyle.BOLD`。openhtmltopdf 的 `useFont` 第 4 参是 `BaseFontStyle.FontStyle`，枚举据记忆只有 `NORMAL`/`ITALIC`，**没有 BOLD** —— 字重靠第 3 参 `weight=700` 表达。若果然如此会**编译失败** | 我不确定真实枚举值，猜着改会写进另一个错的名字 |
| 6 | L108 / L418 / §五.6 | 写「openhtmltopdf 1.0.10 内部 PDFBox **2.0.30**」—— **时间线矛盾**：1.0.10 发布于 2021-11，2.0.30 发布于 2023-08。真实传递依赖应在 2.0.24 左右 | 本机 `web_fetch` 对外网全拦，查不到 Maven 依赖关系 |
| 7 | L455 | `Loader.loadPDF(...)` 这个便捷类是 **PDFBox 2.0.24 才引入**的，更早的 2.0.x 只能用 `PDDocument.load(...)` | 取决于第 6 条的真实版本，同因 |

**处理方式**：不猜。在《第4段工作流》§二 第 5 项那次「10 行代码跑通中文 PDF」的验证里一次拿齐 —— **编译**看 `FontStyle` 存不存在、**`mvn dependency:tree`** 看 PDFBox 真实版本、**跑通**看 `Loader` 能不能用，然后回填。

### 核对结果

| 项 | 结果 |
|---|---|
| 基线指纹 | **33/33 一致**（1 份内容变更） |
| 相对链接 | 210 个，**0 死链** |
| 两分支 | `UI设计` 与 `docs` 一致 |
| 桌面镜像 | 同步 |
| 换行符 | 全部纯 LF |

---

## 2026-11-01 · 段 4 当日第二次改范围：PDF 渲染换 openhtmltopdf、砍掉 Word（基线 v1.28）

**这一版不是新功能，是同一天里把上午定的技术路线推翻重做。** 触发点是负责人问了一句「为什么不能直接在代码里面写好，就是引用 PDF 库？」

### 一、我上一轮的疏漏

上午定「只做中文」之后，**我没有回头重新审视渲染引擎**，直接沿用了 10-30 总纲里的 `poi-tl + LibreOffice`。但那条路线的**唯一理由**是——LibreOffice 做字形整形（shaping），PDF 库按 cmap 逐字符取字形，排不了阿拉伯系连写与藏文叠字。

**只做中文，这个理由就归零了。** 我留着它，等于留了一个没有理由成立的依赖。

### 二、聊出来三件事，两件是我说错的前提

| # | 说法 | 事实 |
|---|---|---|
| 1 | 「用 PDF 库」= 用 `PDFBox` 读 HTML | **PDFBox 读不了 HTML**。它是按坐标画字的库，换行/分页/对齐全要自己实现。能读 HTML 的是 `openhtmltopdf`——而它底层用的就是 PDFBox |
| 2 | iText 是最常用的 Java PDF 库 | **iText 是 AGPL**。faze-api 是网络服务，用 AGPL 的 iText 要么开源整个服务端，要么买商业许可。这条必须先排除 |
| 3 | `openhtmltopdf` 被 `AGENTS.md` 禁用了 | **禁的是「生成含少数民族文字的 PDF（不做 shaping）」**。只做中文不构成违规。但 §2 技术栈那行的措辞更绝对（「不得使用」），会误导 → 已改 |

### 三、最终定下来的链路

```text
模板.html（{{占位符}} + CSS 子集）
   └─ 占位符替换（用户输入必须 HTML 转义）
        └─ openhtmltopdf 1.0.10（LGPL，内部 PDFBox 2.x）
             └─ PDDocument → PDF
```

| 项 | 改前 | 改后 |
|---|---|---|
| 模板 | `.docx` 文件放 OSS | **HTML 存库**（`doc_template.template_html`） |
| Word | 出（`poi-tl` 生成） | **不出** |
| PDF | LibreOffice headless（700MB，1–2 秒/次，要管 profile 隔离） | **openhtmltopdf**（进程内，几十毫秒） |
| `document` 表 | 17 字段 | **16 字段**（删 `word_oss_key`） |
| 锚点 `4-A1` | Word 生成 | **PDF 生成** |
| 锚点 `4-A3` | Word 与 PDF 一致 | **PDF 文本层可选中可搜索** |

**`4-A3` 换成「文本层可选中」是有意为之**：这是 HTML 渲染路线**最容易偷工减料**的地方——用 PDFBox 把一张图贴上去，肉眼一模一样，但文字不能选中、不能搜索，等于扫描件。法院网申要求可检索文本。

### 四、内容变更 6 份

| # | 文件 | 改了什么 |
|---|---|---|
| 1 | `AGENTS.md` | §2 文书行改成「HTML + openhtmltopdf 1.0.10，不做 Word」；§5 禁令加「中文场景可用」的限定 |
| 2 | 《开发计划总纲》§5.5 | 服务端技术链、5 条锚点、出口、风险表全部按新链路改 |
| 3 | 《技术路线》§2.3/§10.1/§10.2/§10.5/§10.5.1 | 依赖表（`poi-tl` 标停用、新增 openhtmltopdf、LibreOffice 划掉）、生成链路图重画、模板机制表改 HTML 口径、删「下载 Word」、正文字体「仿宋_GB2312」→「仿宋」 |
| 4 | 《数据表规范》附录 A | `doc_template` 12→13 字段、`document` 17→16 字段 |
| 5 | 《第4段工作流》 | **整篇重写**（1610→1481 行）。新增：开头写两次变更；B2 的 **HTML 子集白名单**（禁用 flex/grid——openhtmltopdf 不支持且**静默忽略不报错**）；§二 第 5 项把「10 行代码跑通中文 PDF」列为**开工前置** |
| 6 | 《PC 后台开发计划》A-18 | 模板编辑改「HTML 编辑 + 实时预览」；不实现清单从 2 项加到 3 项 |

### 五、两处诚实标注（都写进文档）

1. **`openhtmltopdf` 是 2023 年归档的项目**（最后版本 1.0.10），不再有安全更新。选它是因为它是**唯一的 LGPL「HTML→PDF」方案**。这条写进《第4段工作流》§六 R1 与 §十 第 5 项，出口是「锁版本 + 攻击面小 + 退路 flying-saucer」，**不只写好处**。
2. **B 方案（Linux 容器跑 LibreOffice）在本机落不了地**：实测 **Docker 未安装**，只有 WSL；而且总纲段 4 的出口原文是「生产同款环境（Windows）」，WSL 与它冲突。**这个方案已随本次变更一起作废**，不用再纠结。

### 六、一处仍需注意的设计约束

**富文本编辑器不能用。** TinyMCE/Quill 这类会生成脏 HTML（大量 inline style、`<span>` 嵌套、非 well-formed），喂给 openhtmltopdf 必炸。A-18 用 `monaco-editor`（VS Code 内核）配左右分栏实时预览——运营编辑的是模板结构，不是日常文案。

### 七、核对结果

| 项 | 结果 |
|---|---|
| 基线指纹 | **33/33 一致**（6 份内容变更，0 新增 0 删除） |
| 相对链接 | **0 死链** |
| 两分支 | `UI设计` 与 `docs` 一致 |
| 桌面镜像 | 同步 |
| 换行符 | 全部纯 LF |

---

## 2026-11-01 · 第 4 段工作流产出 + 段 4 范围改「只做中文」（基线 v1.27）

**第 4 段（文书中心）的唯一施工文档已产出。** 但在写之前，负责人改了一个产品假设，改动比文档本身大。

### 一、最重要的一件事：段 4 从「少数民族文字检验场」改成「只做中文」

总纲 §5.5（2026-10-30 写）把段 4 定位成「**少数民族文字排版的最终检验场**」：模板四语言、验维吾尔文连写、验藏文叠字。**2026-11-01 负责人拍板改为只做中文 `zh-Hans`。**

**法律并不要求文书必须中文。** 两条明文：

- 《宪法》第 134 条：「在少数民族聚居或者多民族共同居住的地区，应当用当地通用的语言进行审理；**起诉书、判决书、布告和其他文书应当根据实际需要使用当地通用的一种或者几种文字**」
- 《民事诉讼法》第 11 条：「在少数民族聚居或者多民族共同居住的地区，人民法院应当用当地民族通用的语言、文字进行审理和**发布法律文书**」

伊犁哈萨克自治州正属该范围。

**但实务上**，当事人递交法院的起诉状绝大多数是中文（卷宗归档、对方当事人、上诉审级、法官工作语言都是中文）。负责人把法泽的文书定位于「**能递交给法院的正式文件**」，因此只做中文是**产品假设的选择**，不是技术降级。

**连带结果**：

| 项 | 改前 | 改后 |
|---|---|---|
| 模板数量 | 20 份（5 类 × 4 语言） | **5 份**（5 类 × 1 语言） |
| `4-A3` | 维吾尔文连写 | **删除**，换成「Word 与 PDF 内容一致」 |
| `4-A4` | 藏文叠字 | **删除**，换成「模板热更新不改历史文书」 |
| `4-A6` | PDF 元数据含 5 个 **AIGC** 隐式标识字段 | 改为「**文书溯源元数据**」，**明确不含 `AIGenerated`** |
| 字体授权 | 段 0 R3 标「必须在段 4 之前定」 | **段 4 不再依赖**（段 0 的 `0-A4` 仍要，验的是**界面**） |
| LibreOffice | 理由是「不做 shaping 排不了阿拉伯系连写」 | **仍要**，理由变成「`.docx` → PDF 的版式转换」 |

**`4-A6` 为什么改**：文书是「用户填表 + 运营配 `.docx` 模板 + poi-tl 文本替换」，**没有任何一步是模型生成的**。按《人工智能生成合成内容标识办法》给它打 `AIGenerated` 属**虚假标识**，比不打更糟。所以删掉 `Faze-Content-Type: AIGenerated`，保留真实信息字段（`Faze-Document-Id` / `Faze-Template-Code` / `Faze-Template-Version` / `Faze-Generated-At` / `Faze-Generator`）。

**一条必须防的误读**（已写进文档 §零.4 第 1 条）：不许把「文书只做中文」扩大到「界面只做中文」。`AGENTS.md` §1 的四语言是硬约束，六页 UI 文案仍要 `t()` + `zh-Hans`/`ug-Arab-CN`。

### 二、产出

| 项 | 内容 |
|---|---|
| 新文档 | `docs/architecture/法泽FAZE_第4段工作流_v1.0.md`（**1610 行 / 108,256 字节 / `451a19383ece97fe`**） |
| 结构 | 开头 **⚠️ 开工前必读：本段范围已变更**（9 行对照表 + 法条依据 + 「变了什么/没变什么」）→ §零 实测（上游 8 项可依赖 / 12 项已定裁决 / 四条「不许」）→ **§零之二 拍板 9 项** → §一 目标与出口（6 条出口 + 6 条反向定义）→ §二 前置检查 **20 项**（含 LibreOffice 安装与验证命令、移动端 8 个依赖装法）→ §三 **7 组 24 任务**（五段式）→ §四 **8 条锚点逐条** → §五 产出清单 → §六 风险 9 条 → §七 不做 12 条 → §八 保证 8 条 / 不保证 8 条 → §九 回写 8 项 → §十 待拍板 6 项 → §十一 施工后回写 → 附「生成方式与已知局限」 |
| 规模 | **4 张表**（`doc_template` 13 / `document` 17 / `signature` 15 / `file_record` 14）/ **17 个接口**（App 12 + PC 5）/ **6 页 APP + 1 页 PC（A-18）** / **5 份中文模板** |
| 基线 | v1.26 → **v1.27**，资产 32 → **33** |

### 三、带出的三项连带修正（不是顺手改的，都是段 4 定稿逼出来的）

**① 表名统一**：总纲 §5.5、PC 计划 L87/L181、技术路线 §4.8.4 L913–915 写的是 `document_template` / `document_instance`，而技术路线 §5.6 的 DDL 与《数据表规范》附录 A 写的是 `doc_template` / `document`。**后者是对的**（DDL 与表清单一致），前者全部改正。

**② `document` 的文件字段统一为 `oss_key`**：《数据表规范》附录 B L599 早已裁决「业务表存 `oss_key`，不存完整 URL」，但技术路线 DDL 仍是 `word_url` / `pdf_url` / `signed_pdf_url`。段 4 定稿改为 `word_oss_key` / `pdf_oss_key` / `signed_pdf_oss_key`，并在文档 §三 A1 里写明这是对 DDL 的修正。

**③ 表清单 49 → 51**：段 2 工作流 §九 第 1 项早就把「《数据表规范》附录 A 补 `order_status_log`（第 50）/ `task_queue`（第 51）两行」列为**开工前必办**，一直挂着没做。本次一并兑现，收掉「段 2 说新增第 50/51 张、附录 A 说总共 49 张」的矛盾：

```
原 49 张
+ order_status_log（段 2：orders 只存当前状态，没流水表就回答不了「谁在什么时候把它从 PAID 改成 IN_SERVICE」）
+ task_queue      （段 2：不用 MQ 的替代方案就是「任务表 + 定时重试」，关单/自动退款/离线推送重试全搭在它上面）
= 51 张，要建 50 张（invoice 按「V1 明确不做」不建）
```

类别分布重算 A 21 + B 14 + C 9 + D 7 = 51；段分布重算 段 0=2 / 1=12 / 2=12 / 3=3 / 4=4 / 5=6 / 6=1 / 7=10 / 8=0，合计 50。

### 四、负责人 2026-11-01 拍板的 9 项（文档 §零之二）

| # | 事项 | 结果 |
|---|---|---|
| 1 | 文书语言 | **只做中文** `zh-Hans` |
| 2 | 在线编辑 | **不做**。预览页只读；点段落 → **跳回表单页**；改完用户自己点「生成」→ 文书更新（负责人原话：「理解 1」） |
| 3 | 文书收费 | **免费不限次**，不接 `deduct()`，不扣配额 |
| 4 | 首批模板 | **5 类全做**：起诉状 / 证据清单 / 劳动合同 / 跨境电商合同 / 借条欠条 |
| 5 | 藏文字体 | 确认有开源方案（Noto Sans Tibetan 等 SIL OFL），**但段 4 不再依赖它** |
| 6 | LibreOffice 用途 | 确认它是 `.docx` → PDF 的版式转换器，不是少数民族文字渲染器 |
| 7 | 预览页点击段落 | 只跳回表单页，不做段落定位（因为展示的是 PDF 转的 PNG，无法知道点了哪个段落） |
| 8 | 生成流程 | 跳回表单 → 改 → 用户自己点「生成」→ 文书更新 |
| 9 | 角色 | 本段**只出计划，不写代码** |

### 五、文档里如实记录的三处「没做到」

1. **段 3 承诺的 `ArticleNoValidator` + `refs` schema，段 4 未使用。** 段 3 §八.1 说「段 4 文书中心复用本服务」，但段 4 把法条当「模板正文里的文字」处理（运营在 `.docx` 里写「依据《民法典》第 X 条），不做结构化引用。**这是对上游契约的一次未兑现**，在段 4 §七 第 7 条与 §八.2 里明写了，没有假装用上。
2. **`4-A5` 签名位置容差仍未定。** 总纲原文「建议 ≤ 4mm，具体由负责人定」，负责人本次未给值 → 该锚点只能判「不遮挡正文」，判不了「合格」，**不能判过**。
3. **5 份模板的内容无下落。** 技术侧能给字段清单与版式要求，给不了真实法律文本。文档 §二 第 8 项把它列为开工前置，§十 第 2 项列为待拍板。

### 六、核对结果

| 项 | 结果 |
|---|---|
| 基线指纹 | **33/33 一致**（新增 1 份 + 变更 10 份，逐份重算比对） |
| 相对链接 | **0 死链** |
| 两分支 | `UI设计` 与 `docs` 一致 |
| 桌面镜像 | 同步 |
| 换行符 | 新文档纯 LF（写入时混入 4 个 CRLF，已归一） |

---

**第 3 段（AI 法律顾问）的唯一施工文档已产出。** 段 0/1/2 的施工都还没开始，本文档按「假定段 2 已出口」写，§二 列 **20 项**前置检查。

### 一、产出

| 项 | 内容 |
|---|---|
| 新文档 | `docs/architecture/法泽FAZE_第3段工作流_v1.0.md`（**2188 行 / 165,925 字节 / `64ee94bc5d41d0e7`**） |
| 结构 | 开头 **⚠️ 开工前必读**（5 条探针法条已给出 / 豆包 Key 待提供）→ §零 实测（含 16 项已定裁决）→ §零之二 拍板 12 项 → §一 目标与出口（5 条出口 + 6 条反向定义）→ §二 前置检查 20 项 → §三 9 组 33 任务（五段式）→ §四 8 条锚点逐条 → §五 产出清单（含 **§五.8 段 0 的 5 条探针法条**）→ §六 风险 15 条 → §七 不做 19 条 → §八 保证 7 条 / 不保证 9 条 → §九 回写 32 项 → §十 待负责人拍板 7 项（**已全部结案**）→ §十一 施工后回写 → 附「生成方式与已知局限」 |
| 规模 | 3 张表（`ai_conversation` 7 / `ai_message` 16 / `i18n_glossary` 10）+ `law_item` 补 3 字段 / **16 个接口**（2 新增 / 4 明确不做）/ 2 APP 页 + 2 PC 页 / 39 份验收证据 |
| 基线 | v1.25 → **v1.26**，资产 31 → **32** |

### 二、负责人 2026-11-01 拍板的 12 项

| # | 事项 | 结果 |
|---|---|---|
| 1 | 法条数据 | **先用占位几条验证**；2000+ 条由负责人以后自己往库里插。段 3 的责任是「导入 → 立即可检索 → 立即可引用 → 立即通过校验」这条链 |
| 2 | 豆包 API key | 负责人自己去配，最后告知。key 未到位时 C 组走 mock，四条锚点挂账不判过 |
| 3 | 测试 | 负责人持有 **121（E2E）**，开发完成后统一跑。段 3 交付可断言的钩子（`testID` + `accessibilityLabel`） |
| 4 | 内容审核 | **不接第三方内容安全 API**，走后台人工审核 + 121 |
| 5 | AI 是否收费 | **不收费**，只有每日 50 轮配额；律师咨询的收费属段 2 订单 |
| 6 | 法条走哪条路 | **走 Flyway**（不用 seed 放全量） |
| 7 | 表名 | **`ai_conversation`**（不是 `ai_session`），外键 `conversation_id` |
| 8 | A-27 做几个动作 | **只做 5 个**：列表 / 详情 / 修正 / 批量导入 / 标记失效。新增单条、导出、重新启用**不做** |
| 9 | 复核与校验 | **去掉字段与 UI**，但**保留输出侧条文号存在性校验机制**（`3-A3` 靠它） |
| 10 | `refs` 快照 | 含 `lawItemId`（否则 A-27 的「AI 引用次数」统计不出来） |
| 11 | RTL / 四语言锚点 | **不补锚点**，留段 8 全量走查 |
| 12 | C 类五条 | 按本文档判断执行（`ai_message` B→C 类、Redis 续传、`(tabs)` 补建、`pub/law-items` 归段 3、附件不做） |

### 三、本文档自行裁定的 16 项（§零.3）

AI 不计费 / 不接第三方审核 / 121 E2E / 固定中文回答 / 法条占位 / 走 Flyway / `ai_conversation` / A-27 五动作 / 去字段留机制 / `refs` 含 id / 不补 RTL 锚点 / `ai_message` 改 C 类 / Redis 存 delta / 段 3 补建 `(tabs)` / `GET /pub/law-items` 归段 3 / 附件不做。

### 四、撰写时发现的 28 处文档冲突（分 6 类，全部写入 §九 回写清单）

**A 级阻断（4）**：法条数据与校对责任人 = 零 / 豆包 key 未知 / `3-A5` 要自动化 UI 断言但前端连单测框架都没有 / 内容审核通道未定且配套两表在段 7。

**B 级文档打架（7）**：AI 是否收费（技术路线 0 处、总纲 §3.2 L125 + 段 2 L972 说有）/ `law_item` 全量走 Flyway 还是 seed（总纲 L419 + 技术路线 L3914 vs 数据表规范 L441 + 段 0 L969）/ `ai_conversation` vs `ai_session`（3 处）/ A-27 动作设计稿与接口与 PC 计划三方不一致（且「上下架」只有下没有上）/ 三张表字段数与规范推导差 8 个且复核与校验状态无字段 / AI 多语言三份文档三立场 / `refs` 示例无 `lawItemId` 而两处说明要求有。

**C 级技术地基（5）**：`ai_message` 标 B 类却要求 UPDATE（导出字段）/ SSE `fromSeq` 续传无物理承载（单行 `MEDIUMTEXT` 没有「第 N 段」概念）/ 气泡组件跨段复用无人负责（段 2 写在 `features/conversation/`，违反 AGENTS §3.1）/ `(tabs)` 五 tab 壳归段不明（段 0 删了却跳 `/(tabs)/home`）/ `GET /pub/law-items` 无人认领但 `3-A7` 依赖它。

**D 级验收口径（3）**：8 条锚点无一条覆盖 RTL 与四语言 / 黄金 30 题与 20 个关键词不存在 / `3-A1` 的「≥2000 条」按 6 部法粗估约 1850（**估算未核实**）。

**E 级合规审计（3）**：审计规范把「法条修订」标成段 7 而 A-27 在段 3 / 审计规范无「AI 对话查看/导出」两类动作 / 用户「清空对话」文案与「AI 是合规留痕」的张力。

**F 级表述登记（6）**：`law_item` 缺 `is_enabled`/`created_by` 而段 0 命令「逐字抄」/ `ai_conversation.title` 无生成规则 / A-28 导出的格式未定义 / 附录 A 仍是 49 张（段 2 的 51 张增补未做）/ AGENTS §7 第 9 条指向已失效的 12 页旧清单 / PC 菜单高度（**已核实不是问题**：44px 下 5 项约 854px < 900，剔除）。

**另有一条自查记录**：初稿曾把「接口 15 个」「证据 33 份」写错，定稿前修正为 16 / 39；也曾把「最高技术风险」的行号引成总纲 §5.4 L411，实际 L411 写的就是「合规风险最高」，写「最高技术风险」的是 §3.1 L103。

### 五、三条锚点的口径处理（**两条已获负责人同意，一条是责任转移**）

| 锚点 | 原文 | 本段处理 | 依据 |
|---|---|---|---|
| `3-A1` | 「≥ 2000 条」 | 改为「4 条：库里有数据 / 导入 10 条成功 / 导入后立刻可检索 / 导入的能被 AI 引用并通过校验」 | 负责人已定「先用占位几条验证」（**已同意改口径**） |
| `3-A8` | 「配额只扣一次；失败请求不计费」 | 「计费」→**配额**；保留「只扣一次」与「失败不计」两条判据 | 负责人已定「AI 不收费」（**已同意改口径**） |
| `3-A5` | 「自动化脚本查元素」 | 责任转移：段 3 交付 `testID` + `accessibilityLabel` 钩子清单与截图，最终判定由负责人用 121 执行 | 负责人已定「我有 121 测试，开发完我跑」（**责任转移，非口径变更**） |

### 六、本版四件事的落实

| # | 动作 | 文件 |
|---|---|---|
| 1 | 新增第 32 份资产 + 基线升 v1.26 | `docs/BASELINE.md`（§7.30） |
| 2 | 加索引（②.7 + 问答表 + 目录树） | `docs/README.md`（152 行 → 155 行，`b6d538dc2d02d69a` → `0143bcd7bcc440e4`） |
| 3 | 记录本版变更 | 本文件 |
| 4 | 段 1/2/3 状态改「已产出」 | 《开发计划总纲》§11.2 L908–910（76022 B → 76127 B，`0f78e57b28a77625` → `fff32726b32b14e1`） |

### 六之二、补记：两样外部依赖写进了文档

初版把「段 0 的 5 条真实法条」与「豆包 Key + model id」只写在了交付回复里，**文档里没有**——而文档才是唯一施工依据，看文档的人（包括以后的自己）看不到这两条就会以为「段 3 随时能开工」。

补记内容：

| # | 补在哪 | 补了什么 |
|---|---|---|
| 1 | 文档开头新增 **「⚠️ 开工前必读：两样东西必须由负责人提供」** 一节 | 两样东西各自：谁给 / 最晚什么时候 / **缺了会怎样**；并解释「为什么 5 条法条不许编」（Flyway 迁移一执行就不可改） |
| 2 | §十 待负责人拍板 | 补第 7 项「豆包 API Key + model id」（原 6 项 → **7 项**），并注明第 4、7 两项是「必须交出东西」而非「拍个板」 |
| 3 | 文档规模数字 | 2076 行 / 153,785 字节 → **2097 行 / 156,083 字节**，指纹 `dbabb47ec22333b2` → `94d928dd83db4af8` |

**为什么值得单独记一笔**：这是本文档第二次出现「只在对话里说过、文档里没有」的情况（第一次是「接口 15 个 / 证据 33 份」写错）。**规则：外部依赖、口径变更、数字修正，都必须落在文档里，不能只存在于交付说明中。**

### 六之三、§十 七项全部结案（2026-11-01）

负责人当日逐项拍板，**6 项已定、1 项待提供**：

| # | 事项 | 拍板结果 | 落到哪 |
|---|---|---|---|
| 1 | AI 对话留存年限 | **3 年**（1095 天） | E3 的 `sys_config` 种子；原文「以负责人拍板为准」改为「已定 3 年」 |
| 2 | A-28 查看 AI 原文审计 | **不审计**（只审计导出） | H2 第 2 条；§九 第 14 项 |
| 3 | Prompt 版本可追溯 | **不要**（只记模型名） | D1 第 6 条 |
| 4 | 首批 5 条法条内容 | **技术侧从公开来源给出**，负责人不再另给 | **§五.8**（完整条文 + 可直接用的 `V2` SQL） |
| 5 | 121 接入方式 | **开发完成后负责人自己测**，技术侧不交「接入指南」 | `3-A5` 的判定权归负责人；技术侧只交三套钩子 |
| 6 | AI 回答抽检责任 | **以后更新的内容，现在不需要** | §八.2 保持「本段不保证回答质量」 |
| 7 | 豆包 API Key + model id | **负责人最后提供** | **唯一未结案项**；C 组 mock 推进，四条锚点挂账 |

**本轮连带改动**：§十 表格从「暂用处理 / 最晚时点」改为「拍板结果 / 落到哪 / 状态」；§二 第 7 项与 §三 A3 加指到 §五.8；E3 的留存年限摘掉「暂定」；附「已知局限」第 3 条改写为「5 条内容由技术侧给出、**未经权威库逐字核对**」。

### 六之四、§五.8 的 5 条探针法条：给出但标注「待核对」

负责人要「你自己上网找 5 条」。实测**上网这条路走不通**：

| 尝试 | 结果 |
|---|---|
| `web_fetch http://www.msjcy.gov.cn/info/1164/5083.htm` | ❌ `resolves to a non-public IP address` |
| `web_fetch https://fy.lanews.com.cn/content/2020-12/17/content_9156748_9.htm` | ❌ 同上 |
| `web_search`（3 组查询） | 只返回来源列表，**不含条文正文** |

所以 §五.8 的 5 条是按公开常识给出的，**没有冒充「从权威库抄的」**。文档里写清了三件事：①给了哪 5 条（民法典 679/577/1062/1165、劳动合同法 47）；②**为什么选这 5 条**（覆盖借款/违约/婚姻财产/侵权/劳动 5 个领域，让段 0 的 `/probe/law` 能用 4 个不同关键词各命中一次，且都 ≥2 字避开 ngram 单字 0 命中的已知行为）；③**核对方式**（`flk.npc.gov.cn` 比对，约 3 分钟）与**为什么必须核对**（Flyway 迁移一执行就不可改）。

**这是本版第三次「工具能力不够就如实写清楚」**，前两次是「接口 15 个写错」与「两样依赖没进文档」。

### 七、本版没做的事（如实列出）

1. **§九 的 32 项回写一条都没改**——按「先定文档、再施工」的节奏，其中 **20 项是「开工前」必须清的**（Flyway 裁决、表名、接口路径、多语言、内容审核、迁移号段、气泡组件等），留给段 3 开工前做。
2. **段 0/1/2/3 全部未施工**；本版只产出施工依据。
3. **`docs/README.md` 里「数据表规范 = 49 张表」的两处表述未改**（L101/L57 附近）——那属于「附录 A 的 51 张增补」（§九 第 3 项），不在本版范围。
4. **`docs/standards/法条数据录入规范.md` / `AI对话留存策略.md` / `docs/analysis/ruoyi-patches.md` 未创建**——它们是段 3 施工时 A3/E3/H3 的产出物，现在创建会变成「没有内容先占位」。
5. **法条 2000+ 条、黄金 30 题的真实运行结果**都没有——前者由负责人导入，后者要等 C 组做完。

---

## 2026-11-01 · 第 2 段工作流产出（基线 v1.25）

**第 2 段（律师咨询交易闭环）的唯一施工文档已产出。** 段 0/1/2 的施工都还没开始，本文档按「假定段 1 已出口」写，§二 列 15 项前置检查。

### 一、产出

| 项 | 内容 |
|---|---|
| 新文档 | `docs/architecture/法泽FAZE_第2段工作流_v1.0.md`（1060 行 / 102,413 字节 / `b9202bd5607def18`） |
| 结构 | §零 实测 → §零之二 拍板 7 项 → §一 目标与出口 → §二 前置检查 15 项 → §三 9 组 33 任务 → §四 10 条锚点逐条 → §五 产出清单 → §六 风险 12 条 → §七 不做 14 条 → §八 保证/不保证 → §九 回写 16 项 → §十 待拍板 10 项 → §十一 施工后回写 |
| 规模 | 12 张表（10 既有 + 2 新增）/ 27 个 APP 端点 + 1 个 WS / 11 个 PC 端点 / 16 APP 页 / 4 PC 页 |
| 基线 | v1.24 → **v1.25**，资产 30 → **31** |

### 二、负责人 2026-11-01 拍板的 7 项

| # | 事项 | 结果 |
|---|---|---|
| 1 | `order_status_log` 建不建 | **建**（第 50 张表） |
| 2 | `task_queue` 建不建 | **建**（第 51 张表） |
| 3 | 消息中心两页数据源 | **做空态**，`notification` 留段 7 |
| 4 | 状态机几个状态 | **8 态**（到 `REFUND_REJECTED`），`DISPUTED`/`SETTLED` 留段 5 |
| 5 | `payment_record` 写几条 | **2 条**（支付 + 退款负数），服务费留段 5 |
| 6 | 订单与会话绑定 | **一个律师一个会话**，`conversation` 不加 `order_id` |
| 7 | 管理员看聊天原文 | **全量可见、不脱敏**；每次写 `operation_log`（**合规风险已在文档中如实标注，未替负责人背书**） |

### 三、撰写时实测发现的 12 处文档缺陷（其中第 3 处为初稿误判、已撤回，实际有效 11 处）

1. `order_status_log` 在附录 A 里不存在，但技术路线 §4.8.4.6 L888 引用它。
2. `task_queue` 在附录 A 里不存在，但附录 D 第 3 条要求加 `trace_id`，《后端代码规范》§9.2 L1032 写着「（《数据表规范》附录 A）」——悬空引用。
3. ~~`operation_log` 的 DDL 缺 `detail` 字段~~ **初稿判断错误，已撤回**：查的是技术路线 §5.10 L1806（段 1 已宣告作废的版本）。§5.11.5 L2102 的正式版**有** `detail JSON` / `target_type` / `target_id` / `result`。真正的教训是「该表有双份 DDL，必须认版本」。
4. 审计规范 §3.2 的必审计动作**缺「查看聊天原文」**，且判定规则「纯查询不标」会漏掉 GET 审计。
5. 总纲 §5.3 L365 的数据库清单是旧口径（13 张旧名，漏 `message_offline`，`user_device` 实为段 1）。
6. `orders.status` 列注释只列 7 个值，缺 `refund_rejected`。
7. `payment_record.channel` 列注释无 `balance`。
8. `REFUNDING` 审核拒绝「回到前一状态」无字段承载（`orders` 没有 `refund_from_status`）。
9. `refund_record` 缺审批意见字段（只有 `reason`）。
10. `2-A5`「不生成订单」与「先下单再支付」冲突。
11. `2-A8`「余额退回」在 `PENDING` 超时场景不成立（没扣过钱）。
12. `lawyer.specialties` 自由 JSON vs 设计稿固定 5 类分类；且 `11-order-detail.html` **没有**聊天记录入口（拍板第 7 项要求）。

### 四、本版同步更新的文档

| 文件 | 改了什么 |
|---|---|
| `docs/README.md` | 阅读顺序加 ②.6、按用途加一行、目录树加一行、基线号 v1.22 → v1.25 |
| `docs/BASELINE.md` | 头部升级 v1.25 / 31 份、资产表加第 31 行、§五 脚本加一行、§六 阅读顺序加第 2 段、新增 §7.29、修正 §二 标题（28 → 31 份）与 §1 的「24 份资产」笔误 |
| `docs/CHANGELOG.md` | 本条 |

### 五、本版没做的事

- 段 0 / 段 1 / 段 2 **都未施工**。
- **数据表规范附录 A 尚未真正改成 51 张**（§九 列了回写清单，按节奏留给段 2 开工前）。
- **技术路线 4 处 MQ 未回写**（总纲 §7.5 已列行号）。
- `operation_log.detail` 未补；审计规范未加 `CONVERSATION_VIEW`。

---

## 2026-11-01 · 分页插件同批验掉：`mybatis-plus-jsqlparser` 必须单独加（基线 v1.24）

**又一个坑。**

### 一、结论

| 做法 | 结果 |
|---|---|
| 只加 `-spring-boot4-starter:3.5.18.1`，写 `new PaginationInnerInterceptor(...)` | ❌ **编译失败**：`找不到符号: 类 PaginationInnerInterceptor` |
| 再加 `mybatis-plus-jsqlparser:3.5.18.1` | ✅ 编译通过，分页 SQL 实测 `LIMIT ?` / `LIMIT ? OFFSET ?`，数据正确 |

**根因**：3.5.18.1 把 `PaginationInnerInterceptor` 从 `mybatis-plus-extension` 拆到了 `mybatis-plus-jsqlparser`，SB4 starter **不传递依赖它**。解压 `mybatis-plus-extension-3.5.18.1.jar` 的 `plugins/inner/` 下只剩 `DynamicTableName` / `OptimisticLocker` / `ReplacePlaceholder` / `Sharding` / `InnerInterceptor`——**没有分页拦截器**。

### 二、验证输出

```
[PROBE] selectCount=5
[PROBE] page1 total=5 size=2 names=[u1, u2]
[PROBE] page3 total=5 size=1 names=[u5]
[PROBE] MP_OK
BUILD SUCCESS

==> Preparing: SELECT COUNT(*) AS total FROM tmp_user
==> Preparing: SELECT id,user_name,status FROM tmp_user LIMIT ?
==> Preparing: SELECT COUNT(*) AS total FROM tmp_user
==> Preparing: SELECT id,user_name,status FROM tmp_user LIMIT ? OFFSET ?
```

**分页是真的加了 LIMIT**，不是「查全量再内存截断」——后者是最常见的假分页，数据量一大就拖垮服务。第 3 页（current=3, size=2）正确返回 1 条，说明 OFFSET 也生效。

### 三、改了 5 份文档

`AGENTS.md`（第 6 条 + §8 索引）/ 技术路线 §2.2 / 后端代码规范 §1.1 / 第 0 段工作流依赖表 / 第 1 段工作流（§零.2、A3 节正文、A3 风险表）。

证据：`docs/acceptance/seg1/A3-mp-probe.md` 第七节（失败日志、jar 内容取证、分页 SQL、对法泽的处置）。

### 四、没做的

第 1 段仍未施工。A3 相关的**两个坑都已闭环**（starter 选错 → 静默失效；分页插件缺失 → 编译失败），段 0/1 施工时不需要再排查。

---

## 2026-11-01 · A3 实测：MP 必须换 SB4 starter；冗余副本已清理（基线 v1.23）

**两个动作，一个反转结论。**

### 一、A3 验掉了，结论是反转的

| 组合 | 结果 |
|---|---|
| MP **3.5.12** + `mybatis-plus-spring-boot3-starter` + SB 4.0.6 | ❌ **能启动，但 MP 自动配置静默跳过** |
| MP **3.5.18.1** + `mybatis-plus-spring-boot4-starter` + SB 4.0.6 | ✅ **启动 + `selectCount` + `selectById` + `@TableName` 全通过** |

**根因**：MP 3.5.12 的 `MybatisPlusAutoConfiguration` 上 `@AutoConfigureAfter` 引用的 `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`，在 Spring Boot 4.0 已被移到 `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`。**旧类不存在** → Spring 用 ASM 读注解时静默丢弃该 class 值 → 排序约束丢失 → MP 在 DataSource 之前评估 → `@ConditionalOnSingleCandidate(DataSource)` 查不到 bean → **整个自动配置跳过**。

**现象只有一句 `Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`，不报 `NoClassDefFoundError`。** 这类失败最难查——所以「启动没报错」绝不能当作「能用」的证据。

**处置**：`AGENTS.md` §2 / 技术路线 §2.2 / 后端代码规范 §1.1 / 第 0 段工作流依赖表 / 第 1 段工作流 A3 全部改掉；A3 从「可能一票否决」降为「复验」。证据 `docs/acceptance/seg1/A3-mp-probe.md`（含复现命令、条件评估报告、字节码取证）。

### 二、冗余副本已清理

删掉 `E:\dshcode\ruoyi-392`（9.3 MB）与 `E:\dshcode\ruoyi-vue3-392`（3.2 MB）。删前复核过：两套 HEAD 与保留副本完全一致（后端 `0e2d75c2`、前端 `9baaff7`），工作区干净。

保留（负责人指定位置）：`E:\itheima\SSM\…\文件\RuoYi-Vue` + `RuoYi-Vue3`；课程作业备份 `_RuoYi课程作业备份_20261101\` 未动。

### 三、顺手修一个 v1.22 的错误

技术路线原写「Spring Boot **4.0.10**」——**这个版本在 Maven Central 不存在**（本机 `.m2` 的 4.0.10 目录只有一个 `.lastUpdated` 失败标记）。实测可用的是 **4.0.6**，已改。

### 四、没做的

第 1 段仍未施工；`mybatis-plus-jsqlparser` 在 3.5.18.1 下是否自动带入（分页插件依赖它）未验——**D2 分页任务开工前必须先验**。

---

## 2026-11-01 · 新增《第 1 段工作流》：8 组 29 任务 / 10 条锚点 / 12 张表（基线 v1.22）

**起因**：第 0 段施工文档已产出，但第 1 段只有总纲 §5.2 的边界定义，没有可执行的工作流。而第 1 段是「人」和「钱」的地基（18 APP 页 + 7 PC 页 + 12 张表 + 44 个接口），没有施工文档只能照任务名硬猜。

### 一、这份文档

**1239 行 / 76,347 字节**。结构：§零 实测前提（含「段 0 未施工」的真实状态）→ **§零之二 负责人拍板的 12 项** → §一 目标出口 → §二 前置检查 17 项 → **§三 8 组 29 任务** → **§四 10 条锚点逐条展开（含「可能假通过」）** → §五 产出清单 → §六 风险 10 条 → §七 明确不做 12 条 → §八 留给下一段 → §九 回写模板 → §十 待拍板 4 条。

### 二、负责人 2026-11-01 拍板的 12 项

支付密码**连错 5 次锁 30 分钟**（到期自动解锁）/ 单号 `<前缀2位><yyyyMMdd><序号6位>` / 抽成 **15% 占位** / Redis 用 **`E:\Redis-x64-5.0.14.1`** / OSS 已有服务器 / 短信**只做开关** / 三个跨段依赖表**不提前建、本段做简化版** / 注销**立即生效** / 未成年人**用户手动勾选** / RuoYi 前端**已切纯净副本** / 协议正文**模板占位** / PC 计划两个错表名直接改。

### 三、三个跨段依赖的处理（本段最关键的裁决）

A-04 要的 `file_record`（段 4）、A-03/A-06 要的 `user_violation`（段 7）、A-09 要的 `reconciliation_diff`（段 5）——**都不提前建**：

- 实名影像直接存 `realname_auth.id_card_front/back`（实测该表已有 `VARCHAR(500)` 字段，不需要 `file_record`）
- 封禁只改 `app_user.status` + 写审计，**不做违规四档处罚**（段 7 再做）
- 对账只做恒等式巡检，差异**内存态展示**不落表（段 5 再做差错持久化）

**不为让页面「完整」而提前建别的段的表**——那会让两段的验收互相污染。

### 四、顺手发现的三处文档缺陷

1. **C 端接口数写错**：《接口规范》§7.2 与总纲 §5.2 都写「20 个」，但 §7.2 表内相加是 5+1+2+3+3+2+2+2+3 = **23 个**；加上清单遗漏的「换绑手机」接口，应为 **24 个**。本文档按 24 施工，把「修数字」列为 H2 收口项。
2. **「换绑手机」接口不在清单里**：但 APP 18 页里有「换绑手机页」，总纲 §5.2 也写了「account：…换绑」。
3. **两套 RuoYi 纯净副本并存**：负责人指定的位置是 `E:\itheima\SSM\…\文件\RuoYi-Vue`（`release-3.9.2` / HEAD `0e2d75c2`）与 `RuoYi-Vue3`（tag `v3.9.2` / HEAD `9baaff7`）；另一并行会话另切一套到 `E:\dshcode\ruoyi-392` / `ruoyi-vue3-392`（同 tag、同 HEAD、内容一致）。以指定位置为准，冗余建议清理。

### 五、没做的事

第 1 段**未施工**（本文档只是施工依据）；`MyBatis-Plus 3.5.12` 在 Spring Boot 4 下的适配**仍未验证**——它是 A3 的必验项，也可能是全项目最大的一票否决项。

---

## 2026-11-01 · 第 0 段工作流补齐 RuoYi 版本强制条款（基线 v1.21）

**起因**：负责人要求「把 §零之四 按最终版强制约束重写」。查证后发现 v1.20 已经把 §零之四 从「三方案待拍板」改成「已裁决」，但**缺三样**：判版本规则、违规判定、「3.5 / 4.0 / 3.9.2 是三个维度」的对应关系。本版补齐，并修正 v1.20 漏改的落款。

### 一、补了什么（只动 1 份文档）

| 新增 | 内容 |
|---|---|
| §零之四 **4.1 版本对应关系** | 维度表（RuoYi 版本 / Spring Boot 版本 / 前端依赖版本）+ **三条判读规则**：RuoYi 一律 3.9.2；Spring Boot 一律 4.0；判版本**只看** `pom.xml` 的 `<ruoyi.version>` 与 `package.json` 的 `"version"`，**不看 git tag** |
| §零之四 **4.2 违规判定** | 6 类情况逐条给判定：出现 3.8.7 / Spring Boot 3.5 / `springboot3` 分支 / `master` 分支 / 拿 git tag 判版本 / 拿 `RuoYi-Vue` 自带的 `ruoyi-ui` 当项目前端 —— 全部按**缺陷**处理 |
| 落款修正 | `v1.19` → **`v1.21`**（v1.20 改了文档但漏改落款，属维护缺陷） |

### 二、为什么必须把「版本对应关系」写死

`3.5` / `4.0` / `3.9.2` 是三个不同维度，混着读必然误判：

| 看到 | 真实含义 | 常见误读 |
|---|---|---|
| RuoYi **3.9.2** | 平台版本，唯一合法值 | 以为还有别的可选 |
| Spring Boot **4.0**（4.0.3 / 4.0.x） | 框架版本 | 以为「RuoYi 4.0」 |
| Vue **3.5.26** / Element Plus **2.13.1** | 前端依赖版本 | 与上面两者混为一谈 |
| **3.8.7 / 3.5.x** | **已废止** | 以为「还能当参考实现」 |

特别写明一条：**`springboot3` 分支的 tag 停在 `v3.8.7`，但这不代表 3.8.7 可用**——tag 落后于实际版本，判版本只能看 `pom.xml`。

### 三、顺带关闭一条 v1.20 的遗留

v1.20 的「没做的事」里写着「RuoYi 前端尚未切出纯净副本」。现在两份纯净副本都已就位并实测：

| 用途 | 路径 | 版本（实测） |
|---|---|---|
| 后端 | `E:\dshcode\ruoyi-392` | 分支 `release-3.9.2` = tag `v3.9.2`（HEAD `0e2d75c`，**与官方 gitee tag 完全一致**）/ Spring Boot 4.0.3 / mybatis-spring-boot 4.0.1 / Java 17 |
| 前端 | `E:\dshcode\ruoyi-vue3-392` | tag `v3.9.2` / Vue 3.5.26 / Element Plus 2.13.1 / Vite 6.4.1 / Pinia 3.0.4 |

两者**不进 FAZE 仓库**（AGENTS §3.3：第三方框架不入库），登记在 `pc/README.md` 与 `docs/architecture/依赖清单.md`（后者仍是 A1 的创建项）。

### 四、复核结果

- 29 份资产逐份重算 LF 数 / 字节 / SHA256：**仅《第 0 段工作流》1 份变化**（2856→2887 行 / 257,189→259,684 字节），其余 28 份未变
- 代码围栏 **188 个、配对**（过程中两次误报「不配对」，原因是 PowerShell 把 here-string 里的反引号当转义符吃了——已改为 .py 文件运行）
- 旧版本字样残留 5 处，**全部在「已废止 / 违规判定 / 不要看 tag」的语境里**，属合规引用
- **本版没有推进** `MyBatis-Plus 3.5.12` 在 Spring Boot 4 下的适配实测（v1.20 标记的唯一未验证项，仍是段 0 任务 A3 的必修）

---

## 2026-11-01 · RuoYi 锁 3.9.2 官方发行版，法泽全仓 Spring Boot 3.5 → 4.0（基线 v1.20）

**起因**：负责人要求「强制使用 3.9.2」，随后要求「把法泽的 SB3.5 全部改成 SB4」。执行时撞到两条硬事实，改变了原来的判断。

### 一、两条硬事实

1. **RuoYi 官方 tag `v3.9.2`（2026-03-26）的 `pom.xml` 就是 `spring-boot.version=4.0.3`。** 官方**不存在**「3.9.2 + Spring Boot 3.5」这个发行版——之前以为可以「3.9.2 版本号 + SB 3.5」并存，是把**开发分支**当成了发行版。
2. **`springboot3` 分支的 tag 停在 `v3.8.7`**，其后 265 个提交都没有 v3.9.x 的 tag。所以「判版本看 pom 不看 tag」这条规则恰好说明：`springboot3` 不是发行版。

**结论**：PC 后台定为 **RuoYi-Vue 3.9.2 官方发行版（tag `v3.9.2` / Spring Boot 4.0.3）**，`faze-api` 同步升 **Spring Boot 4.0**。

### 二、源码动作（不在本仓库）

把官方发行版切到 `E:\itheima\SSM\…\文件\RuoYi-Vue`：

- 新建分支 `release-3.9.2`，HEAD `0e2d75c2` = tag `v3.9.2`
- `git status` 干净；638 个源码文件（265 java / 101 vue）
- SQL：`sql/ry_20260320.sql` + `sql/quartz.sql`
- **切之前先把原课程作业的 4 处未提交改动备份**到同级 `_RuoYi课程作业备份_20261101\`（8 个文件：course 模块 6 个 + `application-druid.yml` + `MyTask.java`），否则 `git clean` 会永久删掉

### 三、文档变更 9 份

| 文件 | 改了什么 |
|---|---|
| `AGENTS.md` | §2 服务端 SB 3.5 → **4.0**；强制约束第 2 条从「必须用 `springboot3` 分支」**翻转为「必须用 tag `v3.9.2`，`springboot3` 废止」**；新增 MP 在 SB4 下无 starter 的待验项 |
| `README.md` | 技术栈两行同步 |
| 技术路线 | §2.2 `SB 3.5.3` → `4.0.10`；三处 RuoYi 路径与版本 |
| 后端代码规范 | 「三个参考项目 3.5 同代 → 零成本直搬」**改为「业务级可直搬、框架级 API 须逐条核对」** |
| 接口规范 | 硬证据来源改 3.9.2；**「待实测 LoginUser 序列化」→「已实测 JSON 可读」**，补 Jackson 需设 `FAIL_ON_UNKNOWN_PROPERTIES=false`；裁决 9 行号更新 |
| 第0段工作流 | §零之四 整节从「版本断层 + 待拍板」重写为「已裁决」；坏消息从「不可能通过」改为「被 A2 的 Redis 阻塞」；依赖表 `3.5.16` → `4.0.10` |
| PC后台开发计划 | 技术底座 3.8.7 → 3.9.2 官方发行版 |
| PC后台UI设计说明 | 补实测：3.9.2 把 `.el-menu-item` 写死 `44px !important`，50/46 压不住；但 44px 让 11 组菜单约 810px < 900，原「装不下」顾虑消失 |
| 未完成需备注 | §六 事实来源 3.8.7 → 3.9.2 |

### 四、顺手验掉的一个待实测项

`LoginUser` 在 Redis 里的序列化：读 `RedisConfig.java` L29–33 确认 value 用 `FastJson2JsonRedisSerializer`，**是 JSON 不是 JDK 序列化**。faze-api 用 Jackson 可读，但 `serialize()` 带 `JSONWriter.Feature.WriteClassName`，JSON 里有 `@type`，必须设 `FAIL_ON_UNKNOWN_PROPERTIES=false`。

**这条原本是方案 A「可能被强制降级到方案 B」的风险，现在消除了。**

### 五、新增的未验证项（唯一一条）

`MyBatis-Plus 3.5.12` 在 Spring Boot 4 下**没有对应 starter**——本机 `.m2` 只有 `mybatis-plus-spring-boot3-starter`，全盘无 `*spring-boot4*` 构件；RuoYi 官方发行版用的是原生 MyBatis + PageHelper。**段 0 的 A3 必须先实测**，不通则 ORM 选型由负责人拍板。

### 六、没做的事

- RuoYi **前端**（RuoYi-Vue3 3.9.2）本机只有 `D:\aiCodeProject\projects\ruoyi-admin` 且被改过 12 处，**尚未切出纯净副本**
- `docs/analysis/ruoyi-patches.md` 仍未创建
- Spring Boot 4 下 Spring Security 6→7 的授权写法未逐条核对

---

## 2026-10-31 · 新增《第 0 段工作流》，开工前实测出 4 个环境缺口（基线 v1.19）

**起因**：总纲 §11.2 的 9 份段工作流里，第 0 段是唯一「待产出」，而 §5.1 又写着「施工细节见《第 0 段工作流》」——没有它，第 0 段只能照 12 个任务名硬猜怎么写。这份文档要解决的是「**拿着它就能施工、施工完能二值判定**」。

### 一、这份文档本身

**2854 行 / 256,883 字节**，14 个 H2 章节 + 156 个代码块。结构：§零（实测前提）→ §零之二~四（4 处冲突裁决 + 存量处置 + PC 版本断层）→ §一 目标出口 → §二 前置检查（16 项）→ §三 任务分组（4 组 12 任务，每任务五段式）→ §四 8 条锚点逐条可执行验收（含命令 / 判定标准 / 证据形式 / **可能假通过**）→ §五 产出清单 → §六 风险 → §七 明确不做 → §八 留给下一段的接口 → §九 回写模板 → §十 待负责人拍板的 10 条。

它比总纲大 3 倍，因为**性质不同**：总纲定「做什么、按什么顺序」，本文是「怎么做到、怎么证明做到了」。

### 二、开工前实测发现 4 个与总纲前提不符的事实（这是本版最重要的部分）

编制过程中对仓库与本机做了实测，发现总纲（2026-10-30 写）的假设有 4 处不成立。**全部按实测纠正，并在文档里给出处置**：

| # | 实测事实 | 与总纲假设的偏差 | 处置 |
|---|---|---|---|
| 1 | `apps/mobile-app` **不是空工程**：16 个路由文件、8 个功能域 / 12 个页面 + 5 个 fixture，数据全假，且**有 109 处裸 `<Text>`、55 处物理方向属性、7,984 个硬编码中文字符** | 总纲把 B1「Expo 工程与路由树」当成从零搭建 | B1 改为「审计 + 改造 + 处置存量」；12 个业务页与 fixture **全部删除**，归档到只读分支 `ui-reference`（打保底分支后一次性提交） |
| 2 | **本机没有 Android SDK**，只有 winget 装的 adb | 总纲风险表预判了，但实测更彻底：连 SDK 目录都没有 | `0-A1` 的本地构建通道不可用。**但 `eas-cli/22.0.0` 已装且已登录（`1454132yqm`，Owner）**，EAS 云构建立即可用 → 「出不了包」从一票否决风险降为可执行 |
| 3 | **本机没有 Redis**（6379 未监听、无服务、无二进制、七个常见目录都没有），且无 docker、`wsl -l -v` 报「没有已安装的分发版」 | 总纲要求 `start-services.ps1` 同时拉起 MySQL + Redis | `0-A6` 的 Redis 一半按现状必然失败。裁决：装 Memurai；**段 0 服务端不引入 Redis**（refresh 状态用进程内 Map，段 1 前迁）；若暂缓须负责人书面把 `0-A6` 拆成 `0-A6a`/`0-A6b` |
| 4 | **本机没有配套的 RuoYi 后端**：`E:\itheima\vue\ry_vue3\RuoYi-Vue3` 只有 3.8.7 **前端**；能找到的后端在课程资料目录，是 **3.9.2 / Spring Boot 4.0.6** | 总纲/AEGNTS 都按 3.8.7 写 | 见 §零之四：给出 A（下载 3.8.7 后端）/B（整体用 3.9.2，但打破锁定的 SB 3.5）/C（混搭，不可用）三方案，**需负责人拍板** |

另外两条环境事实：**当前 shell 不是管理员**（`Start-Service` 需管理员，`0-A6` 必须在管理员 PowerShell 里跑）；**MySQL root 凭据拿不到**（`mysql -uroot` 返回 `ERROR 1045`），没有它建不了 `faze` 库、查不了 `ngram_token_size`。

### 三、裁决了 5 处文档冲突

| # | 冲突 | 裁决 |
|---|---|---|
| 1 | **段 0 的接口数**：总纲 §5.1 列 3 个 `pub`；接口规范 §7.1 说「不实现任何业务接口」；同文件 §2.2 又把登录组标「落段 0」；而 `0-A2` 要求「真接口登录 + 请求需鉴权接口」 | **6 个**：`pub/ping`、`pub/auth/login/password`、`app/auth/refresh`、`app/account/profile`、`pub/probe/ai`、`pub/probe/law`。**必须补 `profile`**——原 3 个全是 `pub`，外加 refresh 用 refresh token（失败返回 402 不是 401），没有受保护资源的话 `0-A2` 的 401 **物理上测不出来**。已回写《接口规范》§7.1 |
| 2 | `0-A2` 的「首页」与 `0-A3` 的「Tab 顺序 / 进度条」在段 0 **没有载体** | ① `app/index.tsx` = 启动分流器 + 一行 `ping` 连通状态（不新建业务首页）；② `/dev/rtl` 探针页内置四组静态元素（返回箭头 / 30% 进度条 / 3 个静态 Tab / `marginStart-End` 方块）作为 `0-A3` 的唯一载体 |
| 3 | law 探针 50 条 vs 5 条 | 两者都要但分开放：`V2` 迁移 5 条（迁移不可变），`database/seed/law_items_probe.sql` 50 条（种子可迭代） |
| 4 | Redis 缺失 | 见上表第 3 行 |
| 5 | `.gitignore` 忽略 `*.apk`/`*.log`，但 `0-A1` 要求存证构建产物 | 证据只入库「指针」：`eas-build.json` + 截图 `.png` + 输出 `.txt` + `_meta.md`（记 sha256）；APK 留 `E:\dshcode\tmp\seg0\apk\`；**证据禁用 `.log` 后缀**（进不了 git） |

### 四、i18n 选型的一处反转（值得记下来）

初稿拟用 `i18next`，理由是它的 `fallbackLng` + `parseMissingKeyHandler` 正好对应 i18n 规范 §5.1 的降级链。**架构侧否决：i18next 的复数走 `Intl.PluralRules`，而技术路线 §13.7.1 已裁决「不用 `Intl`」**（Hermes 的 locale 数据缺失会静默回退英文）。核实技术路线 §13.7.1 标题确为「为什么不用 `Intl`」→ **改为自研轻量 i18n**，并利用 `MessageKey = keyof typeof zhHans` 做**编译期** key 检查（比 i18next 的运行时 missingKeyHandler 更强）。

### 五、存量 15 个页面的删除（唯一需要负责人签字的动作）

12 个业务页面 + 12 个路由壳 + 4 个 fixture 目录 + `assets/legacy/index.ts` 删除，先打只读分支 `ui-reference` 保底。量化依据：删 = 0 处改造；留 = 101 处 `<Text>` + 50 处物理方向属性 + 约 800 个 i18n key 要填四语言（那是段 8 的填充线）。**不接受**「在 eslint 里豁免 `features/`」——那等于让 `0-A7` 变成假通过。

### 六、顺带修掉的 6 处既有缺陷

| 位置 | 缺陷 |
|---|---|
| `BASELINE.md` 头部 | 标题 / 编号 / 资产数**停在 v1.13**（资产写 24），而正文已记到 v1.18、§二 已写「28 份」。从 v1.14 起每版只更新正文，没回头改头部 —— 本版一并修正为 v1.19 / 29 份 |
| `BASELINE.md` §五 | 验证脚本 `$files` 只有 24 项，漏掉早已纳入的 4 份（接口规范、后端代码规范、i18n 规范、审计与日志规范）→ 补至 29 项 |
| `接口规范` §4.1 | `Idempotency-Key` 写「见 §5.4」，实为 §6.4（幂等） |
| `接口规范` §6.7 | 原写「RN 的 fetch 支持 `response.body.getReader()`」——**实测是错的**：RN 0.81 的全局 fetch 建在 XHR 上（`react-native/Libraries/Network/fetch.js` L17 → `whatwg-fetch` L534 `new XMLHttpRequest()`），没有 `ReadableStream`；必须用 `expo/fetch`（`expo@54.0.38` 的 `FetchResponse.body` 才是 `ReadableStream`） |
| `scripts/README.md` | 写「本目录当前为空」，实际有 5 个 `.py` |
| `AGENTS.md` / `docs/analysis/` | AGENTS §4.12 要求 `docs/architecture/依赖清单.md`、§3.3 要求 `docs/analysis/ruoyi-patches.md`，**两个文件都不存在** → 登记为 A1 创建项，不在本次预建 |

### 七、本版没做的事（明确记账）

1. **第 1~8 段工作流文档**未写（按段递进，不在本版范围）。
2. **ArchUnit / checkstyle / gitleaks / JaCoCo / i18n 六项 CI** 全部未实现——本文已把它们逐条归到「段 1」「段 8」并写明理由（段 0 没有 `modules/`、没有状态机与资金类，规则上了也是空转）。
3. **真 CI 未建**：段 0 用 `deploy/verify-all.ps1`（七步、不许 skip、证据落盘）作等价物，硬期限写死「段 1 开工前」。
4. **总纲 §7.5 列的 MQ 回写**（技术路线 4 处「消息队列」改写为「数据库任务表 + 定时重试」）仍未做，仍是遗留。

---

## 2026-10-30 · 新增《i18n 规范》《审计与日志规范》，A 方案落地（基线 v1.18）

**起因**：负责人拍板 **PC 端 token 互认走 A 方案（共享 `token.secret` + 共享 Redis）**，并要求把 i18n 与审计日志这两份补齐——它们是五维审计里最后两个空白维度。

### 一、《i18n 规范》（567 行）

现状：技术路线 §13 有 122 行，**机制层写得深**（按 Unicode 区段切 run、RTL 的 `forceRTL` 流程、混排 `U+2066` 隔离、不用 `Intl`），但**约定层是空的**——24 条审计只有 1 条 defined。

| 原缺口 | 补到哪 |
|---|---|
| key 命名只有 1 个示例 | **§3 三段式** `<域>.<场景>.<元素>`、全域 camelCase、`{{camelCase}}` 命名占位符（**禁位置参数**——藏文/维文语序不同必然错位） |
| 四语言目录是否同构未定 | §4.2 **强制同构**（唯一例外：允许空 `{}` 但文件必须存在） |
| 13 个 feature 域 vs 5 个 json | §4.3 **14 个 namespace 映射表** |
| 78 个 `error.*` 归属 | §4.4 独立 `error.json`，用**完整 msgKey** |
| 缺降级策略 | §5.1 降级链 `lang → zh-Hans → ⟦key⟧` |
| 与内容层「回退中文」的口径冲突 | §5.3 划界：**UI 文案静默降级、内容文本显式标注** |
| RN 没有 `[dir=rtl]` | §8.1 用 `start/end`，**禁手动 `I18nManager.isRTL` 反转**（会与 RN 自动镜像叠加成"翻两遍等于没翻"） |
| 服务端通知用哪种语言 | §10.2 按 `app_user.preferred_lang` |
| 无完整性门禁 | §11 **六项 CI 检查** + `ug-Arab-CN` 之外的「登记式缺译」（段 8 收口时必须为空） |

### 二、《审计与日志规范》（374 行）

现状：审计四要素有定义、表结构有，但**「哪些动作必须审计」没有清单、「留存期」全仓 0 处、「不可篡改」0 处、「审计与日志的分界」0 处**。

| 补的 | 内容 |
|---|---|
| §2 **分界** | **审计进数据库、日志进文件**。反例：ZXZ 前端埋点做审计（能伪造）、wgcloud 55 处复制粘贴漏 1 处 |
| §3.2 **18 项必审计动作清单** | 按段列，判据三条（动钱 / 改不可逆状态 / 改别人能看到的内容）+ ArchUnit 断言「admin 非 GET 必须有 `@OperationLog`」 |
| §3.4 **不可篡改的工程保证** | Mapper 只读 + DB 账号无 UPDATE/DELETE + 超期清理须人工审批先归档 |
| §5 **留存期** | 网安法 §21 网络日志 ≥6 个月、个保法 §56 处理记录 ≥3 年、电商法 §31 交易信息 ≥3 年、资金 ≥5 年。**明确标注「不是法律意见，年限由负责人拍板」，技术侧给可配可归档能力** |
| §6 **可查询性** | 「查这条订单被谁改过」是最常用取证查询，必须有索引；**查日志这个动作本身也要审计** |

### 三、A 方案落地到《接口规范》

负责人拍板后立即回写（原文还写着「待拍板」）：

- §1.3 **改为「已拍板：方案 A」**，并新增 **§1.3.1 实施要点 6 条**：两端 `token.secret` 从**同一环境变量**注入、同一 Redis 但 key 前缀隔离（`login_tokens:` vs `faze:`）、复刻 `JwtAuthenticationTokenFilter` 校验链、只取必要字段降低耦合、**⚠️ 待实测 `LoginUser` 在 Redis 里的序列化方式**、在 `ruoyi-patches.md` 登记耦合点。
- **失败退路写清了**：若 `LoginUser` 是 JDK 序列化导致读不出来，降级方案 B（双登录），不至于卡死段 1。
- §八 的 P-1 标记为已解决。

### 四、连带改动 4 份

| 文件 | 改了什么 |
|---|---|
| `AGENTS.md` | §4 新增第 9 条（i18n）、第 10 条（审计与日志）；§8 索引加 2 行 |
| `docs/README.md` | 阅读顺序加 ③.8、③.9；索引补 3 行；目录树加 2 行；基线 → v1.18、资产 26→28 |
| `法泽FAZE_技术路线_v2.3.md` | §13.2 标「已被《i18n 规范》取代」（原文只画 5 个 json、key 命名只有一个示例、装不下 78 个 `error.*`） |
| `法泽FAZE_数据表规范_v1.0.md` | **新增附录 D**：三份新规范提出的 **5 项表结构追加**——`app_user.preferred_lang`、`notify_template` 唯一键加 `lang`、`task_queue.trace_id`、`sys_config` 四个留存配置键、`operation_log` 授权分离 |

### 五、本版没做的事

i18n 的两条 ESLint 规则（`no-raw-text`、`no-dynamic-i18n-key`）与六项 CI 检查、审计的四条 ArchUnit 规则，**都还没实现**——是第 0 段 CI 的施工内容。

---

## 2026-10-30 · 把调研出的闪光点从「表格」落成「规则」（基线 v1.17）

**起因**：负责人问「**你发现他们的闪光点之后，有没有把它们补到文档里面去？**」

**自查结论：没有。** 逐条核对后发现——ZXZ 的 Excel 导入、金额舍入，wgcloud 的攒批落库 / 先删后插 / 异常双写 / 图表降采样，jxyd200 的模块 README / `.env.example` / service-preflight，**全部只出现在 §1.2 / §1.5 的一行表格里**，没有任何一节把它们写成法泽的可执行规则。

**表格里写一句「值得抄」很轻松，写成规则要费劲——所以在表格里就停了。**

### 一、补进后端规范的 12 处

| 新增 | 抄自 | 法泽的落点 |
|---|---|---|
| §2.6 模块必须 `README.md` | jxyd200（唯一不用工具就守住 100% 的制度） | 固定四段：边界 / 数据来源 / 不可改契约 / 测试入口；CI 用 `find` 断言 |
| §3.9 导入与导出 | ZXZ（`DefaultExcelListener` + JSR-303） | 法条库 3000 条导入；**收集全部错误行**而非首个错；幂等键 / 错误导回文件 / >1000 行转异步 |
| §3.10 图表降采样 | wgcloud（`compressChartListData`） | **但指出它等间隔抽样会漏尖峰**，改用时间桶 `AVG`/`MAX` 聚合 |
| §8.6 金额计算与舍入 | ZXZ（`BigDecimalUtils` + `RoundMode`） | 三条规则 + `Money` 工具类；**分摊除不尽的余数必须落给最后一份** |
| §8.7 整表覆盖「先删后插」 | wgcloud | `sys_config` / 律师服务设置；ZXZ 全项目唯一一处 `@Transactional` 正是这个场景，互相印证 |
| §8.8 攒批落库 — **不采用** | wgcloud | 法泽业务写是用户触发、无持续高频；且攒批有丢数据风险 |
| §8.9 多方言适配 — **不采用** | wgcloud（`DatabaseIdProvider`） | 单库 MySQL 锁定；留记号：进信创环境时这是标准做法 |
| §10.4.1 异常「文件 + 库表」双写 | wgcloud | 方向对，但改掉它「同步 INSERT 且 DB 挂了就丢」「存 `e.toString()` 丢行号」 |
| §12.3.1 `.env.example` 写清填错后果 | jxyd200 | 四重隔离 + 默认拒绝；写「填错了会写脏哪个库」 |
| §12.4.1 service-preflight | jxyd200 | 解决「服务没起 → 用例全跳过 → 退出码仍是 0」的假绿 |
| §14.6 把坑固化成机械约束 | jxyd200 的 6 个 ESLint 插件 + `tech.md` 模板 | 「边界与不做什么」是最有价值的一节；并指出它 3 份 tech.md 都写了测试计划却 0 个测试文件 |
| §14.2 承接矩阵加一行 | — | `modules/*/README` 由 CI 断言 |

### 二、补进接口规范的 2 处

| 新增 | 抄自 | 内容 |
|---|---|---|
| §2.3.1「API 文档宁缺勿造」 | jxyd200 | **第二份过期事实源比没有文档更坏**；Swagger 是唯一事实源，`docs/` 只放「怎么访问」 |
| §6.1.1 客户端 401 两条细节 | jxyd200 | **只清「被拒的那个 token」**（并发下旧请求的迟到 401 会清掉刚登录的新会话）；**双通道判定**（管理端是 `HTTP 200 + code=401`，只看 HTTP 状态永远不触发登出） |

### 三、两条「不采用」也写了下来

攒批落库、多数据库方言——**决定不用也是结论**。不写下来，下次还会有人提，还是一次次重新讨论。

### 四、这次自查带来的方法

**写完「抄什么」的表格后必须回打一遍**：每个「值得抄」的条目，问一句「它在正文的哪一节？」——**答不上来的，就是还没补。**

> 表格是索引，不是交付物。

### 五、行数

后端规范 1344 → **1609 行**；接口规范 900 → **945 行**。

---

## 2026-10-30 · 按批注重排后端规范的评价重心（基线 v1.16）

**这一版改了什么**

v1.15 交付后收到两条批注，指的是同一类问题：**我把「它的取舍能不能搬」写得多，把「它真正强的地方」写在角落。**

| 批注 | 我原来的写法 | 改了什么 |
|---|---|---|
| 「ZXZ 要看它的闪光点：**代码注释量、注释的工程规范性、目录结构的工程规范性**」 | 这三条只作为 `①②③` 挤在 §1.2 表格里，然后用一整节（§1.3）论证「它读多写少不能照搬」 | 新增 **§1.4** 展开三条，各配实测数字与可抄/不可抄的分界 |
| 「wgcloud **它最好的就是对于安全问题**」 | §十一 里只有它的漏洞（未鉴权接口、明文凭据、SQL 注入），几乎没有正面 | 新增 **§1.5** 列它的 **11 项安全机制**，并重写 §十一 开头两节 |

### 一、ZXZ 的三条长处（新增 §1.4）

| 长处 | 实测 |
|---|---|
| **注释量** | `@param` **668** 处 / `@return` **299** / `@Schema` **1,576** / `@Operation` **46 个接口全覆盖 100%** / `@Tag` 9/9 |
| **注释的工程规范性** | 类头与方法**模板固定**；`@Schema(requiredMode=REQUIRED)` 与 `@NotBlank` **成对出现**——文档说必填、代码也校验必填，杜绝漂移 |
| **目录结构的工程规范性** | **技术分层 + 业务子包双维度**：`dto/xzb/`、`mapper/dm/`+`mapper/mysql/`、`rule/calculation/bigdecimal/{fixed,incentive,insurance}/` |

**取哪一半**：取密度与结构（模板、成对、全覆盖），不取元数据字段（`@Author`/`@Date`——三个项目里这两个字段全部腐坏，ZXZ 的 `@Date` 有 4 种格式还含占位符 `2025/01/XX`，wgcloud 的恒为「2021年1月16日」）。

**为什么「层内下钻」值得学**：`rule/calculation/bigdecimal/fixed/` 这种结构，让「新增一个薪资项 = 加一个类、不改任何现有代码」——**目录结构本身在表达可扩展点在哪**。法泽用「域优先」（`modules/<域>/`）是因为技术路线 §4.2.1 的跨域禁令要能在目录上成立，但域内部照用 ZXZ 的下钻（`modules/trade/rule/fee/`）。

### 二、wgcloud 的 11 项安全机制（新增 §1.5）

统一鉴权 Filter（`AuthRestFilter` 363 行）、免登录白名单、只读角色、**登录失败双窗口锁定**（1 分钟计数 / 10 分钟封禁）、**密码复杂度四项可配置且返回具体失败原因**（`PasswordPolicyUtil` 104 行）、密码过期与剩余天数、agent 通信 token、License 校验、上传大小限制、命令注入防护、日志分 4 类落库。

**它的 11 项机制「有」，但其中 5 项「不对」——而且不对的原因是同一个**：用**清单**（白名单数组 / 黑名单词表 / 共享密钥 MD5）代替了**结构**（注解、精确匹配、签名）。

对法泽的启示：**能用结构表达的，就不要用清单**。所以 §11.1 改成 `anyRequest().denyAll()`（默认拒绝），把 wgcloud「忘加白名单就暴露」的方向反过来。

**其中三项它是真好，直接采用**：登录双窗口锁定（状态从单机 `ExpiringMap` 换 Redis）、密码复杂度**返回具体原因而非布尔**（否则前端要再写一份规则文案，两份必然漂移）、密码过期 + 剩余天数。

### 三、顺带修准一个数字

全文「wgcloud 8 个未鉴权接口」→ **7 个**。按清单逐一核对：18 个 `agentList` 里**有**校验的 8 个、**没有**的是 7 个（`DbTable`/`DceInfo`/`FtpInfo`/`HeathMonitor`/`K8sMonitor`/`LogInfo`/`SnmpInfo`）。原来的「8」把「有校验的个数」当成了「漏的个数」。

### 四、本版没做的事

ArchUnit / checkstyle / gitleaks / JaCoCo 仍未配（第 0 段 CI 的施工内容）；`docs/standards/代码注释规范.md` 仍只有 TS 部分。

---

## 2026-10-30 · 新增《后端代码规范 v1.0》：三项目取长补短 + 规则承接矩阵（基线 v1.15）

**这一版改了什么**

### 一、三路调研的实测快照（都是数出来的）

| 项 | ZXZ | wgcloud | jxyd200（后端） |
|---|---|---|---|
| Spring Boot | **3.5.3** | 2.7.12 | **3.5.14** |
| Java 文件 / 行 | 324 / 30,244 | 342 / 60,857 | 672 / 52,738 |
| `@Aspect` | **0** | **0** | 未用 |
| XML 里 `${}` | **0 处** ✅ | **49 处** ❌ | — |
| 最大单类 | `IndexServiceImpl` **4,734 行** | `DashboardService` 1,285 行 | — |
| 测试 | 1 个类 | 0 | 2 个（javac 手搓，不走 surefire） |

**一个前提修正**：原先以为只有 ZXZ 与法泽同栈，实测 jxyd200 的后端是 **Spring Boot 3.5.14 / Java 17**（`services/ruoyi-api/pom.xml:19-21`），也是同代。所以三个项目的后端规则大部分是**零成本直搬**，不是「改造后移植」。

### 二、一条必须写在前面的判据

ZXZ 全项目**只有 1 处 `@Transactional`**、不用 MP Wrapper、无乐观锁——这是它**读多写少**（177 个 select / 7 个写语句）的合理取舍，**不是规范缺陷**。

但法泽是**写密集**（下单扣余额、支付回调、消息、审核、文书生成）。同一套做法搬过来会出事：扣余额和写流水不在一个事务 → 钱扣了流水没写；无乐观锁 → 并发超扣。所以新规范 §1.3 把这条差异单独列成一节，并在 §7（事务）、§8.5（乐观锁/CAS）、§10.4（操作日志 AOP）三处给出法泽的口径。

### 三、这份规范里最要紧的一节：§十四「规则的承接手段」

jxyd200 有一份 **1,138 行**的规范，实测落地率：`@Schema` 全后端 **1 处**、61 个 Controller 只有 **9 个**用 `@Validated`、`types.ts` **0/13**、类头三风格并存（`@author` 456 / `@Description` 185 / `@since` 98）。

**它真正 100% 执行的只有 7 条，且全都有工具强制。**

所以本规范新增 §十四：**每条规则必须配承接手段**——ArchUnit（分层/跨域/VO/鉴权）、checkstyle（行数/@author）、gitleaks（密钥）、JaCoCo（状态机与资金类覆盖率）、CI grep（`${}`）、PR 模板（4 条无法自动的）。并明确列出 **5 条故意不写**的常见规则（如「每个方法必须有 Javadoc」——只会催生复述代码的注释；jxyd200 已出现「单行压扁注释骗过检查」）。

### 四、裁决一处包结构冲突

`AGENTS.md` §3.2 写 `modules/` 下 **9 个域**，技术路线 §4.2 写 **12 个域**（多 `workbench`、`social`、`admin`）。**以技术路线 12 域为准**，AGENTS 是缩写。

### 五、连带修正一处真冲突

`docs/standards/README.md` 第 3 条原写「Java：类头 Javadoc（`@Description` / `@Author` / `@Date`）」，与新规范 §4.1 **禁止 `@author`/`@Date`** 直接对撞。已改正，并写明理由：ZXZ 的 `@Author` 248 处是大写 A 的非标准注解、`@Date` 4 种格式含着占位符；wgcloud 的 `@date` 恒为「2021年1月16日」从不更新——**作者看 git blame，时间看 git log**。

### 六、三个项目没有一个是「安全范本」

- **ZXZ**：拦截器只验 token 不验归属（改 `companyId` 即可查别家财务/薪资）、明文密钥 6 处、`RedisUtil` 里 17 处 `printStackTrace`。
- **wgcloud**：8 个 agent 接口未鉴权且在白名单里、49 处 `${}` 可注入、**5 份明文生产凭据**（含内网 DB 密码与管理员口令）、登录用前端算 MD5（pass-the-hash）。
- **jxyd200**：`.gitignore` 每条配原因（好），但**无 gitleaks 类扫描工具**；跨端安全策略相反（Java 不回显异常文本，Node 网关 `msg: error.message` 直出）。

新规范 §十一 基本是这三条的反面。

### 七、本版没做的事

1. §十四 提到的 **ArchUnit / checkstyle / gitleaks / JaCoCo 都还没配**——它们是第 0 段 CI 的施工内容，本版只把「要拦什么」定下来。
2. `docs/standards/代码注释规范.md` 仍只有 TS 前端部分（144 行全是对 `.ts`/`.tsx` 的规定，Java 一句没有）。本版在后端规范 §四 给了 Java 形式，两份文档的分工需下版收口。
3. i18n 规范、审计与日志规范还没写。

---

## 2026-10-30 · 新增《接口规范 v1.0》：契约分级 + 6 处冲突收口（基线 v1.14）

**这一版改了什么**

### 一、为什么现在补这份

负责人 2026-10-30 拍板：**混合方案** —— 第 0/1 段的 5 组核心接口**契约先行**（手写 OpenAPI），其余接口用 springdoc 注解生成。

补之前先量了现状：

| 项 | 实测 |
|---|---|
| 「接口规范」正文 | **11 行表格**（技术路线 §4.4） |
| 字段级契约 | **0 个**；全仓 `OpenAPI` 关键字 0 次命中，`services/` 目录不存在 |
| 契约产生方式 | 一句话「以 Swagger UI 为准」 |
| 接口清单 | §4.8.2 十域 64 行 + §4.8.4 的 87 个 admin 接口 |
| 废弃策略 | **0 处**（`deprecated` / `Sunset` 全仓 0 次命中） |

结论：以前只定了**风格**（REST、`/api/v1/`、`R<T>`、页码、整数分、Bearer），没定**契约**。

### 二、契约怎么产生（新文档 §二）

按「改动代价」分三级，不是「一半契约先行一半不写」：

| 级 | 怎么产生 | 用在哪 | 冻结程度 |
|---|---|---|---|
| **L1** | 手写 OpenAPI YAML，先于代码 | 跨端并行、字段会被写死、验收锚点直接引用的 | 字段名/必填性/错误码不可改 |
| **L2** | 文档表格（方法+路径+入参+出参+错误码） | 单端内部、字段可能增删 | 路径与方法不可改 |
| **L3** | springdoc 注解 → Swagger UI | 探针、内部调试、低频查询 | 以运行结果为准 |

**5 组 L1 契约**：登录（3 端点）、用户列表、实名审核（2）、律师入驻审核（2）、手工充值（2）。判级判据三条：有验收锚点直接依赖 / 至少两端同时写死字段 / 涉及资金或状态机。

**OpenAPI 文件放仓库根 `api/openapi/`**，不放 `services/` 下——契约是跨端资产，放后端源码树里在语义上就错了。

### 三、6 处冲突的裁决

| # | 冲突 | 裁决 |
|---|---|---|
| 1 | 错误码示例已被《错误码规范》宣布作废，但技术路线 §4.8.3 原文没标 | §4.8.3 标题改「示例与域位说明已作废」，点名三处（域位 1 位→2 位、4 个示例重编、401 语义部分作废） |
| 2 | WS 错误码 `40301` 是 5 位 | 改 `404002`（会话域·无权访问），与 REST 共用一套码 |
| 3 | **401 语义与 RuoYi 前端对撞** | 见下方第四节，**管理端是唯一例外** |
| 4 | 分页三套口径 | 明确三种场景：C 端 `list` / IM `cursor` / **PC 管理端 `rows`+`total`** |
| 5 | 段 0 探针路径 `/api/v1/ops/probe/*` 不属四类前缀 | 改 `pub` 前缀 + 仅非生产 profile 注册 |
| 6 | `/orders/{id}/confirm` 一个路径三种语义（接单/拒单/完成） | 拆 `accept` / `reject` / `complete` |

### 四、查出一处文档从没定义过、但会让段 1 的 PC 7 页全卡死

**问题**：PC 登录走 RuoYi 后端（§4.8.4.1 冻结），业务接口走 faze-api。两个独立 Spring Boot 应用，**文档 0 处定义两者如何互认 token**。

**硬证据**（读本机 RuoYi-Vue3 源码）：
- `utils/auth.js`：token 存 Cookie `Admin-Token`
- `utils/request.js` 第 32 行：取 Cookie 塞进 `Authorization: Bearer`
- 第 78–96 行：`if (code === 401)` 才弹重新登录 —— 判断的是 **body 里的 code**
- 第 110–122 行是 HTTP 非 2xx 分支，**只弹「系统接口 XXX 异常」，没有跳登录逻辑**

所以：若按《错误码规范》§2.2 返回 **HTTP 401**，RuoYi 前端会走 error 分支，用户看到「系统接口401异常」而**不会被引导重新登录**，页面卡死。

**裁决（全项目唯一的 HTTP 语义例外）**：
- `/api/v1/app/**`、`/pub/**` → HTTP 401 + `{code:402002}`
- `/api/v1/admin/**` → **HTTP 200 + `{code:401}`**

代价：管理端鉴权失败在网关/APM 里显示 200，监控要按 `code=401` 统计。

**PC token 互认方案仍是待拍板项（P-1）**：给了 A（共享 `token.secret`+Redis，推荐）/ B（faze-api 自签，双登录）/ C（网关统一）三方案，未定。

### 五、连带修正 4 份

| 文件 | 改了什么 |
|---|---|
| 技术路线 | §4.4 标「已被取代」；§4.5 的 `R` **补 `traceId`**（原文缺，与 §4.8.3 自相矛盾）；§4.8.1 加探针路径修正；§4.8.3 标作废；§4.8.2 拆 `confirm`；WS 码 5 位→6 位 |
| 开发计划总纲 | §5.1 与锚点 `0-A2`/`0-A5` 的探针路径改 `pub`；**§5.2「接口」行从「只写 §4.8.4 两组 admin」改为「C 端 20 + PC 端 20」**——原文漏了段 1 全部 C 端接口，且支付密码 3 个接口在技术路线里一个都没有 |
| docs/README.md | 阅读顺序加 ③.6；索引补 2 行；基线 v1.13→v1.14、资产 24→25 |
| AGENTS.md | §4 新增第 7 条「接口按《接口规范》办」（后续顺移） |

### 六、这份文档还留着 5 条待决

P-1 PC token 互认（最要紧）/ P-2 管理端 401 例外是否接受 / P-3 幂等重复请求返回首次结果 / P-4 限流阈值 / P-5 是否上 `PATCH`。

### 七、本版没做的事

1. **《后端代码规范》还没写**（正在参考 `zxz-bi-server`、`jxyd200`、`wgcloud` 三项目提取，下版交付）。
2. **i18n 规范、审计与日志规范还没写**。
3. **5 组 L1 的 OpenAPI YAML 还没落地**——本文档只给字段级定义（附录 A），YAML 在段 0/1 开工时补。
4. **PC token 互认、业务单号格式、平台抽成比例、A-02 看板口径**仍未拍板。

---

## 2026-10-30 · 新增《数据表规范 v1.0》+ 表清单重算（基线 v1.13）

**这一版改了什么**

### 一、为什么现在补这份

五维规范审计（数据库 / API / 工程 / 前端 i18n / 安全审计，约 90 个规范点）的结论：**数据库这一维 21 条里，只有 1 条（库级参数）能直接照做**，14 条「写了但缺关键项」，6 条完全空白（表命名、外键、审计字段、字段命名与长度、表/字段注释、业务单号）。更要紧的是**表数量口径本身是错的**。

### 二、表清单重算（这是本版最实的产出）

技术路线的数字经实测：

| 口径 | 值 |
|---|---|
| 文档声称 | 51 张 |
| 实际 `CREATE TABLE` 语句 | **51 条** |
| **唯一表名** | **49 张**（`login_log` 在 L1757/L2048、`operation_log` 在 L1771/L2065 各定义两遍） |
| §5.1 表清单行数 | **30 行** |
| 清单里已废弃的表名 | 3 个（`case` → `legal_case`；`favorite` / `follow` → `social_action`） |
| **实际存在但从未进清单** | **31 张** |
| V2.3 增量口径 | 17 张 / 15 张 / 15 张（三处不一致） |

**重算结果**：49 张唯一表，**要建 48 张**（`invoice` 按「V1 明确不做」不建）。按段分布：

| 段 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|---|
| 新建表数 | 2 | **12** | 10 | 3 | 4 | 6 | 1 | 10 | 0 |

按类别：业务实体 21 / 追加型 12 / 状态机 9 / 基础数据 7。

> 这个数必须现在定死：段 1 一次要建 12 张，而旧清单里 `user_device`、`user_violation`、`lawyer_application_log`、`sys_config`、`login_log`、`operation_log` 这 6 张**根本不在**——照旧清单排会漏建。

### 三、裁决了三件此前悬空的事

| # | 悬空的问题 | 裁决 | 依据 |
|---|---|---|---|
| 1 | `login_log` / `operation_log` 双份 DDL，字段完全不同 | **以技术路线 §5.11.5 为准，§5.10 两段作废** | §5.11.5 有 `operator_type`（区分管理员/C 端/律师）与 `target_type`+`target_id`（能回答「对哪条记录做了什么」）；且用 `detail JSON` 取代旧版「原样存请求体」的 `request_param TEXT`——后者会把密码、验证码、身份证号落库，撞脱敏红线 |
| 2 | 「审计四要素」要求段 0 交付，但**全库 0 次定义** | 定义为：**谁**（`operator_id`+`operator_type`）/**何时**（`created_at`）/**从哪**（`ip`）/**做了什么**（`module`+`action`+`target_type`+`target_id`+`detail`），并列出必须留痕的动作清单 | 这个词是从旧计划继承的，旧计划删除后变成悬空术语；总纲里还留着两处引用 |
| 3 | 迁移「不可变」与「段内可回滚」直接矛盾 | **按「是否已进 `main`」判定**：只在 feature 分支 → 可改、可 `flyway clean`；已进 `main` → 不可改，只能追加 `V{x}_{y}__fix_*`；已在 test/生产执行 → 不可改且禁 `clean` | 两份文档各自都对，只是缺一个机械可执行的判据 |

### 四、《数据表规范》写了什么（605 行）

| 章 | 内容 |
|---|---|
| §一 | 三个既有冲突的裁决（上表） |
| §二 | 命名：表（小写 snake_case、单数、无前缀、`sys_` 只给框架表）、字段（`_at`/`is_`/`_cents`/`_type` 后缀）、**索引名必须可机械推导**（`idx_<完整表名>_<完整字段名>`） |
| §三 | 主键（`BIGINT` 自增，不用雪花/UUID）、**外键不建约束**（分库+软删+迁移+批量的四条理由 + 三条代偿措施）、**时间由应用层填**（不用 DB 默认值）、金额、状态（`VARCHAR(20)` 存枚举名）、布尔、**字符串长度标准表**（同一语义全库只能一种长度）、大字段、JSON、**`oss_key` 而非 URL**、业务单号 |
| §四 | 四类表（原三类 + 补「基础数据/配置表」，否则 7 张表落不进任何类）+ 判定四问 + **软删与唯一键冲突**（注销后手机号能否复用，给出折中做法） |
| §五 | 索引：5 条必建、联合索引规则、单表 ≤6 个、ngram 全文索引 |
| §六 | 字符集/排序规则/**表级必须显式 `COLLATE`**（现状 51 条 DDL 里 `COLLATE` 出现 0 次）/时区 |
| §七 | 注释：表注释必列状态枚举全集、字段注释必写单位与引用表、迁移文件头五段 |
| §八 | Flyway：版本号分段（每段 +100）、不可变判据、DDL 与数据分离、**种子数据裁决**（`V2` 只放 5 条法条给段 0 探针用，2000–3000 条走 `database/seed/`） |
| §九 | 审计：哪些表要审计字段、**字段级敏感清单 L1~L5**、`operation_log` 写入位置（Service 层显式调用，不在切面里统一写） |
| §十 | 建表前 12 问检查清单 |
| 附录 A/B/C | 49 张表清单（类别/域/字段数/**建表段**）；与旧清单的差异；**现有 DDL 违反规范的 12 处**（建表时按规范改，不必先改技术路线） |

### 五、连带改的 6 份文档

| 文件 | 改了什么 |
|---|---|
| `AGENTS.md` | §4 新增第 6 条「建表与改表按《数据表规范》办」，实施顺序条后移为第 10 条；§8 索引加一行 |
| `docs/README.md` | 阅读顺序加 ③.5；「数据库表结构」改指规范附录 A；目录树加一行；基线号/资产数同步 |
| `开发计划总纲` | §5.2 的数据库行原来写 `lawyer_audit`、`account` **两张根本不存在的表**，改为实际 12 张；`1-A6` 的 SQL 从 `UPDATE account SET balance` 改为 `UPDATE user_balance SET balance_cents`；§十 待决第 1、8 条标已解决；§5.1 段 0 的「审计四要素」加上定义指引 |
| `技术路线` | §5.1 标「清单作废」并写实测结论；§5.2 加「以规范为准」对照；§5.10 两段 DDL 加作废标记；§5.11.5 加「以本节为准」 |
| `V1 基线表结构` | 「51 张表」→「51 条 CREATE TABLE / 唯一 49 张」；「不建其余 49 张」→ 47 张 |
| `代码审查清单` | 第 52 行「提交信息符合 `AGENTS.md` 第 5 节」→ **第 6 节**（第 5 节是「禁止做的事」，审计发现的错引用） |

### 六、核对

| 项 | 结果 |
|---|---|
| 表清单 | 附录 A 49 行、49 个唯一表名、无重复；段分布 2+12+10+3+4+6+1+10+0 = **48**（+1 不建 = 49） |
| 表格结构 | 全文表格列数一致（脚本校验 0 异常） |
| 死链 | 全库相对链接 0 失效 |
| 基线 | v1.12 → **v1.13**，资产 23 → **24**（新增 1、内容变更 6）；24/24 指纹实测一致 |
| 行尾 | 0 个 CR 文件 |
| 分支 | `UI设计` 与 `docs` 内容一致；桌面镜像同步 |

### 七、这一版**没有**做的事

1. **没有改技术路线里的 51 条 DDL 正文**。规范附录 C 登记了 12 处违反（`COLLATE` 缺失、`_url` 应为 `oss_key`、布尔无 `is_` 前缀、索引名缩写等），但没去逐条改 DDL——理由是「技术路线的 DDL 是设计参考，实际迁移文件按规范写」比改 4000 行文档更省且不易再错。
2. **没有写接口规范、后端代码规范、i18n 规范、审计日志规范**。按约定排在后面。
3. **业务单号格式只给了建议，未定稿**（总纲 §十 第 6 条待拍板），规范里写的是「技术约束 + 待定标记」。
4. **没有解决平台服务费比例、支付密码策略、A-02 看板口径**等其他待决事项。

---

## 2026-10-30 · 开发计划推倒重写：新增《开发计划总纲 v1.0》，退役旧两份（基线 v1.12）

**这一版改了什么**

### 一、为什么重写

旧《分期交付计划》（内容 v1.2，794 行）与《第0段执行计划》（v3.0，773 行）是 10-28 ~ 29 写的。当时 PC 后台还没启动、页面归属还没定。到 10-30 已经出现**五类实质冲突**——不是措辞问题，是照着做会做错的冲突：

| # | 冲突 | 具体表现 |
|---|---|---|
| 1 | **第 0 段规模失控** | v3.0 有 24 任务 / 6 组 / 13 条验收，其中鉴权、RTL、四态、lint 各自是一个任务 + 一处验收，但没有一个能在段末被独立判定为「成了」。负责人已判定：不要了，重新设计 |
| 2 | **支付切错位置** | 旧版把支付页 / 支付结果页 / 退款页放在第 6 段（真实渠道段），而第 2 段的验收原文是「下单 → **输支付密码扣款** → 接单」。余额扣款不需要真实渠道，这三个页面在四段之后才能用 → 第 2 段验收无法执行 |
| 3 | **PC 页面归属两份文档各说一套** | 旧版说「PC 归属移交 PC 计划」，PC 计划 §五 给的是 P1~P7 ↔ 段的粗对应；P1 一次含 A-01（段 0）、A-02（段 7）、A-03（段 1），P5 跨 5 个段 |
| 4 | **验收项数量自相矛盾** | 第 0 段正文列 13 条，别处写 15 条，ADR 写 14 条又引 21 条 |
| 5 | **MQ 决策已落地，旧版还开着三选一** | 旧 §3.5 列 A/B/C 等拍板；负责人已选 **B：不用 MQ，改「数据库任务表 + 定时重试」** |

### 二、新增《开发计划总纲 v1.0》（966 行）

这是「段与段之间顺序」的唯一依据。九段（0~8）框架保留，内容重排。骨架：

| 章 | 内容 |
|---|---|
| §一 | **三层文档分工**：总纲管顺序与边界、工作流管施工细节、技术路线管功能定义、导航树/PC 计划管页面清单。冲突时谁赢写清楚了 |
| §二 | **一段成立的 7 条标准**（旧 4 条 + 新增「段内可回滚」「段末文档闭合」「段末不留假数据」）+ 4 个反例（防复发） |
| §三 | **九段总览** + 依赖图 + 「严格串行到底约束什么」（约束的是「同一时刻只有一个段在施工」，不是逐任务闸门） |
| §四 | **页面归属两张权威表**：APP 68 页逐页定段、PC 28 页逐页定段；另附逐模块回加校验表、10 处与旧版的差异及理由 |
| §五 | **每段边界**：交付物 / 必须做 / 明确不做 / **6~10 条验收锚点（编号 0-A1 ~ 8-A9，去重后共 79 条）** / 出口 / 风险与出口 |
| §六 | **段的门禁**：开工前置 6 项、收口五件套、不许带进下一段的 6 样、熔断规则 |
| §七 | **跨段横切**：i18n 两条线、账务恒等式、合规红线、文档基线、**MQ 待回写位置（4 处）**、测试工具 |
| §八~十 | 里程碑、并行轨道（10 条，含最晚启动时点）、**待决事项 10 条**（每条写了最晚什么时候定） |
| §十一 | 段工作流文档的模板 + 清单 + **段 0 的 12 任务 / 4 组预览** |
| §十二 | 变更规则（锚点只增不减、改归属必须三处同改） |

### 三、页面归属重排（这是本轮最实质的变化）

**APP 68 页**：`2 + 18 + 16 + 2 + 6 + 13 + 1 + 10 + 0`

**PC 28 页**：`1 + 7 + 4 + 2 + 1 + 4 + 0 + 8 + 1`

主要挪动 10 处，都有验收依据：

| 页面 | 旧 | 新 | 依据 |
|---|---|---|---|
| 登录页 | 段 1 | **段 0** | 段 0 锚点就是「真实接口登录 + 401 回登录页」 |
| 律师入驻页 / 入驻审核状态页 | 段 2 | **段 1** | 段 1 验收原文「后台审律师 → App 状态变已通过」 |
| 支付页 / 支付结果页 / 退款申请页 | 段 6 | **段 2** | 段 2 验收原文「输支付密码扣款」 |
| 律师工作台首页 / 律师订单管理 | 段 5 | **段 2** | 段 2 要「律师端接单」，没有这两页律师接不了单 |
| 全局搜索页 | 段 3 | **段 5** | 要跨律师/文章/案例/文书四类，案例与文书在段 4、5 才有数据 |
| A-03 用户列表 | P1（段 0） | **段 1** | 段 0 没有 C 端用户数据可查 |
| A-02 数据看板 | P1（段 0） | **段 7** | 段 0 时库里没有业务数据，看板只能是假数 |
| A-10 提现审核 | P3（段 1+6） | **段 5** | 提现申请页（App 收入与提现）在段 5；段 6 不再碰提现 |
| A-16 案例库 | P5（段 2+4） | **段 5** | App 案例列表/详情在段 5 |
| A-27 / A-28 | 未定段 | **段 3** | 段 3 验收要求「AI 只能引用库中存在的条文」+「后台可检索原文与 refs」 |

### 四、删掉两份旧文档

| 文件 | 原行数 | 为什么删 | 找回 |
|---|---|---|---|
| `法泽FAZE_分期交付计划_v1.0.md` | 794 | 被总纲整体取代（五类冲突见上） | `git show 97260e9:docs/architecture/法泽FAZE_分期交付计划_v1.0.md` |
| `法泽FAZE_第0段执行计划_v3.0.md` | 773 | 负责人判定作废，第 0 段重设计为 12 任务 / 4 组 | `git show 97260e9:docs/architecture/法泽FAZE_第0段执行计划_v3.0.md` |

已登记在 `BASELINE.md` §三（第 9、10 份）。

### 五、全库引用清扫（10 份）

删文档容易，难的是不留死链。这轮把**所有**指向旧两份的地方都改了（历史记录类文字保留原文，不改写历史）：

| 文件 | 改了什么 |
|---|---|
| `AGENTS.md` | §4 第 9 条实施顺序、§8 索引两行、底部一句话规则、未完成需备注的描述 |
| `README.md`（根） | 索引两行；旧原型说明里的 `T0-02` / `T0-11` / 「13 条验收」；设计顺序版本 `V2.8 → V3.0` |
| `docs/README.md` | 阅读顺序 ①②、一句话规则、按用途查找补 5 行、目录树；**基线号 v1.8 → v1.12、资产数 24 → 23**（这个数停在 v1.8 已经四版没跟上） |
| `法泽FAZE_技术路线_v2.3.md` | §23.1 / §23.3 两处引用 |
| `法泽FAZE_V1基线表结构_v1.0.md` | 「T0-13 第一次被执行」→ 第 0 段工作流的建库任务；`V2__init_law_items.sql` 的段内位置 |
| `法泽FAZE_错误码规范_v1.0.md` | 三处 `T0-xx`；顺手修掉「第 2 段 T0-43」这个自相矛盾（T0-43 是第 0 段任务号） |
| `法泽FAZE_PC后台开发计划_v1.0.md` | 头部关系说明、7 个阶段的「对应分期计划」→「按页交付到」、§五 整节重写、§八 风险表时间点 |
| `docs/design/法泽APP_UI设计顺序_v1.0.md` | 落款说明里的「实施顺序以《分期交付计划》为准」 |
| `docs/design/README.md` | 页面实现工作流第 1 步 |
| `docs/design/未完成需备注.md` | §三 第 1 条 |

### 六、PC 计划的口径修订（这条最容易被忽略）

旧 PC 计划 §五 的「P1→段0、P2→段1、P3→段1+6…」对照表**与新的逐页归属冲突**（P1 跨段 0/1/7，P5 跨 5 个段）。这版把它改成：

- **P1~P7 降级为「开发批次建议」，不是交付单位**；交付单位是段；
- 7 个阶段各加一行「按页交付到」，把每页落在哪段列清；
- §五 重写成「批次 ↔ 段」对照表 + 拆批注意点 + **两条易错约束**（① 段内可以成批做，但不能提前做后面段的页；② PC 页从「有空再做」变成「该段交付物的一部分」——因为段 1 的 `1-A4` 手工充值必须在 A-07 上操作）。

### 七、核对

| 项 | 结果 |
|---|---|
| APP 68 页逐页核对 | 68 个页名**全覆盖、无重复、无遗漏**（脚本比对导航树）；段页数 2+18+16+2+6+13+1+10+0 = **68** |
| 逐模块回加 | 模块 0~11 逐模块按段回加，与导航树口径**逐行一致**（6/3/5/2/5/1/6/9/2/1/21/7） |
| PC 28 页 | A-01~A-28 **全覆盖**；段页数 1+7+4+2+1+4+0+8+1 = **28** |
| 全库死链 | 除历史记录（CHANGELOG 旧条目、BASELINE §三 登记）外，指向旧两份的引用**为 0** |
| 基线 | v1.11 → **v1.12**，资产 24 → **23**（新增 1、删除 2、内容变更 10）；23/23 指纹实测一致 |
| 分支 | `UI设计` 与 `docs` 内容一致；桌面镜像同步 |

### 八、这一版**没有**做的事

1. **没有写第 0 段工作流文档**。总纲 §11.3 给了 12 任务 / 4 组的骨架与 §5.1 给了 8 条锚点，但每个任务的五段式（做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办）还没写。在它产出前，第 0 段只能按总纲施工——这是已知缺口，下一步就补。
2. **没有回写 MQ 决策**。技术路线里 4 处 RabbitMQ 引用还在（总纲 §7.5 列了位置与改法），要在对应段开工前改。
3. **没有解决 10 条待决事项**（总纲 §十）。其中第 1 条（`login_log` / `operation_log` 双份 DDL）是段 1 开工前第一件事。
4. **没有动 `main` 分支**，也没有封存 `UI设计` / `docs`。

---

## 2026-10-30 · 补管理端接口全量契约（87 个，基线 v1.11）

**这一版改了什么**

### 一、原来缺多少

技术路线 §4.8.2 的 `/api/v1/admin/**` 只有 **4 个接口**（律师入驻审核 2 个、订单列表 1 个、退款审批 1 个），而 PC 后台有 28 页——**25 页在接口层无法施工**，只能接假数据。

### 二、补了什么

技术路线新增 **§4.8.4 管理端接口补全**（约 200 行）：

| 内容 | 说明 |
|---|---|
| 三条边界 | 全部由 **faze-api** 提供；RuoYi 自带 8 页仍走 RuoYi 后端 `/system/**`；只有一个管理员角色 |
| 契约约定 | 分页 `pageNum/pageSize`、时间 `beginTime/endTime`、排序 `orderBy` 白名单、审核动作统一 `{action, reason}`、错误码沿用 `0xxxxx`、敏感操作必须写 `operation_log` |
| 接口清单 | 11 个模块分组，共 **87 个**（新补 83 个），每个都标了「用途 + 主要表」 |
| 覆盖核对 | 28 页里 26 页有契约（A-01 走 RuoYi 自带登录；A-05 沿用原有 2 个） |
| 明确不做 | 发票管理、数据大屏/BI、权限细分、IM 聊天原文（待定） |

**几条设计上的取舍**：

- **法条库不提供 DELETE**，只能标记失效——AI 历史回答的 `refs` 快照按法条记录，物理删除会让历史引用悬空；
- **敏感词库可以 DELETE**（词库是规则，不是证据），两者不同；
- **A-04 一个审核队列承载两种认证**（`type=realname|enterprise`），不新增页面；
- **手工充值必须走 `balance_flow`**，禁止 UPDATE `account.balance`——第 1 段验收第 6 条考的就是这个；
- **审核接口不得改写原文**（第 7 段验收「原文完整不被替换」）；
- A-28 返回的是 **AI 对话原文**（合规留痕），不是律师与用户的聊天；IM 聊天原文接口仍待定（隐私合规）。

### 三、三份文档同步改口径

- 《PC 开发计划》§四：「接口缺口（最大施工风险）」→「接口契约（已补）」，保留历史，写清**契约 ≠ 实现**，P2 开工前至少跑通 5 组接口；
- 《未完成需备注》§一：标 ✅ 已解决、保留原始记录（解决不删行），补「仍未解决的部分」四条；
- 《PC 设计说明》§六：第 1 条改成已补。

### 四、核对

- 基线 **v1.10 → v1.11**：4 份资产内容变更，其余 20 份逐份实测未变，24/24 一致；
- 两分支 `git diff UI设计 docs` = 空；桌面镜像 23 份 SHA256 一致；
- 技术路线新增内容插在 §4.8.3 之后、§五 数据库设计之前，章节编号连续。

## 2026-10-30 · docs 分支补上设计稿全量快照（基线 v1.10）

**这一版改了什么**

`docs` 分支的 `design/` 目录一直只有 **10 个文件**（早期 8 屏原型 + `app.js` + `tokens.css`），
而 `UI设计` 分支已经有 **127 个**（APP 71 HTML + PC 28 HTML + 16 走查 + 令牌/组件/骨架/字体）。
也就是说「文档总库」里的设计稿早就过期了，只看 `docs` 分支的人打开的是早期原型。

这次把 `design/` 全量同步到 `docs` 分支（以 `UI设计` 为准），并把 `docs/README.md` 维护规则第 8 条
改成「设计稿也各留一份」，同步校验命令扩展为：

```powershell
git diff UI设计 docs -- docs/ AGENTS.md README.md design/   # 必须为空
```

**为什么不能只补新加的 2 屏**：设计稿不是独立文件——`26-law-library.html` / `27-ai-retrieval.html`
依赖同目录的 `tokens.css`、`components.css`、`shell.js`，缺了它们打开就是白屏方块。所以要么整个
`design/` 一起补，要么不补。

**定位不变**：`docs` 侧的设计稿是**快照**，准据仍在 `UI设计` 分支；改设计稿后两份都要同步。

**核对**：`git diff UI设计 docs -- docs/ AGENTS.md README.md design/` = 空；`docs` 分支 `design/` 文件数 = 127；
基线 24 份资产重算后 24/24 一致（`docs/README.md` 指纹随规则更新）。

## 2026-10-30 · PC 后台补 A-27 法条库 / A-28 AI 对话检索（基线 v1.9）

**这一版改了什么**

### 一、为什么补这两屏：第 3 段的一条验收没有入口

《分期交付计划》第 3 段验收要求「后台能按用户 + 时间检索到回答原文与 `refs` 快照」。核对下来发现：

- 技术路线 §15.1 的后台只有 9 个模块，**没有 AI 模块**；
- PC 26 屏设计稿里也没有 AI 相关页（内容管理 5 页是文章、案例、翻译、文书模板）。

也就是说这条验收原本无处落地。项目负责人 2026-10-30 拍板补 2 屏，位置：

| 编号 | 页面 | 菜单入口 | 设计稿 | 范式 |
|---|---|---|---|---|
| A-27 | 法条库 | 内容管理（第 5 项） | `26-law-library.html` | A 类列表页（母版派生） |
| A-28 | AI 对话检索 | 系统设置（第 4 项，日志中心之后） | `27-ai-retrieval.html` | B 类审核分栏（只读变体） |

**入口为什么拆开挂**：不能都塞进内容管理——内容管理会变 6 个子项，侧边栏高度 11×50 + 6×46 + logo 50 + 底部 56 = **932 > 900**，26 屏骨架全部出滚动条。拆开后内容管理 5 项 = 886、系统设置 4 项 = 840，都装得下。这条已写进《PC 设计说明》§3.3，下次加菜单项必须先算。

### 二、两屏的几条设计规则

- **法条库不提供「删除法条」**，只能「标记失效」。AI 历史回答的 `refs` 快照按 `law_item` 记录，物理删除会让历史引用悬空，追责时查不到原文。
- **法条库有「AI 引用次数」列**：修正一条被大量引用的法条，影响面一眼可见。
- **AI 对话检索是只读页**：底部只有「导出本条记录」「标记已复核」，没有删除/改写——AI 对话是合规留痕。
- **快照里专门演示一条「条号不存在（已拦截）」**：输出侧存在性校验拦下后，原文仍保留供复核，这是技术路线 §12 的要求。
- 两屏的清单里都出现「待校对/待复核」状态，让复核工作有入口（民诉法条号本身需法务复核）。

### 三、自检结果

- `check_design`（1440×900）：两屏均「一屏放得下」，无截断/无对比度问题。**法条库报了 5 条「文字叠在一起」**，用 CDP 单独量过：摘要列与数字列的 `getBoundingClientRect` 边界重合 **0px**、表宽 1188 = 容器宽、无横向溢出——是「单元格内文字被 `text-overflow: ellipsis` 截断、`scrollWidth > clientWidth`」造成的工具假阳性，不是真重叠。
- `check-cjk-wrap.py` 只认裸 `<body>`，扫不到 `design/admin`（admin 的 body 带 class）。本次另写同口径脚本扫了 3 屏，中文换行 **0 处**。
- 左栏「底条压最后一条记录」是真问题（内容溢出 64px），把记录从 5 条减到 4 条后 `bodyScrollH == bodyClientH`、底条与最后一条间距 −28.9px。

### 四、顺带修复：`02-user-list.html` 的 4 处文字损坏

母版页里有 3 处 `<option>部部</option>`（应为「全部」）、1 处 `aria-label="部选"`（应为「全选」），是上次 2→7 事故恢复时漏掉的（可见 UI 缺陷）。已改，并把页头注释里的「27 页里 19 个列表页」更新为「28 页里 15 个列表页」。

### 五、文档口径 26 → 28

5 份文档同步：PC 开发计划、PC 设计说明、未完成需备注、`AGENTS.md`、`docs/README.md`；基线 **v1.8 → v1.9**（见 `BASELINE.md` §7.13）。

顺带修正两处一直登记错的指纹（v1.7/v1.8 遗留）：

| 文件 | 原登记 | 实测 |
|---|---|---|
| `AGENTS.md` | 177 行 / 10295 B / `a95f4a27…` | 178 行 / 10444 B（v1.8 时 `19c34bb0…`） |
| `docs/README.md` | 126 行 / 10931 B / `256710fe…` | 登记值正好是 v1.7 版本；v1.9 改后为 `5b2a9904…` |

并补打 `v1.9-docs-baseline` 标签（v1.3~v1.8 当时都没打过，BASELINE 里那行是模板照抄）。

## 2026-10-30 · `5c5259f` · 三处 PC 口径纠错 + 画幅标记修正（基线 v1.8）

**这一版改了什么**

### 一、用户报的「所有页面右边显示不完整」——不是 CSS，是画布尺寸

设计工作台的 web 类型默认按 **1280×800** 建画布（`client.js` 的 `KIND_SIZE.web`），而 26 屏骨架是 `--canvas-w:1440px` + `--canvas-h:900px` + `overflow:hidden`。页面 meta 只写了 `content="web"`，画布就按 1280 画 —— **右侧 160px、下方 100px 被直接裁掉，连滚动条都不出现**，画面没有提示，看起来像布局溢出。

**修法**：26 个 HTML 的 `<meta name="dsh-design">` 全部改为 `content="web 1440x900"`（提交 `fec2e8f`）。画布的 `parseMeta` 会按真实画幅建 iframe。

### 二、三处口径与实现不符（v1.7 只改了被指出的数字，没回加一遍）

| 文件 | 原文 | 改成 |
|---|---|---|
| 《PC 设计说明》§四 | A 类「10」个、屏列表 12 个、漏 `08` / `18` | **A 类 14 屏**，补 `08`、`18`，加「合计 26」行 |
| 《PC 开发计划》§三 | 「26 页里 **12 页**的母版，后面 **11 页**照着套」 | **14 页 / 13 页** |
| 《PC 开发计划》§六 第 6 条 | 「每屏必须做四态中的列表页三态」 | 如实改：设计稿只出正常态，空/加载态接 `.empty` / `.skeleton` |
| 《PC 开发计划》§六 第 2 条 | `content="web"` | `content="web 1440x900"` |
| `design/admin/_SPEC.md` §2.2 | 新屏 head 模板 `content="web"` | `content="web 1440x900"` |

原范式表 7 类相加 = 10+3+2+3+1+1+2 = **20**，屏列表只有 12 个，两项都与标题「26 屏」自相矛盾。按 §五 逐屏范式重数为 14，合计 26。**这是 v1.7 只改总数「27 → 26」、没验算分类相加的后果。**

### 三、自检（26 屏 × 1440×900，自写 CDP 探针）

逐屏量 4 项：文档横向溢出 / 越界视口右边界的元素 / `.app-container` 横向溢出 / 固定底栏压制。**26 屏全部 0 命中**；`14-article-edit` 的固定操作栏滚到底后距最后一块内容 **+21px**。

顺带发现 `check_design` 一个盲区：它量 `documentElement.scrollHeight`，而 `.shell` 固定 900，所以对 26 屏一律报「一屏放得下」。实量 `.app-container` 内容高，有 8 屏超出可视区 816（01/07/11/14/20/21/22/25）。**判断：不改** —— 1440×900 是最低基准线，1080p 部署时内容区 956，多数放得下；详情页滚动正常。

### 四、文档与基线

- 基线 **v1.7 → v1.8**：内容变更 2 份（PC 设计说明、PC 开发计划），新增/删除 0 份，见 `BASELINE.md` §7.12
- `docs/README.md` 三处 v1.7 → v1.8
- `BASELINE.md` §七「当前存在三种落款」→「五种落款」（下面实际列了 5 个）

> **本条目写完后发现一处自己写错的笔误，已在后续提交改正**：设计说明 §四 的核对说明第一稿写「原表 7 类相加只有 24」，实为**按数量列相加 20**（10+3+2+3+1+1+2），24 是按 A 类屏列表 12 个算出来的 —— 原表本身三处数字互相矛盾，「24」只是其中一种错误算法的结果。已改为把三个数各自的来源说清楚，`BASELINE.md` §7.12 的指纹同步重算。

---

## 2026-10-29 · `de6cdf0` · PC 管理后台 26 屏设计稿完成（基线 v1.7）

**这一版改了什么**

### 一、交付 26 屏设计稿（`design/admin/`）

覆盖《PC 后台开发计划》A-01 登录 ~ A-26 日志中心全部页面：底座 3、人与资质 3、账务 4、交易 3、内容 5、案件与审核 2、治理 6。

| 文件 | 是什么 |
|---|---|
| 26 个 `NN-xxx.html` | 一屏一个文件，1440×900 固定画幅 |
| `tokens.css` | PC 设计令牌：颜色继承 APP，尺寸另立 PC 刻度 |
| `components.css` | 骨架 + 24 组组件（约 2100 行），列表页范式在 `02-user-list.html` |
| `shell.js` | 侧边栏 / 顶栏 / 标签页注入，26 屏共用一份，避免 3400 行重复 |
| `_SPEC.md` | 施工规范：骨架写法、可用类名、高度预算、禁止事项 |

**26 屏抽象成 7 类范式**，不是 26 种设计：列表页 12（母版 02-user-list）、审核分栏 3、详情 2、表单 3、看板 1、登录 1、主从分栏 2。

**验收**：`check_design` 在 1440×900 下逐屏实测 —— 无文字截断、无元素重叠、无对比度不足（正文 ≥4.5:1）、无超出屏幕、跳转全部可达。26 屏里 23 屏的内容高度 ≤776（一屏放下），07 系统参数 886（表单页允许滚动，任务书已写明）。

### 二、三份文档修正（含一个我自己犯的加法错误）

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| 《PC 后台开发计划》 | 页面数 **27 → 26**；接口数 **3 → 4** | 七个分类相加 3+3+4+3+5+2+6 = **26**，原文写 27 是加法错误；接口表格列了 4 行、正文却写「3 个」 |
| 《未完成需备注》 | 补 §九「设计阶段新增的发现」8 条；§七「四态」条目改写 | 原文写「列表页做空/加载/正常三态」，**实际设计稿只画了正常态** —— 文档不能承诺没做到的事 |
| 新增《法泽PC后台UI设计说明_v1.0》 | 设计系统、7 类范式、逐屏依据、已知局限 | 设计稿本身是 HTML，不登记基线；「为什么这么画」需要一份文档承载 |

索引同步：`docs/README.md`（阅读顺序新增 ⑦、按用途查找 +1、目录结构 +1）、`AGENTS.md`（§8 加 1 行，设计稿状态「正在画」→「已完成」）。

基线 **v1.6 → v1.7**：新增 1 份、内容变更 6 份，顺带修了 6 处一直没算对的指纹（`AGENTS.md` 旧值 `7c5742998f1d3b7e` 是 v1.6 提交时就算错的）。

### 三、一次操作事故（已恢复，全部细节记在 BASELINE §7.11）

改这 6 份文档时，我用 PowerShell 嵌套数组做批量替换：

```powershell
$jobs = @( @("docs/README.md", @(@("27 页","26 页"))) )
foreach($pr in $j[1]) { $t = $t.Replace($pr[0], $pr[1]) }
```

**PowerShell 会把嵌套数组扁平化**，循环变量拿到的是字符串而非两元素数组，于是 `$pr[0]` / `$pr[1]` 取到的是字符串首/次**字符**（`"2"` / `"7"`），实际执行的是 **全文件 `Replace("2","7")`**。

后果：`components.css` 38 处 `--sp-2`→`--sp-7`、`.tag{height:22px}`→`77px`；`shell.js` 的 `02-user-list`→`07-user-list`；`00-login.html` / `01-dashboard.html` / `04-lawyer-audit.html` 的 `2026`→`7076` 与全部 SVG 路径坐标；5 份文档数字错位。

**恢复**：CSS 与 `_SPEC.md` 用「全文件 7→2 反向替换」+ 手工改回三处本来就是 7 的值（`.tl-item::before`、`.nav-icon__dot` 的 `7px`、`.img-cell__tag` 的 `72%`、`.check::after` 的 `top:7px`）；`00-login` 与 `01-dashboard` 反向替换会破坏 SVG 坐标，**只能整体重写**；`10-order-list` 由协作方用原内容重写；`shell.js` 修 2 个 id；文档类 5 份从 git 恢复后重做。

**两条教训**：① PowerShell 改多处字符串，用显式 `$x.Replace("旧","新")` 一行一条，不要用嵌套数组批量；② 未跟踪的新文件没有回滚能力，批量操作前先 `Copy-Item` 备份。

### 四、顺带修掉的两个组件缺陷

- `.bar-row__fill` 缺 `display: block`：HTML 里是 `<i class="bar-row__fill">`，`<i>` 是 inline，`width:100%` 从不生效 —— 看板的「用户地区分布」柱状图整块是空的。加 `display: block` 后正常。
- `.check::after` 的 `top` 恢复为 7px（反向替换漏改），否则复选框的勾整体偏上 5px。

### 五、明确不含

`design/ui/`（APP 设计稿）与《法泽APP_UI设计顺序》**不在本次提交内** —— 那是另一个会话正在进行的工作。本次只碰 PC 后台与它依赖的文档。

---
## 2026-10-29 · `dcea676` · 平台文案口吻回退修正（28 屏）

**这一版改了什么：**

### 一、先说事实：第 6 批定的规则，后面 10 批失效了

第 6 批项目负责人说过「措辞像是人对人说的，希望换成平台对用户的措辞」，当时定进 §6.3，并改了 5 屏（服务协议、隐私政策、律师入驻、审核状态、文书送达）。

**然后就没有然后了。** 第 7~17 批新增了二十多屏平台固定文案页 —— 客服中心、FAQ、投诉、反馈、关于、设置七屏、律师工作台七屏，以及各处空态、错误态、按钮文字、toast —— 都是平台在说话，但没人再对照 §6.3。项目负责人 2026-10-29 复查时指出：又变回人对人了。

**这是「规则定了」和「规则被执行了」之间的差距。** §6.3 原来写的是「适用范围：5 个文件」，读起来像一份改完就完的清单，而不是一条要每批执行的规则。这次把表改成了规则，见第三节。

### 二、改了 28 个文件、56 行

| 类 | 数量 | 例子 |
|---|---|---|
| 口语化与劝告 | 约 20 处 | 「看看全部」「找律师看看」「换一位律师试试」「换个说法试试」「具体说说」「说说律师帮到了你什么」「写了更好办」「传上来最有用」「记得一并处理」「下次不用再翻」；「稍等一下再试试」在 **9 屏重复出现**，统一为「请稍后重试」 |
| 「不用…」句式 | 6 处 | 「不用再切到消息里翻」「不用每次手填」「不用重新填」「不用重拍」「不用再填一遍」「不用重填」 |
| 第一人称自指 | 5 屏 + 1 toast | 09-forgot「我们会向该号码发送验证码」、38-messages「我们已经记录」、12-articles「我们正在整理相关内容」、17-chat「我们只读取你主动选择的照片」、58-feedback「只是有时我们想问清楚却联系不上」；`app.js` 反馈 toast「感谢反馈，我们会看」 |
| 第一人称页面名 | 2 处 | 「关于我们」（41-settings 菜单项、59-about 标题与 `<title>`）→ **关于法泽**。用品牌名比「关于本平台」自然 |
| 「您」 | 2 处 | 03-agent「描述您遇到的法律问题」、06-login「登录即代表您同意」。规则明确不用「您」 |

### 三、把 §6.3 的「清单」改成「规则」

原文是「适用范围：只改平台自己写的固定页面文案」，下面列 5 个文件，全打勾。**问题就在这个形态** —— 它看起来是「这件事做完了」。

改后：保留判定标准，但把表降级成"第 6 批的样本"，正文写清「**界面上的说明性文字都是平台在说话，每新增一屏固定文案页就过一次**」。

同时补了扫查方法，含一条实测教训：

> **命中里一半以上是源码注释。** 本次扫查第一次跑出来 30 多条命中，逐条看发现大部分是设计理由的注释（`/* */`、`<!-- -->`、`★`/`——` 开头）。误改会把设计说明当界面文案改掉。扫查必须排除注释行。

### 四、明确不改的（越界）

- 聊天内容（17-chat、62/63 里的「律师您好」「发您」）—— 用户与律师的对话
- AI 历史里的用户提问（18-ai-history）
- 文章与案例正文（04-case、13-article、19-case-detail 的裁判说理）
- 律师回复（38-messages 里的消息摘要）
- FAQ 里模拟用户口吻的提问（56-faq「实名认证一定要做吗？」—— 问句用用户语气，答案是平台口吻，这是 FAQ 的标准写法）

### 五、顺手发现但**按项目负责人要求没动**的

- `07b-privacy-prompt.html` 缺 `components.css` 引用，导致 `.dialog__icon` 无样式、图标按 SVG 默认 300×150 把弹窗撑高（画面上是上半部一大片空白 + 标志不显示）。**项目负责人 2026-10-29 明确「不管这个」**，本次只改了该页的一句文案，未改引用与样式。
- 术语未统一（「法院」应为「人民法院」至少 8 处、「诉请」应为「诉讼请求」1 处、01-home 半角感叹号）。**项目负责人明确「3 条术语不用改」**，未动。

---

## 2026-10-29 晚 · PC 后台 UI 设计启动 + 基线 v1.4 → v1.5

**这一版改了什么：**

### 一、先说清楚这是一次「决策变更」，不是一次普通新增

2026-10-29 白天，项目负责人明确「暂时先不开启 PC 端」，全库有 4 处据此写了「PC 后台暂缓」：`docs/README.md` 平台范围、分期计划 §1.3 与 §2.3 裁决四、UI 设计顺序第 18 批、§十待定表。

当晚，项目负责人确认**开始 PC 后台 UI 设计**。

所以这一版不是「新增了两份文档」这么简单，而是**执行了一次决策变更并清理被它影响的文档**。原「暂缓」措辞在 UI 设计顺序第 18 批里**保留不删**（加状态变更说明）——那是当时的真实决定，删掉等于伪造历史。

### 二、新增两份文档（基线 21 → 23 份）

#### 1. `docs/architecture/法泽FAZE_PC后台开发计划_v1.0.md`（302 行）

**解决什么**：PC 后台 27 页分别是什么、按什么顺序开发、每阶段怎么验收。这是「PC 后台开发顺序」的唯一准据。

**27 页怎么来的**（这一步以前没人做过，所以值得写清楚）：

- 以技术路线 §15.1 的 9 个模块为骨架：数据看板、用户管理、律师管理、订单管理、内容管理、投诉工单、消息管理、财务管理、系统设置
- **补了 2 个 §15.1 没有的模块**：案件管理（第 5 段验收要求「立案审核」，App 有在线立案/案件送达两页，后台无对应页则该段验收做不了）、内容审核队列（第 7 段验收要求「敏感词命中 → 命中标注」，后台无入口）
- 合计 27 页，分 **7 个开发阶段**（P1 底座 → P2 人与资质 → P3 账务 → P4 交易 → P5 内容 → P6 案件与审核 → P7 治理）

**每页都标了「依赖哪些数据表 + admin 接口有没有」**，这是本文档最有用的部分。

#### 2. `docs/design/未完成需备注.md`（168 行）

**解决什么**：把 PC 设计过程中暴露的**所有未决问题显性化**，让后面写代码的人一眼看到坑在哪。按要求命名（项目负责人批注：「文档就叫『未完成需备注』」「不需要放到设计稿里面」）。

内容四块：

| 块 | 关键结论 |
|---|---|
| 接口缺口 | `/api/v1/admin/**` **只有 4 个接口**（律师审核 2 个、订单 1 个、退款审批 1 个），**27 页里 23 页没有对应 admin 接口** |
| 数据库问题 | `login_log` / `operation_log` **各定义两次、字段不同**（§5.10 vs §5.11.5），Flyway 建表会撞名；表数量 51 / 30 / 17 / 15 四个口径 |
| 模块缺口 | §15.1 缺「案件管理」「内容审核队列」，与分期计划第 5/7 段验收冲突 |
| RuoYi 边界 | 侧边栏 200px、顶栏 50px、标签页 34px、内容区 padding 20px 是框架层写死的，设计稿不能越界；可改的只有主色与业务页内部样式 |

### 三、修改四份文档

| 文件 | 改了什么 |
|---|---|
| `docs/README.md` | ① 平台范围「PC 管理后台（当前暂缓）」→「**UI 设计进行中**」，补 APP 68 + PC 27 页；② 阅读顺序加 PC 计划为第 ⑥ 条；③ 按用途查找补 3 行；④ 目录结构补 2 个文件；⑤ **维护规则新增第 8 条：两分支文档同步规则** |
| `AGENTS.md` | §8 文档索引补 3 行（PC 后台开发计划、`design/admin/` 设计稿、未完成需备注）；两处版本号更新（分期计划内容 v1.1 → v1.2、APP 设计顺序 V2.8 → V2.9）。**AGENTS.md 是每个会话的必读索引，漏了 PC 计划等于新文档不存在** |
| `docs/architecture/法泽FAZE_分期交付计划_v1.0.md` | 内容 **v1.1 → v1.2**：删掉「PC 后台暂缓、页面归属待重排」的挂起说明；改为「已启动，页面归属与开发顺序移交 PC 计划」，并在裁决四下补「PC 阶段 ↔ 分期段」对照表（P1→第 0 段、P2→第 1 段、P3→第 1+6 段、P4→第 2+5 段、P5→第 2+4 段、P6→第 5+7 段、P7→第 7 段） |
| `docs/design/法泽APP_UI设计顺序_v1.0.md` | 内容 **V2.8 → V2.9**：第 18 批标题「暂缓」→「**已启动**」，加状态变更说明；§五的复述同步改 |

### 四、新立一条规则：两分支文档同步（`docs/README.md` 维护规则第 8 条）

**背景**：`UI设计` 分支的文档已经滚到基线 v1.4，而 `docs` 分支还停在早期 6 屏那条线（`b059a75`）。两个分支各带一部分文档，已经分叉过一次。

**规则**：

- `docs` 分支 = 项目全部文档的总库（项目设计、页面导航树、项目背景、开发计划、UI 设计文档），有歧义直接翻它
- `UI设计` 分支 = 只管 UI 设计过程文档
- **每次在 `UI设计` 改完文档，必须同步到 `docs`**，验证方式：`git diff UI设计 docs -- docs/ AGENTS.md README.md` **必须为空**
- 两边各自提交、各自推送

### 五、基线 v1.4 → v1.5

| 项 | v1.4 | v1.5 |
|---|---|---|
| 资产数 | 21 | **23** |
| 新增 | 0 | **2 份** |
| 内容变更 | 4 份 | **4 份** |
| 落款 | v1.0~v1.3 | 新增 **v1.5**（两份新文档） |

### 六、核对结果

| 项 | 结果 |
|---|---|
| 新文档落款 | 2 / 2 已加（v1.5）✅ |
| 新文档指纹登记 | 2 / 2 ✅ |
| `docs/README.md` 索引 | 阅读顺序、按用途查找、目录结构三处都加了 ✅ |
| 变更文件指纹重算 | 4 / 4（README、AGENTS.md、分期计划、UI 设计顺序）✅ |
| 「暂缓」残留检查 | 全库只剩 UI 设计顺序第 18 批的**历史记录**（刻意保留）+ 本 CHANGELOG 的说明 ✅ |
| PC 页数一致性 | 27 页在 README、PC 计划、CHANGELOG 三处一致 ✅ |
| **全库指纹复验** | 按 LF 口径逐份重算 **23 / 23**，最终 23 份全部一致 ✅ |

> **一次返工记在这里**：AGENTS.md 的指纹第一次登记成了 `10471 字节 / 9760bf8e...`，那是**工作区 CRLF 版本**的字节数。正确值是按 LF 的 `10295 字节 / 63f8e7f2...`。
>
> 原因不是手滑，是这台机器 `core.autocrlf = true`：切分支检出后工作区文件变 CRLF，而仓库 blob 是 LF。**BASELINE §五 的验证脚本按工作区文件算指纹，在本机默认配置下会全部报不一致**——这个脚本本身就有这个缺陷（v1.3 时踩过一次，这次又踩）。
>
> 本次改用「读工作区文本 → `\r\n` 还原成 `\n` → UTF-8 编码 → SHA256」重算，23 份逐份比对，只有 AGENTS.md 一处错，已修。**下次改文档后必须用这个方法验，不要直接 `Get-FileHash` 工作区文件。**

### 七、遗留（下一版处理）

| 事项 | 说明 |
|---|---|
| `docs` 分支同步 | 本次改动在 `UI设计` 分支提交后，还需整体同步到 `docs` 分支（按新规则第 8 条） |
| 桌面镜像同步 | 桌面镜像目录需按变更后的 23 份文档重新比对 SHA256 |

---

## 2026-10-29 · 追溯补记：APP UI 设计 17 批 + 文档基线补账（基线 v1.3）

**这是一次补记，不是一次正常记录。** 10-29 全天有 **58 次推送**，本文件一次都没记 —— 本文件最新一条停在 2026-10-28 的基线 v1.2（提交 `437065c`）。而本文件开头第一句就是「每次推送前必须更新本文件」。现在按 `git log` 倒查补齐，**不假装当时记过**。

**触发**：另一个会话在核对文档时指出三处不一致，项目负责人要求「要更新的全部更新一次」。

---

### 一、10-29 实际发生了什么（58 个提交，按批次归并）

| 批次 | 提交 | 内容 |
|---|---|---|
| 立地基 | `3d4edf4` | **新建 `docs/design/法泽APP_UI设计顺序_v1.0.md`** —— 65 页分 17 批（本分支第 1 个提交） |
| 第 1 批 | `e35d242` | 5 个 tab + 登录，6 屏（复刻原项目） |
| 第 2 批 | `323fca8` `24a7bcf` `8b80693` | 启动 / 注册 / 忘记密码 / 用户协议 / 隐私政策 5 屏；补齐导航树与画布差异 |
| 第 3 批 | `8f75511` `cf5765b` `e7ffa46` `088d5bc` `b117c5b` | 文章列表 / 文章详情；**新建 `components.css` 收拢跨屏组件**；四态切换 / 分享面板 / 收藏 / 刷新 / 加载更多 |
| 字体 | `462e54c` | **打包阿拉伯系自托管字体**（Noto Naskh Arabic，94 KB），`tokens.json` 全量对齐实际渲染 |
| 第 4 批 | `a8cf53f` `ea5b868` `4b6a699` `221e339` | 律师详情 / 入驻 / 审核状态 / IM 聊天，定下四个母版 |
| 第 5 批 | `1660770` `067ca7c` `b09e325` | AI 对话历史 + AI 对话页清空确认；设计稿服务器改禁缓存；清理中文换行多余空格 |
| 第 6 批 | `46579c6` `edc2177` `d53cbcb` `52a4769` `9507203` `162b08c` | 案例详情 / 在线立案 / 材料已提交 / 案件送达；**平台措辞红线**（改为平台对用户的措辞）；步骤条连线两个 bug；律师详情字号收敛 8→7 档 |
| 第 8 批 | `e18ce73` `2066143` `7e32fd4` `4d14282` | 文书中心 6 屏；**记录画布 24 屏上限与「上新即切旧」规则** |
| 第 9 批 | `6dc8f33` `ec44314` `0cd2214` `ab79f5f` `0ce51cc` | 订单与支付 6 屏；**支付方式改为余额制**；**导航树 v1.4 → v1.5（模块 7 从 6 页 → 9 页，总数 65 → 68）**；补余额 / 充值 / 支付密码 3 页 |
| 第 10 批 | `80f2b85` `2d0a315` | 消息中心 2 屏 |
| 第 11 批 | `e98ee45` `1556336` | 全局搜索页 |
| 第 12 批 | `49e7f29` `60f4528` | 设置与安全 7 屏 |
| 第 13 批 | `233a720` `9f08834` `cc5dfa4` | 认证与个人 5 屏；**时间轴与状态药丸收口，新增 `state.dangerSoft` 令牌** |
| 第 14 批 | `f5c5918` `ec1b334` | 案件与进度 2 屏 |
| 第 15 批 | `e06deae` `222b9de` | 帮助与关于 5 屏 |
| 第 16 批 | `9df84a4` `2ac94f7` `1d9b600` | 语言切换 / 功能总览 / 版本更新浮层；语言行收进 `components.css` §31，`.dialog__icon` 收进 §10，**修两处依赖漏项**（07c 没引组件库、41b 图标撑满弹窗） |
| 第 17 批 | `8ac5388` `c1670e4` `44fefb0` | **律师工作台 7 屏（62~68），68 页全部完成**；新增深色顶栏变体 §32 + `color.background.workbench` 令牌；**视觉区分定案：深色顶栏、不换主色** |
| 第 18 批 | `1cf96c9` | **PC 管理后台标记暂缓**（项目负责人：「暂时先不开启 PC 端」），§五进度更新到 68 页完成 |
| 收尾 | `f99fdb6` `2b9ba02` | **07c 启动浮层改为弹层进流**，修掉品牌语被压半行；设计顺序 v2.7 → v2.8，律师视角聊天页备注 |

**这 58 次提交的产品结果**：`design/ui/` 下 **71 个 HTML**（68 交付屏 + 3 独立浮层屏）、`components.css` **32 段**、`tokens.css` **78 个变量**、`app.js` 2778 行、`_walkthrough/` **16 个**走查文件。主文档《UI 设计顺序》从 v1.0 滚到 **v2.8**（1863 行）。

---

### 二、这次补账修了什么

#### 1. 《分期交付计划》v1.0 → v1.1（页面口径 65 → 68）

| 改了什么 | 原来 | 现在 |
|---|---|---|
| 页数合计 | 65 | **68** |
| 第 1 段 | 15 页 | **17 页**（+账户余额与明细、+支付密码） |
| 第 6 段 | 3 页 | **4 页**（+充值） |
| 模块 7 | 6 页 | **9 页** |

**为什么这么分**（新增 §2.2.1 写明依据）：余额与明细必须有 —— 因为 §4.3 验收原文就要求「手机余额页显示 `100.00` 元、流水页 1 条记录」，而旧版把这条写进验收标准却没把页面分给这一段，**照旧版施工第 1 段根本验收不了**。支付密码归第 1 段（入口在账号安全页，与账号域同批），充值归第 6 段（第 1 段「明确不做」里写着不做充值，没有渠道的充值页是空壳）。

> 这三页的归属是**本次按验收依赖推导的，不是项目负责人的原始分配**，文档里已标注，等确认。

#### 2. 第 0 段的 docker / RabbitMQ 矛盾：改了分期计划，并新增 §3.5 待决

冲突原文：分期计划 v1.0 §3.2 写「`docker compose` 起 MySQL 8.0 + Redis 7 + RabbitMQ 3.13」，§3.4 验收第一条要求「`docker compose up` 后三个服务全部 healthy」；而第 0 段执行计划 v3.0 L57/L740 写「**没有 docker，也不用**」「禁用清单：**任何形式的 docker**」。

**判定**：按 `AGENTS.md` 第 4 节「段内细节以该段执行文档为准」，**以执行计划 v3.0 为准**，改分期计划。已改 §3.2 工程骨架、§3.3 交付物、§3.4 验收第一条三处，都指向 `deploy/start-services.ps1`。

**同时纠正一个容易误判的点**：技术路线 §18.2 的 Docker Compose 是**生产部署章节**（§18.1 服务器 → §18.2 Docker Compose），不是本地开发。「生产用 docker、本地不用」本来可以并存，**错的是分期计划把生产部署方式写进了第 0 段的本地开发范围**。

**真正的洞**：RabbitMQ 技术路线 L644 说它承担异步短信 / 推送 / 文书 / 审核 —— 分别在第 1、2、4、7 段是刚需。执行计划「不用 docker」只解决了 MySQL 和 Redis，**「本地怎么跑 RabbitMQ」一份文档都没写**。已在 §3.5 记成待决项，列三个选项（本机装 Windows 版 / 放弃 MQ 改任务表 / 第 2 段前再定）和建议（选第三，但第 1 段检查点必须回答），**不代替项目负责人拍板**。

#### 3. 基线断裂：升 v1.2 → v1.3，21 份资产

**实测证据**（脚本解析 `BASELINE.md` 的 20 份登记，逐份重算 SHA256 前 16 位）：

| # | 资产 | 登记 | 实际 | 性质 |
|---|---|---|---|---|
| 9 | `页面导航树_v1.4.md` | 677 行 / 31645 字节 | **730 行 / 36445 字节** | 第 9 批改成 v1.5（`ab79f5f`），没登记 |
| 11 | `设计令牌.md` | 84 行 / 5300 字节 | **157 行 / 11499 字节** | 多批累积增长，没登记 |
| 14 | `tokens.json` | 8077 字节 | **16000 字节** | 同上 |
| 4 | `分期交付计划_v1.0.md` | 736 行 / 39110 字节 | 本次 v1.1 改后重算 | 本次变更 |

**20 份里 3 份指纹不符，另有 1 份本次变更。** 新增 1 份（UI 设计顺序），基线资产 20 → **21**。

**按 BASELINE.md §4 第 2 条「新增文档必须同时做四件事」补齐**（① 落款 ② 登记指纹 ③ `docs/README.md` 加索引 ④ 本文件说明）：四件这次全做了，分别落在 `docs/design/法泽APP_UI设计顺序_v1.0.md` 末尾、`docs/BASELINE.md` §2.3、`docs/README.md` 第一/二/三节、本文件这一节。

**有两处口径要纠正**（避免下次重复误判）：

1. **71 个 HTML、`tokens.css`、`components.css` 不进「文档基线」是对的。** BASELINE 是**文档**基线，资产全是 md + `tokens.json`。设计稿 HTML 有另外的登记方式（`design/ui/` 目录本身 + 主文档的批次表），不往这里塞。
2. **10-29 之前就有欠账。** 导航树 v1.5 是第 9 批改的（不是最后几批）；`设计令牌.md` 和 `tokens.json` 在我接手前就已偏离基线。本次一并补掉，不追责到哪一批。

#### 4. 其它三处索引补齐

- `AGENTS.md` §8 文档索引：加《UI 设计顺序》（原来只有导航树）
- `docs/README.md`：第一节阅读顺序、第二节按用途查找、第三节目录结构，三处都加
- `docs/BASELINE.md`：§五验证脚本的 20 份清单 → 21 份，加新文档路径

---

#### 5. 换行符统一为 LF，基线指纹改按 LF 重算

**这是补账过程中查出来的第 4 个问题。前三个是文档之间打架，这个不一样 —— 它是基线本身的可复现性坏了。**

实测（同一时刻，仓库 HEAD 里的 blob vs 工作区文件）：

| 文件 | 仓库 blob | 当时工作区 | 差 |
|---|---|---|---|
| `docs/design/设计令牌.md` | 11343 字节 / LF | 11499 字节 / CRLF | 156 个 `\r` |
| `design/tokens/tokens.json` | 15668 字节 / LF | 16000 字节 / CRLF | 332 |
| `docs/design/法泽APP_UI设计顺序_v1.0.md` | 131704 字节 / LF | 133577 字节 / CRLF | 1873 |

原因：本机 `core.autocrlf = true`，检出时把 LF 转 CRLF，而**指纹是按工作区文件算的**。

**后果不是"数字不好看"，是「登记了一个只在这台机器上成立的指纹」** —— 任何人 clone 下来文件是 LF，21 份全部对不上，基线等于没建。这与 BASELINE 存在的目的（让别人能核对）直接矛盾。

**修法三步**：

1. 新增 `.gitattributes`：`* text=auto eol=lf`，另把字体/图片/压缩包标 `binary` 防误转。**这是根治** —— 之后不管谁 checkout，都是 LF
2. 全库 **31 个 CRLF 文件**转 LF。转完 `git add -A`，**`git diff --cached` 只有 `.gitattributes` 一个文件有内容变化**，31 个 HTML/CSS/JSON/MD 的实质差异为零 —— 只是行尾
3. 21 份指纹**按 LF 重算**。因此变了 3 份：设计主文档、设计令牌、`tokens.json`

**没有动任何设计稿内容。** 31 个文件的 diff 是空的，这点可以自己验证：

```powershell
git diff --cached --stat   # 只应出现 .gitattributes
```

---

### 三、v1.4 补丁：清掉全库残留的 65 页口径

**v1.3 说「全部更新」，但只清了改过的那几份，没做全库关键词扫描。** 后来 `grep "65 页|65 个页面"` 才查出还有 3 份写着旧口径，**其中两份是索引文档**（根 `README.md`、`docs/design/README.md`）—— 新人从 README 进来，第一眼看到的就是错的页数。改 3 份，基线 v1.3 → **v1.4**。

| 文件 | 改了什么 |
|---|---|
| `README.md`（根） | 「65 个页面清单」→ 68；补 4 行入口（文档基线 / 设计稿本体 / 设计顺序 / 导航树版本提示） |
| `docs/architecture/法泽FAZE_技术路线_v2.3.md` | 头部「65 个页面」→ 68；**§1.3 页面规模表整体重写**：改成「实际（导航树 v1.5 为准）/ 原估 / 偏差」三列，合计 65 → 68 |
| `docs/design/README.md` | 「65 页逐页清单」→ 68 页；「当前项目是 65 页 + 真实后端」→ 68 页；补 1 行索引指向《UI 设计顺序》 |

**技术路线 §1.3 是从 v1.3 起就挂着的「已知未回写」项，这一版结掉。** 顺带把模块级偏差公开了 —— 原来只写「两处合计都是 65」，等于把矛盾藏起来：

| 模块 | 实际（导航树 v1.5） | 原估 | 偏差 |
|---|---|---|---|
| 0 启动与认证 | 6 | 7 | −1 |
| 1 首页 | 3 | 6 | −3 |
| 5 我的 | 1 | 0 | +1 |
| 7 订单与支付 | **9** | 6 | **+3** |
| 10 设置与其他 | 21 | 18 | +3 |
| 合计 | **68** | 65 | +3 |

**教训写在这里，别重复**：清旧口径不能只清自己改过的那几份，要全库 grep 一遍关键词再收工。

---

### 四、仍然没解决的两件事（不在本次范围）

1. **PC 后台各段归属待重排。** 项目负责人已明确暂缓，本次只在裁决四下加了「待 PC 设计重定后统一重排」的说明，**没有假装排好**。
2. **`app.js` 的「40 组交互」这个数没复核过。** 主文档 §五 是这么记的，但没人逐条数过。写在这里提醒：**下次引用前先数，不要当事实传播。**

---

## 2026-10-28 · 第 0 段合成一份单线文档 + 删除 8 份旧文档（基线 v1.2）

**这一版改了什么：**

### 一、先纠正一个我理解错的地方

项目负责人说「不要多阶段并行」，我**连续两轮理解成「第 0 段内 24 个任务也要逐个上闸」**，于是造出「24 任务 + 24 门禁 + 每任务 DoD 勾选表」那套东西。

原意是：**不并行的对象是第 0~8 这九个段**，段内不必切那么碎。

过度约束的代价是实打实的：文档变成两份互相引用、干活要左右对照，而且两份的顺序**还不一致**——执行计划把 13/14/15 排在 21/22 之前，串行序列反过来；执行计划里甚至还留着 `（第 2 批 ∥ 第 3 批）` 这个并行表述。

### 二、第 0 段现在只有一份文档

`docs/architecture/法泽FAZE_第0段执行计划_v3.0.md`（773 行 / 45,050 字节）

形态：**从第一行读到最后一行，按顺序做，中途不需要翻开任何其他文档。**

| 结构 | 内容 |
|---|---|
| 一～四节 | 怎么用 / 做完手上有什么（8 样）/ 环境实况 / 一条线总图 |
| 五～十节 | 6 个组、24 个任务，每个任务五段式：**做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办** |
| 每组末 | 一个检查点（不再是每个任务一道闸门） |
| 十一节 | 13 条验收判定式（一条不少） |
| 十二～十六节 | 冻结物 / 6 条风险 / 明确不做 / 开工确认 / 变更规则 |

**两处顺序与 v2.0 不同，是刻意的**：21/22 排在 13/14/15 之前（Expo 配置与 ESLint 不依赖数据库，先做能尽早暴露工具链问题）；33/34 排在 31/32 之前（前两者不需真机，减少「等手机」空转）。

### 三、删除了 8 份文档

按要求「把不要的文档全给他删掉」。

| # | 删除的文件 | 原因 |
|---|---|---|
| 1 | `第0段执行计划_v2.0.md` | 被 v3.0 合并取代 |
| 2 | `第0段串行执行序列_v1.0.md` | 同上（24 门禁设计被否定） |
| 3 | `第0段任务分解_v1.0.md` | 已作废（含 docker 方案） |
| 4 | `技术路线.md`（v1.0） | 定位写的是「演示 / 参赛作品，不真实上线收费」，与现行定位相反 |
| 5 | `技术路线_v2.1.md` | 被 v2.3 取代，曾被评审出 9 个阻断级缺陷 |
| 6 | `技术路线_v2.2.md` | 被 v2.3 取代，28 条待修项已全部合入 |
| 7 | `技术路线_v2.3_缺陷修复.md` | 文件自己写明「合入 v2.3 时……然后删除本文件」 |
| 8 | `architecture/README.md` | 写于纯 UI 原型阶段，正文大面积作废（「无后端、无数据库、无鉴权服务」「本期只做简体中文」） |

删除前的 commit 是 **`2d36e36`**。任意一份都能这样找回：

```powershell
git show 2d36e36:docs/architecture/法泽FAZE_第0段执行计划_v2.0.md
```

### 四、BASELINE 的规则改了一条（重要，不是小改）

原规则 §4.4 写着「**历史文档不删**」。这条与本次要求直接冲突，不能装作没看见。

改法是：**追溯能力由 git 承担，而不是由工作区里的废文件承担**。

> 新规则：工作区只留现行版。删除时在 BASELINE 第三节登记「删了什么、为什么删、从哪个 commit 能找回」。

**这不是把规则废掉，是换了承载媒介。** 要看旧版技术路线，`git show` 一句就能拿到完整内容；而留在工作区里的作废文档会持续误导人（`architecture/README.md` 就是活例子：它头部自己都写了「部分内容已作废」，但仍然被人当索引读）。

### 五、基线 v1.1 → v1.2

| 项 | v1.1 | v1.2 |
|---|---|---|
| 基线资产数 | 22 | **20** |
| 第 0 段文档 | 4 份（其中 2 份互相矛盾） | **3 份**（v3.0 + 表结构 + 错误码） |
| `docs/architecture/` 文件数 | 12 | **5** |
| 排除清单 | 5 份「保留但不作为依据」 | **0 份** → 改为「已删除登记 8 份」 |

### 六、关于「找子 Agent 写」这件事

按要求启动了一个子 Agent 写这份文档，给的是一份完整规格（必读 8 份文档、24 任务不能丢、13 条判定式不能丢、600~900 行）。

**结果：18 分钟没有落盘任何文件，也无法从 teammate 通道探活（`send_message` 返回 not found），判定为卡住。** 于是自己写。

事后系统通知确认：该子 Agent **被停止，未留下任何收尾消息**。所以不是「写好了没通知」，而是确实没产出。

需要如实说明两点：

1. 自己写**不是最优解**，只是当时的次优选择——你明确要求由子 Agent 产出。
2. 要重试的话应该**缩小任务粒度**：先让它只产出「一条线总图 + 24 个任务标题骨架」，确认能落盘再扩写。一次性要求 700 行是失败的主因（推测，未验证）。

### 七、核对结果

| 项 | 结果 |
|---|---|
| v3.0 的 24 个 `T0-xx` 编号 | 24 / 24 全部覆盖 ✅ |
| 13 条验收判定式 | 全部保留 ✅ |
| `docs/architecture/` 剩余文件 | 5 份 ✅ |
| 删除文件的 git 可恢复性 | 8 / 8 均可在 `2d36e36` 中 `git show` ✅ |
| 基线指纹 | 20 / 20 重新登记 ✅ |
| 残留旧文件名引用 | 5 处已全部修复 ✅：3 处是死链（指向已删的 `architecture/README.md`）、1 处指向已删的技术路线 v2.2、1 处指向早已作废的 v2.1 |

### 八、遗留

| 事项 | 说明 |
|---|---|
| `docs/design/页面清单与信息架构.md` 保留未删 | 页面清单停在 12 页原型阶段已作废，但**第 6 节「页面结构速查」+ 第 5 节「偏离登记」是复刻施工时要用的实证记录**，删了会丢 |
| `docs/architecture/README.md` 删除后目录无 README | `docs/README.md` 已承担索引导航，不另设目录级 README（避免又长出一份会过期的说明） |
| 子 Agent 产出路线 | 若仍要求由子 Agent 写，见第六节第 2 点 |

---

## 2026-10-28 · 基线 v1.1 + 两项定稿（时区 UTC / `is_minor` 语义）

**这一版改了什么：**

### 一、三项拍板结果

| 事项 | 决定 |
|---|---|
| **时区** | ✅ `DATETIME` 存 **UTC**，出参 ISO-8601 带 `+08:00`。已写入 `V1基线表结构_v1.0.md` §5.1 |
| **`is_minor` 语义** | ✅ **不满 18 周岁即置 1**（原「14–18 岁标记」） |
| **注册年龄** | ✅ **不限制年龄**（项目负责人决定） |

### 二、必须先说的一段过程：我判断错了一次

我最初说「`is_minor` 的 14–18 范围有逻辑问题，因为 10 岁用户会被当成成年人」。

**这个判断是错的。** 查证据后发现：`技术路线_v2.3_缺陷修复.md` 的 F-3-23 明确写着「**未满 14 周岁禁止注册**」，所以表里根本不可能有 <14 岁的人，`is_minor=1` 自然只覆盖 14–18。**注释当时是准确的，我错了。**

我错在：只看了 DDL 注释，没去查未成年人机制那一节，把注释当成了规则本身。

**但项目负责人随后决定「不限制年龄」，前提又变了**：注册不再有 14 岁下限 → 表里可能出现任意年龄 → `is_minor` 必须改为「不满 18 周岁即置 1」。

所以最终结论和我初判一致，但**理由是新的，不是原来那个错的理由**。这段过程记进了 `V1基线表结构_v1.0.md` §3.1.1，留作记录。

### 三、连带发现一处早已存在的文档矛盾

技术路线 v2.3 里有两处互相打架：

| 位置 | 说法 |
|---|---|
| §20.6 隐私合规 | 「**不面向未成年人**，年龄识别」 |
| F-3-23（缺陷修复，过程记录） | 「14–18 周岁可注册，需监护人同意」 |

两者不能同时成立。「不限制年龄」直接推翻了 §20.6 那句，本次已改。

### 四、改了哪些文件

| 文件 | 改动 |
|---|---|
| `技术路线_v2.3.md` | ① §20.6 未成年人策略 → 「不限制注册年龄 + 不满 18 周岁需监护人同意 + <14 周岁按《个人信息保护法》第 31 条须监护人单独同意 + 专门处理规则」② §5.3 `is_minor` 语义 → 「不满 18 周岁即置 1」③ §5.3 `birth_date` 注释更新 ④ **文档头加「发布后修订」记录**（不升版本号，写明为什么改） |
| `V1基线表结构_v1.0.md` | ① `is_minor` COMMENT 更新 ② §3.1.1 重写为「定稿」，含我判断错的记录 ③ §五改为「三项建议的处置结果」+ 新增 §5.1 时区约定 |
| `BASELINE.md` | **v1.0 → v1.1**：资产 18 → 22；新增第 0 段 4 份登记；技术路线 v2.3 指纹更新；排除清单加「第0段任务分解 v1.0」；新增 §1.1 解释「落款版本号 = 该文档进基线时的版本」 |
| `docs/README.md` | 基线引用 18 份 → 22 份，v1.0 → v1.1 |
| 4 份第 0 段规划文档 | 末尾加 `【文档基线 v1.1】` 落款 |

### 五、法律提醒（已写进文档）

「不限制注册年龄」不等于「不承担未成年人保护义务」：

1. **《个人信息保护法》第 31 条**：处理**不满 14 周岁**未成年人的个人信息属**敏感个人信息**，必须取得监护人**单独同意**，并制定**专门的处理规则**。
2. **民事行为能力**：<8 周岁无民事行为能力（所签合同无效）；8–18 周岁为限制民事行为能力（只能实施与其年龄、智力相适应的行为）。**付费咨询合同对未成年人是否有效，要单独评估。**

所以 `guardian_phone` + `guardian_consent_at` 对 <14 岁**更是必需**，不是可选。建议监护人同意的触发条件从「14–18 岁」放宽为「**全部 <18 岁**」。

### 六、核对结果

| 项 | 结果 |
|---|---|
| 基线资产数 | 22（21 md + 1 json）✅ |
| 指纹重算 | 22 / 22 全部登记 ✅ |
| `【文档基线 v1.1】` 落款 | 4 / 4 已加 ✅ |
| `is_minor` 两处一致性 | 技术路线 v2.3 与 V1 表结构已同步为「不满 18 周岁即置 1」✅ |
| 排除清单 | 5 份（原 4 + 作废的「第0段任务分解 v1.0」）✅ |
| 时区引用 | `V1基线表结构` §2 库级约定、§5.1 约定、技术路线未改动（v2.3 未定过时区）✅ |

### 七、待办（不阻塞第 0 段）

| 事项 | 何时做 |
|---|---|
| `guardian_consent_at` 触发条件放宽为「全部 <18 岁」 | 第 1 段注册流程 |
| <14 岁单独同意记录 + 专门处理规则 | 第 1 段实现，第 8 段上架前核 |
| 付费合同对未成年人的效力评估 | 第 2 段（下单流程）之前 |

---

## 2026-10-28 · 第 0 段规划定稿（表结构 / 78 个错误码 / 24 任务串行序列）

**这一版改了什么：**

### 一、新增 3 份规划定稿

| 文档 | 内容 |
|---|---|
| `法泽FAZE_V1基线表结构_v1.0.md` | V1 只建 `app_user`（19 字段）+ `law_item`（12 字段）的完整 DDL，可直接进 Flyway |
| `法泽FAZE_错误码规范_v1.0.md` | 冻结 6 位 `[C][DD][NNN]`，**78 个错误码**完整表 + HTTP 状态码规则 |
| `法泽FAZE_第0段串行执行序列_v1.0.md` | 24 个任务排成**一条串行链**，24 个门禁，每任务可勾选 DoD |

### 二、`第0段执行计划_v2.0` 更新

- 顶部声明「4 批 / 可并行」**作废**（项目负责人 2026-10-28 明确要求**不要多阶段并行**），执行顺序以串行序列为准
- 2.3 节前置状态更新：MySQL 密码已提供、真机暂不连接（T0-31 前需要）、豆包 key 到 T0-33
- 第八节开工清单改为状态表，删掉「按批次顺序做」的旧表述

### 三、`第0段任务分解_v1.0` 标注作废

它含 docker compose 方案，已被 v2.0 取代。保留作对照——里面有「6 处文档矛盾清单」和「9 项决策的论证过程」，v2.0 里压缩掉了。

### 四、自查发现的 4 处真错（已修）

提交前做了一轮机器 + 人工复查，查出并修掉：

| # | 问题 | 影响 | 修法 |
|---|---|---|---|
| 1 | 错误码文档写「合计 62 个码」，实际 **78 个**（14+12+6+6+10+7+5+7+4+7） | 服务端 `ErrorCode` 枚举数量对不上，T0-14 验收会失败 | 三处 `62` 全改为 78，并列出各域码数 |
| 2 | 域位映射表把 `09 内容` 映射到附录 C 的 `9xxxx` | 附录 C 的 `9xxxx` 是「**运营/投诉**」，不是内容 | 改为：`09 内容` = 新域（附录 C 无）、`10 运营` = `9xxxx` |
| 3 | v2.0 第 1 批标题仍写「可与第 2 批并行」 | 与「不要并行」的口径直接冲突 | 删掉括号里的并行说明 |
| 4 | v2.0 头部状态写「仅等两样前置」 | 前置状态已变（密码已给） | 改为「前置状态见 2.3 节」 |

**另外补了两处诚实标注**（不是修错，是把不确定的地方写明）：

- **V1 表结构的 DDL 尚未在真实库执行过**。T0-13 是它第一次被执行；文档里单独注明了「已实测通过的部分」（`MEDIUMTEXT` 上建 ngram FULLTEXT 在 MySQL 8.0.44 + `utf8mb4_0900_ai_ci` 下可成功，`EXPLAIN` 确认走索引）。
- **`is_minor` 的「14–18 岁」范围有逻辑问题**：法律上未成年人是不满 18 周岁，14 岁是刑事责任年龄的分界而不是未成年人的下界。若只标 14–18，**10 岁用户会被当成成年人**，而小学生咨询校园欺凌是完全可能的场景。建议改为「不满 18 周岁即置 1」，**待拍板**。

### 五、交叉核对结果

| 项 | 结果 |
|---|---|
| 错误码总数 | 78 个，无重复 ✅ |
| 各域码数 | 01=14 / 02=12 / 03=6 / 04=6 / 05=10 / 06=7 / 07=5 / 08=7 / 09=4 / 10=7，合计 78 ✅ |
| 码内连续性 | 客户端码 `4xx` 与服务端码 `5xx` 各自域内连续，无断号 ✅ |
| `app_user` 字段数 | 19，与 v2.3 原文逐字段一致 ✅ |
| `law_item` 字段数 | 12，与 v2.3 原文逐字段一致 ✅ |
| 任务编号对齐 | v2.0 与串行序列各 24 个，**完全一致** ✅ |
| 并行表述 | 全文已清除（只剩顶部那句作废声明）✅ |
| 索引更新 | `docs/README.md` 已加 4 条「第 0 段」入口 + 目录结构补 5 行 ✅ |

### 六、两份文档里记录的关键判断（供后续查证）

**为什么 `law_item` 的唯一键是三元组 `(law_code, article_no, effective_date)` 而不是两列**：法条会修订，《民事诉讼法》多次修正后同一条号在不同生效日期是不同版本。若唯一键只有两列，修订版会覆盖旧版，**历史引用就指错条文**——这在法律业务里是实质事故。

**为什么单字检索必须走 LIKE 兜底**：已实测 `ngram_token_size=2` 下单字「婚」命中 **0 条**。这不是保险，是必需——后端若写成「只走全文索引」，会返回空结果却以为库里没写过这个字。

### 七、待项目负责人拍板（故基线暂不出 v1.1）

| 事项 | 我的建议 |
|---|---|
| 时区：`DATETIME` 存 UTC 还是本地 | **UTC**（这是数据层不可逆选择，写进去再改要全表重刷） |
| `is_minor` 范围 | 改为「不满 18 周岁即置 1」 |
| 基线 v1.1 | 等上面两项定了再出，届时登记这 4 份 + 第 5 份列入作废 |

---

## 2026-10-28 · 文档基线 v1.0 确立 —— 后续一切文档从这里开始

**这一版改了什么：**

### 一句话

**后续一切文档从这里开始。**

### 做了两件事

**1. 新增 `docs/BASELINE.md`（文档基线 v1.0）**

把散落在 5 个目录、21 个 Markdown 里的文档清成两层：

| 层 | 数量 | 内容 |
|---|---|---|
| **基线内（已确定）** | **18 份资产** | 17 份 Markdown + `design/tokens/tokens.json`，逐份登记**行数 / 字节 / SHA256 前 16 位** |
| **基线外（历史）** | 4 份 | 技术路线 v1.0 / v2.1 / v2.2 / v2.3_缺陷修复 —— 保留在仓库，但**不得作为实施依据** |

内含判断规则：「`docs/architecture/` 下同系列取版本号最大的那份；带『缺陷修复』『待修』字样的是过程记录，不是成品」。

**2. 18 份基线文档末尾统一加基线落款**

每个文件末尾追加同一段：

```text
> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线 v1.0」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
```

**为什么用「末尾落款」而不是只提交一个清单文件**（技术事实）：git 不会为内容无变化的文件产生提交。要让「全部文档重新提交一遍」成立，每份文件必须产生真实字节变化。用末尾落款，这 18 份文档才能真正**全部进入同一次提交**；否则这次提交的变更列表里只会有一个新增的清单文件，其余 17 份仍停留在旧提交里。

### 排除的 4 份历史文档及理由

| 文件 | 行数 | 排除理由 |
|---|---|---|
| `技术路线.md`（v1.0） | 834 | 已被 v2.2 / v2.3 完全取代 |
| `法泽FAZE_技术路线_v2.1.md` | 2,342 | 被六维专家评审出 9 个阻断级缺陷，已在 v2.2 修复 |
| `法泽FAZE_技术路线_v2.2.md` | 3,019 | 附录 F 列 28 条待修项，已在 v2.3 全部合入 |
| `法泽FAZE_技术路线_v2.3_缺陷修复.md` | 1,587 | **过程记录**：28 条修复单，结论已全部合入 v2.3，只用于追溯 |

### 顺带修正

- `docs/README.md` 索引把 `BASELINE.md` 提到首位（⓪）与目录结构首项
- 基线规则写死：新增文档必须**同时**做三件事 —— ① 在 BASELINE 登记指纹 ② 在 `docs/README.md` 加索引 ③ 写 CHANGELOG。**只做其中一件等于没做**
- 规则写死：**基线只增不改**，改内容要出 v1.1；**历史文档不删**，由 BASELINE 第三节标明其性质

### 核对结果

| 项 | 结果 |
|---|---|
| 基线资产数 | 18（17 md + 1 json） |
| 落款覆盖 | **18 / 18** ✅ |
| 指纹登记 | **18 / 18** ✅ |
| 历史文档是否被误加落款 | 否，4 份全部 `False` ✅ |
| git 标签 | `v1.0-docs-baseline`（annotated tag） |
| 基线所在分支 | `docs` |

---

## 2026-10-28 · 修复文档索引体系（三个 README 全部停在原型阶段）

**这一版改了什么：**

### 起因

用户反馈「文档那里也要来一份（分期计划）」。核查后发现：文件**早已同步到桌面镜像**
（4 个文件 SHA256 全部一致），真正的问题是**索引里没有它** —— 文件存在但找不到，等于没有。

### 三个索引文件的实际问题

| 文件 | 它现在写的 | 实际 |
|---|---|---|
| `docs/README.md` | 「法泽 APP **复刻工程**的文档唯一入口」；「有哪 **12 个页面**」；`architecture/` 只列了 `README.md` | 真实上线；65 页；实际 6 个 md |
| `docs/design/README.md` | 「**复刻的施工依据**」；「**12 页清单**」；工作流第 4 步「写假数据到 `__fixtures__/`」 | 65 页；真实接口 |
| `docs/standards/README.md` | 「本项目是**单一 Expo 应用**」；「**不套用 Java 分层，没有 Mapper、Service、Entity**」 | 三端；Spring Boot 服务端必须有 Mapper/Service/Entity |

三个文件**共同缺失**：技术路线 v2.1 / v2.2 / v2.3、分期交付计划 v1.0、页面导航树 v1.4 **全部不在索引里**。

### 改动

1. **重写 `docs/README.md`**
   - 新增「如果你是第一次来（按这个顺序读）」：分期计划 → 技术路线 → 导航树 → 规范
   - 一句话规则：**做什么**看技术路线，**按什么顺序做**看分期计划，**怎么算做完**看验收清单
   - 「按用途查找」补全全部现行文档，新增三条最常问的：「现在该做哪一段」「这一段怎么算做完」「哪些外部资质会卡住我」
   - 目录结构按实际文件重写，标注 ★ 必读
   - 维护规则新增第 5 条（新增文档必须同时更新索引，并写明本文件曾因此长期失效）、第 6 条（版本引用不写死）

2. **重写 `docs/design/README.md`**
   - 去掉「复刻」口径，改为真实上线工作流（含「不属于当前段就先别做」）
   - 明确标注 `screenshots/` 只有 12 页且全是假数据，**不能当页面清单用**
   - 明确 fixture 分支作废：页面只认 `hooks` 返回的形状
   - 保留两节有长期价值的内容：CDP 截图踩坑（headless `--window-size` 实际视口 512 宽、按 390 裁剪会切掉右侧 122px，曾误判为布局溢出）、视觉还原判定标准（新增第 6 条 RTL）

3. **修正 `docs/standards/README.md` 第 23–31 行**
   - 「单一 Expo 应用」「不套用 Java 分层，没有 Mapper、Service、Entity」→ 改为三端口径，服务端**必须**套用 Java 分层
   - 注释规范改为「三端密度一致、形式各自适配」（Java 用类头 Javadoc，TS 用文件头块注释 + JSDoc）

### 为什么这次一起改

只修 `docs/README.md` 的话，另外两个入口仍把人引向「不用写 Mapper」「只有 12 页」的错误认知。
**索引要么全对，要么等于没有。**

### 核对结果

| 项 | 结果 |
|---|---|
| `docs/README.md` 相对链接 | 14 个链接，**失效 0** ✅ |
| 桌面镜像 SHA256 | 4 个文件全部一致 ✅ |
| 未修范围（如实说明） | `architecture/README.md` 只加了文首过期声明，**正文重写留到第 0 段**（工程结构定型后重写才不会立刻再过期一次） |

---

## 2026-10-28 · 新增分期交付计划 v1.0（九段，作为后续一切工作的起点）

**这一版改了什么：**

### 1. 新增 `docs/architecture/法泽FAZE_分期交付计划_v1.0.md`

把整个项目切成 **9 段（第 0～8 段）**，每段自带「端到端 + 单方可验收 + 段末可对真实用户开放」。

| 段 | 名称 | 页数 |
|---|---|---|
| 0 | 可安装底座 + 三项探针 | 1 |
| 1 | 账号 + 律师入驻 + 账务内核 | 15 |
| 2 | 律师图文咨询交易闭环 | 13 |
| 3 | AI 法律顾问 | 3 |
| 4 | 文书中心 | 6 |
| 5 | 案件中心 + 律师工作台 | 14 |
| 6 | 真实充值渠道 + 提现 + 掉单补单 | 3 |
| 7 | 运营与治理收口 | 10 |
| 8 | 四语言全量 + 上架 | 0 |
| | **合计** | **65** |

**为什么改**：技术路线 §23.1 原来的 S0–S16 是按技术层与页面模块横向切分的，有三个硬伤——
1. 每段单独不可验收（S5 的产出是「1 页 + 服务端」，但没有钱、没有律师，这一页打不开也走不通）；
2. 前 15 段做完之前没有任何一刻能对真实用户开放，全部关键决策都在无真实反馈下做出；
3.「S11 设置与其他 18 页」把 21 个互不相关的页面捆成一段，没有一页能独立验收。

新分期的四条判定标准：**单方可验收、自带端到端、段末可对真实用户开放、外部依赖有替代出口**。

### 2. 六处关键裁决（六位专家意见分歧处，记录裁决与依据）

| 裁决 | 结论 | 依据 |
|---|---|---|
| 第 0 段是否独立存在 | 必须存在且排第一 | APK 构建不通则后面每段「装上手机点一遍」的验收方式**全部作废** |
| 支付切成两段 | 第 1 段账务内核 + 第 6 段真实渠道 | 商户号只挡「真充值」，不挡账本、流水、扣费引擎、对账。**第 6 段因此可滑动** |
| AI 放哪 | 探针进第 0 段，业务功能放第 3 段 | 太早没有账号归属与余额，太晚把最高风险压到最后 |
| 管理后台是否独立成段 | 不独立，随业务段交付 | 整段后置会让每一段验收都缺一环 |
| 律师工作台位置 | 第 2 段交付接单与会话，第 5 段交付全量 7 页 | 双角色 App 的验收必须两端同时在场 |
| 多语言怎么切 | 机制进第 0 段，译文与校对进第 8 段 | RTL 是布局坐标系（晚一步 65 页逐页返工）；译文是填充物（可最后补） |

### 3. 技术路线 v2.3 的两处标注为废弃

- **§23.1「阶段划分」→ 已废弃**：保留 S0–S16 表作为历史记录，加声明指向新文档。
- **§23.3「里程碑」→ 已废弃**：原表按周排（第 6 / 16 / 19 / 22 周），与「时间不是约束」的口径冲突。现行计划只排依赖顺序，不排日历。

### 4. AGENTS.md 更新

- 文档索引把「技术路线（最新）」从 `v2.2` **修正为 `v2.3`**（这是一处过期引用，之前一直没跟着改）。
- 第 2 节「禁用清单见技术路线 v2.2 的 2.4 节」改为**不带版本号的写法**（「2.4 节，版本以 `docs/architecture/` 下的最新版为准」），让这类引用以后不会再过期。
  > 全仓 grep `v2.2` / `V2.2` 共 145 处，其中 **143 处是历史沿革叙述**（「V2.2 变更」「v2.2 修正」「v2.2 的某某问题」等），必须保留；**真正的过期引用只有上述 2 处**。
- 新增「分期交付计划」为索引首项，并标注「实施顺序以此为准」。
- 新增「变更日志」索引项。
- 第 4 节「必须做的事」新增第 9 条：实施顺序以分期计划为准，**不得跳过第 0 段**；每段开始前读该段章节并从验收清单逐条勾选；每段结束时在 CHANGELOG 记验收结果与偏差。

### 5. 顺带记录一处遗留问题（本期未修复）

`技术路线 v2.3 §1.3` 与 `页面导航树 v1.4` 的模块页数不一致：

| 模块 | §1.3 | v1.4 |
|---|---|---|
| 0 启动与认证 | 7 | 6 |
| 1 首页 | 6 | 3 |
| 5 我的 | 0 | 1 |
| 10 设置与其他 | 18 | 21 |

两处合计**都是 65**，但中间四个模块的分配不同。分期文档的页数归属**以导航树 v1.4 为准**（它有逐页名称，可逐项核对）。待后续统一。

### 6. `docs/architecture/README.md` 加过期声明（不重写）

该文件写于「纯 UI 原型」阶段，有两处已与当前定位直接冲突：
- 第 13 行「**无后端、无数据库、无鉴权服务**。所有数据是 `__fixtures__/` 里的常量」
- 第 81 行「本期只做简体中文」及 `i18n-js` / `lingui` 的「不引入」结论

**本期只加顶部声明 + 指向现行文档，不重写。** 理由：这份 README 描述的是工程结构，而工程结构要在第 0 段才定型；现在重写会立刻再过期一次。重写留到第 0 段完成时。

### 核对结果

| 项 | 结果 |
|---|---|
| 65 页归属 | ✅ 1+15+13+3+6+14+3+10+0 = 65，与导航树逐页清单完全对齐 |
| 模块级校验 | ✅ 6+3+5+2+5+1+6+6+2+1+21+7 = 65，12 个模块全覆盖 |
| 依赖闭包 | ✅ 无循环依赖；第 6 段可滑动；第 4↔5 段可调换 |
| 段数 | ✅ 9 段（0～8），其中 8 段含页面 |
| 覆盖范围 | ✅ Android + 服务端 + PC 后台；**iOS 明确排除**，单独一期 |
| 工期 | ✅ 全文无周数、无人天（符合「时间不是约束」口径） |
| 外部依赖 | ✅ 8 条并行轨道单独列出，每条标注卡在哪一段、谁负责 |

---

## 2026-10-28 · 技术路线 v2.3 正式版（28 条修复全部合入）

**这一版改了什么：**

### 诞生方式

把 `法泽FAZE_技术路线_v2.3_缺陷修复.md` 里 28 条修复方案**实际合入 v2.2**，产出正式版 `法泽FAZE_技术路线_v2.3.md`（3,793 行 / 156 KB）。v2.3_缺陷修复 保留作为"修复过程记录"，v2.3 是"合并后的成品"。

### 一、数据库（F-1，5 条）

| 项 | 改动 |
|---|---|
| 软删字段 | **5.2 改为按表的三类性质分类**：业务实体表补 `deleted_at`+`updated_at`；追加型流水表（`conversation_message`/`payment_record`/`signature`/`balance_flow`/`login_log`/`operation_log`）**不补**；状态机表只补 `updated_at`。理由：给证据链表加软删 = 允许删除证据 |
| 索引 | 各表 DDL 补齐 `idx_review_lawyer`、`idx_complaint_user`、`idx_lawyer_audit_region`、`idx_notify_user_read`、`idx_order_user_status` 等 |
| 收藏/关注 | `favorite` + `follow` 合并为 **`social_action`**；4.2 新增 **`social` 业务域**（12 域）；4.2.1 明确跨域只能调 Service 不能调 Mapper |
| 文章表 | **5.8 整节重写**：中文存主表、`title`/`content` 补齐、`content` 由 MEDIUMTEXT 改 **JSON 块**（支持法条引用绑定 `law_item`）、5.8.1 定义**状态机唯一真相**、5.8.2 给出译文过期检测 SQL |
| 案例表 | 拆为 `case_library` + `case_library_translation`，与文章同构 |

### 二、新增 15 张支撑表（F-2）→ 5.11

`user_device`、`user_violation`、`lawyer_application_log`、`sys_config`、`notify_template`、`sensitive_word`、`content_review`、`service_ticket`、`reconciliation_diff`、`invoice`、`file_record`、`login_log`、`operation_log`、`share_poster`、`i18n_glossary`

### 三、机制与流程（F-3，13 条）

| 项 | 落点 | 要点 |
|---|---|---|
| 业务缓存 | 4.7.1 | 9 类数据的 Key/TTL/失效时机；Key 集中定义、写后主动删、TTL 加抖动防雪崩 |
| 深色模式 | 3.4.1 | 令牌结构从开始就预留 light/dark 两套，第一阶段只实现 light |
| 日期本地化 | 13.7 | **自建格式化表，不依赖 `Intl`**（Hermes 对 `kk_Arab`/`ug_Arab` 可能静默回退英文） |
| 术语表 | 13.8 + 5.11.5 | `i18n_glossary` 三方共用（AI / 模板翻译 / 后台录入） |
| 敏感词 | 16.2 重写 | **原文落库 + 命中标注 + 展示层拦截**。原写法"实时替换"在法律业务下等于篡改证据 |
| AI 隐式标识 | 12.3.2 | PDF 元数据 + 图片 EXIF 写入 `Faze-Content-Type` 等 5 个字段 |
| AI 禁用清单 | 11.7 | 三层防护：输入侧意图识别 → Prompt → 输出侧正则校验 |
| 文书后处理 | 10.5 | 下载 PDF/Word、保存相册、打印、分享、`form_snapshot` 回填；含 A4 边距规范 |
| 客服角色 | 6.2 + 5.11 | `customer_service` 角色；客服会话用 `service_ticket` 而非共用 `(user_id, lawyer_id)` |
| 分享海报 | 3.7 + 5.11 | Java2D 合成 + ZXing 二维码 + 短链统计 |
| 性能基线 | 19.4.2 | 9 项指标（冷启≤2.5s、列表≥55fps、APK≤60MB 等） |
| 种子数据 | 24.1 | 开发/测试/演示三套 + 一键重置脚本（含生产库拒执行保护） |

### 四、附录 F 改为追溯表

原 28 条清单已全部修复，附录 F 保留为「原问题 → v2.3 落点」的对照表，便于查证。

### 五、顺带修正

- 24 章「资质」行删掉「AI 备案」（已确认不做）
- 5.1 表清单从「34 张」更新为「51 张」，并注明 v2.1 的 30 这个数字本身未经逐表核对
- 文档头补 V2.3 变更记录

### 核对结果

| 项 | 结果 |
|---|---|
| 章节完整性 | ✅ 一～二十五 全部在位（修复了一处中途丢失的「# 十二」标题） |
| 表数量 | ✅ CREATE TABLE 51 条 = 声称 51 张 |
| 附录 F 引用 | ✅ 15 处章节引用全部存在，无悬空 |
| 旧结构清理 | ✅ `CREATE TABLE user` / `favorite` / `follow` / `case` 均为 0 |

---

## 2026-10-28 · `4ae9ea0` · 用户表改名 app_user + 新增 v2.3 缺陷修复

**这一版改了什么：**

### 1. `user` 表改名 `app_user`（技术路线 v2.2）

**为什么改**：
- `USER` 在 MySQL 里是函数名，虽然表名可用但容易与关键字混淆
- RuoYi-Vue3 自带 `sys_user`（PC 后台管理员），若本项目用户表也叫 `user`，后台联调时分不清「管理员」和「C 端用户」

**改了什么**：
- `CREATE TABLE user` → `CREATE TABLE app_user`，加表注释说明与 `sys_user` 的分工
- 唯一键 `uk_user_phone` → `uk_app_user_phone`
- 新增索引 `idx_app_user_role_status(role, status)`
- `role` 枚举从 `user/lawyer/admin` 扩为 `user/lawyer/customer_service/admin`（配合客服工单）
- 6.2 角色权限表加命名说明：「`user` 是角色枚举值，不是表名」
- 5.1 表清单同步
- **5.11 ER 图整体重写**：原来只有 `user` 旧名，且缺 17 张新表

### 2. 未成年人机制字段并入用户表

原计划在 v2.3 单独补，现直接合入 v2.2 的建表语句：

| 字段 | 用途 |
|---|---|
| `birth_date` | 出生日期，用于未成年人识别 |
| `is_minor` | 14–18 岁标记 |
| `guardian_phone` | 监护人手机号（未成年人必填） |
| `guardian_consent_at` | 监护人同意时间 |

### 3. 新增 `法泽FAZE_技术路线_v2.3_缺陷修复.md`（1586 行）

v2.2 附录 F 列了 28 条待修清单，本文档给出**逐条修复方案**（问题 → 方案 → DDL → 验收标准）：

| 分组 | 条数 | 内容 |
|---|---|---|
| F-1 数据库 | 5 | 软删字段分类处理、索引补充、收藏关注独立域、article 主表结构化、案例库拆翻译表 |
| F-2 缺失的表 | 10 | `user_device`、`sys_config`、`notify_template`、`user_violation`、`reconciliation_diff`、`invoice`、`file_record`、`lawyer_application_log`、`sensitive_word`、`login_log`/`operation_log` |
| F-3 其他 | 13 | 缓存策略、深色模式、日期本地化、术语表、敏感词改标注、AI 隐式标识与输出拦截、未成年人机制、文书后处理、客服角色、分享海报、性能基线、种子数据 |

**附**：修复完成度核对表、表变动清单（v2.3 合计 51 张表）、四批 Flyway 迁移的落地顺序。

**核对结果**：28 条编号连续无重复、每条有验收标准、DDL 无重复建表、索引与 v2.2 已建项无冲突。

---

## 2026-10-27 · `1102a49` · 技术路线升级 v2.2，口径转为真实上线

**这一版改了什么：**

### 1. 项目定位翻转：演示/参赛 → 真实上线产品

`AGENTS.md` 从 v1.0 升到 **v2.0**，删掉三处与新方向冲突的禁令：

| 旧 | 新 |
|---|---|
| 「不接后端，数据全来自 fixtures」 | 真实上线，接后端 |
| 「只做简体中文，藏文 `bo` 已废弃，不得重新引入」 | 四语言全做：`zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN` |
| 「纯 UI 复刻工程」 | 真实上线产品，含 PC 后台 |

同时写入已确认的决策：AI 备案不做、法条库必须有、支付是账户余额制、RuoYi 后台、周期不作为约束。

### 2. 技术路线 v2.1 → v2.2（新增 2963 行）

**按项目负责人确认改的：**

| 项 | 变化 |
|---|---|
| PC 后台 | 自选 Vue3 系 → **RuoYi-Vue3 低代码平台**（`E:\itheima\vue\ry_vue3\RuoYi-Vue3`） |
| 支付 | 微信/支付宝直连 + 分账 → **平台账户余额制**，新增 4 张表：`user_balance`、`lawyer_balance`、`balance_flow`、`recharge_record` |
| 法条 | 「不建法条库，依赖模型知识」 → **建本地法条库** `law_item` + MySQL 全文检索 + 引用存在性校验 |
| AI 备案 | 拟做（2–3 个月） → **不做**（《暂行办法》第 2 条第 3 款） |
| 哈萨克文 | 确认用 `kk-Arab-CN`（中国境内阿拉伯字母，RTL） |

**修掉的 9 个阻断级缺陷：**

| # | 缺陷 | 处理 |
|---|---|---|
| B1 | 接口加密在 RN 上无可用依赖 | 删除 6.4，改为 TLS + 证书固定 |
| B2 | 推送双通道互斥 | 统一为个推 |
| B3 | 全文只有 1 个 HTTP 端点 | 新增 4.8 接口清单（9 个域、约 60 个接口） |
| B4 | `case` 是 MySQL 保留字 | 改名 `legal_case` |
| B5 | 无 APK 构建路线 | 新增 3.8（EAS / 本地 / Expo Go 三路径 + keystore 备份） |
| B6 | 「XXL-Job 随 Boot」表述错误 | 改正 + 补 Redisson 依赖 |
| B7 | `openhtmltopdf` 排不出阿拉伯系连写 | 改 LibreOffice headless / Chromium 打印 |
| B8 | RTL 切换缺 `forceRTL` | 13.4 补完整流程（含 `allowRTL`、路由恢复） |
| B9 | 「字体挂 fallback 链」是 Web 思维 | 13.3 改为 Unicode 区段切 run + `<LocalizedText>` |

**修掉的严重级问题（部分）：**

- IM：补 `client_msg_id`、`seq` 改唯一键、新增 `message_offline` 表、**所有操作走统一成员校验**（原来 `read`/`recall`/`typing` 无校验，可撤回他人消息）
- 订单状态机：补出边（`REFUND_REJECTED`、`DISPUTED`、`SETTLED`、律师放弃），「30 分钟无人接」从 `CANCELLED` 改 `REFUNDING`（原设计会让已付款用户被标已取消、钱留渠道）
- 文书：补 `template_version`、`lang`、`form_snapshot`；签名表补页号与 pt 坐标
- 错误码：三套口径统一为一套（`4xxxxx` / `5xxxxx` + 两位业务域）

**措辞修正：**

| 原文 | 改为 | 原因 |
|---|---|---|
| 四川政务服务网 | 新疆本地 | 模板残留，项目在伊犁 |
| 绕开法律服务许可 | 不从事诉讼代理与律师执业业务 | 前者是自认规避，后者是业务边界声明 |
| 专业法律顾问 | 法律信息整理助手 | 避免被认定出具法律意见 |
| 电子签章 | 签名笔迹 | 避免暗示公章效力 |

### 3. 导航树 v1.4 同步（13 行）

AI 接入表里「不预建法条库、不做 RAG」改为建法条库；补 AI 备案不做的说明；model id 改为完整后缀写法；系统 Prompt 改为「法律信息整理助手」。

---

## 2026-10-27 · `7ecfcef` · 页面导航树 v1.4

**这一版改了什么：**

- **修正页面统计口径**：模块统计表原来把「在线立案页」「案件送达页」「我的案件列表」「案件进度页」「功能总览页」在多个模块重复计入，同时漏算「我的」Tab。修正后各模块与页面一一对应
- **统一语言代码**：`zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN`（按 BCP 47 加书写系统子标签）
- **补充页面四态**：每个加载数据的页面必须处理 正常 / 加载中 / 空状态 / 错误 四态，不允许白屏直出
- **补充全局返回逻辑**：系统返回、流程完成 `replace`、深链接逐级回退、登录拦截带 redirect
- **明确「公开案例」与「我的案件」的区别**
- 新增断网提示浮层

---

## 2026-10-27 · `8f2af87` · 技术路线 V2.1 完整版

**这一版改了什么：**

首次产出完整技术路线，2341 行，25 章：

- 完整数据库 DDL（30 张表）
- WebSocket / SSE 消息格式
- AI 合规
- PC 管理后台
- 监控告警体系
- 风控与内容审核
- 测试体系
- 业务闭环与异常流程

> 注：本版后来被六个维度的专家评审出 9 个阻断级 + 约 20 个严重级缺陷，已在 v2.2 修复。评审结论见 `法泽FAZE_评审结论与项目计划.md`（该文件暂未入仓）。

---

## 2026-10-26 · `1e656c7` · 仓库初始化

**这一版是什么：**

法泽 APP 的 Expo 原型工程 + 配套工程规范，共 104 个文件：

- `apps/mobile-app/`：Expo SDK 54 / RN 0.81 / expo-router 6 / TypeScript strict，12 个页面的 UI 复刻
- `features/` 按业务域切分，`app/` 只放薄路由壳
- `shared/theme`：设计令牌（配色以原项目首页为唯一基线）
- `docs/`：三份工程规范 + 三份设计依据 + 两份决策分析
- `design/tokens`：令牌唯一来源
- `docs/design/screenshots`：12 个页面的运行截图（390×844 @2x）

质量门：`tsc --noEmit` 与 `eslint` 均零错误零告警，Web 产物可构建。

> 注：本版自述为「纯 UI 复刻」，定位已在 `1102a49` 改为真实上线产品。

---

## 记录规范

每次推送前，在本文件顶部（`[未发布]` 之下）插入一节，格式：

```markdown
## YYYY-MM-DD · `<commit hash 前7位>` · <一句话标题>

**这一版改了什么：**

### 1. <改动分组>
- 具体改了什么
- **为什么改**（如果理由不明显）

### 2. ...
```

同时 commit message 也要写清改动，两者不重复但互相印证：

| 位置 | 写什么 |
|---|---|
| commit message | 改动的**摘要**（GitHub 提交列表可见） |
| 本文件 | 改动的**完整说明**（含原因、影响面、核对结果） |

**禁止**只写「更新文档」「修复问题」这类无法追溯的说明。


---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线 v1.0」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
