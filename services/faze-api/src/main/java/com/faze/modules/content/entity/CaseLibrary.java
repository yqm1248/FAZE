/*
 * 职责：case_library 表的实体映射（公开案例库主表，第 5 段 G1/G2/G4）。
 * 调用方：content 的 CaseLibraryService（PC A-16 写）、CaseLibraryQueryService（App 读）。
 * 数据来源：V503__create_case_library.sql（21 字段）+ V510 补的 dispute_focus。
 * 注意：
 *   1. **两个编号别混**：`caseNo` 是平台案例编号 `CL...`（对外唯一标识，App 展示）；
 *      `courtCaseNo` 是法院案号，**只供后台内部核对，App 任何接口都不返回**
 *      （19-case-detail 的红线：不显示案号、不显示审理法院全名）。
 *   2. **大类由细分推导**：写库时只填 `category`，`caseType` 由
 *      `CaseCategory#bigType()` 算出，不许两处都填（表注释与工作流 G1 第 2 条）。
 *   3. `lawRefs` 是 JSON 数组字符串 `[{"lawName","articleNo","lawItemId"}]`；
 *      保存前必须过段 3 的 `ArticleNoValidator`，条号不存在不许保存。
 *   4. 五个正文块：`summary` / `disputeFocus` / `judgment` / `insight` + `lawRefs`
 *      （工作流 G1 第 6 条与 5-A7 的「四个正文块」指的是前四个文本块）。
 *   5. 无 MP 逻辑删除拦截器：查询必须自己带 `deleted_at IS NULL`。
 */
package com.faze.modules.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 公开案例库主表（简体中文为源语言）。
 */
@Data
@TableName("case_library")
public class CaseLibrary {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台案例编号 `CL...`（对外唯一标识）。 */
    private String caseNo;

    /** 法院案号（内部核对用，App 不展示）。 */
    private String courtCaseNo;

    /** 标题（源语言）。 */
    private String title;

    /** 原告（脱敏姓名，如「张*」；V510 补：A-16 列表要显示当事人）。 */
    private String plaintiffName;

    /** 被告（脱敏姓名；V510 补）。 */
    private String defendantName;

    /** 审理法院（内部用，App 不展示全名）。 */
    private String courtName;

    /** 大类：civil / administrative / criminal，由 category 推导。 */
    private String caseType;

    /** 细分：marriage / labor / contract / traffic / criminal / policy。 */
    private String category;

    /** 裁判日期。 */
    private LocalDate judgmentDate;

    /** 关键词标签（逗号分隔）。 */
    private String keywords;

    /** 案情简介。 */
    private String summary;

    /** 争议焦点（V510 补：详情页四个正文块之一）。 */
    private String disputeFocus;

    /** 裁判结果。 */
    private String judgment;

    /** 案例启示。 */
    private String insight;

    /** 关联法条 JSON：`[{"lawName","articleNo","lawItemId"}]`。 */
    private String lawRefs;

    /** 案例来源（脱敏后的出处说明）。 */
    private String source;

    /** 浏览量。 */
    private Integer viewCount;

    /** 状态：draft / published / offline。 */
    private String status;

    /** 上线时间（UTC）。 */
    private LocalDateTime publishedAt;

    /** 源内容最后更新时间（UTC）；译文更早于它就提示过期。 */
    private LocalDateTime sourceUpdatedAt;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;

    /** 软删时间（UTC）；已上线案例不能直删，须先下架。 */
    private LocalDateTime deletedAt;
}
