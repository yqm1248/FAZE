/*
 * 职责：doc_template 表的实体映射（段 4 A1/B1）。
 * 调用方：modules/document/mapper/DocTemplateMapper、DocTemplateAdminService、App 模板列表（C1）。
 * 数据来源：V400__create_document_tables.sql 建的 doc_template（13 字段）。
 * 注意：
 *   1. **改模板不是原地 UPDATE `template_html`**：那样历史文书重渲染会变内容（`4-A4` 直接失败）。
 *      正确做法是 INSERT 一条 `(code, lang, version+1)` 新行、把旧行置 `inactive`，旧行永不删。
 *   2. `status` 三态：draft / active / inactive。**没有 `is_enabled`**（工作流 §三 A1 的裁决：
 *      两个字段并存会出现 `status='active'` 但 `is_enabled=0` 的矛盾状态）。
 *   3. `template_html` 是 MEDIUMTEXT 不是 TEXT：TEXT 上限 64KB，5 万字的模板会超。
 *   4. `field_schema` 是 JSON 字符串，解析成 FieldSchema 由 service 负责。
 */
package com.faze.modules.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文书模板（含版本行）。
 */
@Data
@TableName("doc_template")
public class DocTemplate {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板编码，如 LAWSUIT-001。 */
    private String code;

    /** 模板名。 */
    private String name;

    /** 分类：诉讼文书 / 合同文书 / 劳动文书 / 跨境电商。 */
    private String category;

    /** 模板 HTML 全文（含占位符与 style）。 */
    private String templateHtml;

    /** 预览缩略图对象键。 */
    private String previewOssKey;

    /** 字段定义（JSON 字符串）。 */
    private String fieldSchema;

    /** 语言；段 4 固定 zh-Hans。 */
    private String lang;

    /** 版本号；改版时插新行 version+1。 */
    private Integer version;

    /** App 端排序。 */
    private Integer sortOrder;

    /** draft / active / inactive。 */
    private String status;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
