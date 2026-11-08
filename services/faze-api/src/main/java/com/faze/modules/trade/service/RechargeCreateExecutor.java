/*
 * 职责：充值下单的事务执行器——生成单号、落 pending 单、回写预支付单号、置失败（第 6 段 B1）。
 * 调用方：modules/trade/service/RechargeOrderService#create（编排层，负责校验、幂等与渠道调用）。
 * 数据来源：infra/seq/BizNoGenerator（RC 单号）+ recharge_record（写）。
 * 注意：
 *   1. **事务里绝不调渠道 prepay()**：《后端代码规范》§7.3 明确禁止在事务内做远程调用——
 *      渠道响应慢会把数据库连接和行锁一起拖住（充值高峰最容易踩）。所以拆成三步：
 *      落单（本类，事务）→ prepay（编排层，事务外）→ 回写 prepay_id（本类，事务）。
 *   2. **落单时 `channel_txn_id` 必须为 NULL**：它由回调/主动查询回填；pending 期间填了，
 *      晚到回调的补偿单就会撞 `uk_recharge_channel_txn`（工作流 A2 第 4 条）。
 *   3. **单号撞唯一键要重试换号**：Redis 被清空/重启后序号会从 1 重来，真实运维场景会撞上
 *      历史单号；静默 500 不如换号重试（与段 2 OrderCreateExecutor 同一处理方式）。
 *   4. `markFailed` 走状态机校验 + CAS：只有 pending 能变 failed，已成功的单不许被覆盖。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.seq.BizNoGenerator;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import com.faze.modules.trade.rule.RechargeStateMachine;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 充值下单事务执行器。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeCreateExecutor {

    /** 单号撞唯一键时的最大尝试次数。 */
    private static final int MAX_ORDER_NO_ATTEMPT = 3;

    private final RechargeRecordMapper rechargeRecordMapper;

    private final BizNoGenerator bizNoGenerator;

    /**
     * 落一张 pending 充值单。
     *
     * <p>前置：金额、渠道、限额、实名均已由调用方校验通过。</p>
     * <p>副作用：插入一行 recharge_record（status=pending，channel_txn_id 为 NULL）。</p>
     *
     * @param userId      下单用户
     * @param amountCents 充值金额（分）
     * @param channel     支付渠道
     * @param expireAt    支付截止时间（UTC）
     * @return 已带自增主键与单号的充值单
     * @throws BusinessException 500000 单号连续冲突（连续 3 次都撞，说明号段异常）
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeRecord createPending(Long userId, long amountCents, PayChannel channel,
                                        LocalDateTime expireAt) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int attempt = 1; attempt <= MAX_ORDER_NO_ATTEMPT; attempt++) {
            RechargeRecord record = new RechargeRecord();
            record.setUserId(userId);
            record.setOrderNo(bizNoGenerator.nextRechargeNo());
            record.setChannel(channel.getCode());
            // channel_txn_id 留 NULL，见类注释第 2 条
            record.setChannelTxnId(null);
            record.setChannelPrepayId(null);
            record.setAmountCents(amountCents);
            record.setStatus(RechargeStatus.PENDING.getCode());
            record.setPaidAt(null);
            record.setExpireAt(expireAt);
            record.setRawPayload(null);
            record.setCreatedAt(now);
            record.setUpdatedAt(now);
            try {
                rechargeRecordMapper.insert(record);
                log.info("充值单落库 userId={} orderNo={} amount={} channel={}",
                        userId, record.getOrderNo(), amountCents, channel.getCode());
                return record;
            } catch (DuplicateKeyException e) {
                log.warn("充值单号冲突，换号重试 attempt={} orderNo={}", attempt, record.getOrderNo());
            }
        }
        throw new BusinessException(ErrorCode.COMMON_INTERNAL, "充值单号生成失败");
    }

    /**
     * 回写渠道预支付单号（prepay 成功后，事务外调用本方法）。
     *
     * <p>只更新仍为 pending 的单：若这期间回调已经把单置成功，回写 prepay_id 已无意义，
     * 影响 0 行是正常结果，不算失败。</p>
     *
     * @param recordId 充值单主键
     * @param prepayId 渠道预支付单号
     * @return 实际更新行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int attachPrepayId(Long recordId, String prepayId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return rechargeRecordMapper.update(null, Wrappers.<RechargeRecord>lambdaUpdate()
                .eq(RechargeRecord::getId, recordId)
                .eq(RechargeRecord::getStatus, RechargeStatus.PENDING.getCode())
                .set(RechargeRecord::getChannelPrepayId, prepayId)
                .set(RechargeRecord::getUpdatedAt, now));
    }

    /**
     * 渠道下单失败时把单置 failed（只置状态，不入账）。
     *
     * <p>先过状态机再 CAS：并发下若回调已经把单置成功，CAS 影响 0 行——此时**不能**把
     * 已成功的单改成失败，只能记 warn 让运维按单号查。</p>
     *
     * @param recordId 充值单主键
     * @param reason   失败原因（只进日志，**不写库**：工作流 A2「卡住怎么办」明确不建 fail_reason 字段）
     */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long recordId, String reason) {
        RechargeStateMachine.check(RechargeStatus.PENDING, RechargeStatus.FAILED);
        int affected = rechargeRecordMapper.casTransit(recordId,
                RechargeStatus.PENDING.getCode(), RechargeStatus.FAILED.getCode(),
                null, null, null, LocalDateTime.now(ZoneOffset.UTC));
        if (affected == 0) {
            log.warn("充值单置失败时 CAS 未命中（可能已被回调置成功）recordId={} reason={}",
                    recordId, reason);
            return;
        }
        log.warn("充值单渠道下单失败 recordId={} reason={}", recordId, reason);
    }
}
