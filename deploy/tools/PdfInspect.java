/*
 * 职责：PDF 验收检查器（段 4 的 4-A2 / 4-A3 / 4-A6 要用它读 PDF 里的真实数据）。
 * 调用方：`deploy/verify-seg4.ps1`、Agent 手工验收。
 * 数据来源：命令行传入的 PDF 文件路径。
 * 注意：
 *   1. **用 JDK 单文件源码启动**（JDK 11+ `java -cp ... PdfInspect.java <pdf> <mode>`），
 *      不引入新依赖、不进 maven 构建：验收工具有 bug 不该影响业务构建。
 *   2. 依赖 PDFBox 2.0.24 的三个 jar（openhtmltopdf 传递引入）：
 *      `pdfbox` / `fontbox` / `commons-logging`。缺 jar 时由调用脚本报「无法检查」。
 *   3. 模式：
 *      - `info`    页面尺寸 + 页数 + 元数据全量
 *      - `text`    抽取全文（4-A3 的判据）
 *      - `fonts`   未嵌入字体清单（4-A2 的隐藏判据）
 *      - `margins` 量首页正文包围盒（4-A2 的四边边距）
 *      - `images`  每页图片对象数（4-A3 的「正文不是图片」反向断言）
 *      - `all`     以上全部
 *   4. 输出一律 UTF-8 打到 stdout，供脚本捕获；不写文件。
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDF 验收检查器。
 */
public final class PdfInspect {

    private PdfInspect() {
    }

    /**
     * 入口。
     *
     * @param args args[0]=PDF 路径，args[1]=模式（info/text/fonts/margins/images/all）
     * @throws IOException 读文件失败
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("用法：java -cp <jars> PdfInspect.java <pdf> [info|text|fonts|margins|images|all|diffbox] [未签pdf]");
            System.exit(2);
        }
        String mode = args.length > 1 ? args[1] : "all";
        File file = new File(args[0]);
        if (!file.isFile()) {
            System.out.println("FILE-NOT-FOUND " + file.getAbsolutePath());
            System.exit(2);
        }
        System.out.println("FILE " + file.getName() + " " + file.length() + " bytes");
        if ("diffbox".equals(mode)) {
            if (args.length < 3) {
                System.out.println("diffbox 需要第二个参数：未签 PDF 路径");
                System.exit(2);
            }
            printDiffBox(new File(args[2]), file);
            return;
        }
        try (PDDocument doc = PDDocument.load(file)) {
            if ("all".equals(mode) || "info".equals(mode)) {
                printInfo(doc);
            }
            if ("all".equals(mode) || "fonts".equals(mode)) {
                printFonts(doc);
            }
            if ("all".equals(mode) || "margins".equals(mode)) {
                printMargins(doc);
            }
            if ("all".equals(mode) || "images".equals(mode)) {
                printImages(doc);
            }
            if ("all".equals(mode) || "text".equals(mode)) {
                printText(doc);
            }
        }
    }

    /**
     * 量测「带签 PDF 相对未签 PDF」的差异包围盒（72 DPI 下 1 像素 = 1 点）。
     *
     * <p>`4-A5` 要的是「签名实际落点与预期坐标的偏差 ≤ 4mm」。这里不做人工目视：
     * 两份 PDF 同一页按 72 DPI 渲染成同尺寸位图，逐像素比，差异像素的最小/最大行列即签名矩形；
     * 同时检查**未签图在该矩形内是否为纯白**——纯白说明这个位置原本没有正文，签名没遮挡内容。</p>
     *
     * @param unsignedPdf 未签 PDF
     * @param signedPdf   带签 PDF
     * @throws IOException 渲染失败
     */
    private static void printDiffBox(File unsignedPdf, File signedPdf) throws IOException {
        try (PDDocument before = PDDocument.load(unsignedPdf);
             PDDocument after = PDDocument.load(signedPdf)) {
            System.out.println("PAGES-BEFORE " + before.getNumberOfPages());
            System.out.println("PAGES-AFTER " + after.getNumberOfPages());
            int pageIndex = 0;
            java.awt.image.BufferedImage a = new PDFRenderer(before).renderImageWithDPI(pageIndex, 72);
            java.awt.image.BufferedImage b = new PDFRenderer(after).renderImageWithDPI(pageIndex, 72);
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                System.out.println("SIZE-MISMATCH " + a.getWidth() + "x" + a.getHeight()
                        + " vs " + b.getWidth() + "x" + b.getHeight());
                return;
            }
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = -1;
            int maxY = -1;
            long diff = 0;
            for (int y = 0; y < a.getHeight(); y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    if (a.getRGB(x, y) != b.getRGB(x, y)) {
                        diff++;
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            System.out.println("DIFF-PIXELS " + diff);
            if (diff == 0) {
                System.out.println("DIFFBOX none（两份 PDF 渲染后完全相同）");
                return;
            }
            int w = maxX - minX + 1;
            int h = maxY - minY + 1;
            System.out.printf("DIFFBOX x=%d y=%d w=%d h=%d pt%n", minX, minY, w, h);
            int inkPixels = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if ((a.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                        inkPixels++;
                    }
                }
            }
            System.out.println("UNSIGNED-INK-PIXELS " + inkPixels
                    + (inkPixels == 0 ? "（该区域原本空白，签名未遮挡正文）" : "（该区域原本有内容，可能遮挡正文）"));
        }
    }

