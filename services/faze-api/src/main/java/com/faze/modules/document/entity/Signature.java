/*
 * 职责：signature 表的实体映射（段 4 A1/D1/D2）。
 * 调用方：modules/document/mapper/SignatureMapper、SignatureService、DocDeliveryService。
 * 数据来源：V400__create_document_tables.sql 建的 signature（15 字段）。
 * 注意：
 *   1. **一表两用，查询必须显式带 `document_id IS NULL` 或 `IS NOT NULL`**：
 *      `document_id IS NULL` = 用户存的签名图（签名库，28-doc-seal 页）；
 *      非空 = 某份文书上的这枚签名（含页码、坐标、盖签前哈希）。
 *      写完「按 user_id 查」的方法名是这里最容易犯的错（工作流 §六 R5）。
 *   2. 本表**没有 `deleted_at`**（15 字段是技术路线 §5.6 原文，不许加字段），
 *      所以签名库的「删除」是物理 DELETE + 把对应 `file_record.status` 置 `orphan`，
 *      图像文件本身不删（留待段 7 清理任务）。这是对工作流 D1「软删」的一处已登记偏离。
 *   3. 坐标是**屏幕坐标系**（原点左上）的 pt；落图时由 `SignatureStamper` 换算成 PDF 的左下原点。
 */
package com.faze.modules.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 电子签章（签名库 + 文书上的签名，一表两用）。
 */
@Data
@TableName("signature")
public class Signature {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户。 */
    private Long userId;

    /** 签名 PNG 对象键（私有，透明底）。 */
    private String imageOssKey;

    /** NULL=签名库；非空=这份文书上的这枚签名。 */
    private Long documentId;

    /** 第几页（从 1 开始）；签名库行为 NULL。 */
    private Integer pageNo;

    /** 相对页面左上角的 x（pt）。 */
    private Integer posXPt;

    /** 相对页面左上角的 y（pt）。 */
    private Integer posYPt;

    /** 签名在页面上的宽度（pt）。 */
    private Integer widthPt;

    /** 签名在页面上的高度（pt）。 */
    private Integer heightPt;

    /** 用户界面上的页面宽度（pt，A4=595），用于缩放校验。 */
    private Integer basePageWPt;

    /** 用户界面上的页面高度（pt，A4=842）。 */
    private Integer basePageHPt;

    /** 盖签前内容流的 SHA-256（事后比对用）。 */
    private String documentHash;

    /** 哈希算法。 */
    private String hashAlgo;

    /** 签署时间（UTC）；签名库行 = 入库时间。 */
    private LocalDateTime signedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
