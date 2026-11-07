/*
 * 职责：PdfRenderer 的单测（段 4 A2 的自查清单）。
 * 调用方：`mvn test`（surefire）。
 * 数据来源：本机 Windows 字体 `simfang.ttf` / `simhei.ttf`；**非 Windows 环境整类跳过**（Assumptions）。
 * 注意：
 *   1. 这里验证的是「渲染通道对中文、字体嵌入、非法 XHTML、字体缺失这四件事的行为」，
 *      不是版式目视——版式（边距/字号/行距）由 `4-A2` 的人工量测负责。
 *   2. **非法 XHTML 必须抛 506002，不能静默产出空 PDF**：这是「失败要能被归因」的基础，
 *      如果这里吞掉，C4 的失败路径就永远走不到。
 *   3. 粗体这条只断言「注册 700 字重后渲染不崩」，**不断言肉眼加粗**——PDF 里的字重是否真的变化，
 *      靠 `4-A2` 放大截图人工看，单测断不出来。
 */
package com.faze.infra.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.properties.FazeDocProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * HTML → PDF 渲染通道的单测。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfRendererTest {

    private static final Path FONT = Path.of("C:/Windows/Fonts/simfang.ttf");
    private static final Path BOLD_FONT = Path.of("C:/Windows/Fonts/simhei.ttf");

    private static final String SAMPLE_HTML = """
            <html><head><style>
              @page { size: A4; margin: 72pt 72pt 72pt 90pt; }
              body { font-family: 'FangSong'; font-size: 16pt; line-height: 28pt; }
              h1 { font-size: 22pt; text-align: center; }
            </style></head><body>
              <h1>民事起诉状</h1>
              <p>原告：马合木提·艾山，男，1985年3月生，住伊宁市。</p>
              <p>诉讼请求：判令被告支付欠款人民币 50000 元。</p>
            </body></html>
            """;

    private PdfRenderer renderer;

    /**
     * 准备渲染器；没有中文字体的机器整类跳过（CI 是 Linux，不装仿宋）。
     */
    @BeforeAll
    void setUp() {
        Assumptions.assumeTrue(Files.isReadable(FONT),
                "本机无 simfang.ttf（非 Windows 环境），跳过 PDF 渲染测试");
        String boldPath = Files.isReadable(BOLD_FONT) ? BOLD_FONT.toString() : "";
        this.renderer = new PdfRenderer(new FazeDocProperties(FONT.toString(), boldPath, 10, 50));
    }

    @AfterAll
    void tearDown() {
        if (renderer != null) {
            renderer.shutdown();
        }
    }

    /**
     * 正常中文：渲染成功、单页、文字可被 PDFTextStripper 抽出（`4-A3` 的地基）。
     *
     * @throws IOException 读取 PDF 失败
     */
    @Test
    void renderChinesePdf() throws IOException {
        byte[] pdf = renderer.render(SAMPLE_HTML);
        assertTrue(pdf.length > 1000, "PDF 太小，可能是空文档");
        try (PDDocument doc = PDDocument.load(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("民事起诉状"), "标题未被抽出：" + text);
            assertTrue(text.contains("马合木提"), "当事人未被抽出：" + text);
        }
    }

    /**
     * 字体嵌入：`4-A2` 的隐藏判据——没嵌入时本机看着正常，换机器就变字形。
     */
    @Test
    void fontsAreEmbedded() {
        byte[] pdf = renderer.render(SAMPLE_HTML);
        List<String> missing = renderer.findUnembeddedFonts(pdf);
        assertTrue(missing.isEmpty(), "存在未嵌入字体：" + missing);
    }

    /**
     * 非法 XHTML（`<br>` 不带斜杠）必须抛 506002，不能静默出空 PDF。
     */
    @Test
    void invalidXhtmlThrowsPdfFailed() {
        String bad = "<html><body><p>第一行<br>第二行</p></body></html>";
        BusinessException ex = assertThrows(BusinessException.class, () -> renderer.render(bad));
        assertEquals(ErrorCode.DOCUMENT_PDF_FAILED, ex.getErrorCode());
    }

    /**
     * 字体文件不存在 → 506003（对应 C4 的失败场景之一）。
     */
    @Test
    void missingFontThrowsFontMissing() {
        PdfRenderer broken = new PdfRenderer(
                new FazeDocProperties("C:/Windows/Fonts/faze-not-exists.ttf", "", 10, 50));
        try {
            BusinessException ex = assertThrows(BusinessException.class, () -> broken.render(SAMPLE_HTML));
            assertEquals(ErrorCode.DOCUMENT_FONT_MISSING, ex.getErrorCode());
        } finally {
            broken.shutdown();
        }
    }

    /**
     * 粗体：注册 700 字重后渲染不崩（视觉是否加粗见 `4-A2` 人工）。
     */
    @Test
    void boldRendersWithoutError() {
        String withBold = SAMPLE_HTML.replace("原告：", "<strong>原告：</strong>");
        byte[] pdf = renderer.render(withBold);
        assertTrue(renderer.pageCount(pdf) >= 1);
    }

    /**
     * 空内容 → 406002（表单非法），不是 500。
     */
    @Test
    void blankHtmlThrowsFormInvalid() {
        BusinessException ex = assertThrows(BusinessException.class, () -> renderer.render("   "));
        assertEquals(ErrorCode.DOCUMENT_FORM_INVALID, ex.getErrorCode());
    }
}
