# 法泽 FAZE · 后端代码规范（V1.0）

> **用途**：`services/faze-api`（Spring Boot 4.0 / Java 17）的代码规范。三端之中**只有服务端**适用本文件；移动端看《代码注释规范》与 `docs/standards/`，PC 后台看 RuoYi 约束。
> **定稿日期**：2026-10-30（2026-11-01 修订：Spring Boot 3.5 → 4.0）
> **参考来源**：`D:\gitzxz\zxz-bi-server`（Spring Boot **3.5.3** / JDK 17，324 个 java / 30,244 行）、`C:\Users\14541\Desktop\2026.7.13实习\wgcloud`（Spring Boot 2.7.12，342 个 java / 60,857 行）、`E:\项目文件\jxyd200`（monorepo，后端 RuoYi 二次开发，Spring Boot **3.5.14** / Java 17，672 个 java / 52,738 行）。
> **三个参考项目的后端全部是 Spring Boot 3.5 同代（ZXZ 3.5.3 / jxyd200 3.5.14），而法泽已升到 Spring Boot 4.0**——分层、注释、异常、事务、安全这些**业务级规则仍可直搬**；但**框架级 API 必须逐条核对**（Spring Security 6→7 的授权写法与 `SecurityFilterChain` 配置、自动配置类名、Jakarta 包路径）。**不能再声称「零成本直搬」。**
> **不重复的内容**：接口契约见《[法泽FAZE_接口规范_v1.0.md](法泽FAZE_接口规范_v1.0.md)》；表结构见《[法泽FAZE_数据表规范_v1.0.md](法泽FAZE_数据表规范_v1.0.md)》；错误码见《[法泽FAZE_错误码规范_v1.0.md](法泽FAZE_错误码规范_v1.0.md)》。
>
> **⚠️ 先读 §十四**：本文件的每条规则都配了「承接手段」。**答不上「谁在拦住违反它的人」的规则，就不该写进来**——这是从 jxyd200 的 1,138 行规范只有 7 条真落地中学到的。

---

# 一、先看问题：三个参考项目各自能给我们什么

## 1.1 三份实测快照（都是数出来的，不是印象）

| 项 | ZXZ | wgcloud | jxyd200（后端） | 说明 |
|---|---|---|---|---|
| Spring Boot | **3.5.3**（`pom.xml:8`） | 2.7.12（`pom.xml:16`） | **3.5.14**（`services/ruoyi-api/pom.xml:19-21`） | 参考项目均为 3.5；**法泽已升 4.0**，跨一个大版本 |
| JDK 声明 | **17**（`pom.xml:15`） | 1.8（`pom.xml:24`） | 17 | — |
| Java 文件 / 行 | 324 / 30,244 | 342 / 60,857 | 672 / 52,738 | — |
| 后端形态 | 单体 Spring Boot | 单体 Spring Boot | **RuoYi 二次开发，Maven 多模块** | jxyd200 与法泽的 PC 后台同源 |
| ORM | MyBatis-Plus 3.5.12 BOM，但**真实业务 100% 自定义 XML，Wrapper 零使用** | MyBatis + PageHelper，全 XML | MyBatis-Plus | 法泽用 MP **3.5.18.1**：`mybatis-plus-spring-boot4-starter` **加** `mybatis-plus-jsqlparser`（分页插件在后者里，缺了编译失败）。3.5.12 在 SB4 下静默失效，2026-11-01 实测，见 `docs/acceptance/seg1/A3-mp-probe.md` |
| `@Aspect` 数量 | **0** | **0** | 未用 | **三个项目都没有操作日志切面** |
| `${}` 在 XML 中 | **0 处**（177 个 select 全 `#{}`） | **49 处**（全是 `ORDER BY t.${orderBy} ${orderType}`） | — | 一个正例、一个反例 |
| `@Operation` 覆盖 | **46 / 46 = 100%** | 0 | — | — |
| `@Schema` 覆盖 | 1,576 处（好） | 0 | **全后端 1 处**（springdoc 引了但为空壳） | jxyd200 是反面 |
| `@Validated` 使用 | 21 处 / 12 处 `@Valid` | 极少 | **61 个 Controller 只有 9 个（15%）** | 规范写了但不落地 |
| `@Transactional` 数量 | **1 处** | 0 | 极少 | 都是读多写少场景的产物，见 §1.3 |
| 最大单类 | `IndexServiceImpl` **4,734 行** | `DashboardService` 1,285 行 | — | 两个项目的反面 |
| 测试 | 1 个类 | 0 | **2 个 Java 文件**（靠 javac 手搓，不走 surefire） | 三个项目的共同债 |
| CI | 有 | 无 | **无**（无 `.github/`、无 husky、无 commitlint） | 无 CI = 所有规则靠自觉 |

## 1.2 三个项目「抄什么 / 不抄什么」

| 来源 | 抄（有据可依） | 不抄（有据可依） |
|---|---|---|
| **ZXZ** | **①注释量**（`@param` 668 / `@return` 299 / `@Schema` 1,576 / `@Operation` **46 个接口全覆盖 100%** / `@Tag` 9/9，见 §1.4）；**②注释的工程规范性**（类头与方法模板固定、`@Schema(requiredMode=REQUIRED)` 与 `@NotBlank` **成对出现**、OpenAPI 文档与代码同步，见 §1.4）；**③目录结构的工程规范性**（**技术分层 + 业务子包双维度**：`dto/xzb/`、`mapper/dm/`+`mapper/mysql/`、`rule/calculation/bigdecimal/{fixed,incentive,insurance}/`，见 §2.1）；④`bo`(入参)/`dto`(出参) 分离；⑤13 分支异常处理器 + 校验异常**聚合 msg**；⑥`ThreadPoolConfig` 全参数化线程池；⑦`logback` 三文件 + `AsyncAppender` + `discardingThreshold=0`；⑧计算规则「枚举持 Class + 容器取 Bean」；⑨导出「策略 + 工厂 + 路由表」；⑩`BigDecimalUtils` 统一舍入；⑪双数据源 `DataSourceConfig` 结构 | ①Controller 里写业务（`BIController:231-298` 68 行日期回退）；②上帝类；③手拼 JSON 做第二套响应（`RecordController:66-72`，`msg` 变 `message`）；④异常 `e.getMessage()` 透传；⑤**只验 token 不验归属**（`ZQTAuthInterceptor:34-53`）；⑥明文密钥进 git（`application-prod.yml:6,27,43,76,93`）；⑦写了不读的死开关（`application-dev.yml:89-91` vs `ZQTConfig:15-35`）；⑧`RedisUtil` 里 17 处 `printStackTrace` |
| **wgcloud** | **①安全机制最完整（11 项，逐项见 §1.5）**——统一鉴权 Filter（`AuthRestFilter` 363 行）、免登录白名单、只读角色、**登录失败双窗口锁定**（1 分钟计数 / 10 分钟封禁）、**密码复杂度四项可配置且返回具体失败原因**（`PasswordPolicyUtil` 104 行）、密码过期与剩余天数、agent 通信 token、License 校验、上传大小限制、命令注入黑名单、日志分 4 类落库；②**agent 主动 pull + DB 任务表**（§9.2 直接采用，对 MQ 替换最关键）；③显式 `TaskScheduler` 线程池（§9.3）；④攒批落库；⑤「当前状态表」先删后插（幂等且免 upsert 方言差异）；⑥异常「文件 + 库表」双写；⑦`compressChartListData` 等间隔降采样 | ①明文生产凭据（`config/prod/*.yml` 5 份）；②**7 个 agent 接口未鉴权且同时在该白名单里**（`DbTableController:73`、`DceInfoController:72`、`FtpInfoController:62`、`HeathMonitorController:71`、`K8sMonitorController:69`、`LogInfoController:58`、`SnmpInfoController:70`——有校验的是另外 8 个，18 个里漏 7 个，见 §11.1）；③49 处 `${}` 拼 `orderBy`；④前端算 MD5 + 后端直查（pass-the-hash）；⑤`StaticKeys` 293 行「全局变量垃圾桶」（常量与可变状态混放）；⑥静态字段 + `getBean()`；⑦无界队列线程池（`ThreadPoolUtil:18`，`maxPoolSize` 永远无效）；⑧统一异常出口缺失导致 55 个 Controller 复制「异常写库」 |
| **jxyd200** | ①**全局异常处理器「对外稳定文案 + 对内完整日志」双轨，且把「为什么」写进 Javadoc**（`GlobalExceptionHandler.java:105-117`，全仓最值得抄的单点）；②`rule/` 纯 POJO 业务规则层，可脱离 Spring 用 `javac` 直接单测；③**分层职责一句话可判定**（`biz/_template/README.md:17`：「Controller 不能写 SQL、事务、金额计算或状态流转」）；④**每个业务模块目录必须有 README，实测三层 100% 落地**（APP 14/14、Java 15/15、网关 7/7）；⑤`{@inheritDoc}` + 只在实现有偏差处写一句原因（`TraceabilityServiceImpl:26-50`）；⑥状态中文名单一来源（后端 VO 同时给 `code` 与 `label`） | ①`Map<String,Object>` 收 body + 手写 `toLong/toInteger` 适配层（61 个 Controller 里 11 个这么写）；②直接注入 Mapper（9 个，违反自己的模板）；③`@Schema` 全后端 1 处、springdoc 装了是空壳；④`assets/01~11` 编号文档集（04 篇整篇写错技术栈却没人发现）；⑤三风格注释并存（`@author` 456 / `@Description` 185 / `@since` 98） |

## 1.3 一条必须写在前面的判据：法泽是**写密集**，不能照搬 ZXZ 的取舍

ZXZ 是**读多写少**（177 个 select / 7 个写语句），所以它「几乎不用 `@Transactional`、不用 MP Wrapper、不做乐观锁」是**场景决定的合理取舍**，不是规范缺陷。

**法泽不是。** 法泽的核心链路全是写：下单扣余额、支付回调、消息发送、审核流转、文书生成。同一套做法搬过来会出事：

| ZXZ 的取舍 | 在法泽直接照搬的后果 | 法泽的规定 |
|---|---|---|
| 全项目仅 1 处 `@Transactional` | 扣余额 + 写流水不在一个事务 → 钱扣了流水没写 | **§7 事务**：涉及资金与状态机的写操作必须显式事务 |
| 不用 MP Wrapper、全手写 XML | 简单 CRUD 也要写 XML，且拼条件时容易出错 | **§8.1**：单表 CRUD 用 Wrapper，多表/复杂查询用 XML |
| 无乐观锁 | 并发扣余额会超扣 | **§8.5**：余额与库存类字段必须乐观锁或 SQL 条件更新 |
| 无 AOP | 操作日志靠前端埋点写库（ZXZ 的做法），**能伪造** | **§10.4**：操作日志必须服务端 AOP 采集，不信前端 |

## 1.4 ZXZ 的三个结构性长处

§1.3 讲的是「它的取舍能不能照搬」，这一节讲**它真正强的地方**——这三条是它作为「工程规范性范本」的价值所在。

### 1.4.1 注释量：不是「有注释」，是「每个公开契约都有注释」

| 指标 | 实测值 |
|---|---|
| `@param` | **668 处** |
| `@return` | **299 处** |
| `@Schema`（字段级文档） | **1,576 处** |
| `@Operation` | **46 处 = 46 个接口，覆盖率 100%** |
| `@Tag` | **9 / 9 个 Controller** |
| 带类头的文件 | 214 / 324 |

**为什么这是「量」而不是「凑数」**：`@Operation` 46/46 意味着**每一个对外接口在 Swagger 里都有中文摘要**；`@Schema` 1,576 处意味着**几乎每个 DTO 字段都有业务说明**。两者合起来，`/v3/api-docs` 打开就是一份能交给前端的文档。

**对照 jxyd200**：springdoc 依赖引了，但全后端 `@Schema` **只有 1 处**——Swagger 是个空壳。**装了文档工具 ≠ 有文档。**

### 1.4.2 注释的工程规范性：模板固定 + 文档与校验成对

**类头模板（固定结构）**：

```java
/**
 * @Description: 薪资包预算执行情况DTO
 * @Author: yang
 * @Date 2025-12-30
 */
```

**方法模板（动作 + 参数 + 返回，固定三段）**：

```java
/**
 * 获取部门列表
 * @param request 部门列表请求参数
 * @return 部门列表
 */
```

**真正的「工程规范性」在第二层——文档与代码约束成对**：

```java
@Schema(description = "公司名称", requiredMode = Schema.RequiredMode.REQUIRED)
@NotBlank(message = "公司名称不能为空")
private String companyName;
```

`requiredMode` 与 `@NotBlank` 同时声明：**文档说必填，代码也校验必填**。这样不会出现「文档写必填、代码不校验」的漂移——而这是文档型契约最常见的腐烂方式。

