# 法泽 FAZE 数据表规范（V1.0）

> **这份文档是什么**：所有建表、改表、写迁移时的**共性规则**，以及**表清单的准确口径**。回答「这张表该叫什么、有哪些字段、索引怎么建、能不能软删、迁移怎么排」。
>
> **不回答**：每张表的字段级定义（那是技术路线 §5.3~§5.11 的 DDL，建表时按本规范修正后落地）。
>
> **上位文档**：《法泽FAZE_技术路线_v2.3.md》§五（51 条 DDL）。
> **取代**：技术路线 §5.1「表清单」、「§5.2 通用约定」的文字条款、以及 §5.10 里的 `login_log` / `operation_log` 两段。
> **定稿日期**：2026-10-30
> **定位**：与《法泽FAZE_错误码规范_v1.0.md》同级 —— 那份定「错误怎么表达」，这份定「数据怎么落库」。

---

# 一、先解决三个既有冲突（不裁决，建表就会撞墙）

## 1.1 表数量：文档说 51，实际唯一 49，清单只列了 30

| 口径 | 值 | 来源 |
|---|---|---|
| 文档声称 | 51 张 | 技术路线 §5.1 标题、`docs/README.md`、V1 基线表结构头部 |
| `CREATE TABLE` 语句数 | **51 条** | 技术路线全文（实测） |
| **唯一表名** | **49 张** | `login_log`、`operation_log` 各被定义两遍 |
| §5.1 表清单行数 | **30 行** | 技术路线 L986–L1015 |
| V2.3 增量说法 | 17 张 / 15 张 / 15 张 | §5.1 正文、§5.11 标题、V2.3 变更记录 —— **三处不同** |

**裁决**：**以本文件附录 A 的 49 张为准**。§5.1 的 30 行清单作废（它里面还有 3 个已改名或已合并的表名：`case` → `legal_case`、`favorite` / `follow` → `social_action`；另有 31 张表从未进过清单）。

**要建的是 48 张**：`invoice`（发票）按总纲「V1 明确不做」不建表；若要建，按本规范补建，编号顺延不插号。

> 为什么这个数必须现在定死：段 1 一次要建 12 张表，按旧清单排会漏掉 `user_device`、`user_violation`、`lawyer_application_log`、`sys_config`、`login_log`、`operation_log` 这 6 张（它们都不在 §5.1 的 30 行里）。

## 1.2 `login_log` / `operation_log` 有两版 DDL：以 §5.11.5 为准

| 对比项 | §5.10 版（L1757 / L1771） | §5.11.5 版（L2048 / L2065） | 裁决 |
|---|---|---|---|
| `login_log` 记谁 | `user_id` + `phone` | `user_id` + `account` | **§5.11.5**：登录失败时 `user_id` 为空，必须靠 `account` 记下输入的账号 |
| 登录方式 | 无 | `login_type`（密码/短信/刷新） | **§5.11.5**：三种登录方式的失败率要分开看 |
| 结果字段 | `success TINYINT` | `result VARCHAR(20)` | **§5.11.5**：结果不止成功/失败，还有锁定、验证码错；且与 `operation_log.result` 统一 |
| 设备 | `device` + `platform` | `user_agent` + `device_id` | **§5.11.5**：`device_id` 能关联 `user_device` 拿平台与推送标识 |
| 索引 | 1 个单列 | 3 个「账号/用户/IP + 时间」复合 | **§5.11.5**：真实查询都是「按 X 查最近的登录」 |
| `operation_log` 记谁 | `user_id` | `operator_id` + `operator_type` | **§5.11.5**：操作人可能是 C 端用户、律师、管理员，一个 `user_id` 分不清 |
| 操作对象 | 无 | `target_type` + `target_id` | **§5.11.5**：这是审计四要素里最关键的一环，旧版完全没有 |
| 变更内容 | `request_param TEXT` 原样存请求体 | `detail JSON` | **§5.11.5**：旧版会把密码、验证码、身份证号原样落库，与脱敏红线直接冲突 |
| 性能字段 | `method` / `request_url` / `cost_ms` | 无 | 这些是**访问日志**的职责，走 Filebeat→ES（技术路线 §17.3），不该进 MySQL 审计表 |

**裁决**：
1. **`login_log` / `operation_log` 以 §5.11.5 的两段为准**，字段照抄，不再改。
2. §5.10 里的那两段**作废**（本文档发布时在技术路线对应位置加作废标记），不删正文以免章节断裂。
3. 结论：`operation_log` **不存请求体原文**。需要排查请求参数时看访问日志（ES），审计表只存结构化摘要。

## 1.3 「审计四要素」此前从未定义过

总纲 §5.1 要求段 0 交付「审计四要素」，但**全库检索 0 命中定义**（这个词是从旧计划继承的，旧计划已删）。现在定义：

| 要素 | 字段 | 说明 |
|---|---|---|
| **谁** | `operator_id` + `operator_type` | `operator_type` 取值：`app_user` / `lawyer` / `sys_user`（管理员） |
| **何时** | `created_at` | UTC 存，出参转 +08:00 |
| **从哪** | `ip` | 客户端 IP；网关转发时取 `X-Forwarded-For` 第一段 |
| **做了什么** | `module` + `action` + `target_type` + `target_id` + `detail` | `action` 用动词短语（`recharge` / `ban` / `refund_approve` / `law_item_update`），`detail` 只存变更前后差异，不存全量对象 |

