/*
 * 职责：审核类写接口的统一出参（`{id, status}`）。
 * 调用方：modules/account/admin/controller/AdminVerificationController、AdminLawyerApplicationController。
 * 数据来源：本次审核后的记录状态。
 * 注意：
 *   1. `status` 是**大写枚举**（APPROVED / REJECTED），与列表口径一致。
 *   2. `id` 是认证记录 / 申请记录的主键，便于前端按行刷新。
 */
package com.faze.modules.account.admin.vo;

import lombok.Data;

/**
 * 审核结果。
 */
@Data
public class AdminAuditResultVO {

    /** 被审核记录主键。 */
    private Long id;

    /** 审核后的状态：APPROVED / REJECTED。 */
    private String status;
}
