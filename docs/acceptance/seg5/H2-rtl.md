# H2 · 四语言与 RTL 核验记录（段 5）

> **这份文件的结论是「静态通过、运行时未验」，不是「RTL 已验收」。**
> 《第 5 段工作流》§一.1 出口第 5 条要求「`zh-Hans` + `ug-Arab-CN` key 齐全，RTL 下无布局错乱」。
> 段 5 实际只做到**前者的一半**（只有源语言齐全），后者**没有任何运行时证据**。
> 负责人 2026-11-08 裁决（《技术路线》§二十六 第 1 项）：翻译与 RTL 运行时核验**整体推段 8**。
> 所以本文件的作用是：把「已做的静态检查」和「没做的运行时核验」分开写清，防止下一段误以为这关已经过了。

---

## 一、结论

| 项 | 结果 | 性质 |
|---|---|---|
| 四语言目录同构（14 个 namespace × 4 语言） | ✅ 通过 | 脚本实测 |
| 缺译已登记（`bo` / `ug-Arab-CN` / `kk-Arab-CN` 各 1462 key） | ✅ 通过 | 脚本实测 |
| 占位符四语言一致 | ✅ 通过 | 脚本实测 |
| 样式里写死 `left` / `right` | ✅ **0 处** | 静态扫描 |
| `letterSpacing` 非 0 | ✅ **0 处**（全为 `0`） | 静态扫描 |
| 手动 `I18nManager` 反转整棵树 | ✅ 无；仅 `shared/i18n/rtl.ts` 集中管理 + `DirectionalIcon` 按 `isRTL` 翻转图标 | 静态扫描 + 逐处人工确认 |
| `start` / `end` 使用 | ✅ 39 处（RTL 正确写法） | 静态扫描 |
| **RTL 运行时截图（阿拉伯系实际渲染）** | ❌ **未做** | **缺 `ug-Arab-CN` 译文，切过去看到的仍是中文，截图证明不了任何事** |

---

## 二、i18n 门禁实测输出（2026-11-08 17:53）

命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-i18n.ps1
```

输出：

```text
[check-i18n] 源语言 zh-Hans 有 14 个 namespace 文件
[check-i18n] bo 缺 1462 个 key（已登记进 .i18n-pending.json）
[check-i18n] ug-Arab-CN 缺 1462 个 key（已登记进 .i18n-pending.json）
[check-i18n] kk-Arab-CN 缺 1462 个 key（已登记进 .i18n-pending.json）
[check-i18n] 已更新 .i18n-pending.json
[check-i18n] 1 四目录同构 2 缺译已登记 3 占位符一致 —— 全部通过
```

- `.i18n-pending.json` 由脚本生成，**不许手改**；段 8 收口时必须为空。
- 三个语言各 1462 key 的记录是「登记式缺译」机制在起作用，**不是「已翻译」**。

---

## 三、RTL 静态扫描明细

### 3.1 写死的 `left` / `right`：0 处

```powershell
Get-ChildItem -Recurse -Include '*.ts','*.tsx' features,shared,app |
  Select-String -Pattern '(^|[\s,{])(left|right)\s*:' |
  Where-Object { $_.Line -notmatch '//|/\*' }
