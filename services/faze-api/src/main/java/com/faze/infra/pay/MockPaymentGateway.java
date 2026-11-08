/*
 * 职责：Mock 支付网关——段 6 的四条可验锚点（6-A2 / 6-A3 / 6-A5 / 6-A7）全在它上面跑。
 * 调用方：PaymentGatewayFactory（`pay.mock.enabled=true` 时选中）；C 组回调端点分发。
 * 数据来源：sys_config 的 `pay.mock.*` 三个测试开关 + 内存状态（重启丢失，可接受）。
 * 注意：
 *   1. **生产禁止开启**：ProdMockPayGuard 在 prod profile 下发现 `pay.mock.enabled=true`
 *      会直接让启动失败（R12）。误开 = 用户付 0 元、余额加 100 的假到账。
 *   2. **报文格式与真实渠道同构**：表单编码 + HMAC-SHA256 签名，验签链路是真的，
 *      这样 6-A2 的「重放 3 次只入账一次」验的是**真实代码路径**，不是特判。
 *   3. `prepay()` 返回的 `payParams.callbackBody` 是**预生成的回调报文**，测试直接拿它
 *      POST 到 `/api/v1/pub/pay/callback/mock` 即可重放——这是 Mock 能验幂等的前提。
 *   4. 三个开关：
 *      · `pay.mock.result` = success / fail / callback_lost（callback_lost 用于造掉单 6-A3）
 *      · `pay.mock.delay_ms` = 回调延迟（记录在 callbackNotBefore，验重放窗口）
 *      · `pay.mock.amount_offset_cents` = 回调金额偏移（造对账差异 6-A4）
 *   5. **本类不打任何日志**：报文里有单号与金额，打全了就是一份可重放的回调。
 */
package com.faze.infra.pay;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.infra.pay.model.CallbackResult;
import com.faze.infra.pay.model.PrepayResult;
import com.faze.infra.pay.model.QueryResult;
import com.faze.infra.pay.model.RechargeOrder;
import com.faze.infra.pay.model.RefundRequest;
import com.faze.infra.pay.model.RefundResult;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import com.faze.modules.trade.enums.RechargeStatus;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Mock 支付网关。
 */
