/*
 * 职责：Mock 账单生成器单测（第 6 段 D3）——生成 → 解析的闭环，以及差异注入的三条分支。
 * 调用方：mvn test。
 * 数据来源：`recharge_record` 用 Mockito 替换；解析用真实的 `MockBillParser`。
 * 注意：
 *   1. **闭环验的是「生成器产出的字节能被解析器读回来」**：这是 D3 第 4 条
 *      「生成的账单要能被 D4 的解析器读」的机器化证明，比人工看一眼 CSV 可靠。
 *   2. 三个注入项各一个用例，对应 D4 要求的三类差异——注入是**验收能重复跑**的前提
 *      （见 BillInjection 注释），所以它必须有单测，不能只靠手工验。
 *   3. 金额与时间必须往返相等：生成时 UTC→东八区写，解析时东八区→UTC 读回。
 *      差 8 小时的话 D4 会把「前一日的单」判成「不在账单日」，误报 local_only。
 */
package com.faze.infra.pay.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Mock 账单生成器单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MockBillGeneratorTest {

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 11, 7);

    @Mock
    private RechargeRecordMapper rechargeRecordMapper;

    @InjectMocks
    private MockBillGenerator generator;

    private final MockBillParser parser = new MockBillParser();

    @BeforeEach
    void setUp() {
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of(
                record(1L, "RC20261107000001", 10_000L, LocalDateTime.of(2026, 11, 7, 2, 0)),
                record(2L, "RC20261107000002", 20_000L, LocalDateTime.of(2026, 11, 7, 3, 30))));
    }

    @Test
    @DisplayName("不注入：生成 2 行，解析回来单号/金额/时间完全一致")
    void generateAndParseRoundTrip() {
        List<BillRow> rows = parser.parse(generator.generate(BILL_DATE, null));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(BillRow::getOrderNo)
                .containsExactly("RC20261107000001", "RC20261107000002");
        assertThat(rows.get(0).getAmountCents()).isEqualTo(10_000L);
        // 生成时 UTC 02:00 → 东八区 10:00 写入 → 解析回 UTC 02:00
        assertThat(rows.get(0).getPaidAt()).isEqualTo(LocalDateTime.of(2026, 11, 7, 2, 0));
        assertThat(rows.get(1).getPaidAt()).isEqualTo(LocalDateTime.of(2026, 11, 7, 3, 30));
    }

    @Test
    @DisplayName("注入 dropOrderNos：账单少一行（造 local_only）")
    void injectDropOrder() {
        BillInjection injection = new BillInjection();
        injection.setDropOrderNos(Set.of("RC20261107000002"));
        List<BillRow> rows = parser.parse(generator.generate(BILL_DATE, injection));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOrderNo()).isEqualTo("RC20261107000001");
    }

    @Test
    @DisplayName("注入 amountShifts：账单金额偏移（造 amount_mismatch）")
    void injectAmountShift() {
        BillInjection injection = new BillInjection();
        injection.setAmountShifts(Map.of("RC20261107000001", -500L));
        List<BillRow> rows = parser.parse(generator.generate(BILL_DATE, injection));
        BillRow first = rows.stream()
                .filter(r -> "RC20261107000001".equals(r.getOrderNo())).findFirst().orElseThrow();
        assertThat(first.getAmountCents()).isEqualTo(9_500L);
    }

    @Test
    @DisplayName("注入 extraRows：账单多一行平台没有的交易（造 channel_only）")
    void injectExtraRow() {
        BillRow extra = new BillRow();
        extra.setChannelTxnId("MOCKONLY001");
        extra.setOrderNo("RCMOCKONLY001");
        extra.setAmountCents(30_000L);
        extra.setPaidAt(LocalDateTime.of(2026, 11, 7, 5, 0));
        extra.setStatus("SUCCESS");
        BillInjection injection = new BillInjection();
        injection.setExtraRows(List.of(extra));
        List<BillRow> rows = parser.parse(generator.generate(BILL_DATE, injection));
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(BillRow::getOrderNo).contains("RCMOCKONLY001");
    }

    @Test
    @DisplayName("当天没有交易：只有表头与汇总，解析出 0 行")
    void emptyDay() {
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of());
        List<BillRow> rows = parser.parse(generator.generate(BILL_DATE, null));
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("channelTxnId 为空时用 MOCKTXN+单号兜底（与 MockPaymentGateway 的生成规则一致）")
    void channelTxnIdFallback() {
        RechargeRecord noTxn = record(3L, "RC20261107000003", 1_000L,
                LocalDateTime.of(2026, 11, 7, 4, 0));
        when(rechargeRecordMapper.selectList(any())).thenReturn(List.of(noTxn));
        List<BillRow> rows = parser.parse(generator.generate(BILL_DATE, null));
        assertThat(rows.get(0).getChannelTxnId()).isEqualTo("MOCKTXNRC20261107000003");
    }

    /**
     * 构造一条本地 success 单。
     *
     * @param id       主键
     * @param orderNo  单号
     * @param cents    金额（分）
     * @param paidAtUtc 支付时间（UTC）
     * @return 充值单
     */
    private RechargeRecord record(Long id, String orderNo, long cents, LocalDateTime paidAtUtc) {
        RechargeRecord record = new RechargeRecord();
        record.setId(id);
        record.setOrderNo(orderNo);
        record.setChannel(PayChannel.MOCK.getCode());
        record.setStatus(RechargeStatus.SUCCESS.getCode());
        record.setAmountCents(cents);
        record.setPaidAt(paidAtUtc);
        return record;
    }
}
