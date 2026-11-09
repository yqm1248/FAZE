/*
 * 职责：MessageService 的单测——发送（幂等 / seq / 摘要）、历史游标、已读重算、撤回四条校验。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 ConversationMapper / ConversationMessageMapper / ConversationService /
 *          ConversationSeqGenerator（seq 生成）。
 * 注意：
 *   1. **seq 规则已抽到 `ConversationSeqGenerator`**（Redis INCR 与库内最大值取 max）：
 *      它的对齐用例在 `ConversationSeqGeneratorTest`；本类只断言「用的是生成器给的 seq」。
 *   2. 撤回必须验「超过 2 分钟被拒」「撤回他人消息被拒」——这两个是 G6.4 的验收判据。
 *   3. 幂等命中（同 clientMsgId）必须返回已有消息且 `duplicated=true`，**不插第二行**。
 *   4. 已读后未读数按消息表重算（不是简单清零）。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.mapper.ConversationMapper;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceTest {

    private static final Long USER_ID = 5001L;
    private static final Long LAWYER_USER_ID = 5002L;
    private static final Long LAWYER_ID = 7003L;
    private static final Long CONVERSATION_ID = 9004L;
    private static final Long MESSAGE_ID = 10005L;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private ConversationMessageMapper messageMapper;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConversationSeqGenerator seqGenerator;

    @Mock
    private com.faze.infra.storage.FileStorage fileStorage;

    private MessageService messageService;

    /**
     * 注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMessage.class);
        TableInfoHelper.initTableInfo(assistant, Lawyer.class);
    }

    @BeforeEach
    void setUp() {
        messageService = new MessageService(conversationMapper, messageMapper, conversationService,
                seqGenerator, fileStorage);
    }

    /**
     * 造会话。
     *
     * @return 会话实体
     */
    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setLawyerId(LAWYER_ID);
        return conversation;
    }

    /**
     * 造成员关系（C 端用户）。
     *
     * @return 成员关系
     */
    private ConversationMember userMember() {
        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setUserId(LAWYER_USER_ID);
        return new ConversationMember(conversation(), SenderRole.USER, USER_ID, LAWYER_USER_ID, "lawyer", lawyer);
    }

    /**
     * 造一条已有消息。
     *
     * @param minutesAgo 距现在多少分钟前创建
     * @return 消息实体
     */
    private ConversationMessage message(int minutesAgo) {
        ConversationMessage message = new ConversationMessage();
        message.setId(MESSAGE_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setSeq(7L);
        message.setClientMsgId("c-1");
        message.setSenderRole(SenderRole.USER.getCode());
        message.setSenderId(USER_ID);
        message.setMsgType("text");
        message.setContent("你好");
        message.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo));
        return message;
    }

    /**
     * 正常发送：seq 取自生成器，落库并回写会话摘要。
     */
    @Test
    @DisplayName("发送：落库 + 回写摘要，duplicated=false")
    void sendText() {
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(seqGenerator.next(CONVERSATION_ID)).thenReturn(3L);
        when(messageMapper.insert(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage row = invocation.getArgument(0);
            row.setId(MESSAGE_ID);
            return 1;
        });

        MessageService.SendOutcome outcome = messageService.send(USER_ID, CONVERSATION_ID, "text",
                "  你好律师  ", "c-abc", null, null, null);

        assertFalse(outcome.duplicated());
        assertEquals(3L, outcome.message().getSeq());
        assertEquals("你好律师", outcome.message().getContent());
        verify(messageMapper).insert(any(ConversationMessage.class));
        verify(conversationService).touchLastMessage(any(), anyString(), any(), any());
    }

    /**
     * 幂等命中：同 clientMsgId 不再插入，返回已有消息。
     */
    @Test
    @DisplayName("发送：clientMsgId 重复 → duplicated=true，不插新行")
    void sendDuplicated() {
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());
        when(messageMapper.selectOne(any(Wrapper.class))).thenReturn(message(1));

        MessageService.SendOutcome outcome = messageService.send(USER_ID, CONVERSATION_ID, "text",
                "重发的同一句", "c-1", null, null, null);

        assertTrue(outcome.duplicated());
        assertEquals(MESSAGE_ID, outcome.message().getId());
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
    }

    /**
     * 空文本：401002。
     */
    @Test
    @DisplayName("发送：空文本 → 401002")
    void sendBlankText() {
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> messageService.send(USER_ID, CONVERSATION_ID, "text", "   ", "c-empty", null, null, null));

        assertEquals(ErrorCode.COMMON_PARAM_MISSING, ex.getErrorCode());
    }

    /**
     * 非法类型（含 system）：401001。
     */
    @Test
    @DisplayName("发送：客户端发 system 类型 → 401001")
    void sendSystemTypeRejected() {
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> messageService.send(USER_ID, CONVERSATION_ID, "system", "系统消息", "c-sys", null, null, null));

        assertEquals(ErrorCode.COMMON_PARAM_INVALID, ex.getErrorCode());
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
    }

    /**
     * 历史：按 seq 升序返回，且 hasMore 正确。
     */
    @Test
    @DisplayName("历史：升序返回并按需截断")
    void historyAscending() {
        ConversationMessage first = message(2);
        first.setId(1L);
        first.setSeq(10L);
        ConversationMessage second = message(1);
        second.setId(2L);
        second.setSeq(11L);
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(second, first));

        var result = messageService.history(USER_ID, CONVERSATION_ID, null, 20);

        assertEquals(2, result.getList().size());
        assertEquals(10L, result.getList().get(0).getSeq().longValue());
        assertEquals(11L, result.getList().get(1).getSeq().longValue());
        assertFalse(result.isHasMore());
    }

    /**
     * 撤回超过 2 分钟：401006。
     */
    @Test
    @DisplayName("撤回：超过 2 分钟 → 401006")
    void recallTooLate() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message(5));
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> messageService.recall(USER_ID, MESSAGE_ID));

        assertEquals(ErrorCode.COMMON_ILLEGAL_STATE, ex.getErrorCode());
        verify(messageMapper, never()).update(any(), any(Wrapper.class));
    }

    /**
     * 撤回他人消息：403。
     */
    @Test
    @DisplayName("撤回：不是发送者本人 → 404002（403）")
    void recallOthersMessage() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message(1));
        // 当前用户是律师（会话的 lawyer 侧），但消息是 user 发的
        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setUserId(LAWYER_USER_ID);
        when(conversationService.assertMember(CONVERSATION_ID, LAWYER_USER_ID))
                .thenReturn(new ConversationMember(conversation(), SenderRole.LAWYER, LAWYER_ID, USER_ID, "user", lawyer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> messageService.recall(LAWYER_USER_ID, MESSAGE_ID));

        assertEquals(ErrorCode.CONVERSATION_FORBIDDEN, ex.getErrorCode());
    }

    /**
     * 正常撤回：只写 recalled_at，出参不回原文。
     */
    @Test
    @DisplayName("撤回：2 分钟内本人消息成功，出参裁掉正文")
    void recallSucceeds() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message(1));
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());

        MessageService.RecallResult result = messageService.recall(USER_ID, MESSAGE_ID);

        assertTrue(result.applied());
        verify(messageMapper).update(any(), any(Wrapper.class));
        var vo = messageService.toVO(result.message(), SenderRole.USER);
        assertTrue(vo.isRecalled());
        assertNull(vo.getContent());
    }

    /**
     * 重复撤回：幂等，applied=false。
     */
    @Test
    @DisplayName("撤回：已撤回的消息再撤回不报错")
    void recallIdempotent() {
        ConversationMessage recalled = message(1);
        recalled.setRecalledAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(recalled);
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());

        MessageService.RecallResult result = messageService.recall(USER_ID, MESSAGE_ID);

        assertFalse(result.applied());
        verify(messageMapper, never()).update(any(), any(Wrapper.class));
    }

    /**
     * 已读：按消息表重算未读，不简单清零。
     */
    @Test
    @DisplayName("已读：lastReadSeq 之后的对端消息计入未读")
    void markReadRecountsUnread() {
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());
        // 库内最大 seq = 20（对齐辅助），未读按消息表重算
        when(seqGenerator.max(CONVERSATION_ID)).thenReturn(20L);
        when(messageMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(messageMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        MessageService.MarkReadResult result = messageService.markRead(USER_ID, CONVERSATION_ID, 18L);

        assertEquals(18L, result.lastReadSeq());
        verify(conversationMapper).update(any(), any(Wrapper.class));
        verify(messageMapper).selectCount(any(Wrapper.class));
    }

    /**
     * 已读：lastReadSeq 超过库内最大值时按最大值处理（不越界）。
     */
    @Test
    @DisplayName("已读：超前游标 clamp 到最大 seq")
    void markReadClamps() {
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());
        when(seqGenerator.max(CONVERSATION_ID)).thenReturn(20L);
        when(messageMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(messageMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        MessageService.MarkReadResult result = messageService.markRead(USER_ID, CONVERSATION_ID, 999L);

        assertEquals(20L, result.lastReadSeq());
    }

    /**
     * 历史：已撤回消息出参不回 content。
     */
    @Test
    @DisplayName("历史：已撤回消息 recalled=true 且无正文")
    void historyHidesRecalledContent() {
        ConversationMessage recalled = message(1);
        recalled.setRecalledAt(LocalDateTime.now(ZoneOffset.UTC));
        when(conversationService.assertMember(CONVERSATION_ID, USER_ID)).thenReturn(userMember());
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of(recalled));

        var result = messageService.history(USER_ID, CONVERSATION_ID, null, 20);

        assertEquals(1, result.getList().size());
        assertTrue(result.getList().get(0).isRecalled());
        assertNull(result.getList().get(0).getContent());
    }
}
