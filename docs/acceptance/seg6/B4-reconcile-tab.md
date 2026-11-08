段 6 B4 · PC A-09「渠道对账」Tab 验收证据
生成时间：2026-11-08 19:26（东八区）
环境：PC 前端 vite dev @ http://localhost:80（1440×900，CDP 真实 Chrome 渲染）
      RuoYi 后端 8080（admin/admin123，captchaEnabled=false）
      faze-api 8081（B4 代码已重启加载）
      库 faze / MySQL 8.0.44

实现文件：
  pc/admin-web/src/views/biz/reconciliation/index.vue          外层包 el-tabs（Tab1 内容原样）
  pc/admin-web/src/views/biz/reconciliation/ChannelDiffPanel.vue  Tab2 组件（筛选/表格/处置/分页）
  pc/admin-web/src/api/biz/reconciliation.js                   加 listChannelDiffs / handleChannelDiff
  services/faze-api/.../admin/controller/AdminReconciliationController.java  加 2 个路由
  services/faze-api/.../admin/service/AdminReconciliationService.java        pageChannelDiffs / handleChannelDiff
  services/faze-api/.../admin/bo/ChannelDiffQueryBO.java、ChannelDiffHandleBO.java
  services/faze-api/.../admin/vo/ChannelDiffRowVO.java

路由：
  GET /api/v1/admin/reconciliation/channel-diffs         差异列表（4 筛选 + 分页）
  PUT /api/v1/admin/reconciliation/channel-diffs/{id}    标记已处理（备注必填 ≤500 字，写审计）

──── 截图 1：Tab1 恒等式巡检（B4-tab1-identity.png）────────────────────
段 1 交付的内容**原样保留**：巡检口径 alert、巡检摘要 6 个指标（检查主体 4 / 平衡 4 /
不平衡 0 / 被检查余额合计 235.18 元 / 差异总额 0.00 / 差异条目 0 条）、
差异列表「差异 = 0：恒等式全部平衡」空态。
变化只有一处：内容上方多了 el-tabs 的 Tab 条（这是 B4 唯一允许的改动）。

──── 截图 2：Tab2 空表（B4-tab2-empty.png）──────────────────────────
筛选栏 4 个条件齐全（账单日期区间默认「昨日」、渠道、差异类型、处理状态），
表格 8 列 + 操作列，空表显示「暂无差异」——不塞占位数据（B4 第 7 条）。

──── 截图 3：Tab2 有数据（B4-tab2-channel.png）──────────────────────
两条由 Mock 造的差异（截图后已清理，D4 会用真实账单重跑）：

| 账单日期 | 渠道 | 差异类型 | 渠道交易号 | 本地单号 | 差异金额 | 状态 | 操作 |
|---|---|---|---|---|---|---|---|
| 2026-11-07 | Mock 通道 | 金额不一致（橙） | MOCK-B4-TXN-002 | RC202611080000… | -5.00 | 待处理（橙） | 标记已处理 |
| 2026-11-07 | Mock 通道 | 渠道有、本地无（红） | MOCK-B4-TXN-001 | — | +100.00 | 已处理（绿） | 已处理 |

验证点：
- 差异类型的颜色语义正确：channel_only / identity_mismatch 红，amount_mismatch / local_only 橙
- 差异金额带符号、两位小数、整数分换算（-500 分 → -5.00，10000 分 → +100.00）
- 本地单号来自 left join recharge_record；channel_only 行没有本地单 → 显示「—」
- 已处理的行不显示「标记已处理」按钮
- 分页「共 2 条」

──── 接口实测（curl / Invoke-RestMethod，admin token）─────────────────
1) GET channel-diffs?pageNum=1&pageSize=10
   → code=200，total=3，list 3 条（含一条巡检写的 identity_mismatch/open），
     orderNo 正确 join（RC20261108000001），id 与 diffCents 序列化正常。
     注意 status=open 的那条 bill_date 为 null（段 5 巡检写入时没设账单日期）——
     这是**真实数据质量问题**，不影响 B4 功能；D4 写渠道差异时会强制带 bill_date。
2) PUT channel-diffs/2 {"handleNote":"…"}  → code=200
   DB 复核：status=handled、handler_id=1、handle_note 落库、handled_at 非空
3) 同一 id 重复 PUT → code=200 且**备注未被覆盖**（幂等，保留第一个运营写的备注）
4) PUT channel-diffs/3 {"handleNote":""} → code=401002（备注必填）
5) GET /diffs（段 1 的恒等式接口）、GET /summary、POST /run 均未受影响

审计：处置走 `@OperationLog(module="trade", action="RECONCILE_HANDLE",
targetType="RECONCILIATION_DIFF")`，detail 含 before/after 状态与备注，
操作人从 AuditContext 取（实测 handler_id=1 = admin）。

──── 构建验证 ───────────────────────────────────────────────────────
npx vite build → 成功（无语法/模板错误；产物 1759KB index + 各 chunk）

──── 偏差与裁决（记入 §十一）─────────────────────────────────────────
1. **新增了 `/channel-diffs` 路由，没复用 `/diffs`**：工作流 B4 第 3、5 条写的是
   `GET /admin/reconciliation/diffs?date=`，但该路径在段 1 已被「恒等式巡检内存态差异」
   占用（返回 List 且无筛选参数），Tab1 依赖它且已验收。直接改会让 Tab1 的契约变化。
   两条路由语义不同（现算 vs 落库），拆开是本段的选择。
2. **状态有两个「待处理」值**：段 5 的巡检写 `open`，段 6 的渠道差异写 `pending`。
   页面下拉合并成「待处理」一项，后端用 `IN ('open','pending')` 查询；
   处置统一置 `handled`。这个不一致是段 5 已落库的事实，本段不迁移历史数据。
3. **Tab1 与段 1 交付的截图不是逐像素一致**：上方多了 Tab 条。这是「外层包 el-tabs」
   的必然结果（B4 第 1 条要求），Tab1 的**内容区**未改任何一行。
4. 权限点 `finance:reconcile:list` / `finance:reconcile:handle` **未接**：
   本段管理端鉴权沿用段 1~5 的「admin token 校验」，未引入 RuoYi 菜单权限点体系
   （那需要同时改 RuoYi 菜单与 faze-api 的鉴权注解，属跨段基础设施）。
   当前效果：所有能进 PC 后台的管理员都能看/处置差异。记入 §十一 与 §十。
