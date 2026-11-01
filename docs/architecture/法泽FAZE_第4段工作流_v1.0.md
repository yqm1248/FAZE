# 第 4 段工作流：文书中心

> **上位文档**：《[法泽FAZE_开发计划总纲_v1.0.md](法泽FAZE_开发计划总纲_v1.0.md)》§5.5（本段边界与锚点）、§4.1/§4.2（页面归属）。
> **位置**：本段是段 4 的**唯一施工文档**。从第一行读到最后一行即可施工。技术路线、数据表规范、接口规范、后端代码规范、i18n 规范这五份是「怎么写」的规则书；本文档只说明「做什么、什么顺序、怎么算做完」——需要时按本文档给的章节号去查。
> **本段规模**：APP **6 页** / PC **1 页**（A-18）/ 表 **4 张** / 接口 **17 个**（App 12 + PC 5）/ 锚点 **8 条** / 任务 **7 组 24 个**。
> **写作依据**：仓库 HEAD `f7e887d`（分支 `UI设计`），2026-11-01 22:1x 实测。

---

## ⚠️ 开工前必读：本段范围已变更（2026-11-01 负责人拍板「只做中文」）

**总纲 §5.5 是 2026-10-30 写的，本文档 2026-11-01 写。两者在范围上不一致，以本文档为准，但必须先把差异说清——否则照总纲施工的人会做出另一套东西。**

| 项 | 总纲原文（2026-10-30） | 本段实际（2026-11-01 拍板） | 影响 |
|---|---|---|---|
| 段定位 | 「少数民族文字排版的**最终检验场**」 | **用户自助生成法律文书的完整链路** | 段定位改写，见下表 |
| 文书语言 | 四语言各一份模板（`zh-Hans`/`bo`/`ug-Arab-CN`/`kk-Arab-CN`） | **只有 `zh-Hans`** | 模板 **20 份 → 5 份** |
| 模板数量 | 5 类 × 4 语言 | 5 类 × 1 语言 | 同上 |
| PDF 渲染 | LibreOffice headless，理由是「不做 shaping 排不了阿拉伯系连写与藏文叠字」 | LibreOffice **仍然要**，但理由变成「`.docx` → PDF 的版式转换」 | 职责降级，风险下降 |
| PDF 少数民族文字 | 技术路线 §10.3 整节 | **本段不涉及** | 段 4 不读该节 |
| 锚点 `4-A3` | 维吾尔文连写正确 | **删除**，改为「Word 与 PDF 内容一致」 | 编号不复用空号 |
| 锚点 `4-A4` | 藏文叠字正确 | **删除**，改为「模板热更新不改历史文书」 | 同上 |
| 锚点 `4-A6` | PDF 元数据含 5 个 **AIGC 隐式标识**字段 | 改为「**文书元数据溯源**」，**不含 AIGC 语义** | 理由见 §零.3 第 9 项 |
| 字体授权 | 段 0 R3「必须在段 4 之前定」 | **本段不再依赖**（文书无民族文字） | 段 0 的 `0-A4` 仍需要它，但不挡段 4 |

### 为什么改（负责人的判断依据，2026-11-01 原话意图）

法律**并不要求**文书必须中文。《宪法》第 134 条：「在少数民族聚居或者多民族共同居住的地区，应当用当地通用的语言进行审理；**起诉书、判决书、布告和其他文书应当根据实际需要使用当地通用的一种或者几种文字**」；《民事诉讼法》第 11 条：「在少数民族聚居或者多民族共同居住的地区，人民法院应当用当地民族通用的语言、文字进行审理和**发布法律文书**」。伊犁哈萨克自治州正属这个范围。

但**实务上**，当事人递交法院的起诉状绝大多数是中文：卷宗归档、对方当事人、上诉审级、法官工作语言都是中文。

所以问题变成：**法泽的文书是给谁看的？** 负责人定为「**能递交给法院的正式文件**」→ 中文。这与总纲的「少数民族文字检验场」是两个不同的产品假设，**本次改的是假设，不是实现方式**。

### 改完之后，什么变了、什么没变

| 变了 | 没变 |
|---|---|
| 5 份中文模板（不是 20 份） | **APP 页面 UI 仍然必须四语言**（`AGENTS.md` §1 是硬约束，用户看不懂中文界面） |
| 不验维文连写、藏文叠字 | `document.lang` / `doc_template.lang` **字段保留**，仅取值收敛为 `zh-Hans` |
| LibreOffice 只做版式转换 | poi-tl 生成 `.docx` 的链路不变 |
| 段 4 不再依赖字体授权 | 段 0 的 `0-A4`（藏文渲染）仍要验，那是**界面**的事 |

> **最容易搞错的一条**：把「文书只做中文」误解成「页面只做中文」。**不是。** 六页界面文案仍走 `t()`、仍要 `zh-Hans` + `ug-Arab-CN` 两套（`AGENTS.md` §4 第 4 条、§7 完成定义）。§三 E 组的每个任务都带这条约束。

### 这两处需要回写（不是本文档自己改完就算）

| # | 文档 | 改什么 |
|---|---|---|
| 1 | 总纲 §5.5 | 段定位、交付物语言、8 条锚点中的 3 条（`4-A3`/`4-A4`/`4-A6`） |
| 2 | 技术路线 §10.3 | 标注「本节的少数民族文字 PDF 渲染，段 4 不使用；仅当未来恢复多语文书时再启用」 |

回写动作见 §九。

---

## 零、开工前必须知道的事（2026-11-01 撰写时实测）

### 零.1 本段的真实起点：段 0、段 1、段 2、段 3 的施工一次都没跑

实测（`E:\dshcode\FAZE`，2026-11-01 22:0x）：

| 事实 | 证据 |
|---|---|
| `services/` 目录**不存在** | `Test-Path services` → False。段 1 承诺的 `faze-api` 骨架代码一行都没有 |
| `apps/mobile-app` 只有 UI 复刻壳，**依赖里没有段 4 需要的任何库** | 见 §二 第 11 项 |
| 仓库源码里**零字体文件** | `*.ttf/*.otf/*.ttc` 扫描（排除 `node_modules`）= **0**。但本段不需要（只做中文） |
| 本机**未安装 LibreOffice** | `Get-Command soffice` 为空；三个常见路径均不存在。这是本段**唯一真正的环境硬缺口**（§二 第 5 项） |
| 本机 JDK | `java version "17.0.17" 2025-10-21 LTS` ✅ |

**这意味着本文档的 §二「开工前置检查」是对上游出口的预测性要求，不是实测现状。** 与段 1、段 2、段 3 的处境相同。

### 零.2 上游承诺了什么（段 4 可直接依赖，不许重造）

逐条来自各段工作流 §八.1，**引用时必须回查原文**：

| # | 承诺 | 来源 | 段 4 怎么用 |
|---|---|---|---|
| 1 | `ArticleNoValidator.validate(String answerText)` → `ALL_EXIST` / `PARTIAL_MISS` / `NO_REF` | 段 3 §八.1 第 1 条 | 文书法条引用块校验。**不许另写一份**（段 3 原文：「段 4 文书中心复用本服务，不许另写一份」） |
| 2 | `refs` 快照 schema 冻结：`[{lawItemId, lawCode, lawName, articleNo, content, effectiveDate, status}]` | 段 3 §八.1 第 2 条 | 文书的法条引用块按同一结构存。**本段不做引用块**（见 §七），但字段口径要留一致 |
| 3 | `task_queue` 的领取是 **CAS + 租约回收** | 段 2 §八.1 第 5 条 | 文书生成走异步任务。**段 2 原文：「段 4 文书生成、段 7 内容审核可直接复用这套消费模式」** |
| 4 | `POST /app/upload/token` + `GET /app/upload/url?key=` | 段 1 C1 | 上传签名图 PNG、上传模板 `.docx`。**不重造** |
| 5 | 私有 OSS + 60 秒签名 URL；凭据走环境变量 | 段 1 §零.2 第 5 项 / C1 | 签名图、文书 PDF 都放私有桶 |
| 6 | 余额扣款唯一入口 `deduct()` | 段 2 §八.1 第 1 条 | **本段不用**（文书免费，§零之二 第 3 项） |
| 7 | `R<T>` / JWT / 分页 / 错误码归一化 / `@OperationLog` | 段 0 / 段 1 | 照段 1 的写法 |
| 8 | MP 分页：`mybatis-plus-spring-boot4-starter:3.5.18.1` + `mybatis-plus-jsqlparser:3.5.18.1` | 段 1 §三 A3 + `docs/acceptance/seg1/A3-mp-probe.md` | 照用，**不要再踩 `-spring-boot3-` starter 的坑** |

### 零.3 已经定了的，本段不再讨论

| # | 事项 | 结论 | 出处 |
|---|---|---|---|
| 1 | 表名 | **`doc_template` / `document` / `signature` / `file_record`** | 技术路线 §5.6 L1419/L1438、§5.11 L2063；数据表规范附录 A L530–532/L559 |
| 2 | 总纲与 PC 计划里的 `document_template` / `document_instance` | **是错的**，本段统一为 `doc_template` / `document` | 本段裁决 D1 |
| 3 | 文件字段口径 | 统一 **`oss_key`**，不存完整 URL（`document` 表的 `word_url`/`pdf_url`/`signed_pdf_url` 要改名） | 数据表规范附录 B L599 已裁决，DDL 未同步。本段裁决 D2 |
| 4 | 文书在线编辑 | **不做**。预览页只读；点段落 → 跳回表单页；改完用户自己点「生成」 | 负责人 2026-11-01 拍板 |
| 5 | 文书收费 | **免费，不限次**。不接 `deduct()`，不扣配额 | 负责人 2026-11-01 拍板 |
| 6 | 首批模板 | **5 类全做**：起诉状 / 证据清单 / 劳动合同 / 跨境电商合同 / 借条欠条，各 1 份中文 `.docx` | 负责人 2026-11-01 拍板 + 导航树 L304 |
| 7 | 签名笔迹 | 手写笔迹图叠加，**不主张法律效力**。可靠电子签名要 CA，明确不做 | 技术路线 §10.4；总纲 §5.5 |
| 8 | 异步任务 | 走段 2 的 `task_queue`，**不引入 RabbitMQ** | 后端代码规范 §9.2；总纲 §8 裁决 |
| 9 | 隐式标识 | 文书**不是 AIGC**，不打 `Faze-Content-Type: AIGenerated`。改为写「文书溯源元数据」 | 本段裁决 D3，理由见下 |
| 10 | A4 边距 | 上 72 / 下 72 / 左 90 / 右 72 pt；正文仿宋 三号 16pt、行距 28pt | 技术路线 §10.5.1 |
| 11 | 签字位置容差 | 总纲写「建议 ≤ 4mm，**具体由负责人定**」 | **未定**，见 §十 第 1 项 |
| 12 | 语言字段 | `doc_template.lang` / `document.lang` **保留**，本段只写 `zh-Hans` | 本段裁决 D4（删字段成本高于保留空值） |

**第 9 项的理由（这条最容易做错）**：《人工智能生成合成内容标识办法》约束的是「AI 生成合成内容」。法泽的文书面是「用户填表 + 运营配的 `.docx` 模板 + poi-tl 文本替换」——**没有任何一步是模型生成的**。给它打 `AIGenerated` 标签属于**虚假标识**，与不打一样是问题。所以：

- **删掉** `Faze-Content-Type: AIGenerated`
- **保留**溯源字段（它们是真实信息，有运维价值）：见 §四 `4-A6`

段 3 §八.1 第 3 条说「段 4 写 PDF 元数据 `Faze-Content-Id: {content_id}`」——那条假设是「文书里嵌了 AI 内容」。本段不接 AI，该假设不成立，故不执行；段 8 若恢复多语或 AI 起草，再回来补。

### 零.4 本段四条「不许」（写在这里防走样）

| # | 不许 | 为什么 |
|---|---|---|
| 1 | **不许把「文书只做中文」扩大到「界面只做中文」** | `AGENTS.md` §1 定死四语言；六页的 UI 文案仍要 `t()` + `zh-Hans`/`ug-Arab-CN` |
| 2 | **不许改框架源码** | 服务端 `services/faze-api` 是新工程不受此限；但 PC 端基于 RuoYi-Vue3，`AGENTS.md` §3.3 明确「不得修改 RuoYi 框架层代码」，确需改动走 `docs/analysis/ruoyi-patches.md` |
| 3 | **不许用 `openhtmltopdf` 或任何「自己写 PDF」的方案** | 本段虽只做中文，但一旦有人为了省掉 LibreOffice 改用 `iText` 直接画，版式会与 Word 不一致（`4-A3` 就是验这个）。`.docx` → PDF 只有一个真源：LibreOffice |
| 4 | **不许把签名图、文书 PDF 放进公开可读的 OSS 路径** | `AGENTS.md` §5 明令；数据表规范 §L3 私有 OSS + 短时签名 URL |

---

## 零之二、2026-11-01 负责人拍板的 9 项（逐项落到哪一节）

> 与 §零.3 的关系：§零.3 是「结论」，本节是「据此要改什么、影响哪些节」。

| # | 决定 | 落到哪 |
|---|---|---|
| 1 | **文书只做中文**（`zh-Hans`） | §开工前必读整节；§三 B 组只产 5 份 `.docx`；§四 删 `4-A3`/`4-A4` 原口径；§五 产出清单 |
| 2 | **在线编辑不做**：预览页只读，点段落 → 跳回表单页，改完用户自己点「生成」 | §三 E4；§四 `4-A1` 的验收路径；§七「明确不做」 |
| 3 | **文书免费不限次** | §三 全线不接 `deduct()`；§五 无扣费接口；§七「明确不做」 |
| 4 | **5 类模板全做**（起诉状/证据清单/劳动合同/跨境电商合同/借条欠条） | §三 B3；§五.5；§二 第 8 项前置（模板内容由谁写） |
| 5 | **藏文字体「有开源的」→ 本段不再依赖** | §开工前必读；§二 第 6 项（字体不再阻塞本段） |
| 6 | **LibreOffice 用途确认**（`.docx`→PDF 版式转换） | §三 A2；§六 R1 |
| 7 | 预览页点击段落的行为 | §三 E4 第 3 条；§四 `4-A1` 步骤 |
| 8 | **理解 1**（跳回表单页 → 改 → 用户自己点「生成」→ 文书更新） | §三 E4；§四 `4-A1`/`4-A4` |
| 9 | 本段**只出计划，不写代码** | 本文档全篇的性质：它是计划，不是实现。§五 的「产出物」是**施工时**要产出的东西 |

---

## 一、本段目标与出口

**目标（三句）**：把「运营在 PC 后台配一份 `.docx` 模板 → 用户在 App 填表 → 生成 `.docx` 与 PDF → 下载/打印/分享 → 手写签名叠到 PDF 上」这条链一次做通；文书的**版式**必须经得起打印与递交，**失败时不留半成品**；把 A-18 做成运营自己就能维护模板的工具（不靠改代码）。

