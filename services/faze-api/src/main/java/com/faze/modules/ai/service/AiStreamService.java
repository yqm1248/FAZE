/*
 * 职责：C3/C4 的 SSE 流式服务——订阅上游豆包、三写 delta（Redis + 内存 + 定时刷库）、
 *       断线续传、结束落全量、首字节超时/单次上限/主动中断/上游失败四个异常分支。
 * 调用方：modules/ai/controller/AiStreamController（`GET /api/v1/app/ai/stream`）。
 * 数据来源：Redis `faze:ai:ask:<conversationId>`（C2 暂存的 Prompt/hits）→ 提交给
 *           `infra/ai/DoubaoClient#stream`；落库写 ai_message；校验用 D3 OutputGuard / D4 RefSnapshotBuilder；
 *           内容编号用 E2 ContentIdGenerator。
 *
 * ── 与《技术路线》的偏离（必须与 §九 登记一致）───────────────────────────────
 *   技术路线 §8.3 L2557 原文是「每个 delta 事件到达即落库」。本实现**不照做**：
 *   改为「Redis 暂存 delta + 每 20 个 delta 或每 2 秒刷一次库 + 结束时写全量」。
 *   理由：每 token 一次 UPDATE 是性能灾难（200 token = 200 次写库）；每 20 个最多丢 20 个 token，
 *   用户感知不到。**这是 lead 已批准并在 §九 登记的偏离，不是实现偷懒。**
 *
 * ── 其他关键决策（都写在这里，避免后来者改错）───────────────────────────────
 *   1. **delta 三写**：① `LPUSH faze:ai:stream:<assistantMessageId> "<seq>|<content>"` + `EXPIRE 600`
 *      ② 内存 StringBuilder ③ 每 20 个或每 2 秒刷库。
 *      刷库用「全量覆盖」而不是 `CONCAT(content, ?)`：SQL 语义等价（结束时同样全量覆盖，幂等），
 *      且避免把模型输出手工拼进 SQL（那是注入面）。行为与工作流 C3 第 4 条一致。
 *   2. **断线不立即取消上游**（这是 C3 续传与 C4 取消之间的取舍）：
 *      RN 的 `abort()`（用户点停止）与网络断线在服务端**无法区分**——两者都是 emitter 断开。
 *      若一断就 dispose，`3-A8` 的「断网 10 秒重连从断点接上」物理上做不到（上游已经停了）。
 *      所以本实现：客户端断开后**继续消费上游并写 Redis/刷库**，同时挂一个
 *      {@link #DETACH_CANCEL_SECONDS} 秒的回收任务；期间重连则接管（续传），超时仍无人连接才
 *      `Disposable.dispose()` 取消上游并保留已生成内容（日志 `UPSTREAM_CANCELED`）。
 *      → 用户点「停止」时，最多再生成 30 秒就会被取消；这不是「不取消」，是「延迟取消」。
 *   3. **重连复用同一 messageId**：一次请求只会有一条 assistant 消息；重连不新建消息（C3 第 8 条）。
 *   4. **fromSeq 语义**：客户端传「已收到的最大 seq」；服务端重放 **seq > fromSeq** 的部分，
 *      保证不重复不丢失。`fromSeq=0` 表示全量重放（C2 返回的 streamUrl 就带 0）。
 *      工作流 C3 第 6 条写的是「重放 ≥ fromSeq」，本实现取严格大于——因为按「≥」会在重连时把
 *      客户端已有的一条 delta 再发一遍，与同一段的「不重复」要求冲突。
 *   5. **错误事件不静默**：首字节超时 / 上游失败 / 回答已过期都发 `event: error {code,msg}`；
 *      单次上限发 `event: done {finishReason:"timeout"}` 并追加截断提示。
 *   6. **SseEmitter 已断开再 send 会抛 IllegalStateException**：捕获并忽略（C4 卡住项第 2 条），
 *      绝不让它变成 500。
 *   7. **日志禁止出现回答原文**（L4）：只记 messageId / conversationId / 字符数 / token / 耗时 / 标记。
 */
package com.faze.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.ai.DoubaoClient;
import com.faze.infra.ai.dto.ChatChunk;
import com.faze.infra.ai.dto.ChatMessage;
import com.faze.infra.ai.dto.ChatRequest;
import com.faze.modules.ai.config.AiProperties;
import com.faze.modules.ai.config.AiRedisKeys;
import com.faze.modules.ai.config.AiStreamScheduler;
import com.faze.modules.ai.dto.GuardOutcome;
import com.faze.modules.ai.dto.LawHit;
import com.faze.modules.ai.dto.LawRefSnapshot;
import com.faze.modules.ai.dto.PendingAsk;
import com.faze.modules.ai.entity.AiConversation;
import com.faze.modules.ai.entity.AiMessage;
import com.faze.modules.ai.mapper.AiConversationMapper;
import com.faze.modules.ai.mapper.AiMessageMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * SSE 流式服务（C3 + C4）。
 */
@Service
@Slf4j
public class AiStreamService {

    /** 每多少个 delta 刷一次库（工作流 C3 第 4 条）。 */
    private static final int FLUSH_EVERY_DELTAS = 20;

    /** 刷库的最长间隔（毫秒，工作流 C3 第 4 条）。 */
    private static final long FLUSH_MAX_INTERVAL_MILLIS = 2_000L;

    /** 断线后等待重连接管的时间（秒）：超时才取消上游（见类注释第 2 条）。 */
    private static final int DETACH_CANCEL_SECONDS = 30;

    /** 首字节超时后写入 ai_message 的固定文案（C4 第 2 条允许「服务暂时不可用」）。 */
    public static final String SERVICE_UNAVAILABLE_CONTENT = "服务暂时不可用";

    /** 单次流上限触发时追加的截断提示（C4 第 3 条）。 */
    public static final String TRUNCATED_NOTICE = "\n\n（回答较长已截断）";

