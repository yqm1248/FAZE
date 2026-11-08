/*
 * 职责：渠道预下单结果（PaymentGateway#prepay 的返回）。
 * 调用方：RechargeService（写 channel_prepay_id、把 payParams 回给 App）。
 * 数据来源：各网关实现（Mock / 微信 / 支付宝）。
 * 注意：
 *   1. `payParams` 是**给客户端拉起渠道用的参数**（微信是时间戳/nonceStr/package/signType/paySign，
 *      支付宝是 orderStr）。这些是渠道公开参数，**不是密钥**，可以下发给 App。
 *   2. `prepayId` 要落库到 recharge_record.channel_prepay_id：回调里渠道可能只给
 *      transaction_id，对不上时用 prepay_id 反查（A1 第 2 条）。
 *   3. **不要在这里拼日志**：payParams 里有 sign，日志打全等于把签名材料交出去。
 */
package com.faze.infra.pay.model;

import com.faze.modules.trade.enums.PayChannel;
import java.util.Map;
import lombok.Data;

/**
 * 渠道预下单结果。
 */
@Data
public class PrepayResult {

    /** 渠道。 */
    private PayChannel channel;

    /** 渠道预支付单号：微信 prepay_id / 支付宝 trade_no；Mock 为自造号。 */
    private String prepayId;

    /** 给 App 拉起渠道支付的参数（渠道公开参数，不含密钥）。 */
    private Map<String, String> payParams;
}
