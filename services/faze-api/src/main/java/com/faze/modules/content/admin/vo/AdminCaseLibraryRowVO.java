/*
 * 职责：PC A-16 案例库列表行（设计稿 8 列）。
 * 调用方：modules/content/admin/controller/AdminCaseLibraryController#list。
 * 数据来源：case_library + case_library_translation（语言完成度）。
 * 注意：
 *   1. 8 列：案例编号 / 标题 + 当事人 / 分类 / 适用法条 / 语言 / 状态 / 更新时间 / 操作。
 *   2. `lawRefText` 是「民法典 第六百八十条、合同法 第九条」这样的可读串，
 *      由服务端拼好：前端拿 JSON 再拼一次会出现两套格式。
 *   3. `langCount` 是 `x/4` 的分子（中文 + 三种译文），缺译时页面用 warning 色。
 *      阈值口径：只有中文 = 1，三种译文各 +1。
 *   4. 列表**不返回法院案号**：它是内部核对字段，列表用不上，
 *      多返回一个 L3 字段就多一个泄露面。
 */
package com.faze.modules.content.admin.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * PC 案例库列表行。
 */
@Data
public class AdminCaseLibraryRowVO {

    /** 主键。 */
    private Long id;

    /** 平台案例编号 `CL...`。 */
    private String caseNo;

    /** 标题。 */
    private String title;

    /** 原告（脱敏）。 */
    private String plaintiffName;

    /** 被告（脱敏）。 */
    private String defendantName;

    /** 细分分类 code。 */
    private String category;

    /** 细分分类中文名（页面直接显示，不用前端再维护一份映射）。 */
    private String categoryLabel;

    /** 大类 code（由分类推导）。 */
    private String caseType;

    /** 大类中文名。 */
    private String caseTypeLabel;

    /** 适用法条可读串（「民法典 第六百八十条」）。 */
    private String lawRefText;

    /** 语言完成度分子（1~4）。 */
    private Integer langCount;

    /** 状态：draft / published / offline。 */
    private String status;

    /** 上线时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