    /** 错误事件：回答已过期（Redis 无 delta 或没有待答载荷）。 */
    private static final String MSG_EXPIRED = "回答已过期，请重新提问";

    /** 错误事件：AI 服务不可用。 */
    private static final String MSG_SERVICE_DOWN = "AI 服务暂时不可用，请稍后重试";

    /** 错误事件：生成中断。 */
    private static final String MSG_INTERRUPTED = "生成中断，请重试";

    /** 错误事件：无权限。 */
    private static final String MSG_FORBIDDEN = "无权限访问该会话";

    /** 原子「取走并删除」待答载荷的 Lua（Redis 5 不支持 GETDEL，又不能让 GET+DEL 分离）。 */
    private static final String TAKE_ASK_LUA = """
            local value = redis.call('GET', KEYS[1])
            if value then
              redis.call('DEL', KEYS[1])
            end
            return value
            """;

    /** 取走待答载荷脚本。 */
    private static final DefaultRedisScript<String> TAKE_ASK_SCRIPT =
            new DefaultRedisScript<>(TAKE_ASK_LUA, String.class);

    private final AiConversationMapper aiConversationMapper;
    private final AiMessageMapper aiMessageMapper;
    private final AiProperties aiProperties;
    private final DoubaoClient doubaoClient;
    private final OutputGuard outputGuard;
    private final RefSnapshotBuilder refSnapshotBuilder;
    private final ContentIdGenerator contentIdGenerator;
    private final AiQuotaService aiQuotaService;
    private final StringRedisTemplate redisTemplate;
    private final tools.jackson.databind.json.JsonMapper jsonMapper;
    private final AiStreamScheduler aiStreamScheduler;

    /** 本进程内正在进行的流（assistantMessageId → 状态）。重连接管靠它。 */
    private final Map<Long, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param aiConversationMapper 会话 Mapper（归属校验）
     * @param aiMessageMapper      消息 Mapper（落库）
     * @param aiProperties         配置（超时/首字节/模型）
     * @param doubaoClient         上游客户端（真实实现或 Stub）
     * @param outputGuard          输出侧校验（D3）
     * @param refSnapshotBuilder   refs 快照（D4）
     * @param contentIdGenerator   内容编号（E2）
     * @param aiQuotaService       配额（C5）
     * @param redisTemplate        Redis（逻辑库 1）
     * @param jsonMapper           Jackson 3
     * @param aiStreamScheduler    AI 流式延迟任务调度器
     */
    public AiStreamService(AiConversationMapper aiConversationMapper,
                           AiMessageMapper aiMessageMapper,
                           AiProperties aiProperties,
                           DoubaoClient doubaoClient,
                           OutputGuard outputGuard,
                           RefSnapshotBuilder refSnapshotBuilder,
                           ContentIdGenerator contentIdGenerator,
                           AiQuotaService aiQuotaService,
                           StringRedisTemplate redisTemplate,
                           tools.jackson.databind.json.JsonMapper jsonMapper,
                           @Qualifier("aiStreamScheduler") AiStreamScheduler aiStreamScheduler) {
        this.aiConversationMapper = aiConversationMapper;
        this.aiMessageMapper = aiMessageMapper;
        this.aiProperties = aiProperties;
        this.doubaoClient = doubaoClient;
        this.outputGuard = outputGuard;
        this.refSnapshotBuilder = refSnapshotBuilder;
        this.contentIdGenerator = contentIdGenerator;
        this.aiQuotaService = aiQuotaService;
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.aiStreamScheduler = aiStreamScheduler;
    }

    /**
     * 打开 SSE 连接（新流 / 续传 / 已过期三种分支）。
     *
     * <p>副作用：可能创建 assistant 消息、写 Redis、发起上游请求。**所有失败都通过
     * `event: error` 返回**，不抛异常——响应已经是 text/event-stream，抛出去客户端解析不了。</p>
     *
     * @param conversationId 会话 id
     * @param fromSeq        客户端已收到的最大 seq（0 表示从头）
     * @param userId         当前用户 id
     * @return SSE emitter（已注册超时/完成/错误回调，后台线程会持续发事件）
     */
    public SseEmitter open(Long conversationId, int fromSeq, Long userId) {
        int timeoutSeconds = aiProperties.timeoutSeconds();
        // 容器 timeout 比业务上限晚 5 秒：单次上限由我们自己的调度器触发（见 runNewStream），
        // 容器 timeout 只做兜底——**不用容器 timeout 做上限**，因为容器超时后再 send 会被拒绝，
        // done 事件发不出去（2026-11-07 实测：3 秒上限时 DB 写了截断提示但客户端没收到 done）。
        SseEmitter emitter = new SseEmitter((Math.max(1, timeoutSeconds) + 5) * 1_000L);
        try {
            AiConversation conversation = aiConversationMapper.selectOne(
                    Wrappers.<AiConversation>lambdaQuery()
                            .eq(AiConversation::getId, conversationId)
                            .eq(AiConversation::getUserId, userId)
                            .isNull(AiConversation::getDeletedAt));
            if (conversation == null) {
                sendErrorAndComplete(emitter, ErrorCode.COMMON_FORBIDDEN, MSG_FORBIDDEN);
                return emitter;
            }

            // 分支 1：已有活跃流（或残留 active key）→ 续传
            String activeMessageId = redisTemplate.opsForValue().get(AiRedisKeys.active(conversationId));
            if (activeMessageId != null && !activeMessageId.isBlank()) {
                handleResume(emitter, conversationId, activeMessageId.trim(), fromSeq);
                return emitter;
            }

            // 分支 2：新流——取走 C2 暂存的待答载荷
            PendingAsk pending = takePendingAsk(conversationId);
            if (pending == null) {
                sendErrorAndComplete(emitter, ErrorCode.AI_SERVICE_DOWN, MSG_EXPIRED);
                return emitter;
            }
            if (pending.getUserId() != null && !pending.getUserId().equals(userId)) {
                sendErrorAndComplete(emitter, ErrorCode.COMMON_FORBIDDEN, MSG_FORBIDDEN);
                return emitter;
            }

            // 创建本次唯一的 assistant 消息（content 先留空，流式过程中刷新）
            AiMessage assistantMessage = insertAssistantMessage(conversationId, userId, pending.getModel());
            if (assistantMessage == null || assistantMessage.getId() == null) {
                sendErrorAndComplete(emitter, ErrorCode.AI_SERVICE_DOWN, MSG_SERVICE_DOWN);
                return emitter;
            }
            redisTemplate.opsForValue().set(AiRedisKeys.active(conversationId),
                    String.valueOf(assistantMessage.getId()), Duration.ofSeconds(AiRedisKeys.TTL_SECONDS));

            ActiveStream active = new ActiveStream(assistantMessage.getId(), conversationId, userId,
                    pending.getHits(), pending.getModel(), pending.getQuotaDay(), timeoutSeconds);
            activeStreams.put(assistantMessage.getId(), active);
            aiStreamScheduler.execute(() -> runNewStream(emitter, active, pending));
            return emitter;
        } catch (RuntimeException e) {
            log.error("AI 流打开失败 conversationId={} error={}", conversationId, e.getClass().getSimpleName());
            sendErrorAndComplete(emitter, ErrorCode.AI_SERVICE_DOWN, MSG_SERVICE_DOWN);
            return emitter;
        }
    }

