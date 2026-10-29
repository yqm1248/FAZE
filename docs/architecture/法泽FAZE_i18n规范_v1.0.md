# 法泽 FAZE · i18n 规范（V1.0）

> **用途**：四语言（`zh-Hans` / `bo` / `ug-Arab-CN` / `kk-Arab-CN`）的 key 命名、文件组织、缺译降级、完整性与 RTL 约定的唯一依据。
> **定稿日期**：2026-10-30
> **不重复的内容**：字体加载、RTL 切换流程、混排隔离、日期格式化的**技术实现**已在技术路线 §13，本文件只补**约定层**并给出两者的分工。
> **与《错误码规范》的关系**：78 个 `msgKey` 的**码表**在《错误码规范》§3；本文件管它们**落在哪个语言文件、怎么组织**。

---

# 一、先看问题：机制层写得深，约定层是空的

## 1.1 实测结论

技术路线 §13 有 122 行，把**技术实现**写得相当细（这是优点）：

| 已写好的（技术路线 §13） | 说明 |
|---|---|
| §13.1 语言代码与字体 | BCP 47 代码、方向、字体选型 |
| §13.3 字体加载 | 按 Unicode 区段切 run、藏文不做子集化、阿拉伯系 `letterSpacing=0`、`fontFamily` 在 Android/iOS 写法不同 |
| §13.3.1 `<LocalizedText>` 切分 | 三个区段的边界值 |
| §13.4 RTL 切换 | `forceRTL` 必须在 reload 之前、路由恢复 |
| §13.5 混排 | `LtrText` + `U+2066…U+2069` |
| §13.7 日期 | 不用 `Intl`（Hermes locale 数据缺失会静默回退英文） |

**但「约定层」系统性缺失**。一轮 24 条的 i18n 审计结论是：**只有 1 条 defined、21 条 partial、2 条 missing**。

| # | 缺口 | 现状 |
|---|---|---|
| 1 | **key 命名规则** | 全文只有**一个示例** `auth.login.phonePlaceholder`（§13.2 规则 1）。缺：段数语义、大小写、复数、占位符、命名冲突 |
| 2 | **缺译降级策略** | 全文无 `fallbackLng`、无「缺 key 时显示什么」 |
| 3 | **完整性与门禁** | 无 CI 检查。而《错误码规范》§4.3 已要求「缺任一语言的 key，构建期就该失败」——**这条要求没有任何落地机制** |
| 4 | **四语言目录是否同构** | §13.2 只画了 `zh-Hans` 的 5 个 json，其余三个目录留空，没说是否必须同名 |
| 5 | **namespace 与业务域的映射** | 前端有 13 个 feature 域，语言文件只有 5 个 json，`auth`/`trade`/`workbench` 放哪？ |
| 6 | **78 个 `error.*` 归哪个文件** | 完全没提 |
| 7 | **RN 侧镜像实现约定** | RN 没有 `[dir="rtl"]` 选择器，镜像逻辑放组件内部还是页面层没定 |
| 8 | **服务端发出的文案** | 通知模板、推送标题用哪种语言？没定 |

## 1.2 一个必须先裁决的口径冲突

| 出处 | 说什么 | 冲突点 |
|---|---|---|
| 《错误码规范》§4.1 | 「收到 `HTTP 200 + code≠200` → 按 `msgKey` 走 `t()` 展示」 | 要求**所有**错误都有 4 语言 |
| 技术路线 §5.8.1（内容层） | 「译文未 published 时**回退显示中文**并在 UI 标注「该语言译文未完成」」 | 内容层**允许回退中文** |

两者不矛盾——**它们说的不是同一层**。本文件 §5 把这条边界划清。

---

# 二、语言与代码

## 2.1 四语言（引用技术路线 §13.1，不重复）

| 语言 | 代码 | 方向 | 备注 |
|---|---|---|---|
| 简体中文 | `zh-Hans` | LTR | **基准语言**（见 §5.1） |
| 藏文 | `bo` | LTR | |
| 维吾尔文 | `ug-Arab-CN` | RTL | |
| 哈萨克文（中国境内阿拉伯字母） | `kk-Arab-CN` | RTL | |

## 2.2 语言代码的硬规则

