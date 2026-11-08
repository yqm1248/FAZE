/*
 * 职责：complaint 表的实体映射（用户投诉，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 ComplaintService。
 * 数据来源：V700__seg7_tables.sql（12 字段）。
 * 注意：
 *   1. **原文不改**：`reason` 是用户原话，任何环节都不改写（敏感词只做标注，见 content_review）。
 *   2. `images` 是 oss_key 数组（JSON），≤3 张；**不存图片本身**，也不存完整 URL
 *      （URL 带签名会过期，存 key 由下载接口换签名 URL）。
 *   3. 提交时**同事务生成 service_ticket**（裁决 23）：投诉表只存投诉内容，
 *      处理流程全在工单上（A-21/A-22 只操作 ticket）。
 *   4. `status` 与工单状态不是一回事：投诉的 pending/processing/resolved/rejected
 *      是「投诉结论」，工单的 waiting/processing/replied/closed 是「处理进度」。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户投诉。
 */
@Data
@TableName("complaint")
public class Complaint {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 投诉人 app_user.id。 */
    private Long userId;

    /** 投诉对象类型：lawyer / order / platform。 */
    private String targetType;

    /** 投诉对象主键（律师 id / 订单 id）。 */
    private Long targetId;

    /** 投诉原因与诉求（原文）。 */
    private String reason;

    /** 截图 oss_key 数组（JSON）。 */
    private String images;

    /** 处理状态：pending / processing / resolved / rejected。 */
    private String status;

    /** 处理人（管理员 app_user.id）。 */
    private Long handlerId;

    /** 处理结果说明。 */
    private String result;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）。 */
    private LocalDateTime deletedAt;
}
