# 法泽 FAZE 第 0 段串行执行序列（V1.0）

> **用途**：第 0 段 24 个任务的执行顺序与完成定义（DoD）
> **上位文档**：《法泽FAZE_第0段执行计划_v2.0.md》（任务细节与验收判定式）
> **口径（项目负责人 2026-10-28 明确）**：**不要多阶段并行。** 24 个任务排成一条串行链，一个做完、验完，才进下一个。
> **定稿日期**：2026-10-28

---

# 一、为什么是串行，代价是什么

## 1.1 串行的理由

| 原因 | 说明 |
|---|---|
| **状态唯一** | 任何时刻只有一处代码在变，不存在「A 改了共享组件、B 同时在用旧版」的错位 |
| **验收不交错** | 每个任务验完再进下一个，出问题时**责任范围就是一个任务**，不用在 3 个并行任务的改动里找 |
| **问题早暴露** | 第 N 步失败就停在第 N 步，不会带着错误往下推 5 步才发现 |
| **返工半径可控** | 串行下返工最多回退 1 步；并行下可能 4 个任务要一起回滚 |

## 1.2 串行的代价（我如实列出）

1. **总耗时更长**。本来能同时做的（比如建库和写 ESLint 规则）现在要排队。
2. **某步卡住则全线停**。比如 T0-31 要真机，真机不连则 22～24 全部停下。
3. **前置任务被阻塞时无处可去**（串行没有"先干别的"的余地）。

**但这三条代价你都已经接受了**：时间不是约束（AGENTS.md 1.2），且第 0 段的目标是「建立可验收的地基」，不是"最快出成果"。**地基阶段用串行换确定性，是划算的。**

## 1.3 串行 ≠ 没有等待

有些任务必须等外部人工（EAS 登录、手机插上、真机审批）。等待期间**不允许跳到后面的任务**——只能停下等。这是串行的定义，不是缺陷。

---

# 二、串行序列总览

```text
【阶段 A 清场】
  01 环境核对
   ↓
  02 删除原型 + 重建 Expo
   ↓
  03 不可逆决策冻结（含 A/B 定稿结论）
   ↓
【阶段 B 骨架与规矩】
  11 仓库布局定型
   ↓
  12 目录边界检查器
   ↓
  21 Expo 工程配置定型
   ↓
  22 ESLint 规则集（9 条）
   ↓
【阶段 C 数据库与服务端】
  13 建库 + Flyway V1 基线
   ↓
  14 服务端骨架（R / 异常 / ping / traceId）
   ↓
  15 CI 流水线
   ↓
【阶段 D 移动端地基】
  23 路由树定稿
   ↓
  24 网络层 client.ts
   ↓
  25 鉴权态 + 单飞刷新
   ↓
  26 LocalizedText
   ↓
  27 LtrText + Icon 镜像
   ↓
  28 错误边界 + 页面四态
   ↓
  29 语言切换页 + 软重启        ← 需真机验证才能通过
   ↓
【阶段 E 三项探针】
  33 豆包 SSE 探针
   ↓
  34 法条检索探针（5 条种子）
   ↓
  31 P-1 APK 构建              ← 需真机
   ↓
  32 P-2 字体                   ← 需真机
   ↓
【阶段 F 验收收口】
  41 真机取证脚本
   ↓
  42 13 条验收执行
   ↓
  43 冻结清单 + CHANGELOG
```

**24 个任务，24 个门禁。** 前一个的 DoD 不全部勾上，不许进下一个。

---

# 三、逐任务完成定义（DoD）

> 每条 DoD 都是可勾选的。**勾满才解锁下一个任务。**

## 阶段 A：清场

### T0-01 环境核对
**DoD**
- [ ] `where node` / `where npm` / `npm root -g` / `npm config get registry` 四条有输出并记入文档
- [ ] `java -version` 记入文档（应 17.x）
- [ ] MySQL 用 `faze_app` 账号能连，`SELECT VERSION()` 记入文档
- [ ] `redis-cli PING` 返回 `PONG`
- [ ] `adb devices -l` 结果记入文档（当前预期为空，**这一条不阻塞，只记录**）
- [ ] 输出汇总到 `docs/ops/env-baseline-2026-10-28.md`

**门禁**：文档存在且 6 条都有原始输出

