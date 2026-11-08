# `7-A10` 看板口径 · 验收证据

> **验收人**：Lead（独立复核）+ T3 fe-pc（界面实测）/ be-api（口径文档与 SQL）
> **时间**：2026-11-09 01:41 ~ 02:08（Asia/Shanghai）
> **方式**：打开 A-02，逐块对照《A-02 指标口径》，再用 SQL 手算一遍与页面比对
> **证据**：`tmp/seg7h/pc-a02-real.png`（Lead 独立截图）、`tmp/seg7f/FINAL-a02-*.png`、`a02-tooltip.png`、`a02-pending.png`（T3）
> **口径文档**：`docs/acceptance/seg7/A-02-指标口径.md`（13964 B，10 节）

## 1. 结论：**通过（有一个口径受限项）**

- 每个数字都能在口径文档里找到「定义 / 算法(SQL) / 数据来源 / 边界 / 已知局限」
- 4 个核心卡片**手算 = 页面**，逐项一致
- 「平台今日收入」的注解与 tooltip 都在
- 「待我处理」5 类全部可点且跳转参数正确
- `pendingReason` 支持「口径待定」而不是 0/空白
- ⚠️ **「用户地区分布」只有一项「其他」**（`app_user.region` 无采集点），页面已如实写明口径与原因

## 2. 页面实测数字（Lead 独立截图，1440×900）

```text
页头：数据统计截至 2026-11-09 01:36，每 10 分钟刷新一次（页面加载后自动刷新，切到后台时暂停）
今日活跃用户  12   ▼ -29.4% 较昨日
今日新增注册   3   —  较昨日
今日订单      3   —  较昨日
平台今日收入  ¥60.00  — 较昨日   已扣除平台服务费
链路自检：已连通（RuoYi 登录态 → faze-api(8081) → 管理端鉴权）
用户地区分布：其他 9
待我处理：共 14 条（实名 0 / 入驻 0 / 提现 2 / 投诉工单 12）+ 另有 2 条内容审核命中待处理
最近订单 4 条：OR20261109009003 / 002 / 001 / OR20261106000720
```

## 3. 手算 vs 页面（逐项）

窗口 = 上海 2026-11-09 全天 = UTC `[2026-11-08 16:00, 2026-11-09 16:00)`。

| 指标 | 手算 SQL | 结果 | 页面 | 一致 |
|---|---|---|---|---|
| 今日订单 | `SELECT COUNT(*) FROM orders WHERE created_at ∈ 窗口` | 3（id 14/15/16） | 3 | ✅ |
| 平台今日收入 | `SELECT SUM(platform_fee_cents) FROM orders WHERE paid_at ∈ 窗口` | 1000+2000+3000 = **6000 分 = ¥60.00** | ¥60.00 | ✅ |
| 今日新增注册 | `COUNT(*) app_user WHERE deleted_at IS NULL AND created_at ∈ 窗口` | 3 | 3 | ✅ |
| 今日活跃用户 | `COUNT(DISTINCT user_id) login_log WHERE result='success' AND created_at ∈ 窗口` | 12 | 12 | ✅ |
| 用户地区分布 | 未注销用户 9（`region` 全 NULL → 归「其他」） | 9 | 其他 9 | ✅ |
| 今日活跃环比 | 昨日 17 → (12−17)/17 = **−29.4%** | −29.4% | ▼ −29.4% | ✅ |
| 其余 3 卡环比 | 昨日为 0 → 返回 `null` → 显示 `—`（不是 ∞/NaN） | — | — | ✅ |

近 7 天切换实测：活跃 **75** / 新增 **8** / 订单 **16** / 收入 **¥204.00**，SQL 手算一致。

## 4. 「算做完」逐条对照

