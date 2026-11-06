-- ============================================================================
-- 迁移：V303__init_i18n_glossary.sql
-- 所属段：第 3 段（AI 法律顾问）
-- 做什么：灌首批法律术语（i18n_glossary），**只灌 lang='zh-Hans' 的源术语**，共 51 条。
-- 依赖：V300__create_ai_tables.sql（i18n_glossary 10 字段）
-- 可回滚：本地库可 flyway clean；已进 main 后只能追加补丁迁移
-- 编写：2026-11-07
-- 依据：技术路线 §13.8 L3233 给的 40 个词 + 工作流 A4 第 2 条补的 10 个
--       （离婚/抚养权/监护/继承/工伤/劳动关系/竞业限制/经济补偿/诉讼费/管辖异议），
--       合计 51 条（要求 ≥50）。
--
-- 为什么 target_text 全是空串：
--   技术路线 §13.8 明确「术语翻译必须由母语法律译者确认，机翻不可靠」。
--   段 3 不许编藏/维/哈文译文（AGENTS.md 不编造原则；段 2 的 A-15 四语言 tab 同样处理）。
--   四语言译文由段 8 补，届时只 UPDATE target_text，不新增行、不改表结构。
--   同时**不许**给 bo / ug-Arab-CN / kk-Arab-CN 灌任何行——工作流 A4 验收第 4 条要求
--   「藏/维/哈三种 lang 的行不存在」。
--
-- 术语的用途：A4 的 GlossaryService 全量加载 → 从用户问题里做最长匹配 →
--   命中词以「术语：原词」列表注入系统 Prompt（D1），让模型用规范法律用词。
--   本段**只做读取 + 注入**，不做管理界面（`GET/POST/PUT /admin/glossary` 归段 8）。
--
-- 幂等：靠 uk_i18n_glossary_term_lang(term_key, lang) 去重，ON DUPLICATE KEY UPDATE
--   只刷新 source_text / note / updated_at，不动的字段不覆盖。
-- ============================================================================

INSERT INTO i18n_glossary
  (term_key, source_text, lang, target_text, note, status, is_enabled, created_at, updated_at)
