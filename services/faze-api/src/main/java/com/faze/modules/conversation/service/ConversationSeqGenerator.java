/*
 * 职责：会话内消息序号 `conversation_message.seq` 的唯一生成入口。
 * 调用方：modules/conversation/service/MessageService（发消息 / 文书消息 / 已读重算）、
 *        modules/conversation/service/OrderNoticeService（进入会话时补发订单通知）。
 * 数据来源：Redis `faze:conv:seq:{conversationId}` + `conversation_message.seq` 的库内最大值。
 * 注意：
 *   1. **不能只用 Redis INCR**：Redis 重启 / 数据被清后 seq 会从 1 重新开始，直接 INCR 会与
 *      `uk_msg_conversation_seq` 撞键。取 max(INCR 值, 库内最大 seq + 1) 是唯一安全的写法
 *      （多一次索引查询，可接受）。
 *   2. 所有写消息的路径（用户消息、文书消息、系统通知）都必须走本类取 seq：
 *      自己拼 seq 就是在复制这段约束，早晚漂移。
 *   3. `max()` 只读：已读逻辑用它算「已读上限」，不产生副作用。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话消息序号生成器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationSeqGenerator {

    /** 会话 seq 的 Redis key 前缀。 */
    private static final String SEQ_KEY_PREFIX = "faze:conv:seq:";

    private final ConversationMessageMapper messageMapper;
    private final StringRedisTemplate redis;

    /**
     * 取下一条会话内序号（Redis INCR 与库内最大值取 max）。
     *
     * @param conversationId 会话主键
     * @return 新序号（≥1）
     */
    public long next(Long conversationId) {
        String key = SEQ_KEY_PREFIX + conversationId;
        Long incr = redis.opsForValue().increment(key);
        long candidate = incr == null ? 1L : incr;
        long dbMax = max(conversationId);
        if (candidate <= dbMax) {
            candidate = dbMax + 1;
            redis.opsForValue().set(key, String.valueOf(candidate));
            log.warn("会话 seq 落后于库内最大值，已对齐 conversationId={} incr={} dbMax={}",
                    conversationId, incr, dbMax);
        }
        return candidate;
    }

    /**
     * 取会话当前最大 seq。
     *
     * @param conversationId 会话主键
     * @return 最大 seq；无消息返回 0
     */
    public long max(Long conversationId) {
        ConversationMessage last = messageMapper.selectOne(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId)
                .orderByDesc(ConversationMessage::getSeq)
                .last("LIMIT 1"));
        return last == null || last.getSeq() == null ? 0L : last.getSeq();
    }
}
