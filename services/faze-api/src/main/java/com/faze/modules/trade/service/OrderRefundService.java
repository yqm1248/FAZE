/*
 * 职责：退款资金编排——退款单状态、入账、支付负行、订单状态迁移（G2.4 自动退 / G4 人工审批）。
 * 调用方：modules/trade/task/OrderCloseTask（PAID 超时自动退）、LawyerOrderService（律师拒单）、
 *        modules/trade/service/RefundService（G4.1 申请 / G4.2 审批通过）。
 * 数据来源：orders（状态）、refund_record（读写）、payment_record（写负数行）、balance_flow（经 RefundCreditService）。
 * 注意：
 *   1. **一次退款 = 状态迁移 + 三张表写入**，全部在一个事务里。工作流 G2.4 第 5 条警告
 *      「很容易做成只改状态不退钱」——`2-A8` ① 的原文是「余额退回**且有流水**」，三件事缺一不可。
 *   2. **先 REFUNDING → 再入账 → 再终态**：入账成功前订单停在 REFUNDING。若入账失败事务回滚，
 *      订单仍是原状态，下次重试。反过来「先置终态再入账」会出现「已退款但用户没收到钱」的既成事实。
 *   3. `payment_record` 的退款行是**新增负数行**，不改原来那条正数行（净收款口径 `SUM(amount_cents)`）。
 *   4. **幂等键是退款单 id**（不是订单 id）：G4 允许同一订单先部分退、之后再次退款，
 *      每次一张退款单、各自一条流水；用订单 id 当幂等键会把第二次退款的入账吃掉（钱退不出去）。
 *   5. **律师余额一律不动**：段 2 没有结算，`orders.lawyer_income_cents` 只是数字，
 *      从未真实入账到 `lawyer_balance`。退款只退回用户余额。段 5 做结算的人不要以为这里是漏写。
 *   6. **部分退款不改订单终态**：全额退 → REFUNDED；部分退 → 回到退款前状态（PAID / IN_SERVICE），
 *      因为「退了一部分」不代表订单作废。这个裁决技术路线没写，是段 2 补的。
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
 * 退款资金编排。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderRefundService {

    /** 退款单状态：用户已申请、待审批。 */
    public static final String REFUND_STATUS_APPLYING = "applying";

    /** 退款单状态：已到账。 */
    public static final String REFUND_STATUS_REFUNDED = "refunded";

    /** 退款单状态：审批驳回。 */
    public static final String REFUND_STATUS_REJECTED = "rejected";

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
     * 订单已在 REFUNDING 时，把「退款单 + 入账 + 支付负行 + 置 REFUNDED」四步做完（**全额**）。
     *
     * <p>调用方：{@link #autoRefundFull}（PAID 超时自动退）、律师拒单（G3.3，已先迁到 REFUNDING）。
     * 人工审批通过走 {@link #settleApprovedRefund}（金额可能是部分的）。</p>
     *
     * <p><b>前置：订单状态必须已是 REFUNDING</b>，本方法不负责 PAID→REFUNDING 那一步。</p>
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

        // 任务 3-5：入账 + 回写流水号 + 支付负行
        creditRefund(order, refund);

        // 任务 6：REFUNDING → REFUNDED（钱已到账，才是终态）
        if (!orderStateService.transit(order.getId(), OrderStatus.REFUNDING, OrderStatus.REFUNDED,
                OperatorRole.SYSTEM, null, "退款到账")) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }

        log.info("退款完成（自动/全额）orderId={} refundId={} userId={} amountCents={}",
                order.getId(), refund.getId(), order.getUserId(), refund.getAmountCents());
    }

    /**
     * 审批通过后结清一笔已申请的退款（支持部分退款）。
     *
     * <p>前置：退款单状态为 applying、订单状态为 REFUNDING（调用方校验）。</p>
     *
     * <p>副作用：退款单置 refunded + 审批人/时间；入账 + 支付负行；订单迁到
     * {@code targetStatus}（全额 = REFUNDED；部分 = 退款前状态）。</p>
     *
     * @param order        订单（金额、用户、主键都会被使用）
     * @param refund       已申请的退款单（金额以它为准）
     * @param adminId      审批管理员 id
     * @param auditRemark  审批意见
     * @param targetStatus 审批通过后订单的目标状态（REFUNDED 或退款前状态）
     * @throws BusinessException 405003 状态不允许 / 405006 金额非法
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleApprovedRefund(Orders order, RefundRecord refund, Long adminId,
                                     String auditRemark, OrderStatus targetStatus) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 任务 1：退款单置为已到账（CAS 带 status = applying，防重复审批）
        int updated = refundRecordMapper.update(null, Wrappers.<RefundRecord>lambdaUpdate()
                .set(RefundRecord::getStatus, REFUND_STATUS_REFUNDED)
                .set(RefundRecord::getAuditRemark, auditRemark)
                .set(RefundRecord::getAuditedBy, adminId)
                .set(RefundRecord::getAuditedAt, now)
                .set(RefundRecord::getRefundedAt, now)
                .set(RefundRecord::getUpdatedAt, now)
                .eq(RefundRecord::getId, refund.getId())
                .eq(RefundRecord::getStatus, REFUND_STATUS_APPLYING));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID, "退款单已处理，不能重复审批");
        }

        // 任务 2-4：入账 + 回写流水号 + 支付负行
        creditRefund(order, refund);

        // 任务 5：订单迁移（全额 → REFUNDED；部分 → 回退款前状态）
        if (!orderStateService.transit(order.getId(), OrderStatus.REFUNDING, targetStatus,
                OperatorRole.ADMIN, adminId, auditRemark)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }

        log.info("退款审批通过 orderId={} refundId={} amountCents={} target={} adminId={}",
                order.getId(), refund.getId(), refund.getAmountCents(), targetStatus, adminId);
    }

    /**
     * 审批驳回：退款单置 rejected，订单 REFUNDING → REFUND_REJECTED。
     *
     * <p>驳回后订单还要由调用方迁回退款前状态（SYSTEM 角色，状态机要求）。本方法不做那一步，
     * 因为「退款前状态」只能由调用方从状态流水里反查。</p>
     *
     * @param order       订单
     * @param refund      退款单
     * @param adminId     审批管理员 id
     * @param auditRemark 驳回原因（必填，调用方校验）
     * @throws BusinessException 405003 状态不允许
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejectRefund(Orders order, RefundRecord refund, Long adminId, String auditRemark) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = refundRecordMapper.update(null, Wrappers.<RefundRecord>lambdaUpdate()
                .set(RefundRecord::getStatus, REFUND_STATUS_REJECTED)
                .set(RefundRecord::getAuditRemark, auditRemark)
                .set(RefundRecord::getAuditedBy, adminId)
                .set(RefundRecord::getAuditedAt, now)
                .set(RefundRecord::getUpdatedAt, now)
                .eq(RefundRecord::getId, refund.getId())
                .eq(RefundRecord::getStatus, REFUND_STATUS_APPLYING));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID, "退款单已处理，不能重复审批");
        }
        if (!orderStateService.transit(order.getId(), OrderStatus.REFUNDING, OrderStatus.REFUND_REJECTED,
                OperatorRole.ADMIN, adminId, auditRemark)) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }
        log.info("退款审批驳回 orderId={} refundId={} adminId={}", order.getId(), refund.getId(), adminId);
    }

    /**
     * 入账 + 回写退款单流水号 + 写 payment_record 负数行。
     *
     * <p>幂等：入账由 {@link RefundCreditService} 按退款单 id 保证；支付负行按
     * `(order_id, channel, transaction_id)` 唯一键预查，重复调用不会插第二条。</p>
     *
     * @param order  订单
     * @param refund 退款单（amountCents 必填）
     * @throws BusinessException 405006 金额非法
     */
    private void creditRefund(Orders order, RefundRecord refund) {
        long amountCents = refund.getAmountCents() == null ? 0L : refund.getAmountCents();
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 入账（幂等键 = 退款单 id）
        Long flowId = refundCreditService.credit(order.getUserId(), refund.getId(), amountCents,
                "订单退款：" + refund.getReason());
        String transactionId = TXN_PREFIX + flowId;

        // 回写退款单流水号
        refundRecordMapper.update(null, Wrappers.<RefundRecord>lambdaUpdate()
                .set(RefundRecord::getTransactionId, transactionId)
                .set(RefundRecord::getUpdatedAt, now)
                .eq(RefundRecord::getId, refund.getId()));

        // payment_record 负数行：按唯一键先查，避免重复插入
        PaymentRecord exists = paymentRecordMapper.selectOne(Wrappers.<PaymentRecord>lambdaQuery()
                .eq(PaymentRecord::getOrderId, order.getId())
                .eq(PaymentRecord::getChannel, CHANNEL_BALANCE)
                .eq(PaymentRecord::getTransactionId, transactionId));
        if (exists == null) {
            PaymentRecord payment = new PaymentRecord();
            payment.setOrderId(order.getId());
            payment.setChannel(CHANNEL_BALANCE);
            payment.setTransactionId(transactionId);
            payment.setAmountCents(-amountCents);
            payment.setStatus("success");
            payment.setPaidAt(now);
            payment.setCreatedAt(now);
            paymentRecordMapper.insert(payment);
        }
    }
}
