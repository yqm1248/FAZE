/*
 * 职责：订单实体 → VO 的转换（唯一转换点）。
 * 调用方：modules/trade/service/impl/OrderServiceImpl（列表与详情）、后续 G9 的 PC 订单查询。
 * 数据来源：faze.orders 行 + 已批量查好的 lawyer 行（本类**不查库**）。
 * 注意：
 *   1. **不查库**：律师信息由调用方用 `LawyerQueryService#mapByIds` 批量取好再传进来。
 *      在 Converter 里逐行查律师会变成 N+1（列表 20 条 = 21 次查询）。
 *   2. 律师已清退 / 查不到时律师字段留空，**不抛异常**：订单是历史事实，
 *      律师被清退不能让用户打不开自己的订单。
 *   3. 不给中文 label（statusLabel 之类）：状态与类型文案由前端走 i18n，
 *      服务端塞中文等于把 UI 文案硬编码，RTL 语言没法翻。
 *   4. `payExpireAt` 只对 PENDING 有值，时长取 `OrderTimeoutPolicy`（唯一出处）。
 */
package com.faze.modules.trade.converter;

import com.faze.modules.account.entity.Lawyer;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.rule.OrderTimeoutPolicy;
import com.faze.modules.trade.vo.OrderDetailVO;
import com.faze.modules.trade.vo.OrderVO;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 订单转换器。
 */
@Component
public class OrderConverter {

    /**
     * 订单行 → 列表 VO。
     *
     * @param order  订单实体
     * @param lawyer 承办律师；可为 null（已清退或查不到）
     * @return 列表行 VO
     */
    public OrderVO toVO(Orders order, Lawyer lawyer) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setServiceType(order.getServiceType());
        vo.setStatus(order.getStatus());
        vo.setAmountCents(order.getAmountCents());
        vo.setLawyerId(order.getLawyerId());
        vo.setRemark(order.getRemark());
        vo.setCreatedAt(order.getCreatedAt());
        if (lawyer != null) {
            vo.setLawyerName(lawyer.getRealName());
            vo.setLawyerAvatarUrl(lawyer.getAvatarUrl());
            vo.setLawyerFirm(lawyer.getLawFirm());
        }
        if (OrderStatus.PENDING.getCode().equals(order.getStatus())) {
            vo.setPayExpireAt(OrderTimeoutPolicy.payExpireAt(order.getCreatedAt()));
        }
        return vo;
    }

    /**
     * 订单行 → 详情 VO。
     *
     * @param order    订单实体
     * @param lawyer   承办律师；可为 null
     * @param actions  已推导好的可执行动作（见 OrderActionResolver）
     * @param reviewed 本单是否已评价（G5.1；已评价时动作里不会有 review）
     * @return 详情 VO
     */
    public OrderDetailVO toDetailVO(Orders order, Lawyer lawyer, List<String> actions, boolean reviewed) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setServiceType(order.getServiceType());
        vo.setStatus(order.getStatus());
        vo.setAmountCents(order.getAmountCents());
        vo.setRemark(order.getRemark());
        vo.setLawyerId(order.getLawyerId());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setAcceptedAt(order.getAcceptedAt());
        vo.setFinishedAt(order.getFinishedAt());
        vo.setCancelledAt(order.getCancelledAt());
        vo.setActions(actions == null ? List.of() : actions);
        vo.setReviewed(reviewed);
        if (lawyer != null) {
            vo.setLawyerName(lawyer.getRealName());
            vo.setLawyerAvatarUrl(lawyer.getAvatarUrl());
            vo.setLawyerRegion(lawyer.getRegion());
            vo.setLawyerFirm(lawyer.getLawFirm());
        }
        if (OrderStatus.PENDING.getCode().equals(order.getStatus())) {
            vo.setPayExpireAt(OrderTimeoutPolicy.payExpireAt(order.getCreatedAt()));
        }
        return vo;
    }
}
