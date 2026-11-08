-- ============================================================================
-- 迁移：V304__import_law_items_batch2.sql
-- 所属段：第 3 段（AI 法律顾问）
-- 做什么：补 4 条首批法条——民法典 577 / 1062 / 1165 + 劳动合同法 47，使库内
--         法条由 6 条（段 0 的 464/509/667/680/1079 + V302 的 679）增至 10 条，
--         覆盖「违约责任 / 夫妻共同财产 / 侵权过错责任 / 劳动经济补偿」四类
--         黄金 30 题要用的场景（工作流 §五.8 的 5 条清单，679 已在 V302）。
-- 依赖：V1__baseline.sql（law_item）、V301__alter_law_item.sql（is_enabled 三列）
-- 可回滚：本地 flyway clean；已执行后只追加补丁迁移，不许改本文件
-- ============================================================================
-- 为什么另起 V304 而不是改 V302：
--   V302 已在开发库执行过。Flyway 对已执行迁移做 checksum 校验，改 V302 会让
--   所有已应用的库启动即报 `Migration checksum mismatch`。规范：已执行迁移不可回改。
--
-- 文本来源与核对状态（**必须由负责人用 flk.npc.gov.cn 复核一次**）：
--   * 原文取自《法泽FAZE_第3段工作流_v1.0.md》§5.8 L1952–2008（该节已含逐条原文
--     与 INSERT 模板，是冻结计划的一部分）；
--   * 劳动合同法第 47 条已另与国务院/福建省人社厅 2019-12-12 发布的《劳动合同法》
--     全文逐字核对一致（https://rst.fujian.gov.cn/fw/xzqlyx/bssxyj/201912/t20191212_5149303.htm）；
--   * 民法典 3 条未取到 flk.npc.gov.cn 原文（本机网络解析不到该域名的公网地址），
--     状态为「待负责人复核」，**不是「已核实」**。
--
-- 三条硬要求（docs/standards/法条数据录入规范.md）：
--   ① 逐字准确：不许改写、不许精简、不许合并条款；段内换行保留（\n）。
--   ② article_no（第六百七十七条式中文）与 article_seq（整数）都要填，
--      且 ChineseNumberUtil.toArabic(article_no) == article_seq。
--   ③ law_code 用小写短码，只能用规范 §一③ 表里的 6 个：
--      civil_code / civil_procedure / labor_contract / labor_dispute /
--      consumer_protection / marriage_judicial_1。
--
-- effective_date 是唯一键 uk_law_article(law_code, article_no, effective_date) 的
-- 组成部分，**必填且必须正确**：
--   * 民法典 = 2021-01-01（2020-05-28 通过）；
--   * 劳动合同法 = **2013-07-01**（2012 修正版施行日）——不是 2008-01-01 那版。
--     写错日期会插出「同一条文的两个版本」两条行，B3 的 findByArticleNo 就可能
--     取到错的那条，且历史 refs 快照会指向不存在的版本。
-- ============================================================================

INSERT INTO law_item
  (law_code, law_name, article_no, article_seq, content, effective_date, expire_date,
   status, keywords, is_enabled, created_by, created_by_type, created_at, updated_at)
VALUES
  ('civil_code', '中华人民共和国民法典', '第五百七十七条', 577,
   '当事人一方不履行合同义务或者履行合同义务不符合约定的，应当承担继续履行、采取补救措施或者赔偿损失等违约责任。',
   '2021-01-01', NULL,
   'active', '违约责任,合同违约,继续履行,赔偿损失,解除合同,不发货', 1, NULL, 'system', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('civil_code', '中华人民共和国民法典', '第一千零六十二条', 1062,
   '夫妻在婚姻关系存续期间所得的下列财产，为夫妻的共同财产，归夫妻共同所有：\n（一）工资、奖金、劳务报酬；\n（二）生产、经营、投资的收益；\n（三）知识产权的收益；\n（四）继承或者受赠的财产，但是本法第一千零六十三条第三项规定的除外；\n（五）其他应当归共同所有的财产。\n夫妻对共同财产，有平等的处理权。',
   '2021-01-01', NULL,
   'active', '夫妻共同财产,离婚财产,婚姻存续,共同财产,财产分割', 1, NULL, 'system', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('civil_code', '中华人民共和国民法典', '第一千一百六十五条', 1165,
   '行为人因过错侵害他人民事权益造成损害的，应当承担侵权责任。\n依照法律规定推定行为人有过错，其不能证明自己没有过错的，应当承担侵权责任。',
   '2021-01-01', NULL,
   'active', '侵权责任,过错责任,损害赔偿,过错推定,漏水,人身损害', 1, NULL, 'system', UTC_TIMESTAMP(), UTC_TIMESTAMP()),
  ('labor_contract', '中华人民共和国劳动合同法', '第四十七条', 47,
   '经济补偿按劳动者在本单位工作的年限，每满一年支付一个月工资的标准向劳动者支付。六个月以上不满一年的，按一年计算；不满六个月的，向劳动者支付半个月工资的经济补偿。\n劳动者月工资高于用人单位所在直辖市、设区的市级人民政府公布的本地区上年度职工月平均工资三倍的，向其支付经济补偿的标准按职工月平均工资三倍的数额支付，向其支付经济补偿的年限最高不超过十二年。\n本条所称月工资是指劳动者在劳动合同解除或者终止前十二个月的平均工资。',
   '2013-07-01', NULL,
   'active', '经济补偿,辞退赔偿,工作年限,月工资,辞退,裁员,解除劳动合同', 1, NULL, 'system', UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  law_name        = VALUES(law_name),
  article_seq     = VALUES(article_seq),
  content         = VALUES(content),
  status          = VALUES(status),
  keywords        = VALUES(keywords),
  is_enabled      = VALUES(is_enabled),
  updated_at      = VALUES(updated_at);
