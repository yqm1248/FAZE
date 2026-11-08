段 6 B4 · PC A-09「渠道对账」Tab 验收证据（2026-11-08 重做）
生成时间：2026-11-08 22:20（东八区）
环境：PC 前端 vite dev @ http://localhost（1440×900，CDP 真实 Chrome 渲染）
      RuoYi 后端 8080（admin/admin123，captchaEnabled=false）· faze-api 8081 · 库 faze
说明：本文件是 D3/D4 补齐后的**重做版**。第一版（19:26）的 Tab2 数据是手工 INSERT 的，
      不是 Mock 账单跑出来的，按 B4「算做完」第 ① 条不算通过。本版全部数据由
      `recharge_reconcile_download` → `recharge_reconcile_compare` 任务真实产出。

实现文件：
  pc/admin-web/src/views/biz/reconciliation/index.vue       外层包 el-tabs（Tab1 内容原样）
  pc/admin-web/src/views/biz/reconciliation/ChannelDiffPanel.vue  Tab2（筛选/表格/处置/分页）
  pc/admin-web/src/api/biz/reconciliation.js               listChannelDiffs / handleChannelDiff
  services/faze-api/.../AdminReconciliationController.java  4 个路由（见下）
  services/faze-api/.../AdminReconciliationService.java     pageChannelDiffs / handleChannelDiff
                                                            / exportChannelDiffs / buildChannelDiffWrapper

路由：
  GET /api/v1/admin/reconciliation/channel-diffs          差异列表（4 筛选 + 分页）
  GET /api/v1/admin/reconciliation/channel-diffs/export   差异 CSV 导出（同筛选，不分页）
  PUT /api/v1/admin/reconciliation/channel-diffs/{id}     标记已处理（备注必填 ≤500 字，写审计）

──── 截图 1：Tab1 恒等式巡检（B4-tab1-identity.png）────────────────────
巡检口径 alert、巡检摘要 6 个指标（检查主体 4 / 平衡 4 / 不平衡 0 / 被检查余额合计
1035.18 元 / 差异总额 0.00 / 差异条目 0 条）、差异列表「差异 = 0：恒等式全部平衡」空态。
内容区与段 1 交付时一致；唯一变化是上方多了一条 el-tabs 的 Tab 条（B4 第 1 条的必然结果）。

──── 截图 2：Tab2 空表（B4-tab2-empty.png）──────────────────────────
筛选栏 4 个条件齐全（账单日期区间**默认「昨日」**、渠道、差异类型、处理状态），
表格 8 列 + 操作列，空表显示「暂无差异」——不塞占位数据（B4 第 7 条）。
这一张是 2026-11-08 22:02 截的：当时库里差异都在 11-08，页面默认筛 11-07，所以是空表。
**这正是「默认昨日」这个产品行为的真实验证**，不是没数据。

──── 截图 3：Tab2 有数据（B4-tab2-channel.png）──────────────────────
数据来源：`recharge_reconcile_download(20261107)` → `recharge_reconcile_compare(mock:20261107)`
两任务真实跑出来的 3 条差异（详见 `6-A4-reconcile.txt` §三）。

| 账单日期 | 渠道 | 差异类型 | 渠道交易号 | 本地单号 | 差异金额 | 状态 |
|---|---|---|---|---|---|---|
| 2026-11-07 | Mock 通道 | 本地有、渠道无（橙） | MOCKTXNRC20261107990004 | RC20261107990004 | -300.00 | 待处理（橙） |
| 2026-11-07 | Mock 通道 | 渠道有、本地无（红） | MOCKTXNC07 | RC20261107990003 | +200.00 | 已自动修复（灰） |
| 2026-11-07 | Mock 通道 | 金额不一致（橙） | MOCKTXNRC20261107990002 | RC20261107990002 | -1.00 | 待处理（橙） |

验证点：
- **三类差异齐全**（B4 算做完第 ① 条）：channel_only / local_only / amount_mismatch
- 颜色语义正确：channel_only / identity_mismatch 红，amount_mismatch / local_only 橙
- 金额带符号两位小数，整数分换算（-30000 分 → -300.00，20000 分 → +200.00，-100 分 → -1.00）
- 「已自动修复」来自 D4 的 channel_only 自动补单（补偿单 RC20261107990003-C + 流水 +20000 分）
- 本地单号来自 left join recharge_record；channel_only 无本地单时显示「—」
- **发现时间有值**（2026-11-08 22:07:20，东八区）：D4 落库时写 `found_at`
- 共 3 条；分页「20 条/页」

