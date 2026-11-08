/*
 * 职责：未认证请求的统一出口——按前缀分两种契约输出（接口规范 §6.2 裁决 9）。
 * 调用方：Spring Security 的 ExceptionTranslationFilter（未通过认证的受保护请求）。
 * 数据来源：request attribute（由 JwtAuthenticationFilter / AdminAuthenticationFilter 写入）。
 * 注意：
 *   1. **C 端（`/app`、`/pub`）必须回 HTTP 401**（不是 200）。App 端按 HTTP 状态分流，
 *      若回 200 + code=402002，RN 的拦截器不会触发刷新逻辑。
 *   2. **管理端（`/api/v1/admin/**`）必须回 HTTP 200 + `{code:401, msg:"登录状态已过期"}`**。
 *      这是全项目唯一的 HTTP 语义例外，理由是实测：RuoYi-Vue3 3.9.2 的 `utils/request.js`
 *      第 78 行取 `res.data.code`、第 85 行判 `code === 401` 才弹重新登录；
 *      它的 HTTP 非 2xx 分支（第 111–123 行）只弹「系统接口 XXX 异常」，**没有任何跳登录逻辑**。
 *      所以管理端若回 HTTP 401，用户会看到一句报错然后页面卡死。
 *      代价：网关/APM 里管理端鉴权失败显示为 200，监控必须按 `code=401` 单独统计。
 *   3. 管理端的 `code` 是**三位** 401，不是六位业务码——这是为了对上 RuoYi 前端的判断，
 *      **不要**「统一」成 402002。
 *   4. 必须回 JSON：Spring Security 默认出口会吐 HTML 或空体，客户端解析不了。
 *   5. 不写 `e.getMessage()`：认证异常的消息里可能带 token 片段。
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

    /** 管理端鉴权失败的业务码：三位 401，只对 RuoYi 前端契约负责（裁决 9）。 */
    static final int ADMIN_CODE_UNAUTHORIZED = 401;
    /** 管理端鉴权失败的提示文案（RuoYi 前端只拿它做通知，不拿它做分支）。 */
    static final String ADMIN_MSG_UNAUTHORIZED = "登录状态已过期";

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
     * 按前缀选契约输出未认证响应。
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param authException 触发本次出口的认证异常（**内容不返回客户端**）
     * @throws IOException 写响应失败时抛出（连接已断等）
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (AdminAuthenticationFilter.isAdminPath(request.getRequestURI())) {
            writeAdminUnauthorized(request, response);
            return;
        }

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

    /**
     * 管理端出口：HTTP 200 + `{code:401}`（裁决 9，见类注释第 2 条）。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @throws IOException 写响应失败时抛出
     */
    private void writeAdminUnauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.debug("管理端未认证 path={} failure={}", request.getRequestURI(),
                request.getAttribute(AdminAuthenticationFilter.ATTR_FAILURE));

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String traceId = MDC.get(R.TRACE_ID_MDC_KEY);
        if (traceId != null) {
            response.setHeader(TraceIdFilter.HEADER_TRACE_ID, traceId);
        }
        R<Void> body = R.ok();
        body.setCode(ADMIN_CODE_UNAUTHORIZED);
        body.setMsg(ADMIN_MSG_UNAUTHORIZED);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