| 规则 | 说明 |
|---|---|
| 一律用 BCP 47 完整标签 | 禁止 `ug`、`kk`、`zh`、`cn` 这类简写。`ug` 与 `ug-Arab-CN` 在 ICU 里可能解析到不同 locale |
| **禁止** `zh-CN`、`zh_CN` | 统一 `zh-Hans`。`zh-CN` 隐含 `Hans` 但不同库的默认值不一致 |
| 下划线写法 `ug_Arab_CN` **只在 Java `Locale` 内部出现** | 对外（文件名、JSON key、URL 参数、数据库字段）一律用连字符 |
| 大小写敏感 | `ug-Arab-CN` 的 `A` 与 `CN` 必须大写，`kk-Arab-CN` 同理。写成 `ug-arab-cn` 会导致 `Intl`/ICU 匹配失败 |

## 2.3 语言状态的唯一来源

```text
用户选择 → SecureStore（持久化）
   ↓
App 启动 → 读 SecureStore → 无则读系统语言 → 无匹配则 zh-Hans
   ↓
I18nProvider（React Context）→ 全树 t()
   ↓
方向变化时走技术路线 §13.4 的 forceRTL + reloadAsync
```

**三条规则**：
1. **语言只有一个来源**（`I18nProvider`）。页面不许自己读 SecureStore 或 `NativeModules`。
2. **不跟随系统语言实时切换**。系统语言只在**首次启动**时作为初始值；用户选了就固定——因为切换方向要重启 App，跟系统走会导致「用户没操作，App 突然重启」。
3. **PC 后台不参与**：RuoYi 后台只有中文（`AGENTS.md` 决策）。

---

# 三、key 命名规范（本文件最大的一节）

## 3.1 三段式：`<域>.<场景>.<元素>`

```text
auth.login.phonePlaceholder     ✓
lawyer.list.emptyText           ✓
trade.order.payButton           ✓
```

| 段 | 内容 | 允许的取值 |
|---|---|---|
| 第 1 段 **域** | 与前端 feature 目录名一致 | 见 §4.3 的 namespace 表 |
| 第 2 段 **场景** | 页面或功能块，用**页面语义**而非路由名 | `login` / `list` / `detail` / `form` / `empty` |
| 第 3 段 **元素** | 具体元素，用**语义**而非控件类型 | `title` / `subtitle` / `button` / `placeholder` / `hint` / `error` |

**禁止第四段**。更深就说明拆得不对：

```text
auth.login.form.phone.placeholder    ✗ 四段
auth.login.phonePlaceholder          ✓ 三段（元素段用组合词）
```

## 3.2 大小写：全域 camelCase

| 段 | 大小写 | 例 |
|---|---|---|
| 三段全部 | **camelCase** | `lawyerApplication.audit.statusPending` |

**裁决：不用 `kebab-case`。** 技术上两种都能用，但《错误码规范》的 `msgKey` 是 `error.auth.token-expired`（kebab），若普通文案也用 kebab，两套混在一起看不出边界。**分工是**：普通文案 `camelCase`、`msgKey` 保持 `kebab-case`（它有自己的独立 namespace `error`，见 §4.4）。

## 3.3 占位符

```json
{
  "lawyer.list.resultCount": "共 {{count}} 位律师",
  "trade.order.timeoutHint": "请在 {{minutes}} 分钟内完成支付",
  "common.unit.yuan": "{{amount}} 元"
}
```

| 规则 | 说明 |
|---|---|
| 用 `{{name}}` 双花括号 | 不用 `${name}`（与模板字符串冲突）、不用 `{0}`（位置参数在语序不同的语言里会错位） |
| 占位符名用 **camelCase** | `{{phoneNumber}}` 不是 `{{phone_number}}` |
| **必须用命名占位符，不用位置参数** | 藏文/维吾尔文的语序与汉语不同，位置参数必然错 |
| 占位符**不参与翻译** | 译者只翻文字，`{{count}}` 原样保留。CI 校验「所有语言的占位符集合一致」 |

## 3.4 复数

四语言里只有**中文没有复数变化**，其余三种都有。但**不要因此只写中文形式**：

```json
// zh-Hans/common.json
"lawyer.list.resultCount": "共 {{count}} 位律师"

// ug-Arab-CN/common.json  —  译者需要区分单复数时给两个 key
"lawyer.list.resultCount_one": "{{count}} ئادۋوكات",
"lawyer.list.resultCount_other": "{{count}} ئادۋوكات"
```

