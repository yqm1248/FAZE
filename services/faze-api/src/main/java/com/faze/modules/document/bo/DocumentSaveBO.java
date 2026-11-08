/*
 * 职责：App 端保存文书草稿的入参（段 4 C1）。
 * 调用方：AppDocumentController#save。
 * 数据来源：App 表单页（动态表单按 field_schema 渲染）。
 * 注意：
 *   1. `id` 为空表示新建；非空表示更新同一份草稿——**必须带归属校验**（不是自己的 id 一律 403）。
 *   2. `formData` 收 JSON **字符串**：结构由模板的 field_schema 决定，后端不建强类型，
 *      只在生成时按 schema 逐字段校验（C2 的 TemplateRenderer 负责）。
 *   3. `title` 可为空（首次自动保存时用户还没填标题），生成时用「模板名」兜底。
 *   4. 草稿**不生成文件**，只存 form_data；生成是另一个接口（`POST /app/documents/{id}/generate`）。
 */
package com.faze.modules.document.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文书草稿保存入参。
 */
@Data
public class DocumentSaveBO {

    /** 草稿主键；空表示新建。 */
    private Long id;

    /** 模板主键。 */
    @NotNull(message = "模板不能为空")
    private Long templateId;

    /** 文书标题。 */
    @Size(max = 200, message = "标题过长")
    private String title;

    /** 表单数据（JSON 字符串）。 */
    @NotBlank(message = "表单数据不能为空")
    private String formData;
}
