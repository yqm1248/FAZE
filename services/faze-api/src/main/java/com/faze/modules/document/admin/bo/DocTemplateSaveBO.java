/*
 * 职责：A-18 新建/保存模板的入参（段 4 B1）。
 * 调用方：AdminDocTemplateController#create / #update。
 * 数据来源：前端表单（含 monaco 编辑器里的 HTML）。
 * 注意：
 *   1. **`templateHtml` 与 `fieldSchema` 不做 @NotBlank，而在 service 里校验**：
 *      它们要过 TemplateHtmlValidator（well-formed + 标签白名单 + 占位符一一对应），
 *      单纯的非空校验会让"空模板"通过格式校验再在渲染时炸。
 *   2. `fieldSchema` 收的是 JSON **字符串**（前端 monaco 里编辑的就是文本），
 *      service 负责解析成 FieldSchema；解析失败 → 406002。
 *   3. `code` 创建后**不可改**：改 code 等于换了另一种模板，历史文书按 (template_id, version)
 *      还能找到，但按 code 聚合的统计会断。更新接口里忽略该字段。
 *   4. `status` 只允许 draft / active / inactive，创建时默认 draft（运营显式启用才进 App）。
 */
package com.faze.modules.document.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模板保存入参。
 */
@Data
public class DocTemplateSaveBO {

    /** 模板编码（创建后不可改）。 */
    @NotBlank(message = "模板编码不能为空")
    @Pattern(regexp = "^[A-Z][A-Z0-9-]{1,49}$", message = "编码须大写字母开头、仅含大写字母数字与短横线、≤50 字符")
    private String code;

    /** 模板名。 */
    @NotBlank(message = "模板名不能为空")
    @Size(max = 100, message = "模板名过长")
    private String name;

    /** 分类。 */
    @Size(max = 50, message = "分类过长")
    private String category;

    /** 模板 HTML（由 TemplateHtmlValidator 校验，不在这里做非空）。 */
    private String templateHtml;

    /** 字段定义 JSON 字符串（由 service 解析校验）。 */
    private String fieldSchema;

    /** App 端排序。 */
    private Integer sortOrder;

    /** 状态：draft / active / inactive；创建时不传默认 draft。 */
    @Pattern(regexp = "^(draft|active|inactive)$", message = "状态非法")
    private String status;
}
