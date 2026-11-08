/*
 * 职责：全局异常处理器。把 Spring MVC 的各类异常统一转成 R 响应，并保证「对外稳定文案 + 对内完整日志」双轨。
 * 调用方：Spring MVC 自动调用（@RestControllerAdvice），任何 Controller 抛出的异常都到这里。
 * 数据来源：异常对象本身；错误码一律取自 ErrorCode 枚举。
 * 注意：
 *   1. **任何分支都不得把 e.getMessage()、SQL、堆栈、类名、文件路径返回客户端**（后端规范 §5.3）。
 *      这是本类存在的根本理由：三个参考项目中有两个在此处泄露了 SQL 与堆栈。
 *   2. HTTP 语义按《错误码规范》§2.2：业务失败 200、未认证 401（不走本类，见
 *      security/RestAuthenticationEntryPoint）、无权限 403、路径不存在 404。
 *   3. 参数类异常要把**全部**字段错误聚合成一条 msg，只报第一个会让前端反复试错。
 *   4. 业务异常（BusinessException）是预期分支：log.warn + 不打印堆栈；其余未知异常
 *      log.error + 打印全栈。混用会让日志要么淹没在堆栈里，要么查不到故障。
 *   5. **multipart 类异常必须单列**（2026-11-07 补）：`MaxUploadSizeExceededException` 是
 *      RuntimeException，落到兜底会回 501001「服务器内部错误」，把「文件太大」误报成「后端坏了」。
 *      见 handleMaxUploadSize / handleMultipart 两条分支。
 */
package com.faze.common.exception;

