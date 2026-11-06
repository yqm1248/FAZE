# 第 2 段 · G8「内容与消息中心」验收证据

> 施工日期：2026-11-06 · 分支 `UI设计` · 环境：本地 dev（faze-api 8081 / MySQL `faze`）
> 对应工作流：`docs/architecture/法泽FAZE_第2段工作流_v1.0.md` §三 G8（3 个任务）、§四 `2-A7`
> 说明：文章列表/详情是**公开接口**（`/pub` 前缀，游客可读），语言走 `Accept-Language`
> 或 `lang` 查询参数。种子文章由 SQL 写入（`article` + `article_translation` 各一行）；
> **PC A-15 的录入页面在 G9.4**，届时 `2-A7` 的「PC 录入 → APP 显示」全链路补齐。

---

## 一、交付物

| 任务 | 交付 |
|---|---|
| G8.1 文章接口（四语言） | `GET /api/v1/pub/articles`、`GET /api/v1/pub/articles/{id}`（同实现也挂在 `/app` 下）；`ArticleService` / `ArticleController` / `Article` / `ArticleTranslation` 实体与 Mapper / `ArticleBlockConverter` / `ArticleCardVO` / `ArticleDetailVO` / `ArticleBlockVO` / `CursorResult` 之外的 `PageResult` 分页 |
| G8.2 首页 + 文章列表 + 详情 | `HomePage.tsx`（01-home：「每日学法」接真实接口）、`ArticleListPage.tsx`（12-articles）、`ArticleDetailPage.tsx`（13-article：段落/标题/法条引用/列表/图片五类块）；路由壳 `app/home.tsx` / `app/articles.tsx` / `app/article.tsx`；`SplashPage` 登录态分流改为首页 |
| G8.3 消息中心两页空态 | `MessagesPage.tsx`（38-messages：空态 + 「通知功能即将开放」+ 找律师入口）、`MessageDetailPage.tsx`（39-message-detail：占位）；路由壳 `app/messages.tsx` / `app/message-detail.tsx`；首页快捷入口保留「消息中心」 |
| 支撑 | `content` / `home` 两个 i18n namespace（此前都是空文件，共 43 个 key）；`routes.ts` 新增 5 条路由与 3 个 href 构造函数 |

---

## 二、G8.1 接口实测（真实请求）

| # | 场景 | 期望 | 实测 |
|---|---|---|---|
| 1 | 游客（无 token）`GET /pub/articles?pageSize=3` | 200，只返回已发布 | `http=200 code=200 total=1` 首条标题为种子文章 |
| 2 | `Accept-Language: ug-Arab-CN`（有标题译文） | 返回维语标题，`langFallback=false` | 标题为维语原文、`fallback=false` |
| 3 | `Accept-Language: bo`（无译文） | 返回简体 + `langFallback=true` | 简体标题、`fallback=true` |
| 4 | 详情 `GET /pub/articles/1` | 正文块数组 + 法条引用块带原文 | `blockCount=9`，types=`paragraph/heading/paragraph/heading/law_ref/paragraph/heading/list/paragraph`；`law_ref.lawContent` = 民法典第六百八十条原文 |
| 5 | 详情 `ug-Arab-CN`（只有标题译文） | 标题维语、**正文降级且 langFallback=true** | 标题维语、`fallback=true` |
| 6 | 不存在 / 未上架 | `401004` | `http=200 code=401004` |

**降级口径**（重要）：标题、摘要、正文**各自**降级——运营可能只录了标题译文，
此时正文仍是中文，必须置 `langFallback=true`，否则等于把中文正文冒充成译文。
列表的 `langFallback` 只看标题是否降级（摘要缺失不影响列表主体）。

---

## 三、G8.2 页面

