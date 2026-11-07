/*
 * 职责：document 表的实体映射（段 4 A1/C1）。
 * 调用方：modules/document/mapper/DocumentMapper、AppDocumentService、DocGenerateHandler。
 * 数据来源：V400__create_document_tables.sql 建的 document（16 字段）。
 * 注意：
 *   1. **归属字段 `user_id` 必须进每条按 id 的查询的 where**（后端规范 §11.2）：
 *      `selectById` 再判归属会有 TOCTOU 窗口，而且漏判时没有提示。
 *   2. `form_data` 是草稿可改的当前表单；`form_snapshot` 是**生成成功那一刻**冻结的表单，
 *      之后 form_data 怎么改都不动它——这是「模板/表单热更新不改历史文书」的另一半。
 *   3. `status=failed` 时 `pdf_oss_key` / `signed_pdf_oss_key` 必须为 NULL（失败不留半成品）。
 *   4. `*_oss_key` 存对象键不存 URL；取文件时现签 60 秒地址。
 */
package com.faze.modules.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文书记录。
 */
@Data
@TableName("document")
public class Document {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户。 */
    private Long userId;

    /** 模板主键（可空：模板停用后历史文书仍在）。 */
    private Long templateId;

    /** 生成时用的模板版本。 */
    private Integer templateVersion;

    /** 文书语言；段 4 固定 zh-Hans。 */
    private String lang;

    /** 文书标题。 */
    private String title;

    /** 冗余模板分类。 */
    private String docType;

    /** 当前表单数据（JSON 字符串）。 */
    private String formData;

    /** 生成瞬间的表单快照（JSON 字符串，不可变）。 */
    private String formSnapshot;

    /** draft / generated / signed / failed。 */
    private String status;

    /** 生成的 PDF 对象键。 */
    private String pdfOssKey;

    /** 叠签后的 PDF 对象键。 */
    private String signedPdfOssKey;

    /** 分享给律师时记；段 4 只写不读。 */
    private Long lawyerId;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）。 */
    private LocalDateTime deletedAt;
}
