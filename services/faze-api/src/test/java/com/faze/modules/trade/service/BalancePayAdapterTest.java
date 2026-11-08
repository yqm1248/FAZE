/*
 * 职责：BalancePayAdapter 的单测——正常扣、密码错、余额不足、重复扣四种分支。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 PayPasswordService 与 DeductService。
 * 注意：
 *   1. 密码错时断言 **deduct 一次都没被调用**——这是 2-A5「不放行、不扣款」的最小证明。
 *   2. 用 InOrder 断言调用顺序：先 verify 密码，再 deduct。
 *   3. 幂等命中（duplicated=true）必须原样返回，不能当失败抛异常。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.common.exception.PayPasswordWrongException;
import com.faze.modules.account.service.PayPasswordService;
import com.faze.modules.trade.vo.DeductResult;
import com.faze.modules.trade.vo.PayFromBalanceResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 余额支付适配层测试。
 */
@ExtendWith(MockitoExtension.class)
class BalancePayAdapterTest {

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 2002L;
    private static final long AMOUNT = 5000L;

    @Mock
    private PayPasswordService payPasswordService;

    @Mock
    private DeductService deductService;

    @InjectMocks
    private BalancePayAdapter adapter;

    @Test
    @DisplayName("正常扣款：先校验密码再扣钱，返回流水 ID")
    void paySuccess() {
        when(deductService.deduct(anyString(), eq(USER_ID), eq(BalancePayAdapter.BIZ_TYPE_CONSUME),
                eq(ORDER_ID), eq(AMOUNT), anyString()))
                .thenReturn(new DeductResult(false, 9001L, 15_000L));

        PayFromBalanceResult result = adapter.payFromBalance(USER_ID, ORDER_ID, AMOUNT, "123456");

        assertEquals(9001L, result.flowId());
        assertFalse(result.duplicated());
        assertEquals(15_000L, result.balanceAfterCents());

        InOrder inOrder = inOrder(payPasswordService, deductService);
        inOrder.verify(payPasswordService).verify(USER_ID, "123456");
        inOrder.verify(deductService).deduct(anyString(), eq(USER_ID), eq(BalancePayAdapter.BIZ_TYPE_CONSUME),
                eq(ORDER_ID), eq(AMOUNT), anyString());
    }

    @Test
    @DisplayName("密码错：抛 PayPasswordWrongException，且一次都没扣款")
    void wrongPassword() {
        org.mockito.Mockito.doThrow(new PayPasswordWrongException(4))
                .when(payPasswordService).verify(USER_ID, "bad");

        assertThrows(PayPasswordWrongException.class,
                () -> adapter.payFromBalance(USER_ID, ORDER_ID, AMOUNT, "bad"));

        verify(deductService, never()).deduct(anyString(), anyLong(), anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("余额不足：deduct 抛 405001，原样向上抛，不吞成通用错误")
    void insufficientBalance() {
        when(deductService.deduct(anyString(), eq(USER_ID), anyString(), eq(ORDER_ID), eq(AMOUNT), anyString()))
                .thenThrow(new BusinessException(ErrorCode.TRADE_BALANCE_INSUFFICIENT));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adapter.payFromBalance(USER_ID, ORDER_ID, AMOUNT, "123456"));
        assertEquals(ErrorCode.TRADE_BALANCE_INSUFFICIENT, ex.getErrorCode());
    }

    @Test
    @DisplayName("重复支付：幂等命中按成功返回，不当失败")
    void duplicatedPay() {
        when(deductService.deduct(anyString(), eq(USER_ID), anyString(), eq(ORDER_ID), eq(AMOUNT), anyString()))
                .thenReturn(new DeductResult(true, 9001L, 15_000L));

        PayFromBalanceResult result = adapter.payFromBalance(USER_ID, ORDER_ID, AMOUNT, "123456");

        assertTrue(result.duplicated());
        assertEquals(9001L, result.flowId());
    }
}
