# 法泽 FAZE 第 0 段任务分解（V1.0）

> ⚠️ **本文件已作废，请勿据此实施。**
> 依据项目负责人 2026-10-28 的决定（不用 docker / 前端重写 / 锁 npm / 单 token / 暂无域名），本版含 docker 方案的内容不再适用。
> **现行版本：[`法泽FAZE_第0段执行计划_v2.0.md`](./法泽FAZE_第0段执行计划_v2.0.md)**
> 本文件保留仅用于对照「两版之间改了什么」。
>
> 已被 v2.0 采纳并保留的内容：专家阵容、24 任务骨架、13 条验收判定式、6 处文档矛盾清单、风险出口。
> 已被 v2.0 推翻的内容：docker compose 方案、"归档原型代码"改为"删除重写"、域名待确认改为"暂无域名用 custom scheme"。

> **上位文档**：《法泽FAZE_分期交付计划_v1.0.md》第三节
> **专家来源**：WorkBuddy 专家库 —— 1 个专家团（engineering-assurance-team）+ 7 位独立专家
> **生成日期**：2026-10-28
> **状态**：待项目负责人拍板第三节的 9 项决策后开工

---

# 一、专家实测后推翻的三件事

八位专家被派去规划前，先做了实地勘察。有三处与我先前在分期计划里写的不一样，**以本节为准**。

## 1.1 「令牌区分 start/end」这个说法是错的

分期计划第 0 段范围 C 写「设计令牌从第一天就区分 `start/end`」。**实测 `tokens.json`：**

```json
"banner": {
  "start": { "value": "#D2E3FB", "note": "首页顶部渐变起点" },
  "end":   { "value": "#F7F8FA", "note": "首页顶部渐变终点，与 canvas 相接" }
}
```

这里的 `start/end` 是**渐变色标**，不是方向。方向不是令牌能表达的，它是**样式属性 + lint 规则**。

**结论**：第 0 段 C 块的正确表述是「**禁用物理方向属性的 lint 规则**」，不是「令牌区分 start/end」。

## 1.2 存量违规比我估计的多一倍以上

| 项 | 分期计划/专家口径 | **实测** |
|---|---|---|
| 直写 `<Text>` | 107 处 / 17 文件 | **284 处 / 96 个文件** |
| 物理方向属性（marginLeft 等） | 51～62 处 | **141 处** |
| `flexDirection: 'row'` | 40 处 | 需逐处判断该不该反转 |

这不是小数目。**如果保留这批代码，第 0 段的质量门（`tsc` + `eslint` 零告警）从第一天就是红的。**

## 1.3 本机环境缺两样硬东西

| 项 | 状态 | 后果 |
|---|---|---|
| `docker` | **无**（`Get-Command docker` 无返回） | 验收第 1 条（compose 三服务 healthy）**现在无法执行** |
| 真机 | **未连接**（`adb devices` 空） | 验收 4/8/9 无法判定 |
| `adb` | 有（platform-tools 37.0.1） | — |
| `eas` | 有 | P-1 的 A 路线可用 |
| `java` | 有（JDK 17） | 服务端可构建 |
| `node` | 有（`E:\uniapp1\node.exe`，非标准安装位置） | 需确认版本一致性 |

---

# 二、开工前必须先解决的四个硬阻塞

这四条不解决，第 0 段的一部分验收**永远做不了**。都是你侧的事，不是技术侧能绕开的。

| # | 阻塞 | 卡住哪条验收 | 可选出路 |
|---|---|---|---|
| **B1** | 本机没有 docker | 验收 1（compose healthy）、验收 2 的依赖 | ① 装 Docker Desktop（需 WSL2 + 虚拟化开启）② 用 WSL2 内自建 engine ③ **接受「以 CI 证据代替亲手验收」**（这条会让你失去「亲手跑」的意义，我不推荐） |
| **B2** | 没有可连的 Android 真机 | 验收 4（装 APK）、8（杀进程重启仍 RTL）、9（藏文渲染） | 一台能开 USB 调试的 Android 手机 + 数据线 |
| **B3** | 没有豆包 API key | 验收 11 的 `/probe/ai` | 豆包 apikey + endpoint + model id |
| **B4** | `law_item` 零条数据 | 验收 11 的 `/probe/law` | **至少 3 条真实法条**（建议覆盖合同/婚姻/劳动各 1 条），并明确来源版本与录入人 |

