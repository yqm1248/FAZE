# FAZE 文档索引

> 本目录是法泽 FAZE 项目的文档唯一入口。所有文档使用简体中文，术语与代码中的标识符保持一致。
>
> **项目定位**：面向新疆伊犁地区的普惠型法律服务平台，**真实上线运营**（不是原型、不是演示）。
> 平台范围：移动端 APP（Android 优先）+ 服务端 API + PC 管理后台（**UI 设计已完成，28 屏**）；4 种语言；APP **69 个页面**（2026-11-09 起；原 68 页，新增第 69 页「我的投诉」列表）+ PC 后台 **28 个页面**（APP 与 PC 是两套口径，**不要相加**）。

---

## 一、如果你是第一次来（按这个顺序读）

| 顺序 | 文档 | 回答什么问题 |
| --- | --- | --- |
| **⓪** | [BASELINE.md](BASELINE.md) | **哪些文档是现行有效的？分别是哪个版本？**（文档基线 v1.59 —— 后续一切文档从这里开始） |
| **①** | [architecture/法泽FAZE_开发计划总纲_v1.0.md](architecture/法泽FAZE_开发计划总纲_v1.0.md) | **现在是第几段？每段的边界在哪？怎么算做完？哪一页属于哪一段？**（9 段串行、页面归属、验收锚点全在这份） |
| **②** | [architecture/法泽FAZE_第0段工作流_v1.0.md](architecture/法泽FAZE_第0段工作流_v1.0.md) | **第 0 段具体怎么做？**（单线文档：从第一行读到最后一行，按顺序做，不用翻别的文档；12 任务 / 4 组 / 8 条锚点逐条可执行验收） |
| **②.5** | [architecture/法泽FAZE_第1段工作流_v1.0.md](architecture/法泽FAZE_第1段工作流_v1.0.md) | **第 1 段具体怎么做？**（8 组 29 任务 / 10 条锚点 / 12 张表 / 24+20 个接口；含负责人 2026-11-01 拍板的 12 项） |
| **②.6** | [architecture/法泽FAZE_第2段工作流_v1.0.md](architecture/法泽FAZE_第2段工作流_v1.0.md) | **第 2 段具体怎么做？**（9 组 33 任务 / 10 条锚点 / 12 张表 / 27+11 个端点；含负责人 2026-11-01 拍板的 7 项——**交易闭环 + IM + 退款 + 评价 + 内容 + 推送**） |
| **②.7** | [architecture/法泽FAZE_第3段工作流_v1.0.md](architecture/法泽FAZE_第3段工作流_v1.0.md) | **第 3 段具体怎么做？**（9 组 33 任务 / 8 条锚点 / 3 张表 / 16 个接口；含负责人 2026-11-01 拍板的 12 项与 **16 项已定裁决**——**法条检索 + 豆包 SSE + 三层防护 + 显式标识 + 后台两页**） |
| **②.8** | [architecture/法泽FAZE_第4段工作流_v1.0.md](architecture/法泽FAZE_第4段工作流_v1.0.md) | **第 4 段具体怎么做？**（7 组 24 任务 / 8 条锚点 / 4 张表 / 17 个接口；**当天改过两次范围**——①只做中文 ②渲染从 LibreOffice 换成 **openhtmltopdf**、模板从 `.docx` 换成 HTML、**不出 Word**。开工前必读那一节必须看） |
| **②.9** | [architecture/法泽FAZE_第5段工作流_v1.0.md](architecture/法泽FAZE_第5段工作流_v1.0.md) | **第 5 段具体怎么做？**（8 组 37 任务 / 8 条锚点（`5-A9` 已删）/ 建 8 张表 / 41 个接口；含负责人 2026-11-01 拍板的 **18 项**——**案件中心 + 律师工作台 5 页 + 送达 + 结算引擎 + 提现闭环 + 案例库 + 全局搜索**；§零之二 里三条改变了资金与送达口径，必须先读） |
| **②.10** | [architecture/法泽FAZE_第6段工作流_v1.0.md](architecture/法泽FAZE_第6段工作流_v1.0.md) | **第 6 段具体怎么做？**（5 组 19 任务（**已全部完成 19/19**）/ 7 条锚点（**3 条挂账**：`6-A1`/`6-A4` 真实部分/`6-A6` 需商户号）/ 建 1 张表（`recharge_record` 13 字段）/ **10 个接口** / 改造 A-09 加「渠道对账」Tab；含 18 项负责人裁决——手续费平台承担、单笔 0.1~5000 元、未实名不可充值、超时 30 分钟；含 Mock 通道先行的验收路径与挂账条件） |
| **②.11** | [architecture/法泽FAZE_第7段工作流_v1.0.md](architecture/法泽FAZE_第7段工作流_v1.0.md) | **第 7 段具体怎么做？**（8 组 41 任务 / 10 条锚点 / 建 11 张表（**新增第 54 张 `sensitive_word_appeal`、第 55 张 `announcement`**）/ 54 个接口（新增 26）/ APP 10 页 + PC 8 页；**26 项负责人裁决**——看板口径已定、告警走钉钉、表数 53→55、A-20 只看命中片段、**操作日志留存按合规 ≥3 年而非设计稿的 90 天**；开工前必读那节列了 **11 处上游冲突**） |
| **③** | [architecture/法泽FAZE_技术路线_v2.3.md](architecture/法泽FAZE_技术路线_v2.3.md) | 做什么、怎么做（技术栈、12 个域、55 张表、接口清单、状态机） |
| **③.5** | [architecture/法泽FAZE_数据表规范_v1.0.md](architecture/法泽FAZE_数据表规范_v1.0.md) | **表怎么命名、字段怎么定、索引怎么建、迁移怎么排、一共 55 张表**（建表前必读） |
| **③.6** | [architecture/法泽FAZE_接口规范_v1.0.md](architecture/法泽FAZE_接口规范_v1.0.md) | **接口长什么样**：契约怎么产生（混合 OpenAPI 三级）、URL/请求头/响应体/分页/金额/时间/脱敏/幂等/限流的全部条款、5 组冻结契约的字段级定义（写接口前必读） |
| **③.7** | [architecture/法泽FAZE_后端代码规范_v1.0.md](architecture/法泽FAZE_后端代码规范_v1.0.md) | **服务端代码怎么写**：包结构与分层、注释、异常、参数校验、事务、持久层、并发与任务表、日志、安全、工程性，**以及每条规则的「承接手段」（谁在拦住违反它的人）**（写服务端代码前必读） |
| **③.8** | [architecture/法泽FAZE_i18n规范_v1.0.md](architecture/法泽FAZE_i18n规范_v1.0.md) | **四语言文案怎么写**：key 三段式命名、14 个 namespace 与业务域的映射、缺译降级链、RTL 组件约定、服务端通知用哪种语言、6 项完整性门禁（写任何界面文案前必读） |
| **③.9** | [architecture/法泽FAZE_审计与日志规范_v1.0.md](architecture/法泽FAZE_审计与日志规范_v1.0.md) | **哪些操作必须留痕、日志里不能写什么、留多久**：审计与日志的分界、18 项必审计动作清单、留存期（含法律依据）、不可篡改、PC 日志中心的查询索引（写管理端写接口前必读） |
| **④** | [design/法泽APP_页面导航树_v1.4.md](design/法泽APP_页面导航树_v1.4.md) | **69 个页面**分别是什么、怎么跳转、返回逻辑（文件名叫 v1.4，内容为 **v1.6**） |
| **⑤** | [design/法泽APP_UI设计顺序_v1.0.md](design/法泽APP_UI设计顺序_v1.0.md) | **这 68 屏是怎么分批画出来的、每一屏的设计依据是什么、哪些地方是占位**（内容 **V3.0**；**第 69 页「我的投诉」是实现页、`design/ui/` 无对应设计稿**，不在本文档范围） |
| **⑥** | [architecture/法泽FAZE_PC后台开发计划_v1.0.md](architecture/法泽FAZE_PC后台开发计划_v1.0.md) | **PC 后台 28 页分别是什么、分 7 个阶段怎么开发、每阶段怎么验收** |
| **⑦** | [design/法泽PC后台UI设计说明_v1.0.md](design/法泽PC后台UI设计说明_v1.0.md) | **PC 后台 28 屏为什么这么画**：设计系统、7 类页面范式、逐屏依据、已知局限 |
| **⑧** | [standards/](standards/) | 代码怎么写、合并前检查什么 |

