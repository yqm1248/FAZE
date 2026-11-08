/*
 * 职责：case_library_translation 表的实体映射（案例译文，第 5 段 G1/G4）。
 * 调用方：content 的 CaseLibraryService（PC A-16 四语言 Tab 保存）、CaseLibraryQueryService（Ag 读译文）。
 * 数据来源：V504__create_case_library_translation.sql + V510 补的 dispute_focus。
 * 注意：
 *   1. 与主表是 1:N：每条案例每种语言至多一条（`uk_case_trans(case_id, lang)`）。
 *   2. **源语言是主表**：`zh-Hans` 的正文在 `case_library` 上，不写本表；
 *      本表只存 `bo` / `ug-Arab-CN` / `kk-Arab-CN` 三种译文。
 *   3. `translatedFrom` 早于主表 `source_updated_at` 即视为「译文过期」，PC 列表要提示。
 *   4. `status`：draft / reviewed / published；只有 `zh-Hans` 齐全即可上线，
 *      译文允许 `pending`（G4 门禁口径）。
 */
package com.faze.modules.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 案例译文。
 */
@Data
@TableName("case_library_translation")
public class CaseLibraryTranslation {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 案例主键。 */
    private Long caseId;

    /** 语言：bo / ug-Arab-CN / kk-Arab-CN。 */
    private String lang;

    /** 译标题。 */
    private String title;

    /** 译案情简介。 */
    private String summary;

    /** 译争议焦点（V510 补：四个正文块要一起切语言）。 */
    private String disputeFocus;

    /** 译裁判结果。 */
    private String judgment;

    /** 译案例启示。 */
    private String insight;

    /** 状态：draft / reviewed / published / pending。 */
    private String status;

    /** 译者（人工校对时填）。 */
    private String translator;

    /** 翻译引擎标识（机翻时填，便于区分人工/机翻）。 */
    private String translationEngine;

    /** 译文对应的源内容时间点（UTC）；早于主表 source_updated_at 即过期。 */
    private LocalDateTime translatedFrom;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 更新时间（UTC）。 */
    private LocalDateTime updatedAt;
}
