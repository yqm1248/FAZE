/*
 * 职责：OrderTimeoutService 的单测——关单与自动退款的分支（G2.4 / 2-A8 ①）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper / OrderStateService / OrderRefundService。
 * 注意：
 *   1. **已支付的订单收到关单任务必须什么都不做**：做了就是把用户付过钱的单关掉。
 *   2. **PAID 超时必须调 autoRefundFull**，不许只改状态——这是 2-A8 ①「余额退回且
 *      有流水」的单元级保证。
 *   3. 订单不存在（被清理）不能抛异常：任务队列不该被历史数据卡住。
 *   4. 未知任务类型要抛：让它进重试/死信，暴露给人工（PC A-26）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.faze.modules.trade.entity.Orders;
import com.faze.infra.mq.TaskQueue;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.faze.infra.mq.TaskQueueService;

/**
 * 订单超时处理测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderTimeoutServiceTest {

    private static final Long ORDER_ID = 3003L;
    private static final String ORDER_NO = "OR20261106000001";

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private OrderStateService orderStateService;

    @Mock
    private OrderRefundService orderRefundService;

    @InjectMocks
    private OrderTimeoutService orderTimeoutService;

    @Test
    @DisplayName("待支付超时：CAS 关单，原因写清楚")
    void closePendingOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PENDING));
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);

        orderTimeoutService.handle(task(TaskQueueService.TYPE_ORDER_CLOSE));

        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED), eq(OperatorRole.SYSTEM), isNull(), anyString());
    }

    @Test
    @DisplayName("已支付订单收到关单任务：什么都不做（不许把付过钱的单关掉）")
    void closeSkipsPaidOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));

        orderTimeoutService.handle(task(TaskQueueService.TYPE_ORDER_CLOSE));

        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("待支付超时遇到订单不存在：静默跳过，不抛异常")
    void closeSkipsMissingOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        orderTimeoutService.handle(task(TaskQueueService.TYPE_ORDER_CLOSE));

        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("已支付 30 分钟无人接：走自动全额退款（2-A8 ①）")
    void acceptTimeoutRefunds() {
        Orders order = order(OrderStatus.PAID);
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        orderTimeoutService.handle(task(TaskQueueService.TYPE_ORDER_ACCEPT_TIMEOUT));

        verify(orderRefundService).autoRefundFull(eq(order), anyString());
    }

    @Test
    @DisplayName("已被接单：不再退款")
    void acceptTimeoutSkipsInService() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.IN_SERVICE));

        orderTimeoutService.handle(task(TaskQueueService.TYPE_ORDER_ACCEPT_TIMEOUT));

        verify(orderRefundService, never()).autoRefundFull(any(), anyString());
    }

    @Test
    @DisplayName("未知任务类型：抛异常，交给重试/死信")
    void unknownTypeThrows() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PENDING));

        assertThrows(IllegalStateException.class,
                () -> orderTimeoutService.handle(task("unknown_type")));
    }

    private Orders order(OrderStatus status) {
        Orders order = new Orders();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setUserId(1001L);
        order.setAmountCents(5000L);
        order.setStatus(status.getCode());
        return order;
    }

    private TaskQueue task(String taskType) {
        TaskQueue task = new TaskQueue();
        task.setId(9001L);
        task.setTaskType(taskType);
        task.setBizNo(ORDER_NO);
        task.setRetryCount(0);
        return task;
    }
}