> **一句话规则**：
> **段与段之间的顺序、每段边界、页面归属**看开发计划总纲（9 段严格串行），
> **段内部怎么做**看该段的工作流文档（第 0 段 = 第 0 段工作流，一条线走到底），
> **做什么**看技术路线。

---

## 二、按用途查找

| 我想知道 | 去哪里 |
| --- | --- |
| **哪些文档是现行版、哪份已删除** | [BASELINE.md](BASELINE.md)（基线 **v1.59**，含全量资产的文件指纹 + 已删除文档登记） |
| **现在该做哪一段** | [architecture/法泽FAZE_开发计划总纲_v1.0.md](architecture/法泽FAZE_开发计划总纲_v1.0.md) |
| **这一段怎么算做完** | 同上，§五 每段的「验收锚点」（编号形如 `2-A3`，可直接引用） |
| **第 0 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第0段工作流_v1.0.md](architecture/法泽FAZE_第0段工作流_v1.0.md) —— 12 个任务分 4 组，每任务五段式：做什么 / 怎么做 / 产出 / 算做完 / 卡住怎么办 |
| **第 1 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第1段工作流_v1.0.md](architecture/法泽FAZE_第1段工作流_v1.0.md) —— 8 组 29 任务；**含 12 项已拍板决策**与三个跨段依赖表的处理方式 |
| **第 2 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第2段工作流_v1.0.md](architecture/法泽FAZE_第2段工作流_v1.0.md) —— 9 组 33 任务；**含 7 项已拍板决策**（两个新增表、8 态状态机、聊天原文可见性等）与 12 处已发现的文档缺陷 |
| **第 3 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第3段工作流_v1.0.md](architecture/法泽FAZE_第3段工作流_v1.0.md) —— 9 组 33 任务；**含 12 项已拍板决策 + 16 项已定裁决**（AI 不计费、不接第三方内容审核、法条走 Flyway、`ai_conversation` 命名等）与 6 类共 28 处已发现的文档冲突 |
| **第 4 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第4段工作流_v1.0.md](architecture/法泽FAZE_第4段工作流_v1.0.md) —— 7 组 24 任务；**当天改过两次范围**（①只做中文 ②换 openhtmltopdf + 砍 Word），开工前必读那一节（**此行 v1.30 补登，此前遗漏**） |
| **第 5 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第5段工作流_v1.0.md](architecture/法泽FAZE_第5段工作流_v1.0.md) —— 8 组 37 任务；**含 18 项已拍板决策**（结算不碰钱、送达不做法院转达、`payment_record` 不写服务费行等）与 12 处已发现的文档冲突 |
| **第 7 段怎么做（唯一施工文档）** | [architecture/法泽FAZE_第7段工作流_v1.0.md](architecture/法泽FAZE_第7段工作流_v1.0.md) —— 8 组 41 任务；**含 26 项已拍板决策**（看板口径、钉钉告警、表数 53→55、A-20 只看片段、日志留存按合规）与 11 处已发现的文档冲突 |
| **运维与值班怎么做** | [ops/运维手册.md](ops/运维手册.md) —— 值班与响应、告警规则、备份与恢复、监控启动、故障速查 |
| **新增依赖要登记到哪** | [architecture/依赖清单.md](architecture/依赖清单.md) —— 每条依赖的理由 / 不加的代价 / 风险与兜底 |
| **哪一页属于哪一段** | 总纲 §4.1（APP 69 页归属）+ §4.2（PC 28 页归属），这是唯一权威表 |
| **第 0 段要建哪两张表** | [architecture/法泽FAZE_V1基线表结构_v1.0.md](architecture/法泽FAZE_V1基线表结构_v1.0.md) |
| **错误码怎么编号** | [architecture/法泽FAZE_错误码规范_v1.0.md](architecture/法泽FAZE_错误码规范_v1.0.md)（号段规则 + HTTP 状态码规则；**全量码表由文档内受控区块从 [facts/error-codes.json](facts/error-codes.json) 生成，当前 94 个**） |
| 为什么是这个顺序、能不能调 | 总纲 §3.2「依赖关系」+ §3.3「严格串行到底约束什么」+ §4.3「归属差异」 |
| 哪些外部资质会卡住我 | 同上，§九「并行轨道」 |
| 每一段开工前要检查什么、结束时交什么 | 同上，§六「段的门禁」 |
| 还有哪些事没拍板 | 同上，§十「待决事项」（10 条，每条写了最晚什么时候定） |
| 每一版改了什么 | [CHANGELOG.md](CHANGELOG.md) |
| 做什么、技术栈、要不要用某个库 | [architecture/法泽FAZE_技术路线_v2.3.md](architecture/法泽FAZE_技术路线_v2.3.md) |
| **数据库表结构** | [architecture/法泽FAZE_数据表规范_v1.0.md](architecture/法泽FAZE_数据表规范_v1.0.md) 附录 A（**51 张表的准确清单**，含类别/域/建表段）；字段级 DDL 看技术路线第五章 |
| **建表/改表要遵守什么** | 同上，§二~§九（命名、类型、四类表、索引、迁移、审计、敏感字段）+ §十「建表前 12 问」 |
| **后端接口有哪些** | [architecture/法泽FAZE_技术路线_v2.3.md](architecture/法泽FAZE_技术路线_v2.3.md) 第 4.8 节「接口清单」 |
| **接口该怎么写（路径/请求头/响应体/分页/金额/时间/脱敏/幂等/限流）** | [architecture/法泽FAZE_接口规范_v1.0.md](architecture/法泽FAZE_接口规范_v1.0.md) —— §二 契约分级 + §三~§六 全部条款 + §九 联调 12 问 |
| **几个核心接口的字段级契约（登录/用户列表/实名审核/入驻审核/手工充值）** | 同上，**附录 A**（5 组冻结契约，字段名与类型已定死） |
| **服务端代码怎么写（分层/注释/异常/事务/持久层/日志/安全）** | [architecture/法泽FAZE_后端代码规范_v1.0.md](architecture/法泽FAZE_后端代码规范_v1.0.md) —— §二~§十二 全部条款 + §十三 合并前 12 问 |
| **规范写了但没人守怎么办** | 同上，**§十四「每条规则的承接手段」**：用 ArchUnit / checkstyle / gitleaks / JaCoCo / CI grep / PR 模板逐条兜住；答不上「谁在拦」的规则不该写 |
| **界面文案/多语言怎么写（key 命名、缺译怎么办、RTL 怎么镜像）** | [architecture/法泽FAZE_i18n规范_v1.0.md](architecture/法泽FAZE_i18n规范_v1.0.md) —— §3 key 命名 + §5 降级 + §8 RTL + §11 六项 CI 门禁 |
| **哪些操作必须留痕、日志里不能写什么、留存多久** | [architecture/法泽FAZE_审计与日志规范_v1.0.md](architecture/法泽FAZE_审计与日志规范_v1.0.md) —— §3.2 十八项必审计动作 + §5 留存期 + §6 查询索引 |
| 订单/文章/案件的状态怎么流转 | 同上，第 9.2 节（订单）、5.8.1 节（内容） |
| **有哪 69 个页面、怎么跳转** | [design/法泽APP_页面导航树_v1.4.md](design/法泽APP_页面导航树_v1.4.md)（内容 v1.6） |
| **APP 设计稿长什么样、怎么看** | 打开 `design/ui/01-home.html` 起，同目录 71 个 HTML 并排看；组件在 `design/ui/components.css`、令牌在 `design/ui/tokens.css` |
| **每屏的设计依据、分了多少批、哪些是占位** | [design/法泽APP_UI设计顺序_v1.0.md](design/法泽APP_UI设计顺序_v1.0.md)（内容 V3.0） |
| **PC 后台有哪 28 页、先做哪几页、每段怎么验收** | [architecture/法泽FAZE_PC后台开发计划_v1.0.md](architecture/法泽FAZE_PC后台开发计划_v1.0.md) —— 28 页清单 + 7 个开发阶段 |
| **PC 后台设计稿长什么样、怎么看** | 打开 `design/admin/00-login.html` 起，同目录 HTML 并排看；令牌在 `design/admin/tokens.css`、组件在 `design/admin/components.css` |
| **PC 后台 28 屏的设计依据、设计系统怎么定的** | [design/法泽PC后台UI设计说明_v1.0.md](design/法泽PC后台UI设计说明_v1.0.md) —— 设计系统 + 7 类范式 + 逐屏说明 |
| **PC 后台现在还有哪些坑没填** | [design/未完成需备注.md](design/未完成需备注.md) —— 仍待拍板的业务规则、双份 DDL、待定值、RuoYi 框架层边界（接口缺口已于 2026-10-30 补齐） |
| 某个页面在原项目里长什么样 | [design/页面清单与信息架构.md](design/页面清单与信息架构.md) —— 含 12 个核心页面的**视觉结构速查**与配色统一映射 |
| 颜色/字号/间距取什么值、为什么 | [design/设计令牌.md](design/设计令牌.md) |
| 有哪些可复用组件 | [design/组件清单.md](design/组件清单.md) |
| 代码怎么写注释 | [standards/代码注释规范.md](standards/代码注释规范.md) |
| 文件放哪个目录、怎么命名 | [standards/目录与命名规范.md](standards/目录与命名规范.md) |
| 合并前要检查什么 | [standards/代码审查清单.md](standards/代码审查清单.md) |
| **同一个事实在代码 / 文档 / 设计稿三处说法不同时听谁的** | [standards/说辞一致与真相源规范.md](standards/说辞一致与真相源规范.md) —— P0/P1/P2/P3 真相源分级、DRIFT/GAP/DIRT 三类不一致的分界、5 种处置值；**已知漂移逐条见 [facts/DRIFT.md](facts/DRIFT.md)**，**计划有但代码没有的见 [facts/DEFERRED.md](facts/DEFERRED.md)** |
| 网络请求怎么写（唯一出口、8 个请求头、401 单飞） | [standards/网络层约定.md](standards/网络层约定.md) |
| 页面加载/空/错误/正常四态怎么写 | [standards/页面四态约定.md](standards/页面四态约定.md) |
| 怎么打一个能装到手机的 APK（换机器怎么重建） | [standards/构建路径.md](standards/构建路径.md) |
| 藏文/阿拉伯系用哪个字体（授权与加载） | [standards/字体选型.md](standards/字体选型.md) |
| SSE 流式怎么收、探针怎么用、首字节怎么量 | [standards/探针与流式约定.md](standards/探针与流式约定.md) |
| 法条怎么录（逐字准确、两个条号字段、Flyway 与运营期两条路） | [standards/法条数据录入规范.md](standards/法条数据录入规范.md) |
| AI 对话存多久、脱敏与否、导出什么格式（留存策略） | [standards/AI对话留存策略.md](standards/AI对话留存策略.md) |
| 为什么不用原项目那套代码 | [analysis/原项目技术债清单.md](analysis/原项目技术债清单.md) |
| 这三份规范从哪来 | [analysis/参考项目规范提取.md](analysis/参考项目规范提取.md) |
| 协作规则、禁止事项 | [../AGENTS.md](../AGENTS.md) |