**法泽取哪一半**：

| | 取 / 不取 | 理由 |
|---|---|---|
| 固定模板、方法必写动作/参数/返回 | ✅ **取** | 模板让「写注释」从创作变成填空，是覆盖率能到 100% 的前提 |
| `@Schema` 与校验注解**成对** | ✅ **取** | 文档与校验同源，杜绝漂移 |
| `@Operation` 覆盖**全部**接口 | ✅ **取** | 46/46 vs jxyd200 的 1 处，差距在这里 |
| 模板里的 `@Author` / `@Date` | ❌ **不取** | 见 §4.1：这两个字段在三个项目里全部腐坏（ZXZ 的 `@Date` 4 种格式含占位符、wgcloud 的恒为「2021年1月16日」） |

> **一句话**：学它的**密度与结构**，不学它的**元数据字段**。

### 1.4.3 目录结构的工程规范性：技术分层 + 业务子包「双维度」

ZXZ 的顶层**按技术角色**分，但**层内再按业务子包**切开：

```text
com.tfgc/
├─ controller/        9 个
├─ service/           9 接口 + 9 实现
├─ dto/              148 个  ← 出参
│   └─ xzb/                薪资包的出参
├─ bo/                14 个  ← 入参
├─ mapper/
│   ├─ mysql/               主库
│   └─ dm/                  达梦只读库
├─ rule/                    纯业务规则，可脱离 Spring 单测
│   └─ calculation/
│       └─ bigdecimal/
│           ├─ fixed/           固定薪资项
│           ├─ incentive/       激励项
│           └─ insurance/       社保项
├─ strategy/          2 + 18 个策略类
├─ enums/             24 个
└─ converter/         2 个
```

**三个可学的点**：

| 点 | ZXZ 怎么做 | 为什么这算「工程规范性」 |
|---|---|---|
| **层内按业务切子包** | `dto/xzb/`、`dto/xzb/qrhs/` | 同一层里 148 个 DTO 不堆一起，按业务域分开——**找文件靠目录，不靠全局搜索** |
| **按数据源分子目录** | `mapper/mysql/`、`mapper/dm/` | 「这个查询打哪个库」在目录上就能看出来，不用点开 XML |
| **规则按计算类别层层下钻** | `rule/calculation/bigdecimal/{fixed,incentive,insurance}/` | 新增一个薪资项 = 在 `fixed/` 下加一个类，**不改任何现有代码**；**目录结构本身在表达「可扩展点在哪」** |

**法泽的对应选择**：法泽用「**域优先**」（`modules/<域>/...`），因为技术路线 §4.2 定了 12 个业务域且有跨域调用禁令（§2.3），域优先能把「一个域一个目录」的边界做硬。**但 ZXZ 的「层内下钻」在域内部照用**：

```text
modules/trade/
├─ rule/fee/          → 抽成规则（对应 ZXZ 的 rule/calculation/*/）
├─ rule/refund/       → 退款规则
├─ vo/order/          → 订单出参（对应 ZXZ 的 dto/xzb/）
└─ vo/wallet/         → 钱包出参
```

## 1.5 wgcloud 的安全机制清单（它最强的维度）

**先给结论**：三个项目里 **wgcloud 的安全机制覆盖最完整（11 项）**——因为它是对公网暴露的运维监控系统；ZXZ 是内部 BI，只有 token 拦截器 + 一个加解密 Filter；jxyd200 更少。

**但要分开看「设计意图」和「实现质量」**：它的机制设计几乎都对，实现有硬伤。法泽要的是**它的机制清单**，不是它的代码。

| # | 机制 | 出处 | 设计意图（值得学） | 实现问题（不能抄） | 法泽怎么做 |
|---|---|---|---|---|---|
| 1 | **统一鉴权入口** | `AuthRestFilter.java`（363 行，`@WebFilter("/*")`） | 一个 Filter 拦全部请求，**不在每个 Controller 里写鉴权** | 白名单 40 条硬编码 + `startsWith` 前缀匹配 | Spring Security `SecurityFilterChain`；白名单集中在一个类、用**精确路径** |
| 2 | **免登录白名单** | 同上 `static_resource[]` | 显式声明「哪些不需要登录」 | 新增接口忘加就暴露；前缀匹配可被路径构造绕过 | `permitAll()` + 精确匹配 + 单测断言「未登录访问受保护接口 → 401」 |
| 3 | **只读角色** | 同上 `ROLE_GUEST` + `guest_no_views[]` | 有「只读账号」角色并限制其写操作 | **黑名单式**（`endsWith("/del")` 等 16 个词），新增写接口不在名单就默认放开 | 白名单式：`@PreAuthorize("hasAuthority('trade:write')")` 显式声明 |
| 4 | **登录失败双窗口锁定** | `LoginController:176-206` + `StaticKeys:204-212` | **两个 TTL 不同的窗口**：1 分钟内错 5 次 → 封禁 10 分钟；且记录登录 IP | 状态在单机 `ExpiringMap`，重启清零、多实例不共享 | 阈值同（5 次 / 10 分钟），**状态放 Redis**，key = `login:err:{phone}` |
| 5 | **密码复杂度（可配置 + 返回原因）** | `PasswordPolicyUtil:34-63`（104 行） | **四项全部可配**（最小长度 / 数字 / 字母 / 特殊字符），且 `validatePasswordComplexity()` **返回具体失败原因字符串**（空 = 通过）而不是布尔 | 无（这一项实现质量高） | 直接采用这个设计：返回原因、配置项进 `sys_config` |
| 6 | **密码过期 + 剩余天数** | `PasswordPolicyUtil:72-103` | `isPasswordExpired()` + `getPasswordRemainingDays()`（-1 过期 / 0 不限），可提前提醒 | 无 | 直接采用；`sys_config` 加 `password_expire_days` |
| 7 | **agent 通信 token** | `TokenUtils.java`（92 行） | 设备侧有独立凭证，与用户 JWT 分离 | **固定 MD5 值、无时间戳、无 nonce** → 抓到一次请求可无限重放 | HMAC-SHA256 + `X-Timestamp` + `X-Nonce`，时间窗 ±5 分钟，每设备独立 secret 可轮换 |
| 8 | **License 校验** | `LicenseController.java`（68 行）+ `ScheduledTask:85` | 商业授权校验 + 定时校验 | — | 法泽不需要（自主运营） |
| 9 | **上传大小限制** | `WgcloudServiceApplication:102-109`（200 MB） | 有全局上限，不是每个接口各写一遍 | 只限总大小，无后缀/魔数校验 | `spring.servlet.multipart.max-file-size` + **魔数校验** + 后缀白名单（《接口规范》§6.6） |
| 10 | **命令注入防护** | `application-dev.yml:79` `shellToRunBlock` | 对会执行命令的功能做危险字符拦截 | **黑名单可绕**（`rm  -rf` 双空格、`/bin/rm`；`curl` 不在名单里） | 法泽无「执行用户命令」功能；**这条是「不要用黑名单」的反例** |
| 11 | **日志分 4 类落库** | `StaticKeys:58-65` + `LogInfoService:59-71` | 业务告警 / 系统操作 / 告警恢复 / 第三方告警**分开存**，页面可按类过滤 | 同步单条 INSERT，数据库挂了就丢 | `@Async` + 队列，失败降级本地文件（§10.4） |

**从这张表能提炼一条通用结论**（也是 §十四 的思想）：

> **安全机制的「有没有」和「对不对」是两件事。** wgcloud 的 11 项机制「有」，但第 1/2/3/7/10 五项「不对」——而它们的「不对」是**同一个原因**：**用清单（白名单数组 / 黑名单词表 / 共享密钥）代替了结构（注解、精确匹配、签名）。**

对法泽的直接启示：**能用结构表达的，就不用清单**。免登录用 `permitAll()` 表达而不是维护字符串数组；权限用 `@PreAuthorize` 表达而不是 `endsWith` 黑名单；设备凭证用签名表达而不是共享密钥。

---

# 二、包结构与分层

## 2.1 目录树（裁决一处冲突 + 选定一种结构范式）

**现状冲突**：`AGENTS.md` §3.2 的目录树写 `modules/` 下 **9 个域**；技术路线 §4.2 写 **12 个域**（多 `workbench`、`social`、`admin`）。两份文档不一致。

**裁决：以技术路线 §4.2 的 12 域为准**，`AGENTS.md` §3.2 是缩写，实施时按 12 域建包。

### 2.1.1 两种结构范式，法泽选哪个

| 范式 | 长什么样 | 代表作 | 优点 | 代价 |
|---|---|---|---|---|
| **域优先**（法泽采用） | `modules/<域>/{controller,service,mapper,vo,bo,rule}/` | jxyd200 的 `features/*`、技术路线 §4.1 | **一个域一个目录**，跨域依赖在路径上一眼可见；删一个域 = 删一个目录 | 同一技术角色散在 12 个域里，找「所有 Controller」要跨目录 |
| **层优先 + 层内下钻** | `controller/` `dto/<域>/` `mapper/<数据源>/` `rule/<类别>/` | **ZXZ**（见 §1.4.3） | 同层同类放一起，横向对比方便；`mapper/mysql/` vs `mapper/dm/` 这种**按基础设施差异分子目录**很直观 | 跨域调用要靠包名判断，边界容易糊（ZXZ 就糊了——`IndexServiceImpl` 4,734 行横跨 12 个域） |

**法泽选「域优先」的硬理由**：技术路线 §4.2.1 有一条禁令——**禁止 A 域直接注入 B 域的 Mapper**。这条禁令要在代码评审和 ArchUnit 里可判定，「域优先」让它在**目录结构**上就成立（`modules.trade.mapper` 只能被 `modules.trade.*` import）；「层优先」下所有 Mapper 都在一起，这条禁令只能靠包名约定。

**但 ZXZ 的「层内下钻」在域内部照用**（这是它的价值，别丢）：

```text
modules/trade/
├─ rule/fee/          → 抽成规则（对应 ZXZ 的 rule/calculation/*/）
├─ rule/refund/       → 退款规则
├─ vo/order/          → 订单出参（对应 ZXZ 的 dto/xzb/）
└─ vo/wallet/         → 钱包出参
```

**理由是同一个**：目录结构本身要能表达「可扩展点在哪」。`rule/fee/` 下有 5 个规则类，新增第 6 种只加一个类、不改任何现有文件——这和 ZXZ 的 `rule/calculation/bigdecimal/fixed/` 是同一个思想。

```text
services/faze-api/src/main/java/com/faze/
├─ FazeApplication.java
├─ common/                    跨域共用，不含业务
│  ├─ R.java                  统一响应（见《接口规范》§5.1）
│  ├─ exception/              BusinessException + GlobalExceptionHandler + ErrorCode
│  ├─ constants/              常量（不可变）
│  ├─ enums/                  枚举（状态码、类型码，必须带 code + label + 静态查找）
│  ├─ pojo/                   PageQuery / PageResult / TableDataInfo
│  └─ util/                   纯函数工具
├─ config/                    配置类，一个领域一个类
│  ├─ WebMvcConfig MybatisPlusConfig RedisConfig ThreadPoolConfig
│  ├─ OpenApiConfig SecurityConfig JacksonConfig
│  └─ properties/             @ConfigurationProperties（按领域拆，不合并）
├─ security/                  JwtFilter / CurrentUser / SecurityContext
├─ filter/                    TraceIdFilter / 请求日志
├─ interceptor/               AuthInterceptor / OperationLogInterceptor
├─ modules/<域>/              12 个域，每个域内部再分层
│  ├─ controller/
│  ├─ service/                接口 + impl/（见 §2.4 判据）
│  ├─ mapper/
│  ├─ entity/                 与表一一对应
│  ├─ bo/                     入参（Business Object）
│  ├─ vo/                     出参（View Object）
│  └─ converter/              Entity ↔ BO ↔ VO
├─ infra/                     storage/ sms/ push/ pdf/ ai/ mq(任务表)/ monitor/
└─ task/                      定时任务（见 §9.3）
```

**域清单（12 个，与技术路线 §4.2 一致）**：`account` `lawyer` `workbench` `conversation` `trade` `document` `caseflow` `content` `social` `ai` `ops` `admin`。

## 2.2 分层职责与禁令

```text
Controller  →  参数校验、鉴权、路由。**不写业务、不碰 Mapper、不做事务**
   ↓
Service     →  业务逻辑、事务边界、状态机校验、跨域编排
   ↓
Mapper      →  数据访问
Infra       →  第三方（OSS/短信/推送/豆包）
Converter   →  Entity ↔ BO ↔ VO
```

