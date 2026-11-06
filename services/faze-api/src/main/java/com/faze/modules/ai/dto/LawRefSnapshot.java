/*
 * 职责：`ai_message.refs` 里单条法条引用快照的 DTO（冻结快照，不回改）。
 * 调用方：modules/ai/service/RefSnapshotBuilder（构造）、C3（be-stream：Jackson 序列化后写 refs）、
 *         段 4 文书中心的法条引用块（按同一 schema 存，见第 3 段工作流 §八）。
 * 数据来源：B1 的 LawHit（content 已是 ≤200 字摘要）+ 必要时 ArticleNoValidator 回查的 law_item。
 * 注意：
 *   1. **字段名冻结**（段 4 与 A-28 都按这个读，任务书 D4 明确「字段名不许改」）：
 *      lawItemId / lawCode / lawName / articleNo / content / effectiveDate / status。
 *   2. `lawItemId` **必填**：A-27 的「AI 引用次数」靠它统计（§零.3 第 10 项）。
 *   3. `content` 是**快照摘要（≤200 字）**，不是全文；法条后来修订/停用，本快照不回改。
 *   4. `effectiveDate` 用 ISO 日期字符串 `yyyy-MM-dd`（不是时间戳），与冻结语义一致。
 */
package com.faze.modules.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 法条引用快照（`ai_message.refs` 数组元素）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LawRefSnapshot {

    /** 法条主键（必填；A-27「AI 引用次数」按它统计）。 */
    private Long lawItemId;

    /** 法律简称码，如 civil_code。 */
    private String lawCode;

    /** 法律全称，如 中华人民共和国民法典。 */
    private String lawName;

    /** 条号文本，如 第六百八十条。 */
    private String articleNo;

    /** 条文摘要（≤200 字，快照值，不随后续修订回改）。 */
    private String content;

    /** 生效日期（ISO `yyyy-MM-dd`，冻结值）。 */
    private String effectiveDate;

    /** 当时的状态（active / amended / repealed，冻结值）。 */
    private String status;
}