**本段不追求的东西**：不做少数民族文字（已拍板）、不做在线编辑、不做可靠电子签名、不做法院系统对接、不做 AI 起草文书。

### 一.1 出口（进段 5 的条件）

1. `4-A1` ~ `4-A8` **八条全部通过**，证据在 `docs/acceptance/seg4/`。
2. **5 份中文模板在库**：`doc_template` 有 5 条 `status='active'` 的记录，每条的 `oss_key` 指向一个真实可渲染的 `.docx`（不是占位文件）。
3. **生成链在「生产同款环境」跑通**：Windows + 本机 LibreOffice，`soffice --headless --convert-to pdf` 能成功，转换后 PDF 的字体是**嵌入**的（不是引用系统字体）。
4. **失败路径已验**：至少 3 种失败（模板字段缺失 / LibreOffice 崩溃 / OSS 上传超时）都能给出明确错误码，且 `document` 不留 `generated` 记录、临时文件被清理。
5. **A-18 可用**：运营能在后台新建模板 → 上传 `.docx` → 填字段定义 → 启用 → App 端立刻出现该模板（**不改代码、不重启服务**）。
6. **本段的文档回写完成**：§九 的 7 项，缺一条即未收口（总纲 §6.2 收口五件套）。

### 一.2 反向定义（什么不算出口）

| 看起来像完成，其实不算 | 为什么 |
|---|---|
| 用**硬编码的模板**（直接读仓库里某个 `.docx`）生成成功 | 违反「模板由 A-18 配置，不在代码里写死」（总纲 §5.5「必须做」第 1 条）；`4-A1` 的路径必须是「选模板 → 填表」，模板来源是库不是文件 |
| PDF 生成成功，但**只看截图没看字体嵌入** | 换台没装仿宋的机器，PDF 会变字形。`4-A2` 要求字体嵌入 |
| 预览页能显示，但显示的是**服务端渲染的 HTML 截图** | 那验不到 PDF。预览必须来自**真实 PDF** |
| 签名能叠上去，但坐标是**写死的**（不随页面尺寸换算） | `4-A5` 验的是「位置偏差」，写死坐标换个模板就错位 |
| 生成失败时 `document` 记 `status='generated'` 但文件为空 | 违反「失败不留半成品」（总纲 §5.5「必须做」第 2 条）；`4-A8` 直接判不通过 |
| 六页能打开，但四态（正常/加载/空/错误）不全 | `AGENTS.md` §7 完成定义的硬要求 |

---

## 二、开工前置检查（逐条勾，不通过不开工）

**20 项，缺一项不许开工。** 前 6 项是段 3 的出口承诺，7~20 是本段新增。

| # | 检查项 | 怎么确认 | 不通过怎么办 |
|---|---|---|---|
| 1 | 段 3 已出口（`3-A1`~`3-A8` 全过） | 读 `docs/acceptance/seg3/` 下 8 条证据 | 回段 3 补。**不许跳过**——`ArticleNoValidator` 的复用契约在这里 |
| 2 | `services/faze-api` 可启动 | `curl /api/v1/pub/ping` 返回 `{appName,version,...}` | 回段 1 |
| 3 | 段 2 的 `task_queue` 表已建，且有消费端在跑 | `SELECT * FROM task_queue LIMIT 1`；段 2 的定时任务日志有一条「领取成功」 | 回段 2。**本段的文书生成全靠它** |
| 4 | `POST /app/upload/token` + `GET /app/upload/url` 可用 | 上传一张 PNG，拿到 key，再用签名 URL 取回 | 回段 1 C1 |
| 5 | **LibreOffice 已安装，`soffice --version` 能输出** | `soffice --version` → 有版本号（7.x） | **本段唯一的真环境缺口。** 见下方「怎么装」 |
| 6 | OSS 私有桶可用：能上传、能生成 60 秒签名 URL、**公开访问返回 403** | 用浏览器直接开 OSS 原 URL，必须 403 | 向负责人要凭据配置；桶策略不对则改私有 |
| 7 | RuoYi 后端可启动（8080）、PC 前端可运行 | 段 0 的 `0-A6` 证据 + `npm run dev` 打开 `17-doc-template.html` 对应的实现页 | 回段 0 |
| 8 | **5 类模板的 `.docx` 内容有下落**（谁写、什么时候交） | 负责人确认：由运营/法务提供，或由技术侧给占位版 | **不许「先做机制，内容以后再说」收工**。见 §十 第 2 项 |
| 9 | 真实起诉状样例（1 份，用于版式对齐） | 文件在工作区某处可访问 | 技术路线 §10.5.1 原话「建议找一份真实的起诉状样例对齐，不要凭感觉排版」 |
| 10 | 本段 4 张表的 DDL 已按 §三 A1 定稿，且与《数据表规范》一致 | 逐字比对本文档 §三 A1 的字段表 | 未对齐不得开工建表 |
| 11 | 移动端依赖齐备（见下） | `npx expo install` 能装上，`tsc --noEmit` 通过 | 缺哪个补哪个，**用 `npx expo install` 不用 `npm i`**（版本对齐 Expo SDK 54） |
| 12 | `apps/mobile-app` 能打 dev-client 包 | 段 0 的 `0-A1` 证据 | 回段 0。**本段要 skia，Expo Go 跑不了** |
| 13 | 段 1 的 JWT 与 `currentUser` 上下文可用 | 带 token 请求 `/api/v1/app/xxx` 返回 200 | 回段 1 |
| 14 | 分页三场景的实现可用（C 端 `list`） | 段 2 的列表接口是同一套 | 回段 2 |
| 15 | `operation_log` 的 AOP 可用（管理端写接口自动审计） | 段 1 验收证据 | 回段 1 |
| 16 | i18n 的 14 个 namespace 目录已建，`t()` 可用 | 段 0 的 `0-A7` 证据 | 回段 0。**六页的 UI 文案靠它** |
| 17 | 页面四态组件（正常/加载/空/错误）可用 | 段 0 的 `0-A3` 证据 | 回段 0 |
| 18 | `apps/mobile-app` 的 `assets/fonts/` 已按段 0 落地 | 段 0 的 D2 产物 | 不影响本段（中文用系统字体），但段 0 出口要求它 |
| 19 | 本段验收脚本骨架 `deploy/verify-seg4.ps1` 已建 | 文件存在，跑出「全部未通过」 | 没有脚本就没有可重复验收 |
| 20 | **MySQL 凭据可用**（本段要建 4 张表） | `mysql -u<user> -p -e "select 1"` | 段 1 §二 第 8 项标「实测当前缺」→ 若仍未给，建表无法开工 |

### 第 5 项怎么装（LibreOffice）

```powershell
# 方式一：官网安装包（推荐，Windows 生产同款）
#   下载 https://www.libreoffice.org/download/ → 安装到默认路径
#   默认路径：C:\Program Files\LibreOffice\program\soffice.exe
# 方式二：包管理器（若本机有 winget）
winget install --id TheDocumentFoundation.LibreOffice -e
```

**装完必须验的三件事**：

```powershell
# 1. 有版本
& "C:\Program Files\LibreOffice\program\soffice.exe" --version
#    预期输出 LibreOffice 7.x.y ...

# 2. PATH 里能找到（不依赖绝对路径，代码里要能直接调）
$env:Path -split ';' | Where-Object { $_ -like '*LibreOffice*' }
#    若没有：把 C:\Program Files\LibreOffice\program 加进系统 PATH

# 3. 能做一次真实转换（拿任意 .docx 试）
& soffice --headless --convert-to pdf --outdir "$env:TEMP\lotest" "某个.docx"
#    预期在 $env:TEMP\lotest\ 下出同名 .pdf
```

**为什么本段还要它（即使只做中文）**：poi-tl 只输出 `.docx`，用户要 PDF；`.docx` → PDF 必须有一个忠实的渲染器。可选方案只有 LibreOffice（开源）、Word COM（要装 Office，且服务端跑 Office 违反授权）、商业库（收费）。**中文不涉及字形整形**，所以 LibreOffice 的风险从「能不能排对维吾尔文」降为「版式像不像 Word」——这是本段最大的一个好消息（§六 R1）。

### 第 11 项：移动端必须装齐的依赖

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

**版本对齐**：全部用 `npx expo install`（它按 SDK 54 的兼容矩阵选版本），**不要用 `npm install` 手写版本号**——Expo 的包之间有硬版本约束。

**skia 需要 dev-client**：`@shopify/react-native-skia` 是原生模块，Expo Go 里跑不了。段 0 的 `0-A1` 已经要打 dev-client 包，所以这不是本段新增的成本，但**必须确认段 0 真的打了**（第 12 项）。

---

## 三、任务分组（7 组 / 24 个任务）

**编号规则**：任务是 `A1`~`G2`；验收锚点是另一套 `4-A1`~`4-A8`（总纲已定，本段只改内容不改编号规则）。**两套编号不通用**，引用时必须带前缀。

**组与组之间设检查点，组内连续推进。** 检查点的含义是「这一组全收口才有资格开下一组」，不是每个任务设闸门。

| 组 | 任务 | 这一组解决什么 | 组末检查点（过不了不许开下一组） |
|---|---|---|---|
| **A 地基**（4） | A1 → A2 → A3 → A4 | 4 张表、LibreOffice 通道、文件登记、任务表接入 | `flyway:migrate` 出 4 张表；`soffice` 能转一个 .docx；`task_queue` 能捞到 `DOC_GENERATE` |
| **B 模板**（3） | B1 → B2 → B3 | A-18 页面、`field_schema` 规范、5 份 .docx | 后台新建模板 → App 端立刻可见（不重启） |
| **C 生成内核**（4） | C1 → C2 → C3 → C4 | 草稿、Word 渲染、PDF 转换、失败清理 | 一次完整生成跑通，失败路径也验过 |
| **D 签名**（3） | D1 → D2 → D3 | 签名图库、PDFBox 叠图、元数据 | 叠完章的 PDF 能打开、位置正确 |
| **E APP 六页**（6） | E1 ~ E6 | 六页 + 四态 + 多语言 | 六页都能进、四态齐全、`ug` 下无布局错 |
| **F 后处理**（2） | F1 → F2 | 下载/分享/存相册/打印、重新生成 | 四项都能用 |
| **G 收口**（2） | G1 → G2 | 验收脚本、证据与回写 | 8 条锚点有证据、文档回写完成 |

---

### A 组：地基（4 个任务）

#### A1 · 四张表 DDL 定稿与 Flyway 迁移

**做什么**：建 `doc_template`（13 字段）/ `document`（17 字段）/ `signature`（15 字段）/ `file_record`（14 字段），进 Flyway 迁移，空库可重放。

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
   | `oss_key` | `VARCHAR(500) NOT NULL` | **模板 `.docx` 的 OSS 对象键**（私有桶）。不存 URL |
   | `preview_oss_key` | `VARCHAR(500)` | 模板预览缩略图（第一页转图），A-18 列表用 |
   | `field_schema` | `JSON` | 字段定义，schema 见 B2 |
   | `lang` | `VARCHAR(20) NOT NULL DEFAULT 'zh-Hans'` | **本段只写 `zh-Hans`**（§零.3 第 12 项） |
   | `version` | `INT NOT NULL DEFAULT 1` | 模板版本；每次改内容 +1，**旧 key 不删**（历史文书要能复现） |
   | `sort_order` | `INT DEFAULT 0` | App 端排序 |
   | `status` | `VARCHAR(20) NOT NULL DEFAULT 'draft'` | `draft` / `active` / `inactive`（设计稿三态） |
   | `created_at` | `DATETIME NOT NULL` | 应用层填 UTC |
   | `updated_at` | `DATETIME NOT NULL` | 同上 |

   唯一键：`uk_template_code_lang_version (code, lang, version)`。
   索引：`idx_template_status_sort (status, sort_order)`（App 列表查）。

   > **与数据表规范的一处偏离（必须知道）**：§4.1 要求 D 类表必须有 `is_enabled`。本表**不加**，用 `status` 三态代替。理由：`status` 已覆盖「启用/停用」，且多一个 `draft`（设计稿要的第三态）；两个字段并存会出现 `status='active'` 但 `is_enabled=0` 的矛盾状态。这条偏离登记在 §九 第 5 项。

   > **删掉了技术路线的 `template_url`**：它与 `oss_key` 定位同一件东西，两个真相。按数据表规范附录 B L599「业务表存 `oss_key`，不存完整 URL」只留 `oss_key`。

   **`document`（17 字段，C 类状态机）**

   | 字段 | 类型 | 说明 |
   |---|---|---|
   | `id` | `BIGINT PK AUTO_INCREMENT` | 主键 |
   | `user_id` | `BIGINT NOT NULL` | 归属用户 |
   | `template_id` | `BIGINT` | 引用 `doc_template.id`；可空（模板被删后历史文书仍在） |
   | `template_version` | `INT` | 生成时用的模板版本（复现依据） |
   | `lang` | `VARCHAR(20) NOT NULL DEFAULT 'zh-Hans'` | 本段固定 `zh-Hans` |
   | `title` | `VARCHAR(200) NOT NULL` | 文书标题（取模板名 + 原告名） |
   | `doc_type` | `VARCHAR(30)` | 冗余模板分类，列表筛选用 |
   | `form_data` | `JSON` | 当前表单数据（草稿可改） |
   | `form_snapshot` | `JSON` | **生成瞬间**的表单快照，不可变 |
   | `status` | `VARCHAR(20) NOT NULL DEFAULT 'draft'` | `draft` / `generated` / `signed` / `failed` |
   | `word_oss_key` | `VARCHAR(500)` | 生成出的 `.docx` |
   | `pdf_oss_key` | `VARCHAR(500)` | 转换出的 `.pdf` |
   | `signed_pdf_oss_key` | `VARCHAR(500)` | 叠完签名的 `.pdf` |
   | `lawyer_id` | `BIGINT` | 分享给律师时记；**本段只写不读**（律师端在段 5） |
   | `created_at` | `DATETIME NOT NULL` | 应用层填 UTC |
   | `updated_at` | `DATETIME NOT NULL` | 同上 |
   | `deleted_at` | `DATETIME` | 软删 |

   索引：`idx_document_user (user_id, deleted_at, created_at)`（我的文书列表）、`idx_document_template (template_id, template_version)`。

   > **改了一处字段名**：技术路线写 `word_url`/`pdf_url`/`signed_pdf_url`，本表改 `*_oss_key`。依据：数据表规范附录 B L599 已裁决「统一为 `oss_key`」，技术路线 DDL 没同步。这条是本段 D2 裁决，回写见 §九 第 3 项。

   > **加了一个状态值 `failed`**：技术路线只有 `draft/generated/signed/exported`。加 `failed` 是为了让 `4-A8`（失败不留半成品）可判——「失败」必须是一个**能被查询的状态**，不能靠「没有记录」推断。`exported` 本段不用（导出不改状态，`document` 本身不因下载而变）。

   **`signature`（15 字段，B 类追加型）**

   | 字段 | 类型 | 说明 |
   |---|---|---|
   | `id` | `BIGINT PK AUTO_INCREMENT` | 主键 |
   | `user_id` | `BIGINT NOT NULL` | 归属用户 |
   | `image_oss_key` | `VARCHAR(500) NOT NULL` | 签名笔迹 PNG，**私有桶** |
   | `document_id` | `BIGINT` | **可空**：NULL = 签名库（未使用）；非空 = 已叠到某份文书 |
   | `page_no` | `INT` | 第几页（从 1 开始） |
   | `pos_x_pt` | `INT` | 相对页面左上角，单位 pt（1pt = 1/72 inch） |
   | `pos_y_pt` | `INT` | 同上 |
   | `width_pt` | `INT` | 签名图在页面上的宽度 |
   | `height_pt` | `INT` | 同上 |
   | `base_page_w_pt` | `INT` | 渲染时页面宽度（A4 = 595），用于校验缩放 |
   | `base_page_h_pt` | `INT` | A4 = 842 |
   | `document_hash` | `VARCHAR(128)` | 对**盖签前**内容流算的 SHA-256 |
   | `hash_algo` | `VARCHAR(20) DEFAULT 'SHA-256'` | |
   | `signed_at` | `DATETIME` | 实际叠章时间；**签名库记录此列为 NULL** |
   | `created_at` | `DATETIME NOT NULL` | 应用层填 UTC |

   索引：`idx_signature_user (user_id, document_id)`（签名库列表 `document_id IS NULL`）、`idx_signature_document (document_id)`。

   > **这张表承担两个语义，必须知道**：`document_id IS NULL` 的行是「用户存的签名图」（28-doc-seal 管理页用）；非空的行是「这份文书上的这枚签名」（含坐标与哈希）。技术路线就是一张表，本段不改结构，但**所有查询必须显式带 `document_id IS NULL` 或 `IS NOT NULL`**，不能写成「按 user_id 查」。这是本段最容易写错的一处（§六 R6）。

   **`file_record`（14 字段，A 类业务实体）**

   逐字按技术路线 §5.11 L2063–2082 抄，**一字不改**：`id` / `oss_key` / `bucket` / `original_name` / `content_type` / `size_bytes` / `md5` / `biz_type` / `biz_id` / `uploader_id` / `is_private` / `status` / `created_at` / `deleted_at`。
   唯一键 `uk_file_oss_key (oss_key)`；索引 `idx_file_biz (biz_type, biz_id)`、`idx_file_uploader (uploader_id, created_at)`、`idx_file_orphan (status, created_at)`。

   > **这张表原本归段 4，但段 1 已裁决不用它**（段 1 §三 第 74 行：实名影像直接存 `realname_auth.id_card_front/back`）。本段是它的**第一次真正使用**：签名图、模板 `.docx`、文书 PDF 都登记进来。`biz_type` 取值：`doc_template` / `document` / `signature`。