    // ── 新流 ────────────────────────────────────────────────────────────────

    /**
     * 新流：发 start → 订阅上游 → 逐帧转发。
     *
     * @param emitter SSE 连接
     * @param active  流状态
     * @param pending 待答载荷
     */
    private void runNewStream(SseEmitter emitter, ActiveStream active, PendingAsk pending) {
        synchronized (active.lock) {
            active.emitter = emitter;
            registerEmitterCallbacks(active, emitter);
            // start 事件的 data 字段以《接口规范》§6.7 为准：`conversationId` + `messageId` + `model`。
            // **不再发旧字段 `sessionId`**（lead 2026-11-07 实测指出）：表名/路径已按 §零.3 第 7 项
            // 由 `ai_session` 改成 `ai_conversation`，段 3 的协议里不许再出现 session 概念。
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("conversationId", active.conversationId);
            start.put("messageId", active.messageId);
            start.put("model", active.model);
            sendEvent(emitter, "start", start);

            // 首字节超时定时器（C4 第 2 条）
            active.firstByteTask = aiStreamScheduler.schedule(
                    () -> onFirstByteTimeout(active),
                    Math.max(1, aiProperties.firstByteTimeoutMs()),
                    TimeUnit.MILLISECONDS);
            // 单次流上限（C4 第 3 条）：由本调度器主动触发，保证 done 事件能发出去；
            // 容器的 emitter timeout 只作兜底（见 open 里的 +5 秒）
            active.streamTimeoutTask = aiStreamScheduler.schedule(
                    () -> onStreamTimeout(active),
                    Math.max(1, active.timeoutSeconds),
                    TimeUnit.SECONDS);
        }

        ChatRequest request = new ChatRequest();
        request.setModel(active.model);
        request.setMessages(toChatMessages(pending.getMessages()));
        request.setStream(true);

        try {
            Disposable disposable = doubaoClient.stream(request).subscribe(
                    chunk -> onChunk(active, chunk),
                    error -> onUpstreamError(active, error),
                    () -> onComplete(active));
            active.upstream = disposable;
            if (active.finished) {
                // 上游可能在 subscribe 返回前就已失败/结束（Stub 的同步分支），补一次清理
                disposable.dispose();
            }
        } catch (RuntimeException e) {
            // 上游请求在「构建/订阅」阶段就失败（如未配置 Key、地址非法）：按上游失败分支处理
            onUpstreamError(active, e);
        }
    }

    /**
     * 收到一个上游增量：三写 + 按需刷库 + 转发 SSE。
     *
     * @param active 流状态
     * @param chunk  增量
     */
    private void onChunk(ActiveStream active, ChatChunk chunk) {
        if (chunk == null) {
            return;
        }
        synchronized (active.lock) {
            if (active.finished) {
                return;
            }
            if (chunk.getPromptTokens() != null) {
                active.promptTokens = chunk.getPromptTokens();
            }
            if (chunk.getCompleteTokens() != null) {
                active.completeTokens = chunk.getCompleteTokens();
            }
            if (chunk.getFinishReason() != null) {
                active.finishReason = chunk.getFinishReason();
            }
            String content = chunk.getContent();
            if (content == null || content.isEmpty()) {
                return;
            }
            // 首字节到了：取消首字节超时任务
            if (!active.firstChunkSeen) {
                active.firstChunkSeen = true;
                cancelFuture(active.firstByteTask);
                active.firstByteTask = null;
            }
            int seq = active.seq.incrementAndGet();
            active.content.append(content);
            // ① Redis 暂存（续传用；Redis 故障不能阻断回答，只告警）
            pushDeltaToRedis(active.messageId, seq, content);
            // ② 内存已累积（active.content）
            // ③ 每隔 20 个 delta 或 2 秒刷库
            active.deltasSinceFlush++;
            long now = System.currentTimeMillis();
            if (active.deltasSinceFlush >= FLUSH_EVERY_DELTAS
                    || now - active.lastFlushAtMillis >= FLUSH_MAX_INTERVAL_MILLIS) {
                flushIncremental(active);
            }
            // 转发给客户端（客户端已断开时 send 抛 IllegalStateException，捕获忽略）
            boolean sent = sendEvent(active.emitter, "delta", Map.of("content", content));
            if (!sent) {
                markClientGone(active);
            }
        }
    }

