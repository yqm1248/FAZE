# 第 3 段 H3 · 菜单挂载 + 权限 + 框架边界核验

> 施工日期：2026-11-07 · 分支 `UI设计` · 环境：RuoYi 3.9.2（前端 :80 / 后端 :8080 / 库 `faze_admin`）+ faze-api :8081
> 对应工作流：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` H3（L1430 起）
> 施工人：pc-admin（task-2）· 截图与断言：`deploy/verify-h-pages.mjs`（真实浏览器 CDP，1440×900）

---

## 一、结论速览

| H3「算做完」条目 | 结果 | 证据 |
|---|---|---|
| 管理员登录 → 左侧「内容管理」下有「法条库」 | ✅ 通过 | `_artifacts/h3-sidebar-1440x900.png`、`h1-a27-law-library.png` |
| 管理员登录 → 左侧「系统设置」下有「AI 对话检索」 | ✅ 通过 | `_artifacts/h2-a28-ai-retrieval.png` |
| 点进去能操作 | ✅ 通过（列表真实数据 10 / 10 条，详情/修正/导入弹窗均能打开） | `h-pc-verify.json` 31/31 PASS |
| 换个没有该权限的角色 → 看不到菜单、直接调接口返回 403 | ⚠️ **后端做不到**，见 §四「偏差」 | — |
| 1440×900 展开「内容管理」，侧边栏无滚动条 | ✅ 通过（wrap client=850 / scroll=850） | `h3-sidebar-1440x900.png` |
| `git status` 无 `src/{layout,components,router,store,utils}` 改动 | ✅ 通过（命令输出为空） | `h-pc-verify.json` / 本文件 §五 |
| `docs/analysis/ruoyi-patches.md` 存在且登记实测结果 | ✅ 0 条新 patch（已追加「第 3 段核对」章节） | `docs/analysis/ruoyi-patches.md` |

---

## 二、菜单挂载（`database/seed/seg3_admin_menu.sql`）

**目标库：`faze_admin`**（RuoYi 框架库；业务库是 `faze`，两份库不混用）。
**菜单 id 段：2200~2209**（segi 占 2100~2108；段 2 手工插库占 2109~2112 且未入仓库 seed；本段从 2200 起）。

执行方式（幂等，`ON DUPLICATE KEY UPDATE`）：

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" --host=127.0.0.1 --port=3306 `
  --user=root --password=*** --database=faze_admin --default-character-set=utf8mb4 `
  -e "source E:/dshcode/FAZE/database/seed/seg3_admin_menu.sql"
```

执行后实测（`SELECT menu_id, menu_name, parent_id, order_num, path, component, route_name, menu_type, perms, icon FROM sys_menu WHERE menu_id BETWEEN 2200 AND 2209`）：

| menu_id | 菜单名 | parent | order | path | component | route_name | 类型 | perms |
|---|---|---|---|---|---|---|---|---|
| 2200 | 内容管理 | 0 | 5 | content | — | — | M | — |
| 2201 | 法条库 | 2200 | **5** | law-library | `biz/ai/law-library/index` | `BizAiLawLibrary` | C | `biz:law:list` |
| 2202 | 法条查询 | 2201 | 1 | — | — | — | F | `biz:law:query` |
| 2203 | 法条修正 | 2201 | 2 | — | — | — | F | `biz:law:edit` |
| 2204 | 法条导入 | 2201 | 3 | — | — | — | F | `biz:law:import` |
| 2205 | 法条失效 | 2201 | 4 | — | — | — | F | `biz:law:disable` |
| 2206 | 系统设置 | 0 | 6 | setting | — | — | M | — |
| 2207 | AI 对话检索 | 2206 | **4** | ai-retrieval | `biz/ai/ai-retrieval/index` | `BizAiRetrieval` | C | `biz:ai:list` |
| 2208 | 对话检索查询 | 2207 | 1 | — | — | — | F | `biz:ai:query` |
| 2209 | 对话导出 | 2207 | 2 | — | — | — | F | `biz:ai:export` |

`sys_role_menu` 已给 **admin 角色（role_id=1）** 授权 2200~2209 共 10 行（实测 `SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id BETWEEN 2200 AND 2209` 返回 10 行）。

**为什么 order_num 留空位**：设计稿要求 A-27 是「内容管理」第 5 项、A-28 是「系统设置」第 4 项。前 4 项（文章列表 / 案例库 / 多语言翻译 / 文书模板、系统参数 / 敏感词 / 日志中心）属于后续段，当前菜单树里还没有。RuoYi 只按 `order_num` 排序、不渲染空位，所以先写 5 / 4，等后续段补齐时位置自然对，不用再搬。

