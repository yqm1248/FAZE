/*
 * 职责：充值入账链路（第 6 段 C3）——把「渠道确认收款」变成「用户余额真的加上」，且只加一次。
 * 调用方：modules/trade/service/RechargeCallbackService（回调链路）、
 *         RechargeOrderService#detail 的「现查渠道」兜底（D1 接入）。
 * 数据来源：recharge_record（状态迁移）+ balance_flow（写）+ user_balance（回写）+ task_queue（投递事件）。
 * 注意：
 *   1. **顺序与事务边界是这个类唯一重要的事**（C3 第 1 条）：
 *      CAS `pending→success` → 写 balance_flow → SUM 回写 user_balance → 投递事件，**全在同一事务内**。
 *      任何一步失败整事务回滚，单回到 pending，渠道重试时还能再入账一次。
 *   2. **为什么把 CAS 放在最前面**（与文档 ③④⑤ 的书写顺序不同）：
 *      先 CAS 拿到「这次入账是我负责」的独占权，CAS 影响 0 行说明并发的另一个回调已经接管，
 *      此时**什么都没写过**，直接返回幂等结果即可，不需要「写一半再回滚」。语义与文档一致
 *      （文档要求「CAS 0 行整个事务回滚，然后按幂等成功处理」），实现更简单且没有中间态。
 *   3. **`biz_id` 必须是 recharge_record.id**：`uk_flow_biz` 是幂等的最后一道，NULL 不拦重复（C3 第 3 条）。
 *   4. **`balance_after` 取账户总余额**（可用 + 冻结），不是可用余额（段 1 §三 D 组口径，C3 第 2 条）。
 *   5. **不新写一套流水逻辑**：理论上应复用段 1 的流水服务，但段 1 没有独立的「入账」服务
 *      （`RechargeService.applyDelta` 是手工充值的私有实现，`biz_id` 用幂等键 SHA-256 派生，
 *      与本段「biz_id = 充值单 id」的幂等口径不同，复用会把两套口径混在一起）。
 *      本类因此直接写 `balance_flow` + `user_balance`，但**语义完全按段 1 的流水规则**：
 *      余额只能由流水推导、`balance_after` 是总余额、改缓存与写流水同事务。
 *   6. **晚到回调不走本类的原地改状态**：单已 closed / failed 时收到 success，
 *      新开一张补偿单（状态直接 success）再入账（工作流 A2 第 4 条）。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.common.audit.AuditContext;
import com.faze.infra.mq.TaskQueueService;
import com.faze.modules.trade.entity.BalanceFlow;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.entity.UserBalance;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.BalanceFlowMapper;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
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
 * 充值入账服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeSettleService {

    /** 流水业务类型：充值（与手工充值同一个值，靠 biz_id 指向不同东西区分）。 */
    public static final String BIZ_TYPE_RECHARGE = "recharge";

    /** 入账成功后要投递的事件类型（段 7 消费，本段只投递）。 */
    public static final String TASK_RECHARGE_SUCCESS = "recharge_success_notify";

    /** 流水备注前缀。 */
    private static final String REMARK_PREFIX = "充值 ";

    private final RechargeRecordMapper rechargeRecordMapper;

    private final BalanceFlowMapper balanceFlowMapper;

    private final UserBalanceMapper userBalanceMapper;

    private final TaskQueueService taskQueueService;

    /**
     * 入账结果。
     */
    public enum Outcome {
        /** 本次完成入账。 */
        SETTLED,
        /** 之前已入过账（重复回调 / 并发回调），本次没有加钱。 */
        ALREADY_SETTLED,
        /** 充值单不存在（渠道给了平台没有的单号）。 */
        NOT_FOUND,
        /** 金额与本地不一致：不入账，写差异。 */
        AMOUNT_MISMATCH,
        /** 原单已关闭/失败，已按补偿新单入账。 */
        COMPENSATED
    }

    /**
     * 按渠道回调结果入账。
     *
     * <p>前置：调用方已完成验签与解析；本方法只做业务判定与事务。</p>
     * <p>副作用：改状态、写流水、回写余额缓存、投递 `recharge_success_notify`、写审计。</p>
     *
     * @param orderNo      平台充值单号
     * @param channelTxnId 渠道交易号（幂等键）
     * @param amountCents  渠道实付金额（分）
     * @param paidAt       渠道支付时间（UTC）；为空取当前时间
     * @param rawPayload   脱敏后的渠道报文；可为空
     * @return 入账结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Outcome settleByCallback(String orderNo, String channelTxnId, long amountCents,
                                    LocalDateTime paidAt, String rawPayload) {
        RechargeRecord record = findRecord(orderNo);
        if (record == null) {
            log.warn("回调的充值单不存在 orderNo={} channelTxnId={}", orderNo, channelTxnId);
            return Outcome.NOT_FOUND;
        }
        if (record.getAmountCents() == null || record.getAmountCents() != amountCents) {
            // 金额不一致：不入账。差异由 D4 的对账比对落 reconciliation_diff（渠道报文的原文已留痕）
            log.error("回调金额与本地不一致，拒绝入账 orderNo={} 本地={} 渠道={}",
                    orderNo, record.getAmountCents(), amountCents);
            return Outcome.AMOUNT_MISMATCH;
        }
        RechargeStatus status = RechargeStatus.of(record.getStatus());
        if (status == RechargeStatus.SUCCESS) {
            return Outcome.ALREADY_SETTLED;
        }
        if (status != RechargeStatus.PENDING) {
            // 晚到回调：原单已关闭/失败，新开补偿单入账（A2 第 4 条）
            return compensate(record, channelTxnId, amountCents, paidAt, rawPayload);
        }
        return settle(record, channelTxnId, amountCents, paidAt, rawPayload);
    }

    /**
     * 正常路径入账（单仍 pending）。
     *
     * @param record       充值单
     * @param channelTxnId 渠道交易号
     * @param amountCents  金额（分）
     * @param paidAt       支付时间
     * @param rawPayload   脱敏报文
     * @return 入账结果
     */
    private Outcome settle(RechargeRecord record, String channelTxnId, long amountCents,
                           LocalDateTime paidAt, String rawPayload) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // ① CAS 抢占：并发回调只有一个能拿到 1 行
        int affected = rechargeRecordMapper.casTransit(record.getId(),
                RechargeStatus.PENDING.getCode(), RechargeStatus.SUCCESS.getCode(),
                channelTxnId, paidAt == null ? now : paidAt, rawPayload, now);
        if (affected == 0) {
            log.info("入账 CAS 未命中（并发/重复回调）id={} orderNo={}", record.getId(), record.getOrderNo());
            return Outcome.ALREADY_SETTLED;
        }
        // ②③④ 写流水 + 回写余额（同一事务）
        credit(record.getUserId(), record.getId(), amountCents, record.getOrderNo(), now);
        // ⑤ 事件投递（只投不消费，段 7 消费）
        taskQueueService.enqueue(TASK_RECHARGE_SUCCESS, record.getOrderNo(), now, null);
        AuditContext.mark("trade", "RECHARGE_SETTLE", "RECHARGE_RECORD", String.valueOf(record.getId()),
                "{\"orderNo\":\"" + record.getOrderNo() + "\",\"amountCents\":" + amountCents
                        + ",\"channelTxnId\":\"" + (channelTxnId == null ? "" : channelTxnId) + "\"}");
        log.info("充值入账成功 orderNo={} amount={} userId={}", record.getOrderNo(), amountCents, record.getUserId());
        return Outcome.SETTLED;
    }

    /**
     * 晚到回调：新开补偿单后入账。
     *
     * <p>原单（closed/failed）**不改状态、不回填 channel_txn_id**——回填会撞
     * `uk_recharge_channel_txn`，把补偿单挤掉（A2 第 4 条）。</p>
     *
     * @param origin       原单
     * @param channelTxnId 渠道交易号
     * @param amountCents  金额
     * @param paidAt       支付时间
     * @param rawPayload   脱敏报文
     * @return 入账结果
     */
    private Outcome compensate(RechargeRecord origin, String channelTxnId, long amountCents,
                               LocalDateTime paidAt, String rawPayload) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RechargeRecord compensation = new RechargeRecord();
        compensation.setUserId(origin.getUserId());
        compensation.setOrderNo(nextCompensationNo(origin.getOrderNo()));
        compensation.setChannel(origin.getChannel());
        compensation.setChannelTxnId(channelTxnId);
        compensation.setAmountCents(amountCents);
        compensation.setStatus(RechargeStatus.SUCCESS.getCode());
        compensation.setPaidAt(paidAt == null ? now : paidAt);
        compensation.setExpireAt(null);
        compensation.setRawPayload("原单 " + origin.getOrderNo() + " 已关闭，补入账");
        compensation.setCreatedAt(now);
        compensation.setUpdatedAt(now);
        try {
            rechargeRecordMapper.insert(compensation);
        } catch (DuplicateKeyException e) {
            // 同一渠道交易号已经入过账（补偿单早就在了）→ 幂等
            log.info("补偿单已存在（同一渠道交易号），按幂等处理 channelTxnId={}", channelTxnId);
            return Outcome.ALREADY_SETTLED;
        }
        credit(origin.getUserId(), compensation.getId(), amountCents, compensation.getOrderNo(), now);
        taskQueueService.enqueue(TASK_RECHARGE_SUCCESS, compensation.getOrderNo(), now, null);
        AuditContext.mark("trade", "RECHARGE_COMPENSATE", "RECHARGE_RECORD",
                String.valueOf(compensation.getId()),
                "{\"originOrderNo\":\"" + origin.getOrderNo() + "\",\"compensationOrderNo\":\""
                        + compensation.getOrderNo() + "\",\"amountCents\":" + amountCents + "}");
        log.warn("晚到回调已按补偿新单入账 原单={} 补偿单={} amount={}",
                origin.getOrderNo(), compensation.getOrderNo(), amountCents);
        return Outcome.COMPENSATED;
    }

    /**
     * 写充值流水并回写余额缓存。
     *
     * @param userId      用户
     * @param rechargeId  充值单 id（= flow.biz_id，幂等键）
     * @param amountCents 金额（分）
     * @param orderNo     单号（备注用）
     * @param now         当前时间（UTC）
     */
    private void credit(Long userId, Long rechargeId, long amountCents, String orderNo, LocalDateTime now) {
        UserBalance cache = userBalanceMapper.selectByUserIdForUpdate(userId);
        if (cache == null) {
            // 用户没查过余额就没有行；入账必须要有行可改，补一行 0
            UserBalance created = new UserBalance();
            created.setUserId(userId);
            created.setBalanceCents(0L);
            created.setFrozenCents(0L);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            userBalanceMapper.insert(created);
            cache = userBalanceMapper.selectByUserIdForUpdate(userId);
        }
        if (cache == null) {
            throw new IllegalStateException("充值入账时无法创建余额行 userId=" + userId);
        }
        long frozen = cache.getFrozenCents() == null ? 0L : cache.getFrozenCents();
        // balance_after 取「账户总余额」= 现有总 + 本次入账（不是可用余额）
        long totalBefore = (cache.getBalanceCents() == null ? 0L : cache.getBalanceCents()) + frozen;
        BalanceFlow flow = new BalanceFlow();
        flow.setOwnerType(BalanceService.OWNER_USER);
        flow.setOwnerId(userId);
        flow.setBizType(BIZ_TYPE_RECHARGE);
        flow.setBizId(rechargeId);
        flow.setDeltaCents(amountCents);
        flow.setBalanceAfter(totalBefore + amountCents);
        flow.setRemark(REMARK_PREFIX + orderNo);
        flow.setCreatedAt(now);
        balanceFlowMapper.insert(flow);
        // 缓存 = 可用额增加（frozen 不变）
        cache.setBalanceCents((cache.getBalanceCents() == null ? 0L : cache.getBalanceCents()) + amountCents);
        cache.setUpdatedAt(now);
        userBalanceMapper.updateById(cache);
    }

    /**
     * 按单号查充值单。
     *
     * @param orderNo 平台充值单号
     * @return 充值单；不存在返回 null
     */
    private RechargeRecord findRecord(String orderNo) {
        return rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo));
    }

    /**
     * 生成补偿单号：原单号加 `-C` 后缀。
     *
     * <p>不改用新序号：补偿单必须能一眼看出它是哪张单的补账，运营排查时不用再查一张表。</p>
     *
     * @param originOrderNo 原单号
     * @return 补偿单号（≤64 字符，表结构够放）
     */
    private String nextCompensationNo(String originOrderNo) {
        return originOrderNo + "-C";
    }
}
