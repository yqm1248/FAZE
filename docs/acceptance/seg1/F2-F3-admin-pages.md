# F2 / F3 PC 管理端 7 页与 21 个接口 验收证据

> 段：第 1 段 · F 组 · 任务 F2（A-03/A-04/A-05/A-06）、F3（A-07/A-08/A-09）
> 日期：2026-11-05
> 结论：F2 / F3 **主体通过**；有 1 条验收项未做（A-08「改配置即改行为」的联动验证），见 §五。
> 本文件是**验收证据**，不在《文档基线》登记范围内（基线只登记 `architecture/` 与 `design/` 下的文档）。
> 截图：本目录 `_artifacts/f2-*.png`、`_artifacts/f3-*.png`。

## 一、交付物

| 层 | 内容 |
|---|---|
| 后端 | 21 个 `/api/v1/admin/**` 接口（提交 `dd4fdb2` F2 12 个、`e601e96` F3 9 个）；新增 `modules/account/admin`、`modules/trade/admin`、`modules/ops/admin` 三个包 |
| 前端 | 7 个页面 `src/views/biz/{user,verification,lawyerApplication,lawyer,wallet,sysConfig,reconciliation}/index.vue`；7 个 api 模块 `src/api/biz/*.js`；1 个公共格式化模块 `src/views/biz/shared/format.js` |
| 菜单 | `database/seed/seg1_admin_menu.sql`（`sys_menu` 2100~2108，`faze_admin` 库） |
| 鉴权桥 | 见 `F1-admin-auth-bridge.md`（PC 登录态与 faze-api 管理端接口互认） |

**前端代码不在本仓库**（`E:\itheima\...\RuoYi-Vue3`，见 `pc/README.md`）。**这是本段最大的工程性隐患**，处置见 §六。

## 二、F2 的 5 条二值验收（《第1段工作流》§F2）

| # | 验收项 | 判定 | 证据 |
|---|---|---|---|
| 1 | App 提交实名 → A-04 队列出现 → 通过 → App 状态变「已通过」 | ✅（队列→通过→状态这一段实测；「提交」由 C 组已验） | `realname_auth` id=2（user 1）`pending → approved`、`audited_by=1`；`app_user.id=1.realname_status=none → approved`；`operation_log` 有 `REALNAME_APPROVED` 且 detail 带 before/after |
| 2 | 驳回必填理由，App 能看到理由 | ✅ | 空理由 → `401001`；有理由 → `realname_auth.reject_reason` / `lawyer_application_log.reason` 落库；App 侧 `GET /app/account/realname` 返回 `rejectReason`（C 组已验） |
| 3 | App 提交入驻 → A-05 队列 → 通过 → 状态变「已通过」，四条申请记录可见 | ✅ | `lawyer_application` id=7 `pending → approved`；`lawyer` 新增一行（user 1 / `TEST-LIC-20261105-02`）；`app_user.role=lawyer`；`lawyer_application_log` 追加 `approve`（operator_type=admin、operator_id=1）；四块详情截图 `f2-a05-four-step-detail.png` |
| 4 | A-03 封禁某用户 → 该用户 App 端被拒（401 或明确提示） | ✅ | 实测：App 登录拿 token（封禁前 `GET /app/account/profile` → **200**）→ PC 封禁 user 1 → **同一个旧 token** 立刻 `GET /app/account/profile` → **HTTP 401 + `{code:402003}`** → PC 解封 |
| 5 | 所有审核动作写 `operation_log` | ✅ | `operation_log` 实测含 `USER_BANNED` / `USER_UNBANNED` / `REALNAME_APPROVED` / `REALNAME_REJECTED` / `ENTERPRISE_APPROVED` / `LAWYER_APPLICATION_APPROVED` / `LAWYER_APPLICATION_REJECTED`，`operator_type=admin`、`operator_id=1`、`target_type/target_id` 齐全、detail 均含 before/after |

## 三、F3 的 4 条二值验收（§F3）

| # | 验收项 | 判定 | 证据 |
|---|---|---|---|
| 1 | A-07 充值 100 元走通（锚点 `1-A4`） | ✅ | `POST /admin/wallets/3/recharge` `{amountCents:10000, bizNo:"ACC-20261105-100"}` → `balanceCents 29302 → 39302`；`user_balance(37302+2000=39302) == SUM(balance_flow.delta_cents)=39302` |
| 2 | 重放同一幂等键不重复入账 | ✅ | 同 `Idempotency-Key` 再发一次 → `duplicated:true`，`balanceCents` 仍 39302，`flowId` 仍 105（没有新流水） |
| 3 | A-08 改一个配置项后服务端行为随之变化（如 `pay_pwd_max_fail=3` 则第 3 次就锁） | ❌ **未做** | 见 §五第 1 条。A-08 的**读写本身**已验（`editable` 只读项被拒 → 401006、类型不符 → 401001、保存后 `updated_at` 与审计都对） |
| 4 | A-09 展示「差异 = 0」；手工造差异后能列出 | ✅ | 正常态 `summary{checkedOwners:1, balancedOwners:1, diffOwners:0}` + `diffs:[]` + 页面「差异 = 0」；人为把 `user_balance.balance_cents` 改 +100 → `diffs` 返回 1 行（`cache=29300 / flow=29200 / diff=100`）→ 恢复后归零；`POST /admin/reconciliation/run` 写 `RECONCILE_RUN` 审计，且结果与 `deploy/verify-balance.ps1` 一致 |

