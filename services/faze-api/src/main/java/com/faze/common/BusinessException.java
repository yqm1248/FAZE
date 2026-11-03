/*
 * 职责：可预期的业务异常。Service / Controller 遇到「已知的失败原因」时抛它，由全局异常处理器统一转成 R。
 * 调用方：各业务 Service（段 0：modules/account，infra/law）。
 * 数据来源：错误码来自 ErrorCode 枚举。
 * 注意：
 *   1. detail 只进日志，**绝不进响应体**；响应体的 msg 由 GlobalExceptionHandler 决定
 *      （默认用 ErrorCode.msg，参数类异常才用聚合后的字段提示）。
 *   2. 不打印完整堆栈：业务异常是预期内分支，不是故障（后端规范 §5.1）。
 *   3. 禁止在 Controller 里 try/catch 它——那不是它的用法（后端规范 §4）。
 */
package com.faze.common;

import lombok.Getter;

/**
 * 可预期的业务异常。
 *
 * <p>继承 {@link RuntimeException} 而不受检：业务异常穿透 Service 层不该被层层声明；
 * 真正的兜底交给全局异常处理器。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 与之对应的稳定错误码。 */
    private final transient ErrorCode errorCode;

    /** 给开发者看的补充信息（日志用，不进响应）。 */
    private final transient String detail;

    /**
     * 用错误码构造。
     *
     * @param errorCode 错误码，决定响应体 code 与 HTTP 状态
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.detail = null;
    }

    /**
     * 用错误码 + 补充信息构造。
     *
     * @param errorCode 错误码
     * @param detail    日志用的补充信息（禁止放敏感数据）
     */
    public BusinessException(ErrorCode errorCode, String detail) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.detail = detail;
    }
}
