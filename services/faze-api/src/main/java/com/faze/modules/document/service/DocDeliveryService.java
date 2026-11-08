/*
 * 职责：文书的「交付侧」编排（段 4 D2/E4/F1）——预览图、导出地址、叠章。
 * 调用方：modules/document/controller/AppDocumentController。
 * 数据来源：document（`pdf_oss_key` / `signed_pdf_oss_key`）、signature（签名库）、file_record（预览图缓存）。
 * 注意：
 *   1. **归属校验走 `requireOwned`**：`user_id` 进 where，不是 selectById 再判（后端规范 §11.2）。
 *   2. **生效的文件优先取 `signed_pdf_oss_key`**：用户盖过章之后，预览与导出的都该是带签名的那份
 *      （`4-A5` 要「下载带签名的 PDF」；原 `pdf_oss_key` 那对象永不被改写，`4-A4` 的 hash 比对靠它）。
 *   3. **预览图缓存**：按「PDF 对象键的短哈希」做前缀（`pv{短哈希}:{页号}.png`）存进 `file_record`。
 *      重新生成 → 新 PDF 对象键 → 前缀变化 → 旧缓存自然命中不到，不需要额外的失效逻辑。
 *      本段没有给 `document` 加字段（16 字段是工作流原文），所以用它当缓存索引。
 *   4. **预览只渲染前 5 页**（`PdfPageRenderer.MAX_PAGES`）：文书动辄十几页，预览不需要全部。
 *   5. 叠章**不改原 PDF**：产物落到新的 `signed_pdf_oss_key`；盖签前字节的 SHA-256 写入
 *      `signature.document_hash`，事后可重算比对（D3 第 4 条）。
 *   6. 事务只包「插签名行 + 更新 document」；PDF 渲染与落盘在事务外（CPU/IO 重，不占连接）。
 *      顺序是「先落盘拿到 key，再开事务写库」，落盘成功而写库失败只留一个孤儿对象，由段 7 清理。
 */
