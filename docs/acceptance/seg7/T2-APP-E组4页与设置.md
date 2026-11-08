# T2 · APP 端 E 组（9 页）验收证据

> 段 7 · E 组（工作流 §三 E1~E5）· APP 端负责人：fe-app
> 日期：2026-11-09
> 证据截图：`tmp/seg7e/`（已 gitignore）；截图方式：CDP 真实 Chrome（`scripts/cdp-shot.mjs`，
> 390×844、mobile 触摸视口、登录态为测试账号 `13900008888`）。
> 接口联调：faze-api(8081) 的 E 组 6 个接口由 be-api 交付后，本页所有「真数据」项已重新跑过。

## 一、交付清单（9 个页面 / 14 个新文件）

| # | 页面 | 实现文件 | 路由壳 | 路由常量 |
|---|---|---|---|---|
| E1 | 设置页（41） | `features/settings/pages/SettingsPage.tsx` | `app/settings.tsx` | `PAGE_ROUTES.settings` |
| E1 | 字体倍率 | `shared/theme/fontScale.ts` | — | — |
| E1 | 本机存储与缓存 | `features/settings/storage.ts` | — | — |
| E1 | 检查更新接口 | `features/settings/api/version.ts` | — | — |
| E5 | 版本弹窗（41b） | `shared/components/UpdateModal.tsx` | —（弹窗，不新增页） | — |
| E2 | 企业认证（49） | `features/account/pages/EnterpriseAuthPage.tsx` | `app/verify-company.tsx` | `PAGE_ROUTES.verifyCompany` |
| E2 | 企业认证接口 | `features/account/api/enterprise.ts` | — | — |
| E3 | 我的收藏（51） | `features/social/pages/FavoritesPage.tsx` | `app/favorites.tsx` | `PAGE_ROUTES.favorites` |
| E3 | 关注律师（52） | `features/social/pages/FollowingPage.tsx` | `app/following.tsx` | `PAGE_ROUTES.following` |
| E3 | social 接口与类型 | `features/social/api/social.ts`、`features/social/types.ts` | — | — |
| E4 | 关于法泽（59） | `features/settings/pages/AboutPage.tsx` | `app/about.tsx` | `PAGE_ROUTES.about` |
| E4 | 全部功能（61） | `features/settings/pages/AllFeaturesPage.tsx` | `app/all-features.tsx` | `PAGE_ROUTES.allFeatures` |

配套改动：
- `shared/navigation/routes.ts`：新增 6 条路由常量 + `docMine`；
- `shared/i18n/resources/zh-Hans/profile.json`：新增 `settings` / `about` / `allFeatures` / `enterprise`
  四个命名空间 + `my.menu*` 5 条 + `enterprise.legalPersonDisplay`；
- `shared/i18n/resources/zh-Hans/content.json`：新增 `favorites`；
- `shared/i18n/resources/zh-Hans/lawyer.json`：新增 `following`；
- `features/profile/pages/MyPage.tsx`：「我的」页新增企业认证 / 我的收藏 / 关注律师 / 设置 / 全部功能入口；
- `features/profile/pages/MyPage.tsx`：hero 天平水印从 Ionicons `scale` 改为 SVG（见 §七 坑 2）。

## 二、门禁（真实退出码，最终一轮）

| 门禁 | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 类型检查 | `cd apps\mobile-app; npx tsc --noEmit` | **0** | 无输出 |
| ESLint | `npx eslint . --ext .ts,.tsx` | **0** | 0 error / 0 warning |
| 裸文本 | `powershell -File scripts\check-raw-text.ps1` | **0** | 扫描 183 个 tsx，命中 0 |
| i18n | `powershell -File scripts\check-i18n.ps1` | **0** | 四目录同构通过；缺译登记 **1797 条**（本段前 1618 → 新增 **179 条**） |

## 三、逐页实测记录（全部为真实浏览器 + 真实点击）

### E1 设置页（`/settings`）

