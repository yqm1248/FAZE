/*
 * 职责：支付回调的统一处理链（第 6 段 C1/C2/C3）——验签解析 → 业务判定 → 入账 → 按渠道格式应答。
 * 调用方：modules/trade/controller/PayCallbackController（三个回调路径共用）。
 * 数据来源：infra/pay 的网关（parseCallback）+ RechargeSettleService（入账）。
 * 注意：
 *   1. **应答体绝不能是统一的 `R<T>`**：微信要 `{"code":"SUCCESS"}`、支付宝要纯文本 `success`、
 *      Mock 用简化 JSON。返回平台统一响应体会让渠道一直重试（C1 第 6 条）。
 *   2. **入账失败必须回 500**，让渠道重试——宁可让渠道重试，也不要吞掉（C3 第 5 条）。
 *      而「已入过账」「金额不符」这类**确定性结果回 200**：重试也不会有不同结果。
 *   3. **验签失败回 4xx**：这是安全事件，不是「请重试」。同时写日志留痕。
 *   4. 报文大小上限 64KB（C1 第 2 条），超限回 413，不解析、不落库。
 *   5. 本类**不做 Redis 去重**：渠道回调天然重放，去重靠 DB 两把唯一键 + CAS（C4 第 2 条）。
 */
package com.faze.modules.trade.service;

import com.faze.common.audit.AuditContext;
import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.infra.pay.PaymentGatewayFactory;
import com.faze.infra.pay.model.CallbackResult;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import com.faze.modules.trade.mapper.RechargeRecordMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.faze.modules.trade.entity.RechargeRecord;

/**
 * 支付回调处理服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RechargeCallbackService {

    /** 报文大小上限（字节）：64KB。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final PaymentGatewayFactory paymentGatewayFactory;

    private final RechargeSettleService rechargeSettleService;

    private final RechargeRecordMapper rechargeRecordMapper;

    /**
     * 回调应答。
     *
     * @param httpStatus HTTP 状态码（渠道按非 2xx 重试）
     * @param contentType 响应 Content-Type
     * @param body       响应体（**渠道要求的格式**，不是 R）
     */
    public record Reply(int httpStatus, String contentType, String body) {
    }

    /**
     * 处理一次渠道回调。
     *
     * <p>副作用：可能入账、改单状态、投递事件、写审计。</p>
     *
     * @param channel 渠道
     * @param rawBody 原始报文（不落日志）
     * @param headers 请求头
     * @return 按渠道格式的应答
     */
    public Reply handle(PayChannel channel, String rawBody, Map<String, String> headers) {
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            log.warn("回调报文超过 64KB，拒绝 channel={}", channel.getCode());
            return failure(channel, 413, "报文过大");
        }
        CallbackResult callback;
        try {
            callback = paymentGatewayFactory.get(channel).parseCallback(rawBody, headers);
        } catch (BusinessException e) {
            // 验签失败等：4xx，并留痕（安全事件）
            log.error("回调验签/解析失败 channel={} code={}", channel.getCode(), e.getErrorCode().getCode());
            AuditContext.mark("trade", "RECHARGE_CALLBACK_REJECT", "PAY_CALLBACK", channel.getCode(),
                    "{\"reason\":\"sign-or-parse-failed\"}");
            return failure(channel, 400, "验签失败");
        }
        if (callback == null || callback.getOrderNo() == null) {
            log.warn("回调解析结果缺少单号 channel={}", channel.getCode());
            return failure(channel, 400, "缺少单号");
        }
        if (!callback.isSuccess()) {
            // 渠道明确失败：只置状态、不入账
            markFailed(callback.getOrderNo(), callback.getSanitizedPayload());
            AuditContext.mark("trade", "RECHARGE_CALLBACK_FAILED", "RECHARGE_RECORD", callback.getOrderNo(),
                    "{\"channel\":\"" + channel.getCode() + "\"}");
            return success(channel);
        }
        RechargeSettleService.Outcome outcome;
        try {
            outcome = rechargeSettleService.settleByCallback(callback.getOrderNo(),
                    callback.getChannelTxnId(), callback.getAmountCents(),
                    callback.getPaidAt(), callback.getSanitizedPayload());
        } catch (RuntimeException e) {
            // 入账异常（DB 等）：回 500 让渠道重试
            log.error("回调入账异常 orderNo={} channel={}", callback.getOrderNo(), channel.getCode(), e);
            return failure(channel, 500, "入账失败");
        }
        log.info("回调处理完成 channel={} orderNo={} outcome={}", channel.getCode(), callback.getOrderNo(), outcome);
        // 确定性结果（含金额不符）都回成功：重试不会有不同结果，回失败只会让渠道一直重发
        return success(channel);
    }

    /**
     * 把单置 failed（只改状态，不入账）。
     *
     * @param orderNo      单号
     * @param rawPayload   脱敏报文
     */
    private void markFailed(String orderNo, String rawPayload) {
        RechargeRecord record = rechargeRecordMapper.selectOne(Wrappers.<RechargeRecord>lambdaQuery()
                .eq(RechargeRecord::getOrderNo, orderNo));
        if (record == null) {
            log.warn("失败回调的单不存在 orderNo={}", orderNo);
            return;
        }
        if (!RechargeStatus.PENDING.getCode().equals(record.getStatus())) {
            return;
        }
        rechargeRecordMapper.casTransit(record.getId(),
                RechargeStatus.PENDING.getCode(), RechargeStatus.FAILED.getCode(),
                null, null, rawPayload, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 构造「成功」应答（按渠道格式）。
     *
     * @param channel 渠道
     * @return 应答
     */
    private Reply success(PayChannel channel) {
        return switch (channel) {
            case WECHAT -> new Reply(200, "application/json",
                    "{\"code\":\"SUCCESS\",\"message\":\"成功\"}");
            case ALIPAY -> new Reply(200, "text/plain", "success");
            case MOCK -> new Reply(200, "application/json", "{\"code\":\"SUCCESS\"}");
        };
    }

    /**
     * 构造「失败」应答（按渠道格式）。
     *
     * @param channel 渠道
     * @param status  HTTP 状态
     * @param message 失败原因（**不含内部细节**）
     * @return 应答
     */
    private Reply failure(PayChannel channel, int status, String message) {
        return switch (channel) {
            case WECHAT -> new Reply(status, "application/json",
                    "{\"code\":\"FAIL\",\"message\":\"" + message + "\"}");
            case ALIPAY -> new Reply(status, "text/plain", "fail");
            case MOCK -> new Reply(status, "application/json",
                    "{\"code\":\"FAIL\",\"message\":\"" + message + "\"}");
        };
    }
}
