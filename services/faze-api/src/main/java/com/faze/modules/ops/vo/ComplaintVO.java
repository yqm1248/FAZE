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

    /** 投诉状态：pending / processing / resolved / rejected。 */
    private String status;

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
