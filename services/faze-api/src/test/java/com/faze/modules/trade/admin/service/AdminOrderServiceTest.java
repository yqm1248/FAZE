/*
 * 职责：AdminOrderService 的单测——PC A-11 订单列表的分页/total 口径、A-12 详情、状态流水时间线（G9.1 / G9.2）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper / PaymentRecordMapper / RefundRecordMapper / OrderStatusLogMapper /
 *          ConversationMapper / AccountMapper / LawyerMapper；AccountConverter 用真实实例
 *          （脱敏是纯函数，mock 掉就测不出「明文手机号是否泄露」）。
 * 注意：
 *   1. **total 必须是数据库总条数**，不是本页行数：本页返回 2 行而库里有 42 行时，
 *      total 只能是 42，否则前端分页器只显示 1 页（最难发现的列表缺陷）。
 *   2. **手机号只出脱敏值**（138****8888）：管理端列表是高频页，明文手机号等于全站用户信息摊开。
 *   3. **金额按分透传**：接口规范定「金额一律整数分」，分转元是前端展示层职责；
 *      本类的断言锁定「1999 分不会在服务端被截成 19」。
 *   4. 关联用户/律师**批量补**：一页 N 行只允许一次 selectBatchIds，禁止逐行查（N+1）。
 *   5. 订单不存在一律 405002，且在抛异常前不得再查支付/退款/会话（不编造数据）。
 *   6. 状态流水的 operatorId 是 Long 型 ID，必须转字符串：JS 端 Long 超过 2^53 会丢精度。
 */
