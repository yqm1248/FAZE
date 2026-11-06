# 第 2 段 · G9「PC 后台 4 页」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：RuoYi 3.9.2（8080 / 库 `faze_admin`）+ faze-api 8081
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G9（4 个任务）、§四 `2-A4` / `2-A6` / `2-A7`
> 说明：管理端 token 走**真实 RuoYi 登录**（`POST http://127.0.0.1:8080/login`，`admin` / `admin123`），
> 经 `AdminAuthenticationFilter` 校验 Redis db0 的 `login_tokens:<token>` 后还原 `ROLE_ADMIN`。
> 所有接口实测都带这个 token 打 8081。

---

## 一、交付物

| 任务 | 交付 |
|---|---|
| G9.1 A-11 订单列表 | 后端 `GET /api/v1/admin/orders`（RuoYi 口径 rows/total，字段与筛选见下）；前端 `views/biz/trade/order/index.vue` + `api/biz/order.js`（状态/时间/律师/用户筛选、分页、CSV 导出留异步分支注释） |
| G9.2 A-12 订单详情 | `GET /admin/orders/{id}`、`GET /admin/orders/{id}/status-log`、复用 `GET /admin/refunds?orderId=`、`POST /admin/refunds/{id}/audit`、`GET /admin/conversations/{id}/messages`；前端 `detail.vue`（信息卡 + 状态流转时间线 + 支付记录 + 退款单审批弹窗 + 聊天记录抽屉） |
| G9.3 A-14 文章列表 | `GET /admin/articles`（服务端一次聚合出四语言完成度 `languages`）；前端 `views/biz/content/article/index.vue`（完成度徽标 4/4 + 上架/下架） |
| G9.4 A-15 文章编辑 | `GET/POST/PUT /admin/articles[/{id}]`、`POST /admin/articles/{id}/publish`、`POST /admin/articles/{id}/offline`；前端 `edit.vue`（源内容 + 藏/维/哈三 tab + 正文 JSON 块编辑 + 保存草稿 / 保存并上架） |
| 菜单 | `sys_menu` 2109~2112（订单管理 / 订单详情[隐藏] / 文章管理 / 文章编辑[隐藏]），admin 角色默认全通过 |
| 审计 | 4 个文章写方法各一个 `@OperationLog`；聊天记录查看**破例**标审计（拍板第 7 项） |

---

## 二、接口实测（真实 admin token，2026-11-06 15:11）

| # | 场景 | 实测 |
|---|---|---|
| 1 | RuoYi 登录 | `http=200 code=200` 拿到 token |
| 2 | `GET /admin/orders?pageSize=5` | `total=13` `rows=5` 首行 `OR20261106000720 / refunding`，**手机号脱敏为 `139****0009`** |
| 3 | `GET /admin/orders?status=completed` | `total=2`，两行状态都是 completed（筛选生效） |
| 4 | `GET /admin/orders/12` | `status=completed`、**支付记录 2 条**（拍板第 5 项）、退款单 2 条、`conversationId=1`（拍板第 6 项） |
| 5 | `GET /admin/orders/12/status-log` | 9 条完整链路：`-→pending → paid → refunding → paid → refunding → refund_rejected → paid → in_service → completed`（**含退款节点，不是时间戳拼的**） |
| 6 | `GET /admin/refunds?orderId=12` | 2 条 |
| 7 | `GET /admin/articles` | `total=1`，种子文章 `languages=["zh-Hans"]`（维语只有标题、无正文 → 不算完整） |
| 8 | 四语言不齐上架（只有中文的草稿） | `code=401006` **被拒**（HTTP 200 + 业务码，管理端口径） |
| 9 | 补齐三语后上架 | `code=200`，`languages=["zh-Hans","bo","ug-Arab-CN","kk-Arab-CN"]` → **4/4** |
| 10 | 下架 | `code=200`；APP 列表随即不可见 |
| 11 | `GET /admin/conversations/1/messages?limit=10` | 6 条，**含已撤回消息且原文可见**（`recalled=true && content != null`） |

---

## 三、审计留痕（`operation_log` 真实数据）

```sql
SELECT operator_id, operator_type, module, action, target_type, target_id, result FROM operation_log ORDER BY id DESC LIMIT 6;
-- 1 admin content  ARTICLE_OFFLINE   ARTICLE  NULL success
-- 1 admin content  ARTICLE_PUBLISH   ARTICLE  NULL success
-- 1 admin content  ARTICLE_UPDATE    ARTICLE  NULL success
-- 1 admin content  ARTICLE_CREATE    ARTICLE  NULL success
-- 1 admin conversation CHAT_VIEW     CONVERSATION 1  success   ← 拍板第 7 项：查看聊天原文必须留痕
-- 1 admin content  ARTICLE_OFFLINE   ARTICLE  NULL success
```