4. **回滚脚本**：`V400_1__drop_document_tables.sql`（只写 `DROP TABLE`，供段内回滚用；**不进 `flyway_schema_history`**，手动执行）。总纲 §6.2 要求「段内可回滚」，4 张表互相无外键，DROP 顺序 `signature` → `document` → `doc_template` → `file_record`。

**前置**：`services/faze-api` 可启动、Flyway 通道可用（§二 第 20 项 MySQL 凭据）。

**产出物**：
- `services/faze-api/src/main/resources/db/migration/V400__create_document_tables.sql`
- 同上目录 `V400_1__drop_document_tables.sql`
- `docs/acceptance/seg4/A1-ddl.md`（建表输出 + `SHOW CREATE TABLE` 四张表的完整结果）

**自查（全过才收口）**：
- [ ] `flyway:migrate` 在**空库**执行成功，`flyway_schema_history` 有 `V400`
- [ ] `SHOW TABLES` 里有 4 张表，字段数与上表一致（13 / 17 / 15 / 14）
- [ ] 字段注释齐全（`information_schema.COLUMNS.COLUMN_COMMENT` 非空）
- [ ] 没有外键约束（数据表规范 §附录 B 要求软关联）
- [ ] 回滚脚本能跑通，跑完 4 张表消失
- [ ] `doc_template` 的 `lang` 默认值是 `'zh-Hans'` 不是 `'zh-CN'`

**常见失败**：
1. 用 `flyway:clean` 代替重放 —— 会清掉段 0~3 的数据，**禁止**。
2. 表名写回 `document_template` —— 那是总纲的旧名（§零.3 第 2 项）。
3. `signature.document_id` 加了 `NOT NULL` —— 签名库就存不进去了。
4. `document.status` 漏了 `failed` —— `4-A8` 无法验。

---

#### A2 · LibreOffice 转换通道（`DocumentConverter`）

**做什么**：把「`.docx` 文件 → PDF 文件」封装成一个可被任务表调用的服务，处理超时、并发、临时目录、字体嵌入。

**怎么做**：

1. **类**：`infra/pdf/DocumentConverter.java`（`AGENTS.md` §3.2 规定 PDF 相关放 `infra/`）。

2. **核心调用**（必须用 `ProcessBuilder`，不许用 JNI/wrapper）：

   ```java
   // 关键点：每次调用必须给独立的 UserInstallation profile
   // 否则并发时多个 soffice 抢同一个 profile 锁，第二个直接失败（"UserInstallation" 冲突）
   List<String> cmd = List.of(
       sofficePath,                                    // 从 sys_config 或环境变量读绝对路径
       "--headless",
       "--norestore",
       "--nolockcheck",
       "--invisible",
       "-env:UserInstallation=file:///" + profileDir.toUri().getRawPath(),
       "--convert-to", "pdf",
       "--outdir", outDir.toString(),
       docxFile.toString()
   );
   ProcessBuilder pb = new ProcessBuilder(cmd);
   pb.redirectErrorStream(true);
   Process p = pb.start();
   boolean done = p.waitFor(60, TimeUnit.SECONDS);   // 超时 60 秒
   ```

3. **三个必须处理的细节**：

   | 细节 | 怎么做 | 不做的后果 |
   |---|---|---|
   | **独立 profile** | `-env:UserInstallation=file:///` + 每次生成一个临时目录 | 并发第二个进程直接失败，且报错信息是首次启动向导相关，完全看不出是并发问题 |
   | **超时** | `waitFor(60, SECONDS)`，超时 `destroyForcibly()` | LibreOffice 卡死会让整个任务线程池耗尽 |
   | **清理** | `finally` 里删临时 profile 与 outdir（**除非成功且已上传 OSS**） | 磁盘疯长；且违反 `4-A8`「失败不留半成品」 |

4. **字体嵌入校验**（`4-A2` 的判据）：转换后用 PDFBox 读 PDF，检查 `/FontDescriptor` 是否有 `FontFile2`/`FontFile3`。没有嵌入 = 换机器会变字形。

   ```java
   try (PDDocument doc = Loader.loadPDF(pdfFile)) {   // PDFBox 2.0.x 用 PDDocument.load(...)
       for (PDPage page : doc.getPages()) {
           for (COSName fontName : page.getResources().getFontNames()) {
               PDFont font = page.getResources().getFont(fontName);
               // font.isEmbedded() 为 false → 记警告，写进转换报告
           }
       }
   }
   ```

5. **soffice 路径来源**：`sys_config` 的 `doc.soffice.path`，取不到则读环境变量 `FAZE_SOFFICE_PATH`，都没有则**启动时报错**（不做硬编码 `C:\Program Files\...`）。

**前置**：§二 第 5 项 LibreOffice 已装。

**产出物**：
- `services/faze-api/src/main/java/com/faze/infra/pdf/DocumentConverter.java`
- 单测：`DocumentConverterTest`（正常转换 / 超时 / 文件不存在 / 并发 3 个同时转换）
- `docs/acceptance/seg4/A2-converter.md`（4 个用例的输出日志）

**自查**：
- [ ] 拿任意 `.docx` 转出 PDF，PDF 能打开、页数一致
- [ ] **并发 3 个不同文件同时转，3 个都成功**（这条专验 profile 隔离）
- [ ] 故意给一个不存在的 `.docx`，返回明确异常，不抛 `NullPointerException`
- [ ] 超时能被触发（用一个超大文件或人为 `sleep` 验证 `destroyForcibly` 生效）
- [ ] 转换后临时目录被清理（`$env:TEMP` 下不残留 `lo-profile-*`）
- [ ] PDF 字体嵌入检查能报出「未嵌入」的字体名

**常见失败**：
1. 不传 `-env:UserInstallation` → 单次能跑，**并发必炸**，而且报错看不懂。
2. `soffice` 用相对路径 → 单位测试能过，服务启动后工作目录不同就找不到。
3. 输出文件名假设是「输入文件名 + .pdf」→ LibreOffice 会按 `--outdir` + 输入 basename 命名，中文文件名可能被转义，**必须以 `--outdir` 里实际出现的文件为准**。
4. 忘了 `--norestore` → 若上次 LibreOffice 崩溃过，下次启动会弹恢复对话框，headless 下卡死。

---

#### A3 · 文件登记与私有 OSS 通道

**做什么**：把所有本段产生的文件（模板 `.docx`、签名 PNG、文书 `.docx`/`.pdf`）登记进 `file_record`，统一走私有桶 + 60 秒签名 URL。

**怎么做**：

1. **上传通道复用段 1**：`POST /app/upload/token` 签发直传签名 + `GET /app/upload/url` 取回签名 URL。**本段不新增上传接口。**

2. **服务端产生的文件（文书 .docx/.pdf）**：走服务端直接 `PutObject`，不经客户端直传。上传后调 `FileRecordService.record(...)` 登记。

3. **`biz_type` 取值固定三个**：

   | `biz_type` | `biz_id` | 用途 |
   |---|---|---|
   | `doc_template` | `doc_template.id` | 模板 `.docx` 与预览图 |
   | `document` | `document.id` | 文书的 `.docx` / `.pdf` / `signed_pdf` |
   | `signature` | `signature.id` | 签名 PNG |

4. **签名 URL 有效期 60 秒**（与段 1 一致）。`4-A7` 的下载要考虑到「用户可能点开后才慢慢看」，所以下载接口**每次调用重新签发**，不缓存 URL。

**前置**：§二 第 6 项 OSS 私有桶可用。

**产出物**：
- `services/faze-api/src/main/java/com/faze/infra/oss/FileRecordService.java`
- `docs/acceptance/seg4/A3-oss.md`（三类文件各上传一次 + `file_record` 查询结果 + 公开 URL 访问 403 的截图）

**自查**：
- [ ] 三类文件都能上传并登记，`file_record.oss_key` 与 OSS 实际对象一致
- [ ] 用 OSS 原始 URL 直接访问 → **403**（私有桶生效）
- [ ] 用 60 秒签名 URL 访问 → 200
- [ ] 60 秒后再用同一 URL → 403
- [ ] 日志里**不出现**完整 `oss_key`（数据表规范 §L4）

**常见失败**：
1. 把 OSS 的完整 URL 存进 `document.pdf_oss_key` —— 违反「业务表存 `oss_key`」。
2. 签名 URL 有效期设成 1 小时方便调试 → 上线忘改，等于半公开。
3. 日志打 `log.info("uploaded: {}", ossKey)` → 违规。

---

#### A4 · `task_queue` 接入 `DOC_GENERATE`

**做什么**：把文书生成注册成段 2 任务表的一种任务类型，走 CAS 领取 + 租约回收 + 指数退避。

**怎么做**：

1. **任务类型**：`task_type = 'DOC_GENERATE'`，`biz_no = document.id`。
2. **投递时机**：`POST /app/documents/{id}/generate` 里**只做三件事**：校验表单 → 把 `document.status` 置 `draft` → 插一条 `task_queue` 记录，然后**立即返回 `taskId`**。
   - 理由：后端代码规范 §9.2 第 1 条 + 「事务内禁止远程调用」（LibreOffice 是外部进程，可能 5 秒）。生成绝不能同步做。
3. **消费端**：照段 2 的消费模式（CAS 领取 → 租约 → 指数退避 → `retry_count >= 5` 进死信）。**本段不重写消费框架**，只注册 handler。
4. **handler 做什么**：读 `document` + `doc_template` → C2 渲染 Word → C3 转 PDF → 上传 OSS → 更新 `document.status='generated'` 与三个 `*_oss_key`。**任何一步失败 → `status='failed'` + `task_queue.last_error` 写原因**。
5. **轮询接口**：`GET /app/documents/{id}` 返回 `status`，App 端用 3 秒间隔轮询直到 `generated`/`failed`（不用 WebSocket，文书生成是低频操作）。

**前置**：段 2 的 `task_queue` 与消费端在跑（§二 第 3 项）。

**产出物**：
- `services/faze-api/src/main/java/com/faze/modules/document/task/DocGenerateHandler.java`
- `docs/acceptance/seg4/A4-task.md`（插一条任务 → 消费日志 → 状态变化的 SQL）

**自查**：
- [ ] 投递后接口**立即**返回（用 `curl -w %{time_total}` 量，< 300ms）
- [ ] 消费端能在 5 秒内捞到任务并执行
- [ ] 人为让 handler 抛异常 → `retry_count` 递增，`next_retry_at` 按 2^n 分钟退避
- [ ] `retry_count` 到 5 → 进死信，`4-A8` 可查
- [ ] 同一 `document.id` 连投 3 次 → 不会生成出 3 份互相覆盖的文件（幂等：用 `biz_no` 去重，或后写覆盖前写且 key 带时间戳）

**常见失败**：
1. 在 Controller 里同步调 LibreOffice —— 接口超时，且事务被外部进程拖死。
2. 消费时不加 `lock_expire_at` —— 进程崩溃后任务永久卡住。
3. 幂等没做 —— 用户连点两次「生成」，两次任务都跑，第二个把第一个的 `*_oss_key` 覆盖，`file_record` 里留孤儿。

---

### B 组：模板（3 个任务）

#### B1 · PC A-18 文书模板管理页

**做什么**：运营在 RuoYi 后台维护模板：列表、新建、编辑、启停、上传 `.docx`。

**怎么做**：

