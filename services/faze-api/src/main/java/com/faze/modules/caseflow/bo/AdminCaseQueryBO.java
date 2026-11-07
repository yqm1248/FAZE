/*
 * 职责：PC 案件列表查询入参（`GET /admin/cases`）。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：PC A-19 查询栏（案件号/原告/被告、案件类型下拉、承办律师）。
 * 注意：
 *   1. PC 管理端分页场景是 `rows`/`total`（接口规范 §6），不是 C 端的 `list`。
 *   2. `type` 用案件 5 类 code（civil/criminal/administrative/labor/family）。
 *   3. `kw` 同时匹配案件号、原告名、被告名（原告/被告在 form_snapshot 里，用 JSON 提取）。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * PC 案件列表查询入参。
 */
@Data
public class AdminCaseQueryBO {

    /** 状态筛选（filed/accepted/in_progress/closed/rejected）；空为全部。 */
    private String status;

    /** 案件类型筛选（5 类 code）。 */
    private String type;

    /** 承办律师 id。 */
    private Long lawyerId;

    /** 关键词：案件号 / 原告 / 被告。 */
    private String kw;

    /** 页码。 */
    @Min(value = 1, message = "页码从 1 开始")
    private int pageNum = 1;

    /** 每页条数（≤100）。 */
    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最多 100")
    private int pageSize = 20;
}
