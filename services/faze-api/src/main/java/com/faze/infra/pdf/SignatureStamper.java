/*
 * 职责：把签名 PNG 叠到 PDF 指定页的指定位置（段 4 D2）。
 * 调用方：modules/document/service/DocDeliveryService（叠章接口）。
 * 数据来源：`pdf_oss_key` 指向的 PDF 字节 + `signature.image_oss_key` 指向的 PNG 字节。
 * 注意：
 *   1. **PDF 坐标系原点在左下角，屏幕坐标系原点在左上角**：
 *      存的是屏幕坐标（左上），落图必须换算 `pdfY = pageH - (posY + height)`。
 *      不换算的典型现象：签名跑到页顶（本段第二容易写错的地方，第一是字重注册）。
 *   2. **页面尺寸与 `basePageW/H` 不一致时按比例缩放**（工作流 D2 第 3 条）。
 *      写死 595×842 在本模板上看着对，换模板就错位。
 *   3. 用 `AppendMode.APPEND`，**绝不能 OVERWRITE**——那会把原正文全部吃掉，
 *      生成的 PDF 只剩一枚签名。
 *   4. 用 `PDImageXObject.createFromByteArray` 而不是 `createFromFile`：
 *      后者会写临时文件，`4-A8` 要求生成过程不在 `$env:TEMP` 留残留。
 *   5. 本类**不写库、不碰存储**，纯字节进字节出，便于单测覆盖四种边界坐标。
 */
package com.faze.infra.pdf;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/**
 * 签名叠图器。
 */
@Component
@Slf4j
public class SignatureStamper {

    /**
     * 把签名叠到指定页。
     *
     * @param pdf       原始 PDF 字节（**不被修改**）
     * @param png       签名 PNG 字节（透明底）
     * @param pageNo    页码，从 1 开始
     * @param posX      相对页面左上角的横坐标（pt）
     * @param posY      相对页面左上角的纵坐标（pt）
     * @param width     签名宽度（pt，屏幕坐标系下）
     * @param height    签名高度（pt，屏幕坐标系下）
     * @param basePageW 用户看到的页面宽度（pt）
     * @param basePageH 用户看到的页面高度（pt）
     * @return 叠签后的新 PDF 字节
     * @throws BusinessException 506002（页不存在 / PDF 损坏 / PNG 无法解析）
     */
    public byte[] stamp(byte[] pdf, byte[] png, int pageNo, double posX, double posY,
                        double width, double height, double basePageW, double basePageH) {
        if (pdf == null || pdf.length == 0 || png == null || png.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED, "PDF 或签名图为空");
        }
        try (PDDocument doc = PDDocument.load(pdf)) {
            if (pageNo < 1 || pageNo > doc.getNumberOfPages()) {
                throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED, "页码越界：" + pageNo);
            }
            PDPage page = doc.getPage(pageNo - 1);
            PDRectangle box = page.getMediaBox();
            double pageW = box.getWidth();
            double pageH = box.getHeight();

            double scaleX = basePageW > 0 ? pageW / basePageW : 1d;
            double scaleY = basePageH > 0 ? pageH / basePageH : 1d;
            double drawW = width * scaleX;
            double drawH = height * scaleY;
            double drawX = posX * scaleX;
            double drawY = toPdfY(pageH, posY * scaleY, drawH);

            PDImageXObject img = PDImageXObject.createFromByteArray(doc, png, "signature");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(img, (float) drawX, (float) drawY, (float) drawW, (float) drawH);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 64 * 1024);
            doc.save(out);
            log.info("签名叠图完成 pageNo={} pageSize={}x{} draw=({},{},{},{})",
                    pageNo, pageW, pageH, drawX, drawY, drawW, drawH);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("签名叠图失败", e);
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }

    /**
     * 屏幕坐标系（原点左上）的纵向坐标 → PDF 坐标系（原点左下）的图片左下角 y。
     *
     * <p>公开 static 供单测覆盖四种边界（0 / 贴底 / 贴右 / 超界）。</p>
     *
     * @param pageH   页面高度（pt）
     * @param posY    屏幕坐标下的图片上边缘距离页顶的距离（pt）
     * @param height  图片高度（pt）
     * @return PDF 坐标下图片左下角的 y（pt）
     */
    public static double toPdfY(double pageH, double posY, double height) {
        return pageH - (posY + height);
    }
}
