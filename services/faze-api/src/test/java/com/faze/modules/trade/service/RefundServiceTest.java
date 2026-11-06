/*
 * 职责：RefundService 的单测——退款申请的状态/金额/归属校验与「退款前状态反查」（G4.1）。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper / RefundRecordMapper / OrderStatusLogMapper / OrderStateService。
 * 注意：
 *   1. **金额上限按订单实付算**：部分退传超额必须 405005，且在插单/迁移**之前**就被拒——
 *      用例同时断言这两件事没发生，防「先落库后校验」。
 *   2. **COMPLETED 不允许申请退款**：段 2 没有申诉/裁定流程，放开就是「已完成也能随时退钱」。
 *   3. 归属校验进 SQL：订单不是我的一律 405002（不是 403），避免「探测哪些订单存在」。
 *   4. `resolveOrigin` 反查不到日志时兜底 PAID——这是最保守的回退目标（钱已付、活没开始）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.bo.RefundApplyBO;
import com.faze.modules.trade.entity.OrderStatusLog;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.RefundRecord;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrderStatusLogMapper;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.mapper.RefundRecordMapper;
import com.faze.modules.trade.vo.RefundVO;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
 * 退款申请服务测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceTest {

    private static final Long USER_ID = 5001L;
    private static final Long ORDER_ID = 6002L;
    private static final long ORDER_AMOUNT = 20_000L;

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private RefundRecordMapper refundRecordMapper;

    @Mock
    private OrderStatusLogMapper orderStatusLogMapper;

    @Mock
    private OrderStateService orderStateService;

    @InjectMocks
    private RefundService refundService;

    /**
     * 注册 lambda 列名解析所需的 TableInfo。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Orders.class);
        TableInfoHelper.initTableInfo(assistant, RefundRecord.class);
        TableInfoHelper.initTableInfo(assistant, OrderStatusLog.class);
    }

    /**
     * 造一笔本人的订单。
     *
     * @param status 订单状态
     * @return 订单实体
     */
    private Orders order(OrderStatus status) {
        Orders order = new Orders();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setLawyerId(7003L);
        order.setAmountCents(ORDER_AMOUNT);
        order.setStatus(status.getCode());
        return order;
    }

    /**
     * 造退款申请入参。
     *
     * @param reason 原因
     * @param amount 金额（可空）
     * @return 入参
     */
    private RefundApplyBO request(String reason, Long amount) {
        RefundApplyBO bo = new RefundApplyBO();
        bo.setReason(reason);
        bo.setAmountCents(amount);
        return bo;
    }

    /**
     * 全额申请：不传金额 → 用订单实付，插 applying 单并迁移到 REFUNDING。
     */
    @Test
    @DisplayName("申请全额：金额取订单实付，退款单 applying，订单迁 REFUNDING")
    void applyFullAmount() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));
        when(refundRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);

        RefundVO vo = refundService.apply(USER_ID, ORDER_ID, request("服务未开始", null));

        assertEquals(ORDER_AMOUNT, vo.getAmountCents());
        assertEquals(RefundService.STATUS_APPLYING, vo.getStatus());
        verify(refundRecordMapper).insert(any(RefundRecord.class));
        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.PAID), eq(OrderStatus.REFUNDING),
                eq(OperatorRole.USER), eq(USER_ID), anyString());
    }

    /**
     * 部分退：传了金额就用传的。
     */
    @Test
    @DisplayName("申请部分退款：金额取传入值")
    void applyPartialAmount() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.IN_SERVICE));
        when(refundRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(orderStateService.transit(anyLong(), any(), any(), any(), any(), anyString())).thenReturn(true);

        RefundVO vo = refundService.apply(USER_ID, ORDER_ID, request("只做了一半", 5_000L));

        assertEquals(5_000L, vo.getAmountCents());
        verify(orderStateService).transit(eq(ORDER_ID), eq(OrderStatus.IN_SERVICE),
                eq(OrderStatus.REFUNDING), eq(OperatorRole.USER), eq(USER_ID), anyString());
    }

    /**
     * 超额：405005，且不插单、不迁移。
     */
    @Test
    @DisplayName("超额退款：405005 且不落库不迁移")
    void applyExceedingAmount() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, ORDER_ID, request("多退点", ORDER_AMOUNT + 1)));

        assertEquals(ErrorCode.TRADE_REFUND_EXCEEDS, ex.getErrorCode());
        verify(refundRecordMapper, never()).insert(any(RefundRecord.class));
        verify(orderStateService, never()).transit(anyLong(), any(), any(), any(), any(), anyString());
    }

    /**
     * 0 元退款：405006。
     */
    @Test
    @DisplayName("0 元退款申请：405006")
    void applyZeroAmount() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, ORDER_ID, request("白退", 0L)));

        assertEquals(ErrorCode.TRADE_AMOUNT_INVALID, ex.getErrorCode());
    }

    /**
     * 非本人订单：405002（不是 403）。
     */
    @Test
    @DisplayName("非本人订单：405002")
    void applyOtherUserOrder() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, ORDER_ID, request("不是我的单", null)));

        assertEquals(ErrorCode.TRADE_ORDER_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 已完成订单不允许申请退款（段 2 无申诉流程）。
     */
    @Test
    @DisplayName("COMPLETED 申请退款：405003")
    void applyCompletedOrderRejected() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.COMPLETED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, ORDER_ID, request("做完了也想退", null)));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(refundRecordMapper, never()).insert(any(RefundRecord.class));
    }

    /**
     * 已有进行中的退款单：405004（同一订单只允许一张）。
     */
    @Test
    @DisplayName("重复申请：405004")
    void applyDuplicated() {
        RefundRecord applying = new RefundRecord();
        applying.setId(1L);
        applying.setOrderId(ORDER_ID);
        applying.setStatus(RefundService.STATUS_APPLYING);
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));
        when(refundRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(applying));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, ORDER_ID, request("再提一次", null)));

        assertEquals(ErrorCode.TRADE_DUPLICATE_SUBMIT, ex.getErrorCode());
    }

    /**
     * 反查退款前状态：取最近一条 to_status=refunding 的 from_status。
     */
    @Test
    @DisplayName("resolveOrigin：从状态流水反查退款前状态")
    void resolveOriginFromLog() {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(ORDER_ID);
        log.setFromStatus(OrderStatus.IN_SERVICE.getCode());
        log.setToStatus(OrderStatus.REFUNDING.getCode());
        when(orderStatusLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

        assertEquals(OrderStatus.IN_SERVICE, refundService.resolveOrigin(ORDER_ID));
    }

    /**
     * 反查不到日志：兜底 PAID。
     */
    @Test
    @DisplayName("resolveOrigin：无流水兜底 PAID")
    void resolveOriginFallback() {
        when(orderStatusLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertEquals(OrderStatus.PAID, refundService.resolveOrigin(ORDER_ID));
    }

    /**
     * 审批用：退款单不存在 → 405011。
     */
    @Test
    @DisplayName("审批不存在退款单：405011")
    void auditMissingRefund() {
        when(refundRecordMapper.selectById(anyLong())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.requireRefund(9999L));

        assertEquals(ErrorCode.TRADE_REFUND_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * 退款原因空白：参数缺失。
     */
    @Test
    @DisplayName("原因为空：参数缺失")
    void applyBlankReason() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(order(OrderStatus.PAID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.apply(USER_ID, ORDER_ID, request("   ", null)));

        assertEquals(ErrorCode.COMMON_PARAM_MISSING, ex.getErrorCode());
    }
}
