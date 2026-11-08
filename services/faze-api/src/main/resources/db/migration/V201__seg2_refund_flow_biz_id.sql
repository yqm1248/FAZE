-- ============================================================================
-- V201 · 段 2 G4：修正「退款流水 biz_id 语义」历史数据
--
-- 背景：
--   G2.4 的自动退款（超时/律师拒单）把 `balance_flow.biz_id` 写成**订单 id**；
--   G4 把退款入账的幂等键改成**退款单 id**（`refund_record.id`）——原因是一张订单
--   可以多次退款（先部分退、之后再次申请），用订单 id 当幂等键会把第二次退款吃掉。
--
-- 问题：
--   `uk_flow_biz (owner_type, owner_id, biz_type, biz_id)` 是全局唯一。
--   旧数据 biz_id 是订单 id（例如订单 6 → biz_id=6），与新代码的退款单 id（1、2、3…）
--   **数值区间重叠**：新退款单 id=6 入账时命中旧流水、被当成「已退过」直接返回旧 flowId，
--   结果是「审批通过、退款单显示已到账，但用户余额没变」——2026-11-06 实测踩到（订单 10 部分退 2000）。
--
-- 修法：
--   把旧的 refund 流水 `biz_id` 从「订单 id」改成「该订单最早一张已退款单的 id」。
--   金额、余额快照、payment_record.transaction_id（BAL-<flowId>）全部不变。
--
-- 注意（2026-11-06 实测踩坑）：
--   **不能用 CREATE TEMPORARY TABLE**——`faze_migrate` 账号是逐表授权，没有
--   CREATE TEMPORARY TABLES 权限，直接报 Error Code 1044（Access denied）。
--   也不能在 UPDATE 的 WHERE/SET 里子查询 `balance_flow` 本身（MySQL 1093）。
--   因此只 JOIN `refund_record`，用「该订单最早一张已退款单」锁定目标行。
--
-- 若目标 biz_id 已被别的退款流水占用，本语句会因 `uk_flow_biz` 冲突而失败——
-- 这是有意的：那是真实的数据不一致，必须人工看，不许静默跳过。
-- ============================================================================

UPDATE balance_flow f
JOIN refund_record r
  ON r.order_id = f.biz_id
 AND r.status = 'refunded'
SET f.biz_id = r.id
WHERE f.biz_type = 'refund'
  AND f.owner_type = 'user'
  AND r.id = (SELECT MIN(r2.id)
              FROM refund_record r2
              WHERE r2.order_id = r.order_id
                AND r2.status = 'refunded')
  AND r.id <> f.biz_id;
