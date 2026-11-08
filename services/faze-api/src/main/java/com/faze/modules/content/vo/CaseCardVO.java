/*
 * 职责：App 案例列表条目（04-case 的分组内一行）。
 * 调用方：CaseLibraryQueryService#listByType。
 * 数据来源：case_library（只取 published）。
 * 注意：
 *   1. **设计稿的列表条目只有标题一个字段**（工作流 G2 第 1 条明确没有来源/日期/阅读量/缩略图），
 *      所以这里只给 `id` + `title` + 分组用的 `caseType`。多给字段前端就会忍不住渲染出来。
 *   2. `caseNo` 也不给：平台编号是后台对账用的，App 列表不需要（详情页也不显示案号）。
 */
package com.faze.modules.content.vo;

import lombok.Data;

/**
 * App 案例列表条目。
 */
@Data
public class CaseCardVO {

    /** 案例主键（跳详情用）。 */
    private Long id;

    /** 标题。 */
    private String title;

    /** 大类 code（页面分组用）。 */
    private String caseType;
}
