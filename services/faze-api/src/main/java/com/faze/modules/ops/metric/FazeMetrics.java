/*
 * 职责：6 个自定义 Micrometer 指标的唯一注册与打点入口（第 7 段 G1）。
 * 调用方：config/MetricsInterceptor（HTTP 三指标）、
 *         modules/ops 的 ContentReviewService（敏感词命中）、
 *         modules/ops 的 SensitiveWordReloadListener（热更新失败）、
 *         modules/ops 的 TicketSlaScanTask（工单超时）、
 *         Gauge 由本类注册后由 Prometheus 抓取时读文件。
 * 数据来源：HTTP 请求（拦截器）、敏感词引擎命中、工单扫描、`logs/backup-last-success.txt`。
 * 注意：
 *   1. **指标名固定为 Prometheus 输出名**，T4 的 alert-rules.yml 与 Grafana dashboard 已按它写：
 *      `faze_http_requests_total{path,method,status}`、`faze_http_request_duration_seconds{path,method}`、
 *      `faze_sensitive_hit_total{level}`、`faze_sensitive_reload_fail_total`、
 *      `faze_ticket_overtime_total`、`faze_backup_last_success_timestamp`。
 *      Micrometer 的命名用点分隔、Prometheus registry 自动转下划线并给 Counter 加 `_total`
 *      （所以代码里写 `faze.http.requests`，输出就是 `faze_http_requests_total`）。
 *   2. **`path` 标签必须是模板**（`/api/v1/admin/users/{id}`）：用实际 URL 会让每个 id
 *      生成一条时间序列，一周就能把 Prometheus 撑爆。模板由 MetricsInterceptor 从
 *      `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` 取。
 *   3. **备份 gauge 读文件而不是查库**：文件 `logs/backup-last-success.txt` 单行 epoch 秒，
 *      由 T4 的 `scripts/backup/backup.ps1` 写入（**跨成员硬约定，不许改成读 DB**）。
 *      文件不存在时返回 0（Prometheus 端 `time() - gauge > 86400*1.5` 会触发备份过期告警）。
 *   4. Gauge 的读取发生在**每次 scrape**（15s 一次），只读一个百字节文件，开销可忽略。
 *   5. 指标注册失败（同名重复）会抛异常：本类只在构造器里注册一次，不允许其他类再注册同名指标。
 */
package com.faze.modules.ops.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 法泽自定义指标。
 */
@Slf4j
@Component
public class FazeMetrics {

    /** HTTP 请求总数（输出 `faze_http_requests_total`）。 */
    private static final String HTTP_REQUESTS = "faze.http.requests";

    /** HTTP 请求耗时（Timer 输出 `faze_http_request_duration_seconds_*`）。 */
    private static final String HTTP_DURATION = "faze.http.request.duration";

    /** 敏感词命中数（输出 `faze_sensitive_hit_total`）。 */
    private static final String SENSITIVE_HIT = "faze.sensitive.hit";

    /** 敏感词热更新失败数（输出 `faze_sensitive_reload_fail_total`）。 */
    private static final String SENSITIVE_RELOAD_FAIL = "faze.sensitive.reload.fail";

    /** 工单超时数（输出 `faze_ticket_overtime_total`）。 */
    private static final String TICKET_OVERTIME = "faze.ticket.overtime";

    /** 备份最后成功时间（输出 `faze_backup_last_success_timestamp`）。 */
    private static final String BACKUP_LAST_SUCCESS = "faze.backup.last.success.timestamp";

    /** 未匹配到模板时的 path 标签值（防止每个 404 路径单独成一条序列）。 */
    public static final String UNKNOWN_PATH = "UNKNOWN";

    /** 备份成功时间文件（T4 的备份脚本写，本类只读）。 */
    private final Path backupFile;

    private final MeterRegistry registry;

