# 法泽 FAZE · 后端代码规范（V1.0）

> **用途**：`services/faze-api`（Spring Boot 3.5 / Java 17）的代码规范。三端之中**只有服务端**适用本文件；移动端看《代码注释规范》与 `docs/standards/`，PC 后台看 RuoYi 约束。
> **定稿日期**：2026-10-30
> **参考来源**：`D:\gitzxz\zxz-bi-server`（Spring Boot **3.5.3** / JDK 17，324 个 java / 30,244 行）、`C:\Users\14541\Desktop\2026.7.13实习\wgcloud`（Spring Boot 2.7.12，342 个 java / 60,857 行）、`E:\项目文件\jxyd200`（monorepo，后端 RuoYi 二次开发，Spring Boot **3.5.14** / Java 17，672 个 java / 52,738 行）。
> **三个参考项目的后端全部是 Spring Boot 3.5 同代（ZXZ 3.5.3 / jxyd200 3.5.14）**，所以本节大部分是**零成本直搬**，不是「改造后移植」。
> **不重复的内容**：接口契约见《[法泽FAZE_接口规范_v1.0.md](法泽FAZE_接口规范_v1.0.md)》；表结构见《[法泽FAZE_数据表规范_v1.0.md](法泽FAZE_数据表规范_v1.0.md)》；错误码见《[法泽FAZE_错误码规范_v1.0.md](法泽FAZE_错误码规范_v1.0.md)》。
>
> **⚠️ 先读 §十四**：本文件的每条规则都配了「承接手段」。**答不上「谁在拦住违反它的人」的规则，就不该写进来**——这是从 jxyd200 的 1,138 行规范只有 7 条真落地中学到的。

---

# 一、先看问题：三个参考项目各自能给我们什么

## 1.1 三份实测快照（都是数出来的，不是印象）

| 项 | ZXZ | wgcloud | jxyd200（后端） | 说明 |
|---|---|---|---|---|
| Spring Boot | **3.5.3**（`pom.xml:8`） | 2.7.12（`pom.xml:16`） | **3.5.14**（`services/ruoyi-api/pom.xml:19-21`） | 三个都是 3.5 同代或接近 |
| JDK 声明 | **17**（`pom.xml:15`） | 1.8（`pom.xml:24`） | 17 | — |
| Java 文件 / 行 | 324 / 30,244 | 342 / 60,857 | 672 / 52,738 | — |
| 后端形态 | 单体 Spring Boot | 单体 Spring Boot | **RuoYi 二次开发，Maven 多模块** | jxyd200 与法泽的 PC 后台同源 |
| ORM | MyBatis-Plus 3.5.12 BOM，但**真实业务 100% 自定义 XML，Wrapper 零使用** | MyBatis + PageHelper，全 XML | MyBatis-Plus | 法泽用 MP，两者都不能直接照搬 |
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
| **ZXZ** | ①`bo`(入参)/`dto`(出参) 分离；②13 分支异常处理器 + 校验异常**聚合 msg**；③`ThreadPoolConfig` 全参数化线程池；④`logback` 三文件 + `AsyncAppender` + `discardingThreshold=0`；⑤计算规则「枚举持 Class + 容器取 Bean」；⑥导出「策略 + 工厂 + 路由表」；⑦`BigDecimalUtils` 统一舍入；⑧双数据源 `DataSourceConfig` 结构 | ①Controller 里写业务（`BIController:231-298` 68 行日期回退）；②上帝类；③手拼 JSON 做第二套响应（`RecordController:66-72`，`msg` 变 `message`）；④异常 `e.getMessage()` 透传；⑤**只验 token 不验归属**（`ZQTAuthInterceptor:34-53`）；⑥明文密钥进 git（`application-prod.yml:6,27,43,76,93`）；⑦写了不读的死开关（`application-dev.yml:89-91` vs `ZQTConfig:15-35`）；⑧`RedisUtil` 里 17 处 `printStackTrace` |
| **wgcloud** | ①**agent 主动 pull + DB 任务表**（§9.2 直接采用）；②显式 `TaskScheduler` 线程池；③攒批落库；④「当前状态表」先删后插；⑤异常「文件 + 库表」双写；⑥限流双窗口（闸值放 Redis）；⑦「已投递」是状态不是删除 | ①明文生产凭据（`config/prod/*.yml` 5 份）；②**8 个 agent 接口未鉴权且在白名单**（`DbTableController:73` 等）；③49 处 `${}` 拼 `orderBy`；④前端算 MD5 + 后端直查（pass-the-hash）；⑤`StaticKeys` 293 行「全局变量垃圾桶」（常量与可变状态混放）；⑥静态字段 + `getBean()`；⑦无界队列线程池（`ThreadPoolUtil:18`，`maxPoolSize` 永远无效）；⑧统一异常出口缺失导致 55 个 Controller 复制「异常写库」 |
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

