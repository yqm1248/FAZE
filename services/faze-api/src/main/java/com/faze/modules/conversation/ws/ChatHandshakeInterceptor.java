/*
 * 职责：`/ws/chat` 的握手鉴权（JWT 校验 → 把 LoginUser 放进 session attributes）。
 * 调用方：modules/conversation/config/WebSocketConfig（注册到 handler）。
 * 数据来源：握手请求的 query 参数 `token`，或 `Authorization: Bearer <token>` 头。
 * 注意：
 *   1. **握手阶段必须验完 JWT**（技术路线 §7.2「先连上再发 login 消息」的写法会留一个未鉴权窗口）。
 *      校验失败直接拒绝握手（HTTP 403），不允许「连上来但不认证」的连接存在。
 *   2. **token 从 query 取的优先级高于 header**：浏览器/RN 的 WebSocket 构造器不能自定义头，
 *      query 是唯一通用通道；两者都给时以 query 为准（客户端显式传的更可信）。
 *   3. 不在这里查库：JWT 里已有 userId/role/tokenVersion，账号禁用/版本失效由
 *      `JwtAuthenticationFilter` 的同一套规则覆盖；WS 侧只解析不查库（握手要快）。
 *   4. **不把 token 记日志**：日志规范禁止记录凭证原文。
 */
package com.faze.modules.conversation.ws;

import com.faze.security.JwtTokenProvider;
import com.faze.security.LoginUser;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * WS 握手鉴权拦截器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    /** session attributes 里存放 LoginUser 的键（handler 从这里取身份）。 */
    public static final String ATTR_LOGIN_USER = "loginUser";

    /** query 参数名。 */
    private static final String PARAM_TOKEN = "token";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        try {
            LoginUser loginUser = jwtTokenProvider.parseAccessToken(token);
            if (loginUser == null || loginUser.getUserId() == null) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put(ATTR_LOGIN_USER, loginUser);
            return true;
        } catch (RuntimeException e) {
            // 过期 / 签名不对 / 结构非法：都是「不给连」，不区分原因（避免给攻击者反馈）
            log.info("WS 握手鉴权失败 原因={}", e.getClass().getSimpleName());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无副作用
    }

    /**
     * 从 query 或 Authorization 头取 token。
     *
     * @param request 握手请求
     * @return token；都没有返回 null
     */
    private String resolveToken(ServerHttpRequest request) {
        String fromQuery = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst(PARAM_TOKEN);
        if (StringUtils.hasText(fromQuery)) {
            return fromQuery.trim();
        }
        String header = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        return null;
    }
}
