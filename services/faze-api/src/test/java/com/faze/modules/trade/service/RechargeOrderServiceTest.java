/*
 * 职责：App 充值单编排层的单元测试（第 6 段 B1）——校验顺序、幂等命中、渠道失败收敛、归属校验。
 * 调用方：mvn test（CI 与本地）。
 * 数据来源：全部依赖被 Mockito 替换，不连库、不连 Redis、不连渠道。
 * 注意：
 *   1. **本测试只验编排**：金额区间、未实名、单日限额的真实规则在 RechargeQuotaPolicyTest，
 *      这里把 RechargeQuotaPolicy 整体 mock 掉，避免重复测同一段逻辑。
 *   2. 幂等命中的断言点是「**不落新单、不调 prepay**」——这两件事任一发生就是资金事故
 *      （用户点两次出两张单 / 渠道侧重复下单）。
 *   3. prepay 抛异常时的断言点是「单被置 failed + 抛 405019 + **异常原文不出现在 message 里**」，
 *      对应 B1 与《后端代码规范》§5.4。
 */
package com.faze.modules.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.pay.PaymentGateway;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.infra.pay.model.PrepayResult;
import com.faze.modules.trade.bo.RechargeCreateBO;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.vo.BalanceVO;
import com.faze.modules.trade.vo.RechargeCreateVO;
import com.faze.modules.trade.vo.RechargeDetailVO;
import com.faze.modules.trade.vo.RechargeListPageVO;
import com.faze.modules.trade.vo.RechargeRowVO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

