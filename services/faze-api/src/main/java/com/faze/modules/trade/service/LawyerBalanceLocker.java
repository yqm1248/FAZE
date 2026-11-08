/*
 * 职责：律师余额行（lawyer_balance）的「加锁 + 落库」公共能力（段 5 E1/E3/F1/F3 共用）。
 * 调用方：modules/trade/service/SettlementService（收入入账、到期解冻）、
 *         modules/trade/service/WithdrawService（提现冻结、驳回回滚、出款扣减）。
 * 数据来源：faze.lawyer_balance（缓存表，权威仍是 balance_flow）。
 * 注意：
 *   1. **为什么抽出来**：五个动钱点如果各写一份「读行 → 算 → updateById」，
 *      早晚有一处忘了带 version 或忘了 FOR UPDATE，就会出现「余额对不上流水」。
 *   2. **为什么用 `SELECT ... FOR UPDATE` 而不是纯乐观锁重试**：
 *      调用方要在同一事务里「读旧值 → 算新值 → 把新总余额写进 balance_after」，
 *      而 REPEATABLE READ 下快照读重试会一直读到旧值，重试 3 次还是 0 行。
 *      行锁是唯一能让「读取的数」和「写入的数」自洽的做法。
 *   3. **惰性建行**：新律师没有余额行是正常状态，本类负责建 0 行（唯一键兜并发）。
 *   4. 本类**不校验业务规则**（够不够扣、是不是负数）：那是调用方的事。
 *      本类只保证「加锁 + 带 version 落库」这一件事正确。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.modules.trade.entity.LawyerBalance;
import com.faze.modules.trade.mapper.LawyerBalanceMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 律师余额行加锁与落库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LawyerBalanceLocker {

    private final LawyerBalanceMapper lawyerBalanceMapper;

    /**
     * 锁住律师余额行（不存在则先建 0 行再加锁）。
     *
     * <p>必须在事务内调用，否则 `FOR UPDATE` 的锁会在语句结束后立刻释放。</p>
     *
     * @param lawyerId 律师主键
     * @return 已加行锁的余额行（返回后调用方可以安全地读旧值）
     */
    public LawyerBalance lock(Long lawyerId) {
        LawyerBalance row = selectForUpdate(lawyerId);
        if (row != null) {
            return row;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LawyerBalance created = new LawyerBalance();
        created.setLawyerId(lawyerId);
        created.setAvailableCents(0L);
        created.setFrozenCents(0L);
        created.setTotalIncomeCents(0L);
        created.setVersion(0);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            lawyerBalanceMapper.insert(created);
        } catch (DuplicateKeyException e) {
            // 并发首访：另一请求刚建了行，重新读即可
            log.debug("律师余额行并发创建 lawyerId={}", lawyerId);
        }
        return selectForUpdate(lawyerId);
    }

    /**
     * 按行锁拿到的旧行更新余额（带 `@Version` 乐观锁）。
     *
     * @param locked          {@link #lock(Long)} 返回的行
     * @param availableCents  新可用余额（分），null 表示不改
     * @param frozenCents     新冻结额（分），null 表示不改
     * @param totalIncomeCents 新累计收入（分），null 表示不改
     * @throws BusinessException 500000 乐观锁冲突（同一行被并发改过）
     */
    public void apply(LawyerBalance locked, Long availableCents, Long frozenCents, Long totalIncomeCents) {
        if (locked == null) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "律师余额行不存在");
        }
        LawyerBalance patch = new LawyerBalance();
        patch.setId(locked.getId());
        patch.setVersion(locked.getVersion());
        if (availableCents != null) {
            patch.setAvailableCents(availableCents);
        }
        if (frozenCents != null) {
            patch.setFrozenCents(frozenCents);
        }
        if (totalIncomeCents != null) {
            patch.setTotalIncomeCents(totalIncomeCents);
        }
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (lawyerBalanceMapper.updateById(patch) == 0) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL, "律师余额更新失败（乐观锁冲突）");
        }
    }

    /**
     * 取律师余额行并加行锁。
     *
     * @param lawyerId 律师主键
     * @return 余额行；不存在返回 null
     */
    private LawyerBalance selectForUpdate(Long lawyerId) {
        return lawyerBalanceMapper.selectOne(Wrappers.<LawyerBalance>lambdaQuery()
                .eq(LawyerBalance::getLawyerId, lawyerId)
                .last("FOR UPDATE"));
    }
}