| 设计稿要求 | 实现 |
|---|---|
| 首页「每日学法」接 `GET /app/articles?limit=3`（`2-A7` 判据） | `HomePage` 调 `getArticles({pageSize:3})`，**没有硬编码文章**；区块有自己的 loading / error（点按重试）/ 空态三态，文章接口挂了不影响搜索与快捷入口 |
| 首页不是假数据 | 页面数据全部来自接口；未实现的功能（搜索、AI 咨询）**点按给「即将开放」提示**，不做静默按钮 |
| 文章列表：分类筛选 + 分页 + 四态 | 分类 5 档（全部 + 4 类，提交固定中文业务值、显示走 i18n）、`hasMore` 加载更多、空态/错误态/加载态齐全；`langFallback` 角标 |
| 文章详情：正文 + 法条引用块 | 五类块分别渲染：paragraph / heading（2、3 级）/ law_ref（浅底卡片展示法名+条号+原文）/ list（有序/无序）/ image（`expo-image` + 说明）；未知块类型忽略不崩 |
| RTL | 全部文本走 `LocalizedText`（自动方向）、数字/时间走 `LtrText`；无 `letterSpacing`、无 left/right 硬编码（ESLint 门禁兜底） |
| 免责声明 | 详情页底部固定「仅作一般性法律知识参考，不构成个案法律意见」 |

---

## 四、G8.3 消息中心（空态 + 入口占位）

| 要求 | 实现 |
|---|---|
| 页面结构按设计稿，数据源为空 | 空态插图（图标）+「暂无通知」+「系统通知、订单提醒、律师回复都会出现在这里」+「通知功能即将开放」 |
| **不建 `notification` 表、不调接口、不做已读/删除/分类** | 两页均无任何请求；数据库无新表（本组零迁移） |
| 入口保留、不因无数据隐藏 | 首页快捷入口有「消息中心」；空态页内有「去找律师」与「返回首页」两个真实出口 |
| 空态不能白屏 | 图标 + 三行文案 + 两个出口 |
| 消息详情页 | 占位页：图标 +「没有可展示的消息」+ 回消息中心；**不硬编码假通知** |

---

## 五、自动化测试与门禁

- `mvn test`：`ArticleServiceTest` 7 项（中文不降级 / 无译文降级 / 有译文用译文 /
  详情块解析 + 法条原文补全 / 部分降级标 fallback / 未上架 401004 / 未知语言按简体）
- 前端门禁：`tsc --noEmit`、`eslint .`（0 告警）、`check-raw-text`（102 文件 0 命中）、
  `check-i18n`（四目录同构 + 缺译已登记 + 占位符一致）全过
- i18n：`content` 23 key + `home` 20 key；三语登记缺译（`.i18n-pending.json`）

---

## 六、偏差与遗留（如实记录）

1. **首页没有底部 tab 导航**：设计稿 `01-home.html` 有 tabbar，但全局导航（tab 布局、路由分组）
   属段 3；段 2 用「顶部头像 + 快捷入口」承担页面跳转。**入口没有隐藏**，只是形态不同。
2. **首页的 AI 咨询卡、搜索框是占位**：AI 属段 3、全局搜索属段 5；点按给「即将开放」提示。
3. **`2-A7` 前半段（PC A-15 录入 → APP 首页可见）待 G9.4**：本组证明「APP 能读到真实库数据 +
   语言降级正确 + 法条引用块渲染」；「从 PC 录一篇新文章、APP 刷新就能看到」需要 A-15 页面，
   在 G9.4 完成后补证据（同一篇文章的完整链路）。
4. **种子文章是 SQL 写入的**：不是后台录入（A-15 未做）。验收文档已注明来源，
   不把它算作「PC 录入」的证据；G9.4 会用真实录入流程再验一次。
5. **文章阅读数不自增**：详情返回当前 `view_count` 但不写入（高频写会带来无意义行锁竞争，
   统计口径应在段 7 用异步任务做）。
6. **封面与图片依赖 OSS 签名**：当前本地存储 `FileStorage.presignDownload` 返回本地签名地址；
   生产 OSS 配置属段 8 部署。签名失败时字段为 null（详情页降级为不显示图，不报错）。
