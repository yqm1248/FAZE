# 第 3 段 · E1「AI 生成内容显式标识」验收证据

> 施工日期：2026-11-07 · 分支 `UI设计` · 施工成员：`fe-ai`
> 对应工作流：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` 第 **912–953** 行（E1）、锚点 `3-A5`
> 对应规范：`法泽FAZE_i18n规范_v1.0.md` §5（缺译降级）、§10.4（AI 标识文案）、§11.2（登记式缺译）
> 结论：**组件 + 四套可断言钩子就绪；`zh-Hans` 文案落地；`bo` / `ug-Arab-CN` / `kk-Arab-CN`
> 按登记式缺译（脚本生成，无 reason 字段）；真机截图与「挂载后每条回答都有标识」属挂账，
> 由 lead 在 I 组用真机/模拟器验。** 本节所有命令输出均为本机真实执行结果（Windows / PowerShell 5.1 / Node v24.16.0）。
>
> **2026-11-07 追记（E1 选项 A，负责人已拍板）**：标识颜色由 `color.text.muted`（`#999999`，
> 2.85:1）改为 `color.text.secondary`（`#666666`，5.74:1），满足 WCAG AA 4.5:1；
> 本文件第二、六、七节的行号与结论已同步更新（组件现 91 行）。

---

## 一、交付物（3 个代码/资源文件 + 本证据文件）

| 文件 | 状态 | 说明 |
|---|---|---|
| `apps/mobile-app/shared/components/AiGeneratedLabel.tsx` | **新建**（91 行） | 显式标识组件，文案固定走 `common.aiGeneratedLabel`；颜色按选项 A 用 `color.text.secondary` |
| `apps/mobile-app/shared/i18n/resources/zh-Hans/common.json` | 修改（+1 行） | 加 `aiGeneratedLabel` = 「本内容由 AI 生成，仅供参考」 |
| `apps/mobile-app/shared/i18n/resources/.i18n-pending.json` | 修改（+3 行） | 由 `scripts/check-i18n.ps1` **自动生成**，非手改；三种目标语言各登记 1 条 |
| `docs/acceptance/seg3/E1-label.md` | 新建 | 本文件 |

`git status`（本任务范围，真实输出）：

```text
$ git status --porcelain apps/mobile-app/shared/components apps/mobile-app/shared/i18n docs/acceptance/seg3
 M apps/mobile-app/shared/i18n/resources/.i18n-pending.json
 M apps/mobile-app/shared/i18n/resources/zh-Hans/common.json
?? apps/mobile-app/shared/components/AiGeneratedLabel.tsx
?? docs/acceptance/seg3/
```

**未改动**（用 `git status --porcelain` 核对为空）：`ug-Arab-CN/`、`bo/`、`kk-Arab-CN/` 下的 json；
未碰 `app/`、`app/(tabs)/`、`features/`、`shared/api/`、`shared/theme/`、`services/**`、
`package.json`、`docs/BASELINE.md` / `README.md` / `CHANGELOG.md`、`design/**`。
本段**没有新建任何路由**，也**没有改 AI 页面**（F 组的范围）。

---

## 二、组件与四套可断言钩子（源码行号清单）

匹配命令与真实输出（`Select-String` 等价于按行 `grep`）：

```text
$ Select-String -Path apps\mobile-app\shared\components\AiGeneratedLabel.tsx -Pattern 'testID="|nativeID="|accessibilityRole="|accessibilityLabel=\{label\}|const label = t\(|marginTop: spacing\.xs|\.\.\.typography\.micro|color: color\.text\.'

LineNumber Line
---------- ----
        65   const label = t('common.aiGeneratedLabel');
        70         testID="ai-generated-label"
        71         nativeID="ai-generated-label"
        72         accessibilityRole="text"
        73         accessibilityLabel={label}
        85     marginTop: spacing.xs,
        88     ...typography.micro,
        89     color: color.text.secondary,
```

