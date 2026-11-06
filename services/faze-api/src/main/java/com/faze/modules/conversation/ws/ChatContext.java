/*
 * 职责：单个 WebSocket 消息的处理上下文（连接 + 当前身份 + 信封关联 id）。
 * 调用方：modules/conversation/ws/ChatWebSocketHandler#dispatch → 各 ChatTypeHandler。
 * 数据来源：握手阶段解析出的 LoginUser（**唯一身份来源，不许从 data 里取 userId**）。
 * 注意：
 *   1. `userId` 是握手指令解析出来的登录用户；handler 里**任何业务调用都传它**，
 *      data 里出现的 userId / senderId 一律忽略（否则就是越权）。
 *   2. `envelopeId` 只用于日志关联与错误回执，不参与业务。
 */
package com.faze.modules.conversation.ws;

import com.faze.security.LoginUser;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 处理上下文。
 *
 * @param session    当前连接
 * @param loginUser  当前登录主体
 * @param userId     当前登录用户主键（= loginUser.getUserId()，避免每个 handler 重复取）
 * @param envelopeId 客户端信封 id（日志关联用）
 */
public record ChatContext(WebSocketSession session, LoginUser loginUser, Long userId, String envelopeId) {
}
