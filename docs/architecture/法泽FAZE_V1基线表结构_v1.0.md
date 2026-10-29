# 法泽 FAZE V1 基线表结构定稿（V1.0）

> **用途**：第 0 段 Flyway `V1__baseline.sql` 的唯一依据
> **上位文档**：《法泽FAZE_技术路线_v2.3.md》§5.3（`app_user`）、§5.10（`law_item`）
> **约束**：本文件一旦被 Flyway 执行，**就不能再修改**（迁移不可变），只能追加 `V1_1__xxx.sql` 补丁
> **定稿日期**：2026-10-28

---

# 一、为什么 V1 只建这两张表

技术路线 v2.3 已有全部表的 DDL（**实测 51 条 `CREATE TABLE`、唯一 49 张**，口径见《[法泽FAZE_数据表规范_v1.0.md](法泽FAZE_数据表规范_v1.0.md)》附录 A），但第 0 段**只需要两张**：

| 表 | 第 0 段为什么需要 |
|---|---|
| `app_user` | 验收 6 要走「登录 → 拿 token → 401 → 回登录页」，没有用户表就没有真实登录 |
| `law_item` | 验收 11 的 `/probe/law` 要从这张表用 ngram 命中条文 |

**不建其余 47 张的理由**（这是判断，不是偷懒）：
1. 空表不是"预留"，是负债——它会诱导人往里塞代码，也会让"这段到底做了什么"无法验收
2. 凭猜测定下的字段，等真正跑到那段时几乎必然改语义；而改已执行的迁移只能打补丁，表结构会越来越乱
3. 共享表提前定准（`app_user` 被后续 8 个域引用），专属表随段落建

---

# 二、库级约定（V1 建库时就要定）

| 项 | 值 | 说明 |
|---|---|---|
| 业务库名 | `faze` | 与 RuoYi 的 `faze_admin` **分库**，避免 Flyway 与 RuoYi 的 `sys_*` 表打架 |
| 字符集 | `utf8mb4` | 藏文/维吾尔文/哈萨克文都是多字节，必须 utf8mb4 |
| 排序规则 | `utf8mb4_0900_ai_ci` | **已实测**：8.0.44 上可正常创建 ngram FULLTEXT，不需要退 unicode_ci |
| 存储引擎 | `InnoDB` | ngram 解析器是 InnoDB 内置，MyISAM 不可用 |
| `ngram_token_size` | `2` | 默认值即为 2，**不改 my.ini**（只读参数，改了要重启服务）。**已实测双字可命中、单字命中 0 条** |
| 应用账号 | `faze_app`@`localhost` | 只授权 `faze.*`，**不用 root 跑应用** |
| 时区 | **已定：`DATETIME` 存 UTC**（见 §5.1） | — |

---

# 三、`app_user` 完整 DDL

> ⚠️ **本 DDL 尚未在真实库执行过。** 第 0 段工作流里的「建库 + Flyway V1 基线」任务是它第一次被执行；届时若出现语法或 collation 报错，以实际可执行的版本为准并回头修正本文件。
>
> **已实测通过的部分**：`MEDIUMTEXT` 字段上建 `FULLTEXT ... WITH PARSER ngram`，在 MySQL 8.0.44 + `utf8mb4_0900_ai_ci` 下可成功创建，且 `EXPLAIN` 确认走 `ft_law_content` 索引。

