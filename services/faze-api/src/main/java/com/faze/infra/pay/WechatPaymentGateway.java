/*
 * 职责：微信支付网关（APIv3）——代码写完、单测用桩，**联调等商户号下发**。
 * 调用方：PaymentGatewayFactory（`pay.wechat.enabled=true` 时选中）。
 * 数据来源：sys_config 的 `pay.wechat.*`（api_v3_key 加密）；证书只存路径，文件不进仓库。
 * 注意：
 *   1. **本类不许在配置缺失时静默降级**：appid / mch_id / api_v3_key 任一为空，
 *      prepay 直接抛 405019（渠道下单失败），不抛 500——前端要能显示「渠道暂不可用」。
 *   2. **验签是本类的核心，不是可选项**：APIv3 用平台证书 + `Wechatpay-Signature` 头，
 *      签名串格式 `timestamp\nnonce\nbody\n`。验签失败必须抛 405017，不许返回 false。
 *   3. **`mock` 永远不走这里**：Mock 走 MockPaymentGateway，生产误开 Mock 由
 *      ProdMockPayGuard 拦在启动阶段。
 *   4. 证书路径从 sys_config 读，**只读路径不读内容**：内容由 Java 加载，路径本身不是秘密。
 *   5. 本段只实现「充值 + 回调 + 查单 + 重复支付原路退」；微信分账、退款到卡等
 *      属《第 6 段工作流》§七 明确不做，别顺手加。
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
 * 微信支付网关（APIv3）。
 */
@Component
@RequiredArgsConstructor
public class WechatPaymentGateway implements PaymentGateway {

    private final SysConfigQueryService sysConfig;
    private final ConfigCipher configCipher;

    @Override
    public PayChannel channel() {
        return PayChannel.WECHAT;
    }

    /**
     * 微信预下单（JSAPI/App 下单）。
     *
     * @param order   充值单视图
     * @param channel 渠道（必须为 WECHAT）
     * @return 预支付结果
     * @throws BusinessException 405019 配置缺失或渠道调用失败
     */
    @Override
    public PrepayResult prepay(RechargeOrder order, PayChannel channel) {
        // 任务 1：读并校验渠道配置（缺一项就失败，不做半套）
        String appid = sysConfig.getString(SysConfigKeys.PAY_WECHAT_APPID, "");
        String mchId = sysConfig.getString(SysConfigKeys.PAY_WECHAT_MCH_ID, "");
        String apiV3Key = configCipher.decryptIfEncrypted(
                sysConfig.getString(SysConfigKeys.PAY_WECHAT_API_V3_KEY, ""));
        if (!StringUtils.hasText(appid) || !StringUtils.hasText(mchId) || !StringUtils.hasText(apiV3Key)) {
            throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED,
                    "微信支付参数未配置（appid / mch_id / api_v3_key）");
        }
        // TODO(owner: faze-dev, scope: 微信支付联调): 商户号与商户证书下发后，这里换成
        //   POST https://api.mch.weixin.qq.com/v3/pay/transactions/app 的真实调用：
        //   构造请求体（appid/mchid/description/out_trade_no/notify_url/amount.total）
        //   → 用商户私钥 SHA256withRSA 签名 Authorization 头 → 解析 prepay_id 与 paySign。
        //   当前返回明确失败，不造假的 prepay 结果（造假会让 App 显示「可支付」但点了没反应）。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "微信支付尚未联调（商户号未下发）");
    }

    /**
     * 解析并验签微信异步通知。
     *
     * @param rawBody 原始报文
     * @param headers 请求头（含 Wechatpay-Signature / Timestamp / Nonce / Serial）
     * @return 解析结果
     * @throws BusinessException 405017 验签失败 / 405019 报文非法
     */
    @Override
    public CallbackResult parseCallback(String rawBody, Map<String, String> headers) {
        // 任务 1：验签（没有签名头一律拒绝）
        if (headers == null
                || !StringUtils.hasText(headers.get("Wechatpay-Signature"))
                || !StringUtils.hasText(headers.get("Wechatpay-Timestamp"))
                || !StringUtils.hasText(headers.get("Wechatpay-Nonce"))) {
            throw new BusinessException(ErrorCode.TRADE_CALLBACK_SIGN_INVALID, "微信回调缺少签名头");
        }
        // TODO(owner: faze-dev, scope: 微信支付联调): 用平台证书验 `timestamp\nnonce\nbody\n`，
        //   平台证书由 /v3/certificates 下载并按 Wechatpay-Serial 选择；验签失败抛 405017。
        //   报文解密用 APIv3 密钥 AES-256-GCM（resource.associated_data / nonce / ciphertext）。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "微信回调尚未联调（商户号未下发）");
    }

    /**
     * 微信退款（段 6 只用于重复支付原路退回）。
     *
     * @param request 退款请求
     * @return 退款结果
     */
    @Override
    public RefundResult refund(RefundRequest request) {
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "微信退款尚未联调（商户号未下发）");
    }

    /**
     * 微信查单。
     *
     * @param orderNo 平台充值单号
     * @return 查询结果
     */
    @Override
    public QueryResult query(String orderNo) {
        // TODO(owner: faze-dev, scope: 微信支付联调): GET /v3/pay/transactions/out-trade-no/{orderNo}，
        //   把 trade_state（SUCCESS / REFUND / NOTPAY / CLOSED / REVOKED / USERPAYING / PAYERROR）
        //   映射到 RechargeStatus；NOTPAY 等未支付态返回 exists=true + status=pending。
        throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED, "微信查单尚未联调（商户号未下发）");
    }
}
