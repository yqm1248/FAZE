/*
 * 职责：段 0 的三个探针接口（ping / probe/law / probe/ai）。它们不是产品接口，是**验收工具**。
 * 调用方：App 端（0-A2 验真接口）、验收脚本（0-A5 验 ngram 与 SSE 首字节）。
 * 数据来源：Environment（ping）、LawSearchService（law）、DoubaoClient（ai）。
 * 注意：
 *   1. **整个类加 @Profile("!prod")**：生产 profile 下这三个接口不存在，请求会落到 404。
 *      理由（《接口规范》§3.1 裁决 1）：probe/ai 会真实消耗豆包配额且无鉴权，
 *      留一个「无鉴权的、能烧钱的」接口在生产是最典型的线上事故源。
 *   2. 404 也要是 **JSON**（R 体），所以 application.yml 里开了
 *      `spring.mvc.throw-exception-if-no-handler-found` 并关了静态资源映射；
 *      这一对配置缺一个，生产就会回 Tomcat 的 HTML 404 页。
 *   3. `probe/ai` 返回 SSE，事件协议（本段补定的契约）：
 *        event: meta   data: {"mock": true|false}                     ← 首帧，必须让验收人知道是不是 mock
 *        event: delta  data: {"text": "..."}                           ← 可 0~N 次
 *        event: done   data: {"totalChars": N}                         ← 正常结束
 *        event: error  data: {"code": 508001, "msgKey": "..."}         ← 失败结束
 *   4. kw 长度限制（本段补定）：1~50 字符，空或超长回 401001。**单字返回 0 命中是预期结果**，
 *      因为 ngram_token_size=2（实测），不是错误。
 */
package com.faze.infra.probe;

import com.faze.common.exception.ErrorCode;
import com.faze.common.R;
import com.faze.infra.law.LawSearchService;
import com.faze.infra.law.dto.LawHitVO;
import com.faze.infra.probe.dto.PingVO;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 段 0 探针控制器（仅非生产 profile）。
 */
@RestController
@RequestMapping("/api/v1/pub")
@Profile("!prod")
@Validated
@Slf4j
public class ProbeController {

    /** SSE 事件名：首帧元信息。 */
    public static final String EVT_META = "meta";
    /** SSE 事件名：增量文本。 */
    public static final String EVT_DELTA = "delta";
    /** SSE 事件名：正常结束。 */
    public static final String EVT_DONE = "done";
    /** SSE 事件名：失败结束。 */
    public static final String EVT_ERROR = "error";

    /** SSE 连接存活上限（毫秒）。《接口规范》§6.7：单次流最长 120 秒。 */
    private static final long SSE_TIMEOUT_MS = 120_000L;
    /** 探针提示词（固定，不为验收增加变量）。 */
    private static final String PROBE_PROMPT = "请用一句话说明什么是合同。";
    /** kw 最大长度，与 LawSearchService.MAX_KW_LENGTH 保持一致。 */
    private static final int KW_MAX = LawSearchService.MAX_KW_LENGTH;

    private final LawSearchService lawSearchService;
    private final DoubaoClient doubaoClient;
    private final TaskExecutor taskExecutor;
    private final Environment environment;
    private final String appName;
    private final String version;

    /**
     * 构造控制器。
     *
     * <p>`appName` / `version` 用 `@Value` 而不是 `@ConfigurationProperties`：它们是**应用元数据**，
     * 不是「可配置的领域阈值」，为两个常量单开属性类不划算（规范 §12.3 针对的是领域配置）。</p>
     *
     * @param lawSearchService 法条检索服务
     * @param doubaoClient     豆包客户端
     * @param taskExecutor     Spring 异步执行器（用于把 SSE 写入放到工作线程）
     * @param environment      运行环境（取 active profiles）
     * @param appName          应用名（`spring.application.name`）
     * @param version          版本号（`faze.api.version`，缺省取 Maven 版本）
     */
    public ProbeController(LawSearchService lawSearchService,
                           DoubaoClient doubaoClient,
                           TaskExecutor taskExecutor,
                           Environment environment,
                           @Value("${spring.application.name:faze-api}") String appName,
                           @Value("${faze.api.version:0.0.1-SNAPSHOT}") String version) {
        this.lawSearchService = lawSearchService;
        this.doubaoClient = doubaoClient;
        this.taskExecutor = taskExecutor;
        this.environment = environment;
        this.appName = appName;
        this.version = version;
    }

