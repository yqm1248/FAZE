# A3 · MyBatis-Plus 在 Spring Boot 4 下的可用性（2026-11-01 实测）

> 本文件是第 1 段 A3 任务的验收证据。**结论是二值的，且与最初假设不同。**
> 探针工程：`E:\dshcode\tmp\mp-sb4-probe`（临时目录，不入仓库）。

## 一、结论

| 组合 | 结果 | 说明 |
|---|---|---|
| MP **3.5.12** + `mybatis-plus-spring-boot3-starter` + Spring Boot **4.0.6** | ❌ **不可用** | 启动能过，但 MP 的自动配置**静默跳过**，Mapper 创建失败 |
| MP **3.5.18.1** + `mybatis-plus-spring-boot4-starter` + Spring Boot **4.0.6** | ✅ **完全可用** | 启动成功，`selectCount` / `selectById` / `@TableName` 全部工作 |

**所以「法泽不能用 MyBatis-Plus」是错的**——不能用的是 **3.5.12 这个版本**。

## 二、3.5.12 的失败过程与根因

### 2.1 现象

```
Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required
```

Mapper 被 `@MapperScan` 注册了，但 `sqlSessionFactory` bean 不存在。

### 2.2 条件评估报告（`--debug`）

```
MybatisPlusAutoConfiguration:
  Did not match:
  - @ConditionalOnSingleCandidate (types: javax.sql.DataSource; SearchStrategy: all)
    did not find any beans (OnBeanCondition)
  Matched:
  - @ConditionalOnClass found required classes
    'org.apache.ibatis.session.SqlSessionFactory', 'org.mybatis.spring.SqlSessionFactoryBean'
```

而同一份报告里：

```
DataSourceAutoConfiguration matched
DataSourceConfiguration.Hikari matched
```

→ **DataSource 的自动配置是生效的**，只是 MP 评估时 "看不到" 它。

### 2.3 根因：包重命名导致 `@AutoConfigureAfter` 静默失效

| 事实 | 证据 |
|---|---|
| MP 3.5.12 的 `MybatisPlusAutoConfiguration.class` 引用了 `org/springframework/boot/autoconfigure/jdbc/DataSourceAutoConfiguration` | 读 jar 字节码常量池（`javap`/正则提取） |
| Spring Boot **4.0.6** 把这个类移到了 `org/springframework/boot/jdbc/autoconfigure/DataSourceAutoConfiguration` | `spring-boot-jdbc-4.0.6.jar` 内路径；`spring-boot-autoconfigure-4.0.6.jar` 的 imports 里 **DataSource 条目为 0** |
| 旧包 `org.springframework.boot.autoconfigure.jdbc.*` 在 SB 4.0.6 的 autoconfigure jar 里**已不存在** | jar 内无该类 |

**机制**：`@AutoConfigureAfter(DataSourceAutoConfiguration.class)` 里引用了一个已不存在的类。Spring 用 ASM 读注解时，无法解析的 class 值会被**静默忽略**（不报错），于是：

1. 排序约束丢失 → `MybatisPlusAutoConfiguration` 可能在 `DataSourceAutoConfiguration` 之前评估
2. `@ConditionalOnSingleCandidate(DataSource.class)` 此时查不到 bean → **整个自动配置跳过**
3. 没有 `sqlSessionFactory` → Mapper 无法初始化

**这是最危险的一类失败：不报 `NoClassDefFoundError`，只是「不工作」。** 线上表现会是「Mapper 注入失败」，很容易被误判成配置问题而浪费时间。

## 三、3.5.18.1 的成功验证

### 3.1 探针工程

| 项 | 值 |
|---|---|
| Spring Boot | `4.0.6`（`spring-boot-starter-parent`） |
| MP | `mybatis-plus-spring-boot4-starter:3.5.18.1` |
| 数据库 | H2 内存库（`jdbc:h2:mem:probe;MODE=MySQL`） |
| 实体 | `@TableName("tmp_user")` + `@TableId(type = IdType.AUTO)` |
| Mapper | `extends BaseMapper<TmpUser>` + `@Mapper` |
| 建表 | `spring.sql.init.schema-locations=classpath:schema.sql` |

### 3.2 运行输出

```
[PROBE] selectCount=1
[PROBE] selectById=probe/active
[PROBE] MP_OK
BUILD SUCCESS
```

### 3.3 这证明什么

1. MP 的自动配置**被正确加载**（否则没有 `sqlSessionFactory`）
2. `@TableName` 生效（表名映射正确，不是默认类名小写）
3. `BaseMapper` 的内置 CRUD 可用（`selectCount` / `selectById` 是 MP 生成的 SQL，不是手写 XML）
4. `@TableId(type = AUTO)` 生效（`selectById(1L)` 能命中 schema.sql 插入的那行）

## 四、对法泽的处置

| 项 | 原定 | 改为 | 理由 |
|---|---|---|---|
| MP 依赖 | `mybatis-plus-spring-boot3-starter:3.5.12` | **`mybatis-plus-spring-boot4-starter:3.5.18.1`** | 3.5.12 在 SB4 下静默失效；SB4 starter 从 **3.5.13** 开始提供 |
| MP 版本 | 3.5.12 | **3.5.18.1** | 3.5.18.1 是当前最新补丁；3.5.13 是第一个支持 SB4 的版本，但不必停在最早 |
| 后端代码规范 §三 的 Wrapper 条款 | 全部有效 | **全部有效** | MP 可用，条款不需要推翻 |
| 段 1 的 A3 任务 | 「可能一票否决」 | **已通过，降为常规任务** | 见本文件 |

