-- ============================================================================
-- 第 7 段 B6/C3/C4/D4 · PC 后台运营治理 6 页的菜单种子（faze_admin 库）
--
-- 做什么：往 RuoYi 的 sys_menu / sys_role_menu 登记 6 个页面，让它们在左侧
--         「法泽业务」目录（menu_id 2100）下可见、可进入：
--           2230 A-25 敏感词管理        biz/ops/SensitiveWord
--           2231 A-20 内容审核队列      biz/ops/ReviewQueue
--           2232 A-21 工单列表          biz/ops/TicketList
--           2233 A-22 工单详情          biz/ops/TicketDetail（隐藏，从列表进）
--           2234 A-23 公告与推送        biz/ops/Announcement
--           2235 A-24 通知模板          biz/ops/NotifyTemplate
--
-- 目标库：**faze_admin**（RuoYi 的库），不是业务库 faze。
-- 依据：第 7 段工作流 §二之二「A-25 → A-20 → A-21/A-22 → A-23/A-24 → A-02 → A-26」的
--       页面顺序，与设计稿 design/admin/ 的 24/19/20/21/22/23 六屏一一对应。
-- 幂等：全部 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行。
--
-- ⚠️ **前置**：必须先执行 `database/seed/seg1_admin_menu.sql`（它建了父目录 2100「法泽业务」）。
--
-- 菜单 id 段：本段占用 **2230 起**（实测 2222 是当前最大，段 6 未占用 2223+）。
--   A-02 看板复用 2101「业务总览」（段 1 建的自检页，第 7 段改造为真看板），**不新增菜单**；
--   A-26 日志中心属 F 组，本批不动。
--
-- 执行方式：
--   mysql --host=127.0.0.1 --user=root --password=*** --database=faze_admin \
--         --default-character-set=utf8mb4 < database/seed/seg7_admin_menu.sql
--
-- 执行后：**刷新浏览器**（侧边栏由 getRouters 现查数据库）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 六个页面菜单
--    is_frame：0 = 是外链、1 = 否。**目录与菜单都写 1**（段 1 实测踩过）。
--    is_cache：1 = 缓存、0 = 不缓存。列表页缓存，详情页不缓存（每次要拉最新状态）。
--    visible：'0' 显示、'1' 隐藏。A-22 工单详情**隐藏**——它从 A-21 列表点进来，
--    不占侧边栏位置（但必须登记，否则 RuoYi 动态路由不认识这个 component）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query,
                      route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, remark)
VALUES
  (2230, '敏感词管理', 2100, 20, 'ops/sensitive-word', 'biz/ops/SensitiveWord', NULL,
   'OpsSensitiveWord', 1, 1, 'C', '0', '0', 'ops:sensitive-word:list', 'lock',
   'admin', NOW(), '第 7 段 B6：A-25 敏感词库 + 申诉复核'),
  (2231, '内容审核', 2100, 21, 'ops/review-queue', 'biz/ops/ReviewQueue', NULL,
   'OpsReviewQueue', 1, 0, 'C', '0', '0', 'ops:content-review:list', 'eye-open',
   'admin', NOW(), '第 7 段 B6：A-20 内容审核队列（原文只标注不改写）'),
  (2232, '工单管理', 2100, 22, 'ops/ticket-list', 'biz/ops/TicketList', NULL,
   'OpsTicketList', 1, 1, 'C', '0', '0', 'ops:ticket:list', 'message',
   'admin', NOW(), '第 7 段 C3：A-21 工单列表（两个 Tab + 超时标红）'),
  (2233, '工单详情', 2100, 23, 'ops/ticket-detail/:id', 'biz/ops/TicketDetail', NULL,
   'OpsTicketDetail', 1, 0, 'C', '1', '0', 'ops:ticket:detail', 'documentation',
   'admin', NOW(), '第 7 段 C4：A-22 工单详情（从列表进入，侧边栏隐藏）'),
  (2234, '公告与推送', 2100, 24, 'ops/announcement', 'biz/ops/Announcement', NULL,
   'OpsAnnouncement', 1, 0, 'C', '0', '0', 'ops:announcement:list', 'guide',
   'admin', NOW(), '第 7 段 D4：A-23 公告（预计触达 / 打开率 / 不可撤回）'),
  (2235, '通知模板', 2100, 25, 'ops/notify-template', 'biz/ops/NotifyTemplate', NULL,
   'OpsNotifyTemplate', 1, 1, 'C', '0', '0', 'ops:notify-template:list', 'form',
   'admin', NOW(), '第 7 段 D4：A-24 通知模板（变量校验 + 软停用）')
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name), parent_id = VALUES(parent_id),
                        order_num = VALUES(order_num), path = VALUES(path),
                        component = VALUES(component), icon = VALUES(icon),
                        is_frame = VALUES(is_frame), is_cache = VALUES(is_cache),
                        menu_type = VALUES(menu_type), visible = VALUES(visible),
                        status = VALUES(status), perms = VALUES(perms), remark = VALUES(remark);

-- ---------------------------------------------------------------------------
-- 2. 授予超级管理员角色（role_id=1）全部 6 个菜单
--    RuoYi 用 sys_role_menu 做菜单可见性；不插这张表，即使 sys_menu 有也看不到。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 2230), (1, 2231), (1, 2232), (1, 2233), (1, 2234), (1, 2235)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