VALUES
  ('plaintiff',                '原告',       'zh-Hans', '', '提起诉讼的一方当事人',                                'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('defendant',                '被告',       'zh-Hans', '', '被提起诉讼的一方当事人',                              'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('thirdParty',               '第三人',     'zh-Hans', '', '对诉讼标的有独立请求权或与案件结果有利害关系的人',    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('complaint',                '起诉状',     'zh-Hans', '', '原告向法院提交的启动诉讼文书',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('defenseStatement',         '答辩状',     'zh-Hans', '', '被告针对起诉状作出回应的文书',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('evidenceSubmission',       '举证',       'zh-Hans', '', '当事人向法院提交证据的行为',                          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('evidenceCrossExamination', '质证',       'zh-Hans', '', '对对方证据的真实性、合法性、关联性发表意见',          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('limitationOfAction',       '诉讼时效',   'zh-Hans', '', '权利受司法保护的法定期间，一般为三年',                'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('jurisdiction',             '管辖',       'zh-Hans', '', '确定案件由哪个法院受理的规则',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('mediation',                '调解',       'zh-Hans', '', '在第三方主持下协商解决争议',                          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('arbitration',              '仲裁',       'zh-Hans', '', '依仲裁协议由仲裁机构裁决争议',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('judgment',                 '判决',       'zh-Hans', '', '法院对案件实体问题作出的裁判',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('ruling',                   '裁定',       'zh-Hans', '', '法院对程序性问题作出的裁判',                          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('enforcement',              '执行',       'zh-Hans', '', '强制实现生效法律文书确定义务的程序',                  'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('appeal',                   '上诉',       'zh-Hans', '', '不服一审裁判请求上级法院审理',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('retrial',                  '再审',       'zh-Hans', '', '对已生效裁判启动的再次审理程序',                      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('evidence',                 '证据',       'zh-Hans', '', '证明案件事实的材料；注意与「举证」「质证」区分',      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('serviceOfProcess',         '送达',       'zh-Hans', '', '法院将诉讼文书交付当事人的程序',                      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('preservation',             '保全',       'zh-Hans', '', '为防止财产转移或证据灭失采取的临时措施',              'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('agency',                   '代理',       'zh-Hans', '', '以他人名义实施法律行为',                              'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('entrustment',              '委托',       'zh-Hans', '', '授权他人在授权范围内办理事务',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('legalAid',                 '法律援助',   'zh-Hans', '', '为经济困难当事人提供免费法律服务的制度',              'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('civilLiability',           '民事责任',   'zh-Hans', '', '违反民事义务应承担的法律后果',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('criminalLiability',        '刑事责任',   'zh-Hans', '', '实施犯罪行为应承担的法律后果',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('administrativeLiability',  '行政责任',   'zh-Hans', '', '违反行政法律规范应承担的法律后果',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('liquidatedDamages',        '违约金',     'zh-Hans', '', '当事人约定的违约赔偿金额；注意与「定金」区分',        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('deposit',                  '定金',       'zh-Hans', '', '担保合同履行的金钱，适用定金罚则；不是「订金」',      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('claim',                    '债权',       'zh-Hans', '', '债权人请求债务人为一定行为的权利',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('obligation',               '债务',       'zh-Hans', '', '债务人应向债权人为一定行为的义务',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('guarantee',                '担保',       'zh-Hans', '', '保障债权实现的法律制度的总称',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('mortgage',                 '抵押',       'zh-Hans', '', '不转移财产占有的担保，多用于不动产',                  'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('pledge',                   '质押',       'zh-Hans', '', '转移财产占有的担保，多用于动产与权利',                'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('lien',                     '留置',       'zh-Hans', '', '合法占有债务人动产时在其不履行债务时扣留该动产',      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('tort',                     '侵权',       'zh-Hans', '', '侵害他人合法权益应承担侵权责任',                      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('unjustEnrichment',         '不当得利',   'zh-Hans', '', '无法律根据取得利益致他人受损',                        'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('contractTermination',      '合同解除',   'zh-Hans', '', '使合同关系归于消灭的行为；注意与「合同终止」区分',    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('claimOfAction',            '诉讼请求',   'zh-Hans', '', '原告请求法院判决支持的具体主张',                      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('counterclaim',             '反诉',       'zh-Hans', '', '被告在本诉中对原告提出的独立请求',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('summaryProcedure',         '简易程序',   'zh-Hans', '', '事实清楚、权利义务关系明确的案件适用的简化程序',      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('smallClaimsProcedure',     '小额诉讼',   'zh-Hans', '', '标的额较小的金钱给付案件适用的一审终审程序',          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('compulsoryEnforcement',    '强制执行',   'zh-Hans', '', '义务人不履行生效文书时由法院强制其履行',              'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('divorce',                  '离婚',       'zh-Hans', '', '解除婚姻关系；注意与「撤销婚姻」「无效婚姻」区分',    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('custody',                  '抚养权',     'zh-Hans', '', '对未成年子女直接抚养的权利义务',                      'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('guardianship',             '监护',       'zh-Hans', '', '对无/限制民事行为能力人的人身财产照护',                'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('inheritance',              '继承',       'zh-Hans', '', '依法承受被继承人遗产的制度',                          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('workInjury',               '工伤',       'zh-Hans', '', '因工作原因受到事故伤害或患职业病',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('laborRelation',            '劳动关系',   'zh-Hans', '', '劳动者与用人单位之间的用工关系；注意与「劳务关系」区分', 'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('nonCompete',               '竞业限制',   'zh-Hans', '', '约定劳动者离职后一定期限内不从事竞争业务',            'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('economicCompensation',     '经济补偿',   'zh-Hans', '', '解除或终止劳动合同时用人单位支付的经济补偿',          'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('litigationFee',            '诉讼费',     'zh-Hans', '', '当事人进行诉讼应向法院交纳的费用',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('jurisdictionObjection',    '管辖异议',   'zh-Hans', '', '当事人对受诉法院管辖权提出的异议',                    'confirmed', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  source_text = VALUES(source_text),
  note        = VALUES(note),
  updated_at  = VALUES(updated_at);