| 验收点 | 怎么验 | 看到什么 | 截图 / 断言 |
|---|---|---|---|
| 六块落地 | CDP 打开 `/settings` | 账号与安全（5 行）、通用（4 行）、关于（4 行）、退出登录；账号状态「未认证 / 未设置」取自 profile 接口 | `01-settings-default.png` |
| **字体四档各用自己的倍率** | 点「字体大小」 | 「小/标准/大/特大」四行实际字号递增（13.1 / 15 / 16.9 / 18.75dp），当前档有勾 | `05-font-sheet.png` |
| **切换后立即按真实字号变化** | 标准档读分组标题 `fontSize`=12px → 切「特大」→ 再读=15px（12×1.25）；行 label 15→19px | 整页字号与行高变大 | `01` vs `06-font-xlarge.png`；eval `{"before":{"row":"12px"},"afterLabelFontSize":"15px"}` |
| 字体持久化 | 切特大后重载 | 仍为「特大」（`settings.fontScale=xl`） | `06` |
| **深色三选** | 点「深色模式」→ 选「开」 | 浮层三项；行值变「开」，`localStorage['settings.theme']='on'` | `15-theme-on.png`；eval `{"sheetOpen":true,"storedTheme":"on","rowValue":"开"}` |
| 深色持久化 | 重载页面 | 仍显示「开」 | 同上 |
| 缓存大小真实 | 进入设置页 | 「**约 3.6 MB**」（performance 资源字节 + Cache Storage + `faze.cache.*` − 基线） | `01`、`07` |
| **确认框正文逐字** | 点「清除缓存」 | 「将清除图片与临时文件缓存，不会删除你的账号、文书草稿和聊天记录」，eval `exact:true` | `07-cache-confirm.png` |
| **清完变 0 + token 还在** | 确认框点「清除」 | 约 3.6 MB → **约 0 B**；`faze.accessToken` 前后一致；toast「缓存已清除」 | `08-cache-cleared.png`；eval `{"cacheLineBefore":"约 3.6 MB","cacheLineAfter":"约 0 B","tokenKept":true,"toast":true}` |
| **退出登录** | 点「退出登录」→ 二次确认 → 确认 | 弹窗「确定要退出当前账号？」；确认后 `location=/login`、token=null | `17-logout-result.png`；eval `{"confirmVisible":true,"path":"/login","tokenCleared":true}` |
| 检查更新（错误分支） | 接口未就绪时点 | toast「检查更新失败，请稍后重试」（不白屏、不假装成功） | `14-check-update.png` |
| 断网可用 | profile 失败时值退化 `—`、企业认证退化「去认证」 | 不阻塞页面 | 代码 + 截图 |

### E2 企业认证（`/verify-company`）四态全验

| 状态 | 造法 | 看到什么 | 截图 |
|---|---|---|---|
| `none` | 库里无 9324 的行 | 表单：企业名称 / 信用代码 / 营业执照（必填，未上传）/ 法定代表人；顶部「请先完成实名认证」提示条；底部「提交审核」 | `25-verify-company-none.png` |
| `pending` | `INSERT enterprise_auth ... status='pending'` | 状态头「企业认证审核中」+「一般 1 个工作日出结果」+ 已提交内容（企业名称/信用代码/法定代表人/提交时间）+ 审核期间说明；**无底部按钮** | `30-enterprise-pending.png` |
| `approved` | `UPDATE status='approved'` | 「企业认证已通过」+「现在可以以该企业名义委托律师、开具企业抬头发票」+ 认证信息（含审核时间）；**无底部按钮** | `31-enterprise-approved.png` |
| `rejected` | `UPDATE status='rejected'` + 驳回原因 | 「企业认证未通过」+ 驳回原因原文 + 已提交内容；底部「重新提交」 | `32-enterprise-rejected.png` |
| 驳回后重提 | 点「重新提交」 | 表单重新打开，企业名称/信用代码/法人**已预填**，只有执照需重传 | `37-enterprise-resubmit.png`；eval `{"formOpen":true,"inputs":["伊宁市解放路商贸有限公司","91654002MA77K3L82X","","马合木提·艾山"],"submitButton":true}` |
| **未实名禁用提交** | 表单态读按钮 | 提交按钮 `aria-disabled=true`（灰）；「请先完成实名认证」提示条可见且可点跳 `/verify-realname` | `39-enterprise-realname-guard.png`；eval `{"submitFound":true,"submitDisabled":"true","noticeVisible":true}` |
| 信用代码校验 | 代码 | `/^[0-9A-HJ-NPQRTUWXY]{2}\d{6}[0-9A-HJ-NPQRTUWXY]{10}$/`，**只做长度+字符集，不做校验位** | 代码 |
| 执照必填 | 代码 | 无 `license.key` 时提交被拦并提示 | 代码 |

