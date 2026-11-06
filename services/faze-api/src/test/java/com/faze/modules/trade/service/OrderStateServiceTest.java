/*
 * 职责：OrderStateService 的单测——CAS 命中/未命中、非法迁移、时间字段只写对应列。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper 与 OrderStatusLogService。
 * 注意：
 *   1. CAS 未命中必须返回 false 且**不写状态流水**：写了日志却没改状态，
 *      等于给 2-A4 的时间线造一条假记录。
 *   2. 非法迁移在碰 SQL 之前就要抛（由 OrderStatusMachine 判定），
 *      所以断言 mapper 一次都没被调用。
 *   3. 「时间字段只写对应列」靠条件式 set 保证：这里断言 update 调用本身完成，
 *      具体 SQL 由集成验证覆盖（单测不拼 SQL）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单状态迁移器测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderStateServiceTest {

    private static final Long ORDER_ID = 3003L;
    private static final Long USER_ID = 1001L;

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private OrderStatusLogService orderStatusLogService;

    @InjectMocks
    private OrderStateService orderStateService;

    /**
     * 初始化 MP 的实体元数据缓存。
     *
     * <p>`LambdaUpdateWrapper.set(Orders::getXxx, ...)` 会**立即解析列名**，而纯 Mockito 测试
     * 没有 MyBatis 上下文，不初始化就会抛 "can not find lambda cache for this entity"。
     * `eq()` / `in()` 是惰性解析，所以只有本类（用了 set）需要这段。</p>
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Orders.class);
    }

    @Test
    @DisplayName("CAS 命中：返回 true 并写一条状态流水")
    void transitSuccess() {
        when(ordersMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        boolean ok = orderStateService.transit(ORDER_ID, OrderStatus.PENDING, OrderStatus.CANCELLED,
                OperatorRole.USER, USER_ID, "用户取消订单");

        assertTrue(ok);
        verify(orderStatusLogService).record(eq(ORDER_ID), eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED), eq(OperatorRole.USER), eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("CAS 未命中：返回 false 且不写状态流水")
    void transitCasMiss() {
        when(ordersMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        boolean ok = orderStateService.transit(ORDER_ID, OrderStatus.PENDING, OrderStatus.CANCELLED,
                OperatorRole.USER, USER_ID, "用户取消订单");

        assertFalse(ok);
        verify(orderStatusLogService, never()).record(anyLong(), any(), any(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("非法迁移：在碰 SQL 之前抛 405003")
    void illegalTransitRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderStateService.transit(ORDER_ID, OrderStatus.PENDING, OrderStatus.COMPLETED,
                        OperatorRole.USER, USER_ID, null));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
        verify(ordersMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("角色不允许的迁移同样被拒：用户不能自己接单")
    void wrongRoleRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderStateService.transit(ORDER_ID, OrderStatus.PAID, OrderStatus.IN_SERVICE,
                        OperatorRole.USER, USER_ID, null));

        assertEquals(ErrorCode.TRADE_ORDER_STATE_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("currentStatus：订单不存在返回 null")
    void currentStatusMissing() {
        when(ordersMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertEquals(null, orderStateService.currentStatus(ORDER_ID));
    }
}