    /**
     * 上游正常结束：D3 校验 → D4 refs → 落全量 → 发 refs/done → 清理。
     *
     * @param active 流状态
     */
    private void onComplete(ActiveStream active) {
        synchronized (active.lock) {
            if (active.finished) {
                return;
            }
            active.finished = true;
            cancelFuture(active.firstByteTask);
            cancelFuture(active.streamTimeoutTask);
            cancelFuture(active.detachCleanupTask);
            active.firstByteTask = null;
            active.streamTimeoutTask = null;
            active.detachCleanupTask = null;
            try {
                String rawAnswer = active.content.toString();
                String finishReason = active.finishReason == null ? "stop" : active.finishReason;
                GuardOutcome outcome = outputGuard.inspect(String.valueOf(active.messageId), rawAnswer);
                if (outcome != null && outcome.isOutputInvalid()) {
                    // OUTPUT_INVALID（508002）：落固定文案 + refs=[]，再回错误（lead 2026-11-07 裁决）
                    String fixed = outcome.getAnswer();
                    finishAssistantMessage(active, fixed, "[]", false, null);
                    cleanupRedis(active);
                    sendEvent(active.emitter, "error", errorPayload(ErrorCode.AI_OUTPUT_INVALID, MSG_SERVICE_DOWN));
                    completeQuietly(active.emitter);
                    log.warn("AI 输出校验无效 messageId={} conversationId={} missing={}",
                            active.messageId, active.conversationId,
                            outcome.getMissingArticleCount());
                    return;
                }

                String finalAnswer = outcome == null ? rawAnswer : outcome.getAnswer();
                List<LawRefSnapshot> snapshots = refSnapshotBuilder.build(finalAnswer, active.hits);
                String refsJson = jsonMapper.writeValueAsString(snapshots);
                String contentId = contentIdGenerator.generate();
                boolean degraded = outcome != null && outcome.isDegraded();
                finishAssistantMessage(active, finalAnswer, refsJson, degraded, contentId);
                cleanupRedis(active);

                // refs 事件（D4 快照）
                Map<String, Object> refsPayload = new LinkedHashMap<>();
                refsPayload.put("refs", snapshots);
                sendEvent(active.emitter, "refs", refsPayload);

                // done 事件：字段沿用技术路线 §8.2.1；degraded / finalContent 是本段新增（不改名、只加字段）
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("messageId", active.messageId);
                done.put("finishReason", finishReason);
                done.put("promptTokens", active.promptTokens);
                done.put("completeTokens", active.completeTokens);
                done.put("degraded", degraded);
                if (!finalAnswer.equals(rawAnswer)) {
                    // D3 删过条号/追加了提示：让客户端用最终文本覆盖气泡，避免显示「未校验版」
                    done.put("finalContent", finalAnswer);
                }
                sendEvent(active.emitter, "done", done);
                completeQuietly(active.emitter);
                log.info("AI 流完成 messageId={} conversationId={} chars={} seq={} finishReason={} degraded={}",
                        active.messageId, active.conversationId, finalAnswer.length(), active.seq.get(),
                        finishReason, degraded);
            } catch (RuntimeException e) {
                // 收尾失败（DB/校验异常）不能把连接悬着：保留半截 + error + 清理
                log.error("AI 流收尾失败 messageId={} error={}",
                        active.messageId, e.getClass().getSimpleName());
                try {
                    writeUnavailableMessage(active, active.content.toString());
                } catch (RuntimeException ignored) {
                    // 落库也失败时不再重试
                }
                cleanupRedis(active);
                sendEvent(active.emitter, "error", errorPayload(ErrorCode.AI_SERVICE_DOWN, MSG_INTERRUPTED));
                completeQuietly(active.emitter);
            } finally {
                activeStreams.remove(active.messageId);
            }
        }
    }

    // ── 异常分支（C4）────────────────────────────────────────────────────────

    /**
     * 首字节超时：发 508001、回退配额、写「服务暂时不可用」。
     *
     * @param active 流状态
     */
    private void onFirstByteTimeout(ActiveStream active) {
        synchronized (active.lock) {
            if (active.finished || active.firstChunkSeen) {
                return;
            }
            active.finished = true;
            cancelFuture(active.streamTimeoutTask);
            cancelFuture(active.detachCleanupTask);
            active.streamTimeoutTask = null;
            active.detachCleanupTask = null;
            try {
                disposeUpstream(active);
                // 回退配额：本次没有产生任何回答（C5 第 3 条）
                if (active.quotaDay != null) {
                    aiQuotaService.refund(active.userId, active.quotaDay);
                }
                writeUnavailableMessage(active, SERVICE_UNAVAILABLE_CONTENT);
                cleanupRedis(active);
                sendEvent(active.emitter, "error", errorPayload(ErrorCode.AI_SERVICE_DOWN, MSG_SERVICE_DOWN));
                completeQuietly(active.emitter);
                log.warn("UPSTREAM_FIRST_BYTE_TIMEOUT messageId={} conversationId={}",
                        active.messageId, active.conversationId);
            } catch (RuntimeException e) {
                log.error("AI 首字节超时收尾失败 messageId={} error={}",
                        active.messageId, e.getClass().getSimpleName());
                completeQuietly(active.emitter);
            } finally {
                activeStreams.remove(active.messageId);
            }
        }
    }

