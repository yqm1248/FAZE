/*
 * 职责：导出结果（段 4 F1 `GET /app/documents/{id}/export?format=pdf`）。
 * 调用方：AppDocumentController → DocDeliveryService。
 * 数据来源：document 的 `signed_pdf_oss_key`（有签名时）或 `pdf_oss_key`，现签 60 秒地址。
 * 注意：
 *   1. `url` 是**60 秒有效**的现签地址，61 秒后同一 url 会 403（`4-A7` 第 3 项判据）。
 *   2. `fileName` 由服务端拼（标题 + 日期），客户端不要再自造文件名——中文文件名在
 *      不同浏览器下的编码表现不同，由服务端统一给出可读名。
 *   3. 只有 pdf，不出 Word（工作流 §零.3 第 12 项）。
 */
package com.faze.modules.document.vo;

import lombok.Data;

/**
 * 导出结果。
 */
@Data
public class DocExportVO {

    /** 现签的 60 秒下载地址。 */
    private String url;

    /** 建议文件名（含扩展名）。 */
    private String fileName;

    /** 有效期（秒），恒为 60。 */
    private Long expiresIn;
}