```sql
-- ============================================================================
-- 表名：app_user
-- 职责：C 端用户主表。C 端用户与律师共用此表，律师身份在 lawyer 表扩展
-- 调用方：account 域（登录、注册、实名、资料）；后续 8 个域通过 user_id 引用
-- 数据来源：App 注册 / PC 后台创建
-- 注意：PC 后台管理员见 RuoYi 的 sys_user，**两者不混用**
-- ============================================================================
CREATE TABLE app_user (
  id                  BIGINT       PRIMARY KEY AUTO_INCREMENT       COMMENT '主键',
  phone               VARCHAR(20)  NOT NULL                         COMMENT '手机号，登录账号，唯一',
  password_hash       VARCHAR(100)                                  COMMENT '密码哈希，BCrypt 60 字符；第三方登录用户可为 NULL',
  nickname            VARCHAR(50)                                   COMMENT '昵称',
  avatar_url          VARCHAR(500)                                  COMMENT '头像地址',
  gender              TINYINT      DEFAULT 0                        COMMENT '0未知 1男 2女',
  birth_date          DATE                                          COMMENT '出生日期，用于未成年人识别',
  role                VARCHAR(20)  DEFAULT 'user'                   COMMENT 'user / lawyer / customer_service / admin',
  lang                VARCHAR(20)  DEFAULT 'zh-Hans'                COMMENT '语言偏好：zh-Hans / bo / ug-Arab-CN / kk-Arab-CN',
  realname_status     VARCHAR(20)  DEFAULT 'none'                   COMMENT 'none / pending / approved / rejected',
  is_minor            TINYINT      NOT NULL DEFAULT 0               COMMENT '不满 18 周岁即置 1；注册不限年龄',
  guardian_phone      VARCHAR(20)                                   COMMENT '监护人手机号，未成年人必填',
  guardian_consent_at DATETIME                                      COMMENT '监护人同意时间',
  status              VARCHAR(20)  DEFAULT 'active'                 COMMENT 'active / blocked / cancelled',
  last_login_at       DATETIME                                      COMMENT '最后登录时间',
  last_login_ip       VARCHAR(50)                                   COMMENT '最后登录 IP',
  created_at          DATETIME     NOT NULL                         COMMENT '创建时间',
  updated_at          DATETIME     NOT NULL                         COMMENT '更新时间',
  deleted_at          DATETIME                                      COMMENT '软删除时间；NULL 表示未删除',
  UNIQUE KEY uk_app_user_phone (phone),
  KEY idx_app_user_role_status (role, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='C 端用户表；PC 后台管理员见 sys_user，两者不混用';
```

## 3.1 字段逐个说明（为什么是这些字段）

| 字段 | 为什么必须有 | 被谁用 |
|---|---|---|
| `phone` | 国内手机号登录的唯一账号标识，`UNIQUE` 保证不重复注册 | 登录、注册、换绑 |
| `password_hash` | BCrypt 散列值固定 60 字符，`VARCHAR(100)` 留余量 | 登录校验 |
| `lang` | 4 语言偏好必须持久化到服务端，否则换设备/重装后语言丢失 | 每段都可能用 |
| `realname_status` | **法律服务的硬性要求**：未实名不能下单咨询 | 第 1 段实名认证、第 2 段下单前置校验 |
| `birth_date` + `is_minor` + `guardian_phone` + `guardian_consent_at` | 法律咨询场景**必然触达未成年人**（校园欺凌、家暴、抚养权）。这四个字段是同一组机制，不能拆 | 第 1 段注册、各段下单前置 |
| `role` | 判断是 C 端用户 / 律师 / 客服 / 管理员 | 全段鉴权 |
| `status` | `blocked` 用于封禁；`cancelled` 用于**注销账号**（第 8 段应用商店硬要求） | 第 1 段、第 7 段风控、第 8 段注销 |
| `deleted_at` | 软删除。用户数据不能物理删（合规留痕） | 全段 |

### 3.1.1 `is_minor` 的定稿：不满 18 周岁即置 1

**先说一段我搞错的事，留作记录**：我最初看到注释「14–18 岁标记」时判断它是错的。查过之后发现**它当时是对的** —— 因为 F-3-23 规定「未满 14 周岁禁止注册」，表里不可能有 <14 岁的人，`is_minor=1` 自然只覆盖 14–18。

**但项目负责人 2026-10-28 决定「不限制年龄」，前提变了**：

- 注册不再有 14 岁下限 → 表里可能出现任意年龄的用户
- 因此 `is_minor` 的语义必须改为「**不满 18 周岁即置 1**」
- 注释随之改为「不满 18 周岁即置 1；注册不限年龄」

**判断逻辑放服务端**（不放客户端——客户端时间可被篡改）：

```java
// 年龄按周岁计算
isMinor = Period.between(birthDate, LocalDate.now(ZoneOffset.UTC)).getYears() < 18;
```

**⚠ 法律提醒（这不是产品选择，是义务）**

「不限制注册年龄」不等于「不承担未成年人保护义务」。两条硬约束：

