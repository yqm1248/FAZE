/*
 * 职责：`FazeMetrics` 业务错误率打点的单元测试（不连 Spring / 不连文件系统）。
 * 调用方：`mvn test` 自动执行（第 7 段工程债：错误率指标口径 = R.code != 200）。
 * 数据来源：`SimpleMeterRegistry`（Micrometer 内存实现）。
 * 注意：
 *   1. 用 `SimpleMeterRegistry` 而不是 Prometheus registry：本测试验的是「计数逻辑与标签」，
 *      名称下划线转换是 registry 的事（Prometheus 抓取时由 registry 完成）。
 *   2. 断言的口径必须是：成功只加分母；失败同时加分子分母；code/module 进标签。
 *   3. 备份 gauge 的文件路径给一个不存在的临时路径：读取返回 0，不影响本测试。
 */
package com.faze.modules.ops.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 业务错误率指标测试。
 */
class FazeMetricsTest {

    /** 临时目录（不存在的备份文件放这里）。 */
    @TempDir
    Path tempDir;

    /**
     * 成功响应只计入分母。
     */
    @Test
    @DisplayName("code=200 只加 responses，不加 errors")
    void successOnlyCountsResponse() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FazeMetrics metrics = new FazeMetrics(registry, tempDir.resolve("no-such-file.txt").toString());
        metrics.recordBizResponse(200, "app");
        assertThat(registry.get("faze.biz.responses").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("faze.biz.errors").counter()).isNull();
    }

    /**
     * 业务失败同时计入分子分母，并带 code/module 标签。
     */
    @Test
    @DisplayName("code=401008 同时加 responses 与 errors{code,module}")
    void errorCountsBoth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FazeMetrics metrics = new FazeMetrics(registry, tempDir.resolve("no-such-file.txt").toString());
        metrics.recordBizResponse(200, "app");
        metrics.recordBizResponse(401008, "account");
        metrics.recordBizResponse(401008, "account");
        metrics.recordBizResponse(501001, "trade");
        assertThat(registry.get("faze.biz.responses").counter().count()).isEqualTo(4.0);
        assertThat(registry.get("faze.biz.errors")
                .tag("code", "401008").tag("module", "account").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("faze.biz.errors")
                .tag("code", "501001").tag("module", "trade").counter().count()).isEqualTo(1.0);
    }

    /**
     * module 为空时归 unknown（标签值不能是 null/空串）。
     */
    @Test
    @DisplayName("module 为空归 unknown")
    void blankModuleBecomesUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FazeMetrics metrics = new FazeMetrics(registry, tempDir.resolve("no-such-file.txt").toString());
        metrics.recordBizResponse(402003, " ");
        assertThat(registry.get("faze.biz.errors")
                .tag("code", "402003").tag("module", "unknown").counter().count()).isEqualTo(1.0);
    }
}