| 层 | 允许 | **禁止** |
|---|---|---|
| Controller | 收参、`@Valid`、调 Service、组装 `R` | 写 `if` 业务分支、注入 Mapper、`@Transactional`、`try/catch` 包业务 |
| Service | 事务、状态机、跨域调 Service、发任务 | 注入别的域 Mapper、返回 Entity、碰 `HttpServletRequest` |
| Mapper | SQL | 写业务逻辑、做数据转换 |
| Converter | 对象转换 | 做校验、查库 |

## 2.3 跨域调用（技术路线 §4.2.1 的落地）

```java
// ✅ 正确：social 域要律师信息，注入 lawyer 域的 Query Service
@Service
@RequiredArgsConstructor
public class SocialApplicationService {
    private final SocialActionMapper socialActionMapper;   // 本域 Mapper
    private final LawyerQueryService lawyerQueryService;   // 跨域只走 Service 接口
}

// ❌ 错误：直接注入别的域的 Mapper
// private final LawyerMapper lawyerMapper;
```

**配套规则**：
1. 跨域只允许调**查询**（`getXxx`/`listXxx`/`existsXxx`）。**跨域写**必须由目标域的 Service 自己提供方法，不许「A 域直接改 B 域的表」。
2. 跨域调用的 Service 必须是 `XxxQueryService`（只读接口），不是 `XxxService`（含写方法）。**这样才挡得住「顺手改一下」**。
3. 循环依赖 = 域切分错了，**必须重构，不许用 `@Lazy` 绕**。

## 2.4 Service 要不要接口？（ZXZ 与 wgcloud 都是反例和正例混杂）

**裁决**：
- **需要接口**：跨域被调用的 `XxxQueryService`（因为 §2.3 要靠接口限制只读）。
- **不需要接口**：域内自己用的 Service、只有一个实现且不跨域的。

依据：wgcloud 67 个 Service **全是具体类、0 个 `*Impl`**，因为单模块、无第二个实现——这时接口只是「方法签名复制粘贴」。ZXZ 是 9 对接口 + 2 个无接口 `@Service` **两种风格并存**——这才是问题（不知道该不该写接口，就去抄最近的那个）。

**所以规则是「判据明确」，不是「统一都写」或「统一都不写」。**

## 2.5 单类规模上限（两个项目都在这上面翻车）

| 类型 | 上限 | 超了怎么办 | 反例 |
|---|---|---|---|
| Controller | **200 行** | 逻辑下沉 Service | ZXZ `BIController:231-298` 68 行日期回退写进 Controller |
| Service | **500 行** | 按子域拆 | ZXZ `IndexServiceImpl` **4,734 行**、`XzbServiceImpl` **2,812 行**；wgcloud `DashboardService` **1,285 行** |
| 方法 | **50 行** | 拆私有方法或提取策略 | ZXZ `WarnMailUtil` 1,149 行 / 45 个告警方法各一个 |
| `constants` 类 | **150 行** | 按域拆成多个 | wgcloud `StaticKeys` **293 行**混装 7 类东西 |
| `@ConfigurationProperties` | 一个领域一个类 | 拆 | wgcloud `CommonConfig` 575 行 60+ 字段 |

> 4,734 行的类不是「代码多」，是**没有边界**：一个类里同时有 12 个域的查询。规范能挡住它的唯一办法是**上限数字 + CI 检查**，靠"自觉拆"挡不住。

## 2.6 每个业务模块必须有 `README.md`（抄 jxyd200 唯一不用工具就守住的一条）

**实测**：jxyd200 的三层 README **全部 100% 落地**——APP `features/*/README` 14/14、Java `biz/*/README` 15/15、网关 7/7。它是那份 1,138 行规范里**唯一不靠工具也守住了**的制度，原因很简单：**写一次就完事，成本极低**。

`modules/<域>/README.md` 固定四段：

| 段 | 写什么 | 例（trade 域） |
|---|---|---|
| **边界** | 这个域**管什么**、**不管什么**（明确写出邻域） | 管订单状态机、余额、流水、退款；**不管**会话（conversation）、不管文书生成（document） |
| **数据来源** | 读哪些表、写哪些表、跨域读谁的 `QueryService` | 写 `legal_order`/`balance_flow`/`user_balance`；跨域读 `LawyerQueryService` |
| **不可改的契约** | 对外接口里哪些字段被前端写死 | `POST /admin/wallets/{userId}/recharge` 的 `amountCents`/`bizNo` 见《接口规范》附录 A.5 |
| **测试入口** | 测试类在哪、跑什么命令 | `OrderStatusMachineTest`；`mvn -Dtest=Order*Test test` |

**为什么要求把「不管什么」也写下来**：域的边界最容易在自己加功能时被悄悄吃掉。**写下来那一刻才会发现**「这个查询其实该放 conversation 域」。

**承接手段**：CI 断言 `modules/*/README.md` 存在（`find modules -maxdepth 1 -mindepth 1 -type d -exec test -f {}/README.md \;`），缺一个就构建失败。jxyd200 靠人守住了，法泽加一条脚本就不可退化。

---

# 三、类与方法

## 3.1 命名

| 对象 | 规则 | 例 |
|---|---|---|
| 包 | 全小写，单数域名单段 | `com.faze.modules.trade.service` |
| 类 | UpperCamelCase | `OrderStatusMachine` |
| 方法 | lowerCamelCase，动词开头 | `createOrder` `cancelOrder` `assertCanPay` |
| 常量 | UPPER_SNAKE | `MAX_PAGE_SIZE` |
| 枚举常量 | UPPER_SNAKE | `PENDING` `APPROVED` |
| 布尔方法 | `is`/`has`/`can`/`should` 开头 | `canTransitTo()` |
| **禁止** | `Manager` `Helper` `Util2` `Data` `Info` `Processor` 这类泛名 | — |

**后缀语义（强制，不可混用）**：
| 后缀 | 含义 |
|---|---|
| `Controller` | HTTP 入口 |
| `Service` | 业务编排（含写） |
| `QueryService` | **只读**跨域查询接口 |
| `Mapper` | 数据访问 |
| `Entity` / `DO` | 与表一一对应（本项目统一用 `Entity`） |
| `BO` | 入参（Request） |
| `VO` | 出参（Response） |
| `Converter` | 对象转换 |
| `Task` | 定时任务 |

## 3.2 Controller

```java
/**
 * 订单控制器。
 *
 * 只做三件事：收参校验、调 Service、包 R。业务分支一律在 OrderService。
 * 状态机迁移以路径动词表达意图（见《接口规范》§3.3），不从 body 里读目标状态。
 */
@RestController
@RequestMapping("/api/v1/app/orders")
@RequiredArgsConstructor
@Tag(name = "订单")
public class OrderController {

    private final OrderService orderService;

    /** 律师接单。状态机校验在 Service，这里只收 id。 */
    @PostMapping("/{id}/accept")
    @Operation(summary = "律师接单")
    public R<OrderVO> accept(@PathVariable @Positive Long id) {
        return R.ok(orderService.accept(CurrentUser.id(), id));
    }
}
```

**四条禁令**：
1. 不许注入 Mapper。**判据**：Controller 里出现 `Mapper` 字样即为违规。
2. 不许 `@Transactional`。事务边界在 Service。
3. 不许在 Controller 里 `try/catch` 业务异常——`GlobalExceptionHandler` 统一处理。
4. 不许返回 Entity——只能返回 `R<VO>`。

## 3.3 Service

```java
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final UserWalletService userWalletService;   // 跨域走 Service

    /**
     * 律师接单。
     *
     * 前置：订单状态 = PAID 且当前律师是该单承办人；否则抛 405003。
     * 副作用：状态遷移 + 写 order_status_log + 发一条 IM 通知任务。
     * 事务：状态与日志必须同事务；通知走任务表，不进事务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO accept(Long lawyerId, Long orderId) {
        // 任务 1：带归属条件取单，取不到就是「不存在」，不区分「不是你的单」
        Order order = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getId, orderId)
                .eq(Order::getLawyerId, lawyerId));
        if (order == null) throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);

        // 任务 2：状态机校验（唯一真相在服务端，客户端只发意图）
        OrderStatusMachine.assertCanTransit(order.getStatus(), OrderStatus.ACCEPTED);

        // 任务 3：更新 + 追加状态日志（同一事务）
        order.setStatus(OrderStatus.ACCEPTED);
        orderMapper.updateById(order);

        // 任务 4：通知走任务表，不在事务内发（失败可补发）
        taskQueueService.enqueue(TaskType.IM_NOTIFY, order.getOrderNo());

        return orderConverter.toVO(order);
    }
}
```

**五条要求**：
1. **事务边界清晰**：一个 public 方法一个事务；事务内只做 DB 操作。
2. **状态机校验集中在 `XxxStatusMachine`**，不许散在 Service 各方法里。
3. **归属条件进 SQL**（见 §11.2）。
4. **事务内禁止远程调用**（豆包、OSS、短信、推送）——它们慢且可能挂，会把事务拖死。走 §9.2 任务表。
5. **方法超过 50 行**：拆私有方法，用 `// 任务 n：` 分段注释（见 §4.4）。

## 3.4 Mapper

| 规则 | 说明 |
|---|---|
| 单表 CRUD 用 `BaseMapper` + `LambdaQueryWrapper` | 类型安全，字段改名能被编译发现 |
| 多表查询 / 复杂聚合 / 批量用 XML | 写在 `src/main/resources/mapper/<域>/` |
| **禁止 `${}`** | 唯一例外见 §8.2 |
| 分页用 `IPage` | 不用 PageHelper（threadlocal 隐式拦截，`startPage` 后忘记紧跟查询就错乱） |
| 返回类型 | 单表返回 `Entity`；联表返回专门 `XxxView`（放 `mapper/dto` 或 `vo`），**不返回 `Map`** |

## 3.5 四类对象（Entity / BO / VO / View）

| 对象 | 位置 | 对应 | 约束 |
|---|---|---|---|
| `Entity` | `modules/<域>/entity/` | 表一行 | 字段与表一致；**不出现在 Controller 签名里** |
| `BO` | `modules/<域>/bo/` | 请求体 | 带校验注解；字段名与 JSON 一致（camelCase） |
| `VO` | `modules/<域>/vo/` | 响应体 | **不含敏感字段**（`password_hash`/`id_card_enc` 根本不声明） |
| `View` | `modules/<域>/mapper/` | 联表结果 | 只在本域内用 |

**禁止**：把 Entity 当 BO 或 VO 用。ZXZ 的 `FundsPlanMainDO` 错放进 `dto` 包就是这条的反例。

## 3.6 Converter

用 **MapStruct**（编译期生成，无反射）：

```java
@Mapper(componentModel = "spring")
public interface OrderConverter {
    OrderVO toVO(Order entity);
    Order toEntity(OrderCreateBO bo);
    // 敏感字段脱敏在 VO 的 JsonSerializer 上做，不在这里手写 substring
}
```

**禁止**：
- 手写 `BeanUtils.copyProperties`（同名字段静默覆盖，改名不报错）。
- 在 Converter 里查库或做业务判断。

## 3.7 策略与工厂（抄 ZXZ 的模式）

ZXZ 最值得抄的两处**模式化设计**：

**① 计算规则「枚举持 Class + 容器取 Bean」**（`CalRuleSelectorEnum:12-35` + `Calculator:20-34`）：

```java
public enum FeeRuleType {
    PLATFORM_PERCENT(PlatformPercentRule.class),
    FIXED(PlatformFixedRule.class);

    private final Class<? extends FeeRule> beanClass;
    FeeRuleType(Class<? extends FeeRule> c) { this.beanClass = c; }
    public FeeRule resolve(ApplicationContext ctx) { return ctx.getBean(beanClass); }
}
```

**为什么好**：新增一种规则 = 加一个枚举值 + 一个实现类，**不改 if-else 链**。

**② 导出「策略 + 工厂 + 路由表」**（`ContractExportStrategyFactory:28-52`）：

```java
public class ExportStrategyFactory {
    private final Map<ExportType, ExportStrategy> routes;   // Map.entry 静态表
}
```

**法泽的落点**：`trade` 的**平台抽成规则**、`document` 的**文书模板渲染策略**、`content` 的**分享海报生成策略**。

## 3.8 `rule/` 层：可脱离 Spring 单测的业务规则（抄 jxyd200）

把「纯计算、纯判断」的规则抽成不依赖 Spring 的 POJO：

```java
// modules/trade/rule/FeeRule.java
/** 平台抽成规则。输入金额与比例，输出抽成额；不查库、不注入、无状态，可直接 new 出来单测。 */
public interface FeeRule {
    long calculate(long amountCents, int ratioBasisPoints);
}

// modules/trade/rule/PlatformPercentRule.java
public class PlatformPercentRule implements FeeRule {
    @Override
    public long calculate(long amountCents, int ratioBasisPoints) {
        // 分转元再按基点算，最后 HALF_UP 回分；顺序颠倒会丢精度
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(ratioBasisPoints))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
```