package com.faze.modules.trade.admin.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.pojo.TableDataInfo;
import com.faze.modules.account.converter.AccountConverter;
import com.faze.modules.account.entity.AppUser;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.conversation.entity.Conversation;
import com.faze.modules.conversation.mapper.ConversationMapper;
import com.faze.modules.trade.admin.bo.AdminOrderQueryBO;
import com.faze.modules.trade.admin.vo.AdminOrderDetailVO;
import com.faze.modules.trade.admin.vo.AdminOrderRowVO;
import com.faze.modules.trade.admin.vo.AdminOrderStatusLogVO;
import com.faze.modules.trade.entity.OrderStatusLog;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.PaymentRecord;
import com.faze.modules.trade.entity.RefundRecord;
import com.faze.modules.trade.mapper.OrderStatusLogMapper;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.mapper.PaymentRecordMapper;
import com.faze.modules.trade.mapper.RefundRecordMapper;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端订单查询服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOrderServiceTest {

    private static final Long ORDER_ID = 6002L;
    private static final Long USER_ID = 5001L;
    private static final Long LAWYER_ID = 7003L;
    private static final Long CONVERSATION_ID = 9004L;

    /** 19.99 元的金额（单位分）。 */
    private static final Long AMOUNT_CENTS = 1_999L;

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private PaymentRecordMapper paymentRecordMapper;

    @Mock
    private RefundRecordMapper refundRecordMapper;

    @Mock
    private OrderStatusLogMapper orderStatusLogMapper;

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private LawyerMapper lawyerMapper;

    private AdminOrderService adminOrderService;

    /** 最近一次传给 Mapper 的分页对象，用于断言 pageNum / pageSize 的 clamp 口径。 */
    private IPage<Orders> capturedPage;

    /**
     * 注册 lambda 列名解析所需的 TableInfo：不注册时 `Wrappers.lambdaQuery()` 找不到列缓存会直接抛异常。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Orders.class);
        TableInfoHelper.initTableInfo(assistant, PaymentRecord.class);
        TableInfoHelper.initTableInfo(assistant, RefundRecord.class);
        TableInfoHelper.initTableInfo(assistant, OrderStatusLog.class);
        TableInfoHelper.initTableInfo(assistant, Conversation.class);
        TableInfoHelper.initTableInfo(assistant, AppUser.class);
        TableInfoHelper.initTableInfo(assistant, Lawyer.class);
    }

    /**
     * 手工装配：AccountConverter 用真实实例，脱敏逻辑不被 mock 吞掉。
     */
    @BeforeEach
    void setUp() {
        adminOrderService = new AdminOrderService(ordersMapper, paymentRecordMapper, refundRecordMapper,
                orderStatusLogMapper, conversationMapper, accountMapper, lawyerMapper, new AccountConverter());
    }

    /**
     * 造一笔订单。
     *
     * @param id        订单主键
     * @param userId    用户主键（可空）
     * @param lawyerId  律师主键（可空）
     * @return 订单实体
     */
    private Orders order(Long id, Long userId, Long lawyerId) {
        Orders order = new Orders();
        order.setId(id);
        order.setOrderNo("FAZE" + id);
        order.setUserId(userId);
        order.setLawyerId(lawyerId);
        order.setServiceType("text");
        order.setAmountCents(AMOUNT_CENTS);
        order.setStatus("paid");
        order.setCreatedAt(LocalDateTime.of(2026, 11, 1, 9, 30));
        return order;
    }

    /**
     * 造一条状态流水。
     *
     * @param id           主键
     * @param fromStatus   原状态（可空，首条为空）
     * @param toStatus     新状态
     * @param operatorType 操作人类型
     * @param operatorId   操作人主键（可空）
     * @param reason       原因
     * @param createdAt    发生时间
     * @return 流水实体
     */
    private OrderStatusLog statusLog(Long id, String fromStatus, String toStatus, String operatorType,
                                     Long operatorId, String reason, LocalDateTime createdAt) {
        OrderStatusLog log = new OrderStatusLog();
        log.setId(id);
        log.setOrderId(ORDER_ID);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setReason(reason);
        log.setCreatedAt(createdAt);
        return log;
    }

    /**
     * 让分页查询返回给定行与总数，并记录服务实际传入的分页参数。
     *
     * @param total 数据库总条数
     * @param rows  本页行
     */
    private void stubPage(long total, Orders... rows) {
        when(ordersMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<Orders> page = invocation.getArgument(0);
            capturedPage = page;
            page.setRecords(List.of(rows));
            page.setTotal(total);
            return page;
        });
    }

    /**
     * 列表：total 取库总条数；用户/律师各批量查一次；手机号脱敏。
     */
    @Test
    @DisplayName("列表：total 取数据库总数，用户/律师批量补，手机号脱敏")
    void listKeepsDbTotalAndMasksPhone() {
        Orders first = order(ORDER_ID, USER_ID, LAWYER_ID);
        Orders second = order(6003L, USER_ID, LAWYER_ID);
        stubPage(42L, first, second);

        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setNickname("张三");
        user.setPhone("13800008888");
        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setRealName("李律师");
        when(accountMapper.selectBatchIds(any())).thenReturn(List.of(user));
        when(lawyerMapper.selectBatchIds(any())).thenReturn(List.of(lawyer));

        TableDataInfo<AdminOrderRowVO> result = adminOrderService.list(new AdminOrderQueryBO());

        assertEquals(2, result.getRows().size(), "本页 rows 条数应等于分页记录数");
        assertEquals(42L, result.getTotal(),
                "违反「total 取数据库总条数」：用本页行数（2）代替会让前端分页器只显示 1 页");
        assertEquals(200, result.getCode(), "RuoYi 口径列表必须是 code=200");
        AdminOrderRowVO row = result.getRows().get(0);
        assertEquals("138****8888", row.getUserPhoneMasked(),
                "违反「手机号只出脱敏值」：管理端列表明文手机号等于全站用户信息泄露");
        assertEquals("张三", row.getUserName());
        assertEquals("李律师", row.getLawyerName());
        assertEquals(AMOUNT_CENTS, row.getAmountCents());
        verify(accountMapper, times(1)).selectBatchIds(any());
        verify(lawyerMapper, times(1)).selectBatchIds(any());
    }

    /**
     * 列表：未传 query 时按 RuoYi 默认 1/10 分页；空页不发起批量补用户/律师查询。
     */
    @Test
    @DisplayName("列表：query 为空按 1/10 分页，空页不查用户/律师")
    void listUsesDefaultPagingAndSkipsEmptyBatch() {
        stubPage(0L);

        TableDataInfo<AdminOrderRowVO> result = adminOrderService.list(null);

        assertEquals(1L, capturedPage.getCurrent(), "未传 query 时页码必须是 1（RuoYi 口径 pageNum 默认值）");
        assertEquals(10L, capturedPage.getSize(), "未传 query 时每页必须是 10（RuoYi 口径 pageSize 默认值）");
        assertTrue(result.getRows().isEmpty(), "空页应返回空列表而不是 null");
        assertEquals(0L, result.getTotal());
        verify(accountMapper, never()).selectBatchIds(any());
        verify(lawyerMapper, never()).selectBatchIds(any());
    }

    /**
     * 列表：pageSize clamp 到 1~100，pageNum 小于 1 归 1。
     */
    @Test
    @DisplayName("列表：pageSize 超界 clamp 到 1~100，pageNum<1 归 1")
    void listClampsPaging() {
        stubPage(0L);

        AdminOrderQueryBO tooLarge = new AdminOrderQueryBO();
        tooLarge.setPageSize(1_000);
        adminOrderService.list(tooLarge);
        assertEquals(100L, capturedPage.getSize(),
                "违反「每页条数上限 100」：不 clamp 会让一次列表拖出全表，管理端被打挂");

        AdminOrderQueryBO tooSmall = new AdminOrderQueryBO();
        tooSmall.setPageSize(0);
        tooSmall.setPageNum(0);
        adminOrderService.list(tooSmall);
        assertEquals(1L, capturedPage.getSize(), "pageSize 小于 1 必须归 1，否则 SQL LIMIT 0 永远空页");
        assertEquals(1L, capturedPage.getCurrent(), "pageNum 小于 1 必须归 1，否则 offset 为负数");
    }

    /**
     * 列表：金额保持「分」口径透传，不在服务端做分转元。
     */
    @Test
    @DisplayName("列表：金额按分透传（1999 分不得变成 19）")
    void listKeepsAmountInCents() {
        Orders order = order(ORDER_ID, USER_ID, LAWYER_ID);
        order.setAmountCents(1_999L);
        stubPage(1L, order);
        when(accountMapper.selectBatchIds(any())).thenReturn(List.of());
        when(lawyerMapper.selectBatchIds(any())).thenReturn(List.of());

        AdminOrderRowVO row = adminOrderService.list(new AdminOrderQueryBO()).getRows().get(0);

        assertEquals(1_999L, row.getAmountCents(),
                "违反「金额一律整数分」：接口传分、前端负责分转元展示；变成 19 说明服务端提前截断了金额");
    }

    /**
     * 详情：订单字段、脱敏手机号、律师律所、关联会话、支付/退款记录全部映射。
     */
    @Test
    @DisplayName("详情：订单/用户/律师/会话/支付/退款全部映射")
    void detailMapsAllSections() {
        Orders order = order(ORDER_ID, USER_ID, LAWYER_ID);
        order.setRemark("加急");
        order.setPaidAt(LocalDateTime.of(2026, 11, 1, 9, 40));
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setNickname("张三");
        user.setPhone("13912345678");
        when(accountMapper.selectById(USER_ID)).thenReturn(user);

        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setRealName("李律师");
        lawyer.setLawFirm("伊犁某某律师事务所");
        when(lawyerMapper.selectById(LAWYER_ID)).thenReturn(lawyer);

        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation);

        PaymentRecord payment = new PaymentRecord();
        payment.setId(1L);
        payment.setOrderId(ORDER_ID);
        payment.setChannel("wechat");
        payment.setTransactionId("wx-001");
        payment.setAmountCents(AMOUNT_CENTS);
        payment.setStatus("success");
        when(paymentRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(payment));

        RefundRecord refund = new RefundRecord();
        refund.setId(2L);
        refund.setOrderId(ORDER_ID);
        refund.setAmountCents(500L);
        refund.setReason("部分退款");
        refund.setStatus("refunded");
        refund.setAuditedBy(88L);
        when(refundRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(refund));

        AdminOrderDetailVO vo = adminOrderService.detail(ORDER_ID);

        assertEquals(ORDER_ID, vo.getId());
        assertEquals("FAZE" + ORDER_ID, vo.getOrderNo());
        assertEquals("paid", vo.getStatus());
        assertEquals(AMOUNT_CENTS, vo.getAmountCents());
        assertEquals("加急", vo.getRemark());
        assertEquals("139****5678", vo.getUserPhoneMasked(),
                "违反「手机号只出脱敏值」：详情抽屉也不得显示明文手机号");
        assertEquals("伊犁某某律师事务所", vo.getLawyerFirm());
        assertEquals(CONVERSATION_ID, vo.getConversationId(),
                "关联会话必须按 (user_id, lawyer_id) 查到后回填，不能编造会话编号");
        assertEquals(1, vo.getPayments().size());
        assertEquals("wx-001", vo.getPayments().get(0).getTransactionId());
        assertEquals(1, vo.getRefunds().size());
        assertEquals(500L, vo.getRefunds().get(0).getAmountCents());
        assertEquals(88L, vo.getRefunds().get(0).getAuditedBy());
    }

    /**
     * 详情：订单不存在 → 405002，且不继续查用户/支付/退款。
     */
    @Test
    @DisplayName("详情：订单不存在抛 405002 且不查关联数据")
    void detailMissingOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminOrderService.detail(ORDER_ID));

        assertEquals(ErrorCode.TRADE_ORDER_NOT_FOUND, ex.getErrorCode(),
                "违反「订单不存在统一 405002」：换个错误码会让前端分支与日志统计口径不一致");
        verify(accountMapper, never()).selectById(any());
        verify(paymentRecordMapper, never()).selectList(any(Wrapper.class));
        verify(refundRecordMapper, never()).selectList(any(Wrapper.class));
    }

    /**
     * 详情：订单没有用户/律师关联时，相关字段为 null 而不是占位值。
     */
    @Test
    @DisplayName("详情：无用户/律师关联时字段为 null，不查库")
    void detailWithoutUserAndLawyer() {
        Orders order = order(ORDER_ID, null, null);
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(paymentRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(refundRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AdminOrderDetailVO vo = adminOrderService.detail(ORDER_ID);

        assertNull(vo.getUserName(), "用户不存在时昵称必须是 null，不能填「未知用户」之类的占位值");
        assertNull(vo.getUserPhoneMasked(), "用户不存在时脱敏手机号必须是 null");
        assertNull(vo.getLawyerName());
        assertNull(vo.getLawyerFirm());
        assertNull(vo.getConversationId(), "没有用户/律师关联时不得编造会话编号");
        verify(accountMapper, never()).selectById(any());
        verify(lawyerMapper, never()).selectById(any());
        verify(conversationMapper, never()).selectOne(any(Wrapper.class));
    }

    /**
     * 状态流水：顺序沿用 Mapper 结果；operatorId 由 Long 转字符串。
     */
    @Test
    @DisplayName("状态流水：时间线字段映射，operatorId Long→String")
    void statusLogMapsTimelineAndStringifiesOperatorId() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(ORDER_ID, USER_ID, LAWYER_ID));
        OrderStatusLog created = statusLog(1L, null, "pending", "system", null, "创建订单",
                LocalDateTime.of(2026, 11, 1, 9, 30));
        OrderStatusLog paid = statusLog(2L, "pending", "paid", "user", 9_007_199_254_740_993L, "余额支付",
                LocalDateTime.of(2026, 11, 1, 9, 40));
        when(orderStatusLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(created, paid));

        List<AdminOrderStatusLogVO> logs = adminOrderService.statusLog(ORDER_ID);

        assertEquals(2, logs.size());
        AdminOrderStatusLogVO first = logs.get(0);
        assertEquals(1L, first.getId());
        assertEquals(ORDER_ID, first.getOrderId());
        assertNull(first.getFromStatus(), "第一条流水的 from_status 为空（订单创建），不能填占位字符串");
        assertEquals("pending", first.getToStatus());
        assertEquals("system", first.getOperatorType());
        assertNull(first.getOperatorId());
        assertEquals("余额支付", logs.get(1).getReason());
        assertEquals("9007199254740993", logs.get(1).getOperatorId(),
                "违反「Long 型 ID 一律序列化为字符串」：operatorId 直接给 Long 会在 JS 端丢精度");
    }

    /**
     * 状态流水：订单不存在 → 405002，且不查流水表。
     */
    @Test
    @DisplayName("状态流水：订单不存在抛 405002 且不查流水表")
    void statusLogMissingOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminOrderService.statusLog(ORDER_ID));

        assertEquals(ErrorCode.TRADE_ORDER_NOT_FOUND, ex.getErrorCode());
        verify(orderStatusLogMapper, never()).selectList(any(Wrapper.class));
        verify(ordersMapper, never()).selectPage(any(), any(Wrapper.class));
    }
}
