/*
 * 职责：生产环境的 Mock 支付通道守卫——`pay.mock.enabled=true` 时**启动即失败**。
 * 调用方：Spring 启动流程（@PostConstruct），只在 prod profile 注册。
 * 数据来源：sys_config.pay.mock.enabled。
 * 注意：
 *   1. 这是工作流 §六 R12 的硬约束：Mock 通道会让「用户付 0 元、余额加 100」成为可能，
 *      一旦带着它跑起来，第一笔就是真金白银的窟窿。必须在启动阶段炸掉，不能等出事。
 *   2. 用 IllegalStateException 而不是 BusinessException：这不是业务错误，是配置错误，
 *      启动上下文应当直接失败（与 ProdSmsFixedCodeGuard 同一口径）。
 *   3. 只检查开关，不检查 Mock 的三个测试项：那三项只在开关为 true 时有意义，
 *      开关关掉它们就是死数据，无害。
 */
package com.faze.config;

import com.faze.modules.ops.service.SysConfigQueryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境 Mock 支付守卫。
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class ProdMockPayGuard {

    private final SysConfigQueryService sysConfig;

    /**
     * 启动时断言 Mock 支付通道已关闭。
     *
     * @throws IllegalStateException 生产环境仍开启 Mock 支付时抛出，应用启动失败
     */
    @PostConstruct
    public void assertMockPayDisabled() {
        if (sysConfig.getBoolean(SysConfigKeys.PAY_MOCK_ENABLED, false)) {
            throw new IllegalStateException(
                    "生产 profile 下 sys_config.pay.mock.enabled 必须为 false（当前为 true）。"
                            + "Mock 通道会造出「用户未付款、余额已到账」的假充值，请先在 faze 库改正再启动。");
        }
        log.info("生产环境 Mock 支付开关检查通过：pay.mock.enabled=false");
    }
}