---

## 三、文档目录结构

```text
docs/
  README.md                              本索引
  BASELINE.md                            ★ 文档基线 v1.59 —— 一切文档从这里开始（SHA256 指纹 + 已删清单）
  CHANGELOG.md                           每次推送的完整变更说明（改了什么 / 为什么 / 影响面 / 核对结果）
  architecture/                          ★ 只留现行版，历史版本在 git 里
    法泽FAZE_开发计划总纲_v1.0.md          ★ 起点：9 段顺序、每段边界、页面归属（69+28，两套口径不相加）、79 条验收锚点、段的门禁
    法泽FAZE_第0段工作流_v1.0.md           ★ 第 0 段唯一施工文档（12 任务 / 4 组 / 8 锚点逐条可验收）
    法泽FAZE_第1段工作流_v1.0.md           ★ 第 1 段唯一施工文档（8 组 29 任务 / 10 锚点 / 12 张表 / 24+20 接口）
    法泽FAZE_第2段工作流_v1.0.md           ★ 第 2 段唯一施工文档（9 组 33 任务 / 10 锚点 / 12 张表 / 27+11 端点）
    法泽FAZE_第3段工作流_v1.0.md           ★ 第 3 段唯一施工文档（9 组 33 任务 / 8 锚点 / 3 张表 / 16 接口 / 39 份证据）
    法泽FAZE_第4段工作流_v1.0.md           ★ 第 4 段唯一施工文档（7 组 24 任务 / 8 锚点 / 4 张表 / 17 接口；**只做中文**）
    法泽FAZE_第5段工作流_v1.0.md           ★ 第 5 段唯一施工文档（8 组 37 任务 / 8 锚点 / 8 张表 / 41 接口；**18 项裁决**）
    法泽FAZE_PC后台开发计划_v1.0.md         ★ PC 后台 28 页清单 + 7 个开发阶段（P1~P7 是批次建议，交付单位以总纲为准）
    法泽FAZE_技术路线_v2.3.md              ★ 现行技术路线（一～二十五章 + 附录；§4.8.4 是 2026-10-30 的
                                              计划数 87 个管理端接口，**实测 123，见 facts/api.json**）
    法泽FAZE_数据表规范_v1.0.md             ★ 建表/改表规则 + 51 张表清单（含类别/域/建表段）；§5.1 旧清单已作废
    法泽FAZE_接口规范_v1.0.md               ★ 接口契约的唯一依据：三级契约、URL/请求/响应/横切全条款、5 组冻结契约字段级定义；取代 §4.4/§4.8.1/§4.8.4.2
    法泽FAZE_后端代码规范_v1.0.md           ★ 服务端代码唯一依据：分层/注释/异常/事务/持久层/并发/日志/安全 + §十四 规则承接手段矩阵
    法泽FAZE_i18n规范_v1.0.md                ★ 四语言唯一依据：key 三段式命名、14 个 namespace 映射、缺译降级、RTL 组件约定、6 项完整性门禁
    法泽FAZE_审计与日志规范_v1.0.md          ★ 审计≠日志的分界、18 项必审计动作、留存期（含法律依据）、不可篡改、查询索引
    法泽FAZE_V1基线表结构_v1.0.md           ★ V1（段 0）只建 app_user + law_item，完整 DDL
    法泽FAZE_错误码规范_v1.0.md              ★ 号段规则 + HTTP 状态码规则；**全量码表是受控区块**，
                                              由 facts/error-codes.json 生成（当前 94 个，别再手写数字）
  design/                                设计依据
    README.md
    法泽APP_页面导航树_v1.4.md             ★ 69 页逐页清单、跳转关系、页面四态、返回逻辑（内容 v1.6）
    法泽APP_UI设计顺序_v1.0.md             ★ 17 批怎么画完 68 屏、每屏设计依据、占位项（内容 V3.0；第 69 页无设计稿）
    未完成需备注.md                        ★ PC 后台设计暴露的未决问题（业务规则/DDL/框架边界）
    页面清单与信息架构.md                  复刻期 12 个核心页面的视觉结构速查与配色映射 + 第 69 页归属说明（**不是现行 69 页清单**）
    设计令牌.md                           与 design/tokens/tokens.json 保持同步
    组件清单.md
    screenshots/                         12 张 UI 截图（原型阶段产物，仅作视觉参考）
  standards/                             工程规范（必须遵守）
    README.md
    目录与命名规范.md
    代码注释规范.md
    代码审查清单.md
    说辞一致与真相源规范.md                ★ 同一个事实三处说法不同时听谁的（本版新增）
    （另 7 份：网络层约定 / 页面四态约定 / 构建路径 / 字体选型 / 探针与流式约定 /
      法条数据录入规范 / AI对话留存策略，见 standards/README.md）
  facts/                                 事实登记表（长期记忆，不是待办清单）
    DRIFT.md                              说辞漂移：同一事物、说法不同（D3 组是跑脚本量出来的）
    DEFERRED.md                           段八挂账汇总索引：计划有、代码无
    api.json                              接口清单    ← scripts/facts/api.mjs 生成，禁止手改
    tables.json                           表结构      ← scripts/facts/tables.mjs 生成
    error-codes.json                      错误码      ← scripts/facts/error-codes.mjs 生成
    i18n-keys.json                        四语言 key  ← scripts/facts/extract-i18n.mjs 生成
    pages.json                            页面逐项明细 ← scripts/facts/extract-pages.mjs 生成
    （以上 5 份 json 全部由 `npm run facts` 生成、`npm run check:facts` 校验，
      CI 用 `git diff --exit-code docs/facts/` 拦漂移；**手改一定被覆盖**。
      本目录不设 README —— 怎么用、什么该进哪张表，只在
      standards/说辞一致与真相源规范.md §二、§五 写一份，避免第二份副本腐坏）
  analysis/                              决策依据与背景
    原项目技术债清单.md
    参考项目规范提取.md
```

