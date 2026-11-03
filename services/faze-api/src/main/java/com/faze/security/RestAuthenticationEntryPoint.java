/*
 * 职责：未认证请求的统一出口——回 HTTP 401 + R 体，并按失败原因区分 402002（过期）/ 402003（无效）。
 * 调用方：Spring Security 的 ExceptionTranslationFilter（未通过认证的受保护请求）。
 * 数据来源：request attribute "faze.auth.failure"（由 JwtAuthenticationFilter 写入）。
 * 注意：
 *   1. **必须回 HTTP 401**（不是 200）。《接口规范》§6.2 已裁决：App 端按 HTTP 状态分流，
 *      若回 200 + code=402002，RN 的拦截器不会触发刷新逻辑。
 *   2. **必须回 JSON**。Spring Security 默认的 entry point 会吐 HTML 或空体，客户端解析不了。
 *   3. 没带 token 的匿名请求也走这里（attribute 为空 → 按「凭证无效」处理，不引导刷新）。
 *      理由是：从未登录过的用户不该被客户端塞进刷新流程。
 *   4. 不写 e.getMessage()：认证异常的消息里可能带 token 片段。
 */
package com.faze.security;

import com.faze.common.ErrorCode;
import com.faze.common.R;
import com.faze.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 未认证出口。
 */
@Component
@Slf4j
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    /**
     * 构造出口。
     *
     * @param jsonMapper Jackson 3 的 Mapper（SB4 自动配置提供）
     */
    public RestAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 输出 401 响应。
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param authException 触发本次出口的认证异常（**内容不返回客户端**）
     * @throws IOException 写响应失败时抛出（连接已断等）
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object failure = request.getAttribute(JwtAuthenticationFilter.ATTR_FAILURE);
        ErrorCode code = JwtAuthenticationFilter.FAILURE_EXPIRED.equals(failure)
                ? ErrorCode.AUTH_TOKEN_EXPIRED
                : ErrorCode.AUTH_TOKEN_INVALID;

        log.debug("未认证 path={} code={}", request.getRequestURI(), code.getCode());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 与 TraceIdFilter 写出的响应头同值，保证 body.traceId 与 X-Trace-Id 一致
        String traceId = MDC.get(R.TRACE_ID_MDC_KEY);
        if (traceId != null) {
            response.setHeader(TraceIdFilter.HEADER_TRACE_ID, traceId);
        }
        jsonMapper.writeValue(response.getOutputStream(), R.<Void>fail(code));
    }
}