**为什么值得单列**：这类类**不需要 `@SpringBootTest` 就能测**（`new PlatformPercentRule().calculate(...)`），测试成本接近零。jxyd200 就是这么做的——根 `package.json:22-23` 的 `test:rule:*` 用 `javac` + `java -cp` 直接跑，不启动 Spring。

**法泽的落点**：`OrderStatusMachine`（状态迁移合法性）、`FeeRule`（抽成）、`AmountValidator`（金额上下限）、`SensitiveWordMatcher`（敏感词分级）。

## 3.9 导入与导出（抄 ZXZ 的 Excel 导入 + JSR-303 行列级报错）

法泽有三个导入场景：**法条库批量导入**（`POST /admin/law-items/import`，2000~3000 条）、敏感词库导入、律师资质批量核验。

**ZXZ 的做法值得直接搬**（`DefaultExcelListener:57-95` + `ExcelUtil:68-91`）：把 JSR-303 校验**塞进读取回调**，逐行校验并**收集全部错误行**，不是遇到第一个错就中断。

```java
/**
 * 导入行校验监听器。
 *
 * 关键取舍：**一次收全所有错误**，而不是遇到第一个错就抛出。
 * 用户拿到「第 3 行法律名称不能为空；第 7 行条号格式错误」可以一次改完，
 * 而拿「第 3 行错了」要来回导 10 次。
 */
public class ValidatingListener<T> extends AnalysisEventListener<T> {
    private final Validator validator;
    private final List<String> errors = new ArrayList<>();
    private final int maxErrors;
    private int rowNum = 0;

    @Override
    public void invoke(T row, AnalysisContext ctx) {
        rowNum++;
        Set<ConstraintViolation<T>> vs = validator.validate(row);
        if (!vs.isEmpty() && errors.size() < maxErrors) {   // 上限防 3000 行全错时刷爆响应
            errors.add("第 " + rowNum + " 行: " + vs.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining("; ")));
        }
    }
}
```

**三条硬规则**：

| # | 规则 | 为什么 |
|---|---|---|
| 1 | **导入必须带幂等键** | 法条的键是「法律 + 条号 + 生效日期」（技术路线 §4.8.4.7 已定）。重复导入靠 `unique` 索引拦住，**不产生重复行**——不能在应用层先查后插 |
| 2 | **错误必须导回文件** | 所有错误行 + 原因导成 Excel 还给用户，改完直接再导。**只返回一句「有 3 行错误」等于让用户自己找** |
| 3 | **超过 1000 行必须转异步** | 走任务表（§9.2），接口立即返回 `taskId`，前端轮询进度。3000 条同步导入会超过网关超时 |

**导出**：金额字段的格式化见 §8.6，大批量导出（>5000 行）同样走任务表 + OSS，接口只返回下载用的 `ossKey`（《接口规范》§6.6）。

## 3.10 图表数据的服务端降采样（抄 wgcloud，但有一个坑要避开）

PC 看板 A-02 的趋势图，查 30 天可能是几十万个点，直接返回会拖垮浏览器。

**wgcloud 的做法**（`HostUtil:362-389` `compressChartListData`）：按 `size / maxShow` 的步长**等间隔取样**，把点数裁到 4000，并在页面上给一句「系统已对图表数据进行压缩均衡化处理，处理前 N 条，处理后 M 条」。

**三条规则**：

1. **降采样在服务端做**，不把原始点全丢给前端再抽样（传输本身就要时间）。
2. **必须告诉用户这是抽样后的**——wgcloud 的 `compressDataMsg` 这一点做对了，不能静默改变数据。
3. **⚠️ 但要避开它的一个坑：等间隔抽样会漏掉尖峰。**

```text
原始：[1,1,999,1,1]  → 步长 2 取样 → [1, 999, 1]  ✓ 偶然保住了
原始：[1,1,1,999,1]  → 步长 2 取样 → [1, 1, 1]    ✗ 峰值没了
```

**法泽的口径**：
- 「趋势」图用**按时间桶聚合**（`GROUP BY FLOOR(UNIX_TIMESTAMP(created_at)/3600)`），每个桶取 `AVG`——统计意义明确，且不会漏掉真实峰值（峰值会抬高所在桶的均值）。
- 要展示「峰值」的图，用 `MAX()` 而不是抽样。
- **只有在「点数太多但必须是原始值」的场景**（如单日高频曲线）才用等间隔抽样，且必须在图上标注「已抽样」。

---

# 四、注释（Java 形式，密度要求与前端一致）

> **先看 §1.4.1 / §1.4.2**：ZXZ 在这块是全仓最强的范本——`@param` 668 处、`@return` 299、`@Schema` 1,576 处、`@Operation` **46 个接口全覆盖 100%**。
> 本章取它的**密度与结构**（固定模板 + 文档与校验成对），不取它的**元数据字段**（`@Author` / `@Date`，见 §4.1）。
>
> **法泽的类头模板（与 ZXZ 的差别只有一处：删掉元数据三行）**：
>
> ```java
> /**
>  * 订单状态机。只负责「从 A 能不能到 B」，不做落库、不发通知。
>  *
>  * 状态迁移表见《技术路线》§9.2.1，是服务端唯一依据；客户端只发意图。
>  */
> ```
>
> **法泽的方法模板（与 ZXZ 同构：动作 + 参数 + 返回，再加「前置/副作用」两行）**：
>
> ```java
> /**
>  * 律师接单。
>  *
>  * 前置：订单状态 = PAID 且当前律师是承办人。
>  * 副作用：迁移状态、写 order_status_log、投递一条通知任务。
>  *
>  * @param lawyerId 当前登录律师，来自 JWT，不从请求体取
>  * @param orderId  订单主键
>  * @return 接单后的订单视图
>  */
> ```
>
> **强制**：`@Schema(description = ...)` 与校验注解**成对出现**（ZXZ 的做法，见 §1.4.2）——文档说必填、代码也校验必填。

## 4.1 类头（强制，三行以内）

```java
/**
 * 订单状态机。只负责「从 A 能不能到 B」，不做落库、不发通知。
 *
 * 状态迁移表见《技术路线》§9.2.1，是服务端唯一依据；客户端只发意图。
 */
```

**禁止**（两个参考项目都踩了）：
- `@author`（git blame 已有）
- `@date` / `@version` / `@ClassName` / `@Copyright`（wgcloud 五件套占掉 294 个文件的开头，`@date` 恒为「2021年1月16日」，从不更新）
- 把类名复述一遍

ZXZ 的 `@Author`（**大写 A，非标准注解**）248 处、`@Date` 4 种格式并存（含占位符 `@Date 2025/01/XX`），`@since` 与 `@Date` 两套——**这些不是规范，是历史包袱**。

> **注意分寸**：ZXZ 的注释**覆盖率与结构**值得抄（§1.4），但这三行元数据不值得抄。两者不矛盾——**它的强项是「每个契约都有注释」，不是「注释里写了作者和日期」。**

## 4.2 方法注释（public 强制，私有方法承担流程步骤时强制）

```java
/**
 * 律师接单。
 *
 * 前置：订单状态 = PAID 且当前律师是承办人，否则抛 405003。
 * 副作用：迁移状态、写 order_status_log、投递一条通知任务。
 *
 * @param lawyerId 当前登录律师，来自 JWT，不从请求体取
 * @param orderId  订单主键
 * @return 接单后的订单视图
 */
```

## 4.3 常量与阈值（强制写来源）

```java
// 上次上报 + (上报间隔 + 60s 网络缓冲) < now 即判定离线。
// 60s 是给弱网和 GC 停顿的余量，改小会误判。
private static final long OFFLINE_TIMEOUT_MS = (120 + 60) * 1000L;
```

这一条抄 wgcloud 的**好习惯**（`ScheduledTask:467-468` 就是这么写的），它的反面是 magic number 散落。

## 4.4 复杂流程的阶段注释

抄 ZXZ 的 `// 任务 n：` 风格（`IndexServiceImpl` 里大量使用）：

```java
// 任务 1：先校验状态，避免并发下两个请求都过了校验再抢更新
// 任务 2：落库用条件更新（CAS），影响行数 0 说明被别人抢了
// 任务 3：日志与流水在同一事务，缺一不可
```

**触发条件**：一个方法超过 3 个逻辑步骤。这是硬要求，不是建议。

## 4.5 禁止的注释

| 禁止 | 例 |
|---|---|
| 复述代码 | `// 设置用户名` `setUserName(name)` |
| 注释掉的死代码 | 用 git，不用注释 |
| 与实现不符的注释 | wgcloud 注释写「29s 周期」，实际 `BatchData` 每 **9 秒**清空（`AgentGoController:751` vs `DataScheduledTask:118`）——**腐坏的注释比没有注释更坏** |
| TODO 无 owner | 格式见《代码注释规范》§7：`// TODO(owner: xx, scope: yy): ...` |

---

# 五、异常与错误码

## 5.1 全局异常处理器（13 分支，抄 ZXZ 的划分）

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常：已知、可预期，**不打印完整堆栈**，只记 warn + 业务码 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMsg());
    }

    /** 参数校验：把全部错误**聚合**成一条 msg，不要只报第一个 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(ErrorCode.COMMON_PARAM_INVALID, msg);
    }

    /** 兜底：**必须打印完整堆栈**，但**绝不把堆栈或 e.getMessage() 返回给客户端** */
    @ExceptionHandler(Exception.class)
    public R<Void> handleUnknown(Exception e) {
        log.error("未捕获异常", e);
        return R.fail(ErrorCode.COMMON_INTERNAL);   // 只给通用文案
    }
}
```

**必须覆盖的 13 类**（技术路线 §4.6 已列）：`HttpRequestMethodNotSupported` `MissingPathVariable` `MethodArgumentTypeMismatch` `NoHandlerFound` `IllegalArgumentException` `BindException` `ConstraintViolationException` `MethodArgumentNotValidException` `DuplicateKeyException` `BusinessException` `RuntimeException` `Exception` `AccessDeniedException`。

**把这个「为什么」写进 Javadoc**（抄 jxyd200 的写法，`GlobalExceptionHandler.java:105-117`）：

```java
/**
 * 拦截未知的运行时异常。
 *
 * <p>这里只返回稳定的中文提示：异常消息可能包含 SQL、表名、类名或文件路径，
 * 直接回显会给前端和运营暴露内部实现。细节仍完整写入服务端日志。</p>
 */
@ExceptionHandler(Exception.class)
public R<Void> handleUnknown(Exception e, HttpServletRequest req) {
    log.error("请求地址 '{}' 发生未知异常", req.getRequestURI(), e);   // 细节进日志
    return R.fail(ErrorCode.COMMON_INTERNAL);                          // 稳定文案进响应
}
```

> **为什么值得单列**：三个参考项目里只有 jxyd200 把这条**原因**写在了异常处理器自己的注释里，而这恰恰是最容易被后人「优化」掉的一行——后来者会觉得「`e.getMessage()` 对排查更有用」于是改回去。注释写清**为什么不能改**，比写「做什么」有用得多。

## 5.2 业务异常

```java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String detail;   // 给开发者看的补充信息，不进响应
}
```

**错误码只能来自 `ErrorCode` 枚举**（《错误码规范》的 78 个码）。

**反例**：ZXZ **0 个 ErrorCode 枚举**，code 是裸数字散在业务代码里（`ZQTServiceImpl:52,55,69`）；wgcloud 的 code 只有 `"0"`/`"1"` 两个值。**这两个都不能抄。**

## 5.3 禁止把异常信息返回给客户端（两个项目都犯）

| 项目 | 问题 | 后果 |
|---|---|---|
| ZXZ | `GlobalExceptionHandler:128,138` 把 `e.getMessage()` 透传前端 | 泄露 SQL、表名、类名 |
| wgcloud | `SystemInfoController:140` 返回 `e.toString()` | 泄露堆栈 |

**规则**：响应体里的 `msg` 只能是 `ErrorCode` 的固定文案；技术细节只进日志。

---

# 六、参数校验

## 6.1 三层校验，各管一段

| 层 | 管什么 | 手段 |
|---|---|---|
| **格式层** | 非空、长度、格式、范围 | JSR-380 注解（`@NotBlank` `@Size` `@Pattern` `@Min` `@Max`） |
| **业务层** | 状态允许、归属、配额 | Service 里 `assertXxx()` + `BusinessException` |
| **数据库层** | 唯一、外键（无物理外键）、约束 | 唯一索引 + 捕获 `DuplicateKeyException` |

## 6.2 注解使用（ZXZ 的实测短板，法泽要补上）

ZXZ 的实测分布：`@NotBlank` 49、`@NotNull` 5、`@Pattern` 5、`@Max/Min` 4/4、`@NotEmpty` 1，而 **`@Size` 0 处、`@Email` 0 处、分组 `groups=` 0 处、自定义 `ConstraintValidator` 0 处**。

**法泽的强制要求**：

| 字段类型 | 必须加的校验 |
|---|---|
| 字符串 | `@NotBlank` + **`@Size(max=N)`**（不设上限 = 一个 1 MB 的昵称进库） |
| 金额 | `@NotNull @Min(1) @Max(上限)`，类型 `Long`（分） |
| 时间 | `@NotNull` + 业务层校验「不能是未来」「跨度 ≤ N 天」 |
| 枚举 | `@Pattern` 或自定义 `@EnumValue`（**不许裸 `String` 不校验**） |
| 手机号 | `@Pattern(regexp="^1[3-9]\\d{9}$")` |
| 身份证 | 正则 + **校验位算法**（只做正则挡不住乱填） |
| 路径变量 | `@Positive` |

## 6.3 分组校验（ZXZ 0 处，法泽需要）

同一 BO 用在「创建」和「更新」时必填项不同：

```java
public interface Create {}
public interface Update {}

