/*
 * 职责：A-25 敏感词库列表的行出参。
 * 调用方：modules/ops 的 AdminSensitiveWordService / AdminSensitiveWordController。
 * 数据来源：faze.sensitive_word。
 * 注意：
 *   1. 同时返回 `code` 与 `label`（category/level 各一对）：**中文名由服务端给**，
 *      前端不维护一份枚举翻译（否则加一个分类要改两处）。
 *   2. `hitCount` / `lastHitAt` 是运行期累计值，只读；列表页的「命中」「最近命中」两列直接用。
 *   3. 不返回 `createdBy`（对页面无意义、且管理员 id 在 RuoYi 库，本段查不到名字）。
 */
package com.faze.modules.ops.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 敏感词行。
 */
@Data
public class SensitiveWordVO {

    /** 主键。 */
    private Long id;

    /** 词条原文。 */
    private String word;

    /** 分类 code。 */
    private String category;

    /** 分类中文名。 */
    private String categoryLabel;

    /** 级别 code。 */
    private String level;

    /** 级别中文名。 */
    private String levelLabel;

    /** 语种。 */
    private String lang;

    /** 来源。 */
    private String source;

    /** 是否启用。 */
    private Integer enabled;

    /** 累计命中次数。 */
    private Long hitCount;

    /** 最近命中时间（UTC）。 */
    private LocalDateTime lastHitAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
