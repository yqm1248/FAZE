# 法泽 FAZE —— 技术路线 v2.3：28 条缺陷修复

> 版本：v2.3-RC
> 日期：2026-10-27
> 基础文档：`法泽FAZE_技术路线_v2.2.md`
> 修复对象：v2.2 附录 F「待修清单」全部 28 条
> 状态：**修复方案已定稿，待合入 v2.3 正式版**

---

## 0. 本文档的用法

本文档不是又一份技术路线，而是 **v2.2 附录 F 的逐条修复单**。每条包含：

1. **问题**：当前是什么状态、为什么是问题
2. **修复方案**：具体怎么做（含 DDL / 代码 / 配置）
3. **验收标准**：怎么确认修好了

合入 v2.3 时，把本文的 DDL 替换进 v2.2 第五章，把机制说明合并进对应章节，然后删除本文。

### 修复清单总览

| 分组 | 编号 | 条目 | 影响面 |
|---|---|---|---|
| F-1 数据库 | 1–5 | 5 条 | DDL |
| F-2 缺失的表 | 6–15 | 10 张表 | DDL |
| F-3 其他 | 16–28 | 13 条 | 设计/配置/流程 |

---

# F-1 数据库（5 条）

## F-1-1 11 张表缺 `deleted_at` / `updated_at`

### 问题

v2.2 的 5.2 节声明「所有表都有 `created_at` / `updated_at` / `deleted_at`」，但下列 11 张表不符合。这导致软删标准在前 30 行内就失效，Flyway 落库后还要补一版迁移。

### 修复原则（关键）

不是所有表都该补软删。**要按表的性质分三类处理**：

| 类别 | 特征 | 处理 |
|---|---|---|
| **业务实体表** | 记录会被"删除"或"修改"，需要软删保护 | 补 `deleted_at` + `updated_at` |
| **追加型流水表** | 只插入不修改不删除，是审计凭证 | **不补软删**，但要显式声明"仅追加" |
| **状态机表** | 靠 `status` 字段表达生命周期 | 补 `updated_at`，不补 `deleted_at` |

### 逐表处理

| # | 表 | 类别 | 处理 |
|---|---|---|---|
| 1 | `realname_auth` | 业务实体 | 补 `updated_at` + `deleted_at` |
| 2 | `enterprise_auth` | 业务实体 | 补 `updated_at` + `deleted_at` |
| 3 | `refund_record` | 状态机 | 补 `deleted_at`（`updated_at` 已有） |
| 4 | `review` | 业务实体 | 补 `deleted_at`（`updated_at` 已有） |
| 5 | `favorite` | 业务实体 | 补 `updated_at` + `deleted_at` |
| 6 | `notification` | 业务实体 | 补 `updated_at` + `deleted_at` |
| 7 | `complaint` | 业务实体 | 补 `updated_at` + `deleted_at` |
| 8 | `feedback` | 业务实体 | 补 `updated_at` + `deleted_at` |
| 9 | `conversation_message` | **追加型流水** | **不补软删**，加注释「仅追加，撤回由 `recalled_at` 表达」 |
| 10 | `payment_record` | **追加型流水** | **不补软删**，加注释「仅追加，是支付审计凭证」 |
| 11 | `signature` | **追加型流水** | **不补软删**，加注释「仅追加，签署行为不可撤销」 |

### 修复 SQL

```sql
-- ── 业务实体表：补 updated_at + deleted_at ──
ALTER TABLE realname_auth
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER status,
  ADD COLUMN deleted_at DATETIME NULL AFTER updated_at;

ALTER TABLE enterprise_auth
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER status,
  ADD COLUMN deleted_at DATETIME NULL AFTER updated_at;

ALTER TABLE favorite
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD COLUMN deleted_at DATETIME NULL;

ALTER TABLE notification
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD COLUMN deleted_at DATETIME NULL;

ALTER TABLE complaint
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD COLUMN deleted_at DATETIME NULL;

ALTER TABLE feedback
  ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD COLUMN deleted_at DATETIME NULL;

-- ── 状态机表：只补 deleted_at ──
ALTER TABLE refund_record ADD COLUMN deleted_at DATETIME NULL;
ALTER TABLE review        ADD COLUMN deleted_at DATETIME NULL;

-- ── 追加型流水表：不补软删，只加说明 ──
ALTER TABLE conversation_message COMMENT = 'IM 消息，仅追加；撤回由 recalled_at 表达，不做软删';
ALTER TABLE payment_record       COMMENT = '支付流水，仅追加；是资金审计凭证，不做软删';
ALTER TABLE signature            COMMENT = '签名笔迹记录，仅追加；签署行为不可撤销';
```

### 同时修正 5.2 的表述

把「**所有表**都有 created_at/updated_at/deleted_at」改为：

> **业务实体表**必须有 `created_at` / `updated_at` / `deleted_at`；
> **追加型流水表**（`conversation_message`、`payment_record`、`signature`、`balance_flow`、`login_log`、`operation_log`）
> 只有 `created_at`，不做软删；**状态机表**有 `status` + `updated_at`，不一定要 `deleted_at`。

### 验收标准

- [ ] `SHOW CREATE TABLE` 逐表核对，11 张表全部符合上表分类
- [ ] MyBatis-Plus 的 `@TableLogic` 只配在业务实体表上，不要在追加型表配
- [ ] 文档 5.2 的表述已改

---

## F-1-2 关键外键字段无索引

### 问题

v2.2 的 DDL 里，多个高频查询字段没有索引。演示数据量小时看不出来，一旦有几千条数据，律师详情页的评价列表、律师工作台、后台审核队列全部全表扫描。

### 逐条修复

| 表 | 缺失索引 | 对应查询 |
|---|---|---|
| `review` | `lawyer_id` | 律师详情页的评价列表 |
| `complaint` | `user_id` | 我的投诉列表 |
| `lawyer` | `(audit_status, region)` | 后台审核队列、按地区筛选 |
| `lawyer` | `online_status` | 律师列表"仅看在线" |
| `lawyer_application` | `audit_status` | 后台待审列表 |
| `article_translation` | `(lang, status)` | 按语言取已发布文章 |
| `case_library` | `category` | 案例分类筛选 |
| `notification` | `(user_id, is_read, created_at)` | 消息中心未读数 + 列表 |
| `document` | `(user_id, status)` | 我的文书按状态筛选 |
| `orders` | `(user_id, status, created_at)` | 订单列表按状态筛选 |
| `orders` | `(lawyer_id, status)` | 律师订单管理 |

### 修复 SQL

```sql
-- 评价、投诉
ALTER TABLE review    ADD KEY idx_review_lawyer (lawyer_id, created_at);
ALTER TABLE complaint ADD KEY idx_complaint_user (user_id, created_at);

-- 律师检索
ALTER TABLE lawyer ADD KEY idx_lawyer_audit_region (audit_status, region),
                     ADD KEY idx_lawyer_online (online_status, audit_status);

-- 入驻审核队列
ALTER TABLE lawyer_application ADD KEY idx_app_audit (audit_status, created_at);

-- 消息中心（未读数查询是最高频的）
ALTER TABLE notification ADD KEY idx_notify_user_read (user_id, is_read, created_at);

-- 订单
ALTER TABLE orders ADD KEY idx_order_user_status (user_id, status, created_at),
                    ADD KEY idx_order_lawyer_status (lawyer_id, status);

-- 文书（与 v2.2 已有的 idx_document_user 合并，避免冗余索引）
ALTER TABLE document ADD KEY idx_doc_user_status (user_id, status, created_at);

-- ⚠️ 以下两条不在本节执行：
--   article_translation 的 (lang, status) 与 case_library 的 category
--   这两张表在 F-1-4 / F-1-5 会被重建，索引已在重建语句中定义。
--   若你选择不重建这两张表，则在此补：
--     ALTER TABLE article_translation ADD KEY idx_trans_lang_status (lang, status);
--     ALTER TABLE case_library        ADD KEY idx_case_cat (category);
```

### 与 v2.2 已建索引的冲突核对

v2.2 已加的索引（不要再加）：