| 钩子 / 要求 | 源码行号 | 值 | 给谁用 |
|---|---|---|---|
| `testID` | **L70** | `ai-generated-label` | E2E 按 testID 查元素（RN 里同时映射到 accessibility identifier） |
| `nativeID` | **L71** | `ai-generated-label` | Android 原生 `resource-id`，E2E 第三套兜底 |
| `accessibilityRole` | **L72** | `text` | 保证被无障碍树识别为可读文本 |
| `accessibilityLabel` | **L73** | `{label}`（即 `t('common.aiGeneratedLabel')`，见 L65） | 无障碍朗读 + 只认该属性的部分 E2E 工具 |
| 文案 key | **L65** | `'common.aiGeneratedLabel'`（字面量，静态可扫，未拼接） | 静态扫描「文案是否走 `t()`」 |
| 字号 | L88 | `typography.micro`（其 `fontSize` = `fontSize.micro` = 11dp） | 见第七节第 2 条「令牌缺口」 |
| 颜色 | L89 | `color.text.secondary`（`#666666`，白底对比度 **5.74:1**，WCAG AA 达标） | E1 选项 A（负责人 2026-11 拍板） |
| 上间距 | L85 | `marginTop: spacing.xs`（6dp） | 与气泡留出间距 |

其它约束（静态核对）：

- **没有裸 `<Text>`**：组件内文本只经 `<LocalizedText>`（另有布局用 `<View>`），`check-raw-text` 全仓命中 0（见第五节）。
- **没有颜色/字号字面量**：无 `#RRGGBB` / `rgba(`；无 `left` / `right`（ESLint 规则 + 第五节 `eslint` 退出码 0）。
- **`numberOfLines={2}` 显式传**（L74）：i18n 规范 §7.1 要求阿拉伯系文本必须给出行数上限。
- **`style` 类型是 `StyleProp<ViewStyle>`**（L52）：因此外层用 `<View>` 包裹承上间距，不把 `ViewStyle`
  强塞给 `LocalizedText` 的 `TextStyle`。该类型不兼容已实测，`tsc` 报：
  `error TS2322: Type 'StyleProp<ViewStyle>' is not assignable to type 'StyleProp<TextStyle>' … ViewStyle.userSelect …`
- **一条消息一个标识、只挂 assistant 气泡下方**：组件**不判断角色**（拿不到消息角色），
  这条约束写在文件头注释第 1 条（L15–L16），由 F 组调用方保证。
- **当前全仓没有任何地方引用它**（0 调用，属预期：挂载是 F 组的事），证据：

```text
$ Select-String -Path (Get-ChildItem apps\mobile-app -Recurse -File -Include *.tsx,*.ts |
    Where-Object { $_.FullName -notmatch '\\node_modules\\' } | ForEach-Object FullName) -Pattern 'AiGeneratedLabel'
（命中 7 行，全部在本组件文件自身：注释 L5/L11/L22、类型 L44/L45、函数 L63、t() 调用 L65；其它文件 0 处）
```

---

## 三、文案只出现在 json、tsx 里 0 处（真实 grep）

Windows 的 PowerShell PATH 上没有 `grep`（`Get-Command grep` 无结果），因此用 **Git for Windows
自带的 `grep.exe`**（`C:\Program Files\Git\usr\bin\grep.exe`）执行；为避免 Windows 下中文 argv
经 ANSI 代码页转换、匹配不到 UTF-8 文件内容，把命令写进 UTF-8 无 BOM 的 bash 脚本后执行。
命令与输出原样如下：

```text
=== 命令1: grep -rn "本内容由 AI 生成" apps/mobile-app --include=*.tsx ===
exit=1
=== 命令2: grep -rn "本内容由 AI 生成" apps/mobile-app --include=*.json ===
apps/mobile-app/shared/i18n/resources/zh-Hans/common.json:3:  "aiGeneratedLabel": "本内容由 AI 生成，仅供参考",
exit=0
```

**结论（可复跑）**：
- `*.tsx`：**0 处命中**（grep 退出码 1 = 无匹配）→ 组件里的文案只经 `t()`，没有硬编码；
- `*.json`：**1 处命中**，只在 `zh-Hans/common.json:3`（目标语言尚未翻译，见第四节）。
- 组件文件头注释里也**刻意不出现**这句原文（只写 `common.aiGeneratedLabel`），避免把「grep tsx 为 0」这条判据自己打破。

---

## 四、四语言 key 存在性与「登记式缺译」

### 4.1 真实核对命令与输出

```text
$ node -e "…解析四个 common.json，检查 hasOwnProperty('aiGeneratedLabel')…"
zh-Hans      存在，值="本内容由 AI 生成，仅供参考"
ug-Arab-CN   不存在（→ 登记缺译）
bo           不存在（→ 登记缺译）
kk-Arab-CN   不存在（→ 登记缺译）

$ node -e "…解析 .i18n-pending.json，过滤 aiGeneratedLabel…"
bo           aiGeneratedLabel
ug-Arab-CN   aiGeneratedLabel
kk-Arab-CN   aiGeneratedLabel
```

