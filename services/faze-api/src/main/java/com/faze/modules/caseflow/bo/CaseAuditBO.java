/*
 * 职责：PC 立案审核入参（`POST /admin/cases/{id}/audit`）。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController → AdminCaseService。
 * 数据来源：PC A-19 案件管理抽屉的「立案材料」Tab。
 * 注意：
 *   1. `action` 只允许 approve / reject；reject 时 `reason` 必填（≤500 字），由 Service 校验。
 *   2. 审核必须写 operation_log，动作码 `CASE_AUDIT`，detail 存 before/after（§三 B2 第 4 条）。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 立案审核入参。
 */
@Data
public class CaseAuditBO {

    /** 动作：approve / reject。 */
    @NotBlank(message = "审核动作不能为空")
    @Size(max = 10, message = "审核动作取值过长")
    private String action;

    /** 驳回理由（reject 时必填）。 */
    @Size(max = 500, message = "驳回理由不能超过 500 字")
    private String reason;
}
