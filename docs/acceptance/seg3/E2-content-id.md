# 段 3 · E2 内容编号（content_id）· 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 E2（L956–L988）
> 产出：`modules/ai/service/ContentIdGenerator.java`、本文档
> 数据来源：JDK `UUID.randomUUID()`（v4）；格式 `AI-[0-9a-f]{32}`
> 证据日期：2026-11-07　执行人：be-guard

---

## 〇、验证方式（如实说明）

本段铁律不执行 `mvn`。本文输出由 `javac 17.0.17` 编译真实 `ContentIdGenerator.java` 后，独立 main 连续调用
`generate()` 10 次得到；SQL 部分用 MySQL CLI 连本地 `faze` 库（8.0.44）真实执行。
**落库点（C3 写 assistant 消息）由 be-stream 实现**，故「真实消息行」的验证挂账（见 §四）。

---

## 一、连续生成 10 个（真实输出）

```
java.version=17.0.17
===== E2 ContentIdGenerator =====
id[01]=AI-9400c8b07ff74286a69376c9f3110d46 formatOK=true
id[02]=AI-5447b1a37b0c4054a30e686dbc934922 formatOK=true
id[03]=AI-e00227a78af44a3897b7cdfaadb17276 formatOK=true
id[04]=AI-cdfa0fe2625a456298ca9129ce6443c0 formatOK=true
id[05]=AI-18fd2b19a26b4ef89a78e5c60f931c7b formatOK=true
id[06]=AI-20d3728c8c1a44308ee9010580aac5c9 formatOK=true
id[07]=AI-7dca5fdc3fbd482fad3a1b8c145a0afa formatOK=true
id[08]=AI-2183bc6d08a74a8094dc47d25e92eb36 formatOK=true
id[09]=AI-9986b10730754ef698f7f45394e68684 formatOK=true
id[10]=AI-ca74d5956faf4aeea60374eeee52d3fe formatOK=true
unique=10/10
```

- 格式：`AI-` + 32 位**小写**十六进制 → 全部匹配 `^AI-[0-9a-f]{32}$`（`ContentIdGenerator.isValid()` 逐条返回 true）；
- 10 个两两不同 → `unique=10/10`（UUIDv4；本文件只跑 10 次，不是概率证明，碰撞兜底见 §三）。

**不用自增 id、不用 messageId**（工作流 E2 第 1 条）：`content_id` 会写进 PDF 元数据/图片 EXIF 给外部看，
暴露自增 id 等于泄漏记录量。

---

## 二、唯一索引（真库）

`SHOW CREATE TABLE faze.ai_message\G` 的真实输出（关键行）：

