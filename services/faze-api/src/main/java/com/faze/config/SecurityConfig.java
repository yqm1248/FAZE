/*
 * 职责：Spring Security 过滤器链配置（无状态 JWT 鉴权）。段 0 唯一目的：让「受保护资源」能验出 401。
 * 调用方：Spring 容器自动装配；JwtAuthenticationFilter 由本类插入链中。
 * 数据来源：无（纯配置）。
 * 注意：
 *   1. **必须无状态**（STATELESS）：不建 session，否则多实例部署后 JWT 与 session 两套身份并存，
 *      排障时无法判断哪个生效。
 *   2. 放行前缀只有 `pub`（+ 容器错误页 `/error`），`app` 必须认证，其余一律 denyAll。
 *      用 denyAll 而不是 permitAll 是「默认拒绝」原则：段 0 尚未规划的后缀不该被误放行。
 *   3. 统一出口由 RestAuthenticationEntryPoint（401）与 RestAccessDeniedHandler（403）负责，
 *      不许让 Spring Security 的默认 HTML 错误页暴露成响应体——客户端只认 JSON。
 *   4. 401 的两码区分（402002 过期 / 402003 无效）在 EntryPoint 里读数，
 *      原因由 JwtAuthenticationFilter 写入 request attribute，见该过滤器注释。
 */
package com.faze.config;

import com.faze.security.AdminAuthenticationFilter;
import com.faze.security.JwtAuthenticationFilter;
import com.faze.security.RestAccessDeniedHandler;
import com.faze.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置：无状态 JWT + 前缀分级授权。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 构造安全过滤器链。
     *
     * <p>副作用：替换 Spring Boot 默认的「全部需认证 + form 登录」链；关闭 csrf/cors/basic/form/logout。</p>
     *
     * @param http                   HttpSecurity 构建器
     * @param jwtAuthenticationFilter JWT 解析过滤器（失败不抛异常，只标记原因后放行）
     * @param entryPoint             未认证出口（401 + 402002/402003）
     * @param deniedHandler          无权限出口（403 + 401008）
     * @return 过滤器链
     * @throws Exception 构建失败时抛出（启动即失败，属于配置错误）
     */
    @Bean
    public SecurityFilterChain fazeSecurityFilterChain(HttpSecurity http,
                                                       JwtAuthenticationFilter jwtAuthenticationFilter,
                                                       AdminAuthenticationFilter adminAuthenticationFilter,
                                                       RestAuthenticationEntryPoint entryPoint,
                                                       RestAccessDeniedHandler deniedHandler) throws Exception {
        http
                // 前后端分离 + JWT：CSRF 无意义（没有 cookie 会话可被利用）
                .csrf(AbstractHttpConfigurer::disable)
                // CORS：真机 RN 不受同源限制，生产 profile 下 CorsConfig 不存在 → 此处退化为空配置。
                // dev profile 下由 config/CorsConfig 放开本机 8090（expo web 自测用）。
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC 分派必须放行（第 3 段 C3 的 SSE）。
                        // 为什么：SseEmitter 完成/超时后容器会做一次 DispatcherType.ASYNC 的重新分派，
                        // 此时 JwtAuthenticationFilter 跳过、SecurityContext 已清，若这里不放行，
                        // 授权链会以「未认证」拒绝这次内部分派，日志刷出 AuthorizationDeniedException
                        // 与 "Cannot render error page"（数据不受影响，但日志被污染，且看起来像 401）。
                        // 只放行「异步分派」这一种 dispatcher type，普通 REQUEST 仍走下面的 authenticated()。
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        // pub：公开接口（ping / 登录 / 探针），探针的「仅非生产」由 @Profile 控制而非这里
                        .requestMatchers("/api/v1/pub/**").permitAll()
                        // /error：容器内部转发用，必须放行，否则异常响应会被 401 覆盖
                        .requestMatchers("/error").permitAll()
                        // 刷新令牌必须放行：调用它的前提就是 access token 已过期，
                        // 若落进下面的 authenticated() 就永远刷不了（客户端会卡在 401）。
                        // 它的「鉴权」是请求体里的 refreshToken，由 AuthService 校验。
                        .requestMatchers(HttpMethod.POST, "/api/v1/app/auth/refresh").permitAll()
                        // 律师浏览（G3.1/G3.2）：**找律师对游客开放**。
                        // 首页与「找律师」入口在未登录时也要能点开，否则游客看到的是 401。
                        // 只放行 GET 的浏览路径；同前缀下的律师侧写接口（工作台等）仍走下面的 authenticated。
                        .requestMatchers(HttpMethod.GET, "/api/v1/app/lawyers", "/api/v1/app/lawyers/*",
                                "/api/v1/app/lawyers/*/reviews").permitAll()
                        // WebSocket 握手（G6.2）：握手请求没有 Authorization 头（token 在 query），
                        // 放到过滤器链里必然 401。真正的鉴权在 ChatHandshakeInterceptor：
                        // 校验失败直接拒绝握手（403），不存在「连上但未认证」的连接。
                        .requestMatchers("/ws/**").permitAll()
                        // app：需 access token，是 0-A2 验 401 的唯一载体
                        .requestMatchers("/api/v1/app/**").authenticated()
                        // admin：管理端写接口（第 1 段 D4 起）。用 hasRole 而不是 authenticated，
                        // 否则任何普通用户拿自己的 token 就能调充值接口
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 默认拒绝：未规划的后缀一律不通
                        .anyRequest().denyAll())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 管理端认证桥（第 1 段 F1，方案 A）：校验 RuoYi 签发的 token 并还原 ROLE_ADMIN。
                // 必须排在 JwtAuthenticationFilter 之后：两者靠各自的 shouldNotFilter 互斥
                // （C 端 token 是 HS256 自签、管理端是 HS512 由 RuoYi 签），顺序固定便于排障。
                .addFilterAfter(adminAuthenticationFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 密码编码器。
     *
     * <p>用 BCrypt（`$2b$` 前缀，60 字符）而不是 MD5/SHA：项目内 `app_user.password_hash`
     * 声明为 VARCHAR(100)，数据库种子里的既有哈希也是 `$2b$10$` 格式，换算法会让存量用户全部登不上。</p>
     *
     * <p>强度 10 是 BCrypt 默认值，本机单次校验约 60~100ms；调高到 12 会让登录接口在
     * 压测时成为瓶颈，段 0 不调。</p>
     *
     * @return BCrypt 编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
