/*
 * 职责：解析 Authorization: Bearer <token>，把用户放进 SecurityContext；解析失败**不抛异常**，只标记原因后放行。
 * 调用方：SecurityConfig 把它插到 UsernamePasswordAuthenticationFilter 之前。
 * 数据来源：请求头 + JwtTokenProvider。
 * 注意：
 *   1. **失败不抛异常、只清上下文后放行**：这是 Spring Security 的链式约定——认证失败要让
 *      后续的 ExceptionTranslationFilter 交给 EntryPoint 出 401。若在这里直接抛，会变成 500。
 *   2. 失败原因写进 request attribute（EXPIRED / INVALID），由 RestAuthenticationEntryPoint
 *      读出来决定回 402002 还是 402003。**两者必须区分**：都回 402002 会让客户端无限刷新，
 *      都回 402003 会让过期用户被强制登出（《错误码规范》§4.3 第 3 条）。
 *   3. 认证成功时顺手初始化审计四要素（operator_id / operator_type / ip），并在请求结束时 clear。
 *      不 clear 的话线程池复用会把上一位操作人带给下一个请求——审计写成别人的名字，比没有更糟。
 *   4. 没有 Authorization 头时直接放行，不标记失败：匿名访问 pub 接口是正常路径，
 *      只有真正需要认证的请求才会走到 EntryPoint。
 */
package com.faze.security;

import com.faze.common.audit.AuditContext;
import com.faze.common.util.RequestUtils;
import com.faze.modules.account.service.AccountQueryService;
import com.faze.modules.account.vo.AccountAuthSnapshot;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：失败不抛异常，只标记原因后放行。
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** request attribute 名：认证失败原因。EntryPoint 据此选 402002 / 402003。 */
    public static final String ATTR_FAILURE = "faze.auth.failure";
    /** 失败原因值：token 过期。 */
    public static final String FAILURE_EXPIRED = "EXPIRED";
    /** 失败原因值：token 无效（签名错、格式错、类型错）。 */
    public static final String FAILURE_INVALID = "INVALID";

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 可用状态值。 */
    private static final String STATUS_ACTIVE = "active";

    private final JwtTokenProvider tokenProvider;
    private final AccountQueryService accountQueryService;

    /**
     * 构造过滤器。
     *
     * @param tokenProvider        JWT 解析器
     * @param accountQueryService  账号认证快照查询（比对 token_version 与账号状态）
     */
    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   AccountQueryService accountQueryService) {
        this.tokenProvider = tokenProvider;
        this.accountQueryService = accountQueryService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // 匿名请求：正常路径，不标记失败
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            request.setAttribute(ATTR_FAILURE, FAILURE_INVALID);
            chain.doFilter(request, response);
            return;
        }

        try {
            LoginUser loginUser = tokenProvider.parseAccessToken(token);

            // 凭证版本与账号状态校验：改密 / 换绑 / 注销会把 token_version +1，
            // 旧 token 即使没过期也必须立即失效（B3 的安全要求）。
            // 四种情况对客户端是同一件事——这张令牌不能用了，统一走 402003。
            AccountAuthSnapshot snapshot = accountQueryService
                    .findAuthSnapshot(loginUser.getUserId())
                    .orElse(null);
            if (snapshot == null
                    || snapshot.deleted()
                    || !STATUS_ACTIVE.equals(snapshot.status())
                    || snapshot.tokenVersion() != loginUser.getTokenVersion()) {
                throw new JwtException("凭证版本或账号状态已变化");
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    loginUser, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.getRole().toUpperCase())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 审计四要素：从 JWT 取操作人，IP 从请求取
            AuditContext.init(loginUser.getUserId(), "user", RequestUtils.clientIp(request));
        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute(ATTR_FAILURE, FAILURE_EXPIRED);
            log.debug("access token 已过期 path={}", request.getRequestURI());
        } catch (JwtException | IllegalArgumentException e) {
            SecurityContextHolder.clearContext();
            request.setAttribute(ATTR_FAILURE, FAILURE_INVALID);
            log.debug("access token 无效 path={}", request.getRequestURI());
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // ThreadLocal 必须清，否则线程复用会把上一位操作人带给下一个请求
            AuditContext.clear();
        }
    }
}
