-- ============================================================================
-- 文件：scripts/monitor/data-integrity.sql
-- 职责：数据完整性巡检（DIRT 检查）。回答「库里有没有违反代码自身约束的行」。
--
-- 调用方：
--   - 人工：mysql ... faze < scripts/monitor/data-integrity.sql
--   - 每轮验收前、发布前各跑一次
--
-- 数据来源：只读 faze 库。**本文件不得包含任何写操作**（DELETE/UPDATE/INSERT）。
--
-- 为什么单独一份而不并进对账脚本：
--   `verify-balance.ps1` 查的是「钱对不对」（恒等式）；
--   本文件查的是「行合不合法」（状态、孤儿、编码）。两者失败的处置完全不同：
--   钱不对 = P0 停手上报；行不合法 = 清数据或改代码。
--
-- 判读规则（唯一）：
--   结果里每一行的 n = 0 为通过；**n > 0 必须逐条人工处置**，没有"可接受的非零"。
--
-- 与 DIRT 的关系（见 docs/standards/说辞一致与真相源规范.md §2.3）：
--   DIRT = 代码正确、库里数据违规。因此**修法永远不是改文档**：
--     · 测试造数 → 删行（并留备份）
--     · 代码缺陷持续产生 → 先修代码，再清存量，否则清了还会再来
--     · 历史快照过期 → 重算快照（如 JSON 冗余字段）
--
-- 已知未清项（2026-11-11 首次巡检基线，跑出来不是 0 属预期）：
--   ~~A12 realname 两列矛盾 = 1 条~~ **2026-11-13 已清（DRIFT-015 修复）**：
--       代码改为「实名状态由 realname_auth 派生」，A12 从此是「缓存列有没有跟上真相源」
--       的告警，正常应为 0。存量 3 行（user 3 / 9416 / 9418）由
--       `V800__fix_realname_status_derive.sql` 按 auth 重算收敛。
--       当时的顾虑「user 3 改成 rejected 会断管理端验收」实测不成立：
--       `deploy/verify-admin-api.mjs` 只把 walletUser=3 用在管理端钱包与手工充值，
--       不经 C 端实名门禁；脚本里涉及实名状态的断言只有 A-03 的四值枚举检查。
--   A20 案例引用快照自相矛盾 = 1 条（case_library.law_refs 写「第七百三十三条」，
--       但其 lawItemId=23 经 V710 已正名为「第七百二十二条」）
--       不在此处清，原因是根因是**法条库缺真正的 733 正文**（V710 明确不补录、等人工核对原文），
--       属段八。改快照只会把错误藏起来（DRIFT-018 的静默兜底还在）。
-- ============================================================================

SELECT '=== 订单状态与凭据 ===' AS section;

SELECT 'A01 completed/refunded 但无 paid_at' AS chk, COUNT(*) AS n
FROM orders
WHERE status IN ('completed', 'refunded', 'refunding') AND paid_at IS NULL;

SELECT 'A02 paid 及以后状态但无 payment_record' AS chk, COUNT(*) AS n
FROM orders o
WHERE o.status IN ('paid', 'accepted', 'in_service', 'completed', 'refunded', 'refunding')
  AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id);

SELECT 'A03 completed 但状态日志 < 2 条' AS chk, COUNT(*) AS n
FROM orders o
WHERE o.status = 'completed'
  AND (SELECT COUNT(*) FROM order_status_log s WHERE s.order_id = o.id) < 2;

SELECT 'A04 订单的 user_id 指向不存在的用户' AS chk, COUNT(*) AS n
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = o.user_id);

SELECT 'A05 自下单（下单人就是该律师本人）' AS chk, COUNT(*) AS n
FROM orders o
JOIN lawyer l ON o.lawyer_id = l.id
WHERE o.user_id = l.user_id;

SELECT 'A06 order_status_log 孤儿（订单已不存在）' AS chk, COUNT(*) AS n
FROM order_status_log s
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = s.order_id);

SELECT 'A07 日志 operator 指向不存在的用户' AS chk, COUNT(*) AS n
FROM order_status_log s
WHERE s.operator_type = 'user' AND s.operator_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = s.operator_id);

SELECT '=== 账务恒等式（与 verify-balance 同口径）===' AS section;

SELECT 'A08 用户余额 ≠ 最后一条流水 balance_after' AS chk, COUNT(*) AS n
FROM user_balance ub
WHERE ub.available_cents + ub.frozen_cents <> COALESCE(
  (SELECT b.balance_after FROM balance_flow b
   WHERE b.owner_type = 'user' AND b.owner_id = ub.user_id
   ORDER BY b.id DESC LIMIT 1), 0);

SELECT 'A09 用户余额 ≠ SUM(delta_cents)' AS chk, COUNT(*) AS n
FROM user_balance ub
WHERE ub.available_cents + ub.frozen_cents <> COALESCE(
  (SELECT SUM(b.delta_cents) FROM balance_flow b
   WHERE b.owner_type = 'user' AND b.owner_id = ub.user_id), 0);

-- 两张余额表的可用余额列已统一为 `available_cents`（V712，2026-11-13，DRIFT-043 已关闭）。
-- 此前 user_balance 叫 `balance_cents`、lawyer_balance 叫 `available_cents`，
-- 按其中一个写 SQL 在另一张表上必报 Unknown column。
SELECT 'A10 律师余额 ≠ SUM(delta_cents)' AS chk, COUNT(*) AS n
FROM lawyer_balance lb
WHERE lb.available_cents + lb.frozen_cents <> COALESCE(
  (SELECT SUM(b.delta_cents) FROM balance_flow b
   WHERE b.owner_type = 'lawyer' AND b.owner_id = lb.lawyer_id), 0);

