/*
 * 职责：表示「推送通道暂不可用」的受检失败（可重试）。
 * 调用方：infra/push/PushSender 的实现、modules/conversation/task/PushTask。
 * 数据来源：无。
 * 注意：
 *   1. 继承 RuntimeException 而不是受检异常：调用链上有 `@Scheduled` 与 task_queue 消费者，
 *      受检异常会逼着每个调用方写 try/catch，最终被某个 catch 吞掉；运行时异常由
 *      统一的失败处理（markFailed → 退避 → 死信）接住，路径唯一。
 *   2. **消息里不许带凭据**（appId / masterSecret 一律不进异常消息，日志规范禁止）。
 */
package com.faze.infra.push;

/**
 * 推送通道不可用。
 */
public class PushUnavailableException extends RuntimeException {

    /**
     * 构造。
     *
     * @param message 失败原因（不含凭据）
     */
    public PushUnavailableException(String message) {
        super(message);
    }
}
