/*
 * 职责：ngram 全文检索的查询结果行（Mapper 专用，**不是出参**）。
 * 调用方：infra/law/mapper/LawItemMapper（resultType）、LawSearchService（转成 LawHitVO）。
 * 数据来源：law_item 表 + MySQL `MATCH ... AGAINST` 计算出的相关度得分。
 * 注意：
 *   1. 这里带 `content` 全文，是为了让 Service 能截摘要（snippet）；**出参 LawHitVO 不含全文**。
 *   2. `score` 是 MySQL 的 TF-IDF 相对得分，**不是 0~1 的概率**，跨查询不可比较。
 *      接口契约里它的用途只有「排序 + 展示」，不要在前端做阈值判断。
 */
package com.faze.infra.law.mapper;

import lombok.Data;

/**
 * ngram 检索结果行。
 */
@Data
public class LawSearchRow {

    /** 法条主键。 */
    private Long id;

    /** 法律简称码。 */
    private String lawCode;

    /** 条号文本。 */
    private String articleNo;

    /** 条文原文（用于生成摘要）。 */
    private String content;

    /** MySQL MATCH 相关度得分。 */
    private Double score;
}
