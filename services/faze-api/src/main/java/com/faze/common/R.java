/*
 * 职责：统一响应体。所有 Controller 返回值一律包成 R，不返回裸对象、不返回 Map。
 * 调用方：全部 Controller、common/GlobalExceptionHandler、security/RestAuthenticationEntryPoint /
 *         RestAccessDeniedHandler。
 * 数据来源：无（形状按《法泽FAZE_接口规范_v1.0.md》§5.1 逐字实现，**不自己加字段**）。
 * 注意：
 *   1. 字段固定四个：code / msg / data / traceId。data 为 null 时字段**必须保留**（不省略），
 *      否则前端按固定形状解析会炸出 TS 类型错误（§5.1 约束 1）。
 *   2. 业务失败 HTTP 仍为 200，只有鉴权失败（401）与无权限（403）才改变 HTTP 状态。
 *   3. traceId 与响应头 X-Trace-Id 同值，由 TraceIdFilter 写入 MDC；这里只负责读取。
 */
package com.faze.common;

import com.faze.common.exception.ErrorCode;

import jakarta.annotation.Nullable;
import java.io.Serializable;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

/**
 * 统一响应体（code / msg / data / traceId）。
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功码。 */
    public static final int SUCCESS = 200;

    /** MDC 中的 traceId 键名，与 logback-spring.xml 的 %X{traceId}、TraceIdFilter 三处必须一致。 */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 200 或 6 位业务错误码。 */
    private int code;

    /** 开发者可见的调试信息，**客户端禁止直接展示**，必须按 msgKey 走 t()。 */
    private String msg;

    /** 业务数据；无数据时为 null，但字段保留。 */
    private T data;

    /** 服务端链路 id，与响应头 X-Trace-Id 相同。 */
    private String traceId;

    /**
     * 成功响应，无数据。
     *
     * @param <T> 数据类型
     * @return {@code code=200}、{@code data=null} 的响应体
     */
    public static <T> R<T> ok() {
        return build(null, SUCCESS, "ok");
    }

    /**
     * 成功响应，带数据。
     *
     * @param data 业务数据（可为 null）
     * @param <T>  数据类型
     * @return {@code code=200} 的响应体
     */
    public static <T> R<T> ok(@Nullable T data) {
        return build(data, SUCCESS, "ok");
    }

    /**
     * 失败响应，文案取错误码自带的稳定中文。
     *
     * @param e 错误码
     * @param <T> 数据类型
     * @return 对应业务码的响应体（HTTP 状态由调用方/异常处理器决定）
     */
    public static <T> R<T> fail(ErrorCode e) {
        return build(null, e.getCode(), e.getMsg());
    }

    /**
     * 失败响应，用自定义 detail 覆盖默认文案。
     *
     * <p><b>只允许传参数校验的字段级提示</b>（如 {@code "phone: 手机号格式不对"}），
     * 绝不允许传异常原文、SQL、堆栈——那会泄露内部实现（后端规范 §5.3）。</p>
     *
     * @param e      错误码
     * @param detail 给开发者看的补充信息
     * @param <T>    数据类型
     * @return 对应业务码的响应体
     */
    public static <T> R<T> fail(ErrorCode e, String detail) {
        return build(null, e.getCode(), detail);
    }

    /**
     * 构造响应体并自动注入链路 id。
     *
     * <p>副作用：读取 SLF4J MDC 中的 traceId，不修改 MDC。</p>
     */
    private static <T> R<T> build(@Nullable T data, int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        r.setTraceId(MDC.get(TRACE_ID_MDC_KEY));
        return r;
    }
}
