/*
 * 职责：A-27 法条库列表行。
 * 调用方：modules/ai/admin/controller/AdminLawItemController#list（RuoYi TableDataInfo 的 rows）。
 * 数据来源：LawItemAdminService#list → faze.law_item + B4 LawRefStatsService（AI 引用次数）。
 * 注意：
 *   1. **列表不返回条文全文**：`contentSummary` 是压平空白后 ≤200 字的摘要（设计稿列名「条文摘要」），
 *      全文只在详情里给。原因：`law_item.content` 是 MEDIUMTEXT，一页 10 条整段回传浪费带宽，
 *      而列表根本展示不了全文。
 *   2. `aiRefCount` 的字段名已与 PC 前端约定（2026-11-07）；口径见 B4：
 *      历史 assistant 消息 `refs` 快照里引用该条文的次数，**含已软删会话**，未被引用=0。
 *   3. `id` 是 Long，序列化为字符串（JacksonConfig 全局规则）。
 *   4. 含已失效行（`isEnabled=0`）：默认列表不查它们，但带 `isEnabled=0/all` 筛选时会返回，
 *      所以本 VO 必须带 `isEnabled` 字段让页面标「已失效」。
 */
package com.faze.modules.ai.admin.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A-27 法条库列表行。
 */
@Data
public class AdminLawItemRowVO {

    /** 法条主键（序列化为字符串）。 */
    private Long id;

    /** 法律简称码，如 civil_code。 */
    private String lawCode;

    /** 法律全称。 */
    private String lawName;

    /** 条号文本，如 第六百七十九条。 */
    private String articleNo;

    /** 条号数字序号（排序用；历史数据可能为 null）。 */
    private Integer articleSeq;

    /** 条文摘要（压平空白后 ≤200 字，超出加「…」）。 */
    private String contentSummary;

    /** 生效日期（该版本）。 */
    private LocalDate effectiveDate;

    /** 失效日期；null = 现行有效。 */
    private LocalDate expireDate;

    /** 状态：active / amended / repealed。 */
    private String status;

    /** 是否启用：1 启用 / 0 已失效。 */
    private Integer isEnabled;

    /** 检索用关键词（逗号分隔）。 */
    private String keywords;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** AI 引用次数（B4 口径；未被引用=0）。 */
    private Long aiRefCount;
}
