/*
 * 职责：ConversationService 的单测——成员校验（含 403）、创建或获取（含购买准入）、两端口径的列表 VO。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 ConversationMapper / LawyerMapper / AccountMapper / OrderQueryService / OrderNoticeService。
 * 注意：
 *   1. **越权是 IM 域最常见的漏洞**：非成员必须 404002（HTTP 403），且「会话不存在」与
 *      「不是成员」要能区分（前者 404001）——混成一个码会让用户以为会话被删了。
 *   2. 「创建或获取」的核心断言：已存在时**不插新行**，直接返回已有会话。
 *   3. **新建会话有购买准入**：没有有效订单必须 404005 且不插行；律师内部通道
 *      （`createOrGetForLawyerInitiated`）不校验（律师履约与用户付款无关）。
 *   4. 律师端列表：当前用户不是律师时返回空页而不是报错（前端不需要为「没入驻」写分支）。
 */
package com.faze.modules.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.enums.SenderRole;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.conversation.vo.ConversationVO;
import com.faze.modules.trade.service.OrderQueryService;
import java.util.List;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationServiceTest {

    private static final Long USER_ID = 5001L;
    private static final Long LAWYER_USER_ID = 5002L;
    private static final Long LAWYER_ID = 7003L;
    private static final Long CONVERSATION_ID = 9004L;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private LawyerMapper lawyerMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private OrderQueryService orderQueryService;

    @Mock
    private OrderNoticeService orderNoticeService;

    private ConversationService conversationService;

    /**
     * 注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, Lawyer.class);
        TableInfoHelper.initTableInfo(assistant, AppUser.class);
    }

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationMapper, lawyerMapper, accountMapper,
                orderQueryService, orderNoticeService);
    }

    /**
     * 造一条会话。
     *
     * @return 会话实体
     */
    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setLawyerId(LAWYER_ID);
        conversation.setUnreadUser(0);
        conversation.setUnreadLawyer(0);
        return conversation;
    }

    /**
     * 造一个律师行。
     *
     * @param lawyerId 律师主键
     * @param userId   关联账号
     * @return 律师实体
     */
    private Lawyer lawyer(Long lawyerId, Long userId) {
        Lawyer lawyer = new Lawyer();
        lawyer.setId(lawyerId);
        lawyer.setUserId(userId);
        lawyer.setRealName("验收律师甲");
        lawyer.setAuditStatus("approved");
        return lawyer;
    }

    /**
     * 会话成员（C 端用户本人）。
     */
    @Test
    @DisplayName("成员校验：会话的 user 本人 → USER 角色")
    void assertMemberAsUser() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation());
        when(lawyerMapper.selectById(LAWYER_ID)).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));

        ConversationMember member = conversationService.assertMember(CONVERSATION_ID, USER_ID);

        assertEquals(SenderRole.USER, member.getRole());
        assertEquals(USER_ID, member.getSelfId());
        // 对端推送目标是律师的账号 id，不是 lawyer.id
        assertEquals(LAWYER_USER_ID, member.getPeerUserId());
        assertEquals("lawyer", member.getPeerType());
    }

    /**
     * 会话成员（承办律师）。
     */
    @Test
    @DisplayName("成员校验：承办律师 → LAWYER 角色，selfId 是 lawyer.id")
    void assertMemberAsLawyer() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation());
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));

        ConversationMember member = conversationService.assertMember(CONVERSATION_ID, LAWYER_USER_ID);

        assertEquals(SenderRole.LAWYER, member.getRole());
        assertEquals(LAWYER_ID, member.getSelfId());
        assertEquals(USER_ID, member.getPeerUserId());
        assertEquals("user", member.getPeerType());
    }

    /**
     * 非成员：404002（HTTP 403）。
     */
    @Test
    @DisplayName("成员校验：非成员 → 404002（403），不是 404001")
    void assertMemberForbidden() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation());
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(9999L, 8888L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> conversationService.assertMember(CONVERSATION_ID, 123456L));

        assertEquals(ErrorCode.CONVERSATION_FORBIDDEN, ex.getErrorCode());
        assertEquals(403, ex.getErrorCode().getHttpStatus());
    }

    /**
     * 会话不存在：404001。
     */
    @Test
    @DisplayName("成员校验：会话不存在 → 404001")
    void assertMemberMissing() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> conversationService.assertMember(CONVERSATION_ID, USER_ID));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 创建或获取：已存在直接返回，不插新行。
     */
    @Test
    @DisplayName("创建或获取：已有会话不再插入")
    void createOrGetExisting() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation());

        ConversationVO vo = conversationService.createOrGet(USER_ID, LAWYER_ID);

        assertEquals(CONVERSATION_ID, vo.getId());
        assertEquals("lawyer", vo.getPeerType());
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    /**
     * 创建或获取：律师不可见 → 401004。
     */
    @Test
    @DisplayName("创建或获取：律师不可见 → 401004")
    void createOrGetInvisibleLawyer() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> conversationService.createOrGet(USER_ID, LAWYER_ID));

        assertEquals(ErrorCode.COMMON_NOT_FOUND, ex.getErrorCode());
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    /**
     * 创建或获取：没有有效订单 → 404005，且不插会话行。
     */
    @Test
    @DisplayName("创建或获取：未购买该律师服务 → 404005，不建会话")
    void createOrGetWithoutActiveOrder() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(orderQueryService.existsActiveOrderOfUserAndLawyer(USER_ID, LAWYER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> conversationService.createOrGet(USER_ID, LAWYER_ID));

        assertEquals(ErrorCode.CONVERSATION_NOT_PURCHASED, ex.getErrorCode());
        assertEquals(200, ex.getErrorCode().getHttpStatus());
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    /**
     * 创建或获取：有有效订单 → 建会话并补发订单通知。
     */
    @Test
    @DisplayName("创建或获取：有有效订单 → 建会话 + 补发订单通知")
    void createOrGetWithActiveOrder() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(orderQueryService.existsActiveOrderOfUserAndLawyer(USER_ID, LAWYER_ID)).thenReturn(true);
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation row = invocation.getArgument(0);
            row.setId(CONVERSATION_ID);
            return 1;
        });

        ConversationVO vo = conversationService.createOrGet(USER_ID, LAWYER_ID);

        assertEquals(CONVERSATION_ID, vo.getId());
        verify(conversationMapper).insert(any(Conversation.class));
        verify(orderNoticeService).ensureOrderNotices(any(Conversation.class), eq(USER_ID), eq(LAWYER_ID));
    }

    /**
     * 律师内部通道：没有有效订单也能建会话（律师履约不看用户付款）。
     */
    @Test
    @DisplayName("律师内部建会话：跳过购买准入")
    void createOrGetForLawyerInitiatedWithoutOrder() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation row = invocation.getArgument(0);
            row.setId(CONVERSATION_ID);
            return 1;
        });

        ConversationVO vo = conversationService.createOrGetForLawyerInitiated(USER_ID, LAWYER_ID);

        assertEquals(CONVERSATION_ID, vo.getId());
        verify(orderQueryService, never()).existsActiveOrderOfUserAndLawyer(any(), any());
        verify(conversationMapper).insert(any(Conversation.class));
    }

    /**
     * 已存在会话：不再校验订单（退款后历史聊天仍可打开），但要补发通知。
     */
    @Test
    @DisplayName("创建或获取：已有会话不校验订单，仍补发通知")
    void createOrGetExistingWithoutOrderCheck() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(lawyer(LAWYER_ID, LAWYER_USER_ID));
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation());

        conversationService.createOrGet(USER_ID, LAWYER_ID);

        verify(orderQueryService, never()).existsActiveOrderOfUserAndLawyer(any(), any());
        verify(orderNoticeService).ensureOrderNotices(any(Conversation.class), eq(USER_ID), eq(LAWYER_ID));
    }

    /**
     * 律师端列表：当前用户不是律师 → 空页（不报错）。
     */
    @Test
    @DisplayName("律师端列表：非律师返回空页")
    void listForLawyerNotLawyer() {
        when(lawyerMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        var result = conversationService.listForLawyer(USER_ID, 1, 20);

        assertTrue(result.getList().isEmpty());
        assertEquals(0, result.getTotal());
    }

    /**
     * C 端列表：VO 的未读取 unread_user 列。
     */
    @Test
    @DisplayName("C 端列表：未读取用户侧计数")
    void listForUserUnread() {
        Conversation conversation = conversation();
        conversation.setUnreadUser(3);
        conversation.setUnreadLawyer(9);
        when(conversationMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.metadata.IPage<Conversation> page = invocation.getArgument(0);
            page.setRecords(List.of(conversation));
            page.setTotal(1);
            return page;
        });
        when(lawyerMapper.selectBatchIds(any())).thenReturn(List.of(lawyer(LAWYER_ID, LAWYER_USER_ID)));

        var result = conversationService.listForUser(USER_ID, 1, 20);

        assertEquals(1, result.getList().size());
        assertEquals(3, result.getList().get(0).getUnread().intValue());
        assertEquals("验收律师甲", result.getList().get(0).getPeerName());
    }
}
