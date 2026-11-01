# 第 7 段工作流：运营与治理收口

> **上位**：《[法泽FAZE_开发计划总纲_v1.0.md](法泽FAZE_开发计划总纲_v1.0.md)》（段顺序、§5.8 边界、`7-A1`~`7-A10` 十条验收锚点、§4.1/§4.2 页面归属）
> **位置**：本段是唯一施工文档。**从第一行读到最后一行即可**，除《技术路线 v2.3》与本节点名的规范外，不需要翻别的文档。
> **生成日期**：2026-11-02
> **依据的实测**：仓库 `HEAD 32892e5`（分支 `UI设计`，基线 v1.33，工作区干净）；`design/admin/01-dashboard.html` 等 8 屏、`design/ui/41-settings.html` 等 10 屏逐屏提取过结构；技术路线 §4.8.4 / §5.11 / §16 / §17 / §18.6 相关段落逐段读过。
> **状态**：**待项目负责人确认**。负责人已于 2026-11-02 就 26 项拍板（§零之二），本文档正文已按裁决写死。
> **规模**：**8 组 / 41 个任务 / 10 条锚点 / 新建 11 张表（含新增的第 54、55 张）/ 接口清单见 §五 5.2 / APP 10 页 + PC 8 页**。

---

## ⚠️ 开工前必读：本段与上游文档有 11 处冲突，全部在 §零之二 裁决完毕

这 11 处不是措辞差异，是**照上游施工就会建错表、画错页、验收不了**的冲突。逐条列在这里，是为了让施工方一眼看到「哪里和你想的不一样」：

| # | 冲突 | 上游怎么说 | 本段怎么定 | 裁决号 |
|---|---|---|---|---|
| 1 | **段 7 建几张表** | 总纲 §5.8 写 7 张（含 `operation_log`、`login_log`）；数据表规范附录 A 写段 7 = **10 张**，且这两张日志表归**段 1** 建 | **建 9 张在册（`share_poster` 不做）+ 新增第 54、55 张 = 实建 11 张**；`operation_log` / `login_log` 本段**只读不建** | 第 3 项 |
| 2 | **`sensitive_word_appeal` 表不存在** | 技术路线 §4.8.4.11 有 2 个申诉接口、A-25 稿画了申诉复核、§16.2.3 写了申诉入口，但 53 张表清单里**没有这张表**，全仓无 `CREATE TABLE` | **新建第 54 张表** `sensitive_word_appeal` | 第 4 项 |
| 3 | **公告的「触达 / 打开率」无表可落** | A-23 稿表头有「触达」「打开率」；技术路线只说公告用 `notify_template` + `notification`，而 `notification` 是**每人一行**的通知，没有「公告批次」概念 | **新建第 55 张表** `announcement`（批次主表 + 统计计数） | 第 5 项 |
| 4 | **`service_ticket` 缺 4 个页面要的字段** | 表 9 字段：`user_id`/`agent_id`/`conversation_id`/`category`/`status`/`closed_at`/时间戳；A-21 稿表头要**工单号、标题、优先级**，提示要**24 小时受理时限** | 补 4 字段：`ticket_no` / `title` / `priority` / `sla_due_at` | 第 6 项 |
| 5 | **`sensitive_word` 缺「最近命中」** | 表有 `hit_count`，A-25 稿表头有「最近命中」列 | 补 1 字段 `last_hit_at` | 第 7 项 |
| 6 | **`content_review` 缺「命中片段」** | 表只存 `biz_type`/`biz_id`/`hit_words`/`level`；A-20 左栏列表要显示**命中词所在的那句话** | 补 1 字段 `hit_snippet`（**不是原文全文**，见裁决 13） | 第 8 项 |
| 7 | **收藏 / 关注列表接口没登记** | 技术路线 L1865 有方法名 `SocialController.listFavorites(...)`，但 §4.8.2 接口清单里**没有路径**；APP 51「我的收藏」、52「关注律师」两页要拉列表 | 补登 **4 个** C 端接口 | 第 9 项 |
| 8 | **企业认证没有「查状态」接口** | §4.8.2 只有 `POST /app/account/enterprise`；APP 49 稿有**四态**（未认证 / 审核中 / 已通过 / 驳回），拉不到状态就没法切界面 | 补登 `GET /app/account/enterprise` | 第 10 项 |
| 9 | **A-20 能不能看聊天原文** | 技术路线 §4.8.4.12 写「A-20 暂只返回摘要与命中片段」；总纲 §十 第 5 条挂「待定」；**A-20 稿画的是「原文：完整保留，命中处只标注」+ 前后上下文** | 取折中：**只返回命中片段 + 前后各 3 条上下文**，不提供全量会话拉取；`7-A2` 的「原文完整」用**直接查库**证明 | 第 13 项 |
| 10 | **客服时段两个数** | 技术路线 §16.4「在线客服 9:00-21:00」；APP 55 / 56 稿写「9:00–20:00」 | **以设计稿 9:00–20:00 为准**（用户看得见的是稿） | 第 15 项 |
| 11 | **日志留存三处打架，且设计稿低于合规下限** | 技术路线 §17.3「日志保留 30 天，错误日志 90 天」（**文件日志**）；审计规范 §5.2「`login_log` ≥ 180 天（网安法）、`operation_log` **≥ 3 年**（个保法/电商法）」；**A-26 稿写「操作日志保留 90 天」——低于合规下限 3 年** | **以审计规范 §5.2 为准**：`login_log` ≥ 180 天、`operation_log` **≥ 3 年**；**A-26 稿的「90 天」文案作废，必须改稿**（改为「按合规要求长期保留」）。与 §17.3 不冲突（那边说的是文件日志） | 第 16 项 |

### 这 11 处必须回写，不是本文档自己改完就算

| 冲突 | 回写到哪 | 为什么必须回写 |
|---|---|---|
| 1 / 4 / 5 / 6 | 《数据表规范》附录 A + 技术路线 §5.11.3 / §5.11.5 对应 DDL | 附录 A 是表清单唯一权威，字段数不对施工时少建 |
| 2 / 5 | 《数据表规范》附录 A 补第 54、55 张 | 不登记 = 建表时找不到、算总表数对不上 |
| 3 | 《技术路线》§4.8.4.10 | 不补，A-23 的「触达/打开率」无处取数 |
| 7 / 8 | 《技术路线》§4.8.2 + 《接口规范》§7.3.2 | 接口不在清单里 = 联调时对方不知道有它 |
| 9 | 《技术路线》§4.8.4.12（把「暂只返回摘要」改成明确口径） | 施工方照旧文会去拉全量会话 |
| 10 / 11 | 《技术路线》§16.4 / §17.3 各加一句「以第 7 段工作流 §零之二 为准」 | 两个数并存时，施工方会按技术路线写死 21:00 |
| 表数 53 → 55 | 《技术路线》§5.1 注 + 《数据表规范》§一 总表数 | 总表数是跨文档引用最多的数字（段 8 收口还要用） |

---

## 零、开工前必须知道的事（2026-11-02 实测）

### 零.1 本段的真实起点：段 0 ~ 段 6 的施工一次都没跑

| 实测项 | 结果（2026-11-02 22:00） |
|---|---|
| `services/` 目录 | **不存在**（服务端一行业务代码都没有） |
| `apps/mobile-app/app/` | 只有 `index.tsx` / `_layout.tsx` / `+not-found.tsx` 3 个文件 |
| `git log` | 全部 `docs:` / `chore:` / `feat(admin)`，**没有一条业务代码提交** |
| `docs/acceptance/` | 只有 `seg1/A3-mp-probe.md` 一份 |
| 设计稿 | APP 71 屏 + PC 28 屏，**齐全**（含本段 18 屏） |
| 文档 | 总纲 + 技术路线 v2.3 + 6 份规范 + 段 0~6 工作流（**7 份**），齐全 |

**结论**：本文档与段 1~6 同性质，是**纯计划文档**。§二 的开工前置检查里，凡涉及「上游已交付」的条目都是**预测性要求**，施工前必须逐条复核。**不要把「上游承诺了什么」当成「上游已经做到了」。**

### 零.2 上游承诺了什么（段 7 可以直接依赖，不许重造）

| 上游 | 承诺 | 段 7 怎么用 |
|---|---|---|
| 段 0 | 登录页 + 鉴权态 + 四态 + i18n 地基 + `LocalizedText` | 本段 10 个 APP 页直接用，不重做（**只补页面，不补地基**） |
| 段 1 | 管理端登录（RuoYi `/login`）+ `operation_log` + `login_log` **两张表** + 审计 AOP + `sys_config` 配置中心 | **A-26 只查不建**；本段所有写接口标 `@OperationLog`；客服时段、留存天数、告警阈值全放 `sys_config` |
| 段 1 | `sys_config` + Jasypt 加密 | 钉钉 webhook 地址、SMTP、告警阈值存这里（**webhook 地址属密钥，必须加密**） |
| 段 2 | `social_action` 表（收藏/关注合并）+ `task_queue`（12 字段：投递是状态不是删除 / 领取 CAS / 租约回收） | 收藏关注列表**只读**本表；本段所有定时任务（超时工单提醒、备份、指标聚合）搭 `task_queue`，**不新建任务表** |
| 段 2 | 个推通道 + `user_device` 推送标识 | A-23 公告的「App 推送」复用，不接第二套推送 |
| 段 2 | 消息中心（APP 38/39）+ `notification` 表 | **本段只写不建**：C 端消息中心在段 2 已交付，本段只负责「公告/工单回复/审核结果」往 `notification` 里投 |
| 段 3 | AI 会话检索接口（A-28）+ `ai_conversation` / `ai_message` | 内容审核队列的 `biz_type` 含 `ai_message` 时能查到；**A-28 页面属段 3，本段不碰** |
| 段 5 | `reconciliation_diff` + 两条账务恒等式巡检 `finance_identity_check` | 本段的「待我处理」不重复算对账，恒等式告警直接用段 5 的巡检结果 |
| 段 5 | 提现队列 `withdraw_record` + 审核接口 | A-02「待我处理」里的「提现待审核 2」**只做计数与跳转**，不重做审核功能 |
| 段 6 | 错误码已用到 `405020`；`task_queue` 的 4 个 handler 范式；`BizNoGenerator` 前缀机制 | 本段错误码从 `410005` 起编；工单号前缀 `TK`、投诉号 `CP`、反馈号 `FB` |

### 零.3 已经定了的（本段不再讨论）

- RuoYi 3.9.2 官方发行版（SB 4.0.3）；MP 用 `mybatis-plus-spring-boot4-starter:3.5.18.1` + `mybatis-plus-jsqlparser:3.5.18.1`（两个都要，缺分页插件会编译失败）
- 金额一律整数分（`BIGINT`），接口传分，前端负责展示转换；**禁止浮点运算**
- 只做**一个管理员角色**（不做客服角色细分）；A-21/A-22 由管理员代办
- 状态迁移**由服务端校验**，客户端只发意图、不发状态；非法迁移抛错误码，不静默忽略
- 管理端写接口**默认标 `@OperationLog`**（只有纯查询不标）；审计进库、日志进文件
- 日志**禁写 L4/L5 敏感字段**：手机号脱敏、身份证号不落日志、`hit_snippet` 不含完整身份证/银行卡
- 多语言：**段 7 只做 `zh-Hans`**（与段 4/6 一致），缺译登记进 `resources/.i18n-pending.json`，段 8 收口时清空
- 迁移版本号：**段 7 = `V700` ~ `V799`**
- 错误码：运营域 `10`。已用到 `410004` / `510003`，本段从 **`410005`** 起编
- 审计动作码：本段新增 13 个（§三 A4 第 3 条与 §九 第 7 项）
- 表名前缀、四类表公共字段、`COLLATE`、`deleted_at`、索引命名全按《数据表规范》
- 接口 URL 前缀、9 个请求头、`R<T>`、三种分页场景（PC 用 `pageNum/pageSize`）、Long 型 ID 序列化为字符串，全按《接口规范》

### 零.4 本段七条「不许」（写在这里防走样）

1. **不许改写原文**。命中敏感词的 IM 消息、评价、评论、AI 回答、投诉正文，**原文一个字不改**；`content_review` 只记录「命中在哪、命中什么词、什么级别」。这是 `7-A2` 的验收对象，也是唯一一条「做错就等于篡改证据」的红线。
2. **不许给 A-20 开「拉全量聊天记录」的口子**。只提供「命中片段 + 前后各 3 条上下文」的只读视图（裁决 13）。管理员要看全文，走投诉工单里的**用户主动提交的截图**。
3. **不许建 `operation_log` / `login_log`**。这两张表段 1 已建，本段只查、只导出、只做页面。重复建会撞名。
4. **不许把告警做成「只打日志」**。`7-A8` 的通过标准是「**真的产生告警**」——钉钉机器人收到一条消息，截图留存。`log.warn()` 不算。
5. **不许在前端做指标聚合**。A-02 的每个数字必须是服务端算好返回的，前端不做除、不做累加。理由：口径只有一份，散到前端就变成多份。
6. **不许让运营改库**。`7-A1` 的五步（审律师 / 审文章 / 处理投诉 / 拉黑 / 回复工单）必须**全部在 PC 后台点完**，任何一步需要连数据库改数据，本段算没做完。
7. **不许把监控与告警依赖外部 SaaS**。钉钉机器人是唯一的对外通道（负责人已确认有配置经验），其余全部本机可跑；不得引入需要企业主体才能开通的服务（企业微信 / 短信签名 / 电话外呼）。

---

## 零之二、负责人 2026-11-02 拍板的 26 项（逐项落到哪一节）

> 这 26 项是本次冲突的**唯一裁决依据**。本文档正文已按裁决写死；施工时若发现正文与本节矛盾，**以本节为准并回改正文**。
> 第 1~4 项是开工前由负责人逐条确认的硬缺口；第 5~26 项是负责人授权「按建议处理」后由本文档落定的。
> **标「建议」的行是我给出的方案，负责人已口头确认「没有问题，按你的处理和建议来」——它不是既有事实，改口径只需改这一行。**

