/*
 * 职责：我的投诉列表的行出参。
 * 调用方：modules/ops 的 ComplaintController（`GET /api/v1/app/complaints`）。
 * 数据来源：faze.complaint + 按工单回查的最近一条回复（notification）。
 * 注意：
 *   1. `replyContent` / `replyAt` 来自 `notification`（biz_type='ticket'），
 *      不是 service_ticket 的字段——一条工单可多次回复，只有通知表能承载多条
 *      （工作流 C2 第 3 条）。
 *   2. `ticketNo` 要给：用户联系客服时报单号最快定位。
 *   3. 不返回 handlerId / 内部处理人信息（用户不需要知道是谁处理的）。
 */
package com.faze.modules.ops.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 我的投诉行。
 */
@Data
public class ComplaintVO {

    /** 投诉 id。 */
    private Long id;

    /** 投诉对象类型。 */
    private String targetType;

    /** 投诉对象主键。 */
    private Long targetId;

    /** 投诉内容。 */
    private String reason;

    /** 截图 oss_key 数组。 */
    private String images;

    /**
     * 投诉状态。
     *
     * <p><b>口径（2026-11-09 修正）</b>：有工单时返回**工单状态**
     * （`waiting` / `processing` / `replied` / `closed`），无工单时退回 `complaint.status`
     * （`pending` / `processing` / `resolved` / `rejected`）。原因：投诉落库时同时生成工单，
     * 用户感知的「处理到哪一步了」由工单流转决定；只回 complaint.status 会让
     * 「后台已分配/已回复」在 APP 上永远显示 `pending`（`7-A5` 实测踩过）。</p>
     */
    private String status;

    /**
     * 工单状态（`waiting` / `processing` / `replied` / `closed`）；无工单为 null。
     *
     * <p>与 `status` 同值时也返回，供前端明确区分「投诉单状态」与「工单流转状态」，
     * 不让前端去猜 `status` 的取值域。</p>
     */
    private String ticketStatus;

    /** 处理结果说明。 */
    private String result;

    /** 关联工单号（TK...）。 */
    private String ticketNo;

    /** 最新回复内容；无回复为 null。 */
    private String replyContent;

    /** 最新回复时间（UTC）；无回复为 null。 */
    private LocalDateTime replyAt;

    /** 提交时间（UTC）。 */
    private LocalDateTime createdAt;
}