**`is_frame` 坑（复用段 1 结论）**：RuoYi 的 `is_frame` 是「是否外链」，**0 = 是外链，1 = 否**；目录必须写 1，否则路由 path 不补前导 `/`，整页白屏。本 SQL 目录与页面均写 1。

---

## 三、设计稿 IA vs 当前库结构（**必须有这一节，别把偏差当 bug**）

设计稿 `design/admin/shell.js` L81–124 是 **11 组一级导航**：
数据看板 / 用户管理 / 律师管理 / 订单管理 / 财务管理 / **内容管理** / 案件管理 / 投诉工单 / 消息管理 / **系统设置** / 系统管理。

当前 `faze_admin.sys_menu` 实际是 **5 组一级**（系统管理 / 法泽业务 / 系统监控 / 系统工具 / 若依官网）+ 本段新增的 2 组（内容管理 / 系统设置）：

| 事实 | 数量/位置 |
|---|---|
| 段 1 建的扁平目录「法泽业务」(2100) | 8 页（业务总览 / 用户 / 实名审核 / 律师入驻 / 律师列表 / 余额充值 / 系统参数 / 对账报表） |
| 段 2 补的菜单 | 4 页（订单管理 / 订单详情[隐藏] / 文章管理 / 文章编辑[隐藏]），**只手工插了开发库、未进仓库 seed**（既有债务，由 Lead 记 §十一，本段不修） |
| 本段新增 | 「内容管理 → 法条库」「系统设置 → AI 对话检索」2 页 |
| 当前侧边栏一级目录总数 | 7 个（含 RuoYi 自带 3 个目录 + 若依官网外链）+「首页」直达项 |

**本段做了什么、没做什么**：

- 做了：按 H3 的挂载表**新建**两个一级目录，只挂新增 2 页；`order_num` 按设计稿位置写 5 / 4。
- 没做：**没有把既有 12 页从「法泽业务」迁走**。迁移会改既有菜单的 `parent_id`，让段 1/段 2 的入口路径与验收截图全部变化，属整体 IA 改造；本段只保证「新增两页的挂载位置符合设计稿」。
- 归口：设计与现状的整体 IA 收口（11 组 vs 5 组的合并/拆分、段 2 4 页补 seed）**归段 8**，本段不越界。

---

## 四、权限串与前端控制

### 4.1 权限串清单（与工作流 H3 逐字一致）

| 页面 | 页面/入口权限（C 菜单 `perms`） | 按钮权限（F 菜单 `perms`） | 前端使用点 |
|---|---|---|---|
| A-27 法条库 | `biz:law:list` | `biz:law:query` / `biz:law:edit` / `biz:law:import` / `biz:law:disable` | 查看/查询 → query；修正 → edit；批量导入 → import；标记失效 → disable |
| A-28 AI 对话检索 | `biz:ai:list` | `biz:ai:query` / `biz:ai:export` | 查询/查看消息 → query；导出本条记录 → export |

页面里所有按钮都挂了 `v-hasPermi="['biz:law:edit']"` 这类指令；权限串都已写进 `sys_menu.perms`（F 菜单），非超管角色被授对应菜单后按钮才会渲染。

### 4.2 偏差：后端只有角色门槛，无方法级权限串校验（**待负责人决策**）

- 事实：`faze-api` 的 admin 侧只做 `AdminAuthenticationFilter` + `hasRole('ADMIN')` 门槛，`AdminLoginUser` 不携带 `permissions`；接口规范 §6.2 已裁定「只校验角色，不做数据级权限细分」，所以**没有** `@PreAuthorize("@ss.hasPermi(...)")`（写了会因没有 `@ss` bean 启动失败）。
- 后果：H3 原文「换个没有该权限的角色 → 看不到菜单、直接调接口返回 403」**后端这一半做不到**——同一个 ADMIN 角色直连接口不会被权限串拦；前端 `v-hasPermi` 只影响按钮是否渲染，是可以绕过的 UI 层控制。
- 本段的处理：如实实现前端权限控制（菜单 + 按钮），**没有伪造 403 结果**，也没有为了让验收好看去加假的权限拦截。
- 待决策：是否由负责人拍板在 faze-api 补方法级权限串校验（需要 `AdminLoginUser` 带 permissions，并新增 `@ss`-like bean）；否则权限验收只能按「前端菜单/按钮可见性」判。
- 另：当前库在用角色只有 `role_id=1`（超级管理员，`role_key=admin` → permissions `*:*:*`）。非超管角色的「看不到菜单」实测**未做**，因为本段后台按计划只有管理员一个角色（PC 计划 §25 / 接口规范 §6.2）。如果负责人要求补，可新建测试角色 → 只授部分 `sys_role_menu` → 复跑 `deploy/verify-h-pages.mjs` 即可（脚本会打印菜单文本，可扩展断言）。