──── 接口实测（RuoYi token，Invoke-RestMethod）─────────────────────────
1) GET channel-diffs?beginDate=2026-11-08&endDate=2026-11-08&pageSize=50
   → code=200，total=**6**：4 条渠道差异（bill_date=11-08）+ **2 条恒等式差异**
     （bill_date 为 NULL，按 found_at 落进 11-08 区间）。
   这条同时验证了日期筛选的**双口径修复**：只按 bill_date 筛时那 2 条恒等式差异
   永远筛不到（B4 §3 默认昨日、§5 又要求展示 4 类，两者原本是互斥的）。
2) GET channel-diffs?pageSize=50（不传日期）→ total=6（全部）
3) GET channel-diffs/export?beginDate=2026-11-07&endDate=2026-11-07
   → HTTP 200，Content-Type: text/csv;charset=UTF-8，
     Content-Disposition: attachment; filename="reconcile-diffs.csv"，463 字节
     内容与列表逐行一致（见 6-A4-diffs-export.csv）
4) PUT channel-diffs/17 {"handleNote":"D4 验收：确认渠道多记 1 分，已与渠道核对"}
   → code=200；DB 复核 status=handled、handler_id=1、handle_note 落库、handled_at 非空
5) 同一 id 重复 PUT → code=200 且备注未被覆盖（幂等，保留第一个运营写的备注）
6) PUT channel-diffs/18 {"handleNote":""} → code=401002（备注必填）
7) GET /diffs（段 1 的恒等式接口）、GET /summary、POST /run 均未受影响

审计：处置走 `@OperationLog(module="trade", action="RECONCILE_HANDLE",
targetType="RECONCILIATION_DIFF")`，detail 含 before/after 状态与备注，
操作人从 AuditContext 取（handler_id=1 = admin）。

──── B4「算做完」5 条逐条对照 ───────────────────────────────────────
① Tab2 能拉到 3 类差异（Mock 账单造的）→ **通过**。本版数据由 D3/D4 任务真实产出，
   不是手工 INSERT。三条 diff_type 分别为 local_only / channel_only / amount_mismatch。
② 标记已处理后 status 变 handled、备注落库、列表刷新 → **通过**（见接口实测 4~6）。
③ Tab1 与段 1 交付时截图逐像素一致 → **不通过，接受偏差**：上方多了 Tab 条，
   这是 B4 第 1 条「外层包 el-tabs」的必然结果；Tab1 内容区未改一行。
   B4「卡住怎么办」也写了「不要为了塞 Tab 去改段 1 的布局骨架」，同理反方向也不该
   为了像素一致而放弃 Tab 壳。已记 §十一。
④ 空表显示「暂无差异」→ **通过**（截图 2）。
⑤ 无权限账号看不到「标记已处理」按钮 → **不通过，负责人 2026-11-08 裁决不做**。
   裁决：不引入 RuoYi 细粒度权限点（与《接口规范》§6.2「不做数据级权限细分」、
   《开发计划总纲》§5.8「不做多角色权限细分」、`AdminSession`「只取必要字段」三处
   已拍板决策一致；且后台只有一个业务角色，权限点建起来没有第二个角色可分配）。
   **替代动作**：停用 RuoYi 内置演示账号 `ry`（`sys_user.status='1'`）。
   实测：`POST /login {"username":"ry","password":"admin123"}`
   → `code=500`、`msg=用户已封禁，请联系管理员`、无 token；admin 仍可正常登录。
   当前后台账号面收敛为 admin 一个。
   本项记为**文档冲突偏差**（B4 §8 要求权限点 vs 三份文档禁止细粒度权限），
   由负责人裁决以「不做 + 停用 ry」落地。

──── 构建验证 ───────────────────────────────────────────────────────
npx vite build → 成功（无语法/模板错误）

──── 偏差与裁决（记入 §十一）─────────────────────────────────────────
1. **新增 `/channel-diffs` 与 `/channel-diffs/export`，没复用 `/diffs`**：工作流 B4 第 3、5 条
   写的是 `GET /admin/reconciliation/diffs?date=`，但该路径段 5 已被「恒等式巡检内存态
   差异」占用（返回 List 且无筛选参数），Tab1 依赖它且已验收。拆开是本段的选择。
2. **状态有两个「待处理」值**：段 5 的巡检写 `open`，段 6 的渠道差异写 `pending`。
   页面下拉合并成「待处理」一项，后端用 `IN ('open','pending')` 查询；处置统一置 `handled`。
3. **差异金额列按 diffType 取字段**：渠道差异读 `amountCents`（D4 字段映射写的就是它），
   恒等式差异读 `diffCents`（段 5 巡检写的）。第一版只读 `diffCents`，渠道差异全部显示
   `+0.00`——**单测和接口都发现不了，只有真实截图暴露**。
4. 权限点不做 + 停用 ry，见上文 ⑤。
