/*
 * 职责：发起送达的入参（`POST /admin/cases/{id}/deliveries`）。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController → AdminDeliveryService。
 * 数据来源：PC A-19 抽屉「送达」Tab 的「发起送达」弹窗（设计稿没画，本段自定）。
 * 注意：
 *   1. `method` 只允许 platform / sms_pickup / mail；非法值由 `DeliveryMethod.of` 抛 407008
 *      （**不静默回退**到 platform）。
 *   2. 送达动作本身不做任何远程调用（不接短信网关、不接物流），见工作流 C1 第 3 条。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起送达入参。
 */
@Data
public class DeliveryCreateBO {

    /** 要送达的文书 id（该文书必须是 generated/signed 且有 PDF）。 */
    @NotNull(message = "文书 id 不能为空")
    private Long documentId;

    /** 送达方式：platform / sms_pickup / mail。 */
    @NotBlank(message = "送达方式不能为空")
    @Size(max = 20, message = "送达方式取值过长")
    private String method;
}