    /**
     * 上游中途失败：保留半截 + `event: error`（不重试，重试会拼接混乱）；未出字则回退配额。
     *
     * @param active 流状态
     * @param error  上游异常
     */
    private void onUpstreamError(ActiveStream active, Throwable error) {
        synchronized (active.lock) {
            if (active.finished) {
                return;
            }
            active.finished = true;
            cancelFuture(active.firstByteTask);
            cancelFuture(active.streamTimeoutTask);
            cancelFuture(active.detachCleanupTask);
            active.firstByteTask = null;
            active.streamTimeoutTask = null;
            active.detachCleanupTask = null;
            try {
                disposeUpstream(active);
                boolean hasContent = active.content.length() > 0;
                if (!hasContent) {
                    if (active.quotaDay != null) {
                        aiQuotaService.refund(active.userId, active.quotaDay);
                    }
                    writeUnavailableMessage(active, SERVICE_UNAVAILABLE_CONTENT);
                } else {
                    // 已经出字：保留半截，照扣配额（C5 第 3 条）。
                    // content_id 照生成（是 AI 内容）；refs 写 NULL（半截回答没走 D4 校验，不是"查过没引用"）
                    finishAssistantMessage(active, active.content.toString(), null, false,
                            contentIdGenerator.generate());
                }
                cleanupRedis(active);
                sendEvent(active.emitter, "error", errorPayload(ErrorCode.AI_SERVICE_DOWN, MSG_INTERRUPTED));
                completeQuietly(active.emitter);
                log.warn("UPSTREAM_FAILED messageId={} conversationId={} chars={} error={}",
                        active.messageId, active.conversationId, active.content.length(),
                        error == null ? "unknown" : error.getClass().getSimpleName());
            } catch (RuntimeException e) {
                log.error("AI 上游失败收尾失败 messageId={} error={}",
                        active.messageId, e.getClass().getSimpleName());
                completeQuietly(active.emitter);
            } finally {
                activeStreams.remove(active.messageId);
            }
        }
    }

    /**
     * 单次流上限（emitter timeout）：发 done + timeout + 截断提示，**不静默断开**。
     *
     * @param active 流状态
     */
    private void onStreamTimeout(ActiveStream active) {
        synchronized (active.lock) {
            if (active.finished) {
                return;
            }
            active.finished = true;
            cancelFuture(active.firstByteTask);
            cancelFuture(active.detachCleanupTask);
            active.firstByteTask = null;
            active.detachCleanupTask = null;
            try {
                disposeUpstream(active);
                String partial = active.content.toString();
                // 追加截断提示（也落库，让历史里能解释为什么回答是半截的）
                String truncated = partial + TRUNCATED_NOTICE;
                sendEvent(active.emitter, "delta", Map.of("content", TRUNCATED_NOTICE));
                // 截断仍属 AI 生成内容 → 生成 content_id（E2 要求每条 assistant 消息有它）；
                // 但没走完 D3/D4，refs 写 **NULL（没查过）** 而不是 `[]`（`[]` = 查过没引用，两者语义不同）
                finishAssistantMessage(active, truncated, null, false, contentIdGenerator.generate());
                cleanupRedis(active);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("messageId", active.messageId);
                done.put("finishReason", "timeout");
                done.put("promptTokens", active.promptTokens);
                done.put("completeTokens", active.completeTokens);
                done.put("degraded", false);
                sendEvent(active.emitter, "done", done);
                completeQuietly(active.emitter);
                log.warn("AI_STREAM_TIMEOUT messageId={} conversationId={} chars={}",
                        active.messageId, active.conversationId, truncated.length());
            } catch (RuntimeException e) {
                log.error("AI 流超时收尾失败 messageId={} error={}",
                        active.messageId, e.getClass().getSimpleName());
                completeQuietly(active.emitter);
            } finally {
                activeStreams.remove(active.messageId);
            }
        }
    }

    /**
     * 客户端断开（onCompletion / onError）：标记 + 延迟回收。
     *
     * <p>不立即取消上游：见类注释第 2 条。30 秒内重连接管则取消回收任务。</p>
     *
     * @param active 流状态
     */
    private void markClientGone(ActiveStream active) {
        if (active.finished || active.clientGone) {
            return;
        }
        active.clientGone = true;
        active.detachCleanupTask = aiStreamScheduler.schedule(() -> cancelAbandoned(active),
                DETACH_CANCEL_SECONDS, TimeUnit.SECONDS);
        log.info("AI 流客户端断开，等待重连接管 messageId={} window={}s",
                active.messageId, DETACH_CANCEL_SECONDS);
    }

    /**
     * 断线超过窗口仍无人接管：取消上游、保留已生成内容。
     *
     * @param active 流状态
     */
    private void cancelAbandoned(ActiveStream active) {
        synchronized (active.lock) {
            if (active.finished || !active.clientGone) {
                return;
            }
            active.finished = true;
            cancelFuture(active.firstByteTask);
            cancelFuture(active.streamTimeoutTask);
            active.firstByteTask = null;
            active.streamTimeoutTask = null;
            try {
                disposeUpstream(active);
                // 保留已生成内容（用户可能还想看半截）：落库当前累积文本。
                // content_id 照生成（是 AI 内容）；refs 写 NULL（没走 D4 校验，不是"查过没引用"）
                if (active.content.length() > 0) {
                    finishAssistantMessage(active, active.content.toString(), null, false,
                            contentIdGenerator.generate());
                }
                cleanupRedis(active);
                log.warn("UPSTREAM_CANCELED messageId={} conversationId={} chars={} reason=client_gone_timeout",
                        active.messageId, active.conversationId, active.content.length());
            } catch (RuntimeException e) {
                log.error("AI 断线回收失败 messageId={} error={}",
                        active.messageId, e.getClass().getSimpleName());
            } finally {
                activeStreams.remove(active.messageId);
            }
        }
    }

    // ── 续传 ────────────────────────────────────────────────────────────────

