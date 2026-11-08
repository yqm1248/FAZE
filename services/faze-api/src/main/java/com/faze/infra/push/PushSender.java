/*
 * 职责：离线推送通道的统一接口（G7.1 的抽象层）。
 * 调用方：modules/conversation/task/PushTask（task_queue 的第二个消费者）。
 * 数据来源：无（实现自己决定走个推 / APNs / 厂商通道）。
 * 注意：
 *   1. **接口先冻结、实现挂账**：`2-A3` 需要真机 + 个推应用凭据（负责人提供，段 1 结束前承诺，
 *      到今天仍未到位）。段 2 只定义接口与调用方，真实通道在凭据到位后补
 *      `GetuiPushSender implements PushSender`，**不改任何调用方代码**。
 *   2. 实现抛 `PushUnavailableException` 表示「通道不可用，可重试」：PushTask 据此走
 *      `task_queue` 的指数退避与死信，这是 G7.2 的验收路径（重试 5 次进死信）。
 *      抛其它 RuntimeException 视为「不可重试的编程错误」，同样进重试流程（保守）。
 *   3. 实现**不许在事务内调用**：推送是远程调用（《后端代码规范》§7.2 的事务五条规则），
 *      调用方必须保证在事务提交之后（PushTask 是独立的事务外消费者）。
 */
package com.faze.infra.push;

/**
 * 离线推送发送器。
 */
public interface PushSender {

    /**
     * 发送一条推送。
     *
     * @param message 推送内容
     * @throws PushUnavailableException 通道未接入或暂时不可用（可重试）
     */
    void send(PushMessage message);
}
