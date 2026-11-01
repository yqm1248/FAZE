# 第 4 段工作流：文书中心

> **上位文档**：《[法泽FAZE_开发计划总纲_v1.0.md](法泽FAZE_开发计划总纲_v1.0.md)》§5.5（本段边界与锚点）、§4.1/§4.2（页面归属）。
> **位置**：本段是段 4 的**唯一施工文档**。从第一行读到最后一行即可施工。
> **本段规模**：APP **6 页** / PC **1 页**（A-18）/ 表 **4 张** / 接口 **17 个**（App 12 + PC 5）/ 锚点 **8 条** / 任务 **7 组 24 个**。
> **写作依据**：仓库 HEAD `2c6148f`（分支 `UI设计`），2026-11-01 22:3x 实测。**本文档 2026-11-01 当天改过两次范围**，两处都在下面「开工前必读」里写清。

---

## ⚠️ 开工前必读：本段范围改过两次，两次都要知道

本文档 2026-11-01 写，总纲 §5.5 是 2026-10-30 写。**两者不一致，以本文档为准**。而且本文档自己在同一天里被改了两次方向。

### 变更一：文书只做中文（2026-11-01 早）

| 项 | 总纲原文（10-30） | 第一次变更后 |
|---|---|---|
| 段定位 | 「少数民族文字排版的**最终检验场**」 | 用户自助生成法律文书的完整链路 |
| 文书语言 | 四语言各一份模板 | **只有 `zh-Hans`** |
| 模板数量 | 5 类 × 4 语言 = 20 份 | 5 类 × 1 语言 = **5 份** |
| 锚点 `4-A3` | 维吾尔文连写 | 删除，换内容 |
| 锚点 `4-A4` | 藏文叠字 | 删除，换内容 |
| 锚点 `4-A6` | PDF 含 5 个 **AIGC** 隐式标识字段 | 改为文书溯源元数据，**不含 AIGenerated** |

**依据**：法律**并不要求**文书必须中文。《宪法》第 134 条：「在少数民族聚居或者多民族共同居住的地区，应当用当地通用的语言进行审理；**起诉书、判决书、布告和其他文书应当根据实际需要使用当地通用的一种或者几种文字**」；《民事诉讼法》第 11 条同旨。伊犁哈萨克自治州正属该范围。

**但实务上**当事人递交法院的起诉状绝大多数是中文（卷宗归档、对方当事人、上诉审级、法官工作语言都是中文）。负责人把法泽的文书定位于「**能递交给法院的正式文件**」→ 只做中文。

### 变更二：PDF 渲染从 LibreOffice 换成 openhtmltopdf，且不再出 Word（2026-11-01 晚）

| 项 | 第一次变更后 | 第二次变更后（现行） |
|---|---|---|
| 模板格式 | `.docx`（Word 文件） | **HTML 片段 + CSS 子集** |
| Word 渲染 | poi-tl 填 `{{}}` 生成 `.docx` | **不做**（直接渲染 HTML） |
| PDF 渲染 | LibreOffice headless（`soffice --convert-to pdf`） | **openhtmltopdf 1.0.10**（LGPL，内部就是 PDFBox 2.0.x） |
| 是否装 LibreOffice | 要（约 700MB，外部进程） | **不要** |
| 是否要 `poi-tl` | 要 | **不要** |
| 是否出 Word | 出（`4-A1` 原文「下载 Word」） | **不出**（`4-A1` 改为下载 PDF） |
| 锚点 `4-A3` | Word 与 PDF 内容一致 | **删除**，改为「PDF 文本层可选中可搜索」 |
| `document` 表 | 17 字段（含 `word_oss_key`） | **16 字段**（删 `word_oss_key`） |

**依据**：只做中文之后，LibreOffice 唯一不可替代的能力（字形整形 shaping，排阿拉伯系连写与藏文叠字）**用不上了**。而它的代价很实在：装 700MB、单次 1–2 秒、并发要 `-env:UserInstallation` 隔离、Windows 上杀软会拦它写临时文件、首次启动要手工跑一次建 profile。

同时负责人确认「**不需要给用户一份能自己改的 Word**」——这是砍掉 `.docx` 链路的关键前提，因为 **Word 也是从 `.docx` 模板来的**：模板换成 HTML，Word 就没有来源了。

**这次变更换掉的东西**：`poi-tl`（不再需要）、LibreOffice（不再装）、Word 导出（不做）、`document.word_oss_key`（删字段）。
**这次变更没动的东西**：PDFBox 仍在（openhtmltopdf 底层就是它，叠签名、写元数据、转预览图继续用）；`4-A2`/`4-A5`/`4-A6`/`4-A7`/`4-A8` 继续保留；表数、接口数、页面数基本不变（只少 1 个 `format=docx` 分支）。

### 两条「最容易搞错」的话

> **① 不许把「文书只做中文」理解成「界面只做中文」。**
> `AGENTS.md` §1 的四语言是硬约束。六页界面的文案仍要 `t()` + `zh-Hans`/`ug-Arab-CN` 两套齐全。文书**内容**中文，界面**文案**四语言 —— 两件事。

> **② 不许说「openhtmltopdf 被禁用了」。**
> `AGENTS.md` §5 的原文是「禁止使用 `openhtmltopdf` 生成**含少数民族文字**的 PDF（不做 shaping）」——约束的是少数民族文字。本段只做中文，`openhtmltopdf` 不构成违规。但 `AGENTS.md` §2 技术栈那一行的措辞更绝对（写着「不得使用」），**要改**（§九 回写第 1 项）。

### 这三处要回写（不是本文档自己改完就算）

| # | 文档 | 改什么 |
|---|---|---|
| 1 | 总纲 §5.5 | 段定位、模板格式、渲染引擎、锚点 `4-A3`/`4-A4`/`4-A6` |
| 2 | 技术路线 §10.1/§10.2/§10.3 | 生成链路从 `poi-tl + LibreOffice` 改为 `HTML + openhtmltopdf`；§10.3 标注「本段不使用」 |
| 3 | `AGENTS.md` §2 技术栈「文书」行 + §5 禁令 | 从「poi-tl + LibreOffice / openhtmltopdf 不得使用」改为「HTML + openhtmltopdf」 |

---

## 零、开工前必须知道的事（2026-11-01 撰写时实测）

### 零.1 本段的真实起点：段 0、段 1、段 2、段 3 的施工一次都没跑

实测（`E:\dshcode\FAZE`，2026-11-01 22:0x）：

| 事实 | 证据 |
|---|---|
| `services/` 目录**不存在** | `Test-Path services` → False。段 1 承诺的 `faze-api` 骨架代码一行都没有 |
| `apps/mobile-app` 只有 UI 复刻壳，**依赖里没有段 4 需要的库** | 见 §二 第 10 项 |
| 仓库源码里**零字体文件** | `*.ttf/*.otf/*.ttc` 扫描（排除 `node_modules`）= **0**。本段不需要（中文用系统字体） |
| 本机**未安装 LibreOffice** | `Get-Command soffice` 为空。**这不是缺口**——本段不用它 |
| 本机 JDK | `java version "17.0.17" 2025-10-21 LTS` ✅ |

**这意味着本文档的 §二「开工前置检查」是对上游出口的预测性要求，不是实测现状。**

### 零.2 上游承诺了什么（段 4 可直接依赖，不许重造）

| # | 承诺 | 来源 | 段 4 怎么用 |
|---|---|---|---|
| 1 | `task_queue` 的领取是 **CAS + 租约回收** | 段 2 §八.1 第 5 条（原文：「段 4 文书生成、段 7 内容审核可直接复用这套消费模式」） | 文书生成走异步任务 |
| 2 | `POST /app/upload/token` + `GET /app/upload/url?key=` | 段 1 C1 | 上传签名图 PNG |
| 3 | 私有 OSS + 60 秒签名 URL；凭据走环境变量 | 段 1 §零.2 第 5 项 / C1 | 签名图、文书 PDF 都放私有桶 |
| 4 | 余额扣款唯一入口 `deduct()` | 段 2 §八.1 第 1 条 | **本段不用**（文书免费，§零.3 第 6 项） |
| 5 | `R<T>` / JWT / 分页 / 错误码归一化 / `@OperationLog` | 段 0 / 段 1 | 照段 1 的写法 |
| 6 | MP 分页：`mybatis-plus-spring-boot4-starter:3.5.18.1` + `mybatis-plus-jsqlparser:3.5.18.1` | 段 1 §三 A3 + `docs/acceptance/seg1/A3-mp-probe.md` | 照用，**不要再踩 `-spring-boot3-` starter 的坑** |
| 7 | `ArticleNoValidator` + `refs` schema | 段 3 §八.1 第 1/2 条 | **本段未使用**，理由见 §七 第 7 条与 §八.2。这是对上游契约的一次未兑现，**不假装用上** |

### 零.3 已经定了的，本段不再讨论

| # | 事项 | 结论 | 依据 |
|---|---|---|---|
| 1 | 表名 | **`doc_template` / `document` / `signature` / `file_record`** | 技术路线 §5.6/§5.11；数据表规范附录 A |
| 2 | 总纲与 PC 计划里的 `document_template` / `document_instance` | **是错的**，本段统一为 `doc_template` / `document` | 本段裁决 |
| 3 | 文件字段口径 | 统一 **`oss_key`**，不存完整 URL | 数据表规范附录 B L599 已裁决，DDL 未同步 |
| 4 | 文书语言 | **只有 `zh-Hans`**；`lang` 字段保留但只写一个值 | 负责人 2026-11-01 拍板 |
| 5 | 在线编辑 | **不做**。预览页只读；点段落 → 跳回表单页；改完用户自己点「生成」 | 同上 |
| 6 | 文书收费 | **免费，不限次**。不接 `deduct()`，不扣配额 | 同上 |
| 7 | 首批模板 | **5 类全做**：起诉状 / 证据清单 / 劳动合同 / 跨境电商合同 / 借条欠条 | 同上 + 导航树 L304 |
| 8 | 签名笔迹 | 手写笔迹图叠加，**不主张法律效力**。可靠电子签名要 CA，明确不做 | 技术路线 §10.4 |
| 9 | 异步任务 | 走段 2 的 `task_queue`，**不引入 RabbitMQ** | 后端代码规范 §9.2 |
| 10 | **PDF 渲染引擎** | **openhtmltopdf 1.0.10**（LGPL 2.1，内部 PDFBox 2.0.30） | 本段裁决；理由见「变更二」 |
| 11 | **模板格式** | **HTML 片段 + CSS 子集 + `{{占位符}}`**，存库（`doc_template.template_html`），不存 OSS | 本段裁决 |
| 12 | **不出 Word** | `4-A1` 改为「下载 PDF」；`document` 表删 `word_oss_key` | 负责人 2026-11-01 拍板「不要 Word」 |
| 13 | **不装 LibreOffice** | 本段任何环节都不调用 `soffice` | 同上 |
| 14 | 隐式标识 | 文书**不是 AIGC**，不打 `Faze-Content-Type: AIGenerated`。写「文书溯源元数据」 | 本段裁决，理由见下 |
| 15 | A4 边距 | 上 72 / 下 72 / 左 90 / 右 72 pt；正文仿宋 三号 16pt、行距 28pt | 技术路线 §10.5.1 |
| 16 | 签名位置容差 | **≤ 4mm（≈11.34pt）**，由技术侧自定、负责人 2026-11-01 授权 | 见 §四 `4-A5` |
| 17 | 中文正文字体 | **用「仿宋」**（Windows 自带 `simfang.ttf`），**不用「仿宋_GB2312」**（系统不自带，会静默回退） | 本段裁决 |

**第 14 项的理由（最容易做错）**：《人工智能生成合成内容标识办法》约束的是「AI 生成合成内容」。法泽的文书是「用户填表 + 运营配的 HTML 模板 + 占位符文本替换」——**没有任何一步是模型生成的**。给它打 `AIGenerated` 标签属于**虚假标识**，与不打一样是问题。所以删掉 AIGC 字段，保留真实溯源信息（见 §四 `4-A6`）。

### 零.4 本段四条「不许」（写在这里防走样）

| # | 不许 | 为什么 |
|---|---|---|
| 1 | **不许把「文书只做中文」扩大到「界面只做中文」** | `AGENTS.md` §1 定死四语言；六页 UI 文案仍要 `t()` + `zh-Hans`/`ug-Arab-CN` |
| 2 | **不许改框架源码** | 服务端 `services/faze-api` 是新工程不受此限；PC 端基于 RuoYi-Vue3，`AGENTS.md` §3.3 明确「不得修改 RuoYi 框架层代码」 |
| 3 | **不许在业务代码里手写 PDF 坐标画正文** | 这是本段换引擎要防的事。正文排版一律交给 openhtmltopdf 读 HTML；只有**签名图叠加**和**预览图渲染**允许直接用 PDFBox 的坐标 API |
| 4 | **不许把签名图、文书 PDF 放进公开可读的 OSS 路径** | `AGENTS.md` §5 明令；数据表规范 §L3 私有 OSS + 短时签名 URL |

---

## 零之二、2026-11-01 负责人拍板的 12 项（逐项落到哪一节）

| # | 决定 | 落到哪 |
|---|---|---|
| 1 | **文书只做中文**（`zh-Hans`） | §开工前必读变更一；§三 B3 只产 5 份 |
| 2 | **在线编辑不做**：预览页只读，点段落 → 跳回表单页 | §三 E4；§七 |
| 3 | **文书免费不限次** | §三 全线不接 `deduct()`；§七 |
| 4 | **5 类模板全做** | §三 B3；§五.5；§十 第 2 项（内容来源） |
| 5 | **PDF 渲染换 openhtmltopdf，不装 LibreOffice** | §开工前必读变更二；§三 A2/C3；§五.6 依赖表 |
| 6 | **不出 Word** | §四 `4-A1`；§三 A1（`document` 16 字段）；§七 第 2 条 |
| 7 | **模板用 HTML**（不是 `.docx`） | §三 B2（HTML 子集规范）；§三 B3（5 份 `.html`） |
| 8 | **签名位置容差由技术侧定** | §零.3 第 16 项定 ≤4mm；§四 `4-A5` |
| 9 | 预览页点击段落只跳回表单页（不做段落定位） | §三 E4 第 3 条 |
| 10 | 生成流程：跳回表单 → 改 → 用户自己点「生成」 | §三 E4；§四 `4-A1`/`4-A4` |
| 11 | **模板内容从法律官网/官方样式扒** | §三 B3 第 4 条；**有前提问题**，见 §十 第 2 项 |
| 12 | 本段**只出计划，不写代码** | 全篇性质 |

---

## 一、本段目标与出口

**目标（三句）**：把「运营在 PC 后台配一份 HTML 模板 → 用户在 App 填表 → 生成 PDF → 预览/下载/打印/分享 → 手写签名叠到 PDF 上」这条链一次做通；文书的**版式**必须经得起打印与递交，**失败时不留半成品**；把 A-18 做成运营自己就能维护模板的工具（不靠改代码、不发版）。

