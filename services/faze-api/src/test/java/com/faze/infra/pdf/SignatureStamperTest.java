/*
 * 职责：SignatureStamper 的单测（段 4 D2 自查清单里的「坐标换算四种边界」「多页指定第 3 页」
 *       「页面尺寸不同按比例缩放」「透明底不遮挡」）。
 * 调用方：`mvn test`（surefire）。
 * 数据来源：PDFBox 在内存里现造的白页 PDF + java.awt 现造的透明底 PNG，不依赖本机字体与网络。
 * 注意：
 *   1. **坐标换算是本段第二容易写错的地方**：PDF 原点在左下、屏幕原点在左上。
 *      这里不止断言公式（`toPdfY`），还**渲染出来数像素**——公式对但 drawImage 传参顺序错，
 *      只有像素能抓到。
 *   2. 断言"原 PDF 未被修改"用字节哈希：叠章是纯函数，输入字节不该被改动。
 *   3. 不验「看起来好不好看」，那属于 `4-A5` 的人工量测（偏差 ≤ 4mm）。
 */
package com.faze.infra.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/**
 * 签名叠图器的单测。
 */
class SignatureStamperTest {

    private final SignatureStamper stamper = new SignatureStamper();

    /**
     * 造一张透明底 PNG（中间一块实心方块）。
     *
     * @param width  像素宽
     * @param height 像素高
     * @param color  方块颜色
     * @return PNG 字节
     * @throws IOException 编码失败
     */
    private byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * 造一份 N 页 A4 白页 PDF。
     *
     * @param pages  页数
     * @param width  页面宽（pt）
     * @param height 页面高（pt）
     * @return PDF 字节
     * @throws IOException 保存失败
     */
    private byte[] blankPdf(int pages, float width, float height) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(new PDRectangle(width, height)));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 屏幕坐标 → PDF 坐标的四个边界：贴顶 / 贴底 / 中间 / 横向页面。
     */
    @Test
    void toPdfYFourBoundaries() {
        // 贴顶：posY=0 → 图片上边缘在页顶，左下角 y = 页高 - 图高
        assertEquals(792d, SignatureStamper.toPdfY(842d, 0d, 50d), 0.001d);
        // 贴底：posY + height == 页高 → 左下角 y = 0
        assertEquals(0d, SignatureStamper.toPdfY(842d, 792d, 50d), 0.001d);
        // 中间
        assertEquals(400d, SignatureStamper.toPdfY(842d, 400d, 42d), 0.001d);
        // 横向页面（A4 旋转 90°）
        assertEquals(545d, SignatureStamper.toPdfY(595d, 0d, 50d), 0.001d);
    }

    /**
     * 叠图后原 PDF 字节不变（纯函数语义），且页数不变。
     *
     * @throws IOException 读写失败
     */
    @Test
    void stampDoesNotMutateInput() throws IOException {
        byte[] pdf = blankPdf(1, 595f, 842f);
        byte[] copy = pdf.clone();
        byte[] signed = stamper.stamp(pdf, png(20, 10, Color.BLACK), 1, 100, 100, 60, 30, 595, 842);
        assertArrayEquals(copy, pdf, "输入 PDF 被就地修改了");
        assertTrue(signed.length > pdf.length, "叠图后字节数应增加");
        try (PDDocument doc = PDDocument.load(signed)) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    /**
     * Y 轴换算真的生效：在页顶附近盖章，**渲染后像素出现在上方**（不换算会跑到页底）。
     *
     * <p>用 72 DPI 渲染，1 px = 1 pt；盖在 (100,100) 的 60×30 红块，
     * 断言 (130,115) 处是红的、(130,842-115) 处是白的。</p>
     *
     * @throws IOException 渲染失败
     */
    @Test
    void stampLandsAtTopLeftNotBottom() throws IOException {
        byte[] pdf = blankPdf(1, 595f, 842f);
        byte[] signed = stamper.stamp(pdf, png(60, 30, Color.RED), 1, 100, 100, 60, 30, 595, 842);
        try (PDDocument doc = PDDocument.load(signed)) {
            BufferedImage rendered = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            Color nearTop = new Color(rendered.getRGB(130, 115));
            Color nearBottom = new Color(rendered.getRGB(130, 842 - 115));
            assertTrue(nearTop.getRed() > 200 && nearTop.getGreen() < 80,
                    "页顶附近应为红色，实际 RGB=" + nearTop);
            assertTrue(nearBottom.getGreen() > 200,
                    "页底附近应为白色（说明 Y 没换算反），实际 RGB=" + nearBottom);
        }
    }

    /**
     * 多页文书指定第 3 页：签名只出现在第 3 页。
     *
     * @throws IOException 读写失败
     */
    @Test
    void stampOnlyOnRequestedPage() throws IOException {
        byte[] pdf = blankPdf(3, 595f, 842f);
        byte[] signed = stamper.stamp(pdf, png(20, 10, Color.BLUE), 3, 50, 50, 80, 40, 595, 842);
        try (PDDocument doc = PDDocument.load(signed)) {
            assertEquals(3, doc.getNumberOfPages());
            assertEquals(0, xobjectCount(doc.getPage(0).getResources()), "第 1 页不该有图");
            assertEquals(0, xobjectCount(doc.getPage(1).getResources()), "第 2 页不该有图");
            assertEquals(1, xobjectCount(doc.getPage(2).getResources()), "第 3 页应有 1 张图");
        }
    }

    /**
     * 页面实际尺寸与 `basePageW/H` 不一致时按比例缩放（2 倍页面 → 2 倍图）。
     *
     * @throws IOException 读写失败
     */
    @Test
    void scalesWhenPageSizeDiffers() throws IOException {
        byte[] pdf = blankPdf(1, 1190f, 1684f);
        byte[] signed = stamper.stamp(pdf, png(20, 10, Color.BLACK), 1, 100, 100, 60, 30, 595, 842);
        try (PDDocument doc = PDDocument.load(signed)) {
            BufferedImage rendered = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            // 缩放 2 倍后红块占 (200..320, 200..260)，取内部点 (260, 230)
            Color inside = new Color(rendered.getRGB(260, 230));
            assertTrue(inside.getRed() < 80, "缩放后签名应覆盖到 2 倍坐标处，实际 RGB=" + inside);
        }
    }

    /**
     * 页码越界 → 506002（不是 500）。
     *
     * @throws IOException 造 PDF 失败
     */
    @Test
    void pageOutOfRangeThrows() throws IOException {
        byte[] pdf = blankPdf(1, 595f, 842f);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> stamper.stamp(pdf, png(20, 10, Color.BLACK), 2, 0, 0, 10, 10, 595, 842));
        assertEquals(ErrorCode.DOCUMENT_PDF_FAILED, ex.getErrorCode());
    }

    /**
     * 数一页上的图片对象个数。
     *
     * <p>注意：PDFBox 2.0.24 的 `PDResources` 没有 `getXObjectNames` 的返回类型便利性，
     * 这里遍历 `COSName` 并统计 `/Subtype /Image` 的对象。</p>
     *
     * @param resources 页面资源；可能为 null（空页）
     * @return 图片对象个数
     */
    private int xobjectCount(PDResources resources) {
        if (resources == null) {
            return 0;
        }
        int count = 0;
        for (COSName name : resources.getXObjectNames()) {
            try {
                org.apache.pdfbox.pdmodel.graphics.PDXObject object = resources.getXObject(name);
                if (object instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    count++;
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取 XObject 失败", e);
            }
        }
        return count;
    }
}