**规则**：源语言（`zh-Hans`）**不写复数后缀**；目标语言**可以**增加 `_one` / `_other` 后缀。CI 校验时**允许目标语言比源语言多 key**，不允许少（见 §12.2）。

## 3.5 禁止的写法

| 禁止 | 为什么 |
|---|---|
| 在代码里拼 key | `t('auth.login.' + field)` —— 静态扫描找不到，且拼出来的 key 一定漏译 |
| 用中文当 key | `t('登录')` —— 改文案等于改 key |
| key 里带序号 | `common.button1` / `common.button2` —— 看不出语义 |
| 一个 key 用于两处不同语义 | `common.ok` 同时做「确定」和「完成」——某种语言里这两个词不同 |
| 把整句拼起来 | `t('a') + t('b')` —— 语序不同的语言里必然错 |

## 3.6 长文本与富文本

| 类型 | 做法 |
|---|---|
| 纯文本 | 普通 key |
| 带样式（加粗某词） | **不用 HTML**。用 `t()` 返回分段数组，或拆成 `xxxPrefix` / `xxxEmphasis` / `xxxSuffix` |
| 多段落 | 一个 key 里用 `\n`，**不要**拆成多个 key（段落顺序可能因语言不同） |

---

# 四、文件组织

## 4.1 目录结构

```text
apps/mobile-app/shared/i18n/
├─ index.ts                  i18n 初始化、t() 入口
├─ datetime.ts               日期格式化（技术路线 §13.7）
├─ resources/
│  ├─ zh-Hans/
│  │   ├─ common.json
│  │   ├─ auth.json
│  │   ├─ home.json
│  │   ├─ lawyer.json
│  │   ├─ conversation.json
│  │   ├─ trade.json
│  │   ├─ document.json
│  │   ├─ case.json
│  │   ├─ content.json
│  │   ├─ ai.json
│  │   ├─ profile.json
│  │   ├─ ops.json
│  │   ├─ workbench.json
│  │   └─ error.json
│  ├─ bo/                     ← 与 zh-Hans 完全同构（同名同数量）
│  ├─ ug-Arab-CN/
│  └─ kk-Arab-CN/
```

## 4.2 四语言目录**必须同构**

**硬规则**：四个语言目录下的**文件名集合必须完全一致**。不允许「`bo` 目录少一个 `trade.json`」。

理由：若允许缺文件，代码里 `useTranslation('trade')` 在 `bo` 下会静默返回 key 本身——**表现为界面上出现 `trade.order.payButton` 这种原始 key**，而这类 bug 在中文环境永远测不出来。

**唯一的例外**：文件内容允许为空对象 `{}`（表示「这一批还没翻」），但**文件必须存在**。

## 4.3 namespace 与业务域的映射

**这是原文档完全缺失的部分**。前端有 13 个 feature 域，语言文件按业务域切，映射表如下：

| namespace（文件名） | 对应 feature 目录 | 内容 |
|---|---|---|
| `common` | —（全局共用） | 按钮、单位、通用提示、空态、加载态 |
| `auth` | `features/auth/` | 登录、注册、找回密码、短信验证码 |
| `home` | `features/home/` | 首页、搜索入口 |
| `lawyer` | `features/lawyer/` | 律师列表、详情、关注 |
| `conversation` | `features/message/` | 会话、消息、聊天 |
| `trade` | `features/payment/` + `features/order/` | 订单、支付、退款、余额、流水 |
| `document` | `features/document/` | 文书模板、生成、签名 |
| `case` | `features/case/` | 案件、进度、送达 |
| `content` | `features/article/` | 文章、案例库、法条 |
| `ai` | `features/ai/` | AI 问答 |
| `profile` | `features/profile/` + `features/settings/` | 我的、设置、实名、注销 |
| `ops` | `features/support/` | 投诉、反馈、FAQ、通知中心 |
| `workbench` | `features/workbench/` | 律师工作台 |
| `error` | —（全局） | **78 个 `msgKey`**（见 §4.4） |

**规则**：页面用哪个 namespace，**由它所属的 feature 决定**，不由路由决定。`features/lawyer/pages/LawyerDetailPage` 用 `lawyer`，不用「detail」。

**一处例外**：`common.json` 的准入标准与 `shared/components/` 相同——**被 ≥2 个 feature 使用且不含业务词汇**。否则放回各自 namespace。

## 4.4 `error.json` 的 key 用完整 msgKey（特殊规则）

