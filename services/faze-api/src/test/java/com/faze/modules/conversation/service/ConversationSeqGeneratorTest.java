/*
 * 职责：ConversationSeqGenerator 的单测——会话 seq 生成「Redis INCR 与库内最大值取 max」的对齐规则。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 ConversationMessageMapper / StringRedisTemplate。
 * 注意：
 *   1. 这条规则是消息表唯一键 `uk_msg_conversation_seq` 的最后一道防线：
 *      Redis 重启 / 被清后 INCR 从 1 重新开始，不取 max 就会撞键。
 *   2. 对齐后必须**把 Redis 写回对齐值**，否则后续每条消息都会再对齐一次。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话序号生成器测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationSeqGeneratorTest {

    private static final Long CONVERSATION_ID = 9004L;

    @Mock
    private ConversationMessageMapper messageMapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ConversationSeqGenerator seqGenerator;

    /**
     * 注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConversationMessage.class);
    }

    @BeforeEach
    void setUp() {
        seqGenerator = new ConversationSeqGenerator(messageMapper, redis);
        when(redis.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * 造一条已有消息（只关心 seq）。
     *
     * @param seq 序号
     * @return 消息实体
     */
    private ConversationMessage message(long seq) {
        ConversationMessage message = new ConversationMessage();
        message.setId(1L);
        message.setConversationId(CONVERSATION_ID);
        message.setSeq(seq);
        return message;
    }

    /**
     * 正常：Redis INCR 大于库内最大值，直接用 INCR。
     */
    @Test
    @DisplayName("next：INCR 正常递增时直接使用")
    void nextUsesIncr() {
        when(valueOperations.increment(anyString())).thenReturn(3L);
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message(2L));

        assertEquals(3L, seqGenerator.next(CONVERSATION_ID));
        // 不需要回写 Redis
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    /**
     * Redis 落后于库内最大值：对齐到 dbMax+1，并把 Redis 写回。
     */
    @Test
    @DisplayName("next：Redis 落后时取库内最大值 +1 并对齐 Redis")
    void nextAlignsStaleSeq() {
        when(valueOperations.increment(anyString())).thenReturn(2L);
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message(42L));

        assertEquals(43L, seqGenerator.next(CONVERSATION_ID));
        verify(valueOperations).set(eq("faze:conv:seq:" + CONVERSATION_ID), eq("43"));
    }

    /**
     * 会话无消息：库内最大 seq 视为 0。
     */
    @Test
    @DisplayName("max：无消息返回 0")
    void maxWithoutMessage() {
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertEquals(0L, seqGenerator.max(CONVERSATION_ID));
    }
}