**图例**：★ = 必读。

---

## 四、文档维护规则

1. **文档先行**：新增页面或组件前，先在 `design/` 里写清它的用途、令牌取值和跳转关系。
2. **代码与文档同一提交**：改了行为就同时改文档，不允许「代码先合，文档下次补」。
3. **不做推测记录**：拿不准的内容标注「待确认」，不要写成结论。
4. **不复述代码**：文档写「为什么」和「边界」，不抄代码实现细节。
5. **新增文档必须同时更新本索引**。文件存在但索引里没有，等于没有。
6. **版本引用不写死**：说「技术路线 2.4 节」而不是「v2.2 的 2.4 节」，避免升级后引用过期。
7. **工作区只留现行版，历史版本靠 git 找回**。过期文档不在工作区堆积，删除当时在 `BASELINE.md` 登记「删了什么、为什么、从哪个 commit 能找回」。追溯能力不依赖仓库里留着废文件。
8. **两个分支各留一份，`docs` 是总库（2026-10-29 确立；2026-10-30 补设计稿快照）**。
   - `UI设计` 分支：设计稿本体 + 设计过程中产生的文档（设计顺序、令牌、组件、未完成需备注）。
   - `docs` 分支：承担**整个项目开发过程中的全部文档**——项目设计、页面导航树、项目背景、开发计划、UI 设计文档等；**设计稿（`design/`）也留一份封存快照**，让只看文档库的人能直接打开设计稿。设计稿的准据仍是 `UI设计` 分支，`docs` 侧是快照。
   - **每次在 `UI设计` 分支改完文档或设计稿，必须同步到 `docs` 分支**：把 `docs/` + 根 `AGENTS.md` + 根 `README.md` + `design/` 拷过去，然后验证
     ```powershell
     git diff UI设计 docs -- docs/ AGENTS.md README.md design/   # 必须为空
     ```
     两边各自提交、各自推送。**不这么做的后果是两分支分叉**——2026-10-29 已经发生过一次（`docs` 停在早期 6 屏那条线，`UI设计` 已经滚到基线 v1.4）；设计稿目录也一直没同步，`docs` 侧停在早期 8 屏原型，2026-10-30 才发现并补齐。

