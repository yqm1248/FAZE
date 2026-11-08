/*
 * 职责：选择送达方式的请求体（`PUT /app/case-deliveries/{id}/method`）。
 * 调用方：modules/caseflow/controller/AppDeliveryController → DeliveryService。
 * 数据来源：App 22-service 页的「选择送达方式」。
 * 注意：
 *   1. 只允许 platform / sms_pickup / mail 三值；非法值抛 407008（**不静默回退**）。
 *   2. 已签收（signed）的送达记录**不许再改方式**。
 *   3. 只改方式，不触发送达；真正发起送达是 `POST /app/case-deliveries/{id}/confirm`。
 */
package com.faze.modules.caseflow.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 送达方式选择入参。
 */
@Data
public class DeliveryMethodBO {

    /** 送达方式：platform / sms_pickup / mail。 */
    @NotBlank(message = "送达方式不能为空")
    @Size(max = 20, message = "送达方式取值过长")
    private String method;
}
