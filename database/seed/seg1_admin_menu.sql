-- ============================================================================
-- 第 1 段 F 组 · PC 后台「法泽业务」菜单种子（faze_admin 库）
--
-- 做什么：往 RuoYi 的 sys_menu / sys_role_menu 里登记 1 个目录 + 8 个业务页，
--         让 faze-api 的 admin 接口有界面入口。
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。两份库不混用：
--         业务表（app_user / balance_flow …）在 faze，框架表（sys_menu / sys_user …）在 faze_admin。
-- 依据：AGENTS §3.3（页面放 src/views/biz/<domain>/）、第 1 段工作流 F1 第 4 步。
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE，重复执行不报错、不产生重复菜单。
--
-- 菜单 id 段：本段占用 2100~2108。RuoYi 出厂数据最大到 1060，
-- 留出 1000 个空号是为了将来手写其他段菜单时不撞号（不要用 AUTO_INCREMENT 的随机号，
-- 脚本要能重复执行，随机号做不到幂等）。
--
-- 执行方式（root 或对 faze_admin 有写权限的账号）：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=*** --database=faze_admin \
--         --default-character-set=utf8mb4 < database/seed/seg1_admin_menu.sql
--
-- 执行后：**刷新浏览器**（RuoYi 的侧边栏路由由 getRouters 现查数据库，
--         但前端路由表缓存在 pinia 里，不刷新看不到新菜单；不需要重新登录）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 目录：法泽业务
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES (2100, '法泽业务', 0, 1, 'biz', NULL, NULL,
        '', 1, 0, 'M', '0', '0', NULL, 'shopping',
        'admin', NOW(), '第 1 段 F 组：法泽业务后台目录')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), path = VALUES(path),
                        order_num = VALUES(order_num), icon = VALUES(icon),
                        is_frame = VALUES(is_frame), visible = VALUES(visible),
                        status = VALUES(status), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 2. 页面
--    注意 component 不带 .vue 后缀、不带 src/views 前缀：
--    RuoYi-Vue3 的 loadView 是按 `import('../../views/' + component + '.vue')` 解析的。
--    route_name 必须唯一且首字母大写（keep-alive 的缓存 key 就是它）。
-- ---------------------------------------------------------------------------
-- 注意 is_frame 的取值容易搞反：RuoYi 的 sys_menu.is_frame 是「是否外链」，
-- **0 = 是外链，1 = 否**。一级目录写成 0 时，RuoYi 的 getRouterPath 不会给 path 补前导 "/"，
-- 前端 vue-router 会抛 `Route paths should start with a "/": "biz" should be "/biz"`，整页白屏。
-- 2026-11-05 实测踩过，正确值是 1。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2101, '业务总览',   2100, 1, 'dashboard',        'biz/dashboard/index',        NULL,
   'BizDashboard',        1, 1, 'C', '0', '0', 'biz:dashboard:view',      'dashboard',    'admin', NOW(), 'F1：链路自检页'),
  (2102, '用户列表',   2100, 2, 'user',             'biz/user/index',             NULL,
   'BizUser',             1, 1, 'C', '0', '0', 'biz:user:list',           'user',         'admin', NOW(), 'A-03'),
  (2103, '实名认证审核', 2100, 3, 'verification',     'biz/verification/index',     NULL,
   'BizVerification',     1, 1, 'C', '0', '0', 'biz:verification:list',   'peoples',      'admin', NOW(), 'A-04（实名 + 企业两种认证同队列）'),
  (2104, '律师入驻审核', 2100, 4, 'lawyerApplication', 'biz/lawyerApplication/index', NULL,
   'BizLawyerApplication', 1, 1, 'C', '0', '0', 'biz:lawyer-application:list', 'documentation', 'admin', NOW(), 'A-05'),
  (2105, '律师列表',   2100, 5, 'lawyer',           'biz/lawyer/index',           NULL,
   'BizLawyer',           1, 1, 'C', '0', '0', 'biz:lawyer:list',         'education',    'admin', NOW(), 'A-06'),
  (2106, '余额与充值', 2100, 6, 'wallet',           'biz/wallet/index',           NULL,
   'BizWallet',           1, 1, 'C', '0', '0', 'biz:wallet:view',         'money',        'admin', NOW(), 'A-07（锚点 1-A4 的界面）'),
  (2107, '系统参数',   2100, 7, 'sysConfig',        'biz/sysConfig/index',        NULL,
   'BizSysConfig',        1, 1, 'C', '0', '0', 'biz:sys-config:list',     'edit',         'admin', NOW(), 'A-08'),
  (2108, '对账报表',   2100, 8, 'reconciliation',   'biz/reconciliation/index',   NULL,
   'BizReconciliation',   1, 1, 'C', '0', '0', 'biz:reconciliation:view', 'chart',        'admin', NOW(), 'A-09（锚点 1-A7 的界面）')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), route_name = VALUES(route_name),
                        is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        perms = VALUES(perms), icon = VALUES(icon), visible = VALUES(visible),
                        status = VALUES(status), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 3. 授权：挂到超级管理员角色（role_id = 1，RuoYi 出厂 admin 角色）
--    本段后台只有一个管理员角色（接口规范 §6.2 / AGENTS 决策），因此只授这一个角色。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2100), (1, 2101), (1, 2102), (1, 2103),
       (1, 2104), (1, 2105), (1, 2106), (1, 2107), (1, 2108)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- ---------------------------------------------------------------------------
-- 4. 自检（预期 9 行，parent_id=2100 的应是 8 行）
-- ---------------------------------------------------------------------------
-- SELECT menu_id, menu_name, parent_id, path, component, route_name, menu_type
--   FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2108 ORDER BY menu_id;
-- SELECT COUNT(*) AS granted FROM sys_role_menu WHERE role_id = 1 AND menu_id BETWEEN 2100 AND 2108;