---


---

## 段 2 验收证据索引（2026-11-06 收口）

| 文件 | 内容 |
|---|---|
| [acceptance/seg2/G1-tables.md](acceptance/seg2/G1-tables.md) | G1 建表：12 张表与唯一键/索引 |
| [acceptance/seg2/G2-order-pay.md](acceptance/seg2/G2-order-pay.md) | G2 下单与支付（含幂等、超时、支付密码错误） |
| [acceptance/seg2/G3-lawyer.md](acceptance/seg2/G3-lawyer.md) | G3 律师侧与展示 |
| [acceptance/seg2/G4-refund.md](acceptance/seg2/G4-refund.md) | G4 退款申请/审批/入账（含吞钱缺陷修复） |
| [acceptance/seg2/G5-review.md](acceptance/seg2/G5-review.md) | G5 评价提交与评分即时回写 |
| [acceptance/seg2/G6-im.md](acceptance/seg2/G6-im.md) | G6 IM 会话（WS / 已读撤回 / 重连 / 离线消息） |
| [acceptance/seg2/G7-push.md](acceptance/seg2/G7-push.md) | G7 离线推送（G7.1 挂账、G7.2 完成、G7.3 逻辑完成） |
| [acceptance/seg2/G8-content.md](acceptance/seg2/G8-content.md) | G8 文章四语言与消息中心空态 |
| [acceptance/seg2/G9-admin.md](acceptance/seg2/G9-admin.md) | G9 PC 后台 4 页（订单列表/详情、文章列表/编辑） |

锚点（二值判定见各文件首行）：`2-A1` 下单扣款 / `2-A2` 接单与会话（IM 45ms）/ `2-A3` 杀进程推送（**未通过，挂段 8**）/ `2-A4` 结束与评价 / `2-A5` 支付密码错误 / `2-A6` 退款闭环 / `2-A7` 首页真实数据 / `2-A8` 超时与幂等 / `2-A9` 断线重连（5 条实测）/ `2-A10` RTL 会话页（近似，真 RTL 挂段 8）。

## 段 4 验收证据索引（2026-11-08 收口）

> 一键复跑：`deploy/verify-seg4.ps1`（8 条锚点自动判定，需 `faze-api:8081` + MySQL + RuoYi:8080 + 本机 `simfang.ttf`）。
> 浏览器项另跑 `node deploy/tools/seg4-browser-a7.mjs`（4-A7）与 `node deploy/tools/seg4-browser-e.mjs`（E 组六页）。