    /**
     * 探针 ping：返回应用名、版本、服务端时间、环境。
     *
     * @return 统一响应体，data 为 PingVO
     */
    @GetMapping("/ping")
    public R<PingVO> ping() {
        PingVO vo = new PingVO();
        vo.setAppName(appName);
        vo.setVersion(version);
        vo.setServerTime(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        vo.setEnv(resolveEnv());
        return R.ok(vo);
    }

    /**
     * 探针：用 ngram 全文索引检索法条，验证索引在本机实例上真的可用。
     *
     * @param kw 关键词（1~50 字符）
     * @return 统一响应体，data 为 {@code {"hits":[...]}}
     */
    @GetMapping("/probe/law")
    public R<Map<String, List<LawHitVO>>> probeLaw(
            @RequestParam("kw") @Size(min = 1, max = KW_MAX, message = "kw 长度需在 1~" + KW_MAX + " 之间") String kw) {
        return R.ok(Map.of("hits", lawSearchService.search(kw)));
    }

    /**
     * 探针：触发一次豆包流式调用（无 key 时走 mock），用于验「5 秒内出首字节」。
     *
     * <p>副作用：真实模式下会消耗豆包配额；mock 模式下只写本地流。</p>
     *
     * @return SSE 发射器（meta → delta* → done | error）
     */
    @PostMapping("/probe/ai")
    public SseEmitter probeAi() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        boolean mock = doubaoClient.isMock();

        taskExecutor.execute(() -> {
            AtomicInteger total = new AtomicInteger();
            try {
                // 任务 1：首帧 meta，让验收人知道本次是不是 mock。
                // 首帧必须是 meta：验收记录依赖它判断「本次是否 mock」，不能靠外部猜
                emitter.send(SseEmitter.event()
                        .name(EVT_META)
                        .data(Map.of("mock", mock), MediaType.APPLICATION_JSON));

                // 任务 2：逐段转发；客户端断开时转成非受检异常，由外层统一收尾。
                doubaoClient.stream(PROBE_PROMPT, delta -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(EVT_DELTA)
                                .data(Map.of("text", delta), MediaType.APPLICATION_JSON));
                        total.addAndGet(delta.length());
                    } catch (IOException e) {
                        // 客户端断开：转成非受检异常，交给外层统一收尾
                        throw new UncheckedIOException(e);
                    }
                });

                // 任务 3：正常结束发 done；失败走下方 error 分支，二者必居其一。
                emitter.send(SseEmitter.event()
                        .name(EVT_DONE)
                        .data(Map.of("totalChars", total.get()), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                if (isClientAbort(e)) {
                    // 客户端提前断开是**预期路径**（验收脚本读完首屏就会 close），
                    // 按《后端规范》§10.1 分级记 DEBUG，不许刷 ERROR——否则生产日志里
                    // 全是「用户切走 SSE」的噪音，真正的故障会被淹掉。
                    log.debug("AI 探针：客户端提前断开 mock={}", mock);
                    // 只用 complete()：completeWithError 会把异常再抛给 GlobalExceptionHandler，
                    // 那里按「运行时异常」记一条 ERROR——连接都断了，这条日志毫无价值。
                    emitter.complete();
                    return;
                }
                log.error("AI 探针失败 mock={}", mock, e);
                try {
                    emitter.send(SseEmitter.event()
                            .name(EVT_ERROR)
                            .data(Map.of(
                                    "code", ErrorCode.AI_SERVICE_DOWN.getCode(),
                                    "msgKey", ErrorCode.AI_SERVICE_DOWN.getMsgKey()),
                                    MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                    // 连接已断，发不出去也没关系
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 判断异常链是否属于「客户端已断开」。
     *
     * <p>Spring 6.1+ 抛 `AsyncRequestNotUsableException`，Tomcat 抛 `ClientAbortException`。
     * 用类名后缀判断而不是写死 import：容器实现可换，写死类名会在换容器后悄悄失效。</p>
     *
     * @param e 顶层异常
     * @return true 表示客户端连接已断，属预期路径
     */
    private boolean isClientAbort(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.endsWith("AsyncRequestNotUsableException")
                    || name.endsWith("ClientAbortException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 取当前环境标识。
     *
     * <p>没有显式 profile 时回 {@code default}；用 `prod` 关键字判断而不是枚举比较，
     * 是为了让 `prod-xxx` 这类派生 profile 也能被识别。</p>
     *
     * @return dev / prod / default
     */
    private String resolveEnv() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            return "default";
        }
        return String.join(",", profiles);
    }

    /**
     * 取本机主机名。仅用于排查（未在本段输出，保留以防后续扩展需要）。
     *
     * @return 主机名；取不到时返回 unknown
     */
    private String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