> **B4 是我要特别强调的**：分期计划里 `law_item` 建了表，但没人给文本。没有种子数据，「用 ngram 检索能命中至少 1 条条文」就是一条**空验收**——跑一次返回空数组也会「通过」如果判定写松了。这个坑不先说清，第 3 段会直接翻车。

---

# 三、需要你拍板的 9 项决策（附我的建议）

标 ★ 的是**不可逆或高代价可逆**，必须现在定。

## ★D1 原型代码去留

**现状**：`apps/mobile-app` 里是 12 页纯 UI 原型（96 个 tsx 文件、284 处直写 `<Text>`、141 处物理方向属性），全是假数据。

**我的建议（判断）：归档，不保留在构建路径里。**

理由三条：
1. 第 0 段只交付 1 个页面（语言切换页），第 1 段起全部页面要接真实接口重写。原型代码**没有一行能进生产**。
2. 保留它 = 质量门永远红，人会去关规则；或者要写 284 处例外，例外比代码还难维护。
3. 视觉参考价值**已经用截图保存下来了**（`docs/design/screenshots/` 12 张），代码本身不需要留。

**具体做法**：移到 `archive/prototype-2026-10/`，不加进任何构建/ lint 范围，README 注明「历史参考，不是基线代码」。
**备选**：保留但 lint 排除（代价：技术债永久存在，且第 1 段要边写新页面边清旧账）。

## ★D2 包管理器：npm 还是 pnpm

**现状冲突**：仓库有 `package-lock.json`（npm），但 `AGENTS.md` 第 7 节写 `pnpm run lint`。

**我的建议：锁 npm。** 理由：现状已经是 npm，改 pnpm 要重装 + 重生成锁文件，零收益；而 `AGENTS.md` 那句话改两个字就行。**反过来改代价更大且没好处。**

## ★D3 Android 包名 + URL scheme + 分享域名

APK 分发之后**这三样改不了**（改了等于换一个 App，用户要重装、商店要重新审核）。

| 项 | 现状 | 需你确认 |
|---|---|---|
| 包名 | 文档写 `com.faze.app`，未实际配置 | 是否就用它 |
| custom scheme | 文档写 `faze://` | 是否就用它 |
| https 分享域名 | 文档写 `m.faze.com` | **域名归属未核实，你有这个域名吗？** |

**专家补的一条重要风险（我要转达）**：`faze` 是通用 custom scheme，Android 上任何应用都能声明同名抢占，微信/浏览器也会拦截。**主链路应走 App Links（https + `assetlinks.json`），custom scheme 只作兜底。** 这使 D3 的硬阻断从「scheme 叫什么」变成「**你有没有可用的 https 域名**」。

## D4 RuoYi 源码放哪

**现状冲突**：分期计划写 `apps/admin/`（仓库内），`AGENTS.md` 写本地路径 `E:\itheima\vue\ry_vue3\RuoYi-Vue3`（仓库外）。

**我的建议：纳入 `apps/admin/`。** 理由：`AGENTS.md` 有一条规则「不得修改 RuoYi 框架层」——如果源码在仓库外，这条规则**无法做机器检查，从第一天起就是空话**。纳入仓库才能用 diff 拦住框架层改动。

## D5 HTTP 状态码：401 还是恒 200

**这是文档与验收标准的直接矛盾，必须定。**

- 技术路线 §4.5「统一响应 R」写的是**一律 HTTP 200**，业务结果看 body 里的 `code`
- 但第 0 段验收 6 要求「**401** 自动清 token 并回登录页」

**我的建议：鉴权失效用真正的 HTTP 401，业务失败仍 200 + code。** 理由：401 是 HTTP 语义标准，网关、监控、`fetch` 都靠它；恒 200 会让所有中间层都看不出鉴权失败。这一点定了，后面 8 段的拦截器都不用改。

