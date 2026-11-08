/*
 * 职责：PC 端退款审批入参（G4.2，`POST /admin/refunds/{id}/audit`）。
 * 调用方：modules/trade/admin/controller/AdminRefundController#audit。
 * 数据来源：请求体。
 * 注意：
 *   1. `approved` 必须有值（null 会让「通过/驳回」二选一变成第三种未定义行为）。
 *   2. `remark` 在**驳回时必填**（没有原因的驳回，客服无法回答用户）；通过时可选。
 *      校验分两层：BO 只限长度，业务层判「驳回必须给理由」。
 *   3. 审批人 id 一律从登录态取（`CurrentAdmin.id()`），**不从请求体取**，否则可伪造审批人。
 */
package com.faze.modules.trade.admin.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 退款审批入参。
 */
@Data
public class AdminRefundAuditBO {

    /** 是否通过：true 通过 / false 驳回。 */
    @NotNull(message = "approved 不能为空")
    private Boolean approved;

    /** 审批意见（驳回时必填，≤500 字）。 */
    @Size(max = 500, message = "审批意见最长 500 字")
    private String remark;
}