    /**
     * 续传：重放 Redis 里的 delta，并把新连接挂到正在运行的流上。
     *
     * <p><b>`3-A8` 口径（必须显式守住）</b>：重连接管**不调 `AiQuotaService.reserve`**、
     * 也**不新建 assistant 消息**——同一条 `ai_message` 从开始到结束只扣 1 次配额，
     * 重连 3 次配额仍是 1。这里只做「重放已生成 delta + 接管后续推送」两件事
     * （验收证据见 `docs/acceptance/seg3/C3-stream.md` 的「重连 3 次配额仍为 1」一节）。</p>
     *
     * @param emitter        SSE 连接
     * @param conversationId 会话 id
     * @param messageIdText  active key 里的 assistantMessageId
     * @param fromSeq        客户端已收到的最大 seq
     */
    private void handleResume(SseEmitter emitter, Long conversationId, String messageIdText, int fromSeq) {
        Long messageId;
        try {
            messageId = Long.valueOf(messageIdText);
        } catch (NumberFormatException e) {
            sendErrorAndComplete(emitter, ErrorCode.AI_SERVICE_DOWN, MSG_EXPIRED);
            return;
        }
        ActiveStream active = activeStreams.get(messageId);
        if (active != null && !active.finished) {
            // 分支 1：原流仍在跑 → 重放 + 接管
            synchronized (active.lock) {
                active.emitter = emitter;
                active.clientGone = false;
                registerEmitterCallbacks(active, emitter);
                cancelFuture(active.detachCleanupTask);
                active.detachCleanupTask = null;
                int replayed = replayFromRedis(active.messageId, fromSeq);
                log.info("AI 流续传接管 messageId={} conversationId={} fromSeq={} replayed={}",
                        active.messageId, conversationId, fromSeq, replayed);
            }
            return;
        }
        // 分支 2：本进程里没有该流（已结束 / 进程重启）→ 从 Redis + DB 恢复
        List<String> entries = redisTemplate.opsForList().range(AiRedisKeys.stream(messageId), 0, -1);
        if (entries == null || entries.isEmpty()) {
            sendErrorAndComplete(emitter, ErrorCode.AI_SERVICE_DOWN, MSG_EXPIRED);
            return;
        }
        AiMessage message = aiMessageMapper.selectById(messageId);
        if (message == null) {
            sendErrorAndComplete(emitter, ErrorCode.AI_SERVICE_DOWN, MSG_EXPIRED);
            return;
        }
        int replayed = replayEntries(emitter, entries, fromSeq);
        // refs 从 DB 恢复（Redis 不存 refs）
        sendEvent(emitter, "refs", Map.of("refs", parseRefs(message.getRefs())));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("messageId", messageId);
        done.put("finishReason", "resume");
        done.put("promptTokens", message.getPromptTokens());
        done.put("completeTokens", message.getCompleteTokens());
        done.put("degraded", false);
        sendEvent(emitter, "done", done);
        completeQuietly(emitter);
        cleanupRedisKeys(conversationId, messageId);
        log.info("AI 流从 Redis 恢复 messageId={} conversationId={} fromSeq={} replayed={}",
                messageId, conversationId, fromSeq, replayed);
    }

    /**
     * 重放 Redis 里 seq > fromSeq 的 delta 到当前连接。
     *
     * @param messageId assistant 消息 id
     * @param fromSeq   客户端已收到的最大 seq
     * @return 重放条数
     */
    private int replayFromRedis(Long messageId, int fromSeq) {
        List<String> entries = redisTemplate.opsForList().range(AiRedisKeys.stream(messageId), 0, -1);
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        ActiveStream active = activeStreams.get(messageId);
        SseEmitter emitter = active == null ? null : active.emitter;
        if (emitter == null) {
            return 0;
        }
        return replayEntries(emitter, entries, fromSeq);
    }

    /**
     * 把 Redis 倒序列表重放成升序 delta（seq > fromSeq）。
     *
     * @param emitter 目标连接
     * @param entries LPUSH 产生的倒序列表
     * @param fromSeq 客户端已收到的最大 seq
     * @return 重放条数
     */
    private int replayEntries(SseEmitter emitter, List<String> entries, int fromSeq) {
        List<String> ordered = new ArrayList<>(entries);
        Collections.reverse(ordered);
        int replayed = 0;
        for (String entry : ordered) {
            int index = entry == null ? -1 : entry.indexOf('|');
            if (index <= 0) {
                continue;
            }
            int seq;
            try {
                seq = Integer.parseInt(entry.substring(0, index));
            } catch (NumberFormatException e) {
                continue;
            }
            if (seq <= fromSeq) {
                continue;
            }
            String content = entry.substring(index + 1);
            if (sendEvent(emitter, "delta", Map.of("content", content))) {
                replayed++;
            }
        }
        return replayed;
    }

    // ── 落库 / Redis ────────────────────────────────────────────────────────

