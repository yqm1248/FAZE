/*
 * 职责：充值单的两类定时任务业务逻辑（第 6 段 D1 掉单补单 / D2 超时关单）。
 * 调用方：modules/trade/task/RechargeTaskScanner（@Scheduled 扫描器）。
 * 数据来源：recharge_record（查单/CAS）、PaymentGatewayFactory（查渠道）、RechargeSettleService（补入账）。
 * 注意：
 *   1. **关单前必须先查渠道**（D2 第 2 条）：用户可能在最后一秒付了款，只按时间关单会造成
 *      「渠道有钱、本地关闭」的掉单。查到已付就不关，直接走补入账。
 *   2. **补单与关单共用同一份入账代码**（D1 第 3 条 ④「不复制」）：都调 RechargeSettleService。
 *   3. **「查不到」不抛异常**（D1「卡住怎么办」）：抛异常会让任务快速进死信；这里按
 *      「本次没查到，交给任务表退避重试」处理——调用方 markFailed 时会按 2^n 退避，5 次后死信。
 *   4. 自动补单/自动关单**不写 operation_log**（不是人工操作），只写应用日志；
 *      人工触发的那条在管理端接口里另外标审计（D1 的人工补单接口留到 E 组，见 §十一）。
 *   5. 关单**不产生任何流水**。
 */
package com.faze.modules.trade.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.infra.pay.model.QueryResult;
import com.faze.modules.trade.entity.RechargeRecord;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 充值定时任务业务逻辑。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeTaskService {

    /** 任务类型：待支付单主动查询补单（D1）。 */
    public static final String TASK_PENDING_QUERY = "recharge_pending_query";

    /** 任务类型：超时关单（D2）。 */
    public static final String TASK_CLOSE = "recharge_close";

    private final RechargeRecordMapper rechargeRecordMapper;

    private final PaymentGatewayFactory paymentGatewayFactory;

    private final RechargeSettleService rechargeSettleService;

    /**
     * 处理一条「待支付单主动查询」任务（D1）。
     *
     * <p>副作用：可能补入账、置 failed、改单状态。</p>
     *
     * @param orderNo 平台充值单号
     * @throws IllegalStateException 本次查不到（交给任务表退避重试）
     */
    public void handlePendingQuery(String orderNo) {
        RechargeRecord record = findRecord(orderNo);
        if (record == null) {
            log.warn("补单任务：充值单不存在，任务结束 orderNo={}", orderNo);
            return;
        }
        if (!RechargeStatus.PENDING.getCode().equals(record.getStatus())) {
            // 已被回调处理，任务自然结束
            return;
        }
        QueryResult result = queryChannel(record);
        if (result == null || !result.isExists()) {
            // 渠道还没这笔单：本次不处理，靠任务表退避重试（5 次后死信）
            throw new IllegalStateException("渠道查不到充值单 " + orderNo);
        }
        if (result.getStatus() == RechargeStatus.SUCCESS) {
            RechargeSettleService.Outcome outcome = rechargeSettleService.settleByCallback(orderNo,
                    result.getChannelTxnId(),
                    record.getAmountCents() == null ? 0L : record.getAmountCents(),
                    result.getPaidAt(), null);
            log.info("补单任务：查到渠道已收款并补入账 orderNo={} outcome={}", orderNo, outcome);
            return;
        }
        if (result.getStatus() == RechargeStatus.FAILED) {
            int affected = rechargeRecordMapper.casTransit(record.getId(),
                    RechargeStatus.PENDING.getCode(), RechargeStatus.FAILED.getCode(),
                    null, null, null, LocalDateTime.now(ZoneOffset.UTC));
            log.info("补单任务：渠道明确失败，单据置 failed orderNo={} affected={}", orderNo, affected);
            return;
        }
        // 渠道仍待支付：交给退避重试
        throw new IllegalStateException("渠道仍待支付 " + orderNo);
    }

    /**
     * 处理一条「超时关单」任务（D2）。
     *
     * <p>副作用：可能补入账（查到已付）或把单置 closed（确认未付）。</p>
     *
     * @param orderNo 平台充值单号
     */
    public void handleClose(String orderNo) {
        RechargeRecord record = findRecord(orderNo);
        if (record == null) {
            log.warn("关单任务：充值单不存在，任务结束 orderNo={}", orderNo);
            return;
        }
        if (!RechargeStatus.PENDING.getCode().equals(record.getStatus())) {
            return;
        }
        // 关单前先查渠道：最后一秒付款的情况必须救回来
        QueryResult result = queryChannel(record);
        if (result != null && result.isExists() && result.getStatus() == RechargeStatus.SUCCESS) {
            RechargeSettleService.Outcome outcome = rechargeSettleService.settleByCallback(orderNo,
                    result.getChannelTxnId(),
                    record.getAmountCents() == null ? 0L : record.getAmountCents(),
                    result.getPaidAt(), null);
            log.warn("关单任务：查到渠道已付款，改为补入账而不是关单 orderNo={} outcome={}", orderNo, outcome);
            return;
        }
        int affected = rechargeRecordMapper.casTransit(record.getId(),
                RechargeStatus.PENDING.getCode(), RechargeStatus.CLOSED.getCode(),
                null, null, null, LocalDateTime.now(ZoneOffset.UTC));
        log.info("关单任务：确认未付款，充值单已关闭 orderNo={} affected={}", orderNo, affected);
    }

    /**
     * 查渠道（异常不外抛：查单失败按「没查到」处理，交给重试）。
     *
     * @param record 充值单
     * @return 查询结果；异常返回 null
     */
    private QueryResult queryChannel(RechargeRecord record) {
        try {
            PayChannel channel = PayChannel.of(record.getChannel());
            return paymentGatewayFactory.get(channel).query(record.getOrderNo());
        } catch (RuntimeException e) {
            log.warn("充值单查渠道失败 orderNo={}", record.getOrderNo(), e);
            return null;
        }
    }

    /**
     * 按单号查单。
     *
     * @param orderNo 单号
     * @return 充值单；不存在返回 null
     */
    private RechargeRecord findRecord(String orderNo) {
        return rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo));
    }
}