### 4.2 为什么是「key 只写在 zh-Hans + 登记表」，而不是在三个文件里写 `null`

这是本任务与工作流措辞之间**唯一的口径差异**，按「现有机制优先」处理，理由可核：

1. i18n 规范 **§4.2** 定义的「四语言目录必须同构」是**文件名集合**一致（四个目录都有
   `common.json`），且原文写明「**唯一的例外**：文件内容允许为空对象 `{}`（表示「这一批还没翻」），
   但**文件必须存在**」。四个 `common.json` 均存在且可解析。
2. 规范 **§11.2** 的登记式缺译由 `scripts/check-i18n.ps1` 实现：它按「目标语言 key 缺失」生成
   `.i18n-pending.json`。若在三个目标语言文件里写 `"aiGeneratedLabel": null`，脚本会认为 key
   **已存在**（`Flatten-Json` 把 null 转成空串），该 key 就**不会进登记表**——「登记」这条判据反而失效，
   而且未来加载目标语言资源时会得到空串（`lookup()` 只接受 `string`），比缺译更糟。
3. `.i18n-pending.json` 的文件头/脚本注释都写明「**由脚本生成，不要手改**」，脚本格式只有
   `"<lang>": ["<key>", …]`，**没有 reason 字段**。因此「原因：待母语法律译者确认（段 8 收口）」
   不能写进该 json（下一次 CI 生成即被覆盖），只能记录在本节——这是当前格式下唯一不会腐坏的位置。

**运行时行为（静态可证）**：`shared/i18n/index.ts` 的 `t()` 只查 `zh-Hans`
（`lookupZhHans()` L104–120、`t` 回调 L183–197），目标语言文件当前根本没有被 `resources/index.ts`
静态 import。因此切到 `ug-Arab-CN` 时：
**显示中文标识，不显示原始 key、不显示空串**（i18n 规范 §5.1 降级链），满足 E1 判据
「切 `ug-Arab-CN` 后显示缺译登记/降级中文，不显示原始 key」。

### 4.3 缺译登记文件的真实片段

```text
$ git diff -- apps/mobile-app/shared/i18n/resources/.i18n-pending.json
@@ -30,6 +30,7 @@
       "agreement.updatedAt",
       "aiCta",
       "aiDesc",
+      "aiGeneratedLabel",
       "aiTitle",
       "articlesEmpty",
       "articlesFailed",
@@ -879,6 +880,7 @@   （ug-Arab-CN 段，同样 1 行）
       "agreement.updatedAt",
       "aiCta",
       "aiDesc",
+      "aiGeneratedLabel",
@@ -1728,6 +1730,7 @@ （kk-Arab-CN 段，同样 1 行）
       "agreement.updatedAt",
       "aiCta",
       "aiDesc",
+      "aiGeneratedLabel",
```

登记原因（段 8 收口必须清零）：**待母语法律译者确认；禁止机翻、禁止编造维吾尔文/藏文/哈萨克文译文**
（工作流 L952 的「卡住怎么办」与段 2 A-15 同一处理）。

### 4.4 `zh-Hans` 的真实 diff

```text
$ git diff -- apps/mobile-app/shared/i18n/resources/zh-Hans/common.json
@@ -1,5 +1,6 @@
 {
   "fallbackText": "内容暂时无法显示，请稍后重试",
+  "aiGeneratedLabel": "本内容由 AI 生成，仅供参考",
   "state": {
```

---

## 五、门禁（全部真实执行）

| 检查 | 命令 | 真实输出 | 结论 |
|---|---|---|---|
| 类型 | `npx tsc --noEmit`（cwd `apps/mobile-app`） | 无输出 | **exit=0** |
| 组件 lint | `npx eslint shared/components/AiGeneratedLabel.tsx` | 无输出 | **exit=0** |
| 裸 `<Text>` | `scripts/check-raw-text.ps1` | `已扫描 106 个文件` / `命中 0` | **exit=0** |
| i18n 门禁 | `scripts/check-i18n.ps1` | 见下 | **exit=0** |

```text
[check-i18n] 源语言 zh-Hans 有 14 个 namespace 文件
[check-i18n] bo 缺 848 个 key（已登记进 .i18n-pending.json）
[check-i18n] ug-Arab-CN 缺 848 个 key（已登记进 .i18n-pending.json）
[check-i18n] kk-Arab-CN 缺 848 个 key（已登记进 .i18n-pending.json）
[check-i18n] 已更新 .i18n-pending.json
[check-i18n] 1 四目录同构 2 缺译已登记 3 占位符一致 —— 全部通过
```