- 文章四个写接口的审计由 `@OperationLog` 采集（标在 Service 方法上，不标 Controller）。
- **聊天记录查看是查询类审计的例外**：每次打开抽屉与翻页都会调接口 → 每次都写一条
  `CHAT_VIEW`，`target_id` = 会话 id（`AuditContext.mark` 补的，否则只有 module/action 看不出看了哪段）。

---

## 四、`2-A7` 全链路（PC 录入 → APP 可见）

| 步骤 | 实测 |
|---|---|
| 1. PC 新建文章（四语言齐全） | `POST /admin/articles` → `id=2` |
| 2. 上架 | `POST /admin/articles/2/publish` → `code=200` |
| 3. APP 首页/列表能看到 | `GET /pub/articles` → `total=2`，标题包含「G9 验证：从 PC 录入的一篇文章」 |
| 4. 切维吾尔文 | `GET /pub/articles?lang=ug-Arab-CN` → 标题为维语、`langFallback=false` |
| 5. 下架后不可见 | 下架后再查 APP 列表，该文章不在 |

**判定**：`2-A7` 的「PC 录入 → APP 首页显示（不是假数据）」与「切语言显示译文」在接口级通过；
法条引用块渲染在 G8 的 `13-article` 已验（块类型 `law_ref` + 原文补全）。
**页面级**（浏览器登录后台点菜单截图）本轮未做，见偏差 1。

---

## 五、`2-A4` / `2-A6` 的页面支撑

- **状态流转时间线**：数据来自 `order_status_log`（实测 9 条链路），`detail.vue` 用 `el-timeline`
  画 `from → to` + 操作人 + 原因 + 时间；流水为空时显示「历史记录缺失，不补假数据」。
- **退款审批入口**：`detail.vue` 的退款单表里只有 `applying` 行有「审批」按钮，
  弹窗通过/驳回（驳回必填原因），调用 G4.2 的 `POST /admin/refunds/{id}/audit`，
  审批成功整体重拉；审批本身已在 G4 用真实 token 验过（含 `ORDER_REFUND_APPROVED/REJECTED` 审计）。
- **支付记录**：段 2 只有余额渠道，退款行为负数用红色区分；页面注明「服务费结算见段 5」。
- **关联会话**：显示 `conversation.id`（无会话时写「尚未建立会话」），**不编造 CV 编号**。

---

## 六、构建与门禁

- PC 前端 `npx vite build` **构建成功**（4 个新页面的 import/模板/样式全部通过编译）。
- PC 前端**没有 lint/build 脚本**（`package.json` 只有 `dev`），所以门禁 = vite build + 后端编译。
- 后端 `mvn -q -DskipTests compile` 通过；本节接口全部真实调用验证。

---

## 七、偏差与遗留（如实记录）

1. **页面级浏览器验收未做**：菜单已注册（`sys_menu` 2109~2112）、`vite build` 通过、接口全通，
   但没有「登录后台 → 点菜单 → 页面渲染」的截图证据。缺的是浏览器自动化/人工点击，
   不是代码。**建议段 2 收口时由人工登录 `http://localhost` 点一遍四个页面**。
2. **文章正文用 JSON 文本框编辑**（不是富文本）：正文是结构化块数组，富文本会丢 `law_ref` 的
   `lawItemId` 绑定；工作流 G9.4「卡住怎么办」明确允许 textarea 兜底、富文本增强挂账。
3. **上架失败的「缺哪种语言」提示在前端做**：服务端的 `R.fail(ErrorCode)` 只回通用 msg
   （`R.fail` 取 `ErrorCode.msg`，自定义 detail 只进日志），所以列表页用服务端返回的
   `languages` 本地预判并提示缺失语言；服务端仍然兜底拒绝（防绕过）。编辑页没有 `languages`
   数据，只能依赖服务端拒绝 + 通用提示。
4. **管理端 Service 没有单测**：`AdminOrderService` / `AdminArticleService` /
   `AdminConversationService` 是查询与字段搬运为主，本轮用真实接口验证覆盖；
   写路径（上架校验）虽有接口级验证，但没有 Mockito 单测——**这是本组唯一的测试缺口**，
   在收口清单里记为待补。
5. **导出是前端同步 CSV（上限 1000 行）**：工作流允许段 2 同步导出并留异步分支；
   代码注释标明了「超过 1000 行应改任务表异步导出（段 7）」。
6. **订单列表「导出当前筛选」不导出全量**：明确按钮文案是「导出当前筛选」，避免运营误以为导全库。
7. **PC 菜单是手工 SQL 插入的**：RuoYi 的菜单在 `faze_admin.sys_menu`，段 2 用 SQL 补 4 行
   （`tmp_menu_g9.sql` 执行后已删除）；生产部署应把这段 SQL 纳入部署脚本（段 8）。