### T0-02 删除原型 + 重建 Expo
**DoD**
- [ ] `apps/mobile-app` 下原 `app/` `features/` `shared/` `assets/legacy/` 全部删除
- [ ] `npx create-expo-app@latest` 重建成功，`npx expo --version` 为 SDK 54
- [ ] `npm install` 成功，`npm run lint` 可执行
- [ ] `design/tokens/tokens.json` 已复制为 `apps/mobile-app/shared/theme/tokens.json`
- [ ] **独立 commit** 记录删除动作（message 写明理由）
- [ ] `git log` 能查到该 commit

**门禁**：能 `npx expo start` 启动到默认页

### T0-03 不可逆决策冻结
**DoD**
- [ ] `docs/adr/ADR-000-不可逆决策总表.md` 存在，≥14 条
- [ ] 每条含四栏：决策 / 备选及否决理由 / 为什么选它 / 什么条件下该推翻
- [ ] 已纳入 A 文档（`app_user`+`law_item` 表结构）与 B 文档（错误码 6 位格式）的结论
- [ ] 时区策略（存 UTC）已记录

**门禁**：14 条四栏无空项

## 阶段 B：骨架与规矩

### T0-11 仓库布局定型
**DoD**
- [ ] 顶层目录齐：`apps/` `services/` `database/` `deploy/` `docs/` `scripts/` `tools/` `archive/`
- [ ] 根 `package.json` 只有 scripts，**无 workspaces 字段**
- [ ] `deploy/start-services.ps1` 能从"MySQL 已停 + Redis 未启动"状态依次拉起两者
- [ ] `start-services.ps1 -Stop` 能停掉 Redis
- [ ] 每个新目录有 README 说明职责边界

**门禁**：`start-services.ps1` 两条路径（启动 / 停止）都跑通

### T0-12 目录边界检查器
**DoD**
- [ ] `scripts/arch/check-boundaries.mjs` 存在
- [ ] `scripts/arch/check-route-shell.mjs` 存在
- [ ] 注入违规 ①：feature 互引 → 拦下并指出文件:行
- [ ] 注入违规 ②：`shared/` import `features/` → 拦下
- [ ] 注入违规 ③：`app/` 里写 `useState` → 拦下
- [ ] 三个违规撤销后 → 绿
- [ ] 已接入 `npm run lint`

**门禁**：三次注入全拦、撤销后全绿