环境：`node v24.16.0`、`tsc Version 5.8.3`、PowerShell 5.1.26100.8655、
`apps/mobile-app/node_modules` **已安装**（所以按要求跑了 `npx tsc --noEmit`，未执行 `npm install`，
未触碰锁文件）。本机 PowerShell 执行策略为受限，两个 `.ps1` 门禁实际以
`powershell -NoProfile -ExecutionPolicy Bypass -File …` 运行；这是本机环境限制，不是脚本问题。
本段**未执行** `git commit` / `git push` / `mvn`。

---

## 六、E1「算做完」清单逐条对照

| 工作流判据（L944–948） | 状态 | 证据 / 说明 |
|---|---|---|
| 真机每条 AI 回答下方有合规标识 | **挂账** | 组件已就绪，但**全仓 0 调用**（第二节末）；挂载由 F 组在 AI 页面做，真机由 lead 在 I 组验 |
| 切 `ug-Arab-CN` 后不以原始 key 显示 | **静态通过** | 第四节的登记 + `t()` 只见 zh 的降级链；真机切换同样**挂账** |
| `grep … --include=*.tsx` 为 0 / 只在 json | **通过** | 第三节真实输出：tsx `exit=1`（0 命中），json 1 处 |
| 组件有 `testID="ai-generated-label"` | **通过** | L70 |
| 用户消息下方**没有**这个标识 | **静态通过 + 挂账** | 组件不判断角色（注释 L15–16），且当前无任何调用；「不挂到用户消息下」的执行点在 F 组调用方，需 E2E/真机验 |

---

## 七、挂账项与需要 lead 处理的事

1. **真机截图不可执行** → **挂账：需真机/模拟器，由 lead 在 I 组用 CDP/模拟器验证；本节只提供钩子清单与静态证据。**
   本机实测：`adb` 存在（`C:\Users\14541\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_…\platform-tools\adb.exe`），
   但 `adb devices` 列表为空（已连接设备数 = 0）；`emulator` 命令不存在、`ANDROID_HOME` /
   `ANDROID_SDK_ROOT` 均未设置、也没有模拟器进程 → 本机没有可截图的真机/模拟器。
   `E1` 的「每条 AI 回答下方都有标识」「用户消息下方没有」两条属于**运行时/UI 断言**，
   静态证据无法替代，按工作流 L951 的三套钩子（testID / accessibilityLabel / nativeID）交付抓手。
2. **工作流写的令牌名在 `shared/theme/tokens.ts` 里不存在**：L925 要求 `tokens.fontSize.xs` +
   `tokens.color.textMuted`，实际令牌是 `fontSize.micro`（11dp，最小字号）与
   `color.text.secondary` / `color.text.muted`（选项 A 之前用的是 `muted`）。
   `spacing.xs` 存在（6dp）。因 `shared/theme/**` 本段只读，我**没有改令牌**，用的是等价令牌
   （`typography.micro` = `fontSize.micro`；颜色按选项 A 用 `color.text.secondary`）。
   **建议 lead 在段 3 收口回写工作流 L925 的令牌名**，否则下一位施工者会照文档找不到符号。
3. **对比度（已关闭，选项 A）**：`color.text.muted` = `#999999` 在白色气泡底上实测
   **2.85:1**（on `#F7F8FA` 页面底色 **2.68:1**），低于 WCAG AA 普通文本 4.5:1。
   **负责人 2026-11 已拍板选项 A**：合规标识改用 `color.text.secondary`（`#666666`，
   on `#FFFFFF` = **5.74:1**）。组件 L89 已改，本文件第二节行号与颜色值同步更新；
   该挂账项**关闭**，不需要段 8 再评估。
4. **F 组挂载点**：AI 会话页需要 `<AiGeneratedLabel />` **每 assistant 气泡下方一个**，
   禁止挂到 `role='user'` 消息下、禁止一条消息重复挂；建议 E2E 用 `testID` 计数 = assistant 消息数。
5. **文档基线**：新增 `docs/acceptance/seg3/E1-label.md` 后需按 `docs/BASELINE.md` §4 登记指纹、
   更新 `docs/README.md` 索引与 `docs/CHANGELOG.md`。本任务**明确不许改**这几份文件，**挂账由 lead 收口**。
6. `.i18n-pending.json` 的 3 行是脚本生成（`check-i18n.ps1` 幂等：重跑后 hash 不变），
   若后续有人手工编辑会与 CI 的「防漂移」检查冲突——**不要手改**。
