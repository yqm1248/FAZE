/*
 * 职责：送达业务骨架实现（A5 阶段只保证接口可访问，C 组替换为真实实现）。
 * 调用方：modules/caseflow/controller/AppDeliveryController。
 * 数据来源：暂无（骨架不做任何写操作）。
 * 注意：
 *   1. **骨架必须显式拒绝写操作**，不能「假装成功」：送达方式改成功但库里没改，
 *      会让 A-19 的送达 Tab 显示错误数据。因此这里抛 407006（送达记录不存在）的语义
 *      留给 C 组实现，骨架先抛不支持的通用错误。
 *   2. C 组实现时必须：归属条件进 SQL + 已签收拒绝 + 非法方式抛 407008。
 */
package com.faze.modules.caseflow.service;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 送达业务骨架实现。
 */
@Service
public class DeliveryServiceImpl implements DeliveryService {

    @Override
    public void chooseMethod(Long userId, Long deliveryId, String methodCode) {
        // C 组实现：case_delivery 按 id + user_id 归属查询 → 已签收拒绝 → 改 method
        throw new BusinessException(ErrorCode.CASE_DELIVERY_NOT_FOUND, "送达模块待 C 组实现");
    }
}
