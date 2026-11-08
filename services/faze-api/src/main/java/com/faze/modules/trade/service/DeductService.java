/*
 * 职责：统一扣费入口（第 1 段 D3）。幂等靠 uk_flow_biz，并发靠行锁。
 * 调用方：段 2 起的订单扣费、段 5 的结算扣减；本段给 D4 调账复用。
 * 数据来源：user_balance（加锁读）、balance_flow（写）。
 * 注意：
 *   1. **顺序不能变**：幂等预查 → 锁余额行 → 校验余额 → 插流水 → 更新缓存。
 *      把「插流水」放到校验之前，余额不足时也会留一条脏流水；
 *      把「更新缓存」放到插流水之前，中途失败会出现「扣了缓存没流水」。
 *   2. **幂等命中返回成功，不抛异常**。抛异常会让调用方重试，
 *      而重复请求本来就是正常场景（网络重试、用户连点）。
 *   3. **隔离级别必须是 READ_COMMITTED**：MySQL 默认 REPEATABLE READ，
 *      事务快照看不到并发事务后续提交的流水，导致「拿锁后复查幂等」永远查不到、
 *      只能靠唯一键异常兜底。改成 READ_COMMITTED 后，锁后二次读能看到最新已提交数据，
 *      幂等判断才能落在正常路径上，而不是异常路径上。
 *      实测：10 线程并发同一 biz 时，REPEATABLE READ 会抛 DuplicateKeyException。
 *   4. `DuplicateKeyException` 仍要在事务内捕获并返回（兜底）：MySQL 的重复键错误
 *      不会让事务失效（PostgreSQL 会，所以这里锁 MySQL 的口径）。
 *   5. 金额必须是正整数分；扣减金额 ≤ 0 一律拒绝——允许 0 会让「免费扣费」变成
 *      一个可以无限调用的流水生成器。
 *   6. 事务内**不做远程调用**（规范 §7.2），本方法是纯 DB。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.UserBalanceMapper;
import com.faze.modules.trade.vo.DeductResult;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 扣费引擎。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeductService {

    private final UserBalanceMapper userBalanceMapper;
    private final BalanceFlowMapper balanceFlowMapper;

    /**
     * 扣减余额。
     *
     * <p>前置：金额为正整数分；归属与业务键完整。</p>
     * <p>副作用：插入一条 balance_flow、更新 user_balance 的可用余额。
     * 幂等命中时不产生任何写入。</p>
     *
     * @param ownerType   归属类型（段 1 只支持 user）
     * @param ownerId     归属主键
     * @param bizType     业务类型（consume / adjust / withdraw …）
     * @param bizId       业务主键（**必须非空**，否则唯一键不拦重复）
     * @param amountCents 扣减金额（分，必须 > 0）
     * @param remark      备注
     * @return 扣费结果（含是否幂等命中）
     * @throws BusinessException 405006 金额非法 / 401001 参数缺失 / 405001 余额不足 / 401004 余额行不存在
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DeductResult deduct(String ownerType, Long ownerId, String bizType, Long bizId,
                               long amountCents, String remark) {
        // 任务 1：参数校验。金额必须 > 0；bizId 必须非空（NULL 会让唯一键失效）。
        if (amountCents <= 0) {
            throw new BusinessException(ErrorCode.TRADE_AMOUNT_INVALID);
        }
        if (!BalanceService.OWNER_USER.equals(ownerType)) {
            // 段 1 律师侧还没有扣费场景，先显式拒绝，避免写出半套逻辑
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }
        if (!StringUtils.hasText(bizType) || bizId == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID);
        }

        // 任务 2：幂等预查（快速路径，避免对已完成的业务无谓加锁）。
        BalanceFlow existing = findFlow(ownerType, ownerId, bizType, bizId);
        if (existing != null) {
            log.info("扣费幂等命中 ownerId={} bizType={} bizId={} flowId={}",
                    ownerId, bizType, bizId, existing.getId());
            return new DeductResult(true, existing.getId(), existing.getBalanceAfter());
        }

        // 任务 3：锁余额行。锁住之后，校验与更新之间不会有其他事务插入修改。
        UserBalance cache = userBalanceMapper.selectByUserIdForUpdate(ownerId);
        if (cache == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }

        // 任务 3.5：**拿到锁之后必须再查一次幂等**。
        // 原因：任务 2 的预查在锁外发生，并发时两个线程都可能查不到；
        // 而 REPEATABLE READ 下事务快照看不到别人后续提交的行，所以只能
        // 「先拿锁（此时前一个事务已提交）→ 在同一事务内重新读」。
        // 本方法已声明 READ_COMMITTED，保证这次读能看到最新已提交数据。
        BalanceFlow afterLock = findFlow(ownerType, ownerId, bizType, bizId);
        if (afterLock != null) {
            log.info("扣费幂等命中（锁后复查）ownerId={} bizType={} bizId={} flowId={}",
                    ownerId, bizType, bizId, afterLock.getId());
            return new DeductResult(true, afterLock.getId(), afterLock.getBalanceAfter());
        }
        long available = cache.getBalanceCents() == null ? 0L : cache.getBalanceCents();
        long frozen = cache.getFrozenCents() == null ? 0L : cache.getFrozenCents();
        long totalBefore = available + frozen;

        // 任务 4：余额校验。不足时直接抛，**不产生流水**。
        if (available < amountCents) {
            throw new BusinessException(ErrorCode.TRADE_BALANCE_INSUFFICIENT);
        }

        // 任务 5：插流水。唯一键兜底并发下的重复提交。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(ownerType);
        flow.setOwnerId(ownerId);
        flow.setBizType(bizType);
        flow.setBizId(bizId);
        flow.setDeltaCents(-amountCents);
        flow.setBalanceAfter(totalBefore - amountCents);
        flow.setRemark(remark);
        flow.setCreatedAt(now);
        try {
            balanceFlowMapper.insert(flow);
        } catch (DuplicateKeyException e) {
            // 并发下同一 biz 被另一线程抢先插入：按幂等成功处理
            BalanceFlow first = findFlow(ownerType, ownerId, bizType, bizId);
            if (first == null) {
                // 理论上不可能：唯一键冲突说明它存在。真出现说明有别的唯一键，重新抛出
                throw e;
            }
            log.info("扣费并发幂等命中 ownerId={} bizType={} bizId={}", ownerId, bizType, bizId);
            return new DeductResult(true, first.getId(), first.getBalanceAfter());
        }

        // 任务 6：更新缓存（带乐观锁版本）。FOR UPDATE 已保证不会冲突。
        cache.setBalanceCents(available - amountCents);
        cache.setUpdatedAt(now);
        userBalanceMapper.updateById(cache);

        log.info("扣费成功 ownerId={} bizType={} bizId={} amount={} balanceAfter={}",
                ownerId, bizType, bizId, amountCents, flow.getBalanceAfter());
        return new DeductResult(false, flow.getId(), flow.getBalanceAfter());
    }

    /**
     * 按业务键查流水（幂等判断用）。
     *
     * @param ownerType 归属类型
     * @param ownerId   归属主键
     * @param bizType   业务类型
     * @param bizId     业务主键
     * @return 已存在的流水；不存在返回 null
     */
    private BalanceFlow findFlow(String ownerType, Long ownerId, String bizType, Long bizId) {
        return balanceFlowMapper.selectOne(Wrappers.<BalanceFlow>lambdaQuery()
                .eq(BalanceFlow::getOwnerType, ownerType)
                .eq(BalanceFlow::getOwnerId, ownerId)
                .eq(BalanceFlow::getBizType, bizType)
                .eq(BalanceFlow::getBizId, bizId));
    }
}