| # | 事项 | 裁决 | 落到哪一节 |
|---|---|---|---|
| 1 | **A-02 看板口径** | ① **DAU** = 当日 00:00–24:00（`Asia/Shanghai`）内**登录过或有任意业务请求**的去重用户数；② **新增注册** = 当日 `app_user.created_at` 落当日的条数；③ **留存** = **次日留存**，分母 = 前一日新增注册，分子 = 这批人次日有登录或业务请求；④ **平台收入** = 当日**平台服务费**（已扣律师所得），**不含充值**（充值属预付款，不是收入）；⑤ 时间范围「今日 / 近 7 天 / 近 30 天」按**自然日**切，不按滚动 24 小时；⑥ 金额单位分，前端转元 | §三 F1 / F3、§四 `7-A10` |
| 2 | **告警通道** | **钉钉自定义机器人 webhook**（负责人有配置经验）。地址存 `sys_config.alert.dingtalk_webhook`（Jasypt 加密）；加签密钥存 `sys_config.alert.dingtalk_secret`；备用通道 = 后台红标 + 每日巡检报告（`GET /admin/ops/health-report`） | §三 G3、§四 `7-A8` |
| 3 | **段 7 建表数** | 按《数据表规范》附录 A：**建 10 张**（`notification` `complaint` `feedback` `faq` `user_violation` `notify_template` `sensitive_word` `content_review` `service_ticket` `share_poster`→不做，见第 11 项）+ **新增 2 张**（第 54、55 张）。`operation_log` / `login_log` **段 1 已建，本段只读**。**实建 11 张**（10 张在册 − `share_poster` 1 张 + 新增 2 张） | §三 A1、§五 5.1 |
| 4 | **`sensitive_word_appeal`（第 54 张）** | **建**。字段：`id` / `user_id` / `content_review_id`（指向被误判的那条命中记录）/ `appeal_reason` / `status`（`pending`/`accepted`/`rejected`）/ `handler_id` / `handle_reason` / `handled_at` / `created_at` / `updated_at`。唯一键 `uk_appeal_review (content_review_id)`——**一条命中只能申诉一次** | §三 A1.2、B2 |
| 5 | **`announcement`（第 55 张）** | **建**。字段：`id` / `announcement_no` / `title` / `content` / `target_type`（`all`/`role`/`region`/`user_ids`）/ `target_value`（JSON）/ `channels`（JSON：`inapp`/`push`/`sms`）/ `status`（`draft`/`scheduled`/`sending`/`sent`/`failed`）/ `scheduled_at` / `sent_at` / `reach_count` / `open_count` / `created_by` / 时间戳。`announcement_no` 前缀 `AN` | §三 D1 |
| 6 | **`service_ticket` 补 4 字段** | `ticket_no VARCHAR(32) NOT NULL`（前缀 `TK`，唯一）/ `title VARCHAR(200) NOT NULL` / `priority VARCHAR(10) NOT NULL DEFAULT 'normal'`（`low`/`normal`/`high`/`urgent`）/ `sla_due_at DATETIME NOT NULL`（= `created_at + 24h`，落库而非现算，便于索引与超时扫描） | §三 A1.3、C1 |
| 7 | **`sensitive_word` 补 1 字段** | `last_hit_at DATETIME`（A-25 表头「最近命中」列） | §三 A1.4 |
| 8 | **`content_review` 补 1 字段** | `hit_snippet VARCHAR(500)`（**命中词所在的片段，前后各 40 字，不是全文**）+ `biz_user_id BIGINT`（被审核内容的作者，列表按用户筛选要用） | §三 A1.5 |
| 9 | **收藏 / 关注列表接口** | 补登 **4 个**：`GET /app/social/favorites?targetType=` / `DELETE /app/social/favorites`（批量取消）/ `GET /app/social/follows` / `GET /app/social/follow-count`（个人页角标）。**`social_action` 表段 2 建，本段只读写数据** | §三 E3、§五 5.2 |
| 10 | **企业认证状态查询** | 补登 `GET /app/account/enterprise`（返回四态 + 驳回原因 + 已提交内容）。**表的字段不够时按附录 A 补，不在本段新建认证表** | §三 E2 |
| 11 | **`share_poster`（8 字段）** | **段 7 不做**。总纲 §5.8 没列、APP 无对应页、`7-A1`~`7-A10` 里没有它。**不建表、不写接口**，登记为「段 8 之后再做」 | §五 5.1、§七 |
| 12 | **内容审核引擎** | **段 7 只做本地词库**（`sensitive_word` + Trie）。阿里云内容安全**记为「上线前接入」挂账**，本段不申请 key、不写适配器。理由：`7-A2`/`7-A3` 只验本地词库；接云会引入计费与主体资质，段 7 的外部依赖应保持为零 | §三 A2、§六 R1 |
| 13 | **A-20 能看到什么** | 只返回「命中片段（`hit_snippet`）+ 前后各 3 条上下文（脱敏后的摘要）」。**不提供全量会话拉取接口**；`7-A2` 的「原文完整」用 `SELECT content FROM conversation_message WHERE id=?` **直接查库**证明，不靠后台页面 | §三 B3、§四 `7-A2` |
| 14 | **工单 SLA** | **24 小时受理**作数（A-21 稿明写、技术路线 §16.4 也写）。`sla_due_at` 落库；超时工单在列表**标红**并计入 A-02「待我处理」；超时扫描任务挂 `task_queue`，每小时一次 | §三 C1 / C3 |
| 15 | **客服时段** | **9:00–20:00**（以 APP 55/56 设计稿为准）。存 `sys_config.service.online_hours`，非时段走「留言 → 次日回复」 | §三 E4 |
| 16 | **日志留存** | **以《审计与日志规范》§5.2 为准（合规下限，不得下调）**：`login_log` **≥ 180 天**（网安法 6 个月）、`operation_log` **≥ 3 年**（个保法/电商法）。配置项沿用审计规范已定义的 `audit.login-log-days=180` / `audit.operation-log-days=1095`。**A-26 设计稿写的「操作日志保留 90 天」作废**——设计稿不是合规依据，90 天低于 3 年下限，**必须改稿**（§九 第 9 项）。清理任务挂 `task_queue`，**只删超期行、先归档到文件再删**；且启动时若配置值低于合规下限，**拒绝启动并报错** | §三 A4 / F2、§六 R4、§九 第 9 项 |
| 17 | **敏感词初始词库** | 本段交付**机制 + 50 条示例词**（五类 × 四语言，每类 10 条中英为主）。正式词库由负责人/运营提供，**挂账**记进 `docs/acceptance/seg7/挂账.md` | §三 A2.3、§九 |
| 18 | **监控栈** | 段 0 定「不用 docker」，所以本机装 **Windows 版 Prometheus + Grafana 二进制**，`scripts/monitor/start-monitor.ps1` 一键起。只抓 `/actuator/prometheus`。开发/验收期允许与业务同机（总纲 §5.8 风险表已允许） | §三 G1 / G2 |
| 19 | **备份** | 本机 `E:\faze-backup\`，`mysqldump` 每日全量 + binlog，**保留 30 天**（技术路线 §17.5 已有口径）。恢复演练回到 `faze_restore` 库，**不动生产库** | §三 G5、§四 `7-A9` |
| 20 | **值班方式** | P0（全站不可用）立即响应；其余次日 9:00–21:00。**一个人做不到 7×24，如实写进运维文档**，不许写「7×24 值班」 | §三 G3.4、§六 R5 |
| 21 | **A-02 接口数** | 设计稿实际需要 **4 个**：`summary`（4 个核心指标）/ `trends`（14 天折线）/ `regions`（地区分布）/ `todos`（待我处理 5 类计数）。技术路线原 3 个里的 `online`（实时在线）**并入 `summary`**，不单独开接口 | §三 F1、§五 5.2 |
| 22 | **公告渠道** | 站内信**必选**（`notification` 落库）+ App 推送（复用段 2 个推）+ 短信**仅资金/服务中断时用**（按条计费，本段只留配置与开关，不接短信服务商） | §三 D1 / D4 |
| 23 | **投诉 / 反馈 → 工单** | 用户提交投诉或反馈后，**写 `complaint` / `feedback` 原表（含截图 JSON）+ 同时在 `service_ticket` 生成一条工单**（`category` = `complaint`/`feedback`，`type_id` 指向原表 id）。A-21/A-22 **只操作 `service_ticket`**，详情页 join 原表拿正文与截图 | §三 C2 |
| 24 | **迁移区间** | **`V700` ~ `V799`**。本段用 4 个：`V700`（11 张表 + 4 处补字段）/ `V701`（`announcement`）/ `V702`（`sensitive_word_appeal`）/ `V703`（种子数据：敏感词示例 + FAQ + 通知模板） | §三 A1 |
| 25 | **错误码** | 从 **`410005`** 起编，本段用 12 个（§三 A6）。`410001`~`410004`、`510001`~`510003` 已被占用 | §三 A6、§九 第 6 项 |
| 26 | **公告不可撤回** | A-23 稿明写「公告发出后不可撤回」。**按稿实现**：`sent` 状态不可回退，只允许「补发一条更正公告」。撤销按钮不做 | §三 D1.4、§七 |

### 这 26 项里，哪几条改变了总量

| 项 | 改了什么 | 影响面 |
|---|---|---|
| 3 + 4 + 5 + 11 | 表总数 **53 → 55**，段 7 实建 **11 张** | 《数据表规范》附录 A、技术路线 §5.1 注、段 8 收口核对 |
| 6 + 7 + 8 | 3 张表各补字段（共 7 个新字段） | 附录 A 的字段数：`service_ticket` 9→13、`sensitive_word` 11→12、`content_review` 10→12 |
| 9 + 10 | 接口清单补 5 个（4 收藏关注 + 1 企业认证） | 技术路线 §4.8.2、接口规范 §7.3.2 |
| 21 | A-02 接口 3 → 4 | 技术路线 §4.8.4.3 |
| 1 | 看板口径从「未定」变成「已定」 | 技术路线 §4.8.4.3 那两句「口径仍未定」必须删 |
| 15 + 16 | 两个口径以小数为准 | 技术路线 §16.4、§17.3 各加一句 |

---

## 一、本段目标与出口

### 一.1 本段目标（3 句）

1. **让运营方自己干活**：审核律师 / 审核文章 / 处理投诉 / 拉黑用户 / 回复工单 五件事，全部在 PC 后台点完，**不需连数据库、不需找技术改代码**。
2. **把治理能力建起来**：敏感词全文命中可标注可申诉、违规与黑名单可执行、公告与通知模板可下发、每一次管理动作可追溯（谁 / 何时 / 从哪 IP / 改了什么）。
3. **把「盲飞」补上**：Prometheus 抓得到指标、钉钉收得到告警、备份能恢复、A-02 看板的每个数字都能说出口径与来源。

### 一.2 出口（进段 8 的条件）

`7-A1` ~ `7-A10` **十条全过**，且：

| # | 出口条件 | 判定方式 |
|---|---|---|
| E1 | 告警通道已定且**真的响过** | 钉钉群里有至少一条由系统触发的告警截图，含时间戳 |
| E2 | 值班方式已定并写进运维文档 | 文档里有一节「值班与响应」，写明 P0/非 P0 的响应口径 |
| E3 | 备份策略（频率 / 保留期 / 存放位置）已写进运维文档 | 文档 + `scripts/backup/` 可执行脚本 + 一次真实恢复记录 |
| E4 | 看板口径文档化，每个指标有算法与数据来源 | §五 5.5 的《A-02 指标口径》一页纸，10 个指标逐条有 SQL 口径 |
| E5 | 11 张表迁移可在空库上一次跑通 | `flyway migrate` 从 V700 跑到 V703 零报错，`flyway info` 全绿 |
| E6 | 段 8 要用的东西没有一个是本段挖的坑 | §八.2「我不保证」无新增项 |

### 一.3 反向定义（什么**不算**出口）

| 不算 | 为什么 |
|---|---|
| 「接口写完了」 | 接口写完但页面对不上 = 没做完。本段 54 个接口**全部有页面消费方或明确的验证方式** |
| 「告警规则配好了，但没触发过」 | `7-A8` 要求**真的响**。没响过就是没验过 |
| 「看板能显示数字」 | 数字来源说不清 = 假数。总纲 §5.8 明确「口径未定不许上线假数」 |
| 「备份脚本写好了」 | 没做过一次真实恢复 = 不知道能不能恢复 |
| 「敏感词能拦了」 | 拦了但把原文改了 = `7-A2` 直接不过，而且违反红线 |
| 「日志能查了」 | 能查但四要素缺一（谁/何时/从哪/改了什么）= `7-A6` 不过 |
| 「运营动作走通了」 | 走通但中途改了库 = `7-A1` 不过 |

---

## 二、开工前置检查（逐条勾，不通过不开工）

### 二.1 硬前置（缺一项就不能开工）

| # | 检查项 | 怎么验 | 不通过怎么办 |
|---|---|---|---|
| 1 | **段 0 已交付** | `apps/mobile-app` 能真机装包、能真接口登录、四态与 i18n 地基在 | **停**。没有可安装包，本段 10 个 APP 页无处落 |
| 2 | **段 1 已交付** | `operation_log` / `login_log` / `sys_config` 三张表存在；审计 AOP 可用；`GET /admin/wallets/{userId}` 能查余额 | **停**。A-26 无表可查、审计无处落 |
| 3 | **段 2 已交付** | `social_action` / `notification` / `task_queue` / `user_device` 存在；消息中心能收发 | **停**。收藏关注列表无表可读、公告无处投 |
| 4 | **段 5 已交付** | `withdraw_record` / `reconciliation_diff` 存在；提现审核接口可用 | **降级**：A-02 的「提现待审核」计数写 0 并标注「段 5 未交付」（记 §十一） |
| 5 | **段 6 已交付** | `recharge_record` 存在；`task_queue` 有 4 个 handler 范式可照抄 | **降级**：A-02 的「平台收入」暂按「服务费」单口径（本段不依赖充值数据） |
| 6 | **管理端登录可用** | RuoYi `/login` 能进后台，能看到 biz 菜单 | **停**。所有 PC 页都无法验收 |
| 7 | **`sys_config` 可写** | 能通过 A-08 增删一个键值 | **停**。本段所有阈值、webhook、时长全放这里 |
| 8 | **设计稿 18 屏齐全** | `design/admin/` 8 屏 + `design/ui/` 10 屏，文件存在且能打开 | **停**。缺页先补稿，不许「边写代码边想界面」 |
| 9 | **本段 18 屏逐屏读过** | 施工方必须读过本文档 §三 各任务里引用的设计稿要点 | **停**。本文档已把关键文案与交互写进任务，未读会写错 |
| 10 | **MySQL 可 `mysqldump`** | 本机 `mysqldump --version` 可用，备份目录可写 | **停**。`7-A9` 无法验收 |

### 二.2 软前置（缺了可降级，但要记挂账）

| # | 检查项 | 缺了怎么办 |
|---|---|---|
| 11 | **钉钉群机器人已创建** | 负责人 2026-11-02 确认有配置经验。**开工前必须给出 webhook 地址与加签密钥**（存 `sys_config`）。若临时没有：`7-A8` 记为「挂账」，用本地 mock 接收端（`scripts/alert/mock-receiver.py` 监听 8088）证明「发出去了」 |
| 12 | **Prometheus / Grafana 二进制可下载** | 无网络时：先用 `/actuator/prometheus` 端点 + `curl` 抓一次文本作为 `7-A7` 的最小证据，Grafana 面板记为挂账 |
| 13 | **个推通道可用** | 无个推：公告的「App 推送」渠道降级为「站内信 only」，A-23 的推送勾选项灰掉并标注（记 §十一） |
| 14 | **短信服务商** | 本段**不需要**（裁决 22：短信只留配置与开关），无影响 |
| 15 | **备份存放盘** | 默认 `E:\faze-backup\`。若该盘空间不足 20GB，改 `sys_config` 指向其他盘 |

### 二.3 第 9 项展开：设计稿没画、由本段自定规格的清单

设计稿是静态 HTML，有些交互与取数规则它表达不了。下面这些**由本文档定**，施工时不得自行发挥：

| # | 未画的部分 | 本段定成什么 | 依据 |
|---|---|---|---|
| 1 | **A-02「待我处理」每条的跳转目标** | 实名认证待审核 → A-04（带 `type=realname`）；律师入驻待审核 → A-05；提现申请待审核 → A-10（带 `status=pending`）；投诉工单待处理 → A-21（带 `status=waiting`）；内容审核命中待处理 → A-20 | A-02 稿注释「每一条都链到对应的审核队列页」 |
| 2 | **A-21「批量分配」怎么选人** | 只有一个管理员角色（零.3），所以「批量分配」实际是**批量指派给自己**，按钮文案保留、逻辑为「批量把 `agent_id` 置为当前登录管理员」 | 零.3「只做一个管理员角色」 |
| 3 | **A-21「关闭前需用户确认」怎么实现** | 关闭工单时服务端**先发一条站内信**问用户「问题解决了吗」，用户回复「已解决」或 7 天无回复才允许关闭。**不在本段做完整的双向确认流**：本段实现为「关闭时必填 `close_reason` + 自动发通知」，用户确认流记 §八.2 | 稿面文案 + 本段范围控制 |
| 4 | **A-25「批量导入」格式** | CSV，列固定：`word,category,level,lang`。UTF-8 with BOM（Excel 友好）。导入**幂等**：`(word, lang)` 已存在则更新 `category`/`level`，不报错。单次上限 5000 行 | 数据表规范 §3.11 与 A-25 稿「批量导入」按钮 |
| 5 | **A-25「导出词库」列** | `word` / `category` / `level` / `lang` / `hit_count` / `last_hit_at` / `enabled`，CSV 同上编码 | A-25 稿表头 |
| 6 | **A-26「导出日志」列** | 操作日志：`id` / `created_at` / `operator_id` / `operator_type` / `module` / `action` / `target_type` / `target_id` / `ip` / `result` / `detail`（JSON 原文）。登录日志：`id` / `created_at` / `account` / `login_type` / `result` / `fail_reason` / `ip` / `user_agent` / `device_id` | 技术路线 §5.11.5 DDL |
| 7 | **APP 55「人工客服」入口点了做什么** | 非时段（20:00–09:00）：切到「留言」表单（复用 APP 58 反馈页，`type=service`）。时段内：弹出「当前无在线客服，请留言或拨打 0999-XXXXXXX」——**本段不做真人在线客服（IM）**，记 §七 | 稿面「9:00–20:00，非工作时间可留言」 |
| 8 | **APP 59「关于法泽」的备案号** | **占位文案**`[待替换：ICP 备案号 / App 备案号]`，并在 `AboutPage.tsx` 顶部注释写「备案号下来必须改这一处」。**不许编一个看起来像真的号** | 稿内 ★ 注释原文 |
| 9 | **APP 61「全部功能」的四组入口** | 找律师与咨询 → 律师列表 / AI / 文章 / 案例库；办案件 → 在线立案 / 我的案件 / 文书中心；订单与钱 → 订单 / 余额 / 充值 / 发票说明；我的与服务 → 设置 / 收藏 / 关注 / 客服 / 反馈 / 关于。**纯前端路由聚合，不新增接口** | 稿面四组标题 |
| 10 | **设置页「清除缓存」清什么** | 只清 `expo-file-system` 缓存目录 + 图片缓存，**不清 SecureStore、不清草稿、不清聊天记录**。确认框必须写清这句（稿内注释明确要求） | `41-settings.html` 注释原文 |
| 11 | **设置页「深色模式」是否同步服务端** | **不同步**，纯本地 `AsyncStorage`（`settings.theme`）。深色模式是设备偏好，不是账号属性 | 稿面「跟随系统 / 开 / 关」三选 |
| 12 | **字体大小四档存在哪** | 纯本地 `AsyncStorage`（`settings.fontScale` = `small`/`normal`/`large`/`xlarge`），映射到 `tokens.fontSize` 的四档倍率 | `41-settings.html` 注释「四档用真实字号渲染」 |
| 13 | **A-23「打开率」怎么算** | 打开率 = `open_count / reach_count`。`open_count` 的统计口径：用户**点击公告进入详情**（`POST /app/notifications/{id}/read` 且来源是公告）时 +1。不做曝光统计（无埋点基建） | A-23 稿表头「触达 / 打开率」 |
| 14 | **A-02「用户地区分布」按什么维度** | 按 `app_user.region`（注册地，段 1 已有）。**不按 IP 归属地**（IP 地理库不在本段范围） | A-02 稿「按注册地」 |
| 15 | **A-20「上下文」拉多少** | 命中记录前后**各 3 条**同会话消息，只返回 `created_at` + `sender_type` + **脱敏后摘要 100 字**。不返回图片 URL、不返回语音 | 裁决 13 |
| 16 | **工单超时扫描频率** | 每小时整点扫一次（`task_queue` cron `0 0 * * * *`），把 `sla_due_at < NOW()` 且 `status` 不是 `closed` 的工单标 `overtime=1`（**不新增字段**，用查询实时判定 + Redis 缓存，避免加列） | 裁决 14 |
| 17 | **备份脚本的运行方式** | Windows 计划任务，每日 03:00；失败重试 3 次；结果写 `logs/backup.log` 并**告警**（连 `7-A8` 的通道） | 裁决 19 |
| 18 | **恢复演练的目标库名** | `faze_restore`（固定名，脚本自动 `DROP DATABASE IF EXISTS` 后重建）。**不许指向 `faze`** | 裁决 19 |
| 19 | **告警「每日巡检报告」内容** | `GET /admin/ops/health-report` 返回：昨日 5xx 数 / P95 延迟 / 备份是否成功 / 磁盘余量 / 未处理工单数 / 未处理审核命中数。每天 09:00 推一次钉钉 | 裁决 2 |

---

## 二之二、与 PC 后台开发计划的关系（本段不重写 PC 计划）

《PC 后台开发计划 v1.0》§二 的 A-02 / A-20 ~ A-26 共 8 页，本段**全部交付**。但 PC 计划里的 **P1~P7 是「开发批次建议」，不是交付单位**（总纲 §1.4 已定）。本段按段交付，顺序是：

```text
A-25 敏感词  →  A-20 内容审核  →  A-21/A-22 工单  →  A-23/A-24 消息  →  A-02 看板  →  A-26 日志
（先有词库才能命中）（有命中才能审）（有审核结果才有工单可关联）（看板最后做，等前面有数据）
```

**为什么这个顺序**：A-20 的列表要显示「命中词与级别」，词库没有就出不来；A-02 的「待我处理」要数 5 类，前四类（实名/入驻/提现/投诉）里本段只负责投诉一类，其余靠上游；A-26 的日志要等本段其他写接口先跑起来才有记录。

---

## 三、任务分组（8 组 / 41 个任务）

> **组内连续推进，组间设检查点**。每个任务五段式：做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办。
> **顺序不可调**：A 组是地基（建表、引擎、口径），B~F 是五条业务线，G 是运维，H 是收口。B~F 之间**可以换序**（都依赖 A），但每组内部任务顺序不能换。
> **每组结束跑一次**：`mvn -q compile` + `npm run lint`（`tsc --noEmit` + `eslint` 零告警）+ 本文档 §五 对应产出清单。

### A 组：地基（6 个任务）

#### A1 · 建 11 张表与 4 处补字段（`V700` ~ `V703`）

**做什么**：按裁决 3~8、11，一次把本段所有表结构变更落成 Flyway 迁移。**11 张表**里 8 张照抄技术路线 §5.11 的 DDL，3 张新建（`sensitive_word_appeal`、`announcement`、以及把 `share_poster` 排除后的核对）。

**怎么做**

1. **`V700__seg7_tables.sql`**——建 9 张既有表的本段版本（字段按技术路线原样，**只加裁决的补字段**）：
   - `notification`（12 字段，照抄 L1797）
   - `complaint`（12 字段，照抄 L1814）
   - `feedback`（10 字段，照抄 L1832）
   - `faq`（9 字段，照抄 L1847）
   - `user_violation`（14 字段，照抄 L1967）
   - `notify_template`（9 字段，照抄 L2032）
   - `sensitive_word`（11 字段 + **`last_hit_at DATETIME`** = 12 字段）；`uk_word_lang (word, lang)` 保留；**新增索引 `idx_word_level_lang (level, lang, enabled)`**（A-20 每次命中都要按级别+语言查）
   - `content_review`（10 字段 + **`hit_snippet VARCHAR(500)`** + **`biz_user_id BIGINT`** = 12 字段）；新增索引 `idx_review_user (biz_user_id, created_at)`
   - `service_ticket`（9 字段 + **`ticket_no VARCHAR(32) NOT NULL`** + **`title VARCHAR(200) NOT NULL`** + **`priority VARCHAR(10) NOT NULL DEFAULT 'normal'`** + **`sla_due_at DATETIME NOT NULL`** = 13 字段）；`uk_ticket_no (ticket_no)`；`idx_ticket_sla (status, sla_due_at)`
   - **`operation_log` / `login_log` 不在 V700 里**（段 1 建，本段只读）
   - **`share_poster` 不建**（裁决 11）
2. **`V701__create_announcement.sql`**——第 55 张表：
   ```sql
   CREATE TABLE announcement (
     id              BIGINT PRIMARY KEY AUTO_INCREMENT,
     announcement_no VARCHAR(32)  NOT NULL,
     title           VARCHAR(200) NOT NULL,
     content         TEXT         NOT NULL,
     target_type     VARCHAR(20)  NOT NULL DEFAULT 'all',   -- all / role / region / user_ids
     target_value    JSON,                                   -- role=lawyer / region=["伊宁市"] / user_ids=[1,2]
     channels        JSON         NOT NULL,                  -- ["inapp","push"] 或 ["inapp","push","sms"]
     status          VARCHAR(20)  NOT NULL DEFAULT 'draft',  -- draft / scheduled / sending / sent / failed
     scheduled_at    DATETIME,
     sent_at         DATETIME,
     reach_count     INT NOT NULL DEFAULT 0,
     open_count      INT NOT NULL DEFAULT 0,
     created_by      BIGINT NOT NULL,
     created_at      DATETIME NOT NULL,
     updated_at      DATETIME NOT NULL,
     UNIQUE KEY uk_announcement_no (announcement_no),
     KEY idx_announcement_status (status, scheduled_at),
     KEY idx_announcement_sent (sent_at)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告批次主表；一条公告一行，触达/打开数在此累计';
   ```
3. **`V702__create_sensitive_word_appeal.sql`**——第 54 张表：
   ```sql
   CREATE TABLE sensitive_word_appeal (
     id                BIGINT PRIMARY KEY AUTO_INCREMENT,
     user_id           BIGINT NOT NULL,
     content_review_id BIGINT NOT NULL,
     appeal_reason     VARCHAR(500) NOT NULL,
     status            VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending / accepted / rejected
     handler_id        BIGINT,
     handle_reason     VARCHAR(500),
     handled_at        DATETIME,
     created_at        DATETIME NOT NULL,
     updated_at        DATETIME NOT NULL,
     UNIQUE KEY uk_appeal_review (content_review_id),
     KEY idx_appeal_status (status, created_at)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词误判申诉；一条命中记录只能申诉一次';
   ```
4. **`V703__seg7_seed.sql`**——种子数据，**全部可重复执行**（用 `INSERT ... ON DUPLICATE KEY UPDATE` 或先 `WHERE NOT EXISTS`）：
   - **50 条敏感词示例**（裁决 17）：五类（政治/暴恐/色情/欺诈/违法）× 各 10 条，`lang='zh-Hans'` 为主，维吾尔文/哈萨克文/藏文各 2~3 条示意。**来源**：通用违禁词表 + 法律行业禁语（「包打赢」「稳赢」「百分百胜诉」「保证判决」），不抄任何具体词库文件
   - **`faq` 7 条**（APP 56 稿的 7 个问题，`zh-Hans`，`category` 四类：账号/支付/律师/立案）
   - **`notify_template` 6 条**（`order_paid` / `order_accepted` / `ticket_replied` / `review_result` / `violation_notice` / `announcement`；每条 `zh-Hans`，`channel='inapp'`）
   - **`sys_config` 新增键**（见 §五 5.4 清单）：`dashboard.dau_definition`、`log.operation_retention_days=90`、`log.login_retention_days=180`、`service.online_hours=09:00-20:00`、`ticket.sla_hours=24`、`alert.dingtalk_webhook`（空，加密）、`alert.dingtalk_secret`（空，加密）、`alert.error_rate_pct=5`、`alert.p95_ms=1000`、`alert.disk_pct=80`、`sensitive.reload_pubsub_channel=faze:sensitive:reload`

**产出**

| 项 | 内容 |
|---|---|
| 迁移文件 | `services/faze-api/src/main/resources/db/migration/V700__seg7_tables.sql` / `V701__create_announcement.sql` / `V702__create_sensitive_word_appeal.sql` / `V703__seg7_seed.sql` |
| 表数 | **新建 11 张**（9 张在册 + 2 张新增）；`operation_log` / `login_log` 只读不建；`share_poster` 不建 |
| 字段变更 | `service_ticket` 9→13、`sensitive_word` 11→12、`content_review` 10→12 |

**算做完**

- [ ] 空库上 `flyway migrate` 从 `V700` 跑到 `V703` **零报错**，`flyway info` 全绿
- [ ] `SHOW CREATE TABLE` 逐张核对：11 张表存在、字段数与上面一致、索引齐全、`COMMENT` 非空、`COLLATE=utf8mb4_0900_ai_ci`
- [ ] 种子数据**跑两遍不报错、不产生重复行**（幂等验证）
- [ ] `SELECT COUNT(*) FROM sensitive_word` = 50；`SELECT COUNT(*) FROM faq` = 7；`SELECT COUNT(*) FROM notify_template` = 6
- [ ] 把 `SHOW CREATE TABLE` 全量输出存 `docs/acceptance/seg7/A1-schema.txt`

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 段 1 建的 `operation_log` / `login_log` 字段与 §5.11.5 不一致 | **不改段 1**。A-26 的查询按**实际字段**写，把差异记 §十一，并在 §九 第 2 项登记「段 1 建表偏离，段 8 收口时统一」 |
| `notification` 表段 2 建过，与 §5.11.1 定义不同 | 同上——**以实际表为准**，本段只写不改；差异记 §十一 |
| Flyway 版本号被段 6 占用到 `V699` 之上 | 段 6 定的是 `V600~V699`，本段 `V700~V799` 安全。若已被占，改用 `V710~V713` 并在 §九 登记 |

#### A2 · 敏感词引擎（Trie + Redis Pub/Sub 热更新）

**做什么**：建一个「加载全量词库到内存 Trie、改动后不重启即生效」的引擎，供 IM 消息、评价、评论、AI 回答、投诉正文共用。**只做判定与标注，不改写任何原文**。

**怎么做**

1. **数据结构**：`SensitiveWordEngine` 单例 Bean，内部 `TrieNode` 树。每个节点存 `Map<Character, TrieNode>` + `List<WordHit>`（`word` / `level` / `category` / `lang` / `wordId`）。按 `lang` 分四棵树，判定时按内容语种选树（语种不明的多棵都跑）。
2. **匹配算法**：Aho-Corasick 或「Trie + 失败指针」。**必须支持重叠命中**（一句话里命中 3 个词要全返回），返回每个词的 `start`/`end`，用于生成 `hit_snippet`（命中词前后各 40 字）。
3. **加载**：启动时 `@PostConstruct` 全量加载 `sensitive_word WHERE enabled=1`。加载完成后写一条 INFO 日志：`sensitive word loaded: version=<max(id)>, count=<n>, cost=<ms>`。
4. **热更新**：管理端增删改词 → 事务提交后 `PUBLISH faze:sensitive:reload <newVersionTo>`。所有实例订阅该 channel，收到后**异步重载**（重载期间继续用旧树，加载完原子替换引用）。**禁止**在请求线程里全量重载。
5. **降级**：Redis 不可用 → 不重载，打 `WARN`，引擎继续用旧树；同时 `7-A7` 的指标能反映（`faze_sensitive_reload_fail_total`）。
6. **判定结果类型**：`SensitiveCheckResult { boolean blocked; String maxLevel; List<WordHit> hits; String snippet; }`。`blocked = 命中任一词且 level='block'`。
7. **性能**：单条消息（≤2000 字）判定 P99 ≤ 5ms（本机实测，A2 完成时记录一次基线）。

**产出**

| 项 | 内容 |
|---|---|
| 引擎类 | `infra/sensitive/SensitiveWordEngine.java` / `TrieNode.java` / `WordHit.java` / `SensitiveCheckResult.java` |
| 热更新 | `config/SensitiveWordReloadListener.java`（Redis 订阅）+ `SensitiveWordPublisher.java` |
| 初始化 | `V703` 的 50 条词；启动日志含版本与条数 |
| 单测 | `SensitiveWordEngineTest`：命中 / 不命中 / 重叠 / 多语言 / 超长文本 / 空文本，≥ 12 个用例 |

**算做完**

- [ ] 单测 12 个用例全绿
- [ ] 改一条词（后台或直接 SQL + 发一次 Pub/Sub）→ **不重启**，下一次判定即按新词库生效（手工验一遍，截图/日志存证据）
- [ ] 2000 字文本判定 P99 ≤ 5ms（跑 1000 次取 P99，写进证据文件）
- [ ] Redis 停掉后引擎仍能判定（用旧树），日志有 `WARN`，恢复后自动重载
- [ ] **没有任何一处代码把命中词替换成 `***`**（全仓 `grep -r '\\*\\*\\*' services/` 零命中，除测试用例）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| Trie 实现太慢（P99 > 5ms） | 先降级为「按 lang 分组 + 长度分桶」，再不行改 Aho-Corasick 库（需先登记依赖清单） |
| Redis Pub/Sub 在单机开发环境不可靠 | 加兜底：管理端保存后**本地实例直接重载**（同 JVM），Pub/Sub 只负责其他实例。单实例开发时本来就用不到 Pub/Sub |
| 多语言分词（维吾尔文/藏文）成词边界不清 | 本段按**子串匹配**，不分词。小语种词库量少（裁决 17：各 2~3 条示意），子串匹配足够；正式词库接入时再评估 |

#### A3 · 内容审核管道（命中标注，不改原文）

**做什么**：给所有 UGC 内容（IM 消息、评价、评论、AI 回答、投诉/反馈正文）接一个**统一的审核入口**：判定 → 写 `content_review` → 按 level 决定「拦截发送 / 送审 / 仅记录」。**原文在任何环节都不改一个字节**。

**怎么做**

1. **统一入口**：`ContentReviewService.review(ReviewRequest req)`，`ReviewRequest { String bizType; Long bizId; Long bizUserId; String content; String lang; }`。返回 `ReviewVerdict { boolean blockDelivery; Long reviewId; }`。
2. **`biz_type` 枚举**（6 个）：`im_message` / `review` / `article_comment` / `ai_message` / `complaint` / `feedback`。
3. **三档处置**（照技术路线 §16.2.1）：
   - `block` → **不投递**给对方（IM 消息不推给对方，但仍落库；评价/评论不进展示）；返回给发送方「该内容含违规信息，未发送」
   - `review` → **正常投递/展示**，同时写 `content_review`（`review_result='pending'`）进 A-20 队列
   - `warn` → 只写 `content_review`，不进队列（`review_result='ignored'`），仅作统计
4. **写 `content_review` 的字段映射**：`biz_type` / `biz_id` / `biz_user_id`（裁决 8）/ `hit_words`（JSON 数组：`[{word, level, category, start, end}]`）/ `level`（取命中最高级）/ `engine='local_trie'` / `hit_snippet`（裁决 8：**命中词前后各 40 字**拼接，多个命中取第一个）/ `review_result` / `created_at`。
5. **脱敏**：`hit_snippet` 落库前过一遍脱敏——手机号 `138****8888`、身份证号只留前 3 后 4、银行卡只留后 4。**不脱敏原文**（原文在业务表里保持完整）。
6. **命中计数**：命中一次 `sensitive_word.hit_count += 1`、`last_hit_at = NOW()`（异步批量更新，不阻塞主流程；用 `task_queue` 或 `@Async` + 批量 flush）。
7. **接入点**（本段只接 3 个，其余由各段自己接）：`complaint` 提交时、`feedback` 提交时、以及给段 2/3 暴露 `review()` 接口供其调用（本段不改编段 2/3 的代码，只在 §九 登记「段 2/3 需在消息/评价/评论落库前调用 `ContentReviewService`」）。
8. **开关**：`sys_config.sensitive.enabled`（总开关）+ `sys_config.sensitive.block_enabled`（是否真拦截）。开关关闭时只写记录不拦。

**产出**

| 项 | 内容 |
|---|---|
| 服务 | `modules/ops/service/ContentReviewService.java` + `impl` |
| DTO | `ReviewRequest` / `ReviewVerdict` / `ContentReviewVO` |
| 接入 | 投诉、反馈两个提交接口内联调用 |
| 脱敏 | 复用段 1 的 `DesensitizeUtil`（若无则本段补 `MaskUtil`） |

**算做完**

- [ ] 发一条含 `block` 级敏感词的投诉 → 接口返回被拦（`410006`），**`complaint.content` 里原文完整**（查库确认）、`content_review` 有 1 条含 `hit_snippet`
- [ ] 发一条含 `review` 级词的反馈 → 正常提交，`content_review.review_result='pending'`，A-20 能查到
- [ ] `hit_snippet` 里的手机号已脱敏、`content_review` 表里**不含完整身份证号**
- [ ] 同一句话命中 3 个词 → `hit_words` 数组 3 项
- [ ] 关掉 `sensitive.enabled` → 不再拦截、不再写记录（只打一条 WARN）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 段 2/3 的消息与评价代码还没写 | 本段只交付 `review()` 方法与接入约定，段 2/3 施工时调用；`7-A2` 的验收用**投诉/反馈**这条链路证明（它们在本段内） |
| `hit_count` 异步更新丢数 | 退化为同步 `UPDATE ... SET hit_count=hit_count+1`（单条，可接受），性能问题记 §十一 |
| 脱敏工具未就绪 | 本段自写 `MaskUtil`（手机号/身份证/银行卡三条正则），登记进依赖清单 |

#### A4 · 审计 AOP 对齐 + 日志中心查询后端

**做什么**：确保本段**所有管理端写接口**都标了 `@OperationLog`、`detail` 存 `before`/`after`；并把 A-26 的 2 个查询接口（操作日志 / 登录日志）+ 2 个导出接口做出来。

**怎么做**

1. **对齐检查**：本段新增的每个 `POST`/`PUT`/`DELETE`（`/admin/**` 前缀）逐个标 `@OperationLog(action="...", module="...")`。`GET` 一律不标（纯查询）。
2. **`detail` 规范**：写操作必须填 `before` 与 `after` 两个键（按审计规范 §3.3）。示例：封禁用户 → `{"before":{"status":"normal","banEndAt":null},"after":{"status":"banned","banEndAt":"2026-12-01 00:00:00"},"reason":"多次发布违规内容"}`。**大文本截断到 2000 字符**。
3. **本段新增 13 个审计动作码**（§九 第 7 项）：`CONTENT_REVIEW_IGNORE` / `CONTENT_REVIEW_WARN` / `CONTENT_REVIEW_BAN` / `SENSITIVE_WORD_CREATE` / `SENSITIVE_WORD_UPDATE` / `SENSITIVE_WORD_DELETE` / `SENSITIVE_WORD_IMPORT` / `APPEAL_HANDLE` / `ANNOUNCEMENT_CREATE` / `ANNOUNCEMENT_PUSH` / `TICKET_ASSIGN` / `TICKET_CLOSE` + C4 的 `TICKET_REPLY`（共 13 个）。**`LOG_EXPORT` 不是本段新增**——审计规范 §6.3 已定义，本段直接复用。
4. **查询接口**：
   - `GET /admin/operation-logs?operatorId=&module=&action=&targetType=&targetId=&ip=&beginTime=&endTime=&pageNum=&pageSize=` → `{list,total,pageNum,pageSize,hasMore}`
   - `GET /admin/login-logs?account=&ip=&result=&beginTime=&endTime=&pageNum=&pageSize=`
   - 两个都按**时间倒序**，索引见审计规范 §6.2
5. **导出**：`GET /admin/operation-logs/export` / `GET /admin/login-logs/export` → CSV（UTF-8 with BOM）。**导出本身要审计**（`action='LOG_EXPORT'`，审计规范 §6.3）。同步导出 ≤ 5000 行，超出走 `task_queue`。
6. **清理任务**：`task_queue` 每日 04:00 跑，按 `audit.operation-log-days`（默认 1095 = 3 年）/ `audit.login-log-days`（默认 180）**先归档到 CSV 文件再删**。**归档失败则不删**（打 ERROR + 告警）。**启动校验**：若这两项配置低于 1095 / 180，服务启动直接失败——留存期不许被配置改到合规下限以下。

**产出**

| 项 | 内容 |
|---|---|
| 接口 | 4 个（2 查询 + 2 导出） |
| 审计 | 12 个动作码；本段全部写接口标注到位 |
| 清理 | `task_queue` handler `LogRetentionCleanHandler` |
| 归档 | `logs/archive/operation_log_YYYYMM.csv` / `login_log_YYYYMM.csv` |

**算做完**

- [ ] 用 ArchUnit 或脚本扫一遍：本段 `modules/**/controller` 下**所有非 GET 方法都有 `@OperationLog`**（漏一个就不过）
- [ ] 做一次封禁 → `operation_log` 有 1 条，`detail` 含 `before`/`after`/`reason`，`ip` 非空
- [ ] A-26 两个 Tab 都能查出刚做的操作（`7-A6` 的底层）
- [ ] 导出 CSV 用 Excel 打开中文不乱码；导出动作本身在 `operation_log` 里有 1 条 `LOG_EXPORT`
- [ ] 改 `log.operation_retention_days=0` 跑一次清理任务 → 归档文件生成、表里旧行被删；把归档目录设为只读再跑 → **不删**且打 ERROR

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 段 1 的审计 AOP 未就绪 | 本段**自己补一个最小 `@OperationLog` + AOP**（不依赖段 1），段 1 交付后合并。**不许因为「段 1 没做」就不审计** |
| `operation_log.detail` 是 `TEXT` 不是 `JSON`（段 1 建错） | 不改表；写入时仍是 JSON 字符串，查询时按字符串处理；差异记 §十一 |
| ArchUnit 未引入 | 用 `grep -rn '@PostMapping\|@PutMapping\|@DeleteMapping' modules/*/controller` 人工核对 + 脚本，把核对结果存证据 |

#### A5 · 待办聚合与看板指标口径固化

**做什么**：把 A-02 要的 4 组数据算出来（`summary` / `trends` / `regions` / `todos`），并把**每个指标的 SQL 口径**写成文档。这是 `7-A10` 的正面回答。

**怎么做**

1. **口径表**（对应裁决 1，写进 §五 5.5 的《A-02 指标口径》）：

   | 指标 | 算法 | 数据来源 | 边界 |
   |---|---|---|---|
   | 今日活跃用户（DAU） | `COUNT(DISTINCT user_id)` 当日有 `login_log.result='success'` **或** 任意 `/app/**` 请求 | `login_log` + 访问日志表/Redis 日活集合 | 当日 00:00–24:00 `Asia/Shanghai` |
   | 今日新增注册 | `COUNT(*) FROM app_user WHERE DATE(created_at)=CURDATE()` | `app_user` | 不含软删 |
   | 今日订单 | `COUNT(*) FROM legal_order WHERE DATE(created_at)=CURDATE()` | `legal_order` | 全状态计入 |
   | 平台今日收入 | `SUM(platform_fee_cents) FROM balance_flow WHERE biz_type='order_income' AND DATE(created_at)=CURDATE()` | `balance_flow` | **已扣律师所得**；**不含充值** |
   | 较昨日 | `(今日 - 昨日) / 昨日 × 100%`，昨日为 0 时显示 `—` 而非 `∞` | 同上 | 除零保护 |
   | 订单量趋势 | 近 14 天每日订单数 | `legal_order` | 自然日 |
   | 用户地区分布 | `GROUP BY region` 取前 5 + 其他 | `app_user` | `region` 为空归「其他」 |
   | 待我处理 5 类 | 实名待审 / 入驻待审 / 提现待审 / 工单待处理（含超时数）/ 内容审核待处理 | `realname_auth` / `lawyer_application` / `withdraw_record` / `service_ticket` / `content_review` | 只计数，不取明细 |
2. **接口 4 个**（裁决 21）：
   - `GET /admin/dashboard/summary?range=today|7d|30d` → 4 个指标 + 环比 + `onlineCount`（实时在线，Redis 集合）
   - `GET /admin/dashboard/trends?metric=orders|revenue&range=14d` → 折线点位数组
   - `GET /admin/dashboard/regions` → `[{region,count}]` 前 5 + 其他
   - `GET /admin/dashboard/todos` → 5 类 `{key,label,count,extra,link}`（`extra` 用于「最新一条等待 3 小时」「涉及金额 ¥4,600」这类副文案）
3. **缓存**：`summary` / `trends` / `regions` 结果 Redis 缓存 **10 分钟**（稿面写「每 10 分钟刷新一次」）。`todos` **不缓存**（要看实时）。
4. **性能】**：`summary` 的全表 `COUNT` 在数据量大时会慢——本段先用实时 SQL（数据量 < 10 万行前无感），并在代码注释里写「> 50 万行需改预聚合表」，记 §八.2。
5. **`onlineCount`**：用 Redis `SET faze:online:{date}`，登录/心跳时 `SADD user:id`，`SCARD` 取数。不做精确在线（无所谓）。

**产出**

| 项 | 内容 |
|---|---|
| 接口 | 4 个（§五 5.2 第 1~4 条） |
| 文档 | §五 5.5《A-02 指标口径》一页纸（10 个指标逐条给 SQL） |
| 缓存 | Redis key `dashboard:summary:{range}` 等，TTL 600s |
| 配置 | `sys_config.dashboard.*` |

**算做完**

- [ ] 造 10 条测试数据（2 个用户、3 笔订单、2 笔服务费流水）→ 4 个接口返回值与手算一致（**逐个数对上，不是「看着差不多」**）
- [ ] 口径文档里每个指标都写了「数据来源表 + SQL + 边界」，**没有一处写「按系统统计」这种含糊话**
- [ ] 缓存生效：10 分钟内重复请求不查库（日志或 Redis 命中率证明）
- [ ] 昨日为 0 时环比返回 `—`，不返回 `Infinity` 或 `NaN`

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 「任意业务请求」这个日活口径拿不到访问日志 | 降级为「当日登录成功的去重用户」，并在口径文档里**明确写降级**、§九 登记；**不许**改口径不说明 |
| 段 5 未交付 → `withdraw_record` 不存在 | `todos` 里该计数返回 0 + `link` 置空，副文案「段 5 未交付」 |
| 段 2 未交付 → `legal_order` 不存在 | `trends` 返回空数组，页面显示空态；**不造假数据** |

#### A6 · 枚举、错误码、审计动作码、i18n key

**做什么**：把所有常量与码值一次登记齐，避免施工时现编。

**怎么做**

1. **错误码 12 个**（运营域 `10`，从 `410005` 起，§九 第 6 项回写《错误码规范》）：

   | code | HTTP | msgKey | 场景 |
   |---|---|---|---|
   | `410005` | 200 | `error.ops.sensitive-word-exists` | 词条已存在（同词同语言） |
   | `410006` | 200 | `error.ops.content-blocked` | 内容命中 block 级敏感词，未发送/未提交 |
   | `410007` | 200 | `error.ops.review-not-found` | 审核记录不存在 |
   | `410008` | 409 | `error.ops.review-already-handled` | 该条已处置过 |
   | `410009` | 200 | `error.ops.appeal-exists` | 该条已申诉过 |
   | `410010` | 409 | `error.ops.appeal-handled` | 申诉已处置 |
   | `410011` | 200 | `error.ops.ticket-not-found` | 工单不存在 |
   | `410012` | 409 | `error.ops.ticket-status-invalid` | 工单状态不允许该操作 |
   | `410013` | 200 | `error.ops.announcement-sent` | 公告已发出，不可撤回 |
   | `410014` | 400 | `error.ops.import-format` | 导入文件格式错误 |
   | `410015` | 200 | `error.ops.export-too-large` | 导出行数超限，已转后台任务 |
   | `410016` | 200 | `error.ops.retention-archive-failed` | 归档失败，已停止清理 |
2. **枚举 5 套**：
   - `SensitiveLevel`：`BLOCK` / `REVIEW` / `WARN`
   - `SensitiveCategory`：`POLITICS` / `VIOLENCE` / `PORN` / `FRAUD` / `ILLEGAL`
   - `ReviewResult`：`PENDING` / `IGNORED` / `WARNED` / `BANNED`
   - `TicketStatus`：`WAITING` / `PROCESSING` / `REPLIED` / `CLOSED`
   - `TicketPriority`：`LOW` / `NORMAL` / `HIGH` / `URGENT`
   - `AnnouncementStatus`：`DRAFT` / `SCHEDULED` / `SENDING` / `SENT` / `FAILED`
3. **审计动作码 13 个**（A4 第 3 条列 12 个 + C4 的 `TICKET_REPLY`）。
4. **i18n key**：`ops.*` 约 45 个（`ops.review.*` / `ops.sensitive.*` / `ops.ticket.*` / `ops.announcement.*` / `ops.dashboard.*` / `ops.export.*`）。**只写 `zh-Hans`**，其余语言登记 `.i18n-pending.json`。
5. **业务单号前缀**：`TK`（工单）/ `CP`（投诉）/ `FB`（反馈）/ `AN`（公告）。复用段 2 的 `BizNoGenerator`。

**产出**

| 项 | 内容 |
|---|---|
| 枚举 | `common/enums/ops/*.java`（6 个枚举） |
| 错误码 | `ErrorCode` 常量 + §九 回写《错误码规范》§3.10 |
| i18n | `resources/i18n/zh-Hans/ops.json` |
| 单号 | `BizNoPrefix` 常量 |

**算做完**

- [ ] 12 个错误码在《错误码规范》§3.10 有登记，与代码里的常量**逐字一致**
- [ ] 6 个枚举都有对应的 DB 字符串值映射（`@EnumValue` 或手写 converter），且**枚举值与 DDL 注释一一对应**
- [ ] 45 个 i18n key 在 `zh-Hans` 里都有值，`key` 格式符合 i18n 规范三段式（`<域>.<场景>.<元素>`）
- [ ] 工单号生成 `TK20261102000001` 格式正确、长度 ≤ 32、唯一索引兜得住

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `410005`~`410016` 与段 8 预留冲突 | 运营域到 `410099` 还有 84 个号，够用；冲突时往后挪并在 §九 登记 |
| `BizNoGenerator` 未交付（段 2 承诺） | 本段自写一个最小实现（日期 + Redis `INCR` + 补零），登记「待段 2 实现后替换」 |

### B 组：审核与风控（6 个任务）

#### B1 · 敏感词管理接口（6 个）

**做什么**：A-25 左栏（词库）的全部接口。列表 / 新增 / 修改 / 删除 / 批量导入 / 导出。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 | 幂等 | 主要错误码 |
|---|---|---|---|---|---|
| 1 | `GET /admin/sensitive-words?level=&lang=&category=&kw=&enabled=&pageNum=&pageSize=` | 词库列表（含 `hit_count` / `last_hit_at`） | PC token | 只读 | — |
| 2 | `POST /admin/sensitive-words` | 新增词条 | PC token | `uk_word_lang` 唯一键兜底 | `410005` |
| 3 | `PUT /admin/sensitive-words/{id}` | 修改（词 / 分类 / 级别 / 语言 / 启停） | PC token | 幂等（全量覆盖） | `410005` |
| 4 | `DELETE /admin/sensitive-words/{id}` | 删除词条 | PC token | 幂等（删已删返回成功） | — |
| 5 | `POST /admin/sensitive-words/import` | 批量导入 CSV | PC token | **幂等**：`(word,lang)` 存在则更新级别与分类 | `410014` |
| 6 | `GET /admin/sensitive-words/export?level=&lang=` | 导出 CSV | PC token | 只读 + 审计 `LOG_EXPORT` | `410015` |

**关键实现**

1. **每次增删改后发 Pub/Sub**（A2 第 4 条）：`PUBLISH faze:sensitive:reload`。**必须放在事务提交之后**（`TransactionSynchronization.afterCommit`），否则订阅方可能读到未提交数据。
2. **批量导入**（裁决：§二.3 第 4 项）：CSV 表头固定 `word,category,level,lang`。逐行校验 → 收集错误行号 → **全对才写**（事务内）。单次上限 5000 行。返回 `{success:0,failed:0,errors:[{line,reason}]}`。
   - 级别值只接受 `block`/`review`/`warn`；分类只接受枚举值；语言只接受四个语种代码
   - 重复行（同文件内同 `word+lang`）→ 报错该行「文件内重复」
3. **删除是物理删还是停用**？技术路线 §4.8.4.11 明写「**词库可删**」。本段按**物理删**（`DELETE`），但审计留 `before` 快照。停用走 `PUT`（`enabled=0`）。
4. **列表排序**：默认 `created_at DESC`；带 `kw` 时按 `hit_count DESC`（用户搜词是想看命中多的）。
5. **导出列**（§二.3 第 5 项）：`word,category,level,lang,hit_count,last_hit_at,enabled`，UTF-8 with BOM。

**产出**：`modules/ops/controller/SensitiveWordController.java` + Service + Mapper + XML；6 个接口；审计动作码 `SENSITIVE_WORD_CREATE/UPDATE/DELETE/IMPORT`。

**算做完**

- [ ] 6 个接口在 Swagger 里可见，路径与 §五 5.2 表一致
- [ ] 新增重复词 → `410005`；改级别后下一次判定按新级别（**联 A2 的热更新一起验**）
- [ ] 导入 100 行合法 CSV → 成功 100、失败 0；导入含 3 行错误的 CSV → 整批不写、返回 3 条错误行号
- [ ] 导出的 CSV 在 Excel 打开中文不乱码、行数与列表一致
- [ ] 每个写接口都有 `@OperationLog`，`detail` 里 `before`/`after` 都有

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| CSV 上传走 OSS 直传太复杂 | 本段用 `multipart/form-data` 直接上传（≤ 2MB），不经 OSS；登记「大文件后续改直传」 |
| 导入 5000 行超时 | 降到 1000 行同步 + 超出走 `task_queue` 异步，结果写 `notification` 给管理员 |

#### B2 · 申诉复核（3 个任务内接口 + 1 张新表）

**做什么**：A-25 右栏（申诉复核）。用户对 `block` 级误判提交申诉 → 后台复核 → 维持 / 撤销 / 降级。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 | 主要错误码 |
|---|---|---|---|---|
| 1 | `POST /app/content-appeals` | 用户提交申诉（`{contentReviewId, reason}`） | App JWT + 归属校验 | `410009` / `410007` |
| 2 | `GET /admin/sensitive-word-appeals?status=&pageNum=&pageSize=` | 申诉列表（join `content_review` 拿 `hit_snippet`） | PC token | — |
| 3 | `POST /admin/sensitive-word-appeals/{id}/audit` | 处置：`{action:"accepted"\|"rejected", reason}` | PC token | `410010` |

**关键实现**

1. **谁能申诉**：只有 `content_review.biz_user_id = 当前用户` 的那条才能申诉（**IDOR 防护**，接口规范 §6.3）。且只有 `level='block'` 的命中可申诉（`review`/`warn` 用户看不见，无从申诉）。
2. **一条只能申诉一次**：`uk_appeal_review (content_review_id)` 唯一键兜底（裁决 4）。重复提交返回 `410009`。
3. **处置语义**：
   - `accepted`（通过申诉）→ `content_review.review_result='ignored'`（从审核队列移除），**词条本身不变**（A-25 稿明写「词条本身不变」），该用户不被处置
   - `rejected`（维持原判）→ `content_review.review_result='warned'`，用户收到通知
   - **「降级」不做**：稿面只有两个按钮（通过申诉 / 维持原判），技术路线写的「维持/撤销/降级」三选与稿不符，**以稿为准**（裁决：§零之二 未单列，按稿实现）
4. **通知**：处置后写 `notification`（`type='system'`，`notify_template` code `review_result`）。

**产出**：`sensitive_word_appeal` 表（V702）、3 个接口、审计动作码 `APPEAL_HANDLE`、i18n `ops.appeal.*`。

**算做完**

- [ ] 用测试用户对一条 `block` 命中提交申诉 → `sensitive_word_appeal` 有 1 条 `pending`
- [ ] 同一 `content_review_id` 再提交 → `410009`
- [ ] 用别的用户的 token 提交 → 403（越权被拦）
- [ ] 后台 A-25 右栏能看到该申诉，点「通过申诉」→ `content_review.review_result` 变 `ignored`，A-20 队列里查不到它了，**词库那条词还在**
- [ ] 用户收到「申诉已处理」站内信

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 用户端没有申诉入口（APP 稿里没画） | 申诉入口挂在**内容被拦截时的错误提示**里（「如认为误判，点此申诉」），不新增页面；记 §二.3 |
| `content_review` 表没有 `biz_user_id` | 裁决 8 已补，若迁移漏了就补 `ALTER TABLE`（`V700` 内） |

#### B3 · 内容审核队列接口（3 个）

**做什么**：A-20 左栏（命中记录）+ 右栏（原文复核）的全部取数与处置接口。**原文只读，不改写。**

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 | 主要错误码 |
|---|---|---|---|---|
| 1 | `GET /admin/content-reviews?status=&type=&level=&bizUserId=&beginTime=&endTime=&pageNum=&pageSize=` | 命中记录列表 | PC token | — |
| 2 | `GET /admin/content-reviews/{id}` | 详情：命中词 + **`hit_snippet`** + **前后各 3 条上下文**（裁决 13） | PC token | `410007` |
| 3 | `POST /admin/content-reviews/{id}/audit` | 处置：`{action:"ignore"\|"warn"\|"ban", reason}` | PC token | `410008` |

**关键实现**

1. **处置三选**（照 A-20 稿按钮「忽略（误判）/ 警告用户 / 封禁」）：
   - `ignore` → `review_result='ignored'`，不处置用户
   - `warn` → `review_result='warned'`，写 `user_violation`（`level='warning'`，`start_at=NOW()`，`end_at=NULL`），发站内信
   - `ban` → `review_result='banned'`，写 `user_violation`（`level='ban'`，`end_at` 必填），同时把 `app_user.status` 改 `banned`（**CAS 更新**）
2. **上下文怎么取**（裁决 13）：
   - `biz_type='im_message'` → 取同 `conversation_id` 内 `seq` 在 `[hitSeq-3, hitSeq+3]` 的消息，**每条只返回摘要 100 字**（`content` 截断后脱敏）
   - `biz_type` 其它（`review` / `article_comment` / `ai_message`）→ 上下文为空数组，只返回本条
   - **绝不返回图片 URL、语音、完整会话**。`contextAvailable=false` 时前端显示稿面那句「会话内容需在内容审核页查看」的替代文案
3. **`hit_snippet` 的来源**：A3 落库时已算好（前后各 40 字），列表直接返回，**不再回查原文**（回查会碰到隐私边界）。
4. **列表排序**：`created_at DESC`；`status='pending'` 优先（前端默认筛 pending）。
5. **「命中规则」按钮**（A-20 稿右上角）：只做说明弹窗（前端静态文案），无接口。

**产出**：`ContentReviewController` + Service；3 个接口；审计动作码 `CONTENT_REVIEW_IGNORE/WARN/BAN`。

**算做完**

- [ ] 列表能查到 A3 造的 3 条命中（`block`/`review`/`warn` 各 1），字段含 `hit_snippet`
- [ ] 点某条进详情 → 显示命中词、级别、片段；`im_message` 类型能显示前后各 3 条摘要（**不含完整原文**）
- [ ] 处置 `warn` → `user_violation` 新增 1 条 `warning`；处置 `ban` → `user_violation` 新增 1 条 `ban` 且 `app_user.status='banned'`
- [ ] 同一记录再处置 → `410008`
- [ ] 处置后**原文表里的 content 字段一字未变**（查库比对前后哈希）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `conversation_message` 表段 2 未交付 | 本段只实现 `biz_type != im_message` 的上下文（返回空数组），`im_message` 的上下文取数写成骨架 + `TODO`，`7-A2` 用投诉链路验；记 §八.2 |
| 管理员想看完整原文的要求压过来 | **拒绝**。§零.4 第 2 条是红线。要看全文走「用户主动提交的截图」（投诉工单里） |

#### B4 · 黑名单与违规处理（4 个）

**做什么**：`7-A4` 的落地——拉黑后无法登录且提示明确；违规分级处罚可执行可追溯。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 | 主要错误码 |
|---|---|---|---|---|
| 1 | `POST /admin/users/{id}/ban` | 封禁（`{reason, endAt}`，`endAt=null` 为永久） | PC token | 已有（§4.8.4.4） |
| 2 | `POST /admin/users/{id}/unban` | 解封 | PC token | 已有 |
| 3 | `POST /admin/lawyers/{id}/violations` | 律师违规（`{violationType, level, reason}`） | PC token | 已有 |
| 4 | `GET /admin/users/{id}/violations` | 违规记录列表（用户详情页用） | PC token | 新增 |

**关键实现**

1. **封禁生效点**：**登录接口**在密码校验通过后、签发 token 前检查 `app_user.status='banned'` 或存在未过期 `user_violation(level='ban', end_at IS NULL OR end_at > NOW())` → 拒绝登录，返回 **`420xxx` 账户域错误码**（不新增运营域码；用段 1 的「账户已封禁」码，若无则本段补 `420014 error.account.banned` 并回写）。
2. **提示必须明确**（`7-A4` 原文「不能只提示密码错误」）：返回文案 = `您的账号因「{违规原因摘要}」已被限制使用，如有疑问请联系客服 0999-XXXXXXX 或提交申诉`。**错误码与密码错误不同**，前端据此显示不同界面。
3. **封禁写两张表**：`user_violation`（处罚记录）+ `app_user.status`（登录拦截依据）。**同一事务**。
4. **解封**：`app_user.status='normal'` + `user_violation.revoked_at=NOW()` + `revoke_reason`。
5. **已登录用户被封禁**：JWT 仍有效 → 在鉴权过滤器里**每次校验** `status`（用 Redis 缓存 `user:status:{id}`，TTL 60s，封禁时删除缓存）。**不能等 token 过期**。
6. **律师违规分级**（技术路线 §16.5）：`warning`（警告）/ `restrict`（限流）/ `suspend`（暂停接单）/ `ban`（清退）。**本段只写记录与状态**，限流与暂停接单的**执行**由段 2/5 的业务逻辑读 `user_violation` 判定（本段在 §九 登记「段 2/5 需读违规状态」）。

**产出**：4 个接口（3 复用 + 1 新增）；`user_violation` 表（V700）；审计动作码复用 `USER_BAN`/`USER_UNBAN`/`LAWYER_VIOLATION`。

**算做完**

- [ ] 封禁一个用户 → 该用户**立即**（≤ 60s，缓存过期）无法调用任何需登录接口；重新登录返回封禁提示，**提示文本与密码错误不同**
- [ ] `7-A4` 点名要求「提示明确（含申诉入口或客服电话）」→ 提示文案里有客服电话
- [ ] 解封后能正常登录
- [ ] 律师违规 4 个级别都能写进 `user_violation`，且 A-05 律师详情的违规 tab 能查到（段 1 页面，本段只保证数据可查）
- [ ] 封禁/解封各有 1 条 `operation_log`，`detail` 含 before/after

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 鉴权过滤器是段 1 的，本段不好改 | 用**独立 Filter**（`UserStatusCheckFilter`）插在 JWT 过滤器之后，不改段 1 的类；登记「段 8 收口时合并」 |
| 账户域没有「已封禁」错误码 | 本段补 `420014` 并回写《错误码规范》§3.2；避免占用运营域 |

#### B5 · 频率限制与账号风控

**做什么**：技术路线 §16.3 的「异常注册 / 刷单 / 盗号」三条最小可执行规则。**不做黑产对抗，只做阈值拦截。**

**怎么做**

1. **限流**（接口规范 §6.5 已有基础限流，本段补运营侧配置化）：
   - 按 `sys_config` 配置：`risk.sms_per_minute=1`、`risk.login_fail_lock_count=5`、`risk.login_fail_lock_minutes=30`、`risk.ai_rounds_per_day=50`
   - 实现用 Redis `INCR + EXPIRE`；超限返回 `429` + 对应错误码
2. **异常注册检测**（最小版）：同 IP 24 小时内注册 > 3 个账号 → 第 4 个起要求图形验证码（本段只**记录**并写 `user_violation(level='restrict')`，**不硬拦**）；同设备（`device_id`）24 小时 > 2 个账号 → 同上。
3. **登录失败锁定**：同账号连续失败 5 次 → 锁 30 分钟（Redis key `login:fail:{account}`）。段 1 可能已实现，**本段只做配置化与日志**，不重复实现（若段 1 没有则本段补）。
4. **异常登录提醒**：同账号 IP 变化 + 设备变化 → 写 `login_log`（已有）+ 发站内信「检测到新设备登录」。
5. **黑产设备标记**：`user_device` 有 `push_cid`，本段不做设备指纹（技术路线提到但**无基建**），记 §七。
6. **所有阈值进 `sys_config`**，后台 A-08 可改，**改完即时生效**（每次读配置，不做本地缓存或 TTL ≤ 60s）。

**产出**：`RiskControlService`（限流 + 3 类检测）；`sys_config` 新增 6 个键；`login_log` 增加失败原因枚举（不改表）。

**算做完**

- [ ] 同 IP 24 小时内注册第 4 个账号 → 写 `user_violation(level='restrict')`，日志有记录，注册仍成功（只记录不拦）
- [ ] 连续 5 次密码错误 → 第 6 次返回「账号已锁定 30 分钟」，等待期可配
- [ ] 改 `risk.sms_per_minute` 为 10 → 立即生效（不需重启）
- [ ] 新设备登录 → 站内信有 1 条

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 段 1 已实现登录锁定但阈值写死 | 本段只**读配置**并把写死值挪到配置；若段 1 未交付，本段实现全量 |
| 设备指纹无基建 | 只做 `device_id` 维度（`user_device` 已有），不做指纹 |

#### B6 · PC A-20 内容审核队列 + A-25 敏感词管理（2 页）

**做什么**：把 B1~B4 的接口接到设计稿上。**这是 `7-A2` / `7-A3` 的验收界面。**

**怎么做**

1. **A-20**（`19-review-queue.html`，418 行）：左栏命中记录列表（可筛 `status`/`type`/`level`），右栏详情（命中词高亮 + `hit_snippet` + 上下文列表 + 三个处置按钮）。点「忽略（误判）」→ 调 `audit(ignore)` 并**刷新左栏**；点「警告用户」「封禁」→ 弹确认框（封禁必须填原因与期限）。
   - **原文展示区域要有一行常驻说明**：「原文完整保留，此处只做标注，平台不改写、不替换」——这句是稿面精神，也是 `7-A2` 的自证
2. **A-25**（`24-sensitive-word.html`，391 行）：左栏词库表格（含「命中」「最近命中」两列）+ 筛选（级别/语言/分类/关键词）+ 新增/编辑抽屉 + 批量导入（文件上传）+ 导出；右栏申诉复核（列表 + 通过申诉/维持原判）。
3. **技术实现约束**（AGENTS.md §3.3）：页面放 `src/views/biz/ops/`，API 放 `src/api/biz/ops/`；**不得修改 RuoYi 框架层**。
4. **i18n**：PC 后台按 RuoYi 的 `$t()` 走（与 APP 的 i18n 是两条线，见总纲 §7.1）。本段 PC 只做中文。

**产出**：`src/views/biz/ops/ReviewQueue.vue` / `SensitiveWord.vue` + 对应 API 文件。

**算做完**

- [ ] 两页在 1440×900 下与设计稿逐区块对齐（页头/筛选区/表格列/按钮位置/空态文案）
- [ ] A-20：点「忽略」→ 列表里该条消失（`status` 筛选为 pending 时）、`review_result` 变 `ignored`
- [ ] A-25：新增一个词 → 左栏出现 → **不发版、不重启**，APP 侧下一次发言即按新词判定（`7-A3` 的界面侧证据）
- [ ] A-25：右栏申诉复核能通过/维持，通过后该条从 A-20 队列消失
- [ ] 两页都无 `console.error`；表格分页、排序、筛选组合正常

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| Element Plus 版本差异导致表格列宽不对 | 以设计稿视觉为准调 `min-width`，**不改公共 `tokens.css`**；差异记 §十一 |
| 图表库未引入（A-02 需要） | A-02 用 `echarts`（登记依赖清单）；A-20/A-25 不需要图表 |

---

### C 组：工单与客服（5 个任务）

#### C1 · `service_ticket` 补字段与状态机

**做什么**：把工单从「9 字段的骨架」补成能支撑 A-21/A-22 的实体，并定死 4 态 8 条迁移。

**怎么做**

1. **补 4 字段**（裁决 6）：`ticket_no` / `title` / `priority` / `sla_due_at`。`sla_due_at` 在**创建时算好落库**（`created_at + ticket.sla_hours`），不现算——理由：要能加索引、要被超时扫描任务用。
2. **状态机**（4 态）：

   | from | to | 触发 | 谁 | 校验 |
   |---|---|---|---|---|
   | `waiting` | `processing` | 分配 / 首次回复 | 管理员 | `agent_id` 必须非空 |
   | `processing` | `replied` | 回复用户 | 管理员 | 回复内容非空 |
   | `replied` | `closed` | 关闭 | 管理员 | `close_reason` 必填 |
   | `waiting` | `closed` | 直接关闭（无效工单） | 管理员 | `close_reason` 必填 |
   | `processing` | `closed` | 关闭 | 管理员 | `close_reason` 必填 |
   | `closed` | — | **不可回退** | — | 任何 reopen 返回 `410012` |

3. **`ticket_no` 生成**：`TK` + `yyyyMMdd` + 6 位序号（`BizNoGenerator`）。
4. **超时判定**：`sla_due_at < NOW() AND status IN ('waiting','processing')` → **超时**。**不加 `overtime` 字段**（§二.3 第 16 项）：列表查询时实时判定 + `idx_ticket_sla (status, sla_due_at)` 支撑。
5. **超时扫描任务**：`task_queue` 每小时整点，扫出超时且未提醒过的工单（Redis `SETNX ticket:overtime:notified:{id}` 去重），发钉钉告警（连 `7-A8` 通道）+ 写 `notification` 给管理员。
6. **`title` 从哪来**：投诉 → `complaint.reason` 前 50 字；反馈 → `feedback.content` 前 50 字；客服留言 → 用户填的标题。

**产出**：`V700` 的 `service_ticket` 改版；`TicketStatus` 枚举；`TicketStateMachine`（服务端校验，客户端只发意图）；`task_queue` handler `TicketSlaScanHandler`。

**算做完**

- [ ] 建一条工单 → `ticket_no` 格式 `TK20261102xxxxxx`、`sla_due_at` = 创建 + 24h
- [ ] 非法迁移（如 `closed → processing`）→ `410012`，库里状态不变
- [ ] 把某工单 `sla_due_at` 改到昨天 → 列表里标红、`7-A8` 的钉钉收到超时告警
- [ ] 扫描任务重复跑不重复告警（Redis 去重生效）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `task_queue` 未交付（段 2 承诺） | 本段用 Spring `@Scheduled` 临时代替，登记「段 2 交付后迁移到 `task_queue`」 |
| 24 小时 SLA 对某些类型太短 | 按 `category` 分档（投诉 24h / 反馈 72h），配置在 `sys_config.ticket.sla_hours_by_category`（JSON）。**默认全 24h**，分档不做也合规 |

#### C2 · 投诉 / 反馈落单 → 生成工单

**做什么**：把 APP 的「投诉」「意见反馈」两个提交接口做成「写原表 + 生成工单」两步（裁决 23）。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 | 主要错误码 |
|---|---|---|---|---|
| 1 | `POST /app/complaints` | 提交投诉（已有清单） | App JWT | `410006`（内容被拦） |
| 2 | `POST /app/feedback` | 提交反馈（已有清单） | App JWT | `410006` |
| 3 | `GET /app/complaints` | 我的投诉列表（**补登**，APP 57 提交后要能回看） | App JWT | — |
| 4 | `GET /app/feedback` | 我的反馈列表（**补登**） | App JWT | — |

**关键实现**

1. **投诉提交**（对照 APP 57 稿）：
   - 入参：`targetType`（`lawyer`/`service`/`order`）、`targetId`（**律师问题必须关联已完成订单**，稿面明写）、`reason`（≤500 字）、`images`（≤3 张，先走 `POST /app/upload/token` 直传得 `oss_key`）
   - 校验：`targetType='lawyer'` 时 `targetId` 必须是「当前用户的、状态为已完成的订单」，否则 400
   - 落库 `complaint`（`status='pending'`）+ **同事务**生成 `service_ticket`（`category='complaint'`，`title`=reason 前 50 字，`sla_due_at`=+24h）
   - 内容过 A3 的 `ContentReviewService.review()`：`block` → 拒绝提交（`410006`），**提示「如认为误判，点此申诉」**
2. **反馈提交**（对照 APP 58 稿）：`type`（4 类：`feature`/`bug`/`experience`/`other`）、`content`（**选填**，稿面「选填，填写有助于更快处理」）、`images`（≤3 选填）、`contact`（选填手机号）
   - `content` 为空时 `title` 用 `feedback.type` 的中文标签
   - 同样生成 `service_ticket`（`category='feedback'`，SLA 24h）
3. **两个列表接口**：按时间倒序，返回 `{id, status, createdAt, replyContent, replyAt}`；**用户看得到回复**（`7-A5` 要求「App 能看到回复与状态变化」）。
   - 回复落哪：`service_ticket` 需要一个「回复内容」承载。**不新增字段**——用 `notification`（`type='system'`，`biz_type='ticket'`，`biz_id=ticketId`）承载回复正文，列表 join 取最新一条。**理由**：`service_ticket` 已有 13 字段，再加 `reply_content` 会让「一条工单多次回复」退化成单次；`notification` 天然支持多条与已读状态。

**产出**：2 个补登的 GET 接口 + 投诉/反馈提交改造（含工单生成）；`ComplaintService` / `FeedbackService`。

**算做完**

- [ ] APP 提交一条投诉（关联一笔已完成订单）→ `complaint` 1 条 + `service_ticket` 1 条，`ticket_no` 已生成
- [ ] 「律师问题」不传订单 id → 400 且给可读原因
- [ ] 提交含 `block` 级词 → 被拦、**原文不落库到 complaint**（因为整条拒绝），提示里有申诉入口
- [ ] APP 投诉列表能看到刚提交的那条，状态 `pending`
- [ ] 后台回复后，APP 列表能看到回复内容与状态变化（`7-A5` 的前半段）
- [ ] 上传 4 张图 → 400（最多 3 张）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| OSS 直传未就绪（段 0/2 承诺） | 本段退化为本地存储（`file_record` + 本机目录），接口形状不变；登记挂账 |
| 「必须关联已完成订单」导致用户投诉无门 | 稿面已给出口：「不涉具体订单的意见请走意见反馈」。**照稿实现**，不放松 |

#### C3 · PC A-21 工单列表

**做什么**：`20-ticket-list.html`（405 行）落地。

**怎么做**

1. **两个 Tab**（稿面：「处理」/「查看」）：`处理` = `status IN (waiting, processing)`；`查看` = `status IN (replied, closed)`。**这是前后端约定，不新增字段**。
2. **筛选区**（稿面 label：工单 / 类型 / 处理人）：工单号或标题关键词、类型（投诉/反馈/客服）、处理人（下拉，只有一个管理员时就是「我」）。
3. **表格列**（稿面表头）：工单号 / 类型 / 标题 / 优先级 / 状态 / 提交时间 / 处理人 / 操作。
   - **超时标红**：`sla_due_at < NOW() AND status IN (waiting,processing)` 时整行标红（稿面「超时工单会在列表里标红」）
   - 状态列用 tag 颜色区分
4. **按钮**（稿面）：导出工单 / 查询 / 重置 / 批量分配 / 分配 / 关闭。
   - **批量分配**（§二.3 第 2 项）：批量把 `agent_id` 置为当前管理员（只有一个角色），按钮保留
   - **关闭**（§二.3 第 3 项）：弹框必填 `close_reason`，提交后自动发站内信给用户
5. **分页**：`pageNum/pageSize`，默认 10。
6. **导出**：`GET /admin/tickets/export`（新增接口，列同表格 + `sla_due_at`），审计 `LOG_EXPORT`。

**产出**：`src/views/biz/ops/TicketList.vue`；接口复用 C1/C2 + 新增 `GET /admin/tickets/export`。

**算做完**

- [ ] 两个 Tab 的数据切分正确（处理中 vs 已结束）
- [ ] 超时工单标红，且 A-02「待我处理」的「投诉工单待处理 5」与列表数一致
- [ ] 批量分配 3 条 → `agent_id` 全变当前管理员，`operation_log` 有 3 条 `TICKET_ASSIGN`
- [ ] 关闭工单必须填原因，不填报错；关闭后用户收到站内信
- [ ] 导出 CSV 列齐全、中文不乱码

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 「处理人」下拉只有一个选项（单管理员） | 照做，下拉不隐藏；将来加客服角色时自动生效 |

#### C4 · PC A-22 工单详情

**做什么**：`21-ticket-detail.html`（297 行）落地。

**怎么做**

1. **左栏**（稿面）：工单正文 + 处理记录（时间线）+ 回复框（placeholder「写给用户的回复，通过站内信与 App 推送送达」）+ 三个按钮：存为模板 / 发送回复 / 关闭工单。
2. **右栏**（稿面）：提交人（`U-10284 · 138****6621`，**脱敏**）/ 关联记录（订单号 / 律师 / 会话）/ 工单属性（类型、优先级、SLA、创建时间、处理人）。
3. **「存为模板」**：把本条回复存进 `notify_template`（`template_code` 自动生成 `ticket_reply_{n}`，`channel='inapp'`），供下次选用。**不新增表**。
4. **「发送回复」**：写 `notification`（`type='system'`，`biz_type='ticket'`）+ 若有 `user_device.push_cid` 则调个推；`service_ticket.status` → `replied`；`agent_id` 为空时置当前管理员。**审计 `TICKET_REPLY`**（本段第 13 个动作码，C 组补登）。
5. **「关闭工单」**：必填原因 → `status='closed'`、`closed_at=NOW()`。
6. **超时提示**（稿面：「已超时 26 小时未受理」）：服务端返回 `overtimeHours` 计算值。
7. **关联记录**：`category='complaint'` → 显示 `complaint.target_type` + `target_id`（律师名 / 订单号）；`category='feedback'` → 显示反馈类型。**会话内容不显示**（稿面「会话内容需在内容审核页查看」——照这句实现，给一个跳到 A-20 的链接）。

**产出**：`src/views/biz/ops/TicketDetail.vue`；接口 `GET /admin/tickets/{id}`（已有）+ 回复/关闭（已有）+ `GET /admin/tickets/{id}/timeline`（**新增**，处理记录时间线）。

**算做完**

- [ ] 详情页四块（正文/记录/回复/属性）与稿一致，提交人手机号脱敏
- [ ] 发送回复 → APP 侧投诉/反馈列表能看到（`7-A5` 后半段）、`notification` 有 1 条
- [ ] 「存为模板」后 A-24 通知模板列表里出现该模板
- [ ] 关闭后不能再回复（`410012`）
- [ ] 超时工单详情页显示「已超时 X 小时」

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 「处理记录」无表可落 | **不加表**：用 `operation_log`（`target_type='TICKET'`）当时间线数据源，`GET /timeline` 就是查它。审计表天然是处理记录 |
| APP 侧收不到回复 | 个推未就绪时至少保证站内信（`notification`）能查到；推送记挂账 |

#### C5 · APP 客服中心 / FAQ / 投诉 / 反馈（4 页）

**做什么**：`55-support.html` / `56-faq.html` / `57-complaint.html` / `58-feedback.html` 四页落地。

**怎么做**

| 页 | 稿面要点 | 接口 | 实现要点 |
|---|---|---|---|
| **55 客服中心**（397 行） | 「大家常问」四分类（账号/支付/律师/立案）入口 + 人工客服 9:00–20:00 + 电话 | `GET /pub/faq?category=` + `GET /pub/app/config`（客服时段与电话，**新增**） | 非时段（>20:00 或 <9:00）切「留言」形态，按钮文案变「留言，次日回复」；电话用 `Linking.openURL('tel:...')` |
| **56 常见问题**（383 行） | 7 条可折叠问答 + 「没找到答案？」→ 客服中心 | `GET /pub/faq` | 折叠用 `Accordion`；答案支持 `\n` 换行；空态文案照稿 |
| **57 投诉**（369 行） | 类型三选（律师/服务/订单）+ 经过与诉求 + 截图 ≤3 + 「3 个工作日内核实」+「会同步给被投诉的律师」 | `POST /app/complaints` + `GET /app/complaints` | 选「律师问题」时**必须**选一笔已完成订单（稿面明写）；图片压缩后上传；提交成功跳「已提交」态并给单号 |
| **58 反馈**（302 行） | 四类型 + 具体情况（**选填**）+ 截图选填 + 联系方式选填 | `POST /app/feedback` | 「具体情况」可空；空时提交按钮仍可用；联系方式必须校验手机号格式（若填） |

**关键实现**

1. **图片上传三段式**（接口规范 §6.6）：`POST /app/upload/token` → 直传 → 提交时传 `oss_key` 数组。最多 3 张，单张 ≤ 5MB，只接受 jpg/png。
2. **i18n**：四页所有文案走 `t()`，key 前缀 `ops.support.*` / `ops.faq.*` / `ops.complaint.*` / `ops.feedback.*`。**只做 `zh-Hans`**，缺译登记 `.i18n-pending.json`。
3. **四态**：每页都要有 正常 / 加载 / 空 / 错误 四态（AGENTS.md §7）。FAQ 空态、投诉列表空态、提交失败态都要画出来。
4. **RTL**：`letterSpacing: 0`、用 `start`/`end`（段 0 地基已定，本段照办）。

**产出**：`features/ops/pages/SupportPage.tsx` / `FaqPage.tsx` / `ComplaintPage.tsx` / `FeedbackPage.tsx` + 路由壳 + API 层 + `ops.*` i18n 约 40 个 key。

**算做完**

- [ ] 四页在真机上四态全走一遍（断网看错误态、空数据看空态）
- [ ] 56 页 7 条问答从 `faq` 表读出来（**不是写死在前端**）——改库里的答案，页面跟着变
- [ ] 57 页提交成功 → 后台 A-21 出现工单（`7-A5` 的起点）
- [ ] 58 页「具体情况」留空也能提交成功
- [ ] 非客服时段进入 55 页 → 显示留言形态
- [ ] 四页无裸 `<Text>`（全部走 `<LocalizedText>`）、无 `rgba()` / `#RRGGBB` 字面量

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 图片上传 OSS 未就绪 | 退化到本地上传（服务端存 `E:\faze-upload`），接口形状不变 |
| 电话号未定 | 用 `sys_config.service.phone` 占位 `0999-XXXXXXX`，**页面不写死**；记 §十 |

### D 组：消息与公告（5 个任务）

#### D1 · 公告批次表与公告接口（5 个）

**做什么**：`announcement` 表（第 55 张，裁决 5）+ A-23 的 5 个接口（含预计触达 `estimate`）。**这是「触达 / 打开率」唯一能落地的方案**（冲突 3）。

**怎么做**

1. **表**：见 A1 第 2 步的 DDL。一条公告一行；`reach_count` / `open_count` 在这张表上累计。
2. **接口 4 个**：

   | # | 方法 + 路径 | 用途 | 鉴权 | 主要错误码 |
   |---|---|---|---|---|
   | 1 | `GET /admin/announcements?status=&beginTime=&endTime=&pageNum=&pageSize=` | 公告列表（含触达/打开率） | PC token | — |
   | 2 | `POST /admin/announcements` | 新建（草稿或定时） | PC token | — |
   | 3 | `GET /admin/announcements/{id}` | 详情（含目标人群解析结果） | PC token | — |
   | 4 | `POST /admin/announcements/{id}/push` | 立即发送（草稿→发送）/ 取消定时 | PC token | `410013` |

3. **`target_type` 解析**（`target_value` JSON）：
   - `all` → 全量用户（`app_user.status='normal'`）
   - `role` → `target_value = {"role":"lawyer"|"user"}`
   - `region` → `target_value = {"regions":["伊宁市","奎屯市"]}`
   - `user_ids` → `target_value = {"userIds":[...]}`，**上限 5000**
   - **发送前必须返回「预计触达 N 人」**（稿面「预计触达 1,284 人」）：`POST /admin/announcements` 的响应里带 `estimatedReach`；前端在建公告时先调一次 `GET /admin/announcements/estimate?targetType=&targetValue=`（**第 5 个接口**，补登）
4. **`channels`**：`["inapp"]` 必选；`push` 可选（走段 2 个推）；`sms` 可选但**本段只存配置不改**（裁决 22：短信仅资金/服务中断时用，本段不接服务商）。
5. **状态流转**：`draft` →（立即发送）`sent`；`draft` →（定时）`scheduled` → 到点 `sending` → `sent`/`failed`。**`sent` 不可回退**（裁决 26、稿面「公告发出后不可撤回」）→ 尝试撤回返回 `410013`。
6. **审计**：`ANNOUNCEMENT_CREATE` / `ANNOUNCEMENT_PUSH`。

**产出**：`announcement` 表（V701）；`AnnouncementController` + 5 个接口；`AnnouncementStatus` 枚举。

**算做完**

- [ ] 新建一条「全量 + 站内信」公告 → 返回 `estimatedReach` 与 `app_user` 总数一致（测试库 10 个用户就是 10）
- [ ] 点「立即发送」→ `status='sent'`、`sent_at` 有值、`reach_count` = 实际写入的 `notification` 条数
- [ ] 对已发送公告再点发送/撤回 → `410013`
- [ ] 定时公告到点自动发送（把 `scheduled_at` 设为 1 分钟后，等它自己跑）
- [ ] 目标人群 `region=["伊宁市"]` → 只给该地区用户发（造 2 个不同地区用户验证）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 全量用户发送 1 万条超时 | 分批（每批 500）走 `task_queue`，`status='sending'` 期间允许查询进度；`reach_count` 实时累加 |
| 段 2 个推未就绪 | `push` 渠道置灰（前端），后端记录「push 未投递」，`reach_count` 只算站内信；记 §十一 |

#### D2 · 通知模板接口（5 个）

**做什么**：A-24 的模板增删改查 + 变量校验 + 测试发送。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 | 主要错误码 |
|---|---|---|---|---|
| 1 | `GET /admin/notify-templates?channel=&status=&kw=&pageNum=&pageSize=` | 模板列表（含变量解析） | PC token | — |
| 2 | `POST /admin/notify-templates` | 新建 | PC token | `410005`（编码+语言重复） |
| 3 | `PUT /admin/notify-templates/{id}` | 修改 | PC token | — |
| 4 | `DELETE /admin/notify-templates/{id}` | 删除（逻辑：`status='disabled'`） | PC token | — |
| 5 | `POST /admin/notify-templates/{id}/test` | 测试发送（发给自己站内信） | PC token | — |

**关键实现**

1. **变量规则**（稿面「模板变量形如 `{{变量名}}`」）：正则 `\{\{([a-zA-Z][a-zA-Z0-9]*)\}\}` 提取变量名。
   - **禁位置参数**（i18n 规范已定），只认具名变量，命名 camelCase
   - 保存时校验：`title` / `content` 里的变量集合要一致（有一处缺另一个就报错）
   - 列表返回 `variables` 数组，前端显示为 tag（稿面「变量」列）
2. **唯一性**：`uk_notify_tpl (template_code, lang)`（表已有）。重复 → `410005`。
3. **删除**：**不物理删**（因为已被 `notification` 引用的模板删了会断链）。改成 `status='disabled'`。技术路线没规定，本段按「软停用」处理并记 §十一。
4. **测试发送**：用当前登录管理员的 `user_id` 渲染模板 → 写一条 `notification` → 响应「已发到你的消息中心」。

**产出**：`NotifyTemplateController` + 5 个接口；变量解析工具 `TemplateVariableParser`。

**算做完**

- [ ] 新建模板 `{code:'test_tpl', lang:'zh-Hans', title:'您好 {{userName}}', content:'您的订单 {{orderNo}} 已完成'}` → 变量解析出 `userName`、`orderNo`
- [ ] 标题有 `{{a}}`、正文没有 → 保存被拒，提示「标题与正文变量不一致」
- [ ] 同 code + lang 再建 → `410005`
- [ ] 测试发送 → 消息中心收到一条按模板渲染的通知
- [ ] 删除 → 列表里 `status` 变 `disabled`，已发出的历史通知不受影响

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 变量值从哪来（渲染时） | 本段只做模板管理与测试发送（变量值由测试数据填充）；**真正的业务发送由各业务段自己渲染**，本段在 §八.1 承诺「模板 CRUD 与变量语法稳定」 |

#### D3 · 站内信 C 端接口（3 个）

**做什么**：`notification` 的读接口。**段 2 应已交付消息中心**，本段只补「公告类通知」与「已读/未读数」的差异点。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 |
|---|---|---|---|
| 1 | `GET /app/notifications?cursor=&type=` | 消息列表（cursor 分页，段 2 已定） | App JWT |
| 2 | `POST /app/notifications/{id}/read` | 标记已读（**公告的打开率在这里 +1**） | App JWT |
| 3 | `GET /app/notifications/unread-count` | 未读数（tab 角标） | App JWT |

**关键实现**

1. **归属校验**：`notification.user_id` 必须 = 当前用户（IDOR）。
2. **打开率埋点**（§二.3 第 13 项）：`read` 时若该 `notification.biz_type='announcement'` 且原来 `is_read=0` → `UPDATE announcement SET open_count=open_count+1 WHERE id=biz_id`。**只在首次已读时加**。
3. **段 2 已交付时的处理**：若段 2 已有这些接口，本段**不重写**，只补「公告 → `open_count`」这一处逻辑；在 §九 登记「段 2 的 read 接口需要加一行计数」。

**产出**：3 个接口（或 1 处改动，取决于段 2 交付情况）。

**算做完**

- [ ] 列表分页正常、`is_read` 状态正确
- [ ] 标记已读后未读数 -1
- [ ] 点开一条公告 → `announcement.open_count` +1；重复点开不再 +1
- [ ] 用别人 token 读别人的通知 → 403

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 段 2 接口与本段约定不一致 | **以段 2 为准**（它先交付），本段只在段 2 的 read 接口里加计数逻辑；差异记 §十一 |

#### D4 · PC A-23 公告与推送 + A-24 通知模板（2 页）

**做什么**：`22-announcement.html`（282 行）+ `23-notify-template.html`（355 行）落地。

**怎么做**

1. **A-23**：左侧「新建公告」表单（公告标题 / 目标人群 / 发送时间 / 三个渠道勾选 / 公告正文）+ 右侧「已发公告」表格（标题/目标/渠道/发送时间/触达/打开率/状态）。
   - 渠道勾选带稿面说明：「站内信 必选」「App 推送 走个推通道，用户未开启通知权限时收不到」「短信 仅在涉及资金或服务中断时使用，按条计费」——**三条说明照抄到页面**
   - 「预计触达 1,284 人」实时计算（目标人群变化时重算）
   - 两个按钮：「存为草稿」「立即发送」；**「立即发送」要二次确认**（稿面「公告发出后不可撤回」）
2. **A-24**：模板列表（模板编码/名称/渠道/内容预览/变量/状态/更新时间/操作）+ 新建/编辑抽屉 + 测试。
   - 「变量」列用 tag 展示（`{{userName}}` → `userName`）
   - 内容预览截断 60 字
3. **技术实现**：`src/views/biz/ops/Announcement.vue` / `NotifyTemplate.vue`。

**产出**：2 个 Vue 页面 + 2 个 API 文件。

**算做完**

- [ ] A-23 五块区域与稿一致；渠道三条说明在位
- [ ] 目标人群改成「律师」→ 预计触达人数立即变化
- [ ] 「立即发送」有二次确认弹框，文案含「不可撤回」
- [ ] A-24 新建模板 → 列表出现，变量 tag 正确
- [ ] 两页分页、筛选、表单校验（标题必填、正文必填）正常

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 「预计触达」每次改人群都请求太频繁 | 加 300ms 防抖；不做本地估算（口径只有服务端一份） |
| Element Plus 没有合适的 tag 展示变量 | 用 `el-tag`，样式在页面 `<style>` 内，不改公共文件 |

#### D5 · 公告发送链路与统计回写

**做什么**：把「公告发送」做成一條可靠链路：落 `notification` → 推送 → 计数 → 失败可重试。

**怎么做**

1. **发送流程**（`status: scheduled/sent → sending → sent/failed`）：
   ```
   1. 解析目标人群 → 得到 userId 列表（分批 500）
   2. 每批：批量 INSERT notification（type='system', biz_type='announcement', biz_id=announcementId）
   3. 每批：reach_count += 本批成功条数（同一事务）
   4. push 渠道：按 user_device.push_cid 调个推（异步，失败不阻断）
   5. 全部完成 → status='sent', sent_at=NOW()
   6. 任一批失败 → status='failed'，已发的保留（不回滚！），失败批号写日志
   ```
   **关键：已发出去的通知不回滚**。「部分成功」比「全员没收到然后重发」好。
2. **幂等**：同一 `announcement_id` + 同一用户只插一条 `notification`（`uk` 不存在，用 `SELECT ... WHERE biz_id=? AND user_id=?` 前置判断 + 唯一索引补 `(user_id, biz_type, biz_id)`？**不加索引**，改用 Redis `SETNX announcement:{id}:user:{userId}` TTL 7 天）。
3. **打开率**：D3 已定（首次已读 +1）。
4. **重试**：`failed` 状态允许再次「发送」（**只补发失败的批次**，用 Redis 记录已发用户）。

**产出**：`AnnouncementSendService`；`task_queue` handler `AnnouncementSendHandler`；Redis 幂等键。

**算做完**

- [ ] 发一条全量公告（10 个测试用户）→ `notification` 10 条、`reach_count=10`
- [ ] 重复点「发送」→ 不重复插入（`notification` 仍是 10 条）
- [ ] 造一个用户插入失败（如 `user_id` 不存在）→ 其余 9 条成功、`status='failed'`、**已发的 9 条没被回滚**
- [ ] 点重试 → 只补那 1 条，最终 10 条
- [ ] 用户 A 点开公告 → `open_count=1`；再点 → 仍是 1

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 大公告（10 万用户）发送耗时长 | 走 `task_queue` 异步，页面显示 `sending` + 已发进度（`reach_count` 实时涨）；**不允许同步接口等 10 万条** |
| 个推批量接口有限流 | 按 100/批 + 200ms 间隔；推送失败只记日志不阻断站内信 |

---

### E 组：APP 设置与账户收口（5 个任务）

#### E1 · 设置页（`41-settings.html`，426 行）

**做什么**：设置页六块（账号与安全 / 通用 / 关于 / 字体大小 / 深色模式 / 清除缓存 / 退出登录）全部落地。**大部分是本地能力，不新建接口。**

**怎么做**

| 块 | 内容 | 存哪 | 接口 |
|---|---|---|---|
| 账号与安全 | 跳 42 账号与安全页（段 1 已交付） | — | 无 |
| 字体大小 | 四档（小/标准/大/特大），**用真实字号渲染**（稿内注释明确要求） | `AsyncStorage settings.fontScale` | 无 |
| 深色模式 | 三选（跟随系统/开/关） | `AsyncStorage settings.theme` | 无（裁决：不同步服务端） |
| 清除缓存 | 显示当前缓存大小（如 12.4 MB），点击确认清除 | 本地计算 | 无 |
| 检查更新 | 显示当前版本 `v1.0.0`，点击检查 | `GET /pub/app/version`（清单已有） | 复用 |
| 关于 | 跳 59 关于页 | — | 无 |
| 退出登录 | 二次确认后登出 | `POST /app/account/logout`（段 1） | 复用 |

**关键实现**

1. **字体大小四档的真实渲染**：四档映射到 `tokens.fontSize` 的倍率（1.0 / 0.875 / 1.125 / 1.25）——**具体倍率写进 `shared/theme/fontScale.ts`**，四档选项本身用各自倍率渲染（稿内注释：让用户直接看到差别）。
2. **清除缓存说清「清什么」**（稿内注释原文要求）：确认框正文必须是「将清除图片与临时文件缓存，**不会删除你的账号、文书草稿和聊天记录**」。
3. **退出登录**复用 `05-profile` 的同一套属性名（稿内注释提到 `app.js` 已绑过）。
4. **检查更新**：`/pub/app/version` 返回 `{latestVersion, minVersion, downloadUrl, changelog}`；有新版本弹「发现新版本 vX.Y.Z」+ 「去更新」；低于 `minVersion` 弹强更（不可关）。**强更逻辑本段只做前端判断**，不做灰度。

**产出**：`features/settings/pages/SettingsPage.tsx`、`shared/theme/fontScale.ts`、`features/settings/storage.ts`（AsyncStorage 封装）。

**算做完**

- [ ] 字体大小切四档 → 设置页自身文字**立即按真实字号变化**（能看出差别）
- [ ] 深色模式三选切换即时生效，重启后保持
- [ ] 清除缓存：显示真实缓存大小 → 清除后变 0 → **SecureStore 里的 token 还在**（没被清掉）、草稿还在
- [ ] 检查更新：改后端版本号 → 前端弹出更新提示
- [ ] 退出登录 → 回登录页，token 清空

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 缓存大小算不准（RN 无统一 API） | 用 `expo-file-system` 遍历缓存目录求和，估算即可；显示「约 12 MB」 |
| 深色模式要改全局令牌（影响面大） | 本段只做**开关与持久化**，主题令牌替换在段 8 前统一做；`7-A1`~`7-A10` 都不验深色，记 §八.2 |

#### E2 · 企业认证页（`49-verify-company.html`，553 行）

**做什么**：四态（未认证 / 审核中 / 已通过 / 驳回）全落地，补 `GET /app/account/enterprise` 查状态接口。

**怎么做**

1. **补接口**（裁决 10）：`GET /app/account/enterprise` → `{status: none|pending|approved|rejected, rejectReason, submitted:{companyName, creditCode, legalPerson, licenseOssKey, submittedAt}}`。`POST /app/account/enterprise` 已有（清单 L「POST /app/account/enterprise」）。
2. **四态界面**（照稿）：
   - `none`：可填写（企业名称 / 统一社会信用代码 / 法定代表人 / 营业执照照片）
   - `pending`：只读展示 + 「已提交内容」+ 「一般 1 个工作日出结果」
   - `approved`：展示认证信息 + 「现在可以以该企业名义委托律师、开具企业抬头发票」
   - `rejected`：展示驳回原因 + 「重新提交」按钮（**不限次数**，稿面明写）
3. **底部按钮跟状态走**（稿内注释）：`none`→「提交审核」；`rejected`→「重新提交」；`pending`/`approved`→无按钮
4. **前置校验**：稿面「法定代表人 需已完成个人实名」→ 未实名时**禁用提交并提示**「请先完成实名认证」（跳 48 页）。
5. **信用代码校验**：18 位统一社会信用代码正则（`[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}`），前两位机构类型 + 校验位算法**本段只做长度与字符集校验**，不做校验位（避免误杀），记 §十一。
6. **照片上传**：`POST /app/upload/token` 三段式；**必填**（稿面「必填，还未上传 · 点击拍照」）；要求「拍整张执照，四角完整入框」——文案照抄。

**产出**：`features/account/pages/EnterpriseAuthPage.tsx` + `GET /app/account/enterprise` 接口。

**算做完**

- [ ] 四态在真机上都能看到（用改库 `enterprise_auth.status` 造四种状态）
- [ ] 未实名时提交按钮禁用并提示
- [ ] 提交后变 `pending`，只读展示已提交内容
- [ ] 驳回后能看到原因、能重新提交
- [ ] 营业执照必填，不传图点提交被拦

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `enterprise_auth` 表字段与接口要返回的不一致 | **以实际表为准**，接口返回能拿到的字段；缺的字段记 §十一，不擅自 `ALTER` 段 1 的表（除裁决明文） |
| 审核方（企业信用公示系统核验）没有 API | 纯人工审核（A-04 的 `type=enterprise`），稿面写的「正在核对公示系统」是文案，**不做真实对接**；记 §七 |

#### E3 · 我的收藏（`51-favorites.html`）+ 关注律师（`52-following.html`）

**做什么**：两页 + 4 个补登接口（裁决 9）。

**怎么做**

| # | 方法 + 路径 | 用途 | 鉴权 |
|---|---|---|---|
| 1 | `GET /app/social/favorites?targetType=article\|lawyer\|case&cursor=` | 收藏列表（跨域组装） | App JWT |
| 2 | `DELETE /app/social/favorites?targetType=&targetId=` | 取消收藏 | App JWT + 归属校验 |
| 3 | `GET /app/social/follows?cursor=` | 关注律师列表 | App JWT |
| 4 | `GET /app/social/follow-count` | 关注数（个人页角标） | App JWT |

**关键实现**

1. **跨域组装**（技术路线 §5.9.1 原文要求）：
   ```
   social_action 查 target_id 列表（本域单表查询）
     → 按 targetType 调对应域 Service 批量取（LawyerQueryService.batchGetByIds / ArticleQueryService / CaseQueryService）
     → 组装 VO
   ```
   **不许在 social 域写 join**（跨域读只走 Service 接口）。
2. **收藏页三 Tab**（稿面：文章 3 / 律师 2 / 案例 2）：Tab 上带数量，数量来自三个 `targetType` 的 `COUNT`（一次接口返回三个计数，避免三次请求）。
3. **左滑取消**（稿面注释：左滑把内容左移 84px）：用 `react-native-gesture-handler` 的 `Swipeable`；**不在静态稿里展开**（稿面注释明确说明）。
4. **关注页**（稿面注释）：三种状态要能区分——「在线 + 有新动态」「离线 + 回复过咨询」「长期未活跃：明确写出来」。数据来自 `lawyer` 的 `online_status` + 最近活跃时间（段 2/5 已有字段）。
5. **空态**（稿面）：收藏空 →「还没有收藏」+ 说明 + 「前往首页」；关注空 →「还没有关注律师」+ 说明 + 「去找律师」。
6. **`social_action` 表段 2 建，本段只读写数据**（裁决 9）。

**产出**：`features/social/pages/FavoritesPage.tsx` / `FollowingPage.tsx`；4 个接口；`social` 的跨域组装 Service。

**算做完**

- [ ] 收藏 3 篇文章 + 2 个律师 + 2 个案例 → Tab 显示 `文章 3 / 律师 2 / 案例 2`，与稿面一致
- [ ] 左滑取消收藏 → 列表减少、Tab 数字减 1、`social_action` 对应行删除（或 `deleted_at` 置位）
- [ ] 关注页三种状态各造 1 个律师，显示不同
- [ ] 空态文案与稿一致
- [ ] 用别人 token 取消别人的收藏 → 403

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 段 2 未交付 `social_action` | **停**（硬前置第 3 项）。表没有，两页无数据源 |
| 跨域 Service 未提供批量取 | 本段按 `target_id` 逐个查（N+1），数据量小可接受；登记「段 8 前改批量」 |
| 左滑与列表滚动冲突 | 用 `Swipeable` 的 `simultaneousHandlers`，不做自定义手势 |

#### E4 · 关于法泽（`59-about.html`）+ 全部功能（`61-all-features.html`）

**做什么**：两页纯静态 + 一个版本接口复用。

**怎么做**

1. **59 关于**（331 行）：四块（法泽介绍 / 协议与说明 / 联系方式 / 版本号）。
   - **备案号占位**（§二.3 第 8 项）：`[待替换：ICP 备案号 / App 备案号]`，`AboutPage.tsx` 顶部注释写明「备案号下来必须改这一处」
   - 「协议与说明」链到段 1 的用户服务协议页、隐私政策页（已有页面）
   - 联系方式：客服电话从 `sys_config.service.phone` 读，**不写死**
2. **61 全部功能**（431 行）：四组入口（§二.3 第 9 项），纯前端路由聚合，**不新增接口**。
   - 找律师与咨询：律师列表 / AI 智能体 / 文章 / 案例库
   - 办案件：在线立案 / 我的案件 / 文书中心
   - 订单与钱：订单列表 / 余额 / 充值 / 发票说明
   - 我的与服务：设置 / 我的收藏 / 关注律师 / 客服中心 / 意见反馈 / 关于
   - **每个入口的目标页必须真实存在**（段 1~6 已交付的页面）；不存在的入口**不显示**（不做「敬请期待」）

**产出**：`features/settings/pages/AboutPage.tsx` / `AllFeaturesPage.tsx`。

**算做完**

- [ ] 59 页四块齐全，备案号是占位文案（**不是编造的号**），顶部有 TODO 注释
- [ ] 61 页四组共 ~18 个入口，**逐个点进去都能到真实页面**（无 404、无空壳）
- [ ] 两页无接口调用（除版本与配置），断网也能打开
- [ ] 客服电话改了 `sys_config` 后 59 页跟着变

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 某些入口的目标页属于未交付的段（如发票说明） | **不显示该入口**，不给「敬请期待」占位（避免假界面，总纲 §4.4 第 3 条） |

#### E5 · 版本检查与强更（`41b-version-update.html`）

**做什么**：`/pub/app/version` 的返回结构与强更判断。**这一屏不在总纲 §4.1 的 68 页里**（`41b` 是设计稿多画的），但 E1 的「检查更新」要用它。

**怎么做**

1. **接口**：`GET /pub/app/version?platform=android&currentVersion=1.0.0` → `{latestVersion, minVersion, downloadUrl, changelog, forceUpdate}`。
2. **`sys_config`**：`app.version.latest` / `app.version.min` / `app.version.download_url` / `app.version.changelog`（改这些不用发版）。
3. **判定**：`currentVersion < minVersion` → **强更**（弹窗不可关闭）；`< latestVersion` → 可跳过提示；相等 → 「已是最新版本」。
4. **`41b-version-update.html`**：这个界面按稿实现为**弹窗**（不是独立页），挂在 E1「检查更新」上。

**产出**：`GET /pub/app/version` 接口；`UpdateModal` 组件；4 个 `sys_config` 键。

**算做完**

- [ ] 改 `app.version.latest` 为 `1.0.1` → APP 检查更新弹提示
- [ ] 改 `minVersion` 为 `9.9.9` → 弹强更且无法关闭
- [ ] `sys_config` 不变时点检查 → 「已是最新版本」
- [ ] **`41b` 不新增第 69 页**（总纲页面归属不变），登记为弹窗

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 总纲 68 页里没有 41b | 按弹窗处理（**不开新页**），并在 §九 登记「设计稿多画一屏 41b，实现为弹窗」 |
| 真实热更新通道未打通（段 8 才做） | 本段只做「提示去下载」，不做应用内下载安装；记 §八.2 |

### F 组：看板与日志（4 个任务）

#### F1 · PC A-02 数据看板（`01-dashboard.html`，570 行）

**做什么**：把 A5 的 4 个接口接到稿面六块上。这是全项目**唯一一个「所有数字都要能自证口径」**的页面。

**怎么做**

1. **六块对应**（稿面结构）：

   | 稿面区块 | 数据来源 | 接口 |
   |---|---|---|
   | 页头（数据统计截至 …，每 10 分钟刷新） | `dataAsOf` 字段 | `summary` 响应里带 |
   | 核心指标 4 卡（今日活跃 / 今日新增 / 今日订单 / 平台今日收入） | `summary` | `GET /admin/dashboard/summary` |
   | 时间范围切换（今日 / 近 7 天 / 近 30 天） | 改 `range` 参数 | 同上 |
   | 订单量趋势（近 14 天折线） | `trends` | `GET /admin/dashboard/trends?metric=orders&range=14d` |
   | 用户地区分布（按注册地，前 5 + 其他） | `regions` | `GET /admin/dashboard/regions` |
   | 待我处理（5 类 + 「另有 N 条内容审核命中」） | `todos` | `GET /admin/dashboard/todos` |
   | 最近订单（4 条） | **复用段 5 的订单列表接口** | `GET /admin/orders?pageSize=4` |
   | 导出报表 | 新增 | `GET /admin/dashboard/export?range=` |

2. **四个卡片的副文案**（稿面）：每个卡片有「较昨日 +8.2%」这类环比。**涨跌颜色**：涨绿跌红（中国财务习惯与西方相反，**稿面用哪个跟稿**——需按稿确认，本段按稿：涨用红、跌用绿。若稿是反的，以稿为准）。
3. **「平台今日收入」的注解**（稿面「已扣除平台服务费」）：卡片下方必须显示这句，且鼠标悬停显示口径 tooltip：「= 当日订单服务费合计，不含充值金额」。
4. **「待我处理」每条可点**（§二.3 第 1 项）：按裁决的跳转目标带参数跳转。
5. **图表**：用 `echarts`（需登记依赖清单）。折线图照稿注释：「网格线 4 条水平线，最小 0 最大 45」「面积：品牌色 14% 透明，从折线到基线」「峰值点只标一个」「横轴隔天标一个日期」。
6. **导出报表**：CSV（指标 + 日期 + 数值），审计 `LOG_EXPORT`。
7. **刷新**：页面每 10 分钟自动刷新一次（稿面文案），用 `setInterval` + 页面失焦暂停。

**产出**：`src/views/biz/ops/Dashboard.vue`；接口 4 个（A5）+ 导出 1 个；`echarts` 依赖登记。

**算做完**

- [ ] 六块与稿逐块对齐；4 个卡片的数值与手算一致（`7-A10` 的界面侧）
- [ ] 切「今日/7天/30天」→ 4 个卡片与趋势图同时变
- [ ] 「平台今日收入」悬停能看到口径 tooltip
- [ ] 「待我处理」5 条各自跳到正确页面并带对参数
- [ ] 折线图 4 条网格线、峰值点唯一、横轴隔天（与稿注释逐条对上）
- [ ] 导出 CSV 内容与页面一致

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `echarts` 体积大（后台无妨） | 按需引入（`echarts/core` + 折线图 + 柱状图两个模块） |
| 稿面的涨跌配色与常识相反 | **以稿为准**；若稿有歧义，在 §十一 记「配色待负责人确认」，不自作主张 |
| 段 5 未交付 → 「最近订单」空 | 显示空态「暂无订单」，不造假 |

#### F2 · PC A-26 日志中心（`25-logs.html`，351 行）

**做什么**：两个 Tab（操作日志 / 登录日志）+ 查询 + 详情 + 导出。

**怎么做**

1. **两个 Tab**（稿面）：操作日志 / 登录日志。
2. **筛选区**：
   - 操作日志：操作人（`operatorId`）/ 模块 / 动作 / 时间范围（稿面 label 四项）
   - 登录日志：账号 / IP / 结果 / 时间范围
3. **表格列**（稿面操作日志表头）：时间 / 操作人 / 模块 / 动作 / 目标对象 / 来源 IP / 结果 / 操作（详情按钮）。
   - 「目标对象」要能显示成 `订单 OD20261030000123` 这种可读形式 → 服务端返回 `targetType` + `targetId` + `targetDisplay`（后端做 label 映射，**不在前端拼**）
4. **详情抽屉**：展示 `detail` JSON 的 `before`/`after`/`reason`，**格式化成对比表格**（不是裸 JSON）——这是稿面「详情」按钮的唯一用途。
5. **留存提示**：**稿面文案「操作日志保留 90 天」不能用**（低于合规下限 3 年，见裁决 16）。页面显示改为「操作日志按合规要求长期保留（≥ 3 年），登录日志保留 180 天」，天数从 `audit.operation-log-days` / `audit.login-log-days` 读。**这一处必须改稿**，记 §九 第 9 项。
6. **导出**：`GET /admin/operation-logs/export` / `GET /admin/login-logs/export`（A4 已定）。
7. **`7-A6` 的界面侧**：做一次手工充值 / 封禁后，在这里能查到「谁 / 何时 / 从哪 IP / 改了什么」四项齐全。

**产出**：`src/views/biz/ops/LogCenter.vue`。

**算做完**

- [ ] 两个 Tab 数据切分正确
- [ ] 做一次封禁 → 操作日志里能查到该条，详情抽屉显示 before/after 对比
- [ ] 「来源 IP」列非空；「目标对象」显示可读名称（不是裸 id）
- [ ] 改 `log.operation_retention_days=90` 为 `30` → 页面提示文案跟着变
- [ ] 导出两个日志各自成功、中文不乱码、导出动作本身被审计

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `targetDisplay` 映射要查多张表 | 只做**白名单映射**（订单/用户/工单/公告/律师），映射不到的直接显示 `targetType#id`；**不做通用反射** |

#### F3 · 看板口径文档与性能兜底

**做什么**：把 A5 的口径表写成**独立一页纸**《A-02 指标口径》（`7-A10` 的证据），并给将来的性能问题留好出口。

**怎么做**

1. **文档**：`docs/acceptance/seg7/A-02-指标口径.md`。每个指标一节：定义 / 算法（SQL）/ 数据来源 / 边界条件 / 已知局限。**10 个指标逐条**。
2. **代码里也要有**：每个指标的计算方法上方注释引用该文档的节号（`见 docs/acceptance/seg7/A-02-指标口径.md §3`）。**文档与代码要能互相找到**。
3. **性能兜底**：`summary` 的全表 `COUNT` 在 `legal_order` / `balance_flow` 上会随数据量线性变慢。本段在代码注释里写死升级路径：
   ```
   // 数据量 > 50 万行时改为预聚合表 dashboard_daily_stat（不在本段范围）
   // 触发条件：P95 > 800ms 或 单次查询 > 2s
   ```
4. **「口径待定」的出口**（总纲 §5.8 风险表原文）：若某个指标口径临时被推翻，页面显示「口径待定」而**不显示估算值**。本段实现为 `summary` 响应里允许 `value: null` + `pendingReason` 字段。

**产出**：`docs/acceptance/seg7/A-02-指标口径.md`；代码注释引用；`pendingReason` 支持。

**算做完**

- [ ] 口径文档 10 个指标齐全，每个都有 SQL 与数据来源
- [ ] 代码里每个指标的计算处都有文档节号引用
- [ ] 把某个指标置为 `null` + `pendingReason='口径待定'` → 页面显示「口径待定」而不是 0 或空白
- [ ] 用 `EXPLAIN` 记录 4 个接口的执行计划，存 `docs/acceptance/seg7/F3-explain.txt`

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 真实数据量下查询慢 | 先加 Redis 10 分钟缓存（A5 已定）；仍慢则退化为「前一小时快照」，并在页面角落标注「数据截至 HH:00」 |

#### F4 · 导出能力统一

**做什么**：本段有 5 处导出（词库 / 日志 ×2 / 工单 / 看板）。**统一成一套实现**，避免五份重复代码。

**怎么做**

1. **统一工具**：`common/export/CsvExporter.java`。签名 `export(String fileName, List<String> headers, List<List<Object>> rows)`。
   - **UTF-8 with BOM**（Excel 兼容，审计规范 §6.3 已定）
   - 字段值含逗号 / 引号 / 换行时按 RFC 4180 转义
   - 单元格以 `=` `+` `-` `@` 开头时**前置单引号**（防 CSV 注入）
2. **同步 / 异步分界**（审计规范 §6.3）：≤ 5000 行同步返回；> 5000 行写 `task_queue`，完成后写 `notification` 给管理员并给下载链接。超限时接口返回 `410015` + `taskId`。
3. **导出即审计**：每次导出写 `operation_log`（`action='LOG_EXPORT'`，`detail` 含 `rows` 与 `params`）。
4. **文件名**：`{type}_{yyyyMMddHHmmss}.csv`。

**产出**：`CsvExporter`；5 处导出接入；`ExportTaskHandler`（异步）。

**算做完**

- [ ] 5 处导出全部走同一个工具类（`grep` 确认没有第二份 CSV 拼接代码）
- [ ] 导出内容含中文、逗号、双引号、换行 → Excel 打开正常
- [ ] 构造一个以 `=1+1` 开头的字段 → 导出后是 `'=1+1`（防注入）
- [ ] 6000 行导出 → 返回 `410015` + `taskId`，任务完成后有通知
- [ ] 每次导出都有 `LOG_EXPORT` 审计

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 异步导出无下载服务（OSS 未就绪） | 存本机 `E:\faze-export\` + 提供 `GET /admin/exports/{taskId}/download`（带鉴权与过期时间）；记挂账 |

---

### G 组：监控、告警与备份（5 个任务）

> **本组的定位**：让系统「不再盲飞」。总纲 §5.8 原话——「没有这两样（监控与备份），前面所有段跑起来都是盲飞」。
> **允许同机**（总纲风险表原文：「开发 / 验收期允许同机；生产部署时监控独立」）。

#### G1 · Micrometer 指标端点

**做什么**：把 `/actuator/prometheus` 打开，并补上「接口 QPS / 延迟 / 错误率」三类**业务级**指标（技术路线 §17.1「接口」层）。

**怎么做**

1. **依赖**：`spring-boot-starter-actuator` + `micrometer-registry-prometheus`。**登记依赖清单**。
2. **配置**（`application.yml`）：
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,info,prometheus,metrics
     endpoint:
       prometheus:
         enabled: true
     metrics:
       tags:
         application: faze-api
   ```
   **`/actuator/**` 必须鉴权**：`/actuator/health` 放行（给探活用），其余要求 admin token 或限本机访问（`management.server.address=127.0.0.1`）。**不许裸奔在公网**。
3. **自定义指标**（Micrometer `MeterRegistry`）：
   - `faze_http_requests_total{path,method,status}` → QPS 与错误率都从它算
   - `faze_http_request_duration_seconds{path,method}` → Histogram，P95 从它算
   - `faze_sensitive_hit_total{level}` → 命中量（`7-A7` 之外的业务指标，顺带）
   - `faze_sensitive_reload_fail_total` → 热更新失败（A2 第 5 条）
   - `faze_ticket_overtime_total` → 超时工单（C1）
   - `faze_backup_last_success_timestamp` → 备份最后成功时间（G5）
4. **统一打点**：用 `HandlerInterceptor` 或 `@Timed` 注解，**二选一，全项目用同一种**。本段选**拦截器**（能拿到 path 模板，避免 path 变量导致标签爆炸）。
   - **关键**：标签用 `path` 的**模板**（`/admin/users/{id}`）而不是实际路径（`/admin/users/10284`），否则 Prometheus 时间序列爆炸
5. **验证**：`curl http://127.0.0.1:8080/actuator/prometheus | grep faze_http` 能看到指标。

**产出**：`config/MetricsConfig.java` + `MetricsInterceptor.java`；6 个自定义指标；依赖登记。

**算做完**

- [ ] `curl /actuator/prometheus` 能抓到 `faze_http_requests_total`
- [ ] 打 10 次接口后 `faze_http_requests_total` 增长 10
- [ ] **一个 500 错误能被计入**（`status="500"` 标签）
- [ ] 路径标签是模板形式（`/admin/users/{id}`），不是实际 id
- [ ] `/actuator/env` 等敏感端点**不可访问**（403 或 404）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| SB 4.0 的 actuator 端点路径变了 | 以实际为准（SB4 改过 management 默认路径），在证据文件里记录实际路径 |
| 拦截器拿不到 path 模板 | 用 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`（Spring 标准做法） |

#### G2 · Prometheus + Grafana 本机部署

**做什么**：本机起 Prometheus + Grafana（Windows 二进制，不用 Docker，裁决 18），并把 Grafana 面板配出来。

**怎么做**

1. **目录结构**：
   ```text
   scripts/monitor/
   ├─ start-monitor.ps1        # 一键启动（下载检测 → 启动 prometheus → 启动 grafana）
   ├─ stop-monitor.ps1
   ├─ prometheus.yml            # 抓取配置（target: 127.0.0.1:8080）
   ├─ alert-rules.yml           # 告警规则（G3）
   └─ grafana-dashboard.json    # 面板 JSON（导入即用）
   ```
2. **`prometheus.yml` 关键段**：
   ```yaml
   global:
     scrape_interval: 15s
     evaluation_interval: 15s
   rule_files:
     - alert-rules.yml
   scrape_configs:
     - job_name: faze-api
       metrics_path: /actuator/prometheus
       static_configs:
         - targets: ['127.0.0.1:8080']
   alerting:
     alertmanagers: []   # 本段不部署 Alertmanager，告警走 G3 的自研推送
   ```
   **为什么不部署 Alertmanager**：Alertmanager 的告警通道也是 webhook，但配置与调试成本高，且不提供「可视化历史」。本段用「Prometheus 规则 + 一个小轮询器调钉钉」实现（G3），更简单可控。**这是取舍，不是标准做法**，记 §十一。
3. **Grafana 面板**（`grafana-dashboard.json`）至少 4 个图：
   - 接口 QPS（`rate(faze_http_requests_total[1m])`）
   - P95 延迟（`histogram_quantile(0.95, rate(faze_http_request_duration_seconds_bucket[5m]))`）
   - 错误率（`rate(faze_http_requests_total{status=~"5.."}[5m]) / rate(faze_http_requests_total[5m])`）
   - 敏感词命中量（`rate(faze_sensitive_hit_total[5m])`）
4. **启动脚本必须能重复跑**（先检查进程是否在跑，在跑就跳过），且**不写死绝对路径**（用 `$PSScriptRoot`）。

**产出**：`scripts/monitor/*` 5 个文件；`grafana-dashboard.json`。

**算做完**

- [ ] `.\scripts\monitor\start-monitor.ps1` → Prometheus 在 `:9090`、Grafana 在 `:3000` 起来
- [ ] Prometheus UI 的 `Status → Targets` 里 `faze-api` 是 **UP**
- [ ] 导入 dashboard JSON → 4 个图都有数据
- [ ] 停止监控再启动，脚本不报错（幂等）
- [ ] **截图留存**：`docs/acceptance/seg7/7-A7-prometheus.png` + `7-A7-grafana.png`

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 无法下载二进制（网络受限） | `7-A7` 降级为「`/actuator/prometheus` 输出文本 + 一次 `curl` 结果」，Grafana 面板记为挂账；**不许用假截图** |
| 端口冲突 | 脚本参数化端口（`-PromPort 9090 -GrafanaPort 3000`） |

#### G3 · 告警规则与钉钉通道（`7-A8` 的核心）

**做什么**：让告警**真的响**。裁决 2：钉钉自定义机器人 webhook。

**怎么做**

1. **`alert-rules.yml` 规则 6 条**（阈值来自 `sys_config`，**但 Prometheus 规则文件是静态的**——所以本段把阈值同时写在两处并在文档里说明：改阈值要改规则文件 + 重载，或改 `sys_config` 并改规则文件里的数值。**这是已知冗余，记 §十一**）：

   | 告警名 | 表达式 | 持续 | 级别 |
   |---|---|---|---|
   | `FazeServiceDown` | `up{job="faze-api"} == 0` | 1m | P0 |
   | `FazeHighErrorRate` | 5xx 占比 > 5% | 3m | P1 |
   | `FazeHighLatency` | P95 > 1000ms | 5m | P1 |
   | `FazeBackupStale` | `time() - faze_backup_last_success_timestamp > 86400*1.5` | 即时 | P1 |
   | `FazeDiskLow` | 磁盘 > 80%（需 `node_exporter`，**本段可选**） | 5m | P2 |
   | `FazeIdentityBroken` | `faze_identity_check_failed_total > 0`（段 5 巡检结果；**指标名以段 5 实际交付为准**，若不同则改这里一处） | 即时 | **P0** |

   > `node_exporter`（CPU/内存/磁盘）技术路线 §17.1 列了，但本段**只做可选**：装了就有，没装不影响 `7-A8`（`7-A8` 只要求「某接口返回 500 或注入延迟 → 真告警」，用前三条就能验）。

2. **告警推送器**（本段自研，不部署 Alertmanager）：
   - `task_queue` 定时任务每 60s 查一次 Prometheus 的 `/api/v1/alerts`（读 `alerting` 规则结果）
   - 有 `firing` 且未推送过的告警 → 组装钉钉 markdown 消息 → POST webhook
   - **去重**：Redis `SETNX alert:sent:{alertName}:{fingerprint}` TTL 1 小时；同一告警 1 小时内只发一次
   - **恢复通知**：`resolved` 时发一条「✅ 已恢复」
3. **钉钉消息格式**：
   ```json
   {"msgtype":"markdown","markdown":{"title":"[P1] 接口错误率超阈值","text":"#### [P1] 接口错误率超阈值\n> **当前值**：8.3%（阈值 5%）\n> **持续**：3 分钟\n> **时间**：2026-11-02 21:30:00\n> **环境**：本机开发"}}
   ```
   加签（`secret`）按钉钉规范：`timestamp + "\n" + secret` 做 HMAC-SHA256 → base64 → `&timestamp=&sign=`。
4. **值班方式**（裁决 20）：P0 立即响应；其余次日 9:00–21:00。**写进运维文档**，不写「7×24」。
5. **备用通道**（裁决 2）：后台红标（`GET /admin/ops/health-report` 异常项标红）+ 每日 09:00 推一次巡检报告。

**产出**：`alert-rules.yml`；`AlertDispatcher`（task_queue handler）；`GET /admin/ops/health-report`（**新增接口**）；运维文档一节。

**算做完**（`7-A8` 的正面回答）

- [ ] **真的响过**：故意让某接口返回 500（或注入 2s 延迟）→ 3~5 分钟内钉钉群收到一条告警消息，**截图留存**（含群名、消息内容、时间）
- [ ] 恢复后收到「已恢复」消息
- [ ] 同一告警 1 小时内不重复轰炸（去重生效）
- [ ] 停掉服务 → `FazeServiceDown` 在 1~2 分钟内告警
- [ ] `health-report` 接口返回 6 项状态，异常项为 `false`
- [ ] 运维文档有「值班与响应」一节，P0/非 P0 口径明确

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 没有钉钉群/机器人 | `7-A8` **挂账**，用 `scripts/alert/mock-receiver.py`（本机 8088 端口）证明「HTTP POST 真的发出去了」；记录挂账。**不许只打日志就说过了** |
| 查 Prometheus `/api/v1/alerts` 太绕 | 退化方案：**自研检测器直接查 MySQL/Redis 指标**（错误率从 `login_log`/访问计数算，服务存活靠 `health` 探活），不依赖 Prometheus。**只要能真发消息就算过** |
| 阈值硬编码在规则文件里 | 接受冗余，§十一 记录「改阈值要改两处」，段 8 前统一 |

#### G4 · 结构化日志与链路追踪

**做什么**：让日志能查、能串、不泄密。对应技术路线 §17.3。

**怎么做**

1. **JSON 格式**（技术路线 §17.3「应用日志统一 JSON 格式」）：用 `logback-spring.xml` 配 `LogstashEncoder`（需登记依赖 `logstash-logback-encoder`）。
   - 字段：`timestamp` / `level` / `logger` / `message` / `traceId` / `spanId` / `thread` / `env`
2. **`traceId`**（审计规范 §4.5）：入口过滤器生成 `traceId`（`MDC.put("traceId", ...)`），无上游传递时自生成（`UUID` 前 16 位）；响应头返回 `X-Trace-Id`。**日志与错误响应里都带它**——用户报错时给这一个号就能查全链路。
3. **脱敏**（三条红线强制）：
   - 日志**禁写** L4/L5 字段（密码、支付密码、token、身份证号、银行卡号、`ossKey` 全量、聊天原文）
   - 手机号只能 `138****8888`
   - **`hit_snippet` 打日志前必须再脱敏一次**（A3 已脱敏落库，日志层再兜一遍）
4. **文件与轮转**：`logs/faze-api.log`（INFO+）/ `logs/faze-api-error.log`（ERROR），按天 + 100MB 轮转，保留 30 天（技术路线 §17.3）。
5. **审计落库失败的降级**（审计规范 §7.1）：审计写库失败 → 降级写 `logs/audit-fallback.log` + **立即告警**（这是审计链断了，P1）。
6. **不做 ELK / Zipkin**（技术路线列了，但需要部署 3 个服务）。本段用「JSON 文件 + `Select-String` 查」满足验收；ELK 记 §八.2。

**产出**：`logback-spring.xml`；`TraceIdFilter`；`LogMaskUtil`；审计降级 handler。

**算做完**

- [ ] 打一次接口 → 日志是 JSON、含 `traceId`、响应头有 `X-Trace-Id`、两者一致
- [ ] 故意在日志里打一个手机号 → 输出是 `138****8888`
- [ ] `grep` 全仓业务代码里 `log.(info|warn|error)` 的参数，**没有一处直接传 `content` 原文或 token**
- [ ] 人为让 `operation_log` 插入失败（改表名为不存在）→ 出现 `audit-fallback.log` + 钉钉收到告警
- [ ] 日志按天轮转，`logs/` 下有当天文件

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| `logstash-logback-encoder` 与 SB4 logback 版本冲突 | 用 Spring 自带的 `LogstashEncoder` 若不行，退化为 pattern 输出 JSON 字符串（`{"t":"%d","l":"%level","m":"%msg","tid":"%X{traceId}"}`） |

#### G5 · 备份与恢复演练（`7-A9`）

**做什么**：每日全量备份 + 一次真实恢复演练。

**怎么做**

1. **脚本** `scripts/backup/backup.ps1`：
   ```text
   1. mysqldump --single-transaction --routines --triggers faze > E:\faze-backup\faze_YYYYMMDD_HHmm.sql
   2. 校验：文件大小 > 0 且 > 上次大小 × 0.5（防 dump 到一半失败产生空文件）
   3. 写 faze_backup_last_success_timestamp 指标（G1）与 logs/backup.log
   4. 清理：删除 30 天前的 .sql（保留最后 3 份不论日期）
   ```
   **必须 `--single-transaction`**（InnoDB 一致性快照，不锁表）。
2. **恢复脚本** `scripts/backup/restore.ps1`：
   ```text
   1. DROP DATABASE IF EXISTS faze_restore; CREATE DATABASE faze_restore;
   2. mysql faze_restore < 指定 .sql
   3. 校验：逐表比对行数（源库 vs 恢复库），抽样 3 张表各比对 10 行 MD5
   4. 输出校验报告
   ```
   **库名固定 `faze_restore`，脚本里硬校验「目标库不能是 faze」**（防误删生产）。
3. **定时**：Windows 计划任务每日 03:00。失败重试 3 次，仍失败 → 告警（G3）。
4. **演练**（`7-A9`）：真实跑一次恢复 → 报告含「源库行数 / 恢复库行数 / 是否一致 / 抽样结果」。
5. **业务可用性验证**（`7-A9` 的「业务可用」）：把服务连到 `faze_restore` 起一次（改 datasource url），能登录、能查单。

**产出**：`scripts/backup/backup.ps1` / `restore.ps1`；计划任务配置说明；恢复演练报告 `docs/acceptance/seg7/7-A9-restore.txt`。

**算做完**

- [ ] 跑一次备份 → 生成 `.sql` 文件、大小合理、`logs/backup.log` 有记录
- [ ] 跑一次恢复 → `faze_restore` 库建起来、**逐表行数一致**、抽样 MD5 一致
- [ ] 服务连 `faze_restore` 能登录、能查订单（业务可用）
- [ ] 故意把备份目录设为不可写 → 备份失败、钉钉收到告警、`faze_backup_last_success_timestamp` 不更新
- [ ] 脚本里 `restore.ps1` 有「目标库名 === 'faze' 则直接 exit 1」的硬保护

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 本机没有 `mysqldump`（只有 MySQL 服务） | 用 `mysqlsh` 或 MySQL Workbench 的导出命令；或在 `PATH` 里补 `C:\Program Files\MySQL\MySQL Server 8.0\bin` |
| 磁盘不够存 30 天备份 | 降到 14 天并在运维文档写明；`7-A9` 只要求「能恢复」，不要求保留期 |

---

### H 组：收口（5 个任务）

#### H1 · 运营动作清单走查（`7-A1`）

**做什么**：按总纲 §5.8 的五步清单**完整走一遍**，证明运营不需要技术介入。

**怎么做**

严格按顺序做，**每步只允许用 PC 后台界面**（不许开数据库客户端）：

| 步 | 动作 | 完成后应看到 |
|---|---|---|
| 1 | 审核一个律师入驻申请（A-05） | APP 端入驻状态变「已通过」 |
| 2 | 审核一篇文章并上架（A-14/A-15） | APP 首页能看到该文章 |
| 3 | 处理一条投诉工单（A-21→A-22） | APP 投诉列表看到回复与状态变化 |
| 4 | 拉黑一个用户（A-03 或 A-20 的封禁） | 该用户无法登录，提示含客服电话 |
| 5 | 回复一条工单（A-22） | 用户收到站内信 |

**产出**：`docs/acceptance/seg7/7-A1-runbook.md`，含每步的截图文件名 + 时间 + 操作人。

**算做完**

- [ ] 五步全部完成，**全程没打开过 SQL 客户端**（这一条自己声明 + 截图佐证）
- [ ] 每步都有「后台操作前 / 后台操作后 / APP 端结果」三张截图
- [ ] 中途遇到任何「必须改库」的环节 → **算这一步没做完**，回去补接口

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 第 1 步段 1 的 A-05 未交付 | 本段不重做 A-05；`7-A1` 记为「部分完成」（第 2~5 步可做），**记挂账**并写清原因 |

#### H2 · 十条锚点逐条验收（`7-A1`~`7-A10`）

**做什么**：按 §四 逐条验，每条产出证据文件。

**怎么做**：见 §四 每条的「证据形式」列。证据统一放 `docs/acceptance/seg7/`。

**产出**：`docs/acceptance/seg7/` 下 10+ 个证据文件。

**算做完**

- [ ] 10 条各有独立证据文件，文件名含锚点编号
- [ ] 挂账的锚点（如钉钉未配置时的 `7-A8`）在 `挂账.md` 里单列，写清「挂账条件 + 补验方式」
- [ ] 证据里没有「看起来正常」「功能可用」这类无法判定的描述

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 某条锚点依赖的段未交付（如 `7-A1` 第 1 步要段 1 的 A-05） | 该条记「部分完成 + 挂账」，**不许写成通过**；挂账里写清依赖哪一段的哪个交付物 |
| 某条锚点验出来的数对不上 | 先查口径文档与 SQL，再查代码；**不许改口径去迎合数据**——口径是负责人拍板的，改要走 §十 |

#### H3 · 监控与告警演练（复验 `7-A7` / `7-A8`）

**做什么**：把 G 组的能力**再跑一遍**，形成可复现的演练记录。

**怎么做**

1. 记录 3 组数据：正常态 / 制造故障 / 恢复后
2. 故障注入脚本 `scripts/monitor/fault-inject.ps1`：支持 `-mode 500`（让一个测试接口返回 500）/ `-mode slow`（注入 2s 延迟）/ `-mode down`（停服务）
3. 每次注入后记录：Prometheus 是否有指标变化、Grafana 图是否变化、钉钉是否收到消息

**产出**：`docs/acceptance/seg7/7-A8-drill.md` + 截图。

**算做完**

- [ ] 3 种故障模式各演练 1 次，均有钉钉消息截图
- [ ] 恢复后 5 分钟内收到「已恢复」
- [ ] 演练记录含时间戳，可与钉钉消息时间对上

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 故障注入导致本机服务起不来 | 用 `-mode slow`（只加延迟，不真崩）代替 `-mode down`；`7-A8` 只要求「达到阈值真的告警」，延迟也能触发 |
| 钉钉消息收不到（网络/加签错） | 先用 `mock-receiver.py` 确认系统侧发出正常，再单独调钉钉 webhook 的加签；两者分开排查，**不要混在一起猜** |

#### H4 · 文档回写（§九 的 17 项）

**做什么**：把本段与上游的差异全部回写。**这是总纲 §6.2 的「收口五件套」之一。**

**怎么做**：按 §九 表逐项做，每项做完全部打勾。

**算做完**

- [ ] §九 的 17 项全部完成（除标注「需负责人决定」的）
- [ ] 《数据表规范》附录 A 能查到第 54、55 张表，且 `service_ticket` / `sensitive_word` / `content_review` 的字段数已更新
- [ ] 《技术路线》§4.8.4.3 的「口径仍未定」两句已删
- [ ] 《技术路线》§4.8.2 有 5 个补登接口
- [ ] 《错误码规范》§3.10 有 12 个新码
- [ ] 《审计与日志规范》§3.2 有本段新增的审计动作码

**产出**

| 项 | 内容 |
|---|---|
| 回写后的文档 | 总纲 / 技术路线（5 处）/ 接口规范 / 错误码规范 / 审计与日志规范 / 数据表规范 / PC 计划 / 未完成需备注 |
| 新建文档 | `docs/ops/运维手册.md`、`docs/acceptance/seg7/A-02-指标口径.md`、`docs/acceptance/seg7/挂账.md` |
| 依赖登记 | `docs/architecture/依赖清单.md` 补 4 项 |

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 回写时发现上游文档的表述与本段裁决冲突更大 | **以 §零之二 裁决为准**改上游；若上游是「规范」级文档（数据表规范 / 接口规范），**先在 §十一 记一条「需规范作者复核」**再改，不许默默改完不留痕 |
| 某项回写牵动其他段的文档（如总纲 §4.2 页面归属） | **不擅自改页面归属**。归属变更要负责人拍板，本段只在 §十一 提出来 |

#### H5 · 证据归档与基线

**做什么**：跑四件事（落款 / 指纹 / README 索引 / CHANGELOG），出基线。

**怎么做**

1. 本文档落款（文末）
2. `docs/BASELINE.md` 出 v1.34（资产数 +1，从 34 → 35）
3. `docs/README.md` 加索引一行
4. `docs/CHANGELOG.md` 加一条
5. 全文重算指纹，29+1 份逐个核对
6. 桌面镜像同步
7. 双分支提交推送

**算做完**

- [ ] `docs/BASELINE.md` 里本文档的指纹与实测一致（三项：行数+1 / 字节数 / SHA256 前 16）
- [ ] 全部资产指纹核对 **0 差异**
- [ ] 桌面镜像与仓库逐文件 SHA256 一致
- [ ] `UI设计` 与 `docs` 两分支 `git diff` 为空
- [ ] 两次提交已推送，远端分支与本地一致

**产出**

| 项 | 内容 |
|---|---|
| 基线 | `docs/BASELINE.md` v1.34（36 份资产），本文档指纹三项登记 |
| 索引 | `docs/README.md` 加一行 |
| 变更日志 | `docs/CHANGELOG.md` 新增「2026-11-02 · 新增《第 7 段工作流 v1.0》」 |
| 镜像 | 桌面 `文档\docs` 同步（SHA256 逐文件一致） |
| 分支 | `UI设计` + `docs` 两分支，`git diff` 为空 |

**算做完**（见上方勾选项）

**卡住怎么办**

| 卡点 | 出口 |
|---|---|
| 指纹对不上（行数/字节/SHA256 三项之一） | **以实测为准**，先查是不是编辑器加了 BOM 或改了换行符（必须 LF）；`write` 工具已用 `newline='\n'`，二次编辑可能破坏，改完重算 |
| 桌面镜像同步失败（路径/权限） | 镜像失败**不算收口**，但可先在 §十一 记「镜像待同步」，**不许说「已同步」** |

---

## 四、验收锚点（`7-A1` ~ `7-A10` 逐条展开）

> 编号与通过标准**引用总纲 §5.8，一字不改**。本节只补「怎么验 + 证据形式 + 常见假通过」。
> 证据统一放 `docs/acceptance/seg7/`，文件名前缀用锚点编号。

### `7-A1` 运营动作清单

**总纲原文**：按清单完整走一遍：审核律师 → 审核文章 → 处理投诉 → 拉黑用户 → 回复工单。**五步全部在后台完成，无需找技术改库。**

**怎么验**

1. 开两个窗口：左边 PC 后台（A-05 / A-14 / A-21 / A-03 / A-22），右边 APP 真机
2. 严格按顺序做五步，**每步只允许点界面**。做之前先把 MySQL 客户端关掉（并声明）
3. 每步记录：后台操作前截图 / 后台操作后截图 / APP 端结果截图

**证据形式**：`7-A1-runbook.md` + 15 张截图（5 步 × 3 张），每张带时间戳

**通过标准**：五步全完成且无一处需要改库

**常见假通过**（一律不算）

- 「接口调通了」→ 不算。要界面点完
- 「数据在库里变了」→ 不算。要 APP 端能看见
- 「第 1 步段 1 没交付，跳过」→ 记为**部分完成 + 挂账**，不许写成「通过」

### `7-A2` 原文不被篡改

**总纲原文**：发一条含敏感词的消息。**库中原文完整；命中记录标注词与级别；展示层按 `block` 拦截、`review` 待审。**

**怎么验**

1. 用 App 提交一条投诉（或反馈），正文含 `block` 级词（如「包打赢」）
2. 接口返回被拦（`410006`）
3. **直接查库**：`SELECT content FROM complaint WHERE id = <上一条>` → 确认**原文完整**（含「包打赢」三个字，没被替换成 `***`）
4. `SELECT * FROM content_review WHERE biz_id = ?` → 有 1 条，`hit_words` 含该词，`level='block'`，`hit_snippet` 非空
5. 再提交一条含 `review` 级词的 → 能提交成功，`content_review.review_result='pending'`，A-20 队列能看到
6. 全仓 `grep -r '\*\*\*' services/` → 除测试用例外**零命中**

**证据形式**：`7-A2-no-rewrite.md` + 三段 SQL 输出 + A-20 队列截图 + grep 结果

**通过标准**：原文一字未变 + 命中记录三要素（词/级别/片段）齐全 + 两种级别处置不同

**常见假通过**

- 只看「有没有被拦」，没查原文是否被改 → **这条锚点的核心就是原文**
- 命中记录只存了 `level` 没存词 → 不算
- 把 `hit_snippet` 当成原文 → 不算。`hit_snippet` 是标注，**原文在业务表里**

### `7-A3` 敏感词即时生效

**总纲原文**：在 A-25 增删改一个词并保存。**无需重启，下一次消息即按新词库判定。**

**怎么验**

1. 记录服务启动时间（`ps` 或日志首行），**之后全程不重启**
2. 在 A-25 新增一个此前不存在的词（如「测试违禁词A」），级别 `block`
3. **立刻**发一条含「测试违禁词A」的投诉 → 被拦（`410006`）
4. 把该词改为 `warn` 级 → 再发一条 → **能提交成功**
5. 把该词删除 → 再发一条 → 能提交成功
6. 全程 `git log` / 进程启动时间不变

**证据形式**：`7-A3-hot-reload.md` + 三次提交的接口响应 + 服务启动时间前后一致

**通过标准**：三次判定都按最新词库，且服务未重启

**常见假通过**

- 「重启后生效」→ **不算**。总纲原文是「无需重启」
- 改完等 5 分钟才生效 → 稿面写「词库改动 5 分钟内生效」，但**验收要求「下一次消息即生效」**，两者取严（≤ 1 次请求的间隔）

### `7-A4` 黑名单

**总纲原文**：拉黑一个用户后登录。**无法登录，提示明确（含申诉入口或客服电话）。**

**怎么验**

1. 在 A-03 或 A-20 封禁一个测试用户（填原因、期限）
2. 用该用户账号密码登录 → **登录失败**
3. 看返回的错误码与文案：**必须不是「密码错误」**，且文案里含客服电话或申诉入口
4. ≤ 60 秒内（Redis 缓存过期），该用户已登录的 token 再调任意接口 → 被拒
5. 解封 → 能正常登录

**证据形式**：`7-A4-ban.md` + 登录失败响应截图 + 文案全文

**通过标准**：拦得住 + 提示与密码错误**可区分** + 含联系客服方式

**常见假通过**

- 只验「登录失败」→ 不够。总纲点名「不能只提示密码错误」
- 提示里只有「账号异常」没有联系方式 → 不算（总纲原文「含申诉入口或客服电话」）

### `7-A5` 投诉工单

**总纲原文**：App 提交投诉 → PC 受理 → 回复。**App 能看到回复与状态变化；工单详情能关联订单 / 用户。**

**怎么验**

1. App 提交投诉（关联一笔已完成订单）
2. 后台 A-21 看到该工单（`waiting`，未超时）
3. 进 A-22：能关联到订单号、用户（脱敏手机号）
4. 点「分配」→ 状态变 `processing`
5. 填回复 → 「发送回复」→ 状态变 `replied`
6. **App 端投诉列表**：看到状态 `replied` + 回复内容
7. App 消息中心：收到一条「工单已回复」站内信

**证据形式**：`7-A5-ticket.md` + App 提交截图 + A-21/A-22 截图 + App 回复截图 + `notification` 表 SQL

**通过标准**：双向可见（App 提交 → PC 处理 → App 看到结果）+ 关联信息正确

**常见假通过**

- 只在后台改了状态，App 没刷新 → 不算（要 App 端能看到）
- 回复只落在 `service_ticket` 但 App 读不到 → 不算

### `7-A6` 操作日志

**总纲原文**：做一次手工充值 / 封禁。**A-26 能查到「谁 / 何时 / 从哪 / 改了什么」四项齐全。**

**怎么验**

1. 做一次手工充值（段 1 的 A-07）或封禁（A-03/A-20）
2. 打开 A-26 操作日志 Tab，按操作人 + 时间筛
3. 找到该条，**核对四项**：
   - 谁：`operator_id` → 显示管理员名
   - 何时：`created_at` 精确到秒
   - 从哪：`ip` 非空且是真实 IP（不是 `0.0.0.0` / `127.0.0.1` 之外的假值）
   - 改了什么：`detail` 的 `before` / `after` / `reason`
4. 点「详情」→ 格式化成对比表格（不是裸 JSON）

**证据形式**：`7-A6-audit.md` + A-26 列表截图 + 详情抽屉截图 + `operation_log` 行 SQL

**通过标准**：四项齐全且详情可读

**常见假通过**

- 只有 `after` 没有 `before` → 不算（审计规范 §3.3 硬性要求）
- `detail` 是裸请求体（含密码）→ **不算且违规**
- IP 是 `127.0.0.1` → 本机开发可接受，但要说明；生产必须是真实 IP（记 §十一）

### `7-A7` 监控指标

**总纲原文**：访问 Prometheus 与 Grafana。**抓到接口 QPS、延迟、错误率；面板可见。**

**怎么验**

1. `.\scripts\monitor\start-monitor.ps1`
2. 浏览器开 `http://127.0.0.1:9090` → `Status → Targets` → `faze-api` 为 **UP**
3. 打 20 次接口（含 2 次 500）
4. Prometheus 查询 `faze_http_requests_total` → 有 20 条
5. 查询错误率 `sum(rate(faze_http_requests_total{status=~"5.."}[1m])) / sum(rate(faze_http_requests_total[1m]))` → 约 10%
6. 查询 P95 `histogram_quantile(0.95, ...)` → 有值
7. 开 Grafana `http://127.0.0.1:3000` → 导入 dashboard → 4 个图都有数据

**证据形式**：`7-A7-metrics.md` + Prometheus 查询截图 + Grafana 面板截图 + `/actuator/prometheus` 文本片段

**通过标准**：三个指标（QPS / 延迟 / 错误率）都能抓到 + Grafana 面板可见

**常见假通过**

- 只有 `/actuator/prometheus` 有输出，没 Grafana → **降级**：记「Grafana 挂账」，Prometheus 部分算过（但要在挂账里写清）
- 指标存在但没数据 → 不算

### `7-A8` 告警真的会响

**总纲原文**：故意让某接口返回 500（或注入延迟）。**达到阈值后真的产生告警（邮件 / webhook / 钉钉任一，需先定通道），不是只打日志。**

**怎么验**

1. 确认 `sys_config.alert.dingtalk_webhook` 已配（负责人提供）
2. 跑 `.\scripts\monitor\fault-inject.ps1 -mode 500`（让测试接口持续返回 500）
3. **等待 3~5 分钟**（错误率规则持续 3m）
4. 钉钉群收到告警消息 → **截图**（含群名、消息内容、时间戳）
5. 停止注入 → 等恢复 → 收到「已恢复」消息
6. 再跑 `-mode down`（停服务）→ 1~2 分钟内收到 `FazeServiceDown`

**证据形式**：`7-A8-alert.md` + 两张钉钉消息截图（告警 + 恢复）+ 注入脚本输出

**通过标准**：**钉钉真的收到消息**（不是日志里有 `WARN`）

**常见假通过**（这是本段最容易糊弄的一条）

- 「日志里有 ERROR」→ **不算**
- 「Prometheus 里有 firing 告警但没推出去」→ **不算**
- 「用 curl 手动 POST 一次 webhook 截图」→ **不算**。必须是**系统自动触发**
- 没有钉钉群 → **挂账**，用 mock 接收端证明「系统真的发出了 HTTP POST」，并在挂账里写清补验方式

### `7-A9` 备份恢复演练

**总纲原文**：从备份恢复到新库。**数据一致（行数 + 抽样校验），业务可用（能登录、能查单）。**

**怎么验**

1. 跑 `.\scripts\backup\backup.ps1` → 生成 `.sql`
2. 跑 `.\scripts\backup\restore.ps1 -File <刚生成的.sql>` → 恢复到 `faze_restore`
3. **行数比对**：源库与恢复库逐表 `COUNT(*)`，输出对比表
4. **抽样校验**：抽 3 张表（`app_user` / `legal_order` / `balance_flow`）各 10 行，比对 `MD5(CONCAT_WS(...))`
5. **业务可用**：把服务 datasource 临时指向 `faze_restore`，起服务 → 能登录、能查订单 → 改回
6. 重跑脚本时 `restore.ps1` 拒绝目标库为 `faze`

**证据形式**：`7-A9-restore.md` + 行数对比表 + MD5 比对结果 + 登录截图 + 拒绝保护输出

**通过标准**：行数一致 + 抽样一致 + 能登录能查单

**常见假通过**

- 只 dump 不 restore → **不算**
- 只 restore 不比对 → 不算（总纲原文「行数 + 抽样校验」）
- 恢复到同一个生产库上 → **危险且不算**（脚本有硬保护）

### `7-A10` 看板口径

**总纲原文**：对照口径文档核对 A-02 每个数字。**每个指标能说出算法与数据来源；无「不知道哪来的数」。**

**怎么验**

1. 打开 A-02，逐个卡片看
2. 对照 `docs/acceptance/seg7/A-02-指标口径.md`，**逐条**核对：
   - 这个数的定义是什么
   - SQL 是什么
   - 数据从哪张表来
   - 边界（时区 / 是否含软删 / 除零怎么办）
3. **手工验算一个**：造 2 个用户、3 笔订单 → 手算今日订单数 → 与页面数字比对
4. 悬停「平台今日收入」→ 有 tooltip 说明「= 当日订单服务费合计，不含充值」

**证据形式**：`7-A10-dashboard.md` + 口径文档 + 手算过程 + 页面截图 + 至少 1 个指标的「手算 = 页面」证明

**通过标准**：10 个指标**每一个**都能在口径文档里找到算法与来源

**常见假通过**

- 口径文档写「按系统统计」→ **不算**（含糊话等于没写）
- 只解释了 4 个核心卡片，没解释「待我处理 5 类」→ 不算
- 数字看着合理但算不出来 → **这正是总纲说的「不知道哪来的数」**

### 锚点与任务的对应关系（自查用）

| 锚点 | 主要靠哪些任务 | 次要依赖 |
|---|---|---|
| `7-A1` | H1 + B6 + C3 + C4 | 段 1 的 A-05、段 2 的 A-14 |
| `7-A2` | A3 + B3 + C2 | A2（词库） |
| `7-A3` | A2 + B1 + B6 | — |
| `7-A4` | B4 | 段 1 的登录 |
| `7-A5` | C2 + C3 + C4 + C5 | 段 2 的消息中心 |
| `7-A6` | A4 + F2 | 段 1 的审计 AOP |
| `7-A7` | G1 + G2 | — |
| `7-A8` | G3 + H3 | G1（指标来源） |
| `7-A9` | G5 | 本机 MySQL |
| `7-A10` | A5 + F1 + F3 | 段 2 的订单、段 1 的 `balance_flow` |

---

## 五、产出清单（交付时逐项勾）

### 5.1 数据库（新建 11 张表 + 3 张表补字段）

| # | 表 | 段 | 字段数 | 本段动作 | 来源 |
|---|---|---|---|---|---|
| 1 | `notification` | 7 | 12 | 建 | 技术路线 §5.11.1 L1797 |
| 2 | `complaint` | 7 | 12 | 建 | §5.11.1 L1814 |
| 3 | `feedback` | 7 | 10 | 建 | §5.11.1 L1832 |
| 4 | `faq` | 7 | 9 | 建 | §5.11.1 L1847 |
| 5 | `user_violation` | 7 | 14 | 建 | §5.11.1 L1967 |
| 6 | `notify_template` | 7 | 9 | 建 | §5.11.2 L2032 |
| 7 | `sensitive_word` | 7 | **12** | 建 + 补 `last_hit_at` | §5.11.3 L2051（裁决 7） |
| 8 | `content_review` | 7 | **12** | 建 + 补 `hit_snippet` / `biz_user_id` | §5.11.3 L2068（裁决 8） |
| 9 | `service_ticket` | 7 | **13** | 建 + 补 4 字段 | §5.11.3 L2084（裁决 6） |
| 10 | `announcement` | **7** | 15 | **新建（第 55 张）** | 本段裁决 5 |
| 11 | `sensitive_word_appeal` | **7** | 10 | **新建（第 54 张）** | 本段裁决 4 |
| — | `share_poster` | — | 8 | **不建** | 裁决 11 |
| — | `operation_log` | 1 | 11 | **只读** | 技术路线 §5.11.5 L2201 |
| — | `login_log` | 1 | 10 | **只读** | §5.11.5 L2184 |

**迁移文件 4 个**：`V700__seg7_tables.sql` / `V701__create_announcement.sql` / `V702__create_sensitive_word_appeal.sql` / `V703__seg7_seed.sql`

**种子数据**：敏感词 50 / `faq` 7 / `notify_template` 6 / `sys_config` 11 个新键

### 5.2 接口（54 个：新增 26 + 已列 28）

> **判据**：「新增」= 技术路线 §4.8.2 / §4.8.4 与接口规范里**没有**这条路径，必须回写登记。「已列」= 上游文档里已有路径，本段实现。

#### 管理端（40 个：新增 17）

| # | 方法 + 路径 | 用途 | 新增? | 鉴权 |
|---|---|---|---|---|
| 1 | `GET /admin/dashboard/summary` | 4 个核心指标 + 在线数 | 已列 | PC token |
| 2 | `GET /admin/dashboard/trends` | 趋势序列 | 已列 | PC token |
| 3 | `GET /admin/dashboard/regions` | 地区分布 | **新增** | PC token |
| 4 | `GET /admin/dashboard/todos` | 待我处理 5 类计数 | **新增** | PC token |
| 5 | `GET /admin/dashboard/export` | 导出报表 | **新增** | PC token |
| 6 | `GET /admin/sensitive-words` | 词库列表 | 已列 | PC token |
| 7 | `POST /admin/sensitive-words` | 新增词条 | 已列 | PC token |
| 8 | `PUT /admin/sensitive-words/{id}` | 修改词条 | 已列 | PC token |
| 9 | `DELETE /admin/sensitive-words/{id}` | 删除词条 | 已列 | PC token |
| 10 | `POST /admin/sensitive-words/import` | 批量导入 | **新增** | PC token |
| 11 | `GET /admin/sensitive-words/export` | 导出词库 | **新增** | PC token |
| 12 | `GET /admin/sensitive-word-appeals` | 申诉列表 | 已列 | PC token |
| 13 | `POST /admin/sensitive-word-appeals/{id}/audit` | 申诉处置 | 已列 | PC token |
| 14 | `GET /admin/content-reviews` | 命中记录列表 | 已列 | PC token |
| 15 | `GET /admin/content-reviews/{id}` | 命中详情 + 上下文 | **新增** | PC token |
| 16 | `POST /admin/content-reviews/{id}/audit` | 审核处置 | 已列 | PC token |
| 17 | `GET /admin/users/{id}/violations` | 违规记录列表 | **新增** | PC token |
| 18 | `GET /admin/tickets` | 工单列表 | 已列 | PC token |
| 19 | `GET /admin/tickets/{id}` | 工单详情 | 已列 | PC token |
| 20 | `POST /admin/tickets/{id}/assign` | 分配 | 已列 | PC token |
| 21 | `POST /admin/tickets/{id}/reply` | 回复 | 已列 | PC token |
| 22 | `POST /admin/tickets/{id}/close` | 关闭 | 已列 | PC token |
| 23 | `GET /admin/tickets/{id}/timeline` | 处理记录时间线 | **新增** | PC token |
| 24 | `GET /admin/tickets/export` | 导出工单 | **新增** | PC token |
| 25 | `GET /admin/announcements` | 公告列表 | 已列 | PC token |
| 26 | `POST /admin/announcements` | 新建公告 | 已列 | PC token |
| 27 | `GET /admin/announcements/{id}` | 公告详情 | **新增** | PC token |
| 28 | `POST /admin/announcements/{id}/push` | 发送 / 取消定时 | 已列 | PC token |
| 29 | `GET /admin/announcements/estimate` | 预计触达人数 | **新增** | PC token |
| 30 | `GET /admin/notify-templates` | 模板列表 | 已列 | PC token |
| 31 | `POST /admin/notify-templates` | 新建模板 | 已列 | PC token |
| 32 | `PUT /admin/notify-templates/{id}` | 修改模板 | 已列 | PC token |
| 33 | `DELETE /admin/notify-templates/{id}` | 停用模板 | **新增** | PC token |
| 34 | `POST /admin/notify-templates/{id}/test` | 测试发送 | **新增** | PC token |
| 35 | `GET /admin/operation-logs` | 操作日志 | 已列 | PC token |
| 36 | `GET /admin/operation-logs/export` | 导出操作日志 | **新增** | PC token |
| 37 | `GET /admin/login-logs` | 登录日志 | 已列 | PC token |
| 38 | `GET /admin/login-logs/export` | 导出登录日志 | **新增** | PC token |
| 39 | `GET /admin/ops/health-report` | 每日巡检报告 | **新增** | PC token |
| 40 | `GET /admin/exports/{taskId}/download` | 异步导出下载 | **新增** | PC token |

#### C 端（14 个：新增 9）

| # | 方法 + 路径 | 用途 | 新增? | 鉴权 |
|---|---|---|---|---|
| 41 | `POST /app/content-appeals` | 提交误判申诉 | **新增** | App JWT |
| 42 | `POST /app/complaints` | 提交投诉 | 已列 | App JWT |
| 43 | `GET /app/complaints` | 我的投诉列表 | **新增** | App JWT |
| 44 | `POST /app/feedback` | 提交反馈 | 已列 | App JWT |
| 45 | `GET /app/feedback` | 我的反馈列表 | **新增** | App JWT |
| 46 | `GET /app/social/favorites` | 收藏列表（三类型） | **新增** | App JWT |
| 47 | `DELETE /app/social/favorites` | 取消收藏 | **新增** | App JWT |
| 48 | `GET /app/social/follows` | 关注律师列表 | **新增** | App JWT |
| 49 | `GET /app/social/follow-count` | 关注数 | **新增** | App JWT |
| 50 | `GET /app/account/enterprise` | 企业认证状态 | **新增** | App JWT |
| 51 | `GET /pub/faq` | 常见问题 | 已列 | 公开 |
| 52 | `GET /pub/app/version` | 版本检查 | 已列 | 公开 |
| 53 | `GET /pub/app/config` | 客服时段 / 电话等公开配置 | **新增** | 公开 |
| 54 | `POST /app/notifications/{id}/read` | 标记已读（**增强：公告打开率 +1**） | 段 2 已有 | App JWT |

**复用但不重做的接口**（段 1/2/5 已交付，本段只调用）：`POST /admin/users/{id}/ban`、`/unban`、`POST /admin/lawyers/{id}/violations`、`POST /app/upload/token`、`GET /app/upload/url`、`GET /admin/orders`、`GET /app/notifications`。

### 5.3 APP 页面（10 页）

| # | 页面 | 设计稿 | 行数 | 主要接口 |
|---|---|---|---|---|
| 1 | 设置页 | `41-settings.html` | 426 | 无（本地）+ `/pub/app/version` |
| 2 | 企业认证页 | `49-verify-company.html` | 553 | `GET/POST /app/account/enterprise` |
| 3 | 我的收藏页 | `51-favorites.html` | 470 | `GET/DELETE /app/social/favorites` |
| 4 | 关注律师列表 | `52-following.html` | 489 | `GET /app/social/follows` |
| 5 | 客服中心页 | `55-support.html` | 397 | `GET /pub/faq` + `GET /pub/app/config` |
| 6 | 常见问题页 | `56-faq.html` | 383 | `GET /pub/faq` |
| 7 | 投诉页 | `57-complaint.html` | 369 | `POST/GET /app/complaints` |
| 8 | 意见反馈页 | `58-feedback.html` | 302 | `POST/GET /app/feedback` |
| 9 | 关于我们页 | `59-about.html` | 331 | `GET /pub/app/config` |
| 10 | 功能总览页 | `61-all-features.html` | 431 | 无（纯路由） |

> `41b-version-update.html` **不新增页面**，实现为弹窗（E5）。

### 5.4 PC 页面（8 页）

| # | 页面 | 设计稿 | 行数 | 主要接口 |
|---|---|---|---|---|
| 1 | A-02 数据看板 | `01-dashboard.html` | 570 | 5 个（summary/trends/regions/todos/export） |
| 2 | A-20 内容审核队列 | `19-review-queue.html` | 418 | 3 个 |
| 3 | A-21 工单列表 | `20-ticket-list.html` | 405 | 4 个（列表/导出/分配/关闭） |
| 4 | A-22 工单详情 | `21-ticket-detail.html` | 297 | 4 个（详情/时间线/回复/关闭） |
| 5 | A-23 公告与推送 | `22-announcement.html` | 282 | 5 个 |
| 6 | A-24 通知模板 | `23-notify-template.html` | 355 | 5 个 |
| 7 | A-25 敏感词管理 | `24-sensitive-word.html` | 391 | 8 个 |
| 8 | A-26 日志中心 | `25-logs.html` | 351 | 4 个 |

### 5.5 脚本、配置与文档

| 类 | 文件 | 说明 |
|---|---|---|
| 监控 | `scripts/monitor/start-monitor.ps1` / `stop-monitor.ps1` / `prometheus.yml` / `alert-rules.yml` / `grafana-dashboard.json` | 本机二进制，非 Docker |
| 故障注入 | `scripts/monitor/fault-inject.ps1` | `-mode 500\|slow\|down` |
| 备份 | `scripts/backup/backup.ps1` / `restore.ps1` | 含 `faze_restore` 硬保护 |
| 告警 | `scripts/alert/mock-receiver.py` | 无钉钉时的本地接收端 |
| 配置 | `sys_config` **11 个新键** | 见 A1 第 4 步 + E5 的 4 个版本键 |
| 文档 | `docs/acceptance/seg7/A-02-指标口径.md` | `7-A10` 的证据 |
| 文档 | `docs/acceptance/seg7/挂账.md` | 挂账清单 |
| 文档 | `docs/ops/运维手册.md`（**新建**） | 值班、告警、备份、恢复、监控启动 |
| i18n | `zh-Hans/ops.json` 约 85 个 key | APP 40 + PC 45 |
| 依赖登记 | `docs/architecture/依赖清单.md` 补 4 项 | actuator / micrometer-prometheus / logstash-encoder / echarts |

---

## 六、已知风险与出口

| # | 风险 | 概率 | 影响 | 出口 |
|---|---|---|---|---|
| R1 | **不接阿里云内容安全 → 审核能力弱** | 必然（裁决 12） | 小语种、图片、变异词识别不到；`7-A2` 只验文本词库能过，但**上线后会有漏网** | 本段只做本地词库（范围内）；**段 8 前的上线检查清单里必须有一条「接入云审核」**，写进 §八.2；小语种人工审核岗由运营负责（技术路线 §16.2.2 已定） |
| R2 | **钉钉 webhook 拿不到** | 中 | `7-A8` 无法正面验收 | 用 `scripts/alert/mock-receiver.py` 证明「系统真的发出 HTTP POST」；`7-A8` 记**挂账**，补验方式写在 `挂账.md` |
| R3 | **A-20 管理员要看全文的诉求压过来** | 高（人性） | 一旦开口子，隐私合规红线破 | §零.4 第 2 条是红线，**不做**。要看全文走「用户主动提交的截图」（投诉工单）；如负责人坚持，需书面裁决并改 §零.4 |
| R4 | **日志清理误删** | 中（不可逆） | 审计证据消失，合规风险 | 先归档后删；归档失败则不删（`410016`）；清理任务**只删超期行**，不做 `TRUNCATE`；脚本里 `WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)` 且 `LIMIT 10000` 分批 |
| R5 | **一个人值班 → 告警没人看** | 必然（裁决 20） | 告警形同虚设 | 如实写「P0 立即响应，其余次日 9:00–21:00」；告警消息里分级标注；**不写 7×24 的假承诺** |
| R6 | **11 张表里 5 张上游已建（`notification`/`complaint`/`feedback`/`user_violation`/`faq`）** | 中 | 撞名或字段不一致 | 开工前置第 2/3 项已拦；冲突时**以实际表为准**，本段只加不删，差异记 §十一 |
| R7 | **看板全表 COUNT 随数据量变慢** | 低（当前无数据） | A-02 打开慢 | 10 分钟缓存（A5）；> 50 万行改预聚合（代码注释已写升级路径）；`7-A10` 只要求口径正确，不要求性能 |
| R8 | **`content_review` 的 `biz_id` 跨表引用无法外键约束** | 必然 | 孤儿记录 | 不加外键（跨域表设计如此）；A-20 列表对远端查不到的 `biz_id` 显示「内容已删除」 |
| R9 | **敏感词热更新的 Pub/Sub 在多实例下丢消息** | 中 | 某实例用旧词库 | A2 兜底：本实例保存后本地直接重载；其他实例漏了会在下次词库改动时一起更新；记 §八.2「多实例部署前需改为版本号轮询」 |
| R10 | **批量导入 5000 行超时** | 中 | 导入失败 | 降到 1000 行同步 + 超出走 `task_queue`；事务边界按批（不整批回滚，失败行返回行号） |
| R11 | **公告发送 10 万用户同步阻塞** | 中（用户量起来后） | 接口超时 | 分批 500 + 走 `task_queue` + `status='sending'` 显示进度；**绝不同步发全量** |
| R12 | **Grafana 面板 JSON 版本不兼容** | 中 | 导入失败 | 面板 JSON 里写 `schemaVersion`，并附「手搭 4 个图的步骤」作为兜底文档 |
| R13 | **`operation_log` 段 1 建错字段（`detail` 是 TEXT 不是 JSON）** | 中 | 详情抽屉格式化失败 | 运行时按字符串兼容；差异记 §十一；段 8 收口统一 |
| R14 | **A-02 涨跌配色与国内财务习惯相反** | 低 | 视觉争议 | 以设计稿为准；若稿有歧义，记 §十一 待负责人确认，不自作主张 |
| R15 | **本段做完但段 0~6 没做 → 全部验收无法执行** | **高**（当前事实） | 本文档只能是计划 | 零.1 已说明；**段 7 施工必须等段 0~6 交付**（严格串行，总纲 §3.3）；本文档提前写是为了「计划先行」，不是提前施工 |

---

## 七、明确不做（本段不许碰，碰了就是超范围）

| # | 不做 | 理由 | 归属 |
|---|---|---|---|
| 1 | **建 `operation_log` / `login_log`** | 段 1 已建（裁决 3） | 段 1 |
| 2 | **建 `share_poster` 表、分享海报功能** | 总纲没列、无页面、无锚点（裁决 11） | 段 8 之后 |
| 3 | **接阿里云内容安全** | 裁决 12：本段只做本地词库 | 上线前 |
| 4 | **做图片/视频审核** | 无云审核 = 无图片识别能力 | 上线前（附 R1） |
| 5 | **设备指纹 / 黑产对抗** | 无基建（技术路线 §16.3 提到但无落地路径） | 不做 |
| 6 | **真人在线客服（IM 客服）** | APP 稿只有「电话 + 留言」，没有客服对话页 | 不做 |
| 7 | **客服角色与权限细分** | 全局定「只有一个管理员角色」 | 不做 |
| 8 | **数据大屏 / BI 报表 / 自定义报表** | 总纲 §5.8 明确不做 | 不做 |
| 9 | **等保测评 / 渗透测试** | 资质与段 8 | 段 8 |
| 10 | **ELK / Zipkin / Loki 部署** | 需 3 个服务，本机跑不动；JSON 文件日志已满足验收 | 生产部署时 |
| 11 | **Alertmanager 部署** | 用自研 60s 轮询 + 钉钉，更简单（G2 已说明是取舍） | 不做 |
| 12 | **SMS 服务商接入** | 裁决 22：只留配置与开关 | 上线前 |
| 13 | **深色模式主题令牌全量替换** | 影响面大，本段只做开关与持久化（E1） | 段 8 前 |
| 14 | **应用内下载安装 / 热更新** | 段 8 做「热更新通道打通」 | 段 8 |
| 15 | **A-20 的全量聊天原文查看** | §零.4 第 2 条红线 | 不做 |
| 16 | **公告撤回** | 裁决 26 + 稿面「不可撤回」 | 不做 |
| 17 | **四语言文案** | 段 8 收口（与段 4/6 一致） | 段 8 |
| 18 | **改 A-03 / A-04 / A-05 等段 1 页面** | 段 1 已交付；本段只复用其接口 | 段 1 |

---

## 八、留给下一段的接口（我保证什么 / 我不保证什么）

### 八.1 我保证（段 8 可以直接依赖）

1. **治理能力全在**：敏感词可增删改即时生效、命中可标注可申诉、违规可处罚、工单可流转、公告可下发。段 8 只需做译文与上架，**不必碰治理逻辑**。
2. **`sys_config` 里 11 个运营参数可调**：客服时段、SLA、日志留存、告警阈值、敏感词开关。段 8 改运营策略不用发版。
3. **`sensitive_word` / `content_review` / `service_ticket` / `announcement` / `sensitive_word_appeal` 五张表结构稳定**，段 8 做统计与导出可直接用。
4. **监控栈可复用**：`/actuator/prometheus` + Grafana 面板 JSON，生产部署时只改 `prometheus.yml` 的 target。
5. **备份脚本可复用**：改路径与保留天数即可用于生产；`restore.ps1` 的 `faze_restore` 保护逻辑仍有效。
6. **审计动作码 13 个已登记**，段 8 从 `SYS_CONFIG_EDIT` 之后接着编。
7. **错误码 `410005`~`410016` 已登记**，段 8 从 `410017` 起编。
8. **工单号前缀 `TK` / `CP` / `FB` / `AN` 可用**，段 8 若要新增单号只需加前缀值。
9. **导出能力统一**：`CsvExporter` 段 8 的「逐页走查表归档」「性能测试报告」可直接用它导 CSV。
10. **i18n `ops.*` 85 个 key 已就位**，段 8 只补 `ug-Arab-CN` / `bo` / `kk-Arab-CN` 翻译（`.i18n-pending.json` 里已登记）。

### 八.2 我不保证（后续段可能推翻或补做）

| # | 不保证 | 什么时候要补 |
|---|---|---|
| 1 | **云内容审核**（阿里云/腾讯云） | 上线前必须有，否则图片与小语种审核等于没有 |
| 2 | **告警的多通道冗余**（短信/电话/邮件） | 生产环境前，P0 告警不能只靠钉钉 |
| 3 | **ELK / 分布式追踪** | 生产部署时（本段只有 JSON 文件 + traceId） |
| 4 | **看板预聚合表** | 数据量 > 50 万行时（`legal_order` / `balance_flow`） |
| 5 | **多实例下的敏感词热更新一致性** | 部署多实例前改为版本号轮询 |
| 6 | **深色模式主题令牌** | 段 8 前（本段只有开关） |
| 7 | **A-20 的 IM 上下文**（依赖 `conversation_message`） | 段 2 交付后补真实上下文取数（本段是骨架 + TODO） |
| 8 | **应用内下载安装 / 热更新** | 段 8 |
| 9 | **`service_ticket` 的用户确认关闭流** | 本段只做「必填原因 + 发通知」；双向确认流后续补 |
| 10 | **统一社会信用代码校验位算法** | 本段只做长度与字符集；正式上线前补校验位 |
| 11 | **真实 IP 采集**（本机开发是 `127.0.0.1`） | 生产部署时确认反向代理 `X-Forwarded-For` 配置 |
| 12 | **A-02 涨跌配色**（若稿有歧义） | 负责人确认后改一处样式 |
| 13 | **钉钉阈值的双处维护**（规则文件 + `sys_config`） | 段 8 前统一为「规则文件读配置」 |

---

## 九、回写到哪些文档（本段结束必须完成，缺一条算未收口）

> 按 `docs/BASELINE.md` §4 的「四件事」办：**落款 / 登记指纹 / `docs/README.md` 加索引 / `CHANGELOG.md` 说明**。只做一件等于没做。

| # | 文档 | 改什么 | 为什么必须改 |
|---|---|---|---|
| 1 | **《开发计划总纲》§5.8 + §11.2** | ①数据库行：7 张表 → 「**建 11 张**：9 张在册（`notification` `complaint` `feedback` `faq` `user_violation` `notify_template` `sensitive_word` `content_review` `service_ticket`）+ 新增第 54 张 `sensitive_word_appeal`、第 55 张 `announcement`；`operation_log` / `login_log` 段 1 建、本段只读；`share_poster` 不做」；②接口行 → 「54 个（新增 26 + 已列 28），契约见第 7 段工作流 §五 5.2」；③§11.2 清单第 7 行状态改「已产出」+ 规模 | 不改，施工方照「7 张表」建会撞名、少建 4 张 |
| 2 | **《技术路线》§5.1 注 + §5.11.3 + §5.11.1** | ①§5.1 的表总数注补「**v1.34 起 53 → 55 张**（段 7 新增 `sensitive_word_appeal` / `announcement`）」；②§5.11.3 的 `service_ticket` / `sensitive_word` / `content_review` 三段 DDL **补字段**（裁决 6/7/8）；③§5.11 补两段新表的 DDL | 附录 A 与 DDL 是建表唯一依据，字段数不对施工时会少建 |
| 3 | **《技术路线》§4.8.4.3 看板** | 删掉「**A-02 的口径仍未定**」两句，改为「口径已定，见《第 7 段工作流》§零之二 第 1 项与 `docs/acceptance/seg7/A-02-指标口径.md`」；接口表 3 个 → **5 个**（补 `regions` / `todos` / `export`，`online` 并入 `summary`） | 上游明写「未定」，施工方会以为还要等拍板 |
| 4 | **《技术路线》§4.8.2 + §4.8.4.9/§4.8.4.10/§4.8.4.11** | 补 **26 个**新接口（§五 5.2 表里标「新增」的全部）；§4.8.4.9 工单组补 `timeline` / `export`；§4.8.4.10 补 `estimate` / `{id}`；§4.8.4.11 补导入导出/测试/停用/违规记录/健康报告/导出下载 | 接口不在清单 = 联调时对方不知道有它 |
| 4b | **《技术路线》§4.8.4.11 的 A-28 三个接口** | **不动**。A-28（AI 会话检索）页面归**段 3**（总纲 §4.2），本段不实现它的接口；但在 §4.8.4.11「系统」这一组的标题下加一句「A-28 三个接口归段 3 实现，列在此组仅为按页面编号归位」 | 这一组标题写着「A-25、A-26、A-28」，读的人会以为段 7 要做 A-28 |
| 5 | **《接口规范》§7.3.2（新增小节）** | 加「段 7 的 54 个接口」简表（路径 / 鉴权 / 幂等 / 主要错误码）；**字段级契约留在本文档 §三 各任务与 §五 5.2**，接口规范不重复（其 §7.3 明文「只规定长什么样，不管有哪些」） | 现在接口规范里没有任何一段的段 7 条目 |
| 6 | **《错误码规范》§3.10** | 补 **12 个**码（`410005`~`410016`）；另在 §3.2 账户域补 `420014 error.account.banned`（若段 1 已有则复用） | 不登记会撞号；`7-A4` 的「提示明确」依赖独立错误码 |
| 7 | **《审计与日志规范》§3.2 + §5.2 + §6** | ①§3.2 补 **13 个**审计动作码（`CONTENT_REVIEW_*` ×3 / `SENSITIVE_WORD_*` ×4 / `APPEAL_HANDLE` / `ANNOUNCEMENT_CREATE` / `ANNOUNCEMENT_PUSH` / `TICKET_ASSIGN` / `TICKET_CLOSE` / `TICKET_REPLY`）；**`LOG_EXPORT` 已在 §6.3 定义，不重复登记**，本段只复用；②§5.2 留存年限**不动**（它已是合规下限：登录 ≥180 天 / 操作 ≥3 年）；只补一句「**任何页面的留存文案不得低于本节期限**」；③§6 A-26 的查询维度与索引补 `tickets` 相关 | 审计动作码不登记 = 采集时没有 action 值 |
| 8 | **《数据表规范》附录 A + §一** | ①补第 54 张 `sensitive_word_appeal`（10 字段）、第 55 张 `announcement`（16 字段）；②字段数更新：`service_ticket` 9→13、`sensitive_word` 11→12、`content_review` 10→12；③总数 53 → **55**，段 7 = **11 张**（实建）；④§一「唯一表名」行同步 | 附录 A 是表清单唯一权威；总数是跨文档引用最多的数字 |
| 8b | **设计稿 `design/admin/25-logs.html`（需负责人决定改不改稿）** | 稿内提示语「操作日志保留 90 天，登录日志保留 180 天，到期自动清理」→ 应改为「**操作日志按合规要求长期保留（≥ 3 年），登录日志保留 180 天**」。理由：90 天低于《审计与日志规范》§5.2 的合规下限，**稿与规范冲突时以规范为准** | 验收「视觉与设计稿一致」这条会过不了；不改稿就必须在本文档写明「以本文档为准」 |
| 9 | **《PC 后台开发计划》§二 + §八** | ①A-02 / A-20~A-26 八页的「接口」列由「缺」改为「见第 7 段工作流」；②§八 待定第「看板指标口径」一行标「✅ 已定（第 7 段工作流 §零之二 第 1 项）」 | PC 计划仍写「缺」，读的人会以为没做 |
| 10 | **《未完成需备注》§5.2 + §八** | ①§5.2「管理员能不能看聊天原文」→ 已定（只看命中片段 + 前后各 3 条，不看全文）；②§八 待拍板表里「数据看板指标定义」标已定 | 这是 PC 设计阶段的遗留待定项，段 7 已裁决 |
| 11 | **《技术路线》§16.4 + §17.3** | §16.4 客服时段「9:00-21:00」→「**9:00-20:00**（以 APP 设计稿为准）」；§17.3 日志保留补一句「**审计日志（`operation_log` ≥ 3 年 / `login_log` ≥ 180 天，以《审计与日志规范》§5.2 为准）与文件日志（30/90 天）是两套口径**」 | 两个数并存，施工方会按 21:00 写死 |
| 12 | **`docs/README.md`** | 加本文档索引一行（段 7 工作流，含 8 组 41 任务 / 10 锚点 / 11 表 / 54 接口 / 18 页） | 索引是入口 |
| 13 | **`docs/CHANGELOG.md` + `docs/BASELINE.md`** | CHANGELOG 新增一条「2026-11-02 · 新增《第 7 段工作流 v1.0》」，写明 11 处冲突与 **26 项裁决**、26 个新增接口、2 张新表；BASELINE 基线 v1.33 → **v1.34**（资产 **35 → 36**），登记指纹 | 基线不做等于文档没有版本 |
| 14 | **`docs/architecture/依赖清单.md`** | 补 4 项：`spring-boot-starter-actuator` / `micrometer-registry-prometheus` / `logstash-logback-encoder` / `echarts` | AGENTS.md §4.12「新增依赖必须先说明理由并登记」 |
| 15 | **`docs/ops/运维手册.md`**（新建） | 值班与响应 / 告警通道与阈值 / 备份策略与恢复步骤 / 监控启动 / 故障分级 | `7-A8` / `7-A9` 的出口要求「写进运维文档」 |

---

## 十、待负责人拍板（本段开工前或施工中必须有人回答，不许「假设已定」）

| # | 事项 | 影响 | 最晚什么时候 |
|---|---|---|---|
| 1 | ~~看板口径~~ ✅ **已定（2026-11-02）** | DAU / 留存 / 收入 / 时间口径见 §零之二 第 1 项 | 已定 |
| 2 | ~~告警通道~~ ✅ **已定（2026-11-02）：钉钉机器人** | 需要**实际提供** webhook 地址与加签密钥 | 段 7 开工前给到；给不到则 `7-A8` 挂账 |
| 3 | ~~段 7 表数 / 申诉表 / 公告表~~ ✅ **已定（2026-11-02）** | 建 11 张，新增第 54、55 张 | 已定 |
| 4 | **客服电话**（`0999-XXXXXXX` 占位） | APP 55 / 59 页、封禁提示、A-22 详情 | 段 7 开工前（可先用占位，但**不许编一个真号**） |
| 5 | **ICP 备案号 / App 备案号** | APP 59 关于页 | 段 8 上架前（本段用占位文案 + TODO 注释） |
| 6 | **正式敏感词库**（本段只给 50 条示例） | 上线后的审核效果 | 上线前（段 8） |
| 7 | **`faq` 正式内容**（本段给 7 条稿面对应的答案草稿） | APP 56 页显示内容 | 段 8 前运营补充 |
| 8 | **`notify_template` 正式文案**（本段给 6 条） | 通知内容 | 段 8 前 |
| 9 | **A-02 涨跌配色**（红涨绿跌 vs 绿涨红跌） | A-02 视觉 | 段 7 开工前若稿有歧义 |
| 10 | **公告是否允许定向到单个用户**（`user_ids` 上限 5000） | A-23 的人群选择 | 段 7 施工中 |

---

## 十一、实际完成与偏差（施工后回写，不删原文）

> 施工完成后在这里追加，**不改上面的内容**。每条写：计划 vs 实际、偏差原因、是否影响下一段。

| # | 计划 | 实际 | 偏差原因 | 影响 |
|---|---|---|---|---|
| | | | | |

---

## 附：本文档的生成方式与已知局限

- **生成日期**：2026-11-02（晚间）。负责人同日就 26 项拍板（§零之二：4 项硬缺口逐条确认 + 22 项授权「按建议处理」）。
- **依据的实测**：仓库 `HEAD 32892e5`（分支 `UI设计`，基线 v1.33）；工作区干净；`design/admin/` 8 屏与 `design/ui/` 10 屏用脚本提取过结构（标题 / Tab / 表头 / 按钮 / label / 占位文案 / 空态 / 注释意图），18 屏共 **7,438 行**；技术路线 §4.8.4 全部小节、§5.11 相关 DDL、§16、§17 逐段读过。
- **读过的文档**：总纲 §3.1 / §4.1 / §4.2 / §5.7 / §5.8 / §5.9 / §6.1~§6.3 / §7.5 / §七 / §十 / §11.1 / §11.2；技术路线 §2.2 / §4.8.2 / §4.8.4.1~§4.8.4.12 / §5.9.1 / §5.11.1~§5.11.5 / §9.1 / §16.1~§16.5 / §17.1~§17.5 / §18.6；数据表规范 §1.2 / §3.11 / §4.1 / §8.1 / 附录 A / 附录 B；接口规范 §1.3.1 / §3.3 / §4.1 / §5.1 / §5.2 / §5.7 / §6.2 / §6.3 / §6.4 / §6.5 / §6.6 / §7.3 / §八 / §九；审计与日志规范 §3.2 / §3.3 / §4.5 / §5.1~§5.3 / §6 / §7；错误码规范 §2.1 / §3.2 / §3.10；i18n 规范（key 三段式与四语言同构）；后端代码规范 §三 / §九 / §十 / §十四；PC 后台开发计划 §二 / §八；未完成需备注 §5.1 / §5.2 / §八；段 6 工作流 §零 / §八 / §九 / §十（作为格式与严谨度基准）。
- **已知局限**：
  1. **本文档是计划，不是施工记录**。段 0~6 一行代码没写（零.1 已说明），所以 §二 的所有「上游已交付」都是**预测**，施工前必须逐条复核。
  2. **A-02 的口径虽已定，但「今日活跃用户」的「任意业务请求」口径依赖访问日志/Redis 日活集合**，该基建在段 0/1 是否落地未验证。若没有，按 A5「卡住怎么办」降级为「登录去重」，**降级必须写进口径文档**。
  3. **`sensitive_word_appeal` 与 `announcement` 两张表的字段是本段设计的**，不是上游既有定义。施工时若发现与 `service_ticket` / `notify_template` 职责重叠，**优先复用既有表**，并在 §十一 记录。
  4. **26 个新增接口的路径是本段拟定的**，遵循接口规范 §3 的前缀与命名规则，但**尚未经接口规范作者复核**；联调前建议再审一遍（尤其 `/admin/exports/{taskId}/download` 是否应放 `/admin/files/**`）。
  5. **`Grafana + Prometheus` 的本机二进制方案**是本段取舍（裁决 18），开发/验收期可用，**生产部署时必须重评**（监控独立、告警多通道）。
  6. **13 个审计动作码中有 3 个与既有命名风格不完全一致**（`TICKET_ASSIGN` / `TICKET_CLOSE` / `TICKET_REPLY` 用「表名前缀」，而既有用「业务语义」如 `WITHDRAW_APPROVE`）。为一致性可改成 `TICKET_ASSIGN` 保持，若规范作者要求改成 `SERVICE_TICKET_*`，在 §九 第 7 项一并改。
