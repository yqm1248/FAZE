/*
 * 职责：在线连接注册表（userId → WebSocketSession），支撑「在线直推 / 离线入队 / 多端踢旧」。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler、
 *        modules/conversation/service/MessagePushService（判断是否需要写 message_offline）。
 * 数据来源：内存 `ConcurrentHashMap`（本进程连接）+ Redis `faze:im:online:{userId}`（跨实例可见的在线标记）。
 * 注意：
 *   1. **段 2 是单实例部署，推送以内存表为准**；Redis 只承担两件事：
 *      ① 让「在线表在 Redis」这条约定不是空话（重启/排障时能查）；② 多实例时可直接扩展成
 *      「发布订阅到目标实例」而不用改表结构。**不要**在此之上做业务判断（比如用它算未读）。
 *   2. **踢旧连接**：同一账号在新设备连上来时，`register` 返回旧 session，由调用方推 `chat.kicked`
 *      再关闭它（技术路线 §7.4「多端登录」）。返回旧 session 而不是直接关，是为了让 handler 能先发消息。
 *   3. **`unregister` 必须校验 sessionId**：旧连接被踢时也会触发 `afterConnectionClosed`，
 *      若不带 sessionId 就删，会把刚建立的新连接从在线表里抹掉（表现为「刚连上就显示离线」）。
 *   4. 连接关闭不抛异常：清理路径上的异常一律吞掉并记 warn（否则一次关闭失败会污染 onClose 后续逻辑）。
 */
package com.faze.modules.conversation.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 在线连接注册表。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineRegistry {

    /** Redis 在线标记 key 前缀（`faze:im:online:{userId}`）。 */
    private static final String KEY_PREFIX = "faze:im:online:";

    /** 在线标记 TTL：客户端每 30 秒心跳，TTL 给 120 秒，掉线后 2 分钟内自然过期。 */
    private static final Duration ONLINE_TTL = Duration.ofSeconds(120);

    /** 本进程的连接表：userId → session。 */
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** 反查表：sessionId → userId（空闲扫描按 sessionId 找连接用）。 */
    private final Map<String, Long> owners = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;

    /**
     * 注册一个连接（同一账号的新连接会替换旧的）。
     *
     * @param userId  登录用户
     * @param session 新连接
     * @return 被替换的旧连接；没有则 null（调用方负责推 `chat.kicked` 并关闭它）
     */
    public WebSocketSession register(Long userId, WebSocketSession session) {
        WebSocketSession old = sessions.put(userId, session);
        owners.put(session.getId(), userId);
        touch(userId, session.getId());
        if (old != null && !old.getId().equals(session.getId())) {
            owners.remove(old.getId());
            log.info("同一账号新连接替换旧连接 userId={} oldSession={} newSession={}",
                    userId, old.getId(), session.getId());
            return old;
        }
        return null;
    }

    /**
     * 注销连接（只注销自己那一条）。
     *
     * @param userId    登录用户
     * @param sessionId 关闭的连接 id
     */
    public void unregister(Long userId, String sessionId) {
        if (userId == null) {
            return;
        }
        WebSocketSession current = sessions.get(userId);
        if (current != null && current.getId().equals(sessionId)) {
            sessions.remove(userId, current);
            owners.remove(sessionId);
            try {
                redis.delete(KEY_PREFIX + userId);
            } catch (RuntimeException e) {
                log.warn("清理 Redis 在线标记失败 userId={} 原因={}", userId, e.getClass().getSimpleName());
            }
            log.info("连接已注销 userId={} session={}", userId, sessionId);
        }
    }

    /**
     * 按 sessionId 关闭连接（心跳超时回收用）。
     *
     * <p>关闭动作会触发 `afterConnectionClosed`，在线表由那里统一清理；本方法只管「关」。</p>
     *
     * @param sessionId 连接 id
     */
    public void closeBySessionId(String sessionId) {
        Long userId = owners.get(sessionId);
        if (userId == null) {
            return;
        }
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.getId().equals(sessionId)) {
            owners.remove(sessionId);
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            }
        } catch (Exception e) {
            log.warn("关闭超时连接失败 session={} 原因={}", sessionId, e.getClass().getSimpleName());
        }
    }

    /**
     * 续期在线标记（收到心跳或任意消息时调用）。
     *
     * @param userId    登录用户
     * @param sessionId 连接 id
     */
    public void touch(Long userId, String sessionId) {
        try {
            redis.opsForValue().set(KEY_PREFIX + userId, sessionId, ONLINE_TTL);
        } catch (RuntimeException e) {
            // Redis 抖动不该影响长连接本身：在线表退化为内存表仍能工作
            log.warn("刷新 Redis 在线标记失败 userId={} 原因={}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * 判断用户当前是否有可用连接。
     *
     * @param userId 登录用户
     * @return 有打开中的连接返回 true
     */
    public boolean isOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        WebSocketSession session = sessions.get(userId);
        return session != null && session.isOpen();
    }

    /**
     * 取用户当前连接（推送用）。
     *
     * @param userId 登录用户
     * @return 连接；离线或已关闭返回 null
     */
    public WebSocketSession sessionOf(Long userId) {
        if (userId == null) {
            return null;
        }
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            return null;
        }
        return session;
    }
}
