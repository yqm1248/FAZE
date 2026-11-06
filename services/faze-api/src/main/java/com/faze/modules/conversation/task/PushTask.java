/*
 * 职责：离线推送任务的消费者（G7.2，task_queue 的第二个消费者）。
 * 调用方：Spring `@Scheduled`（主类已 `@EnableScheduling`，调度线程池在 ThreadPoolConfig/配置里）。
 * 数据来源：task_queue（`task_type='push'`，`biz_no` = conversation_message.id）、
 *          conversation_message / conversation / lawyer（组装推送内容）、PushSender（发送）。
 * 注意：
 *   1. **推送不在消息事务内发**（《后端代码规范》§7.2）：消息落库后由 MessagePushService
 *      往 task_queue 入队，本类在独立的事务外消费——远程调用不许挂在事务里。
 *   2. **失败走退避重试**：`TaskQueueService#markFailed` 按 2^retry 分钟退避，
 *      `retry_count >= 5` 进死信（`status='dead'`，PC A-26 可见；段 2 只落数据不做页面）。
 *   3. **同一消息只推一次**：入队前 `hasActiveTask(push, messageId)` 判重；
 *      消费时若对端已重连在线，直接标完成**不推**（用户已经在看聊天页，再弹通知是骚扰）。
 *   4. **推送内容不含聊天正文**（技术路线 §7.5）：只带「您有一条新消息」。
 *   5. 消息/会话不存在时**标完成**而不是失败：重试也不会变出数据，只会把死信表塞满。
 *   6. 只领 `push` 类型任务（`claimBatch(worker, types)`）：不筛类型会与订单扫描器互相抢任务。
 */
package com.faze.modules.conversation.task;

import com.faze.infra.push.PushMessage;
import com.faze.infra.push.PushSender;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import com.faze.modules.conversation.service.OnlineRegistry;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.entity.TaskQueue;
import com.faze.modules.trade.service.TaskQueueService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 离线推送任务消费者。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PushTask {

    /** 扫描间隔（毫秒）：15 秒。推送比关单更敏感，间隔更短。 */
    private static final long SCAN_INTERVAL_MS = 15_000L;

    /** 首次扫描延迟（毫秒）。 */
    private static final long INITIAL_DELAY_MS = 20_000L;

    /** 本消费者标识。 */
    private static final String WORKER_NAME = "offline-push";

    /** 通知标题配置键。 */
    public static final String CONFIG_TITLE = "push.message_title";

    /** 通知正文配置键。 */
    public static final String CONFIG_BODY = "push.message_body";

    /** 默认标题（不含聊天内容）。 */
    private static final String DEFAULT_TITLE = "您有一条新消息";

    /** 默认正文。 */
    private static final String DEFAULT_BODY = "点击查看详情";

    private final TaskQueueService taskQueueService;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationMapper conversationMapper;
    private final LawyerMapper lawyerMapper;
    private final OnlineRegistry onlineRegistry;
    private final PushSender pushSender;
    private final SysConfigQueryService sysConfigQueryService;

    /**
     * 启动时登记日志（`@Scheduled` 没生效时不会有任何报错，这行是唯一可观测点）。
     */
    @jakarta.annotation.PostConstruct
    void logRegistered() {
        log.info("离线推送消费者已注册：fixedDelay={}ms initialDelay={}ms worker={}",
                SCAN_INTERVAL_MS, INITIAL_DELAY_MS, WORKER_NAME);
    }

    /**
     * 扫描并执行到期的推送任务。
     *
     * 副作用：领取任务、调用推送通道、更新任务状态。
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    public void scan() {
        List<TaskQueue> tasks;
        try {
            tasks = taskQueueService.claimBatch(WORKER_NAME, List.of(TaskQueueService.TYPE_PUSH));
        } catch (RuntimeException e) {
            log.error("领取推送任务失败，本轮跳过", e);
            return;
        }
        if (tasks.isEmpty()) {
            return;
        }
        String owner = taskQueueService.ownerOf(WORKER_NAME);
        for (TaskQueue task : tasks) {
            try {
                handle(task);
                taskQueueService.markDone(task, owner);
            } catch (RuntimeException e) {
                log.error("推送任务失败 taskId={} bizNo={}", task.getId(), task.getBizNo(), e);
                taskQueueService.markFailed(task, owner,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 执行一条推送任务。
     *
     * @param task 任务（bizNo = messageId）
     * @throws RuntimeException 推送失败（交给 markFailed 退避重试）
     */
    void handle(TaskQueue task) {
        Long messageId = parseMessageId(task.getBizNo());
        if (messageId == null) {
            log.warn("推送任务 bizNo 不是消息 id，跳过 taskId={} bizNo={}", task.getId(), task.getBizNo());
            return;
        }
        ConversationMessage message = conversationMessageMapper.selectById(messageId);
        if (message == null) {
            log.warn("推送任务对应的消息不存在，标记完成 messageId={}", messageId);
            return;
        }
        Conversation conversation = conversationMapper.selectById(message.getConversationId());
        if (conversation == null) {
            log.warn("推送任务对应的会话不存在，标记完成 conversationId={}", message.getConversationId());
            return;
        }
        Long receiverUserId = resolveReceiver(message, conversation);
        if (receiverUserId == null) {
            log.info("无法确定推送接收方（系统消息或律师账号缺失），跳过 messageId={}", messageId);
            return;
        }
        if (onlineRegistry.isOnline(receiverUserId)) {
            // 用户已经重连并在线：客户端会走 message_offline 补推，不必再弹系统通知
            log.info("接收方已在线，跳过系统推送 userId={} messageId={}", receiverUserId, messageId);
            return;
        }

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("conversationId", String.valueOf(message.getConversationId()));
        payload.put("messageId", String.valueOf(messageId));
        String title = sysConfigQueryService.getString(CONFIG_TITLE, DEFAULT_TITLE);
        String body = sysConfigQueryService.getString(CONFIG_BODY, DEFAULT_BODY);
        pushSender.send(new PushMessage(receiverUserId, title, body, payload));
        log.info("推送已提交 userId={} messageId={} conversationId={}",
                receiverUserId, messageId, message.getConversationId());
    }

    /**
     * 解析接收方：消息发送者是律师 → 推向用户；是用户 → 推向律师账号。
     *
     * @param message      消息
     * @param conversation 会话
     * @return 接收方 app_user.id；无法确定返回 null
     */
    private Long resolveReceiver(ConversationMessage message, Conversation conversation) {
        SenderRole sender = SenderRole.ofOrNull(message.getSenderRole());
        if (sender == SenderRole.LAWYER) {
            return conversation.getUserId();
        }
        if (sender == SenderRole.USER) {
            Lawyer lawyer = conversation.getLawyerId() == null ? null
                    : lawyerMapper.selectById(conversation.getLawyerId());
            return lawyer == null ? null : lawyer.getUserId();
        }
        return null;
    }

    /**
     * 解析 bizNo 为消息主键。
     *
     * @param bizNo 业务号
     * @return 消息主键；非法返回 null
     */
    private Long parseMessageId(String bizNo) {
        if (bizNo == null || bizNo.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(bizNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
