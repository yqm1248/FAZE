/*
 * 职责：A-25 右栏申诉列表的行出参。
 * 调用方：modules/ops 的 AdminAppealService / AdminAppealController。
 * 数据来源：faze.sensitive_word_appeal + 按 contentReviewId 回查的 content_review 片段。
 * 注意：
 *   1. `hitSnippet` 来自 content_review（已脱敏），**不回查原文**——
 *      回查原文会碰到「管理员能看全文」的红线，片段足够判断是否误判。
 *   2. `level` / `hitWords` 一起给，复核员要看「命中了哪个词、什么级别」才知道该不该通过申诉。
 *   3. 不返回申诉人手机号：A-25 稿只显示用户 id，且手机号属 L2，列表页不展示。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 申诉行。
 */
@Data
public class AppealVO {

    /** 主键。 */
    private Long id;

    /** 申诉人 app_user.id。 */
    private Long userId;

    /** 被误判的命中记录 id。 */
    private Long contentReviewId;

    /** 命中片段（已脱敏）。 */
    private String hitSnippet;

    /** 命中级别：block / review / warn。 */
    private String level;

    /** 命中词 JSON。 */
    private String hitWords;

    /** 申诉理由。 */
    private String appealReason;

    /** 状态：pending / accepted / rejected。 */
    private String status;

    /** 复核说明。 */
    private String handleReason;

    /** 复核时间（UTC）。 */
    private LocalDateTime handledAt;

    /** 提交时间（UTC）。 */
    private LocalDateTime createdAt;
}
