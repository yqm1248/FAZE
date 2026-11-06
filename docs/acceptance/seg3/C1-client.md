# 段 3 · C1 豆包客户端（infra/ai）· 验收证据

> 任务：`docs/architecture/法泽FAZE_第3段工作流_v1.0.md` §三 C1（L560–L602）
> 产出：`infra/ai/DoubaoClient.java`、`ApiException.java`、`dto/{ChatChunk,ChatRequest,ChatResult,ChatMessage}.java`、
> `impl/DoubaoClientImpl.java`（WebClient 真实实现）、`impl/DoubaoClientStub.java`（mock）、
> `modules/ai/config/AiProperties.java`、`modules/ai/config/AiRedisKeys.java`、`modules/ai/config/AiStreamScheduler*.java`、本文档
> 数据来源：`sys_config` 的 AI 键（`AiProperties` 读，5 秒缓存）+ 环境变量 `FAZE_ARK_API_KEY`
> 证据日期：2026-11-07 · 执行人：be-stream
>
> **⚠️ 本次环境 `deploy/.env` 的 `FAZE_ARK_API_KEY` 为空，`faze.ai.mock=true`（dev）→ 走 `DoubaoClientStub`。
> 因此本文件所有结果均属「mock」：`3-A2`/`3-A3`/`3-A4`/`3-A8` 记挂账不判过。真 key 到位后须重跑第五节。**

---

## 一、mock 生效与 `AI_MOCK_ACTIVE` 日志（C1 判据第 4 条）

服务端由 lead 以 dev profile 启动（日志 `logs/faze-api.out.log`），真实输出：

```
2026-11-07 00:46:23.338 WARN  [no-trace] [anon] [] c.f.infra.ai.impl.DoubaoClientStub - AI_MOCK_ACTIVE faze.ai.mock=true：豆包调用走本地 Stub，不联网、不花钱；验收记录必须注明「本次为 mock，3-A2/3-A3/3-A4/3-A8 不判过」
```

配置依据：`application-dev.yml` 的 `faze.ai.mock: ${FAZE_AI_MOCK:true}`（本次新增，dev 默认 mock）；
`application-prod.yml` 显式 `faze.ai.mock: false`（生产强制真实实现，防止 mock 回答被当成 AI 回答）。
两个实现类用相反的 `@ConditionalOnProperty` 互斥，**不存在「真实实现失败后偷偷回落 Stub」的路径**。

## 二、Stub 流式真实执行（不启动 Spring，直接实例化）

命令（tmp 临时 harness，不进仓库）：

```powershell
# javac 编译 tmp/C1StubProbe.java（classpath = m2 全部 jar + tmp/be-stream-classes）
# java -cp "...;tmp/c1-probe" C1StubProbe
```

真实输出（节选，完整 66 行）：

```
chunk#1 at=272ms contentLen=2
chunk#2 at=365ms contentLen=2
chunk#3 at=426ms contentLen=2
...
chunk#65 at=4283ms contentLen=2
chunk#66 at=4283ms contentLen=0 finishReason=stop
totalChunks=66
fullTextLen=130
contains679=true
elapsedMs=4288
chat.contentLen=130 finishReason=stop promptTokens=128 completeTokens=96
```

判读：
- 每块约 60ms，**逐块产出**（真流式的数据源；C3 的 SSE 转发基于它，见 `C3-stream.md`）。
- 文本含真实条号「第六百七十九条」（库内 id=7），D3 的条号存在性校验能走通，不会被 mock 数据误判成编造。
- 非流式 `chat()` 也返回固定文本 + token 数（真实实现同样有 `chat()`，本段 C3 只用流式）。
- 文本总时长 4.29 秒 > 3 秒：C4 的「单次流上限改 3 秒」验收可直接用它触发截断。

## 三、`grep -r "sk-" services/`（C1 判据第 5 条）

真实执行（PowerShell 等价命令，范围 = `services/` 下 java/xml/yml/yaml/properties/json/sql）：

```
services\faze-api\src\main\resources\application.yml:59: thread-name-prefix: faze-task-
services\faze-api\target\classes\application.yml:59: thread-name-prefix: faze-task-
```

