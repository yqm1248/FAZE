/*
 * 职责：A-25 右栏申诉列表的查询条件。
 * 调用方：modules/ops 的 AdminAppealService / AdminAppealController。
 * 数据来源：HTTP 查询参数。
 * 注意：
 *   1. `status` 为空表示全查；A-25 右栏默认筛 pending。
 *   2. 列表按 `created_at DESC`（最新的申诉在最上面），与工单列表相反——
 *      申诉是「等处理」的队列，新申诉优先级天然更高。
 */
package com.faze.modules.ops.admin.bo;

import lombok.Data;

/**
 * 申诉查询条件。
 */
@Data
public class AppealQueryBO {

    /** 状态：pending / accepted / rejected；空则全查。 */
    private String status;

    /** 页码，从 1 开始。 */
    private Integer pageNum = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;
}
