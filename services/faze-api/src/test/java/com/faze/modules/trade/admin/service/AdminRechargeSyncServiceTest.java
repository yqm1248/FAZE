/*
 * 职责：D1 管理端人工补单的四个结论分支单测（渠道 success / failed / 查不到 / 已终态），
 *       以及「本地无此单」的错误码。
 * 调用方：mvn test。
 * 数据来源：Mapper 与 PaymentGatewayFactory 全部 Mockito 替换；不连库、不启容器。
 * 注意：
 *   1. 本类**同时覆盖 `RechargeTaskService#onceQuery`**（真实对象，只把 Mapper/网关/入账服务换成 mock），
 *      因为 D1 的人工补单是「复用同一 handler 逻辑」——只测 Admin 层的映射等于没测 handler。
 *   2. 「死信」在自动任务里是任务表 retry_count 用尽；人工接口不经过任务表，
 *      对应的口径是「单已不在 pending」→ `not_pending`（工作流 D1 单测 4 用例的第 4 个）。
 *   3. 断言的是**结论字符串 + 出参状态**，不是异常类型：`channel_not_found` 必须是
 *      200 语义的正常返回，不是 500。
 */
package com.faze.modules.trade.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.pay.PaymentGateway;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.infra.pay.model.QueryResult;
import com.faze.modules.trade.admin.vo.AdminRechargeSyncResultVO;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.service.RechargeSettleService;
import com.faze.modules.trade.service.RechargeTaskService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * 管理端人工补单单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminRechargeSyncServiceTest {

    private static final String ORDER_NO = "RC20261108000042";
    private static final Long RECORD_ID = 8842L;
    private static final Long USER_ID = 7010L;
    private static final long AMOUNT = 20_000L;
    private static final String TXN_ID = "MOCKTXN" + ORDER_NO;

    @Mock
    private RechargeRecordMapper rechargeRecordMapper;

    @Mock
    private PaymentGatewayFactory paymentGatewayFactory;

    @Mock
    private RechargeSettleService rechargeSettleService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private JsonMapper jsonMapper;

    private RechargeTaskService rechargeTaskService;

    private AdminRechargeSyncService adminRechargeSyncService;

    @BeforeEach
    void setUp() {
        rechargeTaskService = new RechargeTaskService(rechargeRecordMapper,
                paymentGatewayFactory, rechargeSettleService);
        adminRechargeSyncService = new AdminRechargeSyncService(rechargeRecordMapper,
                rechargeTaskService, jsonMapper);
        when(paymentGatewayFactory.get(PayChannel.MOCK)).thenReturn(paymentGateway);
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test
    @DisplayName("渠道返回 success：补入账，syncResult=settled，状态变 success")
    void settledWhenChannelSuccess() {
        RechargeRecord pending = record(RechargeStatus.PENDING);
        RechargeRecord success = record(RechargeStatus.SUCCESS);
        success.setChannelTxnId(TXN_ID);
        success.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
        // before → onceQuery 内 → after
        when(rechargeRecordMapper.selectOne(any())).thenReturn(pending, pending, success);
        when(paymentGateway.query(ORDER_NO)).thenReturn(queryResult(RechargeStatus.SUCCESS));
        when(rechargeSettleService.settleByCallback(eq(ORDER_NO), eq(TXN_ID), eq(AMOUNT), any(), any()))
                .thenReturn(RechargeSettleService.Outcome.SETTLED);

        AdminRechargeSyncResultVO vo = adminRechargeSyncService.sync(ORDER_NO);

        assertThat(vo.getSyncResult()).isEqualTo("settled");
        assertThat(vo.getStatus()).isEqualTo(RechargeStatus.SUCCESS.getCode());
        assertThat(vo.getChannelTxnId()).isEqualTo(TXN_ID);
    }

    @Test
    @DisplayName("渠道返回 failed：单据置 failed，syncResult=marked_failed，不写流水")
    void markedFailedWhenChannelFailed() {
        RechargeRecord pending = record(RechargeStatus.PENDING);
        RechargeRecord failed = record(RechargeStatus.FAILED);
        when(rechargeRecordMapper.selectOne(any())).thenReturn(pending, pending, failed);
        when(paymentGateway.query(ORDER_NO)).thenReturn(queryResult(RechargeStatus.FAILED));
        when(rechargeRecordMapper.casTransit(eq(RECORD_ID), eq(RechargeStatus.PENDING.getCode()),
                eq(RechargeStatus.FAILED.getCode()), any(), any(), any(), any())).thenReturn(1);

        AdminRechargeSyncResultVO vo = adminRechargeSyncService.sync(ORDER_NO);

        assertThat(vo.getSyncResult()).isEqualTo("marked_failed");
        assertThat(vo.getStatus()).isEqualTo(RechargeStatus.FAILED.getCode());
    }

    @Test
    @DisplayName("渠道查不到：200 正常返回 syncResult=channel_not_found，状态仍 pending")
    void channelNotFound() {
        RechargeRecord pending = record(RechargeStatus.PENDING);
        when(rechargeRecordMapper.selectOne(any())).thenReturn(pending, pending, pending);
        when(paymentGateway.query(ORDER_NO)).thenReturn(queryResult(null));

        AdminRechargeSyncResultVO vo = adminRechargeSyncService.sync(ORDER_NO);

        assertThat(vo.getSyncResult()).isEqualTo("channel_not_found");
        assertThat(vo.getStatus()).isEqualTo(RechargeStatus.PENDING.getCode());
    }

    @Test
    @DisplayName("单已终态（自动任务到这一步会进死信 / 已处理）：syncResult=not_pending，不再查渠道")
    void notPendingWhenAlreadySettled() {
        RechargeRecord success = record(RechargeStatus.SUCCESS);
        success.setChannelTxnId(TXN_ID);
        when(rechargeRecordMapper.selectOne(any())).thenReturn(success);

        AdminRechargeSyncResultVO vo = adminRechargeSyncService.sync(ORDER_NO);

        assertThat(vo.getSyncResult()).isEqualTo("not_pending");
        assertThat(vo.getStatus()).isEqualTo(RechargeStatus.SUCCESS.getCode());
    }

    @Test
    @DisplayName("本地无此单：抛 405029（不是 200，也不是 500）")
    void notFoundThrows405029() {
        when(rechargeRecordMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> adminRechargeSyncService.sync(ORDER_NO))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TRADE_RECHARGE_NOT_FOUND);
    }

    /**
     * 造一张充值单。
     *
     * @param status 状态
     * @return 单据
     */
    private RechargeRecord record(RechargeStatus status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RechargeRecord record = new RechargeRecord();
        record.setId(RECORD_ID);
        record.setUserId(USER_ID);
        record.setOrderNo(ORDER_NO);
        record.setChannel(PayChannel.MOCK.getCode());
        record.setAmountCents(AMOUNT);
        record.setStatus(status.getCode());
        record.setExpireAt(now.plusMinutes(30));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    /**
     * 造渠道查单结果。
     *
     * @param status 渠道侧状态；null 表示查不到这笔单
     * @return 查询结果
     */
    private QueryResult queryResult(RechargeStatus status) {
        QueryResult result = new QueryResult();
        result.setChannel(PayChannel.MOCK);
        result.setOrderNo(ORDER_NO);
        result.setExists(status != null);
        result.setStatus(status);
        if (status != null) {
            result.setChannelTxnId(TXN_ID);
            result.setAmountCents(AMOUNT);
            result.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return result;
    }
}
