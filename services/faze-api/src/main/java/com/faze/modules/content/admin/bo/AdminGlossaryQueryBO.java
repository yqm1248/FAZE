/*
 * 职责：PC A-17 术语表的查询条件。
 * 调用方：AdminTranslationController#glossary → AdminTranslationService#listGlossary。
 * 数据来源：查询参数。
 * 注意：
 *   1. 术语表**只有 zh-Hans 源术语**（51 条，`target_text` 全为空），
 *      译文由母语法律译者补（技术路线 §13.8：**不许机翻**）。所以本页的语言筛选
 *      实际只有 zh-Hans 一种有数据，保留 `lang` 参数是为了译文进来后不用改接口。
 *   2. `pendingOnly=true` 只返回 `target_text` 为空的条目——这是运营最常用的视图
 *      （「还有哪些术语没译文」）。
 */
package com.faze.modules.content.admin.bo;

import lombok.Data;

/**
 * PC 术语表查询条件。
 */
@Data
public class AdminGlossaryQueryBO {

    /** 语言（可空 = 全部）。 */
    private String lang;

    /** 术语键或中文源术语关键词（可空，模糊匹配）。 */
    private String keyword;

    /** 只看还没译文的条目（target_text 为空）。 */
    private Boolean pendingOnly;

    /** 页码（默认 1）。 */
    private Integer pageNum = 1;

    /** 每页条数（默认 20；术语表总共 51 条，一屏能看全）。 */
    private Integer pageSize = 20;
}
