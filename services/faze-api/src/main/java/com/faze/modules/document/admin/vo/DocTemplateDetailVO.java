/*
 * 职责：A-18 模板详情（段 4 B1）——含 template_html 与 field_schema，供 monaco 编辑器回填。
 * 调用方：AdminDocTemplateController#detail。
 * 数据来源：doc_template。
 * 注意：`previewUrl` 是**现签的 60 秒地址**，不是存库的固定 URL；
 *      前端拿到就渲染，不要缓存到 localStorage（过期即 403）。
 */
package com.faze.modules.document.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 模板详情。
 */
@Data
public class DocTemplateDetailVO {

    /** 主键（序列化为字符串）。 */
    private Long id;

    /** 模板编码。 */
    private String code;

    /** 模板名。 */
    private String name;

    /** 分类。 */
    private String category;

    /** 模板 HTML 全文。 */
    private String templateHtml;

    /** 字段定义 JSON 字符串（前端 monaco 里编辑）。 */
    private String fieldSchema;

    /** 语言。 */
    private String lang;

    /** 版本。 */
    private Integer version;

    /** 排序。 */
    private Integer sortOrder;

    /** 状态。 */
    private String status;

    /** 预览图短时地址（60 秒）；无预览图为 null。 */
    private String previewUrl;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
