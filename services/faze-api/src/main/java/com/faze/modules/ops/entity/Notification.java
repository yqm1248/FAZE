/*
 * 职责：notification 表的实体映射（站内通知，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 NotificationService（写）、D 组的站内信读接口。
 * 数据来源：V700__seg7_tables.sql（12 字段）。
 * 注意：
 *   1. **一条通知一行**：公告发给 1000 人就是 1000 行，批次统计在 announcement 上累计
 *      （这就是为什么需要 announcement 表，见 V701 注释）。
 *   2. `biz_type` + `biz_id` 是「这条通知因什么而来」的定位：announcement / ticket / order / review。
 *      公告的打开率回写靠 `biz_type='announcement'` 找到批次。
 *   3. `is_read` 只在 0→1 时回写 `read_at`，重复已读不再改时间（打开率只算首次）。
 *   4. 软删：用户删除消息置 `deleted_at`，列表默认过滤。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内通知。
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收用户 app_user.id。 */
    private Long userId;

    /** 类型：system / order / im / case / promotion。 */
    private String type;

    /** 标题（已渲染）。 */
    private String title;

    /** 正文（已渲染）。 */
    private String content;

    /** 业务类型：announcement / ticket / order / review。 */
    private String bizType;

    /** 业务主键。 */
    private Long bizId;

    /** 是否已读：0 未读 / 1 已读。 */
    private Integer isRead;

    /** 首次已读时间（UTC）。 */
    private LocalDateTime readAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）。 */
    private LocalDateTime deletedAt;
}
