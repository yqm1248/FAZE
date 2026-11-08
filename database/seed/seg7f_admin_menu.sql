-- ============================================================================
-- 第 7 段 F 组 · PC 后台「看板 + 日志中心」菜单种子（目标库：faze_admin）
--
-- 做什么：本文件只做两件事——
--   ① 登记 A-26 日志中心为新菜单（menu_id = 2236）；
--   ② 把段 1 建的自检页 2101「业务总览」改造成 A-02 数据看板
--      （component / path / route_name 三处改指 biz/ops/Dashboard）。
--
-- 目标库：**faze_admin**（RuoYi 的库），**不是**业务库 faze。
-- 依据：第 7 段工作流 §三 F 组 F1/F2；§五 5.2 第 35~38 号接口；
--       PC 后台开发计划 §2.1 A-02 / §2.7 A-26；设计稿 design/admin/01-dashboard.html、25-logs.html。
--
-- ── 为什么 A-02 **不新增菜单** ────────────────────────────────────────────
-- A-02 是「登录后的首页」性质的总览页，段 1 已经在 2100「法泽业务」目录下
-- 建了 2101「业务总览」（component = biz/dashboard/index，route name = BizDashboard），
-- 当时它只是三段链路自检页。第 7 段把 2101 **原地改造成真看板**：
--   - 不新增菜单：同一入口不重复登记，否则侧边栏会出现两个「总览」；
--   - 旧的自检能力不丢：A-02 页面里保留一条可折叠的「链路自检」小条，
--     仍调 GET /admin/dashboard/ping（段 1 的验收线还在）；
--   - 旧的 src/views/biz/dashboard/index.vue **保留不删**（只把菜单指向新页面）。
-- 因此这一段用 UPDATE，而不是 INSERT。
--
-- ── 为什么 A-26 占 menu_id = 2236 ─────────────────────────────────────────
-- 第 7 段 B/C/D 组已用掉 2230~2235（seg7_admin_menu.sql），段 6 止于 2222，
-- 2236 是 2100 目录下顺延的下一个空号；写入前已 SELECT 确认 2236 不存在，
-- 且 2101 当前仍指向 biz/dashboard/index（见文件末尾回读 SQL）。
-- 若将来 2236 被别的段占用，改号即可，其余字段不变（ON DUPLICATE 只按 menu_id 命中）。
--
-- ── 幂等性 ────────────────────────────────────────────────────────────────
--   · A-26 用 INSERT ... ON DUPLICATE KEY UPDATE：重复执行只会把字段刷回本文档口径；
--   · A-02 用 UPDATE ... WHERE menu_id = 2101：语句天然幂等；
--   · sys_role_menu 用 INSERT ... ON DUPLICATE KEY UPDATE：重复执行不报错。
-- 整个文件可重复执行，不产生重复行。
--
-- ── 前置与执行方式 ────────────────────────────────────────────────────────
-- 前置：database/seed/seg1_admin_menu.sql 已执行（2100「法泽业务」目录与 2101 存在）。
-- 执行（Windows PowerShell，路径与口令按 deploy/.env）：
--   $mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
--   & $mysql --host=127.0.0.1 --user=root --password=*** --database=faze_admin `
--            --default-character-set=utf8mb4 < database/seed/seg7f_admin_menu.sql
-- 执行后：**刷新浏览器**（RuoYi 侧边栏与动态路由由 getRouters 现查数据库，
--         已登录会话不需要重新登录，但要重新拉一次路由）。
--
-- ⚠️ 本文件不改 RuoYi 框架层代码，只写 sys_menu / sys_role_menu 数据。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. A-26 日志中心（新增菜单，menu_id = 2236）
--    is_frame：0 = 是外链、1 = 否 → 后台菜单写 1（段 1 实测踩过）；
--    is_cache：0 = 不缓存（日志要看最新，缓存住会误导「刚做的操作查不到」）；
--    visible：'0' 显示（日志中心是常用排障入口，占侧边栏位置）；
--    perms：ops:log:list（本页两个 Tab 均为只读查询）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2236, '日志中心', 2100, 26, 'ops/log-center', 'biz/ops/LogCenter', NULL,
   'OpsLogCenter', 1, 0, 'C', '0', '0', 'ops:log:list', 'log',
   'admin', NOW(), '第 7 段 F2：A-26 操作日志/登录日志（双 Tab + 详情对比表 + 双导出）')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), route_name = VALUES(route_name),
                        icon = VALUES(icon), is_frame = VALUES(is_frame),
                        is_cache = VALUES(is_cache), menu_type = VALUES(menu_type),
                        visible = VALUES(visible), status = VALUES(status),
                        perms = VALUES(perms), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 2. A-02 数据看板：**复用** 2101「业务总览」，原地改 component / path / route_name
--    - 菜单名「业务总览」保留（它仍是「法泽业务」目录下的第一个入口）；
--    - perms 保留 biz:dashboard:view（段 1 已分配，不新增权限点）；
--    - 旧页面 src/views/biz/dashboard/index.vue 不删（A-02 里保留 ping 自检条）。
-- ---------------------------------------------------------------------------
UPDATE sys_menu
SET component  = 'biz/ops/Dashboard',
    path       = 'ops/dashboard',
    route_name = 'OpsDashboard'
WHERE menu_id = 2101;

-- ---------------------------------------------------------------------------
-- 3. 授予超级管理员角色（role_id = 1）A-26 菜单
--    RuoYi 用 sys_role_menu 做菜单可见性；不插这张表，即使 sys_menu 有也看不到。
--    2101 早已由 seg1_admin_menu.sql 授予 role 1，这里顺手补一条幂等插入，
--    保证「单独只跑本文件」时 A-02 也在侧边栏可见。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2236), (1, 2101)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- ---------------------------------------------------------------------------
-- 4. 回读（执行完直接看，便于留证）
-- ---------------------------------------------------------------------------
SELECT menu_id, menu_name, parent_id, path, component, route_name, menu_type, visible,
       status, perms, icon, is_frame, is_cache
FROM sys_menu
WHERE menu_id IN (2101, 2236)
ORDER BY menu_id;

SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id IN (2101, 2236) ORDER BY menu_id;