```json
// resources/zh-Hans/error.json
{
  "error.auth.login-failed": "手机号或密码错误",
  "error.auth.token-expired": "登录已过期，请重新登录",
  "error.trade.balance-insufficient": "账户余额不足"
}
```

**为什么这里不用 §3.1 的三段式**：因为《错误码规范》§4.3 已把 `msgKey` 定义成 `error.<域>.<场景>` 的完整字符串，服务端返回的就是这个值。**客户端要能直接 `t(response.msgKey)`**，不需要拼前缀。

```ts
// 唯一正确的用法
t(error.msgKey)        // error.msgKey = "error.auth.token-expired"
```

**禁止**：在客户端把 `msgKey` 拆开再拼（`t('error.' + domain + '.' + scene)`）——拼接出来的 key 静态扫描不到。

## 4.5 单文件规模

| 文件 | 上限 | 超了怎么办 |
|---|---|---|
| 单个 namespace JSON | **300 行** | 拆子 namespace（`trade.json` → `trade-order.json` + `trade-wallet.json`），但**必须同步更新 §4.3 的映射表** |
| 单个 key 的译文长度 | 无硬限 | 但 `common` 里的按钮文案 ≤ 12 字（中文），否则按钮会换行 |

---

# 五、缺译与降级（原文档完全空白）

## 5.1 降级链

```text
请求的 lang
   ↓ 缺
zh-Hans（基准语言）
   ↓ 缺
key 本身（可见的红色标识，仅 dev）
```

**裁决：基准语言是 `zh-Hans`。** 理由：`AGENTS.md` §7 要求「四语言文案已补齐（至少 `zh-Hans` + `ug-Arab-CN`）」，但开发期不可能四种同步齐——`zh-Hans` 是团队母语、是产品定义语言，它缺说明是**漏写**，不是「没翻译」。

## 5.2 缺 key 时显示什么

| 环境 | 显示 | 为什么 |
|---|---|---|
| **development** | `⟦auth.login.title⟧`（带方括号和原 key，红色） | 必须刺眼。开发期看到它就立刻补 |
| **production** | 该 namespace 的 `common.fallbackText`（「内容加载中」） | 不能把原始 key 暴露给用户 |

**禁止**：生产环境显示原始 key。它既难看出是 bug，又会让用户看到技术字符串。

## 5.3 与「内容层回退中文」的口径统一

§1.2 提到的冲突，在这里划清：

| 层 | 例子 | 缺译时 | 依据 |
|---|---|---|---|
| **UI 层**（`t()` 的文案） | 按钮、标题、错误提示 | 降级到 `zh-Hans`，**不标注** | 按钮显示中文是「能用」，显示 `⟦key⟧` 是「坏」 |
| **内容层**（数据库里的文章/案例/法条） | 文章正文、案例详情 | 回退显示中文，**并在 UI 明确标注「该语言译文未完成」** | 内容层是「大段文本」，用户需要知道这不是目标语言——技术路线 §5.8.1 的口径 |

**一句话**：**UI 文案静默降级，内容文本显式标注。** 因为 UI 文案回退后不影响理解，内容文本回退后用户会以为「这篇就是中文写的」。

## 5.4 译文过期（内容层）

沿用技术路线 §13.6：`translated_from < source_updated_at` 视为译文过期，后台标红。

**补充规则**：过期的译文**仍然展示**（不是回退中文），但在 PC A-17「待翻译/待更新队列」里可见。理由：过期译文虽然可能旧，但比中文对目标用户更可读。

---

# 六、翻译的生产流程

## 6.1 谁翻什么

| 内容 | 方式 | 校对 |
|---|---|---|
| UI 文案 | 人工翻译 | 母语者校对 |
| 每日学法 | 机翻初稿 + 人工校对 | 必须 |
| 案例、法条 | **人工翻译，法条原文不机翻** | 必须 |
| AI 回答 | **不预翻译**，按当前语言生成 | — |

依据：技术路线 §13.6。

## 6.2 术语表

`i18n_glossary` 表是**唯一来源**（技术路线 §13.8），AI / 模板翻译 / 后台录入三方共用。

**规则**：
- 新增术语必须先入库，再用；
- 术语表里的词，翻译时**不得意译**（如「法泽」不翻成「Fa Ze」而保持品牌名）；
- CI 检查：`error.json` 与 `common.json` 里出现的术语，必须与 `i18n_glossary` 一致（人工评审，不做自动）。

