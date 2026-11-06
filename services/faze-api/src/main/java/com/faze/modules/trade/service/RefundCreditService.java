/*
 * 职责：退款入账的唯一入口——把退款金额退回用户可用余额，并写一条 refund 流水。
 * 调用方：modules/trade/service/OrderRefundService（G2.4 自动全额退、G4.3 人工审批通过后入账）。
 * 数据来源：user_balance（加锁读/写）、balance_flow（写）。
 * 注意：
 *   1. **顺序与 DeductService 同构**：幂等预查 → 锁余额行 → 锁后复查 → 插流水 → 更新缓存。
 *      顺序错了会出现「有流水没入账」或「入了账没流水」。
 *   2. **幂等键用 (user, refund, orderId)**：一单只退一次。重复调用返回已有流水的 id，
 *      不抛异常（重复请求是正常场景：任务重试、审批重放）。
 *   3. **隔离级别必须 READ_COMMITTED**：REPEATABLE READ 下锁后复查看不到并发事务刚提交的流水，
 *      幂等只能靠唯一键异常兜底（与 DeductService 的实测结论一致）。
 *   4. 只做入账，**不改订单状态、不写 payment_record、不写 refund_record**：
 *      那三件事是 OrderRefundService 的职责，放在同一个外层事务里。
 *   5. 金额必须 > 0：0 元退款会产生一条无意义流水。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款入账。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundCreditService {

    /** 流水业务类型：退款。 */
    public static final String BIZ_TYPE_REFUND = "refund";

    private final UserBalanceMapper userBalanceMapper;
    private final BalanceFlowMapper balanceFlowMapper;

    /**
     * 把退款金额退回用户余额。
     *
     * <p>前置：调用方已完成「订单属于该用户」「退款金额不超过实付」等业务校验。</p>
     * <p>副作用：插一条 refund 流水（正数）、更新 user_balance 的可用余额。</p>
     *
     * @param userId      退款接收人
     * @param orderId     订单主键（同时作为幂等键）
     * @param amountCents 退款金额（分，> 0）
     * @param remark      流水备注
     * @return 退款流水主键（用于 payment_record.transaction_id = BAL-&lt;flowId&gt;）
     * @throws BusinessException 405006 金额非法 / 401004 余额行不存在
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long credit(Long userId, Long orderId, long amountCents, String remark) {
        // 任务 1：参数校验
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        if (orderId == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_MISSING);
        }

        // 任务 2：幂等预查（锁外快速路径）
        BalanceFlow existing = findRefundFlow(userId, orderId);
        if (existing != null) {
            log.info("退款入账幂等命中 userId={} orderId={} flowId={}", userId, orderId, existing.getId());
            return existing.getId();
        }

        // 任务 3：锁余额行
        UserBalance cache = userBalanceMapper.selectByUserIdForUpdate(userId);
        if (cache == null) {
            // 余额行不存在 = 这个用户从没充值过；退款无处可入账，必须暴露而不是静默丢弃
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND, "用户余额记录不存在");
        }

        // 任务 4：锁后复查幂等（READ_COMMITTED 下能看到最新提交）
        BalanceFlow afterLock = findRefundFlow(userId, orderId);
        if (afterLock != null) {
            log.info("退款入账幂等命中（锁后复查） userId={} orderId={} flowId={}",
                    userId, orderId, afterLock.getId());
            return afterLock.getId();
        }

        // 任务 5：插流水（balanceAfter 记总余额 = 可用 + 冻结，与段 1 D 组口径一致）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long available = cache.getBalanceCents() == null ? 0L : cache.getBalanceCents();
        long frozen = cache.getFrozenCents() == null ? 0L : cache.getFrozenCents();
        long totalBefore = available + frozen;
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(BalanceService.OWNER_USER);
        flow.setOwnerId(userId);
        flow.setBizType(BIZ_TYPE_REFUND);
        flow.setBizId(orderId);
        flow.setDeltaCents(amountCents);
        flow.setBalanceAfter(totalBefore + amountCents);
        flow.setRemark(remark);
        flow.setCreatedAt(now);
        try {
            balanceFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一键拦住了第二个请求，把已有流水返回（不抛）
            BalanceFlow first = findRefundFlow(userId, orderId);
            if (first == null) {
                throw e;
            }
            return first.getId();
        }

        // 任务 6：更新缓存
        cache.setBalanceCents(available + amountCents);
        cache.setUpdatedAt(now);
        userBalanceMapper.updateById(cache);
        log.info("退款入账成功 userId={} orderId={} amountCents={} flowId={} totalAfter={}",
                userId, orderId, amountCents, flow.getId(), totalBefore + amountCents);
        return flow.getId();
    }

    /**
     * 查已存在的退款流水。
     *
     * @param userId  用户主键
     * @param orderId 订单主键
     * @return 流水；不存在返回 null
     */
    private BalanceFlow findRefundFlow(Long userId, Long orderId) {
        return balanceFlowMapper.selectOne(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, BalanceService.OWNER_USER)
                .eq(BalanceFlow::getOwnerId, userId)
                .eq(BalanceFlow::getBizType, BIZ_TYPE_REFUND)
                .eq(BalanceFlow::getBizId, orderId));
    }
}
