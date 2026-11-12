-- ============================================================================
-- 法泽 FAZE · PC 后台信息架构重整（目标库：faze_admin）
--
-- 做什么：把「法泽业务」一个菜单平铺 23 个页面，改成设计书定义的一级 / 二级
--        两级结构；同时按负责人 2026-11-13 的裁决隐藏若依原生菜单的 6 项。
--
-- 依据：
--   1. design/admin/shell.js 第 48~134 行的 MENU 表（11 组一级菜单，设计书定的）；
--   2. docs/design/法泽PC后台UI设计说明_v1.0.md §79~81（一级 11 组、菜单高度预算）；
--   3. 负责人 2026-11-13 口头裁决：
--      · 隐藏：首页（静态路由，不在本表）、系统管理下的用户/角色/部门管理、
--        「若依官网」、「系统工具」；
--      · **保留「菜单管理」**（要它继续当菜单维护入口，所以不能藏）；
--      · 系统监控、系统管理下的岗位/字典/参数/通知公告/日志管理 **保留**；
--      · 登录后直接落在数据看板。
--
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。
-- 前置：seg1~seg8 的 admin_menu 种子均已执行（2100 等旧菜单存在）。
-- 执行（Windows PowerShell）：
--   mysql --host=127.0.0.1 --user=root --password=*** --database=faze_admin `
--         --default-character-set=utf8mb4 -e "source database/seed/admin_menu_ia.sql"
-- 执行后刷新浏览器（RuoYi 侧边栏与动态路由由 getRouters 现查数据库）。
--
-- ── 为什么必须改 path（重要，不是随手改名） ──────────────────────────────
-- RuoYi 的前端 URL = 一级目录 path + '/' + 页面 path（Vue Router 嵌套路由）。
-- 改造前所有法泽页面的 URL 都是 `/biz/<原 path>`，改成分组目录后前缀换成
-- 各目录自己的 path，**URL 一定会变**。已同步改掉前端 14 处硬编码跳转与
-- services/faze-api 的 AdminDashboardService 里的 5 条 link，
-- 改后的 URL 清单见表末 §8。
--
-- ── 幂等性 ────────────────────────────────────────────────────────────────
-- 目录用 INSERT ... ON DUPLICATE KEY UPDATE；页面重挂用无条件的
-- UPDATE ... WHERE menu_id = X（重复执行结果相同）。可以反复跑。
-- 回滚见 database/seed/admin_menu_ia_rollback.sql。
-- ============================================================================

-- ── 1. 新建 7 个一级目录（M）────────────────────────────────────────────────
-- 内容管理（2200）与系统设置（2206）已存在，复用，见 §2。
--
-- ⚠️ 为什么「用户管理」的 path 是 `members` 而不是 `user`（2026-11-13 实测踩到的坑）：
--   RuoYi 的 constantRoutes 里有一条**静态**路由 `{ path: '/user', ... }`（隐藏，个人中心
--   `/user/profile`）。Vue Router 的同 path 路由按注册顺序匹配，静态路由先注册 → 动态目录
--   `/user` 虽然也 addRoute 了，但 `/user/list` 会先落到静态 `/user` 下、匹配不到 children，
--   **直接 404**（实测：其他 21 个页面全 PASS，只有 `/user/list` 与 `/user/verification` 404）。
--   改路径比重写框架的静态路由安全得多，所以这里用 `members`。
--   其它新建目录的 path（lawyer / order / finance / caseflow / ticket / message）已确认
--   与 RuoYi 内置路由（/system /monitor /tool /login /register /lock /redirect /401 /user）不冲突。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2250, '用户管理', 0, 10, 'members', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'user', 'admin', NOW(), 'C 端用户与实名资质'),
  (2251, '律师管理', 0, 11, 'lawyer', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'peoples', 'admin', NOW(), '律师入驻审核、律师列表、结算'),
  (2252, '订单管理', 0, 12, 'order', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'shopping', 'admin', NOW(), '咨询订单与详情'),
  (2253, '财务管理', 0, 13, 'finance', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'money', 'admin', NOW(), '余额充值、对账、提现审核'),
  (2254, '案件管理', 0, 15, 'caseflow', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'form', 'admin', NOW(), '案件流转与内容审核队列'),
  (2255, '投诉工单', 0, 16, 'ticket', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'message', 'admin', NOW(), '工单列表与详情'),
  (2256, '消息管理', 0, 17, 'message', NULL, NULL, NULL,
   1, 0, 'M', '0', '0', NULL, 'bell', 'admin', NOW(), '公告推送与通知模板')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  icon = VALUES(icon),
  remark = VALUES(remark);

-- ── 2. 既有目录重定位（内容管理 / 系统设置 / 系统管理 / 系统监控）────────────
-- 2200 内容管理、2206 系统设置 是种子里早就建好的，不新建，只调顺序与图标；
-- 系统管理（1）与系统监控（2）整体排到法泽业务之后（order 90 / 91）。
UPDATE sys_menu SET order_num = 14, icon = 'documentation',
    remark = '文章、案例库、多语言、文书模板、法条库' WHERE menu_id = 2200;
UPDATE sys_menu SET order_num = 18, icon = 'system',
    remark = '系统参数、敏感词、日志中心、AI 对话检索' WHERE menu_id = 2206;
UPDATE sys_menu SET order_num = 90 WHERE menu_id = 1;
UPDATE sys_menu SET order_num = 91 WHERE menu_id = 2;

-- ── 3. 页面重挂：用户管理 ──────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2250, path = 'list',         order_num = 1 WHERE menu_id = 2102;
UPDATE sys_menu SET parent_id = 2250, path = 'verification', order_num = 2 WHERE menu_id = 2103;

-- ── 4. 页面重挂：律师管理 ──────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2251, path = 'audit',  order_num = 1 WHERE menu_id = 2104;
UPDATE sys_menu SET parent_id = 2251, path = 'list',   order_num = 2 WHERE menu_id = 2105;
UPDATE sys_menu SET parent_id = 2251, path = 'settle', order_num = 3 WHERE menu_id = 2221;

-- ── 5. 页面重挂：订单管理 ──────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2252, path = 'list',   order_num = 1 WHERE menu_id = 2109;
UPDATE sys_menu SET parent_id = 2252, path = 'detail', order_num = 2 WHERE menu_id = 2110;

-- ── 6. 页面重挂：财务管理 ──────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2253, path = 'wallet',    order_num = 1 WHERE menu_id = 2106;
UPDATE sys_menu SET parent_id = 2253, path = 'reconcile', order_num = 2 WHERE menu_id = 2108;
UPDATE sys_menu SET parent_id = 2253, path = 'withdraw',  order_num = 3 WHERE menu_id = 2222;

-- ── 7. 页面重挂：内容管理（2200 已在位，只改子项的 parent/path/order）────────
UPDATE sys_menu SET parent_id = 2200, path = 'article',      order_num = 1 WHERE menu_id = 2111;
UPDATE sys_menu SET parent_id = 2200, path = 'article/edit', order_num = 2 WHERE menu_id = 2112;
UPDATE sys_menu SET parent_id = 2200, path = 'i18n',         order_num = 4 WHERE menu_id = 2240;
UPDATE sys_menu SET parent_id = 2200, path = 'template',     order_num = 5 WHERE menu_id = 2210;
UPDATE sys_menu SET parent_id = 2200, path = 'template/edit/:id', order_num = 6 WHERE menu_id = 2211;
UPDATE sys_menu SET parent_id = 2200, path = 'law-library',  order_num = 7 WHERE menu_id = 2201;

-- ── 8. 补齐缺失的「案例库」菜单（menu_id 2223）──────────────────────────────
-- 为什么现在补：页面 src/views/biz/content/caseLibrary/index.vue 早已写好，
-- 文件头注释写的是「RuoYi 菜单『法泽业务 → 案例库』（menu_id 2223）」，
-- 但 seg5/seg7 的种子都没有插入 2223，实测 sys_menu 里查不到这条记录
-- → 页面做完了、菜单上根本点不进去。属漏建，不是本次新增功能。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2223, '案例库', 2200, 3, 'case-library', 'biz/content/caseLibrary/index', NULL, 'BizCaseLibrary',
   1, 0, 'C', '0', '0', 'biz:case-library:list', 'table', 'admin', NOW(),
   'A-16 案例四语言维护；补 seg5 漏建的菜单记录')
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

-- ── 9. 页面重挂：案件管理 ──────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2254, path = 'case',   order_num = 1 WHERE menu_id = 2220;
UPDATE sys_menu SET parent_id = 2254, path = 'review', order_num = 2 WHERE menu_id = 2231;

-- ── 10. 页面重挂：投诉工单 ─────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2255, path = 'list',       order_num = 1 WHERE menu_id = 2232;
UPDATE sys_menu SET parent_id = 2255, path = 'detail/:id', order_num = 2 WHERE menu_id = 2233;

-- ── 11. 页面重挂：消息管理 ─────────────────────────────────────────────────
UPDATE sys_menu SET parent_id = 2256, path = 'announcement',    order_num = 1 WHERE menu_id = 2234;
UPDATE sys_menu SET parent_id = 2256, path = 'notify-template', order_num = 2 WHERE menu_id = 2235;

-- ── 12. 页面重挂：系统设置（2206 已在位）────────────────────────────────────
UPDATE sys_menu SET parent_id = 2206, path = 'config',         order_num = 1 WHERE menu_id = 2107;
UPDATE sys_menu SET parent_id = 2206, path = 'sensitive-word', order_num = 2 WHERE menu_id = 2230;
UPDATE sys_menu SET parent_id = 2206, path = 'log-center',     order_num = 3 WHERE menu_id = 2236;
UPDATE sys_menu SET parent_id = 2206, path = 'ai-retrieval',   order_num = 4 WHERE menu_id = 2207;

-- ── 13. 改名：一级目录已占用原名，页面侧改叫「列表」────────────────────────
-- 2109 原叫「订单管理」、2220 原叫「案件管理」，与新建的一级目录同名，
-- 侧边栏展开后会出现「订单管理 › 订单管理」这种两行一样字的条目。
-- 2109 改成「订单列表」与设计稿 10-order-list 的 crumb 一致；
-- 2220 设计稿本身就叫「案件管理」（18-case-manage），这里刻意偏离设计稿改成
-- 「案件列表」，理由同上：可读性优先。
UPDATE sys_menu SET menu_name = '订单列表' WHERE menu_id = 2109;
UPDATE sys_menu SET menu_name = '案件列表' WHERE menu_id = 2220;

-- ── 14. 隐藏（visible='1'，**不删数据**，随时可翻回来）──────────────────────
-- 100/101/103 = 若依原生的用户管理 / 角色管理 / 部门管理；
-- 102 = **菜单管理，故意不藏**（负责人要求留着当菜单维护入口）；
-- 3 = 系统工具，4 = 若依官网；
-- 2100 法泽业务 = 拆空后的旧容器；2101 业务总览 = 登录后 /index 已直接是数据看板，
-- 留着会出现两个入口。
UPDATE sys_menu SET visible = '1'
 WHERE menu_id IN (3, 4, 100, 101, 103, 2100, 2101);

-- 恢复办法（任意一项想显示回来，把 visible 改回 '0' 再刷新浏览器即可）：
--   UPDATE sys_menu SET visible = '0' WHERE menu_id = 100;

-- ── 15. 角色绑定：新目录 + 新页面绑给超级管理员（role_id = 1）──────────────
-- RuoYi 的 admin 角色本身跳过权限校验，这里绑是给「非超管身份登录时也能看到」用的。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
  (1, 2250), (1, 2251), (1, 2252), (1, 2253), (1, 2254), (1, 2255), (1, 2256),
  (1, 2223)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

-- ── 16. 回读校验（执行后人工确认）───────────────────────────────────────────
-- 16.1 一级目录：应看到 用户管理 / 律师管理 / 订单管理 / 财务管理 / 内容管理 /
--      案件管理 / 投诉工单 / 消息管理 / 系统设置 / 系统管理 / 系统监控
--      （系统工具、若依官网 visible=1 不出现）
SELECT menu_id, menu_name, order_num, path, visible
  FROM sys_menu
 WHERE parent_id = 0 AND status = '0'
 ORDER BY order_num, menu_id;

-- 16.2 每个目录下的页面数：法泽 9 个目录 + 系统管理与系统监控
SELECT p.menu_name AS parent_name, COUNT(c.menu_id) AS children,
       SUM(CASE WHEN c.visible = '0' THEN 1 ELSE 0 END) AS shown
  FROM sys_menu p LEFT JOIN sys_menu c ON c.parent_id = p.menu_id AND c.menu_type = 'C'
 WHERE p.parent_id = 0 AND p.menu_type = 'M' AND p.status = '0'
 GROUP BY p.menu_id, p.menu_name, p.order_num
 ORDER BY p.order_num;

-- 16.3 改造后的完整 URL 清单（对照表末 §17）
SELECT CONCAT(p.path, '/', c.path) AS url, c.menu_name, c.visible
  FROM sys_menu c JOIN sys_menu p ON p.menu_id = c.parent_id
 WHERE c.menu_type = 'C' AND c.menu_id BETWEEN 2100 AND 2299
 ORDER BY p.order_num, c.order_num;

-- ── 16. 改造后的 URL 对照（改硬编码时查这张表）──────────────────────────────
--  2102 用户列表        /members/list
--  2103 实名认证审核    /members/verification
--  2104 律师入驻审核    /lawyer/audit
--  2105 律师列表        /lawyer/list
--  2221 律师结算        /lawyer/settle
--  2109 订单管理        /order/list
--  2110 订单详情        /order/detail            （隐藏页，从列表点进来）
--  2106 余额与充值      /finance/wallet
--  2108 对账报表        /finance/reconcile
--  2222 提现审核        /finance/withdraw
--  2111 文章管理        /content/article
--  2112 文章编辑        /content/article/edit     （隐藏页）
--  2223 案例库          /content/case-library     ← 本次补建
--  2240 多语言翻译      /content/i18n
--  2210 文书模板        /content/template
--  2211 模板编辑        /content/template/edit/:id（隐藏页）
--  2201 法条库          /content/law-library
--  2220 案件管理        /caseflow/case
--  2231 内容审核        /caseflow/review
--  2232 工单管理        /ticket/list
--  2233 工单详情        /ticket/detail/:id        （隐藏页）
--  2234 公告与推送      /message/announcement
--  2235 通知模板        /message/notify-template
--  2107 系统参数        /setting/config
--  2230 敏感词管理      /setting/sensitive-word
--  2236 日志中心        /setting/log-center
--  2207 AI 对话检索     /setting/ai-retrieval