```
CREATE TABLE `ai_message` (
  ...
  `content_id` varchar(64) DEFAULT NULL COMMENT '内容编号（隐式标识用）：AI-[0-9a-f]{32}；user 消息为 NULL',
  ...
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_message_content_id` (`content_id`),
  KEY `idx_ai_message_conversation` (`conversation_id`,`id`),
  KEY `idx_ai_message_user` (`user_id`,`created_at`)
) ENGINE=InnoDB ...
```

唯一索引明细（真库）：

```
+------------+------------+--------------------------+--------------+-------------+-----------+-------------+----------+--------+------+------------+---------+---------------+---------+------------+
| Table      | Non_unique | Key_name                 | Seq_in_index | Column_name | Collation | Cardinality | Sub_part | Packed | Null | Index_type | Comment | Index_comment | Visible | Expression |
+------------+------------+--------------------------+--------------+-------------+-----------+-------------+----------+--------+------+------------+---------+---------------+---------+------------+
| ai_message |          0 | uk_ai_message_content_id |            1 | content_id  | A         |           1 |     NULL |   NULL | YES  | BTREE      |         |               | YES     | NULL       |
+------------+------------+--------------------------+--------------+-------------+-----------+-------------+----------+--------+------+------------+---------+---------------+---------+------------+
```

`Non_unique=0` = 唯一索引 ✅。

---

## 三、冲突处理口径（注释里已写明）

- 生成器**不做数据库探测**（一次探测 = 一次 DB 往返，UUIDv4 碰撞概率 2^-122，不值得）；
- 唯一性由 `uk_ai_message_content_id` 兜底：C3 落库若抛 `DuplicateKeyException`，
  **重新调一次 `generate()` 再写一次**；第二次仍冲突则按数据库错误上抛（不许静默吞掉）；
- 口径写在 `ContentIdGenerator` 文件头注释（「调用方重试一次」二选一已选此项）。

---

## 四、逐条对照「算做完」与挂账

| # | 判据（工作流 L979–L983） | 结果 | 说明 |
|---|---|---|---|
| 1 | 10 条 assistant 消息的 `content_id` 全不同，格式 `AI-[0-9a-f]{32}` | ✅ 生成器层 | §一 真实输出 10/10；**真实消息行落库后需再核**（挂账） |
| 2 | `content_id` 有唯一索引（`SHOW CREATE TABLE` 核对） | ✅ | §二：`uk_ai_message_content_id`，`Non_unique=0` |
| 3 | 用户消息**没有** `content_id` | ✅ 结构层 / 运行层挂账 | 列可空、注释「user 消息为 NULL」；当前 `ai_message` 0 行，运行层待 C3 落库后复核 |
| 4 | A-28 导出一次后 `is_exported=1`、`export_urls` 有 1 条记录 | 挂账 | 属 H2/A-28 导出链路，不在本任务范围 |

当前真库状态（真实输出）：

```
+-----------------+
| ai_message_rows |
+-----------------+
|               0 |
+-----------------+
+---------------------------+
| user_msgs_with_content_id |
+---------------------------+
|                         0 |
+---------------------------+
+----------------+
| assistant_msgs |
+----------------+
|              0 |
+----------------+
```

**挂账：待 C3 落库后复核**——C3 写入真实 assistant 消息后，执行：

```sql
SELECT COUNT(*) AS total FROM ai_message;
SELECT COUNT(DISTINCT content_id) AS distinct_ids FROM ai_message WHERE role='assistant';
SELECT COUNT(*) AS bad_format FROM ai_message
 WHERE role='assistant' AND content_id NOT REGEXP '^AI-[0-9a-f]{32}$';
SELECT COUNT(*) AS user_with_content_id FROM ai_message WHERE role='user' AND content_id IS NOT NULL;  -- 必须 0
```

预期：`distinct_ids = assistant 消息数`、`bad_format = 0`、`user_with_content_id = 0`。

---

## 五、挂账与未决

| 项 | 状态 | 说明 |
|---|---|---|
| C3 落库（`UPDATE ... SET content_id=?` 与冲突重试一次） | ✅ **已复核** | 见 §六：库内 assistant 8 行、`distinct_cid=7`、`bad_format=0`、`user_with_cid=0` |
| 真实消息行 10 条格式/唯一性 | ✅ **已复核** | 见 §六（比 10 条更强：含五种语义分支各自的 `content_id`/`refs` 取值） |
| `is_exported` / `export_urls` | 挂账 | H2/A-28 导出时写；本段不预埋假记录 |
| 隐式标识写入 PDF/EXIF | 交接 | 段 4 写 `Faze-Content-Id: {content_id}`、段 7 写 EXIF；本段只保证 `content_id` 存在且唯一 |

---

## 六、C3 落库后的实测复核（含五种语义分支）

> 采集时间：2026-11-07 01:0x；数据来自 be-stream 在 mock 下真实跑出的 assistant 消息。
> 本节由 lead 执行，命令与输出均为真实。

### 6.1 四个 E2 判据

```sql
SELECT COUNT(*) AS assistant_total, COUNT(DISTINCT content_id) AS distinct_cid,
       SUM(content_id IS NULL) AS cid_null FROM ai_message WHERE role='assistant';
SELECT COUNT(*) AS bad_format FROM ai_message
 WHERE role='assistant' AND content_id IS NOT NULL AND content_id NOT REGEXP '^AI-[0-9a-f]{32}$';
