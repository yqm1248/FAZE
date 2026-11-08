/*
 * 职责：离线消息队列（message_offline）的入队、批量补推读取、推送成功清理与超期清理（G6.6）。
 * 调用方：modules/conversation/service/MessagePushService（入队 + 清理）、
 *        modules/conversation/ws/ChatWebSocketHandler（连接建立时补推）、
 *        modules/conversation/task/OfflineMessageCleanupTask（超期清理）。
 * 数据来源：faze.message_offline。
 * 注意：
 *   1. **入队前先查重**：表上没有唯一键，重复入队会让对端收到重复消息（客户端虽能按 messageId
 *      去重，但每次重连都白推一遍），所以先按 `(user_id, message_id)` 查一次。
 *   2. **补推分页**（每次 50 条）：一次全量会拖慢登录/重连，而且大结果集在 WS 上分帧发送
 *      容易超时。调用方循环直到取空。
 *   3. **推送成功才删**：删早了消息就丢了；删晚了只是重复推送（客户端去重）。
 *      所以顺序永远是「发送 → 删除」。
 *   4. 保留期由 `sys_config.im.offline_retention_days` 给（默认 7 天），清理是定时批量删除，
 *      **不走 task_queue**：补推失败重试没有意义（用户已离线超过保留期，消息仍在
 *      `conversation_message` 里，下次进会话能拉到）。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.conversation.entity.MessageOffline;
import com.faze.modules.conversation.mapper.MessageOfflineMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 离线消息服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfflineMessageService {

    /** 单批补推条数。 */
    public static final int DRAIN_BATCH = 50;

    private final MessageOfflineMapper messageOfflineMapper;

    /**
     * 入队一条离线消息（已入队则跳过）。
     *
     * @param userId         接收方用户
     * @param conversationId 会话主键
     * @param messageId      消息主键
     * @param seq            消息序号
     */
    @Transactional(rollbackFor = Exception.class)
    public void enqueue(Long userId, Long conversationId, Long messageId, Long seq) {
        if (userId == null || messageId == null) {
            return;
        }
        long exists = messageOfflineMapper.selectCount(Wrappers.<MessageOffline>lambdaQuery()
                .eq(MessageOffline::getUserId, userId)
                .eq(MessageOffline::getMessageId, messageId));
        if (exists > 0) {
            return;
        }
        MessageOffline row = new MessageOffline();
        row.setUserId(userId);
        row.setConversationId(conversationId);
        row.setMessageId(messageId);
        row.setSeq(seq);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        messageOfflineMapper.insert(row);
        log.info("离线消息入队 userId={} conversationId={} messageId={} seq={}",
                userId, conversationId, messageId, seq);
    }

    /**
     * 取该用户最早的一批待补推消息（按 seq 升序）。
     *
     * @param userId 接收方用户
     * @return 待补推行；没有返回空列表
     */
    public List<MessageOffline> drainBatch(Long userId) {
        return messageOfflineMapper.selectList(Wrappers.<MessageOffline>lambdaQuery()
                .eq(MessageOffline::getUserId, userId)
                .orderByAsc(MessageOffline::getSeq)
                .last("LIMIT " + DRAIN_BATCH));
    }

    /**
     * 删除单条已推送的离线记录（按业务键，幂等）。
     *
     * @param userId    接收方用户
     * @param messageId 消息主键
     */
    public void remove(Long userId, Long messageId) {
        messageOfflineMapper.delete(Wrappers.<MessageOffline>lambdaQuery()
                .eq(MessageOffline::getUserId, userId)
                .eq(MessageOffline::getMessageId, messageId));
    }

    /**
     * 清理超过保留期的离线记录。
     *
     * @param retentionDays 保留天数（≤0 时按 7 天）
     * @return 删除行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanup(int retentionDays) {
        int days = retentionDays <= 0 ? 7 : retentionDays;
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        int deleted = messageOfflineMapper.delete(Wrappers.<MessageOffline>lambdaQuery()
                .lt(MessageOffline::getCreatedAt, before));
        if (deleted > 0) {
            log.info("清理超期离线消息 retentionDays={} deleted={}", days, deleted);
        }
        return deleted;
    }
}
