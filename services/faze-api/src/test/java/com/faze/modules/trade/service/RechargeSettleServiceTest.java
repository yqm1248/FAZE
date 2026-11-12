/*
 * 职责：充值入账链路单测（第 6 段 C3/C4）——五种结果分支与「只入账一次」的关键断言。
 * 调用方：mvn test。
 * 数据来源：Mapper 与 TaskQueueService 全部 Mockito 替换；不连库、不启容器。
 * 注意：
 *   1. 本类**不验证事务回滚**（没有 Spring 容器时 `@Transactional` 不生效），
 *      回滚由「同一事务内调用」这一结构性事实保证；这里验的是**分支选择**与
 *      「不该写流水的时候一条都没写」。
 *   2. 最关键的断言是「CAS 影响 0 行时不写任何流水」——并发两个回调都进到这里时，
 *      只有一个写，另一个必须原样退出。写错了就是重复入账（资金事故）。
 */
package com.faze.modules.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import java.time.LocalDateTime;
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
 * 充值入账单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RechargeSettleServiceTest {

    private static final Long USER_ID = 7001L;
    private static final Long RECORD_ID = 9001L;
    private static final String ORDER_NO = "RC20261108000001";
    private static final String TXN_ID = "MOCKTXN" + ORDER_NO;
    private static final long AMOUNT = 10_000L;

    @Mock
    private RechargeRecordMapper rechargeRecordMapper;

    @Mock
    private BalanceFlowMapper balanceFlowMapper;

    @Mock
    private UserBalanceMapper userBalanceMapper;

    @Mock
    private TaskQueueService taskQueueService;

    @InjectMocks
    private RechargeSettleService rechargeSettleService;

    @BeforeEach
    void setUp() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(record(RechargeStatus.PENDING));
        when(rechargeRecordMapper.casTransit(anyLong(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        UserBalance cache = new UserBalance();
        cache.setUserId(USER_ID);
        cache.setAvailableCents(100_000L);
        cache.setFrozenCents(2_000L);
        when(userBalanceMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(cache);
    }

    @Test
    @DisplayName("单不存在：NOT_FOUND，不写流水")
    void settle_shouldReturnNotFound() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(null);

        assertThat(rechargeSettleService.settleByCallback(ORDER_NO, TXN_ID, AMOUNT, null, null))
                .isEqualTo(RechargeSettleService.Outcome.NOT_FOUND);
        verify(balanceFlowMapper, never()).insert(any(com.faze.modules.trade.entity.BalanceFlow.class));
    }

    @Test
    @DisplayName("金额不一致：AMOUNT_MISMATCH，不入账（防篡改）")
    void settle_shouldRejectAmountMismatch() {
        assertThat(rechargeSettleService.settleByCallback(ORDER_NO, TXN_ID, 9_999L, null, null))
                .isEqualTo(RechargeSettleService.Outcome.AMOUNT_MISMATCH);
        verify(balanceFlowMapper, never()).insert(any(com.faze.modules.trade.entity.BalanceFlow.class));
        verify(rechargeRecordMapper, never())
                .casTransit(anyLong(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("已 success：ALREADY_SETTLED，不再入账")
    void settle_shouldBeIdempotentForSettledOrder() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(record(RechargeStatus.SUCCESS));

        assertThat(rechargeSettleService.settleByCallback(ORDER_NO, TXN_ID, AMOUNT, null, null))
                .isEqualTo(RechargeSettleService.Outcome.ALREADY_SETTLED);
        verify(balanceFlowMapper, never()).insert(any(com.faze.modules.trade.entity.BalanceFlow.class));
    }

    @Test
    @DisplayName("CAS 影响 0 行（并发回调已接管）：ALREADY_SETTLED 且不写流水")
    void settle_shouldNotCreditWhenCasLoses() {
        when(rechargeRecordMapper.casTransit(anyLong(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThat(rechargeSettleService.settleByCallback(ORDER_NO, TXN_ID, AMOUNT, null, null))
                .isEqualTo(RechargeSettleService.Outcome.ALREADY_SETTLED);
        verify(balanceFlowMapper, never()).insert(any(com.faze.modules.trade.entity.BalanceFlow.class));
        verify(taskQueueService, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("正常入账：写且只写一条流水、更新余额、投递事件")
    void settle_shouldCreditOnce() {
        assertThat(rechargeSettleService.settleByCallback(ORDER_NO, TXN_ID, AMOUNT, null, null))
                .isEqualTo(RechargeSettleService.Outcome.SETTLED);
        verify(balanceFlowMapper).insert(any(com.faze.modules.trade.entity.BalanceFlow.class));
        verify(userBalanceMapper).updateById(any(UserBalance.class));
        verify(taskQueueService).enqueue(eq(RechargeSettleService.TASK_RECHARGE_SUCCESS),
                eq(ORDER_NO), any(LocalDateTime.class), any());
    }

    @Test
    @DisplayName("晚到回调（原单 closed）：开补偿单入账，原单不改状态")
    void settle_shouldCompensateForClosedOrder() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(record(RechargeStatus.CLOSED));

        assertThat(rechargeSettleService.settleByCallback(ORDER_NO, TXN_ID, AMOUNT, null, null))
                .isEqualTo(RechargeSettleService.Outcome.COMPENSATED);
        // 补偿单要插入；原单绝不能被 CAS 改成 success
        verify(rechargeRecordMapper).insert(any(RechargeRecord.class));
        verify(rechargeRecordMapper, never())
                .casTransit(anyLong(), anyString(), anyString(), any(), any(), any(), any());
        verify(balanceFlowMapper).insert(any(com.faze.modules.trade.entity.BalanceFlow.class));
    }

    /**
     * 构造一张充值单。
     *
     * @param status 状态
     * @return 充值单
     */
    private RechargeRecord record(RechargeStatus status) {
        RechargeRecord record = new RechargeRecord();
        record.setId(RECORD_ID);
        record.setUserId(USER_ID);
        record.setOrderNo(ORDER_NO);
        record.setChannel(PayChannel.MOCK.getCode());
        record.setAmountCents(AMOUNT);
        record.setStatus(status.getCode());
        return record;
    }
}
