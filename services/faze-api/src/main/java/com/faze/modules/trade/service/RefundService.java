/*
 * 职责：退款申请与查询（G4.1 / G4.3）——用户发起退款、C 端查退款进度、PC 按订单查退款单。
 * 调用方：modules/trade/controller/OrderController（申请与查询）、
 *        modules/trade/admin/service/RefundAuditExecutor（审批时取退款单与订单）。
 * 数据来源：orders（读）、refund_record（读写）、order_status_log（反查退款前状态）、
 *          OrderStateService（状态迁移唯一入口）。
 * 注意：
 *   1. **允许发起退款的状态只有 PAID / IN_SERVICE**：COMPLETED 之后的争议要走段 5 的申诉/裁定，
 *      段 2 没有那条流程，放开就是「已完成也能随时退钱」的口子。
 *   2. **同一订单同时只允许一张进行中的退款单**（status = applying）。用应用层查询保证：
 *      `refund_record` 上没有可用的部分唯一索引（MySQL 不支持条件唯一索引）。
 *   3. **部分退款的金额限制**：amountCents 为空 = 全额；传了必须 0 < amount ≤ 订单实付 → 否则 405005。
 *      注意上限是「订单实付金额」，不是「减去已退金额」——段 2 的退款都是一次结清，
 *      同一订单不会有第二张进行中的单；若将来支持多次部分退，这里要改成累计校验。
 *   4. **退款前状态只能从 order_status_log 反查**：`refund_record` 没有 original_status 字段，
 *      补字段要改 V200 迁移（已执行），所以用「最近一条 to_status=refunding 的 from_status」。
 *      反查不到时兜底 PAID（最保守：钱已付、活没开始）。
 *   5. 本类只做「申请 + 查询」，不做审批（审批要标 `@OperationLog`，放在 RefundAuditExecutor）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.bo.RefundApplyBO;
import com.faze.modules.trade.entity.OrderStatusLog;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.RefundRecord;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.OrderStatusLogMapper;
import com.faze.modules.trade.mapper.OrdersMapper;
import com.faze.modules.trade.mapper.RefundRecordMapper;
import com.faze.modules.trade.vo.RefundVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 退款申请与查询服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    /** 退款单状态：待审批。 */
    public static final String STATUS_APPLYING = "applying";

    /** 退款单状态：已到账。 */
    public static final String STATUS_REFUNDED = "refunded";

    /** 退款单状态：已驳回。 */
    public static final String STATUS_REJECTED = "rejected";

    /** 允许用户发起退款申请的订单状态。 */
    private static final Set<OrderStatus> APPLICABLE =
            EnumSet.of(OrderStatus.PAID, OrderStatus.IN_SERVICE);

    private final OrdersMapper ordersMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final OrderStatusLogMapper orderStatusLogMapper;
    private final OrderStateService orderStateService;

    /**
     * 用户发起退款申请。
     *
     * <p>前置：订单属于当前用户。副作用：插一条 `status=applying` 的退款单；
     * 订单迁到 REFUNDING（USER）并写状态流水。</p>
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @param request 退款原因与可选金额
     * @return 新建的退款单
     * @throws BusinessException 405002 订单不存在/非本人 / 405003 状态不允许 /
     *                           405004 已有进行中的退款 / 405005 金额超上限 / 405006 金额非法
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundVO apply(Long userId, Long orderId, RefundApplyBO request) {
        String reason = requireReason(request == null ? null : request.getReason());
        Orders order = findOrderOfUser(orderId, userId);

        // 先判「已有进行中的退款单」再判状态：订单此时已经是 REFUNDING，
        // 若先判状态会返回 405003（状态非法），把「重复申请」这个更准确的原因盖掉。
        RefundRecord applying = findApplying(orderId);
        if (applying != null) {
            throw new BusinessException(ErrorCode.TRADE_DUPLICATE_SUBMIT, "该订单已有进行中的退款申请");
        }

        OrderStatus from = OrderStatus.ofOrNull(order.getStatus());
        if (from == null || !APPLICABLE.contains(from)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID,
                    "当前订单状态不允许申请退款：" + order.getStatus());
        }
        long amountCents = resolveAmount(order, request.getAmountCents());

        // 订单先迁状态再插单：迁移失败就不用回滚插入（虽然同事务，顺序上先做更有意义的校验）
        if (!orderStateService.transit(orderId, from, OrderStatus.REFUNDING,
                OperatorRole.USER, userId, "用户申请退款：" + reason)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RefundRecord refund = new RefundRecord();
        refund.setOrderId(orderId);
        refund.setReason(reason);
        refund.setAmountCents(amountCents);
        refund.setStatus(STATUS_APPLYING);
        refund.setCreatedAt(now);
        refund.setUpdatedAt(now);
        refundRecordMapper.insert(refund);

        log.info("用户申请退款 orderId={} userId={} refundId={} amountCents={}",
                orderId, userId, refund.getId(), amountCents);
        return toVO(refund);
    }

    /**
     * 查订单最新的一张退款单（C 端展示退款进度）。
     *
     * @param userId  当前登录用户
     * @param orderId 订单主键
     * @return 最新退款单；从未申请过返回 null
     * @throws BusinessException 405002 订单不存在/非本人
     */
    public RefundVO latestOfOrder(Long userId, Long orderId) {
        findOrderOfUser(orderId, userId);
        List<RefundRecord> rows = refundRecordMapper.selectList(Wrappers.<RefundRecord>lambdaQuery()
                .eq(RefundRecord::getOrderId, orderId)
                .orderByDesc(RefundRecord::getId));
        return rows.isEmpty() ? null : toVO(rows.get(0));
    }

    /**
     * PC 端按订单查全部退款单（按时间倒序）。
     *
     * @param orderId 订单主键
     * @return 退款单列表
     */
    public List<RefundVO> listByOrder(Long orderId) {
        return refundRecordMapper.selectList(Wrappers.<RefundRecord>lambdaQuery()
                        .eq(RefundRecord::getOrderId, orderId)
                        .orderByDesc(RefundRecord::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 审批用：按主键查退款单。
     *
     * @param refundId 退款单主键
     * @return 退款单实体
     * @throws BusinessException 405011 退款单不存在
     */
    public RefundRecord requireRefund(Long refundId) {
        RefundRecord refund = refundId == null ? null : refundRecordMapper.selectById(refundId);
        if (refund == null) {
            throw new BusinessException(ErrorCode.TRADE_REFUND_NOT_FOUND);
        }
        return refund;
    }

    /**
     * 审批用：取退款单对应的订单。
     *
     * @param refund 退款单
     * @return 订单实体
     * @throws BusinessException 405002 订单不存在
     */
    public Orders orderOf(RefundRecord refund) {
        Orders order = ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, refund.getOrderId())
                .isNull(Orders::getDeletedAt));
        if (order == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 反查退款前的订单状态（最近一条 to_status=refunding 的 from_status）。
     *
     * @param orderId 订单主键
     * @return 退款前状态；反查不到兜底 PAID
     */
    public OrderStatus resolveOrigin(Long orderId) {
        List<OrderStatusLog> logs = orderStatusLogMapper.selectList(Wrappers.<OrderStatusLog>lambdaQuery()
                .eq(OrderStatusLog::getOrderId, orderId)
                .eq(OrderStatusLog::getToStatus, OrderStatus.REFUNDING.getCode())
                .orderByDesc(OrderStatusLog::getId));
        if (logs.isEmpty()) {
            return OrderStatus.PAID;
        }
        OrderStatus origin = OrderStatus.ofOrNull(logs.get(0).getFromStatus());
        return origin == null ? OrderStatus.PAID : origin;
    }

    /**
     * 实体 → VO。
     *
     * @param refund 退款单
     * @return VO
     */
    public RefundVO toVO(RefundRecord refund) {
        RefundVO vo = new RefundVO();
        vo.setId(refund.getId());
        vo.setOrderId(refund.getOrderId());
        vo.setReason(refund.getReason());
        vo.setAmountCents(refund.getAmountCents());
        vo.setStatus(refund.getStatus());
        vo.setAuditRemark(refund.getAuditRemark());
        vo.setAuditedAt(refund.getAuditedAt());
        vo.setRefundedAt(refund.getRefundedAt());
        vo.setTransactionId(refund.getTransactionId());
        vo.setCreatedAt(refund.getCreatedAt());
        return vo;
    }

    /**
     * 查本订单进行中的退款单。
     *
     * @param orderId 订单主键
     * @return 退款单；没有返回 null
     */
    private RefundRecord findApplying(Long orderId) {
        List<RefundRecord> rows = refundRecordMapper.selectList(Wrappers.<RefundRecord>lambdaQuery()
                .eq(RefundRecord::getOrderId, orderId)
                .eq(RefundRecord::getStatus, STATUS_APPLYING)
                .orderByDesc(RefundRecord::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查「本人的」订单（归属进 SQL）。
     *
     * @param orderId 订单主键
     * @param userId  当前用户
     * @return 订单实体
     * @throws BusinessException 405002 不存在或非本人
     */
    private Orders findOrderOfUser(Long orderId, Long userId) {
        Orders order = orderId == null ? null : ordersMapper.selectOne(Wrappers.<Orders>lambdaQuery()
                .eq(Orders::getId, orderId)
                .eq(Orders::getUserId, userId)
                .isNull(Orders::getDeletedAt));
        if (order == null) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 解析退款金额：空 = 全额；否则 0 < 金额 ≤ 订单实付。
     *
     * @param order       订单
     * @param amountCents 申请金额（可空）
     * @return 退款金额（分）
     * @throws BusinessException 405006 金额非法 / 405005 超上限
     */
    private long resolveAmount(Orders order, Long amountCents) {
        long orderAmount = order.getAmountCents() == null ? 0L : order.getAmountCents();
        if (orderAmount <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID, "订单金额非法，无法退款");
        }
        if (amountCents == null) {
            return orderAmount;
        }
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        if (amountCents > orderAmount) {
            throw new BusinessException(ErrorCode.TRADE_REFUND_EXCEEDS);
        }
        return amountCents;
    }

    /**
     * 校验退款原因。
     *
     * @param reason 原因
     * @return 去空白后的原因
     * @throws BusinessException 401001 为空或超长
     */
    private String requireReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING, "退款原因不能为空");
        }
        if (value.length() > 200) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "退款原因最长 200 字");
        }
        return value;
    }
}
