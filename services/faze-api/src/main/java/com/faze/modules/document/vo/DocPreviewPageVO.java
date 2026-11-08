/*
 * 职责：预览页的一项（段 4 E4 `GET /app/documents/{id}/preview`）——一页 PDF 渲染成的 PNG。
 * 调用方：AppDocumentController → DocDeliveryService。
 * 数据来源：PDF 用 `PdfPageRenderer` 渲染后落盘登记，`imageUrl` 现签 60 秒。
 * 注意：
 *   1. `widthPt`/`heightPt` 是**这一页真实的 PDF 页面尺寸**（A4 = 595×842），
 *      客户端盖章时把它当 `basePageW/H` 传回来，服务端据此做缩放校验（工作流 D2 第 3 条）。
 *   2. `pageNo` 从 **1** 开始（PDFBox 内部从 0 起，转换只在渲染时发生一次）。
 */
package com.faze.modules.document.vo;

import lombok.Data;

/**
 * 预览页。
 */
@Data
public class DocPreviewPageVO {

    /** 页码，从 1 开始。 */
    private Integer pageNo;

    /** 该页 PNG 的 60 秒签名地址。 */
    private String imageUrl;

    /** 页面宽度（pt）。 */
    private Integer widthPt;

    /** 页面高度（pt）。 */
    private Integer heightPt;
}
