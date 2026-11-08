/*
 * 职责：已认证但无权限的请求的统一出口——C 端回 HTTP 403 + {code:401008}，管理端回 HTTP 200 + 同码。
 * 调用方：Spring Security 的 ExceptionTranslationFilter（AccessDeniedException）。
 * 数据来源：无（恒定错误码）。
 * 注意：
 *   1. 与 401 的区别是硬语义：「你是谁我不知道」→ 401；「我知道你是谁，但你不能做这件事」→ 403。
 *      混用会让客户端在「刷新 token」和「提示无权限」之间做错选择。
 *   2. **管理端必须回 HTTP 200**（接口规范附录 C 第 4 行）：RuoYi 前端对 `code !== 200`
 *      一律走 `ElNotification.error(msg)`，它不需要 HTTP 非 2xx 来表达「无权限」，
 *      而 HTTP 403 会落进它的「系统接口403异常」分支，把六位业务码的 msg 盖掉。
 *      所以管理端这里是 HTTP 200 + `{code:401008, msg:"无权限访问该资源"}`。
 *   3. 不返回 `e.getMessage()`（可能含表达式、角色名等内部信息）。
 */
package com.faze.security;

import com.faze.common.exception.ErrorCode;
import com.faze.common.R;
import com.faze.filter.TraceIdFilter;
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
     * 按前缀选 HTTP 状态输出无权限响应。
     *
     * @param request               当前请求
     * @param response              当前响应
     * @param accessDeniedException 拒绝原因（**内容不返回客户端**）
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        boolean adminPath = AdminAuthenticationFilter.isAdminPath(request.getRequestURI());
        log.warn("无权限 path={} adminPath={} reason={}",
                request.getRequestURI(), adminPath, accessDeniedException.getMessage());

        response.setStatus(adminPath ? HttpServletResponse.SC_OK : HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = MDC.get(R.TRACE_ID_MDC_KEY);
        if (traceId != null) {
            response.setHeader(TraceIdFilter.HEADER_TRACE_ID, traceId);
        }
        jsonMapper.writeValue(response.getOutputStream(), R.<Void>fail(ErrorCode.COMMON_FORBIDDEN));
    }
}
