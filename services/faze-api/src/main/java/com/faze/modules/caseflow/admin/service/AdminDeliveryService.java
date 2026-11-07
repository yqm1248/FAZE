/*
 * 职责：PC 端送达操作（A-19 抽屉的「送达」Tab）——发起送达。
 * 调用方：modules/caseflow/admin/controller/AdminCaseController。
 * 数据来源：case_delivery / legal_case / document（经 DeliveryService）。
 * 注意：
 *   1. 只有**一条写方法**（发起送达），必须标 `@OperationLog`，动作码 `DELIVERY_SEND`
 *      （工作流 C1 第 5 条）。查询不标（纯查询不进审计）。
 *   2. 送达的业务规则全在 `DeliveryService`，本类不重复实现，只负责审计标记。
 *   3. 本类**不建接口**：域内只有一个实现，按后端代码规范 §2.4 直接用类。
 */
package com.faze.modules.caseflow.admin.service;

import com.faze.common.audit.OperationLog;
import com.faze.modules.caseflow.bo.DeliveryCreateBO;
import com.faze.modules.caseflow.service.DeliveryService;
import com.faze.modules.caseflow.vo.DeliveryRowVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PC 送达管理业务。
 */
@Service
@Slf4j
public class AdminDeliveryService {

    private final DeliveryService deliveryService;

    /**
     * 构造器注入。
     *
     * @param deliveryService 送达业务（域内复用，不重复实现）
     */
    public AdminDeliveryService(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /**
     * 发起送达（管理员操作）。
     *
     * <p>审计 `detail` 由切面采集方法入参，其中只含文书 id / 方式 / 回执号，
     * 不含用户身份证、手机号等 L4 级字段（《审计与日志规范》§4.3）。</p>
     *
     * @param caseId 案件 id
     * @param bo     文书 id + 送达方式
     * @return 新建的送达记录
     */
    @OperationLog(module = "caseflow", action = "DELIVERY_SEND", targetType = "CASE_DELIVERY")
    public DeliveryRowVO send(Long caseId, DeliveryCreateBO bo) {
        DeliveryRowVO vo = deliveryService.send(caseId, bo);
        log.info("PC 发起送达 caseId={} deliveryId={} receiptNo={}",
                caseId, vo.getId(), vo.getReceiptNo());
        return vo;
    }
}