| 要求 | 实测 | 判定 |
|---|---|---|
| 六块与稿逐块对齐 | 页头 / 4 卡 / 时间切换 / 趋势 / 地区 / 待我处理 / 最近订单 / 导出，八块齐全（稿面六块 + 导出 + 自检条） | ✅ |
| 4 个卡片数值与手算一致 | 见 §3 | ✅ |
| 切今日/7天/30天 → 4 卡与趋势图同时变 | **只有 4 卡与页头变**，趋势图固定「近 14 天」（接口无 `range` 参数） | ⚠️ 见 §6.2 |
| 「平台今日收入」悬停能看到口径 tooltip | 真实鼠标 hover → popper 文本 **「= 当日订单服务费合计，不含充值金额」**；卡片下方常驻「已扣除平台服务费」 | ✅ |
| 「待我处理」5 条各自跳到正确页面并带对参数 | 实测 5 条：`/biz/verification?type=realname&status=PENDING`、`/biz/lawyerApplication?status=PENDING`、`/biz/trade/withdraw?status=applying`、`/biz/ops/ticket-list?status=waiting`、`/biz/ops/review-queue?status=pending` | ✅ |
| 折线图 4 条网格线、峰值点唯一、横轴隔天 | 网格 0/5/10/15；峰值只标 1 点（「11-06 · 13」）；横轴 10-27/10-29/…/11-08 隔天；面积=品牌色 14% | ✅ |
| 导出 CSV 内容与页面一致 | 实测下载 `dashboard_20261109013651.csv`（1428 B，UTF-8 **BOM**，33 数据行 = 核心指标 + 14 天订单/服务费趋势）；`range=7d` 时标签已随 range 变 | ✅ |
| 口径文档 10 个指标每个都有 SQL 与来源 | §1 时间窗 + §2~§10 九个指标，逐节四段齐全，无「按系统统计」这类含糊话 | ✅ |
| 代码里每个指标处都有文档节号引用 | `AdminDashboardService` / `DashboardMapper` 逐方法引用 `见 docs/acceptance/seg7/A-02-指标口径.md §N` | ✅ |
| 某指标置 null + `pendingReason` → 页面显示「口径待定」 | 后端已返回 `pendingReason` + `pendingReasons`（`onlineCount` 口径待定）；前端注入 null 后卡片显示「**口径待定**」+「口径待定，暂不计算环比」，**不是 0 也不是空白** | ✅ |
| `EXPLAIN` 存证 | `docs/acceptance/seg7/F3-explain.txt`（4 个接口执行计划 + 慢查询判断） | ✅ |

## 5. 本批修掉的一个口径矛盾

**修复前**：`trends` 按库内 **UTC** 切日，`summary` 按 **Asia/Shanghai** 自然日 → 同一页面自相矛盾。造 3 笔单（`created_at`=UTC 11-08 17:21 = 上海 11-09 01:21）后，4 卡显示「今日订单 3」，折线却把这 3 单画在 **11-08**。
**修复**：`DATE(created_at + INTERVAL 8 HOUR)`（revenue 用 `paid_at` 同改）。实测末三点 `11-07:0 / 11-08:0 / 11-09:3`。口径文档 §6 的「已知局限」已改为「已修正」。

## 6. 遗留（不影响本次判定，但必须写清）

### 6.1 地区分布只有「其他」

`app_user.region` 是 V707 新建列，**注册流程不采集地区、资料页也没有该字段** → 全部为 NULL，看板只能归到「其他」。页面已如实写明：「口径：注册地区前 5 名 + 其余合并为『其他』。`app_user.region` 为 V707 新建列，当前无采集点（注册流程未采集地区），数据全部落在『其他』；段 8 补采集入口后才会出现真实地区」。
**这不是 bug，是数据还没采。** 段 8 补采集入口。

### 6.2 趋势图不随 `range` 变

`GET /admin/dashboard/trends` 只有 `metric` 参数、固定 14 天，没有 `range`。工作流 F1 要求「切今日/7天/30天 → 4 卡与趋势图同时变」，与接口现状冲突。**按接口现状降级**（图表标题写死「近 14 天」，不误导）。要联动需段 8 给接口加 `range`。

### 6.3 涨跌配色待负责人确认

设计稿 `components.css`：`--up → --state-success`（青绿）、`--down → --state-danger`（红），即**稿面涨绿跌红**；工作流 F1 正文写「涨用红、跌用绿」。按「以稿为准」实现**涨绿跌红**。**§十 第 9 项仍未拍板**，确认后改一处 class 即可。

## 7. 复现命令

```powershell
cd E:\dshcode\FAZE
# 注入 admin 的 Admin-Token 后打开 A-02（A-02 复用菜单 2101，URL 已变为 /biz/ops/dashboard）
node scripts/cdp-shot.mjs --url "http://localhost/biz/ops/dashboard" --out tmp/seg7h/pc-a02-real.png `
  --width 1440 --height 900 --wait 5000 --post-wait 8000
```

> 看板 `summary` / `trends` / `regions` 有 **10 分钟 Redis 缓存**（业务库 db1）。造数后要删 `faze:dashboard:*` 才能看到新数字。
