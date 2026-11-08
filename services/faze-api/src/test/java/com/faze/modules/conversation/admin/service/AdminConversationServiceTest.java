/*
 * 职责：AdminConversationService 的单测——PC 管理端查看聊天记录的 seq 游标分页、审计采集、附件签名（G9.2）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 ConversationMapper / ConversationMessageMapper / FileStorage。
 * 注意：
 *   1. **每次查看都留痕**（拍板第 7 项）：聊天原文是 L4 敏感信息，服务方法里必须
 *      `AuditContext.mark(module=conversation, action=CONVERSATION_VIEW, targetType=CONVERSATION,
 *      targetId=会话 id)`，且方法上有同名 `@OperationLog`（切面靠它落库）。
 *   2. **游标口径**：无 beforeSeq 表示从最新开始（游标取 Long.MAX_VALUE）；有 beforeSeq 时取
 *      `seq < beforeSeq`。limit clamp 1~50，一次最多 50 条，**不一次拉全量**。
 *   3. **返回按 seq 升序**、`hasMore` 由服务端算、`nextCursor` 是本批最早一条的 seq；
 *      没有下一页时 CursorResult 会把 nextCursor 置 null。
 *   4. **已撤回消息返回原文**（审计用途），recalled=true 只是标记：管理员看的是原始记录，
 *      不返回原文就失去了审计意义。
 *   5. **附件签名**：库里存的是 OSS key，出参换签名地址；已是 http(s) 的原样直通；
 *      签名失败降级为 null（不能因为一张图打不开就让整个聊天记录接口 500）。
 */
