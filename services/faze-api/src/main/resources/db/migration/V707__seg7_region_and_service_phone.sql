-- ============================================================================
-- 迁移：V707__seg7_region_and_service_phone.sql
-- 所属段：第 7 段（运营与治理收口）——尾巴补漏
-- 做什么：① 给 `app_user` 补 `region` 列（含索引）；
--         ② 把 `sys_config` 的客服电话从占位号换成负责人确认的正式号。
--
-- 为什么补 region（负责人 2026-11-09 拍板：「可做，写好备注即可」）：
--   A-02 看板的「用户地区分布」与 D5 公告的「按地区发送」都需要这一列，但段 1 没建它
--   （见 `DashboardRegionVO` 原注释、本段工作流 §十一 挂账）。补列只解决「查询不报错」，
--   **不解决「有数据」**——本段及之前**没有任何采集入口**，注册流程不收集地区，
--   所以建列之后该列**恒为 NULL**：地区分布当下只会返回一项「其他」，公告按地区发送
--   目标人数为 0。采集入口留给段 8（注册页 / 资料页加地区选择）。
--
-- 为什么索引是 `idx_app_user_region` 单列（《数据表规范》§5.1 / §5.2）：
--   按 §5.2「每个索引都要能说出它服务哪个查询」，它服务两个**真实存在**的查询：
--     · `AccountQueryServiceImpl.listUserIdsByRegion` —— 公告按地区发送
--       （WHERE region IN (...) AND status='normal' AND deleted_at IS NULL）
--     · `DashboardMapper.selectRegions` —— 看板按地区分组计数
--   `region` 既不是外键也不是状态字段，本来不在 §5.1 的必建清单里；
--   是因为有了「真实按 region 过滤的查询」才建，不是为了凑索引数。
--   加完之后 `app_user` 共 4 个索引（PRIMARY / uk_app_user_phone /
--   idx_app_user_role_status / idx_app_user_region），未超 §5.2 的「单表 ≤ 6 个」上限。
--
-- 为什么客服电话要换（负责人 2026-11-09 确认）：
--   设计稿与 V703 里的 `0999-XXXXXXX` 是**占位**，正式号码 = `002-00854967`。
--   占位号若被 APP 55/56（客服 / 常见问题）页原样渲染给用户，用户会拨到空号。
--   号码本身属公开信息（本来就要给用户看），不受密钥管理约束。
--
-- 幂等（本迁移设计为可重复执行）：
--   Flyway 本身保证迁移只跑一次，但**手工重放的概率是真实存在的**——
--   `deploy/migrate.ps1` 会重跑迁移链路，开发库重建也会再跑一遍。
--   而 `ALTER TABLE ... ADD COLUMN` 重复执行会直接报 1060（列已存在），
--   让整个迁移失败，比「语句没生效」更难排查。
--   所以两条 DDL 都用 `information_schema` 做存在性判断：
--     已存在 → 执行 `DO 0`（空操作，且 `DO` 在 MySQL 的可 PREPARE 语句白名单里）
--     不存在 → 执行真正的 DDL
--   MySQL 8.0 **不支持** `ADD COLUMN IF NOT EXISTS`，所以只能走 PREPARE / EXECUTE。
--   末尾的 UPDATE 天然幂等（重复执行只是再写同样的值），不需要守卫。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① 加列：app_user.region
--    位置放在 guardian_consent_at 之后，与 AppUser 实体的字段顺序保持一致
--    （实体里 region 紧跟 guardianConsentAt，便于人工核对）。
-- ---------------------------------------------------------------------------
SET @ddl_add_region := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE app_user ADD COLUMN region VARCHAR(64) NULL '
              'COMMENT ''段 7 新增；当前无采集点（注册流程未采集），恒为 NULL；'
              '段 8 在注册/资料页补采集入口'' AFTER guardian_consent_at',
              'DO 0')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'app_user'
      AND COLUMN_NAME = 'region'
);
PREPARE stmt_add_region FROM @ddl_add_region;
EXECUTE stmt_add_region;
DEALLOCATE PREPARE stmt_add_region;

-- ---------------------------------------------------------------------------
-- ② 加索引：idx_app_user_region
--    单独判断（而不是和 ① 合成一条 ALTER）：列存在但索引不存在是可能的状态
--    ——比如有人手工补了列却没补索引。合并成一条就会漏掉这种情况。
-- ---------------------------------------------------------------------------
SET @ddl_add_region_idx := (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE app_user ADD KEY idx_app_user_region (region)',
              'DO 0')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'app_user'
      AND INDEX_NAME = 'idx_app_user_region'
);
PREPARE stmt_add_region_idx FROM @ddl_add_region_idx;
EXECUTE stmt_add_region_idx;
DEALLOCATE PREPARE stmt_add_region_idx;

-- ---------------------------------------------------------------------------
-- ③ 客服电话：占位号 → 正式号
--    `remark` 同时去掉「占位」字样并记录确认来源，避免后人在 §十一 与库之间反复确认。
--    `value_type` / `category` / `editable` 不动（V703 已定，仍为 string / service / 1）。
-- ---------------------------------------------------------------------------
UPDATE sys_config
   SET config_value = '002-00854967',
       remark       = '客服电话（负责人 2026-11-09 确认正式号）',
       updated_at   = UTC_TIMESTAMP()
 WHERE config_key = 'service.phone';