### E3 我的收藏（`/favorites`）

| 验收点 | 怎么验 | 看到什么 | 截图 |
|---|---|---|---|
| **三 Tab 带数量** | 造 3 文章 + 2 律师 + 2 案例收藏后打开 | Tab 显示「**文章 3 / 律师 2 / 案例 2**」，与稿面一致（一次请求返回三个计数） | `20-favorites-data.png` |
| 切分类 | 点「律师」Tab | 律师卡 2 张（头像/姓名/律所/在线点/「律师 · 评分」meta），计数保持 3/2/2 | `23-favorites-lawyer-tab.png`；eval `{"tabCounts":["文章3","律师2","案例2"],"cardCount":4}` |
| **左滑 84px 露出取消** | CDP 鼠标从 (320,190) 拖到 (120,190) | 卡片左移、右侧露出 84px 红色「取消收藏」 | `26-swipe-left.png` |
| **左滑取消 → 列表减少、Tab 减 1** | 上一步后点红色按钮 | 文章计数 **3 → 2**，列表清空、显示空态；toast | `27-swipe-cancelled.png`；eval `{"before":["文章3"...],"after":["文章2"...],"cards":0}` |
| 心形按钮第二条路 | 卡片右上角心形 | 与左滑同一删除逻辑（设计稿要求两条路） | `20` |
| 空态文案 | 文章 Tab 清空后 | 「还没有收藏」+ 说明 + 「前往首页」 | `27-swipe-cancelled.png` |
| 左滑提示 | 列表下方 | 「向左滑动任一收藏可取消收藏」 | `20`、`23` |

### E3 关注律师（`/following`）

| 验收点 | 怎么验 | 看到什么 | 截图 |
|---|---|---|---|
| **三种状态可区分** | 造 3 个律师（在线 / 离线且回复过评价 / 60 天未活跃） | ①「端到端测试」→ 标签「**在线**」（绿点）+ 动态「暂无新动态」；②「验收律师甲」→ 标签「**2 天前在线**」+ 动态「**回复了用户评价**」；③「古丽娜尔律师」→ 标签「**长期未活跃**」+ 动态「**近 30 天没有发布内容，也没有上线**」+「最后活跃于 2026-09-10」 | `24-following-data.png` |
| 排序说明 | 页面顶部 | 「按最近活跃排序，最近有动态的排在前面」 | `24` |
| 取消关注 | 点第一张卡「取消关注」 | 卡片 3 → 2（调 `DELETE /app/social/follows?lawyerId=`） | `36-following-unfollowed.png`；eval `{"cardsBefore":3,"cardsAfter":2}` |
| 空态文案 | 临时软删全部关注 | 「还没有关注律师」+ 说明 + 「去找律师」 | `38-following-empty.png` |
| 发消息 | 卡片按钮 | 跳 `/chat?lawyerId=` | 代码 |

### E4 关于法泽（`/about`）

| 验收点 | 怎么验 | 看到什么 | 截图 |
|---|---|---|---|
| 四块齐全 | 打开并滚到底 | 品牌（SVG 天平 + 法泽 + slogan + v1.0.0）/ 法泽介绍（含「**法泽是技术平台，不是律师事务所**」边界）/ 协议与说明（3 行）/ 联系方式 / 页脚 | `12-about-logo.png`、`04-about-bottom.png` |
| **备案号是占位** | 滚到底 | `[待替换：ICP 备案号 / App 备案号]`，**没有任何编造号码** | `04-about-bottom.png` |
| 顶部 TODO | 源码 | 「TODO（备案号下来必须改这一处）」在文件头 + 页脚行内注释 | 代码 |
| 客服电话不写死 | 读配置 | 显示 `002-00854967`（`GET /pub/app/config.phone`）；在线客服 `09:00-20:00` 同源 | `12-about-logo.png` |
| 协议页真实 | 路由 | 用户服务协议 → `/agreement`；隐私政策/实名说明 → `/privacy` | 代码 |

### E4 全部功能（`/all-features`）

