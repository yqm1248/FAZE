/*
 * 职责：`/ws/chat` 的 WebSocket 处理器——连接生命周期（注册/踢旧/补推/清理）与按 type 分发。
 * 调用方：modules/conversation/config/WebSocketConfig（注册到 `/ws/chat`）。
 * 数据来源：握手 attributes 里的 LoginUser、入站信封（ChatEnvelope）、各 ChatTypeHandler。
 * 注意：
 *   1. **身份只来自握手 attributes**（`ChatHandshakeInterceptor.ATTR_LOGIN_USER`）：
 *      每条消息都重新读一次，不缓存字段，避免 handler 里出现「用户传了 userId 就用它」。
 *   2. **每种 type 一个处理器**（`ws/handler/ChatTypeHandler`）：本类只做查表分发与错误帧，
 *      业务逻辑一律在 handler → Service。
 *   3. 错误一律回 `chat.error`（带 code / msgKey / clientMsgId），**不静默丢弃、不断连接**：
 *      一条非法消息断开整条长连接会让用户丢掉正在打的字。
 *   4. **心跳**：收到 `ping` 回 `pong` 并续期在线标记；连接空闲 90 秒由容器回收
 *      （`setMaxIdleTimeout`，技术路线 §7.4）。客户端每 30 秒 ping 一次。
 *   5. **多端踢旧**：`register` 返回旧连接时，推 `chat.kicked` 再关它（技术路线 §7.4）。
 *   6. 补推离线消息在**连接建立后**立刻做（G6.6 第 2 条）：重连即补齐，客户端无需额外请求。
 */