**本段不追求的东西**：不做少数民族文字（已拍板）、不做 Word 导出、不做在线编辑、不做可靠电子签名、不做法院系统对接、不做 AI 起草文书。

### 一.1 出口（进段 5 的条件）

1. `4-A1` ~ `4-A8` **八条全部通过**，证据在 `docs/acceptance/seg4/`。
2. **5 份 HTML 模板在库**：`doc_template` 有 5 条 `status='active'` 的记录，每条的 `template_html` 是**能渲染出正确 PDF 的完整 HTML**（不是占位字符串）。
3. **渲染链在生产同款环境跑通**：Windows + JDK 17，`openhtmltopdf` 能把含中文的 HTML 渲染成 PDF，且 **PDF 里的中文是可选中、可搜索的文本**（不是图片、不是乱码）。
4. **字体已嵌入**：PDF 的 `/FontDescriptor` 里有 `FontFile2`/`FontFile3`，**换台没装仿宋的机器打开字形不变**。
5. **失败路径已验**：至少 3 种失败（模板占位符不匹配 / 字体文件缺失 / OSS 上传超时）都能给出明确错误码，且 `document` 不留 `generated` 记录、临时文件被清理。
6. **A-18 可用**：运营能在后台新建模板 → 编辑 HTML → 填字段定义 → 启用 → App 端立刻出现该模板（**不改代码、不重启服务、不发版**）。
7. **本段的文档回写完成**：§九 的 8 项，缺一条即未收口（总纲 §6.2 收口五件套）。

### 一.2 反向定义（什么不算出口）

| 看起来像完成，其实不算 | 为什么 |
|---|---|
| 用**硬编码的模板**（直接读仓库里某个 HTML 文件）生成成功 | 违反「模板由 A-18 配置，不在代码里写死」（总纲 §5.5「必须做」第 1 条）。`4-A1` 的路径必须是「选模板 → 填表」，模板来源是库不是文件 |
| PDF 生成成功，但**文字是图片贴上去的** | 不能选中、不能搜索、不能复制，等于扫描件。`4-A3` 专抓这个 |
| PDF 生成成功，但**只看截图没验字体嵌入** | 换台没装仿宋的机器会变字形。`4-A2` 要求字体嵌入 |
| 预览页显示的**不是真 PDF 转的图** | 那验不到 PDF。预览必须来自真实 PDF |
| 签名能叠上去，但坐标**写死**（不随页面尺寸换算） | `4-A5` 验的是「位置偏差」，写死坐标换个模板就错位 |
| 生成失败时 `document` 记 `status='generated'` 但文件为空 | 违反「失败不留半成品」（总纲 §5.5「必须做」第 2 条）；`4-A8` 直接判不通过 |
| 六页能打开，但四态（正常/加载/空/错误）不全 | `AGENTS.md` §7 完成定义的硬要求 |
| **跑通了但产物里还有 `{{}}` 残留** | 说明占位符替换没校验。`4-A1` 必查 |

---

## 二、开工前置检查（逐条勾，不通过不开工）

**19 项，缺一项不许开工。** 前 6 项是段 3 出口承诺，7~19 是本段新增。

| # | 检查项 | 怎么确认 | 不通过怎么办 |
|---|---|---|---|
| 1 | 段 3 已出口（`3-A1`~`3-A8` 全过） | 读 `docs/acceptance/seg3/` 下 8 条证据 | 回段 3 补。**不许跳过** |
| 2 | `services/faze-api` 可启动 | `curl /api/v1/pub/ping` 返回 `{appName,version,...}` | 回段 1 |
| 3 | 段 2 的 `task_queue` 表已建，消费端在跑 | `SELECT * FROM task_queue LIMIT 1`；段 2 定时任务日志有「领取成功」 | 回段 2。**本段文书生成全靠它** |
| 4 | `POST /app/upload/token` + `GET /app/upload/url` 可用 | 上传一张 PNG，拿 key，用签名 URL 取回 | 回段 1 C1 |
| 5 | **`openhtmltopdf` 在本机渲染中文 PDF 实测通过**（**本段第一件要做的事，不是等出来再验**） | 写个 10 行的 `main`，把含中文的 HTML 渲染成 PDF，打开能看到字、能选中 | 见下方「第 5 项怎么做」 |
| 6 | OSS 私有桶可用：能上传、能生成 60 秒签名 URL、**公开访问返回 403** | 浏览器直接开 OSS 原 URL，必须 403 | 向负责人要凭据配置 |
| 7 | RuoYi 后端可启动（8080）、PC 前端可运行 | 段 0 的 `0-A6` 证据 | 回段 0 |
| 8 | **5 类模板的内容有下落**（谁写、什么时候交） | 见 §十 第 2 项。**这一项有前提问题，必须先解决** | 不许「先做机制，内容以后再说」收工 |
| 9 | 中文正文字体确认：`C:\Windows\Fonts\simfang.ttf` 存在 | `Test-Path C:\Windows\Fonts\simfang.ttf` → True | 没有则装仿宋；**不许用「仿宋_GB2312」**（系统不自带） |
| 10 | 移动端依赖齐备（见下方第 10 项） | `npx expo install` 能装上，`tsc --noEmit` 通过 | 缺哪个补哪个 |
| 11 | `apps/mobile-app` 能打 dev-client 包 | 段 0 的 `0-A1` 证据 | 回段 0。**本段要 skia，Expo Go 跑不了** |
| 12 | 段 1 的 JWT 与 `currentUser` 上下文可用 | 带 token 请求 `/api/v1/app/xxx` 返回 200 | 回段 1 |
| 13 | 分页三场景的实现可用（C 端 `list`） | 段 2 的列表接口是同一套 | 回段 2 |
| 14 | `operation_log` 的 AOP 可用（管理端写接口自动审计） | 段 1 验收证据 | 回段 1 |
| 15 | i18n 的 14 个 namespace 目录已建，`t()` 可用 | 段 0 的 `0-A7` 证据 | 回段 0。**六页的 UI 文案靠它** |
| 16 | 页面四态组件（正常/加载/空/错误）可用 | 段 0 的 `0-A3` 证据 | 回段 0 |
| 17 | 本段验收脚本骨架 `deploy/verify-seg4.ps1` 已建 | 文件存在，跑出「全部未通过」 | 没有脚本就没有可重复验收 |
| 18 | **MySQL 凭据可用**（本段要建 4 张表） | `mysql -u<user> -p -e "select 1"` | 段 1 §二 第 8 项标「实测当前缺」→ 若仍未给，建表无法开工 |
| 19 | **确认不再需要 Word 导出**（这条要留痕） | 负责人 2026-11-01 已定「不要 Word」 | 若日后要恢复，见 §八.2 |

### 第 5 项怎么做（openhtmltopdf 中文渲染，动静最小的一次验证）

**为什么把它列为开工前置**：这整段的输出质量押在「openhtmltopdf 能不能把中文排好」上。它是个 **2023 年已归档的项目**（最后版本 1.0.10），不是活跃维护的库。**先跑通 10 行代码再谈别的**，别等做到 C 组才发现它排不了中文。

```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.openhtmltopdf</groupId>
  <artifactId>openhtmltopdf-pdfbox</artifactId>
  <version>1.0.10</version>
</dependency>
```

```java
// 最小验证：中文 + 换行 + 分页 + 字体嵌入
String html = """
    <html><head><style>
      @page { size: A4; margin: 72pt 72pt 72pt 90pt; }
      body { font-family: 'FangSong'; font-size: 16pt; line-height: 28pt; }
    </style></head><body>
      <h1>民事起诉状</h1>
      <p>原告：马合木提·艾山，男，1985年3月生，住伊宁市……</p>
      <p>诉讼请求：一、判令被告支付欠款人民币 50,000 元……</p>
    </body></html>
    """;

try (OutputStream os = new FileOutputStream("out.pdf")) {
    PdfRendererBuilder b = new PdfRendererBuilder();
    b.useFastMode();
    b.useFont(() -> new FileInputStream("C:/Windows/Fonts/simfang.ttf"), "FangSong", 400,
              FontStyle.NORMAL, true);           // 关键：注册中文字体
    b.withHtmlContent(html, null);
    b.toStream(os);
    b.run();
}
```

**验收这六条**：
- [ ] PDF 打开能看到中文（不是方块、不是空白）
- [ ] 文字**可以选中**（用 PDF 阅读器拖选一段，能复制出来）
- [ ] 标题字号、正文字号、行距与 CSS 一致
- [ ] **换行正确**（长段落自动折行，不溢出页面）
- [ ] `@page` 的边距生效（量：上 72pt / 下 72pt / 左 90pt / 右 72pt）
- [ ] 字体**已嵌入**（PDFBox 读 `/FontDescriptor` 有 `FontFile2`）

**若第 5 项失败**（中文出方块/乱码/不嵌入），两条退路：
- 退路一：改用 **flying-saucer-pdf-openpdf**（`org.xhtmlrenderer:flying-saucer-pdf-openpdf`，LGPL）
- 退路二：回到 **`.docx` + LibreOffice**（即变更一的状态），但需要负责人重新确认「不要 Word」这个决定可以推翻

### 第 10 项：移动端必须装齐的依赖

| 包 | 用途 | 装法 |
|---|---|---|
| `expo-print` | 打印（`4-A7`） | `npx expo install expo-print` |
| `expo-sharing` | 系统分享面板（`4-A7`） | `npx expo install expo-sharing` |
| `expo-file-system` | 下载 PDF 到本地（`4-A7`） | `npx expo install expo-file-system` |
| `expo-media-library` | 存相册（仅图片；PDF 走分享面板） | `npx expo install expo-media-library` |
| `@shopify/react-native-skia` | 手写签名板（`4-A5`） | `npx expo install @shopify/react-native-skia` |
| `react-native-gesture-handler` | 拖拽定位签名（`4-A5`） | `npx expo install react-native-gesture-handler` |
| `react-native-reanimated` | 同上（手势动画） | `npx expo install react-native-reanimated` |
| `react-native-view-shot` | 把手写板导出 PNG | `npx expo install react-native-view-shot` |

**版本对齐**：全部用 `npx expo install`（按 SDK 54 兼容矩阵选版本），**不要用 `npm install` 手写版本号**。

**skia 需要 dev-client**：原生模块，Expo Go 跑不了。段 0 的 `0-A1` 已经要打 dev-client 包，不是本段新增成本，但**必须确认段 0 真的打了**（第 11 项）。

---

## 三、任务分组（7 组 / 24 个任务）

**编号规则**：任务是 `A1`~`G2`；验收锚点是另一套 `4-A1`~`4-A8`。**两套编号不通用**，引用时必须带前缀。

**组与组之间设检查点，组内连续推进。**

| 组 | 任务 | 这一组解决什么 | 组末检查点（过不了不许开下一组） |
|---|---|---|---|
| **A 地基**（4） | A1 → A2 → A3 → A4 | 4 张表、HTML→PDF 渲染通道、文件登记、任务表接入 | `flyway:migrate` 出 4 张表；**10 行代码渲染出可选中中文 PDF**；`task_queue` 能捞到 `DOC_GENERATE` |
| **B 模板**（3） | B1 → B2 → B3 | A-18 页面、`field_schema` + HTML 子集规范、5 份 HTML | 后台新建模板 → App 端立刻可见（不重启、不发版） |
| **C 生成内核**（4） | C1 → C2 → C3 → C4 | 草稿、占位符替换、PDF 生成、失败清理 | 一次完整生成跑通，失败路径也验过 |
| **D 签名**（3） | D1 → D2 → D3 | 签名图库、PDFBox 叠图、元数据 | 叠完章的 PDF 能打开、位置正确 |
| **E APP 六页**（6） | E1 ~ E6 | 六页 + 四态 + 多语言 | 六页都能进、四态齐全、`ug` 下无布局错 |
| **F 后处理**（2） | F1 → F2 | 下载/分享/存相册/打印、重新生成 | 四项都能用 |
| **G 收口**（2） | G1 → G2 | 验收脚本、证据与回写 | 8 条锚点有证据、文档回写完成 |

---

### A 组：地基（4 个任务）

#### A1 · 四张表 DDL 定稿与 Flyway 迁移

**做什么**：建 `doc_template`（13 字段）/ `document`（16 字段）/ `signature`（15 字段）/ `file_record`（14 字段），进 Flyway 迁移，空库可重放。

**怎么做**：

