/*
 * 职责：服务端错误码的**唯一**来源。业务代码禁止出现裸数字错误码。
 * 调用方：common/R（构造失败响应）、common/GlobalExceptionHandler（按分支取码）、
 *         security/RestAuthenticationEntryPoint（401 两码）、各业务 Service 抛 BusinessException。
 * 数据来源：《法泽FAZE_错误码规范_v1.0.md》§三 的完整错误码表（段 0 只登记本段用到的子集，
 *           段 1 起按同一张表继续补，**不许自行发明新码，也不许改已冻结码的含义**）。
 * 注意：
 *   1. httpStatus 必须与《错误码规范》§2.2 一致：业务失败仍 HTTP 200，只有鉴权（401）与
 *      无权限（403）才不是 200。这两个必须与 code 同时正确，客户端是按「HTTP 状态 + body.code」
 *      两个维度分流的（§6.1）。
 *   2. msgKey 是给前端 i18n 的稳定键（《i18n 规范》三段式），msg 只是给开发者看的兜底中文，
 *      **客户端不得直接展示 msg**（接口规范 §5.1 约束 2）。
 *   3. 402002 与 402003 的区分是硬要求：前者「过期，可刷新」，后者「无效，直接登出」；
 *      混为一谈会让客户端进入无限刷新循环（《错误码规范》§4.3 第 3 条）。
 */
package com.faze.common.exception;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * 服务端错误码的唯一来源（段 0 子集）。
 */
@Getter
public enum ErrorCode {

    // ── 通用域 01 ──────────────────────────────────────────────────────────
    /** 参数校验失败（Bean Validation）。HTTP 200。 */
    COMMON_PARAM_INVALID(401001, 200, "error.common.param-invalid", "参数校验失败"),
    /** 缺少必填参数。HTTP 200。 */
    COMMON_PARAM_MISSING(401002, 200, "error.common.param-missing", "缺少必填参数"),
    /** 参数格式错误（手机号、日期等）。HTTP 200。 */
    COMMON_PARAM_FORMAT(401003, 200, "error.common.param-format", "参数格式错误"),
    /** 资源不存在。HTTP 200（路径不存在另走 404，见 GlobalExceptionHandler）。 */
    COMMON_NOT_FOUND(401004, 200, "error.common.not-found", "资源不存在"),
    /** 资源已存在。HTTP 200。 */
    COMMON_ALREADY_EXISTS(401005, 200, "error.common.already-exists", "资源已存在"),
    /** 状态非法，当前操作不允许。HTTP 200。 */
    COMMON_ILLEGAL_STATE(401006, 200, "error.common.illegal-state", "状态非法，当前操作不允许"),
    /** 请求过于频繁。HTTP 200。 */
    COMMON_RATE_LIMITED(401007, 200, "error.common.rate-limited", "请求过于频繁"),
    /** 无权限访问该资源。HTTP **403**。 */
    COMMON_FORBIDDEN(401008, 403, "error.common.forbidden", "无权限访问该资源"),
    /** 服务器内部错误（兜底）。HTTP 200。 */
    COMMON_INTERNAL(501001, 200, "error.common.internal", "服务器内部错误"),
    /** 第三方服务不可用。HTTP 200。 */
    COMMON_THIRD_PARTY_DOWN(501002, 200, "error.common.third-party-down", "第三方服务不可用"),
    /** 数据库操作失败。HTTP 200。 */
    COMMON_DB_ERROR(501003, 200, "error.common.db-error", "数据库操作失败"),
    /** 幂等键冲突（重复请求）。HTTP 200。 */
    COMMON_IDEMPOTENT_CONFLICT(501005, 200, "error.common.idempotent-conflict", "重复请求，请勿重复提交"),

    // ── 账户与鉴权域 02 ───────────────────────────────────────────────────
    /** 手机号或密码错误（**不区分是哪个错**，防账号枚举）。HTTP 200。 */
    AUTH_LOGIN_FAILED(402001, 200, "error.auth.login-failed", "手机号或密码错误"),
    /** 登录已过期，可刷新。HTTP **401**。 */
    AUTH_TOKEN_EXPIRED(402002, 401, "error.auth.token-expired", "登录已过期，请重新登录"),
    /** 凭证无效（签名错、已被拉黑），刷了也没用。HTTP **401**。 */
    AUTH_TOKEN_INVALID(402003, 401, "error.auth.token-invalid", "登录凭证无效，请重新登录"),
    /** 账号已被封禁。HTTP **403**。 */
    AUTH_ACCOUNT_BLOCKED(402004, 403, "error.auth.account-blocked", "账号已被封禁"),
    /** 短信验证码错误或已过期。HTTP 200。 */
    AUTH_CODE_INVALID(402005, 200, "error.auth.code-invalid", "验证码错误或已过期"),
    /** 手机号已注册。HTTP 200（**不泄露除「已注册」外的任何信息**）。 */
    AUTH_PHONE_TAKEN(402006, 200, "error.auth.phone-taken", "手机号已注册"),
    /** 账号已注销。HTTP 200。 */
    AUTH_ACCOUNT_CANCELLED(402008, 200, "error.auth.account-cancelled", "账号已注销"),
    /** 未成年人需监护人同意（未填监护人手机号）。HTTP 200。 */
    AUTH_GUARDIAN_REQUIRED(402009, 200, "error.auth.guardian-required", "未成年人需填写监护人手机号"),
    /** 密码强度不足。HTTP 200。 */
    AUTH_PASSWORD_WEAK(402010, 200, "error.auth.password-weak", "密码强度不足"),
    /** 短信发送失败（第三方通道）。HTTP 200。 */
    AUTH_SMS_SEND_FAILED(502001, 200, "error.auth.sms-send-failed", "短信发送失败"),

