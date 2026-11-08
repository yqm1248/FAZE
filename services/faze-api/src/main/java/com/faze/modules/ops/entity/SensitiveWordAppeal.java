/*
 * 职责：sensitive_word_appeal 表的实体映射（敏感词误判申诉，第 7 段 A1 建表）。
 * 调用方：modules/ops 的用户端申诉接口、管理端申诉复核接口。
 * 数据来源：V702__create_sensitive_word_appeal.sql（10 字段）。
 * 注意：
 *   1. **`uk_appeal_review` 让「一条命中只能申诉一次」由数据库兜底**：
 *      应用层先查后插在并发下会双写，接口返回 410009 的依据是这个唯一键。
 *   2. 只有 `content_review.level='block'` 的命中可申诉（review/warn 用户看不见，无从申诉）。
 *   3. `status` 只有 pending / accepted / rejected 三态；**没有「降级」**
 *      （A-25 稿只有两个按钮，以稿为准）。
 *   4. accepted 的处置是「把对应 content_review.review_result 改成 ignored」，
 *      **词条本身不动**（A-25 稿明写「词条本身不变」）。
 */
package com.faze.modules.ops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 敏感词误判申诉。
 */
@Data
@TableName("sensitive_word_appeal")
public class SensitiveWordAppeal {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申诉人 app_user.id。 */
    private Long userId;

    /** 被误判的命中记录 content_review.id。 */
    private Long contentReviewId;

    /** 申诉理由。 */
    private String appealReason;

    /** 状态：pending / accepted / rejected。 */
    private String status;

    /** 复核管理员 app_user.id。 */
    private Long handlerId;

    /** 复核说明。 */
    private String handleReason;

    /** 复核时间（UTC）。 */
    private LocalDateTime handledAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