@Component
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    /** 仅测试用签名密钥。**不是生产密钥**，生产永远走 wechat / alipay。 */
    private static final String MOCK_SIGN_KEY = "faze-mock-pay-sign-key-dev-only";

    private static final String ALGORITHM = "HmacSHA256";

    private final SysConfigQueryService sysConfig;

    /** 单号 → 内存状态。段 6 的测试通道允许进程内状态。 */
    private final Map<String, MockState> states = new ConcurrentHashMap<>();

    @Override
    public PayChannel channel() {
        return PayChannel.MOCK;
    }

    /**
     * Mock 预下单。
     *
     * @param order   充值单视图
     * @param channel 渠道（必须为 MOCK）
     * @return 预支付结果；`payParams.callbackBody` 可直接用于重放
     */
    @Override
    public PrepayResult prepay(RechargeOrder order, PayChannel channel) {
        // 任务 1：读三个测试开关（缺省按 success / 0 / 0）
        String result = sysConfig.getString(SysConfigKeys.PAY_MOCK_RESULT, "success");
        int delayMs = sysConfig.getInt(SysConfigKeys.PAY_MOCK_DELAY_MS, 0);
        long offsetCents = parseLong(sysConfig.getString(SysConfigKeys.PAY_MOCK_AMOUNT_OFFSET_CENTS, "0"));

        // 任务 2：生成本地 Mock 状态（金额按 offset 造差异）
        String orderNo = order.getOrderNo();
        String txnId = "MOCKTXN" + orderNo;
        long callbackAmount = order.getAmountCents() + offsetCents;
        LocalDateTime paidAt = LocalDateTime.now();
        MockState state = new MockState();
        state.orderNo = orderNo;
        state.channelTxnId = txnId;
        state.amountCents = callbackAmount;
        state.paidAt = paidAt;
        state.callbackNotBefore = LocalDateTime.now().plusNanos(Math.max(delayMs, 0) * 1_000_000L);
        state.status = "fail".equals(result) ? RechargeStatus.FAILED : RechargeStatus.PENDING;
        state.callbackBody = "callback_lost".equals(result) || "fail".equals(result)
                ? null
                : buildCallbackBody(orderNo, txnId, callbackAmount, paidAt);
        states.put(orderNo, state);

        // 任务 3：返回预支付参数（callbackBody 是给测试/App 的重放素材，不是给真实渠道的）
        Map<String, String> payParams = new LinkedHashMap<>();
        payParams.put("mock", "true");
        payParams.put("orderNo", orderNo);
        payParams.put("channel", PayChannel.MOCK.getCode());
        payParams.put("amountCents", String.valueOf(order.getAmountCents()));
        if (state.callbackBody != null) {
            payParams.put("callbackBody", state.callbackBody);
        }
        PrepayResult prepay = new PrepayResult();
        prepay.setChannel(PayChannel.MOCK);
        prepay.setPrepayId("MOCKPRE" + orderNo);
        prepay.setPayParams(payParams);
        return prepay;
    }

    /**
     * 解析并验签 Mock 回调报文。
     *
     * <p>验签失败抛 405017（安全事件）；签名正确但语义是失败的返回 success=false。</p>
     *
     * @param rawBody 表单编码报文
     * @param headers 未使用（Mock 签名在 body 里）
     * @return 解析结果
     */
    @Override
    public CallbackResult parseCallback(String rawBody, Map<String, String> headers) {
        Map<String, String> form = parseForm(rawBody);
        String sign = form.remove("sign");
        if (sign == null || !sign.equals(hmac(canonical(form)))) {
            throw new BusinessException(ErrorCode.TRADE_CALLBACK_SIGN_INVALID, "Mock 回调验签失败");
        }
        String orderNo = form.get("orderNo");
        String txnId = form.get("channelTxnId");
        long amount = parseLong(form.get("amountCents"));
        boolean success = "true".equals(form.get("success"));

        MockState state = states.get(orderNo);
        if (state != null) {
            state.status = success ? RechargeStatus.SUCCESS : RechargeStatus.FAILED;
            state.channelTxnId = txnId == null ? state.channelTxnId : txnId;
        }

        CallbackResult result = new CallbackResult();
        result.setChannel(PayChannel.MOCK);
        result.setOrderNo(orderNo);
        result.setChannelTxnId(txnId);
        result.setAmountCents(amount);
        result.setSuccess(success);
        result.setPaidAt(success ? parseTime(form.get("paidAt")) : null);
        // 脱敏：Mock 报文本身不含用户隐私，原样保留（真实渠道要按 L4 规则裁剪）
        result.setSanitizedPayload(rawBody);
        return result;
    }

    /**
     * Mock 退款：直接受理成功。
     *
     * @param request 退款请求
     * @return 成功
     */
    @Override
    public RefundResult refund(RefundRequest request) {
        RefundResult result = new RefundResult();
        result.setSuccess(true);
        result.setRefundId("MOCKREF" + request.getOrderNo());
        return result;
    }

    /**
     * Mock 查单：读内存状态。
     *
     * @param orderNo 平台充值单号
     * @return 查询结果；未下过单返回 exists=false
     */
    @Override
    public QueryResult query(String orderNo) {
        QueryResult result = new QueryResult();
        result.setChannel(PayChannel.MOCK);
        result.setOrderNo(orderNo);
        MockState state = states.get(orderNo);
        if (state == null) {
            result.setExists(false);
            return result;
        }
        result.setExists(true);
        result.setStatus(state.status);
        result.setChannelTxnId(state.channelTxnId);
        result.setAmountCents(state.amountCents);
        result.setPaidAt(state.paidAt);
        return result;
    }

    /**
     * 生成一条可直接重放的 Mock 回调报文（供缺陷复现与 6-A2 重放测试）。
     *
     * @param orderNo     平台充值单号
     * @param txnId       渠道交易号
     * @param amountCents 回调金额（分）
     * @param paidAt      支付时间
     * @return 表单编码 + 签名的报文
     */
    private String buildCallbackBody(String orderNo, String txnId, long amountCents, LocalDateTime paidAt) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("orderNo", orderNo);
        form.put("channelTxnId", txnId);
        form.put("amountCents", String.valueOf(amountCents));
        form.put("success", "true");
        form.put("paidAt", paidAt.toString());
        String body = canonical(form) + "&sign=" + hmac(canonical(form));
        return body;
    }

    /**
     * 把 key/value 拼成稳定顺序的待签串（字典序，不含 sign）。
     *
     * @param form 表单
     * @return 待签串
     */
    private String canonical(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    if (sb.length() > 0) {
                        sb.append('&');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                });
        return sb.toString();
    }

    /**
     * HMAC-SHA256 签名（Base64）。
     *
     * @param text 待签串
     * @return 签名
     */
    private String hmac(String text) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(MOCK_SIGN_KEY.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Mock 签名失败", e);
        }
    }

    /**
     * 解析表单编码报文。
     *
     * @param rawBody 原始报文
     * @return 字段表
     */
    private Map<String, String> parseForm(String rawBody) {
        Map<String, String> form = new LinkedHashMap<>();
        if (rawBody == null || rawBody.isBlank()) {
            return form;
        }
        for (String pair : rawBody.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            form.put(key, value);
        }
        return form;
    }

    /**
     * 解析长整数，非法返回 0。
     *
     * @param text 文本
     * @return 数值
     */
    private long parseLong(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 解析时间，失败返回 null。
     *
     * @param text ISO-8601 文本
     * @return 时间
     */
    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Mock 单的内存状态。
     */
    private static final class MockState {
        private String orderNo;
        private String channelTxnId;
        private long amountCents;
        private LocalDateTime paidAt;
        private LocalDateTime callbackNotBefore;
        private RechargeStatus status;
        private String callbackBody;
    }
}