1. **页面位置**（`AGENTS.md` §3.3）：`pc/ruoyi-ui/src/views/biz/document/template/index.vue`，API 在 `src/api/biz/document/template.js`。
2. **5 个接口**（技术路线 L913–915 的 3 个 + 本段补 2 个）：

   | # | 方法与路径 | 用途 | 来源 |
   |---|---|---|---|
   | 1 | `GET /admin/doc-templates?type=&status=&lang=` | 列表（分页用 PC 的 `rows` 场景） | 技术路线 L913 |
   | 2 | `GET /admin/doc-templates/{id}` | 详情（含 `field_schema`） | **本段补** |
   | 3 | `POST /admin/doc-templates` | 新建 | 技术路线 L914 |
   | 4 | `PUT /admin/doc-templates/{id}` | 保存 / 启停 → **每次保存 `version+1`**，生成新 `oss_key` | 技术路线 L915 |
   | 5 | `POST /admin/doc-templates/{id}/upload` | 上传 `.docx`（服务端收文件 → 私有 OSS → 写 `oss_key`） | **本段补** |

3. **按设计稿 `design/admin/17-doc-template.html` 做，但有 3 处不实现**（照段 3 的先例：设计稿有、功能不做，要明说）：
   - ❌ **「适用法条」列** —— 本段不做文书法条引用（§七）。该列删掉，不留空列。
   - ❌ **「导入模板」「批量导出模板」两个按钮** —— 批量导入/导出是运营效率功能，不是本段出口要的。留图标但禁用 + tooltip「暂未开放」。
   - ✅ 「新建模板」「编辑」「启停」「查询」「重置」照做。

4. **不修改 RuoYi 框架层**：新增菜单走 RuoYi 的菜单配置（`sys_menu` 一条记录），不改 `src/layout`、`src/router`。

**前置**：段 0 的 RuoYi 可登录（§二 第 7 项）；A1 建表完成。

**产出物**：
- `pc/ruoyi-ui/src/views/biz/document/template/index.vue` + `edit.vue`
- `pc/ruoyi-ui/src/api/biz/document/template.js`
- 后端 `modules/document/controller/AdminDocTemplateController.java` + `service` + `mapper`
- `docs/acceptance/seg4/B1-a18.md`（列表截图 + 新建 → 上传 → 启用 → App 可见的截图）

**自查**：
- [ ] 能新建模板、上传 `.docx`、填字段定义、启用
- [ ] 启用后**不重启服务**，App 端 `GET /app/doc-templates` 立刻能看到
- [ ] 保存一次 → `version` 从 1 变 2，旧 `oss_key` 的对象**仍在 OSS 上**（不删）
- [ ] 启停能切 `active` ↔ `inactive`，`inactive` 后 App 列表看不到
- [ ] 没有改任何 `src/layout` / `src/router` / `src/store` 下的框架文件（`git status` 证明）
- [ ] 写接口都标了 `@OperationLog`（审计规范）

**常见失败**：
1. 改 RuoYi 的框架文件让菜单出现 —— 违反 `AGENTS.md` §3.3。要走 `sys_menu`。
2. 保存模板时**覆盖**同一个 `oss_key` —— 历史文书就复现不出来了（`4-A4` 直接失败）。
3. 忘记分页三场景里的 PC 管理端是 `rows` 不是 `list`（接口规范）。

---

#### B2 · `field_schema` 字段定义规范

**做什么**：定死 `doc_template.field_schema` 的 JSON 结构，让「App 端动态渲染表单」与「poi-tl 替换」两边都只认这一份。

**怎么做**：

1. **schema 结构**（JSON Schema 风格的简化版）：

   ```json
   {
     "version": 1,
     "fields": [
       {
         "key": "plaintiff_name",
         "label": "原告姓名",
         "type": "text",
         "required": true,
         "maxLength": 50,
         "placeholder": "请输入原告姓名",
         "rules": [{ "rule": "notBlank", "message": "原告姓名不能为空" }]
       },
       {
         "key": "plaintiffs",
         "label": "原告信息",
         "type": "array",
         "required": true,
         "minItems": 1,
         "maxItems": 5,
         "itemSchema": { "fields": [ ... ] }
       }
     ]
   }
   ```

2. **`type` 只允许 6 种**：`text` / `textarea` / `number` / `date` / `select` / `array`。**不许扩展**（每个新类型都要在 App 端加渲染分支，成本不是零）。

3. **与 poi-tl 的对应**：`{{key}}` 直接映射 `fields[].key`。**`array` 类型用 poi-tl 的 `{{?list}}...{{/list}}` 区块**渲染多行（多原告/被告）。

4. **校验三层**：
   - App 端（即时提示）
   - 服务端 `POST /app/documents`（落草稿前）与 `POST .../generate`（生成前）**各校验一次**
   - poi-tl 渲染时若发现模板里有 `{{xxx}}` 但 `form_data` 没有 `xxx` → **必须报错**，不许渲染成空白（这正是 `4-A8` 的失败场景之一）

5. **字段定义校验器**：`FieldSchemaValidator` 同时校验「schema 本身合法」与「form_data 符合 schema」。两个方向都要测。

**前置**：B1 的页面能填 schema。

**产出物**：
- `services/faze-api/src/main/java/com/faze/modules/document/dto/FieldSchema.java`（含 Jackson 反序列化）
- `FieldSchemaValidator.java` + 单测（合法 / 缺必填 / 类型错 / array 超界 / 多余字段）
- `docs/standards/` 下不需要新文档——schema 定义写进本文档 §B2 即可

**自查**：
- [ ] 6 种 type 各有一份示例 schema，都能被解析
- [ ] 给一个 schema 里没有的 `form_data` 字段 → 被拒绝（或明确忽略并记 warning，二选一，选前者）
- [ ] array 超过 `maxItems` → 明确报错，不是静默截断
- [ ] 模板里写 `{{not_in_schema}}` → 生成期报错（对应 `4-A8`）

**常见失败**：
1. schema 里 `key` 用了中文或空格 → poi-tl 的 `{{}}` 占位符匹配不上。
2. 前后端各写一套校验规则 → 两边不一致时表现为「App 说能交，服务端拒」。
3. `array` 用逗号分隔拼字符串传给 poi-tl → 多原告变成一段文本，不是多段。

---

#### B3 · 5 份中文模板 `.docx` 的产出与验收

**做什么**：5 类模板各一份真实可用的 `.docx`，字段占位符与 B2 的 schema 一一对应，版式符合 A4 规范。

**怎么做**：

1. **5 份清单**（导航树 L304 + 负责人拍板「五类全做」）：

   | # | 模板 | `code` | `category` | 关键字段（至少） |
   |---|---|---|---|---|
   | 1 | 民事起诉状 | `LAWSUIT-001` | 诉讼文书 | 原告（多）、被告（多）、诉讼请求、事实与理由、受理法院、具状日期 |
   | 2 | 证据清单 | `EVIDENCE-001` | 诉讼文书 | 案由、证据条目（多：名称/来源/证明目的）、提交人、日期 |
   | 3 | 劳动合同 | `LABOR-001` | 劳动文书 | 甲方、乙方、岗位、期限、薪资、工作地点、签订日期 |
   | 4 | 跨境电商合同 | `CROSSBORDER-001` | 跨境电商 | 买方、卖方、商品、数量、单价、总价、交货方式、争议解决 |
   | 5 | 借条欠条 | `IOU-001` | 合同文书 | 出借人、借款人、金额（大写/小写）、利率、还款日期、担保 |

2. **版式必须按技术路线 §10.5.1**：A4（595×842pt），上 72 / 下 72 / 左 90 / 右 72 pt，正文仿宋 三号 16pt、行距 28pt。**装订线在左（左边距更大）**。

3. **占位符写法**：Word 里写字面量 `{{plaintiff_name}}`。poi-tl 只替换文本，**不改变段落与 run 属性**——所以 `w:bidi`/`w:rtl` 本段不需要（只中文），但**字体必须显式设为「仿宋」而不是「宋体」默认**，否则 PDF 里是宋体。

4. **内容来源（这是本段最大的外部依赖）**：
   - ✅ **正路**：由运营/法务按真实诉讼文书改写，技术侧只要求「占位符与 schema 对齐」
   - ⚠️ **退路**：技术侧按公开格式（GB/T 9704-2012 参考）写一版「格式正确但内容示意」的模板，先跑通链路；**收口前必须换成真模板**
   - 无论哪条路，**真实起诉状样例**（§二 第 9 项）都要有，否则版式无从对齐

**前置**：B2 的 schema 定稿；§二 第 8/9 项。

**产出物**：
- `assets/doc-templates/` 下 5 个 `.docx`（**这是源文件，进仓库**；运行时以 OSS 上的为准）
- 每份模板的字段对照表（模板里每个 `{{key}}` ↔ schema 里的 `field key`）
- `docs/acceptance/seg4/B3-templates.md`（5 份模板的首页截图 + 字段对照表）

**自查**：
- [ ] 5 份模板的 `{{}}` 占位符集合，与对应 schema 的 `fields[].key` 集合**完全相等**（多一个或少一个都不行）
- [ ] 用一套示例 `form_data` 能渲染出无残留 `{{}}` 的文书
- [ ] PDF 打印出来，A4 四边边距与规范一致（用尺量或 PDF 工具量）
- [ ] 5 份都传进 `doc_template` 且 `status='active'`

**常见失败**：
1. Word 里把 `{{key}}` 拆成多个 run（Word 编辑时容易发生）→ poi-tl 匹配不到。**防**：在 Word 里一次性输入，或者 poi-tl 用 `GreedyTemplate` 模式。
2. 模板用了「宋体」默认，PDF 出来不是仿宋。
3. 占位符拼错（`{{plaintiffName}}` vs schema 的 `plaintiff_name`）→ 渲染成空白，且不报错（除非按 B2 第 4 条做校验）。

---

### C 组：生成内核（4 个任务）

#### C1 · 文书草稿与表单接口

**做什么**：`POST /app/documents` 存草稿、`GET /app/documents/{id}` 取回、`DELETE /app/documents/{id}` 删草稿。

**怎么做**：

1. **接口**：

   | # | 方法与路径 | 用途 |
   |---|---|---|
   | 1 | `GET /app/doc-templates` | 模板列表（只返回 `status='active'`） |
   | 2 | `POST /app/documents` | 创建/保存草稿（`form_data`） |
   | 3 | `GET /app/documents/{id}` | 详情（含 `form_data`、`status`、三个 `*_oss_key` 的签名 URL） |
   | 4 | `DELETE /app/documents/{id}` | 删草稿（软删；`generated` 的也能删，走软删） |

2. **归属校验**：所有 `{id}` 接口必须 `WHERE user_id = currentUser.id`，且**越权返回 403 而不是 404**（接口规范 §九 要求区分「不存在」与「不属于你」——本段按 `406004 error.document.forbidden` 走 403）。

3. **草稿自动保存**：App 端每 5 秒或失焦时调 `POST /app/documents` 更新同一 `id`。**草稿不生成文件**，只有 `form_data`。

4. **分页**：`GET /app/documents` 我的列表走 C 端 `list` 场景（`list` + `total`）。

**前置**：A1 建表；段 1 的 JWT。

**产出物**：
- `modules/document/controller/AppDocumentController.java` + `service` + `mapper`
- `docs/acceptance/seg4/C1-crud.md`（4 个接口的 curl 输出 + 越权 403 的输出）

**自查**：
- [ ] 创建草稿 → 再保存 → 是同一条记录（不是新增）
- [ ] 用 A 的 token 访问 B 的文书 → **403**，错误码 `406004`
- [ ] 删草稿后列表看不到，但库里 `deleted_at` 有值（软删）
- [ ] 模板 `inactive` 后 `GET /app/doc-templates` 不返回它
- [ ] 所有返回字段的 ID 是**字符串**（接口规范：Long 一律序列化为 String）

**常见失败**：
1. ID 返回数字 → 前端 JS 精度丢失（接口规范硬条款）。
2. 越权返回 404 → 前端无法区分「删掉了」和「没权限」。
3. 草稿保存没做幂等 → 用户快速编辑产生多条草稿。

---

#### C2 · poi-tl 渲染 Word

**做什么**：`form_data` + 模板 `.docx` → 填充后的 `.docx`。

**怎么做**：

1. **依赖**：`com.deepoove:poi-tl:1.12.2`（技术路线 §2.3 L217）。要登记进 `docs/architecture/依赖清单.md`（`AGENTS.md` §4 第 12 条）。

2. **渲染逻辑**：

   ```java
   XWPFTemplate template = XWPFTemplate.compile(templateFile);
   // 数据准备：把 array 类型的字段转成 List<Map>，供 {{?list}} 区块使用
   template.render(dataModel);
   template.writeToFile(outDocx);
   ```

3. **占位符残留检测（必须做，`4-A8` 靠它）**：渲染完成后，**再读一遍生成的 `.docx`**，检查是否还残留 `{{` 或 `}}`。有残留 → 抛 `406002 form-invalid` 或新错误码，**不许当成功**。

4. **字段缺失检测**：渲染前，从模板里抽出所有 `{{key}}` 与 schema 对比；`form_data` 缺任何一个必填 → 拒绝生成。

5. **文件名**：`{documentId}_{timestamp}.docx`，避免并发覆盖。

**前置**：A1 表；B2 schema；B3 模板。

**产出物**：
- `infra/pdf/WordRenderer.java`
- 单测（正常 / 缺字段 / 占位符残留 / array 多行）
- `docs/acceptance/seg4/C2-word.md`（一份真实生成的 .docx + 打开截图）

**自查**：
- [ ] 生成出的 `.docx` 用 Word/WPS 打开正常，**无残留 `{{}}`**
- [ ] 多原告（array）渲染成多段，不是一坨
- [ ] 故意删掉一个必填字段 → 报错，且**不产出文件**
- [ ] 模板里写一个 schema 没有的 `{{xxx}}` → 报错（不是渲染成空白）

**常见失败**：
1. 只 `render` 不检测残留 → `4-A8` 的「模板引用不存在的字段」场景会静默成功。
2. `array` 的数据结构给错（poi-tl 要 `List<Map<String,Object>>`，给 `List<String>` 直接抛异常或渲染空）。
3. 渲染完不关流 → 文件句柄泄漏，Windows 上文件删不掉（影响 `4-A8` 的临时文件清理）。

---

#### C3 · LibreOffice 转 PDF 与产物上传

**做什么**：把 C2 的 `.docx` 交给 A2 的 `DocumentConverter` 转 PDF，两个产物都传 OSS，更新 `document`。

**怎么做**：

1. 调 `DocumentConverter.convert(docx) → pdf`
2. 上传 `.docx` 与 `.pdf` 到私有 OSS，登记 `file_record`
3. 更新 `document`：`status='generated'`、`word_oss_key`、`pdf_oss_key`、`form_snapshot`（此时冻结）
4. **`form_snapshot` 必须在生成成功的那一刻写入**，之后 `form_data` 再怎么改都不动它——这是 `4-A4`（模板热更新不改历史）的另一半

**前置**：A2、A3、C2。

**产出物**：
- `modules/document/task/DocGenerateHandler.java` 的 PDF 分支补全
- `docs/acceptance/seg4/C3-pdf.md`（PDF 首页截图 + 字体嵌入检查输出）