    /**
     * 构造并注册指标。
     *
     * @param registry    Micrometer 注册表（Prometheus registry 由 starter 自动装配）
     * @param backupFile  备份成功时间文件路径（默认 logs/backup-last-success.txt）
     */
    public FazeMetrics(MeterRegistry registry,
                       @Value("${faze.backup.last-success-file:logs/backup-last-success.txt}") String backupFile) {
        this.registry = registry;
        this.backupFile = Paths.get(backupFile);
        Gauge.builder(BACKUP_LAST_SUCCESS, this, FazeMetrics::readBackupEpochSeconds)
                .description("备份最后成功时间（epoch 秒）；文件不存在为 0")
                .register(registry);
        // **预注册三个业务 Counter（初值 0）**：Micrometer 的 Counter 是惰性注册的——
        // 不预注册的话，在第一次事件发生前 `/actuator/prometheus` 里根本看不到这三个指标名，
        // 会让「6 个自定义指标」的验收看起来缺 3 个（2026-11-09 实测：只触发过 HTTP 指标时
        // sensitive_hit / reload_fail / ticket_overtime 全部 MISSING）。
        // 预注册的 level=unknown 序列只会是 0，Prometheus 抓取不受影响；
        // 告警规则写的是 `increase(...)` 或 `> 0`，多一条 0 值序列无副作用。
        Counter.builder(SENSITIVE_HIT).description("敏感词命中数").tag("level", "unknown")
                .register(registry);
        Counter.builder(SENSITIVE_RELOAD_FAIL).description("敏感词热更新失败次数").register(registry);
        Counter.builder(TICKET_OVERTIME).description("工单超时数").register(registry);
        log.info("自定义指标已注册：{} / {} / {} / {} / {} / {}",
                HTTP_REQUESTS, HTTP_DURATION, SENSITIVE_HIT, SENSITIVE_RELOAD_FAIL,
                TICKET_OVERTIME, BACKUP_LAST_SUCCESS);
    }

    /**
     * 记录一次 HTTP 请求（QPS 与错误率都从它算）。
     *
     * @param path   路由模板（必须已模板化；未匹配用 {@link #UNKNOWN_PATH}）
     * @param method HTTP 方法
     * @param status HTTP 状态码
     */
    public void recordHttpRequest(String path, String method, int status) {
        Counter.builder(HTTP_REQUESTS)
                .description("HTTP 请求总数")
                .tags("path", path, "method", method, "status", String.valueOf(status))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次 HTTP 请求耗时（P95 从它算）。
     *
     * @param path  路由模板
     * @param method HTTP 方法
     * @param nanos 耗时（纳秒）
     */
    public void recordHttpDuration(String path, String method, long nanos) {
        Timer.builder(HTTP_DURATION)
                .description("HTTP 请求耗时")
                .tags("path", path, "method", method)
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录敏感词命中（按级别分标签）。
     *
     * @param level 级别：block / review / warn
     */
    public void recordSensitiveHit(String level) {
        String safeLevel = level == null || level.isBlank() ? "unknown" : level;
        Counter.builder(SENSITIVE_HIT)
                .description("敏感词命中数")
                .tag("level", safeLevel)
                .register(registry)
                .increment();
    }

    /**
     * 记录一次敏感词热更新失败。
     */
    public void recordSensitiveReloadFail() {
        Counter.builder(SENSITIVE_RELOAD_FAIL)
                .description("敏感词热更新失败次数")
                .register(registry)
                .increment();
    }

    /**
     * 记录一次工单超时（首次扫描命中）。
     *
     * @param count 本次新增超时数（正数；<=0 不记录）
     */
    public void recordTicketOvertime(int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(TICKET_OVERTIME)
                .description("工单超时数")
                .register(registry)
                .increment(count);
    }

    /**
     * 读备份最后成功时间。
     *
     * @return epoch 秒；文件不存在 / 内容非法 / 读取失败返回 0
     */
    private double readBackupEpochSeconds() {
        try {
            if (!Files.exists(backupFile)) {
                return 0d;
            }
            List<String> lines = Files.readAllLines(backupFile, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return 0d;
            }
            return Double.parseDouble(lines.get(0).trim());
        } catch (IOException | NumberFormatException e) {
            log.debug("读备份时间文件失败（按 0 处理）file={}", backupFile, e);
            return 0d;
        }
    }
}