| 文件 | 内容 |
|---|---|
| [acceptance/seg4/4-A1.md](acceptance/seg4/4-A1.md) | PDF 生成：3 原告/2 被告/2 诉求全在、`{{` 残留 0 处 |
| [acceptance/seg4/4-A2.md](acceptance/seg4/4-A2.md) | A4 版式 + 字体全嵌入；**上下边距判据口径调整的原因** |
| [acceptance/seg4/4-A3.md](acceptance/seg4/4-A3.md) | 文本层可选中可搜索（正文非贴图） |
| [acceptance/seg4/4-A4.md](acceptance/seg4/4-A4.md) | 模板热更新不改历史文书（H1==H2、H3!=H1），含「升版后重新生成 406001」缺陷修复 |
| [acceptance/seg4/4-A5.md](acceptance/seg4/4-A5.md) | 签名落点偏差 2.00pt ≤ 4mm，且不遮挡正文 |
| [acceptance/seg4/4-A6.md](acceptance/seg4/4-A6.md) | 5 个溯源元数据字段 + `AIGenerated` 0 处 |
| [acceptance/seg4/4-A7.md](acceptance/seg4/4-A7.md) | 后处理链（浏览器口径）：下载落盘真 PDF、分享降级、打印触发、存相册挂账 |
| [acceptance/seg4/4-A8.md](acceptance/seg4/4-A8.md) | 失败不留半成品：4 场景 × 5 件事（含死信可读性与可重试修复） |
| [acceptance/seg4/4-A7-browser.json](acceptance/seg4/4-A7-browser.json) | 真 Chrome 产出的 4-A7 浏览器证据 |
| [acceptance/seg4/E-group.md](acceptance/seg4/E-group.md) | E1~E6 六页 + F1 前端出口验收（含 `E-group-browser.json`） |
| [acceptance/seg4/B1-a18.md](acceptance/seg4/B1-a18.md) | B1 A-18 页面（含 3 个缺陷修复） |
| [acceptance/seg4/C4-failure.md](acceptance/seg4/C4-failure.md) | C4 失败路径（字体缺失 / 存储不可用） |
| [acceptance/seg4/D1-signature.md](acceptance/seg4/D1-signature.md) · [D2-stamp.md](acceptance/seg4/D2-stamp.md) · [D3-meta.md](acceptance/seg4/D3-meta.md) | 签名库规则 / 叠图坐标 / 溯源元数据 |
| [acceptance/seg4/F2-regen.md](acceptance/seg4/F2-regen.md) | 重新生成与旧产物保留（3 次生成 3 组产物） |
| [acceptance/seg4/遗留与挂账.md](acceptance/seg4/遗留与挂账.md) | 偏离登记、未验项、**上线前必须清理的测试数据** |

锚点结论：`4-A1`~`4-A8` **8/8 自动判定通过**（`deploy/verify-seg4.ps1`），其中 `4-A2` 的上下边距判据口径调整已单独说明；`4-A7` 的降级项与段 8 挂账见该文件 §5。

## 段 5 验收证据索引（2026-11-08 收口）

> 段 5 没有一键脚本（`5-A1/A2` 需要两个账号交替操作），锚点证据是「真打接口 + 查库」的手工留证：
> 每条锚点一个文件，文件里写判据表、原始响应与 SQL 输出。
> 环境：`faze-api` 8081（dev）+ MySQL `faze` + RuoYi 8080；律师账号 `13800000001 / 123456`、管理员 `admin / admin123`。

| 文件 | 内容 |
|---|---|
| [acceptance/seg5/5-A1.md](acceptance/seg5/5-A1.md) | 在线立案：`CS` 单号、`form_snapshot` 9 字段、审核通过 → `accepted`、进度 2 条（含中文 `????` 数据瑕疵说明） |
| [acceptance/seg5/5-A2.md](acceptance/seg5/5-A2.md) | 进度同步：律师推进 + 操作人 + `accepted → in_progress` + 法院案号（**含本轮修掉的「只写案号不迁状态」缺陷**） |
| [acceptance/seg5/5-A3.md](acceptance/seg5/5-A3.md) | 案件送达：`SD` 回执、签收幂等 `407007`、签收设备、全文无三处法院表述 |
| [acceptance/seg5/5-A4.md](acceptance/seg5/5-A4.md) | 收入对账：三处数字逐笔一致（66 页 / A-13 结算页 / SQL 流水），明细每行带订单号 |
| [acceptance/seg5/5-A5.md](acceptance/seg5/5-A5.md) | 提现闭环：四步余额对照 + `freeze/unfreeze/withdraw` 流水 + 两条恒等式 + 四条边界校验 |
| [acceptance/seg5/5-A6.md](acceptance/seg5/5-A6.md) | 服务设置：改价立即生效（无缓存）、区间校验 `405006`、`autoAccept` 回读 |
| [acceptance/seg5/5-A7.md](acceptance/seg5/5-A7.md) | 案例库：PC 录入 → 上线 → APP 三分组可见 → 四块正文 + 法条引用 → 大类自动算 → 不显示案号/法院 |
| [acceptance/seg5/5-A8.md](acceptance/seg5/5-A8.md) | 全局搜索：四域分域响应（原样贴 JSON）、单域只回一组、空态与 idle 态 |
| [acceptance/seg5/PROGRESS-A-B.md](acceptance/seg5/PROGRESS-A-B.md) | A/B 组施工与偏差（含 3 个环境问题与 6 条偏差） |
| [acceptance/seg5/PROGRESS-C-D.md](acceptance/seg5/PROGRESS-C-D.md) | C/D 组施工与偏差（含 D2 发送未验、`5-A4` 当时挂账） |
| [acceptance/seg5/PROGRESS-E-H.md](acceptance/seg5/PROGRESS-E-H.md) | E~H 组施工、实测数字与 **12 条偏差**（含解冻 `biz_type`、案例库 24 字段、凭证上传修正） |
| [acceptance/seg5/A4-enums.md](acceptance/seg5/A4-enums.md) | A 组枚举清单（案件 5 态 / 案例 3 大类 + 6 细分 / 提现 4 态 / 结算 3 态） |
| [acceptance/seg5/A1-schema.txt](acceptance/seg5/A1-schema.txt) | 段 5 建表与补字段的物证：9 张表 `SHOW CREATE TABLE` 原样输出（22 KB，flyway=`510`） |
| [acceptance/seg5/H2-rtl.md](acceptance/seg5/H2-rtl.md) | 四语言与 RTL：静态核验**通过**（0 处 `left/right`、`letterSpacing` 全 0、无手动反转），但 **RTL 运行时未验**（缺译文，推段 8） |

锚点结论：`5-A1`~`5-A8` **8/8 通过**。三条与计划口径不同的地方必须一起看：
① 解冻流水 `biz_type` 是 `income_unfreeze`（不是 `unfreeze`）、`balance_flow.biz_type` 共 **5 值**；
② `case_library` 实为 **24 字段**（补争议焦点 / 原告 / 被告）；
③ 三语言文案**仍是登记式缺译**，段 8 统一翻译。
三项都登记在《[技术路线 v2.3](architecture/法泽FAZE_技术路线_v2.3.md)》§二十六「段 5 施工登记」里——**该节与正文冲突时以该节为准**。

