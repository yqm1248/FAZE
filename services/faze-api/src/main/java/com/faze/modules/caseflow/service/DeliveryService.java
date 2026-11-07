/*
 * 职责：送达（caseflow 域）的业务接口——选择方式、发起送达、签收、回执。
 * 调用方：modules/caseflow/controller/AppDeliveryController、PC A-19 的送达 Tab。
 * 数据来源：case_delivery / document / legal_case。
 * 注意：
 *   1. **不碰案件状态**：送达不改 legal_case.status（工作流 A5 的服务边界表）。
 *   2. **只送平台自有文书**，不做法院转达（§零.4 第 3 条）；文案里不许出现「人民法院」。
 *   3. 送达对象必须是已有 PDF 的 document，否则抛 407005。
 */
package com.faze.modules.caseflow.service;

/**
 * 送达业务接口。
 */
public interface DeliveryService {

    /**
     * 选择/修改送达方式（`PUT /app/case-deliveries/{id}/method`）。
     *
     * <p>前置：送达记录存在且属于当前用户；未签收。
     * 副作用：更新 case_delivery.method 与 updated_at。</p>
     *
     * @param userId     当前登录用户 app_user.id
     * @param deliveryId 送达记录 id
     * @param methodCode 送达方式 code（platform / sms_pickup / mail）
     */
    void chooseMethod(Long userId, Long deliveryId, String methodCode);
}
