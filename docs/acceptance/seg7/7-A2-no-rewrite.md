# `7-A2` 原文不被篡改 · 验收证据

> **验收人**：Lead（独立复核，不采信实现者自述）
> **时间**：2026-11-09 01:22:04 ~ 01:22:06（Asia/Shanghai）
> **方式**：真实 faze-api(8081) 提交 + 真实 MySQL 查库 + 全仓 grep
> **脚本**：`tmp/seg7h/anchor-run.mjs A2`（原始输出 `tmp/seg7h/anchor-A2.log` / `.json`，不入库）
> **被验对象**：投诉/反馈提交链路 + `ContentReviewService` + `content_review` 表

## 1. 结论

**部分通过。**

- ✅ 原文一字未改（含命中词的正文原样落库，未被替换成 `***`）
- ✅ 命中记录三要素齐全（命中词 + 级别 + 片段）
- ✅ 全仓没有把原文改写成 `***` 的代码
- ❌ **锚点第 3、4 步在 `block` 分支上无法取证**：`block` 级内容整条被拒，业务行与命中记录**在同一事务里一起回滚**，库里什么都不会留下
- ❌ **命中记录无法关联到被审内容**：`content_review.biz_id` 恒为 `0`

## 2. 前置事实（推翻了锚点的一个举例）

锚点原文写「正文含 `block` 级词（**如「包打赢」**）」，但实测：

```sql
SELECT level FROM sensitive_word WHERE word='包打赢';
-- review
```

**「包打赢」是 `review` 级，不是 `block` 级**（种子 50 条里 `block` 30 条全部是 `【示例】…` 占位词，`review` 20 条是 FRAUD 类真实话术）。锚点这句举例是错的，照它照做会得到「能提交成功」然后误判失败。本次验收改用 `【示例】政治类敏感词01` 作 block 级样本、`包打赢` 作 review 级样本。

## 3. block 级：拦得住，但库里不留痕

```text
POST /api/v1/app/feedback  {"type":"other","content":"这是验收用内容，包含【示例】政治类敏感词01，看是否被拦"}
=> HTTP 200  code=410006  msg=内容含违规信息，未发送
```

提交前后行数对比（同一秒内）：

| 表 | 提交前 | 提交后 | Δ |
|---|---|---|---|
| `feedback` | 7 | 7 | **0** |
| `content_review` | 2 | 2 | **0** |

原因（读代码确认）：`ContentReviewService.review()` 在调用方事务内 `insert` 命中记录，`FeedbackService.submit()` 拿到 `blockDelivery=true` 后 `throw BusinessException` → 整个事务回滚，命中记录一起没了。类注释第 5 条自己写了这个设计。

**所以锚点第 3 步「查 `complaint` 原文是否完整」和第 4 步「查 `content_review` 有 1 条」在 block 分支上永远查不到东西** —— 这不是实现 bug，是**锚点与「block 整条拒绝」的设计互相矛盾**。处置见 §6。

## 4. review 级：原文完整 + 命中标注齐全

```text
POST /api/v1/app/feedback  {"type":"other","content":"诉讼服务承诺包打赢，这是验收样本"}
=> HTTP 200  code=200  ticketNo=TK20261109000014
```

库内原文（**逐字比对**）：

```sql
SELECT content FROM feedback WHERE id=8;
-- 诉讼服务承诺包打赢，这是验收样本
```

- 含「包打赢」三个字 → ✅ 未被替换
- 不含 `***` → ✅ 未被掩码
- `originalIntact = true`

命中记录（`content_review.id=6`）：

| 字段 | 值 |
|---|---|
| `biz_type` | `feedback` |
| `biz_id` | **`0`** ← 缺陷，见 §5 |
| `level` | `review` |
| `review_result` | `pending`（进 A-20 队列） |
| `hit_words` | `[{"end":9,"word":"包打赢","level":"review","start":6,"category":"FRAUD"}]` |
| `hit_snippet` | `诉讼服务承诺包打赢，这是验收样本` |
| `engine` | `local_trie` |

三要素齐全：**词**（`包打赢`）、**级别**（`review`）、**片段**（非空且未改写）。

`GET /api/v1/admin/content-reviews?pageNum=1&pageSize=5` 实测返回 `total=3`，队列里能看到该条（`id=6`、`reviewResult=pending`、`bizTypeLabel=反馈`）——即「`review` 级待审」这条链路通。

## 5. 缺陷：`content_review.biz_id` 恒为 0，A-20 队列点不到内容

实测队列里三条**全部** `bizId="0"`：

| id | bizType | bizId | level | hitSnippet |
|---|---|---|---|---|
| 6 | feedback | **0** | review | 诉讼服务承诺包打赢，这是验收样本 |
| 4 | feedback | **0** | warn | 这里含验收违禁词ZZ18882两个字（warn 阶段） |
| 2 | complaint | **0** | review | 我保证包打赢这个案子，不赢不收费 |

根因：投诉/反馈是「先审后落库」，`new ReviewRequest(..., bizId=null, ...)`，落库时 `row.setBizId(0L)`；业务行插入后没有回填。后果是 A-20 队列里的命中记录**无法定位到具体是哪条投诉/反馈**（只能靠 `bizUserId` + 时间猜），`biz_id` 这个字段等于废的。

已转 be-api 修（`ReviewVerdict` 带 `reviewId`，落库后回填）。修复状态见本文件末尾的「修复后复验」。

## 6. 全仓「改写原文」检查

```powershell
Get-ChildItem -Path services/faze-api/src/main/java -Recurse -Filter *.java |
  Select-String -Pattern '***' -SimpleMatch | Measure-Object
# => 45
```

45 处命中**逐条看过**，全部属于两类，**没有一处是内容改写**：

1. `LogMaskUtil` / `MaskUtil` / 各 Converter 的**脱敏规则**（手机号 `138****8888`、身份证、银行卡、`ossKey`）；
2. **注释里描述脱敏规则**的文字。

`MaskUtil.java` 的类注释第 2 条本身就把红线写死了：「一律不用『整段替换成 ***』——那样审核员看不出是什么内容，等于把证据删了」。

## 7. 处置与挂账

| # | 事项 | 处置 |
|---|---|---|
| 1 | 锚点举例「包打赢 是 block 级」错误 | 已在本文件 §2 更正，建议回写工作流 §四 `7-A2` |
| 2 | block 分支无法取证（业务行与命中记录一起回滚） | **不改事务语义**（改了会破坏「block 不落库」的既有设计）。锚点第 3/4 步在 block 分支记为「不适用」，改在 review 分支取证。是否要为「被拦截的提交」单独留一条审计记录，**留给负责人/段 8 决定** |
| 3 | `content_review.biz_id=0` | 转 be-api 修（回填业务行 id）。block 分支不回填，属预期 |
| 4 | 工作流要求「A-20 队列截图」 | 本轮以 `GET /admin/content-reviews` 的真实响应替代；A-20 页面截图在 H2 收口时补（依赖 fe-pc 把 PC 前端跑起来） |

## 8. 复现命令

```powershell
cd E:\dshcode\FAZE
node tmp/seg7h/anchor-run.mjs A2
```

前置：faze-api(8081) 与 RuoYi(8080) 在跑；`app_user` 里存在手机号 `13900008888` 的验收账号（`tmp/seg7h/app-token.json` 缓存 JWT）。
