/*
 * 职责：全项目**唯一**的 CSV 生成工具——UTF-8 with BOM、RFC 4180 转义、CSV 注入防护、文件名规范。
 * 调用方：modules/ops 的 5 处导出（敏感词 / 工单 / 操作日志 / 登录日志 / 看板报表）、
 *         对账差异导出（段 6 的 AdminReconciliationService）、留存期归档（LogRetentionCleanTask /
 *         LoginLogRetentionService）、异步导出 ExportService。
 * 数据来源：无（纯函数，入参是已组装好的表头与行）。
 * 注意：
 *   1. **必须带 UTF-8 BOM**：不带 BOM 的 UTF-8 CSV 在中文 Windows 的 Excel 里会按 GBK 解释，
 *      结果是乱码。这是「导出的中文在 Excel 打开不乱码」这条验收的唯一实现方式。
 *   2. **RFC 4180 转义**：字段含逗号 / 双引号 / 换行时整体用双引号包裹，
 *      字段内的双引号写成两个双引号。不转义的话，内容里的逗号会把一列拆成两列。
 *   3. **CSV 注入防护**：单元格以 `=` `+` `-` `@` 开头时前置单引号。
 *      否则 Excel 会把 `=cmd|...` 当公式执行（CSV Injection，OWASP 有专门条目）。
 *      负数的 `-` 也会被加引号：这是有意的保守处理——财务数字由前端负责，导出文件优先安全。
 *   4. 换行统一 CRLF：Excel 与 Windows 记事本都认，LF 在部分老版本 Excel 里会挤成一行。
 *   5. **文件名规则固定**：`{type}_{yyyyMMddHHmmss}.csv`。调用方传 `type`（如 `operation-logs`），
 *      时间戳由本类生成；传进来的值带 `.csv` 后缀会被剥掉，避免出现 `xxx.csv_2026...csv`。
 *   6. 本类**只生成字节与文件名，不写盘、不审计**——导出动作的审计（LOG_EXPORT）由调用方 Service 负责，
 *      因为「谁导出了什么」需要 AuditContext 里的操作人，而那不在本类的入参里。
 *   7. 本类取代段 1~6 的 `common/util/CsvWriter`（后者已删除，不允许再出现第二份 CSV 拼接代码）。
 */
package com.faze.common.export;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CSV 生成工具（UTF-8 with BOM + RFC 4180 + 防注入）。
 */
public final class CsvExporter {

    /** UTF-8 BOM 三字节；Excel 靠它识别编码。 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 行分隔符（CRLF）。 */
    private static final String CRLF = "\r\n";

    /** 文件名里的时间戳格式（北京时间，运营看得懂；文件内容里的时间由调用方决定口径）。 */
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai"));

    /** 需要前置单引号的危险首字符（CSV 注入向量）。 */
    private static final String DANGEROUS_PREFIX = "=+-@";

    /** 工具类禁止实例化。 */
    private CsvExporter() {
    }

    /**
     * 生成 CSV 与最终文件名。
     *
     * <p>无副作用（不写盘、不审计）。</p>
     *
     * @param type    导出类型（作为文件名前缀，如 `operation-logs`；带 `.csv` 后缀会自动剥掉）
     * @param headers 表头（不可为 null，可为空列表）
     * @param rows    数据行（每个元素为一行；null 单元格写成空串）
     * @return 文件名 + 字节 + 实际数据行数
     */
    public static ExportPayload export(String type, List<String> headers, List<List<Object>> rows) {
        String baseName = normalizeType(type);
        String fileName = baseName + "_" + FILE_TIME.format(java.time.ZonedDateTime.now())
                + ".csv";
        StringBuilder sb = new StringBuilder(2048);
        appendRow(sb, headers == null ? List.of() : headers);
        int dataRows = 0;
        if (rows != null) {
            for (List<Object> row : rows) {
                appendRow(sb, row);
                dataRows++;
            }
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return new ExportPayload(fileName, out, dataRows);
    }

    /**
     * 生成 CSV 字节（不含文件名生成），供归档等「文件名有自己的规则」的场景使用。
     *
     * @param headers 表头
     * @param rows    数据行（元素为任意对象，null 写空串）
     * @return UTF-8 with BOM 的字节
     */
    public static byte[] toBytes(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder(2048);
        appendRow(sb, headers == null ? List.of() : headers);
        if (rows != null) {
            for (List<Object> row : rows) {
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
    private static void appendRow(StringBuilder sb, List<?> row) {
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
     * 转义单个单元格（含防注入前缀）。
     *
     * @param value 原始值（null → 空串）
     * @return 转义后的值
     */
    public static String escape(Object value) {
        String cell = value == null ? "" : String.valueOf(value);
        if (cell.isEmpty()) {
            return "";
        }
        // 防注入：Excel 只认「开头字符」，先补单引号再走 RFC 4180 转义
        if (DANGEROUS_PREFIX.indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
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
     * 规整文件名前缀：去空白、去 `.csv` 后缀；空值回退 `export`。
     *
     * @param type 调用方传入的类型名
     * @return 可用作前缀的字符串
     */
    private static String normalizeType(String type) {
        String value = type == null ? "" : type.trim();
        if (value.toLowerCase().endsWith(".csv")) {
            value = value.substring(0, value.length() - 4);
        }
        return value.isEmpty() ? "export" : value;
    }

    /**
     * 生成结果：文件名 + 字节 + 数据行数。
     *
     * @param fileName 最终文件名（含 `.csv`，已带时间戳）
     * @param content  CSV 字节（含 UTF-8 BOM）
     * @param rowCount 数据行数（不含表头）
     */
    public record ExportPayload(String fileName, byte[] content, int rowCount) {
    }
}
