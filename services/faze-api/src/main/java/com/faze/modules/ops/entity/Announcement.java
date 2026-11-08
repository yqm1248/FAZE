/*
 * 职责：announcement 表的实体映射（公告批次，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 AnnouncementService / AnnouncementSendService / NotificationQueryService（打开率）。
 * 数据来源：V701__create_announcement.sql（15 字段）。
 * 注意：
 *   1. **一条公告一行**：发给 1000 人就是 1000 行 notification，本表只存批次信息与计数。
 *   2. `sent` 不可回退（裁决 26）：状态机 draft → scheduled/sent，scheduled → sending → sent/failed。
 *   3. `reach_count` / `open_count` 是计数器：明细在 notification（biz_type='announcement'）。
 *   4. `target_value` 是 JSON（all 为 null / role / region / user_ids 四种形状），不拆列。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 公告批次。
 */
@Data
@TableName("announcement")
public class Announcement {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公告编号：AN + yyyyMMdd + 6 位序号。 */
    private String announcementNo;

    /** 标题。 */
    private String title;

    /** 正文。 */
    private String content;

    /** 目标人群类型：all / role / region / user_ids。 */
    private String targetType;

    /** 目标人群参数（JSON）。 */
    private String targetValue;

    /** 发送渠道（JSON 数组）。 */
    private String channels;

    /** 状态：draft / scheduled / sending / sent / failed。 */
    private String status;

    /** 定时发送时间（UTC）。 */
    private LocalDateTime scheduledAt;

    /** 实际发送完成时间（UTC）。 */
    private LocalDateTime sentAt;

    /** 触达数（实际写入 notification 的条数）。 */
    private Integer reachCount;

    /** 打开数（首次已读时 +1）。 */
    private Integer openCount;

    /** 创建管理员 app_user.id。 */
    private Long createdBy;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
