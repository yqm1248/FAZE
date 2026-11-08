-- ============================================================================
-- 迁移：V708__seg7_app_version_and_alert_url.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：补 5 个 sys_config 键，供 E5（版本检查与强更）与 G3（告警轮询）使用：
--         ① app.version.latest      最新版本号（语义化 x.y.z）
--         ② app.version.min         最低支持版本号（低于它强制更新）
--         ③ app.version.download_url 下载地址
--         ④ app.version.changelog   更新说明
--         ⑤ alert.prometheus_url    Prometheus 基地址（AlertDispatcher 轮询 /api/v1/alerts）
--
-- 依据：《第 7 段工作流》§三 E5 第 2 条（4 个版本键）与 G3 第 2 条（告警轮询源）。
--
-- 幂等：用 ON DUPLICATE KEY UPDATE，**不更新 config_value**：
--   版本号与下载地址是运营会改的值（E5 算做完第 1 条就是「改 app.version.latest 看 APP 是否提示」），
--   重跑迁移把运营改过的值冲回默认值，等于迁移脚本偷偷回滚了运营动作。
--   只更新 value_type / category / remark / editable（结构信息）。
--
-- ⚠️ 两条必须让后人知道的：
--   1. **`app.version.min` 初始值 = 1.0.0，与 latest 相同**：这样默认「不提示更新、不强更」。
--      若上成 1.0.1 会让所有 1.0.0 客户端一启动就强更，上线首日必踩。
--   2. **`alert.prometheus_url` 默认指向本机 9090**：G2 的 Prometheus 由 T4 在 127.0.0.1:9090
--      起；生产部署时改这个键指向独立监控主机，不用改代码。
--   3. 本迁移**只加 sys_config 行，不建表、不改列**，因此不需要补表级授权
--      （deploy/sync-db-grants.ps1 只对新表有意义）。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, category, remark, editable, created_at, updated_at) VALUES
('app.version.latest',       '1.0.0',                'string', 'app',   'APP 最新版本号（语义化 x.y.z；改这里不用发版）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('app.version.min',          '1.0.0',                'string', 'app',   'APP 最低支持版本号；currentVersion 低于它强制更新（不可关闭）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('app.version.download_url', '',                     'string', 'app',   'APP 下载地址（应用市场 / 官网安装包）；空串时客户端不给「去更新」按钮', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('app.version.changelog',    '',                     'string', 'app',   '更新说明（给 41b 更新弹窗展示）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()),
('alert.prometheus_url',     'http://127.0.0.1:9090', 'string', 'alert', 'Prometheus 基地址（G3 告警轮询 GET /api/v1/alerts）', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE
  value_type = VALUES(value_type),
  category   = VALUES(category),
  remark     = VALUES(remark),
  editable   = VALUES(editable),
  updated_at = UTC_TIMESTAMP();
