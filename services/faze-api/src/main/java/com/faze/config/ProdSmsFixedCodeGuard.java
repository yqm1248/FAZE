/*
 * 职责：生产环境的固定验证码守卫——`sms_fixed_code_enabled=true` 时**启动即失败**。
 * 调用方：Spring 启动流程（@PostConstruct），只在 prod profile 注册。
 * 数据来源：sys_config.sms_fixed_code_enabled。
 * 注意：
 *   1. 这是 B1 的硬约束第 3 条：**不许静默降级**。固定码能注册任何人的账号，
 *      一旦带到生产就是全站可被任意注册的洞，必须在启动阶段炸掉，而不是等出事。
 *   2. 用 IllegalStateException 而不是 BusinessException：这条不是业务错误，
 *      是配置错误，启动上下文应当直接失败。
 *   3. 只检查「开关」不检查「固定码值」：值本身晦气但无害，开关才是风险。
 */
package com.faze.config;

import com.faze.modules.ops.service.SysConfigQueryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境固定码守卫。
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class ProdSmsFixedCodeGuard {

    private final SysConfigQueryService sysConfig;

    /**
     * 启动时断言固定码开关已关闭。
     *
     * @throws IllegalStateException 生产环境仍开启固定码时抛出，应用启动失败
     */
    @PostConstruct
    public void assertFixedCodeDisabled() {
        if (sysConfig.getBoolean("sms_fixed_code_enabled", false)) {
            throw new IllegalStateException(
                    "生产 profile 下 sys_config.sms_fixed_code_enabled 必须为 false（当前为 true）。"
                            + "固定验证码会让任何人都能注册/登录任意手机号，请先在 faze 库改正再启动。");
        }
        log.info("生产环境固定码开关检查通过：sms_fixed_code_enabled=false");
    }
}
