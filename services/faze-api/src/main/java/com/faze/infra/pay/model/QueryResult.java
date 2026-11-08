/*
 * 职责：渠道主动查询结果（PaymentGateway#query 的返回）。
 * 调用方：D 组掉单补单（人工 sync 与定时 pending 扫描）、C 组回调兜底。
 * 数据来源：各网关实现调渠道查单接口（Mock 直接返回内存状态）。
 * 注意：
 *   1. **查询结果不能直接改本地状态**：必须先过 RechargeStateMachine 再 CAS，
 *      否则「渠道说成功」就无条件改，会绕过「晚到回调走补偿单」的约束。
 *   2. 渠道查不到单时返回 `status=null` + `exists=false`，不要抛异常——
 *      「查不到」是正常结果（用户根本没付），不是故障。
 */
package com.faze.infra.pay.model;

import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 渠道查单结果。
 */
@Data
public class QueryResult {

    /** 渠道。 */
    private PayChannel channel;

    /** 平台充值单号。 */
    private String orderNo;

    /** 渠道是否查到了这笔单。 */
    private boolean exists;

    /** 渠道侧状态映射到本地状态；exists=false 时为 null。 */
    private RechargeStatus status;

    /** 渠道交易号。 */
    private String channelTxnId;

    /** 渠道实付金额（分）。 */
    private Long amountCents;

    /** 渠道支付成功时间（UTC）。 */
    private LocalDateTime paidAt;
}