/**
 * 充值单编排层单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RechargeOrderServiceTest {

    private static final Long USER_ID = 7001L;
    private static final Long OTHER_USER_ID = 7002L;
    private static final Long RECORD_ID = 9001L;
    private static final String ORDER_NO = "RC20261108000001";
    private static final long AMOUNT_CENTS = 10_000L;

    @Mock
    private RechargeRecordMapper rechargeRecordMapper;

    @Mock
    private RechargeCreateExecutor rechargeCreateExecutor;

    @Mock
    private RechargeQuotaPolicy rechargeQuotaPolicy;

    @Mock
    private RechargeIdempotencyGuard rechargeIdempotencyGuard;

    @Mock
    private RechargePrepayStore rechargePrepayStore;

    @Mock
    private PaymentGatewayFactory paymentGatewayFactory;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private BalanceService balanceService;

    @InjectMocks
    private RechargeOrderService rechargeOrderService;

    @BeforeEach
    void setUp() {
        when(paymentGatewayFactory.get(any())).thenReturn(paymentGateway);
        when(rechargeQuotaPolicy.resolveExpireAt())
                .thenReturn(LocalDateTime.now().plusMinutes(30));
        when(rechargeIdempotencyGuard.tryStart(anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("金额为 null 时归一成 0 交给策略，不抛 NPE 也不落单")
    void create_shouldNormalizeNullAmountToZero() {
        doThrow(new BusinessException(ErrorCode.TRADE_RECHARGE_AMOUNT_INVALID))
                .when(rechargeQuotaPolicy).ensureAmountAllowed(anyLong());
        RechargeCreateBO bo = new RechargeCreateBO();
        bo.setChannel("mock");

        BusinessException ex = catchThrowableOfType(
                () -> rechargeOrderService.create(USER_ID, bo, null), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECHARGE_AMOUNT_INVALID);
        verify(rechargeQuotaPolicy).ensureAmountAllowed(0L);
        verify(rechargeCreateExecutor, never()).createPending(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("正常下单：落 pending 单 → prepay → 回写 prepayId → 绑定幂等键")
    void create_shouldCreatePendingThenPrepay() {
        RechargeCreateBO bo = new RechargeCreateBO();
        bo.setAmountCents(AMOUNT_CENTS);
        bo.setChannel("mock");
        when(rechargeCreateExecutor.createPending(any(), anyLong(), any(), any()))
                .thenReturn(pendingRecord(USER_ID));
        PrepayResult prepay = new PrepayResult();
        prepay.setChannel(PayChannel.MOCK);
        prepay.setPrepayId("MOCKPREPAY");
        prepay.setPayParams(Map.of("payUrl", "http://x"));
        when(paymentGateway.prepay(any(), any())).thenReturn(prepay);

        RechargeCreateVO vo = rechargeOrderService.create(USER_ID, bo, "idem-1");

        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getAmountCents()).isEqualTo(AMOUNT_CENTS);
        assertThat(vo.getChannel()).isEqualTo("mock");
        assertThat(vo.getChannelKey()).isEqualTo("trade.pay.channelMock");
        assertThat(vo.getPayParams()).containsEntry("payUrl", "http://x");
        verify(rechargeCreateExecutor).attachPrepayId(RECORD_ID, "MOCKPREPAY");
        verify(rechargePrepayStore).save(ORDER_NO, prepay.getPayParams());
        verify(rechargeIdempotencyGuard).bind(USER_ID, "idem-1", RECORD_ID);
    }

    @Test
    @DisplayName("幂等命中：返回原单、不落新单、不重新调渠道")
    void create_shouldReturnSameOrderOnIdempotentHit() {
        when(rechargeIdempotencyGuard.tryStart(USER_ID, "idem-1"))
                .thenReturn(Optional.of(RECORD_ID));
        when(rechargeRecordMapper.selectById(RECORD_ID)).thenReturn(pendingRecord(USER_ID));
        when(rechargePrepayStore.load(ORDER_NO)).thenReturn(Map.of("payUrl", "http://cached"));

        RechargeCreateBO bo = new RechargeCreateBO();
        bo.setAmountCents(AMOUNT_CENTS);
        bo.setChannel("mock");
        RechargeCreateVO vo = rechargeOrderService.create(USER_ID, bo, "idem-1");

        assertThat(vo.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.getPayParams()).containsEntry("payUrl", "http://cached");
        verify(rechargeCreateExecutor, never()).createPending(any(), anyLong(), any(), any());
        verify(paymentGateway, never()).prepay(any(), any());
    }

    @Test
    @DisplayName("渠道下单失败：单置 failed、抛 405019，且不把渠道异常原文透给客户端")
    void create_shouldMarkFailedWhenPrepayThrows() {
        RechargeCreateBO bo = new RechargeCreateBO();
        bo.setAmountCents(AMOUNT_CENTS);
        bo.setChannel("mock");
        when(rechargeCreateExecutor.createPending(any(), anyLong(), any(), any()))
                .thenReturn(pendingRecord(USER_ID));
        when(paymentGateway.prepay(any(), any()))
                .thenThrow(new IllegalStateException("渠道侧返回 500，含敏感内部标识"));

        BusinessException ex = catchThrowableOfType(
                () -> rechargeOrderService.create(USER_ID, bo, "idem-1"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED);
        assertThat(ex.getMessage()).doesNotContain("敏感内部标识");
        verify(rechargeCreateExecutor).markFailed(eq(RECORD_ID), any());
        verify(rechargeIdempotencyGuard).release(USER_ID, "idem-1");
    }

    @Test
    @DisplayName("明细：别人的单号查询抛 405007 且 HTTP 403")
    void detail_shouldRejectOtherUsersOrder() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(pendingRecord(OTHER_USER_ID));

        BusinessException ex = catchThrowableOfType(
                () -> rechargeOrderService.detail(USER_ID, ORDER_NO), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TRADE_NOT_ORDER_OWNER);
        assertThat(ex.getErrorCode().getHttpStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("明细：本人 pending 单返回状态与 i18n key，余额取流水汇总")
    void detail_shouldReturnStatusAndBalance() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(pendingRecord(USER_ID));
        BalanceVO balance = new BalanceVO();
        balance.setAvailableCents(1234L);
        when(balanceService.getBalance(USER_ID)).thenReturn(balance);

        RechargeDetailVO vo = rechargeOrderService.detail(USER_ID, ORDER_NO);

        assertThat(vo.getStatus()).isEqualTo(RechargeStatus.PENDING.getCode());
        assertThat(vo.getStatusKey()).isEqualTo("trade.recharge.statusPending");
        assertThat(vo.getBalanceAfterCents()).isEqualTo(1234L);
    }

    @Test
    @DisplayName("列表：多查一条判断 hasMore，nextCursor 是本页最后一条 id")
    void list_shouldUseCursorAndHasMore() {
        RechargeRecord first = pendingRecord(USER_ID);
        first.setId(11L);
        RechargeRecord second = pendingRecord(USER_ID);
        second.setId(10L);
        RechargeRecord overflow = pendingRecord(USER_ID);
        overflow.setId(9L);
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of(first, second, overflow));

        RechargeListPageVO page = rechargeOrderService.list(USER_ID, null, 2);

        assertThat(page.getList()).hasSize(2);
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(10L);
        assertThat(page.getList()).extracting(RechargeRowVO::getOrderNo).containsOnly(ORDER_NO);
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────────

    /**
     * 构造一张 pending 单。
     *
     * @param userId 归属用户
     * @return 充值单
     */
    private RechargeRecord pendingRecord(Long userId) {
        RechargeRecord record = new RechargeRecord();
        record.setId(RECORD_ID);
        record.setUserId(userId);
        record.setOrderNo(ORDER_NO);
        record.setChannel(PayChannel.MOCK.getCode());
        record.setAmountCents(AMOUNT_CENTS);
        record.setStatus(RechargeStatus.PENDING.getCode());
        record.setCreatedAt(LocalDateTime.now());
        record.setExpireAt(LocalDateTime.now().plusMinutes(30));
        return record;
    }
}
