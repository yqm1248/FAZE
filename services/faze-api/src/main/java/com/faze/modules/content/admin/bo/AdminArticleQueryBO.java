/*
 * 职责：PC A-14 文章列表查询条件（G9.3）。
 * 调用方：modules/content/admin/controller/AdminArticleController#list。
 * 数据来源：查询参数。
 * 注意：
 *   1. `lang` 筛选语义是「**已有该语言译文**」：运营想找「还没写维语版」的文章时传
 *      `lang=ug-Arab-CN&translated=false`（本 BO 只管语言，translated 由页面据返回行判断）。
 *   2. `status` 单值：draft / published / archived。
 *   3. 分页用 RuoYi 口径（pageNum / pageSize）。
 */
package com.faze.modules.content.admin.bo;

import lombok.Data;

/**
 * PC 文章列表查询条件。
 */
@Data
public class AdminArticleQueryBO {

    /** 分类（可空）。 */
    private String category;

    /** 状态：draft / published / archived（可空 = 全部）。 */
    private String status;

    /** 语言码（可空；给了就只返回该语言已有译文的文章）。 */
    private String lang;

    /** 标题关键词（可空，模糊匹配源标题与译标题）。 */
    private String keyword;

    /** 页码（默认 1）。 */
    private Integer pageNum = 1;

    /** 每页条数（默认 10）。 */
    private Integer pageSize = 10;
}
