/*
 * 职责：支付渠道枚举（3 种），数据库存 code 小写值（recharge_record.channel）。
 * 调用方：infra/pay 的 PaymentGatewayFactory、B 组下单、C 组回调、D 组对账。
 * 数据来源：《第 6 段工作流》§三 A4 第 1 条（3 种渠道）与 A1 第 3 条（列注释补 mock）。
 * 注意：
 *   1. **mock 是测试通道，生产必须关**（`pay.mock.enabled=false`）：开发环境误开 Mock
 *      会让线上出现「用户付 0 元、余额加 100」的假到账。R12 的兜底是「prod + mock=true
 *      启动即失败」，见 PayChannel 的调用方（MockGuard）。
 *   2. 渠道未启用时**不许静默回退 Mock**，抛 405013（A3 第 3 条）。
 *   3. 渠道配置文件在 sys_config，键名 `pay.<channel>.<item>`，加密项由 Jasypt 解密。
 */
package com.faze.modules.trade.enums;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付渠道。与 {@code recharge_record.channel} 的取值一一对应。
 */
@Getter
public enum PayChannel {

    /** 微信支付（APIv3）。商户号未下发前只写代码，不联调。 */
    WECHAT("wechat", "微信支付"),

    /** 支付宝（当面付/App 支付）。同上。 */
    ALIPAY("alipay", "支付宝"),

    /** Mock 通道。段 6 的四条可验锚点全部在它上面跑；生产禁止开启。 */
    MOCK("mock", "Mock 通道");

    /** 持久化值（recharge_record.channel / sys_config 键的中段）。 */
    private final String code;

    /** 给开发者与后台看的中文名；前端展示走 {@link #i18nKey()}。 */
    private final String label;

    PayChannel(String code, String label) {
        this.code = code;
        this.label = label;
    }

    private static final Map<String, PayChannel> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PayChannel::getCode, Function.identity()));

    /**
     * 按持久化值反查。
     *
     * @param code 渠道 code（wechat / alipay / mock）
     * @return 对应枚举
     * @throws BusinessException 值非法时抛 401001（参数错误），不是 405013——
     *                           405013 的语义是「渠道存在但被关掉」，两者不能混
     */
    public static PayChannel of(String code) {
        PayChannel channel = code == null ? null : BY_CODE.get(code);
        if (channel == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "未知支付渠道: " + code);
        }
        return channel;
    }

    /**
     * 按持久化值反查，值非法返回 null（回调解析时用于「未知渠道」容错）。
     *
     * @param code 待解析值
     * @return 对应枚举；未知值返回 null
     */
    public static PayChannel ofOrNull(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 是否为真实渠道（非 Mock）。
     *
     * @return wechat / alipay 返回 true
     */
    public boolean isReal() {
        return this != MOCK;
    }

    /**
     * sys_config 里该渠道配置项的前缀，如 {@code pay.wechat}。
     *
     * @return 配置键前缀
     */
    public String configPrefix() {
        return "pay." + code;
    }

    /**
     * 该渠道中文名的 i18n key。
     *
     * <p>同样遵循三段式 + camelCase 元素段（{@code trade.pay.channelWechat}），
     * 与既有 {@code trade.pay.methodBalance} 同族；**不拼 key**，用枚举 switch 写字面量。</p>
     *
     * @return 形如 {@code trade.pay.channelWechat}
     */
    public String i18nKey() {
        return switch (this) {
            case WECHAT -> "trade.pay.channelWechat";
            case ALIPAY -> "trade.pay.channelAlipay";
            case MOCK -> "trade.pay.channelMock";
        };
    }
}
