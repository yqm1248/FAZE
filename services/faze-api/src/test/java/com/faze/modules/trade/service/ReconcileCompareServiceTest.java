/*
 * 职责：逐笔比对服务单测（第 6 段 D4「产出」要求的 5 个用例）。
 * 调用方：mvn test。
 * 数据来源：账单字节用真实 `MockBillParser` 解析；Mapper / 入账服务 / JsonMapper 全部 Mockito 替换。
 * 注意：
 *   1. 5 个用例是 D4 明文要求的：**三类差异各 1 / 自动补单 1 / 幂等重跑 1**。
 *      额外补两个「最容易写错」的：补偿单不许误报 local_only、退款行不许当收入。
 *   2. **本类不验事务**（没有 Spring 容器时 `@Transactional` 不生效）；验的是「该插几条、
 *      插的是什么类型、什么时候回写 auto_fixed」。
 *   3. 最关键的断言是「同一份账单跑两次，insert 只发生一次」——差异表是人工工单，
 *      重复行会让运营以为有两笔账要处理。
 */
package com.faze.modules.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faze.infra.pay.reconcile.BillParserFactory;
import com.faze.infra.pay.reconcile.MockBillParser;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.entity.ReconciliationDiff;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.mapper.ReconciliationDiffMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * 逐笔比对服务单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconcileCompareServiceTest {

    private static final String CHANNEL = PayChannel.MOCK.getCode();
    private static final LocalDate BILL_DATE = LocalDate.of(2026, 11, 7);

    /** 账单日的区间（东八区 2026-11-07 00:00 ~ 11-08 00:00 对应的 UTC）。 */
    private static final LocalDateTime DAY_BEGIN_UTC = LocalDateTime.of(2026, 11, 6, 16, 0);
    private static final LocalDateTime DAY_MID_UTC = LocalDateTime.of(2026, 11, 7, 2, 0);

    @Mock
    private ReconcileBillService reconcileBillService;

    @Mock
    private BillParserFactory billParserFactory;

    @Mock
    private RechargeRecordMapper rechargeRecordMapper;

    @Mock
    private ReconciliationDiffMapper reconciliationDiffMapper;

    @Mock
    private RechargeSettleService rechargeSettleService;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private ReconcileCompareService compareService;

    @BeforeEach
    void setUp() {
        when(billParserFactory.get(CHANNEL)).thenReturn(new MockBillParser());
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");
        // readTree 不 stub → 返回 null → extractPaidAt 捕获异常后返回 null（settle 用当前时间）
    }

    @Test
    @DisplayName("三类差异各 1 条：channel_only / local_only / amount_mismatch")
    void threeTypesOfDiff() {
        // 账单：RC01 正常 100、RC02 金额 99（本地 100 → amount_mismatch）、RC_ONLY 渠道独有
        when(reconcileBillService.loadLatestBill(CHANNEL, BILL_DATE))
                .thenReturn(bill("MOCKTXNRC01,RC01,100.00,2026-11-07 10:00:00,SUCCESS\n"
                        + "MOCKTXNRC02,RC02,99.00,2026-11-07 10:00:00,SUCCESS\n"
                        + "MOCKTXNONLY,RCONLY,50.00,2026-11-07 10:00:00,SUCCESS\n"));
        // 本地：RC01 success、RC02 success、RC03 success（账单里没有 → local_only）
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of(
                record(1L, "RC01", 10_000L, RechargeStatus.SUCCESS, DAY_BEGIN_UTC, DAY_MID_UTC),
                record(2L, "RC02", 10_000L, RechargeStatus.SUCCESS, DAY_BEGIN_UTC, DAY_MID_UTC),
                record(3L, "RC03", 30_000L, RechargeStatus.SUCCESS, DAY_BEGIN_UTC, DAY_MID_UTC)));

        ReconcileCompareService.CompareResult result = compareService.compare(CHANNEL, BILL_DATE);

        ArgumentCaptor<ReconciliationDiff> captor = ArgumentCaptor.forClass(ReconciliationDiff.class);
        verify(reconciliationDiffMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(ReconciliationDiff::getDiffType)
                .containsExactlyInAnyOrder("channel_only", "local_only", "amount_mismatch");
        assertThat(result.channelOnly()).isEqualTo(1);
        assertThat(result.localOnly()).isEqualTo(1);
        assertThat(result.amountMismatch()).isEqualTo(1);
        assertThat(result.written()).isEqualTo(3);
        // 正常的那条不落库
        assertThat(captor.getAllValues()).extracting(ReconciliationDiff::getChannelTxnId)
                .doesNotContain("MOCKTXNRC01");
    }

    @Test
    @DisplayName("channel_only 且本地有单 → 自动补单，差异行回写 auto_fixed")
    void autoFixChannelOnlyWithLocalRecord() {
        when(reconcileBillService.loadLatestBill(CHANNEL, BILL_DATE))
                .thenReturn(bill("MOCKTXNRC01,RC01,100.00,2026-11-07 10:00:00,SUCCESS\n"));
        // 本地 RC01 已超时关单（closed），渠道却收到了钱
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of(
                record(1L, "RC01", 10_000L, RechargeStatus.CLOSED, DAY_MID_UTC, null)));
        when(rechargeRecordMapper.selectById(1L))
                .thenReturn(record(1L, "RC01", 10_000L, RechargeStatus.CLOSED, DAY_MID_UTC, null));
        when(rechargeSettleService.settleByCallback(anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(RechargeSettleService.Outcome.COMPENSATED);

        ReconcileCompareService.CompareResult result = compareService.compare(CHANNEL, BILL_DATE);

        verify(reconciliationDiffMapper, times(1)).insert(any(ReconciliationDiff.class));
        verify(rechargeSettleService, times(1))
                .settleByCallback(anyString(), anyString(), anyLong(), any(), any());
        verify(reconciliationDiffMapper, times(1)).updateById(any(ReconciliationDiff.class));
        assertThat(result.autoFixed()).isEqualTo(1);
    }

    @Test
    @DisplayName("channel_only 但本地完全无单 → 只落差异，不补单（缺 user_id）")
    void channelOnlyWithoutLocalRecord() {
        when(reconcileBillService.loadLatestBill(CHANNEL, BILL_DATE))
                .thenReturn(bill("MOCKTXNONLY,RCONLY,50.00,2026-11-07 10:00:00,SUCCESS\n"));
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of());

        ReconcileCompareService.CompareResult result = compareService.compare(CHANNEL, BILL_DATE);

        verify(reconciliationDiffMapper, times(1)).insert(any(ReconciliationDiff.class));
        verify(rechargeSettleService, never()).settleByCallback(anyString(), anyString(), anyLong(), any(), any());
        assertThat(result.autoFixed()).isZero();
    }

    @Test
    @DisplayName("幂等重跑：差异行已存在时不再插入")
    void idempotentRerun() {
        when(reconcileBillService.loadLatestBill(CHANNEL, BILL_DATE))
                .thenReturn(bill("MOCKTXNONLY,RCONLY,50.00,2026-11-07 10:00:00,SUCCESS\n"));
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of());
        // 已存在同四元组的差异行
        when(reconciliationDiffMapper.selectCount(any())).thenReturn(1L);

        ReconcileCompareService.CompareResult result = compareService.compare(CHANNEL, BILL_DATE);

        verify(reconciliationDiffMapper, never()).insert(any(ReconciliationDiff.class));
        assertThat(result.written()).isZero();
    }

    @Test
    @DisplayName("已补过账的原单：账单有、本地有补偿单 success → 判无差异，不报 channel_only")
    void compensatedOriginIsNotADiff() {
        when(reconcileBillService.loadLatestBill(CHANNEL, BILL_DATE))
                .thenReturn(bill("MOCKTXNRC01,RC01,100.00,2026-11-07 10:00:00,SUCCESS\n"));
        // 原单 RC01 已 closed，补偿单 RC01-C 是 success
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of(
                record(1L, "RC01", 10_000L, RechargeStatus.CLOSED, DAY_BEGIN_UTC, null),
                record(2L, "RC01-C", 10_000L, RechargeStatus.SUCCESS, DAY_BEGIN_UTC, DAY_MID_UTC)));

        ReconcileCompareService.CompareResult result = compareService.compare(CHANNEL, BILL_DATE);

        verify(reconciliationDiffMapper, never()).insert(any(ReconciliationDiff.class));
        assertThat(result.channelOnly()).isZero();
        assertThat(result.localOnly()).isZero();
    }

    @Test
    @DisplayName("账单里的退款行不参与比对（渠道退款不是到账）")
    void refundRowIgnored() {
        when(reconcileBillService.loadLatestBill(CHANNEL, BILL_DATE))
                .thenReturn(bill("MOCKTXNRC01,RC01,100.00,2026-11-07 10:00:00,REFUND\n"));
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of());

        ReconcileCompareService.CompareResult result = compareService.compare(CHANNEL, BILL_DATE);

        verify(reconciliationDiffMapper, never()).insert(any(ReconciliationDiff.class));
        assertThat(result.billRows()).isEqualTo(1);
        assertThat(result.channelOnly()).isZero();
    }

    /**
     * 把数据行包成完整账单字节。
     *
     * @param dataRows 数据行（末尾带换行）
     * @return 账单字节
     */
    private byte[] bill(String dataRows) {
        String text = "#法泽FAZE Mock 渠道对账单\n"
                + "#账单日期,2026-11-07\n"
                + "#渠道,mock\n"
                + "渠道交易号,商户单号,金额,交易时间,状态\n"
                + dataRows
                + "#汇总,笔数,0,金额,0.00\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造本地充值单。
     *
     * @param id       主键
     * @param orderNo  单号
     * @param cents    金额（分）
     * @param status   状态
     * @param createdAt 创建时间（UTC，决定是否进候选集）
     * @param paidAt   支付时间（UTC，决定是否属于账单日）
     * @return 充值单
     */
    private RechargeRecord record(Long id, String orderNo, long cents, RechargeStatus status,
                                  LocalDateTime createdAt, LocalDateTime paidAt) {
        RechargeRecord record = new RechargeRecord();
        record.setId(id);
        record.setOrderNo(orderNo);
        record.setChannel(CHANNEL);
        record.setStatus(status.getCode());
        record.setAmountCents(cents);
        record.setCreatedAt(createdAt);
        record.setPaidAt(paidAt);
        return record;
    }
}