| 表 | v2.2 已建 |
|---|---|
| `conversation` | `uk_conversation_user_lawyer`、`idx_conversation_lawyer_msg` |
| `conversation_message` | `uk_msg_conversation_seq`、`uk_msg_client_id`、`idx_msg_conversation_created` |
| `message_offline` | `idx_offline_user_seq`、`idx_offline_created` |
| `legal_case` | `uk_legal_case_no`、`idx_legal_case_user`、`idx_legal_case_lawyer`、`idx_legal_case_status` |
| `withdraw_record` | `idx_withdraw_lawyer`、`idx_withdraw_status` |
| `document` | `idx_document_user`、`idx_document_lawyer`、`idx_document_template` |
| `signature` | `idx_signature_document`、`idx_signature_user` |
| `balance_flow` | `uk_flow_biz`、`idx_flow_owner_time`、`idx_flow_frozen` |
| `recharge_record` | `uk_recharge_order_no`、`uk_recharge_channel_txn`、`idx_recharge_user` |

**注意**：`document` 的 `idx_doc_user_status` 与已有 `idx_document_user` 前缀重叠，落地时二选一（保留 `idx_doc_user_status`，删 `idx_document_user`）。

### 索引设计原则（写进文档，避免以后再加）

1. 所有 `xxx_id` 外键字段**默认建索引**，除非该字段只在写入时出现
2. 列表查询的 `(过滤字段, 排序字段)` 建**联合索引**，顺序不能反
3. 联合索引注意**最左前缀**：`uk(user_id, lawyer_id)` 不能服务「按 `lawyer_id` 查」
4. 未读数这类"每次进页面都查"的，单独优化

### 验收标准

- [ ] `EXPLAIN` 每条高频查询，`type` 不为 `ALL`
- [ ] 律师详情页评价列表、消息中心未读数两条查询走索引

---

## F-1-3 `favorite` / `follow` 的域归属冲突

### 问题

v2.2 把 `favorite`、`follow` 划给了 `lawyer` 业务域，但这两个表的 `target_type` 覆盖 `article` / `case` / `document` / `lawyer`，跨了多个域。而 4.2 节又规定「禁止跨域直接访问 mapper」。**这是架构上的自相矛盾**：一个域的表要读别的域的数据。

### 修复方案：独立 `social` 域

```
modules/
├─ account/
├─ lawyer/
├─ conversation/
├─ trade/
├─ document/
├─ caseflow/
├─ content/       文章、案例、法条
├─ social/        ← 新增：收藏、关注、点赞、浏览历史
├─ ai/
└─ ops/
```

**`social` 域的职责**：只存"某用户对某目标做了什么动作"，**不存目标本身的数据**。

```sql
-- 统一收藏表（替代 v2.2 的 favorite）
CREATE TABLE social_action (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  action_type VARCHAR(20) NOT NULL,   -- favorite / follow / like / history
  target_type VARCHAR(20) NOT NULL,   -- lawyer / article / case / document
  target_id   BIGINT      NOT NULL,
  created_at  DATETIME    NOT NULL,
  updated_at  DATETIME    NOT NULL,
  deleted_at  DATETIME    NULL,
  UNIQUE KEY uk_social_user_action_target (user_id, action_type, target_type, target_id),
  KEY idx_social_user_type (user_id, action_type, created_at),
  KEY idx_social_target (target_type, target_id, action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='社交动作表：收藏/关注/点赞/浏览历史，不存储目标实体数据';
```

### 跨域读取的处理

`social` 域只存 ID。**页面需要展示"我收藏的律师列表"时**，由 API 层协调：

```text
SocialController.listFavorites(userId, actionType, targetType, cursor)
  → 1. social 域查 target_id 列表（本域）
  → 2. 按 targetType 调用对应域的 Service（如 LawyerQueryService.batchGetByIds）
  → 3. 组装成 VO 返回
```

**规则**：跨域调用走 **Service 接口**，不直接访问对方的 Mapper。这符合 v2.2 的 4.2 约束。

### 验收标准

- [ ] `favorite` 表迁移为 `social_action`（或保留原表名但改域归属）
- [ ] `favorite` / `follow` 从 `lawyer` 域移除
- [ ] 收藏列表接口不跨域访问 Mapper

---

## F-1-4 `article` 主表缺 `title` / `content`，且两套状态机

### 问题

v2.2 的 `article` 表只有 `category` / `cover` / `sort_order`，**没有 `title`、`content`、`published_at`**——正文全在 `article_translation`。带来三个问题：

1. 中文也必须建一条 translation 行，否则列表无标题
2. `article_translation.status`（`missing`/`draft`/`reviewed`）与 `article.status`（`published`）是**两套互不相干的状态机**
3. `content` 是纯 `MEDIUMTEXT`，法条引用块、图注、多图**无结构**

### 修复方案

**原则：中文作为"源语言"存在主表，译文存翻译表。**

```sql
-- 文章主表（源语言 = 简体中文）
CREATE TABLE article (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  title            VARCHAR(200) NOT NULL,          -- 新增：源语言标题
  summary          VARCHAR(500),                   -- 新增：摘要（列表用）
  content          JSON         NOT NULL,          -- 新增：源语言正文，结构化块
  category_id      BIGINT,
  cover_url        VARCHAR(500),
  sort_order       INT NOT NULL DEFAULT 0,
  status           VARCHAR(20) NOT NULL DEFAULT 'draft',   -- draft / published / archived（唯一状态机）
  published_at     DATETIME,
  source_updated_at DATETIME NOT NULL,             -- 源语言最后更新时间，用于判断译文是否过期
  created_at       DATETIME NOT NULL,
  updated_at       DATETIME NOT NULL,
  deleted_at       DATETIME,
  KEY idx_article_status_pub (status, published_at),
  KEY idx_article_category (category_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章主表，存简体中文源内容';

-- 翻译表（只存非中文译文）
CREATE TABLE article_translation (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  article_id    BIGINT      NOT NULL,
  lang          VARCHAR(20) NOT NULL,             -- bo / ug-Arab-CN / kk-Arab-CN（不含 zh-Hans）
  title         VARCHAR(200),
  summary       VARCHAR(500),
  content       JSON,
  status        VARCHAR(20) NOT NULL DEFAULT 'draft',  -- draft / reviewed / published
  translator    VARCHAR(100),                     -- 译者，人工校对留痕
  translation_engine VARCHAR(50),                 -- 若为机翻，记录引擎名（配合 AI 内容标识要求）
  translated_from DATETIME,                       -- 翻译时的 source_updated_at
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  UNIQUE KEY uk_trans_article_lang (article_id, lang),
  KEY idx_trans_lang_status (lang, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='非中文译文；zh-Hans 不在此表';
```

### `content` 的结构（JSON 块）

```json
[
  { "type": "paragraph", "text": "……" },
  { "type": "heading", "level": 2, "text": "一、借款利息的上限" },
  { "type": "law_ref", "lawName": "民法典", "articleNo": "第六百八十条", "lawItemId": 1234 },
  { "type": "list", "ordered": true, "items": ["……", "……"] },
  { "type": "image", "src": "oss://...", "alt": "……", "caption": "……" }
]
```

**为什么用 JSON 而不是 Markdown**：法条引用块需要绑定 `law_item` 的 ID（点进去看法条原文），Markdown 做不到。

### 状态机的唯一性

| 状态 | 表 | 含义 |
|---|---|---|
| `draft` → `published` → `archived` | `article.status` | **内容生命周期，唯一真相** |
| `draft` → `reviewed` → `published` | `article_translation.status` | 仅表示**译文质量阶段**，不控制内容可见性 |

**规则**：文章是否对外可见，只看 `article.status`；`translation.status != 'published'` 时，该语言**回退显示中文**并在 UI 标注"该语言译文未完成"。

### 验收标准

- [ ] `article` 有 `title` / `content` / `summary`
- [ ] `article_translation` 不含 `zh-Hans` 行
- [ ] `content` 为 JSON 块结构
- [ ] 译文缺失时能回退中文

---

## F-1-5 `case_library` 用行内 `lang` 单语

### 问题

v2.2 的 `case_library` 用行内 `lang` 字段，意味着**一个案例的四种语言是四条互不相干的记录**——无法关联、无法判断哪条是译文、无法做"译文过期"告警。

### 修复方案：与 article 同构

