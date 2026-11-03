/*
 * 职责：法条检索的出参对象（`GET /api/v1/pub/probe/law` 的 hits 元素）。
 * 调用方：infra/law/LawSearchService、infra/probe/ProbeController。
 * 数据来源：law_item + MySQL MATCH 得分。
 * 注意：字段集合按《接口规范》§7.1 冻结为 id / lawCode / articleNo / snippet / score。
 *       **不加 lawName、不加 content**：契约没这两项，加了就是擅自扩契约；全文不给客户端
 *       也避免「一次探针把 3000 条法条拉下来」的滥用面。
 */
package com.faze.infra.law.dto;

import java.util.List;
import lombok.Data;

/**
 * 法条命中项。
 */
@Data
public class LawHitVO {

    /** 法条主键（序列化为字符串）。 */
    private Long id;

    /** 法律简称码，如 civil_code。 */
    private String lawCode;

    /** 条号文本，如 第六百八十条。 */
    private String articleNo;

    /** 摘要：命中词前后截取的片段，形如 `…借款合同应当…`。 */
    private String snippet;

    /** MySQL MATCH 相关度得分（保留 6 位小数，仅用于排序与展示）。 */
    private Double score;

    /**
     * 命中依据：关键词按 ngram 切出、且在条文原文里实际出现的 token。
     *
     * <p>D3 第 2 条要求：只有 score 一个数字时，验收人看不出「凭什么命中」。</p>
     */
    private List<String> matchedTerms;
}