1. 迁移文件按《数据表规范》§8.1 的段 4 区段命名：`V400__create_document_tables.sql`。**一个文件只做一件事**：本文件只建表，不灌数据（5 份模板是运营上传的，不是种子数据）。
2. 表头注释必须含：所属段 / 做什么 / 依赖 / 可回滚。
3. 四张表**逐字按下面写，不许自己加字段**。

   **`doc_template`（13 字段，D 类基础数据）**

   | 字段 | 类型 | 说明 |
   |---|---|---|
   | `id` | `BIGINT PK AUTO_INCREMENT` | 主键 |
   | `code` | `VARCHAR(50) NOT NULL` | 模板编码，如 `LAWSUIT-001` |
   | `name` | `VARCHAR(100) NOT NULL` | 模板名 |
   | `category` | `VARCHAR(50)` | 分类：`诉讼文书`/`合同文书`/`劳动文书`/`跨境电商`（导航树 L303） |
   | `template_html` | `MEDIUMTEXT NOT NULL` | **模板 HTML 全文**（含 `{{占位符}}` 与 `<style>`）。**存库不存 OSS**：HTML 通常 5–50KB，入库便于版本管理与占位符扫描 |
   | `preview_oss_key` | `VARCHAR(500)` | 模板预览缩略图（渲染后第一页转 PNG），A-18 列表用 |
   | `field_schema` | `JSON` | 字段定义，schema 见 B2 |
   | `lang` | `VARCHAR(20) NOT NULL DEFAULT 'zh-Hans'` | **本段只写 `zh-Hans`**（§零.3 第 4 项） |
   | `version` | `INT NOT NULL DEFAULT 1` | 模板版本；每次保存 `template_html` 就 +1，**旧版本另存**（见下「版本怎么留痕」） |
   | `sort_order` | `INT DEFAULT 0` | App 端排序 |
   | `status` | `VARCHAR(20) NOT NULL DEFAULT 'draft'` | `draft` / `active` / `inactive`（设计稿三态） |
   | `created_at` | `DATETIME NOT NULL` | 应用层填 UTC |
   | `updated_at` | `DATETIME NOT NULL` | 同上 |

   唯一键：`uk_template_code_lang_version (code, lang, version)`。
   索引：`idx_template_status_sort (status, sort_order)`（App 列表查）。

   > **版本怎么留痕（关键，`4-A4` 靠它）**：改模板**不能原地改 `template_html` 就完事**——那样历史文书重渲染会变内容。做法：保存时分两步：
   > 1. `INSERT` 一条新行：`(code, lang, version+1)`，写入新 HTML，`status='draft'`
   > 2. 把旧行 `status='inactive'`
   >
   > **旧行永不删、永不改**。`document.template_version` 指向生成时用的那一版，历史文书重渲染时按 `(template_id, template_version)` 找到那条旧行。这与 `.docx` 方案的「不可变 `oss_key`」是同一个思路，只是载体从 OSS 换成了表行。

   > **与数据表规范的一处偏离**：§4.1 要求 D 类表必须有 `is_enabled`。本表**不加**，用 `status` 三态代替——两个字段并存会出现 `status='active'` 但 `is_enabled=0` 的矛盾状态。登记在 §九 第 5 项。

   > **删掉了技术路线的 `template_url` / `oss_key`**：模板不再是文件，是库里的 HTML 文本。按数据表规范附录 B L599「业务表存 `oss_key`，不存完整 URL」，剩下的文件类字段只有 `preview_oss_key`。

   **`document`（16 字段，C 类状态机）**

   | 字段 | 类型 | 说明 |
   |---|---|---|
   | `id` | `BIGINT PK AUTO_INCREMENT` | 主键 |
   | `user_id` | `BIGINT NOT NULL` | 归属用户 |
   | `template_id` | `BIGINT` | 引用 `doc_template.id`；可空（模板被停用后历史文书仍在） |
   | `template_version` | `INT` | 生成时用的模板版本（复现依据） |
   | `lang` | `VARCHAR(20) NOT NULL DEFAULT 'zh-Hans'` | 本段固定 `zh-Hans` |
   | `title` | `VARCHAR(200) NOT NULL` | 文书标题（取模板名 + 原告名） |
   | `doc_type` | `VARCHAR(30)` | 冗余模板分类，列表筛选用 |
   | `form_data` | `JSON` | 当前表单数据（草稿可改） |
   | `form_snapshot` | `JSON` | **生成瞬间**的表单快照，不可变 |
   | `status` | `VARCHAR(20) NOT NULL DEFAULT 'draft'` | `draft` / `generated` / `signed` / `failed` |
   | `pdf_oss_key` | `VARCHAR(500)` | 生成的 PDF |
   | `signed_pdf_oss_key` | `VARCHAR(500)` | 叠完签名的 PDF |
   | `lawyer_id` | `BIGINT` | 分享给律师时记；**本段只写不读**（律师端在段 5） |
   | `created_at` | `DATETIME NOT NULL` | 应用层填 UTC |
   | `updated_at` | `DATETIME NOT NULL` | 同上 |
   | `deleted_at` | `DATETIME` | 软删 |

   索引：`idx_document_user (user_id, deleted_at, created_at)`、`idx_document_template (template_id, template_version)`。

   > **比原方案少 1 个字段**：删掉了 `word_oss_key`（不出 Word，§零.3 第 12 项）。

   > **加了一个状态值 `failed`**：让 `4-A8`（失败不留半成品）可判——「失败」必须是一个**能被查询的状态**，不能靠「没有记录」推断。

   **`signature`（15 字段，B 类追加型）**

   逐字按技术路线 §5.6 L1469–1487，**一字不改**：`id` / `user_id` / `image_oss_key` / `document_id` / `page_no` / `pos_x_pt` / `pos_y_pt` / `width_pt` / `height_pt` / `base_page_w_pt` / `base_page_h_pt` / `document_hash` / `hash_algo` / `signed_at` / `created_at`。
   索引：`idx_signature_user (user_id, document_id)`、`idx_signature_document (document_id)`。

   > **这张表承担两个语义，必须知道**：`document_id IS NULL` 的行是「用户存的签名图」（28-doc-seal 管理页用）；非空的行是「这份文书上的这枚签名」（含坐标与哈希）。**所有查询必须显式带 `document_id IS NULL` 或 `IS NOT NULL`**，不能写成「按 user_id 查」。这是本段最容易写错的一处（§六 R5）。

   **`file_record`（14 字段，A 类业务实体）**

   逐字按技术路线 §5.11 L2063–2082 抄：`id` / `oss_key` / `bucket` / `original_name` / `content_type` / `size_bytes` / `md5` / `biz_type` / `biz_id` / `uploader_id` / `is_private` / `status` / `created_at` / `deleted_at`。
   唯一键 `uk_file_oss_key (oss_key)`；索引 `idx_file_biz (biz_type, biz_id)`、`idx_file_uploader (uploader_id, created_at)`、`idx_file_orphan (status, created_at)`。

   > 段 1 已裁决不用它（实名影像直接存 `realname_auth` 的字段）。本段是它的**第一次真正使用**：签名图、文书 PDF、模板预览图都登记进来。`biz_type` 取值：`doc_template` / `document` / `signature`。

4. **回滚脚本**：`V400_1__drop_document_tables.sql`（只写 `DROP TABLE`，手动执行，**不进 `flyway_schema_history`**）。DROP 顺序 `signature` → `document` → `doc_template` → `file_record`。

**前置**：`services/faze-api` 可启动、Flyway 通道可用（§二 第 18 项 MySQL 凭据）。

**产出物**：
- `services/faze-api/src/main/resources/db/migration/V400__create_document_tables.sql`
- 同上目录 `V400_1__drop_document_tables.sql`
- `docs/acceptance/seg4/A1-ddl.md`（建表输出 + `SHOW CREATE TABLE` 四张表的完整结果）

**自查（全过才收口）**：
- [ ] `flyway:migrate` 在**空库**执行成功，`flyway_schema_history` 有 `V400`
- [ ] 字段数 13 / 16 / 15 / 14，与上表一致
- [ ] 字段注释齐全（`information_schema.COLUMNS.COLUMN_COMMENT` 非空）
- [ ] 没有外键约束（数据表规范要求软关联）
- [ ] `template_html` 是 `MEDIUMTEXT` 不是 `TEXT`（TEXT 上限 64KB，5 万字的模板可能超）
- [ ] `doc_template.lang` 默认值是 `'zh-Hans'` 不是 `'zh-CN'`
- [ ] 回滚脚本能跑通

**常见失败**：
1. 用 `flyway:clean` 代替重放 —— 会清掉段 0~3 的数据，**禁止**。
2. 表名写回 `document_template` —— 那是总纲的旧名（§零.3 第 2 项）。
3. 模板改版时**原地 UPDATE `template_html`** —— 历史文书重渲染就变内容了，`4-A4` 直接失败。
4. `signature.document_id` 加了 `NOT NULL` —— 签名库就存不进去了。

---

#### A2 · HTML → PDF 渲染通道（`PdfRenderer`）

**做什么**：把「HTML 字符串 + 表单数据」渲染成 PDF 的封装，处理中文字体、CSS 约束、字体嵌入检查。

**怎么做**：

1. **依赖**（登记进 `docs/architecture/依赖清单.md`，`AGENTS.md` §4 第 12 条）：

   ```xml
   <dependency>
     <groupId>com.openhtmltopdf</groupId>
     <artifactId>openhtmltopdf-pdfbox</artifactId>
     <version>1.0.10</version>
   </dependency>
   ```

   这一个依赖会把 `pdfbox:2.0.30` 一起带进来，**不要另外再声明 PDFBox 版本**，避免版本打架。

2. **类**：`infra/pdf/PdfRenderer.java`（`AGENTS.md` §3.2 规定 PDF 相关放 `infra/`）。

3. **字体注册（中文唯一的坑）**：

   ```java
   PdfRendererBuilder b = new PdfRendererBuilder();
   b.useFastMode();
   // 字体路径从 sys_config 的 doc.font.path 读，取不到则读环境变量 FAZE_FONT_PATH，
   // 都没有 → 启动时校验失败，不许硬编码 C:\Windows\Fonts
   b.useFont(() -> new FileInputStream(fontPath), "FangSong", 400, FontStyle.NORMAL, true);
   b.useFont(() -> new FileInputStream(fontBoldPath), "FangSong", 700, FontStyle.BOLD, true);
   b.withHtmlContent(xhtml, null);
   b.toStream(out);
   b.run();
   ```

   - **必须注册两个字重**：Regular 400 + Bold 700。只注册 Regular 时，CSS 里的 `font-weight: bold` 会**静默回退**（不加粗，也不报错）。
   - 仿宋的粗体在 Windows 上不一定有单独文件（`simfang.ttf` 只有常规），**退路**：用 `font-synthesis` 让浏览器式合成，或在 CJK 里用黑体 `simhei.ttf` 承担粗体。

4. **XHTML 约束（必须知道）**：openhtmltopdf 的 `withHtmlContent` 用 **XML 解析器**，要求 **well-formed XHTML**：

   | 写法 | 可以吗 |
   |---|---|
   | `<br/>` | ✅ |
   | `<br>` | ❌ 解析失败 |
   | `<meta charset="utf-8">` | ❌ 要写成 `<meta charset="utf-8" />` |
   | `&nbsp;` | ✅（是 XML 预定义实体） |
   | `&` 裸用 | ❌ 必须写 `&amp;` |
   | 属性不带引号 | ❌ |

   这条要写进 B2 的「HTML 子集规范」，`4-A8` 的一个失败场景就是「模板不是 well-formed」。

5. **字体嵌入检查**（`4-A2` 的判据）：

   ```java
   try (PDDocument doc = Loader.loadPDF(pdfFile)) {
       for (PDPage page : doc.getPages()) {
           for (COSName n : page.getResources().getFontNames()) {
               PDFont f = page.getResources().getFont(n);
               // f.isEmbedded() == false → 记警告，写进渲染报告，不算成功
           }
       }
   }
   ```

6. **超时与资源**：进程内渲染，不需要外部进程超时。但要给它一个**单次上限**（如 10 秒）防止畸形 HTML 卡死，用 `CompletableFuture.orTimeout`。

**前置**：§二 第 5 项已验通；§二 第 9 项字体文件存在。

**产出物**：
- `services/faze-api/src/main/java/com/faze/infra/pdf/PdfRenderer.java`
- 单测：`PdfRendererTest`（正常中文 / 超长段落分页 / 非法 XHTML / 字体缺失 / 粗体）
- `docs/acceptance/seg4/A2-renderer.md`（4 个用例的输出 + 字体嵌入检查输出）

**自查**：
- [ ] 渲染出的 PDF 中文可选中（拖选能复制出原文）
- [ ] 分页正确：超长内容自动分到第 2 页，页眉页脚不压正文
- [ ] 粗体真的加粗了（不是静默回退）
- [ ] 非法 XHTML（`<br>` 无斜杠）→ 抛明确异常，不是静默出空 PDF
- [ ] 字体文件不存在 → 抛明确异常（对应错误码 `506003`）
- [ ] 字体全部嵌入（检查器报 0 个未嵌入）

**常见失败**：
1. **只注册 Regular 不注册 Bold** —— `font-weight: bold` 静默失效，页面上看不出来，但起诉状标题不加粗是明显的版式问题。
2. 模板 HTML 里有 `<br>` 不带斜杠 → XML 解析失败，报错信息是「XML 解析错误 at line 1」，看不出是 `br` 的问题。
3. 用 `simfang.ttf` 当粗体注册（同一个文件注册两次不同字重）—— 不会加粗，只是不报错。
4. 没设单次上限 → 畸形 HTML（如 10 万层嵌套）把线程卡死。

---

#### A3 · 文件登记与私有 OSS 通道

**做什么**：把本段产生的文件（文书 PDF、带签 PDF、签名 PNG、模板预览图）登记进 `file_record`，统一走私有桶 + 60 秒签名 URL。

**怎么做**：
1. **上传通道复用段 1**：`POST /app/upload/token` + `GET /app/upload/url`。**本段不新增上传接口。**
2. **服务端产生的文件（PDF）**：走服务端直接 `PutObject`，上传后调 `FileRecordService.record(...)` 登记。
3. **`biz_type` 取值固定三个**：

   | `biz_type` | `biz_id` | 用途 |
   |---|---|---|
   | `doc_template` | `doc_template.id` | 模板预览图 |
   | `document` | `document.id` | 文书的 PDF / 带签 PDF |
   | `signature` | `signature.id` | 签名 PNG |

4. **签名 URL 有效期 60 秒**，`4-A7` 的下载每次调用重新签发，不缓存 URL。

**前置**：§二 第 6 项 OSS 私有桶可用。

**产出物**：`infra/oss/FileRecordService.java`、`docs/acceptance/seg4/A3-oss.md`
**自查**：
- [ ] 三类文件都能上传并登记，`file_record.oss_key` 与 OSS 实际对象一致
- [ ] OSS 原始 URL 直接访问 → **403**
- [ ] 60 秒签名 URL → 200；61 秒后再用 → 403
- [ ] 日志里**不出现**完整 `oss_key`（数据表规范 §L4）

**常见失败**：把完整 URL 存进 `*_oss_key`；签名 URL 设成 1 小时方便调试就忘了改；`log.info` 打 ossKey。

---

#### A4 · `task_queue` 接入 `DOC_GENERATE`

**做什么**：把文书生成注册成段 2 任务表的一种任务类型，走 CAS 领取 + 租约回收 + 指数退避。

**怎么做**：
1. `task_type = 'DOC_GENERATE'`，`biz_no = document.id`。
2. **投递时机**：`POST /app/documents/{id}/generate` **只做三件事**：校验表单 → `document.status='draft'` → 插一条任务，**立即返回 taskId**（`<300ms`）。
3. **消费端**：照段 2 的消费模式（CAS 领取 → 租约 → 指数退避 → `retry_count>=5` 进死信）。**不重写消费框架**，只注册 handler。
4. **handler**：读 `document` + `doc_template` → C2 占位符替换 → C3 渲染 PDF → 上传 OSS → 更新 `status='generated'` 与 `pdf_oss_key`。任何一步失败 → `status='failed'` + `last_error`。
5. **轮询**：`GET /app/documents/{id}` 返回 `status`，App 端 3 秒轮询到 `generated`/`failed`。

**前置**：段 2 的 `task_queue` 在跑（§二 第 3 项）。
**产出物**：`modules/document/task/DocGenerateHandler.java`、`docs/acceptance/seg4/A4-task.md`
**自查**：
- [ ] 投递后接口立即返回（`curl -w %{time_total}` < 300ms）
- [ ] 消费端 5 秒内捞到并执行
- [ ] 人为抛异常 → `retry_count` 递增，`next_retry_at` 按 2^n 分钟退避
- [ ] `retry_count` 到 5 → 进死信
- [ ] 同一 `document.id` 连投 3 次 → 不会生成 3 份互相覆盖的文件（幂等：`biz_no` 去重）

**常见失败**：在 Controller 里同步渲染（接口变慢，事务被拖死）；消费不加 `lock_expire_at`（进程崩了任务永挂）；幂等没做（用户连点两次，第二次覆盖第一次的 `pdf_oss_key`，`file_record` 留孤儿）。

