/*
 * 职责：A-20 左栏命中记录列表的行出参。
 * 调用方：modules/ops 的 AdminContentReviewService / AdminContentReviewController。
 * 数据来源：faze.content_review。
 * 注意：
 *   1. **不返回原文全文**，只有 `hitSnippet`（命中词前后各 40 字，已脱敏）。
 *      这是 §零.4 第 2 条红线：不给 A-20 开「拉全量聊天记录」的口子。
 *   2. `bizTypeLabel` 由服务端给中文名，前端不维护翻译。
 *   3. `hitWords` 是 JSON 字符串（`[{word,level,category,start,end}]`），
 *      前端自己解析并高亮；服务端不解析（少一处结构耦合）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 内容审核命中行。
 */
@Data
public class ContentReviewVO {

    /** 主键。 */
    private Long id;

    /** 业务类型 code。 */
    private String bizType;

    /** 业务类型中文名。 */
    private String bizTypeLabel;

    /** 业务主键。 */
    private Long bizId;

    /** 内容作者 app_user.id。 */
    private Long bizUserId;

    /** 命中词 JSON。 */
    private String hitWords;

    /** 命中片段（已脱敏，非全文）。 */
    private String hitSnippet;

    /** 级别：block / review / warn。 */
    private String level;

    /** 判定引擎。 */
    private String engine;

    /** 处置结果：pending / ignored / warned / banned。 */
    private String reviewResult;

    /** 处置时间（UTC）。 */
    private LocalDateTime reviewedAt;

    /** 命中时间（UTC）。 */
    private LocalDateTime createdAt;
}
