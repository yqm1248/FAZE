-- ============================================================================
-- 迁移：V712__fix_balance_column_name.sql
-- 所属段：跨段（段 1 建的两张余额表）——列名统一，不是新功能
-- 做什么：把 user_balance 的可用余额列 `balance_cents` 改名为 `available_cents`，
--         与 lawyer_balance.available_cents 对齐。DRIFT-043 的落地。
--
-- 问题（实测，不是推测）：
--   `user_balance` 用 `balance_cents`，`lawyer_balance` 用 `available_cents`，
--   两者表示同一个概念（可用余额），都配 `frozen_cents`。
--   首次写 scripts/monitor/data-integrity.sql 时照 `balance_cents` 写，
--   在 lawyer_balance 上直接报 Unknown column；反过来也一样。
--
-- 为什么统一成 `available_cents` 而不是 `balance_cents`（三条依据，先看再说）：
--   1. **接口层的事实标准是 available**：C 端唯一余额接口 GET /app/wallet/balance
--      的出参 BalanceVO 就是 { availableCents, frozenCents, totalCents }，
--      App 端 features/trade 与 workbench 共 8 处消费 availableCents。
--      数据库跟着接口层走，才不会出现「库里叫 balance、接口叫 available」这一层新分叉。
--   2. **`balance` 在本仓接口层实际指「总余额」**（实测四个 VO 全部如此）：
--      ProfileVO.balanceCents ← getTotalCentsFromFlows()；RechargeResultVO.balanceCents
--      ← flow.balanceAfter；AdminWalletVO.balanceCents ← sumDeltaByOwner()；
--      AdminRechargeResultVO.balanceCents ← 同样的流水净额。**它们都是 SUM(delta_cents)，
--      含冻结。** 而 user_balance 那一列存的是**可用余额**（= 总 − 冻结）。
--      若把它定为 `balance_cents`，库里那一列就叫 balance 却装 available 的值，
--      而接口里同一个词指 total——同一个名字在一张表和一个 VO 里指相反的东西。
--      available / frozen 才是那对真正的反义状态。
--   3. **避开同名不同义**：`lawyer.balance_cents` 是 V4 留下的**历史缓存列**，
--      注释明写「段 5 起禁止读写本字段」。若 lawyer_balance 也叫 balance_cents，
--      库里就会出现两个 balance_cents（一个废弃、一个权威），比现在更乱。
--   《数据表规范》§3 的金额名单原来只列了 `balance_cents`，本次同步补上
--   `available_cents`（后缀规则不变：金额一律 `_cents`）。
--
-- 为什么必须 DROP CHECK + ADD CHECK（2026-11-13 本地 MySQL 8.0.44 实测）：
--   直接 `ALTER TABLE ... CHANGE COLUMN balance_cents available_cents ...` 报
--     ERROR 3959 (HY000): Check constraint 'ck_user_balance_non_negative' uses
--     column 'balance_cents', hence column cannot be dropped or renamed.
--   CHECK 引用的列**不允许**被重命名，必须先摘约束、改名、再重建约束。
--   别图省事写成单条 CHANGE COLUMN——在 8.0.44 上必失败。
--
-- 不改什么：
--   1. **不改 V5__wallet.sql**：Flyway 已校验过的迁移文件不许改（checksum 变了会
--      让已部署环境校验失败）。V5 里的 `balance_cents` 是历史事实，新环境会在
--      本迁移里被改名，终点状态一致。
--   2. **不删 `lawyer.balance_cents`**：那是废弃列没错，但删列是不可逆操作，
--      且不在「统一列名」的范围内。它已在 V4 注释里标了禁止读写，DRIFT-043 的
--      记录里登记为待清理，段八统一处置。
--   3. **不改任何接口出参**：BalanceVO.availableCents / ProfileVO.balanceCents 等
--      HTTP 契约一律不动。改契约是破坏性变更，与列名统一不该混在一次提交里。
--
-- 影响面（代码侧，本次一并改）：
--   - UserBalance.java（实体字段 balanceCents → availableCents）
--   - UserBalanceMapper.xml（手写 SELECT 列表）
--   - BalanceService / BalanceService 注释、ReconciliationSummaryVO 等引用注释
--   - scripts/monitor/data-integrity.sql（A08 / A09）
--   - deploy/verify-balance.ps1、deploy/purge-test-data.ps1、scripts/repair-lawyer-income.sql
--   - 约 30 处文档里的 `user_balance.balance_cents`
-- ============================================================================

ALTER TABLE user_balance DROP CHECK ck_user_balance_non_negative;

ALTER TABLE user_balance
  CHANGE COLUMN balance_cents available_cents BIGINT NOT NULL DEFAULT 0
  COMMENT '可用余额（分）；只能由 balance_flow 推导';

ALTER TABLE user_balance
  ADD CONSTRAINT ck_user_balance_non_negative CHECK (available_cents >= 0);
