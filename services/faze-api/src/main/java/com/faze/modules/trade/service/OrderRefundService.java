/*
 * 职责：退款业务——自动全额退款（G2.4 超时无人接）与退款入账编排。
 * 调用方：modules/trade/task/OrderCloseTask（PAID 超时）、G4.2 后的审批通过分支。
 * 数据来源：orders（状态）、refund_record（写）、payment_record（写负数行）、balance_flow（经 RefundCreditService）。
 * 注意：
 *   1. **一次退款 = 状态两次迁移 + 三张表写入**，全部在一个事务里：
 *      PAID → REFUNDING → REFUNDED，写 refund_record、payment_record 负行、balance_flow。
 *      工作流 G2.4 第 5 条明确警告「很容易做成只改状态不退钱」——`2-A8` ① 的原文是
 *      「余额退回**且有流水**」，所以三件事缺一不可。
 *   2. **先钱后状态还是先状态后钱**：本类选择「先 REFUNDING → 再入账 → 再 REFUNDED」，
 *      即入账成功前订单停在 REFUNDING。若入账失败事务回滚，订单仍是 PAID，
 *      下一次定时任务会再试（任务表保证至少一次）。反过来若先置 REFUNDED 再入账，
 *      入账失败就会出现「已退款但用户没收到钱」的既成事实。
 *   3. `payment_record` 的退款行是**新增负数行**，不改原来那条正数行（净收款口径
 *      `SUM(amount_cents)`）。
 *   4. 幂等：`RefundCreditService` 按 (user, refund, orderId) 幂等，
 *      所以任务重试不会退两次钱；退款单查询也按 orderId 复用。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.PaymentRecord;
import com.faze.modules.trade.entity.RefundRecord;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.PaymentRecordMapper;
import com.faze.modules.trade.mapper.RefundRecordMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款业务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderRefundService {

    /** 退款单状态：已到账。 */
    public static final String REFUND_STATUS_REFUNDED = "refunded";

    /** 支付渠道：余额。 */
    public static final String CHANNEL_BALANCE = "balance";

    /** 渠道流水前缀（`BAL-<flowId>`）。 */
    private static final String TXN_PREFIX = "BAL-";

    private final OrderStateService orderStateService;
    private final RefundCreditService refundCreditService;
    private final RefundRecordMapper refundRecordMapper;
    private final PaymentRecordMapper paymentRecordMapper;

    /**
     * 自动全额退款（超时无人接单、律师拒单等无需人工审批的场景）。
     *
     * <p>前置：订单状态为 PAID，且已确认要走退款（调用方判断超时/拒单）。</p>
     * <p>副作用：订单状态 PAID→REFUNDING→REFUNDED；写 refund_record、payment_record 负行、
     * balance_flow，并把钱退回用户可用余额。</p>
     *
     * @param order  订单（金额、用户、主键都会被使用）
     * @param reason 退款原因（写入退款单与状态流水）
     * @throws BusinessException 405003 订单状态不是 PAID / 405006 金额非法
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoRefundFull(Orders order, String reason) {
        // 任务 1：PAID → REFUNDING（CAS；并发下被别人改过就抛状态非法）
        if (!orderStateService.transit(order.getId(), OrderStatus.PAID, OrderStatus.REFUNDING,
                OperatorRole.SYSTEM, null, reason)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        settleRefund(order, reason, "系统自动全额退款：" + reason);
    }

    /**
     * 订单已在 REFUNDING 时，把「退款单 + 入账 + 支付负行 + 置 REFUNDED」四步做完。
     *
     * <p>调用方：{@link #autoRefundFull}（PAID 超时自动退）、律师拒单（G3.3，已先迁到 REFUNDING）、
     * G4.2 后台审批通过。</p>
     *
     * <p><b>前置：订单状态必须已是 REFUNDING</b>，本方法不负责 PAID→REFUNDING 那一步。
     * 拆出来的原因是两个入口的「谁触发 PAID→REFUNDING」不同（SYSTEM 超时 vs LAWYER 拒单），
     * 但后面的入账补偿必须只有一份实现。</p>
     *
     * <p>副作用：写 refund_record、payment_record 负数行、balance_flow，并把订单置为 REFUNDED。</p>
     *
     * @param order       订单（金额、用户、主键都会被使用）
     * @param reason      退款原因（写入退款单 reason 字段）
     * @param auditRemark 审批意见（写入 refund_record.audit_remark）
     * @throws BusinessException 405006 金额非法 / 405003 状态不是 REFUNDING
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleRefund(Orders order, String reason, String auditRemark) {
        long amountCents = order.getAmountCents() == null ? 0L : order.getAmountCents();
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 任务 2：建退款单（系统/自动退：无人工审批人，audited_at 记为处理时间）
        RefundRecord refund = new RefundRecord();
        refund.setOrderId(order.getId());
        refund.setReason(reason);
        refund.setAmountCents(amountCents);
        refund.setStatus(REFUND_STATUS_REFUNDED);
        refund.setAuditRemark(auditRemark);
        refund.setAuditedAt(now);
        refund.setRefundedAt(now);
        refund.setCreatedAt(now);
        refund.setUpdatedAt(now);
        refundRecordMapper.insert(refund);

        // 任务 3：入账（幂等键 = orderId，重试不会退两次）
        Long flowId = refundCreditService.credit(order.getUserId(), order.getId(), amountCents,
                "订单退款：" + reason);
        String transactionId = TXN_PREFIX + flowId;

        // 任务 4：回写退款单的流水号
        refundRecordMapper.update(null, Wrappers.<RefundRecord>lambdaUpdate()
                .set(RefundRecord::getTransactionId, transactionId)
                .set(RefundRecord::getUpdatedAt, now)
                .eq(RefundRecord::getId, refund.getId()));

        // 任务 5：payment_record 负数行（净收款口径）
        PaymentRecord payment = new PaymentRecord();
        payment.setOrderId(order.getId());
        payment.setChannel(CHANNEL_BALANCE);
        payment.setTransactionId(transactionId);
        payment.setAmountCents(-amountCents);
        payment.setStatus("success");
        payment.setPaidAt(now);
        payment.setCreatedAt(now);
        paymentRecordMapper.insert(payment);

        // 任务 6：REFUNDING → REFUNDED（钱已到账，才是终态）
        if (!orderStateService.transit(order.getId(), OrderStatus.REFUNDING, OrderStatus.REFUNDED,
                OperatorRole.SYSTEM, null, "退款到账")) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }

        log.info("退款完成 orderId={} userId={} amountCents={} flowId={}",
                order.getId(), order.getUserId(), amountCents, flowId);
    }
}
