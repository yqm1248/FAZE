/*
 * 职责：A-22 工单回复的入参。
 * 调用方：modules/ops 的 AdminTicketController（`POST /admin/tickets/{id}/reply`）。
 * 数据来源：HTTP 请求体。
 * 注意：
 *   1. `content` 必填：空回复等于「点了发送但用户什么都没收到」，还占一条通知。
 *   2. **回复正文落 `notification`**（不是 service_ticket）：一条工单可多次回复，
 *      通知表天然支持多条与已读状态（工作流 C2 第 3 条）。
 *   3. 回复成功后工单状态 → `replied`（状态机校验）。
 */
package com.faze.modules.ops.admin.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单回复入参。
 */
@Data
public class TicketReplyBO {

    /** 回复正文（通过站内信与 App 推送送达）。 */
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 500, message = "回复内容不能超过 500 字")
    private String content;
}
