/*
 * 职责：Mock 渠道对账单的本地生成器（第 6 段 D3 第 4 条）。
 * 调用方：modules/trade/service/ReconcileBillService（D3 下载任务，Mock 分支）。
 * 数据来源：`recharge_record`（该日 success 单，按 `paid_at` 归日）+ `BillInjection`（验收用差异注入）。
 * 注意：
 *   1. **格式与真实渠道账单同构**：表头 + 数据行 + 汇总行，`#` 开头的是元信息与汇总，
 *      MockBillParser 会跳过它们。这样 D4 验的是「读一份账单文件」，不是读内存对象。
 *   2. **默认与平台数据完全一致**（不注入时账单就是该日成功单的抄本）。差异只能通过
 *      `BillInjection` 显式注入，且注入规格来自 sys_config（生产为空）。
 *   3. 时间按**东八区**写进账单（真实账单就是渠道当地时区），`BillRow.paidAt` 是 UTC，
 *      用 `BillCsvSupport.utcToBillTime` 换算。
 *   4. 金额按**元、两位小数**写（真实账单的格式），分与元的换算走 `BillCsvSupport`。
 *   5. **本类不落盘、不写 file_record**：那两件事由调用方（Service）做，本类只产字节，
 *      便于单测直接用断言比对 CSV 文本。
 *   6. 只生成 `channel='mock'` 的单：真实渠道的账单必须来自渠道。
 */
package com.faze.infra.pay.reconcile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 渠道账单生成器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MockBillGenerator {

    /** 表头（MockBillParser 靠首列识别它）。 */
    private static final String HEADER = "渠道交易号,商户单号,金额,交易时间,状态";

    /** 渠道交易号前缀：与 MockPaymentGateway 的 `txnId` 生成规则**必须一致**。 */
    private static final String TXN_PREFIX = "MOCKTXN";

    private final RechargeRecordMapper rechargeRecordMapper;

    /**
     * 生成某日某渠道的 Mock 账单字节。
     *
     * @param billDate  账单日期（东八区）
     * @param injection 差异注入规格；null 或空表示不注入
     * @return UTF-8 的 CSV 字节
     */
    public byte[] generate(LocalDate billDate, BillInjection injection) {
        List<BillRow> rows = loadLocalRows(billDate);
        rows = applyInjection(rows, injection);
        rows.sort(Comparator.comparing(BillRow::getOrderNo));

        StringBuilder sb = new StringBuilder();
        sb.append("#法泽FAZE Mock 渠道对账单").append('\n');
        sb.append("#账单日期,").append(billDate).append('\n');
        sb.append("#渠道,").append(PayChannel.MOCK.getCode()).append('\n');
        sb.append(HEADER).append('\n');
        long totalCents = 0L;
        for (BillRow row : rows) {
            sb.append(row.getChannelTxnId()).append(',')
                    .append(row.getOrderNo()).append(',')
                    .append(BillCsvSupport.centsToYuan(row.getAmountCents())).append(',')
                    .append(BillCsvSupport.utcToBillTime(row.getPaidAt())).append(',')
                    .append(row.getStatus()).append('\n');
            totalCents += row.getAmountCents();
        }
        // 汇总行：真实账单都有，Mock 也保留，用来验「汇总行不算数据行」
        sb.append("#汇总,笔数,").append(rows.size())
                .append(",金额,").append(BillCsvSupport.centsToYuan(totalCents)).append('\n');
        log.info("Mock 账单生成 billDate={} rows={} totalCents={}", billDate, rows.size(), totalCents);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 读本地该日的 mock 成功单，转成账单行。
     *
     * @param billDate 账单日期（东八区）
     * @return 账单行
     */
    private List<BillRow> loadLocalRows(LocalDate billDate) {
        LocalDateTime begin = BillCsvSupport.startOfDayUtc(billDate);
        LocalDateTime end = BillCsvSupport.startOfNextDayUtc(billDate);
        List<RechargeRecord> records = rechargeRecordMapper.selectList(
                Wrappers.<RechargeRecord>lambdaQuery()
                        .eq(RechargeRecord::getChannel, PayChannel.MOCK.getCode())
                        .eq(RechargeRecord::getStatus, RechargeStatus.SUCCESS.getCode())
                        .ge(RechargeRecord::getPaidAt, begin)
                        .lt(RechargeRecord::getPaidAt, end));
        List<BillRow> rows = new ArrayList<>(records.size());
        for (RechargeRecord record : records) {
            BillRow row = new BillRow();
            row.setOrderNo(record.getOrderNo());
            row.setChannelTxnId(record.getChannelTxnId() == null
                    ? TXN_PREFIX + record.getOrderNo()
                    : record.getChannelTxnId());
            row.setAmountCents(record.getAmountCents() == null ? 0L : record.getAmountCents());
            row.setPaidAt(record.getPaidAt());
            row.setStatus(RechargeStatus.SUCCESS.getCode().toUpperCase());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 应用差异注入（验收专用，见 BillInjection 的注释）。
     *
     * @param localRows 本地抄本
     * @param injection 注入规格
     * @return 注入后的账单行
     */
    private List<BillRow> applyInjection(List<BillRow> localRows, BillInjection injection) {
        if (injection == null || injection.isEmpty()) {
            return new ArrayList<>(localRows);
        }
        Set<String> dropped = injection.getDropOrderNos() == null
                ? Set.of() : new HashSet<>(injection.getDropOrderNos());
        Map<String, Long> shifts = injection.getAmountShifts() == null
                ? Map.of() : injection.getAmountShifts();
        List<BillRow> result = new ArrayList<>(localRows.size() + 4);
        for (BillRow row : localRows) {
            if (dropped.contains(row.getOrderNo())) {
                continue;
            }
            Long shift = shifts.get(row.getOrderNo());
            if (shift != null) {
                row.setAmountCents(row.getAmountCents() + shift);
            }
            result.add(row);
        }
        if (injection.getExtraRows() != null) {
            result.addAll(injection.getExtraRows());
        }
        return result;
    }
}