package com.faze.modules.conversation.ws;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.service.MessagePushService;
import com.faze.modules.conversation.service.OnlineRegistry;
import com.faze.modules.conversation.ws.handler.ChatTypeHandler;
import com.faze.security.LoginUser;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 聊天 WebSocket 处理器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /** 空闲超时：客户端 30 秒心跳，服务端 90 秒无消息视为掉线（技术路线 §7.4）。 */
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);

    /** 空闲扫描间隔（毫秒）。 */
    private static final long IDLE_SCAN_INTERVAL_MS = 30_000L;

    /**
     * 每个连接的最后活跃时间（sessionId → epoch millis）。
     *
     * <p>为什么要自己记：Spring 的 `WebSocketSession` 接口**没有** `setMaxIdleTimeout`，
     * 容器级超时又依赖具体实现（Tomcat 的 `maxSessionIdleTimeout` 默认 0 = 永不超时）。
     * 应用层记时间戳 + 定时扫描是唯一与容器无关的可靠做法。</p>
     */
    private final Map<String, Long> lastActiveAt = new ConcurrentHashMap<>();

    private final JsonMapper jsonMapper;
    private final OnlineRegistry onlineRegistry;
    private final ChatSocketSender sender;
    private final MessagePushService messagePushService;
    private final List<ChatTypeHandler> handlers;

    /** type → 处理器（懒构建一次，避免每条消息都遍历 List）。 */
    private volatile Map<String, ChatTypeHandler> handlerMap;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        try {
            // 记录最后活跃时间：空闲回收由 closeIdleSessions 负责（容器超时不可靠，见字段注释）
            lastActiveAt.put(session.getId(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("记录 WS 活跃时间失败 session={} 原因={}", session.getId(), e.getClass().getSimpleName());
        }        WebSocketSession old = onlineRegistry.register(userId, session);
        if (old != null) {
            // 新设备登录：踢掉旧连接（先通知，再关）
            Map<String, Object> kicked = new HashMap<>();
            kicked.put("reason", "kicked_by_new_device");
            sender.send(old, "chat.kicked", kicked);
            closeQuietly(old, CloseStatus.NORMAL);
        }
        log.info("WS 连接建立 userId={} session={}", userId, session.getId());

        // 重连即补推：把对方在离线期间发来的消息按 seq 升序推过来（G6.6）
        int pushed = messagePushService.drainOffline(session, userId);
        if (pushed > 0) {
            log.info("重连补推离线消息 userId={} count={}", userId, pushed);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = userIdOf(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        onlineRegistry.touch(userId, session.getId());
        lastActiveAt.put(session.getId(), System.currentTimeMillis());

        ChatEnvelope envelope;
        try {
            envelope = jsonMapper.readValue(message.getPayload(), ChatEnvelope.class);
        } catch (RuntimeException e) {
            sender.sendError(session, ErrorCode.COMMON_PARAM_FORMAT, null);
            return;
        }
        if (envelope == null || envelope.getType() == null) {
            sender.sendError(session, ErrorCode.COMMON_PARAM_MISSING, null);
            return;
        }
        if ("ping".equals(envelope.getType())) {
            sender.send(session, "pong", Map.of());
            return;
        }

        ChatTypeHandler handler = handlers().get(envelope.getType());
        if (handler == null) {
            log.info("未知 WS 消息类型 userId={} type={}", userId, envelope.getType());
            sender.sendError(session, ErrorCode.COMMON_PARAM_INVALID, clientMsgIdOf(envelope));
            return;
        }
        ChatContext context = new ChatContext(session, loginUserOf(session), userId, envelope.getId());
        try {
            handler.handle(context, envelope.getData());
        } catch (BusinessException e) {
            // 业务拒绝：回错误帧，连接保持（用户还能继续用）
            sender.sendError(session, e.getErrorCode(), clientMsgIdOf(envelope));
            log.info("WS 业务拒绝 userId={} type={} code={}", userId, envelope.getType(),
                    e.getErrorCode().getCode());
        } catch (RuntimeException e) {
            // 未预期异常：记录堆栈便于排查，但只回通用错误码（不外泄内部信息）
            log.error("WS 处理异常 userId={} type={} 原因={}", userId, envelope.getType(),
                    e.getClass().getSimpleName(), e);
            sender.sendError(session, ErrorCode.COMMON_INTERNAL, clientMsgIdOf(envelope));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = userIdOf(session);
        if (userId != null) {
            onlineRegistry.unregister(userId, session.getId());
        }
        lastActiveAt.remove(session.getId());
        log.info("WS 连接关闭 userId={} session={} status={}", userId, session.getId(), status.getCode());
    }

    /**
     * 关闭超时未心跳的连接（技术路线 §7.4：90 秒无心跳则主动关闭并清理在线表）。
     *
     * <p>为什么不用容器的 idle timeout：`WebSocketSession` 接口没有该设置，
     * Tomcat 的 `maxSessionIdleTimeout` 默认 0（永不超时），依赖容器就等于没有心跳检测。</p>
     *
     * 副作用：关闭连接并触发 `afterConnectionClosed`（在线表随之清理）。
     */
    @Scheduled(fixedDelay = IDLE_SCAN_INTERVAL_MS)
    public void closeIdleSessions() {
        long now = System.currentTimeMillis();
        long timeout = IDLE_TIMEOUT.toMillis();
        for (Map.Entry<String, Long> entry : lastActiveAt.entrySet()) {
            if (now - entry.getValue() <= timeout) {
                continue;
            }
            lastActiveAt.remove(entry.getKey());
            log.info("WS 连接心跳超时，主动关闭 session={} idleMs={}", entry.getKey(), now - entry.getValue());
            // 连接对象在在线表里，按 sessionId 找到并关闭
            onlineRegistry.closeBySessionId(entry.getKey());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输错误 session={} 原因={}", session.getId(), exception.getClass().getSimpleName());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 构建（或取缓存）type → 处理器映射。
     *
     * @return 映射
     */
    private Map<String, ChatTypeHandler> handlers() {
        Map<String, ChatTypeHandler> cached = handlerMap;
        if (cached != null) {
            return cached;
        }
        Map<String, ChatTypeHandler> map = new HashMap<>();
        for (ChatTypeHandler handler : handlers) {
            map.put(handler.type(), handler);
        }
        handlerMap = map;
        return map;
    }

    /**
     * 从 session attributes 取登录用户主键。
     *
     * @param session 连接
     * @return 用户主键；未认证返回 null
     */
    private Long userIdOf(WebSocketSession session) {
        LoginUser loginUser = loginUserOf(session);
        return loginUser == null ? null : loginUser.getUserId();
    }

    /**
     * 从 session attributes 取登录主体。
     *
     * @param session 连接
     * @return 登录主体；未认证返回 null
     */
    private LoginUser loginUserOf(WebSocketSession session) {
        Object value = session.getAttributes().get(ChatHandshakeInterceptor.ATTR_LOGIN_USER);
        return value instanceof LoginUser loginUser ? loginUser : null;
    }

    /**
     * 取客户端消息 id（错误帧回显用）。
     *
     * @param envelope 入站信封
     * @return clientMsgId；没有返回 null
     */
    private String clientMsgIdOf(ChatEnvelope envelope) {
        if (envelope.getData() == null) {
            return null;
        }
        var node = envelope.getData().get("clientMsgId");
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 安静地关闭连接（失败只记 debug，不再抛）。
     *
     * @param session 连接
     * @param status  关闭状态
     */
    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception e) {
            log.debug("关闭 WS 连接失败 session={} 原因={}", session.getId(), e.getClass().getSimpleName());
        }
    }
}