public record LawyerApplyBO(
    @NotBlank(groups = Update.class) Long id,
    @NotBlank String realName,
    @NotNull(groups = Create.class) String licenseNo
) {}
```

## 6.4 级联校验（抄 ZXZ 的好写法）

ZXZ `FundsPlanSaveRequestBO:46-52` 用 `@Valid` 级联校验 List 内的子 BO，且 `@Schema(requiredMode=REQUIRED)` 与约束注解成对出现。**法泽照做**：`List<@Valid ItemBO>` 必须逐项校验，不能只校验外层。

## 6.5 反例：必填了但后端不读的字段

ZXZ `BiRequestBO:20-22` 的 `@NotBlank companyName`，10 个使用点全只取 `companyId`，`getCompanyName()` 全仓无一作用于此 BO —— **前端被迫传一个后端根本不读的必填字段**。

**规则**：BO 上每个必填字段，必须在 Service 里至少被读一次。CI 里可以用「未使用字段检查」兜（IDEA 的 unused 检查 + review）。

---

# 七、事务

## 7.1 边界在 Service.public 方法

```java
@Override
@Transactional(rollbackFor = Exception.class)
public void deductAndRecord(Long userId, Long amountCents, String bizNo) { ... }
```

## 7.2 五条硬规则

| # | 规则 | 为什么 |
|---|---|---|
| 1 | `rollbackFor = Exception.class` 必须写 | 默认只回滚 `RuntimeException`，受检异常不回滚 |
| 2 | **事务内禁止远程调用**（OSS/短信/推送/豆包/HTTP） | 慢调用持有连接，最坏把连接池占满 |
| 3 | 事务内**禁止发 MQ / 发任务以外的异步** | 事务未提交时消息已发出，消费方读不到数据 |
| 4 | 事务方法**必须 public** | Spring AOP 代理不拦截 `private`/`final`/自调用 |
| 5 | **禁止自调用**（同类 `this.xxx()`） | 代理不生效，事务形同虚设 |

## 7.3 事务内只做 DB

通知、日志投递、消息推送一律走**任务表**（§9.2），在事务**提交后**由定时任务捞取。这与 MQ 方案 B 的决策一致。

## 7.4 大事务禁令

单个事务的写操作 **≤ 500 行**。超过就分批（每批独立事务）。

**反例**：wgcloud `RemindScheduledTask:24-46` 每分钟全表扫 + 全量 `updateByIds`，账号 1000 时每分钟写 1000 行 binlog/undo。

---

# 八、持久层

## 8.1 MyBatis-Plus vs 手写 XML（与 ZXZ 相反的选择）

| 场景 | 用什么 | 理由 |
|---|---|---|
| 单表 CRUD、按字段查、简单条件 | **`BaseMapper` + `LambdaQueryWrapper`** | 类型安全，字段改名编译期报错 |
| 多表联查、聚合统计、复杂子查询 | **XML** | Wrapper 写出来不可读 |
| 批量插入/更新 | **XML**（`insertList`） | MP 的 `saveBatch` 内部逐条，慢 |
| 动态 `ORDER BY` | **白名单映射**（§8.2） | 不能拼字符串 |

ZXZ 是「真实业务 100% XML、Wrapper 零使用」——它是读多写少的取舍，**法泽不照搬**：法泽的 CRUD 量大，全写 XML 会把简单查询写复杂。

## 8.2 `${}` 的唯一例外：`ORDER BY` 白名单

```java
private static final Map<String, String> ORDER_COLUMNS = Map.of(
    "createdAt", "created_at",
    "amountCents", "amount_cents"
);

String column = ORDER_COLUMNS.get(bo.getOrderBy());
if (column == null) throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
```

**反例（必须避免）**：wgcloud 49 处 `${}`，全是 `ORDER BY t.${orderBy} ${orderType}`，参数由前端直接透传（`SystemInfoController:123-126`），且黑名单 `sqlInKeys` 不覆盖这条路径 → **可注入**。

**正例**：ZXZ 全仓 **0 处 `${}`**、177 个 select 全 `#{}`。

## 8.3 分页

| 场景 | 结构 |
|---|---|
| C 端列表 | `IPage` → `PageResult`（`list/total/pageNum/pageSize/hasMore`） |
| IM 消息 | `cursor` + `limit`（按 `seq`，不用 OFFSET） |
| PC 管理端 | `IPage` → `TableDataInfo`（`rows/total`） |

**禁止**：PageHelper（`ThreadLocal` 隐式拦截）；`pageSize` 超过 100 必须服务端 clamp。

## 8.4 批量操作

- 批量插入**分片**，每片 ≤ 500 行（MySQL `max_allowed_packet`；Oracle 有 1000 项上限）。
- 批量更新用 `UPDATE ... WHERE id IN (...)`，**不要** for 循环单条 update。

**反例**：wgcloud `CpuStateService:49` 一次拼全量 `VALUES(...)`，主机数上去就撞包大小。

## 8.5 乐观锁与条件更新（法泽必须有，ZXZ/wgcloud 都没有）

涉及**余额、库存、状态机**的更新，必须二选一：

```java
// 方案 A：MP 乐观锁（entity 上 @Version）
@Version private Integer version;

// 方案 B：条件更新（推荐，资金场景）
int rows = walletMapper.update(null, Wrappers.<UserWallet>lambdaUpdate()
        .setSql("balance_cents = balance_cents - " + amountCents)
        .eq(UserWallet::getUserId, userId)
        .ge(UserWallet::getBalanceCents, amountCents));
if (rows == 0) throw new BusinessException(ErrorCode.TRADE_BALANCE_INSUFFICIENT);
```

**方案 B 为什么要 `ge` 条件**：并发下两个请求都读到余额 100、都扣 80，没有 `ge` 就变成 -60。有了 `ge`，第二个的 `rows` 是 0，直接判定余额不足。

## 8.6 金额计算与舍入（抄 ZXZ 的 `BigDecimalUtils`）

法泽有三处必须算金额：**平台抽成**（订单）、**退款分摊**（多人/多笔分摊）、**结算单**（律师收入）。

**三条规则**：

| # | 规则 | 反例 |
|---|---|---|
| 1 | **一律 `BigDecimal`，禁止 `double`/`float`** | `0.1 + 0.2 = 0.30000000000000004`；金额差一分钱在资金链里是事故 |
| 2 | **舍入方式集中定义，不许每处写** | ZXZ 把它收进 `BigDecimalUtils.scan` + `RoundMode` 枚举。法泽同样收进一个工具类，业务代码只调 `Money.of(cents).percent(x)` |
| 3 | **「差一分钱」必须显式处理** | 10000 分按 3 人分摊，`3333.33...` 除不尽——**余数必须落给某一个人**，否则三人之和 ≠ 10000 |

```java
/** 金额工具。所有舍入集中在此，业务代码不得自行 setScale。 */
public final class Money {

    /** 按基点抽成（10000 基点 = 100%）。金额是整数分，结果也必须是整数分。 */
    public static long percent(long amountCents, int basisPoints) {
        return BigDecimal.valueOf(amountCents)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * 均分。除不尽的余数**全部给最后一份**，保证 sum(result) == total。
     * 不允许「四舍五入后差额不管」——那会让账对不上。
     */
    public static long[] splitEvenly(long totalCents, int parts) {
        long base = totalCents / parts;
        long remainder = totalCents - base * parts;
        long[] out = new long[parts];
        Arrays.fill(out, base);
        out[parts - 1] += remainder;
        return out;
    }
}
```

**为什么单列一节**：`§8.5` 讲的是「怎么不出错地扣钱」，这一节讲的是「怎么不出错地算钱」。两者都出事，但前者是并发问题、后者是**口径问题**——口径错了，测再并发也是错的钱。

## 8.7 整表覆盖用「先删后插」（抄 wgcloud）

适用场景：**一行一条配置、保存时整批提交**的表——`sys_config`（PC 系统参数页）、律师服务设置、通知模板变量。

```java
@Service
@RequiredArgsConstructor
public class SysConfigService {

    /**
     * 整批保存系统参数。
     *
     * 用「先删后插」而不是逐条 upsert：
     * ① 天然幂等（同样的请求执行两次结果相同）
     * ② 删掉界面上被移除的那几条——upsert 做不到「删除」
     * ③ 避免 MySQL ON DUPLICATE KEY 与 MP saveOrUpdate 的隐式行为差异
     * ④ 必须在同一事务内，否则删完插入失败会丢配置
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(List<SysConfigBO> items) {
        configMapper.deleteByGroup(items.get(0).getGroup());     // 先删本组
        configMapper.insertList(items);                          // 再插（§8.4 批量分片）
    }
}
```

**ZXZ 的做法印证**：它全项目**唯一**一处 `@Transactional`（`ProjectServiceImpl:173`）就是 delete + reinsert 全量覆盖，且写了 `rollbackFor = Exception.class`——**同一个场景，同一个解法**。

**注意**：这只适合「整组覆盖」。**不要**用在高并发写的大表（删+插之间会有短暂空窗，别的事务可能读到空）；这类表用逐条 upsert。

## 8.8 攒批落库：评估后**不采用**（写明为什么，避免以后反复讨论）

wgcloud 用「内存攒 9 秒 → 批量落库」（`BatchData` + `DataScheduledTask:114-124`），因为它是监控系统：**每台设备每秒一条**，写放大极高，逐条 INSERT 撑不住。

**法泽不需要**：
- 法泽的写都是**用户触发**（下单、发消息、审核），频率由用户行为决定，不存在持续高频写入。
- 攒批的代价是**延迟 + 丢数据风险**（wgcloud 的 `addAll → clear → save` 在 save 失败时整批丢失，见附录 B）。
- 真到需要时（如加行为埋点、AI 调用日志），再按任务表 + 分批 INSERT 做，**不要提前引入内存缓冲区**。

## 8.9 多数据库方言适配：评估后**不采用**（同上，写明理由）

wgcloud 用 `MyDatabaseIdProvider`（`config/MyDatabaseIdProvider:38-63`）+ mapper XML 里的 `_databaseId` 分支，让一套代码支撑 MySQL / PostgreSQL / Oracle / SQLServer / 瀚高。这是个**做得很好**的方案。

**法泽不需要**，理由是两条：

1. `AGENTS.md` §2 已锁定 **MySQL 8.0.44**，且是自有部署（不是卖给客户去装在他们库上）。
2. 引入方言分支的代价是**每条复杂 SQL 都要写 N 份**，而测试只能覆盖跑得到的那个库——**没测到的分支等于没有**，反而制造"我支持"的错觉。

**但要留个记号**：如果将来要进信创环境（达梦 / 人大金仓），这套 `DatabaseIdProvider + _databaseId` 就是标准做法。**现在不引入，但不能不知道它的存在**——否则到时要重写所有 XML。

---

# 九、并发、锁与定时任务

## 9.1 分布式锁

用 **Redisson**（技术路线 §4.7 已选），锁的 key 必须带业务语义：

```java
RLock lock = redisson.getLock("lock:order:" + orderId);
if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) throw ...;
```

**三条规则**：必须设过期时间（防死锁）；必须在 `finally` 解锁；被锁住的临界区只做 DB 操作。

## 9.2 异步任务：DB 任务表（MQ 方案 B 的代码规范）

```text
task_queue 表字段（《数据表规范》附录 A）：
  id / task_type / biz_no / status / retry_count / next_retry_at /
  last_error / lock_owner / lock_expire_at / created_at / updated_at
```

**规范**：

