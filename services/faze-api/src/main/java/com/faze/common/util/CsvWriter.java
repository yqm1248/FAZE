/*
 * 职责：CSV 生成工具——统一处理「Excel 打开不乱码」与字段转义。
 * 调用方：modules/ops 的日志导出（A-26）、工单导出（A-21）、看板报表导出（A-02）、
 *         敏感词库导出（A-25）。
 * 数据来源：无（纯函数，入参是已组装好的表头与行）。
 * 注意：
 *   1. **必须带 UTF-8 BOM**：不带 BOM 的 UTF-8 CSV 在中文 Windows 的 Excel 里会按 GBK 解释，
 *      结果是乱码。这是「导出的中文在 Excel 打开不乱码」这条验收的唯一实现方式。
 *   2. **转义规则**（RFC 4180）：字段含逗号 / 双引号 / 换行时整体用双引号包裹，
 *      字段内的双引号写成两个双引号。不转义的话，内容里的逗号会把一列拆成两列。
 *   3. 换行统一用 CRLF：Excel 与 Windows 记事本都认，LF 在部分老版本 Excel 里会挤成一行。
 *   4. 本类只生成字节，**不负责写盘、不负责审计**——导出动作本身要审计（LOG_EXPORT），
 *      那是调用方 Service 的事。
 */
package com.faze.common.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 生成工具（UTF-8 with BOM）。
 */
public final class CsvWriter {

    /** UTF-8 BOM 三字节；Excel 靠它识别编码。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 行分隔符（CRLF）。 */
    private static final String CRLF = "\r\n";

    /** 工具类，禁止实例化。 */
    private CsvWriter() {
    }

    /**
     * 生成 CSV 字节（含 BOM）。
     *
     * @param headers 表头（不可为 null，可为空列表）
     * @param rows    数据行（每行单元格数应与表头一致；不强制校验，长度不足的行会少列）
     * @return UTF-8 with BOM 的字节数组
     */
    public static byte[] toBytes(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder(1024);
        appendRow(sb, headers);
        if (rows != null) {
            for (List<String> row : rows) {
                appendRow(sb, row);
            }
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    /**
     * 追加一行。
     *
     * @param sb  目标缓冲
     * @param row 单元格列表（null 元素写成空串）
     */
    private static void appendRow(StringBuilder sb, List<String> row) {
        if (row == null) {
            sb.append(CRLF);
            return;
        }
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(row.get(i)));
        }
        sb.append(CRLF);
    }

    /**
     * 转义单个单元格。
     *
     * @param cell 原始值
     * @return 转义后的值（必要时加双引号）
     */
    public static String escape(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        boolean needQuote = cell.indexOf(',') >= 0
                || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0
                || cell.indexOf('\r') >= 0;
        if (!needQuote) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    /**
     * 值转字符串，null 写成空串（导出时不要出现字面 "null"）。
     *
     * @param value 任意值
     * @return 字符串
     */
    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
