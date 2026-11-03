/*
 * 职责：豆包（火山方舟）大模型的流式调用客户端。段 0 只服务 `POST /api/v1/pub/probe/ai` 这一个探针。
 * 调用方：infra/probe/ProbeController。
 * 数据来源：`faze.probe.ai.*` 配置（key 为空时自动降级为 mock）。
 * 注意：
 *   1. **key 为空或 model 为空时必须走 mock 且必须让调用方知道**：`isMock()` 会把状态交给
 *      Controller 写成 SSE 的 meta 帧，验收记录里必须注明「本次为 mock」。绝不能静默假装成功——
 *      那会让 0-A5 的「首字节」判据变成假绿。
 *   2. `model` 是**推理接入点 ID**（形如 `ep-xxxxxxxx`），不是模型名字。传模型名会 404。
 *      这是豆包方舟最常见的接入错误，所以配置项注释里写死。
 *   3. 用 JDK 自带 `HttpClient`，**不引第三方 HTTP 客户端**（段 0 不为一个探针加依赖）。
 *   4. 段 0 不做重试、不做熔断：探针的用途是「通或不通」，自动重试会掩盖真实故障。
 *      生产级的重试/降级在段 6 的 ai 域做。
 *   5. 请求体与响应体都要按 UTF-8 处理；Windows 默认编码是 GBK，漏掉就会把中文变成乱码。
 */
package com.faze.infra.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 豆包流式客户端（段 0：mock / 真实两条路）。
 */
@Component
@Slf4j
public class DoubaoClient {

    /** mock 模式下逐段回调的文本。内容刻意简短，只为验「能出字节」。 */
    private static final String MOCK_TEXT = "【mock】这是段 0 的本地探针输出，未调用真实豆包接口。";
    /** mock 分段大小（字符）。 */
    private static final int MOCK_CHUNK = 2;
    /** mock 每段间隔（毫秒），用于模拟流式节奏。 */
    private static final long MOCK_DELAY_MS = 30L;
    /** 连接超时（秒）。 */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final boolean mockEnabled;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration requestTimeout;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    /**
     * 构造客户端。
     *
     * @param mockEnabled    是否强制 mock（`faze.probe.ai.mock`）
     * @param apiKey         豆包 API key（空则自动 mock）
     * @param baseUrl        接入点 URL（OpenAI 兼容的 chat/completions）
     * @param model          推理接入点 ID（`ep-` 开头），不是模型名
     * @param timeoutSeconds 单次请求超时（秒）
     * @param jsonMapper     Jackson 3 Mapper
     */
    public DoubaoClient(@Value("${faze.probe.ai.mock:true}") boolean mockEnabled,
                        @Value("${faze.probe.ai.api-key:}") String apiKey,
                        @Value("${faze.probe.ai.base-url:https://ark.cn-beijing.volces.com/api/v3/chat/completions}") String baseUrl,
                        @Value("${faze.probe.ai.model:}") String model,
                        @Value("${faze.probe.ai.timeout-seconds:120}") int timeoutSeconds,
                        JsonMapper jsonMapper) {
        this.mockEnabled = mockEnabled;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 是否处于 mock 模式。
     *
     * <p>显式开 mock、或缺 key、或缺接入点 ID，三者任一成立即 mock。</p>
     *
     * @return true 表示本次不会真的调用豆包
     */
    public boolean isMock() {
        return mockEnabled || isBlank(apiKey) || isBlank(model);
    }

    /**
     * 流式产出文本片段。
     *
     * <p>本方法是**同步阻塞**的：调用方必须放在异步线程里（Controller 用 TaskExecutor 派发）。</p>
     *
     * @param prompt  提示词
     * @param onDelta 每收到一段文本回调一次（非 null，回调可能被调用 0 次）
     * @throws IOException          网络或响应格式错误
     * @throws InterruptedException 线程被中断（应用关闭时）
     */
    public void stream(String prompt, Consumer<String> onDelta) throws IOException, InterruptedException {
        if (isMock()) {
            mockStream(onDelta);
            return;
        }
        realStream(prompt, onDelta);
    }

    /**
     * mock 流：按固定节奏逐段回调。
     *
     * @param onDelta 文本回调
     * @throws InterruptedException 线程被中断
     */
    private void mockStream(Consumer<String> onDelta) throws InterruptedException {
        for (int i = 0; i < MOCK_TEXT.length(); i += MOCK_CHUNK) {
            String chunk = MOCK_TEXT.substring(i, Math.min(MOCK_TEXT.length(), i + MOCK_CHUNK));
            onDelta.accept(chunk);
            Thread.sleep(MOCK_DELAY_MS);
        }
    }

    /**
     * 真实流：调豆包 chat/completions（stream=true），逐行解析 SSE。
     *
     * @param prompt  提示词
     * @param onDelta 文本回调
     * @throws IOException          网络错误或非 200 响应
     * @throws InterruptedException 线程被中断
     */
    private void realStream(String prompt, Consumer<String> onDelta) throws IOException, InterruptedException {
        String requestBody = jsonMapper.writeValueAsString(Map.of(
                "model", model,
                "stream", true,
                "messages", List.of(Map.of("role", "user", "content", prompt))));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            // 只记状态码，不记响应体全文（可能含配额与接入点信息）
            throw new IOException("豆包接口返回非 200：" + response.statusCode());
        }

        try (InputStream body = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String delta = parseDelta(line);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        }
    }

    /**
     * 解析一行 SSE，取出增量文本。
     *
     * @param line 原始行（形如 `data: {...}`）
     * @return 增量文本；非数据行或 `[DONE]` 返回 null
     */
    private String parseDelta(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null;
        }
        String data = line.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return null;
        }
        try {
            JsonNode root = jsonMapper.readTree(data);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                return null;
            }
            JsonNode delta = choices.get(0).get("delta");
            if (delta == null) {
                return null;
            }
            JsonNode content = delta.get("content");
            return content != null && content.isTextual() ? content.asText() : null;
        } catch (RuntimeException e) {
            // 单行解析失败不该中断整条流：记 debug 跳过（可能是心跳帧或未知事件）
            log.debug("忽略无法解析的 SSE 行: {}", abbreviate(line));
            return null;
        }
    }

    /**
     * 判断字符串是否为空或空白。
     *
     * @param value 待判断值
     * @return 为空/纯空白时 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 日志截断，避免把整行上游响应写进日志。
     *
     * @param line 原始行
     * @return 前 80 字符
     */
    private String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "...";
    }
}