---

# 七、字体与排版

> **实现细节见技术路线 §13.3 / §13.3.1**，本节只补**使用约定**。

## 7.1 `<LocalizedText>` 的强制使用

| 规则 | 说明 |
|---|---|
| **所有**用户可见文本必须用 `<LocalizedText>` | 不许裸用 `<Text>`。ESLint 规则 `no-raw-text` 拦住（见 §12.3） |
| 只有 `<LocalizedText>` 内部可以切 run | 页面层不许自己按 Unicode 区段切 |
| `numberOfLines` 必须设置 | 阿拉伯系文字在折行处会断开连写，不设行数上限会排出难看的结果 |

## 7.2 排版约束（技术路线 §13.3 已定，这里列成检查项）

| 项 | 值 | 违反后果 |
|---|---|---|
| 阿拉伯系 `letterSpacing` | **必须 0** | 非零导致字母断连 |
| 藏文 `lineHeight` | **≥ 1.6 × fontSize** | 字形上下被裁 |
| 混排行高 | 取两字体**最大值** | 小行高会让藏文叠字 |
| 藏文字体 | **不做子集化** | GSUB 引用复合字形，裁错即崩排 |

---

# 八、RTL

> **切换流程见技术路线 §13.4**（`forceRTL` 必须在 reload 之前）。本节补**组件层的镜像约定**——这是原文档缺的第 7 条（RN 没有 `[dir="rtl"]` 选择器）。

## 8.1 布局镜像：用 `start`/`end`，不用 `isRTL` 分支

```tsx
// ✅ 正确：RN 的 flexbox 在 isRTL 为真时自动把 start 解释为 right
const styles = StyleSheet.create({
  row: { flexDirection: 'row', paddingStart: 16, paddingEnd: 12 },
  icon: { marginEnd: 8 },
});

// ❌ 错误：手动判断 isRTL 做三元分支
const row = { flexDirection: I18nManager.isRTL ? 'row-reverse' : 'row' };  // 会和 RN 的自动镜像叠加，变成不镜像
```

**规则**：
1. `paddingStart/End`、`marginStart/End` 替代 `Left/Right`。**禁止** `paddingLeft` 等（`AGENTS.md` §5 已禁）。
2. **禁止**用 `I18nManager.isRTL` 手动反转 `flexDirection` —— RN 的 `start/end` 已经自动处理，手动再翻一次会变成"翻两遍等于没翻"。
3. `transform: scaleX(-1)` **只用于必须镜像的图标**（见 §8.2），不用于布局。

## 8.2 图标镜像白名单

不是所有图标都该镜像——镜像「公司 logo」是错的。**白名单制**：

| 必须镜像 | 不镜像 |
|---|---|
| 返回箭头、前进箭头 | 品牌 logo、用户头像 |
| 列表项右箭头 `>` | 播放/暂停按钮（媒体语义与方向无关） |
| 进度条、滑动条方向 | 对勾、叉、加减号 |
| 「上一步/下一步」 | 时钟、日历图标 |

**实现**：白名单里的图标用 `<DirectionalIcon>` 包装，内部按 `I18nManager.isRTL` 做 `scaleX(-1)`；其余图标用普通 `<Icon>`。

## 8.3 混排隔离

法条编号、案号、金额、电话号码——**这些在 RTL 语言里也不能被反转**，用 `<LtrText>` 包裹（跑 `U+2066…U+2069`）。依据技术路线 §13.5。

**新增一条**：**用户输入的 UGC**（评价内容、聊天消息）如果包含数字串，同样要隔离——否则「500 元」在维吾尔文里会显示成「元 500」。

---

# 九、日期、数字、货币

> **实现见技术路线 §13.7**（不用 `Intl`，自建格式化表）。本节补**调用约定**。

## 9.1 三个统一入口

```ts
// 唯一允许的三种格式化调用
formatDate(date, lang)          // "2026年10月30日" / 藏文 / ...
formatRelativeTime(date, lang)  // "3 天前"
formatAmount(cents, lang)       // "¥100.00"（金额始终从分转换）
```

**禁止**：
- 页面里写 `new Date().toLocaleDateString()`（会走 `Intl`，locale 数据缺失时静默回退英文）；
- 页面里写 `/100` 做金额转换（用 `formatAmount`）；
- 手写「刚刚 / 几分钟前」的判断（用 `formatRelativeTime`）。

