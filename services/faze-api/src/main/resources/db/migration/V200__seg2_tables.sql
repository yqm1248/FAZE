-- ============================================================================
-- V200 · 第 2 段 G1.1：12 张表（10 张既有 + 2 张本段新增）
--
-- 做什么：conversation / conversation_message / message_offline / orders /
--         payment_record / refund_record / review / article / article_translation /
--         social_action + 新增 order_status_log / task_queue。
-- 依赖：V1 的 app_user、V4 的 lawyer、V5 的 balance_flow（全部只是逻辑引用，
--       本库不建物理外键——见《数据表规范》§5.1 第 2 条）。
-- 依据：
--   DDL 原件《技术路线》§5.4（会话/消息）、§5.5（订单/支付/退款/评价）、
--   §5.8（文章）、§5.9（社交动作）；段 2 工作流 §零之二 7 项拍板。
--   order_status_log / task_queue 是本段新增（拍板第 1、2 项），字段见工作流 G1.1。
--
-- 相对技术路线 DDL 的四处修正（都是拍板要求的，不是重新设计）：
--   ① orders.status 注释改为 8 值（补 refund_rejected；disputed/settled 段 5 才加）
--   ② payment_record.channel 注释补 balance（段 2 唯一渠道）
--   ③ refund_record 补 audit_remark（驳回原因；技术路线只有 reason=用户申请原因）
--   ④ 新增 order_status_log / task_queue 两张表
--
-- 本版补的索引（技术路线 DDL 没写，按《数据表规范》§5.1 必建索引补齐）：
--   所有 xxx_id 字段的索引、所有软删表的 deleted_at 索引。
--   单表索引均 ≤ 6 个（§5.2）。
--
-- 版本号：按《数据表规范》§8.1，第 2 段用 V200~V299。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. IM 会话（A 业务实体表；一个用户 × 一个律师 = 一条，跨订单复用）
--    拍板第 6 项：不加 order_id，唯一键保持 (user_id, lawyer_id)。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id       BIGINT       NOT NULL COMMENT 'C 端用户 ID',
  lawyer_id     BIGINT       NOT NULL COMMENT 'lawyer.id',
  last_message  VARCHAR(200)          DEFAULT NULL COMMENT '最后一条消息摘要（列表展示用）',
  last_msg_at   DATETIME              DEFAULT NULL COMMENT '最后一条消息时间（UTC）',
  unread_user   INT          NOT NULL DEFAULT 0 COMMENT '用户侧未读数',
  unread_lawyer INT          NOT NULL DEFAULT 0 COMMENT '律师侧未读数',
  created_at    DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at    DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  deleted_at    DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）；NULL 表示未删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_conversation_user_lawyer (user_id, lawyer_id),
  KEY idx_conversation_lawyer_msg (lawyer_id, last_msg_at),
  KEY idx_conversation_deleted (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='IM 会话；UNIQUE(user_id,lawyer_id) 保证一个律师一个会话、跨订单复用';

-- ---------------------------------------------------------------------------
-- 2. IM 消息（B 追加型；证据链，不 UPDATE 正文、不物理删除）
--    seq 由 Redis INCR conv:{id}:seq 生成；client_msg_id 是客户端幂等键。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation_message (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  conversation_id   BIGINT       NOT NULL COMMENT 'conversation.id',
  seq               BIGINT       NOT NULL COMMENT '会话内递增序号，游标分页依据',
  client_msg_id     VARCHAR(64)  NOT NULL COMMENT '客户端生成的幂等键，重发去重',
  sender_role       VARCHAR(10)  NOT NULL COMMENT 'user / lawyer / system / customer_service',
  sender_id         BIGINT       NOT NULL COMMENT '发送者 ID',
  msg_type          VARCHAR(15)  NOT NULL COMMENT 'text / image / file / system（撤回不改此字段）',
  content           TEXT                  DEFAULT NULL COMMENT '文本内容；非 text 类型可为空',
  file_url          VARCHAR(500)          DEFAULT NULL COMMENT '文件 URL（私有桶签名地址另拼）',
  file_name         VARCHAR(200)          DEFAULT NULL COMMENT '文件名',
  file_size         BIGINT                DEFAULT NULL COMMENT '文件大小（字节）',
  read_by_user_at   DATETIME              DEFAULT NULL COMMENT '用户侧已读时间（UTC）',
  read_by_lawyer_at DATETIME              DEFAULT NULL COMMENT '律师侧已读时间（UTC）',
  recalled_at       DATETIME              DEFAULT NULL COMMENT '撤回时间（UTC）；非空即已撤回',
  created_at        DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_conversation_seq (conversation_id, seq),
  UNIQUE KEY uk_msg_client_id (conversation_id, client_msg_id),
  KEY idx_msg_conversation_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='IM 消息，只追加；离线补推与断线重连都按 seq 增量拉取';

-- ---------------------------------------------------------------------------
-- 3. IM 离线消息（B 追加型；重连补推用，推送成功后清理）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_offline (
  id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id         BIGINT   NOT NULL COMMENT '接收方用户 ID',
  conversation_id BIGINT   NOT NULL COMMENT 'conversation.id',
  message_id      BIGINT   NOT NULL COMMENT 'conversation_message.id',
  seq             BIGINT   NOT NULL COMMENT '消息 seq，补推排序依据',
  created_at      DATETIME NOT NULL COMMENT '创建时间（UTC）；定时清理按它扫',
  PRIMARY KEY (id),
  KEY idx_offline_user_seq (user_id, seq),
  KEY idx_offline_created (created_at),
  KEY idx_offline_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='离线消息队列；重连后补推，推送成功即清理';

-- ---------------------------------------------------------------------------
-- 4. 订单（A 业务实体表）
--    status 只做 8 态（拍板第 4 项）：disputed / settled 属段 5。
--    金额全为整数分；platform_fee_cents / lawyer_income_cents 只记账，
--    段 2 不产生流水、不动 lawyer_balance（结算属段 5）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_no            VARCHAR(32)  NOT NULL COMMENT '业务单号：OR+yyyyMMdd+6 位，
  由 Redis INCR 按天分桶生成，数据库唯一键兜底',
  user_id             BIGINT       NOT NULL COMMENT '下单用户 ID',
  lawyer_id           BIGINT                DEFAULT NULL COMMENT 'lawyer.id；下单时必填，允许为空兼容历史数据',
  service_type        VARCHAR(20)  NOT NULL COMMENT 'consult_text / consult_phone / document / case',
  amount_cents        BIGINT       NOT NULL COMMENT '订单金额（分）；价格快照，律师改价不影响已下单',
  platform_fee_cents  BIGINT       NOT NULL DEFAULT 0 COMMENT '平台服务费（分），amount × platform_fee_rate',
  lawyer_income_cents BIGINT       NOT NULL DEFAULT 0 COMMENT '律师收入（分），只在订单上记账，段 5 结算',
  status              VARCHAR(20)  NOT NULL COMMENT 'pending / paid / in_service / completed / cancelled / refunding / refunded / refund_rejected（段 2 八态；disputed / settled 段 5 追加）',
  paid_at             DATETIME              DEFAULT NULL COMMENT '支付时间（UTC）',
  accepted_at         DATETIME              DEFAULT NULL COMMENT '律师接单时间（UTC）',
  finished_at         DATETIME              DEFAULT NULL COMMENT '服务完成时间（UTC）',
  cancelled_at        DATETIME              DEFAULT NULL COMMENT '取消时间（UTC）',
  remark              VARCHAR(500)          DEFAULT NULL COMMENT '用户下单备注',
  created_at          DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at          DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  deleted_at          DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_orders_no (order_no),
  KEY idx_orders_user (user_id),
  KEY idx_orders_lawyer (lawyer_id),
  KEY idx_orders_status (status),
  KEY idx_orders_deleted (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单主表；状态迁移只由服务端做，每次迁移写 order_status_log';

-- ---------------------------------------------------------------------------
-- 5. 支付记录（B 追加型）
--    段 2 只有一种渠道：balance（余额抵扣）。退款行 amount_cents 记负数，
--    使 SUM(amount_cents) = 净收款（拍板第 5 项；服务费行不写，段 5 也不写）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_record (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id       BIGINT       NOT NULL COMMENT 'orders.id',
  channel        VARCHAR(20)  NOT NULL COMMENT 'balance（段 2 订单支付/退款）/ wechat / alipay / mock（段 6 充值）',
  transaction_id VARCHAR(64)  NOT NULL COMMENT '渠道流水号；余额支付记 BAL-<balance_flow.id>',
  amount_cents   BIGINT       NOT NULL COMMENT '正数=收款，负数=退款（净额口径）',
  status         VARCHAR(20)           DEFAULT NULL COMMENT 'success / failed / pending',
  raw_payload    TEXT                  DEFAULT NULL COMMENT '渠道原始回调报文（脱敏后）',
  paid_at        DATETIME              DEFAULT NULL COMMENT '支付/退款完成时间（UTC）',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_order_channel_txn (order_id, channel, transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='支付记录；正数收款、负数退款，SUM(amount_cents)=净收款';

-- ---------------------------------------------------------------------------
-- 6. 退款单（C 状态机表）
--    reason = 用户申请原因；audit_remark = 审批意见/驳回原因（本段补，技术路线没有）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS refund_record (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id       BIGINT       NOT NULL COMMENT 'orders.id',
  reason         VARCHAR(500)          DEFAULT NULL COMMENT '用户申请退款的原因',
  amount_cents   BIGINT       NOT NULL COMMENT '申请退款金额（分）',
  status         VARCHAR(20)  NOT NULL COMMENT 'applying / approved / rejected / refunded',
  transaction_id VARCHAR(64)           DEFAULT NULL COMMENT '退款流水号（BAL-<balance_flow.id>）',
  audit_remark   VARCHAR(500)          DEFAULT NULL COMMENT '审批意见 / 驳回原因（段 2 拍板：驳回必须留原因）',
  audited_by     BIGINT                DEFAULT NULL COMMENT '审批人 ID（PC 后台管理员）',
  audited_at     DATETIME              DEFAULT NULL COMMENT '审批时间（UTC）',
  refunded_at    DATETIME              DEFAULT NULL COMMENT '退款到账时间（UTC）',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at     DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_refund_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='退款单；审核通过才写 balance_flow，退款必须走流水';

-- ---------------------------------------------------------------------------
-- 7. 评价（A 业务实体表）
--    一单只能评一次（uk_review_order）；段 2 不提供修改/删除/申诉（工作流 §七）。
--    lawyer.rating / review_count 由评价后重算写回，不用增量算法（2-A4）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id     BIGINT       NOT NULL COMMENT 'orders.id；唯一，一单只能评一次',
  user_id      BIGINT       NOT NULL COMMENT '评价人 ID',
  lawyer_id    BIGINT       NOT NULL COMMENT 'lawyer.id',
  rating       INT          NOT NULL COMMENT '评分 1-5',
  tags         JSON                  DEFAULT NULL COMMENT '标签数组，如 ["专业","响应快"]',
  content      VARCHAR(500)          DEFAULT NULL COMMENT '评价正文',
  lawyer_reply VARCHAR(500)          DEFAULT NULL COMMENT '律师回复（段 2 新增接口）',
  is_anonymous TINYINT      NOT NULL DEFAULT 0 COMMENT '是否匿名 1=是',
  created_at   DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at   DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_order (order_id),
  KEY idx_review_lawyer (lawyer_id),
  KEY idx_review_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='评价；一单一次，评分即时写回 lawyer.rating / review_count';

-- ---------------------------------------------------------------------------
-- 8. 文章主表（A 业务实体表；源语言 = 简体中文）
--    content 是 JSON 块数组（段落/标题/法条引用/列表/图片），
--    法条引用块要绑定 law_item.id，所以不用 Markdown。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS article (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  title             VARCHAR(200) NOT NULL COMMENT '源语言标题',
  summary           VARCHAR(500)          DEFAULT NULL COMMENT '摘要（列表用）',
  content           JSON         NOT NULL COMMENT '源语言正文，结构化块数组',
  category          VARCHAR(30)           DEFAULT NULL COMMENT '分类',
  cover_oss_key     VARCHAR(500)          DEFAULT NULL COMMENT '封面图的 OSS key（私有桶）',
  sort_order        INT          NOT NULL DEFAULT 0 COMMENT '排序权重（大在前）',
  status            VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft / published / archived（唯一可见性状态机）',
  published_at      DATETIME              DEFAULT NULL COMMENT '上架时间（UTC）',
  source_updated_at DATETIME     NOT NULL COMMENT '源文最后更新时间（UTC），用于判断译文是否过期',
  view_count        INT          NOT NULL DEFAULT 0 COMMENT '阅读数',
  created_at        DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at        DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  deleted_at        DATETIME              DEFAULT NULL COMMENT '软删时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_article_pub (status, published_at),
  KEY idx_article_category (category, status),
  KEY idx_article_deleted (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='文章主表，存简体中文源内容；是否可见只看本表 status';

-- ---------------------------------------------------------------------------
-- 9. 文章翻译（A 业务实体表；只存非中文，zh-Hans 不在本表）
--    translation.status 只表示译文质量阶段，不控制可见性（《技术路线》§5.8.1）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS article_translation (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  article_id         BIGINT       NOT NULL COMMENT 'article.id',
  lang               VARCHAR(20)  NOT NULL COMMENT 'bo / ug-Arab-CN / kk-Arab-CN',
  title              VARCHAR(200)          DEFAULT NULL COMMENT '译标题',
  summary            VARCHAR(500)          DEFAULT NULL COMMENT '译摘要',
  content            JSON                  DEFAULT NULL COMMENT '译正文，结构化块数组',
  status             VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft / reviewed / published（译文质量阶段）',
  translator         VARCHAR(100)          DEFAULT NULL COMMENT '人工译者（留痕）',
  translation_engine VARCHAR(50)           DEFAULT NULL COMMENT '机翻引擎名（配合 AI 内容标识要求）',
  translated_from    DATETIME              DEFAULT NULL COMMENT '翻译时的 source_updated_at（UTC）',
  created_at         DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at         DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_art_trans (article_id, lang),
  KEY idx_art_trans_lang (lang, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='非中文译文；zh-Hans 不在此表；译文未完成时回退显示中文';

-- ---------------------------------------------------------------------------
-- 10. 社交动作（A 业务实体表；收藏/关注/点赞/浏览历史统一表）
--     只存"谁对哪个目标做了什么"，跨域只存 ID，列表由 API 层协调各域 Service 组装。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS social_action (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id     BIGINT      NOT NULL COMMENT '操作用户 ID',
  action_type VARCHAR(20) NOT NULL COMMENT 'favorite / follow / like / history',
  target_type VARCHAR(20) NOT NULL COMMENT 'lawyer / article / case / document',
  target_id   BIGINT      NOT NULL COMMENT '目标实体 ID（跨域只存 ID，不存实体数据）',
  created_at  DATETIME    NOT NULL COMMENT '创建时间（UTC）',
  updated_at  DATETIME    NOT NULL COMMENT '更新时间（UTC）',
  deleted_at  DATETIME             DEFAULT NULL COMMENT '软删时间（UTC）；取消收藏即软删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_social_user_action_target (user_id, action_type, target_type, target_id),
  KEY idx_social_user_type (user_id, action_type, created_at),
  KEY idx_social_target (target_type, target_id, action_type),
  KEY idx_social_deleted (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='社交动作表；跨域只存 ID，列表由 API 层协调多个域的 Service 组装';

-- ---------------------------------------------------------------------------
-- 11. 订单状态迁移流水（B 追加型；段 2 新增，拍板第 1 项）
--     每次 orders.status 变化必须落一行，2-A4 的"状态流转时间线"就以它为准。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_status_log (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  order_id      BIGINT      NOT NULL COMMENT 'orders.id',
  from_status   VARCHAR(20)          DEFAULT NULL COMMENT '迁移前状态；NULL 表示订单创建（PENDING 之前）',
  to_status     VARCHAR(20) NOT NULL COMMENT '迁移后状态',
  operator_type VARCHAR(20) NOT NULL COMMENT 'user / lawyer / admin / system',
  operator_id   BIGINT               DEFAULT NULL COMMENT '操作人 ID；system 时为空',
  reason        VARCHAR(200)         DEFAULT NULL COMMENT '迁移原因（如 超时关闭 / 律师拒单 / 用户申请退款）',
  created_at    DATETIME    NOT NULL COMMENT '创建时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_osl_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订单状态迁移流水，只追加；状态可追溯的唯一依据';

-- ---------------------------------------------------------------------------
-- 12. 异步任务表（B 追加型；段 2 新增，拍板第 2 项）
--     替代 MQ：CAS 领取 + 租约回收 + 指数退避 + 死信可见。
--     status：pending 待领取 / claimed 已领取 / done 已完成 / dead 死信。
--     规则见《后端代码规范》§9.2：已投递是状态不是删除，领取必须按影响行数判断。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_queue (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_type      VARCHAR(40)  NOT NULL COMMENT 'order_close / order_accept_timeout / refund_retry / im_push 等',
  biz_no         VARCHAR(64)  NOT NULL COMMENT '业务单号（订单号 / 退款单号），用于回查与幂等',
  status         VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending / claimed / done / dead',
  retry_count    INT          NOT NULL DEFAULT 0 COMMENT '已重试次数；>=5 进死信',
  next_retry_at  DATETIME     NOT NULL COMMENT '下次可执行时间（UTC）；指数退避 now + 2^retry 分钟',
  last_error     VARCHAR(500)          DEFAULT NULL COMMENT '最近一次失败原因（截断到 500）',
  lock_owner     VARCHAR(64)           DEFAULT NULL COMMENT '领取者标识（实例 ID + 线程）',
  lock_expire_at DATETIME              DEFAULT NULL COMMENT '租约到期时间（UTC）；过期即回收',
  trace_id       VARCHAR(64)           DEFAULT NULL COMMENT '来源请求的 traceId（《数据表规范》附录 D 第 3 条）',
  created_at     DATETIME     NOT NULL COMMENT '创建时间（UTC）',
  updated_at     DATETIME     NOT NULL COMMENT '更新时间（UTC）',
  PRIMARY KEY (id),
  KEY idx_task_claim (status, next_retry_at),
  KEY idx_task_lock (lock_expire_at),
  KEY idx_task_biz (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='异步任务表：CAS 领取 + 租约回收 + 指数退避 + 死信；替代 MQ';