1. **《个人信息保护法》第 31 条**：处理**不满 14 周岁**未成年人的个人信息，属**敏感个人信息**，必须取得监护人**单独同意**，并制定**专门的处理规则**。
   → 所以 `guardian_phone` + `guardian_consent_at` 对 <14 岁**更是必需**，不是可选。
2. **民事行为能力**：<8 周岁为无民事行为能力人（所签合同无效）；8–18 周岁为限制民事行为能力人（只能实施与其年龄、智力相适应的行为）。**付费咨询合同对未成年人是否有效，要单独评估。**

**建议**：监护人同意的触发条件从「14–18 岁」放宽为「**全部 <18 岁**」，<14 岁额外留单独同意记录。第 1 段（注册流程）落实，第 8 段（上架合规）再核。

## 3.2 三个设计说明（容易踩坑的地方）

1. **`role` 是单值，不是数组。** 但技术路线里还有 `lawyer` 表承载律师扩展信息。所以「一个人既是用户又是律师」= `app_user.role='lawyer'` + `lawyer` 表有对应行。**这与 D7「单 token + roles claim」不矛盾**：表里存当前主角色，token 里带全部角色。

2. **`status` 与 `deleted_at` 是两个维度，不要合并。**
   - `status='cancelled'` = 用户主动注销（可保留数据，手机号可重新注册需要特殊处理）
   - `deleted_at IS NOT NULL` = 后台软删（一般用户不可见）
   - 混用会导致「注销后能不能再注册同一个手机号」这个问题无法回答

3. **没有 `email` 字段。** 国内法律服务 App 用手机号登录即可，加 email 会让注册流程变长且无实际用途。这是**有意不加**，不是遗漏。

---

# 四、`law_item` 完整 DDL

```sql
-- ============================================================================
-- 表名：law_item
-- 职责：本地法条库。AI 只能引用本表存在的条文（幻觉防护的物理基础）
-- 调用方：ai 域（检索增强）、document 域（文书引用条文）、content 域（文章绑定条文）
-- 数据来源：Flyway 基础数据迁移（V2__init_law_items.sql），**不是种子数据**
-- 注意：全量 2000–3000 条在第 3 段导入；第 0 段只灌 5 条用于验证链路
-- ============================================================================
CREATE TABLE law_item (
  id             BIGINT       PRIMARY KEY AUTO_INCREMENT  COMMENT '主键',
  law_code       VARCHAR(50)  NOT NULL                    COMMENT '法律简称码：civil_code / civil_procedure / labor_contract 等',
  law_name       VARCHAR(100) NOT NULL                    COMMENT '法律全称：中华人民共和国民法典',
  article_no     VARCHAR(50)  NOT NULL                    COMMENT '条号文本：第六百八十条',
  article_seq    INT                                      COMMENT '条号数字序号，用于排序与范围查询',
  content        MEDIUMTEXT   NOT NULL                    COMMENT '条文原文',
  effective_date DATE         NOT NULL                    COMMENT '生效日期，参与唯一键',
  expire_date    DATE                                     COMMENT '失效日期；NULL 表示现行有效',
  status         VARCHAR(20)  NOT NULL DEFAULT 'active'   COMMENT 'active / amended / repealed',
  keywords       VARCHAR(500)                             COMMENT '检索用关键词，人工维护',
  created_at     DATETIME     NOT NULL                    COMMENT '创建时间',
  updated_at     DATETIME     NOT NULL                    COMMENT '更新时间',
  UNIQUE KEY uk_law_article (law_code, article_no, effective_date),
  KEY idx_law_code_seq (law_code, article_seq),
  FULLTEXT KEY ft_law_content (content) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='法条库；AI 只能引用本表存在的条文';
```

## 4.1 四个设计说明

1. **唯一键是 `(law_code, article_no, effective_date)` 三元组，不是 `(law_code, article_no)`。**
   原因：法条会修订。同一条号在不同生效日期是不同版本（比如《民事诉讼法》多次修正）。**若唯一键只有两列，修订版会覆盖旧版，历史引用就指错了。** 这是法律业务特有的要求。