## 9.2 数字

| 项 | 规则 |
|---|---|
| 数字字形 | 一律 **0-9 阿拉伯数字**，不本地化为阿拉伯-印度数字（技术路线 §13.5） |
| 千分位 | 不显示（中文习惯），法条编号 `第1200条` 不加逗号 |
| 百分比 | `15%` 不空格 |

---

# 十、服务端的多语言

## 10.1 错误 `msgKey`（78 个）

服务端**不返回多语言文案**，只返回 `msgKey`（《错误码规范》§4.2）。

| 服务端返回 | 客户端做 |
|---|---|
| `R.fail(ErrorCode.TRADE_BALANCE_INSUFFICIENT)` | `t('error.trade.balance-insufficient')` |

**服务端不需要 4 语言资源文件**——这是本规范与《错误码规范》的分工：码表在服务端，译文在客户端。

## 10.2 通知模板与推送

**这是原文档缺的第 8 条**。站内通知、App 推送的标题与正文由**服务端**生成，必须决定用哪种语言。

| 场景 | 用哪种语言 | 依据 |
|---|---|---|
| 站内通知（`notification` 表） | **收件人的 `preferred_lang`**（存 `app_user` 表） | 用户自己的语言 |
| 离线推送 | 同上 | |
| 管理员发的公告 | **发送时选择语言**（可多选，按用户 `preferred_lang` 分发） | 公告可能面向多语言人群 |

**规则**：
1. `app_user` 增加 `preferred_lang` 字段（**这是《数据表规范》附录 A 里没有的字段，需要补**，见 §十三 待办）。
2. `notify_template` 表按 `(scene, lang)` 唯一，一个场景四种语言各一行。
3. **推送的 title/body 不用 `t()`**——它是服务端渲染好的最终文本，客户端直接展示。

## 10.3 AI 回答

**不预翻译，按当前语言生成**（技术路线 §13.6）。`POST /app/ai/sessions` 必须带 `Accept-Language`，服务端据此选 prompt 里的语言指令。

**降级**：若豆包对某语言支持差（藏文、哈萨克文），**回退为中文回答 + UI 标注「该问题当前仅提供中文回答」**——不能返回乱码。

## 10.4 AI 生成内容标识（合规）

《AI 生成合成内容标识办法》要求 AI 生成内容有显式标识。**标识文本本身也要 4 语言**：

```json
// error.json 同级的 common.json
"common.aiGeneratedLabel": "本内容由 AI 生成，仅供参考"
```

**注意**：这条**不是**普通 UI 文案——它是合规要求，**四种语言必须全部到位**，不允许降级。列入 §12 的"必须完备"清单。

---

# 十一、完整性门禁（承接手段）

> 遵循《后端代码规范》§十四 的原则：**没有承接手段的规则不写**。

## 11.1 CI 检查（第 0 段就要建）

| # | 检查 | 手段 | 失败后果 |
|---|---|---|---|
| 1 | **四语言目录同构** | 脚本比对四个目录的文件名集合 | 构建失败 |
| 2 | **key 集合一致**：目标语言**不得缺**源语言的 key | 脚本比对 `zh-Hans` 与其余三者的 key 集合（`ug-Arab-CN` 允许缺 = 降级到中文，但**必须在白名单里登记**） | 构建失败（未登记的白名单外缺 key） |
| 3 | **占位符一致** | 每个 key 的 `{{...}}` 集合在四语言间必须相同 | 构建失败 |
| 4 | **术语一致** | `i18n_glossary` 里的术语在译文中出现即为投诉项 | warning |
| 5 | **硬编码扫描** | ESLint 规则 `no-raw-text`：JSX 里裸字符串字面量报错 | 构建失败 |
| 6 | **无拼接 key** | ESLint 规则 `no-dynamic-i18n-key`：`t()` 的参数必须是字面量或常量 | 构建失败 |

## 11.2 为什么 `ug-Arab-CN` 允许"登记式缺 key"

`AGENTS.md` §7 的完成定义是「四语言文案已补齐（**至少 `zh-Hans` + `ug-Arab-CN`**）」。这意味着：

- 每个页面**至少**要 `zh-Hans` 和 `ug-Arab-CN` 齐；
- `bo` / `kk-Arab-CN` 允许阶段性缺，但**必须登记**（`resources/.i18n-pending.json`），否则 CI 失败。

