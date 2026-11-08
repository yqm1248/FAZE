# `7-A6` 操作日志 · 验收证据

> **验收人**：Lead（独立复核，后台界面侧由 T3 fe-pc 实测，两边数据交叉核对）
> **时间**：2026-11-09 02:08（Asia/Shanghai）
> **方式**：做一次真实的**封禁→解封**，然后在 A-26 与数据库里各查一遍，核对「谁 / 何时 / 从哪 / 改了什么」四项
> **证据**：`tmp/seg7h/pc-a26-real.png`（A-26 列表）、`tmp/seg7f/FINAL-a26-op.png` / `a26-detail.png`（T3 的详情抽屉实测）
> **被验对象**：`operation_log` 表 + `OperationLogAspect` + `AdminOperationLogService` + A-26 页面

## 1. 结论：**四项齐全，可读性达标；一个已知缺口**

| 锚点四问 | 实测 | 判定 |
|---|---|---|
| **谁** | `operator_id=1` → 页面显示「管理员 #1」。**`operatorName` 恒 null**（见 §4） | ⚠️ 有 id 无姓名 |
| **何时** | `created_at = 2026-11-08 18:08:17`（精确到秒），页面显示 `2026-11-09 02:08`（+8 时区） | ✅ |
| **从哪** | `ip = 127.0.0.1`（本机 dev；锚点允许，但要求说明） | ✅（附说明） |
| **改了什么** | `detail = {"before":{"status":"blocked"},"after":{"status":"active","reason":"7-A4 锚点验收结束，恢复账号"}}` —— **before / after / reason 三样都在** | ✅ |

## 2. 数据库原文（`SELECT ... ORDER BY id DESC LIMIT 3`）

```text
id=2119492230386212865  operator=1  module=ops      action=TICKET_REPLY   target=SERVICE_TICKET#18  ip=127.0.0.1  result=success  detail={"replyLength": 27}  created_at=2026-11-08 18:08:21
id=2119492229929033729  operator=1  module=ops      action=TICKET_ASSIGN  target=SERVICE_TICKET#18  ip=127.0.0.1  result=success  detail={"agentId": 1}       created_at=2026-11-08 18:08:21
id=2119492216708587521  operator=1  module=account  action=USER_UNBANNED  target=APP_USER#9324      ip=127.0.0.1  result=success  detail={"after":{"reason":"7-A4 锚点验收结束，恢复账号","status":"active"},"before":{"status":"blocked"}}  created_at=2026-11-08 18:08:17
```

`USER_UNBANNED` 那条就是本次验收动作，四项齐全。

## 3. A-26 界面侧（T3 实测，独立于后端自述）

- 操作日志 Tab：实测 **261 条**（当时），8 列表头「时间 / 操作人 / 模块 / 动作 / 目标对象 / 来源 IP / 结果 / 操作」
- 首行显示：`2026-11-09 01:37 | 管理员 #1 | 运营治理 | 导出日志 | DASHBOARD | 127.0.0.1 | 成功 | 详情`
- **目标对象显示可读名**，前端不拼 id：实测「工单#15」「敏感词#152」；未覆盖的类型原样显示 `CODE#id`
- **详情抽屉是 before/after 对比表格，不是裸 JSON**：实测行「字段 状态 status：变更前 `blocked` → 变更后 `active`」+「原因 reason：— → 7-A4 锚点验收结束，恢复账号」，变更行高亮
- **非法 JSON 降级**：注入 `detail='这不是 JSON {broken'` → 显示 warning「detail 不是合法 JSON，已按原文展示」+ 原文文本块，不白屏
- **导出即审计**：导出操作日志/登录日志各生成一条 `LOG_EXPORT`，在列表里能看到（自身也被审计）

## 4. 已知缺口（§十一 第 12 / 35 条）

1. **`operatorName` 恒为 null**：`operation_log.operator_id` 是 `faze_admin.sys_user.id`（**跨库**），业务库 `faze_app` 无权读 RuoYi 用户表。页面因此只显示「管理员 #1」，筛选也只支持管理员 ID，并在页面上明写「本段后端未提供管理员 ID → 姓名/账号查询通道，页面不伪造账号名」。**段 8 补通道**。
2. **`ip` 是 `127.0.0.1`**：本机开发必然如此。锚点原文允许，但生产必须确认反向代理 `X-Forwarded-For`（§八.2 第 11 项）。
3. **`operation_log` 是只追加表**：DB 账号无 `UPDATE/DELETE` 权限（B 类追加表的安全设计），所以本批验收产生的审计行**无法清理**，会永久留在库里。这不是缺陷。

## 5. 复现方式

1. 后台做一次封禁 + 解封（`POST /admin/users/9324/ban` → `/unban`）
2. 打开 `http://localhost/biz/ops/log-center` → 操作日志 Tab → 按操作人 `1` + 时间筛选
3. 点「详情」→ 看「变更明细」对比表
4. 交叉核对：`SELECT operator_id,module,action,target_type,target_id,ip,result,detail,created_at FROM operation_log ORDER BY id DESC LIMIT 3;`
