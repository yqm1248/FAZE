/*
 * 职责：PC 送达选文书用的文书摘要（`GET /admin/cases/{id}/documents`）。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController → AdminCaseService.documents。
 * 数据来源：document 表（经 DocumentQueryService 只读）。
 * 注意：
 *   1. **只给选文书要用的最小字段**：不返回 `form_data` / `form_snapshot` / OSS key。
 *   2. `hasPdf` 是「能否发起送达」的唯一判据（工作流 C1 第 1 条：没有 PDF 不许送达，
 *      服务端在 `DeliveryServiceImpl.send` 里会再挡一次，前端据此置灰）。
 */
package com.faze.modules.caseflow.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 可送达文书摘要。
 */
@Data
public class AdminCaseDocVO {

    /** 文书 id。 */
    private Long id;

    /** 文书标题。 */
    private String title;

    /** 文书类型（模板分类冗余）。 */
    private String docType;

    /** draft / generated / signed / failed。 */
    private String status;

    /** 是否已有可送达的 PDF（盖过章用带签名那份，服务端同口径）。 */
    private Boolean hasPdf;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
