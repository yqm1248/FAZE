/*
 * 职责：从 HttpServletRequest 里提取项目约定的请求头值（客户端 IP、设备 id）。
 * 调用方：security/JwtAuthenticationFilter（写审计四要素的 ip）、modules/account/AuthService（设备一致性校验）。
 * 数据来源：X-Forwarded-For / X-Real-IP / X-Device-Id 请求头（《接口规范》§4.1 的九个请求头之一）。
 * 注意：
 *   1. **X-Forwarded-For 只取第一段**：它是逗号分隔的代理链，第一段才是客户端；
 *      整串透传会让日志/审计里出现「客户端 IP + 所有代理 IP」这种无法归因的值。
 *   2. 取不到时回退 `getRemoteAddr()`，不要返回 "unknown"——排查时 "127.0.0.1"
 *      至少能判断是本机直连，而 "unknown" 什么信息都没有。
 *   3. 这两个值都**可能被客户端伪造**（没有可信代理时）。段 0 只用于日志与审计留痕，
 *      **不许**拿它们做鉴权或风控判定的唯一依据。
 */
package com.faze.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求头解析工具。
 */
public final class RequestUtils {

    /** 客户端 IP 的请求头（标准代理链）。 */
    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    /** 客户端 IP 的请求头（Nginx 常用）。 */
    private static final String HEADER_REAL_IP = "X-Real-IP";
    /** 设备标识请求头。 */
    private static final String HEADER_DEVICE_ID = "X-Device-Id";

    private RequestUtils() {
    }

    /**
     * 取客户端 IP。
     *
     * @param request 当前请求（非 null）
     * @return X-Forwarded-For 第一段 → X-Real-IP → remoteAddr 的顺序回退值
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader(HEADER_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 取设备标识。
     *
     * @param request 当前请求（非 null）
     * @return X-Device-Id 头原值；不存在时返回 null
     */
    public static String deviceId(HttpServletRequest request) {
        String value = request.getHeader(HEADER_DEVICE_ID);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
