/*
 * 职责：按 sys_config 开关选择支付网关实现。
 * 调用方：modules/trade 的 RechargeService（下单）、回调端点（解析报文前先取网关）。
 * 数据来源：sys_config 的 `pay.{channel}.enabled` + Spring 容器里的三个网关 Bean。
 * 注意：
 *   1. **渠道未启用一律抛 405013，不许回退到 Mock**（工作流 A3 第 3 条）。
 *      开发环境误开 Mock 会让线上出现假到账；反过来「微信没配好就自动用 Mock」
 *      会把测试流量带进生产账。
 *   2. 业务代码**只能经本工厂拿网关**，禁止直接注入 WechatPaymentGateway 等具体实现——
 *      那样上面的开关检查会被绕过。
 *   3. 本类不缓存开关值：sys_config 是 9 字段小表、uk_config_key 命中即毫秒级；
 *      缓存会制造「后台关了渠道但服务还在用」的漂移（与 SysConfigQueryService 的约定一致）。
 */
package com.faze.infra.pay;

import com.faze.common.exception.BusinessException;
import com.faze.common.exception.ErrorCode;
import com.faze.config.SysConfigKeys;
import com.faze.modules.ops.service.SysConfigQueryService;
import com.faze.modules.trade.enums.PayChannel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 支付网关工厂。
 */
@Component
public class PaymentGatewayFactory {

    private final Map<PayChannel, PaymentGateway> gateways;
    private final SysConfigQueryService sysConfig;

    /**
     * 构造工厂。
     *
     * @param gatewayList 容器里的全部网关实现（按 channel() 建索引）
     * @param sysConfig   平台参数只读查询
     * @throws IllegalStateException 同一渠道注册了多个实现（配置错误，启动即失败）
     */
    public PaymentGatewayFactory(List<PaymentGateway> gatewayList, SysConfigQueryService sysConfig) {
        this.sysConfig = sysConfig;
        this.gateways = gatewayList.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentGateway::channel, Function.identity()));
    }

    /**
     * 取指定渠道的网关。
     *
     * @param channel 渠道
     * @return 网关实现
     * @throws BusinessException 405013 渠道未启用；405019 渠道实现缺失
     */
    public PaymentGateway get(PayChannel channel) {
        if (channel == null) {
            throw new BusinessException(ErrorCode.COMMON_PARAM_INVALID, "支付渠道不能为空");
        }
        if (!isEnabled(channel)) {
            throw new BusinessException(ErrorCode.TRADE_CHANNEL_DISABLED, "渠道未启用: " + channel.getCode());
        }
        PaymentGateway gateway = gateways.get(channel);
        if (gateway == null) {
            throw new BusinessException(ErrorCode.TRADE_CHANNEL_PREPAY_FAILED,
                    "渠道实现缺失: " + channel.getCode());
        }
        return gateway;
    }

    /**
     * 渠道是否启用（读 sys_config）。
     *
     * @param channel 渠道
     * @return 启用返回 true；未配置按关闭处理（默认关闭，符合「默认拒绝」）
     */
    public boolean isEnabled(PayChannel channel) {
        return switch (channel) {
            case MOCK -> sysConfig.getBoolean(SysConfigKeys.PAY_MOCK_ENABLED, false);
            case WECHAT -> sysConfig.getBoolean(SysConfigKeys.PAY_WECHAT_ENABLED, false);
            case ALIPAY -> sysConfig.getBoolean(SysConfigKeys.PAY_ALIPAY_ENABLED, false);
        };
    }

    /**
     * 当前启用的渠道清单（App 充值页只展示这些）。
     *
     * @return 按枚举声明顺序返回启用的渠道
     */
    public List<PayChannel> enabledChannels() {
        return java.util.Arrays.stream(PayChannel.values()).filter(this::isEnabled).toList();
    }
}