**自查**：
- [ ] `.docx` 与 `.pdf` 的**文字内容逐字一致**（`4-A3` 判据：抽 10 个字段值比对）
- [ ] PDF 的字体全部嵌入（A2 的检查器报 0 个未嵌入）
- [ ] `.docx` 与 `.pdf` 都登记进 `file_record`
- [ ] `form_snapshot` 写入后，再改 `form_data` 不影响已生成的文件

**常见失败**：
1. 只传 PDF 不传 Word → `4-A1`（下载 Word）失败。
2. `form_snapshot` 在生成前就写 → 后面用户改表单，快照和实际文书对不上。

---

#### C4 · 失败路径与错误码

**做什么**：把三种典型失败的「状态 / 错误码 / 清理」定死，让 `4-A8` 可验。

**怎么做**：

| 失败场景 | 触发方式 | `document.status` | 错误码 | 清理动作 |
|---|---|---|---|---|
| 模板引用了不存在的字段 | 模板里有 schema 没有的 `{{xxx}}` | `failed` | `406002` | 删临时 `.docx` |
| LibreOffice 转换失败/超时 | 给一个损坏的 `.docx` | `failed` | `506002` | 删临时 `.docx` 与 profile |
| OSS 上传超时 | 断网或改桶策略 | `failed` | `506001` | 删本地临时文件；**已上传的部分不回滚但要登记** |
| 模板文件丢失（OSS 上没了） | 手动删 OSS 对象 | `failed` | `406001` | 无产物 |

1. **`document.status='failed'` 时，三个 `*_oss_key` 必须为 NULL**（不许指向半个文件）。
2. **`task_queue.last_error` 要写清原因**（人能看懂，不写堆栈），并进死信可查。
3. **失败必须对用户可见**：App 端 `GET /app/documents/{id}` 返回 `status='failed'` + `errorMsg`，预览页显示错误态（不是白屏）。

**前置**：C1–C3。

**产出物**：
- `modules/document/exception/DocumentExceptionHandler.java`（或复用全局异常）
- 错误码已在《错误码规范》§3.6 定义（`406001`/`406002`/`406003`/`406004`/`506001`/`506002`/`506003`），**本段不新增**
- `docs/acceptance/seg4/C4-failure.md`（4 个场景的 SQL + App 截图 + 临时目录清理证据）

**自查**：
- [ ] 4 个场景都能复现，`status` 都是 `failed`
- [ ] 每个场景的 `*_oss_key` 都是 NULL
- [ ] App 能看到错误提示，不是空白页
- [ ] 生成失败后 `$env:TEMP` 下无残留 `.docx` / `lo-profile-*`
- [ ] 死信能在 `task_queue` 查到（`status='dead'` 或等价状态，按段 2 的定义）

**常见失败**：
1. 失败时写 `status='generated'` 但 key 为空 —— 直接判 `4-A8` 不过。
2. 错误信息直接把 `e.getMessage()` 返回客户端 —— 后端代码规范明令禁止。
3. 只删了 `.docx` 忘了删 `lo-profile-*` —— 磁盘慢慢被吃满。

---

### D 组：签名笔迹（3 个任务）

#### D1 · 签名图库（28-doc-seal 页的服务端）

**做什么**：用户存多个手写签名图，用于后续盖章时选择。

**怎么做**：

1. **3 个接口**：

   | # | 方法与路径 | 用途 |
   |---|---|---|
   | 1 | `GET /app/signatures` | 签名库列表（`document_id IS NULL`） |
   | 2 | `POST /app/signatures` | 新增（传 `image_oss_key`，由上传通道直传后回填） |
   | 3 | `DELETE /app/signatures/{id}` | 删除（软删；已被文书引用的**不允许删**，返回明确错误） |

2. **图片规格**：PNG 透明底，建议 600×300 px 以内；上传后服务端不缩放（缩放会损失笔迹细节）。

3. **数量限制**：每用户最多 **5 个**签名（防止有人拿它当图床）。超了返回明确提示。

**前置**：A1 建表；A3 上传通道。

**产出物**：
- `modules/document/controller/AppSignatureController.java` + service
- `docs/acceptance/seg4/D1-signature.md`（列表 / 新增 / 删除 / 超限 的 curl 输出）

**自查**：
- [ ] 新增后列表能看到，`document_id` 为 NULL
- [ ] 删掉一个**已用在某文书上**的签名 → 被拒绝（不能因为删签名让历史文书无法复现）
- [ ] 第 6 个 → 被拒绝，提示「最多 5 个」
- [ ] 签名图通过 60 秒签名 URL 取，不用公开 URL

**常见失败**：
1. 列表查询没带 `document_id IS NULL` → 把「盖在某文书上的签名记录」也当签名库列出来。
2. 允许删被引用的签名 → `signature.image_oss_key` 悬空。

---

#### D2 · PDFBox 叠图与坐标换算

**做什么**：把签名 PNG 叠到 PDF 指定页的指定位置。

**怎么做**：

1. **依赖**：`org.apache.pdfbox:pdfbox:2.0.30`（技术路线写 2.0.x）。**不用 3.x**——`pdmodel` API 有变，本段不冒进。

2. **坐标模型**（技术路线 §10.4）：存 `(page_no, pos_x_pt, pos_y_pt, width_pt, height_pt, base_page_w_pt, base_page_h_pt)`。
   - 单位 pt，A4 = 595×842
   - **PDF 坐标系原点在左下角，屏幕坐标系原点在左上角** → 换算必须做：`pdfY = base_page_h_pt - (pos_y_pt + height_pt)`
   - **这是本段第二容易写错的地方**（第一是 A2 的 profile）

3. **缩放校验**：叠加时比较 `document` 实际页面尺寸与 `base_page_w_pt`/`base_page_h_pt`，不一致则按比例缩放坐标（防止模板换了纸张大小后签名跑位）。

4. **叠加实现**：

   ```java
   PDPage page = doc.getPage(pageNo - 1);
   PDImageXObject img = PDImageXObject.createFromFile(pngPath, doc);
   try (PDPageContentStream cs = new PDPageContentStream(doc, page,
           PDPageContentStream.AppendMode.APPEND, true, true)) {
       cs.drawImage(img, posX, posY, width, height);
   }
   ```

5. **产出去向**：新文件 `signed_pdf_oss_key`，**不覆盖原 `pdf_oss_key`**（原文件要保留，用户可能想重签）。

**前置**：C3（有 PDF）、D1（有签名图）。

**产出物**：
- `infra/pdf/SignatureStamper.java`
- 单测（坐标换算四种边界：左上/右下/跨页/页面尺寸不同）
- `docs/acceptance/seg4/D2-stamp.md`（叠章前后 PDF 的对比截图）

**自查**：
- [ ] 签名出现在预期位置（偏差见 `4-A5`，容差待拍板）
- [ ] 页面尺寸与 `base_page_*` 不同时，坐标按比例缩放
- [ ] 原 PDF 未被修改（`pdf_oss_key` 指向的对象 hash 不变）
- [ ] 签名为 PNG 透明底时，背景不被遮挡
- [ ] 多页文书中指定第 3 页，签名出现在第 3 页

**常见失败**：
1. **Y 轴没换算** → 签名跑到页面顶部（最经典）。
2. 用 `AppendMode.OVERWRITE` → 原内容被吃掉。
3. 不校验页面尺寸 → 换模板后签名跑到纸外。

---

#### D3 · 文书溯源元数据（替代原 4-A6 的 AIGC 隐式标识）

**做什么**：往 PDF 里写**真实**的溯源元数据，明确**不是 AIGC 标识**。

**怎么做**：

1. **写什么**（DocumentInformation + 自定义 XMP）：

   ```text
   Faze-Document-Id:     {document.id}
   Faze-Template-Code:   {doc_template.code}
   Faze-Template-Version:{doc_template.version}
   Faze-Generated-At:    {ISO8601 带时区}
   Faze-Generator:       法泽文书中心 v1.0
   ```

2. **明确不写**：`Faze-Content-Type: AIGenerated`、`Faze-Content-Id`（那是 AI 内容的字段，见 §零.3 第 9 项）。

3. **同时写标准字段**：`Title`（文书标题）、`Author`（`法泽`）、`Producer`（`LibreOffice 7.x`，LibreOffice 自动写）、`CreationDate`。

4. **签名的哈希**（技术路线 §10.4）：对**盖签前**的规范化内容流算 SHA-256，写入 `signature.document_hash` 与 PDF 元数据。

**前置**：D2。

**产出物**：
- `infra/pdf/PdfMetadataWriter.java`
- `docs/acceptance/seg4/D3-meta.md`（`pdfinfo` 或 PDFBox 读出元数据的完整输出）

**自查**：
- [ ] 5 个字段都能被 PDF 工具读出（用 `pdfinfo` 或 PDFBox 读一遍贴输出）
- [ ] **没有** `AIGenerated` 字样（明确验一次，防止有人照旧文档加回去）
- [ ] `Faze-Template-Version` 与实际用的模板版本一致
- [ ] `document_hash` 在叠签后能被重新计算并比对成功

**常见失败**：
1. 照技术路线 §12.3.2 抄，把 `AIGenerated` 写进去 → 虚假标识（§零.3 第 9 项）。
2. 元数据写了但没落库 → 无法从库里反查。
3. 时间用了本地时区但没标 → 换机器读出来差 8 小时。

---
### E 组：APP 六页（6 个任务）

> **本组的共同约束（每个任务都要满足，不再重复）**：
> 1. 页面实现在 `apps/mobile-app/features/document/pages/<Name>Page.tsx`；路由壳在 `apps/mobile-app/app/doc/<name>.tsx`，内容**只有一行** `export { default } from '@/features/document/pages/XxxPage'`（`AGENTS.md` §3.1）。
> 2. **界面文案四语言**走 `t()`，key 三段式 `<document>.<page>.<element>`（i18n 规范 §4）。文书**内容**只做中文，但**界面**必须 `zh-Hans` + `ug-Arab-CN` 两套齐全（缺则登记 `resources/.i18n-pending.json`）。
> 3. **四态齐全**：正常 / 加载 / 空 / 错误。空态与错误态都要有文案，不许白屏。
> 4. 视觉数值全部来自 `tokens.*`，禁字面量（ESLint 强制）。
> 5. 每个页面产出 `testID` 清单（段 3 先例：`3-A5` 用）。
> 6. `npm run lint`（`tsc --noEmit` + `eslint`）零告警。

#### E1 · 文书模板列表页（设计稿 `23-doc-templates.html`）

**做什么**：分类筛选 + 模板卡片网格，点卡片进对应表单页。

**怎么做**：
1. 数据源 `GET /app/doc-templates`（只返回 `active`）。按 `category` 分组：诉讼文书 / 合同文书 / 劳动文书 / 跨境电商（导航树 L303）。
2. 卡片显示：模板名、分类、一句话说明、字段数（`field_schema.fields.length`）。
3. 点卡片的路由分流：`code` 以 `LAWSUIT-` 开头 → `/doc/lawsuit?templateId=`；其余 → `/doc/form?templateId=`。
4. **不实现设计稿里的搜索框**（导航树 §模块6 没有搜索，全局搜索在段 5）——留着但不接功能要说明，本段直接删掉。

**产出物**：`features/document/pages/DocTemplatesPage.tsx`、`app/doc/templates.tsx`、i18n 两语言 key、testID 清单
**自查**：
- [ ] 4 个分类都能筛
- [ ] 空态：后端无 active 模板时显示「暂无可用模板」，不是空白
- [ ] 卡片点击进对应表单页，`templateId` 传对
- [ ] `ug-Arab-CN` 下卡片从右往左排（用 `start/end`，不用 `left/right`）

**常见失败**：把 `category` 写死在 App 里（应该来自后端数据）；用 `flexDirection: 'row-reverse'` 做 RTL（应该靠 RN 自动镜像 + `start/end`）。

---

#### E2 · 起诉状表单页（设计稿 `24-doc-lawsuit.html`）

**做什么**：按 `field_schema` 渲染表单（含多原告/多被告的 array），保存草稿，点「立即生成」进预览页。

**怎么做**：
1. **完全由 `field_schema` 驱动**，不许写死字段——同一个页面要能渲染任何 `LAWSUIT-*` 模板。
2. 导航树 L308–314 的要求：原告信息（多）、被告信息（多）、诉讼请求、事实与理由、字段格式校验（手机号/必填）、保存草稿。
3. **草稿自动保存**：改动后 5 秒防抖调 `POST /app/documents`；离开页面时立即存一次。
4. 「立即生成」→ `POST /app/documents/{id}/generate` → 跳 `/doc/preview?id=`。
5. **array 的交互**：「添加原告」「删除」按钮，`minItems`/`maxItems` 控制可增删。

**产出物**：`features/document/pages/DocLawsuitFormPage.tsx`、`features/document/components/DynamicForm.tsx`（与 E3 共用）、i18n、testID
**自查**：
- [ ] 加 3 个原告，生成后文书里有 3 段原告信息
- [ ] 手机号填错 → 即时提示，不阻塞其他字段
- [ ] 杀进程再进 → 草稿还在（自动保存生效）
- [ ] 必填未填时「立即生成」置灰并说明原因
- [ ] `ug-Arab-CN` 下表单标签在右、输入框文字从右起

**常见失败**：字段写死（换模板就崩）；草稿只在「保存」按钮时存（用户直接杀进程就丢）。

---

#### E3 · 其他文书表单页（设计稿 `25-doc-other.html`）

**做什么**：与 E2 同一套动态渲染，只是模板不同（证据清单 / 劳动合同 / 跨境电商合同 / 借条欠条）。

**怎么做**：
1. **复用 E2 的 `DynamicForm`**——本页几乎不写新 UI，只是路由参数不同。
2. 差异仅在：`array` 的条目结构不同（证据条目 vs 合同商品）、`textarea` 多、日期字段多。

**产出物**：`features/document/pages/DocFormPage.tsx`、`app/doc/form.tsx`
**自查**：
- [ ] 4 个非起诉状模板都能渲染并生成
- [ ] 日期字段出日期选择器，不是手输文本框
- [ ] 与 E2 共用组件，`git diff` 显示没有复制粘贴第二份表单代码

**常见失败**：复制 E2 的代码改一改（两份表单从此各自演化）。

---

#### E4 · 文书预览页（设计稿 `26-doc-preview.html`）— **行为已变更，重点看**

**做什么**：展示生成好的文书，**只读**；点任意段落 → 跳回表单页；导出 PDF/Word；盖章入口。

**怎么做**：

