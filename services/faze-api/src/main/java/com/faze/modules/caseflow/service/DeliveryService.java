/*
 * 职责：送达（caseflow 域）的业务接口——发起送达、改造方式、签收、列表、回执。
 * 调用方：modules/caseflow/controller/AppDeliveryController、admin/controller/AdminCaseController。
 * 数据来源：case_delivery / document / legal_case。
 * 注意：
 *   1. **不碰案件状态**：送达不改 legal_case.status（工作流 A5 的服务边界表）。
 *   2. **只送平台自有文书**，不做法院转达（§零.4 第 3 条）；文案里不许出现「人民法院」「报送」「确认书」。
 *   3. 送达对象必须是已有 PDF 的 document，否则抛 407005。
 */
package com.faze.modules.caseflow.service;

import com.faze.modules.caseflow.bo.DeliveryCreateBO;
import com.faze.modules.caseflow.vo.DeliveryRowVO;
import java.util.List;

/**
 * 送达业务接口。
 */
public interface DeliveryService {

    /**
     * 发起送达（PC 管理员操作）。
     *
     * <p>副作用：生成 `SD` 回执号 → 写一条 `status='pending'` 的 case_delivery。
     * 不写 case_progress、不改案件状态。</p>
     *
     * @param caseId 案件 id
     * @param bo     文书 id + 送达方式
     * @return 新建的送达记录
     */
    DeliveryRowVO send(Long caseId, DeliveryCreateBO bo);

    /**
     * 取某案件的全部送达记录（PC A-19 送达 Tab）。
     *
     * @param caseId 案件 id
     * @return 送达记录列表（按发起时间倒序）
     */
    List<DeliveryRowVO> listByCase(Long caseId);

    /**
     * 取当前用户的送达列表（App 22-service）。
     *
     * @param userId   当前登录用户
     * @param caseId   案件 id（可空：空表示全部案件）
     * @param status   状态筛选（pending / signed；空或非法按全部）
     * @param pageNum  页码（从 1 起）
     * @param pageSize 每页条数（≤100）
     * @return 分页结果
     */
    com.faze.common.pojo.PageResult<DeliveryRowVO> listApp(
            Long userId, Long caseId, String status, int pageNum, int pageSize);

    /**
     * 取单条送达详情 / 回执（App）。
     *
     * @param userId     当前登录用户
     * @param deliveryId 送达记录 id
     * @return 送达记录（含回执字段）
     */
    DeliveryRowVO detailApp(Long userId, Long deliveryId);

    /**
     * 签收（App）。
     *
     * <p>幂等语义：第一次成功写入 `signed_at` / `sign_device` / `ip`；
     * 第二次抛 407007，**不覆盖首次签收时间**。</p>
     *
     * @param userId     当前登录用户
     * @param deliveryId 送达记录 id
     * @param signDevice 签收设备（型号 + OS）
     * @param ip         签收 IP
     */
    void confirm(Long userId, Long deliveryId, String signDevice, String ip);

    /**
     * 选择/修改送达方式（`PUT /app/case-deliveries/{id}/method`）。
     *
     * <p>前置：送达记录存在且属于当前用户；未签收。已签收抛 407007。</p>
     *
     * @param userId     当前登录用户 app_user.id
     * @param deliveryId 送达记录 id
     * @param methodCode 送达方式 code（platform / sms_pickup / mail）
     */
    void chooseMethod(Long userId, Long deliveryId, String methodCode);
}
