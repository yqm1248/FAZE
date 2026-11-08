/*
 * 职责：支付渠道网关抽象（微信 / 支付宝 / Mock 三个实现）。
 * 调用方：modules/trade 的 RechargeService（下单）、C 组回调（解析）、D 组补单（查询）。
 * 数据来源：《技术路线 v2.3》§9.1 原文签名（段 6 未改签名，只把 Order 换成 RechargeOrder 视图）。
 * 注意：
 *   1. **本接口只服务充值**：段 2 的订单支付走余额抵扣（channel='balance'），不经过渠道。
 *   2. `prepay()` 的 `order` 参数是 **RechargeOrder 视图**，不是 RechargeRecord 实体
 *      （技术路线 §9.1 v1.32 补充）。
 *   3. `refund()` 在段 6 **只实现「重复支付自动原路退回」一个用途**，
 *      不做用户主动退款（退款走段 2 的余额体系，钱没出过平台）。
 *   4. 实现类由 PaymentGatewayFactory 按 sys_config 开关选择；**禁止业务代码直接
 *      new / @Autowired 具体实现**——那样「渠道未启用」的检查会被绕过。
 */
package com.faze.infra.pay;

import com.faze.infra.pay.model.CallbackResult;
import com.faze.infra.pay.model.PrepayResult;
import com.faze.infra.pay.model.QueryResult;
import com.faze.infra.pay.model.RechargeOrder;
import com.faze.infra.pay.model.RefundRequest;
import com.faze.infra.pay.model.RefundResult;
import com.faze.modules.trade.enums.PayChannel;
import java.util.Map;

/**
 * 支付渠道网关。
 */
public interface PaymentGateway {

    /**
     * 渠道预下单。
     *
     * <p>前置：渠道已启用（由 Factory 保证）、金额已过校验。</p>
     * <p>副作用：在渠道侧创建一笔待支付交易，返回拉起支付所需参数。</p>
     *
     * @param order   充值单视图（单号 / 金额 / 过期时间）
     * @param channel 支付渠道
     * @return 渠道预支付单号 + 客户端拉起参数
     */
    PrepayResult prepay(RechargeOrder order, PayChannel channel);

    /**
     * 解析并验签渠道异步通知。
     *
     * <p>**验签失败必须抛 405017**，不许返回 success=false 混过去——
     * 验签失败是安全事件，混成业务失败等于把攻击当业务。</p>
     *
     * @param rawBody 原始报文（不落日志）
     * @param headers 请求头（渠道签名在头里）
     * @return 解析结果（含脱敏后报文）
     */
    CallbackResult parseCallback(String rawBody, Map<String, String> headers);

    /**
     * 渠道退款（段 6 只用于重复支付的原路退回）。
     *
     * @param request 退款请求（全额）
     * @return 退款受理结果
     */
    RefundResult refund(RefundRequest request);

    /**
     * 主动查单（掉单补单与回调兜底用）。
     *
     * <p>渠道查不到单是正常结果，返回 {@code exists=false}，不要抛异常。</p>
     *
     * @param orderNo 平台充值单号
     * @return 渠道侧状态
     */
    QueryResult query(String orderNo);

    /**
     * 本实现对应的渠道。
     *
     * @return 渠道枚举
     */
    PayChannel channel();
}
