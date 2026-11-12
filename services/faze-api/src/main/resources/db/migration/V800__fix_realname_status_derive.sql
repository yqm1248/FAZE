-- ============================================================================
-- 迁移：V800__fix_realname_status_derive.sql
-- 所属段：跨段（段 1 建列 / 段 6 当门禁用）——数据一致性修复，落在段 8 版本区间
-- 做什么：把 `app_user.realname_status` 按 `realname_auth` 重算一遍，清掉 DRIFT-015
--         留下的存量矛盾。此后该列只是**冗余缓存**，真相源是 `realname_auth`。
--
-- 问题（2026-11-13 本地库实测，不是推测）：
--   实名状态存在两套真相，同一个用户、同一时刻两个接口给出不同结论：
--     · `GET /app/account/realname` 读 `realname_auth` → PENDING（实名页显示「审核中」）
--     · `GET /app/account/profile` 读 `app_user.realname_status` → NONE（我的页显示「未实名」）
--   根因：提交实名只 upsert `realname_auth`，`app_user.realname_status` 的唯一写入点是
--   后台审核 executor，于是「已提交、待审」这一段永远写不进列。
--   实测不一致 3 行：
--     user 3    auth=rejected  col=approved  ← 两页结论相反（A12 红灯的唯一条目）
--     user 9416 auth=pending   col=none
--     user 9418 auth=pending   col=none
--   `realname_auth` 侧分布：approved 3 / pending 2 / rejected 1；
--   `app_user` 侧只有 approved 4 / none 11 —— 列里从来没有出现过 pending/rejected，
--   所以 App 端「审核中」「已驳回」两个分支是死代码。
--
-- 为什么是 UPDATE 存量而不是只改代码：
--   代码改完后所有读取路径都派生，历史脏行不会影响页面。但这一列仍在库里，
--   导出、直查、历史脚本会读到它——A12 巡检也会一直红。
--   负责人 2026-11-11 定「方案 C：不改数据，等代码修完一次性收敛」，本迁移就是那一次。
--
-- 为什么 user 3 改成 rejected（这行有争议，写清楚）：
--   它是 PC 后台验收脚本 `deploy/verify-admin-api.mjs` 硬编码的 `walletUser = 3`。
--   当时的顾虑是「改成 rejected 会断管理端验收」——**实测不成立**：
--   该脚本只把 user 3 用在 `/admin/wallets/3` 与手工充值两个管理端接口上，
--   这两条不走 `RechargeQuotaPolicy`（管理端充值不校验 C 端实名门禁）。
--   脚本里唯一涉及实名状态的断言是 A-03 列表的
--   `['NONE','PENDING','APPROVED','REJECTED'].includes(u.realnameStatus)` ——
--   派生后值更准确，断言只会更稳。
--   反过来，改成 approved 等于**伪造审核结论**（审核人驳回是留了痕的），不能选。
--
-- 为什么是「按 auth 重算」而不是「逐行手改」：
--   重算口径与 `RealnameStatusResolver#resolveFromAuth` 完全一致：
--   auth 三值之一 → 原值；无记录或脏值 → `none`。以后再有同类漂移，
--   重跑同一条语句即可，不需要重新判断哪个用户该是什么。
--
-- 不改什么：
--   1. **不改 `updated_at`**：这是数据修正，不是用户行为。改了会让「最后更新」
--      指向一个没人操作过的时间点，排查问题时会误导。
--   2. **不删 `app_user.realname_status` 列**：删列不可逆，且导出/直查仍在用。
--      它现在的定位是缓存，写路径（提交实名 + 审核执行）会继续同步维护。
--   3. **不改任何接口出参**：`realnameStatus` 四值枚举不变，只是值变准了。
--
-- 影响面（代码侧，同批提交）：
--   - 新增 `RealnameStatusResolver`（唯一派生入口）
--   - `AccountConverter` / `AdminAccountConverter` / `AdminLawyerConverter`：
--     实名状态改为**由调用方传入**，不再读 `AppUser#getRealnameStatus`
--   - `AuthService` / `ProfileService` / `AccountQueryServiceImpl` /
--     `AdminUserService` / `AdminLawyerService`：改走派生
--   - `AdminUserService#applyRealnameFilter`：列表筛选改 EXISTS 子查询
--   - `RealnameService#submit`：补写缓存列（原来只写 auth，是漂移的直接原因）
--   - `scripts/monitor/data-integrity.sql`：A12 保留（现在应为 0）、A13 值域加 pending/rejected
-- ============================================================================

UPDATE app_user u
  LEFT JOIN realname_auth r ON r.user_id = u.id
SET u.realname_status = CASE
      WHEN r.status IN ('pending', 'approved', 'rejected') THEN r.status
      ELSE 'none'
    END
WHERE u.realname_status <> CASE
      WHEN r.status IN ('pending', 'approved', 'rejected') THEN r.status
      ELSE 'none'
    END;
