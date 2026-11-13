/*
 * 职责：清掉 RuoYi 脚手架在 faze_admin 库里留下的「若依」痕迹（工单 W17）。
 * 调用方：部署时在跑完 pc/admin-server/sql/ry_20260320.sql 之后执行一次；幂等，可重复跑。
 * 数据来源：faze_admin 的 sys_user / sys_notice / sys_job / sys_config。
 * 注意：
 *   1. **不改 pc/admin-server/sql/ry_20260320.sql**。那是 RuoYi 3.9.2 官方发行版的初始化脚本，
 *      改它等于篡改「强制版本」基线，以后无法判断哪些是发行版自带、哪些是我们改的。
 *      正确做法是「官方脚本原样跑 → 再跑本脚本清理」，本脚本就是那一步。
 *   2. 每条都带 WHERE 限定，重复执行结果不变（不会把后来的真实数据误删）。
 *   3. `ry` 账号用**逻辑删**（del_flag='2'）不是物理删：RuoYi 自身也是这么删用户的，
 *      误删可以改回 '0' 恢复。
 *   4. `sys.user.initPassword` 是「新建用户时的初始口令」，RuoYi 默认 123456 属弱口令。
 *      改成一个必须满足大小写+数字+符号的串，但**新建用户后仍应强制改密**（sys.account.initPasswordModify）。
 */
SET NAMES utf8mb4;

-- 1. 顶栏右上角显示的是 nick_name，admin 的昵称是「若依」
UPDATE sys_user
   SET nick_name = '管理员'
 WHERE user_name = 'admin'
   AND del_flag = '0'
   AND nick_name = '若依';

-- 2. RuoYi 自带的 ry 演示账号（口令 admin123，停用状态也一直挂在用户列表里）
UPDATE sys_user
   SET del_flag = '2'
 WHERE user_name = 'ry'
   AND del_flag = '0';

-- 3. 三条 2018 年的若依示例公告（登录后首页「通知公告」区会显示）
DELETE FROM sys_notice
 WHERE notice_title LIKE '%若依%'
    OR notice_title LIKE '%2018-07-01%';

-- 4. 三个 ryTask 示例定时任务（暂停状态，不跑，但会出现在定时任务列表里）
DELETE FROM sys_job
 WHERE invoke_target LIKE 'ryTask%';

-- 5. 新建用户的初始口令
UPDATE sys_config
   SET config_value = 'Faze@Init2026'
 WHERE config_key = 'sys.user.initPassword'
   AND config_value = '123456';