1. **展示方式**：**服务端把 PDF 渲染成 PNG（每页一张），App 显示图片列表**。
   - 为什么不用 `react-native-pdf`：需要原生模块 + 额外构建配置；而 PDFBox 渲染 PNG 在服务端一次就能做好，App 只显示 `<Image>`（Expo 原生支持）。
   - 服务端接口 `GET /app/documents/{id}/preview` 返回 `[{pageNo, imageUrl, widthPt, heightPt}]`（`imageUrl` 是 60 秒签名 URL）。渲染结果**缓存**在 OSS 上（`file_record.biz_type='document'`），不每次重算。
2. **只读**：**没有任何内联编辑**。设计稿 `26-doc-preview.html` 里的「点击任意段落可直接修改」文案**必须删掉**。
3. **点段落 → 跳回表单页**（负责人 2026-11-01 拍板的「理解 1」）：
   - 由于展示的是 PNG，无法知道点的是哪个段落。**因此跳回表单页时不做「段落定位」**，只跳回该文书的表单页（带 `documentId`），用户改完自己点「生成」。
   - **这条要在 UI 上写清楚**：表单页顶部提示「修改后需重新生成，预览才会更新」。
4. **底部三个按钮**：`导出`（进 F1 的导出面板）、`盖章`（进 D2 的叠章流程）、`返回表单修改`。
5. **四态**：`status='draft'` → 未生成态（提示「请先点生成」）；`generating`（轮询中）→ 加载态；`generated` → 正常；`failed` → 错误态 + 错误原因。

**产出物**：
- `features/document/pages/DocPreviewPage.tsx`、`app/doc/preview.tsx`
- 服务端 `GET /app/documents/{id}/preview` + `infra/pdf/PdfPageRenderer.java`（PDFBox `PDFRenderer` → PNG）
- i18n、testID
**自查**：
- [ ] 预览显示的是**真实 PDF 转的图**（不是 HTML 截图、不是设计稿图）
- [ ] 页面内**没有**任何可编辑的输入框（`grep` 一遍确认无 `TextInput`）
- [ ] 设计稿的「点击任意段落可直接修改」这句文案已删除
- [ ] 点「返回表单修改」→ 进表单页，改了字段再点生成 → 回到预览，内容**已更新**
- [ ] `failed` 状态显示错误原因，不是白屏
- [ ] 生成中显示进度（轮询间隔 3 秒，最多 60 秒后提示超时）

**常见失败**：
1. 把设计稿的「在线编辑」真的做出来 —— 违反拍板（§零.3 第 4 项）。
2. 预览用设计稿的静态 HTML 冒充 —— 那验的不是真 PDF。
3. 预览图不缓存，每次进页面重算 → 3 秒以上的等待。

---

#### E5 · 我的文书列表（设计稿 `27-doc-mine.html`）

**做什么**：草稿 / 已完成 分类、翻页、按类型筛选、进预览页。

**怎么做**：
1. `GET /app/documents?status=&docType=&page=&pageSize=`，C 端 `list` 场景。
2. 列表项：标题、类型、状态标签、生成时间、缩略图（`preview_oss_key` 第一页）。
3. **筛选条件**（`4-A7` 原文要求「能按类型筛选」）：状态（草稿/已完成/失败）+ 文书类型。
4. 下拉刷新 + 上拉加载更多。

**产出物**：`features/document/pages/DocMinePage.tsx`、`app/doc/mine.tsx`、i18n、testID
**自查**：
- [ ] 三类状态都能筛
- [ ] 翻页到第 2 页数据正确（不重复、不丢）
- [ ] 空态：一份都没有时显示「还没有文书」+ 去生成按钮
- [ ] 失败态：`status='failed'` 的项显示红色标签，点进去能看到原因

**常见失败**：分页用 `page+1` 但后端从 0 开始（或反之）；筛选条件改变时不重置到第 1 页。

---

#### E6 · 电子签章管理页（设计稿 `28-doc-seal.html`）

**做什么**：签名库列表、手写新签名、上传签名图片、删除。

**怎么做**：
1. **手写板**：`@shopify/react-native-skia` 画路径 + `react-native-gesture-handler` 收手势；清空、撤销、确认三个操作。
2. **导出 PNG**：`react-native-view-shot` 截手写区域 → 透明底 PNG → 走 `POST /app/upload/token` 直传 → `POST /app/signatures` 回填 `image_oss_key`。
3. **上传图片**：`expo-image-picker` 选相册图 → 同上流程（不做抠图，用户自己提供透明底或白底，白底在叠章时会被当不透明区域——**页面要提示这一点**）。
4. **列表**：`GET /app/signatures`，每个显示缩略图 + 创建时间 + 删除。
5. **数量上限提示**：到 5 个时「新增」按钮置灰并说明。

**产出物**：`features/document/pages/DocSealPage.tsx`、`features/document/components/SignaturePad.tsx`、`app/doc/seals.tsx`、i18n、testID
**自查**：
- [ ] 手写一笔能画出线（不是点），松手后路径保留
- [ ] 导出的 PNG 是透明底（拿到图后在文件管理器里看一眼）
- [ ] 第 6 个签名被拒，提示正确
- [ ] 删除已使用的签名被拒
- [ ] `ug-Arab-CN` 下绘制区域坐标不偏移（**笔记：canvas 坐标不随 RTL 自动镜像**，技术路线 §10.4）

**常见失败**：手写板导出的图带白色背景 → 叠到 PDF 上是一个白块盖住正文；RTL 下手势坐标偏移（要单独验）。

---

### F 组：后处理（2 个任务）

#### F1 · 下载 / 分享 / 存相册 / 打印（`4-A7` 的载体）

**做什么**：四种出口都要能用，且都基于**真实文件**。

**怎么做**：

| 功能 | 实现 | 注意 |
|---|---|---|
| 下载 PDF | `GET /app/documents/{id}/export?format=pdf` → 返 60 秒签名 URL → `expo-file-system` 下载到本地 | 下载完给「已保存到」提示 |
| 下载 Word | 同上 `format=docx` | 文件名用文书标题 |
| 分享 | `expo-sharing` 调系统面板，传本地文件 URI | 分享给律师走 IM 是段 5，本段只做系统分享 |
| 存相册 | **仅图片格式**；PDF 走分享面板 | 技术路线 §10.5 明确：PDF 不存相册 |
| 打印 | `expo-print` 调系统打印面板，传**本地 PDF** | 边距已在生成时处理好，不在客户端再算 |

1. **导出接口**：`GET /app/documents/{id}/export?format=pdf|docx`，返回 `{url, fileName, expiresIn: 60}`。
2. **下载留痕**：签名图下载要写 `operation_log`（技术路线 §10.4「下载留 `operation_log`」）；**文书下载不审计**（本段裁决，理由：文书是用户自己的，非敏感他人数据；若负责人要求，见 §十 第 3 项）。

**产出物**：`features/document/api/export.ts`、预览页的导出面板、`app/doc/` 下的下载逻辑
**自查**：
- [ ] 四项都能用（真机实测，每项截图）
- [ ] 下载的 PDF 能打开、内容与预览一致
- [ ] 下载的 `.docx` 能被 WPS/Word 打开
- [ ] 打印面板弹出的预览边距正确
- [ ] iOS 与 Android 都能分享（至少 Android 必验，iOS 若段 8 才做则挂账）

**常见失败**：分享的是 `https://` URL 而不是本地文件（接收方打不开）；打印传了 URL 而不是本地文件。

---

#### F2 · 重新生成与模板版本隔离

**做什么**：用户改表单后重新生成，**旧产物不丢**；模板改版后重新生成，历史文书**不受影响**。

**怎么做**：
1. `POST /app/documents/{id}/generate` **可重复调用**：每次生成产新的 `.docx`/`.pdf`（key 带时间戳），更新 `document` 的 `*_oss_key` 与 `template_version`，`form_snapshot` 刷新为**本次**的表单。
2. **旧产物不删**：`file_record` 里保留历史，`document` 只指向最新。若要查历史版本，靠 `created_at` 排序。
3. **模板改版的影响面**：
   - 已生成且**未重新生成**的文书：`template_version` 停留在旧版，**重下载仍是旧版**（因为 `pdf_oss_key` 指向旧文件）
   - 已生成且重新生成：`template_version` 更新为当前版
   - **这两种都要在列表里能区分**（列表显示 `template_version`）

**前置**：C3、B1。

**产出物**：C1 的 `generate` 接口补全、`docs/acceptance/seg4/F2-regen.md`
**自查**：
- [ ] 改表单 → 重新生成 → 预览更新，旧 PDF 仍在 OSS
- [ ] 运营改模板（`version` 1→2）→ 历史文书**重新下载仍是旧内容**
- [ ] 历史文书重新生成 → 用新版模板，`template_version=2`
- [ ] 连续生成 3 次，`file_record` 有 3 组产物，`document` 指向最后一组

**常见失败**：重新生成时覆盖同一个 `oss_key` → `4-A4` 直接失败；`template_version` 不更新 → 无法判断某份文书用的是哪版模板。

---

### G 组：收口（2 个任务）

#### G1 · 验收脚本 `deploy/verify-seg4.ps1`

**做什么**：把 8 条锚点能自动验的部分脚本化，不能自动的留人工判定入口。

**怎么做**：
1. 脚本骨架 `deploy/verify-seg4.ps1`，**先建出来跑出「8 条全部未通过」**，再逐条接实现。
2. 可自动验的：`4-A1`（生成后检查 docx 无 `{{}}`）、`4-A3`（docx 与 pdf 文字比对）、`4-A4`（改模板后重下的文件 hash 不变）、`4-A6`（读 PDF 元数据）、`4-A8`（失败后查库与临时目录）。
3. 必须人工的：`4-A2`（版式目视，但可脚本化「字体是否嵌入」）、`4-A5`（位置偏差要量）、`4-A7`（真机操作四项）。

**产出物**：`deploy/verify-seg4.ps1`
**自查**：
- [ ] 空跑时 8 条全报「未通过」
- [ ] 每条输出具体判据（不是只有 PASS/FAIL）

---

#### G2 · 证据归档与文档回写

**做什么**：`docs/acceptance/seg4/` 下的 8 条证据 + §九 的 7 项回写。

**怎么做**：见 §四每条锚点的「证据文件」与 §九的表格。

**产出物**：`docs/acceptance/seg4/4-A1.md` ~ `4-A8.md`
**自查**：
- [ ] 8 个文件都在
- [ ] 每个文件含：命令输出/SQL、截图、判定结论
- [ ] §九 7 项全部完成

---
## 四、验收锚点（`4-A1` ~ `4-A8` 逐条展开）

> **格式**：每条给出 ①总纲原文 ②怎么验 ③通过标准（二值）④证据文件 ⑤依赖任务 ⑥**最容易糊弄过去的地方**。
> 证据文件统一放 `docs/acceptance/seg4/`。
> **编号说明**：`4-A3` / `4-A4` 的**内容已替换**（原「维吾尔文连写」「藏文叠字」因只做中文而删除），编号不复用空号；`4-A6` 的口径已从「AIGC 隐式标识」改为「文书溯源元数据」。原委见 §开工前必读。

### `4-A1` Word 生成

- **总纲原文**：「选中文模板 → 填表 → 生成 → 下载 Word」→ 通过标准：「内容与填表一致，无占位符残留、无丢字段」。
- **怎么验**：
  1. App 端进文书模板列表 → 选「民事起诉状」。
  2. 填表：原告 3 人、被告 2 人、诉讼请求 2 条、事实与理由 1 段、受理法院「伊宁市人民法院」。
  3. 点「立即生成」→ 等状态变 `generated`。
  4. 预览页点「导出」→ 导出 Word → 下载到本地。
  5. **用 Word/WPS 打开**，逐字段核对：
     - 3 个原告都出现（不是 1 个）
     - 2 个被告都出现
     - 诉讼请求 2 条都在
     - 受理法院是「伊宁市人民法院」
  6. **全文搜索 `{{`**：必须 0 处。
  7. 查库：`SELECT status, word_oss_key, form_snapshot FROM document WHERE id=?` → `status='generated'`，`word_oss_key` 非空，`form_snapshot` 与提交的表单一致。
- **通过标准（二值）**：7 步全为真 → 通过；任一步为假 → 不通过。
- **证据文件**：`docs/acceptance/seg4/4-A1.md`（填表截图、下载的 `.docx` 原件、打开后的截图、`{{` 搜索结果为 0 的截图、SQL 输出）。
- **依赖任务**：C1、C2、C3、E4、F1、B3。
- **最容易糊弄的地方**：
  - **只看预览截图就判过**——预览是 PNG，看不到 Word 里是否残留 `{{`。必须打开 `.docx` 搜。
  - 只填 1 个原告 —— 那不验 array。**必须填多原告**，这是本锚点唯一能验出 `array` 渲染的手段。
  - 生成的 `.docx` 是**服务端缓存的旧文件**（同一 `id` 生成过两次，下载到的是第一次的）—— 用 `form_snapshot` 与文件内容比对。

### `4-A2` PDF 版式与字体嵌入

- **总纲原文**：「同一份文书转 PDF」→ 通过标准：「A4 边距正确、分页正确、页眉页脚不压正文」。
- **怎么验**：
  1. 拿 `4-A1` 的同一份文书，导出 PDF。
  2. **量边距**（用 PDF 工具，不靠目视）：`pdfinfo` 看页面尺寸；用 PDFBox 读首页文字 bounding box：
     ```powershell
     pdfinfo 文书.pdf        # 预期 Page size: 595.276 x 841.89 pts (A4)
     ```
     - 上边距 72pt ± 2pt、下 72pt ± 2pt、左 90pt ± 2pt、右 72pt ± 2pt
  3. **分页**：内容超过 1 页时，第 2 页的页眉/页脚不压在正文上；页码连续。
  4. **字体嵌入**：A2 的检查器输出「未嵌入字体数 = 0」。**这是本锚点的隐藏判据**——总纲原文没写，但「换台机器就变字形」等于版式不正确。
  5. 打印一份（真机 `expo-print`），量纸张边距。
