-- ============================================================================
-- 第 3 段 H3 · PC 后台「AI 法律顾问」两个业务页的菜单种子（faze_admin 库）
--
-- 做什么：往 RuoYi 的 sys_menu / sys_role_menu 里登记 2 个一级目录 + 2 个页面 +
--         7 个按钮权限，让 A-27 法条库与 A-28 AI 对话检索在左侧菜单可见、可操作。
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。两份库不混用：
--         业务表（law_item / ai_conversation / ai_message）在 faze，
--         框架表（sys_menu / sys_role / sys_role_menu）在 faze_admin。
-- 依据：
--   《第 3 段工作流》H3（菜单挂载表：A-27 → 内容管理第 5 项，A-28 → 系统设置第 4 项）；
--   设计稿 design/admin/shell.js L81–124（11 组一级导航）；
--   AGENTS §3.3（业务页面放 src/views/biz/<domain>/，菜单由数据库驱动）。
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE，重复执行不报错、不产生重复菜单。
--
-- 菜单 id 段：本段占用 **2200~2209**。
--   - RuoYi 出厂数据最大到 1060（见 pc/admin-server/sql/ry_20260320.sql）；
--   - 第 1 段 seg1_admin_menu.sql 占用 2100~2108；
--   - 第 2 段手工插库占用了 2109~2112（**未进仓库 seed，属既有债务，由 Lead 记 §十一**）；
--   - 本段从 2200 起，留出 90 个空号给 2109~2199 段的补录/返工，不撞已有 id。
--
-- 与设计稿的偏差（必须知道，H3-menu.md 有详细说明）：
--   设计稿的一级导航是 11 组（数据看板 / 用户管理 / … / 内容管理 / … / 系统设置 / 系统管理），
--   但当前库里是 5 组（系统管理 / 法泽业务 / 系统监控 / 系统工具 / 若依官网），
--   段 1 的 8 页与段 2 的 4 页都挂在扁平目录「法泽业务」下。
--   本段按工作流 H3 的挂载表**新建**「内容管理」「系统设置」两个一级目录，只挂新增的 2 页：
--   A-27 order_num=5、A-28 order_num=4 —— 序号与设计稿一致，前 4 项留空号，
--   将来补齐其余页面时位置不用再搬。既有 12 页的 IA 归段 8 收口，本段不动它们（避免返工）。
--
-- 执行方式（root 或对 faze_admin 有写权限的账号）：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=*** --database=faze_admin \
--         --default-character-set=utf8mb4 < database/seed/seg3_admin_menu.sql
--
-- 执行后：**刷新浏览器**（侧边栏路由由 getRouters 现查数据库，前端路由表缓存在 pinia 里，
--         不刷新看不到新菜单；不需要重新登录）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 两个一级目录
--    注意 is_frame 的取值：RuoYi 的 sys_menu.is_frame 是「是否外链」，**0 = 是外链，1 = 否**。
--    目录必须写 1（段 1 实测踩过：写 0 时 getRouterPath 不给 path 补前导 "/"，
--    前端 vue-router 抛 `Route paths should start with a "/"`，整页白屏）。
--    path 不能与既有目录重名：已有 system / monitor / tool / biz，
--    所以「内容管理」用 content、「系统设置」用 setting（不是 system）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2200, '内容管理', 0, 5, 'content', NULL, NULL,
   '', 1, 0, 'M', '0', '0', NULL, 'documentation',
   'admin', NOW(), '第 3 段 H3：设计稿一级导航「内容管理」'),
  (2206, '系统设置', 0, 6, 'setting', NULL, NULL,
   '', 1, 0, 'M', '0', '0', NULL, 'system',
   'admin', NOW(), '第 3 段 H3：设计稿一级导航「系统设置」')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        icon = VALUES(icon), is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        menu_type = VALUES(menu_type), visible = VALUES(visible),
                        status = VALUES(status), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 2. 两个页面（C 类菜单）
