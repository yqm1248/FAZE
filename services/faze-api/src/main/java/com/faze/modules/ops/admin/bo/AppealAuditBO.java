/*
 * 职责：A-25 右栏申诉复核的入参。
 * 调用方：modules/ops 的 AdminAppealService（`POST /admin/sensitive-word-appeals/{id}/audit`）。
 * 数据来源：HTTP 请求体（两个按钮：通过申诉 / 维持原判）。
 * 注意：
 *   1. `action` 只接受 `accepted` / `rejected` 两个值；**没有「降级」**
 *      （A-25 稿只画了两个按钮，技术路线写的「维持/撤销/降级」以稿为准）。
 *   2. `reason` 必填：复核结论要能追溯，也是通知用户的内容。
 *   3. 这里用 `String` + `@Pattern` 而不是枚举：HTTP 入参保持字符串，
 *      由 Service 转枚举并给出 401001（参数校验失败）。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 申诉复核入参。
 */
@Data
public class AppealAuditBO {

    /** 处置动作：accepted 通过申诉 / rejected 维持原判。 */
    @NotBlank(message = "处置动作不能为空")
    @Pattern(regexp = "accepted|rejected", message = "处置动作只能是 accepted 或 rejected")
    private String action;

    /** 复核说明。 */
    @NotBlank(message = "复核说明不能为空")
    @Size(max = 500, message = "复核说明不能超过 500 字")
    private String reason;
}
