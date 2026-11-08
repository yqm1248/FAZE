/*
 * 职责：C 端站内信列表的行出参。
 * 调用方：modules/ops 的 NotificationQueryService / NotificationController。
 * 数据来源：faze.notification。
 * 注意：
 *   1. `bizType` + `bizId` 给前端做跳转（公告 → 公告详情，工单 → 工单详情）。
 *   2. 不返回 `deletedAt` / `updatedAt`：客户端不关心。
 *   3. `content` 可能为 null（纯标题通知）。
 */
package com.faze.modules.ops.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内信行。
 */
@Data
public class NotificationVO {

    /** 主键（游标）。 */
    private Long id;

    /** 类型：system / order / im / case / promotion。 */
    private String type;

    /** 标题。 */
    private String title;

    /** 正文。 */
    private String content;

    /** 业务类型：announcement / ticket / order / review / security。 */
    private String bizType;

    /** 业务主键。 */
    private Long bizId;

    /** 是否已读：0 / 1。 */
    private Integer isRead;

    /** 首次已读时间（UTC）。 */
    private LocalDateTime readAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