import com.faze.common.R;
import com.faze.common.support.ServicePhoneProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器：对外稳定文案，对内完整日志。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 客服电话提供者（402004 封禁提示要带申诉入口，号码不许写死）。 */
    private final ServicePhoneProvider servicePhoneProvider;

    /**
     * 构造处理器。
     *
     * @param servicePhoneProvider 客服电话提供者
     */
    public GlobalExceptionHandler(ServicePhoneProvider servicePhoneProvider) {
        this.servicePhoneProvider = servicePhoneProvider;
    }

    // ── 1. 业务异常 ─────────────────────────────────────────────────────────

    /**
     * 拦截可预期的业务异常。
     *
     * <p>这是唯一「按错误码自带 HTTP 状态返回」的分支：402002/402003 回 401，
     * 402004/401008 回 403，其余回 200。</p>
     *
     * <p>不打印堆栈：业务异常是预期内分支（如密码错、余额不足），打全栈只会淹没真正的故障。</p>
     *
     * @param e   业务异常
     * @param req 当前请求（取 URI 用于日志）
     * @return 对应错误码的响应体与 HTTP 状态
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<R<Void>> handleBusiness(BusinessException e, HttpServletRequest req) {
        ErrorCode code = e.getErrorCode();
        log.warn("业务异常 path={} code={} msg={} detail={}",
                req.getRequestURI(), code.getCode(), code.getMsg(), e.getDetail());
        return ResponseEntity.status(code.getHttpStatus()).body(R.<Void>fail(code, messageOf(code)));
    }

    /**
     * 取返回给客户端的 msg。
     *
     * <p><b>只有 402004（账号被封禁）会改写文案</b>：`7-A4` 锚点要求封禁提示「明确，
     * 含申诉入口或客服电话」，而错误码自带的固定文案只有「账号已被封禁」。
     * 客服电话**从 sys_config.service.phone 读**，不许写死在 i18n 资源或代码里
     * （写死改号就要改代码，且违反「客服电话不写死」的既有约定）。</p>
     *
     * <p>为什么把这段放在异常处理器而不是抛异常处：402004 由多个入口抛（密码登录、
     * 短信登录、刷新令牌），每个地方各拼一遍必然漂移；错误码 → 文案的映射只该有一处。</p>
     *
     * <p>其他错误码一律返回错误码自带的固定文案：动态内容（额度、剩余次数）走响应头，
     * 不进 msg（否则前端按 msgKey 翻译会失效）。</p>
     *
     * @param code 错误码
     * @return 最终 msg
     */
    private String messageOf(ErrorCode code) {
        if (code != ErrorCode.AUTH_ACCOUNT_BLOCKED) {
            return code.getMsg();
        }
        String phone = servicePhoneProvider.getServicePhone();
        if (phone == null || phone.isBlank()) {
            return code.getMsg() + "，如需申诉请通过 APP 客服中心提交";
        }
        return code.getMsg() + "，如需申诉请联系客服 " + phone + " 或通过 APP 客服中心提交";
    }

    /**
     * 拦截「支付密码仍处于锁定期」。
     *
     * <p>**剩余秒数走 `Retry-After` 头**（HTTP 标准），不进 body：body 的 msg 是
     * ErrorCode 的固定文案，把动态数字塞进 msg 会让文案、翻译、断言全部失效
     * （负责人 2026-11-05 拍板方案 A）。</p>
     *
     * @param e   锁定异常
     * @param req 当前请求
     * @return 401007 + Retry-After 头
     */
    @ExceptionHandler(PayPasswordLockedException.class)
    public ResponseEntity<R<Void>> handlePayPasswordLocked(PayPasswordLockedException e,
                                                           HttpServletRequest req) {
        ErrorCode code = e.getErrorCode();
        log.warn("支付密码锁定 path={} code={} remaining={}s",
                req.getRequestURI(), code.getCode(), e.getRemainingSeconds());
        return ResponseEntity.status(code.getHttpStatus())
                .header("Retry-After", String.valueOf(e.getRemainingSeconds()))
                .body(R.<Void>fail(code));
    }

    /**
     * 拦截「支付密码校验失败」。
     *
     * <p>剩余尝试次数走 `X-Pay-Pwd-Remaining` 头，理由同上：动态数据走 header，
     * 固定文案走 msg。客户端据此提示「还可尝试 N 次」。</p>
     *
     * @param e   支付密码错误异常
     * @param req 当前请求
     * @return 402001 + X-Pay-Pwd-Remaining 头
     */
    @ExceptionHandler(PayPasswordWrongException.class)
    public ResponseEntity<R<Void>> handlePayPasswordWrong(PayPasswordWrongException e,
                                                          HttpServletRequest req) {
        ErrorCode code = e.getErrorCode();
        log.warn("支付密码错误 path={} remaining={}", req.getRequestURI(), e.getRemainingAttempts());
        return ResponseEntity.status(code.getHttpStatus())
                .header("X-Pay-Pwd-Remaining", String.valueOf(e.getRemainingAttempts()))
                .body(R.<Void>fail(code));
    }

    /**
     * 拦截「限流」异常（HTTP 429 + `Retry-After` 头）。
     *
     * <p>**只有 `httpStatus=429` 的错误码会走到这里**（目前只有
     * `TRADE_RECHARGE_RATE_LIMITED(405030)`）。`COMMON_RATE_LIMITED(401007)` 等
     * 「业务限流 200 + 错误码」口径仍走 {@link #handleBusiness}，两条路径不能混。</p>
     *
     * @param e   限流异常
     * @param req 当前请求
     * @return 429 + `Retry-After` 头
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<R<Void>> handleRateLimited(RateLimitedException e, HttpServletRequest req) {
        ErrorCode code = e.getErrorCode();
        log.warn("触发限流 path={} code={} retryAfter={}s",
                req.getRequestURI(), code.getCode(), e.getRetryAfterSeconds());
        return ResponseEntity.status(code.getHttpStatus())
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(R.<Void>fail(code));
    }

    // ── 2~4. 参数类异常（三种来源）──────────────────────────────────────────

    /**
     * 拦截 `@RequestBody` 的参数校验失败（Bean Validation）。
     *
     * <p>把所有字段错误聚合成一条 msg（`字段: 提示; 字段: 提示`），而不是只报第一个——
     * 前端一次就能看到全部问题。</p>
     *
     * @param e   MethodArgumentNotValidException
     * @param req 当前请求
     * @return HTTP 200 + 401001，msg 为聚合后的字段提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                               HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 path={} {}", req.getRequestURI(), msg);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID, msg));
    }

    /**
     * 拦截表单/查询参数的绑定失败（`BindException`）。
     *
     * <p>与 `MethodArgumentNotValidException` 分开覆盖：后者是它的子类，
     * Spring 会挑更具体的处理器，但若不显式声明本分支，非 body 的校验失败会落到兜底分支，
     * 客户端就拿到 501001 而不是 401001。</p>
     *
     * @param e   BindException
     * @param req 当前请求
     * @return HTTP 200 + 401001
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e, HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败 path={} {}", req.getRequestURI(), msg);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID, msg));
    }

    /**
     * 拦截方法级校验失败（`@Validated` + `@RequestParam` 上的约束）。
     *
     * @param e   ConstraintViolationException
     * @param req 当前请求
     * @return HTTP 200 + 401001
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException e,
                                                             HttpServletRequest req) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败 path={} {}", req.getRequestURI(), msg);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID, msg));
    }

    // ── 5~7. 路由 / 路径参数 / 类型不匹配 ───────────────────────────────────

    /**
     * 拦截 HTTP 方法不支持（如用 GET 调 POST 接口）。
     *
     * <p>《错误码规范》未为 405 单独定义业务码，此处按「参数校验失败」归类并保留 HTTP 405；
     * 若后续规范补码，只改这一行即可。</p>
     *
     * @param e   HttpRequestMethodNotSupportedException
     * @param req 当前请求
     * @return HTTP 405 + 401001
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                             HttpServletRequest req) {
        log.warn("请求方法不支持 path={} method={}", req.getRequestURI(), req.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID));
    }

    /**
     * 拦截路径变量缺失（`@PathVariable` 声明了但路由模板里没有）。
     *
     * <p>这是**接口自身写错**，不是调用方的问题；日志打 warn 便于开发期立刻发现，
     * 对外仍按「缺少必填参数」处理，避免把内部路由细节暴露出去。</p>
     *
     * @param e   MissingPathVariableException
     * @param req 当前请求
     * @return HTTP 200 + 401002
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<R<Void>> handleMissingPathVariable(MissingPathVariableException e,
                                                             HttpServletRequest req) {
        log.warn("路径变量缺失 path={} var={}", req.getRequestURI(), e.getVariableName());
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_MISSING));
    }

    /**
     * 拦截参数类型不匹配（如 `?id=abc` 传给 Long）。
     *
     * @param e   MethodArgumentTypeMismatchException
     * @param req 当前请求
     * @return HTTP 200 + 401003，msg 只带参数名（不含原始值与类型细节）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                      HttpServletRequest req) {
        log.warn("参数类型不匹配 path={} name={}", req.getRequestURI(), e.getName());
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_FORMAT, e.getName() + ": 格式不正确"));
    }

    /**
     * 拦截「没有匹配的处理器」（路径不存在）。
     *
     * <p>触发前提是 `application.yml` 里开了
     * `spring.mvc.throw-exception-if-no-handler-found=true` 且关掉静态资源默认映射，
     * 否则 404 会被 `ResourceHttpRequestHandler` 吞掉、根本进不到这里。
     * 段 0 靠它实现「生产 profile 下探针返回 404 且仍是 JSON 体」（验收第 9 条）。</p>
     *
     * @param e   NoHandlerFoundException
     * @param req 当前请求
     * @return HTTP 404 + 401004
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<R<Void>> handleNoHandlerFound(NoHandlerFoundException e, HttpServletRequest req) {
        log.warn("路径不存在 path={} method={}", req.getRequestURI(), e.getHttpMethod());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.<Void>fail(ErrorCode.COMMON_NOT_FOUND));
    }

    // ── 8~9. 参数非法 / 唯一键冲突 ─────────────────────────────────────────

    /**
     * 拦截 `IllegalArgumentException`。
     *
     * <p>它多数来自「枚举取值失败」「必填参数被传了 null」这类**调用方错误**，
     * 也可能是本项目自己的 bug；统一按参数校验失败返回，日志打全栈以便区分。</p>
     *
     * @param e   IllegalArgumentException
     * @param req 当前请求
     * @return HTTP 200 + 401001
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<R<Void>> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        log.warn("非法参数 path={}", req.getRequestURI(), e);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID));
    }

    /**
     * 拦截唯一键冲突。
     *
     * <p>**绝不把数据库返回的约束名、列名回给客户端**——那等于告诉攻击者表结构。
     * 对外统一「资源已存在」，对内保留完整堆栈（含 SQL）供排查。</p>
     *
     * @param e   DuplicateKeyException
     * @param req 当前请求
     * @return HTTP 200 + 401005
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<R<Void>> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest req) {
        log.warn("唯一键冲突 path={}", req.getRequestURI(), e);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_ALREADY_EXISTS));
    }

    /**
     * 拦截缺少必填查询参数（如 `/probe/law` 不带 `kw`）。
     *
     * <p>《后端规范》§5.1 的 13 类里没有它，但缺参数是**客户端错误**：落到兜底分支会回
     * 501001「服务器内部错误」，客户端会误以为服务端故障而重试。按《错误码规范》
     * 401002「缺少必填参数」处理语义才正确。这是对 13 类的**补充**，不是替代。</p>
     *
     * @param e   MissingServletRequestParameterException
     * @param req 当前请求
     * @return HTTP 200 + 401002，msg 只带参数名
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParameter(MissingServletRequestParameterException e,
                                                          HttpServletRequest req) {
        log.warn("缺少必填参数 path={} param={}", req.getRequestURI(), e.getParameterName());
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_MISSING, e.getParameterName() + ": 不能为空"));
    }

    /**
     * 拦截「上传文件超过 multipart 上限」。
     *
     * <p>**为什么要单列**（2026-11-07 实测缺陷修复，lead 验收发现）：超限时 Spring 抛
     * {@code MaxUploadSizeExceededException}（RuntimeException），原先落到
     * {@link #handleRuntime} 兜底分支回 `501001 服务器内部错误`——用户/运维会以为后端坏了，
     * 实际只是文件太大。A-27 的「批量导入」支持 multipart 上传（工作流 H1 第 3 条，上限 5MB），
     * 超限属于**客户端参数问题**，必须回可读的 401001，而不是 501001。
     * msg 里的 5MB 与 `application.yml` 的 `spring.servlet.multipart.max-file-size` 对应，
     * 改上限时要同步改这里（当前 multipart max-file-size=5MB / max-request-size=6MB）。</p>
     *
     * @param e   上传超限异常
     * @param req 当前请求
     * @return HTTP 200 + 401001，msg 说明文件过大
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e,
                                                       HttpServletRequest req) {
        log.warn("上传文件超过上限 path={}", req.getRequestURI());
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID, "上传文件超过 5MB 上限"));
    }

    /**
     * 拦截其它 multipart 解析失败（兜底防「容器层异常被当成 501001」）。
     *
     * <p>`MaxUploadSizeExceededException` 是 {@code MultipartException} 的子类，会被上面的分支优先命中；
     * 这里处理的是「multipart 结构损坏 / 临时目录不可写」等同类客户端请求错误。
     * 不吞成 501001 的原因同上：客户端能按 401001 自行修正请求。</p>
     *
     * @param e   multipart 解析异常
     * @param req 当前请求
     * @return HTTP 200 + 401001
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<R<Void>> handleMultipart(MultipartException e, HttpServletRequest req) {
        log.warn("multipart 请求解析失败 path={} error={}", req.getRequestURI(), e.getClass().getSimpleName());
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_PARAM_INVALID, "上传文件解析失败，请检查文件大小与格式"));
    }

    /**
     * 拦截「客户端已断开」（SSE / 长轮询被用户切走时容器主动回调）。
     *
     * <p>这不是故障：连接已经没了，连错误响应都写不出去。按 §10.1 分级记 DEBUG，
     * 否则生产日志里会全是「用户切走」的 ERROR，真正的故障被淹没。</p>
     *
     * <p>返回 void 表示本处理器已完全处理、不再写响应体。</p>
     *
     * @param e   客户端断开异常
     * @param req 当前请求
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(AsyncRequestNotUsableException e, HttpServletRequest req) {
        log.debug("客户端已断开 path={}", req.getRequestURI());
    }

    // ── 10. 无权限 ─────────────────────────────────────────────────────────
    /**
     * 拦截 Spring Security 的授权失败（`@PreAuthorize` / 方法级安全）。
     *
     * <p>注意与「未认证」区分：未认证（没 token / token 坏）由
     * `RestAuthenticationEntryPoint` 回 401；本分支是「有身份但没权限」，回 403。</p>
     *
     * @param e    AccessDeniedException
     * @param req  当前请求
     * @return HTTP 403 + 401008
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest req) {
        log.warn("无权限 path={} msg={}", req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.<Void>fail(ErrorCode.COMMON_FORBIDDEN));
    }

    // ── 11~13. 未预期异常（兜底两分支）─────────────────────────────────────

    /**
     * 拦截未预期的 `RuntimeException`。
     *
     * <p>此处单列而不与 `Exception` 合并，是为了让日志能区分「运行时故障」与「受检异常遗漏」，
     * 两者的排查入口不同。</p>
     *
     * @param e   RuntimeException
     * @param req 当前请求
     * @return HTTP 200 + 501001（稳定文案，不含任何内部信息）
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<R<Void>> handleRuntime(RuntimeException e, HttpServletRequest req) {
        log.error("运行时异常 path={}", req.getRequestURI(), e);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_INTERNAL));
    }

    /**
     * 兜底：拦截所有未被上面分支命中的异常。
     *
     * <p><b>为什么这里只返回稳定中文、不回显异常消息</b>：异常消息可能包含 SQL 语句、表名、
     * 类名或文件路径，直接回显会向客户端暴露内部实现（后端规范 §5.3 两个参考项目都栽在这）。
     * 细节仍完整写入服务端日志，按 traceId 可精确定位。</p>
     *
     * <p><b>为什么这条「为什么」必须写进注释</b>：后来者会觉得「把 e.getMessage() 返回对排查更方便」
     * 而改回去——注释写清不能改的原因，比写「做什么」有用得多。</p>
     *
     * @param e   Exception
     * @param req 当前请求
     * @return HTTP 200 + 501001
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("未捕获异常 path={}", req.getRequestURI(), e);
        return ResponseEntity.ok(R.<Void>fail(ErrorCode.COMMON_INTERNAL));
    }
}
