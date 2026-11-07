/*
 * 职责：PdfMetadataWriter 的单测（段 4 D3 自查清单：5 个字段能读出、**没有** AIGenerated、时间带时区）。
 * 调用方：`mvn test`（surefire）。
 * 数据来源：PDFBox 在内存里造的空白 PDF。
 * 注意：
 *   1. `4-A6` 是这条单测的验收锚点：它明确要求「反向断言：全文件搜 AIGenerated / Faze-Content-Type → 0 处」。
 *      技术路线 §12.3.2 那 5 个隐式标识字段只适用于 AI 生成内容，文书不是 AIGC，写了就是虚假标识。
 *   2. 时间必须带时区：断言 `Faze-Generated-At` 以 `+00:00` 结尾（本项目生成时间统一 UTC）。
 */
package com.faze.infra.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/**
 * PDF 溯源元数据写入器的单测。
 */
class PdfMetadataWriterTest {

    private final PdfMetadataWriter writer = new PdfMetadataWriter();

    /**
     * 造一份单页空白 PDF。
     *
     * @return PDF 字节
     * @throws IOException 保存失败
     */
    private byte[] blankPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(595f, 842f)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 5 个溯源字段都写进去了，值也对。
     *
     * @throws IOException 读写失败
     */
    @Test
    void writesAllFiveFazeFields() throws IOException {
        byte[] pdf = writer.write(blankPdf(), 42L, "LAWSUIT-001", 3, "民事起诉状");
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            assertEquals("42", custom(info, "Faze-Document-Id"));
            assertEquals("LAWSUIT-001", custom(info, "Faze-Template-Code"));
            assertEquals("3", custom(info, "Faze-Template-Version"));
            assertEquals("法泽文书中心 v1.0", custom(info, "Faze-Generator"));
            String generatedAt = custom(info, "Faze-Generated-At");
            assertNotNull(generatedAt);
            assertTrue(generatedAt.endsWith("+00:00"), "生成时间必须带时区：" + generatedAt);
            // 标准字段
            assertEquals("民事起诉状", info.getTitle());
            assertEquals("法泽", info.getAuthor());
            assertEquals("法泽文书中心", info.getCreator());
        }
    }

    /**
     * 反向断言：全文没有 AIGenerated / Faze-Content-Type（`4-A6` 明确要求）。
     *
     * @throws IOException 读写失败
     */
    @Test
    void neverWritesAigcMarker() throws IOException {
        byte[] pdf = writer.write(blankPdf(), 1L, "IOU-001", 1, "借条");
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("AIGenerated"), "PDF 里出现了 AIGenerated 字样");
        assertFalse(raw.contains("Faze-Content-Type"), "PDF 里出现了 Faze-Content-Type 字段");
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            assertEquals(null, custom(info, "Faze-Content-Type"));
            assertEquals(null, custom(info, "Faze-Content-Id"));
        }
    }

    /**
     * SHA-256 是 64 位小写十六进制，且同字节同结果、改一字节就变。
     */
    @Test
    void sha256IsStableAndSensitive() {
        byte[] a = "法泽".getBytes(StandardCharsets.UTF_8);
        assertEquals(64, PdfMetadataWriter.sha256(a).length());
        assertEquals(PdfMetadataWriter.sha256(a), PdfMetadataWriter.sha256(a.clone()));
        byte[] b = "法泽1".getBytes(StandardCharsets.UTF_8);
        assertFalse(PdfMetadataWriter.sha256(a).equals(PdfMetadataWriter.sha256(b)));
    }

    /**
     * 读自定义 Info 键。
     *
     * @param info PDF 文档信息
     * @param key  自定义键名
     * @return 值；不存在时 null
     */
    private String custom(PDDocumentInformation info, String key) {
        return info.getCOSObject().getString(COSName.getPDFName(key));
    }
}
