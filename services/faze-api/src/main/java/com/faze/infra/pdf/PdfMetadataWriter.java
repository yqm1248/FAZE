/*
 * 职责：往生成的 PDF 里写**溯源**元数据（段 4 D3），并算盖签前的 SHA-256。
 * 调用方：modules/document/task/DocGenerateHandler（生成成功、落盘之前）。
 * 数据来源：document.id / doc_template.code / doc_template.version / 生成时间。
 * 注意：
 *   1. **只写溯源字段，绝不写 `Faze-Content-Type: AIGenerated`**：
 *      法泽的文书是「用户填表 + 运营配 HTML 模板 + 占位符替换」，没有任何一步是模型生成，
 *      打 AIGC 标识属虚假标识（工作流 §零.3 第 14 项、`4-A6` 反向断言）。
 *   2. `Faze-Generated-At` **必须带时区**（ISO-8601 偏移格式），否则跨时区无法判读。
 *   3. 标准字段只填 Title / Author / Creator；`Producer` 由 openhtmltopdf 自动写，不覆盖。
 *   4. 改元数据会改变文件字节，所以必须在**落盘之前**写完，落盘后的字节才是最终产物
 *      （哈希与 `file_record.md5` 都以最终字节为准）。
 */
package com.faze.infra.pdf;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;

/**
 * PDF 溯源元数据写入器。
 */
@Component
@Slf4j
public class PdfMetadataWriter {

    /** 生成器标识（写进 `Faze-Generator`）。 */
    public static final String GENERATOR = "法泽文书中心 v1.0";

    /** 作者（标准字段 Author）。 */
    private static final String AUTHOR = "法泽";

    /** 创建者（标准字段 Creator）。 */
    private static final String CREATOR = "法泽文书中心";

    /** ISO-8601 带偏移的时间格式（秒级足够，避免毫秒抖动影响逐字比对）。 */
    private static final DateTimeFormatter ISO_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /**
     * 写入溯源元数据。
     *
     * @param pdf             原始 PDF 字节
     * @param documentId      文书主键
     * @param templateCode    模板编码（如 LAWSUIT-001）
     * @param templateVersion 模板版本
     * @param title           文书标题（标准字段 Title）
     * @return 写入元数据后的 PDF 字节
     * @throws BusinessException 506002（PDF 损坏）
     */
    public byte[] write(byte[] pdf, long documentId, String templateCode, int templateVersion,
                        String title) {
        if (pdf == null || pdf.length == 0) {
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED, "PDF 为空");
        }
        String generatedAt = OffsetDateTime.now(ZoneOffset.UTC).format(ISO_OFFSET);
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(title == null ? "" : title);
            info.setAuthor(AUTHOR);
            info.setCreator(CREATOR);
            // 自定义键走 Info 字典：PDFBox 2.0.x 的 setCustomMetadataValue 在部分小版本上缺失，
            // COSObject 直写是各版本都有的稳定写法（`4-A6` 用 PDFBox 读回验证）。
            info.getCOSObject().setString(COSName.getPDFName("Faze-Document-Id"),
                    String.valueOf(documentId));
            info.getCOSObject().setString(COSName.getPDFName("Faze-Template-Code"),
                    templateCode == null ? "" : templateCode);
            info.getCOSObject().setString(COSName.getPDFName("Faze-Template-Version"),
                    String.valueOf(templateVersion));
            info.getCOSObject().setString(COSName.getPDFName("Faze-Generated-At"), generatedAt);
            info.getCOSObject().setString(COSName.getPDFName("Faze-Generator"), GENERATOR);
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 8 * 1024);
            doc.save(out);
            log.info("PDF 溯源元数据已写入 documentId={} templateCode={} version={}",
                    documentId, templateCode, templateVersion);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("写 PDF 元数据失败 documentId={}", documentId, e);
            throw new BusinessException(ErrorCode.DOCUMENT_PDF_FAILED);
        }
    }

    /**
     * 算 SHA-256（小写十六进制）。
     *
     * @param bytes 文件字节
     * @return 64 位十六进制串
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境坏了
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
