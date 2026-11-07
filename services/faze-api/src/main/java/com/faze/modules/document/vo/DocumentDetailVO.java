/*
 * 职责：App 端文书详情（段 4 C1 / E4）。
 * 调用方：GET /app/documents/{id}。
 * 数据来源：document + doc_template。
 * 注意：
 *   1. `formData` 回给 App 用于「返回表单修改」时回填；`formSnapshot` **不返回**
 *      （它是生成瞬间的冻结数据，只用于复现与审计）。
 *   2. `pdfUrl` 是现签的 60 秒地址，不是存库的固定 URL。
 *   3. `errorMsg` 只在 `status=failed` 时有值，内容是**人能看懂的原因**（不是堆栈）。
 *      客户端直接展示它（这是后端给的稳定文案，不是 e.getMessage()）。
 */
package com.faze.modules.document.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文书详情。
 */
@Data
public class DocumentDetailVO {

    /** 主键（序列化为字符串）。 */
    private Long id;

    /** 标题。 */
    private String title;

    /** draft / generated / signed / failed。 */
    private String status;

    /** 当前表单数据（JSON 字符串，回填表单用）。 */
    private String formData;

    /** 模板主键。 */
    private Long templateId;

    /** 生成时用的模板版本。 */
    private Integer templateVersion;

    /** PDF 短时地址（未生成时为 null）。 */
    private String pdfUrl;

    /** 失败原因（仅 status=failed 时有值）。 */
    private String errorMsg;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
