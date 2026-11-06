-- ============================================================================
-- 第 2 段（交易与内容）PC 后台菜单种子（faze_admin 库）
--
-- 做什么：把第 2 段手工插进开发库、**却从没进过仓库**的 4 个菜单补成可复现的 seed。
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。
--
-- 为什么现在补（2026-11-07，负责人批准）：
--   第 3 段验收时发现，`sys_menu` 里 2109~2112 这 4 行是「手工插库」的产物，
--   仓库的 `database/seed/` 里没有对应脚本（段 1 有 `seg1_admin_menu.sql`、
--   段 3 有 `seg3_admin_menu.sql`）。后果：**换机器或重建库后，订单管理/文章管理
--   两个页面没有菜单入口**，只能手工再插一遍 —— 属于「一半交付物没有版本管理」。
--
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE，重复执行不报错、不产生重复菜单。
-- 菜单 id 段：第 2 段占用 **2109~2112**（段 1 用 2100~2108，第 3 段用 2200~2209）。
--
-- 与段 1 / 段 3 的差异（**这 4 个菜单里有两个是隐藏页**）：
--   2110「订单详情」与 2112「文章编辑」是 **visible='1'（隐藏）** —— 它们不是左侧
--   菜单项，而是列表页点进去的二级路由（`/biz/trade/order/detail`、
--   `/biz/content/article/edit`）。隐藏菜单在 RuoYi 里仍然是**必须存在**的：
--   前端 `loadView` 按 `component` 去找页面，路由也要注册，只是不渲染成菜单项。
--
-- 执行方式（root 或对 faze_admin 有写权限的账号）：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=*** --database=faze_admin \
--         --default-character-set=utf8mb4 < database/seed/seg2_admin_menu.sql
--
-- 执行后：**刷新浏览器**（侧边栏路由由 getRouters 现查数据库；不需要重新登录）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 四个页面菜单（全部 C 类，没有 F 类按钮权限 —— 第 2 段当时就没建）
--    is_frame=1（不是外链）、is_cache 按原值：列表页开缓存、详情/编辑页关缓存。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2109, '订单管理', 2100, 9, 'trade/order', 'biz/trade/order/index', NULL,
   'BizTradeOrder', 1, 0, 'C', '0', '0', 'biz:order:list', 'shopping',
   'admin', NOW(), '第 2 段：订单列表（补 seed，原为手工插库）'),
  (2110, '订单详情', 2100, 10, 'trade/order/detail', 'biz/trade/order/detail', NULL,
   'BizTradeOrderDetail', 1, 0, 'C', '1', '0', 'biz:order:detail', 'documentation',
   'admin', NOW(), '第 2 段：订单详情（**隐藏页**，不是菜单项）'),
  (2111, '文章管理', 2100, 11, 'content/article', 'biz/content/article/index', NULL,
   'BizContentArticle', 1, 0, 'C', '0', '0', 'biz:article:list', 'documentation',
   'admin', NOW(), '第 2 段：文章列表（补 seed，原为手工插库）'),
  (2112, '文章编辑', 2100, 12, 'content/article/edit', 'biz/content/article/edit', NULL,
   'BizContentArticleEdit', 1, 0, 'C', '1', '0', 'biz:article:edit', 'edit',
   'admin', NOW(), '第 2 段：文章编辑（**隐藏页**，不是菜单项）')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), route_name = VALUES(route_name),
                        is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        menu_type = VALUES(menu_type), visible = VALUES(visible),
                        status = VALUES(status), perms = VALUES(perms), icon = VALUES(icon),
                        remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 2. 授权给超级管理员角色（role_id = 1）
--    实测：这 4 个菜单在 `sys_role_menu` 里**一条授权都没有**。
--    超管靠 `*:*:*` 仍能看到，但——① 换非超管角色就看不到；② 与段 1/段 3 的
--    「插菜单必插授权」口径不一致，属于漏项。这里补齐，保持三段一致。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2109), (1, 2110), (1, 2111), (1, 2112)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- ---------------------------------------------------------------------------
-- 3. 自检（执行后手工跑，预期：菜单 4 行、授权 4 行）
-- ---------------------------------------------------------------------------
-- SELECT menu_id, menu_name, parent_id, order_num, path, component, visible, perms
--   FROM sys_menu WHERE menu_id BETWEEN 2109 AND 2112 ORDER BY menu_id;
-- SELECT COUNT(*) AS granted FROM sys_role_menu WHERE role_id = 1 AND menu_id BETWEEN 2109 AND 2112;

-- ---------------------------------------------------------------------------
-- 4. 回滚（按 id 精确删除；先确认没有别的段引用）
-- ---------------------------------------------------------------------------
-- DELETE FROM sys_role_menu WHERE menu_id BETWEEN 2109 AND 2112;
-- DELETE FROM sys_menu WHERE menu_id BETWEEN 2109 AND 2112;
