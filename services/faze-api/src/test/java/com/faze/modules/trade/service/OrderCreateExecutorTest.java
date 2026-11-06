/*
 * 职责：OrderCreateExecutor 的单测——下单取价、平台费、状态流水与各类拒绝分支。
 * 调用方：mvn test（纯 Mockito，不启动 Spring、不连库）。
 * 数据来源：Mock 的 OrdersMapper / OrderNoGenerator / OrderStatusLogService /
 *          LawyerQueryService / SysConfigQueryService。
 * 注意：
 *   1. 重点断言 `2-A1` 的「服务端取价」：请求体里没有金额字段，落库金额必须等于律师价格。
 *   2. 「价格为空的律师不许下单成 0 元单」是工作流 G2.1 的明确判据，单独一条用例。
 *   3. 事务注解在单测里不生效（没有 Spring 代理），这里验证的是**方法内的决策**：
 *      该拒的拒、该写的写。真实事务回滚由 `OrderService` 的编排用例覆盖。
 */
package com.faze.modules.trade.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.account.service.LawyerQueryService;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.bo.OrderCreateBO;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrdersMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单执行器测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCreateExecutorTest {

    private static final Long USER_ID = 1001L;
    private static final Long LAWYER_ID = 2002L;
    private static final long CONSULT_PRICE = 5000L;

    @Mock
    private OrdersMapper ordersMapper;

    @Mock
    private OrderNoGenerator orderNoGenerator;

    @Mock
    private OrderStatusLogService orderStatusLogService;

    @Mock
    private LawyerQueryService lawyerQueryService;

    @Mock
    private SysConfigQueryService sysConfigQueryService;

    @InjectMocks
    private OrderCreateExecutor executor;

    private Lawyer lawyer;

    @BeforeEach
    void setUp() {
        lawyer = new Lawyer();
        lawyer.setId(LAWYER_ID);
        lawyer.setRealName("张三");
        lawyer.setAuditStatus("approved");
        lawyer.setOnlineStatus("online");
        lawyer.setServiceTextOn(true);
        lawyer.setConsultPriceCents(CONSULT_PRICE);
        lawyer.setPhonePriceCents(8000L);
        lawyer.setDocumentPriceCents(20000L);
        lawyer.setServicePhoneOn(true);
        lawyer.setServiceDocOn(true);

        when(lawyerQueryService.findById(LAWYER_ID)).thenReturn(Optional.of(lawyer));
        when(orderNoGenerator.nextOrderNo()).thenReturn("OR20261106000001");
        when(sysConfigQueryService.getString(eq(OrderCreateExecutor.CONFIG_PLATFORM_FEE_RATE), anyString()))
                .thenReturn("0.15");
        when(sysConfigQueryService.getBoolean(eq(OrderCreateExecutor.CONFIG_REQUIRE_LAWYER_ONLINE), eq(false)))
                .thenReturn(false);
        // 插入成功并回填主键（MP 的行为）
        doAnswer(invocation -> {
            Orders order = invocation.getArgument(0);
            order.setId(3003L);
            return 1;
        }).when(ordersMapper).insert(any(Orders.class));
    }

    @Test
    @DisplayName("正常下单：金额取律师价格、平台费 15%、状态 PENDING、写一条 from=null 的流水")
    void createSuccess() {
        Orders order = executor.create(USER_ID, request("consult_text"));

        assertEquals(3003L, order.getId());
        assertEquals(CONSULT_PRICE, order.getAmountCents());
        // 5000 × 0.15 = 750，四舍五入到分
        assertEquals(750L, order.getPlatformFeeCents());
        assertEquals(4250L, order.getLawyerIncomeCents());
        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());
        assertEquals(USER_ID, order.getUserId());
        assertEquals(LAWYER_ID, order.getLawyerId());

        verify(orderStatusLogService).record(eq(3003L), isNull(), eq(OrderStatus.PENDING),
                eq(OperatorRole.USER), eq(USER_ID), anyString());
    }

    @Test
    @DisplayName("价格为 null：明确拒（405009），不许下单成 0 元单")
    void nullPriceRejected() {
        lawyer.setConsultPriceCents(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("consult_text")));

        assertEquals(ErrorCode.TRADE_SERVICE_UNAVAILABLE, ex.getErrorCode());
        verify(ordersMapper, never()).insert(any(Orders.class));
    }

    @Test
    @DisplayName("价格为 0：同样拒（405009）")
    void zeroPriceRejected() {
        lawyer.setConsultPriceCents(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("consult_text")));

        assertEquals(ErrorCode.TRADE_SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("服务未上架：拒（405009）")
    void notListedRejected() {
        lawyer.setServiceTextOn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("consult_text")));

        assertEquals(ErrorCode.TRADE_SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("律师未通过审核：拒（405008）")
    void unapprovedLawyerRejected() {
        lawyer.setAuditStatus("pending");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("consult_text")));

        assertEquals(ErrorCode.TRADE_LAWYER_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("律师不存在：拒（405008）")
    void missingLawyerRejected() {
        when(lawyerQueryService.findById(LAWYER_ID)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("consult_text")));

        assertEquals(ErrorCode.TRADE_LAWYER_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    @DisplayName("case 类型本段不支持：拒（401002）")
    void caseTypeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("case")));

        assertEquals(ErrorCode.COMMON_PARAM_INVALID, ex.getErrorCode());
    }

    @Test
    @DisplayName("在线开关打开且律师离线：拒（405010）")
    void offlineRejectedWhenSwitchOn() {
        lawyer.setOnlineStatus("offline");
        when(sysConfigQueryService.getBoolean(eq(OrderCreateExecutor.CONFIG_REQUIRE_LAWYER_ONLINE), eq(false)))
                .thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.create(USER_ID, request("consult_text")));

        assertEquals(ErrorCode.TRADE_LAWYER_OFFLINE, ex.getErrorCode());
    }

    @Test
    @DisplayName("在线开关关闭且律师离线：允许下单（不写死误杀）")
    void offlineAllowedWhenSwitchOff() {
        lawyer.setOnlineStatus("offline");

        Orders order = executor.create(USER_ID, request("consult_text"));

        assertEquals(OrderStatus.PENDING.getCode(), order.getStatus());
    }

    @Test
    @DisplayName("单号撞唯一键：换号重试后成功")
    void retryOnDuplicateOrderNo() {
        when(orderNoGenerator.nextOrderNo())
                .thenReturn("OR20261106000001", "OR20261106000002");
        doAnswer(invocation -> {
            Orders order = invocation.getArgument(0);
            if ("OR20261106000001".equals(order.getOrderNo())) {
                throw new DuplicateKeyException("uk_orders_no");
            }
            order.setId(3004L);
            return 1;
        }).when(ordersMapper).insert(any(Orders.class));

        Orders order = executor.create(USER_ID, request("consult_text"));

        assertEquals(3004L, order.getId());
        assertEquals("OR20261106000002", order.getOrderNo());
        verify(ordersMapper, times(2)).insert(any(Orders.class));
    }

    @Test
    @DisplayName("电话咨询取 phone_price_cents")
    void phonePriceUsed() {
        Orders order = executor.create(USER_ID, request("consult_phone"));

        assertEquals(8000L, order.getAmountCents());
        assertEquals(1200L, order.getPlatformFeeCents());
        assertEquals(6800L, order.getLawyerIncomeCents());
    }

    private OrderCreateBO request(String serviceType) {
        OrderCreateBO bo = new OrderCreateBO();
        bo.setLawyerId(LAWYER_ID);
        bo.setServiceType(serviceType);
        bo.setRemark("我想咨询一下");
        return bo;
    }
}
