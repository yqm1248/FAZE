/*
 * 职责：PC A-15 单种语言的译文入参（G9.4）。
 * 调用方：AdminArticleSaveBO.translations。
 * 数据来源：请求体。
 * 注意：
 *   1. `lang` 只允许 bo / ug-Arab-CN / kk-Arab-CN（zh-Hans 是源语言，不在译文里）。
 *   2. `title` / `content` 可空（草稿阶段允许只录一部分）；但**上架时会校验三种语言都齐全**。
 *   3. `translator` / `translationEngine` 留痕字段：人工翻译填译者，机翻填引擎名
 *      （配合 AI 内容标识要求），两者可同时存在。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PC 文章译文入参。
 */
@Data
public class AdminArticleTranslationBO {

    /** 语言：bo / ug-Arab-CN / kk-Arab-CN。 */
    private String lang;

    /** 译标题。 */
    @Size(max = 200, message = "译标题最长 200 字")
    private String title;

    /** 译摘要。 */
    @Size(max = 500, message = "译摘要最长 500 字")
    private String summary;

    /** 译正文（JSON 块数组字符串，可空）。 */
    private String content;

    /** 译文质量阶段：draft / reviewed / published。 */
    private String status;

    /** 译者。 */
    @Size(max = 100, message = "译者最长 100 字")
    private String translator;

    /** 机翻引擎名。 */
    @Size(max = 50, message = "引擎名最长 50 字")
    private String translationEngine;
}
