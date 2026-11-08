/*
 * 职责：我的案件列表的筛选与分页入参（`GET /app/cases`）。
 * 调用方：modules/caseflow/controller/AppCaseController → CaseService.list。
 * 数据来源：App 53-my-cases 页的 4 个筛选 chips。
 * 注意：
 *   1. 4 个 chip 的值：all / pending（待我处理）/ in_progress（进行中）/ closed（已结案）。
 *      `pending` 是**聚合语义**（filed + rejected + accepted），不是单个状态值，由 Service 展开。
 *   2. pageSize 上限 100（§6），服务端 clamp。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 案件列表查询入参。
 */
@Data
public class CaseQueryBO {

    /** 筛选 chip：all / pending / in_progress / closed；默认 all。 */
    private String filter;

    /** 页码（从 1 起）。 */
    @Min(value = 1, message = "页码从 1 开始")
    private int pageNum = 1;

    /** 每页条数（≤100）。 */
    @Min(value = 1, message = "每页条数至少 1")
    @Max(value = 100, message = "每页条数最多 100")
    private int pageSize = 20;
}