---

### B 组：模板（3 个任务）

#### B1 · PC A-18 文书模板管理页

**做什么**：运营在 RuoYi 后台维护模板：列表、新建、**编辑 HTML**、填字段定义、启停、预览。

**怎么做**：

1. **页面位置**（`AGENTS.md` §3.3）：`pc/ruoyi-ui/src/views/biz/document/template/index.vue` + `edit.vue`，API 在 `src/api/biz/document/template.js`。

2. **5 个接口**：

   | # | 方法与路径 | 用途 |
   |---|---|---|
   | 1 | `GET /admin/doc-templates?type=&status=&lang=` | 列表（PC 的 `rows` 分页） |
   | 2 | `GET /admin/doc-templates/{id}` | 详情（含 `template_html` 与 `field_schema`） |
   | 3 | `POST /admin/doc-templates` | 新建（`version=1`） |
   | 4 | `PUT /admin/doc-templates/{id}` | 保存 → **生成新版本行**（见 A1「版本怎么留痕」），不原地改 |
   | 5 | `POST /admin/doc-templates/{id}/preview` | 用当前 HTML + 示例数据渲染一页预览 PNG，返回签名 URL |

3. **HTML 编辑器（这是本段 A-18 的核心）**：**不用富文本编辑器**。
   - 富文本编辑器（TinyMCE/Quill）会生成脏 HTML（大量 inline style、`<span>` 嵌套、非 well-formed），**喂给 openhtmltopdf 必炸**。
   - 用**代码编辑器**：`monaco-editor`（VS Code 的内核）或 `codemirror`，开 HTML 语法高亮。运营编辑的是模板结构，不是日常文案。
   - **左右分栏**：左边改 HTML，右边实时预览（调接口 5 渲染 PNG）。这是唯一能让运营看懂改动效果的方式。

4. **保存前的服务端校验**（不通过不许保存，不能等到生成时才炸）：
   - ✅ **well-formed XHTML**（用 XML 解析器试解析一遍）
   - ✅ 含 `<style>` 或能引用外部 CSS（本段要求 CSS 内嵌在模板里，见 B2）
   - ✅ 占位符 `{{xxx}}` 与 `field_schema.fields[].key` **一一对应**（多一个少一个都拒）
   - ✅ 不含 `<script>`（安全，且 openhtmltopdf 也不执行）
   - ✅ 体积 < 200KB（超过就说明有问题）

5. **按设计稿 `design/admin/17-doc-template.html` 做，但有 4 处不实现**（照段 3 先例：设计稿有、功能不做要明说）：
   - ❌ 「适用法条」列 —— 本段不做文书法条引用（§七 第 7 条），删掉不留空列
   - ❌ 「导入模板」「批量导出模板」两个按钮 —— 非本段出口，禁用 + tooltip「暂未开放」
   - ❌ 「上传 .docx」——模板不再是文件
   - ✅ 「新建模板」「编辑」「启停」「查询」「重置」照做

6. **不修改 RuoYi 框架层**：新增菜单走 `sys_menu` 一条记录，不改 `src/layout`、`src/router`。

**前置**：段 0 的 RuoYi 可登录（§二 第 7 项）；A1 建表完成；A2 渲染通道可用（预览要用）。
**产出物**：`index.vue` + `edit.vue` + `template.js` + 后端 `AdminDocTemplateController` + `docs/acceptance/seg4/B1-a18.md`
**自查**：
- [ ] 能新建模板、编辑 HTML、填字段定义、启用
- [ ] 启用后**不重启、不发版**，App 端 `GET /app/doc-templates` 立刻能看到
- [ ] 右侧实时预览能出图
- [ ] 保存时：非法 XHTML 被拒、占位符与 schema 不匹配被拒、含 `<script>` 被拒
- [ ] 改一次 → `version` 从 1 变 2，**旧版本行仍在**（`status='inactive'`）
- [ ] 没有改任何 `src/layout` / `src/router` / `src/store` 下的框架文件（`git status` 证明）
- [ ] 写接口都标了 `@OperationLog`

**常见失败**：用富文本编辑器（生成的 HTML 不 well-formed）；保存时原地 UPDATE（`4-A4` 失败）；改 RuoYi 框架文件让菜单出现（违反 `AGENTS.md` §3.3）；忘记 PC 分页是 `rows` 不是 `list`。

---

#### B2 · `field_schema` + HTML 子集规范

**做什么**：定死两件事：①`field_schema` 的 JSON 结构（表单怎么渲染、占位符怎么替换）；②**模板 HTML 允许用哪些标签和 CSS**（openhtmltopdf 支持有限，不约束就会踩空）。

**怎么做**：

1. **`field_schema` 结构**：

   ```json
   {
     "version": 1,
     "fields": [
       { "key": "plaintiff_name", "label": "原告姓名", "type": "text",
         "required": true, "maxLength": 50, "placeholder": "请输入原告姓名" },
       { "key": "plaintiffs", "label": "原告信息", "type": "array",
         "required": true, "minItems": 1, "maxItems": 5,
         "itemSchema": { "fields": [ /* ... */ ] } }
     ]
   }
   ```

2. **`type` 只允许 6 种**：`text` / `textarea` / `number` / `date` / `select` / `array`。**不许扩展**（每个新类型都要在 App 端加渲染分支）。

3. **占位符规则**：
   - 文本字段：`{{field_key}}` → 替换为该字段值（**必须 HTML 转义**，见 C2）
   - array 字段：用 `{{#field_key}} ... {{/field_key}}` 循环块，块内用 `{{item.xxx}}`

4. **HTML 子集白名单（这是 openhtmltopdf 的硬约束，写死在这里）**：

   **允许的标签**：`html` `head` `style` `body` `div` `p` `span` `h1`~`h6` `br` `hr` `table` `thead` `tbody` `tr` `td` `th` `ul` `ol` `li` `strong` `em` `u` `img`（`src` 只能是 OSS 签名 URL 或内嵌 data URI）

   **禁止的标签**：`script`（安全 + 不执行）、`iframe`、`form`、`input`、`link`（不加载外部 CSS）、`video`、`audio`

   **允许的 CSS 属性**：`font-family` `font-size` `font-weight` `font-style` `line-height` `text-align` `text-indent` `letter-spacing` `color` `background-color` `margin*` `padding*` `border*` `width` `height` `page-break-before` `page-break-after` `page-break-inside` `@page`（`size` + `margin`）

   **禁止的 CSS**：`flex` / `grid`（**openhtmltopdf 不支持**，写了会被忽略且不报错，版式静默错乱）、`@media`、`position: fixed`（分页时会跑位）、`transform`

   > **为什么必须写这张白名单**：openhtmltopdf 的 CSS 支持停在 CSS 2.1 + 少量 CSS3，**flexbox / grid 完全不支持**。不明确禁，运营第一版模板就会用 `display: flex` 做两栏，然后发现「怎么变成竖排了」但没有任何报错。**禁用 flex/grid 用 `table` 布局代替。**

5. **校验器**：`TemplateHtmlValidator` 同时校验「HTML 是否 well-formed」「是否只用了白名单标签/属性」「占位符与 schema 是否一一对应」。三个方向都要测。

**前置**：B1 的页面能保存；A2 渲染通道可用。

**产出物**：
- `modules/document/dto/FieldSchema.java`（含 Jackson 反序列化）
- `modules/document/validator/TemplateHtmlValidator.java` + 单测（well-formed / 非法标签 / flex / 占位符多 / 占位符少 / script）
- `docs/acceptance/seg4/B2-schema.md`

**自查**：
- [ ] 6 种 type 各有一份示例 schema，都能被解析
- [ ] 模板用 `display: flex` → 被明确拒绝（不是静默忽略）
- [ ] 模板含 `<script>` → 被拒绝
- [ ] 模板 `<br>` 不带斜杠 → 被拒绝并提示「第 N 行」
- [ ] 占位符与 schema 差一个 → 被拒绝并指出差哪个
- [ ] array 超过 `maxItems` → 明确报错，不静默截断

**常见失败**：白名单写成黑名单（漏了 `display:grid`）；校验只在保存时做、生成时不复查（模板被直接改库就绕过了）；占位符校验只比数量不比名字。

---

#### B3 · 5 份中文 HTML 模板的产出与验收

**做什么**：5 类模板各一份真实可用的 HTML（含 `{{}}` 占位符与内嵌 CSS），版式符合 A4 规范。

**怎么做**：

1. **5 份清单**（导航树 L304 + 负责人「五类全做」）：

   | # | 模板 | `code` | `category` | 关键字段（至少） |
   |---|---|---|---|---|
   | 1 | 民事起诉状 | `LAWSUIT-001` | 诉讼文书 | 原告（多）、被告（多）、诉讼请求、事实与理由、受理法院、具状日期 |
   | 2 | 证据清单 | `EVIDENCE-001` | 诉讼文书 | 案由、证据条目（多：名称/来源/证明目的）、提交人、日期 |
   | 3 | 劳动合同 | `LABOR-001` | 劳动文书 | 甲方、乙方、岗位、期限、薪资、工作地点、签订日期 |
   | 4 | 跨境电商合同 | `CROSSBORDER-001` | 跨境电商 | 买方、卖方、商品、数量、单价、总价、交货方式、争议解决 |
   | 5 | 借条欠条 | `IOU-001` | 合同文书 | 出借人、借款人、金额（大写/小写）、利率、还款日期、担保 |

2. **版式必须按技术路线 §10.5.1**：A4（595×842pt），上 72 / 下 72 / 左 90 / 右 72 pt，正文仿宋 三号 16pt、行距 28pt。**装订线在左**（左边距更大）。用 `@page { size: A4; margin: 72pt 72pt 72pt 90pt; }` 表达。

3. **两份模板要区分「固定条款」和「可填字段」**（这是只做 5 份模板时最容易设计错的地方）：
   - 起诉状、证据清单、借条：**基本是填空**，字段占主体
   - **劳动合同、跨境电商合同：大量固定条款**（法定的必备条款不能改），只有甲方乙方、岗位、薪资、期限等是变量
   - 固定条款在 HTML 里**写死**（运营不要改，改了可能有法律风险），可填字段才是 `{{}}`
   - 这两份模板的 `field_schema` 字段数会显著少于「全文都是字段」的设计

4. **内容来源（有前提问题，见 §十 第 2 项）**：
   - **起诉状 / 证据清单**：以最高人民法院《民事诉讼文书样式》的结构为基准
   - **劳动合同**：以人社部《劳动合同（示范文本）》为基准
   - **跨境电商合同 / 借条欠条**：**官方没有统一示范文本**（借条只有《民法典》第 667 条 + 最高法民间借贷司法解释的要素要求；跨境电商合同只有商会/平台的各自版本）→ **这两份由法泽拟定，模板上必须标注「非官方文本」**
   - ⚠️ **本机 `web_fetch` 对外网全被拦截**（实测：`npc.gov.cn`、`github.com`、`spring.io` 等均报 `resolves to a non-public IP address`），**技术侧拿不到官方原文**，只能按已知结构写 + 标注「需人工核对一次」

**前置**：B2 的 HTML 子集规范定稿；§二 第 8/9 项。
**产出物**：
- `assets/doc-templates/` 下 5 个 `.html`（**源文件进仓库**；运行时以库里的 `template_html` 为准）
- 每份模板的字段对照表（模板里每个 `{{key}}` ↔ schema 的 `field key`）
- `docs/acceptance/seg4/B3-templates.md`（5 份模板渲染出的首页截图 + 字段对照表 + 内容来源标注）

**自查**：
- [ ] 5 份模板的 `{{}}` 集合与对应 schema 的 `fields[].key` **完全相等**
- [ ] 5 份都能用示例数据渲染出**无残留 `{{}}`** 的 PDF
- [ ] 5 份都是 well-formed XHTML（校验器过）
- [ ] 没有用 flex/grid（校验器过）
- [ ] 打印出来 A4 四边边距与规范一致
- [ ] 5 份都进库且 `status='active'`
- [ ] 劳动合同与跨境电商合同的固定条款部分**没有被做成占位符**

**常见失败**：
1. 从网上抄一个 HTML 起诉状模板，里面用了 `flex` 做两栏 → 渲染成竖排。
2. 劳动合同把整段法定条款做成 `{{content}}` 一个字段 → 等于让用户自己写劳动合同，法律风险极高。
3. 占位符拼错（`{{plaintiffName}}` vs schema 的 `plaintiff_name`）→ 渲染成空白，B2 的校验器能拦。
4. 模板里写了 `<br>` 不带斜杠 → XML 解析失败。

---

### C 组：生成内核（4 个任务）

#### C1 · 文书草稿与表单接口

**做什么**：模板列表、草稿 CRUD、我的文书列表。

**怎么做**：

| # | 方法与路径 | 用途 |
|---|---|---|
| 1 | `GET /app/doc-templates` | 模板列表（只返回 `status='active'`） |
| 2 | `POST /app/documents` | 创建/保存草稿（`form_data`） |
| 3 | `GET /app/documents` | 我的文书列表（C 端 `list` 分页） |
| 4 | `GET /app/documents/{id}` | 详情（含 `form_data`、`status`、`pdf_oss_key` 的签名 URL） |
| 5 | `DELETE /app/documents/{id}` | 软删 |

1. **归属校验**：所有 `{id}` 接口必须 `WHERE user_id = currentUser.id`，越权返回 **403** 而非 404（`406004 error.document.forbidden`）。
2. **草稿自动保存**：App 端 5 秒防抖调 `POST /app/documents` 更新同一 `id`。**草稿不生成文件**。
3. **ID 一律字符串**（接口规范硬条款）。

**前置**：A1 建表；段 1 的 JWT。
**产出物**：`AppDocumentController` + service + mapper、`docs/acceptance/seg4/C1-crud.md`
**自查**：
- [ ] 创建草稿 → 再保存 → 同一条记录（不是新增）
- [ ] A 的 token 访问 B 的文书 → **403** + `406004`
- [ ] 删草稿后列表看不到，库里 `deleted_at` 有值
- [ ] 模板 `inactive` 后列表不返回它
- [ ] 所有 ID 是字符串

**常见失败**：越权返回 404；ID 返数字（前端精度丢）；草稿保存不幂等。

---

#### C2 · 占位符替换与 XHTML 组装

**做什么**：把 `form_data` 注入模板 HTML，产出合法的 XHTML 字符串，供 A2 渲染。

**怎么做**：

1. **文本替换必须 HTML 转义**（这是本任务的核心风险）：

   ```java
   // 用户填的 "A&B <script>" 如果直接拼进 HTML，会破坏结构甚至注入
   String safe = HtmlEscaper.escape(value);   // &→&amp;  <→&lt;  >→&gt;  "→&quot;  '→&#39;
   html = html.replace("{{" + key + "}}", safe);
   ```

   **禁止**用 `String.replace` 时忘了转义；**禁止**为了「保留用户输入的换行」直接插 `<br>`（要先把 `\n` 转义再换成 `<br/>`）。

