/*
 * 职责：A-05 律师入驻队列的查询条件。
 * 调用方：modules/account/admin/controller/AdminLawyerApplicationController。
 * 数据来源：URL query 参数。
 * 注意：
 *   1. `status` 为空时**只查 pending**（契约 A-05：draft/pending 都不进待审队列的说法只针对
 *      draft；本段的默认口径是「只看待审」）。传 approved / rejected 时查对应状态。
 *   2. 查询参数里的 `applyType` 本段**没有对应字段**，一律忽略（见 Controller 注释）。
 */
package com.faze.modules.account.admin.bo;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 律师入驻队列查询条件。
 */
@Data
public class AdminLawyerApplicationQueryBO {

    /** 状态：PENDING / APPROVED / REJECTED；空=只看 PENDING。 */
    private String status;

    /** 页码。 */
    private Integer pageNum;

    /** 每页条数。 */
    private Integer pageSize;

    /** 提交时间下界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 提交时间上界（含）。 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
