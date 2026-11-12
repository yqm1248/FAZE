/*
 * 职责：PC A-17 术语行的保存入参。
 * 调用方：AdminTranslationController#updateGlossary → AdminTranslationService#updateGlossary。
 * 数据来源：请求体。
 * 注意：
 *   1. **不允许改 termKey / sourceText / lang**：termKey 是 AI 提示词与文书模板的引用键，
 *      改了等于让下游引用悬空；sourceText 是术语的中文原文，它不是"可编辑字段"而是"定义"。
 *      要改术语本身，走数据迁移，不要在管理页顺手改。
 *   2. `targetText` 传空串表示「清空译文、退回待填」，是合法操作。
 *   3. `status` 只允许 confirmed / pending。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PC 术语行保存入参。
 */
@Data
public class AdminGlossarySaveBO {

    /** 译文（最长 200 字；空串表示清空）。 */
    @Size(max = 200, message = "术语译文最长 200 字")
    private String targetText;

    /** 备注（最长 500 字）。 */
    @Size(max = 500, message = "备注最长 500 字")
    private String note;

    /** 确认状态：confirmed / pending。 */
    private String status;

    /** 是否启用（null 表示不改）。 */
    private Boolean enabled;
}