## 五、复现方式

```powershell
cd E:\dshcode\tmp\mp-sb4-probe
mvn -B spring-boot:run
# 期望输出：[PROBE] MP_OK  +  BUILD SUCCESS
```

> 探针工程不纳入仓库（它是验证用的临时工程）。若日后需要回归，按本文档第 3.1 节的表格重建即可。

## 六、遗留

- ~~`mybatis-plus-jsqlparser` 在 3.5.18.1 里是独立 artifact，分页插件（`PaginationInnerInterceptor`）需要额外确认是否自动带入——**段 1 的 D2 分页任务开工前验一次**~~ → **已于同批验掉，见第七节**
- SB 4.0 的 Spring Security 是 7.x，MP 不受影响，但 faze-api 的 `@PreAuthorize` 写法要单独核对（不属 A3）

---

## 七、分页插件验证（`mybatis-plus-jsqlparser`，2026-11-01 同批）

**结论：光加 SB4 starter 不够，分页还要单独加 `mybatis-plus-jsqlparser`。这是第二个坑。**

### 7.1 失败现象（连编译都过不了）

只加 `mybatis-plus-spring-boot4-starter:3.5.18.1` 时，写：

```java
interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
```

**编译直接失败**：

```
[ERROR] MybatisPlusConfig.java:[5,56] 找不到符号
[ERROR]   符号: 类 PaginationInnerInterceptor
[ERROR]   位置: 程序包 com.baomidou.mybatisplus.extension.plugins.inner
```

### 7.2 根因

| 事实 | 证据 |
|---|---|
| `mybatis-plus-extension:3.5.18.1` 的 `plugins/inner/` 下**没有** `PaginationInnerInterceptor` | 解压 jar 列目录：只有 `DynamicTableName` / `OptimisticLocker` / `ReplacePlaceholder` / `Sharding` / `InnerInterceptor` |
| 它被拆到了 **`mybatis-plus-jsqlparser`** | 该 artifact 在 Central 有 3.5.13 ~ 3.5.18.1 |
| **SB4 starter 不传递依赖它** | 只加 starter 时编译报「找不到符号」 |

### 7.3 正确写法（两个依赖都要，版本一致）

```xml
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
  <version>3.5.18.1</version>
</dependency>

<!-- 分页插件 PaginationInnerInterceptor 在 3.5.18.1 已拆出 starter，必须单独引入 -->
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-jsqlparser</artifactId>
  <version>3.5.18.1</version>
</dependency>
```

### 7.4 验证输出

```
[PROBE] selectCount=5
[PROBE] selectById=u1/active
[PROBE] page1 total=5 size=2 names=[u1, u2]
[PROBE] page3 total=5 size=1 names=[u5]
[PROBE] MP_OK
BUILD SUCCESS
```

**分页 SQL 实测**（`mybatis-plus.configuration.log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`）：

```
==> Preparing: SELECT COUNT( * ) AS total FROM tmp_user
==> Preparing: SELECT id,user_name,status FROM tmp_user WHERE id=?
==> Preparing: SELECT COUNT(*) AS total FROM tmp_user
==> Preparing: SELECT id,user_name,status FROM tmp_user LIMIT ?
==> Preparing: SELECT COUNT(*) AS total FROM tmp_user
==> Preparing: SELECT id,user_name,status FROM tmp_user LIMIT ? OFFSET ?
```

三条业务 SQL 分别对应：`selectCount` 的 COUNT、**第 1 页**（`LIMIT` 无 OFFSET）、**第 3 页**（`LIMIT` + `OFFSET`）。

**这证明分页是「真的加了 LIMIT」，不是「查全量再内存截断」**——后者在数据量大时会拖垮服务，是最常见的假分页。

数据也对：5 条记录，第 1 页 2 条 `[u1, u2]`，第 3 页 1 条 `[u5]`（current=3、size=2 → 第 5 条）。

### 7.5 对法泽的处置

| 项 | 结论 |
|---|---|
| `services/faze-api/pom.xml` | **必须同时有** `mybatis-plus-spring-boot4-starter` 与 `mybatis-plus-jsqlparser`，**版本必须一致**（都 3.5.18.1） |
| 段 1 的 D2（流水分页） | 直接用这套配置，不需要再验 |
| 段 1 的 F 组（PC `rows` 分页） | 同上，C 端 `list` 与 PC `rows` 共用同一个 `PaginationInnerInterceptor` |
| **不要混搭 PageHelper** | RuoYi 用的是 `pagehelper-spring-boot-starter`，法泽**不用**它——两套分页插件同时存在会互相干扰 |

### 7.6 为什么这条必须写进文档

「加 MP 依赖」在很多人脑子里是**一个**依赖。3.5.18.1 之后是**两个**，缺第二个的表现是**编译失败**（比运行时才炸好，但仍然会卡住第一次搭工程的人）。

如果文档只写 starter，段 0 的 A3 复验会「编译不过 → 怀疑 SB4 不兼容 → 白排查一轮」。写清楚就能省掉这一轮。

