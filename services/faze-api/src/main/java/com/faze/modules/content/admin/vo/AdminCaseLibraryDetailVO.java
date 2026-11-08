/*
 * 职责：PC A-16 案例详情（录入抽屉回显）。
 * 调用方：modules/content/admin/controller/AdminCaseLibraryController#detail。
 * 数据来源：case_library + case_library_translation。
 * 注意：
 *   1. 这是**内部接口**，所以返回 `courtCaseNo` / `courtName`（App 接口不返回）。
 *   2. `translations` 只含三种译文；`zh-Hans` 的正文在顶层字段上，
 *      前端切到中文 Tab 时读顶层、切到其它语言读数组里对应项。
 *   3. `status` 与 `langCount` 一并返回，抽屉顶部要显示「已上线 / 待审核」与「语言 2/4」。
 */
package com.faze.modules.content.admin.vo;

import com.faze.modules.content.admin.bo.AdminCaseTranslationBO;
import com.faze.modules.content.admin.bo.AdminLawRefBO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * PC 案例详情。
 */
@Data
public class AdminCaseLibraryDetailVO {

    /** 主键。 */
    private Long id;

    /** 平台案例编号。 */
    private String caseNo;

    /** 法院案号（内部用）。 */
    private String courtCaseNo;

    /** 法院名（内部用）。 */
    private String courtName;

    /** 标题。 */
    private String title;

    /** 原告（脱敏）。 */
    private String plaintiffName;

    /** 被告（脱敏）。 */
    private String defendantName;

    /** 细分分类 code。 */
    private String category;

    /** 大类 code。 */
    private String caseType;

    /** 关键词。 */
    private String keywords;

    /** 来源。 */
    private String source;

    /** 裁判日期。 */
    private LocalDate judgmentDate;

    /** 案情简介。 */
    private String summary;

    /** 争议焦点。 */
    private String disputeFocus;

    /** 裁判结果。 */
    private String judgment;

    /** 案例启示。 */
    private String insight;

    /** 法条引用。 */
    private List<AdminLawRefBO> lawRefs;

    /** 三种译文。 */
    private List<AdminCaseTranslationBO> translations;

    /** 语言完成度分子（1~4）。 */
    private Integer langCount;

    /** 状态。 */
    private String status;

    /** 上线时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 源内容更新时间（UTC）。 */
    private LocalDateTime sourceUpdatedAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