    // ── 交易与资金域 05 ──────────────────────────────────────────────────
    /** 余额不足。HTTP 200。 */
    TRADE_BALANCE_INSUFFICIENT(405001, 200, "error.trade.balance-insufficient", "账户余额不足"),
    /** 订单不存在（含「不是你的订单」——归属条件进 SQL，查不到即不存在）。HTTP 200。 */
    TRADE_ORDER_NOT_FOUND(405002, 200, "error.trade.order-not-found", "订单不存在"),
    /** 订单状态非法，当前操作不允许（非法状态迁移统一走它，HTTP 200 + 5xxxx）。HTTP 200。 */
    TRADE_ORDER_STATE_INVALID(405003, 200, "error.trade.order-state-invalid", "订单状态非法，当前操作不允许"),
    /** 重复提交（幂等拦截：同一 Idempotency-Key / 重复支付）。HTTP 200。 */
    TRADE_DUPLICATE_SUBMIT(405004, 200, "error.trade.duplicate-submit", "请勿重复提交"),
    /** 退款金额超出可退上限。HTTP 200。 */
    TRADE_REFUND_EXCEEDS(405005, 200, "error.trade.refund-exceeds", "退款金额超出可退上限"),
    /** 金额非法（负数、超限、非整数分）。HTTP 200。 */
    TRADE_AMOUNT_INVALID(405006, 200, "error.trade.amount-invalid", "金额非法"),
    /** 非本人订单（越权）。HTTP **403**。 */
    TRADE_NOT_ORDER_OWNER(405007, 403, "error.trade.not-order-owner", "非本人订单，无权访问"),
    /** 律师不可接单（不存在 / 未通过审核 / 已清退）。HTTP 200。 */
    TRADE_LAWYER_UNAVAILABLE(405008, 200, "error.trade.lawyer-unavailable", "该律师当前不可接单"),
    /** 该服务未上架或未定价（价格为空的律师不许下单成 0 元单）。HTTP 200。 */
    TRADE_SERVICE_UNAVAILABLE(405009, 200, "error.trade.service-unavailable", "该律师未开通此服务"),
    /** 律师不在线且平台要求在线才可下单（开关 `order_require_lawyer_online`）。HTTP 200。 */
    TRADE_LAWYER_OFFLINE(405010, 200, "error.trade.lawyer-offline", "该律师当前不在线"),

    /** 退款单不存在（G4.2 审批时按 id 查不到）。 */
    TRADE_REFUND_NOT_FOUND(405011, 200, "error.trade.refund-not-found", "退款单不存在"),
    /*
     * 评价两码用 405021 / 405022，**不复用 405012 / 405013**：《错误码规范》§三 的 405 段已把
     * 405008~405020 预留给段 5（提现 / 结算 / 充值 / 支付渠道）。段 2 施工已实际占用 405008~405011
     * （见《错误码规范》§三 的「段 2 施工登记」），本任务不再往那段里塞，避免与段 5 撞车。
     */
    /** 评价不存在（律师回复时按 id 查不到）。HTTP 200。 */
    TRADE_REVIEW_NOT_FOUND(405021, 200, "error.trade.review-not-found", "评价不存在"),
    /** 该订单已评价过（一单一次，uk_review_order）。HTTP 200。 */
    TRADE_REVIEW_DUPLICATE(405022, 200, "error.trade.review-duplicate", "该订单已评价，不能重复评价"),

    // ── IM 会话域 04 ──────────────────────────────────────────────────────
    /** 会话不存在。HTTP 200。 */
    CONVERSATION_NOT_FOUND(404001, 200, "error.conversation.not-found", "会话不存在"),
    /** 无权访问该会话（不是会话成员）。HTTP **403**。 */
    CONVERSATION_FORBIDDEN(404002, 403, "error.conversation.forbidden", "无权访问该会话"),

    // ── AI 域 08 ─────────────────────────────────────────────────────────
    /** 豆包服务不可用 / 5 秒内未出首字节。HTTP 200。 */
    AI_SERVICE_DOWN(508001, 200, "error.ai.service-down", "AI 服务暂不可用");

    /** 6 位业务码（前 3 位是 HTTP 语义家族，第 4~5 位是业务域）。 */
    private final int code;
    /** 该错误码应回的 HTTP 状态：业务失败恒为 200，鉴权 401，无权限 403。 */
    private final int httpStatus;
    /** 前端 i18n 的稳定键，客户端按它走 t()，**不得展示 msg**。 */
    private final String msgKey;
    /** 开发者可见的兜底中文文案。 */
    private final String msg;

    ErrorCode(int code, int httpStatus, String msgKey, String msg) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.msgKey = msgKey;
        this.msg = msg;
    }

    /** 业务码 → 枚举 的索引。接口层拿到的是数字，必须能按 code 反查（§12.1）。 */
    private static final Map<Integer, ErrorCode> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ErrorCode::getCode, Function.identity()));

    /**
     * 按 6 位业务码反查枚举。
     *
     * @param code 业务码
     * @return 对应枚举；未登记时返回 null（调用方需自行兜底，不要抛异常）
     */
    public static ErrorCode of(int code) {
        return BY_CODE.get(code);
    }
}