## 段 6 验收证据（真实充值渠道 + 掉单补单 + 渠道对账）

> 环境：`faze-api` 8081（dev）+ MySQL `faze` + RuoYi 8080 + Redis；Mock 通道（`pay.mock.enabled=true`）。
> **段 6 任务完成度 19/19**；锚点 **4 通过 / 2 挂账（`6-A1`/`6-A6`）/ 1 部分挂账（`6-A4` 的真实账单部分）**。
> 挂账项是「无支付商户号」的外部条件，不是本段欠账，见 `挂账.md`。

| 文件 | 内容 |
|---|---|
| [acceptance/seg6/A1-schema.txt](acceptance/seg6/A1-schema.txt) | A 组：`recharge_record` 建表 13 字段 4 索引（`SHOW CREATE TABLE`） |
| [acceptance/seg6/A3-config.md](acceptance/seg6/A3-config.md) | A 组：24 个 `sys_config` 键 + `ConfigCipher` 加密流程（不引入 Jasypt 的理由） |
| [acceptance/seg6/A4-enums.md](acceptance/seg6/A4-enums.md) | A 组：3 个枚举 + 状态机 3 条合法边 + 3 条禁令 |
| [acceptance/seg6/B1-api.txt](acceptance/seg6/B1-api.txt) | B1：下单/单笔/列表 3 接口；幂等 4 次 1 单、9 分被拒、未实名 405020、跨用户 403 |
| [acceptance/seg6/B2-page.md](acceptance/seg6/B2-page.md) | B2：充值页四态 + 四类金额校验 + 未实名拦截 + Mock 模拟支付（4 张截图） |
| [acceptance/seg6/B3-limit.txt](acceptance/seg6/B3-limit.txt) | B3：限额策略 17 个单测 + 金额/笔数/未实名三类拦截 |
| [acceptance/seg6/B4-reconcile-tab.md](acceptance/seg6/B4-reconcile-tab.md) | B4：A-09 包 el-tabs + 渠道对账 Tab；**数据由 D3/D4 任务真实产出**（3 张截图） |
| [acceptance/seg6/C4-idempotent.txt](acceptance/seg6/C4-idempotent.txt) | C4：同一回调串行 3 次 + 并发 2 次（**HTTP 进程级 + JVM 内 2 线程两套证据**），只入账 1 次（余额/流水/单三方一致） |
| [acceptance/seg6/D1-D2-tasks.txt](acceptance/seg6/D1-D2-tasks.txt) | D1 掉单补单 + D2 超时关单（关单前先查渠道） |
| [acceptance/seg6/B3-D1-final.txt](acceptance/seg6/B3-D1-final.txt) | **B3 + D1 收尾实测**：B3 用户维度限流（第 4 次 **HTTP 429 + Retry-After + 码 405030**，含 Redis 计数）· D1 管理端人工补单（`callback_lost` 掉单 → sync → `settled` + 余额/流水/审计三方对照） |
| [acceptance/seg6/6-A4-reconcile.txt](acceptance/seg6/6-A4-reconcile.txt) | **D3/D4**：账单留档 + 三类差异落库 + `channel_only` 自动补单 + 幂等重跑 + 6-A4 五项证据逐条对照 |
| [acceptance/seg6/6-A4-diffs-export.csv](acceptance/seg6/6-A4-diffs-export.csv) | D4：差异报表 CSV 导出的真实内容 |
| [acceptance/seg6/6-A7-failure.txt](acceptance/seg6/6-A7-failure.txt) | 6-A7：支付失败 → 单 `failed`、0 流水、额度释放（不占配额） |
| [acceptance/seg6/E1-identity.txt](acceptance/seg6/E1-identity.txt) | E1：恒等式巡检 0 条 → 造 1 分差异 → 检出 1 条 → 恢复 0 条 |
| [acceptance/seg6/E2-e2e.txt](acceptance/seg6/E2-e2e.txt) | **E2**：下单 → 模拟支付 → 入账 → 查余额 → 对账 一条连贯 8 步走查 |
| [acceptance/seg6/挂账.md](acceptance/seg6/挂账.md) | 三张挂账锚点 + 未完成任务 + 负责人三项裁决 + **本次暴露的 4 个真 bug** |

## 段 7 验收证据（运营与治理收口 · A~H 全部交付）

> **本段 A/B/C/D/E/F/G/H 八组均已交付**（后端 + PC 端 8 页 + APP 14 页 + 监控告警备份 + 收口）；
> 逐条偏差见《[第 7 段工作流](architecture/法泽FAZE_第7段工作流_v1.0.md)》§十一 的 **57 条**记录。
> **十条锚点不是全过**：通过 5 条（`7-A3` / `7-A4` / `7-A7` / `7-A9` / `7-A10`）、
> 部分完成 4 条（`7-A2` / `7-A5` / `7-A6` / `7-A8`）、未完成 1 条（`7-A1`）；
> 全部挂账与补验方式见 [acceptance/seg7/挂账.md](acceptance/seg7/挂账.md)。
> **第 69 页「我的投诉」列表交付后，`7-A1` 第 3 步与 `7-A5` 的 APP 界面证据才可能补齐**（该页 2026-11-09 由负责人拍板新增，属段 7 C 组客服链路；`design/ui/` 无对应设计稿，是补锚点证据的**实现页**，稿面依据只有 57 投诉页的卡片与列表样式）。