- **通过标准（二值）**：页面尺寸 = A4 + 四边边距在容差内 + 分页正确 + **字体全部嵌入** → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A2.md`（`pdfinfo` 输出、PDFBox 量边距的输出、字体嵌入检查输出、打印件照片）。
- **依赖任务**：A2、C3。
- **最容易糊弄的地方**：
  - **只看截图说「版式没问题」**——截图看不出 72pt 还是 60pt。必须量。
  - 字体没嵌入但本机装了仿宋，看着正常 —— 换机器就变。这是本锚点最容易漏的一条。
  - 边距是 Word 模板里设的，但 LibreOffice 转换时改了页面尺寸 —— 要量 PDF 不看 docx。

### `4-A3` Word 与 PDF 内容一致（**新增，替代原「维吾尔文连写」**）

- **总纲原文**：无（原 `4-A3` 是「维吾尔文连写」，已因只做中文删除；本条是本段新增的替代锚点）。
- **为什么需要它**：文书有两个产物（`.docx` 与 `.pdf`），走两套引擎（poi-tl 填文本 / LibreOffice 渲染）。**两套引擎不一致是真实风险**——用户拿 Word 改完发现和 PDF 不一样，或者 PDF 缺了一段。原来没人验这件事。
- **怎么验**：
  1. 取 `4-A1` 的同一份文书，同时导出 `.docx` 与 `.pdf`。
  2. 从 `.docx` 里抽出全部文字（python-docx 或解压 `word/document.xml`）。
  3. 从 `.pdf` 里抽出全部文字（PDFBox `PDFTextStripper`）。
  4. **归一化后逐字比对**：去掉空白差异、去掉页眉页脚、统一标点。
  5. 抽 10 个关键字段值（原告 3 名、被告 2 名、法院名、2 条诉讼请求、日期），逐个在两边都能找到。
- **通过标准（二值）**：归一化后的文本**完全相等**（或差异仅为允许项：页码、页眉页脚）→ 通过。
- **证据文件**：`docs/acceptance/seg4/4-A3.md`（两份文本抽取结果、diff 输出、10 个字段的对照表）。
- **依赖任务**：C2、C3。
- **最容易糊弄的地方**：
  - 只比前 100 个字 —— 后面丢段看不出来。
  - PDF 抽文字时因字体嵌入问题抽出乱码，然后说「PDF 就是这样」—— 乱码本身就是问题。
  - 归一化时把差异全「抹平」了 —— 归一化规则要写进证据文件，可复核。

### `4-A4` 模板热更新不改历史文书（**新增，替代原「藏文叠字」**）

- **总纲原文**：无（原 `4-A4` 是「藏文叠字」，已删除；本条是本段新增）。
- **为什么需要它**：`doc_template.version` + 不可变 `oss_key` + `form_snapshot` 这三样就是为这件事设计的（技术路线 §5.6 L1461 原注「模板版本控制，V2.1 的 UNIQUE(code,lang) 会让覆盖更新破坏历史文书」）。**设计意图在文档里，但从来没人验过它真的работ。**
- **怎么验**：
  1. 用模板 v1 生成文书 A，下载 PDF，记 SHA-256 = `H1`。
  2. 运营在 A-18 改模板（把标题字号 16pt 改 18pt），保存 → `version` 变 2。
  3. **不改 A 的任何数据**，直接重新下载 A 的 PDF → 算 SHA-256 = `H2`。
  4. **断言 `H1 == H2`**（A 还是旧版）。
  5. 对 A 点「重新生成」→ 下载 → SHA-256 = `H3`，**`H3 != H1`**，且 `document.template_version = 2`。
  6. 查 OSS：v1 的模板 `.docx` 对象**仍然存在**（没被覆盖）。
- **通过标准（二值）**：`H1==H2` 且 `H3!=H1` 且 v1 模板对象存在 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A4.md`（3 个 hash、`document.template_version` 前后值、OSS 上两个模板对象的截图）。
- **依赖任务**：B1（版本递增）、F2（重新生成）、C3。
- **最容易糊弄的地方**：
  - 保存模板时**覆盖同一个 `oss_key`** —— 历史文书再也复现不出来，`H2 != H1`。这是本锚点要抓的第一件事。
  - 「重新生成」时只改文件不改 `template_version` —— `H3 != H1` 成立但版本号对不上，工序不闭环。
  - 拿 `form_snapshot` 当「历史文书」验 —— 那是表单数据，不是文书产物。

### `4-A5` 签名位置

- **总纲原文**：「叠手写签名后看落款处」→ 通过标准：「位置偏差在可接受范围（**建议 ≤ 4mm，具体由负责人定**）；签名不遮挡正文关键内容」。
- **⚠️ 容差未定**：总纲写「由负责人定」，负责人 2026-11-01 **未就这一条给值**。见 §十 第 1 项。**本锚点在容差确定前只能判「不遮挡正文」，不能判「偏差合格」。**
- **怎么验**：
  1. 生成文书 → 预览 → 「盖章」→ 从签名库选一个 → 拖到落款处 → 确认。
  2. 下载带签名的 PDF。
  3. **量**：签名图左边缘与「具状人：」文字左边缘的水平偏差、签名底边与落款日期的垂直间距。
  4. 单位换算：4mm ≈ 11.34pt（用户给的「≤4mm」是纸上距离）。
  5. 检查签名**不遮挡**诉讼请求、事实与理由、法院名。
- **通过标准（二值）**：
  - 可判部分：签名不遮挡正文关键内容 → 必须为真
  - 待定部分：偏差 ≤ 容差 → **容差定了才能判**
- **证据文件**：`docs/acceptance/seg4/4-A5.md`（带签名的 PDF、量的坐标值、放大截图）。
- **依赖任务**：D1、D2、E6。
- **最容易糊弄的地方**：
  - 签名坐标**写死**（不按 `base_page_*` 缩放）—— 当前模板对了，换模板就错。要让它换一份模板再验一次。
  - Y 轴没换算（PDF 原点在左下，屏幕在左上）—— 签名跑到页顶。
  - 截图缩得很小，看不出偏差 —— 要放大到 1:1 像素量。

### `4-A6` 文书溯源元数据（**口径已改，原为「AIGC 隐式标识」**）

- **总纲原文（原）**：「用 PDF 工具读元数据」→「含 `Faze-Content-Type` 等 5 个字段，值正确」。
- **本段口径（改）**：「含 5 个**溯源**字段，值正确；**不含任何 `AIGenerated` 标识**」。
- **为什么改**：文书是「用户填表 + 运营配模板 + poi-tl 文本替换」，**没有任何一步是模型生成的**。按《人工智能生成合成内容标识办法》给它打 `AIGenerated` 属于虚假标识。理由详见 §零.3 第 9 项。这**不是**「本段偷懒不打标识」，是「打错了比不打更糟」。
- **怎么验**：
  1. 下载任意一份生成的 PDF。
  2. 用 PDFBox 或 `pdfinfo` 读 `DocumentInformation` 与 XMP：
     ```powershell
     pdfinfo 文书.pdf
     # 或用 PDFBox 读 doc.getDocumentInformation().getCOSObject()
     ```
  3. 逐个核对 5 个字段：
     - `Faze-Document-Id` = `document.id`
     - `Faze-Template-Code` = `doc_template.code`
     - `Faze-Template-Version` = 生成时的版本（与 `document.template_version` 一致）
     - `Faze-Generated-At` = ISO8601 带时区，与 `document.created_at`/`updated_at` 对得上
     - `Faze-Generator` = `法泽文书中心 v1.0`
  4. **反向断言**：全文件搜 `AIGenerated` / `Faze-Content-Type` → **0 处**。
- **通过标准（二值）**：5 个字段都有且值对 + `AIGenerated` 为 0 处 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A6.md`（PDFBox 读出的元数据全量输出、5 个字段的对照表、`AIGenerated` 搜索为 0 的输出）。
- **依赖任务**：D3。
- **最容易糊弄的地方**：
  - **照旧文档抄回 `AIGenerated`** —— 技术路线 §12.3.2 还那么写着，不改就会犯。这条在 §零.3 第 9 项特意说了一遍。
  - 元数据写了但 `Faze-Template-Version` 是硬编码的 `1` —— 换模板版本就对不上。
  - `Faze-Generated-At` 用本地时间不标时区。

### `4-A7` 后处理链

- **总纲原文**：「我的文书列表 → 重新下载 / 分享 / 存相册 / 打印」→ 通过标准：「四项都可用；列表能翻页、能按类型筛选」。
- **怎么验**（真机，逐项截图）：
  1. 进「我的文书」→ 能看到刚生成的文书。
  2. **重新下载**：点下载 → 文件保存到本地 → 打开正常。
  3. **分享**：点分享 → 系统面板出现 → 选一个 App（如文件管理器）→ 文件能传出。
  4. **存相册**：对**图片格式**操作；对 PDF 应当走分享面板（技术路线 §10.5 明确 PDF 不存相册）→ 验「PDF 时该按钮不出现或提示走分享」。
  5. **打印**：点打印 → 系统打印面板 → 预览边距正确。
  6. **翻页**：文书 > 10 份时翻到第 2 页，数据不重复不丢。
  7. **按类型筛选**：选「诉讼文书」→ 只剩起诉状与证据清单。
- **通过标准（二值）**：1–7 全为真 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A7.md`（7 步的截图 + 第 2 页数据与第 1 页无交集的 SQL 或列表截图）。
- **依赖任务**：E5、F1。
- **最容易糊弄的地方**：
  - 「分享」分享的是 URL 不是文件 —— 接收方打不开，但本机看着「分享成功」。
  - 「打印」只弹了面板，没验预览边距。
  - 翻页只验了「有第 2 页」，没验数据不重复。

### `4-A8` 失败不留半成品

- **总纲原文**：「人为让生成失败（如模板引用不存在的字段）」→ 通过标准：「有明确错误提示；`document_instance` 不留『成功』记录；临时文件被清理」。
- **怎么验**（三个场景各做一次）：
  1. **模板字段缺失**：在模板 `.docx` 里写一个 `{{not_in_schema}}` → 上传 → 用户生成 → 必须失败。
  2. **LibreOffice 失败**：把 `.docx` 改成一个损坏文件（或让 `soffice` 路径指向假路径）→ 生成 → 必须失败。
  3. **OSS 不可用**：临时改桶策略或断网 → 生成 → 必须失败。
  每场景验五件事：
  - ① App 上看到**明确错误提示**（不是白屏、不是转圈到超时）
  - ② `SELECT status, word_oss_key, pdf_oss_key FROM document WHERE id=?` → `status='failed'`，两个 key **都是 NULL**
  - ③ `task_queue` 里该任务的 `last_error` 有可读原因
  - ④ `$env:TEMP` 下无残留 `.docx` / `lo-profile-*`（生成前后各列一次目录对比）
  - ⑤ 失败后再点「生成」，能重试成功（不允许一次失败后该文书永久卡死）
- **通过标准（二值）**：3 个场景 × 5 件事全为真 → 通过。
- **证据文件**：`docs/acceptance/seg4/4-A8.md`（3 个场景各 5 张证据，共 15 项）。
- **依赖任务**：C4、A2、A4。
- **最容易糊弄的地方**：
  - 失败写 `status='generated'` 只是文件为空 —— 查库就能抓。
  - 只验了 1 个场景 —— 总纲写的是「如模板引用不存在的字段」，是**举例**，三个场景都要验。
  - 临时文件清理没验（`$env:TEMP` 前后对比）—— 这条最容易漏，也是长期最容易出问题的一条（磁盘满）。
  - 失败后不能重试 —— 用户只能删了重建，体验差且是真实缺陷。

---

## 五、产出清单（交付时逐项勾）

### 5.1 数据库（4 张表）

| # | 表名 | 类别 | 字段数 | 任务 |
|---|---|---|---|---|
| 1 | `doc_template` | D 基础数据 | 13 | A1 |
| 2 | `document` | C 状态机 | 17 | A1 |
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
| 4 | `GET /app/documents/{id}` | 详情（含签名 URL） | C1 |
| 5 | `DELETE /app/documents/{id}` | 软删 | C1 |
| 6 | `POST /app/documents/{id}/generate` | 投递生成任务 | A4 |
| 7 | `GET /app/documents/{id}/preview` | 预览（PDF 转图，缓存） | E4 |
| 8 | `POST /app/documents/{id}/sign` | 提交签名坐标并叠章 | D2 |
| 9 | `GET /app/documents/{id}/export?format=pdf\|docx` | 导出（60 秒签名 URL） | F1 |
| 10 | `GET /app/signatures` | 签名库列表 | D1 |
| 11 | `POST /app/signatures` | 新增签名 | D1 |
| 12 | `DELETE /app/signatures/{id}` | 删除签名 | D1 |

**PC 侧（5 个）**

| # | 方法与路径 | 用途 | 任务 |
|---|---|---|---|
| 13 | `GET /admin/doc-templates` | 列表（`rows` 分页） | B1 |
| 14 | `GET /admin/doc-templates/{id}` | 详情 | B1 |
| 15 | `POST /admin/doc-templates` | 新建 | B1 |
| 16 | `PUT /admin/doc-templates/{id}` | 保存/启停（`version+1`） | B1 |
| 17 | `POST /admin/doc-templates/{id}/upload` | 上传 `.docx` | B1 |

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

### 5.5 5 份中文模板

| # | 模板 | `code` | 任务 |
|---|---|---|---|
| 1 | 民事起诉状 | `LAWSUIT-001` | B3 |
| 2 | 证据清单 | `EVIDENCE-001` | B3 |
| 3 | 劳动合同 | `LABOR-001` | B3 |
| 4 | 跨境电商合同 | `CROSSBORDER-001` | B3 |
| 5 | 借条欠条 | `IOU-001` | B3 |

### 5.6 新增依赖（必须登记《依赖清单》并写理由）

| 依赖 | 版本 | 用途 | 理由 |
|---|---|---|---|
| `com.deepoove:poi-tl` | 1.12.2 | Word 模板填充 | 技术路线 §2.3 已定；`{{}}` 占位符 + `{{?list}}` 区块是唯一轻量方案 |
| `org.apache.pdfbox:pdfbox` | 2.0.30 | 签名叠图、PDF 转 PNG、元数据、字体嵌入检查 | 技术路线 §2.3 定 2.0.x；不用 3.x（API 变更，本段不冒进） |
| LibreOffice | 7.x（系统安装） | `.docx` → PDF | 唯一开源且忠实的 `.docx` 渲染器；见 §二 第 5 项 |
| `expo-print` | SDK 54 对齐 | 打印 | `AGENTS.md` §4 第 12 条要求登记 |
| `expo-sharing` | 同上 | 分享 | 同上 |
| `expo-file-system` | 同上 | 下载 | 同上 |
| `expo-media-library` | 同上 | 存相册 | 同上 |
| `@shopify/react-native-skia` | 同上 | 手写签名板 | 技术路线 §2.3 已定 |
| `react-native-gesture-handler` | 同上 | 手势 | Expo 标准 |
| `react-native-reanimated` | 同上 | 手势动画 | gesture-handler 的配套 |
| `react-native-view-shot` | 同上 | 手写板导出 PNG | 比 skia 自带导出更稳 |

### 5.7 脚本、文档与证据

| 类型 | 路径 | 任务 |
|---|---|---|
| 验收脚本 | `deploy/verify-seg4.ps1` | G1 |
| 证据 | `docs/acceptance/seg4/`（8 条 + A1~A4 的过程证据） | G2 |
| 模板源文件 | `assets/doc-templates/*.docx`（5 份） | B3 |
| 回写 | §九 的 7 项 | G2 |

---
## 六、已知风险与出口

> 每条给：风险 → 会不会挡住出口 → 出口（怎么处理）。

