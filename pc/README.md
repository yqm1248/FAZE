# pc/ —— PC 后台指针（RuoYi 源码不在本仓库）

> **本目录只放指针与纪律，不放 RuoYi 源码。** 依据《第 0 段工作流》§三「裁决 2」与 `AGENTS.md` §3.3。

## 1. 源码在哪

| 项 | 值 |
|---|---|
| 路径 | `E:\itheima\SSM\3、SSM框架（Spring框架+SpringMVC框架+Maven高级+SpringBoot框架+MyBatis-Plus）\文件\RuoYi-Vue` |
| 分支 / HEAD | `release-3.9.2` / `0e2d75c2`（= tag `v3.9.2`） |
| 版本 | `ruoyi.version=3.9.2`、`spring-boot.version=4.0.3`、`mybatis-spring-boot.version=4.0.1`、Java 17 |
| 前端 | 仓库自带 `ruoyi-ui` 是 Vue 2.6.12 + element-ui（**不是项目前端**）；项目前端用 RuoYi-Vue3 **3.9.2**（Vue 3.5.26 / Element Plus 2.13.1） |

> **判版本只看两个字段**：`pom.xml` 的 `<ruoyi.version>`、`package.json` 的 `"version"`。不要看 git tag——`springboot3` 分支的 tag 停在 `v3.8.7`，与官方发行线不是一回事。

## 2. 怎么启动（段 0 只要求「能登录 + 侧边栏出现 biz 菜单」）

1. 建 RuoYi 自己的库（**不是 `faze`**），执行 `sql/ry_20260320.sql` 与 `sql/quartz.sql`；
2. 改 `application-druid.yml` 指向该库；Redis 由 `deploy/start-services.ps1` 起（RuoYi 登录态存在 Redis）；
3. 起后端（默认 8080）：`mvn -pl ruoyi-admin spring-boot:run`；
4. 起前端：`cd ruoyi-ui && npm install && npm run dev`（vite proxy 目标 `http://localhost:8080`）。

## 3. 业务代码落点（AGENTS §3.3）

- 页面：`src/views/biz/<domain>/`
- API：`src/api/biz/<domain>/`
- **不得修改 RuoYi 框架层代码**；确需改动时走配置覆盖或独立扩展，并登记到 `docs/analysis/ruoyi-patches.md`。

## 4. 段 0 的范围

只做 **A-01 登录**（RuoYi 自带登录页，不改框架层）+ 一个 `biz` 菜单占位页，用于 `0-A6` 的「能登录并看到 biz 菜单页」。

## 5. 何时改判为 subtree

满足任一条即从「外部路径」升级为 **subtree**（不是 submodule）：段 1 结束时 biz 业务页 ≥ 5 个；出现第 2 个需要改 PC 代码的开发者；`docs/analysis/ruoyi-patches.md` 里框架层 patch 条目 > 3 条。
