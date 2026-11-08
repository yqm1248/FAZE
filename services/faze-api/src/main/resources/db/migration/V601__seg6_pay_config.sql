-- ============================================================================
-- 迁移：V601__seg6_pay_config.sql
-- 所属段：第 6 段（真实充值渠道 + 掉单补单）
-- 做什么：写入本段需要的 sys_config 配置键（21 个渠道/充值键 + 3 个 Mock 测试开关）。
-- 依赖：V300 的 sys_config（uk_config_key 唯一）；V600 的 recharge_record（不依赖，仅同段）。
-- 编写：2026-11-08
-- 依据：
--   《第 6 段工作流》§三 A3 第 5 条（21 个键的清单与默认值）、第 4 条（Mock 三个测试开关）；
--   §零之二 第 2 项（渠道参数不建表，全放 sys_config）、第 14/15/16/17 项（金额与超时取值）。
--
-- ⚠️ 两个加密项 `pay.wechat.api_v3_key` / `pay.alipay.private_key` 在迁移里**先留空**：
--    密文与密钥绑定，迁移里写死一个 ENC(...) 只对开发库有效，带进生产会解密失败。
--    正确流程（写进 docs/acceptance/seg6/A3-config.md）：
--      ① 生成 32 字节密钥设 FAZE_CONFIG_SECRET（生产必须另起一把，不复用 dev 默认值）；
--      ② 用 ConfigCipher#encrypt 得到 ENC(...)；
--      ③ UPDATE sys_config SET config_value='ENC(...)' WHERE config_key='pay.wechat.api_v3_key';
--    开发库的演示加密值由 E2 验收脚本写入，不写进迁移。
--
-- 幂等：ON DUPLICATE KEY UPDATE 只刷新 updated_at，不覆盖已改过的值
--       （运营在 PC 系统参数页改过的配置，重跑迁移不会被重置回默认值）。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
-- ── 渠道开关（默认全关，只开 Mock 供段 6 验收；生产由 ProdMockPayGuard 强制关 Mock） ──
('pay.mock.enabled',              'true',                          'bool',   'pay', 'Mock 支付通道总开关。开发 true；生产必须 false，否则启动失败（ProdMockPayGuard）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.wechat.enabled',            'false',                         'bool',   'pay', '微信支付通道开关。商户号未下发前保持 false', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.alipay.enabled',            'false',                         'bool',   'pay', '支付宝通道开关。商户号未下发前保持 false', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- ── 微信参数（api_v3_key 必须 ENC(...) 包裹后才可用） ──
('pay.wechat.appid',              '',                              'string', 'pay', '微信应用 AppID', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.wechat.mch_id',             '',                              'string', 'pay', '微信支付商户号', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.wechat.api_v3_key',         '',                              'string', 'pay', '微信 APIv3 密钥（**必须 ENC(...) 加密存储**，验签与报文解密用）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.wechat.cert_path',          '',                              'string', 'pay', '微信商户证书文件路径；**证书文件不进仓库**', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.wechat.cert_serial',        '',                              'string', 'pay', '微信商户证书序列号', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- ── 支付宝参数（private_key 必须 ENC(...) 包裹） ──
('pay.alipay.appid',              '',                              'string', 'pay', '支付宝应用 AppID', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.alipay.mch_id',             '',                              'string', 'pay', '支付宝商户号', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.alipay.private_key',        '',                              'string', 'pay', '支付宝应用私钥（**必须 ENC(...) 加密存储**，签名用）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.alipay.public_key',         '',                              'string', 'pay', '支付宝公钥（验签用，非敏感，可明文）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- ── 回调 ──
('pay.callback_base_url',         '',                              'string', 'pay', '回调域名前缀，拼成 {base}/api/v1/pub/pay/callback/{channel}。域名未定可留空，Mock 不依赖', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.callback_ips',              '',                              'string', 'pay', '回调来源 IP 白名单（逗号分隔）；留空则只靠验签', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- ── 充值规则（金额单位一律分） ──
('recharge.min_cents',            '10',                            'int',    'recharge', '单笔最低 10 分（0.1 元）。负责人 2026-11-02 定，设计稿「10 元起」作废', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('recharge.max_amount_cents',     '500000',                        'int',    'recharge', '单笔最高 500000 分（5000 元）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('recharge.max_daily_cents',      '500000',                        'int',    'recharge', '单日累计上限 500000 分（5000 元）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('recharge.max_daily_count',      '10',                            'int',    'recharge', '单日充值笔数上限', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('recharge.amount_options',       '[10000,20000,50000,100000,200000]', 'json', 'recharge', '充值档位（JSON 数组，单位分）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('recharge.expire_minutes',       '30',                            'int',    'recharge', '充值单超时分钟数（上线值 30 分钟）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('recharge.unverified_daily_cents','0',                            'int',    'recharge', '未实名用户单日额度；0 = 完全不可充值（是拦截不是限额）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
-- ── Mock 测试开关（只在 pay.mock.enabled=true 时生效；生产不许出现非默认值） ──
('pay.mock.delay_ms',             '0',                             'int',    'pay', 'Mock 回调延迟毫秒；验 6-A2 重放窗口用', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.mock.result',               'success',                       'string', 'pay', 'Mock 结果：success / fail / callback_lost（下单成功但回调不发，造掉单 6-A3）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('pay.mock.amount_offset_cents',  '0',                             'int',    'pay', 'Mock 回调金额偏移（分）；造对账差异 6-A4 用', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
