/*
 * 职责：`DoubaoClient` 的真实实现——用 WebClient 调火山方舟 OpenAI 兼容接口，支持流式与非流式。
 * 调用方：Spring 容器（`faze.ai.mock=false` 或缺省时生效）；AiStreamService 订阅它的 `stream()`。
 * 数据来源：
 *   - 模型 / 超时：`sys_config`（AiProperties）；
 *   - Key：环境变量 `FAZE_ARK_API_KEY`（本地 deploy/.env），为空则 `FAZE_DOUBAO_API_KEY` 兼容旧键名；
 *   - 地址：`sys_config.ai_ark_base_url` → `FAZE_ARK_BASE_URL` → 兜底默认值。
 * 注意：
 *   1. **连接超时 5s、读超时 ai_timeout_seconds**：连接超时给 HttpClient 的
 *      `CONNECT_TIMEOUT_MILLIS`，读超时同时给 Netty 的 `responseTimeout`（等响应头）与
 *      Flux 的 `timeout(...)`（相邻 chunk 间隔），两者都是「读」的兜底。
 *   2. **重试**：5xx / 网络错重试 2 次、指数退避（技术路线 §11.5）；4xx 不重试。
 *      并且**只在还没吐出任何 chunk 时重试**（`emitted` 标记）——已经出字后重试会把同一段回答拼两遍。
 *   3. **错误映射**：4xx/5xx/超时/解析失败 → `ApiException(AI_SERVICE_DOWN)`，
 *      不把上游 body 回给客户端，也不写进日志（只记状态码与异常类名）。
 *   4. **不做缓冲聚合**：每帧 `data: {...}` 解析成一个 ChatChunk 直接 emit；空 content 帧跳过。
 *      聚合在 AiStreamService 做。这条是 C1 的验收点，不要为了「看起来整齐」改成 `bodyToMono`。
 *   5. WebClient 实例按「baseUrl + 超时配置」缓存；sys_config 改了地址/超时后最多等
 *      AiProperties 的缓存周期（5s）+ 下一次构建，不会永久用旧地址。
 */
package com.faze.infra.ai.impl;

import com.faze.infra.ai.ApiException;
import com.faze.infra.ai.DoubaoClient;
import com.faze.infra.ai.dto.ChatChunk;
import com.faze.infra.ai.dto.ChatRequest;
import com.faze.infra.ai.dto.ChatResult;
import com.faze.modules.ai.config.AiProperties;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 豆包客户端真实实现（WebClient + 流式 SSE 解析 + 重试 + 错误映射）。
 */
@Component
@ConditionalOnProperty(name = "faze.ai.mock", havingValue = "false", matchIfMissing = true)
@Slf4j
public class DoubaoClientImpl implements DoubaoClient {

    /** 上游聊天补全路径（技术路线 §11.2：OpenAI 兼容 `/api/v3/chat/completions`）。 */
    private static final String CHAT_COMPLETIONS_PATH = "/api/v3/chat/completions";

    /** 连接超时（C1 要求固定 5s）。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** 上游 5xx / 网络错的重试次数（技术路线 §11.5：重试 2 次，指数退避）。 */
    private static final int RETRY_MAX = 2;

    /** 指数退避的首次等待（后续翻倍，最大 4s）。 */
    private static final Duration RETRY_FIRST_BACKOFF = Duration.ofMillis(500);

    /** 指数退避的最大等待。 */
    private static final Duration RETRY_MAX_BACKOFF = Duration.ofSeconds(4);

    private final AiProperties properties;

    private final JsonMapper jsonMapper;

    /** WebClient 缓存（key 由 baseUrl + 读超时拼成，配置变化时重建）。 */
    private volatile WebClient cachedClient;

    /** 缓存 key。 */
    private volatile String cachedClientKey;

    /**
     * 构造客户端。
     *
     * @param properties AI 配置（sys_config + 环境变量）
     * @param jsonMapper Jackson 3 的 JsonMapper（Spring 注入）
     */
    public DoubaoClientImpl(AiProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 流式调用。
     *
     * @param request 请求体
     * @return 增量流（冷流，订阅时才发请求）
     */
    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        String apiKey = requireApiKey();
        ChatRequest streaming = copyWithStream(request, true);
        // 是否已经吐出过 chunk：只影响「出字后是否还允许重试」，见类注释第 2 条
        AtomicBoolean emitted = new AtomicBoolean(false);
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());

