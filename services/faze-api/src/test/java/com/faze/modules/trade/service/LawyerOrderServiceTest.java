/*
 * 职责：LawyerOrderService 的单测——接单/拒单/结束服务与越权（G3.3 / G3.4）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 LawyerQueryService / OrdersMapper / LawyerMapper /
 *          OrderStateService / OrderRefundService / AccountMapper。
 * 注意：
 *   1. **越权是重点**：订单不属于当前律师时，必须连状态迁移都不发生（405002）。
 *      这条用例防的是「先查出来再比对 lawyerId，然后忘写 if」的经典越权。
 *   2. 拒单必须同时调 `settleRefund`：只迁状态不退钱就是吞钱。
 *   3. 接单要断言 `order_count` 自增用的是原子 SQL（由 Mapper 调用体现，不比对 SQL 文本）。
 *   4. 非律师账号（找不到 lawyer 行）必须 401008（403），不是 404。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.mapper.AccountMapper;
import com.faze.modules.account.mapper.LawyerMapper;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 律师侧订单服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LawyerOrderServiceTest {

    private static final Long USER_ID = 1001L;
    private static final Long LAWYER_ID = 2002L;
    private static final Long ORDER_ID = 3003L;

    @Mock
    private LawyerQueryService lawyerQueryService;

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private LawyerMapper lawyerMapper;

    @Mock
    private OrderStateService orderStateService;

    @Mock
    private OrderRefundService orderRefundService;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private LawyerOrderService lawyerOrderService;

    /**
     * 初始化 MP 实体元数据（lambdaUpdate 的 set 会即时解析列名）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Lawyer.class);
        TableInfoHelper.initTableInfo(assistant, Orders.class);
    }

    @Test
    @DisplayName("接单：PAID→IN_SERVICE，并把律师接单数原子 +1")
    void acceptSuccess() {
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.of(lawyer()));
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);
        when(lawyerMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        lawyerOrderService.accept(USER_ID, ORDER_ID);

        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.PAID),
                eq(OrderStatus.IN_SERVICE), eq(OperatorRole.LAWYER), eq(USER_ID), anyString());
        verify(lawyerMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("越权：订单不属于本律师 → 405002，状态一次都不迁")
    void acceptOtherLawyerOrderRejected() {
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.of(lawyer()));
        // 归属进 SQL：查不到就是 null
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lawyerOrderService.accept(USER_ID, ORDER_ID));

        assertEquals(ErrorCode.TRADE_ORDER_NOT_FOUND, ex.getErrorCode());
        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("接单：状态已不是 PAID（CAS 未命中）→ 405003")
    void acceptWrongState() {
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.of(lawyer()));
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lawyerOrderService.accept(USER_ID, ORDER_ID));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(lawyerMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("拒单：PAID→REFUNDING 并触发自动退款（不能只改状态不退钱）")
    void rejectRefunds() {
        Orders order = order(OrderStatus.PAID);
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.of(lawyer()));
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);

        lawyerOrderService.reject(USER_ID, ORDER_ID, "临时有事");

        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.PAID),
                eq(OrderStatus.REFUNDING), eq(OperatorRole.LAWYER), eq(USER_ID), anyString());
        verify(orderRefundService).settleRefund(eq(order), anyString(), anyString());
    }

    @Test
    @DisplayName("结束服务：IN_SERVICE→COMPLETED")
    void completeSuccess() {
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.of(lawyer()));
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.IN_SERVICE));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);

        lawyerOrderService.complete(USER_ID, ORDER_ID);

        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.IN_SERVICE),
                eq(OrderStatus.COMPLETED), eq(OperatorRole.LAWYER), eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("非律师账号：401008（403），不是 404")
    void notLawyerRejected() {
        when(lawyerQueryService.findByUserId(USER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lawyerOrderService.complete(USER_ID, ORDER_ID));

        assertEquals(ErrorCode.COMMON_FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("律师侧动作推导：PAID 能接/拒，IN_SERVICE 能完成，其它态无动作")
    void lawyerActions() {
        assertEquals(java.util.List.of("accept", "reject"),
                com.faze.modules.trade.rule.OrderActionResolver.resolveForLawyer(OrderStatus.PAID));
        assertEquals(java.util.List.of("complete"),
                com.faze.modules.trade.rule.OrderActionResolver.resolveForLawyer(OrderStatus.IN_SERVICE));
        assertEquals(java.util.List.of(),
                com.faze.modules.trade.rule.OrderActionResolver.resolveForLawyer(OrderStatus.COMPLETED));
    }

    private Lawyer lawyer() {
        Lawyer lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setUserId(USER_ID);
        lawyer.setRealName("验收律师甲");
        lawyer.setAuditStatus("approved");
        return lawyer;
    }

    private Orders order(OrderStatus status) {
        Orders order = new Orders();
        order.setId(ORDER_ID);
        order.setOrderNo("OR20261106000304");
        order.setUserId(3L);
        order.setLawyerId(LAWYER_ID);
        order.setAmountCents(5000L);
        order.setLawyerIncomeCents(4250L);
        order.setStatus(status.getCode());
        return order;
    }
}