```sql
-- 案例主表（源语言中文）
CREATE TABLE case_library (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  title         VARCHAR(200) NOT NULL,
  keywords      VARCHAR(500),                  -- 关键词标签，逗号分隔或 JSON
  summary       TEXT,                          -- 案情简介
  judgment      TEXT,                          -- 裁判结果
  insight       TEXT,                          -- 案例启示
  category      VARCHAR(30),                   -- civil / administrative / criminal
  law_refs      JSON,                          -- 关联法条：[{"lawName","articleNo","lawItemId"}]
  source        VARCHAR(100),                  -- 案例来源（案号或出处）
  status        VARCHAR(20) NOT NULL DEFAULT 'draft',
  published_at  DATETIME,
  source_updated_at DATETIME NOT NULL,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  deleted_at    DATETIME,
  KEY idx_case_cat (category, status),
  KEY idx_case_pub (status, published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='案例库主表，存简体中文源内容';

CREATE TABLE case_library_translation (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id       BIGINT      NOT NULL,
  lang          VARCHAR(20) NOT NULL,           -- bo / ug-Arab-CN / kk-Arab-CN
  title         VARCHAR(200),
  summary       TEXT,
  judgment      TEXT,
  insight       TEXT,
  status        VARCHAR(20) NOT NULL DEFAULT 'draft',
  translator    VARCHAR(100),
  translation_engine VARCHAR(50),
  translated_from DATETIME,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  UNIQUE KEY uk_case_trans (case_id, lang),
  KEY idx_case_trans_lang (lang, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 译文过期的统一机制

给 article 与 case 用同一套逻辑：

```sql
-- 查询"译文已过期"的条目（源文改了但译文没重翻）
SELECT a.id, a.title, t.lang
FROM article a
JOIN article_translation t ON t.article_id = a.id
WHERE t.translated_from < a.source_updated_at
  AND t.status = 'published';
