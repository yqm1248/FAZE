/*
 * 职责：A-22 关闭工单的入参。
 * 调用方：modules/ops 的 AdminTicketController（`POST /admin/tickets/{id}/close`）。
 * 数据来源：HTTP 请求体。
 * 注意：
 *   1. `reason` **必填**：关闭是终态（不可回退），没有理由的关闭在用户申诉时无法自证
 *      （工作流 C3 第 4 条 / C1 状态机表格「close_reason 必填」）。
 *   2. 关闭后自动给用户发站内信（工作流 §二.3 第 3 项：关闭时必填原因 + 自动发通知）。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单关闭入参。
 */
@Data
public class TicketCloseBO {

    /** 关闭原因（必填）。 */
    @NotBlank(message = "关闭原因不能为空")
    @Size(max = 500, message = "关闭原因不能超过 500 字")
    private String reason;
}