| # | 风险 | 会挡住吗 | 出口 |
|---|---|---|---|
| **R1** | **LibreOffice 的版式与 Word 不完全一致**（同一份 `.docx`，Word 与 LibreOffice 渲染可能差几 pt，分页点可能不同） | **会**（`4-A2`/`4-A3` 直接判它） | ① 模板在 Word 里设计时**留足余量**（行距、段前后）；② `4-A2` 量的是**边距与分页**，不是「像素级与 Word 一致」——容差写进验收；③ 若某模板必现分页错，改模板而不是改 LibreOffice |
| **R2** | **5 份模板内容没人写**（技术侧能给格式，给不了真实法律文本） | **会**（§一.1 出口第 2 条） | ① 正路：运营/法务提供；② 退路：技术侧按 GB/T 9704-2012 写示意版先跑通链路，**收口前必须替换**；③ 两者都没有 → 段 4 不能收口，挂账到负责人 |
| **R3** | `@shopify/react-native-skia` 在 dev-client 里构建失败 | **会**（`4-A5`/E6 要它） | 退路：`react-native-svg` + `react-native-view-shot` 画路径并导出 PNG（不需要原生 skia）。**退路要在 E6 留好接口，不要写死在 skia 上** |
| **R4** | 预览转图慢（每页渲染一次 PDF） | 不会，但体验差 | ① 渲染结果缓存到 OSS，二次进页面直接取 URL；② 只渲染前 5 页，其余「加载更多」（文书通常 1–3 页） |
| **R5** | `signature` 表一表两用（签名库 + 盖章记录），查询容易写错 | 不会，但**会出数据错误** | 所有查询显式带 `document_id IS NULL` / `IS NOT NULL`；在 Mapper 层用两个方法名区分（`listSealLibrary` / `listStampsOnDocument`），不给「按 user_id 查全部」的方法 |
| **R6** | 模板热更新时**覆盖 OSS 对象**（同一 key） | **会**（`4-A4` 失败） | `oss_key` 每次 `version+1` 生成**新 key**（含版本号），旧的永不删；OSS 桶开「版本控制」作第二道保险 |
| **R7** | **`4-A5` 的容差未定**（总纲写「由负责人定」） | **会**（这条锚点判不了） | 见 §十 第 1 项。容差定之前，`4-A5` 只能判「不遮挡正文」，**不能判过** |
| **R8** | 中文文件名在 LibreOffice 转换时被转义或乱码 | 不会，但会连累 A2 | 输入文件名一律用 `{documentId}_{timestamp}.docx`（纯 ASCII），**不用文书标题做文件名**；下载时再改回中文名 |
| **R9** | Windows 上文件句柄未关，临时文件删不掉 | 不会，但 `4-A8` 清理验不过 | `XWPFTemplate` / `PDDocument` / `OutputStream` 全部 try-with-resources；`finally` 里 `File.delete()` 失败重试 3 次 |

---

## 七、明确不做（本段不许碰，碰了就是超范围）

| # | 不做 | 依据 |
|---|---|---|
| 1 | **少数民族文字文书**（维吾尔文/藏文/哈萨克文） | 负责人 2026-11-01 拍板「只做中文」。**注意：这不是「界面也只做中文」** |
| 2 | **文书在线编辑**（段落内联改文字、富文本编辑） | 负责人 2026-11-01 拍板。预览页只读，点段落只跳回表单页 |
| 3 | **可靠电子签名**（CA 数字证书、时间戳服务） | 总纲 §5.5「明确不做」；技术路线 §10.4「不主张法律效力」 |
| 4 | **法院系统对接**（网上立案、送达对接） | 总纲 §5.5「明确不做」；无公开接口 |
| 5 | **AI 起草文书** | 段 3 已把「代写可递交文书」列为拦截意图（`508xxx` 域），AI 只解答法律信息 |
| 6 | **文书收费/限额** | 负责人 2026-11-01 拍板免费不限次；不接 `deduct()`，不扣配额 |
| 7 | **文书法条引用块（结构化）** | 法条在本段是**模板正文里的文字**，由运营写进 `.docx`，系统不需要知道引用了哪条。**因此段 3 §八.1 第 1/2 条承诺的 `ArticleNoValidator` 与 `refs` schema，本段未使用**——见 §八.2 |
| 8 | **模板批量导入/导出** | 设计稿 `17-doc-template.html` 有这两个按钮，本段不实现（照段 3 先例：设计稿有、功能不做要明说）。按钮禁用 + tooltip「暂未开放」 |
| 9 | **律师端文书管理**（`65-lawyer-docs.html`） | 属段 5（导航树 L583–587 在律师工作台模块） |
| 10 | **分享到微信** | 需要微信开放平台资质（负责人负责）；本段只做 `expo-sharing` 系统面板 |
| 11 | **文书模板的四语言版本** | 已随第 1 条降级；`lang` 字段保留但只写 `zh-Hans` |
| 12 | **签名的「设为默认」** | 设计稿没有这个功能，本段不加 |

---

## 八、留给下一段的接口（我保证什么 / 我不保证什么）

### 八.1 我保证（段 5~8 可以直接依赖）

1. **文书产物是标准 `.docx` + `.pdf`**：任何能读 Word/PDF 的工具都能处理。段 5 的「案件送达文书」直接引用 `document` 表即可。
2. **`document` 的四个 `*_oss_key` 与 `file_record` 一一对应**：`biz_type='document'`、`biz_id=document.id`。段 7 做存储清理时按这个口径统计。
3. **`task_queue` 的 `DOC_GENERATE` 任务类型已注册，handler 可复用**：段 5 的「批量生成送达文书」照这个 handler 写。
4. **`signature` 表的双重语义已定**（`document_id` 空/非空），段 5 若要给文书加律师签名，用同一个表。
5. **`doc_template` 的版本机制可靠**（`4-A4` 验过）：段 5 的案例库若要做模板化文书，照这个模式。
6. **模板管理不靠改代码**：A-18 能新建模板并让 App 立刻可见。段 5 若要加新模板类型，只需运营操作。
7. **`FileRecordService` 可复用**：段 5 的提现凭证、段 7 的投诉截图都走它。
8. **`PdfPageRenderer` 可复用**：把 PDF 转 PNG 的能力，段 5 的「文书预览」直接调用。

### 八.2 我不保证（后续段可能推翻或补做）

| 不保证 | 最晚什么时候解决 |
|---|---|
| **段 3 承诺的 `ArticleNoValidator` + `refs` schema 本段未使用** | 段 3 说「段 4 文书中心复用本服务」，但本段把法条当**模板正文文字**处理，不做结构化引用 → **该契约落空**。若将来要做法条引用块（如点击条文看原文），需在段 8 或二期补。**这是本段对上游的一次未兑现，必须记录** |
| **少数民族文字文书** | 若恢复，需重新引入 LibreOffice 的 shaping 能力（本段已有）、字体授权（段 0 的 R3）、以及 `4-A3`/`4-A4` 的原口径 |
| **文书的 PDF/A 归档格式** | 未做。若将来要长期归档，需转 PDF/A |
| **签名笔迹的法律效力** | 明确不主张。若要做可靠电子签名，需接 CA（二期） |
| **批量生成**（一次生成多份） | 未做。段 5 的「送达文书」可能需要 |
| **文书的可访问性**（PDF 标签化、朗读支持） | 未做。若上线地区有无障碍要求，段 8 补 |
| **iOS 端** | 本段只验 Android。iOS 的分享/打印行为差异留段 8 |
| **模板的富文本编辑** | 本段模板靠运营上传 `.docx`，后台不提供在线编辑 Word 内容 |

---

## 九、回写到哪些文档（本段结束必须完成，缺一条算未收口）

> 按 `docs/BASELINE.md` §4 的「四件事」办：**落款 / 登记指纹 / `docs/README.md` 加索引 / `CHANGELOG.md` 说明**。只做一件等于没做。

| # | 文档 | 改什么 | 什么时候 |
|---|---|---|---|
| 1 | 《开发计划总纲》§5.5 | 段定位「少数民族文字最终检验场」→「用户自助生成法律文书的完整链路」；交付物的四语言 → 中文；`4-A3`/`4-A4`/`4-A6` 三条锚点的口径；「必须做」第 1 条「模板四语言版本」→「模板单语言（`zh-Hans`）」 | 开工前 |
| 2 | 《开发计划总纲》§五 状态表（L911） | 段 4 一行改「已产出」+ 基线号 | 收口时 |
| 3 | 《技术路线》§10.3 | 标注「少数民族文字 PDF 渲染：段 4 不使用（只做中文）；保留本节供未来恢复多语时启用」 | 开工前 |
| 4 | 《数据表规范》附录 A | `task_queue` 与 `order_status_log` **未登记**（段 2 新增的第 50/51 张）→ 补两行；总数 49 → 51；段分布重算 | 开工前 |
| 5 | 《数据表规范》§4.1 | `doc_template` 不用 `is_enabled` 而用 `status` 三态 → 在附录 C 附近登记这条偏离 | 收口时 |
| 6 | 《PC 后台开发计划》L87 | A-18 的数据表名 `document_template` → `doc_template`；补「本段不做适用法条列、导入/导出按钮」 | 开工前 |
| 7 | 《PC 后台开发计划》A-18 行 + 《法泽PC后台UI设计说明》 | 记录设计稿的 3 处不实现（适用法条列、导入模板、批量导出模板） | 收口时 |
| 8 | 《CHANGELOG》+ `docs/BASELINE.md` + `docs/README.md` | 新增本文档的登记与索引 | 收口时 |

---

## 十、待负责人拍板（本段开工前或施工中必须有人回答，不许「假设已定」）

| # | 事项 | 为什么必须有人答 | 不答的后果 | 当前状态 |
|---|---|---|---|---|
| 1 | **`4-A5` 签名位置偏差的容差**（总纲原文：「建议 ≤ 4mm，具体由负责人定」） | 这是**判定标准**，不是技术参数。技术侧无权自己定一个数然后宣布通过 | `4-A5` 只能判「不遮挡正文」，**判不了「合格」** → 该锚点挂账，段 4 不能收口 | ⏳ 未定 |
| 2 | **5 份模板的内容由谁写**（运营/法务 vs 技术侧示意版） | 技术侧写不出真实法律文书；示意版收口时会被推翻 | 若技术侧写示意版→`4-A1`~`4-A3` 只验机制不验内容，段 4 的真实可用性存疑 | ⏳ 未定 |
| 3 | **文书下载要不要审计** | 技术路线 §10.4 只说「签名图下载留 `operation_log`」，文书未提。审计规范要求「管理端写接口默认必须审计」，但这是 C 端读操作 | 不审计：合规风险低但无法追溯谁下载过什么；审计：`operation_log` 量会很大（每次下载一条） | ⏳ 未定，本段默认**不审计** |
| 4 | **模板改版后，已生成的历史文书列表里要不要标「旧版」** | 设计稿没有这个字段 | 不标：用户看不出自己那份是旧模板；标：要加 UI 与接口字段 | ⏳ 未定，本段默认**不标**（`4-A4` 只验文件不变） |
| 5 | **iOS 端本段验不验** | 段 0 只做 Android；本段的后处理（分享/打印）在 iOS 上行为不同 | 不验：iOS 的分享/打印留段 8 挂账 | ⏳ 未定，本段默认**只验 Android** |
| 6 | **`.docx` 模板的字体**（仿宋 vs 宋体 vs 其他） | 技术路线 §10.5.1 写「仿宋_GB2312 三号」，但该字体在非 Windows 系统上常见缺失 | 若模板用「仿宋_GB2312」而系统没有 → LibreOffice 回退到别的字体，`4-A2` 的字体嵌入检查会暴露但内容会变字形 | ⏳ 未定，本段默认**用「仿宋」**（Windows 自带 `simfang.ttf`），不用 `_GB2312` 变体 |

---

## 十一、实际完成与偏差（施工后回写，不删原文）

> 施工完成后在这里追加，**不改上面的内容**。每条写：计划 vs 实际、偏差原因、是否影响下一段。

| # | 计划 | 实际 | 偏差原因 | 影响 |
|---|---|---|---|---|
| | | | | |

---

## 附：本文档的生成方式与已知局限

- **生成日期**：2026-11-01。
- **依据的实测**：仓库 HEAD `f7e887d`（分支 `UI设计`），2026-11-01 22:0x–22:1x。实测项：
  - `Get-Command soffice` 为空、三个常见安装路径均不存在 → 本机无 LibreOffice；
  - `C:\Windows\Fonts` 内无 Noto Sans Arabic / Tibetan（但本段不需要）；
  - 仓库 `.ttf/.otf/.ttc` 扫描 = 0（排除 `node_modules`）；
  - `apps/mobile-app/package.json` 的 `dependencies` 里没有 `expo-print`/`expo-sharing`/`expo-file-system`/`expo-media-library`/`@shopify/react-native-skia`/`gesture-handler`/`reanimated`/`view-shot`；
  - `services/` 目录不存在；
  - `docker` 未安装，`wsl` 存在，`java version "17.0.17"`。
- **读过的文档**：总纲 §5.5/§4.1/§4.2/§6.2/§8；技术路线 §5.6/§5.11/§10.1–10.5/§12.3.2/§4.8.4；数据表规范附录 A/附录 B/§4.1/§8.1；接口规范 §2.3/§九；后端代码规范 §9.2/§9.3；错误码规范 §3.6；段 1/2/3 工作流全文（尤其各自 §八.1 的承诺）；`design/ui/23–28*.html` 六页；`design/admin/17-doc-template.html`；导航树 §模块 6；PC 后台开发计划 L87/L180/L251；UI 设计顺序 L567–572。
- **已知局限**：
  1. **段 0–3 的施工一次都没跑**，§二 的 20 项前置是对上游出口的**预测性要求**，不是实测现状。开工前必须逐条复查。
  2. **LibreOffice 的「版式与 Word 一致」未实测**（本机没装）。R1 是本文档最大的技术不确定项。
  3. **5 份模板的内容未产出**：§三 B3 给的是「字段清单与版式要求」，不是真实法律文本。
  4. **`4-A5` 的容差未定**：总纲说「由负责人定」，未定值前该锚点无法判过。
  5. **段 3 承诺的 `ArticleNoValidator` 本段未使用**：本文档 §七 第 7 条与 §八.2 已如实记录，**不假装用上了**。
  6. **PDF 转 PNG 的清晰度与性能未实测**：E4 的预览方案在原理上成立（PDFBox `PDFRenderer`），但 200 DPI 下一张 A4 约 1654×2339 px，多页文书的内存与流量需要实测后调参。
  7. 本文档**不重复** DDL 全文以外的规范条款、i18n key 命名规则、审计动作清单——那些看各自的规范文档。

---

> **【文档基线 v1.27】** 本文件属于「法泽 FAZE 文档基线 v1.27」（2026-11-01 纳入）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