package com.faze.modules.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.pdf.PdfMetadataWriter;
import com.faze.infra.pdf.PdfPageRenderer;
import com.faze.infra.pdf.SignatureStamper;
import com.faze.infra.storage.FileRecordService;
import com.faze.infra.storage.entity.FileRecord;
import com.faze.modules.document.bo.SignBO;
import com.faze.modules.document.entity.Document;
import com.faze.modules.document.entity.Signature;
import com.faze.modules.document.mapper.DocumentMapper;
import com.faze.modules.document.mapper.SignatureMapper;
import com.faze.modules.document.vo.DocExportVO;
import com.faze.modules.document.vo.DocPreviewPageVO;
import com.faze.modules.document.vo.SignResultVO;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文书交付服务（预览 / 导出 / 叠章）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocDeliveryService {

    /** 下载地址有效期（秒），与 `4-A7` 第 3 项判据一致。 */
    public static final long EXPORT_TTL_SECONDS = 60L;

    /** 预览图缓存的对象名前缀（后面接 PDF 短哈希与页号）。 */
    private static final String CACHE_PREFIX = "pv";

    /** 导出文件名里的日期格式。 */
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String STATUS_GENERATED = "generated";
    private static final String STATUS_SIGNED = "signed";
    private static final String FORMAT_PDF = "pdf";

    private final DocumentMapper documentMapper;
    private final SignatureMapper signatureMapper;
    private final SignatureService signatureService;
    private final FileRecordService fileRecordService;
    private final PdfPageRenderer pdfPageRenderer;
    private final SignatureStamper signatureStamper;

    /**
     * 预览：把生效的 PDF 每页渲染成 PNG（前 5 页），带缓存。
     *
     * @param id     文书主键
     * @param userId 当前用户
     * @return 页列表；文书还没有 PDF（草稿 / 失败）时返回空列表，由页面渲染「未生成」态
     */
    public List<DocPreviewPageVO> preview(Long id, Long userId) {
        Document doc = requireOwned(id, userId);
        String pdfKey = effectiveKey(doc);
        if (pdfKey == null) {
            log.debug("文书还没有 PDF，预览返回空 documentId={} status={}", id, doc.getStatus());
            return List.of();
        }
        byte[] pdf = fileRecordService.readBytes(pdfKey);
        List<PdfPageRenderer.PageSize> sizes =
                pdfPageRenderer.pageSizes(pdf, PdfPageRenderer.MAX_PAGES);

        // 先按当前 PDF 的短哈希查已有缓存页，缺的才渲染
        String prefix = CACHE_PREFIX + shortHash(pdfKey) + ":";
        Map<Integer, String> cached = new HashMap<>();
        for (FileRecord record : fileRecordService.recordsByPrefix(
                FileRecordService.BIZ_DOCUMENT, id, prefix)) {
            int pageNo = parsePageNo(record.getOriginalName(), prefix);
            if (pageNo > 0) {
                cached.put(pageNo, record.getOssKey());
            }
        }

        List<DocPreviewPageVO> pages = new ArrayList<>(sizes.size());
        int rendered = 0;
        for (PdfPageRenderer.PageSize size : sizes) {
            String key = cached.get(size.pageNo());
            if (key == null) {
                byte[] png = pdfPageRenderer.renderPagePng(pdf, size.pageNo() - 1);
                key = fileRecordService.storeGenerated(FileRecordService.BIZ_DOCUMENT, id, userId,
                        "png", png, "image/png", prefix + size.pageNo() + ".png");
                rendered++;
            }
            DocPreviewPageVO vo = new DocPreviewPageVO();
            vo.setPageNo(size.pageNo());
            vo.setImageUrl(fileRecordService.presignDownload(key));
            vo.setWidthPt(size.widthPt());
            vo.setHeightPt(size.heightPt());
            pages.add(vo);
        }
        log.info("文书预览就绪 documentId={} pages={} 本次渲染={} 命中缓存={}",
                id, pages.size(), rendered, pages.size() - rendered);
        return pages;
    }

    /**
     * 导出：给出 60 秒有效的 PDF 下载地址。
     *
     * @param id     文书主键
     * @param userId 当前用户
     * @param format 只支持 `pdf`（不出 Word，工作流 §零.3 第 12 项）
     * @return 下载地址 + 建议文件名 + 有效期
     * @throws BusinessException 401001（format 非法）/ 401006（还没生成过）
     */
    public DocExportVO export(Long id, Long userId, String format) {
        if (format != null && !format.isBlank() && !FORMAT_PDF.equalsIgnoreCase(format)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "只支持 format=pdf");
        }
        Document doc = requireOwned(id, userId);
        String key = effectiveKey(doc);
        if (key == null) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "文书尚未生成，无法导出");
        }
        DocExportVO vo = new DocExportVO();
        vo.setUrl(fileRecordService.presignDownload(key));
        String title = doc.getTitle() == null || doc.getTitle().isBlank() ? "文书" : doc.getTitle();
        vo.setFileName(trimFileName(title) + "-"
                + LocalDateTime.now(ZoneOffset.UTC).format(FILE_DATE) + ".pdf");
        vo.setExpiresIn(EXPORT_TTL_SECONDS);
        return vo;
    }

    /**
     * 叠章：把签名库里的一个签名叠到指定页，产物写新的 `signed_pdf_oss_key`。
     *
     * @param id     文书主键
     * @param userId 当前用户
     * @param bo     页码、坐标、尺寸、基准页面尺寸
     * @return 叠签后地址 + 盖签前哈希（已入库）
     * @throws BusinessException 401006（状态不允许）/ 401001（坐标越界）/ 406007（签名不存在）
     */
    @Transactional(rollbackFor = Exception.class)
    public SignResultVO sign(Long id, Long userId, SignBO bo) {
        Document doc = requireOwned(id, userId);
        if (!STATUS_GENERATED.equals(doc.getStatus()) && !STATUS_SIGNED.equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "文书还没生成，不能盖章");
        }
        String unsignedKey = doc.getPdfOssKey();
        if (unsignedKey == null || unsignedKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_ILLEGAL_STATE, "文书还没有 PDF，不能盖章");
        }
        assertInsidePage(bo);

        Signature library = signatureService.requireLibrary(userId, bo.getSignatureId());
        byte[] unsignedPdf = fileRecordService.readBytes(unsignedKey);
        byte[] signaturePng = fileRecordService.readBytes(library.getImageOssKey());
        String documentHash = PdfMetadataWriter.sha256(unsignedPdf);

        byte[] signedPdf = signatureStamper.stamp(unsignedPdf, signaturePng, bo.getPageNo(),
                bo.getPosX(), bo.getPosY(), bo.getWidth(), bo.getHeight(),
                bo.getBasePageW(), bo.getBasePageH());
        String signedKey = fileRecordService.storeGenerated(FileRecordService.BIZ_DOCUMENT,
                id, userId, "pdf", signedPdf, "application/pdf", "signed.pdf");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Signature stamp = new Signature();
        stamp.setUserId(userId);
        stamp.setImageOssKey(library.getImageOssKey());
        stamp.setDocumentId(id);
        stamp.setPageNo(bo.getPageNo());
        stamp.setPosXPt((int) Math.round(bo.getPosX()));
        stamp.setPosYPt((int) Math.round(bo.getPosY()));
        stamp.setWidthPt((int) Math.round(bo.getWidth()));
        stamp.setHeightPt((int) Math.round(bo.getHeight()));
        stamp.setBasePageWPt((int) Math.round(bo.getBasePageW()));
        stamp.setBasePageHPt((int) Math.round(bo.getBasePageH()));
        stamp.setDocumentHash(documentHash);
        stamp.setHashAlgo("SHA-256");
        stamp.setSignedAt(now);
        stamp.setCreatedAt(now);
        signatureMapper.insert(stamp);

        Document update = new Document();
        update.setId(id);
        update.setStatus(STATUS_SIGNED);
        update.setSignedPdfOssKey(signedKey);
        update.setUpdatedAt(now);
        documentMapper.updateById(update);

        SignResultVO vo = new SignResultVO();
        vo.setSignedPdfUrl(fileRecordService.presignDownload(signedKey));
        vo.setDocumentHash(documentHash);
        log.info("文书盖章完成 documentId={} signatureId={} pageNo={} hash={}",
                id, stamp.getId(), bo.getPageNo(), documentHash.substring(0, 12));
        return vo;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 标记文书已发送给委托人（段 5 D2）。
     *
     * <p>只回填 `sent_at`，**不动 `status`**：发送是独立于生成生命周期的另一条业务事实。
     * 首次发送时间由调用方保证「已有值就不再写」，这里不做判重（写入方拿到的文档刚查过）。</p>
     *
     * @param documentId 文书主键
     * @param sentAt     首次发送时间（UTC）
     */
    public void markSent(Long documentId, LocalDateTime sentAt) {
        if (documentId == null || sentAt == null) {
            return;
        }
        Document patch = new Document();
        patch.setId(documentId);
        patch.setSentAt(sentAt);
        documentMapper.updateById(patch);
        log.info("文书已标记发送 documentId={}", documentId);
    }

    /**
     * 取当前用户自己的、未删除的文书。
     *
     * @param id     文书主键
     * @param userId 当前用户
     * @return 文书
     * @throws BusinessException 406004
     */
    private Document requireOwned(Long id, Long userId) {
        Document doc = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getId, id)
                .eq(Document::getUserId, userId)
                .isNull(Document::getDeletedAt));
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_FORBIDDEN);
        }
        return doc;
    }

    /**
     * 生效的文件对象键：盖过章用带签那份，否则用原件。
     *
     * @param doc 文书
     * @return 对象键；都没有时 null
     */
    private String effectiveKey(Document doc) {
        if (doc.getSignedPdfOssKey() != null && !doc.getSignedPdfOssKey().isBlank()) {
            return doc.getSignedPdfOssKey();
        }
        if (doc.getPdfOssKey() != null && !doc.getPdfOssKey().isBlank()) {
            return doc.getPdfOssKey();
        }
        return null;
    }

    /**
     * 坐标越界校验（容差 1pt：客户端四舍五入会带来舍入误差）。
     *
     * @param bo 叠章入参
     * @throws BusinessException 401001
     */
    private void assertInsidePage(SignBO bo) {
        double eps = 1d;
        if (bo.getPosX() + bo.getWidth() > bo.getBasePageW() + eps
                || bo.getPosY() + bo.getHeight() > bo.getBasePageH() + eps) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "签名超出页面范围");
        }
    }

    /**
     * 对象键 → 10 位短哈希（预览图缓存前缀用）。
     *
     * @param key 对象键
     * @return 10 位小写十六进制
     */
    private String shortHash(String key) {
        return PdfMetadataWriter.sha256(key.getBytes(StandardCharsets.UTF_8)).substring(0, 10);
    }

    /**
     * 解析 `pv{短哈希}:{页号}.png` 里的页号。
     *
     * @param originalName 原始文件名
     * @param prefix       前缀（`pv{短哈希}:`）
     * @return 页号；解析失败返回 -1
     */
    private int parsePageNo(String originalName, String prefix) {
        if (originalName == null || !originalName.startsWith(prefix)
                || !originalName.endsWith(".png")) {
            return -1;
        }
        String middle = originalName.substring(prefix.length(), originalName.length() - 4);
        try {
            return Integer.parseInt(middle);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 文件名里去掉路径分隔符等非法字符。
     *
     * @param title 文书标题
     * @return 可安全下载的文件名主体（截断到 40 字，避免过长）
     */
    private String trimFileName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "").trim();
        if (cleaned.isEmpty()) {
            return "文书";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
