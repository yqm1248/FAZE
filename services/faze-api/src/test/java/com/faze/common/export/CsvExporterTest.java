/*
 * 职责：`common/export/CsvExporter` 的单元测试——BOM、RFC 4180 转义、CSV 注入防护、文件名规则。
 * 调用方：`mvn test` 自动执行（F4 验收：导出内容含中文/逗号/引号/换行、`=1+1` 被前置单引号）。
 * 数据来源：无（纯函数测试）。
 * 注意：
 *   1. **必须断言前三个字节是 UTF-8 BOM**（EF BB BF）：这是「Excel 打开中文不乱码」的唯一实现方式，
 *      改成无 BOM 会让 Excel 按 GBK 解释，中文全成乱码。
 *   2. 防注入断言用 `'=1+1`（前置单引号），不是去掉等号——去掉等号会改变用户数据。
 *   3. 文件名断言只校验形状（`{type}_{yyyyMMddHHmmss}.csv`），不校验具体时间。
 */
package com.faze.common.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV 导出工具测试。
 */
class CsvExporterTest {

    /**
     * 生成结果必须带 UTF-8 BOM。
     */
    @Test
    @DisplayName("CSV 带 UTF-8 BOM（Excel 中文不乱码）")
    void hasUtf8Bom() {
        byte[] bytes = CsvExporter.toBytes(List.of("列1"), List.of(List.of((Object) "中文")));
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains("列1").contains("中文");
    }

    /**
     * RFC 4180：逗号 / 双引号 / 换行都要转义。
     */
    @Test
    @DisplayName("RFC4180：含逗号、双引号、换行的字段被双引号包裹")
    void escapesRfc4180() {
        assertThat(CsvExporter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvExporter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvExporter.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvExporter.escape("plain")).isEqualTo("plain");
        assertThat(CsvExporter.escape(null)).isEmpty();
    }

    /**
     * CSV 注入：= + - @ 开头要前置单引号。
     */
    @Test
    @DisplayName("CSV 注入防护：= + - @ 开头前置单引号")
    void preventsCsvInjection() {
        assertThat(CsvExporter.escape("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvExporter.escape("+86")).isEqualTo("'+86");
        assertThat(CsvExporter.escape("-1")).isEqualTo("'-1");
        assertThat(CsvExporter.escape("@cmd")).isEqualTo("'@cmd");
        // 中间出现不算注入（Excel 只认首个字符）
        assertThat(CsvExporter.escape("a=1")).isEqualTo("a=1");
    }

    /**
     * 文件名规则 `{type}_{yyyyMMddHHmmss}.csv`。
     */
    @Test
    @DisplayName("文件名按 {type}_{yyyyMMddHHmmss}.csv 生成")
    void fileNaming() {
        CsvExporter.ExportPayload payload = CsvExporter.export("operation-logs",
                List.of("a"), List.of(List.of((Object) "1")));
        assertThat(payload.fileName()).matches("operation-logs_\\d{14}\\.csv");
        assertThat(payload.rowCount()).isEqualTo(1);
        // 传入带 .csv 后缀的 type 不会产生 xxx.csv_...csv
        assertThat(CsvExporter.export("tickets.csv", List.of("a"), List.of()).fileName())
                .matches("tickets_\\d{14}\\.csv");
        // 空 type 回退 export
        assertThat(CsvExporter.export("", List.of(), List.of()).fileName()).matches("export_\\d{14}\\.csv");
    }

    /**
     * 多行多列时行数统计正确（不含表头）。
     */
    @Test
    @DisplayName("rowCount 只算数据行，不含表头")
    void rowCountExcludesHeader() {
        CsvExporter.ExportPayload payload = CsvExporter.export("x",
                List.of("h1", "h2"),
                List.of(List.of((Object) "1", 2), List.of((Object) "3", 4), List.of((Object) "5", 6)));
        assertThat(payload.rowCount()).isEqualTo(3);
        String body = new String(payload.content(), 3, payload.content().length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains("h1,h2").contains("1,2");
    }
}
