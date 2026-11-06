# 第 2 段 · G5「评价」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze` / Redis db1）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G5（2 个任务）、§四 `2-A4`
> 说明：每条都附真实 API 返回或库内数据。C 端 token 用**短信固定码登录**
> （`POST /api/v1/pub/sms/code` scene=login → `POST /api/v1/pub/auth/login/sms`，dev 固定码 `123456`），
> 律师侧用 `app_user.id=1`（`13800000001`）的登录密码 `123456`。
> 之所以不用密码登录 user3：**user3（`13900000009`）的登录密码不是本轮能确认的值**
> （`deploy/.env` 与验收脚本里都没有；`Faze123456` / `123456` / `111111` 均失败），
> 而短信固定码是 dev 合法通道，且不修改任何用户数据。

---

## 一、交付物

| 任务 | 交付 | 提交 |
|---|---|---|
| G5.1 评价提交 | `POST /app/orders/{id}/review`、`GET /app/orders/{id}/review`；`ReviewService#submit` / `#myReview`；`ReviewSubmitBO` / `ReviewVO` | `feat(trade)` 第 1 批 |
| G5.1 律师回复 | `POST /app/reviews/{id}/reply`（**段 2 新增接口**，技术路线 §4.8.2 未列）；`ReviewController`、`ReviewReplyBO` | 同上 |
| G5.2 评分回写 | `ReviewService#recalcLawyerRating`：同事务内 `SELECT AVG(rating), COUNT(*)` → `UPDATE lawyer SET rating, review_count` | 同上 |
| 订单详情联动 | `OrderDetailVO.reviewed`；已评价时 `actions` 去掉 `review`（G5.1「一单只能评一次」的界面表现） | 同上 |
| APP 评价页 | `ReviewPage.tsx`（34-review）、路由壳 `app/review.tsx`、`routes.ts` 的 `review` / `reviewHref` | `feat(trade)` 第 2 批 |
| 订单详情入口 | `OrderDetailPage` 的「评价服务」改为跳转评价页；已评价显示「查看评价」 | 同上 |
| 错误码 | `TRADE_REVIEW_NOT_FOUND(405021)`、`TRADE_REVIEW_DUPLICATE(405022)`（**不用 405012/405013**，那两个已被《错误码规范》§三 预留给段 5） | 第 1 批 |

---

## 二、G5.1 评价提交（真实 API）

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 评价前订单详情 | `reviewed=false`，`actions=["review"]` | `status=completed` `reviewed=false` `actions=["review"]` |
| 2 | 评价前查评价 | `data=null` | `GET /app/orders/5/review` → `data=null` |
| 3 | 提交评价（4 星 + 2 标签 + 正文） | 200，落库 1 行 | `code=200` `id=1` `rating=4` `tags=["专业能力强","回复及时"]` |
| 4 | 评价后订单详情 | `reviewed=true`，`actions=[]` | `reviewed=true` `actions=[]`（`review` 已被服务端移除） |
| 5 | 同一订单再评一次 | `405022` | `code=405022`「该订单已评价，不能重复评价」 |
| 6 | 未完成订单评价（订单 10，`paid`） | `405003` | `code=405003`「当前订单状态不允许评价：paid」 |
| 7 | 非本人订单 | `405002`（归属进 SQL） | `ReviewServiceTest#submitOtherUserOrder` 覆盖 |
| 8 | 评分 0 / 6 / 空 | `401001` / `401001` / `401002` | `ReviewServiceTest#submitInvalidRating` 覆盖 |
| 9 | 标签超过 6 个 | `401001`（不静默截断） | `ReviewServiceTest#submitTooManyTags` 覆盖 |

> 「一单一次」是**两层**保证：应用层先查（给出可读的 405022），
> `uk_review_order` 兜底并发（`DuplicateKeyException` 也转 405022）。

---

## 三、G5.1 匿名与律师回复

### 3.1 匿名评价（订单 12，先由律师接单并完成）

```text
POST /app/orders/12/accept    → code=200（订单 12：paid → in_service）
POST /app/orders/12/complete  → code=200（订单 12：in_service → completed）
POST /app/orders/12/review {"rating":5,"tags":["态度耐心"],"content":"匿名评价：整体满意","isAnonymous":true}
  → code=200 {"rating":5,"anonymous":true}   ← 出参无 userId 字段
GET /app/lawyers/2/reviews → 首条 {"rating":5,"anonymous":true,"userNickname":null}
                              次条 {"rating":4,"anonymous":false,"userNickname":"用户0009"}
```

匿名评价在律师详情页**不显示用户信息**（`userNickname=null`）；
非匿名那条正常显示昵称。判定口径：出参不带 `userId`，且昵称按 `is_anonymous` 置空
（`LawyerBrowseService#loadNicknames` 只在非匿名时批量取昵称）。

### 3.2 律师回复（`POST /app/reviews/{id}/reply`，段 2 新增）

```text
律师（13800000001，app_user.id=1）：
POST /app/reviews/1/reply {"content":"感谢认可，有问题随时联系。"}
  → code=200 {"lawyerReply":"感谢认可，有问题随时联系。"}
GET /app/lawyers/2/reviews → 首条 lawyerReply="感谢认可，有问题随时联系。"   ← 回复能在 C 端展示

用户（app_user.id=3，不是该评价的律师）：
POST /app/reviews/1/reply {"content":"我自己回一句"}
  → HTTP 403 code=401008「无权限访问该资源」
```

---

## 四、G5.2 评分即时回写（`2-A4` 的关键判据）