--    component 不带 .vue 后缀、不带 src/views 前缀：RuoYi-Vue3 的 loadView 按
--    `import('../../views/' + component + '.vue')` 解析。
--    route_name 必须唯一且首字母大写（keep-alive 的缓存 key 就是它）。
--    页面菜单的 perms 是该页的「列表/入口」权限串，前端 v-hasPermi 与后端菜单都按它判断。
--    order_num 按设计稿位置：A-27 = 内容管理第 5 项，A-28 = 系统设置第 4 项。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2201, '法条库', 2200, 5, 'law-library', 'biz/ai/law-library/index', NULL,
   'BizAiLawLibrary', 1, 1, 'C', '0', '0', 'biz:law:list', 'education',
   'admin', NOW(), 'A-27 法条库（H1）'),
  (2207, 'AI 对话检索', 2206, 4, 'ai-retrieval', 'biz/ai/ai-retrieval/index', NULL,
   'BizAiRetrieval', 1, 1, 'C', '0', '0', 'biz:ai:list', 'message',
   'admin', NOW(), 'A-28 AI 对话检索（H2）')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), route_name = VALUES(route_name),
                        is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        menu_type = VALUES(menu_type), perms = VALUES(perms), icon = VALUES(icon),
                        visible = VALUES(visible), status = VALUES(status), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 3. 按钮权限（F 类菜单）
--    为什么 F 菜单也要插：RuoYi 的 `v-hasPermi` 判断依据是登录用户拿到的 permissions 集合，
--    而 permissions 来自「角色 → sys_role_menu → sys_menu.perms」的 join。
--    超管（role_key='admin'）虽然拿的是 *:*:*，但非超管角色要能授这些按钮，就必须有菜单行。
--    页面菜单 2201 已带 biz:law:list、2207 已带 biz:ai:list，这里补其余 7 个权限串：
--      A-27：biz:law:query / biz:law:edit / biz:law:import / biz:law:disable
--      A-28：biz:ai:query / biz:ai:export
--    注意：页面里所有 v-hasPermi 的权限串都必须能在 sys_menu.perms 里找到，
--    否则换非超管角色后按钮会被整块隐藏（不是报错，是静默不渲染）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2202, '法条查询',   2201, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'biz:law:query',   '#',
   'admin', NOW(), 'A-27 查看/查询按钮'),
  (2203, '法条修正',   2201, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'biz:law:edit',    '#',
   'admin', NOW(), 'A-27 修正按钮（写新版本）'),
  (2204, '法条导入',   2201, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'biz:law:import',  '#',
   'admin', NOW(), 'A-27 批量导入按钮'),
  (2205, '法条失效',   2201, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'biz:law:disable', '#',
   'admin', NOW(), 'A-27 标记失效按钮'),
  (2208, '对话检索查询', 2207, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'biz:ai:query',  '#',
   'admin', NOW(), 'A-28 查询/查看按钮'),
  (2209, '对话导出',   2207, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'biz:ai:export',   '#',
   'admin', NOW(), 'A-28 导出本条记录按钮')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), route_name = VALUES(route_name),
                        is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        menu_type = VALUES(menu_type), perms = VALUES(perms), icon = VALUES(icon),
                        visible = VALUES(visible), status = VALUES(status), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 4. 授权：挂到超级管理员角色（role_id = 1，RuoYi 出厂 admin 角色）
--    本段后台只有一个管理员角色（接口规范 §6.2 / AGENTS 决策），因此只授这一个角色。
--    **必须插这一步**：不插 sys_role_menu 时，超管能看到（*:*:*），但用非超管账号验收
--    菜单会不出现；H3 的「管理员登录能看到两个菜单」验收会假失败。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2200), (1, 2201), (1, 2202), (1, 2203), (1, 2204),
       (1, 2205), (1, 2206), (1, 2207), (1, 2208), (1, 2209)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- ---------------------------------------------------------------------------
-- 5. 自检（执行后手工跑，预期：菜单 10 行、授权 10 行）
-- ---------------------------------------------------------------------------
-- SELECT menu_id, menu_name, parent_id, order_num, path, component, route_name, menu_type, perms
--   FROM sys_menu WHERE menu_id BETWEEN 2200 AND 2209 ORDER BY menu_id;
-- SELECT COUNT(*) AS granted FROM sys_role_menu WHERE role_id = 1 AND menu_id BETWEEN 2200 AND 2209;

-- ---------------------------------------------------------------------------
-- 6. 回滚（本段不写 DELETE；确需回滚时按 id 精确删除，且先确认没有别的段引用）
-- ---------------------------------------------------------------------------
-- DELETE FROM sys_role_menu WHERE role_id = 1 AND menu_id BETWEEN 2200 AND 2209;
-- DELETE FROM sys_menu WHERE menu_id BETWEEN 2200 AND 2209;
