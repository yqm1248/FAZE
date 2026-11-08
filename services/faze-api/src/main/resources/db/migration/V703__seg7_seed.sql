-- ============================================================================
-- 迁移：V703__seg7_seed.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：本段种子数据，四类：
--         ① 50 条敏感词示例（五类 × 各 10 条，lang='zh-Hans'）
--         ② 7 条 FAQ（APP 56 稿的 7 个问题）
--         ③ 6 条通知模板
--         ④ 11 个 sys_config 配置项
--
-- 依据：《第 7 段工作流》§三 A1 第 4 步（四类种子的清单与数量）。
--
-- 幂等：本文件**必须可重复执行**（A1 算做完第 3 条：跑两遍不报错、不产生重复行）。
--   有唯一键的三张表（sensitive_word / notify_template / sys_config）用
--   ON DUPLICATE KEY UPDATE；faq 无唯一键，用 INSERT ... WHERE NOT EXISTS。
--   注意：ON DUPLICATE KEY UPDATE 只更新「允许运营改的内容」，不重置 hit_count /
--   last_hit_at（那两个是运行期累计值，重跑迁移不该清零）。
--
-- ⚠️ 两件事必须让后人知道：
--   1. **政治/暴恐/色情三类的 30 条是占位词**（【示例】前缀）。正式词库由负责人/
--      运营提供（裁决 17 已挂账）。占位词的作用是让「五类 × ten / 级别分档 / 引擎
--      加载 50 条」这些机制可验，**不是可用词库，上线前必须整体替换**。
--      欺诈/违法两类是真实可用的行业禁语与通用话术（法律行业「包打赢」等属
--      广告法/律师执业规范明令禁止的承诺性表述）。
--   2. **log.operation_retention_days 必须 ≥ 1095（3 年）**。工作流 A1 第 4 步
--      的清单里写的是 90，与同文档裁决 16（operation_log ≥ 3 年、login_log ≥ 180 天）
--      直接冲突；且 A4 第 6 条要求「低于合规下限则启动失败」。按裁决 16 执行，
--      此处写 1095。详见 §十一 偏差记录。
--
-- 时间字段：库内一律 UTC，此处用 UTC_TIMESTAMP()（种子数据无应用层可依托）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① 敏感词 50 条
--    POLITICS / VIOLENCE / PORN → block（拦截，用户可见申诉入口）
--    FRAUD / ILLEGAL           → review（送审，正常投递但进 A-20 队列）
-- ---------------------------------------------------------------------------
INSERT INTO sensitive_word (word, category, level, lang, source, enabled, hit_count, created_at, updated_at) VALUES
-- 政治类（占位 × 10）
('【示例】政治类敏感词01', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词02', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词03', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词04', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词05', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词06', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词07', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词08', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词09', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】政治类敏感词10', 'POLITICS', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- 暴恐类（占位 × 10）
('【示例】暴恐类敏感词01', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词02', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词03', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词04', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词05', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词06', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词07', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词08', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词09', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】暴恐类敏感词10', 'VIOLENCE', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- 色情低俗类（占位 × 10）
('【示例】色情低俗类敏感词01', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词02', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词03', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词04', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词05', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词06', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词07', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词08', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词09', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('【示例】色情低俗类敏感词10', 'PORN', 'block', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- 欺诈类（真实：法律行业承诺性禁语 + 通用欺诈话术 × 10）
('包打赢', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('稳赢', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('百分百胜诉', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('保证判决', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('不赢不收费', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('法院有熟人', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('包取保', 'FRAUD', 'review', 'zh-Hans', 'legal', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('刷单返利', 'FRAUD', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('冒充公检法', 'FRAUD', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('内部指标', 'FRAUD', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- 违法类（真实 × 10）
('代开发票', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('办假证', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('买卖驾照分', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('私家侦探', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('偷拍', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('网络赌博', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('代考', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('虚假诉讼', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('倒卖公民信息', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('非法拘禁', 'ILLEGAL', 'review', 'zh-Hans', 'seed', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  category   = VALUES(category),
  level      = VALUES(level),
  source     = VALUES(source),
  enabled    = VALUES(enabled),
  updated_at = UTC_TIMESTAMP();

-- ---------------------------------------------------------------------------
-- ② FAQ 7 条（APP 56 稿的 7 个问题；四分类：账号 / 支付 / 律师 / 立案）
--    无唯一键 → 用 NOT EXISTS 保证重跑不重复。
-- ---------------------------------------------------------------------------
INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'account', '如何注册和登录法泽？',
       '使用手机号注册，获取短信验证码后设置密码即可登录。\n也可以使用手机号 + 密码直接登录。\n如忘记密码，可在登录页点击「忘记密码」重置。',
       'zh-Hans', 1, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '如何注册和登录法泽？' AND lang = 'zh-Hans');

INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'account', '忘记密码怎么办？',
       '在登录页点击「忘记密码」，输入注册手机号并获取短信验证码，即可重置密码。\n每次重置需 1 分钟间隔，单日最多 10 次。',
       'zh-Hans', 2, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '忘记密码怎么办？' AND lang = 'zh-Hans');

INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'pay', '如何充值？未消费的余额可以退吗？',
       '在「我的 - 账户余额」中点「充值」，可选择微信或支付宝支付。\n充值金额实时到账，可用于支付咨询、办案与文书服务。\n余额为预付款，按平台规则可申请退款，具体以用户服务协议为准。',
       'zh-Hans', 3, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '如何充值？未消费的余额可以退吗？' AND lang = 'zh-Hans');

INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'lawyer', '如何选择合适的律师？',
       '可在律师列表中按专业领域、执业年限、服务评分筛选，查看律师详情与用户评价后再决定。\n建议先进行一次图文咨询，确认律师的专业方向与您的案件匹配后再委托。',
       'zh-Hans', 4, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '如何选择合适的律师？' AND lang = 'zh-Hans');

INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'lawyer', '对律师服务不满意怎么办？',
       '可在「订单详情」中查看服务记录，或在「客服中心 - 投诉」中提交投诉，需关联已完成的律师服务订单。\n平台将在 3 个工作日内核实并反馈处理结果。',
       'zh-Hans', 5, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '对律师服务不满意怎么办？' AND lang = 'zh-Hans');

INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'case', '在线立案需要准备哪些材料？',
       '通常需要：起诉状、身份证明、证据材料清单及证据复印件、被告身份信息。\n不同案件类型要求不同，可在「在线立案」中选择案件类型后查看所需材料清单。\n材料不齐时法院会退回补正，可在「我的案件」中查看进度。',
       'zh-Hans', 6, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '在线立案需要准备哪些材料？' AND lang = 'zh-Hans');

INSERT INTO faq (category, question, answer, lang, sort_order, status, created_at, updated_at)
SELECT 'case', '立案后多久能收到受理通知？',
       '法院一般在收到材料后 7 日内决定是否受理，具体时限以受理法院规定为准。\n受理结果会通过站内消息通知您，也可在「我的案件」中查看案件进度。',
       'zh-Hans', 7, 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '立案后多久能收到受理通知？' AND lang = 'zh-Hans');

-- ---------------------------------------------------------------------------
-- ③ 通知模板 6 条（zh-Hans / inapp）
--    变量只用具名 {{camelCase}}，禁位置参数（i18n 规范）。
-- ---------------------------------------------------------------------------
INSERT INTO notify_template (template_code, lang, title, content, channel, status, created_at, updated_at) VALUES
('order_paid',      'zh-Hans', '订单已支付',         '您的订单 {{orderNo}} 已支付成功，律师将尽快与您联系。', 'inapp', 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('order_accepted',  'zh-Hans', '律师已接单',         '律师 {{lawyerName}} 已接受您的订单 {{orderNo}}，可前往订单详情查看。', 'inapp', 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('ticket_replied',  'zh-Hans', '工单有新回复',       '您的工单 {{ticketNo}} 有新回复，请前往客服中心查看。', 'inapp', 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('review_result',   'zh-Hans', '申诉已处理',         '您对内容 {{contentReviewId}} 的申诉已处理：{{handleResult}}。', 'inapp', 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('violation_notice','zh-Hans', '账号使用受限',       '您的账号因「{{reason}}」被限制使用，如有疑问请联系客服或提交申诉。', 'inapp', 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('announcement',    'zh-Hans', '{{title}}',          '{{content}}', 'inapp', 'active', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  title      = VALUES(title),
  content    = VALUES(content),
  channel    = VALUES(channel),
  status     = VALUES(status),
  updated_at = UTC_TIMESTAMP();

-- ---------------------------------------------------------------------------
-- ④ sys_config 11 个键
--    注意：config_value 一律字符串；布尔用 true/false，数字用十进制字符串，
--    JSON（如分档配置）直接存 JSON 文本。
--    没有在这里写的（如 alert.dingtalk_webhook 的值）由负责人填，初始为空串。
-- ---------------------------------------------------------------------------
INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('dashboard.dau_definition',        'login_or_request', 'string', 'dashboard', 'A-02 日活口径：当日登录成功或有任意业务请求的去重用户；取 login_or_request 时按登录去重（降级口径）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('log.operation_retention_days',    '1095',             'int',    'audit',     '操作日志留存天数（合规下限 1095 = 3 年，低于此值服务拒绝启动）', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('log.login_retention_days',        '180',              'int',    'audit',     '登录日志留存天数（合规下限 180 天 = 网安法 6 个月，低于此值服务拒绝启动）', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('service.online_hours',            '09:00-20:00',      'string', 'service',   '人工客服在线时段（以 APP 55/56 设计稿为准）；时段外走留言', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('service.phone',                   '0999-XXXXXXX',     'string', 'service',   '客服电话占位；正式号码确定后由负责人替换（记挂账）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('ticket.sla_hours',                '24',               'int',    'service',   '工单 SLA 受理时限（小时），落库到 service_ticket.sla_due_at', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('alert.dingtalk_webhook',          '',                 'string', 'alert',     '钉钉自定义机器人 webhook 地址（Jasypt 加密；空表示未配置，告警走日志+mock）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('alert.dingtalk_secret',           '',                 'string', 'alert',     '钉钉机器人加签密钥（Jasypt 加密；空表示未配置）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('alert.error_rate_pct',            '5',                'int',    'alert',     '5xx 错误率告警阈值（百分比）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('alert.p95_ms',                    '1000',             'int',    'alert',     'P95 响应时延告警阈值（毫秒）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('alert.disk_pct',                  '80',               'int',    'alert',     '磁盘使用率告警阈值（百分比）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('sensitive.reload_pubsub_channel', 'faze:sensitive:reload', 'string', 'sensitive', '敏感词热更新 Redis Pub/Sub 频道名', 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  value_type = VALUES(value_type),
  category   = VALUES(category),
  remark     = VALUES(remark),
  editable   = VALUES(editable),
  updated_at = UTC_TIMESTAMP();