| 验收点 | 怎么验 | 看到什么 | 截图 |
|---|---|---|---|
| 四组入口 | 打开页面 | 找律师与咨询 4 / 办案件 4 / 订单与钱 4 / 我的与服务 8 = **20 个入口**（设计稿 18 个一个不少 + 工作流点名的意见反馈/关于） | `03-all-features.png` |
| **每个入口目标页真实存在** | CDP 依次导航 20 个目标路由读首屏 | **20/20 渲染真实页面，404 = 0** | `tmp/seg7e/route-walk.json` |
| 无「敬请期待」 | 遍历 | 不存在页（如发票说明）**不显示** | `03` |

### E5 版本检查与强更（41b 弹窗）

| 验收点 | 造法 | 看到什么 | 截图 / 断言 |
|---|---|---|---|
| 有新版本 → 发现新版本 + 去更新 | `app.version.latest=1.0.1` | 「发现新版本」、`v1.0.0 → v1.0.1`、「可更新」、「本次更新」列表（从 changelog 读）、「稍后再说」+「立即更新」 | `33-update-optional.png`；eval `{"optionalOpen":true,"hasLaterButton":true}` |
| **`current < minVersion` 强更不可关** | `app.version.min=9.9.9` | 「需要更新后才能继续使用」、橙色「必须更新」、原因说明、**只有「立即更新」一个按钮**；点遮罩后弹窗仍在 | `34-update-force.png`；eval `{"forceOpen":true,"hasLaterButton":false,"stillOpenAfterMaskClick":true}` |
| 相等 → 已是最新 | 恢复 1.0.0/1.0.0 | toast「已是最新版本」，无弹窗 | `35-update-latest.png`；eval `{"latestToast":true,"modalOpen":false}` |
| 不新增第 69 页 | — | 实现为 `UpdateModal.tsx`，挂在设置页 | 代码 |

## 四、页面四态

| 页面 | 加载 | 空 | 错误 | 正常 |
|---|---|---|---|---|
| 设置 | 本地默认值先渲染（不阻塞） | 不适用 | profile 失败 → `—` / 「去认证」 | ✅ |
| 企业认证 | 骨架 | 不适用 | 错误态 + 重试 | ✅ 四态 |
| 收藏 | 骨架 | 自定义空态块（`27`） | 错误态 + 重试（`10`） | ✅ |
| 关注 | 骨架 | 自定义空态块（`38`） | 错误态 + 重试（`11`） | ✅ |
| 关于 | 配置读取不阻塞 | 不适用 | 电话退化「客服电话待配置」 | ✅ |
| 全部功能 | 不适用 | 不适用 | 不适用 | ✅ |

## 五、偏差与降级

1. **未安装 `@react-native-async-storage/async-storage`**（package.json 无此依赖，新增依赖要登记依赖清单且不在本任务写作用域）。
   `features/settings/storage.ts` 做等价封装：Web 用 `localStorage`（验收环境即 Web，重启浏览器保持）；
   原生端退回进程内 Map，**真机重启会丢字体/深色设置**。段 8 装上后只改 `readRaw/writeRaw` 两处。
2. **未安装 `expo-file-system`**：缓存大小 Web 口径 =「`performance` 资源字节 + Cache Storage + `faze.cache.*` 键 − 清除基线」，
   实测「约 3.6 MB → 约 0 B」；原生端返回 0（不编数字），真机 MB 级图片缓存待段 8 接 `expo-file-system`。
3. **深色模式只做开关与持久化**，不做主题令牌替换（工作流明确挂段 8）。
4. **企业认证不采集「法定代表人身份证号」**：设计稿有这一列，但段 1 的 `EnterpriseBO` 没有该字段；
   少收一份敏感信息，记 §十一。
5. **关于页没有客服邮箱**：`service@faze.example` 是占位域名，工作流只要求电话从 `sys_config` 读，
   邮箱无服务端来源，编一个就是假联系方式，故不实现。
6. **「实名认证信息处理说明」指向隐私政策页**：段 1 只有协议与隐私两页，不新造页面、不做「敬请期待」。
7. **全部功能页 20 个入口**（设计稿 18 + 工作流点名的意见反馈/关于）。
8. **「退款申请」入口不带订单 id**：目标页真实存在；无 id 时页面显示「请求的内容不存在」错误态（不填假数据）。
9. **收藏/关注未做无限滚动**：接口有 `cursor/hasMore`，页面首屏 + 「没有更多了」；分页加载记段 8 前补。
10. **收藏计数与列表可能不一致**：后端设计为「目标已下架/删除时不展示该行，但计数保留」
    （实测 article 2/3 是 `archived` → 文章 Tab 显示 3 但列表只 1 条）。这是后端 VO 注释里写明的取舍，前端如实渲染。
    已在本轮验收里用造数把 7 条收藏全部造出来验证了计数正确性。
