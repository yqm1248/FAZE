# T2b · APP 新增第 69 页「我的投诉」列表 — 验收证据

> 段 7 · 负责人 2026-11-09 拍板新开第 69 页（原 68 页清单外 +1）· APP 端负责人：fe-app
> 证据截图：`tmp/seg7e/70-*.png` ~ `76-*.png`；方式：CDP 真实 Chrome 390×844、mobile 触摸视口。
> 后端：`GET /api/v1/app/complaints?pageNum=&pageSize=`（be-api 已交付，状态映射 2026-11-09 修复为
> `waiting|processing|replied|closed`，带 `replyContent`/`replyAt`/`ticketNo`）。

## 一、交付清单

| 项 | 文件 |
|---|---|
| 页面实现 | `features/ops/pages/ComplaintListPage.tsx` |
| 路由壳（一行导出） | `app/complaint-list.tsx` |
| 路由常量 | `shared/navigation/routes.ts` 新增 `PAGE_ROUTES.complaintList = '/complaint-list'` |
| 类型修正 | `features/ops/types.ts`：`ComplaintItem.status` 注释改为工单四态 + 新增 `ticketStatus?`；`OpsPage` 增加可选 `hasMore` |
| i18n | `zh-Hans/ops.json` 新增 `complaintList.*`（20 条）；`zh-Hans/profile.json` 新增 `my.menuComplaints` |
| 入口 1 | `features/ops/pages/ComplaintPage.tsx`（57 页）：顶部提示块下新增「查看我的投诉」入口；**提交成功态** ActionBar 新增「查看我的投诉」主按钮（原「返回」降为 outline 次按钮） |
| 入口 2 | `features/profile/pages/MyPage.tsx`：「我的与服务」分组新增「我的投诉」行 |

## 二、门禁（真实退出码，最终一轮）

| 门禁 | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 类型检查 | `npx tsc --noEmit` | **0** | 无输出 |
| ESLint | `npx eslint . --ext .ts,.tsx` | **0** | 0 error / 0 warning |
| 裸文本 | `check-raw-text.ps1` | **0** | 扫描 185 个 tsx，命中 0 |
| i18n | `check-i18n.ps1` | **0** | 四目录同构通过；缺译登记 **1818 条**（原 1797 + 新增 **21 条**：`ops.complaintList.*` 20 + `profile.my.menuComplaints` 1） |

## 三、验收点逐条实测（全部真实浏览器）

| # | 验收点 | 怎么验 | 看到什么 | 截图 |
|---|---|---|---|---|
| a | **列表里有真实数据且能看到后台回复内容** | 用 `13900008888`（user 9324）登录后打开 `/complaint-list` | 5 条投诉按时间倒序；每条含对象标签「服务问题」、状态徽标、正文、提交时间、工单号（`TK20261109000018/17/15/07/05`）；**「客服回复」块（浅蓝底）显示回复内容 + 回复时间** | `70-complaint-list.png` |
| b | **状态徽标显示「已回复」不是 pending** | 同上 | 徽标文字「已回复」（绿字浅底）；列表里另有「待处理」（橙字浅底） | `70` |
| — | **四态徽标全部有文字** | 临时把 9324 的两条工单状态改成 processing / closed（截图后已恢复 waiting） | 「处理中」（蓝字浅底）、「已关闭」（灰字浅底）与「已回复」「待处理」四种徽标并列出现，**不依赖颜色也能读** | `75-complaint-status-badges.png`、`76-complaint-status-badges-bottom.png` |
| c | **空态** | 换用没有投诉记录的账号 `13900009001` 登录（未用调试分支、未写死数据） | 「还没有投诉记录」+ 说明「提交投诉后，处理进度和客服回复会出现在这里。」+「去提交投诉」按钮 | `72-complaint-list-empty.png` |
| d | **底栏不压内容** | 页面滚到底 | 最后一条（`TK20261109000005`「待处理」）完整可见，下方「没有更多了」+ `spacing.xxl` 留白；页面无固定底栏 | `71-complaint-list-bottom.png` |
| — | 分页 | `hasMore=false`（total=5 < pageSize=10） | 不出现「加载更多」，显示「没有更多了」 | `71` |
| — | 入口（投诉页顶部） | 打开 `/complaint` | 顶部提示块下方出现「查看我的投诉」入口行（receipt 图标 + 文案 + 箭头） | `74-complaint-page-entry.png` |
| — | 入口（我的页） | `/profile` 滚到「我的与服务」 | 「客服中心 / 投诉 / **我的投诉** / 意见反馈」四行 | `73-profile-complaints-entry.png` |
| — | 入口（提交成功态） | 代码 | `ComplaintPage` 成功态 ActionBar 两个按钮：主「查看我的投诉」→ `/complaint-list`、次「返回」。未真实提交造数据，未截图（见 §五 偏差 3） | 代码 |

## 四、页面实现要点

1. **四态齐全**：加载（`LoadingState rows=4`）/ 错误（`ErrorState` + 重试，复用统一组件）/ 空（自定义块 + 去提交投诉）/ 正常。
2. **状态徽标**：`statusMeta()` 把 `waiting/processing/replied/closed` 映射成「文案 + 底色 + 字色」，
   未知值按「待处理」兜底（不会出现空徽标）。**每枚徽标都有文字**，颜色只做辅助。
3. **后台回复**：`replyContent` 非空才渲染回复块，右上角是 `replyAt`（`formatServerDateTime` 转本机时区）。
4. **截图缩略**：`images` 是 JSON 字符串 → `parseImageKeys()`；每张 key 懒加载短时签名地址
   （`getDownloadUrl`），拿不到时显示灰底占位块（不塌陷布局）。当前数据 `images=null`，未触发网络请求。