## D6 错误码格式

**技术路线 §4.8.3 自相矛盾**：正文说「域位 1 位 + 后三位序号」，但给的示例 `400401` / `500501` / `800801` 是 **6 位、域位 2 位**。

**我的建议：冻结为 6 位 `[C][DD][NNN]`**——
`C` 取 4/5，`DD` 为域位（01 通用 / 02 账户 / 03 律师 / 04 会话 / 05 交易 / 06 文书 / 07 案件 / 08 AI / 09 内容 / 10 运营），`NNN` 从 001。
登录失败 = `402001`。旧的三个示例作废重编——**现在客户端代码为 0，这是改写成本最低的时刻**。

## ★D7 双角色（C 端用户 / 律师）的 token 模型

**我的建议：单 token + `roles` claim。** 一个用户一个 token，角色在 token 里，请求头带当前角色上下文，客户端另存 `activeRole` 供 UI 用。

理由：拆两套 token 会让 access/refresh 的存储、刷新、登出逻辑全部翻倍，而律师端第 2 段才做。**现在按单 token 建模，第 2 段只加功能不改契约；现在按两套建模，第 2 段要改的是契约。**

## D8 第 0 段要不要补 refresh 端点

**矛盾**：分期计划写「第 0 段服务端只有最小登录接口」，但验收 6 要求「401 自动清 token 并回登录页」——**没有 refresh 端点，刷新失败这条链路无法真实验证**。

**我的建议：补一个最小 refresh 端点**（`POST /api/v1/app/auth/refresh`，存 Redis 不建表）。理由：它是验收 6 能否成立的前提，且成本很低（Redis 存 key + 一次校验）。

## D9 第 0 段登录方式

短信通道未定（分期计划已承认）。**我的建议：第 0 段用「密码 + 后台造账号」**，短信留到第 1 段。否则第 0 段会被一个待办的外部供应商卡住。

---

# 四、任务分解：4 批 / 24 个任务

**批次依赖**：第 0 批 → 第 1 批 →（第 2 批 ∥ 第 3 批）→ 第 4 批

## 第 0 批：清场与冻结（必须先做完，否则后面全是白工）

| 编号 | 任务 | 产出物 | 验收 | 阻塞 |
|---|---|---|---|---|
| **T0-01** | 原型代码归档 | `archive/prototype-2026-10/`；`apps/mobile-app` 只留骨架 | 构建/ lint 范围内 tsx 文件数 = 0（仅骨架） | 待 D1 |
| **T0-02** | 包管理器统一 | 保留 `package-lock.json`，改 `AGENTS.md` 的 `pnpm run lint` | `npm ci` 可复现；全套命令用 npm | 待 D2 |
| **T0-03** | 不可逆决策冻结 | `docs/adr/ADR-000-不可逆决策总表.md` | ≥12 条，每条四栏（决策/备选/理由/推翻条件）无空项 | 待 D3、D4 |

## 第 1 批：地基（可 6 路并行）