package com.faze.modules.conversation.admin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.CursorResult;
import com.faze.infra.storage.FileStorage;
import com.faze.modules.conversation.admin.vo.AdminChatMessageVO;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.entity.ConversationMessage;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.conversation.mapper.ConversationMessageMapper;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端聊天记录查看服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminConversationServiceTest {

    private static final Long CONVERSATION_ID = 1201L;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private ConversationMessageMapper conversationMessageMapper;

    @Mock
    private FileStorage fileStorage;

    private AdminConversationService adminConversationService;

    /**
     * 注册 lambda 列名解析所需的 TableInfo：不注册时 `Wrappers.lambdaQuery()` 找不到列缓存会直接抛异常。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, ConversationMessage.class);
    }

    /**
     * 手工装配：FileStorage 用 Mock，签名行为由用例逐条指定。
     */
    @BeforeEach
    void setUp() {
        adminConversationService = new AdminConversationService(conversationMapper,
                conversationMessageMapper, fileStorage);
    }

    /**
     * 造一条会话。
     *
     * @return 会话实体
     */
    private Conversation conversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setUserId(5001L);
        conversation.setLawyerId(7003L);
        return conversation;
    }

    /**
     * 造一条消息。
     *
     * @param seq     会话内序号
     * @param content 文本内容
     * @return 消息实体
     */
    private ConversationMessage message(long seq, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setId(seq * 100);
        message.setConversationId(CONVERSATION_ID);
        message.setSeq(seq);
        message.setSenderRole(seq % 2 == 0 ? "lawyer" : "user");
        message.setSenderId(seq % 2 == 0 ? 7003L : 5001L);
        message.setMsgType("text");
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.of(2026, 11, 1, 10, 0).plusMinutes(seq));
        return message;
    }

    /**
     * 会话存在，消息表返回给定行（按服务期望的 seq 倒序给出）。
     *
     * @param descRows 倒序消息行
     */
    private void stubMessages(ConversationMessage... descRows) {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(conversationMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(descRows));
    }

    /**
     * 捕获最近一次传给消息 Mapper 的 wrapper，用于断言游标条件。
     *
     * <p>同一用例里可能调用 service 两次，所以按「至少一次」捕获并取最后一个。
     * 注意 MP 3.5.18.1 的参数表是**惰性填充**的：必须先调 `getSqlSegment()` 触发，否则拿到空 map。</p>
     *
     * @return 查询 wrapper（参数表已填充）
     */
    @SuppressWarnings("unchecked")
    private AbstractWrapper<ConversationMessage, ?, ?> captureLastMessageWrapper() {
        ArgumentCaptor<Wrapper<ConversationMessage>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(conversationMessageMapper, atLeast(1)).selectList(captor.capture());
        List<Wrapper<ConversationMessage>> captured = captor.getAllValues();
        AbstractWrapper<ConversationMessage, ?, ?> wrapper =
                (AbstractWrapper<ConversationMessage, ?, ?>) captured.get(captured.size() - 1);
        // 触发惰性参数表填充：MP 3.5.18.1 在 getSqlSegment() 之后 paramNameValuePairs 才有值
        wrapper.getSqlSegment();
        return wrapper;
    }

    /**
     * 不满一页：升序返回、无下一页、nextCursor 为 null；已撤回消息返回原文。
     */
    @Test
    @DisplayName("消息：不满一页按 seq 升序返回，无下一页")
    void messagesReturnAscendingPageWithoutMore() {
        ConversationMessage third = message(3L, "第三条");
        ConversationMessage second = message(2L, "第二条");
        ConversationMessage first = message(1L, "第一条");
        first.setRecalledAt(LocalDateTime.of(2026, 11, 1, 11, 0));
        stubMessages(third, second, first);

        CursorResult<AdminChatMessageVO> result =
                adminConversationService.messages(CONVERSATION_ID, null, 10);

        List<AdminChatMessageVO> list = result.getList();
        assertEquals(3, list.size());
        assertEquals(List.of(1L, 2L, 3L),
                list.stream().map(AdminChatMessageVO::getSeq).toList(),
                "违反「返回按 seq 升序」：游标翻页的消息必须正序展示，倒序会让聊天记录上下颠倒");
        assertFalse(result.isHasMore(), "本批行数小于 LIMIT 时不该有下一页");
        assertNull(result.getNextCursor(), "没有下一页时 nextCursor 必须是 null（客户端据此关掉加载）");
        AdminChatMessageVO recalled = list.get(0);
        assertTrue(recalled.isRecalled(), "recalled_at 非空时 recalled 必须为 true");
        assertEquals("第一条", recalled.getContent(),
                "违反「已撤回消息返回原文」：审计要看的正是撤回前的内容，返回 null 等于审计失效");
    }

    /**
     * 满页 + 多取一条：hasMore=true，本批取最新的 limit 条，nextCursor 为本批最早 seq。
     */
    @Test
    @DisplayName("消息：多取一条判断 hasMore，nextCursor 取本批最早 seq")
    void messagesHasMoreAndCursor() {
        stubMessages(message(9L, "9"), message(8L, "8"), message(7L, "7"), message(6L, "6"));

        CursorResult<AdminChatMessageVO> result =
                adminConversationService.messages(CONVERSATION_ID, null, 3);

        assertEquals(List.of(7L, 8L, 9L),
                result.getList().stream().map(AdminChatMessageVO::getSeq).toList(),
                "limit=3 时本批应是 desc 前 3 条再反转（取最新 3 条，倒序查询后升序返回）");
        assertTrue(result.isHasMore(), "多查到第 4 条（size+1）时 hasMore 必须为 true");
        assertEquals("7", result.getNextCursor(),
                "违反「nextCursor = 本批最小 seq」：给最大 seq 会让下一页重复取同一批消息");
    }

    /**
     * limit 上界：超过 50 按 50 处理。
     */
    @Test
    @DisplayName("消息：limit 超过 50 时按 50 截断")
    void messagesClampLimitUpperBound() {
        ConversationMessage[] desc = new ConversationMessage[51];
        for (int i = 0; i < desc.length; i++) {
            desc[i] = message(51L - i, "第" + (51L - i) + "条");
        }
        stubMessages(desc);

        CursorResult<AdminChatMessageVO> result =
                adminConversationService.messages(CONVERSATION_ID, null, 1_000);

        assertEquals(50, result.getList().size(),
                "违反「一次最多 50 条」：limit 不 clamp 会让一段几百条的咨询一次全拉出来");
        assertEquals(List.of(2L, 51L),
                List.of(result.getList().get(0).getSeq(), result.getList().get(49).getSeq()),
                "截断后本批应是 desc 前 50 条（seq 51..2）反转，最早 seq=2、最新 seq=51");
        assertTrue(result.isHasMore(), "返回 51 条说明还有更多");
        assertEquals("2", result.getNextCursor(), "nextCursor 必须是本批最小 seq（2）");
    }

    /**
     * limit 下界：0 或负数按 1 处理。
     */
    @Test
    @DisplayName("消息：limit 小于 1 时按 1 截断")
    void messagesClampLimitLowerBound() {
        stubMessages(message(2L, "第二条"), message(1L, "第一条"));

        CursorResult<AdminChatMessageVO> result =
                adminConversationService.messages(CONVERSATION_ID, null, 0);

        assertEquals(1, result.getList().size(), "违反「limit clamp 1~50」：LIMIT 0 会让管理端永远空页");
        assertEquals(2L, result.getList().get(0).getSeq(),
                "limit=0 按 1 处理后应返回最新一条（desc 首条 seq=2），不是最旧一条");
        assertTrue(result.isHasMore(), "取了 1 条但多查到第 2 条，hasMore 必须为 true");
    }

    /**
     * 游标边界：无 beforeSeq 用 Long.MAX_VALUE，传了则原样进入 `seq < beforeSeq` 条件。
     */
    @Test
    @DisplayName("消息：无游标从最新开始，有游标按 seq < beforeSeq 过滤")
    void messagesCursorBoundary() {
        stubMessages(message(4L, "4"), message(3L, "3"));
        adminConversationService.messages(CONVERSATION_ID, null, 10);
        AbstractWrapper<ConversationMessage, ?, ?> noCursor = captureLastMessageWrapper();
        assertTrue(noCursor.getParamNameValuePairs().containsValue(Long.MAX_VALUE),
                "无 beforeSeq 时游标必须是 Long.MAX_VALUE（从最新一条开始），否则会漏掉最新消息；实际参数="
                        + noCursor.getParamNameValuePairs());

        stubMessages(message(4L, "4"), message(3L, "3"));
        adminConversationService.messages(CONVERSATION_ID, 5L, 10);
        AbstractWrapper<ConversationMessage, ?, ?> withCursor = captureLastMessageWrapper();
        assertTrue(withCursor.getParamNameValuePairs().containsValue(5L),
                "传了 beforeSeq=5 时必须按 seq < 5 过滤；条件用错了越翻越重复");
        assertFalse(withCursor.getParamNameValuePairs().containsValue(Long.MAX_VALUE),
                "有 beforeSeq 时不得再叠加 Long.MAX_VALUE 游标条件");
    }

    /**
     * 会话不存在：404001，且不查消息表。
     */
    @Test
    @DisplayName("消息：会话不存在抛 404001 且不查消息表")
    void messagesMissingConversation() {
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminConversationService.messages(CONVERSATION_ID, null, null));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, ex.getErrorCode(),
                "违反「会话不存在统一 404001」：与 C 端同码，管理端不另造错误码");
        verify(conversationMessageMapper, never()).selectList(any(Wrapper.class));
    }

    /**
     * 会话 id 为空：按不存在处理（404001）。
     */
    @Test
    @DisplayName("消息：conversationId 为空抛 404001")
    void messagesNullConversationId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminConversationService.messages(null, null, null));

        assertEquals(ErrorCode.CONVERSATION_NOT_FOUND, ex.getErrorCode());
        verify(conversationMapper, never()).selectById(any());
        verify(conversationMessageMapper, never()).selectList(any(Wrapper.class));
    }

    /**
     * 审计：调用后 AuditContext 里必须留 module/action/targetType/targetId 四要素。
     */
    @Test
    @DisplayName("审计：CONVERSATION_VIEW + target_id=会话 id 写入 AuditContext")
    void messagesMarksAuditContext() {
        stubMessages(message(1L, "唯一一条"));
        AuditContext.init(9L, "admin", "127.0.0.1");
        try {
            adminConversationService.messages(CONVERSATION_ID, null, 10);

            AuditContext.AuditInfo info = AuditContext.current();
            assertNotNull(info, "审计上下文必须存在（JwtAuthenticationFilter 已 init）");
            assertEquals("conversation", info.getModule(), "审计 module 必须是 conversation");
            assertEquals("CONVERSATION_VIEW", info.getAction(),
                    "违反拍板第 7 项「每次查看聊天记录都留痕」：action 必须是 CONVERSATION_VIEW");
            assertEquals("CONVERSATION", info.getTargetType(), "审计 targetType 必须是 CONVERSATION");
            assertEquals(String.valueOf(CONVERSATION_ID), info.getTargetId(),
                    "违反「审计要能定位到看的是哪段对话」：target_id 必须是会话 id，为空等于只记了动作");
        } finally {
            AuditContext.clear();
        }
    }

    /**
     * 审计：方法上必须声明 @OperationLog（切面靠它落库，缺注解则审计不写）。
     */
    @Test
    @DisplayName("审计：messages 方法声明 @OperationLog(conversation / CONVERSATION_VIEW / CONVERSATION)")
    void messagesDeclaresOperationLogAnnotation() throws NoSuchMethodException {
        Method method = AdminConversationService.class.getMethod("messages", Long.class, Long.class,
                Integer.class);
        OperationLog annotation = method.getAnnotation(OperationLog.class);

        assertNotNull(annotation,
                "违反「管理端查看聊天记录必须审计」：messages 缺 @OperationLog，切面不会写 operation_log");
        assertEquals("conversation", annotation.module());
        assertEquals("CONVERSATION_VIEW", annotation.action());
        assertEquals("CONVERSATION", annotation.targetType());
    }

    /**
     * 附件：OSS key 换签名地址，http(s) 原样直通，空值不签名。
     */
    @Test
    @DisplayName("附件：OSS key 走 FileStorage 签名，http(s) 直通，空值不签名")
    void messagesSignsFileUrl() {
        ConversationMessage keyMessage = message(1L, null);
        keyMessage.setMsgType("image");
        keyMessage.setFileUrl("private/chat/2026/11/a.png");
        ConversationMessage httpMessage = message(2L, null);
        httpMessage.setMsgType("image");
        httpMessage.setFileUrl("https://cdn.example.com/a.png");
        ConversationMessage emptyMessage = message(3L, null);
        emptyMessage.setMsgType("image");
        emptyMessage.setFileUrl("   ");
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(conversation());
        when(conversationMessageMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(emptyMessage, httpMessage, keyMessage));
        when(fileStorage.presignDownload("private/chat/2026/11/a.png"))
                .thenReturn(new FileStorage.PresignedUrl("http://oss.local/signed/a.png", 60));

        List<AdminChatMessageVO> list =
                adminConversationService.messages(CONVERSATION_ID, null, 10).getList();

        assertEquals("http://oss.local/signed/a.png", list.get(0).getFileUrl(),
                "违反「管理端打不开附件」：库里存 OSS key，出参必须换签名地址");
        assertEquals("https://cdn.example.com/a.png", list.get(1).getFileUrl(),
                "已经是 http(s) 地址的附件必须原样直通，不能再签名一次");
        assertNull(list.get(2).getFileUrl(), "空白 key 的 fileUrl 必须是 null，不能返回空白字符串");
        verify(fileStorage, never()).presignDownload("   ");
        verify(fileStorage, never()).presignDownload("https://cdn.example.com/a.png");
    }

    /**
     * 附件：签名失败降级为 null，不得让整个接口 500。
     */
    @Test
    @DisplayName("附件：签名失败降级为 null 不抛异常")
    void messagesDegradesOnPresignFailure() {
        ConversationMessage broken = message(1L, "第一条");
        broken.setMsgType("file");
        broken.setFileUrl("private/chat/2026/11/secret.pdf");
        stubMessages(broken);
        when(fileStorage.presignDownload("private/chat/2026/11/secret.pdf"))
                .thenThrow(new IllegalStateException("OSS 不可用"));

        List<AdminChatMessageVO> list =
                adminConversationService.messages(CONVERSATION_ID, null, 10).getList();

        assertNull(list.get(0).getFileUrl(),
                "签名失败必须降级为 null：一张附件打不开不能让整段聊天记录都看不出来");
        assertEquals("第一条", list.get(0).getContent(), "附件签名失败不影响文本内容返回");
    }
}