        return client()
                .post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(streaming)
                .retrieve()
                // 4xx：重试无用（401/403 是 key 或权限问题），直接失败
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new ApiException(
                                "上游 4xx status=" + response.statusCode().value(), false)))
                // 5xx：可重试
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        Mono.error(new ApiException(
                                "上游 5xx status=" + response.statusCode().value(), true)))
                .bodyToFlux(String.class)
                .map(this::parseChunk)
                .filter(Objects::nonNull)
                .doOnNext(chunk -> emitted.set(true))
                // 读超时：相邻 chunk 间隔超过 ai_timeout_seconds 视为上游卡死
                .timeout(timeout)
                .retryWhen(Retry.backoff(RETRY_MAX, RETRY_FIRST_BACKOFF)
                        .maxBackoff(RETRY_MAX_BACKOFF)
                        .filter(error -> !emitted.get() && isRetryable(error))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorMap(this::mapUpstreamError);
    }

    /**
     * 非流式调用。
     *
     * @param request 请求体
     * @return 完整回答
     */
    @Override
    public ChatResult chat(ChatRequest request) {
        String apiKey = requireApiKey();
        ChatRequest nonStreaming = copyWithStream(request, false);
        Duration timeout = Duration.ofSeconds(properties.timeoutSeconds());

        ChatResult result = client()
                .post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(nonStreaming)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new ApiException(
                                "上游 4xx status=" + response.statusCode().value(), false)))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        Mono.error(new ApiException(
                                "上游 5xx status=" + response.statusCode().value(), true)))
                .bodyToMono(String.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(RETRY_MAX, RETRY_FIRST_BACKOFF)
                        .maxBackoff(RETRY_MAX_BACKOFF)
                        .filter(this::isRetryable)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorMap(this::mapUpstreamError)
                .map(this::parseResult)
                .block(timeout.plusSeconds(5));

        if (result == null) {
            throw new ApiException("上游返回空响应体", true);
        }
        return result;
    }

    /**
     * 取 API Key；缺失时直接失败（**不在真实实现里偷偷转 mock**，否则 mock 状态会被掩盖）。
     *
     * @return 非空 API Key
     * @throws ApiException 未配置 Key
     */
    private String requireApiKey() {
        String apiKey = properties.apiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new ApiException("未配置 FAZE_ARK_API_KEY（faze.ai.mock 未开启）", false);
        }
        return apiKey;
    }

    /**
     * 按当前配置构建/复用 WebClient。
     *
     * @return WebClient 实例
     */
    private WebClient client() {
        String baseUrl = normalizeBaseUrl(properties.arkBaseUrl());
        int timeoutSeconds = properties.timeoutSeconds();
        String cacheKey = baseUrl + "|" + timeoutSeconds;
        WebClient local = cachedClient;
        if (local != null && cacheKey.equals(cachedClientKey)) {
            return local;
        }
        synchronized (this) {
            if (cachedClient != null && cacheKey.equals(cachedClientKey)) {
                return cachedClient;
            }
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .responseTimeout(Duration.ofSeconds(timeoutSeconds));
            WebClient built = WebClient.builder()
                    .baseUrl(baseUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
            cachedClient = built;
            cachedClientKey = cacheKey;
            log.info("豆包客户端已重建 baseUrl={} timeoutSeconds={}", baseUrl, timeoutSeconds);
            return built;
        }
    }

    /**
     * 规整 baseUrl（去尾部斜杠）。
     *
     * @param raw 原始地址
     * @return 规整后的地址
     */
    private String normalizeBaseUrl(String raw) {
        String baseUrl = StringUtils.hasText(raw) ? raw.trim() : "https://ark.cn-beijing.volces.com";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    /**
     * 生成流式/非流式请求副本（不改调用方对象）。
     *
     * @param request 原请求
     * @param stream  是否流式
     * @return 副本
     */
    private ChatRequest copyWithStream(ChatRequest request, boolean stream) {
        ChatRequest copy = new ChatRequest();
        copy.setModel(request.getModel());
        copy.setMessages(request.getMessages());
        copy.setStream(stream);
        return copy;
    }

    /**
     * 解析单个 SSE 帧。
     *
     * <p>Spring 的 StringDecoder 对 `text/event-stream` 会按事件切分并把 `data:` 后面的内容交给我们，
     * 因此这里拿到的是 `{...}` 或 `[DONE]`。</p>
     *
     * @param data 帧内容
     * @return 解析出的增量；`[DONE]`、空帧、无有效字段时返回 null
     */
    private ChatChunk parseChunk(String data) {
        if (data == null) {
            return null;
        }
        String trimmed = data.trim();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
            return null;
        }
        try {
            JsonNode root = jsonMapper.readTree(trimmed);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                return null;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.path("delta");
            String content = delta.hasNonNull("content") ? delta.get("content").asText() : null;
            String finishReason = choice.hasNonNull("finish_reason")
                    ? choice.get("finish_reason").asText() : null;
            Integer promptTokens = null;
            Integer completeTokens = null;
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                promptTokens = readInt(usage, "prompt_tokens");
                completeTokens = readInt(usage, "completion_tokens");
            }
            boolean emptyContent = content == null || content.isEmpty();
            if (emptyContent && finishReason == null && promptTokens == null && completeTokens == null) {
                // 心跳帧 / 只有 role 的帧：跳过（但不算解析失败）
                return null;
            }
            return new ChatChunk(content, finishReason, promptTokens, completeTokens);
        } catch (RuntimeException e) {
            // 单帧解析失败不中断整条流（可能是未知事件），但记 debug 便于排查；不打帧内容（可能含回答原文）
            log.debug("忽略无法解析的上游 SSE 帧，长度={}", trimmed.length());
            return null;
        }
    }

    /**
     * 解析非流式响应。
     *
     * @param body 响应体 JSON
     * @return 回答结果
     */
    private ChatResult parseResult(String body) {
        if (!StringUtils.hasText(body)) {
            throw new ApiException("上游响应体为空", true);
        }
        try {
            JsonNode root = jsonMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new ApiException("上游响应缺少 choices", true);
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            String content = message.hasNonNull("content") ? message.get("content").asText() : null;
            String finishReason = choice.hasNonNull("finish_reason")
                    ? choice.get("finish_reason").asText() : null;
            Integer promptTokens = null;
            Integer completeTokens = null;
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                promptTokens = readInt(usage, "prompt_tokens");
                completeTokens = readInt(usage, "completion_tokens");
            }
            return new ChatResult(content, finishReason, promptTokens, completeTokens);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException("上游响应解析失败：" + e.getClass().getSimpleName(), true);
        }
    }

    /**
     * 读取整数字段。
     *
     * @param node 父节点
     * @param name 字段名
     * @return 值；缺失或非整数返回 null
     */
    private Integer readInt(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isNumber() ? value.asInt() : null;
    }

    /**
     * 判断错误是否值得重试。
     *
     * @param error 异常
     * @return true 表示 5xx / 网络错 / 超时
     */
    private boolean isRetryable(Throwable error) {
        if (error instanceof ApiException apiException) {
            return apiException.isRetryable();
        }
        // 网络错、连接重置、DNS、超时都算可重试；其余（解析错误等）不重试
        return error instanceof java.io.IOException
                || error instanceof java.util.concurrent.TimeoutException
                || error instanceof org.springframework.web.reactive.function.client.WebClientRequestException;
    }

    /**
     * 把上游各类错误统一收敛成 508001 的 ApiException（不透传上游原文）。
     *
     * @param error 原始错误
     * @return 统一异常
     */
    private Throwable mapUpstreamError(Throwable error) {
        if (error instanceof ApiException) {
            return error;
        }
        log.warn("豆包上游调用失败 type={} message={}", error.getClass().getSimpleName(), safeMessage(error));
        return new ApiException("上游调用失败：" + error.getClass().getSimpleName(), isRetryable(error));
    }

    /**
     * 安全取异常消息：只保留类名与状态码级别的信息，**不记录上游响应体**。
     *
     * @param error 异常
     * @return 可安全写日志的简短描述
     */
    private String safeMessage(Throwable error) {
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseException) {
            return "status=" + responseException.getStatusCode().value();
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "timeout";
        }
        return error.getClass().getSimpleName();
    }
}