    /**
     * 页数与页面尺寸 + 元数据全量。
     *
     * @param doc 文档
     */
    private static void printInfo(PDDocument doc) {
        System.out.println("PAGES " + doc.getNumberOfPages());
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDRectangle box = doc.getPage(i).getMediaBox();
            System.out.printf("PAGE %d %.2f x %.2f pt%n", i + 1, box.getWidth(), box.getHeight());
        }
        System.out.println("-- METADATA --");
        PDDocumentInformation info = doc.getDocumentInformation();
        System.out.println("Title: " + safe(info.getTitle()));
        System.out.println("Author: " + safe(info.getAuthor()));
        System.out.println("Creator: " + safe(info.getCreator()));
        System.out.println("Producer: " + safe(info.getProducer()));
        for (COSName key : info.getCOSObject().keySet()) {
            String name = key.getName();
            if (name.startsWith("Faze-")) {
                System.out.println(name + ": " + safe(info.getCOSObject().getString(key)));
            }
        }
        System.out.println("AIGENERATED-IN-METADATA: "
                + (hasAigc(info) ? "FOUND" : "none"));
    }

    /**
     * 判断元数据里有没有 AIGC 标识（4-A6 反向断言）。
     *
     * @param info 文档信息
     * @return 有则 true
     */
    private static boolean hasAigc(PDDocumentInformation info) {
        for (COSName key : info.getCOSObject().keySet()) {
            String value = info.getCOSObject().getString(key);
            if (value != null && (value.contains("AIGenerated") || key.getName().contains("Content-Type"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 未嵌入字体清单（4-A2 的隐藏判据：本机看着对、换机器就变字形）。
     *
     * @param doc 文档
     * @throws IOException 遍历失败
     */
    private static void printFonts(PDDocument doc) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDResources resources = doc.getPage(i).getResources();
            if (resources == null) {
                continue;
            }
            for (COSName name : resources.getFontNames()) {
                PDFont font = resources.getFont(name);
                if (font == null) {
                    continue;
                }
                String label = font.getName();
                all.add(label);
                PDFontDescriptor descriptor = font.getFontDescriptor();
                boolean embedded = descriptor != null && (descriptor.getFontFile() != null
                        || descriptor.getFontFile2() != null || descriptor.getFontFile3() != null);
                if (!embedded) {
                    missing.add(label);
                }
            }
        }
        System.out.println("FONTS " + all.size() + " unique=" + all.stream().distinct().count());
        all.stream().distinct().forEach(f -> System.out.println("  font: " + f));
        System.out.println("UNEMBEDDED " + missing.size());
        missing.forEach(f -> System.out.println("  missing: " + f));
    }

    /**
     * 量首页正文包围盒（4-A2 的四边边距）。
     *
     * <p>用 TextStripper 的字符位置：最小 x = 左边距，首页宽度 - 最大 x = 右边距，
     * 页面高度 - 最大 y（PDF 坐标）= 上边距，最小 y = 下边距。</p>
     *
     * @param doc 文档
     * @throws IOException 抽取失败
     */
    private static void printMargins(PDDocument doc) throws IOException {
        final double[] min = {Double.MAX_VALUE, Double.MAX_VALUE};
        final double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) throws IOException {
                for (TextPosition position : positions) {
                    min[0] = Math.min(min[0], position.getXDirAdj());
                    min[1] = Math.min(min[1], position.getYDirAdj());
                    max[0] = Math.max(max[0], position.getXDirAdj() + position.getWidthDirAdj());
                    max[1] = Math.max(max[1], position.getYDirAdj() + position.getHeightDir());
                }
                super.writeString(text, positions);
            }
        };
        stripper.setStartPage(1);
        stripper.setEndPage(1);
        stripper.getText(doc);
        PDRectangle box = doc.getPage(0).getMediaBox();
        if (min[0] == Double.MAX_VALUE) {
            System.out.println("MARGINS none (首页没有文字)");
            return;
        }
        double left = min[0];
        double right = box.getWidth() - max[0];
        double top = min[1];
        double bottom = box.getHeight() - max[1];
        System.out.printf("MARGINS left=%.2f right=%.2f top=%.2f bottom=%.2f pt%n", left, right, top, bottom);
    }

    /**
     * 每页图片对象数（4-A3 的「正文不是图片」反向断言）。
     *
     * @param doc 文档
     * @throws IOException 遍历失败
     */
    private static void printImages(PDDocument doc) throws IOException {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDResources resources = doc.getPage(i).getResources();
            int count = 0;
            if (resources != null) {
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject object = resources.getXObject(name);
                    if (object instanceof PDImageXObject) {
                        count++;
                    }
                }
            }
            System.out.println("IMAGES page " + (i + 1) + ": " + count);
        }
    }

    /**
     * 抽全文（4-A3 判据）。
     *
     * @param doc 文档
     * @throws IOException 抽取失败
     */
    private static void printText(PDDocument doc) throws IOException {
        String text = new PDFTextStripper().getText(doc);
        System.out.println("-- TEXT (" + text.length() + " chars) --");
        System.out.println(text);
        System.out.println("-- END TEXT --");
        System.out.println("PLACEHOLDER-LEFT: " + (text.contains("{{") ? "FOUND" : "none"));
    }

    /**
     * null 安全的字符串。
     *
     * @param value 原值
     * @return 非 null 字符串
     */
    private static String safe(String value) {
        return value == null ? "(null)" : value;
    }
}
