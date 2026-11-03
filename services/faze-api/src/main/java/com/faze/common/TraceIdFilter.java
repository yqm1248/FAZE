/*
 * 职责：给每个请求生成/透传链路 id，写入 MDC 与响应头，使 R.traceId、日志 pattern、响应头三者同值。
 * 调用方：Spring 容器自动注册（@Component），必须先于 Spring Security 过滤器链执行。
 * 数据来源：请求头 X-Request-Id（客户端/网关带入，无则生成 UUIDv4）。
 * 注意：
 *   1. 必须在 finally 里 remove MDC，否则线程池复用时 traceId 会串到别的请求上（经典的串链路 bug）。
 *   2. 顺序用 HIGHEST_PRECEDENCE：若跑在 Security 之后，401 响应就拿不到 traceId 响应头了。
 *   3. 只接受「看起来像 id」的入参（长度 8~64、仅字母数字与 . _ -），否则重写。
 *      理由是 X-Request-Id 会进日志与响应头，放任客户端塞长串/换行会造成日志注入。
 */
package com.faze.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路 id 过滤器。
 *
 * <p>进入时：取 {@code X-Request-Id}（合法则复用，不合法则新生成 UUIDv4）→ 写 MDC。</p>
 * <p>返回时：把 MDC 里的同一个值写进响应头 {@code X-Trace-Id} → 清 MDC。</p>
 *
 * <p>副作用：修改请求处理线程的 MDC（进入/退出各一次），并写一个响应头。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 入站请求头：允许调用方自带链路 id，便于跨端串联。 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 出站响应头：必须与响应体 R.traceId 同值。 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 合法 id 的字符集与长度（8~64 位字母数字，允许 . _ -）。 */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(HEADER_REQUEST_ID));
        MDC.put(R.TRACE_ID_MDC_KEY, traceId);
        try {
            // 先写响应头再放行：即使下游抛异常，异常处理器也能带着同一个 traceId 出去
            response.setHeader(HEADER_TRACE_ID, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(R.TRACE_ID_MDC_KEY);
        }
    }

    /**
     * 决定本次请求的 traceId。
     *
     * @param inbound 请求头 X-Request-Id 的原始值，可为 null
     * @return 合法的原值，或新生成的 UUIDv4
     */
    private String resolveTraceId(String inbound) {
        if (inbound != null && SAFE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