**必须写 `operation_log` 的动作清单**（不写等于没做审计）：登录失败 5 次锁定、手工充值、调账、封禁/解封、律师入驻审核、实名审核、提现审核与出款、退款审批、法条修改与失效、敏感词增删改、系统参数修改、律师清退、内容强制下线。

---

# 二、表名与字段命名

## 2.1 表名

| 规则 | 说明 | 怎么检查 |
|---|---|---|
| **小写 snake_case** | 全部小写，单词间单下划线 | 正则 `^[a-z][a-z0-9_]*$` |
| **单数** | `order` 不是 `orders`、`lawyer` 不是 `lawyers` | 人工判断；例外见下 |
| **不加业务前缀** | `app_user` 而非 `faze_user`；库名已隔离 | 前缀只允许 `sys_`（见下） |
| **`sys_` 只给框架表** | `sys_config` 是业务参数，**允许**沿用；RuoYi 的 `sys_user`/`sys_menu` 在 `faze_admin` 库，不与业务库同名 | 建表时确认库 |
| **长度 ≤ 32** | 便于索命名与实体映射 | 计数 |
| **不用 SQL 保留字** | `order`、`group`、`key`、`desc` 等不能裸用 | 建表前查保留字表 |

**唯一例外**：`orders`（复数）。原因：`order` 是 SQL 保留字，加反引号能建但到处要转义，风险大于收益。**已建的 49 张表一律不改名**（改名会波及实体类、Mapper、接口路径、PC 页字段，收益不抵风险）；`orders` 登记为唯一例外，**新表不得再出现复数名**。

## 2.2 字段名

| 规则 | 说明 |
|---|---|
| **小写 snake_case** | 同表名 |
| **主键固定 `id`** | 不叫 `xxx_id` |
| **外键 `<单数表名>_id`** | `user_id`、`lawyer_id`、`order_id`（注意：`orders` 表的外键也叫 `order_id`，不带复数） |
| **时间字段 `_at` 后缀** | `created_at`、`updated_at`、`deleted_at`、`paid_at`、`signed_at` |
| **布尔 `is_` / `has_` / `can_` 前缀** | `is_minor`、`is_private`、`has_signed`；**不允许**裸 `enabled` / `success` / `passed` |
| **金额 `_cents` 后缀** | `amount_cents`、`balance_cents`、`delta_cents`；**不允许**裸 `amount` / `balance` |
| **状态字段 `status`** | 不叫 `state` / `phase` / `stage` |
| **类型字段 `_type` 后缀** | `login_type`、`operator_type`、`target_type`、`biz_type` |
| **禁止拼音、禁止缩写歧义** | `shoujihao`、`addr1` 都不行；`msg`、`desc`、`img` 这类通用缩写允许 |
| **不使用 MySQL 保留字** | `desc`、`status`（合法）、`order`、`key` 注意 |

## 2.3 索引名

**规则：`idx_<完整表名>_<完整字段名>`，联合索引按字段顺序追加；唯一索引前缀 `uk_`。**

| 场景 | 正确 | 错误（现存的） |
|---|---|---|
| 单列 | `idx_app_user_phone` | `idx_loginlog_user`（用了缩写） |
| 联合 | `idx_orders_user_id_created_at` | `idx_oplog_user`（既缩写又缺字段） |
| 唯一 | `uk_app_user_phone` | `uk_lawyer_user`（缺字段名） |
| 全文 | `ft_law_item_content` | — |

**为什么要求机械推导**：表名含下划线时（`lawyer_application`），`idx_lawyer_application_user` 无法判断切分点。用**完整表名 + 完整字段名**拼接，任何索引名都能反推回「哪张表哪个字段」，也能写脚本校验。

**长度上限 64**（与 MySQL 一致）。超出时说明表名或字段名过长，先改名而不是缩索引名。

---

# 三、字段与类型

