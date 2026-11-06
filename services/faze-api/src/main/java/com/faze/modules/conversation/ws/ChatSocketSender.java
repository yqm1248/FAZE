/*
 * 职责：WS 出站消息的唯一发送入口（统一信封 + JSON 序列化 + 并发安全）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler、ws/handler/*、service/MessagePushService。
 * 数据来源：业务层 VO / Map（本类不做业务判断）。
 * 注意：
 *   1. **`WebSocketSession#sendMessage` 不是线程安全的**：多个 handler（发送 ack 与推送）可能
 *      同时往同一连接写，并发写会抛 `IllegalStateException: TEXT_PARTIAL_WRITING`。
 *      本类对 session 加锁串行化（同一连接的消息量很小，锁竞争可忽略）。
 *   2. **发送失败不抛给调用方**：对端刚断线时写会抛 IOException，此时业务已经落库成功，
 *      抛出去只会让发送方的请求也失败（消息其实已经存了）。这里记 warn 并返回 false。
 *   3. `chat.error` 单独封装（`sendError`）：错误信封的字段固定，避免各 handler 手拼漏字段。
 *   4. 时间戳一律 **UTC ISO-8601**（与库内时间口径一致），客户端按设备时区展示。
 */
package com.faze.modules.conversation.ws;

import com.faze.common.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * WS 消息发送器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatSocketSender {

    /** 同一毫秒内的信封序号（信封 id 形如 `1730000000000-3`）。 */
    private final AtomicLong sequence = new AtomicLong();

    private final JsonMapper jsonMapper;

    /**
     * 发送一个信封。
     *
     * @param session 目标连接（null 或已关闭时直接返回 false）
     * @param type    消息类型
     * @param data    业务数据（可序列化为 JSON 的对象）
     * @return 发送成功返回 true；连接不可用返回 false
     */
    public boolean send(WebSocketSession session, String type, Object data) {
        if (session == null || !session.isOpen()) {
            return false;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("id", envelopeId());
        envelope.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        envelope.put("data", data);
        String payload;
        try {
            payload = jsonMapper.writeValueAsString(envelope);
        } catch (RuntimeException e) {
            log.warn("WS 消息序列化失败 type={} 原因={}", type, e.getClass().getSimpleName());
            return false;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
            return true;
        } catch (Exception e) {
            // 对端刚断线是常态，不是业务失败
            log.warn("WS 发送失败 type={} session={} 原因={}", type, session.getId(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 发送错误信封。
     *
     * @param session     目标连接
     * @param errorCode   错误码枚举（取 code / msgKey / msg）
     * @param clientMsgId 客户端消息 id（可空；发送方据此把「发送中」气泡标为失败）
     * @return 发送成功返回 true
     */
    public boolean sendError(WebSocketSession session, ErrorCode errorCode, String clientMsgId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", errorCode.getCode());
        data.put("msgKey", errorCode.getMsgKey());
        data.put("msg", errorCode.getMsg());
        if (clientMsgId != null) {
            data.put("clientMsgId", clientMsgId);
        }
        return send(session, "chat.error", data);
    }

    /**
     * 生成信封 id。
     *
     * @return `毫秒时间戳-自增序号`
     */
    private String envelopeId() {
        return InstantMillis.now() + "-" + sequence.incrementAndGet();
    }

    /**
     * 毫秒时间戳工具（单独包一层，避免每处写 System.currentTimeMillis 造成签名不一致）。
     */
    private static final class InstantMillis {

        private InstantMillis() {
        }

        /**
         * @return 当前毫秒时间戳
         */
        private static long now() {
            return System.currentTimeMillis();
        }
    }
}
