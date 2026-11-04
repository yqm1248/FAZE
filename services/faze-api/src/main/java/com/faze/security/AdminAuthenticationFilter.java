/*
 * 职责：`/api/v1/admin/**` 的认证过滤器——把 RuoYi 的登录态翻译成 SecurityContext 里的 ROLE_ADMIN。
 * 调用方：SecurityConfig 插到 JwtAuthenticationFilter 之后、UsernamePasswordAuthenticationFilter 之前。
 * 数据来源：`Authorization: Bearer <RuoYi token>` → AdminTokenVerifier → AdminSessionStore（Redis）。
 * 注意：
 *   1. **只管 `/api/v1/admin/**`**，其它路径由 security/JwtAuthenticationFilter 处理（各自
 *      `shouldNotFilter` 互斥）。两套 token 的签名算法与密钥完全不同（HS256 自签 vs HS512 RuoYi 签），
 *      让两个过滤器都跑一遍只会互相踩上下文，还会把失败原因写串。
 *   2. 失败**不抛异常、只清上下文后放行**：交给 ExceptionTranslationFilter 走 401 出口，
 *      这是 Spring Security 的链式约定（同 JwtAuthenticationFilter 的第 1 条）。
 *   3. 认证成功时初始化审计四要素（operator_type = `admin`）。`operation_log.operator_type`
 *      只有 user / admin / system 三种取值，这里必须是 admin，否则审计里分不清是谁干的。
 *   4. `AuditContext.clear()` 必须在本过滤器的 finally 里做：本类处理完 admin 路径后，
 *      JwtAuthenticationFilter 的 finally 不会执行（它 shouldNotFilter 跳过了），
 *      不清就会在 Tomcat 线程复用时把上一位管理员带给下一个请求。
 *   5. 登录态 UUID 来自**已验签**的 JWT，不是从请求体读——请求体不可信。
 */
package com.faze.security;

import com.faze.common.audit.AuditContext;
import com.faze.common.util.RequestUtils;
import com.faze.infra.adminauth.AdminSession;
import com.faze.infra.adminauth.AdminSessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理端认证过滤器。
 */
@Component
@Slf4j
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    /** 管理端路径前缀。 */
    private static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";
    /** 管理端根路径（不带尾斜杠时也要命中，否则会出现「目录级」请求漏鉴权）。 */
    private static final String ADMIN_PATH_ROOT = "/api/v1/admin";
    /** 管理端唯一角色（AGENTS 决策：后台只有一个管理员角色）。 */
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    /** request attribute 名：管理端认证失败原因。出口据此决定回哪种 401。 */
    public static final String ATTR_FAILURE = "faze.admin.auth.failure";
    /** 失败原因值：没带 token。 */
    public static final String FAILURE_MISSING = "MISSING";
    /** 失败原因值：token 验签失败。 */
    public static final String FAILURE_INVALID = "INVALID";
    /** 失败原因值：验签通过但 Redis 里没有登录态（未登录 / 已过期 / 已登出）。 */
    public static final String FAILURE_NO_SESSION = "NO_SESSION";

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminTokenVerifier tokenVerifier;
    private final AdminSessionStore sessionStore;

    /**
     * 构造过滤器。
     *
     * @param tokenVerifier RuoYi token 验签器
     * @param sessionStore  RuoYi 登录态只读存储
     */
    public AdminAuthenticationFilter(AdminTokenVerifier tokenVerifier, AdminSessionStore sessionStore) {
        this.tokenVerifier = tokenVerifier;
        this.sessionStore = sessionStore;
    }

    /**
     * 判断是否管理端路径。
     *
     * <p>供 401 / 403 出口复用：管理端的鉴权失败必须走「HTTP 200 + body.code」，
     * 不能靠各自复制一遍前缀常量（复制两次就会漂移一次）。</p>
     *
     * @param path 请求路径（`request.getRequestURI()`）
     * @return true 表示属于 `/api/v1/admin/**`
     */
    public static boolean isAdminPath(String path) {
        return path != null && (path.startsWith(ADMIN_PATH_PREFIX) || ADMIN_PATH_ROOT.equals(path));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isAdminPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            authenticate(request);
        } catch (RuntimeException e) {
            // 任何意外都不许变成 500：管理端拿不到身份就必须是 401
            SecurityContextHolder.clearContext();
            request.setAttribute(ATTR_FAILURE, FAILURE_INVALID);
            log.error("管理端认证过程异常 path={}", request.getRequestURI(), e);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    /**
     * 解析 token 并写入 SecurityContext。
     *
     * <p>副作用：可能写 SecurityContext、写 request attribute、初始化 AuditContext。</p>
     *
     * @param request 当前请求
     */
    private void authenticate(HttpServletRequest request) {
        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            request.setAttribute(ATTR_FAILURE, FAILURE_MISSING);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            request.setAttribute(ATTR_FAILURE, FAILURE_MISSING);
            return;
        }

        String loginUserKey = tokenVerifier.extractLoginUserKey(token);
        if (loginUserKey == null) {
            request.setAttribute(ATTR_FAILURE, FAILURE_INVALID);
            log.debug("管理端 token 验签失败 path={}", request.getRequestURI());
            return;
        }

        Optional<AdminSession> session = sessionStore.find(loginUserKey);
        if (session.isEmpty()) {
            request.setAttribute(ATTR_FAILURE, FAILURE_NO_SESSION);
            log.debug("管理端 token 验签通过但无登录态（已过期/已登出） path={}", request.getRequestURI());
            return;
        }

        AdminLoginUser principal = new AdminLoginUser(session.get().adminId(), session.get().username());
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(ADMIN_AUTHORITY)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 审计四要素：操作人是后台账号，类型固定 admin
        AuditContext.init(principal.adminId(), "admin", RequestUtils.clientIp(request));
    }
}
