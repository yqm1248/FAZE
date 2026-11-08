/*
 * 职责：PC A-15 单语言译文出参。
 * 调用方：AdminArticleDetailVO.translations。
 * 数据来源：faze.article_translation。
 * 注意：
 *   1. `status` 是译文质量阶段（draft / reviewed / published），**不是**文章可见性；
 *      编辑页把它显示成「译文状态」并说明「上架看文章状态」。
 *   2. `translatedFrom` 记录翻译时的源文更新时间：与 `article.source_updated_at` 不一致
 *      说明译文可能过期（段 7 做提醒，本段只展示）。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 文章译文。
 */
@Data
public class AdminArticleTranslationVO {

    /** 语言。 */
    private String lang;

    /** 译标题。 */
    private String title;

    /** 译摘要。 */
    private String summary;

    /** 译正文（JSON 块数组字符串）。 */
    private String content;

    /** 译文质量阶段。 */
    private String status;

    /** 译者。 */
    private String translator;

    /** 机翻引擎名。 */
    private String translationEngine;

    /** 翻译时的源文更新时间（UTC）。 */
    private LocalDateTime translatedFrom;
}
