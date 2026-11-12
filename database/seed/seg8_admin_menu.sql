-- ============================================================================
-- 第 8 段 E 组 · PC 后台「A-17 多语言翻译」菜单种子（目标库：faze_admin）
--
-- 做什么：登记 A-17 的三条记录——
--   ① menu_id = 2240「多语言翻译」（菜单 C，挂在 2100「法泽业务」下）；
--   ② menu_id = 2241「译文编辑」（按钮 F，隐藏，控制编辑按钮的显示）；
--   ③ sys_role_menu 把 2240/2241 **只绑给超级管理员角色（role_id = 1）**。
--
-- 目标库：**faze_admin**（RuoYi 的库），**不是**业务库 faze。
-- 依据：第 8 段工作流 §三 E 组 E-1/E-2；设计稿 design/admin/16-translation.html；
--       后端接口 modules/content/admin/controller/AdminTranslationController。
--
-- ── 为什么 menu_id 用 2240 ────────────────────────────────────────────────
-- 段 6 止于 2222，段 7 用到 2236（2230~2236）。2240 是 2100 目录下顺延的下一个
-- 整数空号（留 2237~2239 是因为这几号在其它分支的草稿里出现过，避开可以少一次
-- 将来改号的麻烦）。写入前已 SELECT 确认 2240/2241 不存在；若真被占用，改号即可，
-- 其余字段不变（ON DUPLICATE 只按 menu_id 命中）。
--
-- ── E-2「仅超管可见」怎么落地（重要） ────────────────────────────────────
-- faze-api **没有** RuoYi 的 `@ss` bean，也没有 `@PreAuthorize`（与 AdminArticleController /
-- AdminLawItemController 同一约定），管理端只靠「管理端登录态 + ROLE_ADMIN」这一层。
-- 所以细粒度权限分两层实现：
--   1. **菜单可见性**：`sys_role_menu` 只插 role_id = 1（超级管理员）。
--      普通角色（role_id = 2）没有这条绑定 → 侧边栏看不到、getRouters 不返回这个路由。
--   2. **按钮可见性**：前端 `v-hasPermi="['biz:translation:edit']"`，
--      对应 2241 这条 F 类型按钮菜单；同样只绑超管。
-- 注意 RuoYi 的超级管理员（role_key='admin'）本身**跳过权限字符串校验**，
-- 所以「超管能用」不依赖这两条绑定；两条绑定是给「非超管一律看不到」用的。
--
-- ── 幂等性 ────────────────────────────────────────────────────────────────
-- 全部用 INSERT ... ON DUPLICATE KEY UPDATE：重复执行只把字段刷回本文档口径，不产生重复行。
--
-- ── 前置与执行方式 ────────────────────────────────────────────────────────
-- 前置：database/seed/seg1_admin_menu.sql 已执行（2100「法泽业务」目录存在）。
-- 执行（Windows PowerShell，口令按 deploy/.env 的 FAZE_DB_ROOT_PASSWORD）：
--   $mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
--   & $mysql --host=127.0.0.1 --user=root --password=*** --database=faze_admin `
--            --default-character-set=utf8mb4 -e "source database/seed/seg8_admin_menu.sql"
-- 执行后：**刷新浏览器**（RuoYi 侧边栏与动态路由由 getRouters 现查数据库）。
--
-- ⚠️ 本文件不改 RuoYi 框架层代码，只写 sys_menu / sys_role_menu 数据。
-- ============================================================================

-- ── 1. 菜单：多语言翻译（C 类型 = 菜单页面）──────────────────────────────
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2240, '多语言翻译', 2100, 27, 'i18n', 'biz/i18n/index', NULL, 'BizI18n',
   1, 0, 'C', '0', '0', 'biz:translation:list', 'language', 'admin', NOW(),
   'A-17 译文队列 + 术语表；仅超管可见')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  component = VALUES(component),
  route_name = VALUES(route_name),
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  icon = VALUES(icon),
  remark = VALUES(remark);

-- ── 2. 按钮：译文编辑（F 类型 = 按钮，visible=1 隐藏，只做权限标识）──────
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2241, '译文编辑', 2240, 1, '', NULL, NULL, NULL,
   1, 0, 'F', '1', '0', 'biz:translation:edit', '#', 'admin', NOW(),
   '控制 A-17 编辑按钮与保存接口的前端可见性')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  remark = VALUES(remark);

-- ── 3. 角色绑定：只给超级管理员（role_id = 1）─────────────────────────────
-- 普通角色（role_id = 2）**故意不绑**——这就是 E-2「仅超管可见」的落点。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 2240),
  (1, 2241)
ON DUPLICATE KEY UPDATE
  role_id = VALUES(role_id);

-- ── 4. 回读校验（执行后人工确认这三行输出）────────────────────────────────
SELECT menu_id, menu_name, parent_id, path, component, perms, menu_type, visible
FROM sys_menu WHERE menu_id IN (2240, 2241) ORDER BY menu_id;

SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id IN (2240, 2241) ORDER BY role_id, menu_id;