---

## 五、1440×900 侧边栏高度实测

方法：CDP（`deploy/verify-h-pages.mjs`）固定 `Emulation.setDeviceMetricsOverride 1440×900`，登录后测 `.sidebar-container .el-scrollbar__wrap` 的 `clientHeight / scrollHeight`。

| 状态 | wrap client | wrap scroll | 是否出滚动条 |
|---|---|---|---|
| 展开「内容管理」（设计稿验收场景） | 850 | 850 | **否** ✅ |
| 再展开既有「法泽业务」（10 个可见子项） | 850 | 864 | **是**（+14px） |

截图：`_artifacts/h3-sidebar-1440x900.png`（内容管理展开，无滚动条）、`_artifacts/h3-sidebar-biz-expanded.png`（法泽业务也展开，底部「系统设置」被压到视口外）。

**结论**：H3 的验收场景（展开内容管理）**通过**；法泽业务展开会超高的原因是既有 IA 债务——设计稿假设「11 组一级，最多一组 5 项」，实际「法泽业务」一组合并了段 1+段 2 的 10 个可见子项。本段**没有改 `src/assets/styles/sidebar.scss`**（框架样式），也没有为了消滚动条加全局 CSS；整体 IA 合并归段 8。若段 8 前就必须消滚动条，建议改「法泽业务按域拆分」，而不是压菜单高度。

---

## 六、框架边界核对

```powershell
git status --short -- pc/admin-web/src/layout pc/admin-web/src/components pc/admin-web/src/router pc/admin-web/src/store pc/admin-web/src/utils pc/admin-web/src/directive
# 输出为空
git status --short -- pc/admin-web/src
# ?? pc/admin-web/src/api/biz/ai/
# ?? pc/admin-web/src/views/biz/ai/
```

- 业务代码只新增在 `src/api/biz/ai/` 与 `src/views/biz/ai/`，符合 AGENTS §3.3。
- 未改 `src/utils/request.js` / `src/permission.js` / `src/router/**` / `src/layout/**` / `src/store/**`。
- `docs/analysis/ruoyi-patches.md` 已追加「第 3 段（H3）框架层核对 · 2026-11-07：0 条新 patch」，历史条目未动。

---

## 七、复现与证据

```powershell
# 1) 菜单（幂等）
mysql ... --database=faze_admin < database/seed/seg3_admin_menu.sql
# 2) 浏览器实测（Chrome 需先以 --remote-debugging-port=9222 启动；:80/:8080/:8081 在跑）
node deploy/verify-h-pages.mjs            # 真实接口模式：31/31 PASS
node deploy/verify-h-pages.mjs --mock     # 布局自查（CDP 拦截 API 回放 mock）：27/27 PASS
```

证据文件（`docs/acceptance/seg3/_artifacts/`）：

- `h3-sidebar-1440x900.png`、`h3-sidebar-biz-expanded.png`
- `h1-a27-law-library.png`、`h1-a27-view-dialog.png`、`h1-a27-amend-dialog.png`、`h1-a27-import-dialog.png`
- `h2-a28-ai-retrieval.png`、`h2-a28-refs.png`
- `h-pc-verify.json`（真实接口模式，31/31）、`h-pc-verify-mockdata.json`（布局自查，27/27）
- 文件名带 `-mockdata` 的截图是 **CDP 拦截 API 回放的固定数据**，只用于布局自查，**不是接口验收证据**。

---

## 八、未完成 / 风险

1. **「换无权限角色 → 403」后端做不到**（见 §四.2），已如实记为偏差，待负责人决策。
2. 非超管角色的菜单可见性未实测：当前库只有 admin 一个角色在用（PC 计划只做管理员）。
3. 1440×900 之外的分辨率未测（H3 只要求 1440×900）。
4. 侧边栏「法泽业务展开超高」是既有 IA 债务，本段未修（归段 8），已留截图证据。
