-- ============================================================================
-- 脚本：scripts/repair-lawyer-income.sql
-- 所属段：第 5 段 E2（段 2 期间的已完成订单补入账）
-- 做什么：**只读**核对。列出「已完成但还没有 income 流水」的订单，供人工确认后再调
--         `POST /api/v1/admin/settlements/backfill` 执行回补。
-- 编写：2026-11-08
-- 依据：《第 5 段工作流》§三 E2 第 5 条 —— 不许做成自动执行；先出只读核对，人工确认。
--
-- 为什么必须有这个脚本：回补会**真实增加律师总余额**（不可逆）。E2 要的证据是
--   「核对 SQL 跑出的待补订单数 == 回补后新增的 income 流水数」，
--   两条 SQL 的计数口径必须一致（下面 §1 与 §2 是同一套 WHERE）。
--
-- 用法（Windows PowerShell）：
--   mysql --host=127.0.0.1 -uroot -p123456 faze < scripts\repair-lawyer-income.sql
-- 执行回补前先跑 §0、§1 记下数字，回补后再跑一遍 §1 应为空。
-- ============================================================================

-- ── §0 恒等式基线（回补前后各跑一次，两个数必须相等）────────────────────────
-- ① 缓存 == 最后一条流水的 balance_after
-- 注意：MySQL 8 没有「每组取最后一条」的窗口捷径可走时，用相关子查询也够（律师数量小）。
SELECT b.lawyer_id,
       b.available_cents + b.frozen_cents AS cache_total,
       (SELECT f.balance_after
          FROM balance_flow f
         WHERE f.owner_type = 'lawyer'
           AND f.owner_id = b.lawyer_id
         ORDER BY f.id DESC
         LIMIT 1)                          AS last_flow_balance_after
FROM lawyer_balance b
ORDER BY b.lawyer_id;

-- ② 缓存 == SUM(delta_cents)
SELECT b.lawyer_id,
       b.available_cents + b.frozen_cents AS cache_total,
       COALESCE((SELECT SUM(f.delta_cents)
                   FROM balance_flow f
                  WHERE f.owner_type = 'lawyer'
                    AND f.owner_id = b.lawyer_id), 0) AS flow_sum
FROM lawyer_balance b
ORDER BY b.lawyer_id;

-- ── §1 待回补订单明细（人工逐条看）──────────────────────────────────────────
SELECT o.id                AS order_id,
       o.order_no,
       o.lawyer_id,
       o.finished_at,
       o.amount_cents,
       o.platform_fee_cents,
       o.lawyer_income_cents
FROM orders o
WHERE o.status = 'completed'
  AND o.deleted_at IS NULL
  AND o.lawyer_id IS NOT NULL
  AND COALESCE(o.lawyer_income_cents, 0) > 0
  AND NOT EXISTS (SELECT 1
                    FROM balance_flow f
                   WHERE f.owner_type = 'lawyer'
                     AND f.biz_type = 'income'
                     AND f.biz_id = o.id)
ORDER BY o.id;

-- ── §2 待补合计（回补接口返回的 scanned / pendingCents 必须与这一行相等）────
SELECT COUNT(*)                                 AS pending_orders,
       COALESCE(SUM(o.amount_cents), 0)         AS amount_cents_sum,
       COALESCE(SUM(o.platform_fee_cents), 0)   AS fee_cents_sum,
       COALESCE(SUM(o.lawyer_income_cents), 0)  AS income_cents_sum
FROM orders o
WHERE o.status = 'completed'
  AND o.deleted_at IS NULL
  AND o.lawyer_id IS NOT NULL
  AND COALESCE(o.lawyer_income_cents, 0) > 0
  AND NOT EXISTS (SELECT 1
                    FROM balance_flow f
                   WHERE f.owner_type = 'lawyer'
                     AND f.biz_type = 'income'
                     AND f.biz_id = o.id);

-- ── §3 必须人工处理的「0 收入」历史订单（R2）────────────────────────────────
-- 这类单**不许**用现在的费率重算（费率改过就会与订单快照不一致），
-- 回补接口会跳过它们；把清单抄给负责人，由人决定是补 0 还是按当时费率补。
SELECT o.id AS order_id, o.order_no, o.lawyer_id, o.finished_at,
       o.amount_cents, o.platform_fee_cents, o.lawyer_income_cents
FROM orders o
WHERE o.status = 'completed'
  AND o.deleted_at IS NULL
  AND o.lawyer_id IS NOT NULL
  AND COALESCE(o.lawyer_income_cents, 0) <= 0
ORDER BY o.id;
