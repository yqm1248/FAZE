-- ============================================================================
-- 第 5 段 B6 · PC 后台「案件管理」（A-19）的菜单种子（faze_admin 库）
--
-- 做什么：往 RuoYi 的 sys_menu / sys_role_menu 里登记 1 个页面，
--         让 A-19 案件管理在左侧「法泽业务」下可见、可进入（列表 + 抽屉都在同一页）。
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。
-- 依据：
--   设计稿 design/admin/ 的 A-19 是「案件管理」；挂在段 1 建的「法泽业务」目录
--   （menu_id 2100）下，与订单管理（2109）同级。
--   页面内三个 Tab（立案材料 / 进度 / 送达）都在**同一个组件里用 el-drawer 打开**，
--   所以只登记 1 个菜单，不需要隐藏子路由（B2 第 3 条：抽屉不是新页面）。
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE。
--
-- ⚠️ **前置**：必须先执行 `database/seed/seg1_admin_menu.sql`（它建了父目录 2100「法泽业务」）。
--
-- 菜单 id 段：本段占用 **2220 起**。
--   ⚠️ 2026-11-08 实测：段 3 用满 2200~2209、段 4 用 2210~2211，现取 2220+（无冲突）。
--   后续各组继续占用：2221 律师结算（E5）/ 2222 提现审核（F2）/ 2223 案例库（G1）。
--
-- 执行方式：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=*** --database=faze_admin \
--         --default-character-set=utf8mb4 < database/seed/seg5_admin_menu.sql
--
-- 执行后：**刷新浏览器**（侧边栏由 getRouters 现查数据库，前端路由缓存在 pinia 里）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 页面菜单（A-19 案件管理）
--    is_frame 的取值：RuoYi 里 0 = 是外链、1 = 否。**目录与菜单都写 1**（段 1 实测踩过）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2220, '案件管理', 2100, 10, 'caseflow/case', 'biz/caseflow/case/index', NULL,
   'CaseList', 1, 0, 'C', '0', '0', 'caseflow:case:list', 'documentation',
   'admin', NOW(), '第 5 段 B6：A-19 案件管理（列表 + 抽屉三 Tab）')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), icon = VALUES(icon),
                        is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        menu_type = VALUES(menu_type), visible = VALUES(visible),
                        status = VALUES(status), perms = VALUES(perms), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 2. 角色授权
--    超管（role_id = 1）靠 `*:*:*` 本来就能看到，但**显式授权**能让角色管理页的
--    「菜单权限」树正确勾选；段 3 的 H3 踩过「没有授权行 → 非超管看不到」的坑。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2220)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
