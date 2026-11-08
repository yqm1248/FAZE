/*
 * 职责：叠章结果（段 4 D2 `POST /app/documents/{id}/sign`）。
 * 调用方：AppDocumentController → DocDeliveryService。
 * 数据来源：signature 新增行 + document.signed_pdf_oss_key。
 * 注意：
 *   1. `signedPdfUrl` 指向**新文件**（`signed_pdf_oss_key`），原 `pdf_oss_key` 那对象不变
 *      ——`4-A4`/D2 自查要求「原 PDF 未被修改（hash 不变）」。
 *   2. `documentHash` 是对**盖签前** PDF 整个文件算的 SHA-256，已入库 `signature.document_hash`；
 *      事后把 `pdf_oss_key` 的字节重新哈希一遍就能比对（`4-A5`/D3 自查）。
 */
package com.faze.modules.document.vo;

import lombok.Data;

/**
 * 叠章结果。
 */
@Data
public class SignResultVO {

    /** 叠签后 PDF 的 60 秒签名地址。 */
    private String signedPdfUrl;

    /** 盖签前 PDF 的 SHA-256（小写十六进制）。 */
    private String documentHash;
}
