/*
 * 职责：上游 AI 调用失败的统一异常。对外只暴露 508001（AI 服务不可用），**不带任何上游原文**。
 * 调用方：infra/ai/impl/DoubaoClientImpl（抛出）、modules/ai/service/AiStreamService（捕获后按 C4 分支处理）。
 * 数据来源：ErrorCode.AI_SERVICE_DOWN（508001，HTTP 200）。
 * 注意：
 *   1. 继承 `BusinessException` 以便 GlobalExceptionHandler 的通用分支按错误码返回；
 *      但 C3 的 SSE 场景不走全局处理器（响应已经是 event-stream），由 AiStreamService 自己发 `event: error`。
 *   2. `detail` 只进日志，允许写「上游状态码 / 异常类名 / 耗时」，**禁止写上游响应体与回答原文**。
 *   3. `retryable` 只给客户端内部的重试策略用：5xx / 网络错 true，4xx false。
 */
package com.faze.infra.ai;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;

/**
 * 上游 AI 调用失败（统一映射为 508001）。
 */
public class ApiException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /** 是否值得重试（5xx / 网络错 true；4xx false）。 */
    private final transient boolean retryable;

    /**
     * 构造。
     *
     * @param detail    日志用的补充信息（禁止上游原文与回答原文）
     * @param retryable 是否值得重试
     */
    public ApiException(String detail, boolean retryable) {
        super(ErrorCode.AI_SERVICE_DOWN, detail);
        this.retryable = retryable;
    }

    /**
     * 是否值得重试。
     *
     * @return true 表示 5xx / 网络错，可指数退避重试
     */
    public boolean isRetryable() {
        return retryable;
    }
}