---

# 二、包结构与分层

## 2.1 目录树（裁决一处冲突）

**现状冲突**：`AGENTS.md` §3.2 的目录树写 `modules/` 下 **9 个域**；技术路线 §4.2 写 **12 个域**（多 `workbench`、`social`、`admin`）。两份文档不一致。

**裁决：以技术路线 §4.2 的 12 域为准**，`AGENTS.md` §3.2 是缩写，实施时按 12 域建包。

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

---

# 四、注释（Java 形式，密度要求与前端一致）

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

## 10.5 logback 配置（抄 ZXZ 的 `logback.xml:81-99,122-128`）

三条必须：
1. **三个 appender**：`console` + `file`（全量）+ **`error`（单独）**。
2. **`AsyncAppender` + `discardingThreshold=0`**（不丢 WARN/ERROR）。
3. 滚动策略：按天 + 单文件 100MB + 保留 30 天。

**反例**：wgcloud `logback-spring.xml` 注释写「不同级别存不同文件」，实际**只配了 1 个 FILE appender**。

---

# 十一、安全

## 11.1 鉴权

| 通道 | 方案 |
|---|---|
| C 端 | JWT（`Authorization: Bearer`）+ Redis 黑名单 |
| PC 端 | **待拍板**（见《接口规范》§1.3 的 P-1：共享 RuoYi token A 方案 / faze-api 自签 B 方案） |
| 内部 | 内网白名单 + 服务间令牌 |

**禁止**：把鉴权逻辑写在每个 Controller 方法里（wgcloud 的 8 个未鉴权接口就是这么漏的），必须用注解 + 拦截器/过滤器统一。

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

## 12.4 测试

| 层 | 要求 |
|---|---|
| 状态机 | **必须单测**：合法迁移通过、非法迁移抛异常 |
| 资金链路 | **必须单测**：幂等（同键调 3 次只生效 1 次）、余额不足、并发扣减 |
| Controller | MockMvc 冒烟（每个接口一个 200/400 用例） |
| 覆盖率 | 不设全局数字（会造假），只对**上述三类**要求覆盖 |

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

---

# 附录 A：三个参考项目「抄/不抄」速查

| 主题 | ZXZ | wgcloud | jxyd200 | 法泽 |
|---|---|---|---|---|
| 统一响应 | `R<T>{code,msg,data}` 无 traceId | 三种风格并存 + `code` 只有 0/1 | `AjaxResult`（RuoYi） | `R<T>` + traceId（《接口规范》§5.1） |
| 全局异常 | 13 分支，但 `e.getMessage()` 透传 ❌ | **完全没有** ❌ | **对外稳定文案 + 对内完整日志，且把原因写进 Javadoc** ✅ | 13 分支 + 稳定文案 + 原因写进注释 |
| 错误码 | **0 个枚举** ❌ | 0/1 两值 ❌ | — | 78 个码的 `ErrorCode` 枚举 |
| 参数校验 | bo/dto 分离好 ✅，`@Size` 0 处 ❌ | 基本没有 ❌ | 规范要求但**61 个 Controller 只有 9 个用** ❌ | §6 三层 + `@Size` 强制 |
| 注释 | `@Author` 大写 248 处、`@Date` 4 种格式 ❌ | 五件套 + 方法注释 35% ❌ | 密度表最成体系 ✅ 但**三风格并存** ❌ | 类头三行 + 来源注释 + `// 任务 n` |
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
| 8 个接口未鉴权且在白名单 | wgcloud `DbTableController:73` 等 | 未登录可拉全量设备配置 |
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
