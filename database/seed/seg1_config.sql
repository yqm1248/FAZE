-- ============================================================================
-- 第 1 段 · sys_config 种子数据（手工执行，**不进 Flyway**）
--
-- 为什么不进迁移：《第 1 段工作流》B1 明确「sys_config 插入 4 个键，用种子脚本，不放迁移里」。
--   原因：配置值会随环境改（开发期固定码、生产关掉），放迁移里等于每次部署都覆盖运营改过的值。
-- 执行方式：
--   mysql -uroot --default-character-set=utf8mb4 faze -e "source database/seed/seg1_config.sql"
-- 幂等：ON DUPLICATE KEY UPDATE 只刷新说明与分类，**不覆盖已改过的 config_value**
--   （运营在 PC A-08 调过的值，重跑种子不能被冲掉）。
-- ============================================================================

INSERT INTO sys_config
    (config_key, config_value, value_type, category, remark, editable, created_at, updated_at)
VALUES
    ('sms_fixed_code_enabled', 'true',  'bool',  'sms',      '开发期固定验证码开关；生产 profile 启动时强制校验必须为 false', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('sms_fixed_code',         '123456','string','sms',      '开发期固定验证码（仅 dev 生效，生产不读本键）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('sms_send_interval_seconds', '60', 'int',   'sms',      '同一手机号两次发送的最小间隔（秒）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('sms_daily_limit',        '10',    'int',   'sms',      '同一手机号每日发送上限（次）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('login_max_fail',         '5',     'int',   'security', '登录密码连续错误次数上限，达到后锁定', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('login_lock_minutes',     '15',    'int',   'security', '登录锁定分钟数（与支付密码的 30 分钟分开）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('pay_pwd_max_fail',       '5',     'int',   'security', '支付密码连续错误次数上限（负责人 2026-11-01 拍板）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('pay_pwd_lock_minutes',   '30',    'int',   'security', '支付密码锁定时长（分钟）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('platform_fee_rate',      '0.15',  'decimal','trade',   '平台服务费比例（占位 15%，段 2 订单生效）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('order_no_format',        'PREFIX2+yyyyMMdd+SEQ6', 'string', 'trade', '平台单号格式（段 2 建 orders.order_no 时生效）', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- 以下两条是 G1「用户服务协议页 / 隐私政策页」的正文来源（裁决 #12：正文走配置、页面只渲染）。
    -- 值里是**占位文本**，上线前必须由法务定稿替换；不可回退为写死在页面里。
    -- 分类用 legal：与 sms/security/trade 分开，PC A-08 按分类筛时一眼能看出这两条不是技术参数。
    ('user_agreement_content', '【占位文本 · 上线前须由法务定稿替换】

法泽 FAZE 用户服务协议

一、服务主体
本平台由伊犁师范大学法学专业实验基地提供法律知识与法律服务对接能力，运营主体信息以平台公示为准。

二、服务内容
平台提供法律知识问答、律师信息展示、律师入驻申请、在线咨询与订单服务。平台不是律师事务所，不直接提供律师执业服务；具体法律服务由入驻律师依据执业规范提供。

三、账号与实名
用户须以本人手机号注册，并对账号下的全部操作负责。使用咨询、下单等需要身份核验的功能前，须完成实名认证。

四、账户余额
平台采用账户余额模式：用户通过微信、支付宝等第三方渠道充值，消费时以余额抵扣。余额不计息、不可转让。充值前请确认服务需求，退款规则以平台另行公示的说明为准。

五、青少年保护
未成年人使用本平台须由监护人知情并同意，注册时须填写监护人手机号。

六、内容规范
用户不得利用本平台发布违法信息、实施欺诈、侵犯他人合法权益。平台有权对违规账号采取限制功能、封禁直至注销的措施。

七、知识产权
平台界面、文档模板与法条汇编的组织方式归平台所有；用户发布的咨询内容归用户，平台在提供服务所需范围内使用。

八、协议变更
平台有权根据法律法规与业务调整修订本协议，修订后在本页公示。用户继续使用即视为接受修订后的协议。

九、联系方式
如对本协议有异议，可通过平台内「账号与安全」页提供的客服渠道联系。

（以上为占位条款，不构成正式法律文件。）', 'string', 'legal', '用户服务协议正文（APP 协议页渲染；上线前必须替换为法务定稿）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    ('privacy_policy_content', '【占位文本 · 上线前须由法务定稿替换】

法泽 FAZE 隐私政策

一、我们收集哪些信息
1. 注册与登录：手机号、密码（加密存储）、验证码记录。
2. 实名认证：真实姓名、身份证号、身份证影像。身份证号加密存储，页面与接口只返回掩码。
3. 服务过程：咨询内容、订单记录、余额与流水、设备标识与登录日志。

二、我们如何使用信息
用于身份核验、撮合律师服务、生成订单与结算、保障账号安全、按法律法规要求留存审计记录。不用于与上述目的无关的画像或推送。

三、我们如何共享
除以下情形外不向第三方提供：为完成服务所必需的入驻律师（仅限该订单所需信息）、为完成支付所必需的支付渠道、法律法规要求或有权机关依法调取。

四、我们如何保护信息
敏感字段加密存储、传输链路加密、访问按最小权限控制、审计日志不可篡改。身份证影像仅用于审核，审核流程内部流转。

五、你的权利
你可以在「个人资料」页查看与修改资料，在「账号与安全」页修改密码、换绑手机号、申请注销账号。注销立即生效，手机号立即释放，届时账号内余额不可恢复，请先确认余额已处理。

六、信息留存
账号注销后，交易与审计类记录按法律法规要求的最短必要期限留存，其余个人信息删除或匿名化。

七、未成年人
未成年人使用本平台须取得监护人同意。涉及未成年人的个人信息按更严格的标准处理。

八、联系我们
如对本政策有疑问，可通过平台内「账号与安全」页提供的客服渠道联系。

（以上为占位条款，不构成正式法律文件。）', 'string', 'legal', '隐私政策正文（APP 隐私页渲染；上线前必须替换为法务定稿）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
AS new
ON DUPLICATE KEY UPDATE
    value_type = new.value_type,
    category   = new.category,
    remark     = new.remark,
    updated_at = new.updated_at;
