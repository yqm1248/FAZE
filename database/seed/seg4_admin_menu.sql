-- ============================================================================
-- 第 4 段 B1 · PC 后台「文书模板」页面的菜单种子（faze_admin 库）
--
-- 做什么：往 RuoYi 的 sys_menu / sys_role_menu 里登记 1 个页面 + 1 个隐藏编辑页，
--         让 A-18 文书模板在左侧「内容管理」下可见、可进入编辑页。
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。
-- 依据：
--   设计稿 design/admin/shell.js L172：`17-doc-template` 属于 group `content`，
--   面包屑 ["内容管理", "文书模板"] → 挂在段 3 建的「内容管理」目录（menu_id 2200）下；
--   设计稿 content 组顺序：文章列表 / 案例库 / 多语言翻译 / **文书模板** / 法条库
--   → 文书模板 order_num = 4（与段 3 的 A-28 AI 对话检索同号；RuoYi 按 order_num 再按
--     menu_id 次级排序，同号不会重排，属可接受的历史遗留，段 8 收口时统一排一次）。
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE。
--
-- ⚠️ **前置**：必须先执行 `database/seed/seg3_admin_menu.sql`（它建了父目录 2200「内容管理」）。
--    没执行时父目录不存在，这两个菜单挂不上去（RuoYi 的 getRouters 会直接忽略孤儿节点，
--    表现是「左侧菜单没有文书模板」，且没有任何报错）。
--
-- 菜单 id 段：本段占用 **2210~2211**。
--   ⚠️ 2026-11-07 实测纠正：段 3 实际用满 2200~2209（2200 内容管理 / 2201 法条库 /
--   2202~2205 法条按钮 / 2206 系统设置 / 2207 AI 对话检索 / 2208~2209 按钮），
--   原稿误以为 2201~2205 是空号，**直接执行会覆盖段 3 的法条库菜单**。
--   现取 2210~2211（已查 sys_menu 最大 id = 2209，2210+ 全空）。
--
-- 执行方式：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=*** --database=faze_admin \
--         --default-character-set=utf8mb4 < database/seed/seg4_admin_menu.sql
--
-- 执行后：**刷新浏览器**（侧边栏由 getRouters 现查数据库，前端路由缓存在 pinia 里）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 页面菜单（A-18 文书模板）
--    is_frame 的取值：RuoYi 里 0 = 是外链、1 = 否。**目录与菜单都写 1**（段 1 实测踩过）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2210, '文书模板', 2200, 4, 'template', 'biz/document/template/index', NULL,
   'DocTemplate', 1, 0, 'C', '0', '0', 'document:template:list', 'form',
   'admin', NOW(), '第 4 段 B1：A-18 文书模板管理'),
  (2211, '模板编辑', 2200, 5, 'template/edit/:id', 'biz/document/template/edit', NULL,
   'DocTemplateEdit', 1, 0, 'C', '1', '0', 'document:template:edit', '#',
   'admin', NOW(), '第 4 段 B1：A-18 隐藏编辑页（visible=1 表示不在菜单里显示，但路由必须注册）')
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
VALUES (1, 2210), (1, 2211)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
