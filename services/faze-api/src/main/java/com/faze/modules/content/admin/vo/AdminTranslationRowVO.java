/*
 * 职责：PC A-17 译文队列的行对象。
 * 调用方：AdminTranslationController#list。
 * 数据来源：article_translation + article（中文源标题，用于让运营知道这条译文是哪篇的）。
 * 注意：
 *   1. `articleTitle` 是**中文源标题**，只做定位用，不参与检索（见 AdminTranslationQueryBO 第 3 条）。
 *   2. 列表**不带正文 content**：一篇译文的正文是 JSON 块数组，几十 KB，翻 100 条传不过来。
 *      正文在详情接口 `GET /admin/translations/{id}` 里给。
 *   3. `id` 是译文主键（**不是**文章主键）；`articleId` 单独给，前端点「回文章」时用。
 *   4. 时间字段是 UTC，前端按 `parseTime` 转本地。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 译文队列行。
 */
@Data
public class AdminTranslationRowVO {

    /** 译文主键。 */
    private Long id;

    /** 文章主键。 */
    private Long articleId;

    /** 文章中文源标题（定位用）。 */
    private String articleTitle;

    /** 语言：bo / ug-Arab-CN / kk-Arab-CN。 */
    private String lang;

    /** 译标题。 */
    private String title;

    /** 译摘要。 */
    private String summary;

    /** 译文状态：draft / reviewed / published。 */
    private String status;

    /** 人工译者。 */
    private String translator;

    /** 机翻引擎名。 */
    private String translationEngine;

    /** 译文是否已过期（源文在译文之后又改过）。 */
    private Boolean outdated;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
