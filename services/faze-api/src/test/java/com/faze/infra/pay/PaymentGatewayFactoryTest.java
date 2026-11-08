/*
 * 职责：PaymentGatewayFactory 的渠道开关判定单测（A 组检查点「三个网关实现可切换」）。
 * 调用方：mvn test（surefire）。
 * 数据来源：《第 6 段工作流》§三 A3 第 3 条（工厂按配置选实现、未启用抛 405013、不许回退 Mock）。
 * 注意：
 *   1. 不启 Spring：工厂的判定完全由 SysConfigQueryService 的返回值决定，
 *      mock 掉它就能覆盖全部分支，比 @SpringBootTest 快且不依赖数据库。
 *   2. 必须验「默认拒绝」：getBoolean 的默认值是 false，所以**未配置 = 关闭**，
 *      不能因为「没写配置就放行」。
 *   3. 必须验「Mock 关闭时取不到 Mock」：这是 R12（生产误开 Mock）在代码层的第二道门，
 *      第一道是 ProdMockPayGuard 的启动守卫。
 */
package com.faze.infra.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.infra.crypto.ConfigCipher;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 支付网关工厂单测。
 */
class PaymentGatewayFactoryTest {

    private static final String DEV_KEY = "faze-local-config-key-32-bytes!!";

    private SysConfigQueryService sysConfig;
    private PaymentGatewayFactory factory;

    @BeforeEach
    void setUp() {
        sysConfig = mock(SysConfigQueryService.class);
        // 默认：所有字符串配置为空、所有开关关闭
        when(sysConfig.getString(anyString(), anyString())).thenReturn("");
        when(sysConfig.getBoolean(anyString(), anyBoolean())).thenReturn(false);
        when(sysConfig.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(0);

        ConfigCipher cipher = new ConfigCipher(DEV_KEY);
        List<PaymentGateway> gateways = List.of(
                new MockPaymentGateway(sysConfig),
                new WechatPaymentGateway(sysConfig, cipher),
                new AlipayPaymentGateway(sysConfig, cipher));
        factory = new PaymentGatewayFactory(gateways, sysConfig);
    }

    /**
     * Mock 开启时能取到 Mock 网关。
     */
    @Test
    @DisplayName("pay.mock.enabled=true：取到 MockPaymentGateway")
    void mockEnabled() {
        when(sysConfig.getBoolean(SysConfigKeys.PAY_MOCK_ENABLED, false)).thenReturn(true);
        PaymentGateway gateway = factory.get(PayChannel.MOCK);
        assertThat(gateway).isInstanceOf(MockPaymentGateway.class);
        assertThat(gateway.channel()).isEqualTo(PayChannel.MOCK);
    }

    /**
     * 渠道关闭时抛 405013，且**不回退**到其他实现。
     */
    @Test
    @DisplayName("渠道关闭：抛 405013，不回退 Mock")
    void disabledThrows() {
        when(sysConfig.getBoolean(SysConfigKeys.PAY_MOCK_ENABLED, false)).thenReturn(false);
        assertThatThrownBy(() -> factory.get(PayChannel.MOCK))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.TRADE_CHANNEL_DISABLED));
    }

    /**
     * 默认拒绝：未配置任何开关时，三个渠道全部关闭、enabledChannels 为空。
     */
    @Test
    @DisplayName("默认拒绝：未配置 = 全部关闭")
    void defaultDeny() {
        assertThat(factory.enabledChannels()).isEmpty();
        for (PayChannel channel : PayChannel.values()) {
            assertThat(factory.isEnabled(channel)).as("%s 未配置应关闭", channel).isFalse();
        }
    }

    /**
     * 只开 Mock 时，enabledChannels 只含 MOCK。
     */
    @Test
    @DisplayName("只开 Mock：enabledChannels 只含 MOCK")
    void onlyMockEnabled() {
        when(sysConfig.getBoolean(eq(SysConfigKeys.PAY_MOCK_ENABLED), anyBoolean())).thenReturn(true);
        assertThat(factory.enabledChannels()).containsExactly(PayChannel.MOCK);
    }

    /**
     * 真实渠道开启后能取到对应实现（代码就绪，联调等商户号）。
     */
    @Test
    @DisplayName("微信/支付宝开启：取到对应实现")
    void realChannelsWired() {
        when(sysConfig.getBoolean(eq(SysConfigKeys.PAY_WECHAT_ENABLED), anyBoolean())).thenReturn(true);
        when(sysConfig.getBoolean(eq(SysConfigKeys.PAY_ALIPAY_ENABLED), anyBoolean())).thenReturn(true);
        assertThat(factory.get(PayChannel.WECHAT)).isInstanceOf(WechatPaymentGateway.class);
        assertThat(factory.get(PayChannel.ALIPAY)).isInstanceOf(AlipayPaymentGateway.class);
    }

    /**
     * 空渠道参数抛 401001（参数错误），不是 405013。
     */
    @Test
    @DisplayName("channel=null：抛 401001")
    void nullChannel() {
        assertThatThrownBy(() -> factory.get(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COMMON_PARAM_INVALID));
    }
}
