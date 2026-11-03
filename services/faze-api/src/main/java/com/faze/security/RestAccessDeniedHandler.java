/*
 * 职责：已认证但无权限的请求的统一出口——回 HTTP 403 + {code:401008}。
 * 调用方：Spring Security 的 ExceptionTranslationFilter（AccessDeniedException）。
 * 数据来源：无（恒定错误码）。
 * 注意：
 *   1. 与 401 的区别是硬语义：「你是谁我不知道」→ 401；「我知道你是谁，但你不能做这件事」→ 403。
 *      混用会让客户端在「刷新 token」和「提示无权限」之间做错选择。
 *   2. 段 0 没有任何 @PreAuthorize，所以这条分支实际上只会在 `denyAll()` 命中时触发；
 *      仍然要实现它——否则 Security 会退回默认实现，吐 HTML 或空体。
 *   3. 不返回 e.getMessage()（可能含表达式、角色名等内部信息）。
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 无权限出口。
 */
@Component
@Slf4j
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    /**
     * 构造出口。
     *
     * @param jsonMapper Jackson 3 的 Mapper
     */
    public RestAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * 输出 403 响应。
     *
     * @param request               当前请求
     * @param response              当前响应
     * @param accessDeniedException 拒绝原因（**内容不返回客户端**）
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("无权限 path={} reason={}", request.getRequestURI(), accessDeniedException.getMessage());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = MDC.get(R.TRACE_ID_MDC_KEY);
        if (traceId != null) {
            response.setHeader(TraceIdFilter.HEADER_TRACE_ID, traceId);
        }
        jsonMapper.writeValue(response.getOutputStream(), R.<Void>fail(ErrorCode.COMMON_FORBIDDEN));
    }
}
