/*
 * 职责：feedback 表的实体映射（意见反馈，第 7 段 A1 建表）。
 * 调用方：modules/ops 的 FeedbackService。
 * 数据来源：V700__seg7_tables.sql（10 字段）。
 * 注意：
 *   1. `content` 在表上是 `NOT NULL`，但**业务上「选填」**（APP 58 稿：具体情况选填）：
 *      空时由服务端填类型中文标签，保证落库有值且列表可读——不是让用户必填。
 *   2. `contact` 是选填手机号，**落库不脱敏、出参脱敏**（本人能看全，列表给别人看脱敏）。
 *   3. 提交时同事务生成 service_ticket（category='feedback'）。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 意见反馈。
 */
@Data
@TableName("feedback")
public class Feedback {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 反馈人 app_user.id。 */
    private Long userId;

    /** 反馈类型：feature / bug / experience / other。 */
    private String type;

    /** 具体情况（选填；空时服务端填类型中文标签）。 */
    private String content;

    /** 截图 oss_key 数组（JSON）。 */
    private String images;

    /** 联系方式（手机号，选填）。 */
    private String contact;

    /** 处理状态：pending / processing / resolved / rejected。 */
    private String status;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）。 */
    private LocalDateTime deletedAt;
}
