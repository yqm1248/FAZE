/*
 * 职责：`AlertDispatcher` 恢复通知（差集判定）的单元测试。
 * 调用方：`mvn test` 自动执行（第 7 段工程债：恢复通知必须能真的发出来）。
 * 数据来源：无（纯函数测试；不连 Redis / Prometheus）。
 * 注意：
 *   1. 只测 `recoveredKeys`（差集语义）——这是恢复通知的核心逻辑：
 *      **上一轮有、本轮没有 = 已恢复**。轮询/发送链路由手工演练覆盖（见验收证据）。
 *   2. 必须覆盖「上一轮为空」与「本轮为空」两个边界：
 *      上一轮为空 → 不发恢复（首次启动不能把现存告警当恢复刷一屏）；
 *      本轮为空 → 全部恢复（Prometheus 真的全清）。
 *   3. 不用 mock Redis：`recoveredKeys` 是包级静态纯函数，测试不需要 Spring 上下文。
 */
package com.faze.modules.ops.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 告警恢复差集测试。
 */
class AlertDispatcherTest {

    /**
     * 上一轮有 A、B，本轮只剩 A → B 恢复。
     */
    @Test
    @DisplayName("差集：消失的告警被判定为已恢复")
    void recoveredWhenDisappeared() {
        Set<String> recovered = AlertDispatcher.recoveredKeys(Set.of("A|x@1", "B|x@1"), Set.of("A|x@1"));
        assertThat(recovered).containsExactly("B|x@1");
    }

    /**
     * 本轮新增告警不影响恢复判定（新增只推 firing）。
     */
    @Test
    @DisplayName("差集：本轮新增的告警不算恢复")
    void newAlertIsNotRecovered() {
        Set<String> recovered = AlertDispatcher.recoveredKeys(Set.of("A|x@1"), Set.of("A|x@1", "B|x@1"));
        assertThat(recovered).isEmpty();
    }

    /**
     * 上一轮为空（首次启动）→ 不发任何恢复。
     */
    @Test
    @DisplayName("上一轮为空：首次启动不把现存告警当恢复")
    void noRecoveryOnFirstRound() {
        assertThat(AlertDispatcher.recoveredKeys(Set.of(), Set.of("A|x@1"))).isEmpty();
        assertThat(AlertDispatcher.recoveredKeys(null, Set.of("A|x@1"))).isEmpty();
    }

    /**
     * 本轮为空（Prometheus 全部清空）→ 上一轮全部恢复。
     */
    @Test
    @DisplayName("本轮为空：上一轮全部判定为已恢复")
    void allRecoveredWhenCurrentEmpty() {
        Set<String> previous = new LinkedHashSet<>(Set.of("A|x@1", "B|x@1"));
        assertThat(AlertDispatcher.recoveredKeys(previous, Set.of()))
                .containsExactlyInAnyOrder("A|x@1", "B|x@1");
        assertThat(AlertDispatcher.recoveredKeys(previous, null))
                .containsExactlyInAnyOrder("A|x@1", "B|x@1");
    }

    /**
     * 两轮相同 → 无恢复。
     */
    @Test
    @DisplayName("同一个告警持续 firing：不重复发恢复")
    void sameAlertNotRecovered() {
        assertThat(AlertDispatcher.recoveredKeys(Set.of("A|x@1"), Set.of("A|x@1"))).isEmpty();
    }
}