| 证据 | 说明 |
| --- | --- |
| [acceptance/seg7/A1-schema.txt](acceptance/seg7/A1-schema.txt) | A1：11 张表 `SHOW CREATE` 全量 + 字段数/索引清单 + 种子计数 + **V703 跑 3 遍仍 50/7/6**（幂等） |
| [acceptance/seg7/A-02-指标口径.md](acceptance/seg7/A-02-指标口径.md) | A5：10 个指标逐条给「定义 / 算法 SQL / 数据来源 / 边界 / 已知局限」（`7-A10` 的正面证据，§6 已随趋势切日修正） |
| [acceptance/seg7/C5-前端4页.md](acceptance/seg7/C5-前端4页.md) | C5：APP 55/56/57/58 四页的 CDP 真实浏览器验收（390×844）+ 逐条验收点 + 真实点击提交证据 + 6 条偏离 |
| [acceptance/seg7/7-A1-runbook.md](acceptance/seg7/7-A1-runbook.md) | `7-A1` 运营动作清单：**未完成**（0/15 截图），写清为何没做、五步当前可做性、补验步骤 |
| [acceptance/seg7/7-A2-no-rewrite.md](acceptance/seg7/7-A2-no-rewrite.md) | `7-A2` 原文不被篡改：review 分支原文完整 + 命中三要素；block 分支与命中记录同事务回滚（锚点第 3/4 步不适用） |
| [acceptance/seg7/7-A3-hot-reload.md](acceptance/seg7/7-A3-hot-reload.md) | `7-A3` 敏感词即时生效：增/改/删三次判定全按最新词库，进程启动时间前后一致（**通过**） |
| [acceptance/seg7/7-A4-ban.md](acceptance/seg7/7-A4-ban.md) | `7-A4` 黑名单：403 + 独立码 + 提示含客服电话 + 旧 token 立即失效（**通过**） |
| [acceptance/seg7/7-A5-ticket.md](acceptance/seg7/7-A5-ticket.md) | `7-A5` 投诉工单：接口级双向通过（`waiting→processing→replied`）；**APP 无投诉列表页**（界面级未完成） |
| [acceptance/seg7/7-A6-audit.md](acceptance/seg7/7-A6-audit.md) | `7-A6` 操作日志：谁/何时/从哪/改了什么 四项齐全，详情抽屉 before/after 对比表；操作人只有 id |
| [acceptance/seg7/7-A7-metrics.md](acceptance/seg7/7-A7-metrics.md) | `7-A7` 监控指标：Prometheus target UP、6 指标、10 次请求 Δ=10、Grafana 4 图（**通过**；另有 3 张 png） |
| [acceptance/seg7/7-A8-alert.md](acceptance/seg7/7-A8-alert.md) | `7-A8` 告警真的会响：系统自动 POST 的钉钉 markdown 原文；**钉钉群无证据** + 三条结构缺口 |
| [acceptance/seg7/T4-alert-mock-evidence.md](acceptance/seg7/T4-alert-mock-evidence.md) | `7-A8` 的 mock 接收端原始证据（headers + body + hex） |
| [acceptance/seg7/7-A9-restore.md](acceptance/seg7/7-A9-restore.md) | `7-A9` 备份恢复：逐表行数对比 + 3 表抽样 MD5 + `faze_restore` 硬保护（**通过**） |
| [acceptance/seg7/7-A10-dashboard.md](acceptance/seg7/7-A10-dashboard.md) | `7-A10` 看板口径：4 卡手算=页面、tooltip、待我处理 5 条跳转、pendingReason（**通过**） |
| [acceptance/seg7/F3-explain.txt](acceptance/seg7/F3-explain.txt) | F3：4 个看板接口的 `EXPLAIN` 执行计划 |
| [acceptance/seg7/T1-后端接口实测.md](acceptance/seg7/T1-后端接口实测.md) | T1：E/F/G 三组 28KB 逐条 curl/SQL 原文 |
| [acceptance/seg7/T2-APP-E组4页与设置.md](acceptance/seg7/T2-APP-E组4页与设置.md) | T2：APP E 组 9 页的 35 张截图索引 + 逐项验收 + 偏差降级 |
| [acceptance/seg7/挂账.md](acceptance/seg7/挂账.md) | **本段全部挂账与补验方式**（锚点 / 功能 / 文档 / 测试数据清理 / 进段 8 的五条硬前置） |

**A~D 组端到端实测结果（2026-11-09）**：

- **A1** Flyway V700~V706 全部执行成功；业务表 43 → **53 张**（附录 A 55 张 − `invoice` − `share_poster`）。
- **A2** 敏感词引擎 14 个单测全绿；启动日志 `sensitive word loaded: version=1, count=50`。
- **A3 + C2** block 级投诉 → `{code:410006}` 且**原文不落库**；review 级投诉 → 正常提交并写入
  `content_review`（实测命中「包打赢」「不赢不收费」两词、`hitSnippet` 含原文）。
- **B1/B2/B3** 词库 50 条、模板 6 条、FAQ 7 条可查；审核队列 / 申诉 / 工单接口均 200。
- **A4/A5** 看板 4 接口与日志中心 2 接口均 200（`summary` 含环比，`trends` 缺日补 0）。
- **D1/D5** 公告 / 模板接口 200；发送链路按「解析目标 → 排除已发 → 分批 500 → 原子计数」实现。
- **PC 端** 6 页 + `seg7_admin_menu.sql`（2230~2235）已执行进 `faze_admin`，菜单可见。

**C5 + 尾巴补漏实测结果（2026-11-09，第二轮）**：

- **C5 四页**（APP 55/56/57/58）在真实 Chrome 390×844 下逐页验收：FAQ 7 条全部来自库、
  折叠答案按 `\n` 分行、客服电话从 `sys_config` 读且可点拨号、非服务时段自动切「已下班/留言」；
  57 真实点击提交 → 「已提交，单号 **TK20261109000007**」，58 → 「已提交，感谢你的反馈」。
- **V707**：`app_user` 22→23 列（`region VARCHAR(64)` + `idx_app_user_region`），
  `sys_config.service.phone` 占位号 → **正式号 `002-00854967`**。
- **`GET /pub/app/config`**：`{"onlineHours":"09:00-20:00","phone":"002-00854967"}`。
- **`GET /admin/dashboard/regions`**：返回一项「其他」（region 全 NULL，符合预期，不是空态）。
- ⚠️ **修掉一个静默 bug**：`AccountQueryServiceImpl.selectUserIds` 用 `status='normal'` 查库
  （库内只有 `active/blocked/cancelled`），导致公告「全量 / 按角色 / 指定用户」三种目标
  **一直发 0 人而接口照样 200**。修为 `active` 后 `estimate(all)` 由 `0` 变 `6`。见 §十一 第 18 条。
- 后端测试 **489/489**；前端 `tsc` / `eslint` / `check:rawtext` / `check:i18n` 全绿。

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线 v1.0」（2026-10-28 确立，此后一直有效）。
> 落款写的是**首次纳入基线的版本**，不随内容变更升级（本文件 §1.1）；当前基线版本见 `docs/BASELINE.md` 头部。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
