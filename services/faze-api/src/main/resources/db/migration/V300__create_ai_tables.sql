-- ============================================================================
-- 迁移：V300__create_ai_tables.sql
-- 所属段：第 3 段（AI 法律顾问）
-- 做什么：建 ai_conversation（7 字段）/ ai_message（16 字段）/ i18n_glossary（10 字段）
--         三张表结构，**不含任何数据**。
-- 依赖：V1 的 app_user（ai_conversation.user_id / ai_message.user_id 只是逻辑引用，
--       本库不建物理外键——见《数据表规范》附录 C 第 10 条）。
-- 可回滚：本地库可 flyway clean 重建；已进 main 后不可改，只能追加 V300_1__fix_*.sql。
-- 编写：2026-11-07
-- 依据：
--   《第 3 段工作流》§三 A1（字段逐字按该节，不许自己加）；
--   §零.3 第 7 项（表名 ai_conversation，不是 ai_session）、
--   §零.3 第 9 项（去掉复核/校验三字段，但保留输出侧存在性校验机制）、
--   §零.3 第 12 项（ai_message 由 B 类改 C 类，必须有 updated_at）；
--   《数据表规范》§四（四类表公共字段）、§六（COLLATE）、§七（注释）。
--
-- 相对技术路线 §5.10 DDL 的三处修正：
--   ① ai_session → ai_conversation（负责人 2026-11-01 定）；索引改 (user_id, deleted_at)
--   ② ai_message：12 → 16 字段，补 content_id / is_exported / export_urls / updated_at，
--      布尔 safety_passed → is_safety_passed（《数据表规范》附录 C 第 3 条）
--   ③ i18n_glossary：9 → 10 字段，补 is_enabled（D 类表硬要求，停用而非删除）
--
-- 版本号：按《数据表规范》§8.1，第 3 段用 V300~V399。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. AI 会话（A 业务实体表）
--    一个用户的一个提问会话。删除是软删（deleted_at），物理行永不删——
--    A-28 后台要能查到「用户已删除」的会话（合规追溯，见 E3 留存策略）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_conversation (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id    BIGINT       NOT NULL COMMENT '归属用户，逻辑引用 app_user.id',
  title      VARCHAR(200)          DEFAULT NULL COMMENT '会话标题：首问前 30 字（超出截断加省略号），由 B2 提交接口生成',
  lang       VARCHAR(20)  NOT NULL DEFAULT 'zh-Hans' COMMENT '会话语言；段 3 固定 zh-Hans（§零.3 第 4 项，不做多语言回答）',
  created_at DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；NULL 表示未删。用户删除只写此字段',
  PRIMARY KEY (id),
  KEY idx_ai_conversation_user (user_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='AI 会话；用户删除为软删，A-28 仍可查到已软删会话';

-- ---------------------------------------------------------------------------
-- 2. AI 消息（C 状态机表——不是 B 类）
--    为什么是 C 类：assistant 消息生成过程中要分批 UPDATE content（Redis 暂存 +
--    每 20 个 delta 刷库），结束时再写全量 + model + tokens + refs；
--    导出还要回写 is_exported / export_urls。B 类「只插入不修改」承载不了。
--    role 只有 user / assistant：系统 Prompt 不落库（D1 第 6 条）。
--    content 是 L4 级敏感字段（《数据表规范》§9.2），禁止进日志。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_message (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  conversation_id BIGINT       NOT NULL COMMENT '逻辑引用 ai_conversation.id',
  user_id         BIGINT       NOT NULL COMMENT '冗余存一份，A-28 按用户检索少一次 join',
  role            VARCHAR(10)  NOT NULL COMMENT 'user / assistant；**不存 system**（系统 Prompt 不落库）',
  content         MEDIUMTEXT            DEFAULT NULL COMMENT '消息正文；L4 级敏感字段，禁止进日志、错误上报、APM',
  model           VARCHAR(50)           DEFAULT NULL COMMENT '模型 id（含上下文后缀），如 doubao-seed-1.6-32k',
  prompt_tokens   INT                   DEFAULT NULL COMMENT '上游返回的输入 token 数',
  complete_tokens INT                   DEFAULT NULL COMMENT '上游返回的输出 token 数',
  refs            JSON                  DEFAULT NULL COMMENT '法条引用快照（D4 schema）；user 消息为 NULL，assistant 无引用存 []',
  lang            VARCHAR(20)  NOT NULL DEFAULT 'zh-Hans' COMMENT '语言；段 3 固定 zh-Hans',
  is_safety_passed TINYINT     NOT NULL DEFAULT 1 COMMENT '安全校验是否通过；段 3 不接机审恒为 1，不驱动业务',
  content_id      VARCHAR(64)           DEFAULT NULL COMMENT '内容编号（隐式标识用）：AI-[0-9a-f]{32}；user 消息为 NULL',
  is_exported     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否被 A-28 导出过',
  export_urls     JSON                  DEFAULT NULL COMMENT '导出产物列表 [{type,url,at}]',
  created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at      DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）；C 类必须有',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ai_message_content_id (content_id),
  KEY idx_ai_message_conversation (conversation_id, id),
  KEY idx_ai_message_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='AI 消息（C 状态机）；content 为 L4 级敏感字段；一段流式回答 = 一行';

-- ---------------------------------------------------------------------------
-- 3. 法律术语对照表（D 基础数据表）
--    段 3 只灌 zh-Hans 源术语，target_text 先填空串（**不许机翻**，译文由段 8
--    母语法律译者确认）。读法：GlossaryService 全量加载 is_enabled=1 的 50 条，
--    用最长匹配从问题文本里取命中术语注入 Prompt（A4）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS i18n_glossary (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  term_key    VARCHAR(100) NOT NULL COMMENT '术语键（稳定标识，跨语言一致），如 jin_zhan',
  source_text VARCHAR(200) NOT NULL COMMENT '中文源术语，如 进展',
  lang        VARCHAR(20)  NOT NULL COMMENT '语言：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN；段 3 只有 zh-Hans',
  target_text VARCHAR(200) NOT NULL DEFAULT '' COMMENT '目标语言译文；段 3 留空串（不机翻，段 8 母语译者补）',
  note        VARCHAR(500)          DEFAULT NULL COMMENT '备注：歧义说明、用法限定',
  status      VARCHAR(20)  NOT NULL DEFAULT 'confirmed' COMMENT 'confirmed / pending；段 3 只灌 confirmed',
  is_enabled  TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用；停用而非删除（D 类表硬要求）',
  created_at  DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at  DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_i18n_glossary_term_lang (term_key, lang),
  KEY idx_i18n_glossary_lang (lang, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='法律术语对照表；段 3 只灌中文源术语，译文留段 8 母语译者';
