/*
 * 职责：支付宝支付网关——代码写完、单测用桩，**联调等商户号下发**。
 * 调用方：PaymentGatewayFactory（`pay.alipay.enabled=true` 时选中）。
 * 数据来源：sys_config 的 `pay.alipay.*`（private_key 加密）；公钥非敏感可明文。
 * 注意：
 *   1. 支付宝回调是**表单编码**（不是 JSON），签名算法 RSA2（SHA256withRSA），
 *      待签串是「除 sign / sign_type 外的非空参数按字典序拼接」。
 *   2. `public_key` 是支付宝公钥（验签用），`private_key` 是我们自己的应用私钥（签名用），
 *      两者**不能混**——填反了就是「签名能过但验签必失败」，排查起来很费时。
 *   3. 配置缺失时抛 405019，不静默降级（同微信网关第 1 条）。
 *   4. 本段不做支付宝退款到卡、不做分账（《第 6 段工作流》§七）。
 */
package com.faze.infra.pay;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.infra.crypto.ConfigCipher;
import com.faze.infra.pay.model.CallbackResult;
import com.faze.infra.pay.model.PrepayResult;
import com.faze.infra.pay.model.QueryResult;
import com.faze.infra.pay.model.RechargeOrder;
import com.faze.infra.pay.model.RefundRequest;
import com.faze.infra.pay.model.RefundResult;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 支付宝网关。
 */
@Component
@RequiredArgsConstructor
public class AlipayPaymentGateway implements PaymentGateway {

    private final SysConfigQueryService sysConfig;
    private final ConfigCipher configCipher;

    @Override
    public PayChannel channel() {
        return PayChannel.ALIPAY;
    }

    /**
     * 支付宝预下单（APP 支付）。
     *
     * @param order   充值单视图
     * @param channel 渠道（必须为 ALIPAY）
     * @return 预支付结果
     * @throws BusinessException 405019 配置缺失或尚未联调
     */
    @Override
    public PrepayResult prepay(RechargeOrder order, PayChannel channel) {
        // 任务 1：读并校验配置（appid / private_key 必填；公钥只在验签时需要）
        String appid = sysConfig.getString(SysConfigKeys.PAY_ALIPAY_APPID, "");
        String privateKey = configCipher.decryptIfEncrypted(
                sysConfig.getString(SysConfigKeys.PAY_ALIPAY_PRIVATE_KEY, ""));
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(privateKey)) {
            throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED,
                    "支付宝参数未配置（appid / private_key）");
        }
        // TODO(owner: faze-dev, scope: 支付宝联调): 商户号下发后换成 alipay.trade.app.pay 的真实调用：
        //   组装 biz_content（out_trade_no / total_amount 元 / subject / timeout_express）
        //   → RSA2 签名 → 生成 orderStr 放进 payParams。
        //   当前返回明确失败，不造假的 orderStr。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "支付宝尚未联调（商户号未下发）");
    }

    /**
     * 解析并验签支付宝异步通知（表单编码）。
     *
     * @param rawBody 表单编码报文
     * @param headers 未使用（支付宝签名在 body 的 sign 字段里）
     * @return 解析结果
     * @throws BusinessException 405017 验签失败
     */
    @Override
    public CallbackResult parseCallback(String rawBody, Map<String, String> headers) {
        // TODO(owner: faze-dev, scope: 支付宝联调): 用支付宝公钥 RSA2 验签
        //   （待签串 = 除 sign/sign_type 外非空参数字典序拼接），失败抛 405017。
        //   验签通过后取 out_trade_no / trade_no / total_amount / gmt_payment，
        //   并把报文按 L4 规则脱敏（去掉 buyer_id / buyer_logon_id）。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "支付宝回调尚未联调（商户号未下发）");
    }

    /**
     * 支付宝退款（段 6 只用于重复支付原路退回）。
     *
     * @param request 退款请求
     * @return 退款结果
     */
    @Override
    public RefundResult refund(RefundRequest request) {
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "支付宝退款尚未联调（商户号未下发）");
    }

    /**
     * 支付宝查单。
     *
     * @param orderNo 平台充值单号
     * @return 查询结果
     */
    @Override
    public QueryResult query(String orderNo) {
        // TODO(owner: faze-dev, scope: 支付宝联调): 调 alipay.trade.query，把 trade_status
        //   （WAIT_BUYER_PAY / TRADE_SUCCESS / TRADE_FINISHED / TRADE_CLOSED）映射到 RechargeStatus。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "支付宝查单尚未联调（商户号未下发）");
    }
}