2. **`ngram` 全文索引建在 `content` 上，`ngram_token_size=2`（默认）。**
   **已实测结论**：
   - `MATCH(content) AGAINST('合同' IN BOOLEAN MODE)` → 正常命中，`EXPLAIN` 显示 `type=fulltext`、`key=ft_law_content`
   - 单字 `婚` → **命中 0 条**
   - 因此 **`q` 长度 < 2 时必须走 `LIKE` 兜底**，这不是保险，是必需

3. **`effective_date NOT NULL` 意味着种子数据必须给生效日期。** 这是唯一键的必要组成，不能留空。第 0 段那 5 条种子要写清生效日期。

4. **`keywords` 是人工维护的补充检索词。** ngram 对同义词无效（搜「劳动仲裁」不会命中只写「仲裁」的条文），`keywords` 用来补这个洞。第 0 段可以先留 NULL，第 3 段全量导入时再补。

---

# 五、三项建议的处置结果

| # | 事项 | 结果 |
|---|---|---|
| 1 | `app_user` 加 `KEY idx_app_user_phone_status (phone, status)` | **不加**。现有 `uk_app_user_phone` 足够，加这个只省一次回表，收益不成比例 |
| 2 | `law_item` 加 `FULLTEXT KEY ft_law_keywords (keywords)` | **第 3 段再定**（全量法条导入时才需要） |
| 3 | 时区策略 | ✅ **已定：`DATETIME` 存 UTC，出参 ISO-8601 带 `+08:00`**（项目负责人 2026-10-28 拍板） |

## 5.1 时区约定（已定稿）

| 项 | 约定 |
|---|---|
| 数据库存储 | 所有 `DATETIME` 字段**存 UTC** |
| 服务端取时 | `LocalDateTime.now(ZoneOffset.UTC)`，**禁止用系统默认时区** |
| 接口出参 | ISO-8601 带偏移，如 `2026-10-28T15:00:00+08:00` |
| 客户端展示 | 按用户 locale 转；国内用户默认 `Asia/Shanghai` |
| JSON 序列化 | Jackson 全局统一配置，**不允许各接口各写一套** |

**为什么定 UTC**：`DATETIME` 本身不带时区信息，存本地时间等于把「这条记录到底几点」交给约定。一旦以后要做数据导出、跨区域部署、对接第三方，都必须全表重刷。存 UTC 是唯一不用还债的选择。代价是每次查询要记得转 —— 所以「服务端取时禁止用系统默认时区」要作为硬规则写进 ADR。

---

# 六、V1 与 V2 的边界

| 迁移 | 内容 | 属于哪段 |
|---|---|---|
| `V1__baseline.sql` | **只建 `app_user` + `law_item` 两张表结构**，不含数据 | 第 0 段 |
| `V2__init_law_items.sql` | 5 条真实法条种子数据 | 第 0 段（P-3 法条检索探针） |
| `V3__` 起 | 其余 47 张表，按段落建，每段占版本号区段（分段规则见《数据表规范》§8.1） | 第 1 段起 |

**版本号分段规则**（写进 ADR）：
- 第 0 段：`V1` – `V99`
- 第 n 段：`V{n}00` – `V{n}99`
- **已执行的迁移禁止修改**，修正只能新增 `V{x}_{y}__fix_xxx.sql`

---

# 七、这份定稿的验收

- [ ] 两条 DDL 能直接在空库执行成功（无语法错误、无 collation 报错）
- [ ] `law_item` 建完后 `SHOW CREATE TABLE law_item` 含 `WITH PARSER ngram`
- [ ] `SHOW CREATE TABLE` 两张表的 `COLLATE` 都是 `utf8mb4_0900_ai_ci`
- [ ] 字段数与文档一致：`app_user` 19 个、`law_item` 12 个
- [ ] 与 v2.3 原文逐字段比对，**除 §五三项待确认外无差异**

> 字段数核对：`app_user` 数得 19（id, phone, password_hash, nickname, avatar_url, gender, birth_date, role, lang, realname_status, is_minor, guardian_phone, guardian_consent_at, status, last_login_at, last_login_ip, created_at, updated_at, deleted_at）✓
> `law_item` 数得 12（id, law_code, law_name, article_no, article_seq, content, effective_date, expire_date, status, keywords, created_at, updated_at）✓


---

> **【文档基线 v1.1】** 本文件属于「法泽 FAZE 文档基线 v1.1」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