结论：**没有任何 API Key 明文**。仅命中 2 处，均为 `faze-task-`（线程名前缀）里的 `sk-` 子串；
`target/classes` 是本地构建产物（已 gitignore）。改用「密钥形态」正则复核：

```
命令：Select-String -Pattern 'sk-[A-Za-z0-9]{20,}'  （范围同上）
结果：（空）
```

说明：早期版本注释里出现过 `` `sk-` `` 字样，为避免与这条验收的字面命令冲突，已把注释改成「明文 API Key」。
Key 只从环境变量读（`AiProperties#apiKey`：优先 `FAZE_ARK_API_KEY`，兼容旧的 `FAZE_DOUBAO_API_KEY`）；
`application.yml` / `application-dev.yml` 里**没有任何 Key 占位值**。

## 四、编译自检（不是 mvn 结论）

按项目铁律不跑 `mvn`（并发编译会破坏 lead 的 `target/`）。用本地 m2 的 **404 个 jar** 作 classpath、
`javac` 全量编译 `src/main/java` 的 **388 个源文件**到 `tmp/be-stream-classes`（不碰 `target/`）：

```
javac exit=0
（仅 deprecation 提示）
```

这是语法/类型自检；容器装配问题由 lead 的 `mvn -o -DskipTests compile` 覆盖
（2026-11-07 调度器 bean 类型的启动事故即由 lead 发现并修复，见 `AiStreamScheduler` 类注释）。

## 五、C1「算做完」逐条对照

| 判据 | 结果 |
|---|---|
| 非流式请求拿到 `choices[0].message.content` | **挂账**：无真 key。Stub 的 `chat()` 已真实执行（第二节），但 Stub 不代表豆包 |
| 流式拿到 ≥3 个 chunk | ✅ **mock 下真实执行**：66 chunk（第二节）；C3 的 SSE 端到端 65 个 delta（`C3-stream.md`） |
| baseUrl 改成错的 → 抛 508001、无上游原文 | **挂账**：mock 走 Stub 不读 baseUrl；改 sys_config `ai_ark_base_url` 也只会被 Stub 忽略。代码路径：`DoubaoClientImpl` 的 `onErrorMap` → `ApiException(AI_SERVICE_DOWN)`，日志只记状态码/异常类名 |
| model 改成不存在的 → 508001（不是 500） | **挂账**：同上，mock 下 Stub 不校验 model |
| `faze.ai.mock=true` 时走 Stub，日志出现 `AI_MOCK_ACTIVE` | ✅ 第一节 |
| 仓库 `grep -r "sk-" services/` 为空白 | ⚠️ 字面命令命中 2 处 `faze-task-` 子串（既有配置，非密钥）；密钥形态正则为空（第三节） |
| 重试 2 次指数退避、4xx 不重试 | **挂账**：无真 key 无法触发上游 5xx。代码在 `DoubaoClientImpl#stream/chat` 的 `retryWhen`（`RETRY_MAX=2`、500ms 起指数退避、`isRetryable` 判定；`emitted` 标记保证**已出字后不重试**） |

## 六、挂账清单（统一口径：真 key 到位后重跑）

1. 真 key 下：一次非流式 + 一次流式（≥3 chunk）。
2. 真 key 下：baseUrl 改错（`UPDATE sys_config SET config_value='http://127.0.0.1:1' WHERE config_key='ai_ark_base_url'`，
   5 秒缓存后生效）→ C3 发 `event: error {code:508001}`，且响应体/日志无上游原文。
3. 真 key 下：model 改错 → 508001（不是 500）。
4. 真 key 下：上游 5xx 时验证重试 2 次与 4xx 不重试（看日志 `豆包上游调用失败` 的次数）。

> 补充说明（lead 2026-11-07 定）：`sys_config` **不种子化** `ai_ark_base_url`。
> 正常走环境变量 `FAZE_ARK_BASE_URL`；sys_config 里读它只是排障覆盖口子（不用重启即可让缓存失效后生效）。