5. **分页**：首屏 10 条；`hasMore`（后端下发，缺省按 `已加载 < total` 推算）为真时给「加载更多」；
   加载更多失败只 toast，**不清空已加载数据**。
6. **令牌**：颜色/字号/间距/圆角全部来自 `shared/theme/tokens.ts`；无 `#RRGGBB`/`rgba()`；无 left/right。
7. **注释**：文件头块注释（职责/路由/数据来源/注意事项）+ 每个导出物与私有函数都有注释。

## 五、偏差与未接通

1. **「加载更多」按钮未真机/真数据触发**：9324 的投诉共 5 条 < 每页 10，`hasMore=false`，所以本次只验证了
   「没有更多了」分支。按钮代码与失败隔离逻辑已实现；要验证可分页：把 `PAGE_SIZE` 临时改 2 或再造 6 条投诉。
2. **错误态未在浏览器里造**：错误态复用 `ErrorState`（段 1 起全站同一实现），本次没有断网/停机造 500；
   与其它列表页同构，风险低。要补验：停 faze-api 后打开本页，应出现错误文案 + 重试。
3. **提交成功态入口未截图**：需要在 57 页真实提交一条投诉才会出现；为避免给 dev 库再写测试投诉，
   只做了顶部入口截图（`74`）。成功态入口是同一路由常量、同一 `PrimaryButton`，代码已就位。
4. **截图缩略未做真实数据验收**：`images` 全为 null（历史投诉没上传截图）。代码路径已实现，
   要补验：提交一条带截图的投诉（`scene=complaint` 上传）后回到本页看缩略图。
5. **验收过程改过库**（已恢复）：
   - 9324 的 `TK20261109000007` → processing、`TK20261109000005` → closed，截图后恢复 waiting；
   - 另两条其他用户的工单（0016/0014）曾误改成 processing/closed（不在 9324 列表里、截图无变化），已恢复 waiting；
   - 用 `13900009001` 登录只读列表，**未写入任何投诉数据**。
6. **页面在 68 页清单外新增**：页面数口径由 docs-writer 改为 69 页；`design/ui/` 没有第 69 页设计稿，
   视觉按 57 投诉页与 51 收藏页的既有范式拼（卡片列表 + 徽标 + 回复块），记 §十一。

## 六、坑与结论

1. **cdp-shot 每次打开新 target 不会自动带登录态**：登录态要先用 `--eval-file` 写 `localStorage` 再跳转。
   本轮用已保存 token 生成注入脚本（`tmp/seg7e/gen-nav.mjs`），避免反复登录触发短信 60 秒限流。
2. **短信发码限流（401007）**：同号 60 秒内只能发一次；`prepare-login.mjs` 在限流时登录失败，
   改用「已保存 token + gen-nav.mjs」绕过。
3. **后端计数/状态是字符串**：`status` 是字符串枚举（不是数字）；`counts` 类字段此前也是字符串，
   本页只做等值判断，不受影响。
4. **改中文源码仍只用 write/edit 工具**（本轮未用 PowerShell 文本管道），无编码事故。
5. 8090 由 Lead 看护循环托管：本轮每次截图前 `curl` 确认 200，未遇到 Metro 崩溃。

---

## Lead 补验（2026-11-09 03:0x，负责人要求「先去核验一遍」）

前面 4 项「未真数据触发」的补验结果，逐条实测：

| # | 原未验项 | 补验方式 | 结果 |
|---|---|---|---|
| 1 | **「加载更多」真数据触发** | 用 API 给 `13900008888` 造 6 条投诉（`TK20261109000019`~`000024`），`total` 变 **11 > pageSize 10**；CDP 真实浏览器打开 `/complaint-list` | ✅ **通过**。首屏 10 条、**「加载更多」可见、「没有更多了」不可见**；点一次后 **11 条**、**「加载更多」消失、「没有更多了」出现**（DOM 精确判定：只统计 `offsetParent !== null` 且自身文本完全等于目标串的元素） |
| 2 | **错误态** | 临时停掉 faze-api（kill 8081 进程，`curl` 得 `000`），再打开 `/complaint-list` | ✅ **通过**。页面显示「**网络连接失败，请检查网络后重试**」+「重试」；重启 API 后重新加载恢复正常、11 条数据都在 |
| 3 | **提交成功态入口截图** | 尝试用 DOM 操作填 textarea（原生 setter + input/change 事件）+ 合成 click 点「提交投诉」 | ❌ **未验成**。正文能填进去（43/500），但**提交没生效**：合成 `MouseEvent` 对 ActionBar 里的按钮无效（T2 上一轮也踩过同一条：企业认证「重新提交」必须用 CDP 坐标点击）。**需要 `Input.dispatchMouseEvent` 真鼠标坐标点击**才能截图，本轮没做 |
| 4 | **带截图缩略** | 需要给投诉上传图片（`WebFileButton` → 原生文件选择器） | ❌ **未验成**。文件选择器无法用 DOM 合成事件驱动，需 CDP `DOM.setFileInputFiles`。历史投诉 `images` 全为 `null`，所以缩略分支从未真正渲染过 |

**结论**：4 项里 **2 项已补验通过**（分页、错误态），**2 项仍未验**（提交成功态截图、带图缩略），原因都是「需要 CDP 底层输入 API，合成的 DOM 事件不生效」，已写进 `挂账.md`。

**测试数据**：为验分页新增 6 条投诉（`TK20261109000019`~`000024`，`app_user` id=9324），未回复、无图片；清理 SQL 见 `挂账.md` 末节。