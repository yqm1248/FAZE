/*
 * 职责：Mock 账单解析器与元转分工具的单测（第 6 段 D3「产出」要求的 4 个用例）。
 * 调用方：mvn test。
 * 数据来源：全部用内联 CSV 文本，不读文件、不连库。
 * 注意：
 *   1. 4 个用例是 D3 明文要求的：**5 行解析 / 元转分精度 / 空账单 / 格式异常**。
 *   2. 最关键的断言是「表头与汇总行不算数据行」——算进去会让 D4 把汇总金额当成一笔交易，
 *      产生一条假的 channel_only 差异。
 *   3. 元转分必须无精度丢失：`0.1` 元用 double 乘 100 会得到 10.000000000000002，
 *      这里用 `0.1 → 10`、`1.15 → 115`、`1234.56 → 123456` 三个值钉死。
 */
package com.faze.infra.pay.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mock 账单解析器单测。
 */
class MockBillParserTest {

    private final MockBillParser parser = new MockBillParser();

    /**
     * 构造一份 5 行数据的账单（含元信息、表头、汇总行）。
     *
     * @return CSV 文本
     */
    private String bill5Rows() {
        return "#法泽FAZE Mock 渠道对账单\n"
                + "#账单日期,2026-11-07\n"
                + "#渠道,mock\n"
                + "渠道交易号,商户单号,金额,交易时间,状态\n"
                + "MOCKTXNRC01,RC20261107000001,100.00,2026-11-07 10:00:00,SUCCESS\n"
                + "MOCKTXNRC02,RC20261107000002,0.10,2026-11-07 11:00:00,SUCCESS\n"
                + "MOCKTXNRC03,RC20261107000003,1.15,2026-11-07 12:00:00,SUCCESS\n"
                + "MOCKTXNRC04,RC20261107000004,5000.00,2026-11-07 13:00:00,SUCCESS\n"
                + "MOCKTXNRC05,RC20261107000005,1234.56,2026-11-07 14:00:00,REFUND\n"
                + "#汇总,笔数,5,金额,6335.81\n";
    }

    @Test
    @DisplayName("5 行数据：表头与汇总行都不算数据行")
    void parseFiveRows() {
        List<BillRow> rows = parser.parse(bill5Rows().getBytes(StandardCharsets.UTF_8));
        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(BillRow::getOrderNo)
                .containsExactly("RC20261107000001", "RC20261107000002", "RC20261107000003",
                        "RC20261107000004", "RC20261107000005");
    }

    @Test
    @DisplayName("金额转分无精度丢失：0.10 元 = 10 分、1.15 元 = 115 分、1234.56 元 = 123456 分")
    void amountPrecision() {
        List<BillRow> rows = parser.parse(bill5Rows().getBytes(StandardCharsets.UTF_8));
        assertThat(rows.get(0).getAmountCents()).isEqualTo(10_000L);
        assertThat(rows.get(1).getAmountCents()).isEqualTo(10L);
        assertThat(rows.get(2).getAmountCents()).isEqualTo(115L);
        assertThat(rows.get(3).getAmountCents()).isEqualTo(500_000L);
        assertThat(rows.get(4).getAmountCents()).isEqualTo(123_456L);
    }

    @Test
    @DisplayName("时间按东八区解析并转 UTC（10:00 东八区 = 02:00 UTC）")
    void paidAtIsUtc() {
        List<BillRow> rows = parser.parse(bill5Rows().getBytes(StandardCharsets.UTF_8));
        assertThat(rows.get(0).getPaidAt().getHour()).isEqualTo(2);
        assertThat(rows.get(0).getPaidAt().getDayOfMonth()).isEqualTo(7);
    }

    @Test
    @DisplayName("空账单 → 405028")
    void emptyBill() {
        assertBillInvalid(() -> parser.parse(new byte[0]), "账单文件为空");
    }

    @Test
    @DisplayName("只有表头没有数据行 → 空列表（不是异常）")
    void headerOnly() {
        String text = "渠道交易号,商户单号,金额,交易时间,状态\n";
        assertThat(parser.parse(text.getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    @DisplayName("缺表头 → 405028")
    void missingHeader() {
        String text = "MOCKTXNRC01,RC01,100.00,2026-11-07 10:00:00,SUCCESS\n";
        assertBillInvalid(() -> parser.parse(text.getBytes(StandardCharsets.UTF_8)), "表头");
    }

    @Test
    @DisplayName("列数不符 → 405028")
    void wrongColumnCount() {
        String text = "渠道交易号,商户单号,金额,交易时间,状态\n"
                + "MOCKTXNRC01,RC01,100.00\n";
        assertBillInvalid(() -> parser.parse(text.getBytes(StandardCharsets.UTF_8)), "列数");
    }

    @Test
    @DisplayName("金额非法（三位小数 / 非数字）→ 405028，不静默取整")
    void invalidAmount() {
        String text = "渠道交易号,商户单号,金额,交易时间,状态\n"
                + "MOCKTXNRC01,RC01,1.155,2026-11-07 10:00:00,SUCCESS\n";
        assertBillInvalid(() -> parser.parse(text.getBytes(StandardCharsets.UTF_8)), "转分");
    }

    @Test
    @DisplayName("时间格式非法 → 405028")
    void invalidTime() {
        String text = "渠道交易号,商户单号,金额,交易时间,状态\n"
                + "MOCKTXNRC01,RC01,100.00,2026/11/07 10:00,SUCCESS\n";
        assertBillInvalid(() -> parser.parse(text.getBytes(StandardCharsets.UTF_8)), "时间");
    }

    /**
     * 断言抛的是「账单非法」（405028），并核对 detail 片段。
     *
     * <p>`BusinessException#getMessage()` 返回的是错误码自带的 msg（响应体用），
     * 调用方传的补充说明在 `getDetail()` 里（只进日志）——所以这里查 detail，不查 message。</p>
     *
     * @param action         待执行动作
     * @param detailFragment detail 里应包含的片段
     */
    private void assertBillInvalid(Runnable action, String detailFragment) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRADE_RECONCILE_BILL_INVALID);
                    assertThat(e.getDetail()).contains(detailFragment);
                });
    }

    @Test
    @DisplayName("元↔分互转可逆")
    void yuanCentsRoundTrip() {
        assertThat(BillCsvSupport.yuanToCents("0.10")).isEqualTo(10L);
        assertThat(BillCsvSupport.centsToYuan(10L)).isEqualTo("0.10");
        assertThat(BillCsvSupport.yuanToCents(BillCsvSupport.centsToYuan(-500L))).isEqualTo(-500L);
    }
}
