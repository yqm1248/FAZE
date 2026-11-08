/*
 * 职责：PDF 转 PNG（段 4 E4 预览 + B1 模板预览）——把生成好的 PDF 第一页（或前 N 页）转成图片。
 * 调用方：modules/document/admin/service/DocTemplateAdminService（模板预览）、C3/AppDocumentService（文书预览）。
 * 数据来源：PdfRenderer 产出的 PDF 字节。
 * 注意：
 *   1. **预览必须是"真 PDF 转的图"**，不能拿模板 HTML 截图冒充——`4-A1`/`4-A2` 的判据就是
 *      「预览显示的是真实 PDF」。用 HTML 截图会看不到字体嵌入、边距、分页问题。
 *   2. 200 DPI 下 A4 约 1654×2339 px，单张 PNG 3~8MB，**必须缓存到存储**而不是每次重算
 *      （工作流 §六 R8：渲染慢，二次进页面直接取 URL）。
 *   3. 只渲染前 5 页：文书动辄十几页，预览页不需要全部。
 *   4. PDFBox 2.0.24（openhtmltopdf 传递），API 是 `PDFRenderer#renderImageWithDPI`，
 *      不是 2.0.25+ 的 `renderImage`。
 */
package com.faze.infra.pdf;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * PDF 页面转 PNG。
 */
@Component
@Slf4j
public class PdfPageRenderer {

    /** 预览图 DPI：200 在清晰度与体积之间较平衡（72 DPI 会糊，300 太大）。 */
    public static final int PREVIEW_DPI = 200;

    /** 最多渲染页数。 */
    public static final int MAX_PAGES = 5;

    /**
     * 渲染第一页为 PNG。
     *
     * @param pdf PDF 字节
     * @return PNG 字节
     * @throws BusinessException 506002（PDF 损坏 / 渲染失败）
     */
    public byte[] renderFirstPagePng(byte[] pdf) {
        return renderPagePng(pdf, 0);
    }

    /**
     * 渲染指定页为 PNG（页码从 0 起，与 PDFBox 一致）。
     *
     * @param pdf   PDF 字节
     * @param index 页序号（0 起）
     * @return PNG 字节
     * @throws BusinessException 506002
     */
    public byte[] renderPagePng(byte[] pdf, int index) {
        if (pdf == null || pdf.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
        try (PDDocument doc = PDDocument.load(pdf)) {
            if (index < 0 || index >= doc.getNumberOfPages()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED, "页码越界：" + index);
            }
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(index, PREVIEW_DPI);
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("PDF 转 PNG 失败", e);
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }

    /**
     * 取前 N 页的页面尺寸（**不渲染**，只解析文档）。
     *
     * <p>E4 的预览要「先知道有几页、每页多大」，再决定渲染哪几页；
     * 逐页调用 {@link #renderPagePng} 会把同一个 PDF 反复解析 N 次，
     * 这里只解析一次拿尺寸，缺的页各自渲染。</p>
     *
     * @param pdf      PDF 字节
     * @param maxPages 最多取几页（≤0 或超出实际页数时按实际页数）
     * @return 页面尺寸列表（顺序与页码一致，`pageNo` 从 1 开始）
     * @throws BusinessException 506002（PDF 损坏）
     */
    public java.util.List<PageSize> pageSizes(byte[] pdf, int maxPages) {
        if (pdf == null || pdf.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
        try (PDDocument doc = PDDocument.load(pdf)) {
            int total = doc.getNumberOfPages();
            int limit = maxPages <= 0 ? total : Math.min(maxPages, total);
            java.util.List<PageSize> sizes = new java.util.ArrayList<>(limit);
            for (int index = 0; index < limit; index++) {
                PDRectangle box = doc.getPage(index).getMediaBox();
                sizes.add(new PageSize(index + 1,
                        Math.round(box.getWidth()), Math.round(box.getHeight())));
            }
            return sizes;
        } catch (IOException e) {
            log.error("读取 PDF 页面尺寸失败", e);
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }

    /**
     * 一页的尺寸（点）。
     *
     * @param pageNo   页码（从 1 开始）
     * @param widthPt  页面宽度（pt，四舍五入到整数）
     * @param heightPt 页面高度（pt）
     */
    public record PageSize(int pageNo, int widthPt, int heightPt) {
    }
}
