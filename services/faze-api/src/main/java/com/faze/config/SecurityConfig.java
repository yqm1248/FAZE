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

import com.faze.security.JwtAuthenticationFilter;
import com.faze.security.RestAccessDeniedHandler;
import com.faze.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
                                                       RestAuthenticationEntryPoint entryPoint,
                                                       RestAccessDeniedHandler deniedHandler) throws Exception {
        http
                // 前后端分离 + JWT：CSRF 无意义（没有 cookie 会话可被利用）
                .csrf(AbstractHttpConfigurer::disable)
                // 段 0 不跨域：RN 原生请求没有 Origin 限制，PC 后台是独立服务（RuoYi 自己有 CORS 配置）
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // pub：公开接口（ping / 登录 / 探针），探针的「仅非生产」由 @Profile 控制而非这里
                        .requestMatchers("/api/v1/pub/**").permitAll()
                        // /error：容器内部转发用，必须放行，否则异常响应会被 401 覆盖
                        .requestMatchers("/error").permitAll()
                        // 刷新令牌必须放行：调用它的前提就是 access token 已过期，
                        // 若落进下面的 authenticated() 就永远刷不了（客户端会卡在 401）。
                        // 它的「鉴权」是请求体里的 refreshToken，由 AuthService 校验。
                        .requestMatchers(HttpMethod.POST, "/api/v1/app/auth/refresh").permitAll()
                        // app：需 access token，是 0-A2 验 401 的唯一载体
                        .requestMatchers("/api/v1/app/**").authenticated()
                        // 默认拒绝：未规划的后缀一律不通
                        .anyRequest().denyAll())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
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