| # | 规则 | 理由 |
|---|---|---|
| 1 | **「已投递」是状态，不是删除** | wgcloud `updateSendByIds → STATE='4'` 之后无后续，任务崩了就永挂 |
| 2 | **领取必须 CAS**：`UPDATE ... SET status=已领取, lock_owner=?, lock_expire_at=? WHERE id IN (...) AND status=待领取`，按影响行数判断谁真的领到 | wgcloud 是「先 SELECT 后 UPDATE」，两个消费者会拿到同一条 |
| 3 | **必须有租约过期回收**：`lock_expire_at < now()` 的任务放回「待领取」，`retry_count+1` | 没有租约 = 消费者崩溃后任务永久卡住 |
| 4 | **指数退避**：`next_retry_at = now + 2^retry_count 分钟`，`retry_count >= 5` 进死信 | — |
| 5 | 死信表在 PC A-26 可见 | 失败必须能被人工看到 |

**这套结构比 MQ 多了两个字段（`lock_expire_at`、`next_retry_at`）、改了一条 SQL（CAS），换来「至少一次 + 死信」语义，且不引入中间件。**

## 9.3 定时任务

```java
@Configuration
public class ThreadPoolConfig {
    /**
     * 显式配 poolSize。Spring 默认 TaskScheduler 只有 **1 个线程**，
     * 所有 @Scheduled 串行——一个慢任务会饿死其它全部任务。
     * wgcloud 就是因为它有个 9 秒一次的落库任务，被迫配到 50（WgcloudServiceApplication:90-95）。
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(10);
        s.setThreadNamePrefix("faze-task-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }
}
```

**四条规则**：
1. **任务必须幂等**（重跑不产生重复数据）。
2. **多实例必须加锁**：用 `task_queue` 的行锁或 Redis 锁。**禁止**用「配置文件里写 master/slave」——wgcloud 有 11 处 `if (!NODE_MASTER.equals(nodeType)) return;`，运维漏改一行就两个节点同时跑。
3. **单次执行必须有超时**。
4. **禁止 `synchronized` 当分布式锁**——它只在单 JVM 内有效。wgcloud `DataScheduledTask:114-124` 的 `synchronized` 对同方法不并发进入，纯属错觉。

## 9.4 线程池（抄 ZXZ 的 `ThreadPoolConfig:33-49`）

```java
@Bean("bizExecutor")
public ThreadPoolExecutor bizExecutor() {
    ThreadPoolExecutor e = new ThreadPoolExecutor(
        coreSize, maxSize, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(queueCapacity),      // ← 必须有界
        new ThreadFactoryBuilder().setNameFormat("faze-biz-%d").build(),
        new ThreadPoolExecutor.CallerRunsPolicy());    // ← 拒绝策略必须显式
    e.allowCoreThreadTimeOut(true);
    return e;
}
```

**反例**：wgcloud `ThreadPoolUtil:18` 用 `new LinkedBlockingDeque<>()`（**无界**），线程池扩容规则是「核心满 → 入队 → 队列满才扩到 max」，无界队列永不「满」，所以**永远只有核心线程数在跑**，`maxPoolSize` 形同虚设，任务堆积到 OOM。

---

# 十、日志

## 10.1 分级（强制）

| 级别 | 用途 | 例 |
|---|---|---|
| `ERROR` | 需要人介入 | 未捕获异常、第三方服务挂了、资金链路失败 |
| `WARN` | 可自愈但异常 | 业务异常、重试、限流触发 |
| `INFO` | 关键业务节点 | 下单成功、审核通过、任务开始/结束 |
| `DEBUG` | 排障细节 | 请求参数（**脱敏后**）、SQL 参数 |

**禁止**：在循环里打 `INFO`；打整个对象（用 `log.info("order={}", order)` 而非字符串拼接）；打 `System.out`。

## 10.2 禁止记录的内容（与《数据表规范》§9 的 L1~L5 对齐）

| 级别 | 能否进日志 |
|---|---|
| L4（身份证影像、执业证、聊天附件） | **绝对禁止**（连 ossKey 都不打完整） |
| L5（密码哈希、支付密码、聊天原文） | **绝对禁止** |
| L2（手机号） | 只能打脱敏后 `138****8888` |
| L3（身份证号） | 只能打 `110***********1234` |

**反例**：ZXZ `LBpmProcessInstanceController:48` 打印 `mkCode`（换 token 的凭证）到日志。

## 10.3 traceId

```java
@Component
public class TraceIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(...) {
        String traceId = StringUtils.hasText(req.getHeader("X-Request-Id"))
                ? req.getHeader("X-Request-Id") : UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        resp.setHeader("X-Trace-Id", traceId);
        try { chain.doFilter(req, resp); } finally { MDC.remove("traceId"); }
    }
}
```

logback pattern 必须含 `%X{traceId}`。**两个参考项目都没有 traceId**——ZXZ 的 `R` 无 `traceId` 字段，wgcloud 全项目 0 处 MDC。

## 10.4 操作日志：服务端 AOP 采集（两个项目都是 0 个 `@Aspect`）
```java
@OperationLog(module = "trade", action = "MANUAL_RECHARGE", targetType = "USER_BALANCE")
public void manualRecharge(...) { ... }
```

**为什么必须是服务端 AOP**：
- ZXZ 的做法是**前端埋点写库**（`BIController:179-207`），且 `try/catch` 吞异常 → **能伪造、能漏记**。
- wgcloud 的做法是 55 个 Controller 里**复制粘贴**「异常写库」，必然漏（实测 `AgentGoController:155-157` 就没有）。

**审计四要素**（《数据表规范》§1.3）：谁（`operator_id`+`operator_type`）/ 何时 / 从哪（`ip`）/ 做了什么（`module`+`action`+`target_type`+`target_id`+`detail`）。

### 10.4.1 异常「文件 + 库表」双写（抄 wgcloud，但要改掉它两个毛病）

wgcloud 的 `LogInfoService:59-71` + `StaticKeys:58-65` 做了一件**方向完全正确**的事：异常/操作**同时写文件日志和业务库表**，并用常量分 4 类（业务告警 / 系统操作 / 告警恢复 / 第三方告警）。

**为什么必须双写**：两类消费者的需求不同——**文件日志给运维 grep**（按时间、全文搜），**库表给业务页面查询统计**（PC A-26 日志中心要按操作人/时间/模块筛）。只做一样都不够。

**但它的两个毛病必须改**：

| wgcloud 的问题 | 出处 | 法泽的做法 |
|---|---|---|
| **同步单条 INSERT**，数据库挂了这次审计就丢了 | `LogInfoService:69-70` | `@Async` + 有界队列；**写库失败降级写本地文件**（`.logs/audit-fallback.log`），不丢 |
| 存 `e.toString()`，只给「类名: 消息」，**丢了行号** | `SystemInfoController:140` | 存完整 stacktrace，**截断到 4000 字符**（防一个异常撑爆一列） |

```java
/** 审计日志落库。异步 + 失败降级：审计不能因为数据库抖动而丢。 */
@Async("auditExecutor")
public void record(AuditLog log, Throwable error) {
    try {
        if (error != null) log.setDetail(truncate(StackTraceUtils.toString(error), 4000));
        auditMapper.insert(log);
    } catch (Exception e) {
        fallbackWriter.append(log);   // 降级到本地文件，人工可补录
        log.error("审计日志落库失败，已降级到文件", e);
    }
}
```

## 10.5 logback 配置（抄 ZXZ 的 `logback.xml:81-99,122-128`）

三条必须：
1. **三个 appender**：`console` + `file`（全量）+ **`error`（单独）**。
2. **`AsyncAppender` + `discardingThreshold=0`**（不丢 WARN/ERROR）。
3. 滚动策略：按天 + 单文件 100MB + 保留 30 天。

**反例**：wgcloud `logback-spring.xml` 注释写「不同级别存不同文件」，实际**只配了 1 个 FILE appender**。

---

# 十一、安全

> **本章的骨架来自 §1.5 的 wgcloud 11 项机制**——三个项目里 wgcloud 的安全机制覆盖最完整（统一鉴权入口、免登录白名单、只读角色、登录双窗口锁定、密码复杂度与过期、设备凭证、上传限制、命令注入防护、日志分类落库）。
> 下面按「机制 → 法泽的正确实现」组织；**wgcloud 每项的实现缺陷已在 §1.5 的表格里逐项列出**，这里不重复，只给正确做法。

## 11.1 鉴权：统一入口 + 默认拒绝（wgcloud 机制 1/2/3 的正确版）

wgcloud 的 `AuthRestFilter` 是**统一入口**（363 行 `@WebFilter("/*")`）——这个方向是对的，法泽照做。但它的白名单是 **40 条硬编码字符串 + `startsWith` 前缀匹配**，语义是「除名单外都要登录」，于是：

- 新增接口**忘加白名单就暴露**（本不该暴露的反而被放行）
- 前缀匹配可被路径构造绕过

**法泽的做法：`anyRequest().denyAll()`，把默认方向反过来。**

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)                  // 无 Cookie 会话，JWT 在 Header
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/pub/**").permitAll()       // 只有这一处白名单
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/app/**").authenticated()
            .anyRequest().denyAll())                             // ← 新增接口默认不可访问
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

| 通道 | 方案 |
|---|---|
| C 端 | JWT（`Authorization: Bearer`）+ Redis 黑名单 |
| PC 端 | **待拍板**（见《接口规范》§1.3 的 P-1：共享 RuoYi token A 方案 / faze-api 自签 B 方案） |
| 内部 | 内网白名单 + 服务间签名 |

**禁止**：把鉴权逻辑写在每个 Controller 方法里。wgcloud 就是「每个 Controller 手写 `checkAgentToken`」，结果 **7 个 Controller 的 `agentList` 漏写**（`DbTableController:73`、`DceInfoController:72`、`FtpInfoController:62`、`HeathMonitorController:71`、`K8sMonitorController:69`、`LogInfoController:58`、`SnmpInfoController:70`），而它们**同时还在免登录白名单里** → 未登录即可拉取全量设备配置。

**这不是「某人粗心」，是结构决定的**：手写 18 处、漏 7 处 ≈ 39% 的漏写率。**能用结构（注解 + Filter）表达的，不要用手写重复表达。**

### 11.1.1 登录安全（wgcloud 机制 4/5/6，这几项它是真好）

**① 失败锁定：双窗口，两个不同 TTL**

wgcloud `LoginController:176-206` + `StaticKeys:204-212` 的设计值得直接采用：

| 窗口 | TTL | 阈值 | 语义 |
|---|---|---|---|
| 计数窗口 | **1 分钟** | 错 5 次 | 短期异常（手滑、撞库试探） |
| 封禁窗口 | **10 分钟** | — | 封禁后的冷却期 |

```java
// 1 分钟窗口：记错误次数
String errKey  = "login:err:" + phone;
Long   errCount = redis.opsForValue().increment(errKey);
if (errCount == 1) redis.expire(errKey, Duration.ofMinutes(1));

// 达 5 次 → 封禁 10 分钟
if (errCount >= 5) {
    redis.opsForValue().set("login:block:" + phone, "1", Duration.ofMinutes(10));
    log.warn("账号锁定 phone={} ip={}", mask(phone), clientIp);   // 记录 IP，与 wgcloud 一致
}
```

**wgcloud 的实现问题**：状态在单机 `ExpiringMap`，重启清零、多实例不共享。**法泽放 Redis**——这恰好也是「为什么本项目用 Redis」的一个具体理由。

**② 密码复杂度：四项可配置 + 返回原因（这一项直接采用）**

wgcloud `PasswordPolicyUtil:34-63` 的实现质量高，三个细节值得学：

```java
/**
 * 校验密码复杂度。
 *
 * 返回**具体失败原因**而不是布尔值——前端可以直接显示「密码必须包含数字」，
 * 不用自己再维护一份规则文案（两份规则必然漂移）。
 *
 * @return 空字符串表示通过；否则为可直接展示的失败原因
 */
