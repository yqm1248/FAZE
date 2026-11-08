/*
 * 职责：A-23 公告列表的行出参。
 * 调用方：modules/ops 的 AdminAnnouncementService / AdminAnnouncementController。
 * 数据来源：faze.announcement。
 * 注意：
 *   1. `openRate` 是**服务端算好的百分比字符串**（如 `"23.4%"`），
 *      不是让前端拿 open_count / reach_count 自己除——口径只有一份。
 *   2. `reachCount` 为 0 时 `openRate` 返回 `"—"`（除零保护）。
 *   3. 状态中文名由服务端给（`statusLabel`），前端不维护枚举翻译。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 公告行。
 */
@Data
public class AnnouncementVO {

    /** 主键。 */
    private Long id;

    /** 公告编号。 */
    private String announcementNo;

    /** 标题。 */
    private String title;

    /** 目标类型。 */
    private String targetType;

    /** 渠道 JSON。 */
    private String channels;

    /** 状态：draft / scheduled / sending / sent / failed。 */
    private String status;

    /** 状态中文名。 */
    private String statusLabel;

    /** 定时发送时间（UTC）。 */
    private LocalDateTime scheduledAt;

    /** 发送完成时间（UTC）。 */
    private LocalDateTime sentAt;

    /** 触达数。 */
    private Integer reachCount;

    /** 打开数。 */
    private Integer openCount;

    /** 打开率（如 `"23.4%"`；触达为 0 时为 `"—"`）。 */
    private String openRate;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
