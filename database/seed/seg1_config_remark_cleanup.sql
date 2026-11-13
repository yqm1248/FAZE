/*
 * 职责：清掉 sys_config.remark 里的内部开发术语（工单 W16，「段 2」「本段」「验收编号」等上屏）。
 * 调用方：**新环境初始化后补跑一次**（幂等，可重复执行）。
 * 数据来源：faze.sys_config。
 * 注意：
 *   1. `platform_fee_rate` / `order_no_format` / `ai_message_retention_days` 三条的 seed
 *      （seg1_config.sql / seg3_config.sql）已经同步改过，新环境跑 seed 就是新文案；
 *      本脚本里这几条是给**已经初始化过的库**补的，重复执行结果不变。
 *   2. `pay.mock.*` / `reconcile.mock.inject` / `alert.dingtalk_webhook` 不在 seed 里
 *      （运行时由代码插入），只能靠本脚本清。
 *   3. 只改 remark（给管理员看的说明），不动 config_value，不改变任何行为。
 */
SET NAMES utf8mb4;

UPDATE sys_config SET remark = '平台服务费比例（当前 15%，下单时生效）'
 WHERE config_key = 'platform_fee_rate';

UPDATE sys_config SET remark = '平台单号格式（生成订单号时使用）'
 WHERE config_key = 'order_no_format';

UPDATE sys_config SET remark = 'AI 对话留存天数（1095=3 年）；系统不自动清理，清理需人工审批并先归档'
 WHERE config_key = 'ai_message_retention_days';

UPDATE sys_config SET remark = '模拟支付通道总开关。仅开发环境开启；生产必须关闭，否则启动失败'
 WHERE config_key = 'pay.mock.enabled';

UPDATE sys_config SET remark = '模拟回调延迟（毫秒），仅用于联调'
 WHERE config_key = 'pay.mock.delay_ms';

UPDATE sys_config SET remark = '模拟结果：success / fail / callback_lost'
 WHERE config_key = 'pay.mock.result';

UPDATE sys_config SET remark = '模拟回调金额偏移（分），用于构造对账差异'
 WHERE config_key = 'pay.mock.amount_offset_cents';

UPDATE sys_config SET remark = '模拟账单差异注入规格（JSON，仅验收用；生产必须为空）'
 WHERE config_key = 'reconcile.mock.inject';

UPDATE sys_config SET remark = '钉钉自定义机器人 webhook 地址（加密存储；空表示未配置，告警只落日志）'
 WHERE config_key = 'alert.dingtalk_webhook';