public String validatePasswordComplexity(String password) {
    if (StringUtils.isEmpty(password)) return "密码不能为空";
    int minLength = config.getPasswordMinLength();
    if (password.length() < minLength) return "密码长度不能少于" + minLength + "位";
    if (config.isRequireDigit()       && !DIGIT.matcher(password).find())   return "密码必须包含数字";
    if (config.isRequireLetter()      && !LETTER.matcher(password).find())  return "密码必须包含字母";
    if (config.isRequireSpecialChar() && !SPECIAL.matcher(password).find()) return "密码必须包含特殊字符";
    return "";
}
```

**为什么「返回原因」比「返回 boolean」好**：布尔值迫使前端再写一遍规则文案去告诉用户哪里不合格，两份规则一定漂移。这一条是 wgcloud 全项目质量最高的 60 行。

**③ 密码过期 + 剩余天数（同样直接采用）**

`PasswordPolicyUtil:72-103`：`isPasswordExpired()` + `getPasswordRemainingDays()`（`-1` 已过期 / `0` 不限）。法泽把 `password_expire_days` 放 `sys_config`。

> **但密码「怎么存」不学 wgcloud**：它是前端算 MD5、后端直查库（pass-the-hash，见 §1.5 机制 7 的说明）。**法泽用 BCrypt（cost=10）**，见技术路线 §4.7。
> **这也是「机制对、实现错」的又一个例子**：它有一整套密码策略工具，却因为存储方式用 MD5，整套策略的价值大打折扣。

## 11.2 越权（IDOR）—— ZXZ 与 wgcloud 都在这翻车

| 项目 | 缺陷 | 出处 |
|---|---|---|
| ZXZ | 拦截器**只验 token 存在**，`companyId` 全由前端传；有 `ZqtUserUtils.getCurrentCompanyId()` 却无一处用它做归属校验 → **改 companyId 即可查别家财务/薪资** | `ZQTAuthInterceptor:34-53`、`BIController:50` |
| wgcloud | 白名单式授权（`endsWith("/del")` 等 16 个黑名单词），新增写接口不在名单里就默认放开 | `AuthRestFilter:81-90` |

**法泽的三条**：
1. **归属条件进 SQL**（不是先查再 Judge）：
   ```java
   // ❌ 有 TOCTOU 窗口，且漏判就 0 提示
   Order o = orderMapper.selectById(id);
   if (!o.getUserId().equals(uid)) throw ...;

   // ✅ 查不到就是「不存在」
   Order o = orderMapper.selectOne(Wrappers.<Order>lambdaQuery()
           .eq(Order::getId, id).eq(Order::getUserId, uid));
   ```
2. **当前用户一律从 SecurityContext 取**，**禁止从请求体/查询参数取 userId**。
3. 管理端可见全量数据，但**必须写操作日志**。

## 11.3 SQL 注入

- 一律 `#{}`，禁止 `${}`（唯一例外 §8.2 的白名单）。
- 禁止 `QueryWrapper.apply()` / `.last()` 拼字符串。
- `ORDER BY` / 表名 / 列名这类无法参数化的位置，**必须白名单映射**。

## 11.4 配置与密钥

**禁止把真实凭据写进仓库**。wgcloud 有 5 份（`config/prod/application-{tfgc,dzzwww,xzdsjzx-lili,xzsthj-xmspqy}-prod.yml`）明文生产 DB 密码 + 管理员口令 + agent 通信密钥；ZXZ 有 6 处（`application-prod.yml:6,27,43,76,93` + `application.yml:44-47` 的 RSA 私钥）。

```yaml
spring:
  datasource:
    password: ${FAZE_DB_PASSWORD}     # 环境变量，不写值
doubao:
  api-key: ${FAZE_DOUBAO_KEY}
```

**如果用了 Jasypt，就必须真的用**：wgcloud 引了 `jasypt-spring-boot-starter` 依赖、启动日志显示它在逐条代理所有 PropertySource，但全仓**0 处 `ENC()`** —— 纯开销零收益，还给人「已加密」的错觉。

**死开关禁令**：ZXZ 的 `application-dev.yml:89-91` 写了 `zqt.auth.enabled: false`，但 `ZQTConfig:15-35` 没有该字段、全仓无读取 → **开关不生效，还制造「鉴权可关」的错觉**。法泽的每个配置项必须有人读。

## 11.5 日志脱敏

见 §10.2。另：**禁止打印请求体全文**（聊天、身份证影像的元数据会进去）。

## 11.6 反序列化与 SSRF

- `ObjectMapper` 关掉 `enableDefaultTyping`。
- `RestTemplate` **禁止信任所有证书**。wgcloud `WgcloudServiceApplication:76-81` 的 `TrustStrategy` 无条件 `true` + `NoopHostnameVerifier`，且用它访问用户可控 URL → 可 SSRF。
- 富文本走 **Jsoup whitelist**（技术路线 §6.7）。

## 11.7 依赖

`owasp dependency-check` 在 CI 里跑。新增依赖必须登记到 `docs/architecture/依赖清单.md`。

---

# 十二、工程性

## 12.1 常量与枚举（wgcloud 的反面教材最典型）

**禁止「全局变量垃圾桶」**：wgcloud `StaticKeys` 293 行里混装 7 类东西（查询参数名、角色字面量、状态码、超时阈值、**可变全局 Map**、**可变全局 String**、SNMP OID），其中 `HOST_MAP` / `SERVER_WGTOKEN_MD5STR` **不是 final** → 任何类都能改全局、测试之间串味。

法泽的拆法：
| 内容 | 放哪 |
|---|---|
| 不可变常量 | `common/constants/XxxConstants` |
| 状态码/类型码 | `common/enums/`（枚举，带 `code` + `label` + 静态查找） |
| 运行时缓存 | `@Component` 的类，用实例字段，**不用 static** |
| 可配置的阈值 | `@ConfigurationProperties`，不写死在常量类 |

**枚举必须带静态查找**（抄 ZXZ 的 `AlarmLevelEnum:34-44` / `CalRuleSelectorEnum` 的写法）：

```java
public enum OrderStatus {
    PENDING, PAID, ACCEPTED, DONE, CANCELLED;

    private static final Map<String, OrderStatus> BY_NAME = ...;
    public static OrderStatus of(String name) { ... }
}
```

## 12.2 工具类

- 只用**一套** `StringUtils`（推荐 Spring 的 `org.springframework.util.StringUtils` 或 Apache Commons）。
  - **反例**：ZXZ 混用三套——commons-lang3 8 处、`org.apache.poi.util.StringUtil` **7 处**（Excel 库的工具类进了业务判空）、Hutool `StrUtil` 6 处。
- 工具类**只放纯函数**，不注入 Bean、不查库。
- 禁止静态字段 + `getBean()`（wgcloud `WarnPools:29-31` 的 `static CommonConfig = ApplicationContextHelper.getBean(...)`）——绕过 DI，初始化顺序脆弱。

## 12.3 配置类

- 一个领域一个 `@ConfigurationProperties` 类（**不合并**）。
- 用构造绑定 + `@DefaultValue`，不用 `@Data` + 手写 getter 加默认值（wgcloud `LevelConfig` 11 个告警级别 × 6 行 = 66 行纯样板）。
- **密钥一律走环境变量**，配置文件里只写 `${FAZE_DB_PASSWORD}`（§11.4）。

### 12.3.1 `.env.example` 必须写清「填错了会写脏哪个库」（抄 jxyd200）

第 0 段就要建 `.env.example`（总纲 §5.1），**不能只写 `DB_PASSWORD=` 空着**。jxyd200 的 `.env.e2e.example` 是范本：61 行里每条都写清「这个值错了会发生什么」。

```bash
# ── 数据库 ─────────────────────────────────────────────
# ⚠️ 不要填 3306 的生产实例：本地开发连的是 faze_dev，
#    填成生产库会让「测试充值」直接打进真实账。
FAZE_DB_URL=jdbc:mysql://127.0.0.1:3307/faze_dev
FAZE_DB_USER=faze
FAZE_DB_PASSWORD=            # 必填，留空启动失败（不提供默认值）

# ── Redis ──────────────────────────────────────────────
# ⚠️ 独立逻辑库（/14），避免清缓存时误删别的项目的 key
FAZE_REDIS_URL=redis://127.0.0.1:6380/14

# ── 第三方 ─────────────────────────────────────────────
# 留空时 AI 走 mock（返回固定法条引用），不会调真实豆包、不花钱
FAZE_DOUBAO_API_KEY=
```

**四条要求**：
1. **默认拒绝**：必填项留空时**启动直接失败**，不给"看起来能跑"的默认值。
2. **写清填错的后果**，不只写「这里填数据库地址」。
3. **本地与测试用不同端口/库名/Redis 逻辑库**，物理隔离。
4. `.env` 进 `.gitignore`，`.env.example` 进仓库。

## 12.4 测试

| 层 | 要求 |
|---|---|
| 状态机 | **必须单测**：合法迁移通过、非法迁移抛异常 |
| 资金链路 | **必须单测**：幂等（同键调 3 次只生效 1 次）、余额不足、并发扣减 |
| Controller | MockMvc 冒烟（每个接口一个 200/400 用例） |
| 覆盖率 | 不设全局数字（会造假），只对**上述三类**要求覆盖 |

### 12.4.1 「服务没起 → 用例全跳过 → 退出码仍是 0」（抄 jxyd200 的 service-preflight）

这是 E2E 最常见的假绿：**服务没启动，所有用例被跳过，CI 显示通过。**

jxyd200 的解法（`playwright.config.mjs:57-66`）值得直搬：

```javascript
// 服务前置门：不可用时直接失败，避免「服务没起 → 用例全跳过 → 退出码仍为 0」
{ name: 'service-preflight', testMatch: /shared\/service-preflight\.spec\.mjs/ },
{ name: 'app-e2e', dependencies: ['service-preflight'] }
```

**法泽的对应做法**（第 0 段 CI）：
- 一个 `preflight` 步骤：依次探活 MySQL / Redis / faze-api `/api/v1/pub/ping` / RuoYi 后台，任一个不通就**让整个 job 失败**。
- 所有依赖它的测试步骤 `needs: preflight`。
- **禁止**用「跳过」代替「失败」。

**两个参考项目的现状**：ZXZ 1 个测试类（`BigDecimalUtilsTest` 121 行）、wgcloud 0 个。**这是它们最大的工程债，法泽不能继承。**

## 12.5 提交与 CI

- 提交信息格式见 `AGENTS.md` §6。
- CI 必绿项：`mvn -B verify`（编译 + 单测 + 覆盖率阈值对关键三类）+ `dependency-check` + 契约校验（《接口规范》§2.7）。

---

# 十三、合并前检查清单（服务端 12 问）

1. Controller 里有没有注入 Mapper、写业务 `if`、开事务？（§2.2/§3.2）
2. 类是否超 500 行、方法超 50 行？（§2.5）
3. 出参是不是 `VO` 而不是 `Entity`？VO 里有没有 `password_hash` / `id_card_enc`？（§3.5）
4. 按 id 取/改资源，**归属条件进 SQL 了吗**？（§11.2）
5. 事务内有远程调用吗？有就挪到任务表。（§7.2/§9.2）
6. 涉及余额/状态的更新，有没有 `ge` 条件或乐观锁？（§8.5）
7. XML 里有没有 `${}`？（§8.2/§11.3）
8. 异常有没有把 `e.getMessage()` 返回给客户端？（§5.3）
9. 日志里有没有手机号/身份证/密码/聊天原文的明文？（§10.2）
10. 敏感操作写 `operation_log` 了吗？是 AOP 采集还是手写？（§10.4）
11. 新增的配置项**有人读**吗？（§11.4「死开关」）
12. 状态机新增迁移，有没有单测「非法迁移被拒」？（§12.4）

---

# 十四、每条规则的承接手段（本文件最重要的一节，先读这条）

## 14.1 一条从 jxyd200 学到的教训

jxyd200 有一份 **1,138 行**的《代码结构与注释规范改造计划》，写得极其详尽。实测落地率：

| 规则 | 文档怎么写 | 实际落地 |
|---|---|---|
| Controller 用 `@Validated` + DTO | 模板里写着 | 61 个 Controller 只有 **9 个（15%）** |
| `@Schema` 字段注释 | 整章要求 | 全后端 **1 处**（springdoc 依赖已引，Swagger 是空壳） |
| feature 必须有 `types.ts` | 模板 5 件套之一 | **0 / 13** |
| 类头统一用 `@Description`、明文「不再用 `@author`」 | 规范第 757 行 | `@author` **456** / `@Description` **185**，且 185 个**同时保留** `@author` |
| 允许 `TODO(owner: ...)` | 规范允许 | ESLint 把任何 TODO 报 error → 全仓 **0 个 TODO**（文档与工具打架，工具赢，文档成死条文） |

而它**真正 100% 执行的只有 7 条**——`modules/*/README.md` 三层 100%、路由壳 1 行 63/67、异常不回显、E2E 库名守卫、零 TODO 等。**这 7 条全都有工具或代码强制，不靠自觉。**

**结论（本文件的编辑原则）**：

> **一条规则如果答不上「谁在拦住违反它的人」，它就不该写进规范。**
> 写了只会让人以为它是要求，实际没人守，最后规范变成「理想态文物」，真正被执行的还是那几条有工具兜住的。

## 14.2 本文件的规则承接矩阵

