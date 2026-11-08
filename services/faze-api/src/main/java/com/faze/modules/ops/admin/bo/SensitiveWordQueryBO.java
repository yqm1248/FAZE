/*
 * 职责：A-25 敏感词库列表的查询条件。
 * 调用方：modules/ops 的 AdminSensitiveWordController。
 * 数据来源：HTTP 查询参数。
 * 注意：
 *   1. `kw` 是关键词模糊匹配；**带 kw 时列表按 `hit_count DESC` 排序**（工作流 B1 第 4 条：
 *      运营搜词是想看命中多的），不带 kw 时按 `created_at DESC`。
 *   2. `pageNum` / `pageSize` 的 clamp 在 Service 里做（超 100 收敛到 100）。
 *   3. `enabled` 用 Integer 而不是 boolean：null 表示「不筛」，false 表示「只筛停用」——
 *      用 boolean 会把「不筛」和「筛停用」混成同一个值。
 */
package com.faze.modules.ops.admin.bo;

import lombok.Data;

/**
 * 敏感词查询条件。
 */
@Data
public class SensitiveWordQueryBO {

    /** 级别：block / review / warn。 */
    private String level;

    /** 语种。 */
    private String lang;

    /** 分类。 */
    private String category;

    /** 关键词（模糊匹配 word）。 */
    private String kw;

    /** 是否启用：1 / 0；null 不筛。 */
    private Integer enabled;

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;
}