2. **array 循环块**：`{{#items}} ... {{/items}}`，块内 `{{item.xxx}}`，重复 N 次。

3. **替换后必须复查**：全文搜 `{{`，有残留 → 抛 `406002 form-invalid`，**不许当成功**。

4. **表单校验**（生成前再校验一次，不能只靠 App 端）：必填、类型、长度、`array` 的 `minItems`/`maxItems`。

5. **产出**：`XhtmlDocument`（HTML 字符串）+ `form_snapshot`（此时冻结）。

**前置**：A1 表；B2 schema；B3 模板。
**产出物**：`modules/document/service/TemplateRenderer.java` + 单测（正常 / 含 `<`&`>` / 含换行 / array 多行 / 占位符缺失 / 占位符残留）、`docs/acceptance/seg4/C2-replace.md`
**自查**：
- [ ] 用户输入 `A&B` → 渲染出的 PDF 显示 `A&B`（不是 `A&amp;B`，也不是丢字）
- [ ] 用户输入 `<script>alert(1)</script>` → 原样显示为文本，**不执行、不破坏结构**
- [ ] 用户输入含换行的长文本 → PDF 里正常分段，不溢出
- [ ] 多原告（array 3 项）→ 渲染成 3 组
- [ ] 模板漏了 schema 的必填字段 → 报错，不产出文件
- [ ] 占位符残留 → 报错（`4-A8` 场景之一）

