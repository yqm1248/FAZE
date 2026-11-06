/*
 * 职责：OrderPayExecutor 的单测——正常支付 / 密码错 / 余额不足 / CAS 未命中 / 状态不对（G2.2）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 BalancePayAdapter / OrderStateService / PaymentRecordMapper / TaskQueueService。
 * 注意：
 *   1. **密码错与余额不足时必须断言「状态迁移一次都没发生、支付记录一条都没写」**：
 *      这是 2-A5「不放行、不扣款、不改状态」的最小证明。
 *   2. CAS 未命中要抛异常（让外层事务回滚扣款），不能「就当成功返回」。
 *   3. 正常路径要断言**投递了接单超时任务**：没有它 G2.4 的自动退款就永远不会触发。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.exception.PayPasswordWrongException;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.PaymentRecord;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.PaymentRecordMapper;
import com.faze.modules.trade.vo.PayFromBalanceResult;
import com.faze.modules.trade.vo.PayResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 余额支付执行器测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPayExecutorTest {

    private static final Long ORDER_ID = 3003L;
    private static final Long USER_ID = 1001L;
    private static final long AMOUNT = 5000L;
    private static final String PWD = "123456";

    @Mock
    private BalancePayAdapter balancePayAdapter;

    @Mock
    private OrderStateService orderStateService;

    @Mock
    private PaymentRecordMapper paymentRecordMapper;

    @Mock
    private TaskQueueService taskQueueService;

    @InjectMocks
    private OrderPayExecutor executor;

    @Test
    @DisplayName("正常支付：扣款 → 状态 PENDING→PAID → 写支付记录 → 投递接单超时任务")
    void paySuccess() {
        when(balancePayAdapter.payFromBalance(eq(USER_ID), eq(ORDER_ID), eq(AMOUNT), eq(PWD)))
                .thenReturn(new PayFromBalanceResult(9001L, false, 15_000L));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);
        when(paymentRecordMapper.insert(any(PaymentRecord.class))).thenReturn(1);

        PayResultVO vo = executor.pay(pendingOrder(), PWD);

        assertEquals(OrderStatus.PAID.getCode(), vo.getStatus());
        assertEquals(AMOUNT, vo.getAmountCents());
        assertEquals(15_000L, vo.getBalanceAfterCents());
        assertFalse(vo.isDuplicated());
        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.PENDING), eq(OrderStatus.PAID),
                eq(OperatorRole.USER), eq(USER_ID), anyString());
        verify(paymentRecordMapper).insert(any(PaymentRecord.class));
        verify(taskQueueService).enqueue(eq(TaskQueueService.TYPE_ORDER_ACCEPT_TIMEOUT),
                eq("OR20261106000001"), any(), any());
    }

    @Test
    @DisplayName("密码错：不迁移状态、不写支付记录（2-A5）")
    void wrongPassword() {
        doThrow(new PayPasswordWrongException(4))
                .when(balancePayAdapter).payFromBalance(anyLong(), anyLong(), anyLong(), anyString());

        assertThrows(PayPasswordWrongException.class, () -> executor.pay(pendingOrder(), "bad"));

        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), any(), anyString());
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    @DisplayName("余额不足：不迁移状态、不写支付记录")
    void insufficientBalance() {
        doThrow(new BusinessException(ErrorCode.TRADE_BALANCE_INSUFFICIENT))
                .when(balancePayAdapter).payFromBalance(anyLong(), anyLong(), anyLong(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.pay(pendingOrder(), PWD));

        assertEquals(ErrorCode.TRADE_BALANCE_INSUFFICIENT, ex.getErrorCode());
        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("CAS 未命中（订单刚被关单）：抛 405003，让事务回滚扣款")
    void casMissRollsBack() {
        when(balancePayAdapter.payFromBalance(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(new PayFromBalanceResult(9001L, false, 15_000L));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.pay(pendingOrder(), PWD));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(paymentRecordMapper, never()).insert(any(PaymentRecord.class));
    }

    @Test
    @DisplayName("订单状态不是 PENDING：在扣款之前就拒（一次都不扣）")
    void notPendingRejected() {
        Orders order = pendingOrder();
        order.setStatus(OrderStatus.CANCELLED.getCode());

        BusinessException ex = assertThrows(BusinessException.class, () -> executor.pay(order, PWD));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(balancePayAdapter, never()).payFromBalance(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("支付记录唯一键冲突（重复重放）：吞掉冲突，不中断支付")
    void duplicatePaymentRecordIgnored() {
        when(balancePayAdapter.payFromBalance(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(new PayFromBalanceResult(9001L, true, 15_000L));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);
        when(paymentRecordMapper.insert(any(PaymentRecord.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_payment"));
        when(paymentRecordMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        PayResultVO vo = executor.pay(pendingOrder(), PWD);

        assertTrue(vo.isDuplicated());
    }

    private Orders pendingOrder() {
        Orders order = new Orders();
        order.setId(ORDER_ID);
        order.setOrderNo("OR20261106000001");
        order.setUserId(USER_ID);
        order.setAmountCents(AMOUNT);
        order.setStatus(OrderStatus.PENDING.getCode());
        return order;
    }
}
