/*
 * 职责：法条四路径检索（B1）与 B2 分页列表的查询结果行（Mapper 专用，**不是出参**）。
 * 调用方：com.faze.infra.law.mapper.LawItemMapper（resultType）、
 *         com.faze.modules.ai.service.LawSearchService（转 LawHit / LawItemBrief）。
 * 数据来源：faze.law_item。
 * 注意：
 *   1. **不要与 `infra/law/mapper/LawSearchRow` 合并**：那个是段 0 探针的行（id/lawCode/articleNo/
 *      content/score，只服务 `searchByNgram`，本段不许改）；本类多带 lawName/articleSeq/status/
 *      effectiveDate/expireDate，服务段 3 的四路径与 refs 快照字段，字段集合不同、各管一条链路。
 *   2. 本行不包含 `score`：全文得分来自 `searchByNgram` 返回的 `LawSearchRow`，
 *      条号精确的 1.0 与 LIKE/keywords 的固定分由 Service 赋，避免每个查询都写一份得分口径。
 *   3. `is_enabled` 不进结果：SQL 已按 `is_enabled = 1` 过滤，出参不需要该位。
 */
package com.faze.infra.law.mapper;

import java.time.LocalDate;
import lombok.Data;

/**
 * 法条检索结果行（段 3 B1/B2）。
 */
@Data
public class LawItemSearchRow {

    /** 法条主键。 */
    private Long id;

    /** 法律简称码。 */
    private String lawCode;

    /** 法律全称。 */
    private String lawName;

    /** 条号文本。 */
    private String articleNo;

    /** 条号数字序号。 */
    private Integer articleSeq;

    /** 条文原文（Service 按 200/300 字截断）。 */
    private String content;

    /** 生效日期。 */
    private LocalDate effectiveDate;

    /** 失效日期；null 表示现行有效。 */
    private LocalDate expireDate;

    /** 条文状态：active / amended / repealed。 */
    private String status;
}
