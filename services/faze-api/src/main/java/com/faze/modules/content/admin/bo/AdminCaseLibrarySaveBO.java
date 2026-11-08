/*
 * 职责：PC A-16 案例录入 / 保存入参（新建与编辑共用）。
 * 调用方：modules/content/admin/controller/AdminCaseLibraryController。
 * 数据来源：A-16 录入抽屉三个 Tab（基本信息 / 正文 / 法条引用）+ 四个语言 Tab。
 * 注意：
 *   1. **`caseType` 不许传**：大类由 `category` 推导（工作流 G1 第 2 条），
 *      让运营两处都填必然出现「合同纠纷 + 刑事」这种自相矛盾的数据。
 *   2. 四个正文块按工作流 G1 第 6 条：summary / disputeFocus / judgment / insight。
 *      `disputeFocus` 是 V510 补的列（技术路线 DDL 漏了它）。
 *   3. `lawRefs` 保存前逐条回查法条库，条号不存在直接拒（段 3 的 `ArticleNoValidator`）。
 *   4. `status` 可空：新建默认 draft；上线走独立接口（publish），不在保存里顺手改状态——
 *      否则「保存草稿」会把已上线的案例意外下架。
 */
package com.faze.modules.content.admin.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * 案例保存入参。
 */
@Data
public class AdminCaseLibrarySaveBO {

    /** 标题（源语言，必填）。 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    /** 细分分类 6 选 1（必填；大类由它推导）。 */
    @NotBlank(message = "分类不能为空")
    @Pattern(regexp = "^(marriage|labor|contract|traffic|criminal|policy)$",
            message = "分类只能是 marriage/labor/contract/traffic/criminal/policy")
    private String category;

    /** 原告（脱敏姓名）。 */
    @Size(max = 50, message = "原告姓名不能超过 50 字")
    private String plaintiffName;

    /** 被告（脱敏姓名）。 */
    @Size(max = 50, message = "被告姓名不能超过 50 字")
    private String defendantName;

    /** 关键词标签（逗号分隔）。 */
    @Size(max = 500, message = "关键词不能超过 500 字")
    private String keywords;

    /** 案例来源（脱敏后的出处说明）。 */
    @Size(max = 100, message = "来源不能超过 100 字")
    private String source;

    /** 法院案号（内部用，不对外）。 */
    @Size(max = 64, message = "案号不能超过 64 字")
    private String courtCaseNo;

    /** 审理法院（内部用，App 不展示全名）。 */
    @Size(max = 100, message = "法院名不能超过 100 字")
    private String courtName;

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
    @Valid
    private List<AdminLawRefBO> lawRefs;

    /** 译文（三种，可空）。 */
    @Valid
    private List<AdminCaseTranslationBO> translations;
}