**常见失败**：
1. **不转义**：用户名字里有 `&`，整份文书渲染失败或结构错乱。这是最常见的生产事故。
2. 用 `replaceAll`（正则）而不是 `replace`（字面量）—— 值里有 `$` 或 `\` 会出错。
3. 转义了两遍 → 显示成 `&amp;amp;`。要保证只转义一次。
4. 忘记 array 的空值情况（0 项）→ 循环块残留。

---

#### C3 · PDF 生成与产物上传

**做什么**：调 A2 渲染 PDF，上传私有 OSS，更新 `document`。

**怎么做**：
1. 调 `PdfRenderer.render(xhtml) → byte[]`
2. 上传 OSS（`document/{id}/{timestamp}.pdf`），登记 `file_record`（`biz_type='document'`）
3. 更新 `document`：`status='generated'`、`pdf_oss_key`、`form_snapshot`（此时冻结）
4. **`form_snapshot` 必须在生成成功那一刻写入**，之后 `form_data` 怎么改都不动它 —— 这是 `4-A4`（模板热更新不改历史）的另一半
5. 同时生成**预览图**（PDFBox `PDFRenderer` 第一页 → PNG 200 DPI），存 OSS，供列表缩略图

**前置**：A2、A3、C2。
**产出物**：`DocGenerateHandler` 的 PDF 分支、`infra/pdf/PdfPageRenderer.java`、`docs/acceptance/seg4/C3-pdf.md`
**自查**：
- [ ] 生成的 PDF 能打开、中文可选中
- [ ] 字体全部嵌入（A2 检查器报 0 个未嵌入）
- [ ] PDF 与预览图都登记进 `file_record`
- [ ] `form_snapshot` 写入后，再改 `form_data` 不影响已生成的文件
- [ ] 预览图清晰度够（200 DPI 下 A4 约 1654×2339 px）

**常见失败**：`form_snapshot` 在生成前就写；只传 PDF 不生成预览图（列表没缩略图）；预览图用 72 DPI（糊）。

---

#### C4 · 失败路径与错误码

**做什么**：把典型失败的「状态 / 错误码 / 清理」定死，让 `4-A8` 可验。

**怎么做**：

| 失败场景 | 触发方式 | `document.status` | 错误码 | 清理动作 |
|---|---|---|---|---|
| 模板占位符与 schema 不匹配 | 模板里有 schema 没有的 `{{xxx}}` | `failed` | `406002` | 无临时文件 |
| 模板非法 XHTML | 模板里 `<br>` 不带斜杠 | `failed` | `406002` | 无临时文件 |
| 字体文件缺失 | 把 `doc.font.path` 指向不存在的路径 | `failed` | `506003` | 无临时文件 |
| 渲染异常 | 刻意构造超深嵌套 HTML | `failed` | `506002` | 删临时 PDF |
| OSS 上传超时 | 断网或改桶策略 | `failed` | `506001` | 删本地临时文件 |
| 模板不存在/已停用 | 手动改库 `status='inactive'` | `failed` | `406001` | — |

1. **`status='failed'` 时 `pdf_oss_key` 与 `signed_pdf_oss_key` 必须为 NULL**（不许指向半个文件）。
2. **`task_queue.last_error` 写人能看懂的原因**（不写堆栈），进死信可查。
3. **失败必须对用户可见**：`GET /app/documents/{id}` 返回 `failed` + `errorMsg`，预览页显示错误态（不是白屏）。

**前置**：C1–C3。
**产出物**：`DocumentExceptionHandler`、`docs/acceptance/seg4/C4-failure.md`
**自查**：
- [ ] 6 个场景都能复现，`status` 都是 `failed`
- [ ] 每个场景 `pdf_oss_key` 都是 NULL
- [ ] App 能看到错误提示
- [ ] 生成失败后 `$env:TEMP` 下无残留
- [ ] 死信能在 `task_queue` 查到
- [ ] 失败后能重试成功（不允许一次失败后该文书永久卡死）

**常见失败**：失败写 `status='generated'` 但 key 为空；把 `e.getMessage()` 直接返回客户端（后端代码规范明令禁止）；只删 PDF 忘了删预览图。

---

### D 组：签名笔迹（3 个任务）

> **本组不受引擎变更影响**：签名叠图用 PDFBox 的坐标 API（§零.4 第 3 条明确允许），元数据写入也用 PDFBox。

#### D1 · 签名图库（28-doc-seal 页的服务端）

**做什么**：用户存多个手写签名图。

**怎么做**：

| # | 方法与路径 | 用途 |
|---|---|---|
| 1 | `GET /app/signatures` | 签名库列表（`document_id IS NULL`） |
| 2 | `POST /app/signatures` | 新增（传 `image_oss_key`，由上传通道直传后回填） |
| 3 | `DELETE /app/signatures/{id}` | 删除（软删；**已被文书引用不允许删**） |

1. **图片规格**：PNG 透明底，建议 600×300 px 以内；**服务端不缩放**（缩放损失笔迹细节）。
2. **数量限制**：每用户最多 **5 个**（防图床）。

**产出物**：`AppSignatureController` + service、`docs/acceptance/seg4/D1-signature.md`
**自查**：
- [ ] 新增后列表能看到，`document_id` 为 NULL
- [ ] 删已被文书引用的签名 → 被拒绝
- [ ] 第 6 个 → 被拒绝
- [ ] 签名图走 60 秒签名 URL

**常见失败**：列表查询没带 `document_id IS NULL`；允许删被引用的签名（`image_oss_key` 悬空）。

---

#### D2 · PDFBox 叠图与坐标换算

**做什么**：把签名 PNG 叠到 PDF 指定页的指定位置。

**怎么做**：

1. **依赖**：`org.apache.pdfbox:pdfbox:2.0.30`（由 openhtmltopdf 间接引入，**不另外声明**）。
2. **坐标模型**（技术路线 §10.4）：存 `(page_no, pos_x_pt, pos_y_pt, width_pt, height_pt, base_page_w_pt, base_page_h_pt)`，单位 pt，A4 = 595×842。
   - **PDF 坐标系原点在左下角，屏幕坐标系原点在左上角** → `pdfY = base_page_h_pt - (pos_y_pt + height_pt)`
   - **这是本段第二容易写错的地方**（第一是 A2 的字重注册）
3. **缩放校验**：比较实际页面尺寸与 `base_page_*`，不一致按比例缩放坐标。
4. **叠加实现**：

   ```java
   PDPage page = doc.getPage(pageNo - 1);
   PDImageXObject img = PDImageXObject.createFromFile(pngPath, doc);
   try (PDPageContentStream cs = new PDPageContentStream(doc, page,
           PDPageContentStream.AppendMode.APPEND, true, true)) {
       cs.drawImage(img, posX, posY, width, height);
   }
   ```

5. **产出去向**：新文件 `signed_pdf_oss_key`，**不覆盖原 `pdf_oss_key`**。

**前置**：C3（有 PDF）、D1（有签名图）。
**产出物**：`infra/pdf/SignatureStamper.java` + 单测（坐标换算四种边界）、`docs/acceptance/seg4/D2-stamp.md`
**自查**：
- [ ] 签名出现在预期位置（偏差 ≤ 4mm，见 `4-A5`）
- [ ] 页面尺寸与 `base_page_*` 不同时坐标按比例缩放
- [ ] 原 PDF 未被修改（`pdf_oss_key` 指向的对象 hash 不变）
- [ ] 透明底 PNG 叠加后背景不被遮挡
- [ ] 多页文书指定第 3 页，签名出现在第 3 页

**常见失败**：**Y 轴没换算**（签名跑到页顶，最经典）；用 `AppendMode.OVERWRITE` 吃掉原内容；不校验页面尺寸。

---

#### D3 · 文书溯源元数据

**做什么**：往 PDF 里写**真实**的溯源元数据，明确**不是 AIGC 标识**。

**怎么做**：

1. **写什么**（`PDDocumentInformation`）：

   ```text
   Faze-Document-Id:      {document.id}
   Faze-Template-Code:    {doc_template.code}
   Faze-Template-Version: {doc_template.version}
   Faze-Generated-At:     {ISO8601 带时区}
   Faze-Generator:        法泽文书中心 v1.0
   ```

2. **明确不写**：`Faze-Content-Type: AIGenerated`、`Faze-Content-Id`（那是 AI 内容的字段，§零.3 第 14 项）。
3. **同时写标准字段**：`Title`（文书标题）、`Author`（`法泽`）、`Creator`（`法泽文书中心`）、`Producer`（openhtmltopdf 自动写）。
4. **签名的哈希**：对**盖签前**内容流算 SHA-256，写入 `signature.document_hash`。

**前置**：D2。
**产出物**：`infra/pdf/PdfMetadataWriter.java`、`docs/acceptance/seg4/D3-meta.md`
**自查**：
- [ ] 5 个字段都能被 PDF 工具读出
- [ ] **没有** `AIGenerated` 字样（明确验一次）
- [ ] `Faze-Template-Version` 与实际用的模板版本一致
- [ ] `document_hash` 叠签后能重新计算并比对成功

**常见失败**：照技术路线 §12.3.2 抄，把 `AIGenerated` 写进去（虚假标识）；元数据写了没落库；时间没标时区。

---
### E 组：APP 六页（6 个任务）

> **本组的共同约束（每个任务都要满足，不再重复）**：
> 1. 页面实现在 `apps/mobile-app/features/document/pages/<Name>Page.tsx`；路由壳在 `apps/mobile-app/app/doc/<name>.tsx`，内容**只有一行** `export { default } from '@/features/document/pages/XxxPage'`（`AGENTS.md` §3.1）。
> 2. **界面文案四语言**走 `t()`，key 三段式 `<document>.<page>.<element>`。文书**内容**只做中文，但**界面**必须 `zh-Hans` + `ug-Arab-CN` 两套齐全（缺则登记 `resources/.i18n-pending.json`）。
> 3. **四态齐全**：正常 / 加载 / 空 / 错误。空态与错误态都要有文案，不许白屏。
> 4. 视觉数值全部来自 `tokens.*`，禁字面量（ESLint 强制）。
> 5. 每个页面产出 `testID` 清单（段 3 先例）。
> 6. `npm run lint`（`tsc --noEmit` + `eslint`）零告警。

#### E1 · 文书模板列表页（设计稿 `23-doc-templates.html`）

**做什么**：分类筛选 + 模板卡片网格，点卡片进对应表单页。
**怎么做**：数据源 `GET /app/doc-templates`（只返回 `active`），按 `category` 分组（诉讼文书 / 合同文书 / 劳动文书 / 跨境电商）。卡片显示模板名、分类、一句话说明、字段数。点卡片：`code` 以 `LAWSUIT-` 开头 → `/doc/lawsuit?templateId=`；其余 → `/doc/form?templateId=`。**不实现设计稿的搜索框**（全局搜索在段 5）。
**产出物**：`DocTemplatesPage.tsx`、`app/doc/templates.tsx`、i18n 两语言 key、testID 清单
**自查**：4 个分类能筛；空态显示「暂无可用模板」；点卡片传对 `templateId`；`ug-Arab-CN` 下卡片从右往左（用 `start/end` 不用 `left/right`）
**常见失败**：把 `category` 写死在 App 里；用 `flexDirection: 'row-reverse'` 做 RTL。

---

#### E2 · 起诉状表单页（设计稿 `24-doc-lawsuit.html`）

**做什么**：按 `field_schema` 渲染表单（含多原告/多被告），自动存草稿，点「立即生成」进预览页。
**怎么做**：
1. **完全由 `field_schema` 驱动**，不写死字段 —— 同一页要能渲染任何 `LAWSUIT-*` 模板。
2. 导航树 L308–314 的要求：原告信息（多）、被告信息（多）、诉讼请求、事实与理由、字段校验、保存草稿。
3. **草稿自动保存**：改动后 5 秒防抖调 `POST /app/documents`；离开页面立即存一次。
4. 「立即生成」→ `POST /app/documents/{id}/generate` → 跳 `/doc/preview?id=`。
5. array 的「添加/删除」由 `minItems`/`maxItems` 控制。

**产出物**：`DocLawsuitFormPage.tsx`、`features/document/components/DynamicForm.tsx`（与 E3 共用）、i18n、testID
**自查**：加 3 个原告 → 文书里有 3 段；手机号填错即时提示；杀进程再进草稿还在；必填未填时按钮置灰并说明原因；`ug` 下标签在右
**常见失败**：字段写死；草稿只在「保存」按钮时存。

---

#### E3 · 其他文书表单页（设计稿 `25-doc-other.html`）

**做什么**：与 E2 同一套动态渲染，模板不同（证据清单 / 劳动合同 / 跨境电商合同 / 借条欠条）。
**怎么做**：**复用 E2 的 `DynamicForm`**，本页几乎不写新 UI。差异仅在 array 的条目结构、`textarea` 多、日期字段多。
**产出物**：`DocFormPage.tsx`、`app/doc/form.tsx`
**自查**：4 个非起诉状模板都能渲染并生成；日期字段出选择器不是手输；`git diff` 显示没有复制第二份表单代码
**常见失败**：复制 E2 改一改（两份表单从此各自演化）。

---

#### E4 · 文书预览页（设计稿 `26-doc-preview.html`）— **行为已变更，重点看**

**做什么**：展示生成好的 PDF，**只读**；点任意位置 → 跳回表单页；导出 PDF；盖章入口。
**怎么做**：

1. **展示方式**：**服务端把 PDF 渲染成 PNG（每页一张），App 显示图片列表**。
   - 不用 `react-native-pdf`：需要原生模块 + 额外构建配置；PDFBox 渲染 PNG 在服务端一次做好，App 只显示 `<Image>`。
   - 接口 `GET /app/documents/{id}/preview` 返回 `[{pageNo, imageUrl, widthPt, heightPt}]`（`imageUrl` 是 60 秒签名 URL）。渲染结果**缓存**在 OSS，不每次重算。
2. **只读**：**没有任何内联编辑**。设计稿里的「点击任意段落可直接修改」文案**必须删掉**。
3. **点段落 → 跳回表单页**：由于展示的是 PNG，**无法知道点的是哪个段落**，所以**不做段落定位**，只跳回该文书的表单页（带 `documentId`）。表单页顶部要提示「修改后需重新生成，预览才会更新」。
4. **底部三个按钮**：`导出 PDF`（进 F1）、`盖章`（进 D2 流程）、`返回表单修改`。
5. **四态**：`draft` → 未生成态（提示先点生成）；生成中 → 加载态（轮询 3 秒）；`generated` → 正常；`failed` → 错误态 + 原因。

**产出物**：`DocPreviewPage.tsx`、`app/doc/preview.tsx`、服务端 `GET /app/documents/{id}/preview` + `PdfPageRenderer.java`、i18n、testID
**自查**：
- [ ] 预览显示的是**真实 PDF 转的图**（不是 HTML 截图、不是设计稿图）
- [ ] 页面内**没有**任何可编辑输入框（`grep` 确认无 `TextInput`）
- [ ] 设计稿的「点击任意段落可直接修改」文案已删除
- [ ] 点「返回表单修改」→ 进表单页，改完再点生成 → 回预览，内容**已更新**
- [ ] `failed` 显示错误原因，不是白屏
- [ ] 生成中显示进度，60 秒后提示超时

**常见失败**：把设计稿的「在线编辑」真做出来（违反拍板）；预览用设计稿静态 HTML 冒充；预览图不缓存。

---

#### E5 · 我的文书列表（设计稿 `27-doc-mine.html`）

**做什么**：草稿 / 已完成 分类、翻页、按类型筛选、进预览页。
**怎么做**：`GET /app/documents?status=&docType=&page=&pageSize=`（C 端 `list`）。列表项：标题、类型、状态标签、生成时间、缩略图。筛选：状态（草稿/已完成/失败）+ 文书类型。下拉刷新 + 上拉加载。
**产出物**：`DocMinePage.tsx`、`app/doc/mine.tsx`、i18n、testID
**自查**：三类状态能筛；翻到第 2 页数据不重复不丢；空态有「去生成」按钮；`failed` 项显示红标且点进去能看到原因
**常见失败**：分页页码从 0/1 不一致；筛选改变时不重置到第 1 页。

---

#### E6 · 电子签章管理页（设计稿 `28-doc-seal.html`）

**做什么**：签名库列表、手写新签名、上传签名图片、删除。
**怎么做**：
1. **手写板**：`@shopify/react-native-skia` 画路径 + `react-native-gesture-handler` 收手势；清空、撤销、确认。
2. **导出 PNG**：`react-native-view-shot` 截手写区域 → **透明底** PNG → 走 `POST /app/upload/token` 直传 → `POST /app/signatures` 回填 `image_oss_key`。
3. **上传图片**：`expo-image-picker` 选相册图 → 同上流程。**白底图叠到 PDF 上会是一个白块**，页面要提示用透明底。
4. **列表**：`GET /app/signatures`，缩略图 + 创建时间 + 删除。
5. **上限提示**：到 5 个时「新增」置灰并说明。

**产出物**：`DocSealPage.tsx`、`features/document/components/SignaturePad.tsx`、`app/doc/seals.tsx`、i18n、testID
**自查**：手写一笔能画出线；导出 PNG 是透明底；第 6 个被拒；删已使用的被拒；`ug` 下绘制坐标不偏移（canvas 坐标不随 RTL 自动镜像）
**常见失败**：导出带白底（叠上去白块盖正文）；RTL 下手势坐标偏移。

---

### F 组：后处理（2 个任务）

#### F1 · 下载 / 分享 / 存相册 / 打印（`4-A7` 的载体）

**做什么**：四种出口都要能用，且都基于**真实文件**。

| 功能 | 实现 | 注意 |
|---|---|---|
| 下载 PDF | `GET /app/documents/{id}/export?format=pdf` → 60 秒签名 URL → `expo-file-system` 下载 | 下载完给「已保存到」提示 |
| 分享 | `expo-sharing` 调系统面板，传**本地文件 URI** | 分享给律师走 IM 是段 5，本段只做系统分享 |
| 存相册 | **仅图片格式**；PDF 走分享面板 | 技术路线 §10.5：PDF 不存相册 |
| 打印 | `expo-print` 调系统打印面板，传**本地 PDF** | 边距已在生成时处理好，不在客户端再算 |

1. **导出接口**：`GET /app/documents/{id}/export?format=pdf`，返回 `{url, fileName, expiresIn: 60}`。
2. **`format` 只支持 `pdf`**（不出 Word，§零.3 第 12 项）。传别的值 → `400`。
3. **下载留痕**：签名图下载写 `operation_log`；**文书下载不审计**（本段裁决，理由：文书是用户自己的非敏感他人数据；若负责人要求，见 §十 第 3 项）。

**产出物**：`features/document/api/export.ts`、预览页导出面板
**自查**：四项都能用（真机实测，每项截图）；下载的 PDF 能打开且内容与预览一致；打印面板边距正确；Android 必验
**常见失败**：分享的是 URL 不是本地文件（接收方打不开）；打印传了 URL。

---

#### F2 · 重新生成与模板版本隔离

**做什么**：用户改表单后重新生成，**旧产物不丢**；模板改版后重新生成，历史文书**不受影响**。

1. `POST /app/documents/{id}/generate` **可重复调用**：每次产新 PDF（key 带时间戳），更新 `pdf_oss_key` 与 `template_version`，`form_snapshot` 刷新为**本次**表单。
2. **旧产物不删**：`file_record` 保留历史，`document` 只指最新。
3. **模板改版的影响面**：
   - 已生成且未重新生成的文书：`template_version` 停留旧版，**重下载仍是旧内容**（`pdf_oss_key` 指向旧文件）
   - 重新生成后：`template_version` 更新为当前版

**前置**：C3、B1。
**产出物**：C1 的 `generate` 补全、`docs/acceptance/seg4/F2-regen.md`
**自查**：改表单 → 重新生成 → 预览更新且旧 PDF 仍在 OSS；运营改模板（v1→v2）→ 历史文书重下载仍是旧内容；历史文书重新生成 → `template_version=2`；连生成 3 次 → `file_record` 有 3 组产物
**常见失败**：重新生成覆盖同一个 `oss_key`（`4-A4` 失败）；`template_version` 不更新。

---

### G 组：收口（2 个任务）

#### G1 · 验收脚本 `deploy/verify-seg4.ps1`

**做什么**：把 8 条锚点能自动验的部分脚本化，不能自动的留人工判定入口。
**怎么做**：先建骨架跑出「8 条全部未通过」，再逐条接实现。可自动验：`4-A1`（生成后搜 `{{`）、`4-A3`（PDF 抽文字比对）、`4-A4`（改模板后重下 hash 不变）、`4-A6`（读元数据）、`4-A8`（失败后查库与临时目录）。必须人工：`4-A2`（版式目视，可脚本化「字体是否嵌入」）、`4-A5`（量位置偏差）、`4-A7`（真机操作）。
**产出物**：`deploy/verify-seg4.ps1`
**自查**：空跑时 8 条全报「未通过」；每条输出具体判据

---

#### G2 · 证据归档与文档回写

**做什么**：`docs/acceptance/seg4/` 下的 8 条证据 + §九 的 8 项回写。

**产出物**：`docs/acceptance/seg4/4-A1.md` ~ `4-A8.md`
**自查**：8 个文件都在；每个含命令输出/SQL、截图、判定结论；§九 8 项全部完成

---

## 四、验收锚点（`4-A1` ~ `4-A8` 逐条展开）

> **格式**：①原文 ②怎么验 ③通过标准（二值）④证据文件 ⑤依赖任务 ⑥**最容易糊弄过去的地方**。
> 证据文件统一放 `docs/acceptance/seg4/`。
> **编号说明**：`4-A1` 内容已从「Word 生成」改为「PDF 生成」；`4-A3` 从「Word 与 PDF 一致」改为「PDF 文本层可选中可搜索」；`4-A4`/`4-A6` 保留但对象变了。原委见 §开工前必读两次变更。

### `4-A1` PDF 生成

- **原文（总纲，已改）**：「选中文模板 → 填表 → 生成 → **下载 PDF**」→ 通过标准：「内容与填表一致，无占位符残留、无丢字段」。
- **怎么验**：
  1. App 端进文书模板列表 → 选「民事起诉状」。
  2. 填表：原告 3 人、被告 2 人、诉讼请求 2 条、事实与理由 1 段、受理法院「伊宁市人民法院」。
  3. 点「立即生成」→ 等状态变 `generated`。
  4. 预览页点「导出 PDF」→ 下载到本地。
  5. **用 PDF 阅读器打开**，逐字段核对：3 个原告都在、2 个被告都在、诉讼请求 2 条都在、法院名对。
  6. **全文搜索 `{{`**：必须 0 处。
  7. 查库：`SELECT status, pdf_oss_key, form_snapshot FROM document WHERE id=?` → `status='generated'`，`pdf_oss_key` 非空，`form_snapshot` 与提交的表单一致。
- **通过标准（二值）**：7 步全为真 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A1.md`（填表截图、PDF 原件、打开后的截图、`{{` 搜索为 0 的截图、SQL 输出）。
- **依赖任务**：C1、C2、C3、E4、F1、B3。
- **最容易糊弄的地方**：
  - **只看预览截图就判过** —— 预览是 PNG，看不到 PDF 里是否残留 `{{`。必须打开 PDF 搜。
  - 只填 1 个原告 —— 那不验 array。**必须填多原告**，这是唯一能验出 array 渲染的手段。
  - 生成的 PDF 是**旧的缓存文件**（同一 `id` 生成过两次，下到的是第一次的）—— 用 `form_snapshot` 与文件内容比对。

### `4-A2` PDF 版式与字体嵌入

- **原文**：「同一份文书转 PDF」→「A4 边距正确、分页正确、页眉页脚不压正文」。
- **怎么验**：
  1. 拿 `4-A1` 的 PDF，用 `pdfinfo` 看页面尺寸：预期 `595.276 x 841.89 pts (A4)`。
  2. **量边距**（用 PDFBox 读首页文字 bounding box，不靠目视）：上 72pt ± 2、下 72pt ± 2、左 90pt ± 2、右 72pt ± 2。
  3. **分页**：内容超 1 页时，第 2 页的页眉页脚不压正文；页码连续。
  4. **字体嵌入**：A2 检查器输出「未嵌入字体数 = 0」。**这是隐藏判据** —— 换台没装仿宋的机器会变字形，等于版式不正确。
  5. 打印一份（真机 `expo-print`），量纸张边距。
- **通过标准（二值）**：A4 + 四边边距在容差内 + 分页正确 + **字体全部嵌入** → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A2.md`（`pdfinfo` 输出、量边距的输出、字体嵌入检查输出、打印件照片）。
- **依赖任务**：A2、C3。
- **最容易糊弄的地方**：
  - **只看截图说「版式没问题」** —— 截图看不出 72pt 还是 60pt。必须量。
  - 字体没嵌入但本机装了仿宋，看着正常 —— 换机器就变。**这条最容易漏**。
  - 边距是 CSS 里设的，但 `@page` 写错（如写成 `margin: 72pt` 四边一样）—— 要量 PDF 不看 CSS。

### `4-A3` PDF 文本层可选中可搜索（**新锚点，替代原「Word 与 PDF 一致」**）

- **原文**：无（原 `4-A3` 是「Word 与 PDF 一致」，因不出 Word 而删除；本条是新增替代）。
- **为什么需要它**：这是「HTML 渲染」路线**最容易偷工减料**的地方。用 PDFBox 直接 `drawImage` 把一张图贴上去，肉眼看着一模一样，但文字不能选中、不能搜索、不能复制 —— 那是**扫描件**，不是文书。法院网申要求可检索文本。
- **怎么验**：
  1. 拿 `4-A1` 的 PDF。
  2. 用 PDFBox `PDFTextStripper` 抽全文，**打印出来**。
  3. 从 PDF 里抽出的文字必须包含：3 个原告名、2 个被告名、2 条诉讼请求、法院名、日期 —— 逐项核对。
  4. 用 PDF 阅读器**手动拖选**一段中文 → 能复制出来 → 粘贴到记事本，文字正确不乱码。
  5. **反向断言**：PDF 里的正文**不是图片**（用 `pdfimages -list` 看，正文区域不应有大图；`pdfimages` 只应列出用户上传的证据图之类）。
- **通过标准（二值）**：抽取文字含全部关键字段 + 手动复制正确 + 正文非图片 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A3.md`（PDFTextStripper 的全文输出、手动复制粘贴的截图、`pdfimages -list` 输出）。
- **依赖任务**：A2、C3。
- **最容易糊弄的地方**：
  - **只截图看「长得对」** —— 图片贴上去也长得对。必须抽文字。
  - 抽文字时因字体子集化抽出乱码，然后说「PDF 都这样」—— 乱码本身就是问题。
  - 用 `pdfimages` 时只看到「有图」就以为不行 —— 要区分「正文是图」和「正文里嵌了用户的证据图」。

### `4-A4` 模板热更新不改历史文书

- **原文**：无（原 `4-A4` 是「藏文叠字」，已删除；本条是新增）。
- **为什么需要它**：`doc_template` 的版本行 + `document.template_version` + `form_snapshot` 三样就是为这件事设计的。**设计意图在文档里，但从来没人验过它真的工作。** 而且本段把模板从 `.docx` 换成 HTML 之后，「原地改 HTML」的诱惑更大（一行 UPDATE 就改了），这条锚点更重要。
- **怎么验**：
  1. 用模板 v1 生成文书 A，下载 PDF，记 SHA-256 = `H1`。
  2. 运营在 A-18 改模板（把标题字号 16pt 改 18pt），保存 → **`version` 变 2，且库里多了一行 v2、旧行仍在**。
  3. **不改 A 的任何数据**，重新下载 A 的 PDF → SHA-256 = `H2`。
  4. **断言 `H1 == H2`**（A 还是旧版）。
  5. 对 A 点「重新生成」→ 下载 → `H3`，**`H3 != H1`**，且 `document.template_version = 2`。
  6. 查库：`SELECT id, version, status FROM doc_template WHERE code=?` → 两行，v1 `inactive`、v2 `active`。
- **通过标准（二值）**：`H1==H2` 且 `H3!=H1` 且两行模板都在 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A4.md`（3 个 hash、`document.template_version` 前后值、`doc_template` 两行的查询结果）。
- **依赖任务**：B1（版本行机制）、F2（重新生成）、C3。
- **最容易糊弄的地方**：
  - **保存模板时原地 UPDATE `template_html`** —— 历史文书再也复现不出来，`H2 != H1`。这是本锚点要抓的第一件事。
  - 「重新生成」只改文件不改 `template_version` —— `H3 != H1` 成立但版本号对不上。
  - 拿 `form_snapshot` 当「历史文书」验 —— 那是表单数据，不是文书产物。

### `4-A5` 签名位置

- **原文**：「叠手写签名后看落款处」→「位置偏差在可接受范围（**建议 ≤ 4mm，具体由负责人定**）；签名不遮挡正文关键内容」。
- **容差已定（§零.3 第 16 项）**：**≤ 4mm（≈11.34pt）**，由技术侧自定、负责人 2026-11-01 授权。
  > **要说明这是「自证」**：技术侧定容差、技术侧验，不是第三方验收。产品能接受就接受；若法院那边觉得位置不对，责任追不到验收这一环。这条已在基线里留痕。
- **怎么验**：
  1. 生成文书 → 预览 → 「盖章」→ 从签名库选一个 → 拖到落款处 → 确认。
  2. 下载带签名的 PDF。
  3. **量**：签名图左边缘与「具状人：」文字左边缘的水平偏差、签名底边与落款日期的垂直间距。
  4. 换算：4mm ≈ 11.34pt。
  5. 检查签名**不遮挡**诉讼请求、事实与理由、法院名。
- **通过标准（二值）**：偏差 ≤ 4mm **且** 不遮挡正文关键内容 → 通过（两个条件都满足）。
- **证据文件**：`docs/acceptance/seg4/4-A5.md`（带签名的 PDF、量的坐标值、放大截图）。
- **依赖任务**：D1、D2、E6。
- **最容易糊弄的地方**：
  - 签名坐标**写死**（不按 `base_page_*` 缩放）—— 当前模板对了，换模板就错。要换一份模板再验一次。
  - Y 轴没换算（PDF 原点在左下）—— 签名跑到页顶。
  - 截图缩得很小，看不出偏差 —— 要放大到 1:1 像素量。

### `4-A6` 文书溯源元数据

- **原文（原）**：「含 `Faze-Content-Type` 等 5 个字段」。
- **本段口径**：「含 5 个**溯源**字段，值正确；**不含任何 `AIGenerated` 标识**」。
- **为什么改**：文书是「用户填表 + 运营配 HTML 模板 + 占位符替换」，**没有任何一步是模型生成的**。打 `AIGenerated` 属虚假标识（§零.3 第 14 项）。这**不是**偷懒不打，是「打错了比不打更糟」。
- **怎么验**：
  1. 下载任意生成的 PDF。
  2. 用 PDFBox 或 `pdfinfo` 读 `DocumentInformation`。
  3. 逐个核对：`Faze-Document-Id` = `document.id`；`Faze-Template-Code` = `doc_template.code`；`Faze-Template-Version` = 与 `document.template_version` 一致；`Faze-Generated-At` = ISO8601 带时区；`Faze-Generator` = `法泽文书中心 v1.0`。
  4. **反向断言**：全文件搜 `AIGenerated` / `Faze-Content-Type` → **0 处**。
- **通过标准（二值）**：5 个字段都有且值对 + `AIGenerated` 为 0 处 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A6.md`（元数据全量输出、5 个字段对照表、`AIGenerated` 搜索为 0 的输出）。
- **依赖任务**：D3。
- **最容易糊弄的地方**：**照旧文档抄回 `AIGenerated`**（技术路线 §12.3.2 还那么写着）；`Faze-Template-Version` 硬编码 `1`；时间没标时区。

### `4-A7` 后处理链

- **原文**：「我的文书列表 → 重新下载 / 分享 / 存相册 / 打印」→「四项都可用；列表能翻页、能按类型筛选」。
- **本段口径**：「重新下载」只指 PDF（不出 Word）。
- **怎么验**（真机，逐项截图）：
  1. 进「我的文书」→ 能看到刚生成的文书。
  2. **重新下载**：点下载 → 文件保存到本地 → 打开正常。
  3. **分享**：点分享 → 系统面板出现 → 选一个 App → 文件能传出。
  4. **存相册**：对**图片格式**操作；PDF 应走分享面板（验「PDF 时该按钮不出现或提示走分享」）。
  5. **打印**：点打印 → 系统打印面板 → 预览边距正确。
  6. **翻页**：文书 > 10 份时翻到第 2 页，数据不重复不丢。
  7. **按类型筛选**：选「诉讼文书」→ 只剩起诉状与证据清单。
- **通过标准（二值）**：1–7 全为真 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A7.md`（7 步截图 + 第 2 页数据与第 1 页无交集的证明）。
- **依赖任务**：E5、F1。
- **最容易糊弄的地方**：「分享」分享的是 URL 不是文件；「打印」只弹面板没验边距；翻页只验「有第 2 页」没验不重复。

### `4-A8` 失败不留半成品

- **原文**：「人为让生成失败（如模板引用不存在的字段）」→「有明确错误提示；不留『成功』记录；临时文件被清理」。
- **怎么验**（三个场景各做一次）：
  1. **模板占位符不匹配**：模板里写 `{{not_in_schema}}` → 保存时就该被 B1 拦；绕过保存直接改库 → 生成必须失败。
  2. **模板非法 XHTML**：模板里写 `<br>`（不带斜杠）→ 同上。
  3. **字体文件缺失**：把 `doc.font.path` 指向不存在的路径 → 生成必须失败。
  4. **OSS 不可用**：临时改桶策略 → 生成必须失败。
  每场景验五件事：① App 看到**明确错误提示**（不白屏、不转圈到超时）② `status='failed'` 且 `pdf_oss_key`/`signed_pdf_oss_key` **都是 NULL** ③ `task_queue.last_error` 有可读原因 ④ `$env:TEMP` 下无残留（生成前后各列一次目录对比）⑤ 失败后再点生成能重试成功。
- **通过标准（二值）**：4 场景 × 5 件事全为真 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A8.md`（4 场景各 5 张证据，共 20 项）。
- **依赖任务**：C4、A2、A4。
- **最容易糊弄的地方**：失败写 `status='generated'` 只是文件为空；只验 1 个场景（总纲写的是**举例**）；临时文件清理没验（这条最容易漏）；失败后不能重试。

---
## 五、产出清单（交付时逐项勾）

### 5.1 数据库（4 张表）

| # | 表名 | 类别 | 字段数 | 任务 |
|---|---|---|---|---|
| 1 | `doc_template` | D 基础数据 | 13（含 `template_html`） | A1 |
| 2 | `document` | C 状态机 | 16（无 `word_oss_key`） | A1 |
| 3 | `signature` | B 追加型 | 15 | A1 |
| 4 | `file_record` | A 业务实体 | 14 | A1 |

**迁移**：`V400__create_document_tables.sql`（建表）+ `V400_1__drop_document_tables.sql`（回滚，手动执行）。

### 5.2 接口（17 个：App 12 + PC 5）

**App 侧（12 个）**

| # | 方法与路径 | 用途 | 任务 |
|---|---|---|---|
| 1 | `GET /app/doc-templates` | 模板列表（`active`） | E1 |
| 2 | `POST /app/documents` | 创建/保存草稿 | C1 |
| 3 | `GET /app/documents` | 我的文书列表（`list` 分页） | E5 |
| 4 | `GET /app/documents/{id}` | 详情（含 PDF 签名 URL） | C1 |
| 5 | `DELETE /app/documents/{id}` | 软删 | C1 |
| 6 | `POST /app/documents/{id}/generate` | 投递生成任务 | A4 |
| 7 | `GET /app/documents/{id}/preview` | 预览（PDF 转图，缓存） | E4 |
| 8 | `POST /app/documents/{id}/sign` | 提交签名坐标并叠章 | D2 |
| 9 | `GET /app/documents/{id}/export?format=pdf` | 导出（60 秒签名 URL）；**只支持 pdf** | F1 |
| 10 | `GET /app/signatures` | 签名库列表 | D1 |
| 11 | `POST /app/signatures` | 新增签名 | D1 |
| 12 | `DELETE /app/signatures/{id}` | 删除签名 | D1 |

**PC 侧（5 个）**

| # | 方法与路径 | 用途 | 任务 |
|---|---|---|---|
| 13 | `GET /admin/doc-templates` | 列表（`rows` 分页） | B1 |
| 14 | `GET /admin/doc-templates/{id}` | 详情（含 `template_html`） | B1 |
| 15 | `POST /admin/doc-templates` | 新建（`version=1`） | B1 |
| 16 | `PUT /admin/doc-templates/{id}` | 保存 → **生成新版本行** | B1 |
| 17 | `POST /admin/doc-templates/{id}/preview` | 渲染一页预览 PNG | B1 |

**复用段 1 的 2 个上传接口，不计入本段**：`POST /app/upload/token`、`GET /app/upload/url`。

### 5.3 APP 页面（6 页）

| # | 页面 | 设计稿 | 任务 |
|---|---|---|---|
| 1 | 文书模板列表页 | `23-doc-templates.html` | E1 |
| 2 | 起诉状表单页 | `24-doc-lawsuit.html` | E2 |
| 3 | 其他文书表单页 | `25-doc-other.html` | E3 |
| 4 | 文书预览页 | `26-doc-preview.html` | E4 |
| 5 | 我的文书列表 | `27-doc-mine.html` | E5 |
| 6 | 电子签章管理页 | `28-doc-seal.html` | E6 |

### 5.4 PC 页面（1 页）

| # | 页面 | 设计稿 | 任务 |
|---|---|---|---|
| 1 | A-18 文书模板 | `design/admin/17-doc-template.html` | B1 |

### 5.5 5 份中文模板（HTML）

| # | 模板 | `code` | 内容基准 | 任务 |
|---|---|---|---|---|
| 1 | 民事起诉状 | `LAWSUIT-001` | 最高法《民事诉讼文书样式》结构 | B3 |
| 2 | 证据清单 | `EVIDENCE-001` | 同上（举证目录/证据清单） | B3 |
| 3 | 劳动合同 | `LABOR-001` | 人社部《劳动合同（示范文本）》 | B3 |
| 4 | 跨境电商合同 | `CROSSBORDER-001` | **无官方示范文本**，由法泽拟定并标注 | B3 |
| 5 | 借条欠条 | `IOU-001` | **无官方模板**，按《民法典》第 667 条 + 民间借贷司法解释要素拟定并标注 | B3 |

### 5.6 新增依赖（必须登记《依赖清单》并写理由）

| 依赖 | 版本 | 用途 | 理由 |
|---|---|---|---|
| `com.openhtmltopdf:openhtmltopdf-pdfbox` | 1.0.10 | HTML/CSS → PDF，中文渲染 | 唯一成熟的「HTML→PDF」LGPL 方案；**内部就是 PDFBox**；见下方风险 |
| `org.apache.pdfbox:pdfbox` | 2.0.30（**由上一行间接引入，不单独声明**） | 签名叠图、PDF 转 PNG、元数据读写、字体嵌入检查 | 技术路线 §2.3 定 2.0.x |
| `monaco-editor`（PC 前端） | 最新 | A-18 的 HTML 模板编辑器 | 富文本编辑器会生成脏 HTML，openhtmltopdf 必炸；代码编辑器可控 |
| `expo-print` | SDK 54 对齐 | 打印 | `AGENTS.md` §4 第 12 条要求登记 |
| `expo-sharing` | 同上 | 分享 | 同上 |
| `expo-file-system` | 同上 | 下载 | 同上 |
| `expo-media-library` | 同上 | 存相册 | 同上 |
| `@shopify/react-native-skia` | 同上 | 手写签名板 | 技术路线 §2.3 已定 |
| `react-native-gesture-handler` | 同上 | 手势 | Expo 标准 |
| `react-native-reanimated` | 同上 | 手势动画 | gesture-handler 配套 |
| `react-native-view-shot` | 同上 | 手写板导出 PNG | 比 skia 自带导出更稳 |

**已从依赖清单移除的**：`com.deepoove:poi-tl`（不出 Word，不再需要）、LibreOffice（不装）、`lombok` 无关项不动。

### 5.7 脚本、文档与证据

| 类型 | 路径 | 任务 |
|---|---|---|
| 验收脚本 | `deploy/verify-seg4.ps1` | G1 |
| 证据 | `docs/acceptance/seg4/`（8 条 + A1~A4 的过程证据） | G2 |
| 模板源文件 | `assets/doc-templates/*.html`（5 份） | B3 |
| 回写 | §九 的 8 项 | G2 |

---

## 六、已知风险与出口

| # | 风险 | 会挡住吗 | 出口 |
|---|---|---|---|
| **R1** | **`openhtmltopdf` 已归档（2023 年，最后版本 1.0.10），不再有安全更新** | 不会挡住，但是**长期风险** | ①它只是个渲染库，不处理网络/用户输入，攻击面小；②锁死版本、不放宽；③**若未来出 CVE 或需要新特性，退路是 flying-saucer-pdf-openpdf（同 LGPL）**；④这条写进依赖清单的「理由」栏，不只写好处 |
| **R2** | **CSS 支持有限**：`flex` / `grid` 完全不支持（写了被静默忽略，不报错） | **会**（版式静默错乱） | B2 的 HTML 子集规范**明确禁用** flex/grid，`TemplateHtmlValidator` 主动拒绝；用 `table` 布局 |
| **R3** | **5 份模板内容没人写**（技术侧能给结构，给不了真实法律文本；且本机 `web_fetch` 对外网全拦，拿不到官方原文） | **会**（§一.1 出口第 2 条） | ①起诉状/证据清单/劳动合同按官方样式结构写并标注「需人工核对」；②跨境电商/借条明确标「非官方文本，由法泽拟定」；③若负责人要求逐字一致，必须由人拿官方原文核对 |
| **R4** | **粗体字体缺失**：`simfang.ttf` 只有常规字重，`font-weight: bold` 会静默不加粗 | 不会挡住，但版式不对 | ①注册两个 `useFont`（400 + 700）；②若无仿宋粗体，用 `simhei.ttf`（黑体）承担粗体，或接受「标题不加粗」并写进模板规范 |
| **R5** | `signature` 表一表两用（签名库 + 盖章记录），查询容易写错 | 不会，但**会出数据错误** | 所有查询显式带 `document_id IS NULL` / `IS NOT NULL`；Mapper 层用两个方法名区分（`listSealLibrary` / `listStampsOnDocument`），**不给「按 user_id 查全部」的方法** |
| **R6** | 模板改版时**原地 UPDATE `template_html`** | **会**（`4-A4` 失败） | A1 的「版本怎么留痕」写死两步：INSERT 新版本行 + 旧行置 `inactive`；`TemplateHtmlValidator` 不拦这个，靠**代码评审 + `4-A4` 验收**兜 |
| **R7** | **运营写不出 well-formed XHTML** | 会，但可控 | ①B1 的编辑器 + 保存前校验；②5 份模板作为范本；③模板结构不让运营大改（只改文字与占位符），结构改动由技术侧做 |
| **R8** | PDF 转 PNG 慢（每页渲染一次） | 不会，但体验差 | 渲染结果缓存到 OSS，二次进页面直接取 URL；只渲染前 5 页 |
| **R9** | **中文换行**：openhtmltopdf 的 CJK 断行是否按标点规则（句号/逗号不出现在行首） | 不会挡住，但文书版式会难看 | 实测一次；不行则用 `word-break`/`white-space` 微调，或在模板里控制段落长度 |

---

## 七、明确不做（本段不许碰，碰了就是超范围）

| # | 不做 | 依据 |
|---|---|---|
| 1 | **少数民族文字文书** | 负责人 2026-11-01 拍板「只做中文」。**注意：这不是「界面也只做中文」** |
| 2 | **Word 导出** | 负责人 2026-11-01 拍板「不要 Word」。`4-A1`/`4-A7`/`GET .../export` 都不含 docx |
| 3 | **文书在线编辑** | 同上。预览页只读，点段落只跳回表单页 |
| 4 | **可靠电子签名**（CA 数字证书、时间戳服务） | 总纲 §5.5「明确不做」；技术路线 §10.4「不主张法律效力」 |
| 5 | **法院系统对接**（网上立案、送达对接） | 总纲 §5.5「明确不做」；无公开接口 |
| 6 | **AI 起草文书** | 段 3 已把「代写可递交文书」列为拦截意图，AI 只解答法律信息 |
| 7 | **文书法条引用块（结构化）** | 法条在本段是**模板正文里的文字**，由运营写进 HTML，系统不需要知道引用了哪条。**因此段 3 §八.1 第 1/2 条承诺的 `ArticleNoValidator` 与 `refs` schema，本段未使用**——见 §八.2 |
| 8 | **模板批量导入/导出** | 设计稿有这两个按钮，本段不实现；禁用 + tooltip「暂未开放」 |
| 9 | **律师端文书管理**（`65-lawyer-docs.html`） | 属段 5 |
| 10 | **分享到微信** | 需要微信开放平台资质（负责人负责）；本段只做 `expo-sharing` 系统面板 |
| 11 | **模板的四语言版本** | 随第 1 条降级；`lang` 字段保留但只写 `zh-Hans` |
| 12 | **签名的「设为默认」** | 设计稿没有这个功能 |
| 13 | **PDF/A 归档格式** | 未做。若将来要长期归档，需另转 |
| 14 | **LibreOffice** | 本段任何环节都不调用 `soffice`（§零.3 第 13 项） |

---

## 八、留给下一段的接口（我保证什么 / 我不保证什么）

### 八.1 我保证（段 5~8 可以直接依赖）

1. **文书产物是标准 PDF**：任何能读 PDF 的工具都能处理。段 5 的「案件送达文书」直接引用 `document` 表即可。
2. **PDF 里的中文可选中、可搜索、字体已嵌入**（`4-A2`/`4-A3` 验过）：段 5 若要把文书打包寄给法院/律师，不需要再处理编码问题。
3. **`document` 的三个 `*_oss_key` 与 `file_record` 一一对应**：`biz_type='document'`、`biz_id=document.id`。段 7 做存储清理时按这个口径统计。
4. **`task_queue` 的 `DOC_GENERATE` 已注册，handler 可复用**：段 5 的「批量生成送达文书」照这个 handler 写。
5. **`signature` 表的双重语义已定**（`document_id` 空/非空），段 5 若要给文书加律师签名，用同一个表。
6. **`doc_template` 的版本机制可靠**（`4-A4` 验过）：段 5 的案例库若要做模板化文书，照这个模式（每次改版插新行，旧行不动）。
7. **模板管理不靠改代码、不发版**：A-18 能新建模板并让 App 立刻可见。
8. **`PdfRenderer` / `PdfPageRenderer` / `FileRecordService` 可复用**：段 5 的文书预览、段 7 的投诉截图都能用。
9. **HTML 子集规范 + 校验器可复用**：任何要生成 PDF 的地方都受同一套约束，不会各写一套。

### 八.2 我不保证（后续段可能推翻或补做）

| 不保证 | 最晚什么时候解决 |
|---|---|
| **段 3 承诺的 `ArticleNoValidator` + `refs` schema 本段未使用** | 段 3 说「段 4 文书中心复用本服务」，但本段把法条当**模板正文文字**处理 → **该契约落空**。若将来要做法条引用块（点击条文看原文），需在段 8 或二期补。**这是本段对上游的一次未兑现，必须记录** |
| **少数民族文字文书** | 若恢复，需重新引入 openhtmltopdf 的 shaping 能力（它也不做 GSUB/GPOS，届时**必须换回 LibreOffice**）、字体授权（段 0 的 R3）、以及原 `4-A3`/`4-A4` 口径 |
| **Word 导出** | 若恢复，需重新引入 `.docx` 模板 + poi-tl + LibreOffice（即「变更一」的状态）。**模板格式要再换一次，历史模板全部重做** |
| **`openhtmltopdf` 的长期维护** | 项目已归档。若出 CVE，需评估或迁到 flying-saucer |
| **签名笔迹的法律效力** | 明确不主张。若要做可靠电子签名，需接 CA（二期） |
| **批量生成**（一次生成多份） | 未做。段 5 的「送达文书」可能需要 |
| **文书的可访问性**（PDF 标签化、朗读支持） | 未做 |
| **iOS 端** | 本段只验 Android。iOS 的分享/打印行为差异留段 8 |
| **模板的富文本编辑** | 本段模板靠运营编辑 HTML，后台不提供可视化排版 |
| **PDF 打印精度的跨打印机一致性** | 未验。不同打印机的边距可能有差异 |

---

## 九、回写到哪些文档（本段结束必须完成，缺一条算未收口）

> 按 `docs/BASELINE.md` §4 的「四件事」办：**落款 / 登记指纹 / `docs/README.md` 加索引 / `CHANGELOG.md` 说明**。只做一件等于没做。

| # | 文档 | 改什么 | 什么时候 |
|---|---|---|---|
| 1 | **`AGENTS.md` §2 技术栈「文书」行 + §5 禁令** | 从「`poi-tl`（Word）+ LibreOffice headless 或 Chromium 打印；`openhtmltopdf` 无法排阿拉伯系连写，不得使用」改为「HTML 模板 + **openhtmltopdf**（LGPL，内部 PDFBox）；**不做 Word 导出**；少数民族文字场景仍禁用（不做 shaping）」 | **开工前** |
| 2 | 《开发计划总纲》§5.5 | 段定位、服务端技术链（poi-tl→HTML、LibreOffice→openhtmltopdf）、数据库表名与字段、锚点 `4-A1`/`4-A3`/`4-A6`、「必须做」第 1 条（模板四语言→单语言 HTML） | **开工前** |
| 3 | 《技术路线》§10.1/§10.2 整节 | 生成链路图从 `模板.docx → poi-tl → .docx → LibreOffice → .pdf` 改为 `模板.html → 占位符替换 → openhtmltopdf → .pdf`；模板机制表（占位符、动态字段、多语言）改 HTML 口径；删「草稿」「多语言每种一份」的 Word 说法 | **开工前** |
| 4 | 《技术路线》§10.3 | 加「2026-11-01 起本节不被段 4 使用」的标注（保留本节，因为「为什么不能用 openhtmltopdf 排民族文字」这条结论仍有效） | **开工前** |
| 5 | 《技术路线》§2.3 依赖表 | LibreOffice 那行标注「段 4 不使用」；`poi-tl` 那行标注「段 4 不使用」；新增 `openhtmltopdf` 行；LibreOffice 版本「7.x」过时，若保留则注明「版本号已改日历式，以官网 Still 版为准」 | **开工前** |
| 6 | 《数据表规范》附录 A | `doc_template` 字段数 12→13（`template_url`/`oss_key` → `template_html`）；`document` 17→16（删 `word_url`→`word_oss_key` 的路径）；若 task_queue/order_status_log 未补则一并补 | **开工前** |
| 7 | 《数据表规范》§4.1 + 附录 C | `doc_template` 不用 `is_enabled` 而用 `status` 三态 → 登记这条偏离 | 收口时 |
| 8 | 《PC 后台开发计划》A-18 | 数据表名 `document_template` → `doc_template`；「4 语言版本」→ 单语言；补「设计稿的适用法条列、导入/导出按钮、上传 .docx 本段不实现」 | **开工前** |
| 9 | 《法泽PC后台UI设计说明》 | 记录 A-18 的 4 处不实现 | 收口时 |
| 10 | 《CHANGELOG》+ `docs/BASELINE.md` + `docs/README.md` | 新增本文档的登记与索引 | 收口时 |

---

## 十、待负责人拍板（本段开工前或施工中必须有人回答，不许「假设已定」）

| # | 事项 | 为什么必须有人答 | 不答的后果 | 当前状态 |
|---|---|---|---|---|
| 1 | **模板 HTML 谁维护**（运营自己写 HTML，还是技术侧写好 5 份、运营只改文字与占位符） | 这决定 A-18 的编辑器形态：全功能 HTML 编辑器 vs 受限表单式编辑器 | 若运营不会 HTML，B1 的 monaco 编辑器就没人用，实际就是技术侧包办 → 等于「代码里写死」的变种 | ⏳ 未定，本段默认**技术侧写 5 份骨架、运营改文字与占位符** |
| 2 | **5 份模板的内容来源与核对责任** | ①起诉状/证据清单/劳动合同的官方原文，本机**扒不到**（`web_fetch` 对外网全拦）②跨境电商/借条**没有官方文本** | 技术侧按已知结构写 + 标注「需人工核对」，**准确性由人兜底**；若出了错，用户拿到法院被驳回，影响是批量的 | ⏳ 未定，本段默认「技术侧写结构 + 人工核对」 |
| 3 | **文书下载要不要审计** | 技术路线 §10.4 只说「签名图下载留 `operation_log`」，文书未提 | 不审计：无法追溯谁下载过什么；审计：`operation_log` 量会大（每次下载一条） | ⏳ 未定，本段默认**不审计** |
| 4 | **模板改版后，历史文书列表要不要标「旧版」** | 设计稿没有这个字段 | 不标：用户看不出自己那份是旧模板 | ⏳ 未定，本段默认**不标**（`4-A4` 只验文件不变） |
| 5 | **`openhtmltopdf` 归档风险是否接受** | 项目 2023 年归档，不再有安全更新 | 接受：锁版本、写进依赖清单理由栏；不接受：现在换 flying-saucer（要重写 A2/C3，工作量约 3 天） | ⏳ 未定，本段默认**接受并记录风险** |
| 6 | **iOS 端本段验不验** | 段 0 只做 Android | 不验：iOS 的分享/打印留段 8 挂账 | ⏳ 未定，本段默认**只验 Android** |

---

## 十一、实际完成与偏差（施工后回写，不删原文）

> 施工完成后在这里追加，**不改上面的内容**。每条写：计划 vs 实际、偏差原因、是否影响下一段。

| # | 计划 | 实际 | 偏差原因 | 影响 |
|---|---|---|---|---|
| | | | | |

---

## 附：本文档的生成方式与已知局限

- **生成日期**：2026-11-01，当日**两次改范围**（只做中文 / 换 openhtmltopdf + 砍 Word），本文档反映的是第二次改完的状态。
- **依据的实测**：仓库 HEAD `2c6148f`（分支 `UI设计`），2026-11-01 22:0x–22:3x。实测项：
  - `Get-Command soffice` 为空、三个常见安装路径均不存在 → 本机无 LibreOffice（**本段不需要**）；
  - `C:\Windows\Fonts` 内无 Noto Sans Arabic / Tibetan；
  - 仓库 `.ttf/.otf/.ttc` 扫描 = 0（排除 `node_modules`）；
  - `apps/mobile-app/package.json` 的 `dependencies` 里缺 8 个包（见 §二 第 10 项）；
  - `services/` 目录不存在；
  - `docker` 未安装，`wsl` 存在，`java version "17.0.17"`；
  - `web_fetch` 对 `npc.gov.cn` / `github.com` / `spring.io` / `debian.org` 等外网域名全部报 `resolves to a non-public IP address`（**这是模板内容扒不到官方原文的直接原因**）。
- **读过的文档**：总纲 §5.5/§4.1/§4.2/§6.2；技术路线 §5.6/§5.11/§10.1–10.5/§12.3.2/§4.8.4；数据表规范附录 A/附录 B/§4.1/§8.1；接口规范；后端代码规范 §9.2/§9.3；错误码规范 §3.6；段 1/2/3 工作流（各自 §八.1 的承诺）；`design/ui/23–28*.html` 六页；`design/admin/17-doc-template.html`；导航树 §模块 6；PC 后台开发计划 A-18 行；UI 设计顺序 L567–572。
- **已知局限**：
  1. **段 0~3 的施工一次都没跑**，§二 的 19 项前置是对上游出口的**预测性要求**，不是实测现状。开工前必须逐条复查。
  2. **`openhtmltopdf` 的中文渲染能力未在本机实测**（本机没有 `services/` 工程，也还没起 Maven 工程）。§二 第 5 项把「10 行代码跑通中文 PDF」列为**开工前置**，就是为此。R9（中文断行）也未实测。
  3. **5 份模板的内容未产出**：§三 B3 给的是「字段清单与内容基准」，不是真实法律文本；且跨境电商/借条**没有官方文本**。
  4. **`4-A5` 的容差是技术侧自定的**（4mm），并经负责人 2026-11-01 授权——**这是自证，不是第三方验收**，已在 §四 该条里留痕。
  5. **段 3 承诺的 `ArticleNoValidator` 本段未使用**：§七 第 7 条与 §八.2 已如实记录，**不假装用上**。
  6. **`openhtmltopdf` 是 2023 年归档的项目**（最后版本 1.0.10）。选它是因为它是唯一的 LGPL「HTML→PDF」方案，但这是**已知的长期风险**，见 §六 R1 与 §十 第 5 项。
  7. **PDF 转 PNG 的清晰度与性能未实测**：E4 的预览方案在原理上成立（PDFBox `PDFRenderer`），但 200 DPI 下一张 A4 约 1654×2339 px，内存与流量要实测后调参。
  8. 本文档**不重复** DDL 全文以外的规范条款、i18n key 命名规则、审计动作清单——那些看各自的规范文档。

---

> **【文档基线 v1.28】** 本文件属于「法泽 FAZE 文档基线 v1.28」（2026-11-01 纳入）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