```json
// resources/.i18n-pending.json —— 缺译登记表，段 8 收口时必须清空
{
  "bo": ["trade.order.payButton", "trade.order.refundHint"],
  "kk-Arab-CN": ["trade.order.payButton"]
}
```

**段 8 的出口条件之一**：这个文件必须为空。

## 11.3 dev 环境的缺 key 高亮

开发期用一个 wrapper 把所有 `t()` 结果检查一遍，缺 key 时返回 `⟦key⟧` 并输出到控制台。**这不是生产逻辑**，用 `__DEV__` 门控。

---

# 十二、检查清单（新增页面/新增文案时逐条过）

1. key 是**三段式** `<域>.<场景>.<元素>`？没有四段？
2. 第 1 段是 §4.3 映射表里的 namespace？
3. 全部 camelCase？没有 kebab / 下划线？
4. 占位符用 `{{camelCase}}`？不是 `${}` 或 `{0}`？
5. 四语言文件里都加了吗？（`bo` / `kk` 可以登记待译，但 `zh-Hans` + `ug-Arab-CN` 必须齐）
6. 用的是 `<LocalizedText>` 不是裸 `<Text>`？
7. 有 `numberOfLines`？阿拉伯系文字的折行处理了吗？
8. 阿拉伯系文本的 `letterSpacing` 是 0？
9. 藏文的 `lineHeight ≥ 1.6 × fontSize`？
10. 日期/金额走 `formatDate` / `formatAmount`，没手写？
11. 数字串（法条编号、金额、电话）用 `<LtrText>` 隔离了？
12. CI 六项检查都过了？

---

# 十三、本规范带来的连带改动（待办）

| # | 事项 | 影响 |
|---|---|---|
| 1 | **`app_user` 需要增加 `preferred_lang` 字段** | 《数据表规范》附录 A 的 49 张表里没有这个字段；不改则服务端不知道该发哪种语言的通知 |
| 2 | **`notify_template` 需要按 `(scene, lang)` 唯一** | 数据表规范里该表的唯一键未含 `lang` |
| 3 | 技术路线 §13.2 的 5 个 json → 本文件的 **14 个 namespace** | 技术路线 §13.2 需回写指向本文件 |
| 4 | ESLint 两条规则（`no-raw-text`、`no-dynamic-i18n-key`）未实现 | 第 0 段的 lint 规则集里做 |
| 5 | CI 六项检查未实现 | 第 0 段 CI 施工 |

---

# 附录 A：key 命名速查

| 场景 | 正确 | 错误 |
|---|---|---|
| 按钮 | `common.button.confirm` | `common.confirmBtn` |
| 页面标题 | `lawyer.detail.title` | `lawyerDetailTitle` |
| 输入占位 | `auth.login.phonePlaceholder` | `auth.login.placeholder1` |
| 空态 | `trade.list.emptyText` | `trade.empty` |
| 错误提示（业务） | `error.trade.balance-insufficient` | `trade.insufficient` |
| 错误提示（表单校验） | `common.form.required` | `required` |
| 单位 | `common.unit.yuan` | `yuan` |
| 确认弹窗 | `common.dialog.deleteConfirm` | `common.delete` |

# 附录 B：24 条审计问题的处置对照

| 审计发现 | 本文件处置 |
|---|---|
| key 命名只有 1 个示例、缺段数/大小写规则 | §3 全部补 |
| 缺降级策略 | §5.1 降级链 |
| 缺完整性门禁 | §11 六项 CI 检查 |
| 四语言目录是否同构未定 | §4.2 强制同构 |
| namespace 与 13 个域的映射缺失 | §4.3 映射表（14 个 namespace） |
| 78 个 `error.*` 归属未定 | §4.4 独立 `error.json` + 完整 msgKey |
| RN 无 `[dir=rtl]`，镜像实现未定 | §8.1 用 `start/end`，禁手动 `isRTL` |
| 服务端通知用哪种语言未定 | §10.2 `preferred_lang` |
| 文本扩展（长 30%） | §7.2 按钮 ≤12 字 + `numberOfLines` |
| AI 内容标识本地化 | §10.4，四语言必须完备 |

---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线」（2026-10-30 新增）。
> 基线与文件指纹见 `docs/BASELINE.md`；本文件新增时按 `docs/BASELINE.md` §4 的「四件事」办理。