    /**
     * 创建本次的 assistant 消息（content 先留空字符串，避免 NULL 与 CONCAT 的兼容问题）。
     *
     * @param conversationId 会话 id
     * @param userId         用户 id
     * @param model          模型 id
     * @return 已回填 id 的消息
     */
    private AiMessage insertAssistantMessage(Long conversationId, Long userId, String model) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole("assistant");
        message.setContent("");
        message.setModel(model);
        message.setLang("zh-Hans");
        message.setIsSafetyPassed(1);
        message.setIsExported(0);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        aiMessageMapper.insert(message);
        return message;
    }

    /**
     * 增量刷库（每 20 个 delta 或 2 秒）：写当前累积全文。
     *
     * <p>用「全量覆盖」实现与 `CONCAT(content, ?)` 等价的语义，见类注释第 1 条。</p>
     *
     * @param active 流状态
     */
    private void flushIncremental(ActiveStream active) {
        try {
            aiMessageMapper.update(null, Wrappers.<AiMessage>lambdaUpdate()
                    .set(AiMessage::getContent, active.content.toString())
                    .set(AiMessage::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                    .eq(AiMessage::getId, active.messageId));
            active.deltasSinceFlush = 0;
            active.lastFlushAtMillis = System.currentTimeMillis();
        } catch (RuntimeException e) {
            // 刷库失败不影响继续生成：结束时还会写全量；只告警
            log.warn("AI 流增量刷库失败 messageId={} error={}",
                    active.messageId, e.getClass().getSimpleName());
        }
    }

    /**
     * 结束落库：content / model / tokens / refs / content_id / updated_at。
     *
     * <p>`content_id` 有唯一索引：冲突时重新生成一次（E2 第 2 条）。</p>
     *
     * <p><b>refs 的 null vs `[]`</b>（lead 2026-11-07 提醒，别混）：</p>
     * <ul>
     *   <li>`refsJson = "[]"`：走完了 D4 校验、确认没有引用（"查过，没引用"）；</li>
     *   <li>`refsJson = null`：截断/上游失败/断线取消等**没走完 D4** 的分支（"没查过"），
     *       落库写 NULL。两者语义不同，A-28 与段 4 按 NULL 与空数组分别处理。</li>
     * </ul>
     *
     * @param active     流状态
     * @param content    最终文本
     * @param refsJson   refs JSON（`[]` / 数组 / null=没查过）
     * @param degraded   是否降级（仅日志用；degraded 也由 done 事件带给前端）
     * @param contentId  内容编号（固定文案等非 AI 内容传 null）
     */
    private void finishAssistantMessage(ActiveStream active, String content, String refsJson,
                                        boolean degraded, String contentId) {
        if (contentId == null) {
            // 没有 content_id 就没有唯一冲突的可能，直接写
            updateAssistantMessage(active, content, refsJson, null);
            return;
        }
        String finalContentId = contentId;
        try {
            updateAssistantMessage(active, content, refsJson, finalContentId);
        } catch (DuplicateKeyException e) {
            // content_id 唯一冲突（概率极低）：重新生成一次再写（E2 第 2 条）
            finalContentId = contentIdGenerator.generate();
            log.warn("AI content_id 冲突，已重新生成 messageId={}", active.messageId);
            updateAssistantMessage(active, content, refsJson, finalContentId);
        }
    }

    /**
     * 执行结束写库。
     *
     * @param active    流状态
     * @param content   最终文本
     * @param refsJson  refs JSON
     * @param contentId 内容编号（可为 null）
     */
    private void updateAssistantMessage(ActiveStream active, String content, String refsJson, String contentId) {
        aiMessageMapper.update(null, Wrappers.<AiMessage>lambdaUpdate()
                .set(AiMessage::getContent, content)
                .set(AiMessage::getModel, active.model)
                .set(AiMessage::getPromptTokens, active.promptTokens)
                .set(AiMessage::getCompleteTokens, active.completeTokens)
                .set(AiMessage::getRefs, refsJson)
                .set(AiMessage::getContentId, contentId)
                .set(AiMessage::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(AiMessage::getId, active.messageId));
    }

    /**
     * 首字节超时 / 未出字失败时写「服务暂时不可用」。
     *
     * <p>这类消息的 content 是**固定文案、不是模型输出**：按 E2 的语义不给 content_id
     * （隐式标识只标 AI 生成内容）；refs 写 **NULL（没查过）**，不是 `[]`。</p>
     *
     * @param active  流状态
     * @param content 固定文案
     */
    private void writeUnavailableMessage(ActiveStream active, String content) {
        try {
            aiMessageMapper.update(null, Wrappers.<AiMessage>lambdaUpdate()
                    .set(AiMessage::getContent, content)
                    .set(AiMessage::getModel, active.model)
                    .set(AiMessage::getRefs, (String) null)
                    .set(AiMessage::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))
                    .eq(AiMessage::getId, active.messageId));
        } catch (RuntimeException e) {
            log.warn("AI 失败消息落库失败 messageId={} error={}",
                    active.messageId, e.getClass().getSimpleName());
        }
    }

    /**
     * 推送一条 delta 到 Redis 暂存（LPUSH + EXPIRE 600）。
     *
     * @param messageId assistant 消息 id
     * @param seq       序号
     * @param content   增量文本
     */
    private void pushDeltaToRedis(Long messageId, int seq, String content) {
        try {
            String key = AiRedisKeys.stream(messageId);
            redisTemplate.opsForList().leftPush(key, seq + "|" + content);
            redisTemplate.expire(key, Duration.ofSeconds(AiRedisKeys.TTL_SECONDS));
        } catch (RuntimeException e) {
            log.warn("AI delta 写 Redis 失败 messageId={} error={}",
                    messageId, e.getClass().getSimpleName());
        }
    }

    /**
     * 取走 C2 的待答载荷（GET+DEL 原子）。
     *
     * @param conversationId 会话 id
     * @return 载荷；不存在/解析失败返回 null
     */
    private PendingAsk takePendingAsk(Long conversationId) {
        try {
            String json = redisTemplate.execute(TAKE_ASK_SCRIPT,
                    Collections.singletonList(AiRedisKeys.ask(conversationId)));
            if (json == null || json.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(json, PendingAsk.class);
        } catch (RuntimeException e) {
            log.warn("AI 待答载荷读取失败 conversationId={} error={}",
                    conversationId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 清理本次流的 Redis key。
     *
     * @param active 流状态
     */
    private void cleanupRedis(ActiveStream active) {
        cleanupRedisKeys(active.conversationId, active.messageId);
    }

    /**
     * 删除 active 映射与 delta 暂存。
     *
     * @param conversationId 会话 id
     * @param messageId      assistant 消息 id
     */
    private void cleanupRedisKeys(Long conversationId, Long messageId) {
        try {
            redisTemplate.delete(AiRedisKeys.active(conversationId));
            redisTemplate.delete(AiRedisKeys.stream(messageId));
        } catch (RuntimeException e) {
            log.warn("AI 流 Redis 清理失败 conversationId={} messageId={} error={}",
                    conversationId, messageId, e.getClass().getSimpleName());
        }
    }

    // ── SSE 工具 ────────────────────────────────────────────────────────────

    /**
     * 注册 emitter 的超时/完成/错误回调。
     *
     * @param active  流状态
     * @param emitter 连接
     */
    private void registerEmitterCallbacks(ActiveStream active, SseEmitter emitter) {
        emitter.onTimeout(() -> onStreamTimeout(active));
        emitter.onCompletion(() -> {
            if (!active.finished) {
                markClientGone(active);
            }
        });
        emitter.onError(error -> {
            if (!active.finished) {
                markClientGone(active);
            }
        });
    }

    /**
     * 发送一个 SSE 事件；连接已断开时捕获并返回 false（**不抛 500**）。
     *
     * @param emitter 连接
     * @param name    事件名
     * @param data    data 负载
     * @return 是否成功送出
     */
    private boolean sendEvent(SseEmitter emitter, String name, Object data) {
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IllegalStateException | IOException e) {
            // 客户端已断开 / 连接已关闭：C4 要求忽略，不许变成 500
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 发 error 事件后关闭连接。
     *
     * @param emitter 连接
     * @param code    错误码
     * @param msg     用户可见文案（固定文案，不含上游原文）
     */
    private void sendErrorAndComplete(SseEmitter emitter, ErrorCode code, String msg) {
        sendEvent(emitter, "error", errorPayload(code, msg));
        completeQuietly(emitter);
    }

    /**
     * 构造 error 事件的 data。
     *
     * @param code 错误码
     * @param msg  固定文案
     * @return data
     */
    private Map<String, Object> errorPayload(ErrorCode code, String msg) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code.getCode());
        payload.put("msg", msg);
        return payload;
    }

    /**
     * 安静地完成连接（已断开/已完成的异常一律忽略）。
     *
     * @param emitter 连接
     */
    private void completeQuietly(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            // 已经断开或已完成：忽略
        }
    }

    /**
     * 取消延迟任务。
     *
     * @param future 任务（可 null）
     */
    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 取消上游订阅（Disposable.dispose）。
     *
     * @param active 流状态
     */
    private void disposeUpstream(ActiveStream active) {
        Disposable disposable = active.upstream;
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /**
     * D1 上游消息列表 → ChatRequest 的消息。
     *
     * @param messages PendingAsk 里的消息
     * @return 上游消息；空列表时返回空
     */
    private List<ChatMessage> toChatMessages(List<ChatMessage> messages) {
        return messages == null ? List.of() : messages;
    }

    /**
     * 解析 DB 里的 refs JSON 成快照列表（续传恢复用）。
     *
     * @param refsJson JSON 字符串
     * @return 快照列表；null/空/解析失败时返回空列表（**不抛异常**）
     */
    private List<LawRefSnapshot> parseRefs(String refsJson) {
        if (refsJson == null || refsJson.isBlank()) {
            return List.of();
        }
        try {
            List<LawRefSnapshot> refs = jsonMapper.readValue(refsJson,
                    new tools.jackson.core.type.TypeReference<List<LawRefSnapshot>>() {
                    });
            return refs == null ? List.of() : refs;
        } catch (RuntimeException e) {
            log.warn("AI refs JSON 解析失败 error={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    // ── 内部状态 ────────────────────────────────────────────────────────────

    /**
     * 一条正在进行的流的状态（一个 assistant 消息一个实例）。
     */
    private static final class ActiveStream {

        /** 助手消息 id。 */
        private final Long messageId;

        /** 会话 id。 */
        private final Long conversationId;

        /** 用户 id。 */
        private final Long userId;

        /** 检索命中（D3/D4 用）。 */
        private final List<LawHit> hits;

        /** 模型 id。 */
        private final String model;

        /** 预扣配额日期（失败回退用）。 */
        private final String quotaDay;

        /** 单次流上限（秒，来自 sys_config.ai_timeout_seconds；由调度器主动触发 done）。 */
        private final int timeoutSeconds;

        /** 并发保护：onNext / 超时 / 重连接管都在它里面串行化。 */
        private final Object lock = new Object();

        /** 累积全文（delta 三写的第 ② 写）。 */
        private final StringBuilder content = new StringBuilder();

        /** delta 序号（从 1 开始，Redis 里按它续传）。 */
        private final AtomicInteger seq = new AtomicInteger(0);

        /** 当前连接（重连时会替换）。 */
        private volatile SseEmitter emitter;

        /** 上游订阅（中断时 dispose）。 */
        private volatile Disposable upstream;

        /** 首字节超时定时器。 */
        private volatile ScheduledFuture<?> firstByteTask;

        /** 单次流上限定时器（主动发 done+timeout）。 */
        private volatile ScheduledFuture<?> streamTimeoutTask;

        /** 断线回收定时器。 */
        private volatile ScheduledFuture<?> detachCleanupTask;

        /** 是否已完成（成功/失败/取消都置 true，用于幂等）。 */
        private volatile boolean finished;

        /** 客户端是否已断开（等待重连）。 */
        private volatile boolean clientGone;

        /** 是否已收到首字节。 */
        private volatile boolean firstChunkSeen;

        /** 上次刷库时间。 */
        private volatile long lastFlushAtMillis = System.currentTimeMillis();

        /** 自上次刷库以来的 delta 数。 */
        private int deltasSinceFlush;

        /** 输入 token 数（上游最后一帧给）。 */
        private volatile Integer promptTokens;

        /** 输出 token 数。 */
        private volatile Integer completeTokens;

        /** 上游给的结束原因。 */
        private volatile String finishReason;

        /**
         * 构造。
         *
         * @param messageId      助手消息 id
         * @param conversationId 会话 id
         * @param userId         用户 id
         * @param hits           检索命中
         * @param model          模型 id
         * @param quotaDay       配额日期
         * @param timeoutSeconds 单次流上限（秒）
         */
        private ActiveStream(Long messageId, Long conversationId, Long userId, List<LawHit> hits,
                             String model, String quotaDay, int timeoutSeconds) {
            this.messageId = messageId;
            this.conversationId = conversationId;
            this.userId = userId;
            this.hits = hits == null ? List.of() : hits;
            this.model = model;
            this.quotaDay = quotaDay;
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
