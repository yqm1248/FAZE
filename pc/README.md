# pc/ —— PC 管理后台（源码已入库）

> **2026-11-05 结构变更：RuoYi 源码已从「外部路径指针」改为**入库**。**
> 触发条件正是本文件旧 §5 写的第 1 条：「段 1 结束时 biz 业务页 ≥ 5 个」——第 1 段 F 组交付了 7 个，
> 再让它们躺在仓库外的目录里等于**一半交付物没有版本管理**。项目负责人 2026-11-05 拍板入库。

## 1. 目录结构

```text
pc/
├─ admin-web/      PC 前端   RuoYi-Vue3 3.9.2（Vue 3.5.26 / Element Plus 2.13.1 / Vite 6.4.1）
└─ admin-server/   PC 后端   RuoYi-Vue 3.9.2 官方发行版（tag v3.9.2，Spring Boot 4.0.3 / Java 17）
```

这两个目录与仓库里其它两处平行：

| 端 | 位置 | 说明 |
|---|---|---|
| APP 端 | `apps/mobile-app/` | Expo SDK 54 / RN 0.81 |
| 业务 API | `services/faze-api/` | Spring Boot 4.0.6，提供 `/api/v1/**` |
| **PC 前端** | `pc/admin-web/` | 8080 → 开发端口 **80** |
| **PC 后端** | `pc/admin-server/` | 端口 **8080**，只负责登录/菜单/权限，业务接口在 faze-api |

> 判版本只看两处：`pc/admin-server/pom.xml` 的 `ruoyi.version` / `spring-boot.version`，
> 与 `pc/admin-web/package.json` 的 `version`。**不要看 git tag**
> （`springboot3` 分支的 tag 停在 `v3.8.7`，与官方发行线不是一回事）。

## 2. 怎么启动

```powershell
# 后端（8080）
cd pc\admin-server\ruoyi-admin
mvn -B spring-boot:run

# 前端（80）
cd pc\admin-web
pnpm install          # 首次；必须用 pnpm，npm 11 解析 RuoYi 的 lock 会抛 Invalid Version
pnpm dev
```

依赖与构建产物**不入库**（`.gitignore` 已排除 `node_modules/`、`target/`、`dist/`），
所以新克隆下来必须先装依赖、后端起服务前也要先 `mvn install` 一次（同一 reactor 里
`ruoyi-framework` 等兄弟模块要先装进本地仓库）。

登录：`admin` / `admin123`（`faze_admin` 库，**不是**业务库 `faze`）。

## 3. 业务代码落点（AGENTS §3.3）

- 页面：`pc/admin-web/src/views/biz/<domain>/index.vue`
- 接口：`pc/admin-web/src/api/biz/<domain>.js`
- 业务接口的 axios 实例：`pc/admin-web/src/api/biz/request.js`（**独立实例**，前缀 `/biz-api` 代理到 faze-api:8081）
- **不得修改 RuoYi 框架层**（`src/utils/request.js`、`src/permission.js`、`src/router/**`、`src/layout/**`、`src/store/**`）。
  确需改动走配置覆盖，并登记 `docs/analysis/ruoyi-patches.md`。

## 4. 与官方发行版的差异

本仓库里的 `pc/admin-web` 与 `pc/admin-server` 是在官方 3.9.2 发行版上**打了本地改动**的，
逐条登记在 `docs/analysis/ruoyi-patches.md`（数据源分库、前端 pnpm 安装方式、vite 代理 +
watch 轮询、与 faze-api 的跨服务鉴权耦合点）。升级 RuoYi 时先读那个文件。

- `pc/admin-server/ruoyi-admin/src/main/resources/application-druid.yml` 指向 **`faze_admin`** 库。
- `pc/admin-web/pnpm-workspace.yaml` 是新增文件（`nodeLinker: hoisted` + `allowBuilds`）。
- `pc/admin-web/vite.config.js` 增加了 `/biz-api → 127.0.0.1:8081` 代理与 `server.watch` 轮询。

## 5. 段 0 / 段 1 的范围

- **段 0**：只做 A-01 登录（RuoYi 自带登录页，不改框架层）+ 一个 biz 菜单占位页，用于 `0-A6`。
- **段 1（F 组）**：A-03~A-09 七页 + 21 个 admin 接口 + 菜单种子 `database/seed/seg1_admin_menu.sql`。

## 6. 原始外部路径（仅作来源留档，**不再是工作目录**）

| 项 | 值 |
|---|---|
| 原件来源 | `E:\itheima\SSM\3、SSM框架（…）\文件\RuoYi-Vue`（后端）、`…\文件\RuoYi-Vue3`（前端） |
| 入库时版本 | RuoYi-Vue `release-3.9.2` / `0e2d75c2`；RuoYi-Vue3 `v3.9.2` / `HEAD 9baaff7` |
| 入库日期 | 2026-11-05 |

> 外部原件**保留未删**，作为对照基准；今后一切修改只改 `pc/` 下的副本，别再改外部原件。
