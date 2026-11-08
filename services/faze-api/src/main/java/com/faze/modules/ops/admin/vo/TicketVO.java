/*
 * 职责：A-21 工单列表的行出参。
 * 调用方：modules/ops 的 AdminTicketService / AdminTicketController。
 * 数据来源：faze.service_ticket。
 * 注意：
 *   1. **超时不落列**（裁决 16）：`overtime` / `overtimeHours` 是查询时实时算的，
 *      前端据此把整行标红（A-21 稿面要求）。
 *   2. `categoryLabel` / `priorityLabel` / `statusLabel` 由服务端给中文名，
 *      前端不维护枚举翻译。
 *   3. 不返回提交人手机号：列表页只显示工单信息，联系方式在详情页（脱敏）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 工单行。
 */
@Data
public class TicketVO {

    /** 主键。 */
    private Long id;

    /** 工单号。 */
    private String ticketNo;

    /** 类型 code。 */
    private String category;

    /** 类型中文名。 */
    private String categoryLabel;

    /** 标题。 */
    private String title;

    /** 优先级 code。 */
    private String priority;

    /** 优先级中文名。 */
    private String priorityLabel;

    /** 状态 code。 */
    private String status;

    /** 状态中文名。 */
    private String statusLabel;

    /** 提交人 app_user.id。 */
    private Long userId;

    /** 处理人 app_user.id。 */
    private Long agentId;

    /** SLA 到期时间（UTC）。 */
    private LocalDateTime slaDueAt;

    /** 是否已超时（实时判定）。 */
    private Boolean overtime;

    /** 超时小时数（未超时为 0）。 */
    private Long overtimeHours;

    /** 关闭时间（UTC）。 */
    private LocalDateTime closedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
