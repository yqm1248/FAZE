/*
 * 职责：统一响应出口的**业务错误率**打点——每次写出 `R<T>` 时记一次 `faze_biz_responses_total`，
 *       `code != 200` 再记 `faze_biz_errors_total{code,module}`（第 7 段工程债，负责人 2026-11-09 拍板 A）。
 * 调用方：Spring MVC 自动调用（@ControllerAdvice + ResponseBodyAdvice）。
 * 数据来源：响应体 `R.code` + MDC `auditAction` / 请求 URI（推导 module 标签）。
 * 注意：
 *   1. **为什么不能用 HTTP 5xx 统计错误率**：项目契约是「业务失败恒 HTTP 200 + 业务码」
 *      （《错误码规范》§2.2），`GlobalExceptionHandler` 把 RuntimeException 映射成
 *      `HTTP 200 + 501001`，所以 `faze_http_requests_total{status="500"}` 恒为 0，
 *      旧 `FazeHighErrorRate`（5xx 占比）永远不会 firing。**不能改成真返 5xx**——
 *      `R<T>` 是对外契约，改 5xx 会破坏所有前端的错误处理（负责人明确否决）。
 *   2. **挂在 ResponseBodyAdvice 而不是异常处理器**：业务失败有两个来源——抛
 *      `BusinessException`（走 GlobalExceptionHandler）与 Controller 里直接 `R.fail(...)`。
 *      只在异常处理器统计会漏掉后者；ResponseBodyAdvice 在**响应写回客户端之前**执行，
 *      两种来源都覆盖。
 *   3. **module 标签必须收敛，不能直接用 URI 段**：404 扫描会带任意路径，若把路径第 4 段
 *      直接当标签，攻击者可以往我们的时间序列里灌任意值（基数爆炸）。所以：
 *      ① 优先取 MDC `auditAction` 的 module 段（业务主动 mark，受控）；
 *      ② 否则取 URI 第 4 段，但**必须在白名单里**；
 *      ③ 再否则退到 scope（app / admin / pub）；
 *      ④ 都没有 → `unknown`。
 *   4. 只统计 `R` 响应：文件下载（`byte[]`）、actuator 端点、静态资源不经过这里，
 *      它们不是业务响应，计入会污染错误率分母。
 *   5. 本类**只读不写业务数据**，异常也只吞在内部（打点失败绝不能影响响应写出）。
 */
package com.faze.config;

import com.faze.common.R;
import com.faze.common.audit.AuditContext;
import com.faze.modules.ops.metric.FazeMetrics;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 业务错误率打点（统一响应出口）。
 */
@Slf4j
@ControllerAdvice
public class BizMetricsAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 允许作为 module 标签的路径段白名单（项目实际路由的第 4 段）。
     *
     * <p>**新增路由时如果希望错误率能按该域下钻，需要在这里加一项**；
     * 不加只会归到 scope（app/admin/pub），不会丢数据。白名单是基数保护，
     * 不是功能开关。</p>
     */
    private static final Set<String> MODULE_WHITELIST = Set.of(
            // 账户与鉴权
            "auth", "sms", "profile", "account", "realname", "pay-password", "enterprise",
            "lawyer-application", "lawyers", "upload", "users", "verification",
            // 交易
            "orders", "wallet", "recharge", "withdraw", "reviews", "workbench", "social",
            // 运营
            "complaints", "feedback", "faq", "notifications", "tickets", "sensitive-words",
            "operation-logs", "login-logs", "dashboard", "announcements", "notify-templates",
            "sys-configs", "exports", "config", "app", "ops", "dev",
            // 内容 / 会话 / 文书 / 案件 / AI
            "articles", "case-library", "search", "conversations", "documents", "signatures",
            "cases", "deliveries", "ai", "law-items",
            // 其他
            "ping", "probe");

    /** 允许作为 module 兜底的 scope 段。 */
    private static final Set<String> SCOPE_WHITELIST = Set.of("app", "admin", "pub");

    /** 无法判定时的 module 标签值。 */
    public static final String UNKNOWN_MODULE = "unknown";

    /** 指标收集器。 */
    private final FazeMetrics fazeMetrics;

    /**
     * 构造 advice。
     *
     * @param fazeMetrics 指标收集器
     */
    public BizMetricsAdvice(FazeMetrics fazeMetrics) {
        this.fazeMetrics = fazeMetrics;
    }

    /**
     * 是否处理该返回值：一律 true，在 {@link #beforeBodyWrite} 里按 `instanceof R` 过滤。
     *
     * <p>不用 `supports` 返回值类型判断，是因为异步导出等接口返回
     * `ResponseEntity<R<…>>`——按外层类型判断会漏掉这些业务响应。</p>
     *
     * @param returnType   返回值类型
     * @param converterType 消息转换器类型
     * @return 永远 true
     */
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * 响应写出前打点。
     *
     * @param body               响应体
     * @param returnType         返回值类型
     * @param selectedContentType 选中的 media type
     * @param selectedConverterType 选中的转换器
     * @param request            请求
     * @param response           响应
     * @return 原样返回 body（不修改响应）
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (!(body instanceof R<?> unified)) {
            return body;
        }
        try {
            fazeMetrics.recordBizResponse(unified.getCode(), resolveModule(
                    MDC.get(AuditContext.MDC_ACTION), uriOf(request)));
        } catch (RuntimeException e) {
            // 打点失败不能影响响应写出（指标是观测，不是业务）
            log.debug("业务响应指标打点失败", e);
        }
        return body;
    }

    /**
     * 推导 module 标签（公开给单测：可用固定输入验证收敛规则）。
     *
     * <p>顺序：MDC `auditAction` 的 module 段 → URI 第 4 段（白名单）→ URI 第 3 段 scope → unknown。</p>
     *
     * @param mdcAction MDC 里的 `auditAction`（形如 `account.SUBMIT_ENTERPRISE`，可空）
     * @param uri       请求 URI（可空）
     * @return 受控的 module 值（白名单内或 scope 或 `unknown`）
     */
    static String resolveModule(String mdcAction, String uri) {
        if (mdcAction != null && !mdcAction.isBlank()) {
            int dot = mdcAction.indexOf('.');
            String fromMdc = dot > 0 ? mdcAction.substring(0, dot) : mdcAction;
            if (MODULE_WHITELIST.contains(fromMdc)) {
                return fromMdc;
            }
        }
        String[] segments = splitUri(uri);
        // /api/v1/{scope}/{module}/... → segments[0]=="" [1]=api [2]=v1 [3]=scope [4]=module
        if (segments.length >= 5 && MODULE_WHITELIST.contains(segments[4])) {
            return segments[4];
        }
        if (segments.length >= 4 && SCOPE_WHITELIST.contains(segments[3])) {
            return segments[3];
        }
        return UNKNOWN_MODULE;
    }

    /**
     * 拆 URI 路径段（去掉 query / 空段处理留给调用方）。
     *
     * @param uri URI
     * @return 段数组；null / 空返回空数组
     */
    private static String[] splitUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return new String[0];
        }
        String path = uri;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return path.split("/", -1);
    }

    /**
     * 取请求 URI（拿不到返回 null）。
     *
     * @param request ServerHttpRequest
     * @return URI 字符串
     */
    private static String uriOf(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest raw = servletRequest.getServletRequest();
            return raw == null ? null : raw.getRequestURI();
        }
        return request == null ? null : request.getURI().getPath();
    }
}
