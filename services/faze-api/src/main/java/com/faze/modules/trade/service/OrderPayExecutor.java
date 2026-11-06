/*
 * 职责：余额支付订单的事务执行器（G2.2）——扣款、状态迁移、支付记录、超时任务一个事务。
 * 调用方：modules/trade/service/impl/OrderServiceImpl#pay（编排层，负责归属查询与幂等判断）。
 * 数据来源：orders、balance_flow（经 BalancePayAdapter → DeductService）、payment_record、task_queue。
 * 注意：
 *   1. **六步顺序不可换**（工作流 G2.2 第 1 条）：
 *      ① 状态复核（必须 PENDING）→ ② 校验支付密码 → ③ 扣款 → ④ CAS PENDING→PAID
 *      → ⑤ 写 payment_record → ⑥ 写状态流水（④ 里的 `OrderStateService` 已经写了）。
 *      把 ④ 放到 ③ 之前：扣款失败时订单已变 PAID，钱没扣单却已付款。
 *      把 ③ 放到事务外：扣了钱、改状态失败时没有回滚，钱白扣。
 *   2. **`deduct()` 是 REQUIRED 传播**（段 1 的 DeductService 自带 `@Transactional`），
 *      加入本类事务。**绝不能是 REQUIRES_NEW**：那会让扣款独立提交，
 *      本事务回滚时钱已经扣走了。
 *   3. **支付密码校验放在事务内是有意为之**：它要写 Redis 失败计数（非数据库），
 *      密码错时本事务回滚，但 Redis 的失败计数保留——这正是我们要的（错一次算一次）。
 *   4. 幂等在两个层次保证：订单状态（PAID 就不再扣）+ `DeductService` 的
 *      `uk_flow_biz(user, consume, orderId)`。两层都失效的概率极低，但都要在。
 *   5. 支付成功要**同时**投递 30 分钟接单超时任务（G2.4 的自动退款靠它）。
 *   6. `payment_record` 唯一键是 (order_id, channel, transaction_id)，重复插入用
 *      catch 忽略：流水 ID 相同说明就是同一笔，多写一行反而是脏数据。
 */
package com.faze.modules.trade.service;

import com.faze.common.R;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.Orders;
import com.faze.modules.trade.entity.PaymentRecord;
import com.faze.modules.trade.enums.OperatorRole;
import com.faze.modules.trade.enums.OrderStatus;
import com.faze.modules.trade.mapper.PaymentRecordMapper;
import com.faze.modules.trade.rule.OrderTimeoutPolicy;
import com.faze.modules.trade.vo.PayFromBalanceResult;
import com.faze.modules.trade.vo.PayResultVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单余额支付执行器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderPayExecutor {

    /** 支付渠道：余额。 */
    private static final String CHANNEL_BALANCE = "balance";

    /** 渠道流水前缀（`BAL-<flowId>`）。 */
    private static final String TXN_PREFIX = "BAL-";

    private final BalancePayAdapter balancePayAdapter;
    private final OrderStateService orderStateService;
    private final PaymentRecordMapper paymentRecordMapper;
    private final TaskQueueService taskQueueService;

    /**
     * 执行余额支付。
     *
     * <p>前置：调用方已确认订单属于当前用户、金额 > 0。</p>
     * <p>副作用：扣余额、写 balance_flow、订单状态 PENDING→PAID、写 payment_record 与状态流水、
     * 投递接单超时任务。</p>
     *
     * @param order       当前订单（状态必须是 PENDING）
     * @param payPassword 支付密码明文
     * @return 支付结果
     * @throws com.faze.common.exception.PayPasswordWrongException 密码错（不扣款）
     * @throws com.faze.common.exception.BusinessException          405001 余额不足 / 405003 状态非法
     */
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO pay(Orders order, String payPassword) {
        // 任务 1：状态复核（事务内再确认一次，防止「校验通过后订单被别人关掉」）
        if (!OrderStatus.PENDING.getCode().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }

        // 任务 2 + 3：校验支付密码 → 走唯一扣款入口（adapter 内部顺序已固定）
        PayFromBalanceResult paid = balancePayAdapter.payFromBalance(
                order.getUserId(), order.getId(), order.getAmountCents(), payPassword);

        // 任务 4：CAS 迁移 PENDING → PAID（同时写 order_status_log）
        boolean transitOk = orderStateService.transit(order.getId(), OrderStatus.PENDING, OrderStatus.PAID,
                OperatorRole.USER, order.getUserId(), "用户余额支付");
        if (!transitOk) {
            // 扣款已发生，这里必须抛异常让事务回滚，钱会一起退回去
            throw new BusinessException(ErrorCode.TRADE_ORDER_STATE_INVALID);
        }

        // 任务 5：写支付记录（幂等：同一笔流水只会有一行）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String transactionId = TXN_PREFIX + paid.flowId();
        PaymentRecord payment = new PaymentRecord();
        payment.setOrderId(order.getId());
        payment.setChannel(CHANNEL_BALANCE);
        payment.setTransactionId(transactionId);
        payment.setAmountCents(order.getAmountCents());
        payment.setStatus("success");
        payment.setPaidAt(now);
        payment.setCreatedAt(now);
        try {
            paymentRecordMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            log.info("支付记录已存在（幂等重放） orderId={} txn={}", order.getId(), transactionId);
        }

        // 任务 6：投递 30 分钟接单超时任务（入队时间 = 支付时间 + 30 分钟）
        taskQueueService.enqueue(TaskQueueService.TYPE_ORDER_ACCEPT_TIMEOUT, order.getOrderNo(),
                OrderTimeoutPolicy.acceptExpireAt(now), MDC.get(R.TRACE_ID_MDC_KEY));

        // 任务 7：组装结果
        PayResultVO vo = new PayResultVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(OrderStatus.PAID.getCode());
        vo.setAmountCents(order.getAmountCents());
        vo.setPaidAt(now);
        vo.setBalanceAfterCents(paid.balanceAfterCents());
        vo.setDuplicated(paid.duplicated());
        log.info("订单支付成功 orderId={} orderNo={} amountCents={} flowId={} duplicated={}",
                order.getId(), order.getOrderNo(), order.getAmountCents(), paid.flowId(), paid.duplicated());
        return vo;
    }
}
