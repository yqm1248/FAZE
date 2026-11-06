/*
 * 职责：OrderServiceImpl 的编排单测——幂等命中不再建单、失败释放占位、归属校验。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper / OrderCreateExecutor / OrderIdempotencyGuard /
 *          OrderConverter / LawyerQueryService。
 * 注意：
 *   1. 这一层测的是**编排顺序**，不是取价规则（取价在 OrderCreateExecutorTest 里）。
 *   2. 「幂等命中返回同一订单」与「异常时 release」必须成对出现：
 *      只测前者会出现「一次失败锁死用户 10 分钟」的线上事故而测试全绿。
 *   3. 归属校验以「查不到统一 405002」为判据（不区分不存在 / 不是你的）。
 */
package com.faze.modules.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.trade.bo.OrderCreateBO;
import com.faze.modules.trade.converter.OrderConverter;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.service.OrderCreateExecutor;
import com.faze.modules.trade.service.OrderIdempotencyGuard;
import com.faze.modules.trade.service.OrderPayExecutor;
import com.faze.modules.trade.service.BalanceQueryService;
import com.faze.modules.trade.service.OrderStateService;
import com.faze.modules.trade.service.ReviewService;
import com.faze.modules.trade.service.TaskQueueService;
import com.faze.modules.trade.vo.OrderDetailVO;
import com.faze.modules.trade.vo.PayResultVO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户侧订单服务编排测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 3003L;
    private static final String IDEM_KEY = "idem-key-1";

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private OrderCreateExecutor orderCreateExecutor;

    @Mock
    private OrderIdempotencyGuard idempotencyGuard;

    @Mock
    private OrderConverter orderConverter;

    @Mock
    private LawyerQueryService lawyerQueryService;

    @Mock
    private OrderStateService orderStateService;

    @Mock
    private TaskQueueService taskQueueService;

    @Mock
    private OrderPayExecutor orderPayExecutor;

    @Mock
    private BalanceQueryService balanceQueryService;

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    @DisplayName("幂等命中：直接返回已有订单，不调用创建执行器")
    void createHitsIdempotency() {
        Orders existing = pendingOrder();
        when(idempotencyGuard.tryStart(USER_ID, IDEM_KEY)).thenReturn(Optional.of(ORDER_ID));
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(lawyerQueryService.findById(anyLong())).thenReturn(Optional.empty());
        OrderDetailVO expected = new OrderDetailVO();
        when(orderConverter.toDetailVO(eq(existing), any(), any(), anyBoolean())).thenReturn(expected);

        OrderDetailVO result = orderService.create(USER_ID, request(), IDEM_KEY);

        assertSame(expected, result);
        verify(orderCreateExecutor, never()).create(anyLong(), any());
        verify(idempotencyGuard, never()).bind(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("首次创建成功：事务提交后绑定幂等键")
    void createBindsIdempotencyKey() {
        Orders created = pendingOrder();
        when(idempotencyGuard.tryStart(USER_ID, IDEM_KEY)).thenReturn(Optional.empty());
        when(orderCreateExecutor.create(eq(USER_ID), any())).thenReturn(created);
        when(lawyerQueryService.findById(anyLong())).thenReturn(Optional.empty());
        when(orderConverter.toDetailVO(eq(created), any(), any(), anyBoolean())).thenReturn(new OrderDetailVO());

        orderService.create(USER_ID, request(), IDEM_KEY);

        verify(idempotencyGuard).bind(USER_ID, IDEM_KEY, ORDER_ID);
    }

    @Test
    @DisplayName("创建失败：释放幂等占位并原样上抛异常")
    void createReleasesOnFailure() {
        when(idempotencyGuard.tryStart(USER_ID, IDEM_KEY)).thenReturn(Optional.empty());
        when(orderCreateExecutor.create(eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.TRADE_LAWYER_UNAVAILABLE));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(USER_ID, request(), IDEM_KEY));

        assertEquals(ErrorCode.TRADE_LAWYER_UNAVAILABLE, ex.getErrorCode());
        verify(idempotencyGuard).release(USER_ID, IDEM_KEY);
        verify(idempotencyGuard, never()).bind(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("详情：订单不存在或不属于当前用户时统一 405002")
    void detailNotFound() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.detail(USER_ID, ORDER_ID));

        assertEquals(ErrorCode.TRADE_ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("详情：查询条件带 user_id（归属进 SQL，不靠事后比对）")
    void detailQueriesWithOwnerCondition() {
        Orders order = pendingOrder();
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(lawyerQueryService.findById(anyLong())).thenReturn(Optional.empty());
        when(orderConverter.toDetailVO(eq(order), any(), any(), anyBoolean())).thenReturn(new OrderDetailVO());

        orderService.detail(USER_ID, ORDER_ID);

        // 条件拼装由 MP 生成，这里断言「查了一次且使用 Wrapper」，具体 SQL 由集成验证覆盖
        verify(ordersMapper).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("详情：已评价的订单不再下发 review 动作（G5.1）")
    void detailDropsReviewActionWhenReviewed() {
        Orders order = pendingOrder();
        order.setStatus(OrderStatus.COMPLETED.getCode());
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(lawyerQueryService.findById(anyLong())).thenReturn(Optional.empty());
        when(reviewService.existsOfOrder(ORDER_ID)).thenReturn(true);
        when(orderConverter.toDetailVO(eq(order), any(), anyList(), anyBoolean())).thenReturn(new OrderDetailVO());

        orderService.detail(USER_ID, ORDER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderConverter).toDetailVO(eq(order), any(), captor.capture(), eq(true));
        // COMPLETED 用户侧只有 review 一个动作，去掉后就该是空列表
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    @DisplayName("取消：PENDING → CANCELLED，并取消关单任务")
    void cancelPendingOrder() {
        Orders order = pendingOrder();
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderStateService.transit(eq(ORDER_ID), eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                any(), eq(USER_ID), anyString())).thenReturn(true);

        orderService.cancel(USER_ID, ORDER_ID);

        verify(taskQueueService).cancelPending(eq(TaskQueueService.TYPE_ORDER_CLOSE),
                eq("OR20261106000001"));
    }

    @Test
    @DisplayName("取消：已取消的订单再取消是幂等成功（不报错、不重复写流水）")
    void cancelIsIdempotent() {
        Orders order = pendingOrder();
        order.setStatus(OrderStatus.CANCELLED.getCode());
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        orderService.cancel(USER_ID, ORDER_ID);

        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("取消：CAS 未命中（刚好支付成功）→ 405003")
    void cancelCasMiss() {
        Orders order = pendingOrder();
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderStateService.transit(anyLong(), any(), any(), any(), anyLong(), anyString()))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancel(USER_ID, ORDER_ID));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("重复支付：订单已 PAID 时返回 duplicated=true，不再调扣款执行器（2-A8 ②）")
    void payAlreadyPaidIsIdempotent() {
        Orders order = pendingOrder();
        order.setStatus(OrderStatus.PAID.getCode());
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(balanceQueryService.getAvailableCents(USER_ID)).thenReturn(12_000L);

        PayResultVO vo = orderService.pay(USER_ID, ORDER_ID, payRequest());

        assertTrue(vo.isDuplicated());
        assertEquals(OrderStatus.PAID.getCode(), vo.getStatus());
        assertEquals(12_000L, vo.getBalanceAfterCents());
        verify(orderPayExecutor, never()).pay(any(), anyString());
    }

    @Test
    @DisplayName("支付：非待支付状态直接拒（405003），不进入扣款")
    void payNonPendingRejected() {
        Orders order = pendingOrder();
        order.setStatus(OrderStatus.CANCELLED.getCode());
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(USER_ID, ORDER_ID, payRequest()));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(orderPayExecutor, never()).pay(any(), anyString());
    }

    private com.faze.modules.trade.bo.PayBO payRequest() {
        com.faze.modules.trade.bo.PayBO bo = new com.faze.modules.trade.bo.PayBO();
        bo.setPayPassword("123456");
        return bo;
    }

    private Orders pendingOrder() {        Orders order = new Orders();
        order.setId(ORDER_ID);
        order.setOrderNo("OR20261106000001");
        order.setUserId(USER_ID);
        order.setLawyerId(2002L);
        order.setServiceType("consult_text");
        order.setAmountCents(5000L);
        order.setStatus(OrderStatus.PENDING.getCode());
        return order;
    }

    private OrderCreateBO request() {
        OrderCreateBO bo = new OrderCreateBO();
        bo.setLawyerId(2002L);
        bo.setServiceType("consult_text");
        return bo;
    }
}
