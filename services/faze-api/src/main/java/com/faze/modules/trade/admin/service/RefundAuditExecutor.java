/*
 * 职责：PC 端退款审批执行器（G4.2）——审批通过与驳回各占一个方法，便于各标一个 @OperationLog。
 * 调用方：modules/trade/admin/controller/AdminRefundController#audit。
 * 数据来源：refund_record / orders（经 RefundService 取）、OrderRefundService（入账与状态迁移）、
 *          OrderStateService（驳回到 REFUND_REJECTED 之后的回退迁移）。
 * 注意：
 *   1. **为什么拆成 Executor 而不是一个 audit() 里写 if**：`@OperationLog` 的 action 是注解常量，
 *      通过/驳回必须是两个不同 action（ORDER_REFUND_APPROVED / ORDER_REFUND_REJECTED），
 *      一个方法只能标一个，所以按动作各占一个方法（与 AdminVerificationAuditExecutor 同构）。
 *   2. **审批人来自登录态**（`CurrentAdmin.id()`），不从请求体取。
 *   3. **驳回不是「只改退款单状态」**：订单要先 REFUNDING → REFUND_REJECTED（ADMIN），
 *      再由系统迁回退款前状态。少迁一步订单就卡死在 REFUND_REJECTED，用户既不能继续服务也不能再退款。
 *   4. 审计 detail 存 before/after（退款单状态 + 订单状态四元组），由切面写进 operation_log。
 */
package com.faze.modules.trade.admin.service;

import com.faze.common.audit.AuditContext;
import com.faze.common.audit.OperationLog;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.admin.vo.AdminRefundAuditResultVO;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.RefundRecord;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.service.OrderRefundService;
import com.faze.modules.trade.service.OrderStateService;
import com.faze.modules.trade.service.RefundService;
import com.faze.security.CurrentAdmin;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 退款审批执行器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundAuditExecutor {

    /** 审计模块名。 */
    private static final String MODULE = "trade";

    /** 审计目标类型。 */
    private static final String TARGET_REFUND = "REFUND_RECORD";

    private final RefundService refundService;
    private final OrderRefundService orderRefundService;
    private final OrderStateService orderStateService;
    private final JsonMapper jsonMapper;

    /**
     * 审批通过：入账 + 支付负行 + 订单迁移（全额 → REFUNDED；部分 → 退款前状态）。
     *
     * @param refundId 退款单主键
     * @param remark   审批意见（可空）
     * @return 审批结果
     * @throws BusinessException 405011 退款单不存在 / 405003 状态不允许
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "ORDER_REFUND_APPROVED", targetType = TARGET_REFUND)
    public AdminRefundAuditResultVO approve(Long refundId, String remark) {
        RefundRecord refund = refundService.requireRefund(refundId);
        Orders order = refundService.orderOf(refund);
        assertAuditable(refund, order);

        long refundAmount = refund.getAmountCents() == null ? 0L : refund.getAmountCents();
        long orderAmount = order.getAmountCents() == null ? 0L : order.getAmountCents();
        // 全额退 → 终态 REFUNDED；部分退 → 回退款前状态（部分退款不代表订单作废）
        OrderStatus target = refundAmount >= orderAmount
                ? OrderStatus.REFUNDED
                : refundService.resolveOrigin(order.getId());

        orderRefundService.settleApprovedRefund(order, refund, CurrentAdmin.id(), remark, target);
        markAudit("ORDER_REFUND_APPROVED", refundId, order.getId(),
                RefundService.STATUS_APPLYING, RefundService.STATUS_REFUNDED,
                order.getStatus(), target.getCode(), remark);
        log.info("退款审批通过 refundId={} orderId={} amountCents={} target={}",
                refundId, order.getId(), refundAmount, target);

        AdminRefundAuditResultVO vo = new AdminRefundAuditResultVO();
        vo.setRefundId(refundId);
        vo.setOrderId(order.getId());
        vo.setRefundStatus(RefundService.STATUS_REFUNDED);
        vo.setOrderStatus(target.getCode());
        vo.setAction("approve");
        return vo;
    }

    /**
     * 审批驳回：退款单置 rejected，订单 REFUNDING→REFUND_REJECTED→退款前状态。
     *
     * @param refundId 退款单主键
     * @param remark   驳回原因（必填）
     * @return 审批结果
     * @throws BusinessException 405011 退款单不存在 / 405003 状态不允许 / 401001 原因缺失
     */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(module = MODULE, action = "ORDER_REFUND_REJECTED", targetType = TARGET_REFUND)
    public AdminRefundAuditResultVO reject(Long refundId, String remark) {
        if (!StringUtils.hasText(remark)) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "驳回必须填写原因");
        }
        String reason = remark.trim();
        RefundRecord refund = refundService.requireRefund(refundId);
        Orders order = refundService.orderOf(refund);
        assertAuditable(refund, order);

        OrderStatus origin = refundService.resolveOrigin(order.getId());
        // 任务 1-2：退款单置 rejected + 订单 REFUNDING → REFUND_REJECTED（ADMIN）
        orderRefundService.rejectRefund(order, refund, CurrentAdmin.id(), reason);
        // 任务 3：REFUND_REJECTED → 退款前状态（状态机只允许 SYSTEM 触发）
        if (!orderStateService.transit(order.getId(), OrderStatus.REFUND_REJECTED, origin,
                OperatorRole.SYSTEM, null, "退款驳回，订单回退")) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        markAudit("ORDER_REFUND_REJECTED", refundId, order.getId(),
                RefundService.STATUS_APPLYING, RefundService.STATUS_REJECTED,
                order.getStatus(), origin.getCode(), reason);
        log.info("退款审批驳回 refundId={} orderId={} origin={}", refundId, order.getId(), origin);

        AdminRefundAuditResultVO vo = new AdminRefundAuditResultVO();
        vo.setRefundId(refundId);
        vo.setOrderId(order.getId());
        vo.setRefundStatus(RefundService.STATUS_REJECTED);
        vo.setOrderStatus(origin.getCode());
        vo.setAction("reject");
        return vo;
    }

    /**
     * 审批前置校验：退款单必须 applying、订单必须 REFUNDING。
     *
     * @param refund 退款单
     * @param order  订单
     * @throws BusinessException 405003 状态不允许
     */
    private void assertAuditable(RefundRecord refund, Orders order) {
        if (!RefundService.STATUS_APPLYING.equals(refund.getStatus())) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID,
                    "退款单已处理，当前状态：" + refund.getStatus());
        }
        if (!OrderStatus.REFUNDING.getCode().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID,
                    "订单不在退款中，当前状态：" + order.getStatus());
        }
    }

    /**
     * 把审批前后状态写进审计上下文（切面落库时读它）。
     *
     * @param action          动作码
     * @param refundId        退款单主键
     * @param orderId         订单主键
     * @param refundFrom      退款单变更前状态
     * @param refundTo        退款单变更后状态
     * @param orderFrom       订单变更前状态
     * @param orderTo         订单变更后状态
     * @param remark          审批意见
     */
    private void markAudit(String action, Long refundId, Long orderId,
                           String refundFrom, String refundTo,
                           String orderFrom, String orderTo, String remark) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("refundStatus", refundFrom);
        before.put("orderStatus", orderFrom);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("refundStatus", refundTo);
        after.put("orderStatus", orderTo);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("refundId", refundId);
        detail.put("orderId", orderId);
        detail.put("before", before);
        detail.put("after", after);
        detail.put("remark", remark);
        AuditContext.mark(MODULE, action, TARGET_REFUND, String.valueOf(refundId),
                jsonMapper.writeValueAsString(detail));
    }
}
