# `7-A5` 投诉工单 · 验收证据

> **验收人**：Lead（独立复核）
> **时间**：2026-11-09 02:08:20（Asia/Shanghai）
> **方式**：APP 接口提交 → 后台接口受理 → APP 接口回查（**双向**）
> **脚本**：`tmp/seg7h/anchor-run.mjs A5`（原始输出 `tmp/seg7h/anchor-A5.log` / `.json`）
> **被验对象**：`POST/GET /app/complaints` + `GET /admin/tickets` + `/assign` + `/reply` + `notification`

## 1. 结论：**接口级通过，界面级部分完成**

服务端双向链路全部打通（提交 → 受理 → 回复 → APP 能看到状态与回复 + 站内信）。
**但 APP 侧没有「我的投诉列表」界面**（见 §4），所以锚点要求的「App 端投诉列表看到」只有**接口证据**，没有界面证据。

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

### 3.2 APP 无投诉列表界面（**未修，属页面归属问题**）

- `design/ui/57-complaint.html` **只有提交表单**（逐行核过：无列表、无记录区）
- 总纲 §4.2 的 68 页清单里没有「我的投诉列表」
- 本段 §七 明文「不许加页」

所以 `7-A5` 的「App 端投诉列表：看到状态 replied + 回复内容」**在现有页面归属下无法产出界面证据**。
**处置**：本锚点记「接口级通过 + 界面级未完成」，挂账（见 `挂账.md`）。**要新开一页、还是把记录区并进 57 页，需负责人/段 8 拍板。**

## 4. 与工作流「算做完」逐条对照

| 工作流要求 | 状态 |
|---|---|
| App 提交投诉（关联一笔已完成订单） | ✅ 平台类投诉不需关联订单；律师类投诉强制关联已完成订单（前置校验实测过） |
| 后台 A-21 看到该工单（`waiting`，未超时） | ✅ 16 条待处理里命中 |
| A-22 能关联到订单号、用户（脱敏手机号） | ✅ |
| 点「分配」→ 状态变 `processing` | ✅ 库内实测 |
| 填回复 → 「发送回复」→ 状态变 `replied` | ✅ 库内实测 |
| **App 端投诉列表**：看到状态 `replied` + 回复内容 | ⚠️ **接口可以，界面不存在** |
| App 消息中心：收到一条「工单已回复」站内信 | ✅ `notification` 有行；APP `/messages` 页可读（E 组之外，段 2 已交付） |

## 5. 复现命令

```powershell
cd E:\dshcode\FAZE
node tmp/seg7h/anchor-run.mjs A5
```

> 脚本每跑一次会新建一条投诉 + 一条工单 + 一条 notification（`app_user` id=9324）。清理 SQL 见 `挂账.md` 末节。
