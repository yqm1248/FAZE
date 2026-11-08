/*
 * 职责：充值记录列表的一行。
 * 调用方：modules/trade/controller/RechargeController#list → App「充值记录」。
 * 数据来源：recharge_record（**只查这张表**，不 join balance_flow）。
 * 注意：
 *   1. **本行是「充值单」视图，不是资金流水**：钱的真相仍是 `GET /app/wallet/flows`。
 *      两者不合并——流水里还有消费、退款、手工充值，语义不同（B1 第 3 条）。
 *   2. 状态给 `status` + `statusKey` 两份，理由同 {@link RechargeDetailVO}。
 *   3. `amountCents` 用原生 long（金额给数字），单号是 String（对外标识）。
 */
package com.faze.modules.trade.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 充值记录行。
 */
@Data
public class RechargeRowVO {

    /**
     * 充值单内部主键。
     *
     * <p>**这是游标**：客户端把本页最后一条的 id 原样回传给 `cursor` 才能翻下一页。
     * 不拿单号当游标，是因为单号规则将来可能变（前缀/位数），而主键不变。
     * 全局 Jackson 会把包装类 Long 输出成字符串，JS 不会丢精度。</p>
     */
    private Long id;

    /** 平台充值单号。 */
    private String orderNo;

    /** 充值金额（分）。 */
    private long amountCents;

    /** 支付渠道持久化值。 */
    private String channel;

    /** 渠道名 i18n key。 */
    private String channelKey;

    /** 状态持久化值。 */
    private String status;

    /** 状态名 i18n key。 */
    private String statusKey;

    /** 下单时间（UTC）。 */
    private LocalDateTime createdAt;

    /** 渠道支付成功时间（UTC）；未成功为 null。 */
    private LocalDateTime paidAt;
}