11. **验收造的数据已清理**：临时第 3 个律师（`TEST-SEG7-0003`）、临时案例（`TEST-SEG7-E3-001`）、
    9324 的企业认证行、软删的收藏/关注行均已恢复/删除；`sys_config` 版本键恢复 1.0.0/1.0.0。
    造数脚本保留在 `tmp/seg7e/seed-e3.sql`、`seed-enterprise-*.sql`、`seed-version-*.sql`，需要复核可重跑。
    副作用：`app_user.last_login_at`（id=1、9080）与 `lawyer.online_status` 被本轮验收改过，已把
    online_status 恢复原值；last_login_at 原值未记录，仅影响「X 天前在线」文案。

## 六、仍未接通 / 已在代码里挂账

| 项 | 现状 | 怎么补验 |
|---|---|---|
| 关注页「在线 + 有新动态」的「新动态」 | 后端目前只有 `feedType=reply/none`，没有文章动态 feed；在线律师的动态区显示「暂无新动态」 | 后端补 article feed 后页面已有渲染分支（`item.feedTitle` 非空即用它） |
| 左滑在真机 | Web 已用 CDP 鼠标拖拽验证通过；原生端同一 `Swipeable` 实现，未真机验 | 段 8 有真机时补一次 |
| 字体倍率的全局生效 | 只在本段新页与设置页内部应用（工作流验收点只要求设置页自身变化）；其它段的老页面仍用固定字号 | 段 8 主题层统一接 `fontScale` |
| 强更的「立即更新」 | 真实热更新通道段 8 才做；本段打开 `downloadUrl`，为空时提示「下载地址暂不可用」 | 段 8 |

## 七、坑与结论

1. **`textAlign:'end'` 会让 `StyleSheet.create` 塌成联合类型**（上一批踩过）：本批只用 `'center'`/`'auto'`/`alignSelf`，无 TS2322。
2. **Ionicons 的 `scale` 码位在打包字体里渲染成别的字形**（glyphmap 与字体版本错配）：CDP 读到 `char=f553`（=scale）
   但屏幕显示「i」。品牌天平改用 `react-native-svg` 画（设计稿 path 原样搬），关于页与「我的」页 hero 都换了；
   其余页面装饰图标未逐个排查（记挂账）。
3. **Metro 在 Windows 的 FallbackWatcher 会在新建文件时 ENOENT 崩掉**（Lead 已定位）：8090 全白时先 `curl` 确认 200。
4. **PowerShell 5.1 的 `Get-Content -Raw`（无 `-Encoding`）按 ANSI 读 UTF-8 无 BOM 文件会乱码**：
   本批两次踩坑——①用 `Set-Content` 改 `AboutPage.tsx` 把整文件写成乱码（tsc 仍能过因为代码是 ASCII，注释全坏），
   已用 `write` 整文件重写修复；②用 `-replace` 改 `tmp/seg7e/fav-tab.js` 的匹配串失败且把文件写坏，
   改成新文件名 + `write` 工具写。**结论：改含中文的源文件一律用 edit/write 工具，不用 PowerShell 文本管道。**
5. **expo-router typedRoutes**：新增路由壳后 `routes.ts` 的 `as Href` 依赖 `.expo/types` 生成；
   本轮 dev server 在跑、类型已生成，`tsc` 无 TS2352。干净环境先启动一次 dev server。
6. **CDP 逐入口点击会中断**：点「我的文书」等入口会触发整页导航，`Runtime.evaluate` 上下文销毁
   （`Inspected target navigated or closed`）。改为「直接导航 20 个目标路由 + 读首屏文本」（`route-walk.mjs`）。
7. **`element.click()` 对底部 ActionBar 里的按钮不一定生效**：企业认证「重新提交」用 `.click()` 无反应，
   改用 CDP 真实鼠标坐标点击（`enterprise-resubmit2.mjs`）通过。以后验收底部固定栏按钮用坐标点击。
8. **后端计数是字符串**：`/app/social/favorites` 的 `counts` 是 `{"article":"3"}`（Long→String 全局序列化），
   前端在 api 层归一成 number，否则「减 1」会拿到字符串。
