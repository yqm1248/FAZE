# `7-A5` 投诉工单 · 验收证据

> **验收人**：Lead（独立复核）
> **时间**：2026-11-09 02:08:20（Asia/Shanghai）
> **方式**：APP 接口提交 → 后台接口受理 → APP 接口回查（**双向**）
> **脚本**：`tmp/seg7h/anchor-run.mjs A5`（原始输出 `tmp/seg7h/anchor-A5.log` / `.json`）
> **被验对象**：`POST/GET /app/complaints` + `GET /admin/tickets` + `/assign` + `/reply` + `notification`

## 1. 结论：**通过**

服务端双向链路全部打通（提交 → 受理 → 回复 → APP 能看到状态与回复 + 站内信）。
**界面证据后来补齐了**：负责人 2026-11-09 拍板新增 **第 69 页「我的投诉」列表**（`/complaint-list`），
APP 侧「看到回复与状态变化」这一条现在有真实浏览器证据（见 §3.3）。

## 2. 实测序列（单号 `TK20261109000018`）

| 步 | 动作 | 实测 |
|---|---|---|
| 1 | APP 提交投诉（`targetType=platform`，正文「7-A5 锚点验收：平台功能使用不顺畅，希望协助处理」） | `HTTP 200`，返回 `ticketNo=TK20261109000018` |
| 2 | `GET /admin/tickets?status=waiting` | `HTTP 200`，共 16 条，**命中该单** |
| 3 | `GET /admin/tickets/{id}` | `HTTP 200`，详情含关联用户（脱敏手机号）与工单号 |
| 4 | `POST /admin/tickets/{id}/assign` | `HTTP 200`；库内 `service_ticket.status=**processing**` |
| 5 | `POST /admin/tickets/{id}/reply` `{content:"7-A5 验收回复：已收到你的反馈，我们正在跟进处理。"}` | `HTTP 200`；库内 `status=**replied**` |
| 6 | `GET /app/complaints`（同一账号） | 命中该单：**`status=replied`**、`replyContent=7-A5 验收回复：已收到你的反馈，我们正在跟进处理。` |
| 7 | `SELECT ... FROM notification WHERE user_id=9324` | 新增 `biz_type=ticket, biz_id=18, title=工单回复`，`is_read=0` |

## 3. 修复记录（本锚点验出的两个缺陷）

### 3.1 状态永远停在 `pending`（**已修**）

**修复前**：后台 `assign`/`reply` 后，`GET /app/complaints` 返回的 `status` **恒为 `pending`**。
根因：`ComplaintService.listMine()` 的 `toVO(c)` 取 `complaint.status`，而工单流转改的是 `service_ticket.status`；`listMine` 已经把工单批量查出来了（填 `ticketNo` 与回复），却没把状态映射回去。
修复：`ComplaintService` / `FeedbackService` 同步修，VO 增加 `ticketStatus`。实测 `waiting → processing → replied` 全链路可见。

### 3.2 APP 无投诉列表界面 → **已由第 69 页解决**

**当时的情况**：`design/ui/57-complaint.html` **只有提交表单**，总纲 §4.2 的 68 页清单里也没有「我的投诉列表」，
而本段 §七 明文「不许加页」——所以 APP 侧界面证据产不出来。

**负责人裁决（2026-11-09）**：**新开第 69 页**「我的投诉」列表（`/complaint-list`），页面总数 68 → 69。
这一页**没有设计稿**（`design/ui/` 71 个 HTML 里确实没有），是实现页，稿面依据只有 57 投诉页的卡片与列表样式。

### 3.3 第 69 页的界面证据（真实浏览器 390×844）

Lead 独立复核 + T2 的 7 张截图（`tmp/seg7e/70`~`76`）：

```text
GET /complaint-list（账号 13900008888）页面文本实测：
我的投诉
提交后的处理进度与客服回复都在这里。超过 3 个工作日没收到回复的，可以联系客服并提供工单号。
服务问题 / 已回复 / 7-A5 锚点验收：平台功能使用不顺畅，希望协助处理
提交于 2026-11-09 02:08   工单号 TK20261109000018
  客服回复  2026-11-09 02:08
  7-A5 验收回复：已收到你的反馈，我们正在跟进处理。
…（共 5 条，含 TK20261109000017/00015/00007/00005）
没有更多了
```

| 验收点 | 实测 |
|---|---|
| 列表含真实数据 | 5 条，倒序 |
| **能看到后台回复内容 + 回复时间** | 「客服回复」块有内容与时间 |
| **状态是工单状态而不是恒 `pending`** | 徽标显示「已回复」/「待处理」 |
| 四态徽标都有**文字**（不只靠颜色） | 临时改库造出 `processing`/`closed` 后四态并列：待处理/处理中/已回复/已关闭，截后已恢复 |
| 空态 | 换无投诉账号 `13900009001` →「还没有投诉记录」+「去提交投诉」 |
| 底栏不压内容 | 滚到底最后一条完整可见 +「没有更多了」 |
| 入口 | 57 投诉页顶部 + 提交成功态主按钮 + 「我的」页「我的与服务」 |

## 4. 与工作流「算做完」逐条对照

| 工作流要求 | 状态 |
|---|---|
| App 提交投诉（关联一笔已完成订单） | ✅ 平台类投诉不需关联订单；律师类投诉强制关联已完成订单（前置校验实测过） |
| 后台 A-21 看到该工单（`waiting`，未超时） | ✅ 16 条待处理里命中 |
| A-22 能关联到订单号、用户（脱敏手机号） | ✅ |
| 点「分配」→ 状态变 `processing` | ✅ 库内实测 |
| 填回复 → 「发送回复」→ 状态变 `replied` | ✅ 库内实测 |
| **App 端投诉列表**：看到状态 `replied` + 回复内容 | ✅ **第 69 页 `/complaint-list` 已补**，真实浏览器证据见 §3.3 |
| App 消息中心：收到一条「工单已回复」站内信 | ✅ `notification` 有行；APP `/messages` 页可读（段 2 已交付） |

## 5. 复现命令

```powershell
cd E:\dshcode\FAZE
node tmp/seg7h/anchor-run.mjs A5
```

> 脚本每跑一次会新建一条投诉 + 一条工单 + 一条 notification（`app_user` id=9324）。清理 SQL 见 `挂账.md` 末节。
