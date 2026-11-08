/*
 * 职责：Mock 渠道对账单解析器（第 6 段 D3）——解析 MockBillGenerator 生成的 CSV。
 * 调用方：infra/pay/reconcile/BillParserFactory；D3 下载任务、D4 比对任务。
 * 数据来源：MockBillGenerator 生成的 CSV 字节。
 * 注意：
 *   1. **本解析器是"真的"解析器**，不是骨架：它读的是真实生成的 CSV 字节，D4 比对用它，
 *      所以 6-A4 的「差异识别」验的是真实解析→比对链路，不是特判。
 *   2. 格式（与真实渠道账单同构：表头 + 数据行 + 汇总行）：
 *        #法泽FAZE Mock 渠道对账单
 *        #账单日期,2026-11-07
 *        渠道交易号,商户单号,金额,交易时间,状态
 *        MOCKTXN...,RC...,100.00,2026-11-07 10:00:00,SUCCESS
 *        #汇总,笔数,1,金额,100.00
 *      `#` 开头 = 元信息或汇总，**不算数据行**；第一个非 `#` 行是表头，也不是数据行。
 *   3. 金额走 `BillCsvSupport.yuanToCents`（BigDecimal），时间走 `billTimeToUtc`（东八区→UTC）。
 *   4. 任一数据行解析失败**整份账单失败**（抛 405019）：账单是财务凭证，宁可整份不入库，
 *      也不要"跳过坏行"造成差异漏报。
 */
package com.faze.infra.pay.reconcile;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.enums.PayChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 渠道账单解析器。
 */
@Component
@Slf4j
public class MockBillParser implements ReconcileBillParser {

    /** 表头首列（用于识别表头行）。 */
    private static final String HEADER_FIRST_CELL = "渠道交易号";

    /** 数据行列数。 */
    private static final int COLUMNS = 5;

    @Override
    public String channel() {
        return PayChannel.MOCK.getCode();
    }

    /**
     * 解析 Mock 账单 CSV。
     *
     * @param content 账单文件字节（UTF-8）
     * @return 数据行
     * @throws BusinessException 文件为空 / 列数不符 / 金额或时间非法
     */
    @Override
    public List<BillRow> parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID, "账单文件为空");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        List<BillRow> rows = new ArrayList<>();
        boolean headerSkipped = false;
        for (String raw : BillCsvSupport.lines(text)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                // 元信息 / 汇总行 / 空行：都不是数据行
                continue;
            }
            if (!headerSkipped) {
                if (!line.startsWith(HEADER_FIRST_CELL)) {
                    throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                            "账单缺少表头，首行不是 " + HEADER_FIRST_CELL);
                }
                headerSkipped = true;
                continue;
            }
            rows.add(parseRow(line));
        }
        if (!headerSkipped) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID, "账单缺少表头");
        }
        log.info("Mock 账单解析完成 rows={}", rows.size());
        return rows;
    }

    /**
     * 解析单行。
     *
     * @param line 数据行
     * @return 账单行
     * @throws BusinessException 列数不符 / 金额或时间非法
     */
    private BillRow parseRow(String line) {
        String[] cells = line.split(",", -1);
        if (cells.length != COLUMNS) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID,
                    "账单行列数应为 " + COLUMNS + "，实际 " + cells.length);
        }
        BillRow row = new BillRow();
        row.setChannelTxnId(cells[0].trim());
        row.setOrderNo(cells[1].trim());
        try {
            row.setAmountCents(BillCsvSupport.yuanToCents(cells[2]));
            row.setPaidAt(BillCsvSupport.billTimeToUtc(cells[3]));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.TRADE_RECONCILE_BILL_INVALID, e.getMessage());
        }
        row.setStatus(cells[4].trim().toUpperCase());
        return row;
    }
}
