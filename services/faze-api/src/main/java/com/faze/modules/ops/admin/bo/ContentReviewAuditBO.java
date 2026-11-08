/*
 * 职责：A-20 命中记录的处置入参（忽略 / 警告 / 封禁）。
 * 调用方：modules/ops 的 AdminContentReviewService（`POST /admin/content-reviews/{id}/audit`）。
 * 数据来源：HTTP 请求体（稿面三个按钮）。
 * 注意：
 *   1. `action` 三选：`ignore`（误判，不处置用户）/ `warn`（写警告 + 站内信）/ `ban`（封禁）。
 *   2. `reason` 必填：封禁是要通知用户的，没有理由的封禁在申诉时无法自证。
 *   3. 同一记录只能处置一次（重复返回 410008），所以不需要「改判」语义——
 *      真要改判走申诉流程（B2）。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 命中记录处置入参。
 */
@Data
public class ContentReviewAuditBO {

    /** 处置动作：ignore / warn / ban。 */
    @NotBlank(message = "处置动作不能为空")
    @Pattern(regexp = "ignore|warn|ban", message = "处置动作只能是 ignore / warn / ban")
    private String action;

    /** 处置理由。 */
    @NotBlank(message = "处置理由不能为空")
    @Size(max = 500, message = "处置理由不能超过 500 字")
    private String reason;
}
