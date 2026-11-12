/*
 * 职责：PC A-17 译文队列的查询条件。
 * 调用方：AdminTranslationController#list → AdminTranslationService#listTranslations。
 * 数据来源：查询参数（RuoYi 口径 pageNum / pageSize）。
 * 注意：
 *   1. `lang` 只允许三种非中文（bo / ug-Arab-CN / kk-Arab-CN）——zh-Hans 是源语言，
 *      不在 article_translation 里，传 zh-Hans 视为参数非法（不是"查不到"，是"查错表"）。
 *   2. `status` 单值：draft（待译/草稿）/ reviewed（机翻已校对）/ published（已发布）。
 *   3. `keyword` 同时模糊匹配**译文标题**与本表留痕字段，不匹配文章中文标题——
 *      中文标题检索属 A-14 文章管理页的事，两页职责不重叠。
 */
package com.faze.modules.content.admin.bo;

import lombok.Data;

/**
 * PC 译文队列查询条件。
 */
@Data
public class AdminTranslationQueryBO {

    /** 语言：bo / ug-Arab-CN / kk-Arab-CN（可空 = 全部）。 */
    private String lang;

    /** 译文状态：draft / reviewed / published（可空 = 全部）。 */
    private String status;

    /** 译标题关键词（可空，模糊匹配）。 */
    private String keyword;

    /** 页码（默认 1）。 */
    private Integer pageNum = 1;

    /** 每页条数（默认 10）。 */
    private Integer pageSize = 10;
}