| 编号 | 任务 | 产出物 | 验收 | 阻塞 |
|---|---|---|---|---|
| **T0-11** | 仓库布局定型 | `pnpm-workspace` 或 npm 根编排；`services/`、`deploy/`、`database/` 的 README | 顶层目录与文档表格逐行对应 | 无 |
| **T0-12** | 目录边界检查器 | `scripts/arch/check-boundaries.mjs`、`check-route-shell.mjs` | 注入 3 个违规（feature 互引 / shared→features / app 内写逻辑）后**必须红且指到行**；撤销后绿 | 无 |
| **T0-13** | 服务端骨架 | `services/faze-api`：`R` 统一响应、全局异常、`TraceIdFilter`、`GET /api/v1/ping` | `curl /api/v1/ping` 返回 `{code,msg,data,traceId}`，header 与 body 的 traceId 一致 | 无 |
| **T0-14** | Flyway V1 基线 | `V1__baseline.sql`：只建 `app_user` + `law_item`（含 `FULLTEXT ... WITH PARSER ngram`） | 空库启动后 `flyway_schema_history` 恰 1 行 success；二次启动 0 新增 | 无 |
| **T0-15** | ESLint 规则集 | 9 条规则 + RuleTester 用例 + `gate-selfcheck.mjs` | 每条自研规则 ≥1 valid + 2 invalid；**自检脚本对每个违规 fixture 断言退出码非 0** | 无 |
| **T0-16** | tsconfig 强化 + 品牌类型 | `strict` + `noUncheckedIndexedAccess` + `Cents` 品牌类型 | 故意写 `any`/未检查索引访问，`tsc` 必须报错 | 无 |
| **T0-17** | docker compose | `deploy/docker-compose.yml`（MySQL/Redis/RabbitMQ，版本锁定，健康检查，端口绑 127.0.0.1） | `compose up -d --wait` 退出码 0，`compose ps` 三行 healthy | **B1 无 docker** |
| **T0-18** | CI 流水线 | `.github/workflows/ci.yml` + `scripts/verify.ps1`（本地跑同一组命令） | 故意违规（`any`/`rgba()`/删规则 id）三者都必须让 CI 红 | 无 |

## 第 2 批：移动端地基（本段定型的核心）

| 编号 | 任务 | 产出物 | 验收 | 阻塞 |
|---|---|---|---|---|
| **T0-21** | 路由树 + deep link 定稿 | `app.config.ts`（scheme/package/intentFilters）、`shared/navigation/routes.ts`、ADR-0002 | `npx expo config --type public` 的 scheme/package 与 ADR 一字不差；`tsc` 通过 | 待 D3 |
| **T0-22** | 网络层唯一出口 | `shared/api/client.ts`：环境切换、错误码→i18n key、金额格式化、beforeSend 脱敏 | 全仓除 `client.ts` 外无 `fetch`/`XMLHttpRequest`（lint 强制） | 无 |
| **T0-23** | 鉴权态 + 单飞刷新 | `tokenStore.ts`（SecureStore 分 key）、`refresh.ts`、`useAuth.ts` | mock 端 5 并发请求同时 401，`/auth/refresh` **只被调用 1 次**；POST 收到 401 **不重放** | 待 D8 |
| **T0-24** | LocalizedText | `shared/components/LocalizedText.tsx` + `shared/text/scriptRun.ts` | 单测覆盖 `བསྒྲུབས` / `بسم` / 中藏混排 / emoji ZWJ；`numberOfLines`、`onPress` 透传不丢 | 无 |
| **T0-25** | LtrText（bidi 隔离） | `shared/components/LtrText.tsx` | RTL 界面下 `1,234.56`、`2026-10-28`、`FAZE20261028001` 顺序正确（真机 + 复制结果双验） | 无 |
| **T0-26** | Icon 镜像组件 | `shared/components/Icon.tsx` + 镜像白名单 | 返回箭头 RTL 下朝右；黑名单图标 RTL/LTR 像素一致 | 需 icon 全量清单 |
| **T0-27** | 错误边界 + 页面四态 | 通用 `ErrorBoundary` + 四态容器 | 断网进错误边界，logcat 无 `FATAL EXCEPTION`，进程 pid 未变 | 无 |
| **T0-28** | 语言切换页 + 软重启 | `LanguagePage.tsx` + `languageService.ts` | 切 ug 后杀进程重启仍 RTL；**连切 5 次无死循环**；reload 期间 401 不把人踢回登录页 | 需 P-1（expo-updates 只在 dev build / release 生效） |

## 第 3 批：三项探针（最高风险，必须真跑）