```

结果：**0 条**。AGENTS.md §5 的禁令成立（ESLint 也在拦）。

### 3.2 `letterSpacing`：全部为 0

扫描 `features/`、`shared/`、`app/` 下所有 `.ts`/`.tsx`，`letterSpacing` 出现处**无一例外都是 `0`**
（如 `CaseDeliveryPage.tsx:595`、`CaseProgressPage.tsx:593/707`、`FilingDonePage.tsx:290`、
`MyCasesPage.tsx:333`、`IncomePage.tsx:416`）。阿拉伯系连写时非零字距会断字，这条已满足。

### 3.3 `I18nManager` 的 19 处：没有一处是「手动反转」

| 位置 | 用法 | 判定 |
|---|---|---|
| `shared/i18n/rtl.ts:38/39/65-79` | `allowRTL(true)` + `forceRTL(shouldBeRtl)` + 读 `isRTL` | ✅ **必须这样**：全仓唯一的 RTL 开关入口，页面不许自己调 |
| `shared/components/DirectionalIcon.tsx:41` | `I18nManager.isRTL ? { transform: [{ scaleX: -1 }] } : null` | ✅ 图标按方向翻转，不反转布局树 |
| `app/dev/rtl.tsx`（`RtlProbePage`） | 探针页读 `isRTL` 显示状态 | ✅ 开发工具 |
| 其余 15 处 | 全是**注释**，写明「不手工反转」的理由 | ✅ 文档 |

`flexDirection` / `justifyContent` 没有任何一处被 `I18nManager.isRTL` 条件改写——即不存在
「RN 自动镜像 + 手动再翻一遍 = 翻两遍等于没翻」的问题。

### 3.4 `start` / `end`：39 处

RTL 正确的方向属性写法，覆盖段 5 新增的案例列表、搜索、送达、进度、提现面板等页面。

---

## 四、为什么没有运行时截图（这一节是这份文件最重要的部分）

**根因：没有 `ug-Arab-CN` / `kk-Arab-CN` 的译文。**

RTL 是「方向」，它由 `I18nManager.forceRTL(true)` 触发；但要让截图有意义，
必须同时有**阿拉伯字母的文字内容**。当前三语言各缺 1462 key，`check-i18n` 的降级策略是
回退到 `zh-Hans`——也就是说：

> 强制切到 RTL 后，界面方向确实镜像了，但**上面的字还是简体中文**。

这种截图只能证明「布局镜像了」，**证明不了**「维吾尔文长词在 375 宽的屏幕上不换行错位 /
不超出容器 / 不压住图标」——而那才是出口第 5 条真正要防的事。
拿一张中文内容 + RTL 方向的截图去宣称「RTL 无布局错乱」属于**用弱证据冒充强证据**，
所以本文件不这么做。

**另一种可选但没做的做法**：用维吾尔文**假文**（从其它已译仓库借一段维文或造 20 个词）临时填充
再截图。没做的原因：

1. 假文长度分布与真实文案不同，测出来的换行位置没有代表性；
2. 段 8 会全量翻译，届时一次性用真译文核验，成本更低；
3. 段 4 收口时已按同一口径验收（当时各语言缺 1074 key，同样是静态检查）。

**这条记录的核心意思是：RTL 运行时核验是「未验」，不是「通过」。**

---

## 五、段 8 收口时必须补的（缺一项都不算收口）

1. `.i18n-pending.json` 各语言 key 数为 **0**（`check-i18n.ps1` 全绿且不生成待办）。
2. 用**真实维吾尔文**在 RTL 下截图：至少覆盖段 5 的案例列表 / 案例详情 / 全局搜索 /
   文书送达 / 收入提现 / 服务设置 6 页，并逐页检查：
   - 长词不超出容器、不被截断
   - 数字与金额不被 BiDi 打乱（走 `LtrText`）
   - 图标与文字不重叠
   - TabBar 顺序由 React Navigation 自动镜像，**不手工调**
3. `letterSpacing` 仍为 0（新页面不许破这条）。
4. 截图存到 `docs/acceptance/seg8/`，并在本文件追加一行「段 8 已核验 + 截图路径」。

---

## 六、与工作流原文的出入

| 工作流要求 | 实际 | 处理 |
|---|---|---|
| §5.5 证据文件 `H2-rtl.md` | 本文件 | ✅ 已补（但内容如实标注「运行时未验」） |
| §一.1 出口第 5 条「`zh-Hans` + `ug-Arab-CN` key 齐全」 | 只有 `zh-Hans` 齐全 | 负责人 11-08 裁决推段 8，见《技术路线》§二十六 第 1 项 |
| §三 H2「五 namespace 补 `zh-Hans` + `ug-Arab-CN`」 | 只补了 `zh-Hans` | 同上（段 5 偏差清单第 8 条） |

---

> 记录人：施工方（AI）｜时间：2026-11-08 17:53｜依据：实跑 `check-i18n.ps1` 输出 + 全仓静态扫描
