/*
 * 职责：App 端「文书模板列表」的行对象（段 4 C1 / E1）。
 * 调用方：GET /app/doc-templates。
 * 数据来源：doc_template（status='active'，按 sort_order 排序）。
 * 注意：**返回完整 `field_schema`**——App 的动态表单要靠它渲染，不能只给模板名。
 *      这会让响应体略大（单个 schema 1~5KB），但省掉一次「点进模板再拉表单」的往返。
 */
package com.faze.modules.document.vo;

import lombok.Data;

/**
 * 模板简要信息（含字段定义）。
 */
@Data
public class DocTemplateBriefVO {

    /** 主键（序列化为字符串）。 */
    private Long id;

    /** 模板编码。 */
    private String code;

    /** 模板名。 */
    private String name;

    /** 分类。 */
    private String category;

    /** 排序。 */
    private Integer sortOrder;

    /** 字段定义 JSON 字符串（App 动态表单用它渲染）。 */
    private String fieldSchema;
}
