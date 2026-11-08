/*
 * 职责：余额支付适配层——把「校验支付密码 + 调扣费引擎」串成一次支付，不重写扣款逻辑。
 * 调用方：trade 域的 OrderService（G2.2 支付接口）。
 * 数据来源：PayPasswordService（段 1 的密码校验与连错锁定）、DeductService（段 1 的唯一扣款入口）。
 * 注意：
 *   1. **顺序不可换**：先校验支付密码，再扣钱。密码错时必须「不扣款、不产生流水、不改订单状态」（2-A5）。
 *   2. **禁止 UPDATE user_balance、禁止另写扣款 SQL**——段 2 只有 DeductService 一个扣款入口（§零.4 第 1 条）。
 *   3. 本类**不加 @Transactional**：密码校验会写 Redis（失败计数），把它包进数据库事务没有收益，
 *      且违反「事务内禁止远程调用」（后端规范 §7.2 第 2 条）。扣款自己带事务（DeductService）。
 *   4. 幂等键用订单 id：同一订单重复支付只扣一次（2-A8 ②）。
 */
package com.faze.modules.trade.service;

import com.faze.modules.account.service.PayPasswordService;
import com.faze.modules.trade.vo.DeductResult;
import com.faze.modules.trade.vo.PayFromBalanceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 余额支付适配层。只做「校验密码 → 扣款 → 返回流水」，不碰订单状态。
 *
 * <p>订单状态迁移由调用方在扣款成功后做（CAS 更新），本类不负责。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BalancePayAdapter {

    /** 业务类型：消费（与 balance_flow.biz_type 约定一致）。 */
    public static final String BIZ_TYPE_CONSUME = "consume";

    private final PayPasswordService payPasswordService;

    private final DeductService deductService;

    /**
     * 用账户余额支付一笔订单。
     *
     * <p>前置：userId 与 orderId 来自服务端上下文（不从请求体取）；订单归属与状态由调用方先校验。</p>
     * <p>副作用：校验支付密码（失败会累加 Redis 计数）；扣款成功时写一条 balance_flow 并更新余额缓存。</p>
     *
     * @param userId      付款用户 ID
     * @param orderId     订单主键（同时作为幂等键）
     * @param amountCents 订单金额（分，> 0）
     * @param payPassword 用户输入的支付密码明文（TLS 内）
     * @return 扣款结果（流水 ID + 是否幂等命中 + 变动后余额）
     * @throws com.faze.common.exception.PayPasswordWrongException  密码错（带剩余次数）
     * @throws com.faze.common.exception.PayPasswordLockedException 锁定期内
     * @throws com.faze.common.exception.BusinessException           余额不足等业务失败
     */
    public PayFromBalanceResult payFromBalance(Long userId, Long orderId, long amountCents, String payPassword) {
        // 任务 1：校验支付密码。错就到此为止——不扣款、不写流水（2-A5）
        payPasswordService.verify(userId, payPassword);

        // 任务 2：走唯一扣款入口；幂等键 = (user, consume, orderId)
        DeductResult result = deductService.deduct(
                BalanceService.OWNER_USER, userId, BIZ_TYPE_CONSUME, orderId, amountCents, "订单支付");

        log.info("余额支付 userId={} orderId={} flowId={} duplicated={} amountCents={}",
                userId, orderId, result.flowId(), result.duplicated(), amountCents);

        // 任务 3：把结果原样交给调用方，由它做订单状态迁移
        return new PayFromBalanceResult(result.flowId(), result.duplicated(), result.balanceAfterCents());
    }
}
