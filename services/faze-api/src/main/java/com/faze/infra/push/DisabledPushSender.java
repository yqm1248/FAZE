/*
 * 职责：离线推送通道的**占位实现**——通道未接入时明确失败，而不是静默成功（G7.1 挂账点）。
 * 调用方：modules/conversation/task/PushTask（通过 PushSender 接口）。
 * 数据来源：sys_config `push.enabled`（默认 false）。
 * 注意：
 *   1. **为什么要有这个类**：`2-A3` 依赖个推应用与四个厂商通道凭据，负责人尚未提供。
 *      如果这里返回成功，`task_queue` 里的推送任务会全部「成功」，死信与重试逻辑永远不被触发，
 *      验收时看起来「通过」——这是工作流 §二 第 11 项明令禁止的「假装通过」。
 *      所以未接入就**明确抛 PushUnavailableException**，让任务走重试 → 死信，行为可观测。
 *   2. 凭据到位后：新增 `GetuiPushSender implements PushSender` 并加 `@Primary`（或按
 *      `push.enabled` 条件装配），**本类保留**作为「关闭推送」时的降级实现。
 *   3. 配置键：`push.enabled`；标题/正文模板 `push.message_title` / `push.message_body`
 *      （PushTask 读取，不在这里）。
 */
package com.faze.infra.push;

import com.faze.modules.ops.service.SysConfigQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 未接入通道的推送发送器（占位）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DisabledPushSender implements PushSender {

    /** 开关配置键。 */
    public static final String CONFIG_ENABLED = "push.enabled";

    private final SysConfigQueryService sysConfigQueryService;

    @Override
    public void send(PushMessage message) {
        boolean enabled = sysConfigQueryService.getBoolean(CONFIG_ENABLED, false);
        if (enabled) {
            // 开关打开了但没有真实实现：说明部署方以为推送已接入
            log.error("push.enabled=true 但推送通道尚未接入（G7.1 挂账），userId={}", message.userId());
            throw new PushUnavailableException("推送通道未接入（G7.1 挂账）");
        }
        log.info("推送通道未启用，跳过推送 userId={} conversationId={}",
                message.userId(), message.payload().get("conversationId"));
        throw new PushUnavailableException("推送通道未启用（push.enabled=false）");
    }
}
