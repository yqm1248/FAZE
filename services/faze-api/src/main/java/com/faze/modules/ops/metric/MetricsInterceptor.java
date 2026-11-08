/*
 * 职责：HTTP 指标拦截器——每次请求记录 `faze_http_requests_total` 与
 *       `faze_http_request_duration_seconds`，path 标签取路由模板而不是实际 URL。
 * 调用方：config/MetricsConfig 注册到 Spring MVC 拦截器链。
 * 数据来源：HandlerMapping 的 BEST_MATCHING_PATTERN_ATTRIBUTE + 响应状态码。
 * 注意：
 *   1. **必须用模板**：`request.getRequestURI()` 会把 `/api/v1/admin/users/10284` 原样写进标签，
 *      每个 id 一条时间序列（千万级 id 直接拖垮 Prometheus）。模板属性在 preHandle 时可能还没设置，
 *      所以只在 afterCompletion 读取（此时 handler 已匹配完）。
 *   2. **跳过 `/actuator/**`**：Prometheus 每 15 秒抓一次 `/actuator/prometheus`，
 *      若把抓取本身记进指标，`faze_http_requests_total` 会以固定速率自增，
 *      「打 10 次接口后增长 10」这条验收就没法验了。
 *   3. **未匹配路由的 path 统一写 `UNKNOWN`**：404 扫描（攻击或爬虫）会带无数随机路径，
 *      不收敛就等于让任何人往我们的时间序列里灌数据。
 *   4. afterCompletion 在异常请求上也会执行（Spring 保证），所以 500 能被计入。
 *   5. 不记录 query string、不记录用户 id：标签基数必须可控。
 */
package com.faze.modules.ops.metric;

import com.faze.config.MetricsConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * HTTP 指标拦截器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsInterceptor implements HandlerInterceptor {

    /** 请求属性：进入时间（纳秒）。 */
    private static final String ATTR_START_NANOS = MetricsConfig.class.getName() + ".startNanos";

    private final FazeMetrics fazeMetrics;

    /**
     * 记录开始时间。
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @return 永远 true（不拦截请求）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        return true;
    }

    /**
     * 请求结束时打点。
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @param ex       异常（正常为 null）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/actuator/")) {
            return;
        }
        String path = resolvePath(request);
        String method = request.getMethod();
        fazeMetrics.recordHttpRequest(path, method, response.getStatus());
        Object start = request.getAttribute(ATTR_START_NANOS);
        if (start instanceof Long startNanos) {
            fazeMetrics.recordHttpDuration(path, method, System.nanoTime() - startNanos);
        }
    }

    /**
     * 取路由模板；取不到时收敛为 UNKNOWN。
     *
     * @param request 请求
     * @return 模板路径（如 `/api/v1/admin/users/{id}`）
     */
    private String resolvePath(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String text && !text.isBlank()) {
            return text;
        }
        return FazeMetrics.UNKNOWN_PATH;
    }
}
