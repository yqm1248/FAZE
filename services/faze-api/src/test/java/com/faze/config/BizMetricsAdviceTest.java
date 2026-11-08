/*
 * 职责：`config/BizMetricsAdvice` 的 module 标签收敛规则单元测试。
 * 调用方：`mvn test` 自动执行（第 7 段工程债：业务错误率指标必须按受控 module 分组）。
 * 数据来源：无（纯函数测试）。
 * 注意：
 *   1. **module 必须收敛**：404 扫描会带任意路径，若把 URI 第 4 段直接当标签，
 *      攻击者可以往 Prometheus 时间序列里灌任意值。测试要覆盖「未在白名单的路径段
 *      不能出现在标签里」。
 *   2. 顺序断言优先级：MDC auditAction > URI 第 4 段（白名单）> scope > unknown。
 */
package com.faze.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 业务指标 module 解析测试。
 */
class BizMetricsAdviceTest {

    /**
     * MDC 里的 module 段优先级最高。
     */
    @Test
    @DisplayName("MDC auditAction 优先：account.SUBMIT_ENTERPRISE → account")
    void preferMdcModule() {
        assertThat(BizMetricsAdvice.resolveModule("account.SUBMIT_ENTERPRISE",
                "/api/v1/admin/users/1/ban")).isEqualTo("account");
        assertThat(BizMetricsAdvice.resolveModule("ops.LOG_EXPORT",
                "/api/v1/admin/operation-logs")).isEqualTo("ops");
    }

    /**
     * 无 MDC 时按 URI 第 4 段取，且必须在白名单里。
     */
    @Test
    @DisplayName("无 MDC：URI 第 4 段在白名单内才采用")
    void fallbackToUriModule() {
        assertThat(BizMetricsAdvice.resolveModule(null, "/api/v1/admin/operation-logs?pageSize=10"))
                .isEqualTo("operation-logs");
        assertThat(BizMetricsAdvice.resolveModule(null, "/api/v1/app/social/favorites"))
                .isEqualTo("social");
        assertThat(BizMetricsAdvice.resolveModule(null, "/api/v1/pub/app/version"))
                .isEqualTo("app");
    }

    /**
     * 第 4 段不在白名单 → 收敛到 scope（app/admin/pub），不能把任意路径段当标签。
     */
    @Test
    @DisplayName("未在白名单的路径段不进入 module 标签（防基数爆炸）")
    void unknownSegmentFallsBackToScope() {
        assertThat(BizMetricsAdvice.resolveModule(null, "/api/v1/app/random-attack-path"))
                .isEqualTo("app");
        assertThat(BizMetricsAdvice.resolveModule(null, "/api/v1/random-xyz/zzz/111"))
                .isEqualTo(BizMetricsAdvice.UNKNOWN_MODULE);
        assertThat(BizMetricsAdvice.resolveModule(null, "/totally/random"))
                .isEqualTo(BizMetricsAdvice.UNKNOWN_MODULE);
        assertThat(BizMetricsAdvice.resolveModule(null, null))
                .isEqualTo(BizMetricsAdvice.UNKNOWN_MODULE);
    }

    /**
     * MDC 的 module 段不在白名单时，继续走 URI 兜底。
     */
    @Test
    @DisplayName("MDC 的 module 不在白名单：走 URI 兜底")
    void mdcOutsideWhitelistFallsBack() {
        assertThat(BizMetricsAdvice.resolveModule("weirdmodule.ACTION",
                "/api/v1/admin/tickets")).isEqualTo("tickets");
    }
}
