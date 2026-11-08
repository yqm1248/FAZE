/*
 * 职责：常见问题行出参。
 * 调用方：modules/ops 的 FaqService / PubFaqController。
 * 数据来源：faze.faq。
 * 注意：
 *   `answer` 里可能有 `\n` 换行，前端按行渲染（APP 56 稿的折叠项内容是多行的）。
 */
package com.faze.modules.ops.vo;

import lombok.Data;

/**
 * 常见问题行。
 */
@Data
public class FaqVO {

    /** 主键。 */
    private Long id;

    /** 分类：account / pay / lawyer / case。 */
    private String category;

    /** 问题。 */
    private String question;

    /** 答案（可含换行）。 */
    private String answer;

    /** 排序。 */
    private Integer sortOrder;
}
