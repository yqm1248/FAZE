/*
 * 职责：**仅非生产 profile** 的 CORS 配置，供 `expo start --web` 在浏览器里自测。
 * 调用方：Spring MVC（注册为 `CorsConfigurationSource`）、`SecurityConfig` 的 `.cors(withDefaults())`。
 * 数据来源：`faze.probe.web.allowed-origins`（默认只放本机 8090 端口）。
 * 注意：
 *   1. **真机 RN 不需要 CORS**：原生 fetch 没有 Origin 限制，段 0 的 A3 决策「不加 CORS」对真机仍然成立。
 *      本类只解决「浏览器里自测」这一个场景，所以加 `@Profile("!prod")`——
 *      生产 profile 下这个 bean 不存在，`.cors(withDefaults())` 退化为空配置，不会放开任何源。
 *   2. 允许的源**只写本机 8090**，不写 `*`：`*` 会让任何网页都能调本地后端。
 *   3. 不加 `allowCredentials`：本项目用 Bearer token，不靠 Cookie。
 */
package com.faze.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 开发期 CORS 配置。
 */
@Configuration
@Profile("!prod")
public class CorsConfig {

    /** 允许的源：expo web dev server 的默认端口 8090（两个本机写法）。 */
    private static final List<String> DEFAULT_ORIGINS = List.of(
            "http://localhost:8090",
            "http://127.0.0.1:8090");

    /**
     * 构造允许的源列表。
     *
     * @param configured 逗号分隔的源（`faze.probe.web.allowed-origins`），为空时用默认列表
     * @return 源列表
     */
    private static List<String> originsOf(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ORIGINS;
        }
        return List.of(configured.split(","));
    }

    /**
     * 注册 CORS 配置源。
     *
     * @param allowedOrigins 允许的源（逗号分隔），来自配置
     * @return CORS 配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${faze.probe.web.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(originsOf(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // 让前端能读到这些**动态响应头**。
        //
        // 前两个是链路与首字节调试头。后两个是 2026-11-11 补的（CorsExposedHeadersTest 的直接产物）：
        //   · X-Pay-Pwd-Remaining —— 支付密码剩余尝试次数。服务端一直在发，但没进暴露列表，
        //     浏览器端的 `fetch` 读不到自定义响应头，所以设计稿要求的「还可试 N 次」永远显示不出来。
        //   · Retry-After            —— 限流/锁定后的等待秒数（标准头，但跨域下同样需要显式暴露）。
        //
        // **新增动态响应头时必须同步加到这里**，否则它的存在对浏览器等同于不存在。
        config.setExposedHeaders(
                List.of("X-Trace-Id", "X-First-Byte-Ms", "X-Pay-Pwd-Remaining", "Retry-After"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
