-- ============================================================================
-- 第 2 段 · sys_config 种子数据（手工执行，**不进 Flyway**）
--
-- 口径沿用第 1 段（见 seg1_config.sql 头部说明）：
--   配置值会随环境改，放迁移里等于每次部署都覆盖运营在 PC A-08 改过的值。
--   因此段内新增的配置键一律用种子脚本，幂等：ON DUPLICATE KEY UPDATE
--   只刷新说明与分类，**不覆盖已改过的 config_value**。
-- 执行方式：
--   mysql -uroot --default-character-set=utf8mb4 faze -e "source database/seed/seg2_config.sql"
-- ============================================================================

INSERT INTO sys_config
    (config_key, config_value, value_type, category, remark, editable, created_at, updated_at)
VALUES
    -- G2.1「下单前是否要求律师在线」开关。
    --   默认 false：律师可能只是没开 App，写死 true 会误杀正常业务（工作流 G2.1「卡住怎么办」）。
    --   打开后，online_status != 'online' 的律师不可被下单，返回 405010。
    ('order_require_lawyer_online', 'false', 'bool', 'trade',
     '下单是否要求律师在线（true=离线不可下单；默认 false，避免律师没开 App 就被误杀）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- G3.1「分类 tab → 擅长领域」的展开表（JSON 对象）。
    --   设计稿的 5 个分类是固定的，而入驻页（段 1）提交的是 10 个预定义标签的多选。
    --   归类口径由负责人 2026-11-06 拍板：民事纠纷吸收劳动/债权/交通/房产/其他，合同纠纷吸收知识产权。
    --   要把某几类并进某个 tab，改这里即可，不用改代码、不用建分类关联表
    --   （新建关联表会与段 1 的 JSON 字段形成双真相）。
    ('lawyer_specialty_enum',
     '{"民事纠纷":["民事纠纷","劳动纠纷","债权债务","交通事故","房产纠纷","其他"],"刑事辩护":["刑事辩护"],"婚姻家事":["婚姻家事"],"合同纠纷":["合同纠纷","知识产权"]}',
     'json', 'lawyer',
     '律师分类 tab → 擅长领域值数组（JSON）；默认同名 1:1，扩展归类只改这里',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
    -- G3.1 列表排序键（白名单：online_rating / rating_desc）。
    --   值不直接进 SQL，只决定 LawyerMapper.xml 里走哪个 choose 分支（规范禁 ${} 拼排序）。
    ('lawyer_list_sort', 'online_rating', 'string', 'lawyer',
     '律师列表排序键：online_rating（在线优先+评分+订单数）/ rating_desc（评分降序）',
     1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
    value_type = VALUES(value_type),
    category   = VALUES(category),
    remark     = VALUES(remark),
    updated_at = UTC_TIMESTAMP();