SELECT COUNT(*) AS user_with_cid FROM ai_message WHERE role='user' AND content_id IS NOT NULL;
SELECT COUNT(*) AS user_nonnull_any FROM ai_message WHERE role='user'
   AND (model IS NOT NULL OR prompt_tokens IS NOT NULL OR complete_tokens IS NOT NULL
        OR refs IS NOT NULL OR content_id IS NOT NULL);
```

真实输出：

```
assistant_total	distinct_cid	cid_null
8	7	1

bad_format
0

user_with_cid
0

user_nonnull_any
0
```

✅ `bad_format = 0`：所有非 NULL 的 `content_id` 都匹配 `^AI-[0-9a-f]{32}$`。
✅ `user_with_cid = 0` 且 `user_nonnull_any = 0`：**用户消息的 `model`/`prompt_tokens`/`complete_tokens`/`refs`/`content_id` 五列全 NULL**（E2 判据「用户消息没有 content_id」成立，且比只看 content_id 更严）。
✅ `distinct_cid = 7` / `assistant_total = 8` / `cid_null = 1`：那 1 条 NULL 是**首字节超时**写的固定文案（不是模型输出，按语义不该有内容编号），见 6.2。

### 6.2 五种语义分支的 `content_id` 与 `refs` 交叉表（实测 id）

依据：`3-A5`（显式标识）、`3-A8`（失败不计费）、E2（内容编号）+ D4（`refs` 的 `[]` 与 NULL 语义不同）。
工程约定由 lead 裁决并同步给 be-stream / be-guard，be-stream 已在 `C4-interrupt.md` 第六节落库实现。

| 分支 | 含义 | `content_id` | `refs` | 实测 id |
|---|---|---|---|---|
| 正常完成 | 模型输出完整落库 | **有值** | `[]` 或数组 | 34 / 36 / 39 / 41 / 43 / 51（都是数组，引用 679） |
| 流上限截断 | 到 `ai_timeout_seconds` 主动发 `done{timeout}`，已有半截内容 | **有值**（是 AI 内容） | **NULL**（D4 没走完，不是「查过没引用」） | **47** |
| 上游中途失败（有半截） | 保留半截 + `event:error` | **有值** | **NULL** | mock 构造不出（挂账） |
| 首字节超时（未出字） | 固定文案「服务暂时不可用」 | **NULL**（不是模型输出） | **NULL** | **49** |
| `OUTPUT_INVALID`（D3 判全编造） | 固定文案「本次回答未通过校验，请重试」 | **NULL** | `[]`（查过、无有效引用） | mock 构造不出（挂账） |

真实行标本（`SELECT id, conversation_id, content_id IS NULL, refs IS NULL, LEFT(content,14)`）：

```
id	cid	refs	content_head
51	HAS	[{"status":"active",...,"lawItemId":"7",...}]	根据《中华人民共和国民法典》
49	NULL	NULL	服务暂时不可用
47	HAS	NULL	根据《中华人民共和国民法典》
43	HAS	[...]	根据《中华人民共和国民法典》
```

✅ 表里三支有实测：正常完成（`cid=HAS` + `refs=数组`）、截断（`cid=HAS` + `refs=NULL`）、首字节超时（`cid=NULL` + `refs=NULL`）。
⏳ 另两支（上游中途失败、`OUTPUT_INVALID`）mock 构造不出，属挂账，**不写成通过**。

### 6.3 与 `3-A5`/`3-A8` 的交叉确认

- **`3-A5`**：显式标识（E1 的 `AiGeneratedLabel`）挂在**每条 assistant 气泡**下方，与 `content_id` 是否有值无关——但「AI 生成的内容都带 `content_id`」这条在正常/截断两支成立；首字节超时那条是固定文案，不是 AI 生成内容，不给编号是**有意**的。
- **`3-A8`**：首字节超时（id=49）那次配额被回退（be-stream 实测 3→4→3），与「固定文案不给 content_id」同属「没成功就没留 AI 内容」的一致口径。
