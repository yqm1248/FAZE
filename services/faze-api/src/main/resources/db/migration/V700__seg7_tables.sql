-- ============================================================================
-- 迁移：V700__seg7_tables.sql
-- 所属段：第 7 段（运营与治理收口）
-- 做什么：建本段 9 张在册表——notification / complaint / feedback / faq /
--         user_violation / notify_template / sensitive_word / content_review /
--         service_ticket，其中后 3 张按裁决 6~8 各补字段。
--
-- 依据：
--   《技术路线 v2.3》§5.9 / §5.11 的 DDL 原文（L1826~L2135）；
--   《第 7 段工作流》§三 A1（9 张表 + 4 处补字段，字段数逐个点名）；
--   《数据表规范》§三/§四（表名、字段名、索引名、COLLATE、公共字段）。
--
-- 三个设计取舍（写在这里防后人「优化」）：
--   1. **operation_log / login_log 不在这里**：段 1 已建（V6 / V5 系列），
--      本段只读、只查、只导出。重复建会撞名——工作流 §零.4 第 3 条是红线。
--   2. **share_poster 不建**（裁决 11）：总纲 §5.8 未列、无对应页面、无验收锚点。
--   3. **content_review / sensitive_word 只标注、不改原文**：本表不存原文全文，
--      只存命中词与 hit_snippet（前后各 40 字）。原文永远留在业务表里不动。
--
-- 字段数（与本文件逐个核对，工作流 A1 算做完要求）：
--   notification 12 / complaint 12 / feedback 10 / faq 9 / user_violation 14 /
--   notify_template 9 / sensitive_word 12 / content_review 12 / service_ticket 13
--
-- 时间字段：库内一律 UTC，DATETIME，由应用层写入（不用 DB 默认值 CURRENT_TIMESTAMP）。
-- 字符集：utf8mb4 / utf8mb4_0900_ai_ci（与段 0 的 law_item 等既有表一致）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. notification（A 类：业务实体，软删）12 字段
-- 段 2 的消息中心两页当时按「空态 + 入口占位」交付，本表由本段建（裁决 3）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id    BIGINT       NOT NULL COMMENT '接收用户 app_user.id',
  type       VARCHAR(30)  NOT NULL COMMENT '通知类型：system / order / im / case / promotion',
  title      VARCHAR(100) NOT NULL COMMENT '标题（已渲染，不再含模板变量）',
  content    VARCHAR(500)          DEFAULT NULL COMMENT '正文（已渲染；VARCHAR 500 是上限，超长由发送方截断）',
  biz_type   VARCHAR(30)           DEFAULT NULL COMMENT '业务类型：announcement / ticket / order / review，用于列表筛选与打开率回写',
  biz_id     BIGINT                DEFAULT NULL COMMENT '业务主键：公告 id / 工单 id / 订单 id；biz_type + biz_id 联合定位',
  is_read    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0 未读 / 1 已读',
  read_at    DATETIME              DEFAULT NULL COMMENT '首次已读时间（UTC）；公告打开率只在 0→1 时 +1',
  created_at DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；NULL 为未删',
  PRIMARY KEY (id),
  KEY idx_notification_user (user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='站内通知；每人一行，公告批次统计在 announcement 上累计';

-- ---------------------------------------------------------------------------
-- 2. complaint（A 类：业务实体，软删）12 字段
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS complaint (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT       NOT NULL COMMENT '投诉人 app_user.id',
  target_type VARCHAR(20)           DEFAULT NULL COMMENT '投诉对象类型：lawyer / order / platform',
  target_id   BIGINT                DEFAULT NULL COMMENT '投诉对象主键；target_type=lawyer 时必须是已完成订单 id（工作流 C2 校验）',
  reason      VARCHAR(500) NOT NULL COMMENT '投诉原因与诉求（≤500 字，原文不改）',
  images      JSON                  DEFAULT NULL COMMENT '截图 oss_key 数组（≤3 张）',
  status      VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '处理状态：pending / processing / resolved / rejected',
  handler_id  BIGINT                DEFAULT NULL COMMENT '处理人（管理员 app_user.id）',
  result      VARCHAR(500)          DEFAULT NULL COMMENT '处理结果说明（给用户看）',
  created_at  DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at  DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at  DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；NULL 为未删',
  PRIMARY KEY (id),
  KEY idx_complaint_user (user_id, created_at),
  KEY idx_complaint_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='用户投诉；提交时同事务生成 service_ticket（裁决 23）';

-- ---------------------------------------------------------------------------
-- 3. feedback（A 类：业务实体，软删）10 字段
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS feedback (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id    BIGINT       NOT NULL COMMENT '反馈人 app_user.id',
  type       VARCHAR(30)           DEFAULT NULL COMMENT '反馈类型：feature / bug / experience / other',
  content    VARCHAR(500) NOT NULL COMMENT '具体情况（稿面「选填」，但表上 NOT NULL——空时由服务端填类型中文标签，保证 title 有值）',
  images     JSON                  DEFAULT NULL COMMENT '截图 oss_key 数组（≤3 张，选填）',
  contact    VARCHAR(50)           DEFAULT NULL COMMENT '联系方式（手机号，选填；落库前不脱敏，出参脱敏）',
  status     VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT '处理状态：pending / processing / resolved / rejected',
  created_at DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  deleted_at DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；NULL 为未删',
  PRIMARY KEY (id),
  KEY idx_feedback_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='意见反馈；提交时同事务生成 service_ticket（裁决 23）';

-- ---------------------------------------------------------------------------
-- 4. faq（D 类：基础数据，停用而非删除）9 字段
-- 停用语义用 status（照技术路线原文），不用 is_enabled——本段 A-24/A-25 页面按 status 筛。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS faq (
  id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  category   VARCHAR(30)           DEFAULT NULL COMMENT '分类：account / pay / lawyer / case（账号/支付/律师/立案）',
  question   VARCHAR(200) NOT NULL COMMENT '问题标题',
  answer     TEXT         NOT NULL COMMENT '答案正文（支持 \n 换行，前端按行渲染）',
  lang       VARCHAR(20)           DEFAULT 'zh-Hans' COMMENT '语种：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN',
  sort_order INT                   DEFAULT 0 COMMENT '同分类内排序，升序',
  status     VARCHAR(20)           DEFAULT 'active' COMMENT '状态：active 启用 / disabled 停用',
  created_at DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  KEY idx_faq_category (category, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='常见问题；APP 55/56 两页的数据源，改库即生效';

-- ---------------------------------------------------------------------------
-- 5. user_violation（C 类：状态机，不删）14 字段
-- 只追加处罚记录；撤销是置 revoked_at，不是删除（证据链）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_violation (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id        BIGINT       NOT NULL COMMENT '被处罚主体 app_user.id',
  user_type      VARCHAR(10)  NOT NULL DEFAULT 'user' COMMENT '主体类型：user 普通用户 / lawyer 律师',
  violation_type VARCHAR(30)  NOT NULL COMMENT '违规类型（业务码，与审计动作码分开）',
  level          VARCHAR(20)  NOT NULL COMMENT '处罚级别：warning 警告 / restrict 限流 / suspend 暂停接单 / ban 封禁',
  reason         VARCHAR(500) NOT NULL COMMENT '处罚原因（给用户看的摘要，登录被拒时回显）',
  evidence       JSON                  DEFAULT NULL COMMENT '证据快照：content_review_id / 工单号 / 命中词等',
  start_at       DATETIME     NOT NULL COMMENT '生效时间（UTC）',
  end_at         DATETIME              DEFAULT NULL COMMENT '失效时间（UTC）；NULL = 永久',
  operator_id    BIGINT       NOT NULL COMMENT '操作管理员 app_user.id（审计四要素之「谁」）',
  revoked_at     DATETIME              DEFAULT NULL COMMENT '撤销时间（UTC）；解封时置位',
  revoke_reason  VARCHAR(500)          DEFAULT NULL COMMENT '撤销原因',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at     DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  KEY idx_violation_user (user_id, start_at),
  KEY idx_violation_active (user_type, level, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='违规与处罚记录；只追加，撤销置 revoked_at 不删行';

-- ---------------------------------------------------------------------------
-- 6. notify_template（D 类：配置数据）9 字段
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notify_template (
  id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  template_code VARCHAR(50)   NOT NULL COMMENT '模板编码（业务方按码取模板，如 order_paid / ticket_replied）',
  lang          VARCHAR(20)   NOT NULL COMMENT '语种：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN',
  title         VARCHAR(200)  NOT NULL COMMENT '标题模板，变量形如 {{userName}}（禁位置参数）',
  content       VARCHAR(1000) NOT NULL COMMENT '正文模板，变量形如 {{orderNo}}',
  channel       VARCHAR(50)   NOT NULL DEFAULT 'inapp' COMMENT '渠道：inapp / push / sms',
  status        VARCHAR(20)   NOT NULL DEFAULT 'active' COMMENT '状态：active 启用 / disabled 停用（删除即停用，不物理删）',
  created_at    DATETIME      NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at    DATETIME      NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_notify_tpl (template_code, lang),
  KEY idx_notify_tpl_code (template_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通知模板，按语言分条；删除为软停用，避免已发通知断链';

-- ---------------------------------------------------------------------------
-- 7. sensitive_word（D 类：基础数据）12 字段（11 + last_hit_at 裁决 7）
-- 新增 idx_word_level_lang：A-20 每次命中都要按「级别 + 语言 + 启用」取词。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sensitive_word (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  word        VARCHAR(100) NOT NULL COMMENT '词条原文（子串匹配，不分词）',
  category    VARCHAR(30)  NOT NULL COMMENT '分类：POLITICS / VIOLENCE / PORN / FRAUD / ILLEGAL',
  level       VARCHAR(20)  NOT NULL COMMENT '级别：block 拦截 / review 送审 / warn 仅记录',
  lang        VARCHAR(20)  NOT NULL DEFAULT 'zh-Hans' COMMENT '语种：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN',
  source      VARCHAR(50)           DEFAULT NULL COMMENT '来源：manual 手工 / import 导入 / legal 法律行业禁语',
  enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用（删除为物理删，停用走本字段）',
  hit_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计命中次数（异步批量 +1，不阻塞主流程）',
  last_hit_at DATETIME              DEFAULT NULL COMMENT '最近一次命中时间（UTC）；A-25「最近命中」列',
  created_by  BIGINT                DEFAULT NULL COMMENT '创建管理员 app_user.id',
  created_at  DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at  DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_word_lang (word, lang),
  KEY idx_word_category (category, level, enabled),
  KEY idx_word_level_lang (level, lang, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='本地敏感词库；Trie 引擎启动时全量加载 enabled=1 的词';

-- ---------------------------------------------------------------------------
-- 8. content_review（C 类：状态机）12 字段（10 + hit_snippet + biz_user_id 裁决 8）
-- 新增 idx_review_user：A-20 列表要按「内容作者」筛选。
-- 红线：本表不存原文全文，原文永远留在业务表里不改写。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS content_review (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  biz_type      VARCHAR(30)  NOT NULL COMMENT '业务类型：im_message / review / article_comment / ai_message / complaint / feedback',
  biz_id        BIGINT       NOT NULL COMMENT '业务主键（指向原表行 id）',
  hit_words     JSON                  DEFAULT NULL COMMENT '命中词数组：[{word, level, category, start, end}]',
  hit_snippet   VARCHAR(500)          DEFAULT NULL COMMENT '命中词前后各 40 字的片段（已脱敏，非全文）',
  level         VARCHAR(20)  NOT NULL COMMENT '本次命中的最高级别：block / review / warn',
  engine        VARCHAR(30)           DEFAULT NULL COMMENT '判定引擎：local_trie（本段唯一实现）',
  biz_user_id   BIGINT                DEFAULT NULL COMMENT '被审核内容的作者 app_user.id（A-20 按用户筛选）',
  reviewer_id   BIGINT                DEFAULT NULL COMMENT '处置管理员 app_user.id',
  review_result VARCHAR(20)           DEFAULT 'pending' COMMENT '处置结果：pending / ignored / warned / banned',
  reviewed_at   DATETIME              DEFAULT NULL COMMENT '处置时间（UTC）',
  created_at    DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  PRIMARY KEY (id),
  KEY idx_review_biz (biz_type, biz_id),
  KEY idx_review_pending (review_result, created_at),
  KEY idx_review_user (biz_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='内容审核命中记录；原文不修改，只做标注';

-- ---------------------------------------------------------------------------
-- 9. service_ticket（C 类：状态机）13 字段（9 + ticket_no/title/priority/sla_due_at 裁决 6）
-- 4 态 8 条迁移见 TicketStateMachine；sla_due_at 落库（= created_at + 24h）以便索引与超时扫描。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_ticket (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  ticket_no       VARCHAR(32)  NOT NULL COMMENT '对外工单号：TK + yyyyMMdd + 6 位序号',
  user_id         BIGINT       NOT NULL COMMENT '提交人 app_user.id',
  agent_id        BIGINT                DEFAULT NULL COMMENT '受理管理员 app_user.id；NULL = 未分配',
  conversation_id BIGINT                DEFAULT NULL COMMENT '关联会话 id（客服留言场景，可为空）',
  category        VARCHAR(30)           DEFAULT NULL COMMENT '工单类型：complaint / feedback / service',
  title           VARCHAR(200) NOT NULL COMMENT '标题：投诉取 reason 前 50 字 / 反馈取 content 前 50 字 / 留言取用户填',
  priority        VARCHAR(10)  NOT NULL DEFAULT 'normal' COMMENT '优先级：low / normal / high / urgent',
  status          VARCHAR(20)  NOT NULL DEFAULT 'waiting' COMMENT '状态机：waiting / processing / replied / closed；closed 不可回退',
  sla_due_at      DATETIME     NOT NULL COMMENT 'SLA 到期时间（UTC）= created_at + ticket.sla_hours（默认 24h）；落库便于索引与超时扫描',
  closed_at       DATETIME              DEFAULT NULL COMMENT '关闭时间（UTC）',
  created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC，应用层填）',
  updated_at      DATETIME     NOT NULL COMMENT '更新时间（UTC，应用层填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (ticket_no),
  KEY idx_ticket_status (status, created_at),
  KEY idx_ticket_agent (agent_id, status),
  KEY idx_ticket_sla (status, sla_due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='客服工单；投诉/反馈提交时同事务生成';