## 四、独立复核（不依赖实现者自述）

Lead 另写了一次性脚本按契约逐项打真接口，**23/23 通过**（2026-11-05 01:52）：

```
PASS  AUTH 无 token → HTTP200+code401        PASS  A-04 合并队列 rows/total
PASS  AUTH 有 token → 200                    PASS  A-04 行有 type 与脱敏字段   total=2
PASS  A-03 用户列表 rows/total                PASS  A-05 待审队列 rows/total
PASS  A-03 行字段冻结集  首行 id=3 NORMAL      PASS  A-05 四步详情 + 审核流水
PASS  A-03 用户详情                           PASS  A-06 律师列表 rows/total
PASS  A-06 律师详情                           PASS  A-07 钱包概览 balanceCents=29301 frozen=2000
PASS  A-07 充值（1 分）→ 29302                PASS  A-07 同幂等键重放 duplicated=true
PASS  A-07 幂等不重复入账（余额相等）          PASS  A-07 调账 delta=0 → 405006
PASS  A-07 流水分页                           PASS  A-08 参数列表
PASS  A-08 editable 是 boolean  共 10 项      PASS  A-08 keys 过滤
PASS  A-09 巡检摘要 checked=1 diffOwners=0    PASS  A-09 差异列表
PASS  A-09 手动巡检
========== 23/23 通过 ==========
```

回归：`mvn -B test` → **Tests run: 34, Failures: 0, Errors: 0**（含 F1 的 11 个新增用例）。

## 五、未做 / 挂账

1. **A-08「改配置即改行为」未验**（F3 验收第 3 条）。
   要做的是：`PUT /admin/sys-configs` 把 `pay_pwd_max_fail` 改成 3 → 用 E 组的支付密码校验接口连错 3 次 → 第 3 次应锁定并给 `Retry-After` → 改回 5。
   没做的原因是它需要「已设置支付密码的账号」作为前提数据，实测成本高，留到 H 组收口时连同锚点 `1-A5` 一起做（两件事本来就该一起做）。
2. **A-05 列表 `practiceYears` 恒为 null**：`lawyer_application` 表没有这一列，段 2 补（页面显示「段 2 补」）。
3. **A-09 `summary` 没有「差异总额」字段**：契约只定义了 `totalCheckedCents`（被检查余额合计）。页面用 `/diffs` 的 Σ|diffCents| 另算，并在卡片下注明两者口径不同。**要不要给 summary 加 `totalDiffCents` 留待段 5 建差异表时一并定**。
4. **A-09 的共同盲区**：巡检与 `verify-balance.ps1` 都是「从缓存表出发」找差异，**有流水但没有缓存行的 owner 不在报告内**。段 5 建 `reconciliation_diff` 时要补这条覆盖。
5. **A-07 的 `bizType` 枚举未冻结**：流水筛选目前是文本精确匹配，没有白名单。
6. **A-06 的服务开关只读**：改开关的接口属段 7，本段页面用 `disabled` 的开关 + 「本段只读」标注，没有伪造可编辑。

## 六、本轮修掉的三处真问题（都是联调才暴露的）

| # | 问题 | 后果 | 修法 |
|---|---|---|---|
| 1 | dev CORS 白名单只有 `:8090` | 浏览器对非 GET 一律带 `Origin`，vite 代理只转发不改 Origin → **六个写动作全部 HTTP 403 `Invalid CORS request`**，读接口正常。表现是「只有写操作挂」，极易误判成前端 bug | `application-dev.yml` 的 `faze.probe.web.allowed-origins` 补 `http://localhost` / `127.0.0.1` / `:80` |
| 2 | `user_balance` 惰性建行，充值不兜底 | 「给只注册过、没查过余额的用户充值」→ 401004。真实运营场景走不通 | `RechargeService` 先确认用户存在再加锁建行（先查用户后建行，防止打错 id 造出幽灵余额户） |
| 3 | A-05 approve 不校验申请人账号状态 | 已注销用户的入驻申请仍可通过 → 写 `lawyer` 行并改 `role`，等于给已注销账号开通律师身份 | 补 `assertApplicantActive`：不存在/已注销 → 401004，非 active → 401006 |

另修：`Seg0SmokeTest` 断言 `realnameStatus` 恒为 `NONE`、`payPasswordSet` 恒为 false —— 这两个是**会随业务变化的账号状态**，测试账号走一次实名审核就红（本轮就是这样红的，不是代码坏了）。改成断言「取值合法 / 字段是布尔」。

## 七、遗留数据（测试数据，不是种子）

库里现有：`app_user` 3 个（user 1 现为 lawyer + 实名 approved + 余额 39302 分；user 2 cancelled；user 3 role=admin + 实名 rejected + 余额来自 user 3 的流水）、`lawyer_application` 6 条（2 pending / 2 approved / 2 rejected）、`lawyer` 2 行、`realname_auth` 2 条、`enterprise_auth` 1 条、`operation_log` 200+ 条。

这些都是**联调与验收产生的真实数据**，`operation_log` 按规范不可删。段 1 收口时若需要干净基线，应重建库 + 重跑 Flyway + `database/seed/*.sql`，**不要手工删审计行**。
