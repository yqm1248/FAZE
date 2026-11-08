/*
 * 职责：**仅开发/验收环境**的故障注入端点——用来证明监控与告警真的在工作（第 7 段 G 组）。
 * 调用方：T4 的 `scripts/alert/fault-inject.ps1`（`-mode 500` / `-mode slow`）、
 *         H3「监控与告警演练」的人工复验。
 * 数据来源：请求参数；无业务数据。
 * 注意：
 *   1. **只在非 prod profile 存在**（`@Profile("!prod")`）：生产环境这个类根本不注册，
 *      路径不存在 → 404。故障注入端点上线就是后门，不允许带进生产。
 *   2. **为什么 500 模式要 `sendError` 而不是 throw**：`GlobalExceptionHandler` 会把所有
 *      RuntimeException 映射成「HTTP 200 + 业务码 501001」——这是本项目的既定契约，
 *      但代价是 `faze_http_requests_total{status="500"}` 永远为 0，
 *      `FazeHighErrorRate`（5xx 占比 > 5%）这条告警规则在本项目**永远无法触发**。
 *      `response.sendError(500)` 触发容器错误分派（转发到 `/error`），
 *      **绕过** @RestControllerAdvice，真的回 500 状态码。这不是改业务契约，
 *      而是给「错误率告警」提供一条可注入的 5xx 来源。
 *   3. slow 模式（默认 2000ms）用来触发 `FazeHighLatency`（P95 > 1000ms / 持续 5m）：
 *      HandlerInterceptor 的 duration 指标会把这次慢请求记进直方图。
 *   4. 参数做上限保护：delayMs clamp 到 [0, 10000]，防止误传 1 小时把线程池占满。
 *   5. 不写审计、不写业务日志：它不是业务操作，日志里只打一条 WARN 说明「有人注入了故障」。
 */
package com.faze.infra.probe;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故障注入端点（仅非生产）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dev")
@Profile("!prod")
public class FaultInjectionController {

    /** slow 模式的默认延迟（毫秒）。 */
    private static final long DEFAULT_DELAY_MS = 2000L;

    /** 延迟上限（毫秒）。 */
    private static final long MAX_DELAY_MS = 10_000L;

    /**
     * 注入故障。
     *
     * <p>副作用：500 模式直接回 HTTP 500；slow 模式阻塞当前请求线程 delayMs 毫秒。</p>
     *
     * @param mode    500 / slow（其他值按参数错误处理，仍回 200 + 说明）
     * @param delayMs slow 模式的延迟毫秒数（可空，默认 2000）
     * @param response 响应（500 模式用 sendError 绕过全局异常映射）
     * @return slow / 未知模式的说明 JSON；500 模式无返回体
     * @throws IOException sendError 写响应失败
     */
    @GetMapping("/fault")
    public Map<String, Object> fault(@RequestParam(required = false, defaultValue = "500") String mode,
                                     @RequestParam(required = false) Long delayMs,
                                     HttpServletResponse response) throws IOException {
        if ("500".equalsIgnoreCase(mode)) {
            log.warn("【故障注入】主动返回 HTTP 500（仅 dev；用于验证 FazeHighErrorRate 规则）");
            // sendError 触发容器错误分派，绕过 GlobalExceptionHandler 的「业务失败 200」映射
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "fault-injection");
            return null;
        }
        if ("slow".equalsIgnoreCase(mode)) {
            long delay = delayMs == null ? DEFAULT_DELAY_MS : delayMs;
            long safeDelay = Math.max(0L, Math.min(delay, MAX_DELAY_MS));
            log.warn("【故障注入】主动延迟 {}ms（仅 dev；用于验证 FazeHighLatency 规则）", safeDelay);
            try {
                Thread.sleep(safeDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "slow");
            body.put("delayMs", safeDelay);
            return body;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("hint", "mode 只支持 500 / slow");
        return body;
    }
}