| 时点 | `lawyer.rating` | `lawyer.review_count` | 依据 |
|---|---|---|---|
| 种子状态（无评价） | 5.0 | 0 | 段 1 手工种子，`review` 表 0 行 |
| 订单 5 评价 4 星后 | **4.0** | **1** | 整表重算，不是增量 |
| 订单 12 评价 5 星后 | **4.5** | **2** | (4+5)/2=4.5 |

```sql
SELECT id, rating, review_count FROM lawyer WHERE id=2;
-- 2   4.5   2
SELECT AVG(rating) AS avg_rating, COUNT(*) AS cnt FROM review WHERE lawyer_id=2;
-- 4.5000   2
```

**注意**：种子数据 `rating=5.0 / review_count=0` 是不自洽的历史假数据，
第一次真实评价后被整表重算覆盖为 `4.0`——这是**正确行为**，不是回写错误。
判据是 `lawyer.rating = AVG(review.rating)`、`lawyer.review_count = COUNT(*)` 恒成立（上式已验）。
律师详情 `GET /app/lawyers/2` 的 `rating` / `reviewCount` 直接读 `lawyer` 行、**无缓存**，
所以评价后刷新即变（实测：`{"rating":4.5,"reviewCount":2}`）。

---

## 五、库内数据（真实查询）

```sql
SELECT id, order_id, user_id, lawyer_id, rating, tags, is_anonymous, lawyer_reply FROM review;
-- 1  5   3  2  4  ["专业能力强", "回复及时"]  0  感谢认可，有问题随时联系。
-- 2  12  3  2  5  ["态度耐心"]                1  NULL
```

- `tags` 落库是 JSON 数组字符串，出参是数组（服务端解析，客户端不碰 JSON）。
- `is_anonymous` 是 tinyint(1)，`1` = 匿名。

---

## 六、自动化测试

- `mvn test`：**170 项全绿**（新增 `ReviewServiceTest` 13 项 + `OrderServiceImplTest` 新增 1 项）
  - `ReviewServiceTest`：正常提交（含整表重算断言）/ 非完成态 / 重复 / 非本人 / 评分越界 /
    标签超量 / 四舍五入（4.666→4.7）/ 无评价归零 / 律师回复成功 / 他人回复 403 /
    评价不存在 405021 / 空回复 401002 / 未评价查询返回 null
  - `OrderServiceImplTest#detailDropsReviewActionWhenReviewed`：已评价时 `actions` 不含 `review`
  - 关键断言：**评分为整表重算**（`selectMaps` 被调用）且**不读旧评分**；插评价失败时不回写评分
- 前端门禁：`tsc --noEmit`、`eslint .`（0 告警）、`check-raw-text`（90 文件 0 命中）、
  `check-i18n`（四目录同构 + 缺译已登记 + 占位符一致）全过。
- i18n：新增 `trade.review.*` 33 个 key、`trade.order.actViewReview`、`error.trade.review-not-found`、
  `error.trade.review-duplicate`；删除占位 key `trade.order.reviewPending`（入口已实现）。
  其余三语（`bo` / `ug-Arab-CN` / `kk-Arab-CN`）登记缺译，段 8 清空。

---

## 七、APP 页面（34-review）

| 设计稿要求 | 实现 |
|---|---|
| 星级初始 0，不预填 5 星；未打分底部按钮置灰 | `rating` 初始 0，`disabled={submitting || rating < 1}` |
| 星级点击区 44×44、星形 32 | `styles.star` 触控盒 45×45（≥44）+ Ionicons size 32 |
| 标签多选、文字选填、只有星级必填 | 标签 `Pressable + TagChip`（选中 brand / 未选 outline）；正文选填 |
| 文字长度计数 | 设计稿 `maxlength=200` → 前端 `CONTENT_MAX=200` + `n/200` 计数 |
| 匿名开关（整行可点） | `ToggleRow`（设计稿的 44 高开关行） |
| 「律师可以回复你的评价」「发布后不能修改」 | `trade.review.tip` |
| 已评价只读态 | 同页切换为「我的评价」：星级只读 + 标签 + 正文 + 匿名标记 + 律师回复 |
| 四态 | loading / error（`ErrorState` 重试）/ 表单 / 「这单还不能评价」提示 |

---

## 八、偏差与遗留（如实记录）

1. **正文长度两处口径**：工作流写 `content ≤500`（= `review.content VARCHAR(500)`），
   设计稿 `34-review.html` 写 `maxlength=200`。实现取「服务端上限 500、前端界面 200」
   —— 前端更严不会写入超长，服务端更宽不会误拒历史客户端。若要求一处口径，需负责人拍板取哪个。
2. **律师回复允许覆盖**：工作流没规定回复次数。评价不可改（保护用户），
   回复可覆盖（律师修正错别字），每次覆盖更新 `updated_at`。若要「只能回一次」，需明确。
3. **错误码 405021 / 405022**：《错误码规范》§三 的 405 段 405008~405020 已预留给段 5
   （提现/结算/充值/渠道），而段 2 施工已实际占用 405008~405011。本任务不往预留段里塞，
   改用 405021/405022，并在《错误码规范》§三 追加「段 2 施工登记」说明。**段 5 开工前需统一裁决**。
4. **评价页没有「查看我在律师主页的评价」入口**：律师详情页已有评价列表（G3.2），
   匿名/非匿名展示已验；订单详情的「查看评价」直达本页只读态，够用。
5. **律师端回复界面不在本段**：段 2 只实现接口；律师侧回复入口随段 6 的律师主页/消息中心做。
6. **`review` 表无逻辑删除**：评价不可删（工作流 §七），段 2 也不做申诉，符合预期。