## 3.1 主键

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT
```

- 全库统一，**不用雪花 ID、不用 UUID**。理由：单库单表、无分片计划；雪花给未来分库留的口子，现在付的是「不可读、索引大、联合主键难」的成本。
- **对外暴露的编号用业务单号，不暴露 `id`**（见 3.11）。
- 追加型流水表**也用自增 `id`**，不搞无主键表。

## 3.2 外键：不建物理外键

**裁决：全部表不建 `FOREIGN KEY` 约束。**

理由（按重要性）：
1. **分库**：业务库 `faze` 与 RuoYi 的 `faze_admin` 分开，跨库外键本来就不成立；
2. **软删**：外键与 `deleted_at` 语义冲突——父记录被软删后子记录的约束还在；
3. **迁移**：建表顺序变成硬依赖，段与段之间会被迫互相等待；
4. **批量**：删父记录前要先删子记录，后台的批量操作会变成连锁删除。

**代偿措施（必须有，否则等于放弃完整性）**：
- 所有 `xxx_id` **必须建索引**（见 §5.2）；
- 字段注释里**写明引用哪张表**，例如 `COMMENT 'app_user.id'`；
- 跨表一致性由 **Service 层 + 定时巡检**保证：每段收口时跑一次孤儿检查（如「`orders.user_id` 不存在于 `app_user` 的记录数 = 0」）。

## 3.3 时间字段：应用层填充，DB 不设默认值

| 规则 | 值 |
|---|---|
| 字段 | `created_at DATETIME NOT NULL`、`updated_at DATETIME NOT NULL` |
| **谁填** | **应用层**（MyBatis-Plus `MetaObjectHandler` 自动填充），**不用** `DEFAULT CURRENT_TIMESTAMP` |
| 为什么 | 时区统一由 JVM 控制；DB 服务器的 `time_zone` 不可控，一旦容器化就是隐性 bug 源。技术路线里 `CURRENT_TIMESTAMP` 出现 **0 次**，本来就没人打算用 DB 默认值 —— 现在把它写成规则 |
| `updated_at` 是否 `ON UPDATE` | **不用**。同上，由应用层更新；`ON UPDATE` 在「只改审计字段」的场景会误触发 |
| 精度 | `DATETIME`（秒）。需要毫秒的场景（IM 消息排序）用单独的 `seq` 列，不靠时间 |
| 时区 | **存 UTC**，接口出参 ISO-8601 带偏移（见技术路线 §4.4、《V1 基线表结构》§5.1） |
| 只存日期 | `DATE`，如 `birth_date` |

## 3.4 金额

```sql
amount_cents BIGINT NOT NULL COMMENT '金额，单位：分'
```

- **一律整数分，禁止 `FLOAT` / `DOUBLE` / `DECIMAL`**（除非是比率）。
- 比率（抽成比例、评分）用 `DECIMAL(4,2)` / `DECIMAL(2,1)`，并在注释里写明是比率不是金额。
- **字段名必须带 `_cents`**。现有 `balance_after`（无后缀）登记为偏差，建表时改为 `balance_after_cents`。

## 3.5 状态与枚举

| 规则 | 说明 |
|---|---|
| 类型 | `VARCHAR(20)` 存**枚举名**（如 `pending` / `accepted` / `completed`），不用数字 |
| 为什么 | 数字状态在数据库里不可读，排查时要翻代码；枚举名改名成本可控（一次性 UPDATE） |
| 枚举全集写在哪 | 该表的**表注释**里必须列出全部取值；同时在服务端有对应的 Java `enum`，两者逐字一致 |
| 新增取值 | **不需要迁移**（VARCHAR 不用改表），但必须同步改表注释与 enum，并在 CHANGELOG 记一句 |
| 状态机 | 合法迁移在技术路线 §9.2 等处定义；本规范只管存储类型，不管迁移规则 |

**TINYINT 只用于布尔**，不用于状态（见 3.6）。

## 3.6 布尔

```sql
is_minor TINYINT NOT NULL DEFAULT 0 COMMENT '是否未成年：0 否 1 是'
```

- 类型 `TINYINT NOT NULL DEFAULT 0`，**不用 `BOOLEAN`**（本质是 TINYINT(1)，但容易误用 `true`/`false` 字面量）。
- 字段名必须带 `is_` / `has_` / `can_` 前缀。
- 注释必须写 `0 x / 1 y` 的对应关系。
- 现有 `enabled`、`success`、`safety_passed`、`is_private` 不一致 → 建表时按规范改为 `is_enabled` / `is_success` / `is_safety_passed`（`is_private` 已合规）。

## 3.7 字符串长度标准表

**同一语义在全库只能有一种长度**，否则「谁超长谁错」无法判定：

| 语义 | 类型 | 长度 | 说明 |
|---|---|---|---|
| 手机号 | `VARCHAR(20)` | 20 | 国际号含 `+86` 最多 14 位，留余量 |
| 身份证号 | `VARCHAR(64)` | 64 | **加密后存储**，base64 约 44 位，留余量 |
| IP 地址 | `VARCHAR(45)` | 45 | IPv6 最长 45 |
| 姓名 / 昵称 | `VARCHAR(50)` | 50 | |
| 标题 | `VARCHAR(200)` | 200 | |
| URL（非 OSS） | `VARCHAR(500)` | 500 | |
| `oss_key` | `VARCHAR(500)` | 500 | |
| 文件名 | `VARCHAR(255)` | 255 | |
| 业务单号 | `VARCHAR(32)` | 32 | 见 3.11 |
| 哈希（hex） | `VARCHAR(64)` | 64 | SHA-256 |
| 状态枚举 | `VARCHAR(20)` | 20 | |
| 类型枚举 | `VARCHAR(30)` | 30 | |
| `module` / `action` | `VARCHAR(50)` | 50 | |
| 短文本（描述） | `VARCHAR(200)` | 200 | |
| 长文本（正文） | `MEDIUMTEXT` | — | 见 3.8 |

## 3.8 大字段

| 类型 | 何时用 | 上限 |
|---|---|---|
| `VARCHAR(n)` | 长度可预期且 ≤ 500 | — |
| `TEXT` | 长度不可预期，≤ 64KB | 日志详情、短正文 |
| `MEDIUMTEXT` | 正文类（文章、法条、AI 回答），≤ 16MB | 法条内容、文章正文 |
| **不用 `LONGTEXT` / `BLOB`** | 文件走 OSS，不进口库 | — |

## 3.9 JSON

- 用 MySQL 8 原生 `JSON` 类型，不用 `TEXT` 存 JSON 字符串。
- **JSON 字段不允许作为主要查询条件**。确实要查的（如敏感词命中列表），要么建生成列 + 索引，要么拆表。
- 每个 JSON 字段的**结构必须写在该字段注释里**（键名 + 类型），否则三个月后没人知道里面是什么。

## 3.10 OSS 资源字段

**裁决：业务表一律存 `oss_key`，不存完整 URL。**

- 字段名用 `<用途>_oss_key`（`avatar_oss_key`、`license_oss_key`），不用 `_url` / `_image`。
- **需要 URL 时由服务端拼**（CDN 域名 + key），换域名不用改库。
- 私密文件（身份证影像、签名、文书 PDF）额外标记：`is_private TINYINT`，只能走签名 URL 访问，且有效期 ≤ 10 分钟。
- 现有 DDL 里的 `avatar_url`、`license_image`、`id_card_front`、`file_url` 等登记为偏差（附录 C），建表时按规范改。

## 3.11 业务单号

| 项 | 规定 |
|---|---|
| 字段 | `xxx_no VARCHAR(32) NOT NULL` + `UNIQUE KEY uk_<表>_xxx_no` |
| 谁能看到 | **对外只暴露单号，不暴露 `id`**（客服、对账、用户截图都按单号走） |
| 长度上限 | 32 |
| 字符集 | 大写字母 + 数字，无分隔符（避免用户抄错、避免 URL 转义） |
| 生成时机 | **落库前生成**（在同一事务内），不靠 DB 自增后再回填 |
| 唯一性保障 | 唯一索引；插入冲突时重试（最多 3 次） |
| **格式** | **待定，需项目负责人拍板**（技术路线里的 `FAZE-2026-0087` 只是占位）。技术侧先按下面的建议方案实现，改格式时只改一处： |

**建议格式**（待确认）：`<业务前缀 2 位><yyyyMMdd 8 位><序号 6 位>`

| 业务 | 前缀 | 示例 |
|---|---|---|
| 订单 | `OD` | `OD20261030000123` |
| 案件 | `CS` | `CS20261030000007` |
| 充值 | `RC` | `RC20261030000045` |
| 提现 | `WD` | `WD20261030000003` |
| 退款 | `RF` | `RF20261030000011` |

> 序号用「当日递增」需要额外计数表；若不想维护计数表，改用「时间戳毫秒 + 3 位随机」也可，但**必须保证长度 ≤ 32 且唯一索引能兜住**。这一条等你定。

---

# 四、四类表与公共字段

## 4.1 四类定义（原 §5.2 的三类 + 补第四类）

技术路线 §5.2 定了三类，但实际有 7 张表落不进任何一类（`file_record`、`law_item`、`sys_config`、`i18n_glossary` 等）。补第四类：

| 类别 | 特征 | 必须字段 | 举例 |
|---|---|---|---|
| **A 业务实体表** | 记录会被人删除或撤销 | `created_at` + `updated_at` + `deleted_at` | `app_user`、`lawyer`、`orders`、`review`、`notification` |
| **B 追加型流水表** | 只插入，不修改不删除，是证据 | 只有 `created_at` | `balance_flow`、`conversation_message`、`login_log`、`operation_log`、`case_progress` |
| **C 状态机表** | 靠 `status` 走生命周期，一般不删 | `created_at` + `updated_at` + `status` | `legal_case`、`refund_record`、`withdraw_record`、`service_ticket` |
| **D 基础数据 / 配置表** | 平台维护的字典、模板、法条，运行期读多写少 | `created_at` + `updated_at` + `is_enabled`（停用而非删除） | `law_item`、`sys_config`、`sensitive_word`、`i18n_glossary`、`faq`、`doc_template` |

**B 类为什么不做软删**：这些表是证据链。给流水加 `deleted_at` 等于允许删证据，与法律业务的要求相反。

## 4.2 判定流程（4 个是非题，按顺序问）

1. 这张表记录的是**平台维护的配置/字典/模板**吗？→ 是 → **D 基础数据**
2. 这张表只会**插入**，从不 UPDATE，也从不删吗？→ 是 → **B 追加型**
3. 这张表的生命周期由 `status` 表达，且**不允许用户物理删除**吗？→ 是 → **C 状态机**
4. 以上都不是 → **A 业务实体**

> 49 张表的分类结果见附录 A，不必每次重判。

## 4.3 软删与唯一键的冲突（必须提前想清楚）

软删表里如果存在唯一键，**软删后那条记录仍占着唯一值**。典型坑：`app_user` 注销后，`uk_app_user_phone` 会让同一个手机号**无法重新注册**。

**规则**：

| 场景 | 做法 |
|---|---|
| 手机号、身份证号等**需要复用**的唯一键 | 不用「`deleted_at` + 唯一索引」，改为 **`deleted_at` + `phone_active_flag` 的联合唯一索引**（`phone` + `is_active`，`is_active` 为 1 表示在用） |
| **不需要复用**的唯一键（单号、订单号） | 保持单纯唯一索引 |
| 折中做法 | 注销时把 `phone` 改写成 `phone_del_<原值>`，保留可追溯、释放唯一值 |

**裁决**：采用**折中做法**（改写被删记录的键值）。理由：不需要额外字段、不需要改索引、对现有 DDL 改动最小；代价是被删记录的原值要到 `operation_log` 或历史表查。

> 这一条必须和第 1 段「注销后手机号可重新注册」的验收锚点一起实现，否则验收直接失败。

---

# 五、索引

## 5.1 必建索引（无条件）

1. **主键**（自增，天然有）
2. **所有 `xxx_id` 外键字段**（即使不建外键约束，索引也要建）
3. **所有唯一业务键**（`uk_` 前缀）
4. **所有 `deleted_at`**（软删表的列表查询默认带 `deleted_at IS NULL`）
5. **状态字段**（列表页按状态筛选是最高频查询）

## 5.2 联合索引

- 列表页的查询按 **`(筛选字段, 排序字段)`** 建联合索引，遵循最左前缀；
- **顺序规则**：等值筛选在前，范围筛选在后，排序列放最后；
- 单表索引**不超过 6 个**（含主键与唯一键），超出说明查询设计有问题，先回头看 SQL；
- 每个联合索引都要能说出「它服务哪个接口的哪个查询」，说不出来的不建。

## 5.3 全文索引

`law_item` 的 `content` 用 **ngram 全文索引**：

```sql
FULLTEXT KEY ft_law_item_content (content) WITH PARSER ngram
```

- `ngram_token_size = 2` 是**只读参数**，不改 `my.ini`（改了要重启服务）；
- 该索引依赖 `utf8mb4_0900_ai_ci` 排序规则，因此**表级必须显式写 `COLLATE`**（见 §6.2）；
- 已实测：MySQL 8.0.44 + `utf8mb4_0900_ai_ci` 下可建，`EXPLAIN` 确认走索引；双字能命中、单字命中 0 条。

---

# 六、字符集、排序规则与时区

## 6.1 库级（建库时定，不可改）

| 项 | 值 |
|---|---|
| 库名 | `faze`（RuoYi 用 `faze_admin`，**分库**） |
| 字符集 | `utf8mb4` |
| 排序规则 | `utf8mb4_0900_ai_ci` |
| 引擎 | `InnoDB` |
| 应用账号 | `faze_app`@`localhost`，只授权 `faze.*`，**不用 root 跑应用** |

## 6.2 表级：`COLLATE` 必须显式写

**这是硬要求**，因为技术路线里 `COLLATE` 出现 **0 次**，全部依赖服务器默认值。一旦部署环境的默认排序规则不是 `0900_ai_ci`，`law_item` 的 ngram 索引和所有唯一键的比较行为都会变。

```sql
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='...';
```

## 6.3 时区

- 库内 `DATETIME` 存 **UTC**；
- 服务端写入用 `LocalDateTime.now(ZoneOffset.UTC)`；
- 接口出参转 **ISO-8601 带 `+08:00`**；
- 前端展示按设备时区（当前只服务新疆，默认 `Asia/Shanghai`）；
- **禁止**用 `TIMESTAMP`（会随 `time_zone` 变的隐式转换，是排查噩梦）。

---

# 七、注释

## 7.1 表注释（必须有）

```sql
COMMENT='订单主表；状态枚举：created/paid/accepted/completed/cancelled/refunded'
```

- 每张表必须有表注释；
- **状态机表与基础数据表**的表注释里必须列出枚举全集（见 3.5）；
- 追加型表注明「仅追加」；软删表注明软删字段。

## 7.2 字段注释（每个字段必须有）

- 每列都有 `COMMENT`；
- 布尔写 `0 否 / 1 是`；
- 金额写单位「分」；
- 外键写 `引用表.字段`；
- JSON 写结构（键名 + 类型）。

## 7.3 迁移文件头

每个 `V*.sql` 文件开头必须写：

```sql
-- ============================================================
-- 迁移：V103__create_balance_flow.sql
-- 所属段：第 1 段（账务内核）
-- 做什么：建 balance_flow + user_balance + lawyer_balance
-- 依赖：V101__create_app_user.sql（balance_flow.user_id 引用 app_user.id）
-- 可回滚：是（表为空时可 DROP；已有数据后只能追加补丁）
-- 编写：<日期>
-- ============================================================
```

---

# 八、Flyway 迁移

## 8.1 版本号分段

| 段 | 版本号区间 | 说明 |
|---|---|---|
| 第 0 段 | `V1` ~ `V99` | `V1__baseline.sql`（app_user + law_item）、`V2__init_law_items.sql` |
| 第 1 段 | `V100` ~ `V199` | |
| 第 2 段 | `V200` ~ `V299` | |
| … | 每段 +100 | |
| 第 8 段 | `V800` ~ `V899` | |
| 修正补丁 | `V{原版本}_{n}__fix_xxx.sql` | 如 `V101_1__fix_app_user_is_minor.sql` |

## 8.2 迁移能不能改：以「是否已进 main」为准

这是本文档要裁决的第二个冲突：

| 说法 | 出处 | 结论 |
|---|---|---|
| 「迁移不可变，只能追加补丁」 | 《V1 基线表结构》§六 | 对**已发布**的迁移成立 |
| 「段内可回滚，允许修正迁移」 | 开发计划总纲 §2.2 第 5 条、§6.4 | 对**未发布**的迁移成立 |

**裁决（按分支判定，机械可执行）**：

| 迁移文件状态 | 允许的操作 |
|---|---|
| **只在 feature 分支 / 本地**，未合入 `main` | 允许直接改、允许 `flyway clean` 重建库 |
| **已合入 `main`**（其他人可能已拉取） | **不可改**，只能追加 `V{x}_{y}__fix_*.sql` |
| **已在 test / 生产环境执行过** | **不可改**，且不允许 `flyway clean` |

- `flyway.cleanDisabled` 在生产配置里必须为 `true`；
- 开发环境的 `flyway clean` 只能用于**本地**库，且脚本要单独命名（`reset-db.ps1`），不允许混在启动流程里。

## 8.3 DDL 与数据迁移分文件

- 一个迁移文件**只做一件事**：建表 / 加列 / 建索引 / 灌数据，四者不混；
- 灌数据的迁移只能用 `INSERT ... ON DUPLICATE KEY UPDATE` 或先 `DELETE` 再 `INSERT`，**必须可重复执行**；
- 结构变更与大表数据回填分开（回填用定时任务或分批脚本，不在迁移里跑全表 UPDATE）。

## 8.4 种子数据

| 类型 | 放哪 | 进 Flyway 吗 | 可重复执行 |
|---|---|---|---|
| **最小必要数据**（法条 5 条、系统参数默认值） | `V2__init_law_items.sql` | **进**（不可变） | 否（迁移只跑一次） |
| **全量基础数据**（法条 2000–3000 条） | `database/seed/law_items_full.sql` | **不进** | 是 |
| **开发/演示数据** | `database/seed/dev.sql` / `demo.sql` | 不进 | 是 |
| **测试数据** | `database/seed/test.sql` | 不进 | 是 |

**裁决**：`V2__init_law_items.sql` 只放 **5 条**（够段 0 探针用）。全量 2000–3000 条走 `database/seed/law_items_full.sql`。

理由：迁移不可变，把 2000 条塞进 `V2` 以后就没法改（法条修订、错字纠正都会变成补丁地狱）；而种子数据天然要迭代。

---

# 九、审计与留痕

## 9.1 哪些表要审计字段

| 类别 | `created_at` / `updated_at` | `deleted_at` | `created_by` + `created_by_type` |
|---|---|---|---|
| A 业务实体 | ✅ | ✅ | **看情况**（见下） |
| B 追加型 | 只有 `created_at` | ❌ | ❌（靠 `operation_log` 追溯） |
| C 状态机 | ✅ | ❌ | **看情况** |
| D 基础数据 | ✅ | ❌（用 `is_enabled`） | ✅（谁改的配置必须可查） |

**「看情况」的判据**：这张表的记录**由谁创建**是否需要事后追责或运营统计？
- 需要 → 加 `created_by BIGINT` + `created_by_type VARCHAR(20)`（取值同 `operator_type`）
- 不需要（如 `notification` 由系统生成）→ 不加

**已确定要加的**：`article`、`law_item`、`doc_template`、`case_library`、`faq`、`sensitive_word`、`sys_config`、`notify_template`、`lawyer_application`、`realname_auth`、`enterprise_auth`。

## 9.2 敏感字段清单（字段级，此前只有三类笼统表述）

| 等级 | 处理 | 字段 |
|---|---|---|
| **L1 加密存储** | AES-256-GCM（Jasypt 密钥） | `realname_auth.id_card_number`、`realname_auth.real_name`、`enterprise_auth.legal_person_id_card` |
| **L2 脱敏展示** | 接口出参脱敏（`138****8888`、`110***********1234`），**完整值只在审核页可见** | `app_user.phone`、`realname_auth.id_card_number`、`realname_auth.real_name` |
| **L3 私有 OSS** | 私有桶 + 签名 URL（≤10 分钟） | `realname_auth.id_card_oss_key`（正反面）、`enterprise_auth.license_oss_key`、`signature.sign_oss_key`、`document.pdf_oss_key` |
| **L4 禁止入日志** | 日志、错误上报、APM 一律不得出现 | 密码、验证码、token、支付密码、`id_card_number`、`conversation_message.content`、`ai_message.content`、`signature` 笔迹、`payment_record.raw_payload` |
| **L5 仅审计可查** | 只有 `operation_log` 与审核页能看 | 封禁原因、驳回理由、投诉原文 |

**裁决：手机号不加密**。
技术路线 §6.4 写「身份证号、手机号加密存储」，但手机号是登录账号 + 唯一索引 + 需要模糊搜索，加密后会破坏这三样。改为：**手机号明文存库**（靠库权限、备份加密、网络隔离保护），**接口出参一律脱敏**，**日志禁止出现**。身份证号不需要按它查询，所以走 L1 加密。

## 9.3 `operation_log` 写入位置

- **不在 Controller 切面里统一写**（切面拿不到「改了哪个对象」「改动前后差异」）；
- 在 **Service 层的敏感操作完成后显式调用** `OperationLogService.record(...)`，与业务在同一事务内；
- 写失败**不回滚业务**（审计失败不应导致用户充值失败），但要记 error 日志并告警。

---

# 十、建表前检查清单（12 问，逐条过一遍）

- [ ] 1. 表名：小写 snake_case、单数、≤32、非保留字、不是 `sys_` 前缀（除非确实是框架表）
- [ ] 2. 主键：`id BIGINT PRIMARY KEY AUTO_INCREMENT`
- [ ] 3. 类别：A/B/C/D 已判定，公共字段齐全（`created_at` 必有；A/C 有 `updated_at`；A 有 `deleted_at`）
- [ ] 4. 表选项：`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`
- [ ] 5. 表注释：非空；状态机/基础数据表列出枚举全集
- [ ] 6. 字段注释：每列都有；布尔写 0/1、金额写「分」、外键写引用表、JSON 写结构
- [ ] 7. 命名：布尔带 `is_`、金额带 `_cents`、时间带 `_at`、外键 `<表>_id`
- [ ] 8. 类型：金额 `BIGINT`、状态 `VARCHAR(20)`、时间 `DATETIME`、JSON 原生类型、无 `FLOAT`/`BLOB`
- [ ] 9. 索引：外键字段、唯一键、`deleted_at`、状态字段各有一索引；索引名可机械推导；总数 ≤ 6
- [ ] 10. 软删冲突：若有唯一键且值需复用，确认已按 §4.3 处理（注销改写键值）
- [ ] 11. 敏感字段：对照 §9.2 确认本表涉及的等级与处理方式
- [ ] 12. 迁移文件：版本号落在本段区间、文件头五段齐全、只做一件事、可重复执行

---

# 附录 A：表清单（49 张，重算）

> 「建表段」是这张表在哪一段交付（与《开发计划总纲》§五 的段边界一致）。`invoice` 按总纲「V1 明确不做」不建表。
> 类别：A 业务实体 / B 追加型 / C 状态机 / D 基础数据。

| # | 表名 | 类别 | 字段数 | 域 | 建表段 | 用途 |
|---|---|---|---|---|---|---|
| 1 | `app_user` | A | 19 | account | 段 0 | C 端用户主表；律师身份在 lawyer 扩展 |
| 2 | `lawyer` | A | 23 | lawyer | 段 1 | 律师扩展表 |
| 3 | `lawyer_application` | C | 16 | lawyer | 段 1 | 入驻申请，四步流程 |
| 4 | `realname_auth` | A | 12 | account | 段 1 | 实名认证 |
| 5 | `enterprise_auth` | A | 13 | account | 段 1 | 企业认证 |
| 6 | `conversation` | A | 10 | conversation | 段 2 | IM 会话 |
| 7 | `conversation_message` | B | 15 | conversation | 段 2 | IM 消息，证据链 |
| 8 | `message_offline` | B | 6 | conversation | 段 2 | 离线消息队列；推送成功后清理 |
| 9 | `orders` | A | 17 | trade | 段 2 | 订单主表（复数名，唯一例外） |
| 10 | `payment_record` | B | 9 | trade | 段 2 | 支付记录 |
| 11 | `refund_record` | C | 11 | trade | 段 2 | 退款单 |
| 12 | `review` | A | 11 | trade | 段 2 | 评价 |
| 13 | `withdraw_record` | C | 15 | trade | 段 5 | 律师提现申请 |
| 14 | `user_balance` | A | 7 | trade | 段 1 | 用户余额缓存（balance_flow 为真相） |
| 15 | `lawyer_balance` | A | 8 | trade | 段 1 | 律师余额缓存 |
| 16 | `balance_flow` | B | 10 | trade | 段 1 | 流水，唯一真相 |
| 17 | `recharge_record` | C | 11 | trade | 段 6 | 充值单 |
| 18 | `doc_template` | D | 12 | document | 段 4 | 文书模板 |
| 19 | `document` | C | 17 | document | 段 4 | 文书实例 |
| 20 | `signature` | B | 15 | document | 段 4 | 签章笔迹，敏感 |
| 21 | `legal_case` | C | 14 | caseflow | 段 5 | 案件 |
| 22 | `case_progress` | B | 8 | caseflow | 段 5 | 进度节点，只追加 |
| 23 | `article` | A | 14 | content | 段 2 | 文章，简体源内容 |
| 24 | `article_translation` | A | 12 | content | 段 2 | 非中文译文 |
| 25 | `case_library` | A | 19 | content | 段 5 | 案例库主表 |
| 26 | `case_library_translation` | A | 13 | content | 段 5 | 案例译文 |
| 27 | `law_item` | D | 12 | content | 段 0 | 法条库；AI 只能引用本表条文 |
| 28 | `social_action` | A | 8 | content | 段 2 | 收藏/关注（合并表） |
| 29 | `notification` | A | 12 | content | 段 7 | 站内通知 |
| 30 | `complaint` | C | 12 | ops | 段 7 | 投诉 |
| 31 | `feedback` | A | 10 | ops | 段 7 | 意见反馈 |
| 32 | `faq` | D | 9 | content | 段 7 | 常见问题 |
| 33 | `login_log` | B | 10 | account | 段 1 | 登录日志；留存 ≥6 个月 |
| 34 | `operation_log` | B | 11 | ops | 段 1 | 敏感操作审计 |
| 35 | `ai_session` | A | 7 | ai | 段 3 | AI 会话 |
| 36 | `ai_message` | B | 12 | ai | 段 3 | AI 消息，含 refs 快照 |
| 37 | `user_device` | A | 12 | account | 段 1 | 设备与推送标识 |
| 38 | `user_violation` | A | 14 | ops | 段 7 | 违规与处罚 |
| 39 | `lawyer_application_log` | B | 10 | lawyer | 段 1 | 入驻审核流水 |
| 40 | `sys_config` | D | 9 | ops | 段 1 | 平台参数 |
| 41 | `notify_template` | D | 9 | ops | 段 7 | 通知模板，按语言分条 |
| 42 | `sensitive_word` | D | 11 | ops | 段 7 | 敏感词库 |
| 43 | `content_review` | B | 10 | ops | 段 7 | 审核命中记录，原文不改 |
| 44 | `service_ticket` | C | 9 | ops | 段 7 | 客服工单 |
| 45 | `reconciliation_diff` | B | 14 | trade | 段 5 | 对账差错 |
| 46 | `invoice` | C | 15 | trade | **不建** | 发票；V1 明确不做 |
| 47 | `file_record` | A | 14 | infra | 段 4 | 上传文件登记 |
| 48 | `share_poster` | A | 8 | content | 段 7 | 分享海报与短链 |
| 49 | `i18n_glossary` | D | 9 | content | 段 3 | 法律术语对照表 |

**合计 49 张，要建 48 张。** 段分布：段 0 = 2、段 1 = 12、段 2 = 10、段 3 = 3、段 4 = 4、段 5 = 6、段 6 = 1、段 7 = 10、段 8 = 0。
类别分布：A 业务实体 21、B 追加型 12、C 状态机 9、D 基础数据 7。

---

# 附录 B：与 §5.1 旧清单的差异

旧清单（技术路线 L986–L1015，30 行）与本清单（49 行）的差异：

| 差异类型 | 具体 |
|---|---|
| **旧清单有、实际已不存在**（3 个） | `case`（已改名 `legal_case`）、`favorite`（已并入 `social_action`）、`follow`（已并入 `social_action`） |
| **实际存在、旧清单漏了**（31 张） | `enterprise_auth`、`message_offline`、`user_balance`、`lawyer_balance`、`balance_flow`、`recharge_record`、`case_library`、`case_library_translation`、`law_item`、`social_action`、`user_device`、`user_violation`、`lawyer_application_log`、`sys_config`、`notify_template`、`sensitive_word`、`content_review`、`service_ticket`、`reconciliation_diff`、`invoice`、`file_record`、`share_poster`、`i18n_glossary`、`legal_case`、`signature`、`login_log`、`operation_log`、`doc_template`、`document`、`article_translation`、`feedback` |
| **重复定义**（2 张） | `login_log`、`operation_log`（见 §1.2 裁决） |

旧清单为什么必须作废：它既漏了 31 张，又多了 3 个不存在的表名。照它排建表计划会同时「漏建」和「找不到表」。

---

# 附录 C：现有 DDL 违反本规范的地方（建表时按规范改）

> 这份清单不是「要去改技术路线」的待办，而是**建表时的修正指引**：技术路线的 DDL 是设计参考，实际迁移文件按本规范写。每一条都登记，避免「照抄 DDL 结果不合规」。

| # | 违反的规则 | 现有写法 | 建表时改为 |
|---|---|---|---|
| 1 | 表级 `COLLATE` 必须显式 | 51 条 DDL 全部只有 `CHARSET=utf8mb4` | 补 `COLLATE=utf8mb4_0900_ai_ci` |
| 2 | 金额字段必须带 `_cents` | `balance_after BIGINT` | `balance_after_cents` |
| 3 | 布尔必须带 `is_` | `enabled`、`success`、`safety_passed` | `is_enabled`、`is_success`、`is_safety_passed` |
| 4 | OSS 资源存 `oss_key` | `avatar_url`、`license_image`、`id_card_front`、`id_card_back`、`file_url` | 改为 `*_oss_key` |
| 5 | 索引名必须可机械推导 | `idx_loginlog_user`、`idx_oplog_module`、`idx_application_user`、`idx_violation_user` | 按 `idx_<表>_<字段>` 重命名 |
| 6 | `login_log` / `operation_log` 双份 | §5.10 与 §5.11.5 各一套 | 只用 §5.11.5 |
| 7 | 表注释必须有 | 约半数表无 `COMMENT` | 全部补 |
| 8 | 字段注释必须有 | 多数列只有行内 `--` 注释 | 改为 `COMMENT '...'` |
| 9 | 时间字段不用 DB 默认值 | 现状已符合（`CURRENT_TIMESTAMP` 0 次） | 保持，但必须写应用层填充 |
| 10 | 外键不建约束 | 现状已符合（`FOREIGN KEY` 0 次） | 保持，补「注释写明引用表」与孤儿检查 |
| 11 | IP 长度标准 | 统一 `VARCHAR(50)` | 规范为 45，旧表不必改（偏差可接受） |
| 12 | 文件登记与业务表的 oss_key 一致性 | `file_record` 用 `oss_key`，其余表用 URL | 统一为 `oss_key` |

---

> **【文档基线 v1.13】** 本文件属于「法泽 FAZE 文档基线 v1.13」（2026-10-30 纳入）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