| 编号 | 任务 | 产出物 | 验收 | 阻塞 |
|---|---|---|---|---|
| **T0-31** | P-1 APK 构建 | `eas.json`（profile `preview`，`buildType: apk`）、`docs/ops/P-1-决策与证据.md` | `adb install -r` 成功；`dumpsys package` 的 versionName 与产物一致；APK sha256 存档 | **B2 无真机** |
| **T0-32** | P-2 字体 | `scripts/check-font.py`（fontTools 断言 GSUB/GPOS 存在 + 字形数 + 码点覆盖）、`assets/fonts/`、`fonts.ts` | 4 个字体脚本全 PASS；真机 `བསྒྲུབས`/`བོད` 无豆腐块（截图）+ 维文词首/词中/词尾三形态正确 | 需字体源 + B2 |
| **T0-33** | P-3 豆包 + 法条检索 | `/api/v1/ops/probe/ai`（SseEmitter，事件 meta/delta/done/error，meta 带 ttfbMs）、`/probe/law`（ngram + EXPLAIN 断言） | `curl -N` 收到事件且含 ttfbMs；`q=合同` 命中 ≥1 且 **EXPLAIN 走 FULLTEXT**（防全表扫描假通过） | **B3 key + B4 法条数据** |
| **T0-34** | 真机取证脚本 | `tools/evidence/collect.ps1` | 一条命令产出：截图 + logcat + 时间戳 + git rev + APK sha256 + **设备指纹（含 `ro.kernel.qemu=0` 排除模拟器）** | B2 |

## 第 4 批：验收收口

| 编号 | 任务 | 产出物 | 验收 |
|---|---|---|---|
| **T0-41** | 验收判定词典 | 见第五节，13 条逐条改成判定式 | 全文无「目视/大致/正常/应该」；第二人照做能复现同一结论 |
| **T0-42** | 13 条验收执行 + 取证 | `docs/acceptance/D0-checklist.md` + `evidence/` | 13 行勾选，每行绑定证据路径；干净 clone 可重跑 |
| **T0-43** | 冻结清单签字 + CHANGELOG | `DECISIONS_FROZEN.md` + `docs/CHANGELOG.md` | 每条冻结决策指向**机器可校验锚点**（lint 规则名 / CI job 名 / 文件路径），纯文字不算冻结 |

---

# 五、验收判定：13 条改成可判定式

专家指出原文有 6 条**无法客观判定**。下表是改后的判定式。

| # | 原表述 | 判定式（改后） | 证据 |
|---|---|---|---|
| 1 | compose 三服务 healthy | `docker compose ps` 三行 Health=healthy **且** `flyway_schema_history` success 行数 = 脚本文件数 | 命令输出 |
| 2 | `/ping` 返回 R | 断言 JSON 四个字段存在，**且 header 的 traceId 与 body 一致** | curl 输出 |
| 3 | RuoYi 能登录、看到 biz 菜单 | 登录后 biz 菜单路由返回 200 **且 DOM 含菜单文本** | 截图（测试 profile 关闭验证码） |
| 4 | APK 装到真机 | `adb shell pm list packages` 含包名 **且** 设备指纹 `ro.kernel.qemu=0`、型号非 `sdk_*` | 命令输出 + 装机实物照 |
| 5 | 首页数据非硬编码 | **注入 nonce 法**：`/ping` 返回 8 位 nonce，首页渲染它 → 截图 nonce 与同刻 curl 一致；改 nonce 前缀冷启，界面随变 | 两段截图 |
| 6 | 401 自动清 token | 服务端作废 token 触发 401 → 三段截图（触发/跳登录/再请求被拒）；杀进程后仍免登录（证明持久化） | 三段截图 |
| 7 | 整屏镜像 | 拆三条：① 返回箭头 bbox 中心 x > W/2 ② 进度 30% 填充在右半区 ③ Tab1 中心 x > Tab2 中心 x。走 `uiautomator dump` | dump XML + 截图 |
| 8 | 杀进程重启仍 RTL | `am force-stop` 后冷启重跑第 7 条三条；**必须是 standalone APK，Expo Go 会假阳性** | 同上 |
| 9 | 藏文无豆腐无断笔 | 机器初筛（非空像素率 + 连通域数 + tofu 模板相似度）+ **人工终判并签字** | 截图 + 签字 |
| 10 | 直写 Text 为 0 | 范围限定 `apps/mobile-app/**/*.tsx`；**负向测试**：加一个违规 fixture，eslint 必须报错 | 命令输出 |
| 11 | 两条探针通 | `/probe/ai` 首字节 + ttfbMs；`/probe/law` 命中 ≥1 **且 EXPLAIN 走 FULLTEXT 索引** | 命令输出 |
| 12 | CI 绿 | 四 job 全绿且无 skip；**且删掉任一关键规则 id 后 CI 必须红** | CI 记录 |
| 13 | 断网进错误边界 | `adb shell svc wifi disable` + `svc data disable` → 边界 UI 截图 + logcat 无 FATAL + 进程 pid 未变 + 恢复网络可重试 | 截图 + logcat |

