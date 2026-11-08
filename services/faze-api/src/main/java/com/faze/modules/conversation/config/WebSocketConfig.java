/*
 * 职责：注册 `/ws/chat` 端点与握手拦截器。
 * 调用方：Spring 容器自动装配。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. 用 `@EnableWebSocket` + `WebSocketConfigurer`（Spring 原生 `WebSocketHandler`），
 *      **不用 `@ServerEndpoint`**：后者与 Spring 依赖注入冲突，要额外维护静态 ApplicationContext
 *      （工作流 G6.2「卡住怎么办」点名了这个坑）。
 *   2. `/ws/chat` 的鉴权**不在 Security 过滤器链里**（握手没有 Authorization 头）：
 *      SecurityConfig 对该路径 permitAll，真正的校验在 `ChatHandshakeInterceptor`。
 *      这是「先 permitAll 再握手鉴权」的唯一正确形态，不要为了「看起来安全」把手交换成 401
 *      （会导致浏览器/RN 的 WS 连不上）。
 *   3. `setAllowedOrigins("*")`：RN/浏览器与 API 不同源（开发时 expo web 是 8090）。
 *      生产收紧白名单属段 8 部署配置，不在本段。
 */
package com.faze.modules.conversation.config;

import com.faze.modules.conversation.ws.ChatHandshakeInterceptor;
import com.faze.modules.conversation.ws.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** 聊天端点路径。 */
    public static final String CHAT_ENDPOINT = "/ws/chat";

    private final ChatWebSocketHandler chatWebSocketHandler;

    private final ChatHandshakeInterceptor chatHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, CHAT_ENDPOINT)
                .addInterceptors(chatHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