SELECT 'A11 流水 owner 指向不存在的账户' AS chk, COUNT(*) AS n
FROM balance_flow b
WHERE (b.owner_type = 'user' AND NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = b.owner_id))
   OR (b.owner_type = 'lawyer' AND NOT EXISTS (SELECT 1 FROM lawyer l WHERE l.id = b.owner_id));

SELECT '=== 身份与关系 ===' AS section;

-- A12（DRIFT-015 修复后）：`app_user.realname_status` 是**冗余缓存列**，真相源是 `realname_auth`。
-- 本项查「缓存有没有跟上真相源」，口径与 V800 迁移、`RealnameStatusResolver#resolveFromAuth`
-- 完全一致（auth 三值之一 → 原值；无记录/脏值 → none）。正常应为 0。
SELECT 'A12 实名缓存列与真相源不一致（realname_status 未跟上 realname_auth）' AS chk, COUNT(*) AS n
FROM app_user u
LEFT JOIN realname_auth r ON r.user_id = u.id
WHERE NOT (u.realname_status <=> CASE
      WHEN r.status IN ('pending', 'approved', 'rejected') THEN r.status
      ELSE 'none'
    END);

SELECT 'A13 实名状态值不在代码值域内' AS chk, COUNT(*) AS n
FROM app_user
WHERE realname_status NOT IN ('none', 'pending', 'approved', 'rejected');

SELECT 'A14 收藏/关注的 target 已不存在（article）' AS chk, COUNT(*) AS n
FROM social_action s
WHERE s.target_type = 'article'
  AND NOT EXISTS (SELECT 1 FROM article a WHERE a.id = s.target_id);

SELECT 'A15 social_action 孤儿（用户已不存在）' AS chk, COUNT(*) AS n
FROM social_action s
WHERE NOT EXISTS (SELECT 1 FROM app_user a WHERE a.id = s.user_id);

SELECT '=== 文本编码 ===' AS section;

-- 判据：正常 UTF-8 中文的字节不会出现 C3A5 / C3A6 / C3A7（即字符 å æ ç）。
-- 出现即说明该列被「当 latin1 解码过一次」再存回 UTF-8（mojibake）。
SELECT 'A16 文书模板字段定义含 mojibake' AS chk, COUNT(*) AS n
FROM doc_template
WHERE HEX(field_schema) LIKE '%C3A5%' OR HEX(field_schema) LIKE '%C3A6%' OR HEX(field_schema) LIKE '%C3A7%';

SELECT 'A17 法条正文含 mojibake' AS chk, COUNT(*) AS n
FROM law_item
WHERE HEX(content) LIKE '%C3A5%' OR HEX(content) LIKE '%C3A6%' OR HEX(content) LIKE '%C3A7%';

SELECT 'A18 案例库正文含 mojibake' AS chk, COUNT(*) AS n
FROM case_library
WHERE HEX(summary) LIKE '%C3A5%' OR HEX(summary) LIKE '%C3A6%'
   OR HEX(judgment) LIKE '%C3A5%' OR HEX(judgment) LIKE '%C3A6%';

SELECT '=== 内容一致性 ===' AS section;

SELECT 'A19 案例引用的 lawItemId 不存在' AS chk, COUNT(*) AS n
FROM case_library c
WHERE JSON_VALID(c.law_refs)
  AND JSON_EXTRACT(c.law_refs, '$[0].lawItemId') IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM law_item l
    WHERE l.id = JSON_UNQUOTE(JSON_EXTRACT(c.law_refs, '$[0].lawItemId'))
  );

SELECT 'A20 案例引用快照内部自相矛盾（articleNo 与 lawItemId 指向的不是同一条）' AS chk, COUNT(*) AS n
FROM case_library c
JOIN law_item l ON l.id = JSON_UNQUOTE(JSON_EXTRACT(c.law_refs, '$[0].lawItemId'))
WHERE JSON_VALID(c.law_refs)
  AND JSON_UNQUOTE(JSON_EXTRACT(c.law_refs, '$[0].articleNo')) <> l.article_no;

SELECT '=== 造数残留（回归清单 R-19）===' AS section;

-- R-19：测试数据禁止进入真实用户可见的列表。
-- 判据只看**对外展示字段**（昵称 / 律师姓名 / 案例标题），因为这些会被 App 列表直接返回：
-- 「找律师」列表里出现「验收律师甲」、评价区出现「测试用户」，用户看到的就是这个。
-- 不看订单号 / 流水号这类内部标识——它们不进列表展示，且有独立的唯一键约束。
--
-- 为什么允许值按台账而不是等于 0：开发库与 CI 库的 seed 里本来就有测试账号
-- （`database/seed/test.sql` 的 13800000001 那批），它们的昵称合法地含「测试」二字。
-- 台账 `known-issues.json` 的 closeWhen 是「上线前清库 + 生产库复跑应为 0」。
SELECT 'A21 造数痕迹出现在对外可见字段' AS chk, COUNT(*) AS n
FROM (
  SELECT id FROM app_user WHERE nickname REGEXP '测试|验收|压测|端到端|示例数据|demo'
  UNION ALL
  SELECT id FROM lawyer WHERE real_name REGEXP '测试|验收|压测|端到端|示例数据|demo'
  UNION ALL
  SELECT id FROM case_library WHERE title REGEXP '测试|验收|压测|端到端|示例数据|demo'
) t;