**执行顺序**（依赖驱动）：41 → 11~18 → 21~28 → 31~34 → 42 → 43。第 7/8 必须排在 5 之后（要 standalone APK）。

---

# 六、专家分工与产能

| 专家 | 负责任务 | 交付物 |
|---|---|---|
| 工程架构督导（Archi） | T0-01、T0-11、T0-12、T0-21 | 仓库布局、边界检查器、路由树 |
| 构建与交付（Rex） | T0-17、T0-18、T0-31、T0-34 | compose、CI、APK 决策树、取证脚本 |
| 验收设计（Tessa） | T0-41、T0-42 | 判定词典、验收清单 |
| 国际化与 RTL（Iris） | T0-24、T0-25、T0-26、T0-28、T0-32 | LocalizedText、LtrText、Icon、语言切换、字体断言 |
| 身份与鉴权（Ira） | T0-23 | tokenStore、单飞刷新、useAuth |
| 后端架构（Bao） | T0-13、T0-14、T0-33 | R/异常/ping、Flyway V1、两条探针 |
| 代码质量门（Cody） | T0-15、T0-16 | 9 条 ESLint 规则、tsconfig、自检 |
| 软件架构师（Gao） | T0-03、T0-22、T0-27、T0-43 | ADR 台账、client.ts 契约、错误边界、冻结清单 |

**产能提示**：24 个任务里 **6 个是硬阻塞**（T0-17 需 docker、T0-31/32/34 需真机、T0-33 需 key + 法条数据）。其余 18 个现在就能开工。

---

# 七、专家指出的 6 处文档矛盾（需修，但不在第 0 段）

| # | 矛盾 | 位置 |
|---|---|---|
| 1 | 错误码正文说「域位 1 位」，示例却是 6 位、域位 2 位 | 技术路线 §4.8.3 |
| 2 | 统一响应写「一律 HTTP 200」，验收 6 却要 401 拦截 | 技术路线 §4.5 vs 分期计划第 0 段 |
| 3 | Flyway 位置两处写法冲突（`src/main/resources/db/migration` vs `database/`） | 技术路线 §4.1 vs §24.1.1 |
| 4 | RuoYi 归属两处冲突（`apps/admin/` vs 本地外置路径） | 分期计划 vs AGENTS.md 3.3 |
| 5 | 「令牌区分 start/end」表述错误（实为渐变色标） | 分期计划第 0 段范围 C |
| 6 | 验收 10「全仓」范围不清（是否含 RuoYi 的 Vue 代码） | 分期计划第 0 段验收清单 |

**处理方式**：D5/D6 拍板后一并修正；其余随第 0 段动工时顺手改掉。

---

# 八、开工条件确认表

开工前请确认这几项，勾完就可以动第一批：

- [ ] **D1** 原型代码归档（我的建议：归档）
- [ ] **D2** 包管理器锁 npm
- [ ] **D3** 包名 / scheme / https 域名（尤其域名归属）
- [ ] **D4** RuoYi 纳入 `apps/admin/`
- [ ] **D5** 鉴权失效走 HTTP 401
- [ ] **D6** 错误码冻结为 6 位 `[C][DD][NNN]`
- [ ] **D7** 单 token + roles claim
- [ ] **D8** 第 0 段补最小 refresh 端点
- [ ] **D9** 第 0 段登录用密码
- [ ] **B1** docker 怎么解决
- [ ] **B2** 真机是否可用
- [ ] **B3** 豆包 key
- [ ] **B4** 至少 3 条法条种子数据
