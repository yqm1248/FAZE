/*
 * 职责：PushTask 的单测——接收方推导、在线跳过、消息缺失跳过、通道失败上抛（G7.2）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 TaskQueueService / ConversationMessageMapper / ConversationMapper /
 *          LawyerMapper / OnlineRegistry / PushSender / SysConfigQueryService。
 * 注意：
 *   1. **接收方推导**是这一组的关键：发送者是律师 → 推向会话的用户；发送者是用户 → 推向律师账号。
 *      推错人比不推更糟（把 A 的聊天提醒发给 B）。
 *   2. **在线跳过**：任务延迟执行时用户可能已重连，此时不推——客户端会走 message_offline 补推。
 *   3. **通道失败必须抛出去**：PushTask 的调用方（scan）靠异常走 markFailed/退避/死信；
 *      吞掉异常会让任务全部「成功」，G7.2 的重试与死信永远验不到。
 *   4. 推送内容不带聊天正文（技术路线 §7.5）：用例断言 title/body 来自配置且不含消息内容。
 */
package com.faze.modules.conversation.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.infra.push.PushMessage;
import com.faze.infra.push.PushSender;
import com.faze.infra.push.PushUnavailableException;
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
import com.faze.modules.trade.mapper.TaskQueueMapper;
import com.faze.modules.trade.service.TaskQueueService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 离线推送任务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushTaskTest {

    private static final Long USER_ID = 5001L;
    private static final Long LAWYER_USER_ID = 5002L;
    private static final Long LAWYER_ID = 7003L;
    private static final Long CONVERSATION_ID = 9004L;
    private static final Long MESSAGE_ID = 10005L;

    @Mock
    private TaskQueueService taskQueueService;

    @Mock
    private ConversationMessageMapper conversationMessageMapper;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private LawyerMapper lawyerMapper;

    @Mock
    private OnlineRegistry onlineRegistry;

    @Mock
    private PushSender pushSender;

    @Mock
    private SysConfigQueryService sysConfigQueryService;

    @Mock
    private TaskQueueMapper taskQueueMapper;

    private PushTask pushTask;

    /**
     * 注册 lambda 列名解析所需的 TableInfo（TaskQueueService 被 mock，但实体映射仍要在）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TaskQueue.class);
    }

    @BeforeEach
    void setUp() {
        pushTask = new PushTask(taskQueueService, conversationMessageMapper, conversationMapper,
                lawyerMapper, onlineRegistry, pushSender, sysConfigQueryService);
        when(sysConfigQueryService.getString(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    /**
     * 任务（bizNo = messageId）。
     *
     * @return 任务实体
     */
    private TaskQueue task() {
        TaskQueue task = new TaskQueue();
        task.setId(1L);
        task.setTaskType(TaskQueueService.TYPE_PUSH);
        task.setBizNo(String.valueOf(MESSAGE_ID));
        return task;
    }

    /**
     * 会话。
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
     * 消息。
     *
     * @param role 发送者角色
     * @return 消息实体
     */
    private ConversationMessage message(SenderRole role) {
        ConversationMessage message = new ConversationMessage();
        message.setId(MESSAGE_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setSeq(7L);
        message.setSenderRole(role.getCode());
        message.setSenderId(role == SenderRole.LAWYER ? LAWYER_ID : USER_ID);
        message.setMsgType("text");
        message.setContent("这是聊天正文，不许出现在推送里");
        return message;
    }

    /**
     * 律师发的消息 → 推给会话的用户，且正文不进通知。
     */
    @Test
    @DisplayName("律师发消息：推给会话用户，通知不含正文")
    void pushToUser() {
        when(conversationMessageMapper.selectById(MESSAGE_ID)).thenReturn(message(SenderRole.LAWYER));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(onlineRegistry.isOnline(USER_ID)).thenReturn(false);

        pushTask.handle(task());

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(captor.capture());
        PushMessage message = captor.getValue();
        assertEquals(USER_ID, message.userId());
        assertFalse(message.body().contains("聊天正文"));
        assertEquals(String.valueOf(CONVERSATION_ID), message.payload().get("conversationId"));
        assertEquals(String.valueOf(MESSAGE_ID), message.payload().get("messageId"));
    }

    /**
     * 用户发的消息 → 推给律师账号。
     */
    @Test
    @DisplayName("用户发消息：推给律师账号")
    void pushToLawyer() {
        when(conversationMessageMapper.selectById(MESSAGE_ID)).thenReturn(message(SenderRole.USER));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setUserId(LAWYER_USER_ID);
        when(lawyerMapper.selectById(LAWYER_ID)).thenReturn(lawyer);
        when(onlineRegistry.isOnline(LAWYER_USER_ID)).thenReturn(false);

        pushTask.handle(task());

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(captor.capture());
        assertEquals(LAWYER_USER_ID, captor.getValue().userId());
    }

    /**
     * 接收方已在线：不推（客户端会走补推）。
     */
    @Test
    @DisplayName("接收方已在线：跳过推送")
    void skipWhenOnline() {
        when(conversationMessageMapper.selectById(MESSAGE_ID)).thenReturn(message(SenderRole.LAWYER));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(onlineRegistry.isOnline(USER_ID)).thenReturn(true);

        pushTask.handle(task());

        verify(pushSender, never()).send(any());
    }

    /**
     * 消息不存在：跳过（不抛异常，标记完成）。
     */
    @Test
    @DisplayName("消息不存在：跳过")
    void skipMissingMessage() {
        when(conversationMessageMapper.selectById(MESSAGE_ID)).thenReturn(null);

        pushTask.handle(task());

        verify(pushSender, never()).send(any());
    }

    /**
     * 通道不可用：异常必须上抛（交给 markFailed 退避/死信）。
     */
    @Test
    @DisplayName("通道不可用：异常上抛，不吞掉")
    void pushFailurePropagates() {
        when(conversationMessageMapper.selectById(MESSAGE_ID)).thenReturn(message(SenderRole.LAWYER));
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(onlineRegistry.isOnline(USER_ID)).thenReturn(false);
        org.mockito.Mockito.doThrow(new PushUnavailableException("通道未启用")).when(pushSender).send(any());

        assertThrows(PushUnavailableException.class, () -> pushTask.handle(task()));
    }

    /**
     * bizNo 非法：跳过，不调通道。
     */
    @Test
    @DisplayName("bizNo 非法：跳过")
    void skipInvalidBizNo() {
        TaskQueue bad = task();
        bad.setBizNo("not-a-number");

        pushTask.handle(bad);

        verify(pushSender, never()).send(any());
        assertTrue(true);
    }
}
