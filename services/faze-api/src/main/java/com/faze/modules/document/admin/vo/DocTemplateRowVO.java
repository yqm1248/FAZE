/*
 * 职责：A-18 模板列表行（段 4 B1）。
 * 调用方：AdminDocTemplateController#list → AdminDocTemplateService#list。
 * 数据来源：doc_template。
 * 注意：
 *   1. **列表不返回 template_html**：单条 5~50KB，20 条就是 1MB，列表页只显示名称与状态。
 *      详情接口才带 HTML。
 *   2. `id` 必须是 `Long`（会被 JacksonConfig 序列化成字符串，接口规范 §5.3 裁决 5）——
 *      JS 的 number 精度只有 53 位，雪花/自增 id 都会丢精度。
 *   3. `fieldCount` 是给运营看的「这个模板有几个字段」，从 field_schema 解析出来，不落库。
 */
package com.faze.modules.document.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 模板列表行。
 */
@Data
public class DocTemplateRowVO {

    /** 主键（序列化为字符串）。 */
    private Long id;

    /** 模板编码。 */
    private String code;

    /** 模板名。 */
    private String name;

    /** 分类。 */
    private String category;

    /** 语言。 */
    private String lang;

    /** 版本。 */
    private Integer version;

    /** draft / active / inactive。 */
    private String status;

    /** 排序。 */
    private Integer sortOrder;

    /** 字段数（从 field_schema 解析）。 */
    private Integer fieldCount;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