```

后台（RuoYi）做一个「译文待更新」列表页，定期提示。

### 验收标准

- [ ] `case_library` 拆为 `case_library` + `case_library_translation`
- [ ] 两套表都能查出"译文过期"记录
- [ ] 后台有对应的待办页

---

# F-2 缺失的表（10 张）

> 这 10 张表在 v2.2 中有**业务描述**（提到要做某功能）但**没有数据承载**。
> 全部采用统一规范：`id BIGINT AUTO_INCREMENT` + `created_at` + 必要的 `updated_at` / `deleted_at`。

## F-2-6 `user_device` —— 设备与推送 token

### 问题

v2.2 定了用个推做离线推送，但**没有任何表存设备的推送标识（cid）**。没有它，推送无处可投；也无法实现"登录设备管理"和"踢下线"。

### DDL

```sql
CREATE TABLE user_device (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT      NOT NULL,
  device_id    VARCHAR(128) NOT NULL,      -- 客户端生成的设备唯一标识
  platform     VARCHAR(10) NOT NULL,       -- android / ios
  push_cid     VARCHAR(128),               -- 个推 cid
  device_model VARCHAR(100),               -- 机型，排查用
  os_version   VARCHAR(50),
  app_version  VARCHAR(20),
  last_active_at DATETIME,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  deleted_at   DATETIME,                   -- 用户退出登录或换机时软删
  UNIQUE KEY uk_device_user_device (user_id, device_id),
  KEY idx_device_cid (push_cid),
  KEY idx_device_user (user_id, last_active_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设备与推送标识';
```

### 用途

| 场景 | 用法 |
|---|---|
| 推送 | 按 `user_id` 查所有有效 `push_cid`，批量推 |
| 登录设备管理 | `GET /app/account/devices` 返回该用户所有未删除设备 |
| 踢下线 | 同一账号新设备登录时，旧设备的 `push_cid` 推送 `kicked` 事件并软删 |
| 失效清理 | 个推返回 cid 无效时，软删该记录 |

### 验收标准

- [ ] 登录成功后写入/更新设备记录
- [ ] 推送链路能根据 `user_id` 取到 cid
- [ ] 个推返回无效 cid 时会清理

---

## F-2-7 `sys_config` —— 平台参数

### 问题

v2.2 的 9.6 说"平台抽成可配置（默认 15%）"、"提现门槛 100 元"，但**没有地方存这些配置**。硬编码在代码里会导致改一次要发版。

### DDL

```sql
CREATE TABLE sys_config (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  config_key  VARCHAR(100) NOT NULL,
  config_value VARCHAR(500) NOT NULL,
  value_type  VARCHAR(20) NOT NULL DEFAULT 'string',  -- string / int / decimal / boolean / json
  category    VARCHAR(30),                            -- trade / lawyer / ai / security
  remark      VARCHAR(200),
  editable    TINYINT NOT NULL DEFAULT 1,             -- 0 = 只读（系统内置）
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  UNIQUE KEY uk_config_key (config_key),
  KEY idx_config_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台参数配置';
```

### 首批内置配置项

| config_key | 默认值 | 用途 |
|---|---|---|
| `trade.platform_fee_rate` | `0.15` | 平台抽成比例 |
| `trade.withdraw_min_cents` | `10000` | 提现门槛（100 元） |
| `trade.order_pending_timeout_min` | `15` | 待支付超时（分钟） |
| `trade.order_accept_timeout_min` | `30` | 待接单超时（分钟） |
| `trade.income_freeze_days` | `7` | 律师收入冻结天数 |
| `ai.daily_limit_per_user` | `50` | 每用户每日 AI 轮次上限 |
| `ai.context_max_rounds` | `10` | AI 上下文最多轮次 |
| `security.sms_daily_per_phone` | `10` | 单手机号每日短信上限 |
| `security.login_fail_lock_count` | `5` | 登录失败锁定阈值 |

### 实现要点

1. 启动时全量加载到本地缓存（`ConcurrentHashMap`），后台修改后通过 Redis Pub/Sub 通知各实例刷新
2. **读取走缓存，不每次查库**
3. 金额类配置存**分**，不存元

### 验收标准

- [ ] 抽成比例改配置后生效，无需重启
- [ ] 所有魔法数字（15%、100 元、7 天、50 轮）都从配置读

---

## F-2-8 `notify_template` —— 通知模板

### 问题

v2.2 的 8.x 说要做"消息中心"，16.x 说要做"推送通知"，但**通知内容没有模板管理**——每条通知硬编码文案，四语言更是无法维护。

### DDL

```sql
CREATE TABLE notify_template (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(50) NOT NULL,      -- 如 order_paid / order_accepted / audit_passed
  lang        VARCHAR(20) NOT NULL,
  title       VARCHAR(200) NOT NULL,       -- 模板，支持 {{var}} 占位
  content     VARCHAR(1000) NOT NULL,
  channel     VARCHAR(50) NOT NULL DEFAULT 'inapp',  -- inapp / push / sms（逗号分隔可多选）
  status      VARCHAR(20) NOT NULL DEFAULT 'active',
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  UNIQUE KEY uk_notify_tpl (template_code, lang),
  KEY idx_notify_tpl_code (template_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板，按语言分条';
```

### 首批模板

| template_code | 触发时机 | 变量 |
|---|---|---|
| `order_paid` | 支付成功 | `{{orderNo}}` `{{amount}}` |
| `order_accepted` | 律师接单 | `{{lawyerName}}` `{{orderNo}}` |
| `order_completed` | 服务完成 | `{{orderNo}}` |
| `refund_approved` | 退款通过 | `{{amount}}` |
| `audit_passed` | 入驻审核通过 | — |
| `audit_rejected` | 入驻审核驳回 | `{{reason}}` |
| `case_progress` | 案件进度更新 | `{{caseNo}}` `{{step}}` |
| `new_message` | 收到新消息 | `{{senderName}}` |

### 验收标准

- [ ] 所有通知文案从模板表读，不硬编码
- [ ] 四语言模板齐备，缺失时回退中文

---

## F-2-9 `user_violation` —— 黑名单与违规记录

### 问题

v2.2 的 16.5 写了「黑名单与违规」，但只用 `user.status` 硬凑，**无法记录违规类型、期限、原因、处罚历史**。

### DDL

```sql
CREATE TABLE user_violation (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT      NOT NULL,
  user_type     VARCHAR(10) NOT NULL DEFAULT 'user',  -- user / lawyer
  violation_type VARCHAR(30) NOT NULL,   -- sensitive_content / fraud / no_show / abuse / other
  level         VARCHAR(20) NOT NULL,    -- warning / restrict / suspend / ban
  reason        VARCHAR(500) NOT NULL,
  evidence      JSON,                    -- 关联证据（消息 ID、投诉 ID、截图 key）
  start_at      DATETIME NOT NULL,
  end_at        DATETIME,                -- NULL = 永久
  operator_id   BIGINT NOT NULL,         -- 处罚人（后台管理员）
  revoked_at    DATETIME,                -- 申诉成功撤销
  revoke_reason VARCHAR(500),
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  KEY idx_violation_user (user_id, start_at),
  KEY idx_violation_active (user_type, level, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规与处罚记录';
```

### 与其他模块的关系

- **登录拦截**：`SELECT 1 FROM user_violation WHERE user_id=? AND level='ban' AND (end_at IS NULL OR end_at > NOW()) AND revoked_at IS NULL`
- **律师接单限制**：`level='suspend'` 时禁止接新单，但不影响已进行中的订单
- **申诉**：用户提交申诉 → 后台 `revoked_at` 置值 → 处罚失效

### 验收标准

- [ ] 处罚有期限、原因、证据、操作人
- [ ] 被封禁用户登录被拦截并提示原因与到期时间
- [ ] 支持申诉撤销

---

## F-2-10 `reconciliation_diff` —— 对账差错工单

### 问题

v2.2 的 9.5 说"无法自动处理的生成对账差错工单，财务人工处理"，但**没有这张表**。

### DDL

```sql
CREATE TABLE reconciliation_diff (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  bill_date     DATE        NOT NULL,        -- 对账日期
  channel       VARCHAR(20) NOT NULL,        -- wechat / alipay
  diff_type     VARCHAR(30) NOT NULL,        -- local_only / channel_only / amount_mismatch
  channel_txn_id VARCHAR(64),
  recharge_id   BIGINT,                      -- 关联本地充值记录
  amount_cents  BIGINT,                      -- 差异金额
  detail        JSON,                        -- 双方原始数据
  status        VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending / handling / resolved / ignored
  handler_id    BIGINT,
  handle_note   VARCHAR(500),
  handled_at    DATETIME,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  KEY idx_diff_date (bill_date, channel, status),
  KEY idx_diff_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差错工单';
```

### 验收标准

- [ ] 每日对账任务产出差异记录
- [ ] 后台有差错工单列表与处理流程
- [ ] 处理结果回写

---

## F-2-11 `invoice` —— 发票

### 问题

v2.2 的 9.6 说"用户可申请电子发票"，无表。

### DDL

```sql
CREATE TABLE invoice (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT      NOT NULL,
  order_id      BIGINT      NOT NULL,
  invoice_type  VARCHAR(20) NOT NULL,        -- personal / enterprise
  title         VARCHAR(200) NOT NULL,       -- 发票抬头
  tax_no        VARCHAR(50),                 -- 税号（企业抬头必填）
  amount_cents  BIGINT      NOT NULL,
  email         VARCHAR(100),                -- 接收邮箱
  status        VARCHAR(20) NOT NULL DEFAULT 'applying',  -- applying / issued / rejected
  invoice_no    VARCHAR(50),
  invoice_url   VARCHAR(500),                -- 电子发票 PDF 的 OSS key
  issued_at     DATETIME,
  reject_reason VARCHAR(500),
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NOT NULL,
  KEY idx_invoice_user (user_id, created_at),
  KEY idx_invoice_status (status, created_at),
  UNIQUE KEY uk_invoice_order (order_id)     -- 一单一票
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票申请与开具记录';
```

### 验收标准

- [ ] 用户可申请，后台可开具/驳回
- [ ] 一单只能开一张票

---

## F-2-12 `file_record` —— 上传文件记录

### 问题

v2.2 全篇只在各业务表里散落 `*_url VARCHAR(500)`，**没有统一的上传记录**。后果：无法统计存储用量、无法做孤儿文件清理、无法做鉴权下载（谁上传的、谁能看）。

### DDL

```sql
CREATE TABLE file_record (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  oss_key      VARCHAR(500) NOT NULL,        -- 对象键，不是 URL
  bucket       VARCHAR(100) NOT NULL,
  original_name VARCHAR(255),
  content_type VARCHAR(100),
  size_bytes   BIGINT,
  md5          VARCHAR(32),
  biz_type     VARCHAR(30),                  -- avatar / idcard / license / evidence / document / signature
  biz_id       BIGINT,                       -- 关联业务 ID
  uploader_id  BIGINT NOT NULL,
  is_private   TINYINT NOT NULL DEFAULT 1,   -- 私有（需签名 URL）/ 公开
  status       VARCHAR(20) NOT NULL DEFAULT 'active',  -- active / orphan / deleted
  created_at   DATETIME NOT NULL,
  deleted_at   DATETIME,
  UNIQUE KEY uk_file_oss_key (oss_key),
  KEY idx_file_biz (biz_type, biz_id),
  KEY idx_file_uploader (uploader_id, created_at),
  KEY idx_file_orphan (status, created_at)   -- 孤儿清理
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传文件登记，用于鉴权、统计与清理';
```

### 关键规则

1. **业务表存 `oss_key` 而不是完整 URL**（存 URL 会导致换域名要改全库）
2. 所有下载/预览统一走 `GET /app/upload/url?key=` → 服务端校验权限 → 返回 5 分钟签名 URL
3. 每日定时任务扫描：上传后 24 小时仍未关联业务的 `biz_id IS NULL` → 标记 `orphan` → 7 天后物理删除
4. 用户注销时，按 `uploader_id` 批量删除

### 验收标准

- [ ] 所有上传都经过 `file_record` 登记
- [ ] 私有文件不能直接通过 OSS 公网访问
- [ ] 孤儿文件能被清理

---

## F-2-13 `lawyer_application_log` —— 入驻审核流水

### 问题

v2.2 的 `lawyer_application` 是单行 UPDATE 的模型——律师被驳回后重新提交会**覆盖原记录**，审核历史完全丢失。这在真实上线场景是合规风险（无法证明当时为什么驳回）。

### DDL

```sql
CREATE TABLE lawyer_application_log (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  application_id BIGINT      NOT NULL,
  action         VARCHAR(20) NOT NULL,       -- submit / approve / reject / resubmit / revoke
  from_status    VARCHAR(20),
  to_status      VARCHAR(20) NOT NULL,
  operator_type  VARCHAR(20) NOT NULL,       -- lawyer / admin / system
  operator_id    BIGINT,
  reason         VARCHAR(500),               -- 驳回理由
  snapshot       JSON,                       -- 本次提交的资料快照（含 oss_key）
  created_at     DATETIME NOT NULL,
  KEY idx_applog_application (application_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入驻审核流水，只追加不修改';
```

### 配套改动

- `lawyer_application` 增加 `version INT`（第几次提交）
- 每次提交/审核都写一条 log，**不做 UPDATE**
- 资料快照存 JSON（OSS key 列表），保证历史可追溯

### 验收标准

- [ ] 每次提交与审核都有日志
- [ ] 能查询某律师的完整审核历史
- [ ] 重新提交不覆盖历史

---

## F-2-14 `sensitive_word` —— 敏感词库

### 问题

v2.2 的 16.2 说了敏感词过滤，但**词库从哪来、谁维护、如何热更新**都没定义。同时 16.2（本地词库）与 12.2（阿里云内容安全）是两条并行路线，主次未定。

### DDL

```sql
CREATE TABLE sensitive_word (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  word        VARCHAR(100) NOT NULL,
  category    VARCHAR(30) NOT NULL,          -- politics / violence / porn / fraud / illegal
  level       VARCHAR(20) NOT NULL,          -- block / review / warn
  lang        VARCHAR(20) NOT NULL DEFAULT 'zh-Hans',
  source      VARCHAR(50),                   -- manual / aliyun_green / import
  enabled     TINYINT NOT NULL DEFAULT 1,
  hit_count   BIGINT NOT NULL DEFAULT 0,     -- 命中统计，用于调优
  created_by  BIGINT,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  UNIQUE KEY uk_word_lang (word, lang),
  KEY idx_word_category (category, level, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地敏感词库';
```

### 两条路线的主次（必须定死）

| 层 | 职责 | 说明 |
|---|---|---|
| **主：阿里云内容安全** | 中文实时文本审核 | 覆盖广、持续更新、有官方合规背书 |
| **辅：本地词库** | ①兜底（阿里云超时/失败时）②业务自定义词（如"包过""稳赢"这类法律行业禁语）③小语种关键词 | 不追求全面，只补阿里云覆盖不到的 |
| **小语种** | **机器审核覆盖率低，必须配人工审核岗** | 维吾尔文/哈萨克文/藏文内容上线前人工过一遍 |

### 热更新

- 词库变更走 Redis Pub/Sub 通知，各实例重新加载到 Trie
- 每次加载记录版本号，日志可查

### 验收标准

- [ ] 敏感词可增删改，改完即时生效
- [ ] 阿里云失败时有本地词库兜底
- [ ] 命中记录可用于统计

---

## F-2-15 `login_log` / `operation_log` —— 安全审计

### 问题

v2.2 的 6.3 提到"登录错误追踪"、6.7 提到"操作日志"，但 DDL 里**一张都没有**。《网络安全法》第 21 条要求网络日志留存不少于 6 个月。

### DDL

```sql
-- 登录日志
CREATE TABLE login_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT,
  account     VARCHAR(50) NOT NULL,          -- 手机号（脱敏存储）
  login_type  VARCHAR(20) NOT NULL,          -- password / sms / refresh
  result      VARCHAR(20) NOT NULL,          -- success / fail
  fail_reason VARCHAR(100),
  ip          VARCHAR(50),
  user_agent  VARCHAR(500),
  device_id   VARCHAR(128),
  created_at  DATETIME NOT NULL,
  KEY idx_login_account (account, created_at),
  KEY idx_login_user (user_id, created_at),
  KEY idx_login_ip (ip, created_at)          -- 撞库检测
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志，仅追加，留存 6 个月以上';

-- 操作日志（敏感操作）
CREATE TABLE operation_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT      NOT NULL,
  operator_type VARCHAR(20) NOT NULL,        -- user / lawyer / admin
  module      VARCHAR(50) NOT NULL,
  action      VARCHAR(50) NOT NULL,
  target_type VARCHAR(30),
  target_id   BIGINT,
  detail      JSON,                          -- 变更前后值
  ip          VARCHAR(50),
  result      VARCHAR(20) NOT NULL,
  created_at  DATETIME NOT NULL,
  KEY idx_oplog_operator (operator_id, created_at),
  KEY idx_oplog_module (module, action, created_at),
  KEY idx_oplog_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感操作审计，仅追加';
```

### 必须记录的敏感操作

| 模块 | 操作 |
|---|---|
| 后台 | 律师审核通过/驳回、退款审批、提现审批、用户封禁、配置修改 |
| 用户 | 注销账号、实名提交、换绑手机、修改密码 |
| 律师 | 提交入驻、修改服务价格、提现申请 |
| 数据 | 导出用户数据、查看 IM 明文（工单号必须记录） |

### 留存策略

- 热数据（3 个月内）：MySQL，可查
- 冷数据：归档到 OSS（按月压缩），保留 3 年
- **不得物理删除 6 个月内的日志**

### 验收标准

- [ ] 登录成功/失败都有记录
- [ ] 后台所有敏感操作有审计
- [ ] 日志可查询、可导出、不可篡改

---

# F-3 其他（13 条）

## F-3-16 业务缓存策略缺失

### 问题

v2.2 的第六章只讲 Redis 用于「黑名单、限流、幂等、seq」，**一个字没提业务缓存**。而律师列表、热门文章、未读数这些是最高频的读，不缓存会拖垮数据库。

### 需要缓存的与策略

| 数据 | Key | TTL | 失效时机 |
|---|---|---|---|
| 律师列表首页（第一页、无筛选） | `lawyer:list:default` | 5 分钟 | 律师审核通过/下架时主动删 |
| 律师详情 | `lawyer:detail:{id}` | 10 分钟 | 资料修改时删 |
| 文章详情 | `article:{id}:{lang}` | 30 分钟 | 文章发布/编辑时删 |
| 热门文章列表 | `article:hot:{lang}` | 10 分钟 | 定时刷新 |
| 案例详情 | `case:{id}:{lang}` | 30 分钟 | 同上 |
| 法条库（全量） | `law:item:all` | 常驻（启动加载） | 法条更新时重建 |
| 未读数 | `unread:{user_id}` | 常驻 | 收/读消息时更新 |
| 平台配置 | `sys:config` | 常驻 | 后台修改时 Pub/Sub 刷新 |
| 敏感词 Trie | `sensitive:words` | 常驻 | 词库变更时 Pub/Sub 刷新 |

### 三条规则

1. **缓存 Key 必须集中定义**，不允许在业务代码里拼字符串（参考 zxz-bi-server 的 `Constants` 写法）
2. **写操作后主动删除缓存**，不依赖 TTL 过期（先删缓存再更新库，或更新库后延迟双删）
3. **热点缓存加随机 TTL 抖动**（±10%），防止同一时刻集体失效造成缓存雪崩

### 验收标准

- [ ] 律师列表、详情、文章详情走缓存
- [ ] 写操作后缓存被主动清理
- [ ] 缓存 Key 在常量类中统一定义

---

## F-3-17 深色模式

### 问题

v2.2 的 3.4 设计令牌只有一套 `color`，全文无 dark 方案。而 4 语言 × 深色 × 高对比是**组合爆炸**，越晚做越贵。

### 方案：令牌分两套 + Context 切换

```ts
// shared/theme/tokens.ts
export const lightColors = {
  background: { canvas: '#F7F8FA', surface: '#FFFFFF', input: '#F5F5F5' },
  text: { primary: '#1A1A1A', body: '#333333', secondary: '#666666', muted: '#999999' },
  // ...
};

export const darkColors = {
  background: { canvas: '#0F1115', surface: '#1A1D23', input: '#242830' },
  text: { primary: '#F2F3F5', body: '#D8DBE0', secondary: '#9BA1AC', muted: '#6B7280' },
  // ...
};
```

```tsx
// shared/theme/useTheme.ts
export function useTheme() {
  const { scheme } = useColorSchemeContext();   // 'light' | 'dark' | 'system'
  return scheme === 'dark' ? darkTokens : lightTokens;
}
```

### 关键约束

| 项 | 要求 |
|---|---|
| 品牌色 | 深色下 `#4E6EF2` 需要提亮到 `#6B87F5`，否则在深底上对比度不足 |
| 阴影 | 深色下阴影不可见，改用 `borderWidth: 1` + 略亮的边框表达层次 |
| 图片 | 头像、案例配图在深色下不反色（不要写 `tintColor`） |
| 状态色 | 青绿 `#2C9696` 在深色下要提亮 |
| 系统跟随 | `Appearance.addChangeListener` 监听系统切换，`system` 模式下实时响应 |

### 落地顺序

**第一阶段只做 light，但令牌结构预留两套**——这比事后把所有颜色字面量挖出来重构便宜得多。

### 验收标准

- [ ] 令牌结构支持 `light` / `dark` 两套
- [ ] 设置页有"跟随系统 / 浅色 / 深色"三选项
- [ ] 深色下所有页面无白块、无不可读文字

---

## F-3-18 日期与相对时间本地化

### 问题

v2.2 全文没有 Intl 方案。「3 天前」「有效期至」「答辩期 15 日」在四种语言里怎么表达没有规划。而 `Intl` 在 Hermes 上对 `ug-Arab` / `kk-Arab` 的 locale 数据可能缺失，会静默回退到英文。

### 方案：自建格式化表 + 兜底

```ts
// shared/i18n/datetime.ts

/** 各语言的月份名，不依赖 Intl（Hermes 的 locale 数据不可靠） */
const MONTHS: Record<Lang, string[]> = {
  'zh-Hans': ['1月', '2月', ...],
  'bo':      [...],   // 藏文月份名，需母语者提供
  'ug-Arab-CN': [...],
  'kk-Arab-CN': [...],
};

/**
 * 格式化绝对日期。不使用 Intl.DateTimeFormat，因为 Hermes 在
 * ug-Arab / kk-Arab 上可能缺少 locale 数据而静默回退英文。
 */
export function formatDate(date: Date, lang: Lang, style: 'full' | 'short'): string;

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 具体日期 */
export function formatRelative(date: Date, lang: Lang): string;
```

### 必须覆盖的场景

| 场景 | 中文示例 | 注意 |
|---|---|---|
| 消息时间 | 刚刚 / 3 分钟前 / 昨天 14:20 | 相对时间 |
| 文章发布 | 2026年10月27日 | 绝对日期 |
| 订单超时 | 剩余 14:32 | 倒计时 |
| 提现到账 | 预计 T+1 到账 | 业务规则 |
| 诉讼期限 | 答辩期 15 日 | 法律术语，**必须人工翻译** |
| 金额 | ¥1,234.56 | 数字用 0-9，**不本地化为阿拉伯-印度数字** |

### 为什么不用 Intl

**事实**：Hermes 在 Android 走系统 ICU、iOS 走 Foundation，`kk_Arab` / `ug_Arab` 的支持情况不确定。一旦缺失会静默回退，UI 上就会出现英文月份——而这是很难被测试发现的 bug。

**结论**：日期格式化用自建表，`Intl` 只作为可选加速路径，且必须做快照测试验证。

### 验收标准

- [ ] 四语言的月份、星期、相对时间文案齐备
- [ ] 不依赖 `Intl.DateTimeFormat` 输出用户可见文本
- [ ] 有 Intl 可用性的快照测试（4 个 locale）

---

## F-3-19 法律术语 glossary 缺统一维护

### 问题

v2.2 的 11.4 说"法律术语表注入"给 AI 用，但**模板翻译、文章翻译、后台录入没有统一术语表**。4 个译者各自发挥，同一个术语会出现 4 种译法。

### 方案：术语表作为独立数据

```sql
CREATE TABLE i18n_glossary (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  term_key    VARCHAR(100) NOT NULL,       -- 术语唯一标识，如 plaintiff
  source_text VARCHAR(200) NOT NULL,       -- 中文原文
  lang        VARCHAR(20) NOT NULL,
  target_text VARCHAR(200) NOT NULL,       -- 译法
  note        VARCHAR(500),                -- 使用说明、例外情况
  status      VARCHAR(20) NOT NULL DEFAULT 'confirmed',  -- draft / confirmed
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  UNIQUE KEY uk_glossary (term_key, lang),
  KEY idx_glossary_lang (lang, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法律术语对照表，AI/模板/文章共用';
```

### 三方使用

| 使用方 | 用法 |
|---|---|
| AI | 拼 prompt 时注入相关术语（本期用到的） |
| 模板翻译 | 译者参考，术语必须与表一致 |
| 后台录入 | 录入界面提供术语提示 |

### 首批术语（约 50 个）

原告 / 被告 / 第三人 / 起诉状 / 答辩状 / 举证 / 质证 / 诉讼时效 / 管辖 / 调解 / 仲裁 / 判决 / 裁定 / 执行 / 上诉 / 再审 / 证据 / 送达 / 保全 / 代理 / 委托 / 法律援助 / 民事责任 / 刑事责任 / 行政责任 / 违约金 / 定金 / 定金罚则 / 债权 / 债务 / 担保 / 抵押 / 质押 / 留置 / 侵权 / 不当得利 / 无因管理 / 合同解除 / 效力待定 / 可撤销 / 追认 / 表见代理 / 诉讼请求 / 反诉 / 共同诉讼 / 简易程序 / 小额诉讼 / 强制执行 …

**术语的翻译必须由母语法律译者确认**，机翻不可靠。

### 验收标准

- [ ] 术语表有表，可维护
- [ ] AI prompt 注入术语
- [ ] 翻译流程引用术语表

---

## F-3-20 敏感词"实时替换"在法律业务下有害（必须改）

### 问题

v2.2 的 16.2 隐含了"命中敏感词就替换"的做法。**在法律业务里这是错的**：

> IM 聊天记录、案件描述可能作为**纠纷证据**。如果平台把原文替换成 `***`，等于篡改证据。一旦发生纠纷，平台要承担责任。

### 正确做法：原文落库，展示层拦截

| 环节 | 做法 |
|---|---|
| 存储 | **原文完整落库**，一个字不改 |
| 审核 | 命中敏感词时，写入 `content_review` 表记录命中位置、词、等级 |
| 展示 | 展示层根据命中等级决定：`block` 级**不展示**（提示"该消息含违规内容"），`review` 级正常展示但进人工队列 |
| 日志 | 命中记录进 `operation_log`，可审计 |
| 导出 | 用户导出聊天记录时同样按等级处理 |

```sql
CREATE TABLE content_review (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  biz_type     VARCHAR(30) NOT NULL,      -- conversation_message / article / case / complaint
  biz_id       BIGINT      NOT NULL,
  hit_words    JSON,                      -- 命中的词与位置
  level        VARCHAR(20) NOT NULL,      -- block / review / warn
  engine       VARCHAR(30),               -- aliyun / local / manual
  reviewer_id  BIGINT,
  review_result VARCHAR(20),              -- pending / pass / reject
  reviewed_at  DATETIME,
  created_at   DATETIME NOT NULL,
  KEY idx_review_biz (biz_type, biz_id),
  KEY idx_review_pending (review_result, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容审核命中记录；原文不改，只标注';
```

### 验收标准

- [ ] 数据库中永远是原文
- [ ] 命中记录可追溯到具体消息
- [ ] `block` 级消息对双方都不展示，但运营后台可查

---

## F-3-21 AI 内容标识只有显式、缺隐式

### 问题

v2.2 的 12.3 只写"页面标注 AI 生成"。但《人工智能生成合成内容标识办法》（2025-09-01 施行）要求**显式 + 隐式**双重标识。

### 两种标识

| 类型 | 定义 | 落地位置 |
|---|---|---|
| **显式标识** | 用户能直接看到的文字/图形提示 | ①AI 对话页每条回答下方「本回答由 AI 生成」②导出的文书 PDF 页脚 ③分享的图片上加水印 |
| **隐式标识** | 藏在文件元数据里的信息，用户不可见但可被技术读取 | ①PDF 导出时写入元数据 ②图片导出时写入 EXIF ③服务端留存内容编号 |

### 隐式标识的具体字段

PDF 元数据（`/Producer`、自定义 XMP 字段）：

```text
Faze-Content-Type: AIGenerated
Faze-Provider: 法泽
Faze-Provider-Code: （备案后填，未备案先填占位）
Faze-Content-Id: {ai_message.id}
Faze-Generated-At: 2026-10-27T10:30:00+08:00
```

图片（EXIF `ImageDescription` / `UserComment`）：

```text
AI-generated by 法泽 | contentId: {id}
```

### 服务端留存

`ai_message` 表增加 `content_id`（内容编号）与 `is_exported`、`export_url` 字段，保证"这条 AI 内容被导出过、导出成什么"可追溯。

### 验收标准

- [ ] AI 回答有显式提示
- [ ] 导出的 PDF/图片带隐式标识
- [ ] 服务端可查内容编号

---

## F-3-22 AI 禁用清单需落到拦截层

### 问题

v2.2（v2.2 已在 11.3 补了 Prompt 层的禁用清单）**只写在 Prompt 里**。但 Prompt 是"建议"，模型可以不听。真实上线必须在**输出侧**再加一层硬拦截。

### 三层防护

| 层 | 措施 |
|---|---|
| **输入侧** | 关键词 + 轻量分类识别高风险意图（"怎么打赢官司""能赔多少""帮我写份能直接递交的起诉状"），命中则**不走 AI，直接引导转人工律师** |
| **Prompt 侧** | 已实现（11.3） |
| **输出侧** | 正则/规则校验：①出现"胜诉率""包赢""保证判 X 年"→ 拦截并重生成 ②出现"第 X 条"→ 回查法条库（已实现 ②）③出现具体金额预测 → 追加免责提示 |

### 具体拦截规则

```java
/** AI 输出后置校验；返回 null 表示通过，否则返回拦截原因 */
public String validateOutput(String answer) {
    // 1. 结果承诺类
    if (PATTERN_PROMISE.matcher(answer).find()) return "结果承诺";
    // 2. 诉讼策略类
    if (PATTERN_STRATEGY.matcher(answer).find()) return "诉讼策略建议";
    // 3. 法条存在性（已有）
    // 4. 金额/刑期预测
    if (PATTERN_PREDICTION.matcher(answer).find()) return "结果预测";
    return null;
}
```

命中后的处理：**不是直接报错**，而是追加一段"以上内容涉及推测，请以专业律师意见为准"，并在 UI 上降级展示。

### 验收标准

- [ ] 输入侧能识别高风险意图
- [ ] 输出侧有硬拦截规则
- [ ] 拦截事件有日志，可统计

---

## F-3-23 未成年人与监护人同意机制

### 问题

法律咨询场景很可能触达未成年人（校园欺凌、家暴、抚养权）。v2.2 只提了一句"年龄识别"，没有机制。

### 方案

| 项 | 做法 |
|---|---|
| 识别 | 实名认证时比对身份证出生日期；未实名用户无法使用咨询功能 |
| 未满 14 周岁 | **禁止注册**，提示"请由监护人代为咨询" |
| 14–18 周岁 | 可注册，**首次咨询前弹出监护人同意书**，需填写监护人手机号并短信确认 |
| 数据 | 未成年人对话默认标记 `is_minor=true`，访问权限更严（仅本人、其监护人、必要审核人员） |
| 内容 | 涉及未成年人的 AI 回答强制提示"建议联系监护人一同咨询" |

### 需要的表/字段

```sql
ALTER TABLE `user`
  ADD COLUMN birth_date DATE NULL AFTER id_card_no,
  ADD COLUMN is_minor TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN guardian_phone VARCHAR(20) NULL,
  ADD COLUMN guardian_consent_at DATETIME NULL;
```

### 改动（已定案）

```sql
-- 用户在 v2.3 中改名为 app_user。原因：
--   ① `USER` 在 MySQL 是函数名，易与关键字混淆；
--   ② RuoYi-Vue3 自带 sys_user（PC 后台管理员），两者必须区分。
--
-- ⚠️ v2.2 的 5.3 已经直接定义为 CREATE TABLE app_user，
--    所以**全新部署不需要 RENAME**。下面这条只对「已按 v2.1 部署过、库里还是 user 表」的环境执行。
ALTER TABLE `user` RENAME TO app_user;   -- 仅存量环境需要

-- 补未成年人相关字段（新建环境也要执行，因为 v2.2 已含这些字段，
-- 若从 v2.2 直接建库则跳过；此处列出是为了存量环境补齐）
ALTER TABLE app_user
  ADD COLUMN birth_date DATE NULL AFTER gender,
  ADD COLUMN is_minor TINYINT NOT NULL DEFAULT 0 AFTER realname_status,
  ADD COLUMN guardian_phone VARCHAR(20) NULL AFTER is_minor,
  ADD COLUMN guardian_consent_at DATETIME NULL AFTER guardian_phone,
  ADD KEY idx_app_user_role_status (role, status);

-- 原唯一键改名（保持命名一致）
ALTER TABLE app_user
  DROP INDEX uk_user_phone,
  ADD UNIQUE KEY uk_app_user_phone (phone);
```

### 角色枚举（一并扩展）

```sql
-- 原：user / lawyer / admin
-- 新：user / lawyer / customer_service / admin
ALTER TABLE app_user
  MODIFY COLUMN role VARCHAR(20) DEFAULT 'user'
  COMMENT 'user / lawyer / customer_service / admin';
```

> **注意**：`admin` 在 `app_user` 里几乎不会出现——PC 后台管理员走 RuoYi 的 `sys_user`，
> 不建 `app_user` 记录。保留这个枚举值只是为了兼容极特殊场景（如超管需要一个 C 端身份联调）。

### 改名的连带影响（必须同步）

| 层 | 要改什么 |
|---|---|
| 数据库 | 表名 + 唯一键名；所有 `user_id` **字段名不用改**（它引用 `app_user.id`），但注释统一为「关联 app_user.id」 |
| MyBatis-Plus | 实体类注解 `@TableName("app_user")`；`BaseMapper<AppUser>` |
| Java 类名 | `User` → `AppUser`（避免与 `sys_user` 的 `SysUser` 混淆） |
| 接口路径 | **不改**。`/app/account/profile` 这类路径是面向 C 端的，与表名无关 |
| RuoYi 后台 | 后台用户管理用 `sys_user`；如需在后台看 C 端用户，新建 `AppUserController` 查询 `app_user` |
| Flyway | 单独一个迁移文件：`V3_1__rename_user_to_app_user.sql` |

### 验收标准

- [ ] 未满 14 岁无法注册
- [ ] 14–18 岁需监护人确认
- [ ] 未成年人对话有更严的权限控制
- [ ] 表名已改为 `app_user`，且 `sys_user`（RuoYi）与 `app_user`（C 端）在文档中区分清晰
- [ ] MyBatis-Plus 实体与 Mapper 已同步改名

---

## F-3-24 文书后处理链缺失

### 问题

v2.2 的 10.1 生成了 Word 和 PDF，但**没有下载/分享/打印的出口**。用户拿到文书之后能干什么，文档没规划。

### 补全的后处理

| 功能 | 实现 |
|---|---|
| **下载 PDF** | `GET /app/documents/{id}/export?format=pdf` → 签名 URL → `expo-file-system` 下载到设备 |
| **下载 Word** | 同上 `format=docx`（用户可能需要自己改） |
| **保存到相册** | 仅图片格式；PDF 调系统分享 |
| **打印** | `expo-print` 调系统打印面板，A4 边距在 PDF 生成时已处理好 |
| **分享给律师** | 生成分享链接或直接发到 IM 会话（走 5.x 的文件消息） |
| **分享到微信** | 微信开放平台 SDK 分享文件（需确认微信是否支持 PDF 分享，若不支持则分享图片预览） |
| **文书模板回填** | 用户再次编辑时，用 `form_snapshot` 回填 |

### A4 边距规范

| 项 | 值 |
|---|---|
| 纸张 | A4（595 × 842 pt） |
| 上边距 | 72 pt（1 inch） |
| 下边距 | 72 pt |
| 左边距 | 90 pt（装订侧） |
| 右边距 | 72 pt |
| 正文 | 仿宋_GB2312 三号（16pt），行距 28pt |

> 司法文书有格式惯例（《党政机关公文格式》GB/T 9704-2012 可参考），建议找一份真实的起诉状样例对齐。

### 验收标准

- [ ] 文书可下载 PDF/Word
- [ ] 可分享到 IM 与微信
- [ ] 打印预览边距正确

---

## F-3-25 客服角色不在枚举中

### 问题

v2.2 的 16.4 说"在线客服复用 IM 组件"，但 `role` 枚举与 `sender_role` 枚举里**没有客服**。数据模型不支持这个角色。

### 修复

```sql
-- 用户角色枚举扩展
-- user / lawyer / admin / customer_service

-- IM 消息的 sender_role 扩展
-- user / lawyer / system / customer_service

ALTER TABLE conversation_message
  MODIFY COLUMN sender_role VARCHAR(20) NOT NULL
  COMMENT 'user / lawyer / system / customer_service';
```

### 客服会话的建模

客服会话与律师会话**不能共用 `(user_id, lawyer_id)` 的唯一键**——客服是多对多的。方案：

```sql
CREATE TABLE service_ticket (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT NOT NULL,
  agent_id     BIGINT,                    -- 客服坐席 ID，未分配时为空
  conversation_id BIGINT,                 -- 关联的 IM 会话（客服与用户单独建会话）
  category     VARCHAR(30),               -- consult / complaint / technical / other
  status       VARCHAR(20) NOT NULL DEFAULT 'waiting',  -- waiting / serving / closed
  closed_at    DATETIME,
  created_at   DATETIME NOT NULL,
  updated_at   DATETIME NOT NULL,
  KEY idx_ticket_status (status, created_at),
  KEY idx_ticket_agent (agent_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单';
```

客服与用户的会话用 `lawyer_id = 0` 或独立字段区分，**不参与律师会话的唯一键约束**。

### 验收标准

- [ ] 客服角色在枚举中
- [ ] 客服会话不违反 `(user_id, lawyer_id)` 唯一约束
- [ ] 工单可分配给坐席

---

## F-3-26 分享图片/二维码生成

### 问题

v2.2 的 7.x 提到"生成海报"，但没有方案。分享是获客的核心手段，必须做实。

### 方案

| 项 | 方案 |
|---|---|
| 技术 | 服务端用 **Java2D（`BufferedImage` + `Graphics2D`）** 合成海报，比前端 canvas 更可控 |
| 图层 | 背景图 + 头像（圆形裁剪）+ 姓名/律所 + 二维码 + 平台 logo |
| 二维码 | ZXing 生成，内容为短链（`https://faze.cn/s/{code}`），短链存表可统计扫码 |
| 缓存 | 同一律师的海报生成后缓存到 OSS，内容变更时失效 |
| 字体 | 服务器安装中文字体（思源黑体），否则 `Graphics2D` 出豆腐块 |

```sql
CREATE TABLE share_poster (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  biz_type    VARCHAR(30) NOT NULL,      -- lawyer / article / case
  biz_id      BIGINT      NOT NULL,
  oss_key     VARCHAR(500) NOT NULL,
  short_code  VARCHAR(20) NOT NULL,
  scan_count  BIGINT NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL,
  updated_at  DATETIME NOT NULL,
  UNIQUE KEY uk_poster_biz (biz_type, biz_id),
  UNIQUE KEY uk_poster_short (short_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享海报与短链';
```

### 验收标准

- [ ] 律师名片可生成海报
- [ ] 二维码可扫码跳转并统计
- [ ] 中文渲染正常（服务器字体已安装）

---

## F-3-27 性能基线缺失

### 问题

v2.2 没有性能目标，导致"什么算慢"没有标准，也无法验收。

### 基线指标（写进 19.4 验收标准）

| 指标 | 目标 | 测量方式 |
|---|---|---|
| App 冷启动到首屏可交互 | ≤ 2.5 秒（中端机） | Android Studio Profiler / 手动秒表 |
| 首页 tab 切换 | ≤ 300 ms | — |
| 列表滚动帧率 | ≥ 55 fps | Perf Monitor |
| 列表首屏数据加载 | ≤ 800 ms（4G） | 网络面板 |
| AI 首字返回 | ≤ 2 秒 | 从请求到第一个 delta |
| IM 消息往返 | ≤ 500 ms | 同机房 |
| APK 体积 | ≤ 60 MB（含字体） | `eas build` 产物 |
| 接口 P95 响应 | ≤ 500 ms | Actuator / 日志统计 |
| 数据库慢查询 | 0 条超过 1 秒 | 慢查询日志 |

### 关键：字体体积

四语言字体全内嵌约 3–3.5 MB（Noto Sans Tibetan 1.2–2 MB + Noto Sans Arabic 1–1.4 MB）。
**这是 APK 体积的主要增量**，如果超限，可考虑按语言动态下载字体（首次切换语言时下载）。

### 验收标准

- [ ] 每个迭代记录一次基线数据
- [ ] 有性能回归时能对比

---

## F-3-28 种子数据与回归数据方案

### 问题

v2.2 的 24 章只写了"种子数据"四个字。真实上线需要三套数据：**开发种子、测试种子、演示数据**，且要能一键重置。

### 三套数据

| 用途 | 内容 | 存放 |
|---|---|---|
| **开发种子** | 最小可运行数据：3 个用户、5 个律师、10 篇文章、20 条法条、2 个案例 | `database/seed/dev.sql` |
| **测试种子** | 覆盖边界：空列表、超长文本、四种语言各一份、各种订单状态各一条 | `database/seed/test.sql` |
| **演示数据** | 好看、量足：50 个律师、40 篇文章、完整对话记录、可观数据看板 | `database/seed/demo.sql` |

### 一键重置

```powershell
# scripts/reset-db.ps1
# 用途：重建库 + 跑 Flyway 迁移 + 灌种子
# 是否可重复执行：是（会先 DROP 再重建，仅限开发/测试环境）
# 危险：会清空数据，生产环境禁止执行
.\scripts\reset-db.ps1 -Env dev -Seed demo
```

脚本必须：

1. 先检查环境变量，**生产库名直接拒绝执行**
2. 打印目标主机、库名、要灌的种子文件
3. 执行前要求输入 `YES` 确认

### 法条数据

`law_item` 的 2000–3000 条不是"种子"，是**基础数据**，应作为 Flyway 迁移的一部分（`V2__init_law_items.sql`），随版本发布。

### 验收标准

- [ ] 三套种子数据齐备
- [ ] 一键重置脚本可用且有安全防护
- [ ] 法条基础数据随迁移发布

---

# 附：修复完成度核对表

| 编号 | 条目 | 方案 | DDL | 验收标准 |
|---|---|---|---|---|
| F-1-1 | 11 张表软删字段 | ✅ | ✅ | ✅ |
| F-1-2 | 关键索引 | ✅ | ✅ | ✅ |
| F-1-3 | favorite/follow 域归属 | ✅ | ✅ | ✅ |
| F-1-4 | article 主表结构 | ✅ | ✅ | ✅ |
| F-1-5 | case_library 拆翻译表 | ✅ | ✅ | ✅ |
| F-2-6 | user_device | ✅ | ✅ | ✅ |
| F-2-7 | sys_config | ✅ | ✅ | ✅ |
| F-2-8 | notify_template | ✅ | ✅ | ✅ |
| F-2-9 | user_violation | ✅ | ✅ | ✅ |
| F-2-10 | reconciliation_diff | ✅ | ✅ | ✅ |
| F-2-11 | invoice | ✅ | ✅ | ✅ |
| F-2-12 | file_record | ✅ | ✅ | ✅ |
| F-2-13 | lawyer_application_log | ✅ | ✅ | ✅ |
| F-2-14 | sensitive_word | ✅ | ✅ | ✅ |
| F-2-15 | login_log / operation_log | ✅ | ✅ | ✅ |
| F-3-16 | 业务缓存策略 | ✅ | — | ✅ |
| F-3-17 | 深色模式 | ✅ | — | ✅ |
| F-3-18 | 日期本地化 | ✅ | — | ✅ |
| F-3-19 | 术语 glossary | ✅ | ✅ | ✅ |
| F-3-20 | 敏感词改为标注 | ✅ | ✅ | ✅ |
| F-3-21 | AI 隐式标识 | ✅ | — | ✅ |
| F-3-22 | AI 禁用清单落拦截层 | ✅ | — | ✅ |
| F-3-23 | 未成年人机制 | ✅ | ✅ | ✅ |
| F-3-24 | 文书后处理链 | ✅ | — | ✅ |
| F-3-25 | 客服角色 | ✅ | ✅ | ✅ |
| F-3-26 | 分享海报/二维码 | ✅ | ✅ | ✅ |
| F-3-27 | 性能基线 | ✅ | — | ✅ |
| F-3-28 | 种子数据 | ✅ | ✅ | ✅ |

**合计 28 条，全部有方案、有验收标准；其中 22 条附带完整 DDL。**

---

# 附：v2.3 合入时的表变动清单

## 新增表（17 张）

| 分组 | 表名 |
|---|---|
| 社交 | `social_action`（替代 `favorite`）、`share_poster` |
| 多语言 | `case_library_translation`、`i18n_glossary` |
| 账户与设备 | `user_device`、`user_violation` |
| 运营 | `sys_config`、`notify_template`、`sensitive_word`、`content_review`、`service_ticket` |
| 交易 | `reconciliation_diff`、`invoice` |
| 基础设施 | `file_record`、`login_log`、`operation_log` |
| 审核 | `lawyer_application_log` |

## 重构表（3 张）

| 表 | 变动 |
|---|---|
| `article` | 补 `title` / `summary` / `content(JSON)` / `published_at` / `source_updated_at`；`content` 从 MEDIUMTEXT 改 JSON |
| `article_translation` | 移除 `zh-Hans` 行；补 `translator` / `translation_engine` / `translated_from` |
| `case_library` | 拆为 `case_library` + `case_library_translation`；补 `law_refs` / `source` / `source_updated_at` |

## 改动表（15 张）

`realname_auth`、`enterprise_auth`、`refund_record`、`review`、`notification`、`complaint`、`feedback`、`lawyer`、`lawyer_application`、`document`、`conversation_message`、`payment_record`、`signature`、`app_user`（原 `user`）、`withdraw_record`

## 表总数

| 阶段 | 表数 | 说明 |
|---|---|---|
| v2.1 | 30 | 原始设计 |
| v2.2 新增 | +5 | `user_balance`、`lawyer_balance`、`balance_flow`、`recharge_record`、`message_offline` |
| v2.2 小计 | **35** | |
| v2.3 新增 | +17 | 见上 |
| v2.3 移除 | −1 | `favorite` 并入 `social_action` |
| **v2.3 合计** | **51** | 35 + 17 − 1 |

> **注意**：v2.1 声称的「30 张表」这个数字本身未经逐表核对。落地时应以 Flyway 迁移文件的**实际建表语句**为准，不要沿用文档里的数字。

---

# 附：落地顺序建议

这 28 条不要在同一个迁移里改完，按依赖关系分四批：

| 批次 | 内容 | 原因 |
|---|---|---|
| **第 1 批** | F-1-1（软删字段）、F-1-2（索引）、F-2-15（日志表） | 纯增量，无依赖，先做 |
| **第 2 批** | F-1-3（social 域）、F-1-4（article）、F-1-5（case_library） | 涉及数据迁移，需要在有真实数据前完成 |
| **第 3 批** | F-2-6~14（其余新增表） | 依赖第 2 批的域结构 |
| **第 4 批** | F-3-16~28（非 DDL 部分） | 与数据库解耦，可并行 |

每批一个 Flyway 版本文件：

```text
V3__add_soft_delete_and_indexes.sql     -- 第 1 批
V4__refactor_social_and_content.sql     -- 第 2 批
V5__add_supporting_tables.sql           -- 第 3 批
-- 第 4 批不涉及 DDL，走代码与配置
```