| § | 规则 | 承接手段 | 能否自动拦 |
|---|---|---|---|
| 2.2 | Controller 不许注入 Mapper / 开事务 | **ArchUnit** | ✅ 构建失败 |
| 2.2 | 分层依赖方向 | ArchUnit `layeredArchitecture()` | ✅ |
| 2.3 | 跨域只走 `QueryService`，禁注入他域 Mapper | ArchUnit | ✅ |
| 2.5 | 类 ≤500 行 / 方法 ≤50 行 | **checkstyle** `FileLength` / `MethodLength` | ✅ |
| 3.2 | Controller 不返回 Entity | ArchUnit（返回类型不含 `..entity..`） | ✅ |
| 3.5 | VO 不含 `password_hash` / `id_card_enc` | ArchUnit 字段名断言 | ✅ |
| 4.1 | 禁止 `@author` / `@Date` / `@version` | checkstyle `RegexpSingleline` | ✅ |
| 5.1 | 全局异常处理器存在且覆盖 13 类 | ArchUnit + 单测（故意抛各类异常断言响应） | ✅ |
| 8.2 | XML 禁止 `${}` | **CI grep**：`grep -rn '\${' src/main/resources/mapper/` | ✅ |
| 10.3 | 必须有 traceId | 启动探针 + 单测断言响应头 `X-Trace-Id` | ✅ |
| 10.4 | 操作日志用 AOP，不许在 Handler 里手写 | ArchUnit（禁止 Controller 调 logMapper） | ✅ |
| 11.4 | 禁止明文密钥进仓库 | **gitleaks** | ✅ |
| 12.4 | 状态机 / 资金 / 分账类必须有单测 | **JaCoCo 按类阈值**（只对这几类） | ✅ |
| 11.1 | 鉴权统一，不许逐方法写 | ArchUnit（Controller 不许直接调鉴权工具类） | ✅ |
| 2.6 | `modules/*/README.md` 必须存在 | **CI 断言**（`find` + `test -f`） | ✅ |
| 5.3 | 禁止 `e.getMessage()` 进响应 | 代码评审 | ⚠️ 人工 |
| 7.3 | 事务内禁止远程调用 | 代码评审 + SonarQube `S2229` | ⚠️ 半自动 |
| 9.2 | 任务表领取必须 CAS | 代码评审 + 单测（并发领 3 次只成功 1 次） | ⚠️ 半自动 |
| 11.2 | 归属条件进 SQL | **代码评审**（无法全自动） | ⚠️ 人工 |

**说明**：标 ✅ 的 14 条进 CI 必绿；标 ⚠️ 的 4 条写进 **PR 模板**作为必填勾选项，不假装能自动。

## 14.3 三处最划算的投入

| 工具 | 承接哪些规则 | 为什么划算 |
|---|---|---|
| **ArchUnit** | §2 的分层与跨域、§3.5 的 VO、§11.1 的鉴权 | 一次写规则，长期生效；纯 JUnit 跑，无额外服务 |
| **gitleaks** | §11.4 禁止明文密钥 | ZXZ 和 wgcloud **都是靠人眼漏掉的**（一个 6 处、一个 5 份生产配置） |
| **JaCoCo 类级阈值** | §12.4 只有状态机/资金类要覆盖 | 不设全局数字——全局数字会催生「为了覆盖率而写的假测试」 |

## 14.4 明确「只靠评审」的 4 条

诚实标注，不假装能自动：**§5.3 异常文案、§7.3 事务内禁远程调用、§9.2 任务表 CAS、§11.2 归属校验**。

这四条写进 `.github/pull_request_template.md` 作必填勾选，评审时逐项确认。

## 14.5 明确**不写进**本规范的规则

以下几条常见于其他项目，法泽**不加**，因为无法承接、且收益小于维护成本：

| 不加的规则 | 为什么 |
|---|---|
| 「每个类必须有单测」 | 会催生无断言的测试，覆盖率上去、质量下来 |
| 「每个方法必须有 Javadoc」 | 会催生复述代码的注释；jxyd200 已出现「单行压扁注释骗过检查」 |
| 「禁止 TODO」 | 与《代码注释规范》§7 允许 `TODO(owner:)` 直接冲突。jxyd200 就是两处规定打架，最后 ESLint 赢、文档变废纸 |
| 「必须用构造器注入」 | 已在 §3.2 的示例里体现，但**不单列成规则**——没有工具能拦，写了也没人守 |
| 「禁止 `@Autowired`」 | 同上。除非配 ArchUnit 规则，否则不写 |

> 这五条不是「不重要」，而是「**在没有承接手段之前，写进来是负资产**」。等哪天真把它们做成 ArchUnit 规则，再写进 §14.2。

## 14.6 把踩过的坑固化成机械约束（jxyd200 的这套思想最值得学）

jxyd200 有 **6 个自定义 ESLint 插件**，每个是「`index.js` 9 行注册 + `rule.js` 纯 CommonJS 无第三方依赖」+ 一份 **11 节的 `tech.md`**。其中最有价值的两条：

- **`expo/require-globalcss-and-provider`**：禁 `app/_layout.tsx` 丢 `global.css` 或丢 `Provider`。它 `tech.md` 里把「为什么」写清了——**这是白屏级事故，不是风格问题**。
- **`restrict-linear-gradient`**：禁 `backgroundColor` 值含 `linear-gradient(`。理由写在报错文案里：**RN 的 `backgroundColor` 不接受 CSS 渐变，必须改 `experimental_backgroundImage`**——把平台限制变成编译期报错，而不是等真机背景变白。

**三个可移植点（与语言无关）**：

1. **一条规则 ≈ 一次真实踩坑**，不是「我觉得应该」。
2. **`tech.md` 十一节模板**：目标 / 规则命名 / 行为定义 / 配置项 / 检测策略（精确到 ESTree 节点名）/ 报错信息 / 典型示例（触发 + 不触发）/ **边界与不做什么** / 性能 / 测试计划。**「不做什么」那一节最有价值**——它逼你先想清误报面。
3. **必须写测试**（jxyd200 没写，这是它的缺陷）：每条规则配 `RuleTester` 的 valid/invalid 数组。它的三份 `tech.md` 都写了「测试计划」却 0 个测试文件——**这正是「规则说了但没承接」的另一种形态**。

**法泽的落点**：

| 端 | 用什么 | 落什么规则 |
|---|---|---|
| 前端 | 自建 ESLint 插件（学 jxyd200 形态） | `AGENTS.md` §5 的禁止项：`any`、颜色字面量、`left/right`、裸 `<Text>`、`alert()`、BiDi 控制符 |
| 后端 | **checkstyle + ArchUnit，不自建插件** | §14.2 那 14 条。Java 生态已有成熟工具，自建是重复造轮子 |

---

# 附录 A：三个参考项目「抄/不抄」速查

| 主题 | ZXZ | wgcloud | jxyd200 | 法泽 |
|---|---|---|---|---|
| **注释量**（ZXZ 第一强项） | `@param` **668** / `@return` **299** / `@Schema` **1,576** / `@Operation` **46/46 = 100%** / `@Tag` 9/9 ✅ | 方法注释 **35%**（抽样 10 类：Controller 0%、CommonConfig 0%）⚠️ | 密度表最成体系，但落地分裂 ⚠️ | §四：模板固定 + `@Schema` 与校验**成对** + `@Operation` 全覆盖 |
| **目录结构**（ZXZ 第二强项） | **技术分层 + 业务子包双维度**：`dto/xzb/`、`mapper/dm\|mysql/`、`rule/calculation/bigdecimal/{fixed,incentive,insurance}/` ✅ | 按技术角色平铺，无层内下钻 ⚠️ | feature-first ✅ | **域优先 + 域内下钻**（`modules/trade/rule/fee/`），见 §2.1 |
| **安全机制覆盖** | 2 类（token 拦截器 + `@ApiEncrypt` 加解密 Filter） | **11 类，覆盖最全** ✅ 但其中 5 类实现有硬伤（§1.5） | 3 类（token 分平台、401 只清被拒 token、日志不打敏感） | §十一：以 wgcloud 11 项为骨架，逐项给正确实现 |
| 统一响应 | `R<T>{code,msg,data}` 无 traceId | 三种风格并存 + `code` 只有 0/1 | `AjaxResult`（RuoYi） | `R<T>` + traceId（《接口规范》§5.1） |
| 全局异常 | 13 分支，但 `e.getMessage()` 透传 ❌ | **完全没有** ❌ | **对外稳定文案 + 对内完整日志，且把原因写进 Javadoc** ✅ | 13 分支 + 稳定文案 + 原因写进注释 |
| 错误码 | **0 个枚举** ❌ | 0/1 两值 ❌ | — | 78 个码的 `ErrorCode` 枚举 |
| 参数校验 | bo/dto 分离好 ✅，`@Size` 0 处 ❌ | 基本没有 ❌ | 规范要求但**61 个 Controller 只有 9 个用** ❌ | §6 三层 + `@Size` 强制 |
| 注释的元数据 | `@Author` 大写 248 处、`@Date` 4 种格式（含占位符）❌ | 五件套 `@date` 恒为「2021年1月16日」❌ | 三风格并存（456/185/98）❌ | **禁元数据**，只写职责/边界/约束 |
| 分层 | 上帝类 4734 行 ❌、Controller 写业务 ❌ | 单模块、Service 不接口化（合理） | **职责一句话可判定** ✅ 但 9 个 Controller 注入 Mapper ❌ | 按域分包 + 行数上限 + ArchUnit |
| `rule/` 纯规则层 | 有 `rule/calculation/` ✅ | — | **可 javac 单测** ✅ | `OrderStatusMachine` / `FeeRule` |
| AOP | **0** ❌ | **0** ❌ | 未用 ❌ | 操作日志 AOP 强制 |
| 事务 | 1 处（读多写少的取舍） | 0 | 极少 | §7 五条硬规则（法泽写密集，不能照搬） |
| SQL 注入 | **0 处 `${}`** ✅ | 49 处 ❌ | — | 0 处，`ORDER BY` 白名单 |
| 越权 | **只验 token 不验归属** ❌ | 白名单式授权 ❌ | — | 归属条件进 SQL |
| 密钥 | 明文 6 处 ❌ | 明文 5 份 ❌ | `.gitignore` 每条配原因 ✅，**但无扫描工具** | 环境变量 + gitleaks |
| 日志 | logback 三文件 + Async ✅ | 1 个 appender ❌ | 只打 `requestId/method/path/status/ms` ✅ | §10.5 |
| traceId | 无 ❌ | 无 ❌ | 有 `requestId` ✅ | `TraceIdFilter` |
| 线程池 | `ThreadPoolConfig` 教科书 ✅ | 无界队列 ❌ | — | §9.4 |
| 定时任务 | — | 配置写 master/slave ❌ | — | 行锁/Redis 锁 |
| 测试 | 1 个类 ❌ | 0 ❌ | 2 个（javac 手搓，不走 surefire）❌ | 状态机/资金链路必测 + JaCoCo |
| CI | 有 ✅ | 无 ❌ | **无**（无 `.github/`、无 husky）❌ | ArchUnit + gitleaks + mvn verify |
| 文档密度 vs 落地 | — | — | **1138 行规范只有 7 条真落地** ⚠️ | **§十四：无承接手段的规则不写** |

# 附录 B：反例库（照抄会出事）

| 反例 | 出处 | 后果 |
|---|---|---|
| Controller 里 68 行日期回退 | ZXZ `BIController:231-298` | 逻辑不可复用、不可测 |
| 4734 行的 Service | ZXZ `IndexServiceImpl` | 无边界，改一处怕碰十处 |
| 手拼 JSON 做第二套响应 | ZXZ `RecordController:66-72` | `msg` 变 `message`，前端要写两套解析 |
| 只验 token 不验归属 | ZXZ `ZQTAuthInterceptor:34-53` | 改一个 `companyId` 就能查别家数据 |
| 明文生产密码进 git | wgcloud `config/prod/*.yml`（5 份） | 凭据永久泄露，删了也要轮换 |
| 18 个 Controller 手写 token 校验，**漏 7 个**（且这 7 个在免登录白名单里） | wgcloud `DbTableController:73` 等 | 未登录可拉全量设备配置 |
| 49 处 `${orderBy}` | wgcloud `SystemInfoMapper:359-365` | SQL 注入 |
| 无界队列线程池 | wgcloud `ThreadPoolUtil:18` | `maxPoolSize` 失效，OOM |
| 先 SELECT 后 UPDATE 领任务 | wgcloud `ShellStateMapper:51-56` | 同一条任务被两个消费者执行 |
| 「已下发」无租约无重试 | wgcloud `ShellInfoController:97-111` | 消费者崩溃则任务永挂 |
| `addAll → clear → save` | wgcloud `DataScheduledTask:196-201` | save 失败则整批数据丢失 |
| 注释与实现不符 | wgcloud `AgentGoController:751`（说 29s，实际 9s） | 维护者按注释理解系统 → 判断错 |
| 写了不读的死开关 | ZXZ `application-dev.yml:89-91` | 制造「鉴权可关」错觉 |
| `e.toString()` 返回客户端 | wgcloud `SystemInfoController:140` | 泄露堆栈 |

---

> **【文档基线 v1.0】** 本文件属于「法泽 FAZE 文档基线」（2026-10-30 新增）。
> 基线与文件指纹见 `docs/BASELINE.md`；本文件新增时按 `docs/BASELINE.md` §4 的「四件事」办理。