### T0-21 Expo 工程配置定型
**DoD**
- [ ] `app.config.ts` 含 `scheme: 'faze'`、`android.package: 'com.faze.app'`
- [ ] `tsconfig.json` 开启 `strict` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` + `noImplicitOverride`
- [ ] 路径别名 `@/*` 生效
- [ ] 依赖已装：`expo-router` `expo-secure-store` `expo-updates` `expo-font` `expo-localization`
- [ ] 新依赖已登记到 `docs/architecture/依赖清单.md`
- [ ] `npx expo config --type public` 输出的 scheme/package 与 ADR 一字不差
- [ ] `tsc --noEmit` 通过

**门禁**：`expo config` 输出与 ADR 逐字比对通过

### T0-22 ESLint 规则集
**DoD**
- [ ] 9 条规则全部实现（裸 Text / 物理方向 / 颜色字面量 / BiDi 控制符 / any / ts-comment / double-assertion / file-header / switch-exhaustiveness）
- [ ] 每条自研规则 ≥1 valid + 2 invalid 用例，`npm run test:lint-rules` 全绿
- [ ] `scripts/gate-selfcheck.mjs` 对每个违规 fixture 断言退出码非 0
- [ ] **负向测试**：故意写一个 `marginLeft` → eslint 报错
- [ ] **负向测试**：故意写一个 `#FF0000` → eslint 报错
- [ ] **负向测试**：故意从 `react-native` import `Text` → eslint 报错
- [ ] `rg 'marginLeft|paddingRight' apps/mobile-app` 命中 0

**门禁**：三个负向测试都真的报错（这一条最重要——**零告警不等于规则生效**）

## 阶段 C：数据库与服务端

### T0-13 建库 + Flyway V1 基线
**DoD**
- [ ] `faze` 库已建，字符集 `utf8mb4` / 排序 `utf8mb4_0900_ai_ci`
- [ ] `faze_app` 账号已建，只授权 `faze.*`，应用不用 root
- [ ] `V1__baseline.sql` 建出 `app_user`（19 字段）+ `law_item`（12 字段），与 A 文档逐字段一致
- [ ] `SHOW CREATE TABLE law_item` 含 `WITH PARSER ngram`
- [ ] 空库启动后 `flyway_schema_history` 恰 1 行 `success=1`
- [ ] **二次启动 0 新增**（幂等）
- [ ] `SELECT @@ngram_token_size` = 2

**门禁**：幂等（二次启动不新增）+ ngram 索引真实存在

### T0-14 服务端骨架
**DoD**
- [ ] `services/faze-api` 可 `mvn package`
- [ ] `R` 四字段：`code` / `msg` / `data` / `traceId`
- [ ] `TraceIdFilter` 生效：响应头 `X-Trace-Id` 与 body 的 `traceId` **一致**
- [ ] `GlobalExceptionHandler` 覆盖 4 类：参数校验 / 业务异常 / 鉴权异常 / 未捕获
- [ ] `GET /api/v1/ping` 返回 `R.ok`
- [ ] `ErrorCode` 枚举与 B 文档 78 个码逐行一致
- [ ] **负向测试**：故意抛 `BusinessException(402001)` → HTTP 200 + code 402001
- [ ] **负向测试**：故意让 token 无效 → HTTP 401 + code 402002/402003

**门禁**：两条负向测试的 HTTP 状态码分别正确（这是 D5 的落实证明）

### T0-15 CI 流水线
**DoD**
- [ ] `.github/workflows/ci.yml` 含 4 个 job：`mobile-lint` / `arch-boundary` / `api-build` / `smoke`
- [ ] `scripts/ci/gate.ps1` 本地可跑同一组命令
- [ ] **负向 ①**：写一个 `any` → CI 红
- [ ] **负向 ②**：删掉任一关键 lint 规则 id → CI 红，且 `gate-selfcheck` 报出"规则缺失"
- [ ] **负向 ③**：把 `strict` 改 false → `tsc` 报错
- [ ] 正常状态：4 job 全绿且无 skip

**门禁**：三个负向全红 + 正常全绿

## 阶段 D：移动端地基

### T0-23 路由树定稿
**DoD**
- [ ] `app/` 分组树建立：`(auth)/` `(tabs)/` `settings/language`
- [ ] 每个路由文件只有一行 re-export
- [ ] `shared/navigation/routes.ts` 汇总全部路由常量 + 参数类型
- [ ] `check-route-shell.mjs` 通过
- [ ] `tsc --noEmit` 通过
- [ ] `npx expo start` 能导航到语言切换页

**门禁**：路由壳检查器通过（不是"我看了一眼没问题"）

### T0-24 网络层 client.ts
**DoD**
- [ ] `shared/api/client.ts` 是唯一网络出口
- [ ] baseUrl 从 `app.config.ts` extra 注入，不在代码里硬编码
- [ ] 错误码 → msgKey 映射表与 B 文档对齐
- [ ] `beforeSend` 脱敏：token / 手机号 / 身份证不进日志
- [ ] 暴露 `setRefreshHandler()` / `setOnAuthLost()` 钩子
- [ ] **lint 规则生效**：除 `client.ts` 外写 `fetch` → 报错

**门禁**：故意在别的文件写 `fetch` → lint 报错

### T0-25 鉴权态 + 单飞刷新
**DoD**
- [ ] SecureStore 分 key 存 `faze.v1.access` / `faze.v1.refresh` / `faze.v1.deviceId`
- [ ] `activeRole` / `displayName` 存 AsyncStorage（非敏感）
- [ ] 模块级 `inflightRefresh`：**5 个并发 401 只触发 1 次 refresh**
- [ ] 原请求最多重放 1 次
- [ ] **POST/PATCH/DELETE 收到 401 不自动重放**
- [ ] 刷新失败：清 token → 广播 `auth:lost` → 跳登录，**只跳 1 次无闪屏**
- [ ] token 黑名单存 Redis（`faze:auth:blacklist:{jti}`）
- [ ] 冷启动读 refresh 换新 access，失败进登录页

**门禁**：mock 端 5 并发 401 → refresh 调用次数 = 1（这是可数的，不是"看起来对"）

### T0-26 LocalizedText
**DoD**
- [ ] 按 **grapheme cluster** 切分（不是码点）
- [ ] `Common/Inherited` 字符并入前一个 run
- [ ] 只给 tibetan/arabic run 设 fontFamily，han/latin 不设
- [ ] 单测覆盖 5 类：`བསྒྲུབས` / `بسم` / 中藏混排 / emoji ZWJ / 纯数字
- [ ] `numberOfLines` / `onPress` / `style` 透传不丢
- [ ] `Intl.Segmenter` 不可用时有降级路径（capability probe 有单测）

**门禁**：单测全绿，且**藏文切分不出现在 base 与下加字之间**

### T0-27 LtrText + Icon 镜像
**DoD**
- [ ] `LtrText` 实现 bidi 隔离（优先不加控制符方案）
- [ ] `Icon` + 镜像白名单：返回/前进/发送/回复/撤销 镜像；播放/关闭/加号/搜索/时钟/相机 不镜像
- [ ] 未登记 icon 默认**不镜像**（fail-safe）
- [ ] 单测：`1,234.56` / `2026-10-28` / `FAZE20261028001` 在 RTL 下顺序正确

**门禁**：三个文本单测通过（真机复核留到 T0-42）

### T0-28 错误边界 + 页面四态
**DoD**
- [ ] 通用 `ErrorBoundary` 捕获渲染异常，展示可重试 UI
- [ ] `PageState` 四态容器：正常 / 加载 / 空 / 错误
- [ ] 暴露「鉴权失效」特例出口（401 登出不落进通用错误页）
- [ ] 断网 → 进错误边界 → 不白屏、不崩溃

**门禁**：断网场景手动验一遍

### T0-29 语言切换页 + 软重启
**DoD（代码侧）**
- [ ] 4 语言单选，当前选中态正确
- [ ] 写持久化 → 算 wantRTL → **仅当 `wantRTL !== I18nManager.isRTL`** 才 `forceRTL` + 写 `faze.rtl_applied` → `reloadAsync()`
- [ ] 启动只比对 `rtl_applied` 与 `isRTL`，不一致才 reload 一次（**防死循环**）
- [ ] reload 期间屏蔽 401 拦截（防竞态踢回登录页）

**DoD（真机侧，可标记"待 T0-42"）**
- [ ] 切 ug 后杀进程重启仍 RTL
- [ ] 连切 5 次无循环
- [ ] 断网切换不崩

**门禁**：代码侧全勾；真机侧可延后到 T0-42 统一验，但**必须标注"未验"而不是"通过"**

## 阶段 E：三项探针

### T0-33 豆包 SSE 探针
**DoD**
- [ ] `/api/v1/ops/probe/ai` 用 `SseEmitter`，事件 `meta`/`delta`/`done`/`error`
- [ ] `meta` 含 `ttfbMs`
- [ ] `faze.probe.enabled` + `X-Probe-Token` 开关；关闭时 404
- [ ] `curl -N` 能收到事件流
- [ ] 上游 4xx → 收 `event: error` 收尾，**不抛 500**
- [ ] key 未配置时返回 `510001 PROBE_NOT_CONFIGURED`（算"未就绪"，不算失败）

**门禁**：收到首字节且 ttfbMs 有值

### T0-34 法条检索探针
**DoD**
- [ ] `V2__init_law_items.sql` 含 5 条**真实**法条（附 law_code / article_no / effective_date）
- [ ] `/probe/law?q=` 用 `MATCH ... AGAINST(... IN BOOLEAN MODE)`
- [ ] `q` 长度 < 2 走 `LIKE` 兜底，响应标 `engine:"like"`
- [ ] 入参剔除 `+ - > < ( ) ~ * " @` 等 BOOLEAN 保留字
- [ ] `q=合同` 命中 ≥1
- [ ] `q=合` 走 like 且非空（**已验证单字 ngram 命中 0 条，兜底是必需**）
- [ ] `q=++++` 返回空数组不 500
- [ ] **`EXPLAIN` 显示走 `ft_law_content` FULLTEXT 索引**

**门禁**：EXPLAIN 那一行必须看到 fulltext（防全表扫描假通过）

### T0-31 P-1 APK 构建
**DoD**
- [ ] `eas.json` 含 `preview` profile（`distribution: internal` / `android.buildType: apk`）
- [ ] 构建成功，产出 APK 并记 sha256
- [ ] `adb install -r` 成功
- [ ] `dumpsys package com.faze.app` 的 versionName 与产物一致
- [ ] 装机截图 + 命令输出存 `evidence/p1/`
- [ ] `docs/ops/P-1-决策与证据.md` 记录全过程

**门禁**：**真机**上 `adb shell pm list packages` 能看到 `com.faze.app`

### T0-32 P-2 字体
**DoD**
- [ ] `scripts/check-font.py` 能断言 GSUB + GPOS 表存在、字形数、码点覆盖
- [ ] 候选字体跑脚本，**全 PASS 的才进入打包**
- [ ] `assets/fonts/` + `shared/theme/fonts.ts` 按语言映射
- [ ] 真机渲染 `བསྒྲུབས` / `བོད`，截图存证
- [ ] 人工判定：无豆腐块、无断笔（签字）
- [ ] 维文词首/词中/词尾三形态正确

**门禁**：脚本 PASS **且** 真机截图人工确认（两者缺一不算）

## 阶段 F：验收收口

### T0-41 真机取证脚本
**DoD**
- [ ] `tools/evidence/collect.ps1` 一条命令产出证据包
- [ ] 含：截图 / logcat 片段 / UTC+本地时间戳 / git rev / APK sha256 / 设备指纹
- [ ] 设备指纹含 `ro.kernel.qemu=0` 且型号非 `sdk_*`（**能排除模拟器**）
- [ ] 重复执行不出错

**门禁**：在模拟器上跑一次，脚本必须判定"不合格"

### T0-42 13 条验收执行
**DoD**
- [ ] 13 条按《执行计划 v2.0》第六节判定式逐条执行
- [ ] 每条原始输出落到 `evidence/<case-id>/`
- [ ] `docs/acceptance/D0-checklist.md` 13 行全勾，每行绑定证据路径
- [ ] 干净 clone 后能重跑
- [ ] **不允许出现"目视通过"** —— 无法客观判定的写"未通过"并记原因

**门禁**：13/13 有客观证据（或明确标注未通过 + 原因）

### T0-43 冻结清单 + CHANGELOG
**DoD**
- [ ] `docs/adr/DECISIONS_FROZEN.md` 每条决策指向**机器可校验锚点**（lint 规则名 / CI job 名 / 文件路径）
- [ ] `docs/CHANGELOG.md` 记录本段做了什么、验收结果、**与计划的偏差**（这一栏必须非空）
- [ ] 遗留问题清单（含"未在真机验证"的项）
- [ ] 推送 `docs` 分支

**门禁**：每条冻结决策都能被 grep 到对应锚点

---

# 四、阻塞时的处理规则

串行下遇到阻塞，**只有两个动作**，不允许第三个：

| 情形 | 动作 |
|---|---|
| 缺少外部输入（真机没插、EAS 没登录、豆包 key 没给） | **停下，报告缺什么，等** |
| 技术方案走不通（EAS 构建连败两次 / 字体全不合格 / 刷新链路设计有误） | **停下，报告失败证据 + 备选方案，等决策** |

**禁止的动作**：
- ❌ 跳到后面能做的任务（破坏串行）
- ❌ 降低验收标准让它"通过"（破坏可信度）
- ❌ 用 mock 假装通了（第 0 段的意义就是证明真能通）

---

# 五、序列里三处必须真机的节点

| 任务 | 卡点 | 不连真机的后果 |
|---|---|---|
| **T0-29** 语言切换页 | 杀进程重启仍 RTL | 可标记"代码完成、真机未验"，继续往下 |
| **T0-31** APK 构建 | 装到真机 | **完全卡住**，T0-32/41/42 全部依赖它 |
| **T0-32** 字体 | 真机渲染藏文 | **完全卡住** |

所以真机最早必须在 **T0-31 之前**连上。T0-29 可以先用"未验"往下走。

---

# 六、这份序列的验收

- [ ] 24 个任务全部有 DoD
- [ ] 24 个任务全部有门禁，且门禁是**客观可判定**的
- [ ] 依赖关系是**严格串行**，无任何并行分支
- [ ] 「必须真机」的节点已标出（T0-31、T0-32，以及 T0-29 的可延后项）
- [ ] 阻塞处理规则明确禁止"跳步、降标准、mock 假装"


---

> **【文档基线 v1.1】** 本文件属于「法泽 FAZE 文档基线 v1.1」（2026-10-28 确立）。
> **后续一切文档从这里开始。** 基线清单与文件指纹见 `docs/BASELINE.md`。
